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
