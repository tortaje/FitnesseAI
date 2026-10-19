package com.fitnesseai.plugin;

import fitnesse.http.Request;
import fitnesse.http.SimpleResponse;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ResponderSupport {

  private ResponderSupport() {
  }

  static boolean isGet(Request request) {
    String requestLine = request.getRequestLine();
    return requestLine != null && requestLine.startsWith("GET ");
  }

  static JSONObject parsePayload(String body) {
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

  static JSONObject llmError(Logger log, String tag, LlmException e) {
    log.warning(tag + " LLM error: " + e.getMessage());
    JSONObject error = new JSONObject().put("error", e.getMessage());
    if (e.rawBody() != null) {
      error.put("details", e.rawBody());
    }
    return error;
  }

  static JSONObject runtimeError(Logger log, String tag, RuntimeException e) {
    log.log(Level.WARNING, tag + " unexpected error", e);
    return new JSONObject().put("error", e.getMessage());
  }
}
