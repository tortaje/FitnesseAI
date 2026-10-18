package io.github.tortaje;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientTest {

  private HttpServer server;
  private String baseUrl;
  private String lastAuthorization;
  private String lastRequestBody;
  private int respondWithStatus = 200;
  private String respondWithBody = "";
  private long respondWithDelayMs = 0;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (respondWithDelayMs > 0) {
        try {
          Thread.sleep(respondWithDelayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] bytes = respondWithBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(respondWithStatus, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AiVerifyConfig config() {
    return configWith("llm.api.key", "sk-test");
  }

  private AiVerifyConfig configWith(String... extra) {
    Properties props = new Properties();
    props.setProperty("llm.base.url", baseUrl);
    props.setProperty("llm.model", "test-model");
    for (int i = 0; i < extra.length; i += 2) {
      props.setProperty(extra[i], extra[i + 1]);
    }
    return new AiVerifyConfig(props);
  }

  private static String llmReply(String correctedJson) {
    return new JSONObject()
      .put("choices", new JSONArray().put(new JSONObject()
        .put("message", new JSONObject()
          .put("role", "assistant")
          .put("content", correctedJson))))
      .toString();
  }

  @Test
  void sendsChatCompletionRequestAndParsesResult() throws Exception {
    String corrected = new JSONObject()
      .put("corrected", "!define x 1")
      .put("comments", new JSONArray().put(new JSONObject()
        .put("line", 1).put("explanation", "typo")))
      .toString();
    respondWithBody = llmReply(corrected);

    LlmClient.Result result = new LlmClient().chat(config(), "!define x 2");

    assertEquals("!define x 1", result.corrected());
    assertEquals(1, result.comments().size());
    assertEquals(1, result.comments().get(0).line());
    assertEquals("typo", result.comments().get(0).explanation());
    assertEquals("Bearer sk-test", lastAuthorization);
    assertTrue(lastRequestBody.contains("\"temperature\":0"));
    assertTrue(lastRequestBody.contains("\"model\":\"test-model\""));
  }

  @Test
  void omitsAuthorizationHeaderWithoutApiKey() throws Exception {
    respondWithBody = llmReply(new JSONObject().put("corrected", "x").toString());

    new LlmClient().chat(configWith("llm.api.key", ""), "page");

    assertNull(lastAuthorization);
  }

  @Test
  void stripsMarkdownFencesAroundJson() throws Exception {
    String corrected = new JSONObject().put("corrected", "ok").toString();
    respondWithBody = llmReply("```json\n" + corrected + "\n```");

    assertEquals("ok", new LlmClient().chat(config(), "page").corrected());
  }

  @Test
  void repairsDoubleEscapedQuotesFromSmallModels() throws Exception {
    // llama3.2:3b emits \\" where JSON requires \" inside string values.
    String corrected = "To add your first \"page\", click the Edit button.";
    String wellFormed = new JSONObject()
      .put("corrected", corrected)
      .put("comments", new JSONArray())
      .toString();
    respondWithBody = llmReply(wellFormed.replace("\\\"", "\\\\\""));

    assertEquals(corrected, new LlmClient().chat(config(), "page").corrected());
  }

  @Test
  void repairsAsymmetricQuoteEscapesFromSmallModels() throws Exception {
    // Real llama3.2:3b output: opening quote with \\ (invalid), closing with \\\
    // (valid JSON but decodes to a spurious backslash before the quote).
    String broken = "{\"corrected\": \"say \\\\\"hi\\\\\\\" now\", \"comments\": []}";
    respondWithBody = llmReply(broken);

    assertEquals("say \"hi\" now", new LlmClient().chat(config(), "page").corrected());
  }

  @Test
  void httpErrorRaisesLlmExceptionWithRawBody() {
    respondWithStatus = 500;
    respondWithBody = "internal boom";

    LlmException e = assertThrows(LlmException.class, () -> new LlmClient().chat(config(), "page"));

    assertTrue(e.getMessage().contains("HTTP 500"));
    assertEquals("internal boom", e.rawBody());
  }

  @Test
  void invalidJsonContentRaisesLlmException() {
    respondWithBody = llmReply("this is not json");

    LlmException e = assertThrows(LlmException.class, () -> new LlmClient().chat(config(), "page"));

    assertTrue(e.getMessage().contains("valid JSON"));
  }

  @Test
  void missingCorrectedFieldRaisesLlmException() {
    respondWithBody = llmReply(new JSONObject().put("comments", new JSONArray()).toString());

    LlmException e = assertThrows(LlmException.class, () -> new LlmClient().chat(config(), "page"));

    assertTrue(e.getMessage().contains("corrected"));
  }

  @Test
  void timeoutRaisesLlmException() throws Exception {
    respondWithDelayMs = 3000;
    respondWithBody = llmReply(new JSONObject().put("corrected", "late").toString());
    AiVerifyConfig impatient = configWith("llm.timeout.seconds", "1");

    LlmException e = assertThrows(LlmException.class, () -> new LlmClient().chat(impatient, "page"));

    assertTrue(e.getMessage().contains("timed out"));
  }

  @Test
  void requestAndResponseDumpsFollowDebugFlag() throws Exception {
    respondWithBody = llmReply(new JSONObject().put("corrected", "x").toString());

    try (LogCapture logs = new LogCapture(LlmClient.class)) {
      new LlmClient().chat(configWith("llm.debug", "true"), "page");
      assertTrue(logs.has(Level.INFO, "[ai-verify] POST"));
      assertTrue(logs.has(Level.INFO, "[ai-verify] LLM response HTTP 200"));
      assertTrue(logs.has(Level.INFO, "[ai-verify] LLM content="));
    }

    try (LogCapture logs = new LogCapture(LlmClient.class)) {
      new LlmClient().chat(config(), "page");
      assertFalse(logs.has(Level.INFO, "[ai-verify] POST"));
      assertFalse(logs.has(Level.INFO, "[ai-verify] LLM response HTTP 200"));
      assertFalse(logs.has(Level.INFO, "[ai-verify] LLM content="));
      assertTrue(logs.has(Level.FINE, "[ai-verify] POST"));
      assertTrue(logs.has(Level.FINE, "[ai-verify] LLM response HTTP 200"));
      assertTrue(logs.has(Level.FINE, "[ai-verify] LLM content="));
    }
  }

  @Test
  void generateSendsMessagesAndReturnsPlainWikiText() throws Exception {
    respondWithBody = llmReply("```wikitext\n!1 Login test\n|user|\n|----|\n```");
    JSONArray messages = new JSONArray()
      .put(new JSONObject().put("role", "system").put("content", "GENERATION"))
      .put(new JSONObject().put("role", "user").put("content", "make a test"));

    String content = new LlmClient().generate(config(), messages);

    assertEquals("!1 Login test\n|user|\n|----|", content);
    JSONObject body = new JSONObject(lastRequestBody);
    assertEquals(2, body.getJSONArray("messages").length());
    assertEquals("GENERATION", body.getJSONArray("messages").getJSONObject(0).getString("content"));
    assertEquals("make a test", body.getJSONArray("messages").getJSONObject(1).getString("content"));
    assertEquals("test-model", body.getString("model"));
    assertEquals("Bearer sk-test", lastAuthorization);
  }

  @Test
  void generateDoesNotJsonParseTheContent() throws Exception {
    respondWithBody = llmReply("not json at all {broken");

    String content = new LlmClient().generate(config(), new JSONArray()
      .put(new JSONObject().put("role", "user").put("content", "x")));

    assertEquals("not json at all {broken", content);
  }
}
