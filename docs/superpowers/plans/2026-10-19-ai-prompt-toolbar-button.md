# Ai Prompt Toolbar Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the "Ai Prompt to FitNesse" button between *FitNesse to Spreadsheet* and *Format* in the FitNesse text-area toolbar and give it the same blue star-icon styling as "Verify with AI".

**Architecture:** Client-only change. `addPromptButton(toolbar)` in the bundled `ai-verify.js` switches from `<input type=button>` + `appendChild` to a `<button class="button ai-verify-button">` carrying the Verify button's star SVG + label span, inserted before `#tt-format-wiki` (fallback: append). No CSS, no server, no test-harness changes. Repository has no JS test framework: verification is `node --check` + the manual checklist in `README.md`.

**Tech Stack:** Vanilla ES5-style JS shipped as a FitNesse plugin resource, FitNesse `wysiwyg.js` toolbar DOM, Maven (`mvn -q test`, 71 Java tests).

---

## File Structure

- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` — only `addPromptButton` (~line 47-61).
- Modify: `README.md` — Usage step 6 (line 85) and manual checklist item (lines 129-131).
- No new files. No CSS change (`ai-verify.css` untouched).

---

### Task 1: Reposition and restyle the Ai Prompt toolbar button

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js:47-61` (`addPromptButton`)
- Read for reference: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.js` (Verify button creation in `onReady`) and `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css:3-25` (`.ai-verify-button`, `.ai-verify-icon`)

- [ ] **Step 1: Capture the current function as the failing baseline**

Run: `grep -n -A 16 'function addPromptButton' src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`

Expected output (current, wrong — `input`, plain, appended at end):

```js
  function addPromptButton(toolbar) {
    if (toolbar.querySelector('#tt-ai-prompt')) {
      return;
    }
    var button = document.createElement('input');
    button.type = 'button';
    button.id = 'tt-ai-prompt';
    button.value = 'Ai Prompt to FitNesse';
    button.title = 'Prompt an AI to generate FitNesse wiki content.';
    button.addEventListener('click', function () {
      openPromptModal();
    });
    toolbar.appendChild(button);
    state.promptButton = button;
  }
```

- [ ] **Step 2: Replace the function body**

Replace exactly that function (both the guard and all assignments) with:

```js
  function addPromptButton(toolbar) {
    if (toolbar.querySelector('#tt-ai-prompt')) {
      return;
    }
    var button = document.createElement('button');
    button.type = 'button';
    button.id = 'tt-ai-prompt';
    button.className = 'button ai-verify-button';
    button.title = 'Ai Prompt to FitNesse: prompt an AI to generate FitNesse wiki content.';
    button.innerHTML = '<svg class="ai-verify-icon" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">'
      + '<path fill="currentColor" d="M8 0l1.8 5.2L15 7l-5.2 1.8L8 14l-1.8-5.2L1 7l5.2-1.8z"/></svg>'
      + '<span class="ai-verify-label">Ai Prompt</span>';
    button.addEventListener('click', function () {
      openPromptModal();
    });
    var formatButton = toolbar.querySelector('#tt-format-wiki');
    if (formatButton) {
      toolbar.insertBefore(button, formatButton);
    } else {
      toolbar.appendChild(button);
    }
    state.promptButton = button;
  }
```

Do not add comments. Do not change any other function, including the Verify button creation.

- [ ] **Step 3: Verify the file parses**

Run: `node --check src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: exit code 0, no output.

- [ ] **Step 4: Verify the old markup is gone and the new markup is present**

Run: `grep -n "appendChild(button)\|ai-verify-button\|Ai Prompt</span>\|tt-format-wiki" src/main/resources/fitnesse/resources/ai-verify/ai-verify.js`
Expected: matches on
`button.className = 'button ai-verify-button';`,
`+ '<span class="ai-verify-label">Ai Prompt</span>';`,
`var formatButton = toolbar.querySelector('#tt-format-wiki');`,
`toolbar.appendChild(button);` (the fallback branch only — it must appear once, after the `insertBefore` line), and **no** line containing `button.value = 'Ai Prompt to FitNesse'`.

