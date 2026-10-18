# FitNesse AI Verify Plugin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a FitNesse plugin JAR (drop-in `plugins/` folder) that adds a "Verify with AI" button to the wiki edit page: it sends the current textarea content to an OpenAI-compatible LLM, shows the proposed corrections as selectable diff hunks in a modal, and applies the selection back into the textarea.

**Architecture:** Single Maven project producing one JAR. A `PluginFeatureFactory` (discovered via ServiceLoader) registers two responders: `ai-verify` (POST JSON→hunks) and replaces the default `edit` responder with a subclass that injects the plugin's JS/CSS into the edit page HTML. JS is served from the JAR via FitNesse's classpath resource mechanism (`files/fitnesse/...` URLs). Diff computation and the LLM call happen server-side (org.json + java-diff-utils + JDK HttpClient, all already inside the FitNesse standalone JAR — `provided` scope).

**Tech Stack:** Java 21, Maven, JUnit 5, FitNesse 20250223 (provided), org.json (provided), java-diff-utils 1.3.0 (provided), vanilla JS/CSS.

**Spec:** `docs/superpowers/specs/2026-10-18-fitnesse-ai-verify-design.md`. Task 1 amends the spec with facts discovered during API research (registration mechanism, exact hunk JSON, FitNesse dependency version).

---

## Key FitNesse facts (researched from source, /tmp/fitnesse-src on master)

These facts drive the design; the executor does not need to re-research:

