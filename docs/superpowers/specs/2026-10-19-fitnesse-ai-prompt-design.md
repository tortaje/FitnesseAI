# Spec: "Ai Prompt to FitNesse" — LLM-assisted page generation

Date: 2026-10-19
Status: approved in brainstorming, awaiting spec review

## Goal

Add an **Ai Prompt to FitNesse** button to the FitNesse plain-text editor toolbar
(alongside the existing *Spreadsheet to FitNesse* button). Clicking it opens a
modal with two side-by-side panels: a chat on the left where the user prompts an
LLM for the wiki page they want, and the generated wiki content on the right.
Sending a prompt generates (or regenerates) content; **Save** closes the modal and
writes the right-hand content into the editor, replacing the whole textarea.
Further turns re-send the current right-hand content so the LLM can build on — and
the user can hand-edit — what is there.

## Decisions (from brainstorming)

- **Button location:** the text-area toolbar, on *every* edit form (`?edit` and
  `?new`), not just new-page creation.
- **Approach A** — a new **stateless** responder `ai-prompt`. No mode flags on
  `ai-verify`, no streaming.
- **Layout A** — even 50/50 split between the two panels.
- **Save** replaces the entire textarea (no insert-at-cursor option).
- **Right panel is editable** by the user; its current value is what is fed back
  to the LLM on the next send, and what Save writes.
- **Seed:** the current editor content is included as context in the first request.
- **Left panel** shows a scrolling chat log, not a bare input box.
- **Prompt is configurable**, mirroring the verify prompt:
  `llm.generate.prompt` / `llm.generate.prompt.file` over a bundled default.
- **Page metadata** (`pageName`, `pageType`) is sent as hidden context.

## Components

| Piece | Responsibility |
|---|---|
| `AiPromptResponder` (new) | Registered as `ai-prompt`. Validates the payload, resolves page type when the client couldn't, composes the message array, calls the LLM, returns `{content}` or `{error, details?, missing?}`. |
| `AiVerifyConfig` | Gains `llm.generate.prompt`, `llm.generate.prompt.file` and a bundled default resource `ai-generate-prompt.txt`, resolved with the same priority rules as the verify prompt (`file` > inline > bundled) and reported by `logPromptSource`. |
| `LlmClient` | Transport half of `chat()` extracted to package-private `post(config, JSONArray messages) → String`; `chat()` keeps JSON repair, new `generate()` returns plain text with markdown fences stripped. |
| Shared error helper (package-private) | The `try/catch (LlmException \| RuntimeException)` → warning log → error-JSON block used by both responders, so the error contract cannot drift (same precedent as `AssetInjector`). |
| `ai-verify.js` | Injects the button into `editor.textareaToolbar` from the existing `Wysiwyg.initialize` patch; owns modal markup, chat state, Save. |
| `ai-verify.css` | Reuses overlay/header/footer classes; adds the two-column panel grid. |

The only new registration is the `ai-prompt` responder. Same ServiceLoader entry,
same JAR, no FitNesse core changes.

## Contract

### Request

```
POST <current path>          (form-encoded, as today)
responder=ai-prompt&payload=<urlencoded JSON>
```

```json
{
  "pageName": "LoginTest",
  "pageType": "Test",
  "pageContent": "<current right-hand panel>",
  "messages": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ]
}
```

- `pageContent` — string, may be empty. The right-hand panel at the time of the
  send (so user edits are included). Always re-read from the panel, every turn.
- `messages` — array of `{role, content}`; the full transcript **including** the
  new user prompt as the last entry. On the first turn it holds exactly that one
  prompt; it grows by one `user` prompt and one `assistant` reply per round-trip.
- `pageName` — optional (used for logging and as hidden context), same as today.
- `pageType` — `"Static" | "Test" | "Suite" | "Default" | ""`. The client sends
  what the form exposes (new-page form: the `pageType` / `pageTemplate` input);
  when empty and the page already exists, the responder resolves it server-side
  with `PageType.fromWikiPage()`.

