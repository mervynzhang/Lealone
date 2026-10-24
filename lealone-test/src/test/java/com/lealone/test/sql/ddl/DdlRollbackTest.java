/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * DDL 回滚之后 session 里缓存过的 prepared statement 必须重新编译，
 * 否则回滚已经删掉的对象还被旧计划拿在手里继续返回值，而新 session 里同样的语句已经报"找不到"。
 */
public class DdlRollbackTest extends SqlAssertTestBase {

    // 给CREATE ALIAS用的Java方法，不能有重载，否则无法确定用哪个
    public static long abs(long x) {
        return Math.abs(x);
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
}
