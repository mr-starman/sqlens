import { createEditor, dialectFor } from './editor.js';
import { renderGrid } from './grid.js';
import { publishResult } from './results.js';
import { setupExport } from './export.js';

const $ = id => document.getElementById(id);
const ui = {
  datasource: $('datasource'), reload: $('reload'), dsInfo: $('ds-info'), maxRows: $('max-rows'),
  run: $('run'), runLabel: $('run').querySelector('.label'), runKey: $('run-key'), theme: $('theme'),
  editor: $('editor'), splitter: $('splitter'), status: $('status'), notice: $('notice'), result: $('result'),
  resultActions: $('result-actions'),
};

const store = {
  get(key) { try { return localStorage.getItem('sqlens.' + key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem('sqlens.' + key, value); } catch { /* storage blocked */ } },
};

const DEFAULT_SQL = '-- Ctrl+Enter runs the selection, or the statement under the cursor.\n\nselect 1\n';

let datasources = [];
let grid = null;
let running = null;

const isMac = /Mac|iPhone|iPad/.test(navigator.platform);
ui.runKey.textContent = isMac ? '⌘↵' : 'Ctrl+Enter';

let saveTimer;
const editor = createEditor(ui.editor, {
  doc: store.get('editor') ?? DEFAULT_SQL,
  onRun: () => run(),
  onChange: text => { clearTimeout(saveTimer); saveTimer = setTimeout(() => store.set('editor', text), 400); },
});

/* Theme */

ui.theme.addEventListener('click', () => {
  const next = document.documentElement.dataset.theme === 'mocha' ? 'latte' : 'mocha';
  document.documentElement.dataset.theme = next;
  store.set('theme', next);
  updateThemeTitle();
});
function updateThemeTitle() {
  const dark = document.documentElement.dataset.theme === 'mocha';
  ui.theme.title = dark ? 'Switch to light theme (Catppuccin Latte)' : 'Switch to dark theme (Catppuccin Mocha)';
}
updateThemeTitle();

/* Datasources */

async function loadDatasources() {
  ui.datasource.disabled = true;
  let body;
  try {
    const response = await fetch('api/datasources', { cache: 'no-store' });
    body = await response.json();
  } catch (e) {
    ui.datasource.replaceChildren(new Option('Unavailable'));
    showMessage('error', `Could not load datasources: ${e.message}`);
    return;
  }

  datasources = body.datasources || [];
  const wanted = ui.datasource.value && datasources.some(d => d.name === ui.datasource.value)
    ? ui.datasource.value : store.get('datasource');
  ui.datasource.replaceChildren(...datasources.map(d => new Option(d.name, d.name)));
  if (datasources.length === 0) ui.datasource.append(new Option('No datasources'));
  if (datasources.some(d => d.name === wanted)) ui.datasource.value = wanted;
  ui.datasource.disabled = datasources.length === 0;
  datasourceChanged();

  const problems = [];
  if (body.error) problems.push(body.error);
  for (const p of body.driverJarErrors || []) problems.push(typeof p === 'string' ? p : JSON.stringify(p));
  if (problems.length || datasources.length === 0) {
    const box = showMessage(problems.length ? 'warn' : 'info', '');
    if (datasources.length === 0) {
      box.append('No datasources are configured. Add them to ', code(body.configFile),
        ' and put JDBC driver jars in ', code(body.driversDir), ', then reload.');
    }
    for (const p of problems) box.append(detail(p));
  } else if (!grid && !running) {
    clearResult();
    setStatus(`${datasources.length} datasource${datasources.length === 1 ? '' : 's'} · drivers: ${(body.drivers || []).join(', ') || 'none found'}`);
  }
}

function datasourceChanged() {
  const ds = datasources.find(d => d.name === ui.datasource.value);
  ui.dsInfo.textContent = ds ? [ds.description, ds.url].filter(Boolean).join(' · ') : '';
  ui.dsInfo.title = ds ? [ds.description, ds.url, ds.user && `user ${ds.user}`, ds.readOnly && 'read-only']
    .filter(Boolean).join('\n') : '';
  editor.setDialect(dialectFor(ds?.url));
  if (ds) store.set('datasource', ds.name);
}

ui.datasource.addEventListener('change', datasourceChanged);
ui.reload.addEventListener('click', loadDatasources);

/* Running queries */

ui.run.addEventListener('click', () => (running ? cancel() : run()));

async function run() {
  if (running) return;
  const datasource = ui.datasource.value;
  if (!datasources.some(d => d.name === datasource)) {
    showMessage('warn', 'Pick a datasource first.');
    return;
  }
  const sql = editor.takeStatement();
  if (!sql) {
    setStatus('Nothing to run');
    return;
  }
  const maxRows = ui.maxRows.value === 'all' ? 'all' : Number(ui.maxRows.value);
  const request = { datasource, sql, maxRows };

  const controller = new AbortController();
  running = controller;
  setRunning(true);
  const started = performance.now();
  const timer = setInterval(() => setStatus(`<spin>Running… ${formatMs(performance.now() - started)}`), 100);
  setStatus('<spin>Running…');

  let body;
  try {
    const response = await fetch('api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: controller.signal,
    });
    const text = await response.text();
    try {
      body = JSON.parse(text);
    } catch {
      body = { error: `HTTP ${response.status}: the server did not return JSON.\n${text.slice(0, 500)}` };
    }
  } catch (e) {
    body = { error: e.name === 'AbortError' ? 'Cancelled.' : `Request failed: ${e.message}`, cancelled: e.name === 'AbortError' };
  } finally {
    clearInterval(timer);
    running = null;
    setRunning(false);
  }

  showResult(body, performance.now() - started);
  publishResult(request, body);
}

