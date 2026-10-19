package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.testutil.FitNesseUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptResponderTest {

  private FitNesseContext context;

  @AfterEach
  void tearDown() throws IOException {
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private static MockRequest promptRequest(JSONObject payload) {
    MockRequest request = new MockRequest("SomePage");
    request.setRequestLine("POST /SomePage HTTP/1.1");
    request.setBody("responder=ai-prompt&payload="
      + URLEncoder.encode(payload.toString(), StandardCharsets.UTF_8));
    request.addInput("responder", "ai-prompt");
    return request;
  }

  private static JSONObject validPayload() {
    return new JSONObject()
      .put("pageName", "SomePage")
      .put("pageType", "")
      .put("pageContent", "!1 draft")
      .put("messages", new JSONArray().put(new JSONObject()
        .put("role", "user").put("content", "make a test")));
  }

  private JSONObject dispatch(MockRequest request) throws Exception {
    Responder responder = context.responderFactory.makeResponder(request);
    Response response = responder.makeResponse(context, request);
    assertTrue(response instanceof SimpleResponse, "expected SimpleResponse");
    SimpleResponse simple = (SimpleResponse) response;
    lastStatus = simple.getStatus();
    return new JSONObject(new String(simple.getContentBytes(), StandardCharsets.UTF_8));
  }

  private int lastStatus;

  @Test
  void pluginFactoryRegistersAiPromptResponder() {
    context = FitNesseUtil.makeTestContext(new Properties());

    assertEquals(AiPromptResponder.class, context.responderFactory.getResponderClass("ai-prompt"));
  }

  @Test
  void getReturns405() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    MockRequest request = promptRequest(validPayload());
    request.setRequestLine("GET /SomePage?responder=ai-prompt HTTP/1.1");

    dispatch(request);

    assertEquals(405, lastStatus);
  }

  @Test
  void invalidBodyReturns400() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    MockRequest request = new MockRequest("SomePage");
    request.setRequestLine("POST /SomePage HTTP/1.1");
    request.setBody("responder=ai-prompt&payload=not-json");
    request.addInput("responder", "ai-prompt");

    JSONObject body = dispatch(request);

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("form-encoded"));
  }

  @Test
  void missingPageContentReturns400() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    JSONObject payload = validPayload();
    payload.remove("pageContent");

    JSONObject body = dispatch(promptRequest(payload));

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("pageContent"));
  }

  @Test
  void missingMessagesReturns400() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    JSONObject payload = validPayload();
    payload.remove("messages");

    JSONObject body = dispatch(promptRequest(payload));

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("messages"));
  }

  @Test
  void emptyMessagesReturns400() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    JSONObject payload = validPayload().put("messages", new JSONArray());

    JSONObject body = dispatch(promptRequest(payload));

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("messages"));
  }

  @Test
  void missingConfigReturns400ListingProperties() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());

    JSONObject body = dispatch(promptRequest(validPayload()));

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("llm.base.url"));
    JSONArray missing = body.getJSONArray("missing");
    assertEquals("llm.base.url", missing.getString(0));
    assertEquals("llm.model", missing.getString(1));
  }
}
