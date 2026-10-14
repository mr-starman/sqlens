// Everything the SQLens UI uses from CodeMirror, bundled into one ES module
// at src/main/webapp/vendor/codemirror.js by build.mjs.
export { EditorState, EditorSelection, Compartment, StateEffect, StateField } from '@codemirror/state';
export {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  highlightSpecialChars, drawSelection, dropCursor, rectangularSelection, crosshairCursor,
  placeholder, Decoration
} from '@codemirror/view';
export { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
export {
  syntaxHighlighting, HighlightStyle, indentOnInput, bracketMatching, foldGutter, foldKeymap, syntaxTree, ensureSyntaxTree
} from '@codemirror/language';
export { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
export { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
export { sql, StandardSQL, PostgreSQL, MySQL, MariaSQL, MSSQL, SQLite, PLSQL } from '@codemirror/lang-sql';
export { tags } from '@lezer/highlight';
