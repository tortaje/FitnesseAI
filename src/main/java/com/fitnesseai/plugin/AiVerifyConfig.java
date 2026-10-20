package com.fitnesseai.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AiVerifyConfig {
  private static final Logger LOG = Logger.getLogger(AiVerifyConfig.class.getName());

  public static final String BASE_URL = "llm.base.url";
  public static final String API_KEY = "llm.api.key";
  public static final String MODEL = "llm.model";
  public static final String MAX_TOKENS = "llm.max.tokens";
  public static final String TIMEOUT_SECONDS = "llm.timeout.seconds";
  public static final String DEBUG = "llm.debug";

  // Verify (proofreading) prompts
  public static final String VERIFY_PROMPT = "llm.verify.prompt";
  public static final String VERIFY_PROMPT_FILE = "llm.verify.prompt.file";
  // Aliases (backward compat)
  public static final String PROMPT = "llm.prompt";
  public static final String PROMPT_FILE = "llm.prompt.file";

  // Generate (Ai Prompt) prompts
  public static final String GENERATE_PROMPT = "llm.generate.prompt";
  public static final String GENERATE_PROMPT_FILE = "llm.generate.prompt.file";

  // HTTP client pool
  public static final String HTTP_MAX_CONNECTIONS = "llm.http.max-connections";
  public static final String HTTP_CONNECTION_TTL_SECONDS = "llm.http.connection-ttl.seconds";
  // Aliases (backward compat)
  public static final String HTTP_MAX_CONNECTIONS_ALIAS = "llm.http.max.connections";
  public static final String HTTP_CONNECTION_TTL_SECONDS_ALIAS = "llm.http.connection.ttl.seconds";

  public static final int DEFAULT_MAX_TOKENS = 4096;
  public static final int DEFAULT_TIMEOUT_SECONDS = 120;
  public static final int DEFAULT_HTTP_MAX_CONNECTIONS = 20;
  public static final int DEFAULT_HTTP_CONNECTION_TTL_SECONDS = 300;

  private static final String DEFAULT_PROMPT_RESOURCE = "/ai-verify-prompt.txt";
  private static final String DEFAULT_GENERATE_PROMPT_RESOURCE = "/ai-generate-prompt.txt";

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

  public int httpMaxConnections() {
    return intOr(HTTP_MAX_CONNECTIONS, DEFAULT_HTTP_MAX_CONNECTIONS, HTTP_MAX_CONNECTIONS_ALIAS);
  }

  public int httpConnectionTtlSeconds() {
    return intOr(HTTP_CONNECTION_TTL_SECONDS, DEFAULT_HTTP_CONNECTION_TTL_SECONDS, HTTP_CONNECTION_TTL_SECONDS_ALIAS);
  }

  public boolean debug() {
    return Boolean.parseBoolean(raw(DEBUG));
  }

  public String systemPrompt() {
    return resolvePrompt("[ai-verify]",
      VERIFY_PROMPT_FILE, VERIFY_PROMPT, DEFAULT_PROMPT_RESOURCE,
      new String[]{PROMPT_FILE},           // file aliases
      new String[]{PROMPT}                 // inline aliases
    );
  }

  public String generateSystemPrompt() {
    return resolvePrompt("[ai-prompt]",
      GENERATE_PROMPT_FILE, GENERATE_PROMPT, DEFAULT_GENERATE_PROMPT_RESOURCE,
      new String[]{},                      // no file aliases for generate (only primary)
      new String[]{}                       // no inline aliases for generate (only primary)
    );
  }

  private String resolvePrompt(String tag,
      String primaryFileKey, String primaryInlineKey, String resource,
      String[] fileAliases, String[] inlineAliases) {
    String path = raw(primaryFileKey);
    if (isBlank(path)) {
      for (String alias : fileAliases) {
        path = raw(alias);
        if (!isBlank(path)) break;
      }
    }
    if (!isBlank(path)) {
      String prompt = promptFromFile(path, primaryFileKey, fileAliases);
      logPromptSource(tag, "file(" + path + ")", prompt);
      return prompt;
    }
    String inline = raw(primaryInlineKey);
    if (isBlank(inline)) {
      for (String alias : inlineAliases) {
        inline = raw(alias);
        if (!isBlank(inline)) break;
      }
    }
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

  private static String promptFromFile(String path, String fileKey, String... aliasKeys) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      String allKeys = fileKey;
      if (aliasKeys.length > 0) {
        allKeys += " (aliases: " + String.join(", ", aliasKeys) + ")";
      }
      throw new IllegalStateException(allKeys + " points to a missing file: " + path);
    } catch (IOException e) {
      throw new IllegalStateException(fileKey + " could not read file " + path + ": " + e.getMessage());
    }
  }

  private String raw(String key) {
    String value = properties.getProperty(key);
    return value == null ? null : value.trim();
  }

  private int intOr(String primaryKey, int defaultValue, String... aliasKeys) {
    String value = raw(primaryKey);
    if (value == null) {
      for (String alias : aliasKeys) {
        value = raw(alias);
        if (value != null) break;
      }
    }
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
