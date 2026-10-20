import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { splitStatements, statementAt } from '../statement';

function texts(sql: string): string[] {
  return splitStatements(sql).map(r => sql.slice(r.from, r.to));
}

function at(sql: string): string | null {
  const pos = sql.indexOf('|');
  const text = sql.slice(0, pos) + sql.slice(pos + 1);
  const r = statementAt(text, pos);
  return r && text.slice(r.from, r.to);
}

test('splits at semicolons and blank lines', () => {
  assert.deepEqual(texts('select 1; select 2;\n\nselect 3\nfrom t\n\n\nselect 4'),
    ['select 1', 'select 2', 'select 3\nfrom t', 'select 4']);
});

test('ignores semicolons in strings, quoted names and comments', () => {
  assert.deepEqual(texts(`select 'a;''b' from "x;y" -- c;d\nwhere 1=1; /* e;f */ select 2`),
    [`select 'a;''b' from "x;y" -- c;d\nwhere 1=1`, '/* e;f */ select 2']);
});

test('picks the statement under the cursor', () => {
  assert.equal(at('select 1;\nsel|ect 2;\nselect 3'), 'select 2');
  assert.equal(at('select 1;|  \n\nselect 2'), 'select 1');
  assert.equal(at('select 1;\n|\n\n\nselect 2'), 'select 1');
  assert.equal(at('|'), null);
});
