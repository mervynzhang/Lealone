/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL风格的函数在FROM子句里的行为: 标量函数当成一行一列的表、多OUT函数的record调用、
 * WITH ORDINALITY。
 */
public class PgFunctionFromTest extends SqlTestBase {

    public PgFunctionFromTest() {
        setEmbedded(true);
    }

    // 标量函数也能出现在FROM里: PostgreSQL把它当成只有一行一列的表
    @Test
    public void testScalarFunctionInFrom() throws Exception {
        executeUpdate(
                "CREATE OR REPLACE FUNCTION from_scalar(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeQuery("SELECT * FROM from_scalar(42)");
        assertEquals(43, getIntValue(1));

        // 列名和PostgreSQL一样用函数名
        try (ResultSet result = stmt.executeQuery("SELECT * FROM from_scalar(42)")) {
            ResultSetMetaData meta = result.getMetaData();
            assertEquals(1, meta.getColumnCount());
            assertEquals("FROM_SCALAR", meta.getColumnName(1));
            assertTrue(result.next());
            assertEquals(43, result.getInt(1));
            assertFalse(result.next());
        }

        // 返回NULL时也是一行, 不是空集
        executeUpdate("CREATE OR REPLACE FUNCTION from_scalar_null(a INT) RETURNS INT AS "
                + "$$ SELECT a + 1 $$");
        assertEquals("[null]", queryStringList("SELECT * FROM from_scalar_null(NULL)").toString());

        // 列别名列表和WHERE
        executeQuery("SELECT * FROM from_scalar(1) AS t(n)");
        assertEquals(2, getIntValue(1));
        // 标量函数在FROM里只有一行
        assertEquals(1, getIntValue("SELECT count(*) FROM from_scalar(1)"));
        executeQuery("SELECT * FROM from_scalar(1) t WHERE t.FROM_SCALAR > 1");
        assertEquals(2, getIntValue(1));

        // plpgsql + 单个OUT参数(PostgreSQL里OUT参数就是返回列, 该函数是标量函数)
        executeUpdate("CREATE OR REPLACE FUNCTION from_plpgsql_out(i INT, OUT j INT) RETURNS INT "
                + "AS $$ BEGIN j := i + 1; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT * FROM from_plpgsql_out(42)");
        assertEquals(43, getIntValue(1));
        executeQuery("SELECT from_plpgsql_out(42)");
        assertEquals(43, getIntValue(1));

        // 不带参数的标量函数
        executeUpdate("CREATE OR REPLACE FUNCTION from_scalar_noarg() RETURNS VARCHAR "
                + "AS $$ SELECT 'hello' $$");
        executeQuery("SELECT * FROM from_scalar_noarg()");
        assertEquals("hello", getStringValue(1));

        // 和真实表JOIN
        createUserTable();
        assertEquals("[alice, bob]",
                queryStringList("SELECT u.name FROM pff_user u JOIN from_scalar(0) f "
                        + "ON f.FROM_SCALAR < u.id + 1 ORDER BY u.id").toString());
    }

    // 多个OUT参数时函数返回record: SELECT f(...)得到一行记录, FROM里展开成多列
    @Test
    public void testMultipleOutRecord() throws Exception {
        createUserTable();
        executeUpdate("CREATE OR REPLACE FUNCTION from_record(i INT, OUT j INT, OUT k TEXT) "
                + "AS $$ SELECT i + 1, 'foo' $$");
        // 值上下文里record用ValueArray表示, getString是"(43, foo)"
        executeQuery("SELECT from_record(42)");
        assertEquals("(43, foo)", getStringValue(1));
        // 表上下文里还是多列
        try (ResultSet result = stmt.executeQuery("SELECT * FROM from_record(42)")) {
            ResultSetMetaData meta = result.getMetaData();
            assertEquals(2, meta.getColumnCount());
            assertEquals("J", meta.getColumnName(1));
            assertEquals("K", meta.getColumnName(2));
            assertTrue(result.next());
            assertEquals(43, result.getInt(1));
            assertEquals("foo", result.getString(2));
            assertFalse(result.next());
        }
        // 和普通列一起出现在SELECT列表里
        executeQuery("SELECT id, from_record(id) FROM pff_user ORDER BY id");
        assertEquals(1, getIntValue(1));
        assertEquals("(2, foo)", getStringValue(2));

        // plpgsql的多OUT函数
        executeUpdate("CREATE OR REPLACE FUNCTION from_record_plpgsql(i INT, OUT j INT, OUT k TEXT) "
                + "AS $$ BEGIN j := i + 1; k := 'foo'; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT from_record_plpgsql(42)");
        assertEquals("(43, foo)", getStringValue(1));
        assertEquals("[43]", queryIntList("SELECT j FROM from_record_plpgsql(42)").toString());

        // 三列record, 其中一列是NULL
        executeUpdate("CREATE OR REPLACE FUNCTION from_record3(i INT, OUT a INT, OUT b TEXT, OUT c DATE) "
                + "AS $$ SELECT i, NULL, DATE '2020-01-02' $$");
        executeQuery("SELECT from_record3(7)");
        assertEquals("(7, null, 2020-01-02)", getStringValue(1));
        executeQuery("SELECT * FROM from_record3(7)");
        assertEquals(7, getIntValue(1));
        assertNull(getStringValue(2));
        assertEquals("2020-01-02", getStringValue(3));

        // 列别名
        try (ResultSet result = stmt.executeQuery("SELECT * FROM from_record(1) AS t(x, y)")) {
            assertEquals("X", result.getMetaData().getColumnName(1));
            assertEquals("Y", result.getMetaData().getColumnName(2));
        }

        // STRICT: 入参为NULL时返回NULL记录, FROM里是一行全NULL
        executeUpdate("CREATE OR REPLACE FUNCTION from_record_strict(i INT, OUT j INT, OUT k TEXT) "
                + "STRICT AS $$ SELECT i + 1, 'foo' $$");
        executeQuery("SELECT from_record_strict(NULL)");
        assertNull(getStringValue(1));
        executeQuery("SELECT * FROM from_record_strict(NULL)");
        assertNull(getStringValue(1));
        assertNull(getStringValue(2));
    }

    // 表函数后面可以跟WITH ORDINALITY, 自动追加一列从1开始的bigint行号
    @Test
    public void testWithOrdinality() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION ord_setof() RETURNS SETOF INT "
                + "AS $$ VALUES (10), (20), (30) $$");
        assertEquals("[10:1, 20:2, 30:3]",
                queryRowList("SELECT * FROM ord_setof() WITH ORDINALITY ORDER BY 2").toString());

