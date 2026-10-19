# Diff UI Redesign — Full Side-by-Side Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hunk-block diff view with a full side-by-side line table: both complete wiki texts with line numbers, unchanged lines in neutral, red/green modifications, hover tooltips carrying LLM comments, per-hunk checkbox with rowspan, shared horizontal scroll.

**Architecture:** Client-only change in the plugin's bundled JS and CSS (`src/main/resources/fitnesse/resources/ai-verify/`). The server JSON contract is unchanged. New `alignRows(originalText, hunks)` builds a flat row model (original line no, original text, corrected line no, corrected text, optional hunkId/type/comment); `renderDiff` renders it as one `<table>` inside the scrollable `.ai-verify-diff` container — single scroll container gives free horizontal/vertical sync.

**Tech Stack:** Vanilla ES5 browser JS (FitNesse edit page), plain CSS. No JS test framework in this repo — verification is `mvn clean verify` (Java suite stays green) + deploy + manual browser smoke on FitNesse 8091.

**Spec:** `docs/superpowers/specs/2026-10-19-fitnesse-diff-redesign.md`

---

### Task 1: JS — alignRows + table renderer

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`

- [ ] **Step 1: Remove now-dead helpers and constant**

In `ai-verify.js`, delete:
- `var CONTEXT_LINES = 3;` (line ~5)
- the whole `function cellLines(lines, side, type) { ... }`
- the whole `function contextHtml(lines) { ... }`
- the whole `function typeLabel(type) { ... }`

- [ ] **Step 2: Add alignRows + gutter helpers**

Insert after `escapeHtml`:

```js
  function gutter(no) {
    return '<td class="ai-verify-gutter">' + (no === null ? '' : no) + '</td>';
  }

  function alignRows(originalText, hunks) {
    var origLines = originalText.split('\n');
    var rows = [];
    var cursor = 0;
    var i, k, hunk, start, oLines, cLines, max;
    for (i = 0; i < hunks.length; i++) {
      hunk = hunks[i];
      start = hunk.startLine - 1;
      while (cursor < start && cursor < origLines.length) {
        rows.push({ origNo: cursor + 1, origText: origLines[cursor], corrNo: cursor + 1, corrText: origLines[cursor], hunkId: null });
        cursor++;
      }
      oLines = hunk.originalLines;
      cLines = hunk.correctedLines;
      max = Math.max(oLines.length, cLines.length);
      for (k = 0; k < max; k++) {
        rows.push({
          origNo: k < oLines.length ? start + k + 1 : null,
          origText: k < oLines.length ? oLines[k] : null,
          corrNo: null,
          corrText: k < cLines.length ? cLines[k] : null,
          hunkId: hunk.id,
          type: hunk.type,
          comment: hunk.comment
        });
      }
      cursor = start + oLines.length;
    }
    while (cursor < origLines.length) {
      rows.push({ origNo: cursor + 1, origText: origLines[cursor], corrNo: cursor + 1, corrText: origLines[cursor], hunkId: null });
      cursor++;
    }
    var corrNo = 0;
    for (i = 0; i < rows.length; i++) {
      if (rows[i].corrText !== null) {
        corrNo++;
        rows[i].corrNo = corrNo;
      }
    }
    return rows;
  }
