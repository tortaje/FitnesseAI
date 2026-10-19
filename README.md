# FitNesse AI Verify Plugin

FitNesse plugin that adds a **Verify with AI** button to the wiki edit and
new-page forms (`?edit` and `?new`):
the textarea content is sent to an OpenAI-compatible LLM, and the proposed
corrections are shown in a selectable side-by-side diff. Apply updates the
textarea (manual save as usual afterwards).

## Requirements

- FitNesse standalone (tested with 20251025 and 20260313; any recent version ≥ 2023 should work)
- Java 21+

## Installation

1. Build:

   ```bash
   mvn package
   ```

   The produced JAR is `target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`.

2. Copy the JAR into the `plugins/` directory of your FitNesse standalone instance:

   ```bash
   cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar <fitnesse-folder>/plugins/
   ```

3. Configure `plugins.properties` at the **root** of FitNesse (next to `FitNesseRoot`, *not* inside `plugins/`):

   ```properties
   llm.base.url=http://localhost:11434/v1
   llm.api.key=
   llm.model=llama3.1
   # Markdown system prompt, re-read on every Verify click (no restart needed)
   llm.prompt.file=/path/to/ai-verify-prompt.md
   # Verbose plugin logs (full LLM request/response dumps)
   llm.debug=true
   ```

   Properties:

   | Property             | Required | Default  | Description                                                                    |
   |----------------------|----------|----------|--------------------------------------------------------------------------------|
   | `llm.base.url`       | yes      | —        | Base URL of the OpenAI-compatible API (`.../v1`)                                |
   | `llm.model`          | yes      | —        | Model name                                                                     |
   | `llm.api.key`        | no       | empty    | Bearer token (empty for local endpoints)                                       |
   | `llm.max.tokens`     | no       | 4096     | Request `max_tokens`                                                           |
   | `llm.timeout.seconds`| no       | 120      | HTTP timeout                                                                   |
   | `llm.prompt`         | no       | bundled  | Inline text replacing the default system prompt                                |
   | `llm.prompt.file`    | no       | —        | Path to a UTF-8 file (`.md` recommended) holding the system prompt; re-read on every verification, takes precedence over `llm.prompt` |
   | `llm.generate.prompt`     | no       | bundled  | Inline text replacing the generation system prompt (Ai Prompt to FitNesse) |
   | `llm.generate.prompt.file`| no       | —        | Path to a UTF-8 file holding the generation system prompt; re-read on every send, takes precedence over `llm.generate.prompt` |
   | `llm.debug`          | no       | `false`  | When `true`, logs the full LLM request/response payloads, the prompt source, the verify summary and diff internals at INFO level; when `false` these go to FINE (hidden by default). LLM errors are always logged as warnings |

4. Restart FitNesse (once: the `llm.*` properties are read at startup). After that,
   editing the `llm.prompt.file` file takes effect on the next **Verify with AI**
   click — no restart or recompile needed.

   To start from the embedded prompt:

   ```bash
   unzip -p plugins/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar ai-verify-prompt.txt > ai-verify-prompt.md
   ```

No registration property is required: the plugin self-discovers through
ServiceLoader (META-INF/services inside the JAR).

## Usage

1. Open a page in edit mode (`?edit`), or create a new one (static, test or
   suite) via the **New** menu (`?new`).
2. Click **Verify with AI** (next to Save). The current textarea content is
   analysed, including unsaved changes.
3. In the modal: both complete texts are shown side by side with line numbers on
   both sides — unchanged lines in gray, corrections in red (original) and green
   (corrected). Each correction block has a checkbox (checked by default) in the
   left gutter; hover a corrected line to see the LLM's explanation as a tooltip.
   A single scrollbar scrolls both sides together.
4. **Apply (n)** writes the merged content into the textarea — saving stays
   manual (Save button).
5. If the textarea changed between verification and Apply, applying is refused
   (re-run the verification).
6. **Ai Prompt to FitNesse** (text-area toolbar, next to *Spreadsheet to FitNesse*)
   opens a two-panel modal: describe the page you want on the left, press
   **Send**, and the generated wiki text appears on the right. The right panel is
   editable; every further **Send** re-sends it, so the LLM refines your hand
   edits. **Save** closes the modal and replaces the whole editor content — the
   page is still saved manually with the Save button.

## Limitations

- The LLM must respect the expected JSON format; on a non-conforming response,
  the error message includes the raw text (expandable in the modal).

## Manual validation checklist (JS)

No automated JS tests in this project. After `mvn package` + installation:

- [ ] Open `http://localhost:<port>/<Page>?edit`: the "Verify with AI" button
      appears in `#save_buttons`; the Network tab shows a GET 200 on
      `.../files/fitnesse/ai-verify/ai-verify.js` and `...ai-verify.css`.
- [ ] On `http://localhost:<port>/<Parent>?new&pageTemplate=.TemplateLibrary.TestPage`
      (New → Test Page): the button appears in `#save_buttons` on the new-page
      form (static and suite templates behave the same); the modal title shows
      the page name typed in the "Page name" field (fallback: parent name).
- [ ] On a non-existent page URL (e.g. `/BrandNew`): FitNesse shows the
      create/edit form — the button appears there too.
- [ ] Without LLM config (`llm.*` absent): click → message listing `llm.base.url`,
      `llm.model`.
- [ ] With a valid LLM endpoint: click → "Verifying…" then a modal with both full
      texts side by side, line numbers in both gutters, red/green corrections;
      unchecking a correction updates the Apply counter; Apply updates the textarea
      (and the WYSIWYG editor if active) without saving.
- [ ] Hovering a corrected line shows the LLM explanation tooltip.
- [ ] One horizontal scrollbar moves both sides together.
- [ ] No differences → "No corrections proposed."
- [ ] LLM error (bad URL) → error banner + Retry button; the failure is logged as
      a warning in the FitNesse console.
- [ ] Edit the textarea while the modal is open, then Apply → refused with an
      explicit message.
- [ ] Escape / click outside the modal / ✕ close the modal; focus returns to the button.
- [ ] WYSIWYG mode active: after Apply, switching to text mode shows the applied
      content (no overwrite by the old iframe content).
- [ ] With `llm.debug=true`: the FitNesse console shows `[ai-verify]` INFO lines
      (POST payload, LLM response, prompt source, diff summary). With the property
      absent or `false`, only warnings appear.
- [ ] The **Ai Prompt to FitNesse** button sits at the end of the text-area
      toolbar on every edit form (plain-text mode) and the modal opens with the
      current page content already in the right panel.
- [ ] Type a request → **Send**: a user bubble appears, then an assistant bubble
      and the right panel fills with the reply. Send is disabled while empty.
- [ ] Edit the right panel by hand, send a second request → the edited content is
      sent along with it (check the Network tab payload).
- [ ] **Save** closes the modal and replaces the editor content; the WYSIWYG view
      matches after switching modes.
- [ ] Bad `llm.base.url` → error banner + **Retry**; a warning line appears in the
      FitNesse console tagged `[ai-prompt]`.
- [ ] With `llm.debug=true`: `[ai-prompt]` INFO lines (generate request, LLM
      response, prompt source) are visible; with the property absent, only
      warnings appear.