### Server-side message composition

1. `[system]` generation prompt + `pageName` / `pageType`
2. `[user]` `"Current page content (possibly hand-edited):\n" + pageContent`
3. `[...]` the client transcript, in order

`pageContent` is inserted on **every** turn — this is what makes multi-turn
hand-editing work.

### Response

Success: `200 {"content": "<wiki text>"}` — plain text, no JSON wrapper expected
from the LLM.

Errors (identical shape to `ai-verify`):

| Case | Status | Body |
|---|---|---|
| GET / non-POST | 405 | `{error}` |
| Not form-encoded | 400 | `{error}` |
| Missing `messages` or `pageContent` | 400 | `{error}` |
| `llm.*` config absent | 400 | `{error, missing:[...]}` |
| LLM HTTP/timeout/unreachable | 502 | `{error, details?}` |
| Unexpected runtime failure | 502 | `{error}` |

## UI

Modal reuses `.ai-verify-overlay`, `.ai-verify-modal`, `.ai-verify-header`,
`.ai-verify-close`, `.ai-verify-footer`, `.ai-verify-cancel`, `.ai-verify-error`.
The middle becomes a two-column grid:

- **Left** `.ai-verify-chat`: scrollable bubble log (auto-scrolled to bottom) +
  fixed input row (textarea + **Send**). **Enter** sends, **Shift+Enter** new line.
- **Right** `.ai-verify-content`: `<textarea>`, prefilled with `getPageContent()`,
  freely editable.
- **Footer**: **Cancel** left, **Save** right — Save disabled while the right panel
  is empty.

States: Send disabled when the input is empty or a request is in flight; Send and
the toolbar button show busy state while generating; errors reuse the
`.ai-verify-error` banner + **Retry** (re-POSTs the same payload); Cancel/Escape/X
close without touching the page (nothing is written before Save).

**Save:** `setPageContent(rightPanelValue)` + close.

**Button:** `<input type="button" value="Ai Prompt to FitNesse">` appended to the
end of the text-area toolbar, injected from the captured `Wysiwyg` instance.

## On-page flow

- `pageContent` on open = `getPageContent()` (WYSIWYG/CodeMirror-aware, as Verify).
- After a response: append `{role:"assistant", content}` to the transcript and set
  the right panel to `content`.
- On send: re-read the right panel into `pageContent`, append the user prompt to
  the transcript, POST.
- Save: write panel → editor; the WYSIWYG document is refreshed through the
  existing `setPageContent()` path.

## Error handling

Covered by the shared helper above. Debug logging: `[ai-prompt]`-tagged lines
(request, response, composed message count, prompt source) gated behind
`llm.debug`, exactly like `ai-verify`; LLM errors always `WARNING`.

## Testing

- **`AiPromptResponderTest`** — registration; 405; 400 on missing fields; missing
  config → `missing` array; happy path via the mock LLM → `{content}`; LLM failure
  → 502; **message composition** (system prompt is the generation prompt,
  metadata present, `pageContent` block precedes the transcript, order preserved);
  server-side `pageType` resolution for an existing page.
- **`AiVerifyConfigTest`** — inline `llm.generate.prompt`, `llm.generate.prompt.file`
  from a temp file, bundled fallback, `logPromptSource` values.
- **`LlmClientTest`** — `generate()` returns plain text (fences stripped), body
  carries the full `messages` array; `chat()` regression stays green.
- **JS** — no test framework in this repo; manual checklist added to the README
  (button on every edit form, Send produces a bubble and fills the panel, a second
  Send re-sends hand-edited content, Save replaces the editor, error + Retry).

## Out of scope

- Streaming responses.
- Persisting chat across modal open/close or across sessions.
- Insert-at-cursor / append Save modes.
- A separate config namespace (the generation prompt lives in `AiVerifyConfig`).