```

- [ ] **Step 3: Replace renderDiff**

Delete the whole old `renderDiff(originalText, hunks)` and `contextHtml` references; insert:

```js
  function renderDiff(originalText, hunks) {
    var rows = alignRows(originalText, hunks);
    var diff = state.modal.querySelector('.ai-verify-diff');
    diff.hidden = false;

    var html = ['<table class="ai-verify-table">',
      '<thead><tr><th class="ai-verify-gutter-sel"></th><th class="ai-verify-gutter">#</th>'
      + '<th class="ai-verify-text">Original</th><th class="ai-verify-gutter">#</th>'
      + '<th class="ai-verify-text">Corrected</th></tr></thead>',
      '<tbody>'];
    var i = 0;
    while (i < rows.length) {
      var row = rows[i];
      if (row.hunkId === null) {
        html.push('<tr class="ai-verify-row-unchanged">'
          + '<td class="ai-verify-gutter-sel"></td>'
          + gutter(row.origNo)
          + '<td class="ai-verify-text">' + escapeHtml(row.origText) + '</td>'
          + gutter(row.corrNo)
          + '<td class="ai-verify-text">' + escapeHtml(row.corrText) + '</td>'
          + '</tr>');
        i++;
      } else {
        var hunkId = row.hunkId;
        var start = i;
        while (i < rows.length && rows[i].hunkId === hunkId) {
          i++;
        }
        var count = i - start;
        for (var k = start; k < i; k++) {
          var r = rows[k];
          var selCell = (k === start)
            ? '<td class="ai-verify-gutter-sel" rowspan="' + count + '">'
              + '<input type="checkbox" class="ai-verify-hunk-check" data-hunk-id="' + hunkId + '" checked /></td>'
            : '';
          var leftClass = r.type === 'added' ? '' : ' ai-verify-side-removed';
          var rightClass = r.type === 'removed' ? '' : ' ai-verify-side-added';
          var title = r.comment ? ' title="' + escapeHtml(r.comment).replace(/"/g, '&quot;') + '"' : '';
          html.push('<tr class="ai-verify-row-modified" data-hunk-id="' + hunkId + '">'
            + selCell
            + gutter(r.origNo)
            + '<td class="ai-verify-text' + leftClass + '"' + (r.origText !== null ? title : '') + '>'
            + (r.origText !== null ? escapeHtml(r.origText) : '') + '</td>'
            + gutter(r.corrNo)
            + '<td class="ai-verify-text' + rightClass + '"' + (r.corrText !== null ? title : '') + '>'
            + (r.corrText !== null ? escapeHtml(r.corrText) : '') + '</td>'
            + '</tr>');
        }
      }
    }
    html.push('</tbody></table>');
    diff.innerHTML = html.join('');

    var checks = diff.querySelectorAll('.ai-verify-hunk-check');
    for (var c = 0; c < checks.length; c++) {
      checks[c].addEventListener('change', onCheckChanged);
    }
  }
```

- [ ] **Step 4: Update onCheckChanged and setAllChecked**

Replace the body of `onCheckChanged`:

```js
  function onCheckChanged(e) {
    var id = parseInt(e.target.getAttribute('data-hunk-id'), 10);
    state.checked[id] = e.target.checked;
    var rows = state.modal.querySelectorAll('tr[data-hunk-id="' + id + '"]');
    for (var i = 0; i < rows.length; i++) {
      rows[i].classList.toggle('ai-verify-row-unchecked', !e.target.checked);
    }
    updateApplyButton();
  }
```

Replace the row-toggling part of `setAllChecked` (keep checkbox loop, replace `[data-hunk-row=...]` lookup):

```js
  function setAllChecked(checked) {
    var checks = state.modal.querySelectorAll('.ai-verify-hunk-check');
    for (var i = 0; i < checks.length; i++) {
      checks[i].checked = checked;
      var id = parseInt(checks[i].getAttribute('data-hunk-id'), 10);
      state.checked[id] = checked;
      var rows = state.modal.querySelectorAll('tr[data-hunk-id="' + id + '"]');
      for (var j = 0; j < rows.length; j++) {
        rows[j].classList.toggle('ai-verify-row-unchecked', !checked);
      }
    }
    updateApplyButton();
  }
```

- [ ] **Step 5: Verify no stale references remain**

Run: `grep -n "cellLines\|contextHtml\|typeLabel\|CONTEXT_LINES\|hunk-head\|ai-verify-skipped\|ai-verify-colhead\|data-hunk-row" src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "feat: full side-by-side line table diff renderer"
```

### Task 2: CSS — table layout, colors, tooltips

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`

- [ ] **Step 1: Replace the diff block styles**

In `ai-verify.css`, find the block starting at `.ai-verify-diff` (which currently sets `display: grid; grid-template-columns: 1fr 1fr;`) through `.ai-verify-comment { ... }` (all old diff styles: `.ai-verify-colhead`, `.ai-verify-cell`, `.ai-verify-line*`, `.ai-verify-skipped`, `.ai-verify-hunk-head`, `.ai-verify-hunk-unchecked`, `.ai-verify-comment`) and replace the whole span with:

```css
.ai-verify-diff {
  overflow: auto;
  max-height: 62vh;
  border: 1px solid #ddd;
}

.ai-verify-table {
  border: none;
  margin: 0;
  border-collapse: separate;
  border-spacing: 0;
  width: max-content;
  min-width: 100%;
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
}

.ai-verify-table th {
  position: sticky;
  top: 0;
  background: #f5f5f5;
  border: none;
  border-bottom: 1px solid #ddd;
  padding: 0.4em 0.8em;
  text-align: left;
  font-weight: bold;
  font-family: inherit;
}

.ai-verify-gutter-sel {
  width: 30px;
  text-align: center;
  padding: 0 0.2em;
}

.ai-verify-gutter {
  width: 40px;
  text-align: right;
  color: #999;
  padding: 0 0.6em;
  -webkit-user-select: none;
  user-select: none;
}

.ai-verify-table td {
  border: none;
}

.ai-verify-table th.ai-verify-gutter {
  text-align: right;
  padding-right: 0.6em;
}

.ai-verify-text {
  padding: 0 0.8em;
  white-space: pre;
}

.ai-verify-side-removed {
  background: #ffd6d6;
}

.ai-verify-side-added {
  background: #d4f8d4;
}

.ai-verify-row-unchecked .ai-verify-side-removed,
.ai-verify-row-unchecked .ai-verify-side-added {
  background: #f2f2f2;
  text-decoration: line-through;
  color: #aaa;
}
```

Keep everything else in the file untouched (modal, footer, message, error, button styles).

- [ ] **Step 2: Verify no stale selectors remain**

Run: `grep -n "ai-verify-colhead\|ai-verify-cell\|ai-verify-line\|ai-verify-skipped\|ai-verify-hunk-head\|ai-verify-hunk-unchecked\|ai-verify-comment" src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.css
git commit -m "feat: side-by-side table diff styling with red/green rows"
```

### Task 3: Build, deploy, manual smoke

**Files:**
- none modified; build + `/tmp/fitnesse-smoke`

- [ ] **Step 1: Full build**

Run: `mvn clean verify 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | tail -2`
Expected: `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 2: Deploy and restart smoke FitNesse**

```bash
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
pkill -f "fitnesse.jar -p 8091"; sleep 2
cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &
sleep 5
curl -s -o /dev/null -w "FitNesse HTTP %{http_code}\n" http://localhost:8091/FrontPage
```
Expected: `FitNesse HTTP 200`.

- [ ] **Step 3: Verify new assets served**

```bash
curl -s http://localhost:8091/files/fitnesse/ai-verify/ai-verify.js | grep -c "ai-verify-table"
curl -s http://localhost:8091/files/fitnesse/ai-verify/ai-verify.css | grep -c "ai-verify-side-removed"
```
Expected: both `>0`.

- [ ] **Step 4: Manual browser check (user)**

Open a wiki page → `?edit` → **Verify with AI** against the test page with intentional mistakes. Verify: full texts side by side with line numbers, all unchanged lines visible, red left / green right on modifications, checkbox per modified block with rowspan, hover tooltip shows the explanation, single horizontal scrollbar moves both sides, unchecking grays + strikes through, Apply (n) merges selected hunks.

- [ ] **Step 5: Commit nothing further (no source changes expected; fixes get their own commits).**

## Self-Review

- Spec coverage: no "hunk" word in UI (labels removed in Task 1/2; stats line "correction(s)" kept in header, not a hunk label) ✅; all lines visible (alignRows emits every line) ✅; line numbers both gutters ✅; red/green ✅; hover tooltip via `title` ✅; checkbox rowspan ✅; scroll sync via single container ✅; selection + Apply untouched ✅.
- Placeholders: none.
- Type consistency: row model fields (origNo/origText/corrNo/corrText/hunkId/type/comment) used consistently across Task 1 functions; CSS class names (`ai-verify-row-modified`, `ai-verify-row-unchecked`, `ai-verify-side-removed/added`, `ai-verify-gutter-sel`) match between JS and CSS.
