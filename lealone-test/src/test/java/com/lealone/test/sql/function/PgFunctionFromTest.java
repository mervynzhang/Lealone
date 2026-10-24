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
        executeUpdate("DROP TABLE IF EXISTS pff_user");
        executeUpdate("CREATE TABLE pff_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pff_user VALUES(1, 'alice'), (2, 'bob')");
        assertEquals("[alice, bob]",
                queryStringList("SELECT u.name FROM pff_user u JOIN from_scalar(0) f "
                        + "ON f.FROM_SCALAR < u.id + 1 ORDER BY u.id").toString());
    }

    private int getIntValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
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
