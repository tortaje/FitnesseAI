# Ai Prompt UI Sizing Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Ai Prompt toolbar button the same size/alignment as its neighbours and give the modal's chat column a 70/30 history-to-input split.

**Architecture:** CSS-only. Four rules appended to the plugin stylesheet: one scoped geometry rule for the toolbar button (FitNesse's `input[type=button]` toolbar rules don't match a `<button>`), and three rules that neutralise FitNesse's `.editPage textarea { height: 32em }` and set the chat column flex split. No JS, no markup, no server change.

**Tech Stack:** Plain CSS shipped as a FitNesse plugin resource; FitNesse `fitnesse_pages.css` / `fitnesse-bootstrap.css`; Maven (71 Java tests); headless Chrome for measurement.

---

## File Structure

- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css` — append four rules at the end of the file (after line 404).
- No other file changes. No README change required (the README describes position/styling, not pixel sizes).

Existing rules that must remain intact:
- `.ai-verify-button` (lines 3-8, colors + `margin-left: 0.5em`) — used by the Verify button in `#save_buttons`.
- `.ai-prompt-log` (lines 284-292, `flex: 1`, overflow, padding, background).
- `.ai-prompt-input-row` (lines 313-319, display/gap/padding/border/background).
- `.ai-prompt-input` (321-330) and `.ai-prompt-content` (368-377) sizing.

---

### Task 1: Append the four CSS rules

**Files:**
- Modify: `src/main/resources/fitnesse/resources/ai-verify/ai-verify.css` (append at end, currently 404 lines)

- [ ] **Step 1: Confirm the file's current end**

Run: `tail -5 src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`

Expected: the last rule is

```css
.ai-prompt-save:disabled {
  background: #999 !important;
  border-color: #999 !important;
  color: #eee !important;
  cursor: default;
}
```

- [ ] **Step 2: Append the new rules**

Append exactly this block to the end of the file (after the `.ai-prompt-save:disabled` rule):

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

No comments. Do not edit any existing rule.

- [ ] **Step 3: Verify the rules are present, ordered last, and the rest is unchanged**

Run: `grep -n "textarea-toolbar .ai-verify-button\|ai-prompt-log\|ai-prompt-input-row\|height: auto" src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`

Expected:
- one match for `.editPage .textarea-toolbar .ai-verify-button {` after line 404,
- one match for `.ai-prompt-input,` / `height: auto;` / `.ai-prompt-log {` (with `flex: 7 1 0%`) / `.ai-prompt-input-row {` (with `flex: 3 1 0%`), all after line 404,
- the pre-existing `.ai-prompt-log {` occurrence near line 284 still present with `flex: 1` (so the grep shows two `.ai-prompt-log` lines: the old one and the new override).

Also run: `git diff --stat` → exactly `1 file changed`, insertions only, no deletions.

- [ ] **Step 4: Sanity-check the CSS parses in a browser context**

Run: `node -e "const css=require('fs').readFileSync('src/main/resources/fitnesse/resources/ai-verify/ai-verify.css','utf8'); let d=0; for (const c of css) { if (c==='{') d++; if (c==='}') d--; if (d<0) throw new Error('unbalanced'); } if (d!==0) throw new Error('unbalanced at EOF: '+d); console.log('balanced, rules:', (css.match(/\\{/g)||[]).length);"`

Expected: `balanced, rules: <N>` with N ≥ 30 and no thrown error.

- [ ] **Step 5: Confirm the Java suite is unaffected**

Run: `mvn -q test`
Expected: `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0` and BUILD SUCCESS (check `grep -h 'Tests run' target/surefire-reports/*.txt`).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/fitnesse/resources/ai-verify/ai-verify.css
git commit -m "fix: size Ai Prompt toolbar button like its neighbours and split modal chat 70/30"
```

---

### Task 2: Rebuild, redeploy, and measure the fixes

**Files:**
- Build: `target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`
- Runtime: `/tmp/fitnesse-smoke/plugins/`, instance on port 8091
- Measurement script: create `/tmp/measure-sizing.js` (throwaway, not committed)

- [ ] **Step 1: Package and redeploy**

Run:
```bash
mvn -q -DskipTests package
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
lsof -ti tcp:8091 | xargs kill; sleep 2
(cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &)
sleep 8
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8091/FrontPage'
```
Expected: `200`.

- [ ] **Step 2: Confirm the new CSS is served**

Run: `curl -s 'http://localhost:8091/files/fitnesse/ai-verify/ai-verify.css' | grep -c "textarea-toolbar .ai-verify-button\|flex: 3 1 0%"`
Expected: `2`.

- [ ] **Step 3: Write the measurement script**

Create `/tmp/measure-sizing.js` with exactly:

```js
const { spawn } = require('child_process');
const fs = require('fs');

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9446;
const WIDTH = parseInt(process.argv[2] || '0', 10);
const HEIGHT = parseInt(process.argv[3] || '0', 10);
const OUT = process.argv[4] || '/tmp/measure-sizing-out.json';

const args = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, '--user-data-dir=/tmp/chrome-sizing-profile'];
if (WIDTH && HEIGHT) args.push(`--window-size=${WIDTH},${HEIGHT}`);
args.push('about:blank');

