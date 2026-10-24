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
 * SECURITY DEFINER 必须真的按属主身份执行(过程、触发器函数也要生效)，SECURITY INVOKER 仍按调用者；
 * LEAKPROOF 只有admin能设置；information_schema 报的SECURITY_TYPE要和实际行为一致。
 */
public class SecurityDefinerTest extends SqlTestBase {

    public SecurityDefinerTest() {
        setEmbedded(true);
    }

    // 过程: SECURITY DEFINER 以属主身份读表, INVOKER 仍然按调用者判权
    @Test
    public void testDefinerProcedure() throws Exception {
        executeUpdate("DROP USER IF EXISTS sdt_u1");
        executeUpdate("CREATE USER sdt_u1 PASSWORD 'p'");
        executeUpdate("CREATE TABLE IF NOT EXISTS sdt_secret(id INT)");
        executeUpdate("DELETE FROM sdt_secret");
        executeUpdate("INSERT INTO sdt_secret VALUES(1)");

        executeUpdate("DROP PROCEDURE IF EXISTS sdt_invoker");
        executeUpdate("CREATE PROCEDURE sdt_invoker(OUT a INT) AS 'SELECT COUNT(*) FROM sdt_secret'");
        executeUpdate("DROP PROCEDURE IF EXISTS sdt_definer");
        executeUpdate("CREATE PROCEDURE sdt_definer(OUT a INT) AS 'SELECT COUNT(*) FROM sdt_secret'"
                + " SECURITY DEFINER");
        // information_schema 要如实报告SECURITY_TYPE
        assertEquals("DEFINER", getSecurityType("sdt_definer"));
        assertEquals("INVOKER", getSecurityType("sdt_invoker"));

        // 调用者(sdt_u1)没有sdt_secret的任何权限: INVOKER过程按调用者判权被拒,
        // DEFINER过程以属主身份执行能拿到结果
        Connection c = getConnection("sdt_u1", "p");
        Statement s = c.createStatement();
        try {
            try {
                s.executeQuery("CALL sdt_invoker(NULL)");
                fail("INVOKER过程应该按调用者判权");
            } catch (SQLException e) {
                assertErrorCode(e, ErrorCode.NOT_ENOUGH_RIGHTS_FOR_1);
            }
            try (ResultSet rs = s.executeQuery("CALL sdt_definer(NULL)")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        } finally {
            s.close();
            c.close();
            restoreAdminConnection();
        }
    }

    // 触发器函数: SECURITY DEFINER 能写调用者写不了的审计表, INVOKER 不行
    @Test
    public void testDefinerTriggerFunction() throws Exception {
        executeUpdate("DROP USER IF EXISTS sdt_u2");
        executeUpdate("CREATE USER sdt_u2 PASSWORD 'p'");
        executeUpdate("CREATE TABLE IF NOT EXISTS sdt_main(id INT)");
        executeUpdate("CREATE TABLE IF NOT EXISTS sdt_audit(id INT)");
        executeUpdate("DELETE FROM sdt_main");
        executeUpdate("DELETE FROM sdt_audit");
        executeUpdate("GRANT INSERT ON sdt_main TO sdt_u2");

        // 先用INVOKER版本: 低权限用户插入会被拒
        executeUpdate("DROP FUNCTION IF EXISTS sdt_trig");
        executeUpdate("CREATE FUNCTION sdt_trig() RETURNS TRIGGER AS $$ "
                + "BEGIN INSERT INTO sdt_audit VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("DROP TRIGGER IF EXISTS sdt_trig1");
        executeUpdate("CREATE TRIGGER sdt_trig1 BEFORE INSERT ON sdt_main FOR EACH ROW "
                + "EXECUTE FUNCTION sdt_trig()");
        assertInsertFails("sdt_u2", "INSERT INTO sdt_main VALUES(1)");

        // 改成SECURITY DEFINER后, 触发器函数以属主身份写审计表
        executeUpdate("ALTER FUNCTION sdt_trig() SECURITY DEFINER");
        assertEquals("DEFINER", getSecurityType("sdt_trig"));
        insertAs("sdt_u2", "INSERT INTO sdt_main VALUES(2)");
        executeQuery("SELECT COUNT(*) FROM sdt_audit");
        assertEquals(1, getIntValue(1));

        // 外层DML失败时, 触发器函数里写审计表的修改也要一起回滚
        executeUpdate("DELETE FROM sdt_audit");
        executeUpdate("CREATE TABLE IF NOT EXISTS sdt_ck(id INT CHECK(id < 100))");
        executeUpdate("DELETE FROM sdt_ck");
        executeUpdate("GRANT INSERT ON sdt_ck TO sdt_u2");
        executeUpdate("DROP FUNCTION IF EXISTS sdt_trig2");
        executeUpdate("CREATE FUNCTION sdt_trig2() RETURNS TRIGGER AS $$ "
                + "BEGIN INSERT INTO sdt_audit VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql"
                + " SECURITY DEFINER");
        executeUpdate("DROP TRIGGER IF EXISTS sdt_trig2");
        executeUpdate("CREATE TRIGGER sdt_trig2 BEFORE INSERT ON sdt_ck FOR EACH ROW "
                + "EXECUTE FUNCTION sdt_trig2()");
        // 先确认这条路径下触发器函数确实写了审计表
        insertAs("sdt_u2", "INSERT INTO sdt_ck VALUES(5)");
        executeQuery("SELECT COUNT(*) FROM sdt_audit");
        assertEquals(1, getIntValue(1));
        executeUpdate("DELETE FROM sdt_audit");
        // 外层DML因为CHECK约束失败, 触发器函数写的审计记录要跟着回滚
        try {
            insertAs("sdt_u2", "INSERT INTO sdt_ck VALUES(200)");
            fail("CHECK约束应该失败");
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.CHECK_CONSTRAINT_VIOLATED_1);
        }
        executeQuery("SELECT COUNT(*) FROM sdt_audit");
        assertEquals(0, getIntValue(1));
    }


    private void assertInsertFails(String user, String sql) throws Exception {
        Connection c = getConnection(user, "p");
        Statement s = c.createStatement();
        try {
            s.executeUpdate(sql);
            fail(user + " 不应该能执行: " + sql);
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.NOT_ENOUGH_RIGHTS_FOR_1);
        } finally {
            s.close();
            c.close();
            restoreAdminConnection();
        }
    }

    private void insertAs(String user, String sql) throws Exception {
        Connection c = getConnection(user, "p");
        Statement s = c.createStatement();
        try {
            s.execute(sql);
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

    // information_schema里的SECURITY_TYPE
    private String getSecurityType(String routineName) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT SECURITY_TYPE FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_NAME = '"
                        + routineName.toUpperCase() + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
