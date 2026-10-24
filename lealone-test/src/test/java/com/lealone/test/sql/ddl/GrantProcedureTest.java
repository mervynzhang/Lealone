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

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * GRANT/REVOKE EXECUTE ON PROCEDURE|FUNCTION：带参数类型列表的过程/函数签名解析、
 * 授权的持久化(SCRIPT回放和关库重开)、REVOKE、以及删除过程/函数时顺手清掉授权。
 * <p>
 * Lealone没有过程级的EXECUTE权限检查，授权只记录不生效，所以这里还要验证：
 * 没有授权时默认行为不变(过程和函数照样能调用)，非admin用户不能GRANT。
 */
public class GrantProcedureTest extends SqlTestBase {

    public GrantProcedureTest() {
        setEmbedded(true);
    }

    @Test
    public void testGrantAndRevokeOnProcedure() throws Exception {
        initProcedure();
        executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_p(INT) TO gpr_u1");
        assertNotNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));

        executeUpdate("REVOKE EXECUTE ON PROCEDURE gpr_p(INT) FROM gpr_u1");
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));

        // 重载过程必须写参数类型，写全了才能单独授权某一个重载
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON PROCEDURE gpr_p TO gpr_u1",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_p(VARCHAR) TO gpr_u1");
        assertNotNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN VARCHAR)", "GPR_U1"));
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));
        // 类型对不上等于过程不存在
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON PROCEDURE gpr_p(TEXT) TO gpr_u1",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("REVOKE EXECUTE ON PROCEDURE gpr_p(TEXT) FROM gpr_u1",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON PROCEDURE gpr_no_such TO gpr_u1",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        // 是函数不是过程
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON PROCEDURE gpr_f(INT) TO gpr_u1",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdate("REVOKE EXECUTE ON PROCEDURE gpr_p(VARCHAR) FROM gpr_u1");

        // 带OUT参数的过程，只写入参或写完整参数列表都能匹配
        executeUpdate("DROP PROCEDURE IF EXISTS gpr_out");
        executeUpdate("CREATE PROCEDURE gpr_out(IN a INT, OUT b INT) AS 'SELECT a'");
        executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_out(INT) TO gpr_u1");
        executeUpdate("REVOKE EXECUTE ON PROCEDURE gpr_out(IN INT, OUT INT) FROM gpr_u1");
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_OUT(", "GPR_U1"));
        executeUpdate("DROP PROCEDURE gpr_out");
    }

    @Test
    public void testGrantAndRevokeOnFunction() throws Exception {
        initUser();
        executeUpdate("DROP FUNCTION IF EXISTS gpr_f(INT)");
        executeUpdate("CREATE FUNCTION gpr_f(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("DROP ALIAS IF EXISTS gpr_j");
        executeUpdate("CREATE ALIAS gpr_j AS $$ int gpr_j(int a) { return a; } $$");

        // SQL函数按参数类型消歧
        executeUpdate("GRANT EXECUTE ON FUNCTION gpr_f(INT) TO gpr_u1");
        assertNotNull(getGrantSql("FUNCTION GPR_F(INT)", "GPR_U1"));
        executeUpdate("REVOKE EXECUTE ON FUNCTION gpr_f(INT) FROM gpr_u1");
        assertNull(getGrantSql("FUNCTION GPR_F(INT)", "GPR_U1"));
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON FUNCTION gpr_f(TEXT) TO gpr_u1",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("GRANT EXECUTE ON FUNCTION gpr_no_such TO gpr_u1",
                ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1);

        // CREATE ALIAS建的Java函数只有名字，没有重载
        executeUpdate("GRANT EXECUTE ON FUNCTION gpr_j TO gpr_u1");
        assertNotNull(getGrantSql("FUNCTION GPR_J", "GPR_U1"));
        executeUpdate("REVOKE EXECUTE ON FUNCTION gpr_j FROM gpr_u1");
        assertNull(getGrantSql("FUNCTION GPR_J", "GPR_U1"));

        executeUpdate("DROP ALIAS gpr_j");
        executeUpdate("DROP FUNCTION gpr_f(INT)");
    }

    // 授权要持久化：SCRIPT里的GRANT能回放，关库重开之后还在
    @Test
    public void testGrantPersistence() throws Exception {
        initProcedure();
        executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_p(INT) TO gpr_u1");
        String grantSql = getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1");
        assertNotNull(grantSql);

        // 用SCRIPT里的GRANT语句回放(先删用户上的授权再放回去)
        executeUpdate("REVOKE EXECUTE ON PROCEDURE gpr_p(INT) FROM gpr_u1");
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));
        executeUpdate(grantSql);
        assertNotNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));

        // 关库重开后授权还在，还能正常REVOKE
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        assertNotNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));
        executeUpdate("REVOKE EXECUTE ON PROCEDURE gpr_p(INT) FROM gpr_u1");
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));
    }

    // 删除过程/函数时要顺手删掉它的授权，否则SCRIPT里会留下指向不存在对象的GRANT
    @Test
    public void testDropObjectRemovesGrant() throws Exception {
        initProcedure();
        executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_p(INT) TO gpr_u1");
        assertNotNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(IN INT)", "GPR_U1"));
        executeUpdate("DROP PROCEDURE gpr_p(INT)");
        assertNull(getGrantSql("PROCEDURE PUBLIC.GPR_P(", "GPR_U1"));
        executeUpdate("DROP PROCEDURE gpr_p(VARCHAR)");

        executeUpdate("DROP FUNCTION IF EXISTS gpr_f(INT)");
        executeUpdate("CREATE FUNCTION gpr_f(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("GRANT EXECUTE ON FUNCTION gpr_f(INT) TO gpr_u1");
        assertNotNull(getGrantSql("FUNCTION GPR_F(INT)", "GPR_U1"));
        executeUpdate("DROP FUNCTION gpr_f(INT)");
        assertNull(getGrantSql("FUNCTION GPR_F(INT)", "GPR_U1"));
    }

    // 没有授EXECUTE时默认行为不变：过程和函数照样能调用；GRANT仍然要admin权限
    @Test
    public void testDefaultBehaviorUnchanged() throws Exception {
        initProcedure();
        executeUpdate("DELETE FROM gpr_t");
        // gpr_u1只有表的INSERT权限，没有任何EXECUTE授权
        executeUpdate("GRANT INSERT ON gpr_t TO gpr_u1");
        Connection u = getConnection("gpr_u1", "p");
        Statement us = u.createStatement();
        us.executeUpdate("CALL gpr_p(1)");
        us.close();
        u.close();
        executeQuery("SELECT COUNT(*) FROM gpr_t");
        assertEquals(1, getIntValue(1));

        // 非admin用户不能GRANT
        u = getConnection("gpr_u1", "p");
        us = u.createStatement();
        try {
            us.executeUpdate("GRANT EXECUTE ON PROCEDURE gpr_p(INT) TO gpr_u1");
            fail("only admin can grant");
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.ADMIN_RIGHTS_REQUIRED);
        } finally {
            us.close();
            u.close();
        }
    }

    private void initUser() {
        executeUpdate("CREATE TABLE IF NOT EXISTS gpr_t(id INT)");
        executeUpdate("DROP USER IF EXISTS gpr_u1");
        executeUpdate("CREATE USER gpr_u1 PASSWORD 'p'");
    }

    private void initProcedure() {
        initUser();
        executeUpdate("DROP PROCEDURE IF EXISTS gpr_p(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS gpr_p(VARCHAR)");
        executeUpdate("CREATE PROCEDURE gpr_p(a INT) AS 'INSERT INTO gpr_t VALUES(?)'");
        executeUpdate("CREATE PROCEDURE gpr_p(a VARCHAR) AS 'INSERT INTO gpr_t VALUES(LENGTH(?))'");
        executeUpdate("DROP FUNCTION IF EXISTS gpr_f(INT)");
        executeUpdate("CREATE FUNCTION gpr_f(a INT) RETURNS INT AS 'SELECT a'");
    }

    // 从SCRIPT里找指定对象和接收者的GRANT语句
    private String getGrantSql(String objectAndSignature, String granteeName) {
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql != null && sql.startsWith("GRANT EXECUTE ON ") && sql.contains(objectAndSignature)
                        && sql.endsWith(" TO " + granteeName + ";")) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
