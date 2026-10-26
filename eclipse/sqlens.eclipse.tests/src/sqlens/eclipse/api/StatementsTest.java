package sqlens.eclipse.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public class StatementsTest {

    private static List<String> texts(String sql) {
        return Statements.split(sql).stream().map(r -> sql.substring(r.from(), r.to())).toList();
    }

    /** The statement around the {@code |} marker, which is not part of the text. */
    private static String at(String marked) {
        int pos = marked.indexOf('|');
        String text = marked.substring(0, pos) + marked.substring(pos + 1);
        Statements.Range r = Statements.at(text, pos);
        return r == null ? null : text.substring(r.from(), r.to());
    }

    @Test
    public void splitsAtSemicolonsAndBlankLines() {
        assertEquals(List.of("select 1", "select 2", "select 3\nfrom t", "select 4"),
                texts("select 1; select 2;\n\nselect 3\nfrom t\n\n\nselect 4"));
    }

    @Test
    public void splitsWindowsLineEnds() {
        assertEquals(List.of("select 1", "select 2"), texts("select 1\r\n\r\nselect 2"));
    }

    @Test
    public void ignoresSemicolonsInStringsQuotedNamesAndComments() {
        assertEquals(List.of("select 'a;''b' from \"x;y\" -- c;d\nwhere 1=1", "/* e;f */ select 2"),
                texts("select 'a;''b' from \"x;y\" -- c;d\nwhere 1=1; /* e;f */ select 2"));
    }

    @Test
    public void picksTheStatementUnderTheCursor() {
        assertEquals("select 2", at("select 1;\nsel|ect 2;\nselect 3"));
        assertEquals("select 1", at("select 1;|  \n\nselect 2"));
        assertEquals("select 1", at("select 1;\n|\n\n\nselect 2"));
        assertNull(at("|"));
    }
}
