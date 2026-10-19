# Spec: Diff UI redesign — full side-by-side view

## Goal

Replace the hunk-based diff view in the Verify modal with a full side-by-side
view: both complete wiki texts (original left, corrected right), line numbers in
both gutters, unchanged lines shown in neutral gray, modified lines highlighted
(red on the left, green on the right), and the LLM's explanation shown as a
tooltip on hover over any line of a modified block. Keep per-hunk selection
(checkbox) and the Apply merge logic. Remove the word "hunk" from the UI. Sync
horizontal scrolling between the two columns.

## Decisions (from brainstorming)

- Approach A: single scroll container, two-column table, natural scroll sync.
- Checkbox in a narrow leftmost column with `rowspan` over the hunk's rows.
- Comment tooltip on every line of a hunk (native `title` + CSS tooltip).
- No collapsing of unchanged lines; all lines rendered.
- Default checkbox state: checked (as today).

## Architecture

Only the client is affected: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
and `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`. The server
contract (`/ai-verify` responder) is unchanged: hunks still carry `id`, `type`,
`startLine`, `originalLines`, `correctedLines`, `comment`.

The JS must reconstruct a full aligned line list from the original text and the
hunks. Each row is one of:
- unchanged: same line on both sides (neutral).
- modified: left shows original line(s), right shows corrected line(s); if counts
  differ, blank cells pad the shorter side.
- added: left blank, right green.
- removed: left red, right blank.

Line numbers: original gutter shows the original 1-based line number (or blank);
corrected gutter shows the corrected 1-based line number (or blank).

## Components

- `renderDiff(originalText, hunks)`: builds the aligned row model and renders the
  table. Replaces the current hunk-loop renderer.
- `alignLines(originalText, hunks)`: returns an array of row objects
  `{origNo, origText, corrNo, corrText, hunkId, type, comment}` where hunkId is
  null for unchanged rows.
- Checkbox handling: one checkbox per hunk, placed in the rowspan cell of the
  first row. Toggling updates `state.checked` and the Apply counter.
- Tooltip: `title` attribute on modified cells containing the comment; plus a
  CSS hover tooltip for visibility.

## Data flow

1. Server returns hunks with startLine (original 1-based), originalLines,
   correctedLines, comment.
2. `alignLines` walks the original lines and the hunks in order, emitting rows.
   Unchanged runs between hunks become neutral rows. Each hunk emits
   `max(originalLines.length, correctedLines.length)` rows, aligning by index,
   padding blanks.
3. `mergeContent` (unchanged) applies selected hunks to produce the final text.

## Error handling

Unchanged. Empty hunks → "No corrections proposed." message (existing).

## Testing

Manual only (JS has no unit tests in this repo). Smoke test in FitNesse 8091
after deploy. Verify: all lines visible, colors, hover tooltips, scroll sync,
selection apply.

## Out of scope

- No server changes.
- No change to hunk computation or comment assignment.
