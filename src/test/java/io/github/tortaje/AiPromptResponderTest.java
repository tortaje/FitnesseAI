package io.github.tortaje;

import com.sun.net.httpserver.HttpServer;
import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.testutil.FitNesseUtil;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPageUtil;
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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptResponderTest {

  private FitNesseContext context;
  private HttpServer llmServer;
  private String lastRequestBody;

  @AfterEach
  void tearDown() throws IOException {
    if (llmServer != null) {
      llmServer.stop(0);
      llmServer = null;
    }
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private String startMockLlm(String generatedContent) throws IOException {
    llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String llmJson = new JSONObject()
      .put("choices", new JSONArray().put(new JSONObject()
        .put("message", new JSONObject().put("content", generatedContent))))
      .toString();
    llmServer.createContext("/v1/chat/completions", exchange -> {
      lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      byte[] bytes = llmJson.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    llmServer.start();
    return "http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1";
  }

  private String startFailingMockLlm() throws IOException {
    llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    llmServer.createContext("/v1/chat/completions", exchange -> {
      byte[] bytes = "provider exploded".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    llmServer.start();
    return "http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1";
  }

  private Properties llmProps(String baseUrl) {
    Properties props = new Properties();
    props.setProperty("llm.base.url", baseUrl);
    props.setProperty("llm.model", "test-model");
    return props;
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

  @Test
  void buildMessagesPutsSystemThenFreshContentThenTranscript() {
    JSONArray transcript = new JSONArray()
      .put(new JSONObject().put("role", "user").put("content", "first prompt"))
      .put(new JSONObject().put("role", "assistant").put("content", "!1 draft"))
      .put(new JSONObject().put("role", "user").put("content", "second prompt"));

    JSONArray out = AiPromptResponder.buildMessages("GENERATION PROMPT", "LoginTest", "Test",
      "!1 current", transcript);

    assertEquals(5, out.length());
    JSONObject system = out.getJSONObject(0);
    assertEquals("system", system.getString("role"));
    assertTrue(system.getString("content").startsWith("GENERATION PROMPT"));
    assertTrue(system.getString("content").contains("Page name: LoginTest"));
    assertTrue(system.getString("content").contains("Page type: Test"));
    JSONObject fresh = out.getJSONObject(1);
    assertEquals("user", fresh.getString("role"));
    assertTrue(fresh.getString("content").contains("Current page content"));
    assertTrue(fresh.getString("content").contains("!1 current"));
    assertEquals("first prompt", out.getJSONObject(2).getString("content"));
    assertEquals("!1 draft", out.getJSONObject(3).getString("content"));
    assertEquals("second prompt", out.getJSONObject(4).getString("content"));
  }

  @Test
  void buildMessagesIncludesContentBlockOnFirstTurn() {
    JSONArray out = AiPromptResponder.buildMessages("S", "NewPage", "Static", "",
      new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));

    assertEquals(3, out.length());
    assertTrue(out.getJSONObject(1).getString("content").contains("Current page content"));
    assertEquals("hello", out.getJSONObject(2).getString("content"));
  }

  @Test
  void generateReturnsContentForValidPayload() throws Exception {
    context = FitNesseUtil.makeTestContext(llmProps(startMockLlm("!1 Generated test")));

    JSONObject body = dispatch(promptRequest(validPayload()));

    assertEquals(200, lastStatus);
    assertEquals("!1 Generated test", body.getString("content"));
  }

  @Test
  void clientPageTypeIsUsedWhenProvided() throws Exception {
    context = FitNesseUtil.makeTestContext(llmProps(startMockLlm("x")));
    JSONObject payload = validPayload().put("pageType", "Suite");

    dispatch(promptRequest(payload));

    assertTrue(lastRequestBody.contains("Page type: Suite"));
    assertTrue(lastRequestBody.contains("Page name: SomePage"));
  }

  @Test
  void serverResolvesPageTypeWhenClientSendsNone() throws Exception {
    context = FitNesseUtil.makeTestContext(llmProps(startMockLlm("x")));
    WikiPageUtil.addPage(context.getRootPage(), PathParser.parse("LoginTest"), "!define TEST_RUNNER slim");
    JSONObject payload = validPayload().put("pageName", "LoginTest").put("pageType", "");

    dispatch(promptRequest(payload));

    assertTrue(lastRequestBody.contains("Page type: Test"), lastRequestBody);
  }

  @Test
  void llmFailureReturns502WithErrorAndDetails() throws Exception {
    context = FitNesseUtil.makeTestContext(llmProps(startFailingMockLlm()));

    JSONObject body = dispatch(promptRequest(validPayload()));

    assertEquals(502, lastStatus);
    assertTrue(body.getString("error").contains("HTTP 500"));
    assertEquals("provider exploded", body.getString("details"));
  }

  @Test
  void debugLogsGenerateRequest() throws Exception {
    Properties props = llmProps(startMockLlm("!1 x"));
    props.setProperty("llm.debug", "true");
    context = FitNesseUtil.makeTestContext(props);

    try (LogCapture logs = new LogCapture(AiPromptResponder.class)) {
      dispatch(promptRequest(validPayload()));
      assertTrue(logs.has(Level.INFO, "generate requested"));
      assertTrue(logs.has(Level.INFO, "generated contentLength"));
    }
  }

  @Test
  void llmErrorsAreLoggedAsWarnings() throws Exception {
    context = FitNesseUtil.makeTestContext(llmProps(startFailingMockLlm()));

    try (LogCapture logs = new LogCapture(AiPromptResponder.class)) {
      dispatch(promptRequest(validPayload()));
      assertEquals(502, lastStatus);
      assertTrue(logs.has(Level.WARNING, "LLM error"));
    }
  }

  @Test
  void runtimeFailureReturns502() throws Exception {
    Properties props = llmProps("::bad");

    context = FitNesseUtil.makeTestContext(props);

    JSONObject body = dispatch(promptRequest(validPayload()));

    assertEquals(502, lastStatus);
    assertTrue(body.getString("error").length() > 0);
  }
}
