package io.github.tortaje;

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
import java.util.logging.Level;
import java.util.logging.Logger;

public class LlmClient {
  private static final Logger LOG = Logger.getLogger(LlmClient.class.getName());

  public record Comment(int line, String explanation) {}
  public record Result(String corrected, List<Comment> comments, String rawContent) {}

  /** One HttpClient (and its connection pool) shared by all requests; created on first use. */
  private static final class Shared {
    static final HttpClient CLIENT = HttpClient.newHttpClient();
  }

  private final HttpClient httpClient = Shared.CLIENT;

  public Result chat(AiVerifyConfig config, String pageContent) throws LlmException {
    JSONArray messages = new JSONArray()
      .put(new JSONObject().put("role", "system").put("content", config.systemPrompt()))
      .put(new JSONObject().put("role", "user").put("content", pageContent));
    String content = post(config, messages, "[ai-verify]");
    return parseResult(stripMarkdownFences(content), config.logLevel());
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
    Level verbose = config.logLevel();
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

  static Result parseResult(String json, Level verbose) throws LlmException {
    JSONObject obj = tryParse(json, verbose);
    if (obj == null) {
      // Small local models over-escape quotes with 2+ backslashes (\\" and \\\").
      LOG.log(verbose, "[ai-verify] Strict JSON parse failed, retrying with over-escaped quotes repaired");
      obj = tryParse(json.replaceAll("\\\\{2,}\"", "\\\\\\\""), verbose);
    }
    if (obj == null) {
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

  private static JSONObject tryParse(String json, Level verbose) {
    try {
      return new JSONObject(json);
    } catch (RuntimeException e) {
      LOG.log(verbose, () -> "[ai-verify] JSON parse failed: " + e.getMessage());
      return null;
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
  }
}
