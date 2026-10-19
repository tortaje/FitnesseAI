# Ai Prompt to FitNesse — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Ai Prompt to FitNesse" button to the FitNesse plain-text editor toolbar that opens a two-panel modal (chat left, generated wiki right), calls an OpenAI-compatible LLM to generate page content through a new stateless `ai-prompt` responder, and writes the chosen content back into the editor on Save.

**Architecture:** New responder `AiPromptResponder` (registered like `ai-verify`) composes a system prompt (configurable via `llm.generate.prompt*`) + a fresh `pageContent` block + the client transcript, calls the LLM, and returns plain `{content}`. `LlmClient` is refactored to share its transport (`post`) between verify (JSON-repaired) and generate (plain text). Shared request/error helpers move to `ResponderSupport`. The client adds a second modal and a toolbar button to `ai-verify.js` / `ai-verify.css`.

**Tech Stack:** Java 21 + Maven, `provided` FitNesse/org.json (already in the standalone JAR), JUnit 5 + mock `HttpServer`, vanilla ES5 JS + plain CSS (no build step), FitNesse 20260313.

**Spec:** `docs/superpowers/specs/2026-10-19-fitnesse-ai-prompt-design.md`

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Create | `src/main/resources/ai-generate-prompt.txt` | Bundled default generation prompt |
| Create | `src/main/java/com/fitnesseai/plugin/ResponderSupport.java` | Shared `isGet` / `parsePayload` / `jsonResponse` / LLM error JSON |
| Create | `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java` | `ai-prompt` endpoint: validate → compose → call → `{content}` |
| Create | `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java` | Endpoint tests (mock LLM) |
| Modify | `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java` | `generateSystemPrompt()` + shared `resolvePrompt()` |
| Modify | `src/main/java/com/fitnesseai/plugin/LlmClient.java` | Extract `post()`, add `generate()` |
| Modify | `src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java` | Delegate to `ResponderSupport` |
| Modify | `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java` | Register `ai-prompt` |
| Modify | `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` | Toolbar button, prompt modal, send flow |
| Modify | `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css` | Two-panel layout |
| Modify | `src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java` | Generate-prompt tests |
| Modify | `src/test/java/com/fitnesseai/plugin/LlmClientTest.java` | `generate()` tests |
| Modify | `README.md` | Feature, new properties, checklist |

**Conventions (from the existing code):** no code comments anywhere; English for all UI strings, docs and log messages; commit messages `feat:` / `refactor:` / `docs:` / `chore:`; one commit per task; TDD red → green.

---

### Task 1: Generation prompt configuration

**Files:**
- Create: `src/main/resources/ai-generate-prompt.txt`
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `AiVerifyConfigTest` (imports `assertThrows`, `assertTrue`, `assertFalse`, `Properties`, `Files`, `StandardCharsets`, `Path`, `Level` are already present):

```java
  @Test
  void inlineGeneratePromptOverridesDefault() {
    Properties props = props("llm.generate.prompt", "generate prompt");
    assertEquals("generate prompt", new AiVerifyConfig(props).generateSystemPrompt());
  }

  @Test
  void generatePromptFileOverridesInlineAndDefault(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("gen.md");
    Files.writeString(file, "# Generate\nsecond", StandardCharsets.UTF_8);
    Properties props = props("llm.generate.prompt", "inline",
      "llm.generate.prompt.file", file.toString());
    assertEquals("# Generate\nsecond", new AiVerifyConfig(props).generateSystemPrompt());
  }

  @Test
  void generatePromptFallsBackToBundledResource() {
    String prompt = new AiVerifyConfig(new Properties()).generateSystemPrompt();
    assertTrue(prompt.contains("wiki text"));
    assertFalse(prompt.contains("JSON object"), "must not be the verify prompt");
  }

  @Test
  void missingGeneratePromptFileThrowsListingPropertyAndPath(@TempDir Path dir) {
    String path = dir.resolve("does-not-exist.md").toString();
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> new AiVerifyConfig(props("llm.generate.prompt.file", path)).generateSystemPrompt());
    assertTrue(e.getMessage().contains("llm.generate.prompt.file"));
    assertTrue(e.getMessage().contains(path));
  }

  @Test
  void generatePromptSourceIsLoggedAtDebugLevel(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("gen.md");
    Files.writeString(file, "gen prompt", StandardCharsets.UTF_8);

    try (LogCapture logs = new LogCapture(AiVerifyConfig.class)) {
      new AiVerifyConfig(props("llm.debug", "true", "llm.generate.prompt.file", file.toString()))
        .generateSystemPrompt();
      assertTrue(logs.has(Level.INFO, "prompt source=file"));
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AiVerifyConfigTest`
Expected: FAIL — `cannot find symbol: method generateSystemPrompt()`

- [ ] **Step 3: Create the bundled prompt**

Create `src/main/resources/ai-generate-prompt.txt`:

