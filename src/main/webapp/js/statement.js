import { ensureSyntaxTree } from '../vendor/codemirror.js';

/**
 * Finds the statement to run for a cursor position: the `;`-terminated
 * statement around the cursor (strings and comments are understood by the SQL
 * parser), further cut at blank lines so a scratchpad of queries without
 * semicolons works too. Returns {from, to} in document positions, or null.
 */
export function statementAt(state, pos) {
  const doc = state.doc.toString();
  const tree = ensureSyntaxTree(state, doc.length, 500);
  let ranges = [];
  if (tree) {
    for (let node = tree.topNode.firstChild; node; node = node.nextSibling) {
      if (node.name === 'Statement') ranges.push({ from: node.from, to: node.to });
    }
  }
  if (ranges.length === 0) ranges = [{ from: 0, to: doc.length }];

  // Cut each statement at blank lines.
  const pieces = [];
  for (const r of ranges) {
    const text = doc.slice(r.from, r.to);
    const blank = /\n[ \t]*\r?\n\s*/g;
    let start = 0;
    let m;
    while ((m = blank.exec(text)) !== null) {
      pieces.push({ from: r.from + start, to: r.from + m.index });
      start = m.index + m[0].length;
    }
    pieces.push({ from: r.from + start, to: r.to });
  }
  const statements = pieces
    .map(p => trim(doc, p))
    .filter(p => p.to > p.from);
  if (statements.length === 0) return null;

  // Inside a statement (or touching its end), else the nearest one before the
  // cursor on the same line, else the closest one.
  const inside = statements.find(s => pos >= s.from && pos <= s.to);
  if (inside) return inside;
  const lineStart = state.doc.lineAt(pos).from;
  const before = statements.filter(s => s.to <= pos && s.to >= lineStart).pop();
  if (before) return before;
  let best = statements[0];
  for (const s of statements) {
    const d = Math.min(Math.abs(s.from - pos), Math.abs(s.to - pos));
    if (d < Math.min(Math.abs(best.from - pos), Math.abs(best.to - pos))) best = s;
  }
  return best;
}

function trim(doc, { from, to }) {
  while (from < to && /\s/.test(doc[from])) from++;
  while (to > from && /\s/.test(doc[to - 1])) to--;
  return { from, to };
}
