# Performance Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix performance issues: shared HttpClient singleton, connection pooling, diff rendering optimization, and page type caching.

**Architecture:** 
- Single shared `HttpClient` instance created at plugin startup, injected into `LlmClient`.
- Connection pool configured with reasonable limits (max 20 connections, 5 min TTL).
- Diff rendering uses `DocumentFragment` for large outputs to avoid giant string concatenation.
- Page type resolution cached with 1-minute TTL to avoid repeated wiki lookups.

**Tech Stack:** Java 17+, `java.net.http.HttpClient`, FitNesse plugin API, Maven, JUnit 5.

---

## File Structure

- Modify: `src/main/java/com/fitnesseai/plugin/LlmClient.java` — singleton HttpClient, connection pool config
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java` — add config for connection pool settings
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java` — create shared HttpClient at startup
- Modify: `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java` — add page type cache
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` — diff rendering with DocumentFragment
- Test: `src/test/java/com/fitnesseai/plugin/LlmClientTest.java` — verify HttpClient reuse
- Test: `src/test/java/com/fitnesseai/plugin/AiPromptResponderTest.java` — verify page type cache

---

### Task 1: Add HttpClient configuration to AiVerifyConfig

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java`

- [ ] **Step 1: Add config constants and getters**

Add to `AiVerifyConfig.java` after line 28 (after `DEBUG` constant):

```java
  public static final String HTTP_MAX_CONNECTIONS = "llm.http.max.connections";
  public static final String HTTP_CONNECTION_TTL_SECONDS = "llm.http.connection.ttl.seconds";
  
  public static final int DEFAULT_HTTP_MAX_CONNECTIONS = 20;
  public static final int DEFAULT_HTTP_CONNECTION_TTL_SECONDS = 300;
```

Add getter methods after `timeoutSeconds()` (after line 76):

```java
  public int httpMaxConnections() {
    return intOr(HTTP_MAX_CONNECTIONS, DEFAULT_HTTP_MAX_CONNECTIONS);
  }

  public int httpConnectionTtlSeconds() {
    return intOr(HTTP_CONNECTION_TTL_SECONDS, DEFAULT_HTTP_CONNECTION_TTL_SECONDS);
  }
```

- [ ] **Step 2: Verify compilation**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java
git commit -m "feat: add HTTP connection pool configuration properties"
```

---

### Task 2: Create shared HttpClient in AiVerifyPluginFactory

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java`
- Modify: `src/main/java/com/fitnesseai/plugin/LlmClient.java`

- [ ] **Step 1: Read current AiVerifyPluginFactory**

Run: `cat src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java`

- [ ] **Step 2: Add HttpClient creation and LlmClient injection**

Modify `AiVerifyPluginFactory.java` to create a shared `HttpClient` with connection pooling and pass it to responders. The factory should:
- Create `HttpClient` once with connection pool config from `AiVerifyConfig`
- Store it as a field
- Pass it to `LlmClient` constructors

Add imports:
```java
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
```

Add field and initialization in `createResponders()` or a new `init()` method:
```java
  private HttpClient sharedHttpClient;
  
  private HttpClient createSharedHttpClient(Properties props) {
    AiVerifyConfig config = new AiVerifyConfig(props);
    return HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(config.httpMaxConnections()))
        .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
        .version(HttpClient.Version.HTTP_2)
        .build(); // Connection pooling is automatic with shared executor
  }
```

Modify responder creation to inject the shared client:
```java
  // In createResponders():
  if (sharedHttpClient == null) {
    sharedHttpClient = createSharedHttpClient(context.getProperties());
  }
  LlmClient llmClient = new LlmClient(sharedHttpClient);
  // Pass llmClient to responders that need it
```

- [ ] **Step 3: Modify LlmClient to accept HttpClient**

In `LlmClient.java`:
- Remove `HttpClient.newHttpClient()` from no-arg constructor
- Keep the package-private `LlmClient(HttpClient)` constructor
- Make the no-arg constructor delegate to a default shared instance (for backward compatibility in tests)

```java
  private static final HttpClient DEFAULT_CLIENT = HttpClient.newHttpClient();
  
  public LlmClient() {
    this(DEFAULT_CLIENT);
  }
  
  LlmClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }
```

- [ ] **Step 4: Verify compilation and tests**

Run: `mvn -q test`
Expected: 71 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyPluginFactory.java src/main/java/com/fitnesseai/plugin/LlmClient.java
git commit -m "perf: shared HttpClient with connection pooling via AiVerifyPluginFactory"
```

---

### Task 3: Add page type caching to AiPromptResponder

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiPromptResponder.java`

- [ ] **Step 1: Add cache field and TTL constant**

After line 19 (Logger), add:
```java
  private static final Duration PAGE_TYPE_CACHE_TTL = Duration.ofMinutes(1);
  private static final ConcurrentHashMap<String, Pair<String, Instant>> pageTypeCache = new ConcurrentHashMap<>();
```

Add import: `import java.time.*; import java.util.concurrent.*;`

- [ ] **Step 2: Modify resolvePageType to use cache**

Replace `resolvePageType()` method (lines 75-85) with:

```java
  private static String resolvePageType(FitNesseContext context, String pageName) {
    if (pageName == null || pageName.isBlank()) {
      return "";
    }
    var cached = pageTypeCache.get(pageName);
    if (cached != null && !cached.getValue().isBefore(Instant.now())) {
      return cached.getKey();
    }
    try {
      WikiPage page = context.getRootPage().getPageCrawler().getPage(PathParser.parse(pageName));
      String type = page == null ? "" : PageType.fromWikiPage(page).toString();
      pageTypeCache.put(pageName, Pair.of(type, Instant.now().plus(PAGE_TYPE_CACHE_TTL)));
      return type;
    } catch (RuntimeException e) {
      return "";
    }
  }
```

