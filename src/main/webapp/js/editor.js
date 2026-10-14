import {
  EditorState, EditorView, Compartment, StateEffect, StateField, Decoration, keymap, lineNumbers,
  highlightActiveLine, highlightActiveLineGutter, highlightSpecialChars, drawSelection, dropCursor,
  rectangularSelection, crosshairCursor, placeholder, defaultKeymap, history, historyKeymap, indentWithTab,
  syntaxHighlighting, HighlightStyle, indentOnInput, bracketMatching, searchKeymap, highlightSelectionMatches,
  autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, tags as t,
  sql, StandardSQL, PostgreSQL, MySQL, MariaSQL, MSSQL, SQLite, PLSQL
} from '../vendor/codemirror.js';
import { statementAt } from './statement.js';

/* Colours come from the CSS variables, so switching the page theme restyles
   the editor without reconfiguring it. */
const editorTheme = EditorView.theme({
  '&': { color: 'var(--text)', backgroundColor: 'var(--base)', fontSize: '14px' },
  '.cm-scroller': { fontFamily: 'var(--font-code)', lineHeight: '1.55' },
  '.cm-content': { caretColor: 'var(--rosewater)', padding: '8px 0' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--rosewater)', borderLeftWidth: '2px' },
  '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection':
    { backgroundColor: 'var(--selection)' },
  '.cm-activeLine': { backgroundColor: 'color-mix(in srgb, var(--surface0) 35%, transparent)' },
  '.cm-gutters': { backgroundColor: 'var(--base)', color: 'var(--overlay0)', border: 'none' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--lavender)' },
  '.cm-lineNumbers .cm-gutterElement': { padding: '0 12px 0 16px' },
  '.cm-matchingBracket, &.cm-focused .cm-matchingBracket':
    { backgroundColor: 'color-mix(in srgb, var(--overlay2) 25%, transparent)', outline: 'none' },
  '.cm-selectionMatch': { backgroundColor: 'color-mix(in srgb, var(--blue) 18%, transparent)' },
  '.cm-searchMatch': { backgroundColor: 'color-mix(in srgb, var(--yellow) 30%, transparent)' },
  '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: 'color-mix(in srgb, var(--peach) 40%, transparent)' },
  '.cm-placeholder': { color: 'var(--overlay0)' },
  '.cm-panels': { backgroundColor: 'var(--mantle)', color: 'var(--text)' },
  '.cm-panels input, .cm-panels button': { fontFamily: 'var(--font-ui)' },
  '.cm-textfield': { backgroundColor: 'var(--base)', border: '1px solid var(--surface1)', borderRadius: '4px' },
  '.cm-button': { backgroundImage: 'none', backgroundColor: 'var(--surface0)', border: '1px solid var(--surface1)', borderRadius: '4px' },
  '.cm-tooltip': { backgroundColor: 'var(--mantle)', border: '1px solid var(--surface0)', color: 'var(--text)' },
  '.cm-tooltip-autocomplete > ul': { fontFamily: 'var(--font-code)' },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: 'var(--surface0)', color: 'var(--text)' },
  '.cm-completionDetail': { color: 'var(--overlay1)' },
  '.cm-ran': { backgroundColor: 'color-mix(in srgb, var(--mauve) 22%, transparent)', transition: 'background-color 0.4s' },
});

/* Catppuccin's syntax colour conventions. */
const highlight = HighlightStyle.define([
  { tag: t.keyword, color: 'var(--mauve)' },
  { tag: t.typeName, color: 'var(--yellow)' },
  { tag: t.standard(t.name), color: 'var(--blue)' },
  { tag: t.string, color: 'var(--green)' },
  { tag: t.special(t.string), color: 'var(--teal)' },
  { tag: [t.number, t.bool, t.null], color: 'var(--peach)' },
  { tag: t.special(t.name), color: 'var(--red)' },
  { tag: [t.lineComment, t.blockComment], color: 'var(--overlay2)', fontStyle: 'italic' },
  { tag: t.operator, color: 'var(--sky)' },
  { tag: [t.punctuation, t.paren, t.brace, t.squareBracket], color: 'var(--overlay2)' },
  { tag: t.name, color: 'var(--text)' },
]);

/* Briefly marks the text that was sent to the server. */
const flashEffect = StateEffect.define();
const flashField = StateField.define({
  create: () => Decoration.none,
  update(deco, tr) {
    deco = deco.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(flashEffect)) {
        deco = e.value ? Decoration.set([Decoration.mark({ class: 'cm-ran' }).range(e.value.from, e.value.to)]) : Decoration.none;
      }
    }
    return deco;
  },
  provide: f => EditorView.decorations.from(f),
});

/** SQL dialect for keyword highlighting and completion, guessed from the JDBC URL. */
export function dialectFor(url) {
  const u = (url || '').toLowerCase();
  if (u.startsWith('jdbc:postgresql:') || u.startsWith('jdbc:redshift:')) return PostgreSQL;
  if (u.startsWith('jdbc:mariadb:')) return MariaSQL;
  if (u.startsWith('jdbc:mysql:')) return MySQL;
  if (u.startsWith('jdbc:sqlserver:') || u.startsWith('jdbc:jtds:')) return MSSQL;
  if (u.startsWith('jdbc:sqlite:')) return SQLite;
  if (u.startsWith('jdbc:oracle:')) return PLSQL;
  return StandardSQL;
}

export function createEditor(parent, { doc, onRun, onChange }) {
  const language = new Compartment();
  let flashTimer;

  const runKeys = keymap.of([
    { key: 'Mod-Enter', run: () => { onRun(); return true; }, preventDefault: true },
  ]);

  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [
        runKeys,
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightSpecialChars(),
        history(),
        drawSelection(),
        dropCursor(),
        EditorState.allowMultipleSelections.of(true),
        indentOnInput(),
        bracketMatching(),
        closeBrackets(),
        autocompletion({ icons: false }),
        rectangularSelection(),
        crosshairCursor(),
        highlightActiveLine(),
        highlightSelectionMatches(),
        keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap,
          ...completionKeymap, indentWithTab]),
        language.of(sql({ dialect: StandardSQL })),
        syntaxHighlighting(highlight),
        editorTheme,
        flashField,
        placeholder('Write SQL here. Ctrl+Enter runs the selection, or the statement under the cursor.'),
        EditorView.updateListener.of(u => { if (u.docChanged) onChange(u.state.doc.toString()); }),
      ],
    }),
  });

  return {
    view,
    focus: () => view.focus(),

    setDialect(dialect) {
      view.dispatch({ effects: language.reconfigure(sql({ dialect })) });
    },

    /** The selected text, or the statement under the cursor. Marks it briefly. */
    takeStatement() {
      const { state } = view;
      const sel = state.selection.main;
      const range = sel.empty ? statementAt(state, sel.head) : { from: sel.from, to: sel.to };
      if (!range) return '';
      const text = state.sliceDoc(range.from, range.to);
      if (!text.trim()) return '';
      clearTimeout(flashTimer);
      view.dispatch({ effects: flashEffect.of(range) });
      flashTimer = setTimeout(() => view.dispatch({ effects: flashEffect.of(null) }), 700);
      return text;
    },
  };
}
