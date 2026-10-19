/* FitNesse AI Verify — button + diff modal for the wiki edit and new-page forms. */
(function () {
  'use strict';

  var state = {
    editor: null,
    contextRoot: '/',
    button: null,
    modal: null,
    sentContent: null,
    sentHash: null,
    hunks: [],
    checked: {},
    pageName: '',
    promptModal: null,
    prompt: { messages: [], busy: false },
    promptButton: null,
    promptGeneration: 0,
    promptPreviouslyFocused: null,
    previouslyFocused: null
  };

  // Capture the Wysiwyg/CodeMirror editor. The edit page initializes it inside a
  // jQuery-ready closure; our script runs before DOMContentLoaded, so patching
  // Wysiwyg.initialize here catches the instance when the closure calls it.
  if (window.Wysiwyg && !window.Wysiwyg.__aiVerifyPatched) {
    var originalInitialize = window.Wysiwyg.initialize;
    window.Wysiwyg.initialize = function (textarea) {
      var editor = originalInitialize.apply(this, arguments);
      state.editor = editor || null;
      if (editor && editor.textareaToolbar) {
        addPromptButton(editor.textareaToolbar);
      }
      return editor;
    };
    window.Wysiwyg.__aiVerifyPatched = true;
  }

  function onReady(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

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

  function detectContextRoot() {
    var link = document.querySelector('link[href*="ai-verify/ai-verify.css"]');
    if (!link) {
      return '/';
    }
    var href = link.getAttribute('href');
    var index = href.indexOf('files/fitnesse/ai-verify/');
    return index === -1 ? '/' : href.substring(0, index);
  }

  function currentPageName() {
    // New-page form: the user types the page name there.
    var field = document.querySelector('form input[name="pageName"]');
    if (field && field.value && field.value.trim()) {
      return field.value.trim();
    }
    var path = window.location.pathname;
    var root = state.contextRoot.replace(/\/$/, '');
    var name = path;
    if (root && path.indexOf(root) === 0) {
      name = path.substring(root.length);
    }
    name = name.replace(/^\//, '');
    try {
      name = decodeURIComponent(name);
    } catch (e) { /* keep raw */ }
    return name;
  }

  function getPageContent() {
    var textarea = document.getElementById('pageContent');
    if (state.editor) {
      if (state.editor.activeEditor() === 'wysiwyg') {
        state.editor.loadWikiText();
      }
      return state.editor.codeMirrorEditor.getValue();
    }
    return textarea ? textarea.value : null;
  }

  function setPageContent(text) {
    var textarea = document.getElementById('pageContent');
    if (state.editor) {
      state.editor.codeMirrorEditor.setValue(text);
      state.editor.codeMirrorEditor.save();
      if (state.editor.activeEditor() === 'wysiwyg') {
        state.editor.loadWysiwygDocument();
      }
    } else if (textarea) {
      textarea.value = text;
    }
  }

  // cyrb53-style hash — detects edits between verification and Apply.
  function hashContent(text) {
    var h1 = 0xdeadbeef ^ text.length;
    var h2 = 0x41c6ce57 ^ text.length;
    for (var i = 0; i < text.length; i++) {
      var ch = text.charCodeAt(i);
      h1 = Math.imul(h1 ^ ch, 2654435761);
      h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
  }

  /* Mirror of DiffServiceTest.merge — keep in lockstep with DiffService. */
  function mergeContent(originalText, hunks, checkedIds) {
    var lines = originalText.split('\n');
    var out = [];
    var cursor = 0;
    for (var i = 0; i < hunks.length; i++) {
      var hunk = hunks[i];
      var start = hunk.startLine - 1;
      while (cursor < start) {
        out.push(lines[cursor]);
        cursor++;
      }
      var replacement = checkedIds.indexOf(hunk.id) !== -1 ? hunk.correctedLines : hunk.originalLines;
      for (var j = 0; j < replacement.length; j++) {
        out.push(replacement[j]);
      }
      cursor += hunk.originalLines.length;
    }
    while (cursor < lines.length) {
      out.push(lines[cursor]);
      cursor++;
    }
    return out.join('\n');
  }

  function setBusy(busy) {
    if (!state.button) {
      return;
    }
    state.button.disabled = busy;
    var label = state.button.querySelector('.ai-verify-label');
    if (label) {
      label.textContent = busy ? 'Verifying…' : 'Verify with AI';
    }
  }

  function ensureModal() {
    if (state.modal) {
      return state.modal;
    }
    var overlay = document.createElement('div');
    overlay.className = 'ai-verify-overlay';
    overlay.innerHTML = [
      '<div class="ai-verify-modal" role="dialog" aria-modal="true" aria-labelledby="ai-verify-title">',
      '  <div class="ai-verify-header">',
      '    <h2 id="ai-verify-title"></h2>',
      '    <span class="ai-verify-stats"></span>',
      '    <button type="button" class="ai-verify-close" aria-label="Close">&#10005;</button>',
      '  </div>',
      '  <div class="ai-verify-diff"></div>',
      '  <div class="ai-verify-message" hidden></div>',
      '  <div class="ai-verify-error" hidden><pre class="ai-verify-error-text"></pre></div>',
      '  <div class="ai-verify-footer">',
      '    <button type="button" class="ai-verify-apply">Apply (0)</button>',
      '    <button type="button" class="ai-verify-select-all">Select all</button>',
      '    <button type="button" class="ai-verify-select-none">Select none</button>',
      '    <button type="button" class="ai-verify-retry" hidden>Retry</button>',
      '    <button type="button" class="ai-verify-cancel">Cancel</button>',
      '  </div>',
      '</div>'
    ].join('');
    document.body.appendChild(overlay);
    state.modal = overlay;

    overlay.addEventListener('mousedown', function (e) {
      if (e.target === overlay) {
        closeModal();
      }
    });
    overlay.querySelector('.ai-verify-close').addEventListener('click', closeModal);
    overlay.querySelector('.ai-verify-cancel').addEventListener('click', closeModal);
    overlay.querySelector('.ai-verify-apply').addEventListener('click', applySelections);
    overlay.querySelector('.ai-verify-select-all').addEventListener('click', function () {
      setAllChecked(true);
    });
    overlay.querySelector('.ai-verify-select-none').addEventListener('click', function () {
      setAllChecked(false);
    });
    overlay.querySelector('.ai-verify-retry').addEventListener('click', function () {
      closeModal();
      verifyNow();
    });
    overlay.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        closeModal();
        return;
      }
      if (e.key === 'Tab') {
        trapFocus(e, overlay);
      }
    });
    return overlay;
  }

  function trapFocus(e, overlay) {
    var focusables = overlay.querySelectorAll('button:not([hidden]), input, textarea, [href], [tabindex]:not([tabindex="-1"])');
    var visible = [];
    for (var i = 0; i < focusables.length; i++) {
      if (focusables[i].offsetParent !== null) {
        visible.push(focusables[i]);
      }
    }
    if (visible.length === 0) {
      return;
    }
    var first = visible[0];
    var last = visible[visible.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }

  function openModal(pageName, data) {
    var overlay = ensureModal();
    state.hunks = data.hunks;
    state.checked = {};
    for (var i = 0; i < data.hunks.length; i++) {
      state.checked[data.hunks[i].id] = true;
    }
    state.pageName = pageName;

    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + pageName;
    overlay.querySelector('.ai-verify-stats').textContent =
      '+' + data.stats.added + ' / −' + data.stats.removed + ' · ' + data.hunks.length + ' correction(s)';
    overlay.querySelector('.ai-verify-message').hidden = true;
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('.ai-verify-apply').hidden = false;
    overlay.querySelector('.ai-verify-select-all').hidden = false;
    overlay.querySelector('.ai-verify-select-none').hidden = false;

    renderDiff(state.sentContent, data.hunks);
    updateApplyButton();

    overlay.style.display = 'flex';
    overlay.querySelector('.ai-verify-close').focus();
  }

  function showMessageModal(message) {
    var overlay = ensureModal();
    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + state.pageName;
    overlay.querySelector('.ai-verify-stats').textContent = '';
    var diff = overlay.querySelector('.ai-verify-diff');
    diff.innerHTML = '';
    diff.hidden = true;
    var msg = overlay.querySelector('.ai-verify-message');
    msg.textContent = message;
    msg.hidden = false;
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('.ai-verify-apply').hidden = true;
    overlay.querySelector('.ai-verify-select-all').hidden = true;
    overlay.querySelector('.ai-verify-select-none').hidden = true;
    overlay.style.display = 'flex';
    overlay.querySelector('.ai-verify-close').focus();
  }

  function showErrorModal(message, details) {
    var overlay = ensureModal();
    overlay.querySelector('#ai-verify-title').textContent = 'Verify with AI — ' + state.pageName;
    overlay.querySelector('.ai-verify-stats').textContent = '';
    overlay.querySelector('.ai-verify-diff').hidden = true;
    overlay.querySelector('.ai-verify-message').hidden = true;
    var error = overlay.querySelector('.ai-verify-error');
    var text = overlay.querySelector('.ai-verify-error-text');
    if (details) {
      text.textContent = message + '\n\n' + details;
    } else {
      text.textContent = message;
    }
    error.hidden = false;
    overlay.querySelector('.ai-verify-retry').hidden = false;
    overlay.querySelector('.ai-verify-apply').hidden = true;
    overlay.querySelector('.ai-verify-select-all').hidden = true;
    overlay.querySelector('.ai-verify-select-none').hidden = true;
    if (!overlay.style.display || overlay.style.display === 'none') {
      overlay.style.display = 'flex';
      overlay.querySelector('.ai-verify-close').focus();
    }
  }

  function closeModal() {
    if (!state.modal) {
      return;
    }
    state.modal.style.display = 'none';
    if (state.previouslyFocused && state.previouslyFocused.focus) {
      state.previouslyFocused.focus();
    }
  }

  function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

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
        rows.push({ origNo: cursor + 1, origText: origLines[cursor], corrText: origLines[cursor], hunkId: null });
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
      rows.push({ origNo: cursor + 1, origText: origLines[cursor], corrText: origLines[cursor], hunkId: null });
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
              + '<input type="checkbox" class="ai-verify-hunk-check" data-hunk-id="' + hunkId + '" aria-label="Correction ' + (hunkId + 1) + '" checked /></td>'
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

  function onCheckChanged(e) {
    var id = parseInt(e.target.getAttribute('data-hunk-id'), 10);
    state.checked[id] = e.target.checked;
    var rows = state.modal.querySelectorAll('tr[data-hunk-id="' + id + '"]');
    for (var i = 0; i < rows.length; i++) {
      rows[i].classList.toggle('ai-verify-row-unchecked', !e.target.checked);
    }
    updateApplyButton();
  }

  function setAllChecked(checked) {
    var checks = state.modal.querySelectorAll('.ai-verify-hunk-check');
    for (var i = 0; i < checks.length; i++) {
      checks[i].checked = checked;
      var id = parseInt(checks[i].getAttribute('data-hunk-id'), 10);
      state.checked[id] = checked;
      var rows = state.modal.querySelectorAll('tr[data-hunk-id="' + id + '"]');
      for (var r = 0; r < rows.length; r++) {
        rows[r].classList.toggle('ai-verify-row-unchecked', !checked);
      }
    }
    updateApplyButton();
  }

  function selectedIds() {
    var ids = [];
    for (var id in state.checked) {
      if (state.checked.hasOwnProperty(id) && state.checked[id]) {
        ids.push(parseInt(id, 10));
      }
    }
    ids.sort(function (a, b) { return a - b; });
    return ids;
  }

  function updateApplyButton() {
    var apply = state.modal.querySelector('.ai-verify-apply');
    var count = selectedIds().length;
    apply.textContent = 'Apply (' + count + ')';
    apply.disabled = count === 0;
  }

  function applySelections() {
    if (hashContent(getPageContent()) !== state.sentHash) {
      showErrorModal('The page content changed since the verification. ' +
        'Close this dialog and run "Verify with AI" again.', null);
      return;
    }
    var merged = mergeContent(state.sentContent, state.hunks, selectedIds());
    setPageContent(merged);
    closeModal();
  }

  function verifyNow() {
    var content = getPageContent();
    if (content === null) {
      return;
    }
    var pageName = currentPageName();
    state.sentContent = content;
    state.sentHash = hashContent(content);
    state.pageName = pageName;
    state.previouslyFocused = document.activeElement;
    setBusy(true);

    var form = 'responder=ai-verify&payload=' +
      encodeURIComponent(JSON.stringify({ pageName: pageName, content: content }));

    fetch(window.location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form
    }).then(function (resp) {
      return resp.text().then(function (text) {
        setBusy(false);
        var data = null;
        try {
          data = JSON.parse(text);
        } catch (e) { /* not JSON */ }
        if (!resp.ok) {
          showErrorModal(data && data.error ? data.error : ('HTTP error ' + resp.status),
            data && data.details ? data.details : null);
          return;
        }
        if (!data || !Array.isArray(data.hunks)) {
          showErrorModal('Unexpected response from the server.', text.substring(0, 500));
          return;
        }
        if (data.hunks.length === 0) {
          showMessageModal('No corrections proposed.');
          return;
        }
        openModal(pageName, data);
      });
    }).catch(function (err) {
      setBusy(false);
      showErrorModal('Could not reach the FitNesse server: ' + err.message, null);
    });
  }

  function ensurePromptModal() {
    if (state.promptModal) {
      return state.promptModal;
    }
    var overlay = document.createElement('div');
    overlay.className = 'ai-verify-overlay';
    overlay.innerHTML = [
      '<div class="ai-verify-modal ai-prompt-modal" role="dialog" aria-modal="true" aria-labelledby="ai-prompt-title">',
      '  <div class="ai-verify-header">',
      '    <h2 id="ai-prompt-title"></h2>',
      '    <button type="button" class="ai-verify-close" aria-label="Close">&#10005;</button>',
      '  </div>',
      '  <div class="ai-prompt-panels">',
      '    <div class="ai-prompt-chat">',
      '      <div class="ai-prompt-log"></div>',
      '      <div class="ai-prompt-input-row">',
      '        <textarea class="ai-prompt-input" rows="3" placeholder="Describe the FitNesse page you want (Enter to send, Shift+Enter for a new line)"></textarea>',
      '        <button type="button" class="ai-prompt-send">Send</button>',
      '      </div>',
      '    </div>',
      '    <div class="ai-prompt-side">',
      '      <div class="ai-prompt-side-label">Generated content</div>',
      '      <textarea class="ai-prompt-content" wrap="off" spellcheck="false"></textarea>',
      '    </div>',
      '  </div>',
      '  <div class="ai-verify-error" hidden><pre class="ai-verify-error-text"></pre></div>',
      '  <div class="ai-verify-footer ai-prompt-footer">',
      '    <button type="button" class="ai-verify-cancel">Cancel</button>',
      '    <span class="ai-prompt-footer-actions">',
      '      <button type="button" class="ai-verify-retry" hidden>Retry</button>',
      '      <button type="button" class="ai-prompt-save">Save</button>',
      '    </span>',
      '  </div>',
      '</div>'
    ].join('');
    document.body.appendChild(overlay);
    state.promptModal = overlay;

    overlay.addEventListener('mousedown', function (e) {
      if (e.target === overlay) {
        closePromptModal();
      }
    });
    overlay.querySelector('.ai-verify-close').addEventListener('click', closePromptModal);
    overlay.querySelector('.ai-verify-cancel').addEventListener('click', closePromptModal);
    overlay.querySelector('.ai-prompt-save').addEventListener('click', savePrompt);
    overlay.querySelector('.ai-prompt-send').addEventListener('click', sendPrompt);
    overlay.querySelector('.ai-verify-retry').addEventListener('click', sendPromptRequest);
    overlay.querySelector('.ai-prompt-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendPrompt();
      }
    });
    overlay.querySelector('.ai-prompt-input').addEventListener('input', updatePromptSendButton);
    overlay.querySelector('.ai-prompt-content').addEventListener('input', updatePromptSaveButton);
    overlay.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        closePromptModal();
        return;
      }
      if (e.key === 'Tab') {
        trapFocus(e, overlay);
      }
    });
    return overlay;
  }

  function openPromptModal() {
    if (state.modal && state.modal.style.display && state.modal.style.display !== 'none') {
      return;
    }
    var overlay = ensurePromptModal();
    state.promptGeneration++;
    state.prompt = { messages: [], busy: false };
    overlay.querySelector('.ai-prompt-log').innerHTML = '';
    overlay.querySelector('.ai-prompt-input').value = '';
    overlay.querySelector('.ai-prompt-content').value = getPageContent() || '';
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;
    overlay.querySelector('#ai-prompt-title').textContent =
      'Ai Prompt to FitNesse — ' + currentPageName();
    overlay.querySelector('.ai-prompt-send').textContent = 'Send';
    updatePromptSendButton();
    updatePromptSaveButton();
    state.promptPreviouslyFocused = document.activeElement;
    overlay.style.display = 'flex';
    overlay.querySelector('.ai-prompt-input').focus();
  }

  function closePromptModal() {
    if (!state.promptModal) {
      return;
    }
    state.promptGeneration++;
    state.promptModal.style.display = 'none';
    if (state.promptButton) {
      state.promptButton.disabled = false;
    }
    state.prompt.busy = false;
    if (state.promptPreviouslyFocused && state.promptPreviouslyFocused.focus) {
      state.promptPreviouslyFocused.focus();
    }
  }

  function savePrompt() {
    var overlay = ensurePromptModal();
    setPageContent(overlay.querySelector('.ai-prompt-content').value);
    closePromptModal();
  }

  function updatePromptSendButton() {
    var overlay = ensurePromptModal();
    var input = overlay.querySelector('.ai-prompt-input');
    overlay.querySelector('.ai-prompt-send').disabled =
      state.prompt.busy || input.value.trim() === '';
  }

  function updatePromptSaveButton() {
    var overlay = ensurePromptModal();
    overlay.querySelector('.ai-prompt-save').disabled =
      overlay.querySelector('.ai-prompt-content').value === '';
  }

  function setPromptBusy(busy) {
    state.prompt.busy = busy;
    var overlay = ensurePromptModal();
    var send = overlay.querySelector('.ai-prompt-send');
    send.textContent = busy ? 'Generating…' : 'Send';
    if (state.promptButton) {
      state.promptButton.disabled = busy;
    }
    updatePromptSendButton();
  }

  function appendPromptBubble(role, text) {
    var log = ensurePromptModal().querySelector('.ai-prompt-log');
    var bubble = document.createElement('div');
    bubble.className = 'ai-prompt-bubble '
      + (role === 'user' ? 'ai-prompt-bubble-user' : 'ai-prompt-bubble-assistant');
    bubble.textContent = text;
    log.appendChild(bubble);
    log.scrollTop = log.scrollHeight;
  }

  function currentFormPageType() {
    var checked = document.querySelector('form input[name="pageType"]:checked');
    if (checked) {
      return checked.value;
    }
    var hidden = document.querySelector('form input[name="pageType"]');
    if (hidden && hidden.value) {
      return hidden.value;
    }
    var template = document.querySelector('form input[name="pageTemplate"]');
    if (template && template.value) {
      var segment = template.value.substring(template.value.lastIndexOf('.') + 1);
      if (segment.indexOf('Suite') !== -1) {
        return 'Suite';
      }
      if (segment.indexOf('Test') !== -1) {
        return 'Test';
      }
      if (segment.indexOf('Static') !== -1) {
        return 'Static';
      }
    }
    return '';
  }

  function sendPrompt() {
    var overlay = ensurePromptModal();
    var input = overlay.querySelector('.ai-prompt-input');
    var text = input.value.trim();
    if (text === '' || state.prompt.busy) {
      return;
    }
    state.prompt.messages.push({ role: 'user', content: text });
    appendPromptBubble('user', text);
    input.value = '';
    updatePromptSendButton();
    sendPromptRequest();
  }

  function sendPromptRequest() {
    var overlay = ensurePromptModal();
    if (state.prompt.busy) {
      return;
    }
    var generation = state.promptGeneration;
    setPromptBusy(true);
    overlay.querySelector('.ai-verify-error').hidden = true;
    overlay.querySelector('.ai-verify-retry').hidden = true;

    var payload = JSON.stringify({
      pageName: currentPageName(),
      pageType: currentFormPageType(),
      pageContent: overlay.querySelector('.ai-prompt-content').value,
      messages: state.prompt.messages
    });
    var form = 'responder=ai-prompt&payload=' + encodeURIComponent(payload);

    fetch(window.location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form
    }).then(function (resp) {
      return resp.text().then(function (raw) {
        if (generation !== state.promptGeneration) {
          return;
        }
        setPromptBusy(false);
        var data = null;
        try {
          data = JSON.parse(raw);
        } catch (e) { /* not JSON */ }
        if (!resp.ok) {
          showPromptError(data && data.error ? data.error : ('HTTP error ' + resp.status),
            data && data.details ? data.details : null);
          return;
        }
        if (!data || typeof data.content !== 'string') {
          showPromptError('Unexpected response from the server.', raw.substring(0, 500));
          return;
        }
        state.prompt.messages.push({ role: 'assistant', content: data.content });
        appendPromptBubble('assistant', data.content);
        overlay.querySelector('.ai-prompt-content').value = data.content;
        updatePromptSaveButton();
      });
    }).catch(function (err) {
      if (generation !== state.promptGeneration) {
        return;
      }
      setPromptBusy(false);
      showPromptError('Could not reach the FitNesse server: ' + err.message, null);
    });
  }

  function showPromptError(message, details) {
    var overlay = ensurePromptModal();
    var error = overlay.querySelector('.ai-verify-error');
    var text = overlay.querySelector('.ai-verify-error-text');
    text.textContent = details ? message + '\n\n' + details : message;
    error.hidden = false;
    overlay.querySelector('.ai-verify-retry').hidden = false;
  }

  onReady(function () {
    if (!document.getElementById('pageContent')) {
      return;
    }
    // Only on edit forms: existing pages (Wysiwyg sets responder=saveData)
    // and new-page forms (responder=addChild).
    if (!document.querySelector('form input[name="responder"][value="saveData"], form input[name="responder"][value="addChild"]')) {
      return;
    }
    var saveButtons = document.getElementById('save_buttons');
    if (!saveButtons) {
      return;
    }
    state.contextRoot = detectContextRoot();

    var button = document.createElement('button');
    button.type = 'button';
    button.id = 'ai-verify-button';
    button.className = 'button ai-verify-button';
    button.innerHTML = '<svg class="ai-verify-icon" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">'
      + '<path fill="currentColor" d="M8 0l1.8 5.2L15 7l-5.2 1.8L8 14l-1.8-5.2L1 7l5.2-1.8z"/></svg>'
      + '<span class="ai-verify-label">Verify with AI</span>';
    saveButtons.appendChild(button);
    state.button = button;
    button.addEventListener('click', verifyNow);
  });
})();
