package io.github.tortaje;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    Properties props = props("llm.verify.prompt", "custom prompt");
    assertEquals("custom prompt", new AiVerifyConfig(props).systemPrompt());
  }

  @Test
  void promptFileOverridesInlineAndDefault(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "# My prompt\nsecond line", StandardCharsets.UTF_8);
    Properties props = props("llm.verify.prompt", "inline prompt", "llm.verify.prompt.file", file.toString());
    assertEquals("# My prompt\nsecond line", new AiVerifyConfig(props).systemPrompt());
  }

  @Test
  void promptFileIsRereadOnEachCall(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "first version", StandardCharsets.UTF_8);
    AiVerifyConfig config = new AiVerifyConfig(props("llm.verify.prompt.file", file.toString()));
    assertEquals("first version", config.systemPrompt());
    Files.writeString(file, "second version", StandardCharsets.UTF_8);
    assertEquals("second version", config.systemPrompt());
  }

  @Test
  void missingPromptFileThrowsListingPropertyAndPath(@TempDir Path dir) {
    String path = dir.resolve("does-not-exist.md").toString();
    Properties props = props("llm.verify.prompt.file", path);
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> new AiVerifyConfig(props).systemPrompt());
    assertTrue(e.getMessage().contains("llm.verify.prompt.file"));
    assertTrue(e.getMessage().contains(path));
  }

  @Test
  void blankPromptFileFallsBackToDefault() {
    Properties props = props("llm.verify.prompt.file", "   ");
    assertTrue(new AiVerifyConfig(props).systemPrompt().contains("JSON object"));
  }

  @Test
  void debugDefaultsToFalseAndParsesTrue() {
    assertFalse(new AiVerifyConfig(new Properties()).debug());
    assertTrue(new AiVerifyConfig(props("llm.debug", "true")).debug());
    assertTrue(new AiVerifyConfig(props("llm.debug", " TRUE ")).debug());
    assertFalse(new AiVerifyConfig(props("llm.debug", "yes")).debug());
  }

  @Test
  void promptSourceIsLoggedAtDebugLevel(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("p.md");
    Files.writeString(file, "hello prompt", StandardCharsets.UTF_8);

    try (LogCapture logs = new LogCapture(AiVerifyConfig.class)) {
      new AiVerifyConfig(props("llm.debug", "true", "llm.verify.prompt.file", file.toString())).systemPrompt();
      assertTrue(logs.has(Level.INFO, "prompt source=file"));
      assertTrue(logs.has(Level.INFO, file.toString()));
    }

    try (LogCapture logs = new LogCapture(AiVerifyConfig.class)) {
      new AiVerifyConfig(props("llm.verify.prompt.file", file.toString())).systemPrompt();
      assertFalse(logs.has(Level.INFO, "prompt source=file"));
      assertTrue(logs.has(Level.FINE, "prompt source=file"));
    }
  }

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
