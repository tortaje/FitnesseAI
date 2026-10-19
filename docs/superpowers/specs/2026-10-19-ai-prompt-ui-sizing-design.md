# Ai Prompt UI sizing fixes — spec

**Date:** 2026-10-19
**Status:** Approved (design), awaiting spec review
**Scope:** CSS-only change to `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`. No JS change, no server change.

## Motivation

Two visual defects reported on the running plugin:

1. The **Ai Prompt** button in the plain-text toolbar is too large and vertically
   misaligned with its neighbours.
2. In the Ai Prompt modal, the **chat history is squashed** (16px) while the
   **prompt input is enormous** (430px). The wanted split is 70% history / 30%
   input row.

## Root causes (measured on the live instance with headless Chrome)

**Toolbar button.** FitNesse styles toolbar buttons with
`.editPage .textarea-toolbar input[type=button]` (adds the `btn-sm` geometry:
`padding .25rem .5rem`, `font-size .875rem`, `line-height 1.5`, `border-radius .2rem`)
and `.editPage .textarea-toolbar input[type=button] { margin: 3px 0 6px 0 }`.
Our button is a `<button>` element, so those selectors do not match: it falls back
to the generic `.btn` sizing (`padding .375rem .75rem`, `font-size 1rem`,
`border-radius .25rem`) plus the `margin-left: .5em` from `.ai-verify-button`.
Measured: Ai Prompt 38px tall / 16px font / margin `0 0 0 8px`, versus 31px / 14px /
margin `3px 0 6px` for `#tt-wiki-to-spreadsheet` and `#tt-format-wiki`. The height
difference also shifts the baseline, producing a 2px vertical offset.

**Modal.** FitNesse's `.editPage textarea { display: block; width: 100%; height: 32em }`
(fitnesse_pages.css) applies to every textarea on the edit page, including the
modal's. At the modal's 13px font that is 416px. `.ai-prompt-content` (right
panel) escapes it because it is a column-flex item with `flex-basis: 0`, which
overrides `height`. `.ai-prompt-input` sits in a **row** flex container, where the
`height` applies, so `.ai-prompt-input-row` grows to 430px, and `.ai-prompt-log`
(`flex: 1`) is compressed to 16px (its min-content).

## Design decisions (approved)

- CSS-only, scoped to our own selectors; no `!important`, no JS, no markup change.
- Toolbar button copies the neighbours' `btn-sm` geometry (including margins), so
  it is 31px tall with 14px text and aligns like the `<input>` buttons.
- Modal left column split: history `flex: 7`, input row `flex: 3` (exact 70/30).
- The **Send** button keeps its stretched full-height behaviour (explicitly kept).

## Changes

Append to `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`:

```css
.editPage .textarea-toolbar .ai-verify-button {
  padding: 0.25rem 0.5rem;
  font-size: 0.875rem;
  line-height: 1.5;
  border-radius: 0.2rem;
  margin: 3px 0 6px 0;
}

.ai-prompt-input,
.ai-prompt-content {
  height: auto;
}

.ai-prompt-log {
  flex: 7 1 0%;
  min-height: 0;
}

.ai-prompt-input-row {
  flex: 3 1 0%;
  min-height: 0;
}
```

Notes:
- Specificity: `.editPage .textarea-toolbar .ai-verify-button` (0,3,0) beats
  `.ai-verify-button` (0,1,0) for `margin`, so the `margin-left: 0.5em` is
  replaced by the symmetric `3px 0 6px 0`. Colours (background, border, hover,
  disabled) still come from `.ai-verify-button`.
- `.ai-prompt-content` gets `height: auto` defensively; it keeps its
  `flex: 1` growth inside `.ai-prompt-side` (column), so the right panel continues
  to fill the modal.
- `.ai-prompt-log` currently has `flex: 1`; the rule above supersedes it
  (same specificity 0,1,0 but later in the file). The `padding`/`overflow` stay.

## Out of scope

- No JS/HTML change; Send stays stretched; the two modal columns stay 50/50 wide.
- The Verify with AI button and its modal (`ai-verify.css` lines 1-263) are not
  touched.
- The 60vh modal height and `.ai-prompt-panels` padding are not changed.

## Verification

1. `mvn -q test` → 71 tests green.
2. `mvn -q -DskipTests package`, redeploy to `/tmp/fitnesse-smoke/plugins/`,
   restart FitNesse on port 8091.
3. Headless-Chrome measurements on `http://localhost:8091/FrontPage?edit`:
   - `#tt-ai-prompt`: computed `height` 31px, `font-size` 14px, `margin` `3px 0px 6px`;
     its `getBoundingClientRect().top` equals that of `#tt-wiki-to-spreadsheet`
     and `#tt-format-wiki` (same toolbar row, no vertical offset).
   - Open the modal: `.ai-prompt-log` height ≈ 70% of `.ai-prompt-chat` height and
     `.ai-prompt-input-row` height = the remainder (≈ 30%); `.ai-prompt-input`
     height ≈ input row height minus padding; `.ai-prompt-content` still fills
     `.ai-prompt-side`.
   - Repeat the modal measurement with the browser window resized (e.g. 1600×900
     and 1280×720) to confirm the 70/30 split holds.
   - Confirm the Ai Prompt modal still opens with the page content and that the
     Verify with AI button/modal are unchanged.
4. Manual check: toolbar button visually aligns with neighbours; modal history is
   the dominant area with a small input box.
