/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * LEAKPROOF 会放宽优化器对表达式求值顺序的限制，和 PostgreSQL 一样只有 admin 能设置，
 * CREATE FUNCTION / CREATE OR REPLACE / ALTER FUNCTION / ALTER ROUTINE / CREATE PROCEDURE
 * 每条路径都要挡住。
 */
public class LeakproofRightTest extends SqlTestBase {

    public LeakproofRightTest() {
        setEmbedded(true);
    }


    // LEAKPROOF只有admin能设置, 四条路径都要挡住
    @Test
    public void testLeakproofRequiresAdmin() throws Exception {
        executeUpdate("DROP USER IF EXISTS sdt_u4");
        executeUpdate("CREATE USER sdt_u4 PASSWORD 'p'");
        executeUpdate("DROP FUNCTION IF EXISTS sdt_lf");
        executeUpdate("CREATE FUNCTION sdt_lf(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("DROP PROCEDURE IF EXISTS sdt_lp");

        Connection c = getConnection("sdt_u4", "p");
        Statement s = c.createStatement();
        try {
            assertAdminRequired(s, "CREATE FUNCTION sdt_lf2(a INT) RETURNS INT AS 'SELECT a' LEAKPROOF");
            assertAdminRequired(s, "CREATE OR REPLACE FUNCTION sdt_lf(a INT) RETURNS INT AS 'SELECT a'"
                    + " LEAKPROOF");
            assertAdminRequired(s, "ALTER FUNCTION sdt_lf(INT) LEAKPROOF");
            assertAdminRequired(s, "ALTER FUNCTION sdt_lf(INT) NOT LEAKPROOF");
            assertAdminRequired(s, "ALTER ROUTINE sdt_lf(INT) LEAKPROOF");
            assertAdminRequired(s, "CREATE PROCEDURE sdt_lp(a INT) AS 'SELECT 1' LEAKPROOF");
        } finally {
            s.close();
            c.close();
            restoreAdminConnection();
        }
        // admin还是能设置
        executeUpdate("ALTER FUNCTION sdt_lf(INT) LEAKPROOF");
        executeUpdate("CREATE PROCEDURE sdt_lp(a INT) AS 'SELECT 1' LEAKPROOF");
        executeUpdate("DROP PROCEDURE sdt_lp");
        executeUpdate("DROP FUNCTION sdt_lf");
    }

    private void assertAdminRequired(Statement s, String sql) {
        try {
            s.execute(sql);
            fail("非admin不应该能设置LEAKPROOF: " + sql);
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.ADMIN_RIGHTS_REQUIRED);
        }
    }


    // getConnection(user,password)会改写TestBase里共享的连接参数, 用完恢复成root
    private void restoreAdminConnection() throws Exception {
        getConnection("root", "").close();
    }
}
