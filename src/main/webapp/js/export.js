/**
 * Export and copy for the current result, in the status bar's #result-actions.
 *
 * - Export CSV re-runs the query that filled the grid through POST api/export,
 *   with the row limit currently picked in the toolbar, and downloads the file.
 * - Copy puts the rows already in the grid on the clipboard as tab-separated
 *   text, which pastes into Excel as cells.
 *
 * Both follow the "number style" picker: comma-separated with a decimal point
 * (Excel with US/UK regional settings) or semicolon-separated with a decimal
 * comma (most of Europe). The first visit guesses from the browser's language.
 */
import { latest, onResult } from './results.js';

const STYLES = {
  point: { label: 'Comma, 1.5', title: 'Comma between fields, decimal point (Excel with US/UK regional settings)', delimiter: ',', decimalSeparator: '.' },
  comma: { label: 'Semicolon, 1,5', title: 'Semicolon between fields, decimal comma (Excel with most European regional settings)', delimiter: ';', decimalSeparator: ',' },
};

export function setupExport({ container, notice, maxRows, store }) {
  const style = el('select', 'select small');
  style.setAttribute('aria-label', 'Number style for Excel');
  for (const [value, s] of Object.entries(STYLES)) {
    const option = new Option(s.label, value);
    option.title = s.title;
    style.append(option);
  }
  style.value = STYLES[store.get('exportStyle')] ? store.get('exportStyle') : guessStyle();
  const updateStyleTitle = () => { style.title = `${STYLES[style.value].title}. Used by Copy and Export.`; };
  style.addEventListener('change', () => { store.set('exportStyle', style.value); updateStyleTitle(); });
  updateStyleTitle();

  const copy = button('Copy', COPY_ICON);
  const exportCsv = button('Export CSV', DOWNLOAD_ICON);
  const note = el('span', 'action-note');
  note.setAttribute('role', 'status');
  container.append(note, style, copy, exportCsv);

  let exporting = null;

  function update() {
    const r = latest.response;
    const hasRows = Boolean(r && Array.isArray(r.columns) && r.columns.length > 0);
    copy.disabled = !hasRows;
    exportCsv.disabled = !hasRows && !exporting;
    if (!exporting) container.classList.toggle('idle', !hasRows);
    if (hasRows) {
      const n = r.rows.length;
      copy.title = `Copy ${n.toLocaleString()} row${n === 1 ? '' : 's'} as tab-separated text, for pasting into Excel`;
    }
    if (!exporting) exportCsv.title = `Download as CSV. Runs the query again with the row limit ${limitText()}.`;
  }

  function limitText() {
    return maxRows.value === 'all' ? 'All' : Number(maxRows.value).toLocaleString();
  }

  onResult(() => { clearNote(); update(); });
  maxRows.addEventListener('change', update);
  update();

  copy.addEventListener('click', async () => {
    const r = latest.response;
    if (!r?.columns?.length) return;
    const text = toTsv(r, STYLES[style.value]);
    try {
      await writeClipboard(text);
      showNote(`Copied ${r.rows.length.toLocaleString()} row${r.rows.length === 1 ? '' : 's'}`);
    } catch (e) {
      showError(`Could not copy to the clipboard: ${e.message}`);
    }
  });

  exportCsv.addEventListener('click', async () => {
    if (exporting) {
      exporting.abort();
      return;
    }
    const { datasource, sql } = latest.request;
    const s = STYLES[style.value];
    const body = {
      datasource, sql,
      maxRows: maxRows.value === 'all' ? 'all' : Number(maxRows.value),
      format: 'csv', delimiter: s.delimiter, decimalSeparator: s.decimalSeparator,
    };

    exporting = new AbortController();
    setLabel(exportCsv, 'Cancel export');
    exportCsv.title = 'Stop the export';
    exportCsv.classList.add('busy');
    showNote(`Exporting (${limitText()} rows)…`, true);
    try {
      const response = await fetch('api/export', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: exporting.signal,
      });
      if (!response.ok) {
        const text = await response.text();
        let message;
        try { message = JSON.parse(text).error; } catch { message = `HTTP ${response.status}`; }
        throw new Error(message || `HTTP ${response.status}`);
      }
      let blob;
      try {
        blob = await response.blob();
      } catch (e) {
        if (e.name === 'AbortError') throw e;
        throw new Error('the server stopped partway through, so no file was saved. The Tomcat log has the reason.');
      }
      const name = fileName(response.headers.get('Content-Disposition')) || `${datasource}.csv`;
      download(blob, name);
      showNote(`Saved ${name} (${formatSize(blob.size)})`);
    } catch (e) {
      clearNote();
      if (e.name !== 'AbortError') showError(`Export failed: ${e.message}`);
    } finally {
      exporting = null;
      setLabel(exportCsv, 'Export CSV');
      exportCsv.classList.remove('busy');
      update();
    }
  });

  let noteTimer;
  function showNote(text, sticky) {
    clearTimeout(noteTimer);
    note.textContent = text;
    note.classList.add('shown');
    if (!sticky) noteTimer = setTimeout(clearNote, 4000);
  }
  function clearNote() {
    clearTimeout(noteTimer);
    note.classList.remove('shown');
  }
  function showError(text) {
    const box = el('div', 'message error');
    box.textContent = text;
    notice.replaceChildren(box);
  }
}

/** Tab-separated text with a header row. Quoting follows what Excel's paste understands. */
export function toTsv({ columns, rows }, { decimalSeparator }) {
  const numeric = columns.map(c => c.numeric);
  const lines = [columns.map(c => tsvField(c.name)).join('\t')];
  for (const row of rows) {
    lines.push(row.map((v, i) => tsvValue(v, numeric[i], decimalSeparator)).join('\t'));
  }
  return lines.join('\r\n') + '\r\n';
}

function tsvValue(value, numeric, decimalSeparator) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE';
  if (typeof value === 'number') {
    // Plain digits, never 1e+21 style.
    const s = Number.isFinite(value)
      ? value.toLocaleString('en-US', { useGrouping: false, maximumFractionDigits: 20 })
      : String(value);
    return decimalSeparator === '.' ? s : s.replace('.', decimalSeparator);
  }
  const s = String(value);
  if (numeric && decimalSeparator !== '.' && /^-?\d+\.\d+$/.test(s)) return s.replace('.', decimalSeparator);
  return tsvField(s);
}

function tsvField(s) {
  return /[\t\r\n"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

async function writeClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Permission denied; the older copy command below often still works.
    }
  }
  // Plain http on a host other than localhost has no Clipboard API.
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0';
  document.body.append(area);
  area.select();
  try {
    if (!document.execCommand('copy')) throw new Error('the browser refused');
  } finally {
    area.remove();
  }
}

function guessStyle() {
  try {
    return new Intl.NumberFormat(navigator.language).format(1.5).includes(',') ? 'comma' : 'point';
  } catch {
    return 'point';
  }
}

function fileName(disposition) {
  const match = /filename="?([^";]+)"?/i.exec(disposition || '');
  return match ? match[1] : null;
}

function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

const COPY_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>';
const DOWNLOAD_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m0 0-5-5m5 5 5-5"/><path d="M4 17v3h16v-3"/></svg>';

function button(label, icon) {
  const b = el('button', 'button small');
  b.type = 'button';
  b.innerHTML = icon;
  b.append(el('span', 'label', label));
  return b;
}

function setLabel(b, text) {
  b.querySelector('.label').textContent = text;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}
