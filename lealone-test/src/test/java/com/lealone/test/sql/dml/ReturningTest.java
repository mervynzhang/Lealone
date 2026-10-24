/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.dml;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * DML RETURNING的回归用例。
 */
public class ReturningTest extends SqlTestBase {

    public ReturningTest() {
        setEmbedded(true);
    }

    private void createRtTable(String tableName) {
        executeUpdate("DROP TABLE IF EXISTS " + tableName);
        executeUpdate("CREATE TABLE " + tableName + "(a INT PRIMARY KEY, b VARCHAR(20))");
    }

    @Test
    public void testInsertReturning() throws Exception {
        createRtTable("rt_insert");
        // executeQuery入口
        executeQuery("INSERT INTO rt_insert(a, b) VALUES(1, 'x') RETURNING a, b");
        assertEquals(1, getIntValue(1));
        assertEquals("x", getStringValue(2));
        closeResultSet();

        // execute入口，返回true并且有结果集
        try (Statement s = conn.createStatement()) {
            assertTrue(s.execute("INSERT INTO rt_insert(a, b) VALUES(2, 'y'), (3, 'z') RETURNING *"));
            try (ResultSet r = s.getResultSet()) {
                assertNotNull(r);
                assertEquals(2, r.getMetaData().getColumnCount());
                assertTrue(r.next());
                assertEquals(2, r.getInt("A"));
                assertEquals("y", r.getString("B"));
                assertTrue(r.next());
                assertEquals(3, r.getInt(1));
                assertEquals("z", r.getString(2));
                assertFalse(r.next());
            }
            assertEquals(-1, s.getUpdateCount());
        }

        // RETURNING表达式和列别名
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery(
                        "INSERT INTO rt_insert(a, b) VALUES(4, 'v') RETURNING a + 10 AS c, UPPER(b) AS u")) {
            assertTrue(r.next());
            ResultSetMetaData meta = r.getMetaData();
            assertEquals("C", meta.getColumnLabel(1));
            assertEquals("U", meta.getColumnLabel(2));
            assertEquals(14, r.getInt(1));
            assertEquals("V", r.getString(2));
            assertFalse(r.next());
        }