        // 行号列默认叫ordinality, 类型是bigint; 也可以用列别名列表改名
        try (ResultSet result = stmt.executeQuery("SELECT * FROM ord_setof() WITH ORDINALITY")) {
            ResultSetMetaData meta = result.getMetaData();
            assertEquals(2, meta.getColumnCount());
            assertEquals("ORDINALITY", meta.getColumnName(2));
            assertEquals("BIGINT", meta.getColumnTypeName(2));
        }
        try (ResultSet result = stmt.executeQuery(
                "SELECT * FROM ord_setof() WITH ORDINALITY AS z(a, ord)")) {
            assertEquals("A", result.getMetaData().getColumnName(1));
            assertEquals("ORD", result.getMetaData().getColumnName(2));
        }

        // WHERE条件里能引用行号, 序号是过滤前算的
        assertEquals("[20:2, 30:3]",
                queryRowList("SELECT * FROM ord_setof() WITH ORDINALITY AS z(a, ord) "
                        + "WHERE ord > 1 ORDER BY ord").toString());
        assertEquals(3, getIntValue("SELECT count(*) FROM ord_setof() WITH ORDINALITY"));

        // 空集时也是0行
        executeUpdate("CREATE OR REPLACE FUNCTION ord_empty() RETURNS SETOF INT "
                + "AS $$ SELECT 1 WHERE 1 = 0 $$");
        assertEquals("[]", queryRowList("SELECT * FROM ord_empty() WITH ORDINALITY").toString());

        // 标量函数也有行号, 永远是1
        executeUpdate("CREATE OR REPLACE FUNCTION ord_scalar(a INT) RETURNS INT "
                + "AS $$ SELECT a + 1 $$");
        assertEquals("[43:1]",
                queryRowList("SELECT * FROM ord_scalar(42) WITH ORDINALITY").toString());

