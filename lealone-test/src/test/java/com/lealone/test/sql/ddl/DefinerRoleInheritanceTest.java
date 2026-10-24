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
 * SECURITY DEFINER 以属主身份在嵌套会话里执行，这个嵌套会话不能继承调用方 SET ROLE 切出来的角色，
 * 否则definer函数体里再调用受限例程会按调用方的角色判权，definer语义就不成立了。
 */
public class DefinerRoleInheritanceTest extends SqlTestBase {

    public DefinerRoleInheritanceTest() {
        setEmbedded(true);
    }


    // definer的嵌套会话不能继承调用方的SET ROLE角色, 否则definer函数体里调用受限例程会被误拒
    @Test
    public void testDefinerIgnoresCallerRole() throws Exception {
        executeUpdate("DROP USER IF EXISTS sdt_u3");
        executeUpdate("CREATE USER sdt_u3 PASSWORD 'p'");
        executeUpdate("DROP ROLE IF EXISTS sdt_r1");
        executeUpdate("CREATE ROLE sdt_r1");
        executeUpdate("GRANT sdt_r1 TO sdt_u3");
        executeUpdate("DROP FUNCTION IF EXISTS sdt_secret_f");
        executeUpdate("CREATE FUNCTION sdt_secret_f() RETURNS INT AS 'SELECT 42'");
        executeUpdate("REVOKE EXECUTE ON FUNCTION sdt_secret_f FROM PUBLIC");
        executeUpdate("DROP FUNCTION IF EXISTS sdt_wrap");
        executeUpdate("CREATE FUNCTION sdt_wrap() RETURNS INT AS 'SELECT sdt_secret_f()'"
                + " SECURITY DEFINER");

        // 不带角色时能调
        executeQuery("SELECT sdt_wrap()");
        assertEquals(42, getIntValue(1, true));
        // SET ROLE之后仍然能调(definer按属主身份执行, 不该用调用方的角色)
        Connection c = getConnection("sdt_u3", "p");
        Statement s = c.createStatement();
        try {
            s.execute("SET ROLE sdt_r1");
            try (ResultSet rs = s.executeQuery("SELECT sdt_wrap()")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
            // SET ROLE仍然不能跨用户提权
            try {
                s.execute("SET ROLE root");
                fail("普通用户不能SET ROLE到root");
            } catch (SQLException e) {
                assertErrorCode(e, ErrorCode.INSUFFICIENT_PRIVILEGE_1);
            }
        } finally {
            s.close();
            c.close();
            restoreAdminConnection();
        }
    }


    // getConnection(user,password)会改写TestBase里共享的连接参数, 用完恢复成root
    private void restoreAdminConnection() throws Exception {
        getConnection("root", "").close();
    }
}
