package com.fitnesseai.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  void promptFileOverridesInlineAndDefault(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "# My prompt\nsecond line", StandardCharsets.UTF_8);
    Properties props = props("llm.prompt", "inline prompt", "llm.prompt.file", file.toString());
    assertEquals("# My prompt\nsecond line", new AiVerifyConfig(props).systemPrompt());
  }

  @Test
  void promptFileIsRereadOnEachCall(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "first version", StandardCharsets.UTF_8);
    AiVerifyConfig config = new AiVerifyConfig(props("llm.prompt.file", file.toString()));
    assertEquals("first version", config.systemPrompt());
    Files.writeString(file, "second version", StandardCharsets.UTF_8);
    assertEquals("second version", config.systemPrompt());
  }

  @Test
  void missingPromptFileThrowsListingPropertyAndPath(@TempDir Path dir) {
    String path = dir.resolve("does-not-exist.md").toString();
    Properties props = props("llm.prompt.file", path);
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> new AiVerifyConfig(props).systemPrompt());
    assertTrue(e.getMessage().contains("llm.prompt.file"));
    assertTrue(e.getMessage().contains(path));
  }

  @Test
  void blankPromptFileFallsBackToDefault() {
    Properties props = props("llm.prompt.file", "   ");
    assertTrue(new AiVerifyConfig(props).systemPrompt().contains("JSON object"));
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
