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
