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

    private void createUserTable() {
        executeUpdate("DROP TABLE IF EXISTS pff_user");
        executeUpdate("CREATE TABLE pff_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pff_user VALUES(1, 'alice'), (2, 'bob')");
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