1. **Registration:** `PluginFeatureFactory.Impl` is *not* a FitNesse property (does not exist in `ConfigurationParameter`). Factories are discovered via `ServiceLoader.load(PluginFeatureFactory.class, classLoader)` where `classLoader` is the plugins folder classloader (`FitNesseMain` → `PluginsClassLoaderFactory.getClassLoader`). So the JAR registers itself with `META-INF/services/fitnesse.plugins.PluginFeatureFactory` — no properties line needed for registration.
2. **Replacing the edit responder:** `ResponderFactory.addResponder(key, clazz)` is `map.put` — a plugin factory can call `addResponder("edit", AiVerifyEditResponder.class)` and override the default. `PluginsLoader.loadResponders` runs after the defaults are installed (`ContextConfigurator.makeFitNesseContext`, line 183).
3. **Responder instantiation:** `ResponderFactory.newResponderInstance` tries a `String rootPath` constructor first, then a no-arg constructor. Use a no-arg constructor.
4. **Serving JS/CSS from the JAR:** URL `files/fitnesse/<path>` falls through to classpath `/fitnesse/resources/<path>` via the *thread context classloader* when the file is not on disk (`FileResponder.canLoadFromClasspath`, limit 512 KB). So assets live in the JAR at `fitnesse/resources/ai-verify/ai-verify.js|css` (Maven: `src/main/resources/fitnesse/resources/ai-verify/...`) and are reachable at `{contextRoot}files/fitnesse/ai-verify/...`.
5. **Edit page HTML post-processing:** `EditResponder.makeResponse` returns a `SimpleResponse`; `SimpleResponse.getContentBytes()`/`setContent(String)` allow string post-processing. Encode/decode with UTF-8 (`FileUtil.CHARENCODING`). Inject tags before `</body>` via `String.replace("</body>", ...)`.
6. **Reading config at request time:** `FitNesseContext.getProperties()` (public) returns the merged `plugins.properties` Properties. No cache → config changes need only a FitNesse restart (the file is read at boot into these properties).
7. **Request handling:** `Request.getBody()` returns the raw entity body. FitNesse parses *any* non-multipart body as `key=value&...` into inputs (`Request.parseEntityBody`) — naive and lossy (raw `+`/`&` in a JSON body corrupt the `responder` input). Therefore the JS posts **form-encoded** `responder=ai-verify&payload=<urlencoded-json>`; the responder key comes from the body input, and the JSON travels safely percent-encoded in `payload`. The responder validates the request method via `Request.getRequestLine()` (starts with `GET ` → 405; FitNesse discards the method otherwise). `Response.setStatus(int)` exists.
8. **Security:** implement `SecureResponder` + `SecureReadOperation` (same as `EditResponder`) — FitNesse's existing auth is honored automatically.
9. **Tests:** `fitnesse.testutil.FitNesseUtil.makeTestContext(Properties)` builds a full context (InMemoryPage, no server start); `fitnesse.http.MockRequest` has `setBody/addInput/addHeader/setRequestLine/setQueryString`; `context.responderFactory` and `context.contextRoot` are public. In Surefire, the system classloader sees `target/classes`, so ServiceLoader finds our factory and integration tests exercise the real wiring.
10. **Editor integration:** the edit page initializes CodeMirror 4.12 via `Wysiwyg.initialize(textarea)` inside a jQuery-ready closure — the instance is not stored globally. CodeMirror 4 does expose the instance on the wrapper DOM node (`wrapper.CodeMirror`), but to sync WYSIWYG↔textarea correctly we need the `Wysiwyg` instance (methods `activeEditor()`, `loadWikiText()`, `loadWysiwygDocument()`, `codeMirrorEditor`). Our script patches `Wysiwyg.initialize` (our script tag is injected at end of body, runs before DOMContentLoaded, so the patch is in place when the edit page's ready-callback calls `Wysiwyg.initialize`) and captures the instance.
11. **Versions:** latest GitHub release `v20251025` (standalone jar available); latest on Maven Central `20250223` (used as `provided` compile dependency). FitNesse bundles `org.json:json` and `com.googlecode.java-diff-utils:diffutils:1.3.0` (verified in its `build.gradle`).

---

### Task 1: Amend the spec with researched API facts

**Files:**
- Modify: `docs/superpowers/specs/2026-10-18-fitnesse-ai-verify-design.md`

- [ ] **Step 1: Edit the spec — architecture section (components)**

Replace this bullet in "Composants Java":

```
- **`AiVerifyPluginFactory`** — implémente `PluginFeatureFactory`, enregistré via `PluginFeatureFactory.Impl` dans `plugins.properties`. Injecte le JS/CSS via les *component factories* FitNesse (pages d'édition uniquement) et enregistre le responder.
```

with:

```
- **`AiVerifyPluginFactory`** — implémente `PluginFeatureFactory` (hérite de `PluginFeatureFactoryBase`), découvert automatiquement via ServiceLoader (`META-INF/services/fitnesse.plugins.PluginFeatureFactory` dans le JAR, aucune propriété requise). Enregistre le responder `ai-verify` et **remplace** le responder `edit` par `AiVerifyEditResponder` (sous-classe qui injecte le JS/CSS dans le HTML de la page d'édition — FitNesse n'expose plus de hook `makeBrowsers()`).
```

- [ ] **Step 2: Edit the spec — client components bullet**

Replace:

```
- **`ai-verify.js`** — détection de la page d'édition, injection du bouton, appel du responder, rendu du modal, sélection des hunk, fusion dans le textarea. Inclut une copie minimale de jsdiff.
```

with:

```
- **`ai-verify.js`** — détection de la page d'édition, injection du bouton, appel du responder, rendu du modal, sélection des hunk, fusion dans le textarea. Aucune dépendance JS : le diff est calculé côté serveur, le JS fusionne les hunk sélectionnés.
```

- [ ] **Step 3: Edit the spec — API contract (hunk JSON)**

Replace the responder response example in "Flux de données & contrat d'API" (step 4):

```json
{ "hunks": [ { "id": 0, "type": "modified", "lines": [...], "comment": "..." } ],
  "stats": { "added": 3, "removed": 2 } }
```

with:

```json
{ "hunks": [ { "id": 0, "type": "modified", "startLine": 5,
               "originalLines": ["..."], "correctedLines": ["..."], "comment": "..." } ],
  "stats": { "added": 3, "removed": 2 } }
```

and add after it:

```
`startLine` est le numéro de ligne (1-based) de la zone dans le contenu *original* ; `originalLines`/`correctedLines` portent le contenu des deux côtés du hunk (vide pour un ajout pur / une suppression pure). Le JS reconstruit le document fusionné à partir du contenu envoyé + `startLine`/`originalLines`/`correctedLines` des hunk cochés.

Requête : le JS POSTe `responder=ai-verify&payload=<urlencoded JSON {pageName, content}>` (form-encoded) sur l'URL de la page — le parseur de body de FitNesse traite tout body non-multipart comme `key=value&`, un body JSON brut corromprait la résolution du `responder`.
```

- [ ] **Step 4: Edit the spec — configuration section**

Replace:

```properties
PluginFeatureFactory.Impl=com.fitnesseai.plugin.AiVerifyPluginFactory
llm.base.url=http://localhost:11434/v1
```

with:

```properties
llm.base.url=http://localhost:11434/v1
```

and add this line right after the properties block:

```
Le plugin se découvre via ServiceLoader : aucune propriété de registration n'est nécessaire. `llm.base.url` et `llm.model` sont obligatoires ; les autres ont des valeurs par défaut.
```

- [ ] **Step 5: Edit the spec — packaging section**

Replace:

```
- Dépendances externes minimales (java-diff-utils ou diff maison).
```

with:

```
- Dépendances externes minimales : `org.json` et `com.googlecode.java-diff-utils:diffutils` en scope `provided` (déjà présents dans le JAR standalone FitNesse, donc non empaquetés). Dépendance de compilation : `org.fitnesse:fitnesse:20250223` (provided ; dernière version Maven Central ; dernière release GitHub : 20251025 pour le smoke test manuel).
```

- [ ] **Step 5: Edit the spec — config reload claim**

Replace:

```
Relecture de la config à chaque requête (pas de cache) → changements sans redémarrage.
```

with:

```
Le plugin ne cache pas la config : elle est relue dans `context.getProperties()` à chaque requête. Attention : FitNesse charge `plugins.properties` au démarrage — modifier le fichier nécessite donc un redémarrage de FitNesse.
```

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-10-18-fitnesse-ai-verify-design.md
git commit -m "docs: amend spec with FitNesse plugin API research findings"
```

---

### Task 2: Scaffold the Maven project

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.fitnesseai</groupId>
  <artifactId>fitnesse-ai-verify</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.fitnesse</groupId>
      <artifactId>fitnesse</artifactId>
      <version>20250223</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.json</groupId>
      <artifactId>json</artifactId>
      <version>20250517</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.googlecode.java-diff-utils</groupId>
      <artifactId>diffutils</artifactId>
      <version>1.3.0</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.7.1</version>
        <configuration>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
          <appendAssemblyId>false</appendAssemblyId>
        </configuration>
        <executions>
          <execution>
            <id>make-assembly</id>
            <phase>package</phase>
            <goals>
              <goal>single</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Note: `provided` dependencies are absent from the assembly JAR (correct — FitNesse standalone already contains org.json, java-diff-utils, and of course FitNesse itself) but *are* on the test classpath.

- [ ] **Step 2: Create `.gitignore`**

```
target/
*.iml
.idea/
.DS_Store
```

- [ ] **Step 3: Verify the build resolves dependencies**

Run: `mvn -q dependency:resolve`
Expected: `BUILD SUCCESS`, no errors. (If `org.fitnesse:fitnesse:20250223` cannot be resolved from Central in your environment, download `fitnesse-20251025.jar` from https://github.com/unclebob/fitnesse/releases and run `mvn install:install-file -Dfile=fitnesse-20251025.jar -DgroupId=org.fitnesse -DartifactId=fitnesse -Dversion=20251025 -Dpackaging=jar`, then change the version in the POM to `20251025`.)

- [ ] **Step 4: Commit**

```bash
git add pom.xml .gitignore
git commit -m "chore: Maven project scaffold targeting Java 21 and FitNesse 20250223"
```

---

### Task 3: AiVerifyConfig + default system prompt

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java`
- Create: `src/main/resources/ai-verify-prompt.txt`
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.fitnesseai.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVerifyConfigTest {

  @Test
  void missingRequiredListsBaseUrlAndModel() {
    AiVerifyConfig config = new AiVerifyConfig(new Properties());
    assertEquals(List.of("llm.base.url", "llm.model"), config.missingRequired());
  }

  @Test
  void baseUrlTrailingSlashIsRemoved() {
    Properties props = props("llm.base.url", "http://localhost:11434/v1/", "llm.model", "llama3.1");
    assertEquals("http://localhost:11434/v1", new AiVerifyConfig(props).baseUrl());
  }

  @Test
  void optionalValuesHaveDefaults() {
    Properties props = props("llm.base.url", "http://x/v1", "llm.model", "m");
    AiVerifyConfig config = new AiVerifyConfig(props);
    assertEquals(4096, config.maxTokens());
    assertEquals(120, config.timeoutSeconds());
    assertEquals("", config.apiKey());
    assertTrue(config.systemPrompt().contains("JSON object"),
      "bundled default prompt should ask for a JSON object");
  }

  @Test
  void inlinePromptOverridesDefault() {
    Properties props = props("llm.prompt", "custom prompt");
    assertEquals("custom prompt", new AiVerifyConfig(props).systemPrompt());
  }

  @Test
  void invalidNumbersFallBackToDefaults() {
    Properties props = props("llm.max.tokens", "abc", "llm.timeout.seconds", "xyz");
    AiVerifyConfig config = new AiVerifyConfig(props);
    assertEquals(4096, config.maxTokens());
    assertEquals(120, config.timeoutSeconds());
  }

  private static Properties props(String... kv) {
    Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=AiVerifyConfigTest`
Expected: COMPILATION ERROR — `AiVerifyConfig` does not exist.

- [ ] **Step 3: Write the default prompt resource**

`src/main/resources/ai-verify-prompt.txt`:

```
You are a proofreader for FitNesse wiki pages written in FitNesse's wikitext syntax.
Review the page content the user sends and correct only clear mistakes, for example:
- unbalanced or misspelled FitNesse markup (tables, !- !- literal markers, !style-*, !define, !include, variable references ${...})
- broken table structure (missing cells or separators)
- obvious typos in prose
- inconsistent page or test names referenced inside the page

Rules:
- Keep the overall structure, wording and style of the page. Do not rewrite content, do not add new sections and do not translate text.
- Never remove tables, fixtures or test content unless it is syntactically broken.
- Respond with ONLY a JSON object, no markdown fences and no commentary, in this exact shape:
{"corrected": "<the full corrected page content>", "comments": [{"line": 12, "explanation": "<short reason>"}]}
- "line" is the 1-based line number in the corrected content that the comment refers to.
- If the page is already correct, return the unchanged content with an empty comments array.
```

- [ ] **Step 4: Write the implementation**

`src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java`:

```java
package com.fitnesseai.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class AiVerifyConfig {
  public static final String BASE_URL = "llm.base.url";
  public static final String API_KEY = "llm.api.key";
  public static final String MODEL = "llm.model";
  public static final String MAX_TOKENS = "llm.max.tokens";
  public static final String TIMEOUT_SECONDS = "llm.timeout.seconds";
  public static final String PROMPT = "llm.prompt";

  public static final int DEFAULT_MAX_TOKENS = 4096;
  public static final int DEFAULT_TIMEOUT_SECONDS = 120;

  private static final String DEFAULT_PROMPT_RESOURCE = "/ai-verify-prompt.txt";

  private final Properties properties;

  public AiVerifyConfig(Properties properties) {
    this.properties = properties;
  }

  public List<String> missingRequired() {
    List<String> missing = new ArrayList<>();
    if (isBlank(raw(BASE_URL))) {
      missing.add(BASE_URL);
    }
    if (isBlank(raw(MODEL))) {
      missing.add(MODEL);
    }
    return missing;
  }

  public String baseUrl() {
    String url = raw(BASE_URL);
    if (url == null) {
      return null;
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public String apiKey() {
    String key = raw(API_KEY);
    return key == null ? "" : key;
  }

  public String model() {
    return raw(MODEL);
  }

  public int maxTokens() {
    return intOr(MAX_TOKENS, DEFAULT_MAX_TOKENS);
  }

  public int timeoutSeconds() {
    return intOr(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
  }

  public String systemPrompt() {
    String inline = raw(PROMPT);
    if (!isBlank(inline)) {
      return inline;
    }
    return defaultPrompt();
  }

  static String defaultPrompt() {
    try (InputStream in = AiVerifyConfig.class.getResourceAsStream(DEFAULT_PROMPT_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Default prompt resource missing: " + DEFAULT_PROMPT_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String raw(String key) {
    String value = properties.getProperty(key);
    return value == null ? null : value.trim();
  }

  private int intOr(String key, int defaultValue) {
    String value = raw(key);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=AiVerifyConfigTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java \
        src/main/resources/ai-verify-prompt.txt \
        src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java
git commit -m "feat: AiVerifyConfig reads llm.* properties with defaults and bundled prompt"
```

---

### Task 4: LlmClient (OpenAI-compatible chat client)

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/LlmException.java`
- Create: `src/main/java/com/fitnesseai/plugin/LlmClient.java`
- Test: `src/test/java/com/fitnesseai/plugin/LlmClientTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.fitnesseai.plugin;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Thread.sleep(respondWithDelayMs);
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=LlmClientTest`
Expected: COMPILATION ERROR — `LlmClient`/`LlmException` do not exist.

- [ ] **Step 3: Write LlmException**

`src/main/java/com/fitnesseai/plugin/LlmException.java`:

```java
package com.fitnesseai.plugin;

public class LlmException extends Exception {
  private final String rawBody;

  public LlmException(String message) {
    this(message, null);
  }

  public LlmException(String message, String rawBody) {
    super(message);
    this.rawBody = rawBody;
  }

  /** Raw response body from the LLM, when available. Never contains the API key. */
  public String rawBody() {
    return rawBody;
  }
}
```

- [ ] **Step 4: Write LlmClient**

`src/main/java/com/fitnesseai/plugin/LlmClient.java`:

```java
package com.fitnesseai.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LlmClient {
  public record Comment(int line, String explanation) {}
  public record Result(String corrected, List<Comment> comments, String rawContent) {}

  private final HttpClient httpClient;

  public LlmClient() {
    this(HttpClient.newHttpClient());
  }

  LlmClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public Result chat(AiVerifyConfig config, String pageContent) throws LlmException {
    JSONObject body = new JSONObject()
      .put("model", config.model())
      .put("temperature", 0)
      .put("max_tokens", config.maxTokens())
      .put("messages", new JSONArray()
        .put(new JSONObject().put("role", "system").put("content", config.systemPrompt()))
        .put(new JSONObject().put("role", "user").put("content", pageContent)));

    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create(config.baseUrl() + "/chat/completions"))
      .timeout(Duration.ofSeconds(config.timeoutSeconds()))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
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

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new LlmException("LLM returned HTTP " + response.statusCode(), truncate(response.body()));
    }

    String content = extractContent(response.body());
    return parseResult(stripMarkdownFences(content));
  }

  static String extractContent(String responseBody) throws LlmException {
    try {
      return new JSONObject(responseBody)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content");
    } catch (RuntimeException e) {
      throw new LlmException(
        "LLM response has unexpected shape (missing choices[0].message.content)",
        truncate(responseBody));
    }
  }

  static String stripMarkdownFences(String content) {
    String trimmed = content.strip();
    if (trimmed.startsWith("```")) {
      int firstLineEnd = trimmed.indexOf('\n');
      if (firstLineEnd != -1) {
        trimmed = trimmed.substring(firstLineEnd + 1);
        int closing = trimmed.lastIndexOf("```");
        if (closing != -1) {
          trimmed = trimmed.substring(0, closing);
        }
      }
    }
    return trimmed.strip();
  }

  static Result parseResult(String json) throws LlmException {
    JSONObject obj;
    try {
      obj = new JSONObject(json);
    } catch (RuntimeException e) {
      throw new LlmException("LLM did not return valid JSON", truncate(json));
    }
    if (!obj.has("corrected") || !(obj.get("corrected") instanceof String)) {
      throw new LlmException("LLM JSON is missing the \"corrected\" field", truncate(json));
    }
    List<Comment> comments = new ArrayList<>();
    JSONArray rawComments = obj.optJSONArray("comments");
    if (rawComments != null) {
      for (int i = 0; i < rawComments.length(); i++) {
        JSONObject raw = rawComments.getJSONObject(i);
        comments.add(new Comment(raw.optInt("line", -1), raw.optString("explanation", "")));
      }
    }
    return new Result(obj.getString("corrected"), comments, json);
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LlmClientTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0` — BUILD SUCCESS. (The timeout test takes ~1s.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/LlmException.java \
        src/main/java/com/fitnesseai/plugin/LlmClient.java \
        src/test/java/com/fitnesseai/plugin/LlmClientTest.java
git commit -m "feat: LlmClient talks to OpenAI-compatible chat completions endpoint"
```

---

### Task 5: DiffService (hunks + comment attachment + merge contract)

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/DiffService.java`
- Test: `src/test/java/com/fitnesseai/plugin/DiffServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.fitnesseai.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.fitnesseai.plugin.DiffService.TYPE_ADDED;
import static com.fitnesseai.plugin.DiffService.TYPE_MODIFIED;
import static com.fitnesseai.plugin.DiffService.TYPE_REMOVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

  private final DiffService service = new DiffService();

  @Test
  void noChangesProducesNoHunks() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nb\nc", List.of());
    assertTrue(result.hunks().isEmpty());
    assertEquals(0, result.stats().added());
    assertEquals(0, result.stats().removed());
  }

  @Test
  void singleLineChangeIsModifiedHunk() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nB\nc", List.of());

    assertEquals(1, result.hunks().size());
    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(0, hunk.id());
    assertEquals(TYPE_MODIFIED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of("b"), hunk.originalLines());
    assertEquals(List.of("B"), hunk.correctedLines());
    assertEquals(1, result.stats().added());
    assertEquals(1, result.stats().removed());
  }

  @Test
  void insertionIsAddedHunk() {
    DiffService.Result result = service.compute("a\nc", "a\nb\nc", List.of());

    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(TYPE_ADDED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of(), hunk.originalLines());
    assertEquals(List.of("b"), hunk.correctedLines());
  }

  @Test
  void deletionIsRemovedHunk() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nc", List.of());

    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(TYPE_REMOVED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of("b"), hunk.originalLines());
    assertEquals(List.of(), hunk.correctedLines());
  }

  @Test
  void commentInsideCorrectedRangeIsAttached() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nB\nc",
      List.of(new LlmClient.Comment(2, "typo ici")));

    assertEquals("typo ici", result.hunks().get(0).comment());
  }

  @Test
  void commentOutsideAnyHunkIsIgnored() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nB\nc",
      List.of(new LlmClient.Comment(3, "hors hunk")));

    assertNull(result.hunks().get(0).comment());
  }

  @Test
  void trailingNewlineIsPreservedThroughMerge() {
    String original = "a\n";
    String corrected = "a\nb\n";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(corrected, merge(original, result.hunks(), Set.of(0)));
  }

  @Test
  void mergeWithAllHunksCheckedReconstructsCorrected() {
    String original = "one\ntwo\nthree\nfour\nfive";
    String corrected = "one\nTWO\nthree\nFOUR\nfive";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(corrected, merge(original, result.hunks(), Set.of(0, 1)));
  }

  @Test
  void mergeWithNoHunksCheckedKeepsOriginal() {
    String original = "one\ntwo\nthree";
    String corrected = "one\nTWO\nthree";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(original, merge(original, result.hunks(), Set.of()));
  }

  @Test
  void mergeWithPartialSelectionAppliesOnlyCheckedHunks() {
    String original = "a\nb\nc\nd";
    String corrected = "A\nb\nC\nd";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals("A\nb\nc\nd", merge(original, result.hunks(), Set.of(0)));
    assertEquals("a\nb\nC\nd", merge(original, result.hunks(), Set.of(1)));
  }

  @Test
  void hunksAreOrderedByPosition() {
    String original = "a\nb\nc\nd\ne";
    String corrected = "A\nb\nC\nd\nE";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(2, result.hunks().size());
    assertEquals(1, result.hunks().get(0).startLine());
    assertEquals(3, result.hunks().get(1).startLine());
  }

  /** Mirror of the merge algorithm implemented in ai-verify.js — keeps both sides in lockstep. */
  static String merge(String original, List<DiffService.Hunk> hunks, Set<Integer> checked) {
    List<String> lines = List.of(original.split("\n", -1));
    List<String> out = new ArrayList<>();
    int cursor = 0;
    for (DiffService.Hunk hunk : hunks) {
      int start = hunk.startLine() - 1;
      while (cursor < start) {
        out.add(lines.get(cursor));
        cursor++;
      }
      out.addAll(checked.contains(hunk.id()) ? hunk.correctedLines() : hunk.originalLines());
      cursor += hunk.originalLines().size();
    }
    while (cursor < lines.size()) {
      out.add(lines.get(cursor));
      cursor++;
    }
    return String.join("\n", out);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=DiffServiceTest`
Expected: COMPILATION ERROR — `DiffService` does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/fitnesseai/plugin/DiffService.java`:

```java
package com.fitnesseai.plugin;

import difflib.Chunk;
import difflib.Delta;
import difflib.DeltaType;
import difflib.DiffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DiffService {
  public static final String TYPE_ADDED = "added";
  public static final String TYPE_REMOVED = "removed";
  public static final String TYPE_MODIFIED = "modified";

  public record Hunk(int id, String type, int startLine, List<String> originalLines,
                     List<String> correctedLines, String comment) {}
  public record Stats(int added, int removed) {}
  public record Result(List<Hunk> hunks, Stats stats) {}

  public Result compute(String original, String corrected, List<LlmClient.Comment> comments) {
    List<String> originalLines = List.of(original.split("\n", -1));
    List<String> correctedLines = List.of(corrected.split("\n", -1));

    List<Delta<String>> deltas = new ArrayList<>(DiffUtils.diff(originalLines, correctedLines).getDeltas());
    deltas.sort(Comparator.comparingInt(d -> d.getSource().getPosition()));

    List<Hunk> hunks = new ArrayList<>();
    int added = 0;
    int removed = 0;
    int id = 0;
    for (Delta<String> delta : deltas) {
      if (delta.getType() == DeltaType.EQUAL) {
        continue;
      }
      Chunk<String> source = delta.getSource();
      Chunk<String> target = delta.getTarget();
      List<String> hunkOriginal = List.copyOf(source.getLines());
      List<String> hunkCorrected = List.copyOf(target.getLines());
      int startLine = source.getPosition() + 1;
      String type = switch (delta.getType()) {
        case INSERT -> TYPE_ADDED;
        case DELETE -> TYPE_REMOVED;
        default -> TYPE_MODIFIED;
      };
      added += hunkCorrected.size();
      removed += hunkOriginal.size();
      hunks.add(new Hunk(id++, type, startLine, hunkOriginal, hunkCorrected,
        findComment(comments, startLine, hunkCorrected.size())));
    }
    return new Result(hunks, new Stats(added, removed));
  }

  private static String findComment(List<LlmClient.Comment> comments, int startLine, int correctedCount) {
    if (comments == null || correctedCount == 0) {
      return null;
    }
    List<String> matches = new ArrayList<>();
    for (LlmClient.Comment comment : comments) {
      if (comment.line() >= startLine && comment.line() < startLine + correctedCount) {
        matches.add(comment.explanation());
      }
    }
    return matches.isEmpty() ? null : String.join("\n", matches);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=DiffServiceTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/DiffService.java \
        src/test/java/com/fitnesseai/plugin/DiffServiceTest.java
git commit -m "feat: DiffService computes selectable hunks and attaches LLM comments"
```

---

### Task 6: AiVerifyResponder (POST /?responder=ai-verify)

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyResponderTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
    String llmJson = new JSONObject()
      .put("choices", new JSONArray().put(new JSONObject()
        .put("message", new JSONObject()
          .put("content", new JSONObject()
            .put("corrected", correctedContent)
            .put("comments", new JSONArray().put(new JSONObject()
              .put("line", 2).put("explanation", "majuscule")))))))
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

  private int lastStatus;

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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=AiVerifyResponderTest`
Expected: test failures/errors — the `ai-verify` responder is not yet registered (`makeResponder` throws `InstantiationException`).

- [ ] **Step 3: Write the implementation**

`src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java`:

```java
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
        .put("error", "AI verify is not configured. Add these properties to plugins/plugins.properties: "
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
```

- [ ] **Step 4: Temporarily register the responder so the test can run**

Create `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java` (final version — Task 7 adds the edit override):

```java
package com.fitnesseai.plugin;

import fitnesse.plugins.PluginFeatureFactoryBase;
import fitnesse.responders.ResponderFactory;

public class AiVerifyPluginFactory extends PluginFeatureFactoryBase {
  @Override
  public void registerResponders(ResponderFactory responderFactory) {
    responderFactory.addResponder("ai-verify", AiVerifyResponder.class);
    LOG.info("FitNesse AI Verify plugin loaded (responder: ai-verify)");
  }
}
```

Create `src/main/resources/META-INF/services/fitnesse.plugins.PluginFeatureFactory` with exactly one line (no trailing spaces):

```
com.fitnesseai.plugin.AiVerifyPluginFactory
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=AiVerifyResponderTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` — BUILD SUCCESS.

Also run the full suite to make sure nothing regressed:
Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java \
        src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java \
        src/main/resources/META-INF/services/fitnesse.plugins.PluginFeatureFactory \
        src/test/java/com/fitnesseai/plugin/AiVerifyResponderTest.java
git commit -m "feat: ai-verify responder returns hunks JSON via ServiceLoader registration"
```

---

### Task 7: AiVerifyEditResponder (asset injection) + asset pipeline

**Files:**
- Create: `src/main/java/com/fitnesseai/plugin/AiVerifyEditResponder.java`
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java`
- Create: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` (placeholder — Task 8 fills it)
- Create: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css` (placeholder — Task 8 fills it)
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyEditResponderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.testutil.FitNesseUtil;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageUtil;
import fitnesse.wiki.PathParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVerifyEditResponderTest {

  private FitNesseContext context;

  @AfterEach
  void tearDown() throws IOException {
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private FitNesseContext makeContext() {
    return FitNesseUtil.makeTestContext(new Properties());
  }

  private static MockRequest editRequest() {
    MockRequest request = new MockRequest("TestPage");
    request.setRequestLine("GET /TestPage?edit HTTP/1.1");
    request.addInput("responder", "edit");
    return request;
  }

  @Test
  void pluginFactoryOverridesEditResponderAndAddsAiVerify() {
    context = makeContext();

    assertEquals(AiVerifyEditResponder.class, context.responderFactory.getResponderClass("edit"));
    assertEquals(AiVerifyResponder.class, context.responderFactory.getResponderClass("ai-verify"));
  }

  @Test
  void editPageHtmlContainsAssetTags() throws Exception {
    context = makeContext();
    WikiPage root = context.getRootPage();
    WikiPageUtil.addPage(root, PathParser.parse("TestPage"), "!CONTENTS");

    MockRequest request = editRequest();
    Responder responder = context.responderFactory.makeResponder(request);
    assertTrue(responder instanceof AiVerifyEditResponder);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    String html = new String(((SimpleResponse) response).getContentBytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.css"), "CSS tag injected");
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.js"), "JS tag injected");
    int linkIndex = html.indexOf("ai-verify.css");
    int bodyClose = html.lastIndexOf("</body>");
    assertTrue(linkIndex < bodyClose, "tags must be injected before </body>");
  }

  @Test
  void assetsAreServedFromClasspath() throws Exception {
    context = makeContext();

    MockRequest request = new MockRequest("files/fitnesse/ai-verify/ai-verify.js");
    request.setRequestLine("GET /files/fitnesse/ai-verify/ai-verify.js HTTP/1.1");
    Responder responder = context.responderFactory.makeResponder(request);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    SimpleResponse simple = (SimpleResponse) response;
    assertEquals(200, simple.getStatus());
    String body = new String(simple.getContentBytes(), StandardCharsets.UTF_8);
    assertNotNull(body);
    assertTrue(body.contains("Verify with AI"), "placeholder JS should mention the button label");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=AiVerifyEditResponderTest`
Expected: FAIL — `AiVerifyEditResponder` does not exist / assets not served.

- [ ] **Step 3: Write placeholder assets**

`src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`:

```js
/* FitNesse AI Verify — placeholder, replaced in Task 8 */
(function () {
  'use strict';
  // Verify with AI
})();
```

`src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`:

```css
/* FitNesse AI Verify — placeholder, replaced in Task 8 */
```

- [ ] **Step 4: Write AiVerifyEditResponder**

`src/main/java/com/fitnesseai/plugin/AiVerifyEditResponder.java`:

```java
package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.responders.editing.EditResponder;

import java.nio.charset.StandardCharsets;

public class AiVerifyEditResponder extends EditResponder {
  static final String CSS_PATH = "files/fitnesse/ai-verify/ai-verify.css";
  static final String JS_PATH = "files/fitnesse/ai-verify/ai-verify.js";

  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    Response response = super.makeResponse(context, request);
    if (response instanceof SimpleResponse simpleResponse && simpleResponse.getStatus() == 200) {
      String html = new String(simpleResponse.getContentBytes(), StandardCharsets.UTF_8);
      String contextRoot = context.contextRoot == null ? "/" : context.contextRoot;
      String injection = "<link rel=\"stylesheet\" href=\"" + contextRoot + CSS_PATH + "\" />\n"
        + "<script src=\"" + contextRoot + JS_PATH + "\"></script>\n";
      int bodyClose = html.lastIndexOf("</body>");
      if (bodyClose != -1) {
        html = html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        simpleResponse.setContent(html);
      }
    }
    return response;
  }
}
```

- [ ] **Step 5: Register the edit override in the factory**

Replace the whole content of `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java` with:

```java
package com.fitnesseai.plugin;

import fitnesse.plugins.PluginFeatureFactoryBase;
import fitnesse.responders.ResponderFactory;

public class AiVerifyPluginFactory extends PluginFeatureFactoryBase {
  @Override
  public void registerResponders(ResponderFactory responderFactory) {
    responderFactory.addResponder("ai-verify", AiVerifyResponder.class);
    responderFactory.addResponder("edit", AiVerifyEditResponder.class);
    LOG.info("FitNesse AI Verify plugin loaded (responders: ai-verify, edit override)");
  }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=AiVerifyEditResponderTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` — BUILD SUCCESS.

Run: `mvn -q test`
Expected: full suite green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyEditResponder.java \
        src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java \
        src/main/resources/fitnesse/resources/ai-verify/ai-verify.js \
        src/main/resources/fitnesse/resources/ai-verify/ai-verify.css \
        src/test/java/com/fitnesseai/plugin/AiVerifyEditResponderTest.java
git commit -m "feat: edit responder override injects plugin assets served from the JAR"
```

---

### Task 8: Client UI (ai-verify.js + ai-verify.css)

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` (replace placeholder)
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css` (replace placeholder)

There is no JS test runner in this project (by design — spec: checklist manuelle). The Java tests already lock the server contract and asset serving. The JS must implement exactly the contract the DiffService tests pinned down (see `DiffServiceTest.merge` — the JS `mergeContent` is its literal mirror). Manual verification is Task 9.

- [ ] **Step 1: Replace `ai-verify.js` with the full implementation**

  (All `escapeHtml` output goes through `textContent` → `innerHTML`; never concatenate raw page/LLM text into the diff HTML.)

`src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`:

```js
/* FitNesse AI Verify — button + diff modal for the wiki edit page. */
(function () {
  'use strict';

  var CONTEXT_LINES = 3;

  var state = {
    editor: null,
    contextRoot: '/',
    button: null,
    modal: null,
    sentContent: null,
    sentHash: null,
    hunks: [],
    checked: {},
    pageName: '',
    previouslyFocused: null
  };

  // Capture the Wysiwyg/CodeMirror editor. The edit page initializes it inside a
  // jQuery-ready closure; our script runs before DOMContentLoaded, so patching
  // Wysiwyg.initialize here catches the instance when the closure calls it.
  if (window.Wysiwyg && !window.Wysiwyg.__aiVerifyPatched) {
    var originalInitialize = window.Wysiwyg.initialize;
    window.Wysiwyg.initialize = function (textarea) {
      var editor = originalInitialize.apply(this, arguments);
      state.editor = editor || null;
      return editor;
    };
    window.Wysiwyg.__aiVerifyPatched = true;
  }

  function onReady(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  function detectContextRoot() {
    var link = document.querySelector('link[href*="ai-verify/ai-verify.css"]');
    if (!link) {
      return '/';
    }
    var href = link.getAttribute('href');
    var index = href.indexOf('files/fitnesse/ai-verify/');
    return index === -1 ? '/' : href.substring(0, index);
  }

  function currentPageName() {
    var path = window.location.pathname;
    var root = state.contextRoot.replace(/\/$/, '');
    var name = path;
    if (root && path.indexOf(root) === 0) {
      name = path.substring(root.length);
    }
    name = name.replace(/^\//, '');
    try {
      name = decodeURIComponent(name);
    } catch (e) { /* keep raw */ }
    return name;
  }

  function getPageContent() {
    var textarea = document.getElementById('pageContent');
    if (state.editor) {
      if (state.editor.activeEditor() === 'wysiwyg') {
        state.editor.loadWikiText();
      }
      return state.editor.codeMirrorEditor.getValue();
    }
    return textarea ? textarea.value : null;
  }

  function setPageContent(text) {
    var textarea = document.getElementById('pageContent');
    if (state.editor) {
      state.editor.codeMirrorEditor.setValue(text);
      state.editor.codeMirrorEditor.save();
      if (state.editor.activeEditor() === 'wysiwyg') {
        state.editor.loadWysiwygDocument();
      }
    } else if (textarea) {
      textarea.value = text;
    }
  }

  // cyrb53-style hash — detects edits between verification and Apply.
  function hashContent(text) {
    var h1 = 0xdeadbeef ^ text.length;
    var h2 = 0x41c6ce57 ^ text.length;
    for (var i = 0; i < text.length; i++) {
      var ch = text.charCodeAt(i);
      h1 = Math.imul(h1 ^ ch, 2654435761);
      h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
  }

  /* Mirror of DiffServiceTest.merge — keep in lockstep with DiffService. */
  function mergeContent(originalText, hunks, checkedIds) {
    var lines = originalText.split('\n');
    var out = [];
    var cursor = 0;
    for (var i = 0; i < hunks.length; i++) {
      var hunk = hunks[i];
      var start = hunk.startLine - 1;
      while (cursor < start) {
        out.push(lines[cursor]);
        cursor++;
      }
      var replacement = checkedIds.indexOf(hunk.id) !== -1 ? hunk.correctedLines : hunk.originalLines;
      for (var j = 0; j < replacement.length; j++) {
        out.push(replacement[j]);
      }
      cursor += hunk.originalLines.length;
    }
    while (cursor < lines.length) {
      out.push(lines[cursor]);
      cursor++;
    }
    return out.join('\n');
  }

  function setBusy(busy) {
    if (!state.button) {
      return;
    }
    state.button.disabled = busy;
    state.button.textContent = busy ? 'Vérification…' : 'Verify with AI';
  }

  function typeLabel(type) {
    if (type === 'added') {
      return 'ajouté';
    }
    if (type === 'removed') {
      return 'supprimé';
    }
    return 'modifié';
  }

  function ensureModal() {
    if (state.modal) {
      return state.modal;
    }
    var overlay = document.createElement('div');
    overlay.className = 'ai-verify-overlay';
    overlay.innerHTML = [
      '<div class="ai-verify-modal" role="dialog" aria-modal="true" aria-labelledby="ai-verify-title">',
      '  <div class="ai-verify-header">',
      '    <h2 id="ai-verify-title"></h2>',
      '    <span class="ai-verify-stats"></span>',
      '    <button type="button" class="ai-verify-close" aria-label="Fermer">&#10005;</button>',
      '  </div>',
      '  <div class="ai-verify-diff"></div>',
      '  <div class="ai-verify-message" hidden></div>',
      '  <div class="ai-verify-error" hidden><pre class="ai-verify-error-text"></pre></div>',
      '  <div class="ai-verify-footer">',
      '    <button type="button" class="ai-verify-apply">Apply (0)</button>',
      '    <button type="button" class="ai-verify-select-all">Tout sélectionner</button>',
      '    <button type="button" class="ai-verify-select-none">Tout désélectionner</button>',
      '    <button type="button" class="ai-verify-retry" hidden>Réessayer</button>',
      '    <button type="button" class="ai-verify-cancel">Annuler</button>',
      '  </div>',
      '</div>'
    ].join('');
    document.body.appendChild(overlay);
    state.modal = overlay;

    overlay.addEventListener('mousedown', function (e) {
      if (e.target === overlay) {
        closeModal();
      }
    });
    overlay.querySelector('.ai-verify-close').addEventListener('click', closeModal);
    overlay.querySelector('.ai-verify-cancel').addEventListener('click', closeModal);
    overlay.querySelector('.ai-verify-apply').addEventListener('click', applySelections);
    overlay.querySelector('.ai-verify-select-all').addEventListener('click', function () {
      setAllChecked(true);
    });
    overlay.querySelector('.ai-verify-select-none').addEventListener('click', function () {
      setAllChecked(false);
    });
    overlay.querySelector('.ai-verify-retry').addEventListener('click', function () {
      closeModal();
      verifyNow();
    });
    overlay.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        closeModal();
        return;
      }
      if (e.key === 'Tab') {
        trapFocus(e, overlay);
      }
    });
    return overlay;
  }

  function trapFocus(e, overlay) {
    var focusables = overlay.querySelectorAll('button:not([hidden]), input, [href], [tabindex]:not([tabindex="-1"])');
    var visible = [];
    for (var i = 0; i < focusables.length; i++) {
      if (focusables[i].offsetParent !== null) {
        visible.push(focusables[i]);
      }
    }
    if (visible.length === 0) {
      return;
    }
    var first = visible[0];
    var last = visible[visible.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }

  function openModal(pageName, data) {
    var overlay = ensureModal();
    state.hunks = data.hunks;
    state.checked = {};
    for (var i = 0; i < data.hunks.length; i++) {
      state.checked[data.hunks[i].id] = true;
    }
    state.pageName = pageName;

    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + pageName;
    overlay.querySelector('.ai-verify-stats').textContent =
      '+' + data.stats.added + ' / −' + data.stats.removed + ' · ' + data.hunks.length + ' correction(s)';
    overlay.querySelector('.ai-verify-message').hidden = true;
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('.ai-verify-apply').hidden = false;
    overlay.querySelector('.ai-verify-select-all').hidden = false;
    overlay.querySelector('.ai-verify-select-none').hidden = false;

    renderDiff(state.sentContent, data.hunks);
    updateApplyButton();

    state.previouslyFocused = document.activeElement;
    overlay.style.display = 'flex';
    overlay.querySelector('.ai-verify-close').focus();
  }

  function showMessageModal(message) {
    var overlay = ensureModal();
    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + state.pageName;
    overlay.querySelector('.ai-verify-stats').textContent = '';
    var diff = overlay.querySelector('.ai-verify-diff');
    diff.innerHTML = '';
    diff.hidden = true;
    var msg = overlay.querySelector('.ai-verify-message');
    msg.textContent = message;
    msg.hidden = false;
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('.ai-verify-apply').hidden = true;
    overlay.querySelector('.ai-verify-select-all').hidden = true;
    overlay.querySelector('.ai-verify-select-none').hidden = true;
    state.previouslyFocused = document.activeElement;
    overlay.style.display = 'flex';
    overlay.querySelector('.ai-verify-close').focus();
  }

  function showErrorModal(message, details) {
    var overlay = ensureModal();
    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + state.pageName;
    overlay.querySelector('.ai-verify-stats').textContent = '';
    overlay.querySelector('.ai-verify-diff').hidden = true;
    overlay.querySelector('.ai-verify-message').hidden = true;
    var error = overlay.querySelector('.ai-verify-error');
    var text = overlay.querySelector('.ai-verify-error-text');
    if (details) {
      text.textContent = message + '\n\n' + details;
    } else {
      text.textContent = message;
    }
    error.hidden = false;
    overlay.querySelector('.ai-verify-retry').hidden = false;
    overlay.querySelector('.ai-verify-apply').hidden = true;
    overlay.querySelector('.ai-verify-select-all').hidden = true;
    overlay.querySelector('.ai-verify-select-none').hidden = true;
    if (!overlay.style.display || overlay.style.display === 'none') {
      state.previouslyFocused = document.activeElement;
      overlay.style.display = 'flex';
      overlay.querySelector('.ai-verify-close').focus();
    }
  }

  function closeModal() {
    if (!state.modal) {
      return;
    }
    state.modal.style.display = 'none';
    if (state.previouslyFocused && state.previouslyFocused.focus) {
      state.previouslyFocused.focus();
    }
  }

  function cellLines(lines, side, type) {
    if (lines.length === 0) {
      return '<div class="ai-verify-line ai-verify-line-empty">(aucune ligne)</div>';
    }
    var html = '';
    for (var i = 0; i < lines.length; i++) {
      var cls = 'ai-verify-line';
      if (side === 'original' && type !== 'added') {
        cls += ' ai-verify-line-removed';
      }
      if (side === 'corrected' && type !== 'removed') {
        cls += ' ai-verify-line-added';
      }
      html += '<div class="' + cls + '">' + escapeHtml(lines[i]) + '</div>';
    }
    return html;
  }

  function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  function renderDiff(originalText, hunks) {
    var lines = originalText.split('\n');
    var diff = state.modal.querySelector('.ai-verify-diff');
    diff.hidden = false;
    var html = ['<div class="ai-verify-colhead"><span>Original</span><span>Proposition</span></div>'];
    var cursor = 0;

    for (var i = 0; i < hunks.length; i++) {
      var hunk = hunks[i];
      var start = hunk.startLine - 1;
      var gap = start - cursor;
      var ctxCount = Math.min(CONTEXT_LINES, Math.max(0, gap));
      if (gap - ctxCount > 0) {
        html.push('<div class="ai-verify-skipped">… ' + (gap - ctxCount) + ' ligne(s) inchangée(s) masquée(s) …</div>');
      }
      if (ctxCount > 0) {
        var before = lines.slice(start - ctxCount, start);
        html.push('<div class="ai-verify-ctx-row">'
          + '<div class="ai-verify-cell">' + contextHtml(before) + '</div>'
          + '<div class="ai-verify-cell">' + contextHtml(before) + '</div>'
          + '</div>');
      }

      var checkedAttr = ' checked';
      html.push('<div class="ai-verify-hunk-row" data-hunk-row="' + hunk.id + '">');
      html.push('<div class="ai-verify-cell ai-verify-cell-original">'
        + '<div class="ai-verify-hunk-head">Hunk ' + (hunk.id + 1) + ' — ' + typeLabel(hunk.type) + '</div>'
        + cellLines(hunk.originalLines, 'original', hunk.type)
        + '</div>');
      html.push('<div class="ai-verify-cell ai-verify-cell-corrected">'
        + '<label class="ai-verify-hunk-head">'
        + '<input type="checkbox" class="ai-verify-hunk-check" data-hunk-id="' + hunk.id + '"' + checkedAttr + ' />'
        + ' Hunk ' + (hunk.id + 1) + ' — ' + typeLabel(hunk.type) + '</label>'
        + cellLines(hunk.correctedLines, 'corrected', hunk.type)
        + (hunk.comment ? '<div class="ai-verify-comment">' + escapeHtml(hunk.comment) + '</div>' : '')
        + '</div>');
      html.push('</div>');

      cursor = start + hunk.originalLines.length;
    }

    var trailingCount = Math.min(CONTEXT_LINES, lines.length - cursor);
    var trailingHidden = (lines.length - cursor) - trailingCount;
    if (trailingHidden > 0) {
      html.push('<div class="ai-verify-skipped">… ' + trailingHidden + ' ligne(s) inchangée(s) masquée(s) …</div>');
    }
    if (trailingCount > 0) {
      var trailing = lines.slice(cursor, cursor + trailingCount);
      html.push('<div class="ai-verify-ctx-row">'
        + '<div class="ai-verify-cell">' + contextHtml(trailing) + '</div>'
        + '<div class="ai-verify-cell">' + contextHtml(trailing) + '</div>'
        + '</div>');
    }

    diff.innerHTML = html.join('');

    var checks = diff.querySelectorAll('.ai-verify-hunk-check');
    for (var c = 0; c < checks.length; c++) {
      checks[c].addEventListener('change', onCheckChanged);
    }
  }

  function contextHtml(lines) {
    var html = '';
    for (var i = 0; i < lines.length; i++) {
      html += '<div class="ai-verify-line ai-verify-line-context">' + escapeHtml(lines[i]) + '</div>';
    }
    return html;
  }

  function onCheckChanged(e) {
    var id = parseInt(e.target.getAttribute('data-hunk-id'), 10);
    state.checked[id] = e.target.checked;
    var row = state.modal.querySelector('[data-hunk-row="' + id + '"]');
    if (row) {
      row.classList.toggle('ai-verify-hunk-unchecked', !e.target.checked);
    }
    updateApplyButton();
  }

  function setAllChecked(checked) {
    var checks = state.modal.querySelectorAll('.ai-verify-hunk-check');
    for (var i = 0; i < checks.length; i++) {
      checks[i].checked = checked;
      var id = parseInt(checks[i].getAttribute('data-hunk-id'), 10);
      state.checked[id] = checked;
      var row = state.modal.querySelector('[data-hunk-row="' + id + '"]');
      if (row) {
        row.classList.toggle('ai-verify-hunk-unchecked', !checked);
      }
    }
    updateApplyButton();
  }

  function selectedIds() {
    var ids = [];
    for (var id in state.checked) {
      if (state.checked.hasOwnProperty(id) && state.checked[id]) {
        ids.push(parseInt(id, 10));
      }
    }
    ids.sort(function (a, b) { return a - b; });
    return ids;
  }

  function updateApplyButton() {
    var apply = state.modal.querySelector('.ai-verify-apply');
    var count = selectedIds().length;
    apply.textContent = 'Apply (' + count + ')';
    apply.disabled = count === 0;
  }

  function applySelections() {
    if (hashContent(getPageContent()) !== state.sentHash) {
      showErrorModal('Le contenu de la page a changé depuis la vérification. ' +
        'Fermez ce modal et relancez « Verify with AI ».', null);
      return;
    }
    var merged = mergeContent(state.sentContent, state.hunks, selectedIds());
    setPageContent(merged);
    closeModal();
  }

  function verifyNow() {
    var content = getPageContent();
    if (content === null) {
      return;
    }
    var pageName = currentPageName();
    state.sentContent = content;
    state.sentHash = hashContent(content);
    state.pageName = pageName;
    setBusy(true);

    var form = 'responder=ai-verify&payload=' +
      encodeURIComponent(JSON.stringify({ pageName: pageName, content: content }));

    fetch(window.location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form
    }).then(function (resp) {
      return resp.text().then(function (text) {
        setBusy(false);
        var data = null;
        try {
          data = JSON.parse(text);
        } catch (e) { /* not JSON */ }
        if (!resp.ok) {
          showErrorModal(data && data.error ? data.error : ('Erreur HTTP ' + resp.status),
            data && data.details ? data.details : null);
          return;
        }
        if (!data || !Array.isArray(data.hunks)) {
          showErrorModal('Réponse inattendue du serveur.', text.substring(0, 500));
          return;
        }
        if (data.hunks.length === 0) {
          showMessageModal('Aucune correction proposée.');
          return;
        }
        openModal(pageName, data);
      });
    }).catch(function (err) {
      setBusy(false);
      showErrorModal('Impossible de contacter le serveur FitNesse : ' + err.message, null);
    });
  }

  onReady(function () {
    if (!document.getElementById('pageContent')) {
      return;
    }
    // Only on edit pages of existing pages (Wysiwyg sets responder=saveData).
    if (!document.querySelector('form input[name="responder"][value="saveData"]')) {
      return;
    }
    var saveButtons = document.getElementById('save_buttons');
    if (!saveButtons) {
      return;
    }
    state.contextRoot = detectContextRoot();

    var button = document.createElement('button');
    button.type = 'button';
    button.id = 'ai-verify-button';
    button.className = 'button ai-verify-button';
    button.textContent = 'Verify with AI';
    saveButtons.appendChild(button);
    state.button = button;
    button.addEventListener('click', verifyNow);
  });
})();
```

- [ ] **Step 2: Replace `ai-verify.css` with the full implementation**

`src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`:

```css
/* FitNesse AI Verify */

.ai-verify-button {
  margin-left: 0.5em;
}

.ai-verify-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 10000;
  align-items: flex-start;
  justify-content: center;
  padding: 3vh 2vw;
  box-sizing: border-box;
}

.ai-verify-modal {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.35);
  width: 92%;
  max-width: 1400px;
  max-height: 94vh;
  display: flex;
  flex-direction: column;
  font-family: sans-serif;
  font-size: 13px;
  color: #222;
}

.ai-verify-header {
  display: flex;
  align-items: center;
  gap: 1em;
  padding: 0.75em 1em;
  border-bottom: 1px solid #ddd;
}

.ai-verify-header h2 {
  margin: 0;
  font-size: 1.1em;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-verify-stats {
  color: #555;
  white-space: nowrap;
}

.ai-verify-close {
  border: none;
  background: none;
  font-size: 1.2em;
  cursor: pointer;
  line-height: 1;
  padding: 0.2em 0.4em;
}

.ai-verify-diff {
  overflow: auto;
  flex: 1;
  border-bottom: 1px solid #ddd;
}

.ai-verify-colhead,
.ai-verify-hunk-row,
.ai-verify-ctx-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
}

.ai-verify-colhead {
  position: sticky;
  top: 0;
  background: #f5f5f5;
  border-bottom: 1px solid #ddd;
  font-weight: bold;
  padding: 0.4em 0;
}

.ai-verify-colhead span {
  padding: 0 1em;
}

.ai-verify-cell {
  min-width: 0;
  border-right: 1px solid #eee;
  padding: 0.25em 0;
  overflow-x: auto;
}

.ai-verify-cell:last-child {
  border-right: none;
}

.ai-verify-line {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  padding: 0 1em;
  white-space: pre;
}

.ai-verify-line-context {
  color: #999;
}

.ai-verify-line-removed {
  background: #ffe0e0;
}

.ai-verify-line-added {
  background: #e0f7e0;
}

.ai-verify-line-empty {
  color: #bbb;
  font-style: italic;
}

.ai-verify-skipped {
  text-align: center;
  color: #888;
  background: #fafafa;
  border-top: 1px dashed #ddd;
  border-bottom: 1px dashed #ddd;
  padding: 0.3em;
  font-size: 11px;
}

.ai-verify-hunk-head {
  display: block;
  background: #eef3fa;
  border-top: 1px solid #d0d8e4;
  border-bottom: 1px solid #d0d8e4;
  padding: 0.3em 1em;
  font-weight: bold;
  color: #345;
}

.ai-verify-hunk-unchecked .ai-verify-hunk-head {
  background: #f5f5f5;
  color: #999;
}

.ai-verify-hunk-unchecked .ai-verify-line-added,
.ai-verify-hunk-unchecked .ai-verify-line-removed {
  background: #f7f7f7;
  text-decoration: line-through;
  color: #aaa;
}

.ai-verify-comment {
  margin: 0.4em 1em;
  padding: 0.4em 0.6em;
  background: #fff8dc;
  border-left: 3px solid #e0c060;
  font-size: 12px;
  white-space: pre-wrap;
}

.ai-verify-message {
  padding: 2em 1em;
  text-align: center;
  color: #555;
}

.ai-verify-error {
  padding: 1em;
}

.ai-verify-error-text {
  background: #fff0f0;
  border: 1px solid #e0b0b0;
  padding: 0.75em;
  white-space: pre-wrap;
  font-size: 12px;
  max-height: 30vh;
  overflow: auto;
}

.ai-verify-footer {
  display: flex;
  gap: 0.5em;
  padding: 0.75em 1em;
  border-top: 1px solid #ddd;
  flex-wrap: wrap;
}

.ai-verify-footer button {
  padding: 0.4em 1em;
  cursor: pointer;
  border: 1px solid #bbb;
  border-radius: 4px;
  background: #f5f5f5;
}

.ai-verify-apply {
  background: #2f6f2f !important;
  border-color: #2f6f2f !important;
  color: #fff;
  font-weight: bold;
}

.ai-verify-apply:disabled {
  background: #999 !important;
  border-color: #999 !important;
  cursor: default;
}

@media (max-width: 800px) {
  .ai-verify-colhead,
  .ai-verify-hunk-row,
  .ai-verify-ctx-row {
    grid-template-columns: 1fr;
  }

  .ai-verify-cell {
    border-right: none;
    border-bottom: 1px solid #eee;
  }
}
```

- [ ] **Step 3: Verify the Java tests still pass and assets are packaged**

Run: `mvn -q test`
Expected: BUILD SUCCESS (all tests).

Run: `mvn -q package && unzip -l target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar | grep ai-verify`
Expected: entries for `fitnesse/resources/ai-verify/ai-verify.js`, `fitnesse/resources/ai-verify/ai-verify.css`, `ai-verify-prompt.txt`, and `META-INF/services/fitnesse.plugins.PluginFeatureFactory`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js \
        src/main/resources/fitnesse/resources/ai-verify/ai-verify.css
git commit -m "feat: client UI with button, diff modal, hunk selection and merge"
```

---

### Task 9: README + packaging verification + manual smoke test

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README**

`README.md`:

```
# FitNesse AI Verify Plugin

Plugin FitNesse qui ajoute un bouton **Verify with AI** à la page d'édition wiki :
le contenu du textarea est envoyé à un LLM compatible OpenAI, les corrections
proposées sont affichées en diff sélectionnable par hunk, et Appliquer met à jour
le textarea (sauvegarde manuelle habituelle ensuite).

## Prérequis

- FitNesse standalone (testé avec 20251025, devrait fonctionner avec toute version récente ≥ 2023)
- Java 21+

## Installation

1. Build :

   ```bash
   mvn package
   ```

   Le JAR produit est `target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`.

2. Copier le JAR dans le répertoire `plugins/` de votre instance FitNesse standalone :

   ```bash
   cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar <dossier-fitnesse>/plugins/
   ```

3. Configurer `plugins/plugins.properties` :

   ```properties
   llm.base.url=http://localhost:11434/v1
   llm.api.key=
   llm.model=llama3.1
   ```

   Propriétés :

   | Propriété            | Obligatoire | Défaut | Description                                              |
   |----------------------|-------------|--------|----------------------------------------------------------|
   | `llm.base.url`       | oui         | —      | URL de base de l'API compatible OpenAI (`.../v1`)        |
   | `llm.model`          | oui         | —      | Nom du modèle                                            |
   | `llm.api.key`        | non         | vide   | Bearer token (vide pour les endpoints locaux)            |
   | `llm.max.tokens`     | non         | 4096   | `max_tokens` de la requête                                |
   | `llm.timeout.seconds`| non         | 120    | Timeout HTTP                                             |
   | `llm.prompt`         | non         | embarqué | Texte inline remplaçant le prompt système par défaut   |

4. Redémarrer FitNesse.

Aucune propriété de registration n'est nécessaire : le plugin se découvre via
ServiceLoader (META-INF/services dans le JAR).

## Utilisation

1. Ouvrir une page existante en édition (`?edit`).
2. Cliquer **Verify with AI** (à côté de Save). Le contenu courant du textarea est
   analysé, modifications non sauvegardées comprises.
3. Dans le modal : gauche = original, droite = proposition ; checkbox par hunk
   (cochés par défaut) ; commentaires du LLM sous le hunk concerné.
4. **Apply (n)** écrit le contenu fusionné dans le textarea — la sauvegarde reste
   manuelle (bouton Save).
5. Si le textarea a changé entre la vérification et Apply, l'application est refusée
   (relancer la vérification).

## Limitations

- Bouton disponible uniquement sur les pages existantes en mode édition (`?edit`),
  pas sur la création de page (`?new`).
- Le LLM doit respecter le format JSON attendu ; en cas de réponse non conforme,
  le message d'erreur inclut le texte brut (repliable dans le modal).

## Checklist de validation manuelle (JS)

Pas de test automatisé JS dans ce projet. Après `mvn package` + installation :

- [ ] Ouvrir `http://localhost:<port>/<Page>?edit` : le bouton « Verify with AI »
      apparaît dans `#save_buttons` ; les onglets Réseau montrent un GET 200 sur
      `.../files/fitnesse/ai-verify/ai-verify.js` et `...ai-verify.css`.
- [ ] Sur une page non existante ou `?new` : pas de bouton.
- [ ] Sans config LLM (`llm.*` absentes) : clic → message listant `llm.base.url`,
      `llm.model`.
- [ ] Avec un endpoint LLM valide : clic → « Vérification… » puis modal avec hunks ;
      décocher un hunk met Apply à jour le compteur ; Apply met à jour le textarea
      (et l'éditeur WYSIWYG si actif) sans sauvegarder.
- [ ] Aucune différence → « Aucune correction proposée. »
- [ ] LLM en erreur (mauvaise URL) → bandeau d'erreur + bouton Réessayer.
- [ ] Modifier le textarea pendant que le modal est ouvert, puis Apply → refus
      avec message explicite.
- [ ] Échap / clic hors modal / ✕ ferment le modal ; le focus revient au bouton.
- [ ] Écran étroit (< 800px) : les colonnes du diff s'empilent.
- [ ] Mode WYSIWYG actif : après Apply, basculer en mode texte montre le contenu
      appliqué (pas d'écrasement par l'ancien contenu iframe).
```

- [ ] **Step 2: Full build + inspect the final JAR**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS, all tests green.

Run: `unzip -l target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar | grep -E "ai-verify|PluginFeatureFactory|json/|difflib/"`
Expected: our `ai-verify` resources + `META-INF/services/fitnesse.plugins.PluginFeatureFactory` present; **no** `org/json/` or `difflib/` classes (provided scope correctly excluded).

- [ ] **Step 3: Manual smoke test against real FitNesse standalone (error path — no LLM needed)**

```bash
mkdir -p /tmp/fitnesse-smoke/plugins
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
curl -L -o /tmp/fitnesse-smoke/fitnesse.jar \
  https://github.com/unclebob/fitnesse/releases/download/v20251025/fitnesse-20251025-standalone.jar
printf 'llm.base.url=http://localhost:1/v1\nllm.model=smoke\n' \
  > /tmp/fitnesse-smoke/plugins/plugins.properties
cd /tmp/fitnesse-smoke && java -jar fitnesse.jar -p 8090
```

Then in a browser:
- [ ] `http://localhost:8090/FrontPage?edit` → button present, assets load (200).
- [ ] Click button → modal with error (connection refused to port 1) + Réessayer works.
- [ ] `http://localhost:8090/files/fitnesse/ai-verify/ai-verify.js` → JS served directly.
- [ ] Stop FitNesse (`http://localhost:8090/ShutDown` or Ctrl-C).

If an OpenAI-compatible endpoint is available (Ollama, LM Studio…), also run the full happy path checklist from the README (hunk selection, Apply, WYSIWYG round-trip, conflict refusal).

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: README with installation, usage and manual validation checklist"
```

---

## Plan amendments vs spec (already applied in Task 1)

1. **Registration:** spec said `PluginFeatureFactory.Impl=...` in `plugins.properties` — that property does not exist in FitNesse. Correct mechanism: ServiceLoader via `META-INF/services/fitnesse.plugins.PluginFeatureFactory`. Zero registration config.
2. **Hunk JSON:** spec's `{id, type, lines, comment}` was insufficient for client-side reconstruction; enriched to `{id, type, startLine, originalLines, correctedLines, comment}`.
3. **Request format:** spec said JSON body; FitNesse parses every non-multipart body as form data, which corrupts a JSON body containing `&`/`+` and can break responder dispatch. Final: form-encoded `responder=ai-verify&payload=<urlencoded JSON>`.
4. **No jsdiff in JS:** diff is computed server-side (java-diff-utils); the JS only merges selected hunks (algorithm pinned by `DiffServiceTest.merge`).
5. **Modal layout:** spec said two independently scrolling columns with synced scroll; implemented as a single CSS-grid diff area with paired cells (same visual result, no scroll-sync bugs), stacked on narrow screens.
6. **FitNesse dependency:** `org.fitnesse:fitnesse:20250223` (Maven Central latest); smoke test uses GitHub release 20251025.
7. **Config reload:** FitNesse loads `plugins.properties` at boot into the context properties — config changes require a restart (the plugin itself does no caching and re-reads `context.getProperties()` per request).
