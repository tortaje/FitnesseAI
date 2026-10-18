package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureReadOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class AiVerifyResponder implements SecureResponder {

  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    if (isGet(request)) {
      return jsonResponse(405, new JSONObject().put("error", "Use POST to verify page content."));
    }

    JSONObject payload = parsePayload(request.getBody());
    if (payload == null) {
      return jsonResponse(400, new JSONObject()
        .put("error", "Request body must be form-encoded: responder=ai-verify&payload=<urlencoded JSON {pageName, content}>"));
    }
    if (!payload.has("content") || !(payload.get("content") instanceof String content)) {
      return jsonResponse(400, new JSONObject().put("error", "Missing required field: content"));
    }

    AiVerifyConfig config = new AiVerifyConfig(context.getProperties());
    if (!config.missingRequired().isEmpty()) {
      return jsonResponse(400, new JSONObject()
        .put("error", "AI verify is not configured. Add these properties to plugins.properties at the FitNesse root: "
          + String.join(", ", config.missingRequired()))
        .put("missing", new JSONArray(config.missingRequired())));
    }

    LlmClient.Result llmResult;
    try {
      llmResult = new LlmClient().chat(config, content);
    } catch (LlmException e) {
      JSONObject error = new JSONObject().put("error", e.getMessage());
      if (e.rawBody() != null) {
        error.put("details", e.rawBody());
      }
      return jsonResponse(502, error);
    } catch (RuntimeException e) {
      return jsonResponse(502, new JSONObject().put("error", e.getMessage()));
    }

    DiffService.Result diff = new DiffService().compute(content, llmResult.corrected(), llmResult.comments());

    JSONArray hunks = new JSONArray();
    for (DiffService.Hunk hunk : diff.hunks()) {
      JSONObject json = new JSONObject()
        .put("id", hunk.id())
        .put("type", hunk.type())
        .put("startLine", hunk.startLine())
        .put("originalLines", new JSONArray(hunk.originalLines()))
        .put("correctedLines", new JSONArray(hunk.correctedLines()));
      if (hunk.comment() != null) {
        json.put("comment", hunk.comment());
      }
      hunks.put(json);
    }
    return jsonResponse(200, new JSONObject()
      .put("hunks", hunks)
      .put("stats", new JSONObject()
        .put("added", diff.stats().added())
        .put("removed", diff.stats().removed())));
  }

  private static boolean isGet(Request request) {
    String requestLine = request.getRequestLine();
    return requestLine != null && requestLine.startsWith("GET ");
  }

  private static JSONObject parsePayload(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && "payload".equals(pair.substring(0, eq))) {
        try {
          return new JSONObject(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
          return null;
        }
      }
    }
    return null;
  }

  static SimpleResponse jsonResponse(int status, JSONObject body) {
    SimpleResponse response = new SimpleResponse();
    response.setStatus(status);
    response.setContentType("application/json; charset=utf-8");
    try {
      response.setContent(body.toString());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
    return response;
  }

  @Override
  public SecureOperation getSecureOperation() {
    return new SecureReadOperation();
  }
}
