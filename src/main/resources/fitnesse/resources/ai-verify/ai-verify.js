/* FitNesse AI Verify — button + diff modal for the wiki edit page. */
(function () {
  'use strict';

  var CONTEXT_LINES = 3;

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
    state.button.textContent = busy ? 'Vérification…' : 'Verify with AI';
  }

  function typeLabel(type) {
    if (type === 'added') {
      return 'ajouté';
    }
    if (type === 'removed') {
      return 'supprimé';
    }
    return 'modifié';
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
      '    <button type="button" class="ai-verify-close" aria-label="Fermer">&#10005;</button>',
      '  </div>',
      '  <div class="ai-verify-diff"></div>',
      '  <div class="ai-verify-message" hidden></div>',
      '  <div class="ai-verify-error" hidden><pre class="ai-verify-error-text"></pre></div>',
      '  <div class="ai-verify-footer">',
      '    <button type="button" class="ai-verify-apply">Apply (0)</button>',
      '    <button type="button" class="ai-verify-select-all">Tout sélectionner</button>',
      '    <button type="button" class="ai-verify-select-none">Tout désélectionner</button>',
      '    <button type="button" class="ai-verify-retry" hidden>Réessayer</button>',
      '    <button type="button" class="ai-verify-cancel">Annuler</button>',
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
    var focusables = overlay.querySelectorAll('button:not([hidden]), input, [href], [tabindex]:not([tabindex="-1"])');
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

    state.previouslyFocused = document.activeElement;
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
    state.previouslyFocused = document.activeElement;
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
      state.previouslyFocused = document.activeElement;
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

  function cellLines(lines, side, type) {
    if (lines.length === 0) {
      return '<div class="ai-verify-line ai-verify-line-empty">(aucune ligne)</div>';
    }
    var html = '';
    for (var i = 0; i < lines.length; i++) {
      var cls = 'ai-verify-line';
      if (side === 'original' && type !== 'added') {
        cls += ' ai-verify-line-removed';
      }
      if (side === 'corrected' && type !== 'removed') {
        cls += ' ai-verify-line-added';
      }
      html += '<div class="' + cls + '">' + escapeHtml(lines[i]) + '</div>';
    }
    return html;
  }

  function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  function renderDiff(originalText, hunks) {
    var lines = originalText.split('\n');
    var diff = state.modal.querySelector('.ai-verify-diff');
    diff.hidden = false;
    var html = ['<div class="ai-verify-colhead"><span>Original</span><span>Proposition</span></div>'];
    var cursor = 0;

    for (var i = 0; i < hunks.length; i++) {
      var hunk = hunks[i];
      var start = hunk.startLine - 1;
      var gap = start - cursor;
      var ctxCount = Math.min(CONTEXT_LINES, Math.max(0, gap));
      if (gap - ctxCount > 0) {
        html.push('<div class="ai-verify-skipped">… ' + (gap - ctxCount) + ' ligne(s) inchangée(s) masquée(s) …</div>');
      }
      if (ctxCount > 0) {
        var before = lines.slice(start - ctxCount, start);
        html.push('<div class="ai-verify-ctx-row">'
          + '<div class="ai-verify-cell">' + contextHtml(before) + '</div>'
          + '<div class="ai-verify-cell">' + contextHtml(before) + '</div>'
          + '</div>');
      }

      var checkedAttr = ' checked';
      html.push('<div class="ai-verify-hunk-row" data-hunk-row="' + hunk.id + '">');
      html.push('<div class="ai-verify-cell ai-verify-cell-original">'
        + '<div class="ai-verify-hunk-head">Hunk ' + (hunk.id + 1) + ' — ' + typeLabel(hunk.type) + '</div>'
        + cellLines(hunk.originalLines, 'original', hunk.type)
        + '</div>');
      html.push('<div class="ai-verify-cell ai-verify-cell-corrected">'
        + '<label class="ai-verify-hunk-head">'
        + '<input type="checkbox" class="ai-verify-hunk-check" data-hunk-id="' + hunk.id + '"' + checkedAttr + ' />'
        + ' Hunk ' + (hunk.id + 1) + ' — ' + typeLabel(hunk.type) + '</label>'
        + cellLines(hunk.correctedLines, 'corrected', hunk.type)
        + (hunk.comment ? '<div class="ai-verify-comment">' + escapeHtml(hunk.comment) + '</div>' : '')
        + '</div>');
      html.push('</div>');

      cursor = start + hunk.originalLines.length;
    }

    var trailingCount = Math.min(CONTEXT_LINES, lines.length - cursor);
    var trailingHidden = (lines.length - cursor) - trailingCount;
    if (trailingHidden > 0) {
      html.push('<div class="ai-verify-skipped">… ' + trailingHidden + ' ligne(s) inchangée(s) masquée(s) …</div>');
    }
    if (trailingCount > 0) {
      var trailing = lines.slice(cursor, cursor + trailingCount);
      html.push('<div class="ai-verify-ctx-row">'
        + '<div class="ai-verify-cell">' + contextHtml(trailing) + '</div>'
        + '<div class="ai-verify-cell">' + contextHtml(trailing) + '</div>'
        + '</div>');
    }

    diff.innerHTML = html.join('');

    var checks = diff.querySelectorAll('.ai-verify-hunk-check');
    for (var c = 0; c < checks.length; c++) {
      checks[c].addEventListener('change', onCheckChanged);
    }
  }

  function contextHtml(lines) {
    var html = '';
    for (var i = 0; i < lines.length; i++) {
      html += '<div class="ai-verify-line ai-verify-line-context">' + escapeHtml(lines[i]) + '</div>';
    }
    return html;
  }

  function onCheckChanged(e) {
    var id = parseInt(e.target.getAttribute('data-hunk-id'), 10);
    state.checked[id] = e.target.checked;
    var row = state.modal.querySelector('[data-hunk-row="' + id + '"]');
    if (row) {
      row.classList.toggle('ai-verify-hunk-unchecked', !e.target.checked);
    }
    updateApplyButton();
  }

  function setAllChecked(checked) {
    var checks = state.modal.querySelectorAll('.ai-verify-hunk-check');
    for (var i = 0; i < checks.length; i++) {
      checks[i].checked = checked;
      var id = parseInt(checks[i].getAttribute('data-hunk-id'), 10);
      state.checked[id] = checked;
      var row = state.modal.querySelector('[data-hunk-row="' + id + '"]');
      if (row) {
        row.classList.toggle('ai-verify-hunk-unchecked', !checked);
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
      showErrorModal('Le contenu de la page a changé depuis la vérification. ' +
        'Fermez ce modal et relancez « Verify with AI ».', null);
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
          showErrorModal(data && data.error ? data.error : ('Erreur HTTP ' + resp.status),
            data && data.details ? data.details : null);
          return;
        }
        if (!data || !Array.isArray(data.hunks)) {
          showErrorModal('Réponse inattendue du serveur.', text.substring(0, 500));
          return;
        }
        if (data.hunks.length === 0) {
          showMessageModal('Aucune correction proposée.');
          return;
        }
        openModal(pageName, data);
      });
    }).catch(function (err) {
      setBusy(false);
      showErrorModal('Impossible de contacter le serveur FitNesse : ' + err.message, null);
    });
  }

  onReady(function () {
    if (!document.getElementById('pageContent')) {
      return;
    }
    // Only on edit pages of existing pages (Wysiwyg sets responder=saveData).
    if (!document.querySelector('form input[name="responder"][value="saveData"]')) {
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
    button.textContent = 'Verify with AI';
    saveButtons.appendChild(button);
    state.button = button;
    button.addEventListener('click', verifyNow);
  });
})();
