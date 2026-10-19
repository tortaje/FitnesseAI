package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureReadOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.wiki.PageType;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AiPromptResponder implements SecureResponder {
  private static final Logger LOG = Logger.getLogger(AiPromptResponder.class.getName());
  private static final Duration PAGE_TYPE_CACHE_TTL = Duration.ofMinutes(1);
  private static final ConcurrentHashMap<String, Pair<String, Instant>> pageTypeCache = new ConcurrentHashMap<>();

  private record Pair<K, V>(K key, V value) {}

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
    if (!payload.has("pageContent") || !(payload.get("pageContent") instanceof String pageContent)) {
      return ResponderSupport.jsonResponse(400,
        new JSONObject().put("error", "Missing required field: pageContent"));
    }
    if (!payload.has("messages") || !(payload.get("messages") instanceof JSONArray transcript)
        || transcript.length() == 0) {
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
    var cached = pageTypeCache.get(pageName);
    if (cached != null && !cached.value().isBefore(Instant.now())) {
      return cached.key();
    }
    try {
      WikiPage page = context.getRootPage().getPageCrawler().getPage(PathParser.parse(pageName));
      String type = page == null ? "" : PageType.fromWikiPage(page).toString();
      pageTypeCache.put(pageName, new Pair<>(type, Instant.now().plus(Duration.ofMinutes(1))));
      return type;
    } catch (RuntimeException e) {
      return "";
    }
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
