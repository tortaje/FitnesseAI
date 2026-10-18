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
