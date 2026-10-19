# Ai Prompt toolbar button — placement and styling

**Date:** 2026-10-19
**Status:** Approved
**Scope:** Client-only change to the Ai Prompt to FitNesse button (no server, no modal behaviour changes).

## Motivation

The "Ai Prompt to FitNesse" button is currently appended at the end of the FitNesse
plain-text toolbar as a plain `<input type="button">`. Two changes are requested:

1. It must sit **between *FitNesse to Spreadsheet* and *Format*** in the toolbar.
2. It must match the **Verify with AI** button's visual treatment: same blue
   colour (`#2b6cb0` family via `.ai-verify-button`), same star icon, same
   icon-plus-text label pattern (text itself is shortened, see below).

## Current state

- FitNesse toolbar order (built by `Wysiwyg.prototype.createTextareaToolbar`):
  `tt-spreadsheet-to-wiki` → `tt-wiki-to-spreadsheet` → `tt-format-wiki` →
  template select → `tt-insert-template` → wrap → autoformat.
- `addPromptButton(toolbar)` in `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
  creates `<input id="tt-ai-prompt" value="Ai Prompt to FitNesse">` and
  `toolbar.appendChild(button)` — so the button lands after *autoformat*.
- Verify with AI button (same file, `onReady`): `<button id="ai-verify-button"
  class="button ai-verify-button">` with innerHTML = star SVG (`.ai-verify-icon`)
  + `<span class="ai-verify-label">Verify with AI</span>`.
- Existing CSS (`ai-verify.css`): `.ai-verify-button` (blue background/border,
  white text, hover `#235a97`, disabled `#7a9cc6`), `.ai-verify-icon`,
  `.ai-verify-label`.

## Design decisions (approved)

- **Approach chosen: reuse `.ai-verify-button` / `.ai-verify-icon` as-is** on the
  prompt button (Approach 1). No CSS changes, no shared-class refactor; the
  Verify button code stays untouched. The slightly misleading class name is an
  accepted trade-off.
- **Label:** short form **"Ai Prompt"** (star icon + text), full name kept in the
  tooltip: `title="Ai Prompt to FitNesse: prompt an AI to generate FitNesse wiki content."`

## Changes

### 1. `ai-verify.js` — `addPromptButton(toolbar)`

- Element becomes a `<button type="button" id="tt-ai-prompt"
  class="button ai-verify-button">` carrying the same innerHTML pattern as the
  Verify button: star SVG with `class="ai-verify-icon"` +
  `<span class="ai-verify-label">Ai Prompt</span>`.
- `title` = `Ai Prompt to FitNesse: prompt an AI to generate FitNesse wiki content.`
- Insertion: replace `toolbar.appendChild(button)` with
  `toolbar.insertBefore(button, toolbar.querySelector('#tt-format-wiki'))`;
  if `#tt-format-wiki` is absent, fall back to `appendChild(button)`.
- Unchanged: the per-toolbar `#tt-ai-prompt` existence guard, the click →
  `openPromptModal()` wiring, `state.promptButton = button`, and every consumer
  of `state.promptButton` (`setPromptBusy` disables it; `closePromptModal`
  re-enables it) — `disabled` behaves identically on `button` elements.

Resulting toolbar order:

```
Spreadsheet to FitNesse → FitNesse to Spreadsheet → ⭐ Ai Prompt → Format → …
```

### 2. `README.md`

- Usage step 6: replace "text-area toolbar, next to *Spreadsheet to FitNesse*"
  with "text-area toolbar, between *FitNesse to Spreadsheet* and *Format*".
- Manual checklist first item: same position wording; mention the blue star
  styling shared with *Verify with AI*.

## Out of scope

- No CSS changes (`ai-verify.css` untouched).
- No server-side changes; no new JS unit tests (no framework in repo).
- No renaming of `.ai-verify-button` to a shared class (deferred, not required).
- Modal, send flow, responder, config untouched.

## Verification

1. `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` → exit 0.
2. `mvn -q test` → 71 green (server suite unaffected).
3. Manual pass on the smoke instance (`:8091`, `FrontPage?edit` plain-text mode):
   - Button appears between *FitNesse to Spreadsheet* and *Format*.
   - Blue with star icon, label "Ai Prompt", tooltip shows the full name.
   - Click opens the Ai Prompt modal; button disables during generation and
     re-enables after close (existing behaviour preserved).
   - Toggle plain ↔ WYSIWYG: toolbar hides/shows as before, button returns.
4. README wording updated in both places.