```
You are a FitNesse wiki author. The user asks for a FitNesse page — static
documentation, a test suite, or an acceptance test — and you reply with the
complete page text.

RULES
- Reply with the wiki text and nothing else: no preamble, no explanation, no
  summary, no code fences, no surrounding quotes.
- Use FitNesse wikitext only: tables, !1..!6 headings, !contents, !define,
  !include, literal markers !- ... -!, comments (#), formatting ''', '', --.
- The "Current page content" block is the page as it stands now, possibly edited
  by hand. It is the source of truth: keep it unless the user asks to change it,
  and always return the COMPLETE page text — never a partial snippet and never a
  diff.
- Page type Test (acceptance test): one decision table per behaviour, a leading
  description row, then input columns and expected-output columns. Reference
  fixture or library pages only when the conversation names them.
- Page type Suite: !contents plus a short intro describing what the suite covers.
- Page type Static: clear !1/!2 headings, short paragraphs, tables where they
  help.
- Match the language already used on the page; otherwise use the language of the
  user's request.
- If the request is ambiguous, take the most conventional FitNesse choice and
  proceed. Do not ask questions.

OUTPUT
The full page text, nothing else.
```

- [ ] **Step 4: Implement in `AiVerifyConfig`**

Replace `systemPrompt()`, `logPromptSource`, `defaultPrompt()` and `promptFromFile()` with the shared resolver (keep everything else unchanged):

```java
  public String systemPrompt() {
    return resolvePrompt("[ai-verify]", PROMPT_FILE, PROMPT, DEFAULT_PROMPT_RESOURCE);
  }

  public String generateSystemPrompt() {
    return resolvePrompt("[ai-prompt]", GENERATE_PROMPT_FILE, GENERATE_PROMPT,
      DEFAULT_GENERATE_PROMPT_RESOURCE);
  }

  private String resolvePrompt(String tag, String fileKey, String inlineKey, String resource) {
    String path = raw(fileKey);
    if (!isBlank(path)) {
      String prompt = promptFromFile(path, fileKey);
      logPromptSource(tag, "file(" + path + ")", prompt);
      return prompt;
    }
    String inline = raw(inlineKey);
    if (!isBlank(inline)) {
      logPromptSource(tag, "inline", inline);
      return inline;
    }
    String prompt = defaultPrompt(resource);
    logPromptSource(tag, "bundled default", prompt);
    return prompt;
  }

  private void logPromptSource(String tag, String source, String prompt) {
    Level verbose = debug() ? Level.INFO : Level.FINE;
    LOG.log(verbose, () -> tag + " prompt source=" + source + " (" + prompt.length() + " chars)");
  }

  static String defaultPrompt(String resource) {
    try (InputStream in = AiVerifyConfig.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Default prompt resource missing: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String promptFromFile(String path, String fileKey) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      throw new IllegalStateException(fileKey + " points to a missing file: " + path);
    } catch (IOException e) {
      throw new IllegalStateException(fileKey + " could not read file " + path + ": " + e.getMessage());
    }
  }
```

Add the constants next to the existing ones:

```java
  public static final String GENERATE_PROMPT = "llm.generate.prompt";
  public static final String GENERATE_PROMPT_FILE = "llm.generate.prompt.file";
```

and change the resource constants block to:

```java
  private static final String DEFAULT_PROMPT_RESOURCE = "/ai-verify-prompt.txt";
  private static final String DEFAULT_GENERATE_PROMPT_RESOURCE = "/ai-generate-prompt.txt";
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS (all existing + the 5 new ones)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/ai-generate-prompt.txt \
        src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java \
        src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java
git commit -m "feat: configurable generation prompt (llm.generate.prompt, llm.generate.prompt.file)"
```

---