Add import: `import java.util.AbstractMap.SimpleEntry as Pair;` or create a simple record.

- [ ] **Step 3: Verify compilation and tests**

Run: `mvn -q test`
Expected: 71 tests pass

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiPromptResponder.java
git commit -m "perf: add 1-minute TTL cache for page type resolution"
```

---

### Task 4: Optimize diff rendering with DocumentFragment

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`

- [ ] **Step 1: Locate renderDiff function**

Lines 385-442. Current implementation builds a giant HTML string.

- [ ] **Step 2: Replace with DocumentFragment approach**

Replace `renderDiff()` (lines 385-442) with:

```javascript
  function renderDiff(originalText, hunks) {
    var rows = alignRows(originalText, hunks);
    var diff = state.modal.querySelector('.ai-verify-diff');
    diff.hidden = false;

    var fragment = document.createDocumentFragment();
    var table = document.createElement('table');
    table.className = 'ai-verify-table';
    fragment.appendChild(table);

    var thead = document.createElement('thead');
    thead.innerHTML = '<tr><th class="ai-verify-gutter-sel"></th><th class="ai-verify-gutter">#</th>'
      + '<th class="ai-verify-text">Original</th><th class="ai-verify-gutter">#</th>'
      + '<th class="ai-verify-text">Corrected</th></tr>';
    table.appendChild(thead);

    var tbody = document.createElement('tbody');
    table.appendChild(tbody);

    var i = 0;
    while (i < rows.length) {
      var row = rows[i];
      var tr = document.createElement('tr');
      if (row.hunkId === null) {
        tr.className = 'ai-verify-row-unchanged';
        tr.innerHTML = '<td class="ai-verify-gutter-sel"></td>'
          + gutter(row.origNo)
          + '<td class="ai-verify-text">' + escapeHtml(row.origText) + '</td>'
          + gutter(row.corrNo)
          + '<td class="ai-verify-text">' + escapeHtml(row.corrText) + '</td>';
        tbody.appendChild(tr);
        i++;
      } else {
        var hunkId = row.hunkId;
        var start = i;
        while (i < rows.length && rows[i].hunkId === hunkId) {
          i++;
        }
        var count = i - start;
        for (var k = start; k < i; k++) {
          var r = rows[k];
          var tr2 = document.createElement('tr');
          tr2.className = 'ai-verify-row-modified';
          tr2.setAttribute('data-hunk-id', hunkId);
          var selCell = (k === start)
            ? '<td class="ai-verify-gutter-sel" rowspan="' + count + '">'
              + '<input type="checkbox" class="ai-verify-hunk-check" data-hunk-id="' + hunkId + '" aria-label="Correction ' + (hunkId + 1) + '" checked /></td>'
            : '';
          var leftClass = r.type === 'added' ? '' : ' ai-verify-side-removed';
          var rightClass = r.type === 'removed' ? '' : ' ai-verify-side-added';
          var title = r.comment ? ' title="' + escapeHtml(r.comment).replace(/"/g, '"') + '"' : '';
          tr2.innerHTML = selCell
            + gutter(r.origNo)
            + '<td class="ai-verify-text' + leftClass + '"' + (r.origText !== null ? title : '') + '>'
            + (r.origText !== null ? escapeHtml(r.origText) : '') + '</td>'
            + gutter(r.corrNo)
            + '<td class="ai-verify-text' + rightClass + '"' + (r.corrText !== null ? title : '') + '>'
            + (r.corrText !== null ? escapeHtml(r.corrText) : '') + '</td>';
          tbody.appendChild(tr2);
        }
      }
    }
    diff.innerHTML = '';
    diff.appendChild(fragment);

    var checks = diff.querySelectorAll('.ai-verify-hunk-check');
    for (var c = 0; c < checks.length; c++) {
      checks[c].addEventListener('change', onCheckChanged);
    }
  }
```

- [ ] **Step 3: Verify syntax**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: exit 0

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "perf: diff rendering uses DocumentFragment for large pages"
```

---

### Task 5: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `mvn -q test`
Expected: `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 2: Build and deploy to smoke**

```bash
mvn -q -DskipTests package
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
lsof -ti tcp:8091 | xargs kill; sleep 2
(cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &)
sleep 8
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8091/FrontPage'
```

Expected: `200`

- [ ] **Step 3: Manual verification**

- Open `http://localhost:8091/FrontPage?edit`, click **Verify with AI** — response should be fast, no thread pool leaks.
- Open Ai Prompt modal, send a prompt — page type resolution should be cached on second call.
- Open a large page, verify — diff should render without UI freeze.

- [ ] **Step 4: Commit any final fixes**

```bash
git add -A
git commit -m "perf: final verification and cleanup"
```

---

## Notes for the implementer

- The `HttpClient` connection pool uses a fixed thread pool executor; `HttpClient` manages its own connection pool internally when using a shared executor.
- For `LlmClient` tests, the no-arg constructor still works (uses `DEFAULT_CLIENT`).
- Page type cache uses `ConcurrentHashMap` with timestamp-based TTL; simple and effective.
- DocumentFragment avoids reflow during DOM construction; significant for >500 rows.
- No new dependencies; all Java standard library.