/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL风格的RETURNS SETOF/RETURNS TABLE函数。
 */
public class PgSetofTest extends SqlTestBase {

    public PgSetofTest() {
        setEmbedded(true);
    }

    // RETURNS SETOF标量: SELECT * FROM f(a)返回多行
    @Test
    public void testReturnsSetof() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION setof_int(a INT) RETURNS SETOF INT LANGUAGE SQL "
                + "AS $$ SELECT id FROM pgs_user WHERE id >= a ORDER BY id $$");

        // 表函数形式返回多行
        assertEquals("[2, 3]", queryIntList("SELECT * FROM setof_int(2)").toString());
        // 只有一行
        assertEquals("[3]", queryIntList("SELECT * FROM setof_int(3)").toString());
        // 没有行时返回空集
        assertEquals("[]", queryIntList("SELECT * FROM setof_int(99)").toString());
        // 空集上做聚合
        assertEquals("[0]", queryIntList("SELECT count(*) FROM setof_int(99)").toString());

        // 列名和PostgreSQL一样用函数名
        try (ResultSet result = stmt.executeQuery("SELECT * FROM setof_int(2)")) {
            assertEquals("SETOF_INT", result.getMetaData().getColumnName(1));
        }

        // FROM里带参数调用: 用PreparedStatement绑定参数, 同一个语句用不同参数重复执行
        try (PreparedStatement prep = conn.prepareStatement(
                "SELECT * FROM setof_int(?) ORDER BY 1")) {
            prep.setInt(1, 2);
            try (ResultSet result = prep.executeQuery()) {
                assertEquals("[2, 3]", getIntList(result).toString());
            }
            prep.setInt(1, 3);
            try (ResultSet result = prep.executeQuery()) {
                assertEquals("[3]", getIntList(result).toString());
            }
        }

        // CALL也能拿到整个结果集
        assertEquals("[2, 3]", queryIntList("CALL setof_int(2)").toString());

        // 和WHERE组合
        assertEquals("[3]",
                queryIntList("SELECT * FROM setof_int(1) t WHERE t.SETOF_INT > 2").toString());

        // 函数表支持列别名列表
        assertEquals("[2, 3]",
                queryIntList("SELECT * FROM setof_int(2) AS t(n) ORDER BY 1").toString());

        // 函数体是VALUES时也返回多行
        executeUpdate("CREATE OR REPLACE FUNCTION setof_values() RETURNS SETOF INT "
                + "AS $$ VALUES (1), (2), (3) $$");
        assertEquals("[1, 2, 3]", queryIntList("SELECT * FROM setof_values()").toString());

        // 标量函数也能当表函数用, 只有一行一列(见PgFunctionFromTest)
        executeUpdate("CREATE OR REPLACE FUNCTION scalar_fn(a INT) RETURNS INT AS $$ SELECT a $$");
        assertEquals("[1]", queryIntList("SELECT * FROM scalar_fn(1)").toString());

        // select列表里的setof函数按PostgreSQL语义展开成多行(ProjectSet算子)
        assertEquals("[1, 2, 3]", queryIntList("SELECT setof_int(1)").toString());
        // 每条记录各自展开: 实参引用当前行的列
        assertEquals("[1:1, 1:2, 1:3, 2:2, 2:3, 3:3]",
                queryRowList("SELECT id, setof_int(id) FROM pgs_user").toString());
    }

    // RETURNS TABLE: 多列多行, 列名用声明的名字
    @Test
    public void testReturnsTable() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION setof_table(a INT) "
                + "RETURNS TABLE(id INT, name VARCHAR(50)) LANGUAGE SQL "
                + "AS $$ SELECT id, name FROM pgs_user WHERE id >= a $$");

        try (ResultSet result = stmt.executeQuery("SELECT * FROM setof_table(2) ORDER BY 1")) {
            ResultSetMetaData meta = result.getMetaData();
            assertEquals(2, meta.getColumnCount());
            assertEquals("ID", meta.getColumnName(1));
            assertEquals("NAME", meta.getColumnName(2));
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getInt(1) + ":" + result.getString(2));
            }
            assertEquals("[2:bob, 3:cindy]", list.toString());
        }

        // 和表JOIN
        assertEquals("[bob, cindy]",
                queryStringList("SELECT u.name FROM pgs_user u JOIN setof_table(2) t ON u.id = t.id"
                        + " ORDER BY u.id").toString());
        // WHERE里引用函数返回的列
        assertEquals("[bob, cindy]",
                queryStringList("SELECT name FROM setof_table(1) t WHERE t.id > 1 ORDER BY name")
                        .toString());
        // 函数体没有行时返回空集
        assertEquals("[]", queryStringList("SELECT name FROM setof_table(99)").toString());

        // 函数体用VALUES
        executeUpdate("CREATE OR REPLACE FUNCTION table_values() RETURNS TABLE(a INT, b VARCHAR) "
                + "AS $$ VALUES (1, 'a'), (2, 'b') $$");
        assertEquals("[1:a, 2:b]",
                queryRowList("SELECT * FROM table_values() ORDER BY 1").toString());

        // 各种类型的转换
        executeUpdate("CREATE OR REPLACE FUNCTION table_types() "
                + "RETURNS TABLE(li BIGINT, d DOUBLE, s VARCHAR, b BOOLEAN) "
                + "AS $$ SELECT 1234567890123, 1.5, 'hello', TRUE $$");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM table_types()")) {
            assertTrue(result.next());
            assertEquals(1234567890123L, result.getLong(1));
            assertEquals(1.5, result.getDouble(2), 0);
            assertEquals("hello", result.getString(3));
            assertTrue(result.getBoolean(4));
            assertFalse(result.next());
        }

        // NULL值
        executeUpdate("CREATE OR REPLACE FUNCTION table_nulls() RETURNS TABLE(a INT, b VARCHAR) "
                + "AS $$ SELECT NULL, NULL $$");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM table_nulls()")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
            assertTrue(result.wasNull());
            assertNull(result.getString(2));
            assertFalse(result.next());
        }

        // 函数表支持列别名列表
        try (ResultSet result = stmt.executeQuery(
                "SELECT * FROM setof_table(2) AS t(x, y)")) {
            assertEquals("X", result.getMetaData().getColumnName(1));
            assertEquals("Y", result.getMetaData().getColumnName(2));
        }

        // 函数体返回的列数和声明的不一致时报错
        executeUpdate("CREATE OR REPLACE FUNCTION bad_table(a INT) RETURNS TABLE(x INT, y INT) "
                + "AS $$ SELECT a $$");
        executeQueryThanAssertErrorCode("SELECT * FROM bad_table(1)",
                ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);

        // 结果列名重复
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION dup_column(a INT) RETURNS TABLE(x INT, X INT) "
                        + "AS $$ SELECT a, a $$",
                ErrorCode.SYNTAX_ERROR_1);
    }

    // 重开库后函数仍然可用, getCreateSQL生成的语句能被parser原样回放
    @Test
    public void testPersistence() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION persist_setof(a INT) RETURNS SETOF INT "
                + "AS $$ SELECT id FROM pgs_user WHERE id >= a ORDER BY id $$");
        executeUpdate("CREATE OR REPLACE FUNCTION persist_table(a INT) "
                + "RETURNS TABLE(id INT, name VARCHAR(50)) "
                + "AS $$ SELECT id, name FROM pgs_user WHERE id >= a $$");
        assertEquals("[2, 3]", queryIntList("SELECT * FROM persist_setof(2)").toString());
        assertEquals("[2:bob, 3:cindy]",
                queryRowList("SELECT id, name FROM persist_table(2) ORDER BY 1").toString());

        // 用getCreateSQL生成的语句重建
        String createSql = getCreateSql("PERSIST_TABLE");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains("RETURNS TABLE("));
        executeUpdate("DROP FUNCTION persist_table");
        executeQueryThanAssertErrorCode("SELECT * FROM persist_table(2)",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate(createSql);
        assertEquals("[2:bob, 3:cindy]",
                queryRowList("SELECT id, name FROM persist_table(2) ORDER BY 1").toString());

        // 关库重开
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        assertEquals("[2, 3]", queryIntList("SELECT * FROM persist_setof(2)").toString());
        assertEquals("[2:bob, 3:cindy]",
                queryRowList("SELECT id, name FROM persist_table(2) ORDER BY 1").toString());
        String setofSql = getCreateSql("PERSIST_SETOF");
        assertTrue(setofSql, setofSql.contains("RETURNS SETOF INT"));
    }

    private void createTable() {
        executeUpdate("DROP TABLE IF EXISTS pgs_user");
        executeUpdate("CREATE TABLE pgs_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pgs_user VALUES(1, 'alice'), (2, 'bob'), (3, 'cindy')");
    }

    private ArrayList<Integer> queryIntList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            return getIntList(result);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryStringList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getString(1));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryRowList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getInt(1) + ":" + result.getString(2));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private static ArrayList<Integer> getIntList(ResultSet result) {
        ArrayList<Integer> list = new ArrayList<>();
        try {
            while (result.next()) {
                list.add(result.getInt(1));
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return list;
    }

    // 从SCRIPT的输出里找出指定函数的CREATE FUNCTION语句
    private String getCreateSql(String functionName) {
        String prefix = "CREATE FUNCTION " + functionName + "(";
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql != null && sql.startsWith(prefix)) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