        // INSERT ... SELECT ... RETURNING
        createRtTable("rt_insert2");
        executeQuery("INSERT INTO rt_insert2(a, b) SELECT a, b FROM rt_insert WHERE a = 2 RETURNING a, b");
        assertEquals(2, getIntValue(1));
        assertEquals("y", getStringValue(2));
        closeResultSet();
    }

    @Test
    public void testUpdateDeleteReturning() throws Exception {
        createRtTable("rt_updel");
        executeUpdate("INSERT INTO rt_updel VALUES(1, 'a'), (2, 'b')");

        executeQuery("UPDATE rt_updel SET b = 'u' WHERE a = 2 RETURNING a, b");
        assertEquals(2, getIntValue(1));
        assertEquals("u", getStringValue(2));
        closeResultSet();

        // 没有影响行时返回0行，但列元数据还在
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("UPDATE rt_updel SET b = 'n' WHERE a = 99 RETURNING a, b")) {
            assertNotNull(r);
            assertEquals(2, r.getMetaData().getColumnCount());
            assertFalse(r.next());
        }
        try (Statement s = conn.createStatement()) {
            assertTrue(s.execute("UPDATE rt_updel SET b = 'n' WHERE a = 99 RETURNING a"));
            try (ResultSet r = s.getResultSet()) {
                assertNotNull(r);
                assertFalse(r.next());
            }
        }

        // DELETE返回被删除的旧行
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("DELETE FROM rt_updel WHERE a = 1 RETURNING *")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt("A"));
            assertEquals("a", r.getString("B"));
            assertFalse(r.next());
        }
        executeQuery("SELECT COUNT(*) FROM rt_updel");
        assertEquals(1, getIntValue(1));
        closeResultSet();
    }

    @Test
    public void testMergeReturning() throws Exception {
        createRtTable("rt_merge");
        // 走INSERT分支
        executeQuery("MERGE INTO rt_merge(a, b) KEY(a) VALUES(1, 'x') RETURNING a, b");
        assertEquals(1, getIntValue(1));
        assertEquals("x", getStringValue(2));
        closeResultSet();
        // 走UPDATE分支，返回更新后的行
        executeQuery("MERGE INTO rt_merge(a, b) KEY(a) VALUES(1, 'y') RETURNING *");
        assertEquals(1, getIntValue(1));
        assertEquals("y", getStringValue(2));
        closeResultSet();
    }

    @Test
    public void testPreparedStatement() throws Exception {
        createRtTable("rt_ps");
        try (PreparedStatement ps = conn
                .prepareStatement("INSERT INTO rt_ps(a, b) VALUES(?, ?) RETURNING a, b")) {
            ResultSetMetaData meta = ps.getMetaData();
            assertNotNull(meta);
            assertEquals(2, meta.getColumnCount());

            ps.setInt(1, 1);
            ps.setString(2, "x");
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertEquals("x", r.getString(2));
                assertFalse(r.next());
            }

            // execute入口
            ps.setInt(1, 2);
            ps.setString(2, "y");
            assertTrue(ps.execute());
            try (ResultSet r = ps.getResultSet()) {
                assertTrue(r.next());
                assertEquals(2, r.getInt("A"));
            }
        }
        // UPDATE带参数
        try (PreparedStatement ps = conn
                .prepareStatement("UPDATE rt_ps SET b = ? WHERE a = ? RETURNING b")) {
            ps.setString(1, "z");
            ps.setInt(2, 2);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals("z", r.getString(1));
            }
        }
        // 带表别名的UPDATE/DELETE RETURNING
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery(
                        "UPDATE rt_ps AS t SET b = 'a' WHERE t.a = 1 RETURNING t.a, t.b")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
            assertEquals("a", r.getString(2));
        }
        try (Statement s = conn.createStatement();
                ResultSet r = s.executeQuery("DELETE FROM rt_ps AS t WHERE t.a = 1 RETURNING t.b")) {
            assertTrue(r.next());
            assertEquals("a", r.getString(1));
        }
    }

    @Test
    public void testReturningError() throws Exception {
        createRtTable("rt_err");
        executeUpdate("INSERT INTO rt_err VALUES(1, 'a')");
        // 主键冲突时和普通INSERT一样报错并回滚
        executeUpdateThanAssertErrorCode(
                "INSERT INTO rt_err VALUES(1, 'b') RETURNING a",
                ErrorCode.DUPLICATE_KEY_1);
        executeQuery("SELECT COUNT(*) FROM rt_err");
        assertEquals(1, getIntValue(1));
        closeResultSet();
    }

    @Test
    public void testTransactionRollback() throws Exception {
        createRtTable("rt_tx");
        executeUpdate("INSERT INTO rt_tx VALUES(1, 'a')");
        try (Statement s = conn.createStatement()) {
            conn.setAutoCommit(false);
            // 事务里能拿到返回行
            assertTrue(s.execute("INSERT INTO rt_tx VALUES(2, 'b') RETURNING a"));
            try (ResultSet r = s.getResultSet()) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
            }
            assertTrue(s.execute("UPDATE rt_tx SET b = 'u' WHERE a = 1 RETURNING b"));
            try (ResultSet r = s.getResultSet()) {
                assertTrue(r.next());
                assertEquals("u", r.getString(1));
            }
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        // 回滚后看不到新行，也看不到更新
        executeQuery("SELECT COUNT(*) FROM rt_tx");
        assertEquals(1, getIntValue(1));
        closeResultSet();
        executeQuery("SELECT b FROM rt_tx WHERE a = 1");
        assertEquals("a", getStringValue(1));
        closeResultSet();
    }

    @Test
    public void testSqlFunctionBody() throws Exception {
        createRtTable("rt_func");
        executeUpdate("CREATE OR REPLACE FUNCTION rt_func_ins(x INT) RETURNS INT AS "
                + "'INSERT INTO rt_func(a, b) VALUES(x, ''f'') RETURNING a'");
        executeQuery("SELECT rt_func_ins(1)");
        assertEquals(1, getIntValue(1));
        closeResultSet();
        executeQuery("SELECT b FROM rt_func WHERE a = 1");
        assertEquals("f", getStringValue(1));
        closeResultSet();
        // 不带RETURNING的DML不能作为函数体
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION rt_func_bad(x INT) RETURNS INT AS 'INSERT INTO rt_func(a, b) VALUES(x, ''f'')'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    @Test
    public void testPlpgsqlReturningIntoRecord() throws Exception {
        createRtTable("rt_pl");
        executeUpdate("CREATE OR REPLACE FUNCTION rt_pl_ins(x INT) RETURNS INT AS $$ "
                + "DECLARE r RECORD; BEGIN "
                + "INSERT INTO rt_pl(a, b) VALUES(x, 'p') RETURNING a, b INTO r; "
                + "RETURN r.a; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT rt_pl_ins(1)");
        assertEquals(1, getIntValue(1));
        closeResultSet();
        executeQuery("SELECT b FROM rt_pl WHERE a = 1");
        assertEquals("p", getStringValue(1));
        closeResultSet();

        // RETURNING * INTO %ROWTYPE变量
        executeUpdate("CREATE OR REPLACE FUNCTION rt_pl_ins2(x INT) RETURNS VARCHAR AS $$ "
                + "DECLARE r rt_pl%ROWTYPE; BEGIN "
                + "INSERT INTO rt_pl(a, b) VALUES(x, 'q') RETURNING * INTO r; "
                + "RETURN r.b; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT rt_pl_ins2(2)");
        assertEquals("q", getStringValue(1));
        closeResultSet();

        // UPDATE没有影响行时record变成未赋值状态，字段是NULL
        executeUpdate("CREATE OR REPLACE FUNCTION rt_pl_upd(x INT) RETURNS INT AS $$ "
                + "DECLARE r RECORD; BEGIN "
                + "UPDATE rt_pl SET b = 'z' WHERE a = x RETURNING a, b INTO r; "
                + "IF r.a IS NULL THEN RETURN -1; END IF; RETURN r.a; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT rt_pl_upd(99)");
        assertEquals(-1, getIntValue(1));
        closeResultSet();
    }

    @Test
    public void testReturnQueryDmlReturning() throws Exception {
        createRtTable("rt_rq");
        executeUpdate("CREATE OR REPLACE FUNCTION rt_rq_ins(x INT) RETURNS SETOF INT AS $$ "
                + "BEGIN RETURN QUERY INSERT INTO rt_rq(a, b) VALUES(x, 'r') RETURNING a; END "
                + "$$ LANGUAGE plpgsql");
        executeQuery("SELECT * FROM rt_rq_ins(5)");
        assertEquals(5, getIntValue(1));
        closeResultSet();

        executeUpdate("CREATE OR REPLACE FUNCTION rt_rq_upd() RETURNS SETOF INT AS $$ "
                + "BEGIN RETURN QUERY UPDATE rt_rq SET b = 'u' WHERE a = 5 RETURNING a; END "
                + "$$ LANGUAGE plpgsql");
        executeQuery("SELECT * FROM rt_rq_upd()");
        assertEquals(5, getIntValue(1));
        closeResultSet();
    }
}
