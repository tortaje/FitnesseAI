# FitNesse AI Plugin v1.0.0 Release Notes

## Overview
First stable release of the FitNesse AI Plugin — adds AI-assisted wiki editing to FitNesse via OpenAI-compatible LLMs.

## Features

### Verify with AI (Proofreading)
- **Button**: "Verify with AI" in the save-buttons row (`#save_buttons`) on `?edit` and `?new` forms
- **Action**: Sends current page content to LLM, returns side-by-side diff with line numbers
- **Diff**: Red (original) / Green (corrected), per-line checkboxes to accept/reject changes
- **Apply**: Merges selected corrections into the editor (manual Save still required)
- **Safety**: Detects concurrent edits — refuses Apply if page changed since verification

### Ai Prompt to FitNesse (Generation)
- **Button**: "Ai Prompt" (star icon, blue) in the text-area toolbar between *FitNesse to Spreadsheet* and *Format*
- **Modal**: Two-panel — chat on left (prompt + history), generated wiki text on right
- **Iterative**: Edit right panel by hand, press **Send** again to refine with LLM
- **Save**: Replaces entire editor content (manual Save still required)

### Shared Infrastructure
- **LLM**: OpenAI-compatible API (`/v1/chat/completions`), configurable model, timeout, max tokens
- **HTTP Client**: Shared connection pool (`llm.http.max-connections`, `llm.http.connection-ttl.seconds`)
- **Config**: Reloads prompt files on every request (no restart needed)
- **Debug**: `llm.debug=true` logs full request/response payloads at INFO level

## Configuration

```properties
# Core
llm.base.url=http://localhost:11434/v1
llm.api.key=
llm.model=llama3.1
llm.max.tokens=4096
llm.timeout.seconds=120
llm.debug=false

# HTTP pool
llm.http.max-connections=20
llm.http.connection-ttl.seconds=300

# Verify (proofreading)
llm.verify.prompt=
llm.verify.prompt.file=/path/to/ai-verify-prompt.md

# Generate (Ai Prompt)
llm.generate.prompt=
llm.generate.prompt.file=/path/to/ai-generate-prompt.md
```

## Installation

```bash
mvn package
cp target/fitnesse-ai-1.0.0.jar <fitnesse-folder>/plugins/
```

Add `plugins.properties` at FitNesse root (next to `FitNesseRoot`):
```properties
llm.base.url=http://localhost:11434/v1
llm.model=llama3.1
llm.verify.prompt.file=/path/to/ai-verify-prompt.md
llm.generate.prompt.file=/path/to/ai-generate-prompt.md
```

## Requirements
- FitNesse standalone (tested with 20251025, 20260313)
- Java 21+
- OpenAI-compatible LLM endpoint (Ollama, vLLM, OpenAI, etc.)

## Verification
- 71 unit/integration tests pass
- Manual smoke tested on FitNesse 20260313 at `http://localhost:8091`

## Artifacts
- `fitnesse-ai-1.0.0.jar` — plugin JAR (includes `ai-verify.js`, `ai-verify.css`, prompt resources)