### Task 2: `LlmClient` transport extraction + `generate()`

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/LlmClient.java:34-80`
- Test: `src/test/java/com/fitnesseai/plugin/LlmClientTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `LlmClientTest` (the `respondWithBody` / `lastRequestBody` / `llmReply` / `config()` harness already exists):

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LlmClientTest`
Expected: FAIL — `cannot find symbol: method generate(...)`

- [ ] **Step 3: Extract `post()` and add `generate()`**

Replace the whole `chat()` method (lines 34–80) with:

```java
  public Result chat(AiVerifyConfig config, String pageContent) throws LlmException {
    JSONArray messages = new JSONArray()
      .put(new JSONObject().put("role", "system").put("content", config.systemPrompt()))
      .put(new JSONObject().put("role", "user").put("content", pageContent));
    String content = post(config, messages, "[ai-verify]");
    return parseResult(stripMarkdownFences(content), verboseLevel(config));
  }

  public String generate(AiVerifyConfig config, JSONArray messages) throws LlmException {
    return stripMarkdownFences(post(config, messages, "[ai-prompt]"));
  }

  String post(AiVerifyConfig config, JSONArray messages, String tag) throws LlmException {
    JSONObject body = new JSONObject()
      .put("model", config.model())
      .put("temperature", 0)
      .put("max_tokens", config.maxTokens())
      .put("messages", messages);

    String requestBody = body.toString();
    String url = config.baseUrl() + "/chat/completions";
    Level verbose = verboseLevel(config);
    LOG.log(verbose, () -> tag + " POST " + url + " model=" + config.model()
      + " request=" + truncate(requestBody));

    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(config.timeoutSeconds()))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody));
    if (!config.apiKey().isBlank()) {
      builder.header("Authorization", "Bearer " + config.apiKey());
    }

    HttpResponse<String> response;
    try {
      response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new LlmException("LLM request timed out after " + config.timeoutSeconds() + "s");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("LLM request was interrupted");
    } catch (IOException e) {
      throw new LlmException("Could not reach LLM at " + config.baseUrl() + ": " + e.getMessage());
    }

    LOG.log(verbose, () -> tag + " LLM response HTTP " + response.statusCode()
      + " body=" + truncate(response.body()));

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new LlmException("LLM returned HTTP " + response.statusCode(), truncate(response.body()));
    }

    String content = extractContent(response.body());
    LOG.log(verbose, () -> tag + " LLM content=" + truncate(content));
    return content;
  }

  private static Level verboseLevel(AiVerifyConfig config) {
    return config.debug() ? Level.INFO : Level.FINE;
  }
```

`parseResult` and `tryParse` keep their own `[ai-verify]` log strings — they are verify-only by design.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS — all existing `LlmClientTest` cases plus the 2 new ones

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/LlmClient.java \
        src/test/java/com/fitnesseai/plugin/LlmClientTest.java
git commit -m "refactor: share LLM transport in post() and add plain-text generate()"
```

---

### Task 3: `ResponderSupport` shared helpers

Pure refactor — no new behaviour. The existing tests are the safety net.

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/ResponderSupport.java`
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java`

- [ ] **Step 1: Create `ResponderSupport`**

```java
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
```

- [ ] **Step 2: Make `AiVerifyResponder` delegate**

In `AiVerifyResponder.java` delete the private `isGet`, `parsePayload` and the static `jsonResponse` methods, and change every call site:

```java
    if (ResponderSupport.isGet(request)) {
      return ResponderSupport.jsonResponse(405, new JSONObject().put("error", "Use POST to verify page content."));
    }

    JSONObject payload = ResponderSupport.parsePayload(request.getBody());
```

```java
      return ResponderSupport.jsonResponse(400, new JSONObject().put("error", "Missing required field: content"));
```

```java
      return ResponderSupport.jsonResponse(400, new JSONObject()
        .put("error", "AI verify is not configured. Add these properties to plugins.properties at the FitNesse root: "
          + String.join(", ", config.missingRequired()))
        .put("missing", new JSONArray(config.missingRequired())));
```

```java
    try {
      llmResult = new LlmClient().chat(config, content);
    } catch (LlmException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.llmError(LOG, "[ai-verify]", e));
    } catch (RuntimeException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.runtimeError(LOG, "[ai-verify]", e));
    }
```

```java
    return ResponderSupport.jsonResponse(200, new JSONObject()
      .put("hunks", hunks)
      .put("stats", new JSONObject()
        .put("added", diff.stats().added())
        .put("removed", diff.stats().removed())));
```

Remove now-unused imports: `fitnesse.http.SimpleResponse`, `java.io.UnsupportedEncodingException`, `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`.

- [ ] **Step 3: Run the whole suite**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: `Tests run: NN, Failures: 0, Errors: 0` and `BUILD SUCCESS` (unchanged count — this task adds no tests)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/ResponderSupport.java \
        src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java
git commit -m "refactor: extract shared responder request and LLM error helpers"
```

---

### Task 4: `AiPromptResponder` skeleton + validation + registration

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java`
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AiPromptResponderTest`
Expected: FAIL — `cannot find symbol: class AiPromptResponder`

- [ ] **Step 3: Create the responder with validation only**

```java
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
      return ResponderSupport.jsonResponse(405,
        new JSONObject().put("error", "Use POST to generate page content."));
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

  @Override
  public SecureOperation getSecureOperation() {
    return new SecureReadOperation();
  }
}
```

- [ ] **Step 4: Register it in the factory**

In `AiVerifyPluginFactory.registerResponders`, after the `edit` line:

```java
    responderFactory.addResponder("ai-prompt", AiPromptResponder.class);
```

and change the log line to:

```java
    LOG.info("FitNesse AI Verify plugin loaded (responders: ai-verify, edit override, new override, ai-prompt)");
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS (the `501` branch is never reached by these tests — the LLM call arrives in Task 6)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiPromptResponder.java \
        src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java \
        src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java
