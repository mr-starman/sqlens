// Results webview. Gets {type: 'running' | 'result' | 'error'} messages from
// the extension and draws the grid. Only the rows in view are in the DOM, so
// results of tens of thousands of rows stay responsive.
(function () {
  const vscode = acquireVsCodeApi();
  const ROW_HEIGHT = 22;
  const OVERSCAN = 30;
  const status = document.getElementById('status');
  const grid = document.getElementById('grid');
  let current = null;

  window.addEventListener('message', event => {
    const message = event.data;
    if (message.type === 'running') {
      setStatus(`Running on ${message.datasource}…`);
    } else if (message.type === 'error') {
      current = null;
      grid.replaceChildren();
      setStatus(message.message, 'error');
    } else if (message.type === 'result') {
      showResult(message.result);
    }
  });

  grid.addEventListener('scroll', () => draw());
  window.addEventListener('resize', () => draw());

  function showResult(result) {
    if (result.columns.length === 0) {
      current = null;
      grid.replaceChildren();
    } else {
      current = { result, numeric: result.columns.map(c => c.numeric), shown: null };
      buildTable();
    }
    const parts = [result.datasource];
    if (result.updateCount != null && result.columns.length === 0) {
      parts.push(`${result.updateCount.toLocaleString()} row${result.updateCount === 1 ? '' : 's'} affected`);
    } else {
      parts.push(`${result.rowCount.toLocaleString()} row${result.rowCount === 1 ? '' : 's'}`);
      if (result.truncated) {
        parts.push(result.cappedAt != null
          ? `more available (grid limit ${result.cappedAt.toLocaleString()}; export for all)`
          : 'more available');
      }
    }
    parts.push(`${result.elapsedMs.toLocaleString()} ms`);
    if (result.error) {
      setStatus(`${parts.join(' · ')} · stopped: ${result.error}`, 'error', result.columns.length > 0);
    } else {
      setStatus(parts.join(' · '), '', result.columns.length > 0);
    }
  }

  function buildTable() {
    const { result, numeric } = current;
    const table = el('table');
    const thead = el('thead');
    const head = el('tr');
    head.append(el('th', 'rownum', '#'));
    result.columns.forEach((c, i) => {
      const th = el('th', numeric[i] ? 'num' : '', c.name);
      th.title = `${c.name}\n${c.dbType || ''} (${c.jdbcType})`;
      head.append(th);
    });
    thead.append(head);
    const tbody = el('tbody');
    table.append(thead, tbody);
    grid.replaceChildren(table);
    grid.scrollTop = 0;
    current.tbody = tbody;
    draw();
  }

  function draw() {
    if (!current) return;
    const { rows } = current.result;
    const start = Math.max(0, Math.floor(grid.scrollTop / ROW_HEIGHT) - OVERSCAN);
    const end = Math.min(rows.length, Math.ceil((grid.scrollTop + (grid.clientHeight || 600)) / ROW_HEIGHT) + OVERSCAN);
    if (current.shown && current.shown.start === start && current.shown.end === end) return;
    current.shown = { start, end };

    const span = current.result.columns.length + 1;
    const fragment = document.createDocumentFragment();
    fragment.append(spacer(start * ROW_HEIGHT, span));
    for (let r = start; r < end; r++) {
      const tr = el('tr');
      tr.append(el('td', 'rownum', String(r + 1)));
      rows[r].forEach((value, i) => {
        const td = el('td', current.numeric[i] || typeof value === 'number' ? 'num' : '');
        if (value === null) {
          td.className += ' null';
          td.textContent = 'NULL';
        } else {
          td.textContent = String(value);
          td.title = td.textContent;
        }
        tr.append(td);
      });
      fragment.append(tr);
    }
    fragment.append(spacer((rows.length - end) * ROW_HEIGHT, span));
    current.tbody.replaceChildren(fragment);
  }

  function setStatus(text, kind, canExport) {
    status.className = `status ${kind || ''}`;
    status.replaceChildren(el('span', 'text', text));
    if (canExport) {
      const button = el('button', '', 'Export CSV');
      button.title = 'Run the query again and save the whole result as CSV';
      button.addEventListener('click', () => vscode.postMessage({ type: 'export' }));
      status.append(button);
    }
  }

  function spacer(height, span) {
    const tr = el('tr', 'spacer');
    const td = el('td');
    td.colSpan = span;
    td.style.height = `${height}px`;
    tr.append(td);
    return tr;
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  vscode.postMessage({ type: 'ready' });
})();