        // 多OUT的record函数: 返回列后面追加行号
        executeUpdate("CREATE OR REPLACE FUNCTION ord_record(i INT, OUT j INT, OUT k TEXT) "
                + "AS $$ SELECT i + 1, 'foo' $$");
        assertEquals("[43:foo:1]",
                queryRowList("SELECT * FROM ord_record(42) WITH ORDINALITY").toString());
        assertEquals("[43:foo:1]",
                queryRowList("SELECT * FROM ord_record(42) WITH ORDINALITY AS z(x, y, ord)")
                        .toString());

        // RETURNS TABLE函数
        executeUpdate("CREATE OR REPLACE FUNCTION ord_table(a INT) RETURNS TABLE(id INT, name TEXT) "
                + "AS $$ VALUES (1, 'x'), (2, 'y') $$");
        assertEquals("[1:x:1, 2:y:2]",
                queryRowList("SELECT * FROM ord_table(1) WITH ORDINALITY ORDER BY 3").toString());

        // 别名列表的列数必须和函数返回的列数一致
        executeQueryThanAssertErrorCode(
                "SELECT * FROM ord_setof() WITH ORDINALITY AS z(a, b, c)",
                ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);

        // 只在函数后面合法, 普通表后面不行
        executeUpdate("DROP TABLE IF EXISTS ord_t");
        executeUpdate("CREATE TABLE ord_t(id INT)");
        executeQueryThanAssertErrorCode("SELECT * FROM ord_t WITH ORDINALITY",
                ErrorCode.SYNTAX_ERROR_1);
    }

    // WITH ORDINALITY和函数表的列别名列表要能进视图(视图定义重新编译时不能丢)
    @Test
    public void testOrdinalityInView() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION ord_view_fn() RETURNS SETOF INT "
                + "AS $$ VALUES (10), (20), (30) $$");
        executeUpdate("DROP VIEW IF EXISTS ord_view");
        executeUpdate("CREATE VIEW ord_view AS SELECT * FROM ord_view_fn() WITH ORDINALITY AS z(a, ord)");
        assertEquals("[10:1, 20:2, 30:3]",
                queryRowList("SELECT * FROM ord_view ORDER BY 2").toString());
        String createSql = getScriptSql("CREATE FORCE VIEW PUBLIC.ORD_VIEW");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains("WITH ORDINALITY"));
        assertTrue(createSql, createSql.contains("(A, ORD)"));

        // 只有列别名列表(没有ordinality)的视图也要能重新编译
        executeUpdate("DROP VIEW IF EXISTS ord_view2");
        executeUpdate("CREATE VIEW ord_view2 AS SELECT * FROM ord_view_fn() AS t(x)");
        assertEquals("[10, 20, 30]", queryIntList("SELECT * FROM ord_view2 ORDER BY 1").toString());
        executeUpdate("DROP VIEW ord_view");
        executeUpdate("DROP VIEW ord_view2");
    }

    private ArrayList<String> queryRowList(String sql) throws Exception {
        ArrayList<String> list = new ArrayList<>();
        try (ResultSet result = stmt.executeQuery(sql)) {
            int columnCount = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder buff = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        buff.append(':');
                    }
                    buff.append(result.getString(i));
                }
                list.add(buff.toString());
            }
        }
        return list;
    }

    private void createUserTable() {
        executeUpdate("DROP TABLE IF EXISTS pff_user");
        executeUpdate("CREATE TABLE pff_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pff_user VALUES(1, 'alice'), (2, 'bob')");
    }

    // 从SCRIPT里找出指定前缀的DDL语句(视图定义会跨多行)
    private String getScriptSql(String prefix) throws Exception {
        try (ResultSet result = stmt.executeQuery("SCRIPT")) {
            while (result.next()) {
                String sql = result.getString(1);
                if (sql != null && sql.startsWith(prefix)) {
                    return sql;
                }
            }
        }
        return null;
    }

    private int getIntValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private ArrayList<Integer> queryIntList(String sql) throws Exception {
        ArrayList<Integer> list = new ArrayList<>();
        try (ResultSet result = stmt.executeQuery(sql)) {
            while (result.next()) {
                list.add(result.getInt(1));
            }
        }
        return list;
    }

    private ArrayList<String> queryStringList(String sql) throws Exception {
        ArrayList<String> list = new ArrayList<>();
        try (ResultSet result = stmt.executeQuery(sql)) {
            while (result.next()) {
                list.add(result.getString(1));
            }
        }
        return list;
    }
}
