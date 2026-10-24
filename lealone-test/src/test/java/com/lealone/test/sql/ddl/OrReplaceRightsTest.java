/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * CREATE OR REPLACE 不能改变授权：替换实现之后原来的GRANT和"显式REVOKE掉PUBLIC"的限制都要还在，
 * 否则管理员重新部署一个已经被锁死的函数时，会把它悄悄放开给所有用户。
 */
public class OrReplaceRightsTest extends SqlTestBase {

    public OrReplaceRightsTest() {
        setEmbedded(true);
    }

    @Test
    public void testOrReplaceKeepsPublicRevoke() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS orr_f");
        executeUpdate("DROP USER IF EXISTS orr_u1");
        executeUpdate("CREATE USER orr_u1 PASSWORD 'p'");
        executeUpdate("CREATE FUNCTION orr_f() RETURNS INT AS 'SELECT 1'");

        // 没有授权记录时人人可调用(兼容规则)
        assertCallable("orr_u1", "SELECT orr_f()", true);

        // 显式REVOKE掉PUBLIC之后谁都调不了
        executeUpdate("REVOKE EXECUTE ON FUNCTION orr_f FROM PUBLIC");
        assertCallable("orr_u1", "SELECT orr_f()", false);

        // CREATE OR REPLACE 换成新实现，限制必须还在
        executeUpdate("CREATE OR REPLACE FUNCTION orr_f() RETURNS INT AS 'SELECT 2'");
        assertCallable("orr_u1", "SELECT orr_f()", false);

        // 关库重开之后限制还在(0权限标记要持久化)
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        assertCallable("orr_u1", "SELECT orr_f()", false);

        // 显式GRANT之后又变成可调用的，再来一次OR REPLACE也不能丢掉这个GRANT
        executeUpdate("GRANT EXECUTE ON FUNCTION orr_f TO orr_u1");
        assertCallable("orr_u1", "SELECT orr_f()", true);
        executeUpdate("CREATE OR REPLACE FUNCTION orr_f() RETURNS INT AS 'SELECT 3'");
        assertCallable("orr_u1", "SELECT orr_f()", true);

        executeUpdate("DROP FUNCTION orr_f");
        executeUpdate("DROP USER orr_u1");
    }

    @Test
    public void testOrReplaceKeepsProcedureRevoke() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS orr_p");
        executeUpdate("DROP USER IF EXISTS orr_u2");
        executeUpdate("CREATE USER orr_u2 PASSWORD 'p'");
        executeUpdate("CREATE PROCEDURE orr_p() AS 'SELECT 1'");
        executeUpdate("REVOKE EXECUTE ON PROCEDURE orr_p FROM PUBLIC");
        assertCallable("orr_u2", "CALL orr_p()", false);

        executeUpdate("CREATE OR REPLACE PROCEDURE orr_p() AS 'SELECT 2'");
        assertCallable("orr_u2", "CALL orr_p()", false);

        executeUpdate("GRANT EXECUTE ON PROCEDURE orr_p TO orr_u2");
        assertCallable("orr_u2", "CALL orr_p()", true);
        executeUpdate("CREATE OR REPLACE PROCEDURE orr_p() AS 'SELECT 3'");
        assertCallable("orr_u2", "CALL orr_p()", true);

        executeUpdate("DROP PROCEDURE IF EXISTS orr_p");
        executeUpdate("DROP USER IF EXISTS orr_u2");
    }

    // 用指定用户执行一条SQL，期望成功/被拒绝(EXECUTE权限不足)
    private void assertCallable(String user, String sql, boolean expected) throws Exception {
        Connection c = getConnection(user, "p");
        Statement s = c.createStatement();
        try {
            s.executeQuery(sql);
            if (!expected) {
                fail(user + " 应该没有EXECUTE权限: " + sql);
            }
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.INSUFFICIENT_PRIVILEGE_1);
        } finally {
            s.close();
            c.close();
            // getConnection(user,password)会改写TestBase里共享的连接参数，用完恢复成root
            getConnection("root", "").close();
        }
    }
}
