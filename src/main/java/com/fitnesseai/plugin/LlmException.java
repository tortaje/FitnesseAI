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