git commit -m "feat: ai-prompt responder with request validation"
```

---

### Task 5: Message composition

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `AiPromptResponderTest`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AiPromptResponderTest`
Expected: FAIL — `cannot find symbol: method buildMessages(...)`

- [ ] **Step 3: Implement `buildMessages`**

Add to `AiPromptResponder`:

```java
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=AiPromptResponderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiPromptResponder.java \
        src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java
git commit -m "feat: compose generation messages from prompt, fresh content and transcript"
```

---

### Task 6: LLM call, page type resolution, logging

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java`

- [ ] **Step 1: Write the failing tests**

Add the mock-LLM harness and the new tests. Add these imports to `AiPromptResponderTest`:

```java
import com.sun.net.httpserver.HttpServer;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageUtil;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.logging.Level;
```

and the harness fields/methods inside the class:

```java
  private HttpServer llmServer;
  private String lastRequestBody;

  @AfterEach
  void stopLlm() {
    if (llmServer != null) {
      llmServer.stop(0);
      llmServer = null;
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

  private Properties llmProps(String baseUrl) {
    Properties props = new Properties();
    props.setProperty("llm.base.url", baseUrl);
    props.setProperty("llm.model", "test-model");
    return props;
  }
```

Tests:

```java
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
    context.getRootPage().getChildPage("LoginTest").addVariable("Test", "true");
    JSONObject payload = validPayload().put("pageName", "LoginTest").put("pageType", "");

    dispatch(promptRequest(payload));

    assertTrue(lastRequestBody.contains("Page type: Test"), lastRequestBody);
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
    context = FitNesseUtil.makeTestContext(llmProps("http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1"));

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
    llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    llmServer.createContext("/v1/chat/completions", exchange -> {
      byte[] bytes = "provider exploded".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    llmServer.start();
    context = FitNesseUtil.makeTestContext(llmProps("http://127.0.0.1:" + llmServer.getAddress().getPort() + "/v1"));

    try (LogCapture logs = new LogCapture(AiPromptResponder.class)) {
      dispatch(promptRequest(validPayload()));
      assertEquals(502, lastStatus);
      assertTrue(logs.has(Level.WARNING, "LLM error"));
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AiPromptResponderTest`
Expected: FAIL — `generateReturnsContentForValidPayload` gets HTTP 501 with `{"error":"Not implemented yet"}`

- [ ] **Step 3: Replace the `501` stub with the real call**

Replace the last statement of `makeResponse` with:

```java
    String pageName = payload.optString("pageName", "");
    String pageType = payload.optString("pageType", "");
    if (pageType.isBlank()) {
      pageType = resolvePageType(context, pageName);
    }
    String resolvedPageType = pageType;
    JSONArray messages = buildMessages(config.generateSystemPrompt(), pageName, resolvedPageType,
      pageContent, transcript);

    Level verbose = config.debug() ? Level.INFO : Level.FINE;
    LOG.log(verbose, () -> "[ai-prompt] generate requested messages=" + transcript.length()
      + " pageType=" + resolvedPageType + " pageContentLength=" + pageContent.length());
    String content;
    try {
      content = new LlmClient().generate(config, messages);
    } catch (LlmException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.llmError(LOG, "[ai-prompt]", e));
    } catch (RuntimeException e) {
      return ResponderSupport.jsonResponse(502, ResponderSupport.runtimeError(LOG, "[ai-prompt]", e));
    }
    LOG.log(verbose, () -> "[ai-prompt] generated contentLength=" + content.length());
    return ResponderSupport.jsonResponse(200, new JSONObject().put("content", content));
  }

  private static String resolvePageType(FitNesseContext context, String pageName) {
    if (pageName == null || pageName.isBlank()) {
      return "";
    }
    try {
      WikiPage page = context.getRootPage().getPageCrawler().getPage(PathParser.parse(pageName));
      return page == null ? "" : PageType.fromWikiPage(page).toString();
    } catch (RuntimeException e) {
      return "";
    }
  }
```

Add the logger and imports at the top of the class:

```java
import fitnesse.wiki.PageType;
import fitnesse.wiki.WikiPage;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AiPromptResponder implements SecureResponder {
  private static final Logger LOG = Logger.getLogger(AiPromptResponder.class.getName());
```

Also change the earlier validation blocks to keep `pageContent` and `messages` in named locals (replace the two `instanceof` guards):

```java
    if (!payload.has("pageContent") || !(payload.get("pageContent") instanceof String pageContent)) {
      return ResponderSupport.jsonResponse(400,
        new JSONObject().put("error", "Missing required field: pageContent"));
    }
    if (!payload.has("messages") || !(payload.get("messages") instanceof JSONArray transcript)
        || transcript.length() == 0) {
      return ResponderSupport.jsonResponse(400,
        new JSONObject().put("error", "Missing required field: messages"));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test 2>&1 | grep -E "Tests run:|BUILD"`
Expected: all green, `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiPromptResponder.java \
        src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java
git commit -m "feat: call the LLM and return generated page content"
```

---

### Task 7: Prompt modal in the client (markup, open, save)

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`

No JS test framework in this repo — verification is `node --check` plus the manual pass in Task 11.

- [ ] **Step 1: Add the prompt state fields**

In the `state` object (line 5), add before `previouslyFocused`:

```js
    promptModal: null,
    prompt: { messages: [], busy: false },
    promptButton: null,
```

- [ ] **Step 2: Allow textareas inside the focus trap**

Change `trapFocus`'s selector:

```js
    var focusables = overlay.querySelectorAll('button:not([hidden]), input, textarea, [href], [tabindex]:not([tabindex="-1"])');
```

- [ ] **Step 3: Add the modal functions**

Insert this block immediately before `onReady(function () {`:

```js
  function ensurePromptModal() {
    if (state.promptModal) {
      return state.promptModal;
    }
    var overlay = document.createElement('div');
    overlay.className = 'ai-verify-overlay';
    overlay.innerHTML = [
      '<div class="ai-verify-modal ai-prompt-modal" role="dialog" aria-modal="true" aria-labelledby="ai-prompt-title">',
      '  <div class="ai-verify-header">',
      '    <h2 id="ai-prompt-title"></h2>',
      '    <button type="button" class="ai-verify-close" aria-label="Close">&#10005;</button>',
      '  </div>',
      '  <div class="ai-prompt-panels">',
      '    <div class="ai-prompt-chat">',
      '      <div class="ai-prompt-log"></div>',
      '      <div class="ai-prompt-input-row">',
      '        <textarea class="ai-prompt-input" rows="3" placeholder="Describe the FitNesse page you want (Enter to send, Shift+Enter for a new line)"></textarea>',
      '        <button type="button" class="ai-prompt-send">Send</button>',
      '      </div>',
      '    </div>',
      '    <div class="ai-prompt-side">',
      '      <div class="ai-prompt-side-label">Generated content</div>',
      '      <textarea class="ai-prompt-content" wrap="off" spellcheck="false"></textarea>',
      '    </div>',
      '  </div>',
      '  <div class="ai-verify-error" hidden><pre class="ai-verify-error-text"></pre></div>',
      '  <div class="ai-verify-footer ai-prompt-footer">',
      '    <button type="button" class="ai-verify-cancel">Cancel</button>',
      '    <span class="ai-prompt-footer-actions">',
      '      <button type="button" class="ai-verify-retry" hidden>Retry</button>',
      '      <button type="button" class="ai-prompt-save">Save</button>',
      '    </span>',
      '  </div>',
      '</div>'
    ].join('');
    document.body.appendChild(overlay);
    state.promptModal = overlay;

    overlay.addEventListener('mousedown', function (e) {
      if (e.target === overlay) {
        closePromptModal();
      }
    });
    overlay.querySelector('.ai-verify-close').addEventListener('click', closePromptModal);
    overlay.querySelector('.ai-verify-cancel').addEventListener('click', closePromptModal);
    overlay.querySelector('.ai-prompt-save').addEventListener('click', savePrompt);
    overlay.querySelector('.ai-prompt-send').addEventListener('click', sendPrompt);
    overlay.querySelector('.ai-verify-retry').addEventListener('click', sendPromptRequest);
    overlay.querySelector('.ai-prompt-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendPrompt();
      }
    });
    overlay.querySelector('.ai-prompt-input').addEventListener('input', updatePromptSendButton);
    overlay.querySelector('.ai-prompt-content').addEventListener('input', updatePromptSaveButton);
    overlay.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        closePromptModal();
        return;
      }
      if (e.key === 'Tab') {
        trapFocus(e, overlay);
      }
    });
    return overlay;
  }

  function openPromptModal() {
    var overlay = ensurePromptModal();
    state.prompt = { messages: [], busy: false };
    overlay.querySelector('.ai-prompt-log').innerHTML = '';
    overlay.querySelector('.ai-prompt-input').value = '';
    overlay.querySelector('.ai-prompt-content').value = getPageContent() || '';
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('#ai-prompt-title').textContent =
      'Ai Prompt to FitNesse — ' + currentPageName();
    updatePromptSendButton();
    updatePromptSaveButton();
    state.previouslyFocused = document.activeElement;
    overlay.style.display = 'flex';
    overlay.querySelector('.ai-prompt-input').focus();
  }

  function closePromptModal() {
    if (!state.promptModal) {
      return;
    }
    state.promptModal.style.display = 'none';
    if (state.previouslyFocused && state.previouslyFocused.focus) {
      state.previouslyFocused.focus();
    }
  }

  function savePrompt() {
    var overlay = ensurePromptModal();
    setPageContent(overlay.querySelector('.ai-prompt-content').value);
    closePromptModal();
  }

  function updatePromptSendButton() {
    var overlay = ensurePromptModal();
    var input = overlay.querySelector('.ai-prompt-input');
    overlay.querySelector('.ai-prompt-send').disabled =
      state.prompt.busy || input.value.trim() === '';
  }

  function updatePromptSaveButton() {
    var overlay = ensurePromptModal();
    overlay.querySelector('.ai-prompt-save').disabled =
      overlay.querySelector('.ai-prompt-content').value === '';
  }

  function setPromptBusy(busy) {
    state.prompt.busy = busy;
    var overlay = ensurePromptModal();
    var send = overlay.querySelector('.ai-prompt-send');
    send.textContent = busy ? 'Generating…' : 'Send';
    if (state.promptButton) {
      state.promptButton.disabled = busy;
    }
    updatePromptSendButton();
  }

  function appendPromptBubble(role, text) {
    var log = ensurePromptModal().querySelector('.ai-prompt-log');
    var bubble = document.createElement('div');
    bubble.className = 'ai-prompt-bubble '
      + (role === 'user' ? 'ai-prompt-bubble-user' : 'ai-prompt-bubble-assistant');
    bubble.textContent = text;
    log.appendChild(bubble);
    log.scrollTop = log.scrollHeight;
  }
```

`sendPrompt` / `sendPromptRequest` / `showPromptError` / `currentFormPageType` arrive in Task 8 — the modal is wired to them, so the file will not run correctly until Task 8 lands. Do not commit before Task 8 if you want a working tree at every commit; tasks 7 and 8 are reviewed as a pair.

- [ ] **Step 4: Syntax check**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: no output (exit 0)

- [ ] **Step 5: Run the Java suite (nothing should break)**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "feat: two-panel Ai Prompt modal markup with open and save"
```

---

### Task 8: Send flow (chat round-trip)

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`

- [ ] **Step 1: Add the send functions**

Insert after `appendPromptBubble` (same insertion point as Task 7, before `onReady`):

```js
  function currentFormPageType() {
    var checked = document.querySelector('form input[name="pageType"]:checked');
    if (checked) {
      return checked.value;
    }
    var hidden = document.querySelector('form input[name="pageType"]');
    if (hidden && hidden.value) {
      return hidden.value;
    }
    var template = document.querySelector('form input[name="pageTemplate"]');
    if (template && template.value) {
      var segment = template.value.substring(template.value.lastIndexOf('.') + 1);
      if (segment.indexOf('Suite') !== -1) {
        return 'Suite';
      }
      if (segment.indexOf('Test') !== -1) {
        return 'Test';
      }
      if (segment.indexOf('Static') !== -1) {
        return 'Static';
      }
    }
    return '';
  }

  function sendPrompt() {
    var overlay = ensurePromptModal();
    var input = overlay.querySelector('.ai-prompt-input');
    var text = input.value.trim();
    if (text === '' || state.prompt.busy) {
      return;
    }
    state.prompt.messages.push({ role: 'user', content: text });
    appendPromptBubble('user', text);
    input.value = '';
    updatePromptSendButton();
    sendPromptRequest();
  }

  function sendPromptRequest() {
    var overlay = ensurePromptModal();
    if (state.prompt.busy) {
      return;
    }
    setPromptBusy(true);
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;

    var payload = JSON.stringify({
      pageName: currentPageName(),
      pageType: currentFormPageType(),
      pageContent: overlay.querySelector('.ai-prompt-content').value,
      messages: state.prompt.messages
    });
    var form = 'responder=ai-prompt&payload=' + encodeURIComponent(payload);

    fetch(window.location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form
    }).then(function (resp) {
      return resp.text().then(function (raw) {
        setPromptBusy(false);
        var data = null;
        try {
          data = JSON.parse(raw);
        } catch (e) { /* not JSON */ }
        if (!resp.ok) {
          showPromptError(data && data.error ? data.error : ('HTTP error ' + resp.status),
            data && data.details ? data.details : null);
          return;
        }
        if (!data || typeof data.content !== 'string') {
          showPromptError('Unexpected response from the server.', raw.substring(0, 500));
          return;
        }
        state.prompt.messages.push({ role: 'assistant', content: data.content });
        appendPromptBubble('assistant', data.content);
        overlay.querySelector('.ai-prompt-content').value = data.content;
        updatePromptSaveButton();
      });
    }).catch(function (err) {
      setPromptBusy(false);
      showPromptError('Could not reach the FitNesse server: ' + err.message, null);
    });
  }

  function showPromptError(message, details) {
    var overlay = ensurePromptModal();
    var error = overlay.querySelector('.ai-verify-error');
    var text = overlay.querySelector('.ai-verify-error-text');
    text.textContent = details ? message + '\n\n' + details : message;
    error.hidden = false;
    overlay.querySelector('.ai-verify-retry').hidden = false;
  }
```

Retry re-POSTs `state.prompt.messages` as they stand (the failed user message is already in the transcript and in the log, so it must not be pushed twice).

- [ ] **Step 2: Syntax check**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: no output (exit 0)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "feat: send prompt, stream chat turns and handle errors"
```

---

### Task 9: Toolbar button

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js:21-29`

- [ ] **Step 1: Inject the button from the captured editor**

Replace the patch block:

```js
  if (window.Wysiwyg && !window.Wysiwyg.__aiVerifyPatched) {
    var originalInitialize = window.Wysiwyg.initialize;
    window.Wysiwyg.initialize = function (textarea) {
      var editor = originalInitialize.apply(this, arguments);
      state.editor = editor || null;
      if (editor && editor.textareaToolbar) {
        addPromptButton(editor.textareaToolbar);
      }
      return editor;
    };
    window.Wysiwyg.__aiVerifyPatched = true;
  }
```

and add this function right after `onReady`:

```js
  function addPromptButton(toolbar) {
    if (toolbar.querySelector('#tt-ai-prompt')) {
      return;
    }
    var button = document.createElement('input');
    button.type = 'button';
    button.id = 'tt-ai-prompt';
    button.value = 'Ai Prompt to FitNesse';
    button.title = 'Prompt an AI to generate FitNesse wiki content.';
    button.addEventListener('click', function () {
      openPromptModal();
    });
    toolbar.appendChild(button);
    state.promptButton = button;
  }
```

- [ ] **Step 2: Syntax check**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: no output (exit 0)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "feat: Ai Prompt to FitNesse button in the text-area toolbar"
```

---

### Task 10: Modal CSS

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`

- [ ] **Step 1: Append the prompt styles**

Append at the end of `ai-verify.css`:

```css
/* Ai Prompt modal */

.ai-prompt-panels {
  display: flex;
  gap: 0.75em;
  padding: 0.75em 1em;
  height: 60vh;
  min-height: 40vh;
  box-sizing: border-box;
}

.ai-prompt-chat {
  flex: 1 1 50%;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid #ddd;
  border-radius: 4px;
  overflow: hidden;
}

.ai-prompt-log {
  flex: 1;
  overflow-y: auto;
  padding: 0.6em;
  background: #f7f7f7;
  display: flex;
  flex-direction: column;
  gap: 0.5em;
}

.ai-prompt-bubble {
  padding: 0.5em 0.7em;
  border-radius: 6px;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 85%;
}

.ai-prompt-bubble-user {
  align-self: flex-end;
  background: #dce9f7;
}

.ai-prompt-bubble-assistant {
  align-self: flex-start;
  background: #e6f2e6;
}

.ai-prompt-input-row {
  display: flex;
  gap: 0.5em;
  padding: 0.5em;
  border-top: 1px solid #ddd;
  background: #fff;
}

.ai-prompt-input {
  flex: 1;
  resize: none;
  font-family: inherit;
  font-size: 13px;
  box-sizing: border-box;
  padding: 0.4em;
  border: 1px solid #bbb;
  border-radius: 4px;
}

.ai-prompt-send {
  padding: 0.4em 1em;
  cursor: pointer;
  border: 1px solid #2b6cb0;
  border-radius: 4px;
  background: #2b6cb0;
  color: #fff;
  font-size: 13px;
}

.ai-prompt-send:hover {
  background: #235a97;
  border-color: #235a97;
}

.ai-prompt-send:disabled {
  background: #7a9cc6;
  border-color: #7a9cc6;
  cursor: default;
}

.ai-prompt-side {
  flex: 1 1 50%;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ai-prompt-side-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #666;
  margin-bottom: 0.3em;
}

.ai-prompt-content {
  flex: 1;
  resize: none;
  font-family: Menlo, Consolas, monospace;
  font-size: 13px;
  box-sizing: border-box;
  padding: 0.5em;
  border: 1px solid #bbb;
  border-radius: 4px;
}

.ai-prompt-footer {
  justify-content: space-between;
}

.ai-prompt-footer-actions {
  display: flex;
  gap: 0.5em;
}

.ai-prompt-save {
  background: #2f6f2f !important;
  border-color: #2f6f2f !important;
  color: #fff !important;
  font-weight: bold;
}

.ai-prompt-save:hover {
  background: #255a25 !important;
}

.ai-prompt-save:disabled {
  background: #999 !important;
  border-color: #999 !important;
  color: #eee !important;
  cursor: default;
}
```

- [ ] **Step 2: Syntax check**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js && mvn -q package -DskipTests`
Expected: exit 0, JAR produced

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.css
git commit -m "feat: two-panel layout styles for the Ai Prompt modal"
```

---

### Task 11: README, build, deploy, manual smoke

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the feature**

In `README.md`, after the `llm.prompt.file` row of the properties table add:

```markdown
   | `llm.generate.prompt`     | no       | bundled  | Inline text replacing the generation system prompt (Ai Prompt to FitNesse) |
   | `llm.generate.prompt.file`| no       | —        | Path to a UTF-8 file holding the generation system prompt; re-read on every send, takes precedence over `llm.generate.prompt` |
```

After step 5 of **Usage**, add:

```markdown
6. **Ai Prompt to FitNesse** (text-area toolbar, next to *Spreadsheet to FitNesse*)
   opens a two-panel modal: describe the page you want on the left, press
   **Send**, and the generated wiki text appears on the right. The right panel is
   editable; every further **Send** re-sends it, so the LLM refines your hand
   edits. **Save** closes the modal and replaces the whole editor content — the
   page is still saved manually with the Save button.
```

Add to the **Manual validation checklist (JS)**:

```markdown
- [ ] The **Ai Prompt to FitNesse** button sits at the end of the text-area
      toolbar on every edit form (plain-text mode) and the modal opens with the
      current page content already in the right panel.
- [ ] Type a request → **Send**: a user bubble appears, then an assistant bubble
      and the right panel fills with the reply. Send is disabled while empty.
- [ ] Edit the right panel by hand, send a second request → the edited content is
      sent along with it (check the Network tab payload).
- [ ] **Save** closes the modal and replaces the editor content; the WYSIWYG view
      matches after switching modes.
- [ ] Bad `llm.base.url` → error banner + **Retry**; a warning line appears in the
      FitNesse console tagged `[ai-prompt]`.
- [ ] With `llm.debug=true`: `[ai-prompt]` INFO lines (generate request, LLM
      response, prompt source) are visible; with the property absent, only
      warnings appear.
```

- [ ] **Step 2: Run the full suite and build**

Run: `mvn clean verify 2>&1 | grep -E "Tests run:.*Failures|BUILD"`
Expected: `Tests run: NN, Failures: 0, Errors: 0, Skipped: 0` then `BUILD SUCCESS`

- [ ] **Step 3: Deploy to the smoke instance**

```bash
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
lsof -ti tcp:8091 | xargs kill
sleep 2
(cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &)
sleep 6
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8091/FrontPage
```

Expected: `200`

- [ ] **Step 4: Verify the responder and asset injection with curl**

```bash
curl -s http://localhost:8091/FrontPage | grep -c "ai-prompt"
```

Expected: `0` (no textarea toolbar in page view — that is correct)

```bash
curl -s -X POST http://localhost:8091/FrontPage \
  -d 'responder=ai-prompt&payload=%7B%22pageName%22%3A%22FrontPage%22%2C%22pageType%22%3A%22%22%2C%22pageContent%22%3A%22%211%20draf%22%2C%22messages%22%3A%5B%7B%22role%22%3A%22user%22%2C%22content%22%3A%22write%20a%20login%20test%22%7D%5D%7D' \
  -o /tmp/ai-prompt.json -w "%{http_code}\n"
```

Expected: `200`, and:

```bash
python3 -c "import json;print(json.load(open('/tmp/ai-prompt.json'))['content'][:200])"
```

Expected: wiki text (a `!1` heading or a table), not a JSON error.

```bash
grep -c "\[ai-prompt\]" /tmp/fitnesse-smoke/fitnesse-console.log
```

Expected: `> 0`

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: document Ai Prompt to FitNesse, its properties and checklist"
```

- [ ] **Step 6: Manual browser pass (user)**

Open `http://localhost:8091/FrontPage?edit` (text mode), find **Ai Prompt to
FitNesse** in the toolbar, run two chat turns, hand-edit the right panel between
turns, press **Save**, then save the page. Repeat once on
`http://localhost:8091/FrontPage?new&pageTemplate=.TemplateLibrary.TestPage`.

---

## Self-Review Notes

- **Spec coverage:** button placement (Task 9), 50/50 layout (Task 10), Save
  replaces (Task 7), editable right panel + re-send (Tasks 7–8), seed with page
  content (`openPromptModal`), configurable prompt (Task 1), page metadata
  (Task 8 `currentFormPageType` + Task 6 server-side resolution), stateless
  `ai-prompt` responder (Tasks 4–6), error contract (Task 6), debug logging
  (Tasks 6), tests (Tasks 1–6), README (Task 11). Out-of-scope items from the
  spec are not touched.
- **Type consistency:** `generateSystemPrompt()`, `post(config, messages, tag)`,
  `buildMessages(systemPrompt, pageName, pageType, pageContent, transcript)`,
  `ResponderSupport.llmError(log, tag, e)` — signatures are identical at every call
  site.
- **Known sequencing note:** Task 7 references `sendPrompt` / `sendPromptRequest`
  before Task 8 defines them. They are function declarations hoisted in the same
  IIFE, so the file always parses; the modal only works end-to-end after Task 8.
  Both tasks must be reviewed together before deploy (Task 11).
