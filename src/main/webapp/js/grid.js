/**
 * A read-only result grid. Only the rows in view are in the DOM, so results
 * with "All" rows stay responsive. Column widths are estimated from the first
 * rows and can be dragged wider or narrower on the header edge.
 */
const OVERSCAN = 30;
const MIN_WIDTH = 48;
const MAX_WIDTH = 420;
const SAMPLE_ROWS = 1000;

export function renderGrid(container, result) {
  const { columns, rows } = result;
  container.replaceChildren();

  const rowHeight = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--row-height')) || 26;
  const sample = sampleRows(rows);
  const numeric = columns.map((c, i) => c.numeric || isNumberColumn(sample, i));

  const table = el('table', 'grid');
  const colgroup = el('colgroup');
  const widths = estimateWidths(columns, sample);
  const rownumWidth = Math.max(40, String(rows.length).length * 8 + 20);
  colgroup.append(col(rownumWidth), ...widths.map(col));
  table.append(colgroup);

  const thead = el('thead');
  const headRow = el('tr');
  headRow.append(el('th', 'rownum', '#'));
  columns.forEach((c, i) => {
    const th = el('th', numeric[i] ? 'num' : '');
    th.title = `${c.name}\n${c.dbType || ''} (${c.jdbcType})`;
    const wrap = el('div', 'wrap');
    const label = el('span', 'label', c.name);
    wrap.append(label, resizer(colgroup.children[i + 1], table));
    th.append(wrap);
    headRow.append(th);
  });
  thead.append(headRow);
  table.append(thead);

  const tbody = el('tbody');
  table.append(tbody);
  updateTableWidth(table);
  container.append(table);

  const topSpacer = spacerRow(columns.length + 1);
  const bottomSpacer = spacerRow(columns.length + 1);
  let shown = { start: -1, end: -1 };

  function draw() {
    const viewTop = container.scrollTop;
    const viewHeight = container.clientHeight || 600;
    const start = Math.max(0, Math.floor(viewTop / rowHeight) - OVERSCAN);
    const end = Math.min(rows.length, Math.ceil((viewTop + viewHeight) / rowHeight) + OVERSCAN);
    if (start === shown.start && end === shown.end) return;
    shown = { start, end };

    const fragment = document.createDocumentFragment();
    topSpacer.firstChild.style.height = `${start * rowHeight}px`;
    bottomSpacer.firstChild.style.height = `${(rows.length - end) * rowHeight}px`;
    fragment.append(topSpacer);
    for (let r = start; r < end; r++) fragment.append(rowElement(rows[r], r, numeric));
    fragment.append(bottomSpacer);
    tbody.replaceChildren(fragment);
  }

  let pending = false;
  const onScroll = () => {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => { pending = false; draw(); });
  };
  container.scrollTop = 0;
  container.scrollLeft = 0;
  container.onscroll = onScroll;
  const observer = new ResizeObserver(onScroll);
  observer.observe(container);
  draw();

  return { destroy: () => { observer.disconnect(); container.onscroll = null; } };
}

function rowElement(row, index, numeric) {
  const tr = document.createElement('tr');
  const num = document.createElement('td');
  num.className = 'rownum';
  num.textContent = index + 1;
  tr.append(num);
  for (let i = 0; i < row.length; i++) {
    const value = row[i];
    const td = document.createElement('td');
    if (value === null || value === undefined) {
      td.className = 'null';
      td.textContent = 'NULL';
    } else if (typeof value === 'boolean') {
      td.className = 'bool';
      td.textContent = String(value);
    } else {
      const text = String(value);
      if (numeric[i]) td.className = 'num';
      td.textContent = text.length > 2000 ? text.slice(0, 2000) + '…' : text;
      if (text.length > 30) td.title = text.length > 4000 ? text.slice(0, 4000) + '…' : text;
    }
    tr.append(td);
  }
  return tr;
}

/** Numbers sent as strings (big integers, precise decimals) still align right. */
function isNumberColumn(sample, i) {
  let seen = false;
  for (const row of sample) {
    const v = row[i];
    if (v === null) continue;
    if (typeof v !== 'number') return false;
    seen = true;
  }
  return seen;
}

/** The first rows plus rows spread over the rest, so later wider values count too. */
function sampleRows(rows) {
  if (rows.length <= SAMPLE_ROWS) return rows;
  const head = SAMPLE_ROWS / 2;
  const sample = rows.slice(0, head);
  const step = (rows.length - head) / (SAMPLE_ROWS - head);
  for (let r = head; r < rows.length; r += step) sample.push(rows[Math.floor(r)]);
  sample.push(rows[rows.length - 1]);
  return sample;
}

let measureContext;

function textWidth(text, font) {
  measureContext ??= document.createElement('canvas').getContext('2d');
  measureContext.font = font;
  return measureContext.measureText(text).width;
}

function estimateWidths(columns, sample) {
  const style = getComputedStyle(document.documentElement);
  const code = style.getPropertyValue('--font-code');
  const ui = style.getPropertyValue('--font-ui');
  return columns.map((c, i) => {
    let width = textWidth(c.name, `600 13px ${ui}`);
    let longest = '';
    for (const row of sample) {
      const v = row[i];
      const s = v === null ? 'NULL' : String(v);
      if (s.length > longest.length) longest = s.length > 80 ? s.slice(0, 80) : s;
    }
    width = Math.max(width, textWidth(longest, `13px ${code}`));
    return Math.round(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width + 20)));
  });
}

function resizer(colEl, table) {
  const handle = el('span', 'resizer');
  handle.addEventListener('pointerdown', e => {
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
    const startX = e.clientX;
    const startWidth = parseFloat(colEl.style.width);
    const move = ev => {
      colEl.style.width = `${Math.max(MIN_WIDTH, startWidth + ev.clientX - startX)}px`;
      updateTableWidth(table);
    };
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  });
  return handle;
}

function updateTableWidth(table) {
  let total = 0;
  for (const c of table.querySelector('colgroup').children) total += parseFloat(c.style.width);
  table.style.width = `${total}px`;
}

function spacerRow(span) {
  const tr = el('tr', 'spacer');
  const td = el('td');
  td.colSpan = span;
  tr.append(td);
  return tr;
}

function col(width) {
  const c = document.createElement('col');
  c.style.width = `${width}px`;
  return c;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}
