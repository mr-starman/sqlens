package sqlens.jdbc;

/**
 * A result column.
 *
 * @param name     column label as reported by the driver
 * @param jdbcType standard JDBC type name, such as VARCHAR or INTEGER
 * @param dbType   the database's own type name
 * @param numeric  true for number columns, so a grid can right-align them
 */
public record ColumnInfo(String name, String jdbcType, String dbType, boolean numeric) {
}
