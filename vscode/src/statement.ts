/**
 * Finds the statement to run for a cursor position, like the web UI: statements
 * end at a `;` outside strings, quoted names and comments, and are further cut
 * at blank lines so a scratchpad of queries without semicolons works too.
 */
export interface Range {
  from: number;
  to: number;
}

/** Returns the statement around the cursor offset, or null when the text has none. */
export function statementAt(text: string, pos: number): Range | null {
  const statements = splitStatements(text);
  if (statements.length === 0) return null;

  // Inside a statement (or touching its end), else the nearest one before the
  // cursor on the same line, else the closest one.
  const inside = statements.find(s => pos >= s.from && pos <= s.to);
  if (inside) return inside;
  const lineStart = text.lastIndexOf('\n', pos - 1) + 1;
  const before = statements.filter(s => s.to <= pos && s.to >= lineStart).pop();
  if (before) return before;
  const distance = (s: Range) => Math.min(Math.abs(s.from - pos), Math.abs(s.to - pos));
  return statements.reduce((best, s) => (distance(s) < distance(best) ? s : best));
}

/** Every statement in the text, trimmed, without its terminating `;`. */
export function splitStatements(text: string): Range[] {
  const pieces: Range[] = [];
  for (const r of semicolonRanges(text)) {
    const blank = /\n[ \t]*\r?\n\s*/g;
    const part = text.slice(r.from, r.to);
    let start = 0;
    let m: RegExpExecArray | null;
    while ((m = blank.exec(part)) !== null) {
      pieces.push({ from: r.from + start, to: r.from + m.index });
      start = m.index + m[0].length;
    }
    pieces.push({ from: r.from + start, to: r.to });
  }
  return pieces.map(p => trim(text, p)).filter(p => p.to > p.from);
}

/**
 * Splits at `;`, skipping 'strings', "quoted names", `backticks`, [brackets],
 * -- line comments and block comments. Blank lines inside a string or comment
 * still cut the statement later on, the same as in the web UI.
 */
function semicolonRanges(text: string): Range[] {
  const ranges: Range[] = [];
  let start = 0;
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "'" || c === '"' || c === '`') {
      i = skipQuoted(text, i, c);
    } else if (c === '[') {
      i = skipQuoted(text, i, ']');
    } else if (c === '-' && text[i + 1] === '-') {
      const end = text.indexOf('\n', i);
      i = end < 0 ? text.length : end;
    } else if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 2;
    } else if (c === ';') {
      ranges.push({ from: start, to: i });
      start = ++i;
    } else {
      i++;
    }
  }
  ranges.push({ from: start, to: text.length });
  return ranges;
}

/** Index after the closing quote; a doubled quote is an escaped one. */
function skipQuoted(text: string, open: number, close: string): number {
  let i = open + 1;
  while (i < text.length) {
    if (text[i] === close) {
      if (text[i + 1] === close && close !== ']') {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return text.length;
}

function trim(text: string, { from, to }: Range): Range {
  while (from < to && /\s/.test(text[from])) from++;
  while (to > from && /\s/.test(text[to - 1])) to--;
  return { from, to };
}