const chrome = spawn(CHROME, args, { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  let version;
  for (let i = 0; i < 50; i++) {
    try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; }
    catch (e) { await sleep(200); }
  }
  if (!version) throw new Error('chrome did not start');

  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
  };
  await new Promise(r => ws.onopen = r);
  const send = (method, params) => new Promise(resolve => {
    const myId = ++id;
    pending.set(myId, resolve);
    ws.send(JSON.stringify({ id: myId, method, params: params || {} }));
  });

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: 'http://localhost:8091/FrontPage?edit' });
  await sleep(4000);

  const expr = `(async () => {
    const rect = el => { const r = el.getBoundingClientRect(); return { top: Math.round(r.top), left: Math.round(r.left), width: Math.round(r.width), height: Math.round(r.height) }; };
    const style = (el, props) => { const s = getComputedStyle(el); const o = {}; for (const p of props) o[p] = s[p]; return o; };
    const out = { viewport: { w: window.innerWidth, h: window.innerHeight } };
    const ids = ['tt-ai-prompt', 'tt-wiki-to-spreadsheet', 'tt-format-wiki'];
    out.toolbar = {};
    for (const id of ids) {
      const el = document.getElementById(id);
      out.toolbar[id] = { rect: rect(el), style: style(el, ['height', 'fontSize', 'margin', 'padding', 'borderRadius', 'verticalAlign']) };
    }
    document.getElementById('tt-ai-prompt').click();
    await new Promise(r => setTimeout(r, 600));
    const sel = ['.ai-prompt-chat', '.ai-prompt-log', '.ai-prompt-input-row', '.ai-prompt-input', '.ai-prompt-send', '.ai-prompt-content', '.ai-prompt-side'];
    out.modal = {};
    for (const s of sel) {
      const el = document.querySelector(s);
      out.modal[s] = el ? { rect: rect(el), style: style(el, ['height', 'flex', 'minHeight']) } : null;
    }
    const chat = out.modal['.ai-prompt-chat'].rect.height;
    out.ratio = {
      logPct: +(out.modal['.ai-prompt-log'].rect.height / chat * 100).toFixed(1),
      inputRowPct: +(out.modal['.ai-prompt-input-row'].rect.height / chat * 100).toFixed(1)
    };
    document.querySelector('.ai-verify-close').click();
    return out;
  })()`;

  const result = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  fs.writeFileSync(OUT, JSON.stringify(result.result.result.value, null, 2));
  console.log('written ' + OUT);
  ws.close();
  chrome.kill();
}

main().catch(e => { console.error(e); chrome.kill(); process.exit(1); });
```

- [ ] **Step 4: Measure at the default window size**

Run: `node /tmp/measure-sizing.js && cat /tmp/measure-sizing-out.json`
Expected values:
- `toolbar["#tt-ai-prompt"].rect.height` = 31; `.style.height` = `31px`; `.style.fontSize` = `14px`; `.style.margin` starts with `3px 0px 6px`.
- `toolbar["#tt-ai-prompt"].rect.top` equals `toolbar["#tt-format-wiki"].rect.top` and `toolbar["#tt-wiki-to-spreadsheet"].rect.top` (all three on the same row).
- `ratio.logPct` ≈ 70 (accept 68-72), `ratio.inputRowPct` ≈ 30 (accept 28-32), and the two sum to 100 (±1 for rounding).
- `.ai-prompt-input` height is small (roughly the input row height minus ~13px padding; must be well under 100px).
- `.ai-prompt-content` height still fills `.ai-prompt-side` (height ≈ side height minus the label).

- [ ] **Step 5: Measure at a second window size**

Run: `node /tmp/measure-sizing.js 1600 900 /tmp/measure-sizing-large.json && cat /tmp/measure-sizing-large.json`
Expected: `ratio.logPct` ≈ 70 and `ratio.inputRowPct` ≈ 30 again (68-72 / 28-32), confirming the split is proportional, not fixed pixels, and the toolbar button stays 31px/14px with aligned tops.

- [ ] **Step 6: Confirm no regression elsewhere**

Check the Verify with AI button kept its original size (the new rule must not leak into `#save_buttons`). Reuse the script by pointing it at that page and reading the computed style via a one-off CDP run, or simpler: run this grep-based check that the scope is correct, then confirm visually in the browser.

Run: `grep -n "textarea-toolbar .ai-verify-button" src/main/resources/fitnesse/resources/ai-verify/ai-verify.css`
Expected: exactly one match, and it starts with `.editPage .textarea-toolbar` (so `#save_buttons` cannot match).

Then in a browser on `http://localhost:8091/FrontPage?edit`:
- `#save_buttons` **Verify with AI** button is unchanged (38px tall).
- The Ai Prompt modal still opens with the current page content in the right panel.
- The toolbar Ai Prompt button visually matches its neighbours (same height, aligned on the row).

- [ ] **Step 7: Report**

Report the measured JSON values for both window sizes (toolbar rects/styles and modal ratio), plus the manual confirmation. There is no commit in this task.

---

## Notes for the implementer

- No comments in CSS; append only; keep the existing `.ai-prompt-log` rule intact (the new one overrides it by source order at equal specificity).
- Colours of the toolbar button still come from `.ai-verify-button`; the new scoped rule must not set any colour property.
- The `#save_buttons` Verify with AI button must remain 38px/16px — the new rule is scoped under `.editPage .textarea-toolbar`, so `#save_buttons` is unaffected.
- Do not modify `ai-verify.js` or any Java file.
