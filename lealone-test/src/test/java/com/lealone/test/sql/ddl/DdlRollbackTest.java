/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * DDL 回滚相关: 回滚之后 session 里缓存过的 prepared statement 必须重新编译，
 * 同一个事务里对同一个对象的多条DDL回滚之后必须回到事务开始前的状态。
 */
public class DdlRollbackTest extends SqlAssertTestBase {

    private static final String TABLE_DB = "ddl_rb_table_db";

    // 给CREATE ALIAS用的Java方法，不能有重载，否则无法确定用哪个
    public static long abs(long x) {
        return Math.abs(x);
    }

    public static long plusOne(long x) {
        return x + 1;
    }

    public static long plusTwo(long x) {
        return x + 2;
    }

    @Test
    public void cachedStatementRecompiledAfterDdlRollback() throws Exception {
        tryExecuteUpdate("DROP ALIAS IF EXISTS ddl_rb_abs");
        conn.setAutoCommit(false);
        executeUpdate("CREATE ALIAS ddl_rb_abs FOR \"" + DdlRollbackTest.class.getName() + ".abs\"");
        try (PreparedStatement ps = conn.prepareStatement("SELECT ddl_rb_abs(?)")) {
            ps.setLong(1, -7);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(7L, r.getLong(1));
            }
            // 回滚CREATE ALIAS，函数不存在了
            conn.rollback();
            conn.setAutoCommit(true);
            ps.setLong(1, -7);
            SQLException e = null;
            try {
                ps.executeQuery().close();
            } catch (SQLException ex) {
                e = ex;
            } catch (RuntimeException ex) {
                e = findSQLException(ex);
            }
            assertNotNull("cached statement must be recompiled and fail after DDL rollback", e);
            assertEquals(ErrorCode.FUNCTION_NOT_FOUND_1, e.getErrorCode());
        }
        // 新session里同样的语句同样找不到函数
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT ddl_rb_abs(-7)")) {
                fail("function must not exist after rollback, but got: " + r.next() + " row");
            } catch (SQLException e) {
                assertEquals(ErrorCode.FUNCTION_NOT_FOUND_1, e.getErrorCode());
            }
        }
    }

    // 同一个事务里对同一个对象先DROP再CREATE，回滚之后必须恢复成事务开始前的定义
    @Test
    public void dropAndCreateAliasInSameTransaction() throws Exception {
        String clazz = DdlRollbackTest.class.getName();
        tryExecuteUpdate("DROP ALIAS IF EXISTS ddl_rb_tx");
        executeUpdate("CREATE ALIAS ddl_rb_tx FOR \"" + clazz + ".plusOne\"");
        assertEquals(2L, queryObject("SELECT ddl_rb_tx(1)"));

        conn.setAutoCommit(false);
        executeUpdate("DROP ALIAS ddl_rb_tx");
        executeUpdate("CREATE ALIAS ddl_rb_tx FOR \"" + clazz + ".plusTwo\"");
        conn.rollback();
        conn.setAutoCommit(true);
        // 两条DDL共用一份事务快照，回滚之后还是事务开始前的plusOne
        assertEquals(2L, queryObject("SELECT ddl_rb_tx(1)"));
        // 新session里也一样
        try (Connection c = getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT ddl_rb_tx(1)")) {
            assertTrue(r.next());
            assertEquals(2L, r.getLong(1));
        }

        // 提交路径同样只生效一次: 提交之后生效的是事务里的新定义
        conn.setAutoCommit(false);
        executeUpdate("DROP ALIAS ddl_rb_tx");
        executeUpdate("CREATE ALIAS ddl_rb_tx FOR \"" + clazz + ".plusTwo\"");
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(3L, queryObject("SELECT ddl_rb_tx(1)"));
        executeUpdate("DROP ALIAS ddl_rb_tx");
    }

    // 表对象也一样: 同一事务里 DROP TABLE + CREATE TABLE 再回滚，必须恢复成原来那张表
    @Test
    public void dropAndCreateTableInSameTransaction() throws Exception {
        String lealoneUrl = "jdbc:lealone:embed:" + TestBase.TEST_DIR + "/lealone;user=root;password=";
        String dbUrl = "jdbc:lealone:embed:" + TestBase.TEST_DIR + "/" + TABLE_DB + ";user=root;password=";
        // DROP TABLE执行时表的存储就被销毁了(单条DROP TABLE+ROLLBACK也一样丢数据)，
        // 回滚恢复出来的表没法再用也没法再DROP，那是另一个既有缺陷，不在本次修复范围内；
        // 这里用一个独立的库隔离，只断言模式对象(原表的列定义)被恢复
        try (Connection c = DriverManager.getConnection(lealoneUrl); Statement s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE IF EXISTS " + TABLE_DB);
            s.executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS " + TABLE_DB + " PARAMETERS(database_to_upper=true)");
        }
        try (Connection c = DriverManager.getConnection(dbUrl); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t(id int primary key, v varchar(10), extra int)");
            s.executeUpdate("INSERT INTO t VALUES(1, 'old', 7)");

            c.setAutoCommit(false);
            s.executeUpdate("DROP TABLE t");
            s.executeUpdate("CREATE TABLE t(id int primary key, v varchar(10))");
            s.executeUpdate("INSERT INTO t VALUES(2, 'new')");
            c.rollback();
            c.setAutoCommit(true);

            // 恢复的是原来那张表(3列)，不是事务里重建的那张(2列)
            assertEquals(1, queryInt(c, "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'T' AND column_name = 'EXTRA'"));
            assertEquals(3, queryInt(c,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = 'T'"));
        } finally {
            try (Connection c = DriverManager.getConnection(lealoneUrl); Statement s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE IF EXISTS " + TABLE_DB);
            }
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue("no row returned for: " + sql, r.next());
            return r.getInt(1);
        }
    }
}
