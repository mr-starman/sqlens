package sqlens.jdbc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from artist",
            "  SELECT name FROM t ORDER BY name DESC;  ",
            "-- list them\nselect 1",
            "/* hi */ select 1 /* bye */",
            "(select 1) union (select 2)",
            "with a as (select 1 x) select * from a",
            "select 'insert into t values (1)' as sql_text",
            "select \"update\" from t",
            "select `delete` from t",
            "select [drop] from t",
            "select updated_at, deleted from t",
            "show tables",
            "describe artist",
            "explain select * from t",
            "values (1), (2)",
            "select 'it''s; delete from t' from dual",
            "select 1; -- trailing comment",
    })
    void allowsReads(String sql) {
        assertNull(ReadOnlyGuard.problem(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "insert into t values (1)",
            "UPDATE t SET a = 1",
            "delete from t",
            "drop table t",
            "create table x (a int)",
            "truncate table t",
            "merge into t using s on (1=1) when matched then delete",
            "with x as (delete from t returning *) select * from x",
            "with x as (select 1) insert into t select * from x",
            "select * into backup from t",
            "explain analyze delete from t",
            "select * from t for update",
            "select 1; delete from t",
            "select 1;\ndrop table t;",
            "call do_stuff()",
            "exec sp_who",
            "/* select */ delete from t",
            "-- select\ndelete from t",
            "",
            "  -- only a comment",
    })
    void rejectsWrites(String sql) {
        assertNotNull(ReadOnlyGuard.problem(sql), sql);
    }

    @Test
    void reportsReadOnlyState() {
        SQLException e = assertThrows(SQLException.class, () -> ReadOnlyGuard.check("chinook", "delete from artist"));
        assertEquals(ReadOnlyGuard.SQL_STATE, e.getSQLState());
        assertTrue(e.getMessage().contains("chinook"), e.getMessage());
        assertTrue(e.getMessage().contains("DELETE"), e.getMessage());
    }
}
