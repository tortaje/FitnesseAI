package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureReadOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.http.Request;
import fitnesse.http.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class AiPromptResponder implements SecureResponder {

  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    if (ResponderSupport.isGet(request)) {
      return ResponderSupport.jsonResponse(405, new JSONObject().put("error", "Use POST to generate page content."));
    }

    JSONObject payload = ResponderSupport.parsePayload(request.getBody());
    if (payload == null) {
      return ResponderSupport.jsonResponse(400, new JSONObject().put("error",
        "Request body must be form-encoded: responder=ai-prompt&payload=<urlencoded JSON "
          + "{pageName, pageType, pageContent, messages}>"));
    }
    if (!payload.has("pageContent") || !(payload.get("pageContent") instanceof String)) {
      return ResponderSupport.jsonResponse(400,
        new JSONObject().put("error", "Missing required field: pageContent"));
    }
    if (!payload.has("messages") || !(payload.get("messages") instanceof JSONArray messages)
        || messages.length() == 0) {
      return ResponderSupport.jsonResponse(400,
        new JSONObject().put("error", "Missing required field: messages"));
    }

    AiVerifyConfig config = new AiVerifyConfig(context.getProperties());
    if (!config.missingRequired().isEmpty()) {
      return ResponderSupport.jsonResponse(400, new JSONObject()
        .put("error", "AI prompt is not configured. Add these properties to plugins.properties "
          + "at the FitNesse root: " + String.join(", ", config.missingRequired()))
        .put("missing", new JSONArray(config.missingRequired())));
    }

    return ResponderSupport.jsonResponse(501,
      new JSONObject().put("error", "Not implemented yet"));
  }

  static JSONArray buildMessages(String systemPrompt, String pageName, String pageType,
                                 String pageContent, JSONArray transcript) {
    String system = systemPrompt
      + "\n\nPage name: " + pageName
      + "\nPage type: " + pageType;
    JSONArray messages = new JSONArray()
      .put(new JSONObject().put("role", "system").put("content", system))
      .put(new JSONObject().put("role", "user").put("content",
        "Current page content (possibly hand-edited):\n" + pageContent));
    for (int i = 0; i < transcript.length(); i++) {
      messages.put(transcript.get(i));
    }
    return messages;
  }

  @Override
  public SecureOperation getSecureOperation() {
    return new SecureReadOperation();
  }
}
