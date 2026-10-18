package com.fitnesseai.plugin;

import com.sun.net.httpserver.HttpServer;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVerifyResponderTest {

  private FitNesseContext context;
  private HttpServer llmServer;
  private int lastStatus;

  @AfterEach
  void tearDown() throws IOException {
    if (llmServer != null) {
      llmServer.stop(0);
    }
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private String startMockLlm(String correctedContent) throws IOException {
    llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String content = new JSONObject()
      .put("corrected", correctedContent)
      .put("comments", new JSONArray().put(new JSONObject()
        .put("line", 2).put("explanation", "majuscule")))
      .toString();
    String llmJson = new JSONObject()
      .put("choices", new JSONArray().put(new JSONObject()
        .put("message", new JSONObject()
          .put("content", content))))
      .toString();
    llmServer.createContext("/v1/chat/completions", exchange -> {
      byte[] bytes = llmJson.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    llmServer.start();
    return "http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1";
  }

  private FitNesseContext contextWithLlm(String baseUrl) {
    Properties props = new Properties();
    props.setProperty("llm.base.url", baseUrl);
    props.setProperty("llm.model", "test-model");
    return FitNesseUtil.makeTestContext(props);
  }

  private static MockRequest verifyRequest(String content) {
    MockRequest request = new MockRequest("SomePage");
    request.setRequestLine("POST /SomePage HTTP/1.1");
    String payload = new JSONObject().put("pageName", "SomePage").put("content", content).toString();
    request.setBody("responder=ai-verify&payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8));
    request.addInput("responder", "ai-verify");
    return request;
  }

  private JSONObject dispatch(MockRequest request) throws Exception {
    Responder responder = context.responderFactory.makeResponder(request);
    Response response = responder.makeResponse(context, request);
    assertTrue(response instanceof SimpleResponse, "expected SimpleResponse");
    SimpleResponse simple = (SimpleResponse) response;
    lastStatus = simple.getStatus();
    return new JSONObject(new String(simple.getContentBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void returnsHunksForCorrectedContent() throws Exception {
    context = contextWithLlm(startMockLlm("hello\nWorld\n"));
    MockRequest request = verifyRequest("hello\nworld\n");

    JSONObject body = dispatch(request);

    assertEquals(200, lastStatus);
    JSONArray hunks = body.getJSONArray("hunks");
    assertEquals(1, hunks.length());
    JSONObject hunk = hunks.getJSONObject(0);
    assertEquals("modified", hunk.getString("type"));
    assertEquals(2, hunk.getInt("startLine"));
    assertEquals("world", hunk.getJSONArray("originalLines").getString(0));
    assertEquals("World", hunk.getJSONArray("correctedLines").getString(0));
    assertEquals("majuscule", hunk.getString("comment"));
    assertEquals(1, body.getJSONObject("stats").getInt("added"));
    assertEquals(1, body.getJSONObject("stats").getInt("removed"));
  }

  @Test
  void getReturns405() throws Exception {
    context = contextWithLlm(startMockLlm("x"));
    MockRequest request = verifyRequest("x");
    request.setRequestLine("GET /SomePage?responder=ai-verify HTTP/1.1");

    dispatch(request);

    assertEquals(405, lastStatus);
  }

  @Test
  void missingConfigReturns400ListingProperties() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());

    JSONObject body = dispatch(verifyRequest("content"));

    assertEquals(400, lastStatus);
    assertTrue(body.getString("error").contains("llm.base.url"));
    JSONArray missing = body.getJSONArray("missing");
    assertEquals("llm.base.url", missing.getString(0));
    assertEquals("llm.model", missing.getString(1));
  }

  @Test
  void invalidBodyReturns400() throws Exception {
    context = contextWithLlm(startMockLlm("x"));
    MockRequest request = new MockRequest("SomePage");
    request.setRequestLine("POST /SomePage HTTP/1.1");
    request.setBody("responder=ai-verify&payload=not-json");
    request.addInput("responder", "ai-verify");

    dispatch(request);

    assertEquals(400, lastStatus);
  }

  @Test
  void llmFailureReturns502WithErrorAndDetails() throws Exception {
    llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    llmServer.createContext("/v1/chat/completions", exchange -> {
      byte[] bytes = "provider exploded".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    llmServer.start();
    context = contextWithLlm("http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1");

    JSONObject body = dispatch(verifyRequest("content"));

    assertEquals(502, lastStatus);
    assertTrue(body.getString("error").contains("HTTP 500"));
    assertEquals("provider exploded", body.getString("details"));
  }

  @Test
  void noDifferencesReturnsEmptyHunks() throws Exception {
    context = contextWithLlm(startMockLlm("same\ncontent\n"));

    JSONObject body = dispatch(verifyRequest("same\ncontent\n"));

    assertEquals(200, lastStatus);
    assertEquals(0, body.getJSONArray("hunks").length());
  }
}