- [ ] **Step 5: Confirm the Java suite is unaffected**

Run: `mvn -q test`
Expected: `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0` and BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.js
git commit -m "feat: move Ai Prompt button beside Format and match Verify styling"
```

---

### Task 2: Update README position wording

**Files:**
- Modify: `README.md:85` (Usage step 6)
- Modify: `README.md:129-131` (manual checklist item)

- [ ] **Step 1: Update Usage step 6**

Replace:

```
6. **Ai Prompt to FitNesse** (text-area toolbar, next to *Spreadsheet to FitNesse*)
```

with:

```
6. **Ai Prompt to FitNesse** (text-area toolbar, between *FitNesse to Spreadsheet*
   and *Format*)
```

- [ ] **Step 2: Update the manual checklist item**

Replace:

```
- [ ] The **Ai Prompt to FitNesse** button sits at the end of the text-area
      toolbar on every edit form (plain-text mode) and the modal opens with the
      current page content already in the right panel.
```

with:

```
- [ ] The **Ai Prompt** button (blue, star icon, same styling as *Verify with AI*)
      sits between *FitNesse to Spreadsheet* and *Format* in the text-area toolbar
      on every edit form (plain-text mode); its tooltip shows "Ai Prompt to
      FitNesse: prompt an AI to generate FitNesse wiki content." and the modal
      opens with the current page content already in the right panel.
```

- [ ] **Step 3: Confirm no stale wording remains**

Run: `grep -n "next to \*Spreadsheet\|end of the text-area" README.md`
Expected: no output (exit code 1 from `grep`).

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document new Ai Prompt button position and styling"
```

---

### Task 3: Rebuild, redeploy the smoke instance, and verify in the browser

**Files:**
- Build: `target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`
- Runtime: `/tmp/fitnesse-smoke/plugins/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`, instance on port 8091

- [ ] **Step 1: Package the plugin**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS, jar refreshed in `target/`.

- [ ] **Step 2: Redeploy and restart the smoke instance**

```bash
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
lsof -ti tcp:8091 | xargs kill; sleep 2
(cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &)
sleep 8
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8091/FrontPage'
```

Expected: `200`.

- [ ] **Step 3: Confirm the served asset contains the new code**

Run: `curl -s 'http://localhost:8091/files/fitnesse/ai-verify/ai-verify.js' | grep -c "ai-verify-button\|Ai Prompt</span>"`
Expected: a number ≥ 3 (Verify button class, prompt button class, prompt label span).

- [ ] **Step 4: Manual browser pass**

On `http://localhost:8091/FrontPage?edit`, switch to plain-text mode:
- [ ] The button appears **between *FitNesse to Spreadsheet* and *Format*** (not at the end, not first).
- [ ] It is blue with a star icon and the label **Ai Prompt**; hovering shows the tooltip "Ai Prompt to FitNesse: prompt an AI to generate FitNesse wiki content."
- [ ] Style matches *Verify with AI* (same blue `#2b6cb0`, white text, same icon size/alignment).
- [ ] Click opens the Ai Prompt modal with the current page content in the right panel.
- [ ] Press **Send**: the button is disabled while generation runs and re-enables after the modal is closed (via ✕ or Escape).
- [ ] Toggle plain-text ↔ WYSIWYG: the toolbar hides/shows as before and the button reappears in the same position.
- [ ] Reload the edit page twice: exactly one Ai Prompt button exists (no duplicates).

Report the result of each checkbox; if any fails, fix and re-run Step 1-4 before proceeding.

---

## Notes for the implementer

- No comments in the JS (project rule), English only in UI strings/docs.
- Do not touch `ai-verify.css`: `.ai-verify-button` (`ai-verify.css:3-8`), `:hover` (10-14), `:disabled` (16-20) and `.ai-verify-icon` (22-25) already supply every style the prompt button needs.
- `state.promptButton` consumers (`setPromptBusy` → `state.promptButton.disabled`, close handler → re-enable) work unchanged on a `<button>` element; do not modify them.
- Commit style follows the repo: `feat:` / `docs:` prefixes, one commit per task.
