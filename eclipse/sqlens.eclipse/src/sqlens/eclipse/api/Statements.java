package sqlens.eclipse.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the statement to run for a cursor position, like the web UI: statements
 * end at a {@code ;} outside strings, quoted names and comments, and are further
 * cut at blank lines so a scratchpad of queries without semicolons works too.
 */
public final class Statements {

    public record Range(int from, int to) {
    }

    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\r?\\n\\s*");

    private Statements() {
    }

    /** The statement around the cursor offset, or null when the text has none. */
    public static Range at(String text, int pos) {
        List<Range> statements = split(text);
        if (statements.isEmpty()) {
            return null;
        }
        // Inside a statement (or touching its end), else the nearest one before
        // the cursor on the same line, else the closest one.
        for (Range s : statements) {
            if (pos >= s.from && pos <= s.to) {
                return s;
            }
        }
        int lineStart = pos == 0 ? 0 : text.lastIndexOf('\n', pos - 1) + 1;
        Range before = null;
        for (Range s : statements) {
            if (s.to <= pos && s.to >= lineStart) {
                before = s;
            }
        }
        if (before != null) {
            return before;
        }
        Range best = statements.get(0);
        for (Range s : statements) {
            if (distance(s, pos) < distance(best, pos)) {
                best = s;
            }
        }
        return best;
    }

    /** Every statement in the text, trimmed, without its terminating {@code ;}. */
    public static List<Range> split(String text) {
        List<Range> pieces = new ArrayList<>();
        for (Range r : semicolonRanges(text)) {
            String part = text.substring(r.from, r.to);
            Matcher blank = BLANK_LINE.matcher(part);
            int start = 0;
            while (blank.find()) {
                pieces.add(new Range(r.from + start, r.from + blank.start()));
                start = blank.end();
            }
            pieces.add(new Range(r.from + start, r.to));
        }
        List<Range> trimmed = new ArrayList<>();
        for (Range p : pieces) {
            Range t = trim(text, p);
            if (t.to > t.from) {
                trimmed.add(t);
            }
        }
        return trimmed;
    }

    /**
     * Splits at {@code ;}, skipping 'strings', "quoted names", `backticks`,
     * [brackets], -- line comments and block comments.
     */
    private static List<Range> semicolonRanges(String text) {
        List<Range> ranges = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(text, i, c);
            } else if (c == '[') {
                i = skipQuoted(text, i, ']');
            } else if (c == '-' && charAt(text, i + 1) == '-') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else if (c == '/' && charAt(text, i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (c == ';') {
                ranges.add(new Range(start, i));
                start = ++i;
            } else {
                i++;
            }
        }
        ranges.add(new Range(start, text.length()));
        return ranges;
    }

    /** Index after the closing quote; a doubled quote is an escaped one. */
    private static int skipQuoted(String text, int open, char close) {
        int i = open + 1;
        while (i < text.length()) {
            if (text.charAt(i) == close) {
                if (charAt(text, i + 1) == close && close != ']') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static char charAt(String text, int i) {
        return i < text.length() ? text.charAt(i) : '\0';
    }

    private static Range trim(String text, Range r) {
        int from = r.from;
        int to = r.to;
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        return new Range(from, to);
    }

    private static int distance(Range s, int pos) {
        return Math.min(Math.abs(s.from - pos), Math.abs(s.to - pos));
    }
}