function cancel() {
  running?.abort();
}

function setRunning(on) {
  ui.runLabel.textContent = on ? 'Cancel' : 'Run';
  ui.run.classList.toggle('primary', !on);
  ui.run.classList.toggle('danger', on);
  ui.run.title = on ? 'Stop waiting for this query' : 'Run the selection, or the statement under the cursor';
}

function showResult(body, clientMs) {
  const hasRows = Array.isArray(body.columns) && body.columns.length > 0;
  const elapsed = formatMs(body.elapsedMs ?? clientMs);

  if (hasRows) {
    clearResult();
    grid = renderGrid(ui.result, body);
    const parts = [`${body.rowCount.toLocaleString()} row${body.rowCount === 1 ? '' : 's'}`];
    if (body.truncated) parts.push('<warn>more rows available, raise the row limit to see them');
    parts.push(elapsed);
    if (body.error) {
      parts.push('<warn>stopped early');
      ui.notice.append(message('error', `The query failed after ${body.rowCount} rows: ${body.error}`));
    }
    setStatus(parts);
  } else if (body.error) {
    const box = showMessage(body.cancelled ? 'info' : 'error', body.error);
    if (body.sqlState || body.errorCode) {
      box.append(detail(`SQL state ${body.sqlState ?? '-'} · error code ${body.errorCode ?? '-'}`));
    }
    setStatus(body.cancelled ? 'Cancelled' : ['<warn>Failed', elapsed]);
  } else if (body.updateCount !== null && body.updateCount !== undefined) {
    showMessage('info', `${body.updateCount.toLocaleString()} row${body.updateCount === 1 ? '' : 's'} affected.`);
    setStatus(['Done', elapsed]);
  } else {
    showMessage('info', 'The statement returned no columns.');
    setStatus(['Done', elapsed]);
  }
}

/* Result area helpers */

function clearResult() {
  grid?.destroy();
  grid = null;
  ui.result.replaceChildren();
  ui.notice.replaceChildren();
}

function showMessage(kind, text) {
  clearResult();
  const box = message(kind, text);
  ui.result.append(box);
  return box;
}

function message(kind, text) {
  const box = document.createElement('div');
  box.className = `message ${kind}`;
  box.textContent = text;
  return box;
}

/** Status text: a string or list of parts; a "<warn>" or "<spin>" prefix styles a part. */
function setStatus(parts) {
  ui.status.replaceChildren();
  [].concat(parts).forEach((part, i) => {
    if (i > 0) ui.status.append(' · ');
    if (part.startsWith('<spin>')) {
      const s = document.createElement('span');
      s.className = 'spinner';
      ui.status.append(s, part.slice(6));
    } else if (part.startsWith('<warn>')) {
      const s = document.createElement('span');
      s.className = 'warn';
      s.textContent = part.slice(6);
      ui.status.append(s);
    } else {
      ui.status.append(part);
    }
  });
}

function code(text) {
  const c = document.createElement('code');
  c.textContent = text ?? '';
  return c;
}

function detail(text) {
  const d = document.createElement('div');
  d.className = 'detail';
  d.textContent = text;
  return d;
}

function formatMs(ms) {
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)} s`;
}

/* Editor / result splitter */

const savedHeight = Number(store.get('editorHeight'));
if (savedHeight > 0) ui.editor.style.height = `${Math.min(savedHeight, 85)}%`;

ui.splitter.addEventListener('pointerdown', e => {
  e.preventDefault();
  ui.splitter.setPointerCapture(e.pointerId);
  ui.splitter.classList.add('dragging');
  const top = ui.editor.getBoundingClientRect().top;
  const move = ev => {
    const pct = Math.min(85, Math.max(10, ((ev.clientY - top) / document.body.clientHeight) * 100));
    ui.editor.style.height = `${pct}%`;
  };
  const up = () => {
    ui.splitter.classList.remove('dragging');
    ui.splitter.removeEventListener('pointermove', move);
    ui.splitter.removeEventListener('pointerup', up);
    store.set('editorHeight', parseFloat(ui.editor.style.height));
  };
  ui.splitter.addEventListener('pointermove', move);
  ui.splitter.addEventListener('pointerup', up);
});

setupExport({ container: ui.resultActions, notice: ui.notice, maxRows: ui.maxRows, store });

loadDatasources();
editor.focus();
