package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureReadOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.http.Request;
import fitnesse.http.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.logging.Level;
import java.util.logging.Logger;

public class AiVerifyResponder implements SecureResponder {
  private static final Logger LOG = Logger.getLogger(AiVerifyResponder.class.getName());

  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    if (ResponderSupport.isGet(request)) {
      return ResponderSupport.jsonResponse(405, new JSONObject().put("error", "Use POST to verify page content."));
    }

    JSONObject payload = ResponderSupport.parsePayload(request.getBody());
    if (payload == null) {
      return ResponderSupport.jsonResponse(400, new JSONObject()
        .put("error", "Request body must be form-encoded: responder=ai-verify&payload=<urlencoded JSON {pageName, content}>"));
    }
    if (!payload.has("content") || !(payload.get("content") instanceof String content)) {
      return ResponderSupport.jsonResponse(400, new JSONObject().put("error", "Missing required field: content"));
    }

    AiVerifyConfig config = new AiVerifyConfig(context.getProperties());
    if (!config.missingRequired().isEmpty()) {
      return ResponderSupport.jsonResponse(400, new JSONObject()
        .put("error", "AI verify is not configured. Add these properties to plugins.properties at the FitNesse root: "
          + String.join(", ", config.missingRequired()))
        .put("missing", new JSONArray(config.missingRequired())));
    }

    LlmClient.Result llmResult;
    Level verbose = config.debug() ? Level.INFO : Level.FINE;
    LOG.log(verbose, () -> "[ai-verify] verify requested contentLength=" + content.length());
    try {
      llmResult = new LlmClient().chat(config, content);
    } catch (LlmException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.llmError(LOG, "[ai-verify]", e));
    } catch (RuntimeException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.runtimeError(LOG, "[ai-verify]", e));
    }

    DiffService.Result diff = new DiffService().compute(content, llmResult.corrected(), llmResult.comments(), config.debug());
    LOG.log(verbose, () -> "[ai-verify] diff hunks=" + diff.hunks().size()
      + " added=" + diff.stats().added() + " removed=" + diff.stats().removed());

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
    return ResponderSupport.jsonResponse(200, new JSONObject()
      .put("hunks", hunks)
      .put("stats", new JSONObject()
        .put("added", diff.stats().added())
        .put("removed", diff.stats().removed())));
  }

  @Override
  public SecureOperation getSecureOperation() {
    return new SecureReadOperation();
  }
}
