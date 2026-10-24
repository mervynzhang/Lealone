/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * ALTER ROUTINE / DROP ROUTINE：ROUTINE 是过程和函数的统称。
 * 按名字+签名先在过程里找、再在函数里找；两边都能匹配上时报 42725；
 * 找不到时如果没写 IF EXISTS 就报函数不存在(90022)。
 */
public class RoutineStatementTest extends SqlTestBase {

    public RoutineStatementTest() {
        setEmbedded(true);
    }

    // 函数：ROUTINE 的 RENAME 往返，改名后 DDL、调用、重启后的持久化都要对
    @Test
    public void testAlterRoutineFunction() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS rt_f(INT)");
        executeUpdate("DROP FUNCTION IF EXISTS rt_fa(INT)");
        executeUpdate("DROP FUNCTION IF EXISTS rt_fb(INT)");
        executeUpdate("CREATE FUNCTION rt_f(a INT) RETURNS INT AS 'SELECT a + 1'");

        executeUpdate("ALTER ROUTINE rt_f(INT) RENAME TO rt_fa");
        executeQuery("SELECT rt_fa(1)");
        assertEquals(2, getIntValue(1, true));
        assertNull(getCreateSql("FUNCTION", "RT_F"));
        assertNotNull(getCreateSql("FUNCTION", "RT_FA"));

        // 名字是唯一的，不写参数类型也能定位
        executeUpdate("ALTER ROUTINE rt_fa RENAME TO rt_f");
        executeQuery("SELECT rt_f(2)");
        assertEquals(3, getIntValue(1, true));

        // 函数属性和 SET 参数走的是同一套
        executeUpdate("ALTER ROUTINE rt_f(INT) STRICT");
        executeUpdate("ALTER ROUTINE rt_f(INT) COST 5");
        String ddl = getCreateSql("FUNCTION", "RT_F");
        assertTrue(ddl, ddl.contains(" STRICT"));
        assertTrue(ddl, ddl.contains(" COST 5"));

        // 改名到一个已经存在的函数上报 90076
        executeUpdate("CREATE FUNCTION rt_fb(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdateThanAssertErrorCode("ALTER ROUTINE rt_f(INT) RENAME TO rt_fb",
                ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1);

        executeUpdate("DROP ROUTINE rt_f(INT)");
        executeUpdate("DROP ROUTINE rt_fb(INT)");
    }

    // 过程：ROUTINE 的属性/OWNER/SET/RESET 和 ALTER PROCEDURE 等价
    @Test
    public void testAlterRoutineProcedure() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS rt_p(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS rt_pa(INT)");
        executeUpdate("CREATE TABLE IF NOT EXISTS rt_t(id INT)");
        executeUpdate("CREATE PROCEDURE rt_p(a INT) AS 'INSERT INTO rt_t VALUES(?)'");

        executeUpdate("ALTER ROUTINE rt_p(INT) RENAME TO rt_pa");
        executeUpdate("CALL rt_pa(1)");
        executeQuery("SELECT COUNT(*) FROM rt_t");
        assertEquals(1, getIntValue(1));
        executeUpdate("ALTER ROUTINE rt_pa(INT) RENAME TO rt_p");

        executeUpdate("ALTER ROUTINE rt_p(INT) STABLE");
        executeUpdate("ALTER ROUTINE rt_p(INT) STRICT");
        executeUpdate("ALTER ROUTINE rt_p(INT) OWNER TO root");
        executeUpdate("ALTER ROUTINE rt_p(INT) SET work_mem = '2MB'");
        String ddl = getCreateSql("PROCEDURE", "RT_P");
        assertTrue(ddl, ddl.contains(" STABLE"));
        assertTrue(ddl, ddl.contains(" STRICT"));
        assertTrue(ddl, ddl.contains(" OWNER TO ROOT"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '2MB'"));
        executeUpdate("ALTER ROUTINE rt_p(INT) RESET ALL");
        ddl = getCreateSql("PROCEDURE", "RT_P");
        // RESET ALL 只清配置参数, 不动STABLE/STRICT这些属性
        assertFalse(ddl, ddl.contains(" SET "));

        // 关库重开后改名和属性都还在
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        ddl = getCreateSql("PROCEDURE", "RT_P");
        assertTrue(ddl, ddl.contains(" STRICT"));
        executeUpdate("CALL rt_p(2)");

        executeUpdate("DROP ROUTINE rt_p(INT)");
    }

    // DROP ROUTINE 按签名删，删的是过程还是函数由签名决定
    @Test
    public void testDropRoutine() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS rt_d(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS rt_dp(INT)");
        executeUpdate("CREATE FUNCTION rt_d(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("CREATE PROCEDURE rt_dp(a INT) AS 'INSERT INTO rt_t VALUES(?)'");

        executeUpdate("DROP ROUTINE rt_d(INT)");
        assertNull(getCreateSql("FUNCTION", "RT_D"));
        executeQueryThanAssertErrorCode("SELECT rt_d(1)", ErrorCode.FUNCTION_NOT_FOUND_1);

        executeUpdate("DROP ROUTINE rt_dp(INT)");
        assertNull(getCreateSql("PROCEDURE", "RT_DP"));

        // IF EXISTS 和找不到
        executeUpdate("DROP ROUTINE IF EXISTS rt_d(INT)");
        executeUpdateThanAssertErrorCode("DROP ROUTINE rt_d(INT)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("DROP ROUTINE no_such_routine(INT)",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("DROP ROUTINE IF EXISTS no_such_routine(INT)");

        // 名字存在但签名对不上
        executeUpdate("CREATE FUNCTION rt_d(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdateThanAssertErrorCode("DROP ROUTINE rt_d(VARCHAR)",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("ALTER ROUTINE rt_d(VARCHAR) STRICT",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        // 多个重载时不写参数类型报歧义
        executeUpdate("CREATE FUNCTION rt_d(a VARCHAR) RETURNS INT AS 'SELECT LENGTH(a)'");
        executeUpdateThanAssertErrorCode("DROP ROUTINE rt_d", ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdateThanAssertErrorCode("ALTER ROUTINE rt_d STRICT",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdate("DROP ROUTINE rt_d(VARCHAR)");
        executeUpdate("DROP ROUTINE rt_d(INT)");
    }

    // 过程和函数同名同签名时 ROUTINE 不知道操作哪个，报 42725
    @Test
    public void testRoutineAmbiguousKind() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS rt_x(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS rt_x(INT)");
        executeUpdate("CREATE FUNCTION rt_x(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("CREATE PROCEDURE rt_x(a INT) AS 'INSERT INTO rt_t VALUES(?)'");
        executeUpdateThanAssertErrorCode("ALTER ROUTINE rt_x(INT) STRICT",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdateThanAssertErrorCode("DROP ROUTINE rt_x(INT)",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        // 指定具体种类就没有歧义了
        executeUpdate("ALTER FUNCTION rt_x(INT) STRICT");
        executeUpdate("DROP FUNCTION rt_x(INT)");
        executeUpdate("DROP PROCEDURE rt_x(INT)");
    }

    // 带OUT参数的过程，写完整参数列表或者只写入参都能定位
    @Test
    public void testRoutineOutParameter() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS rt_o");
        executeUpdate("CREATE PROCEDURE rt_o(IN a INT, OUT b INT) AS 'SELECT ?'");
        executeUpdate("ALTER ROUTINE rt_o(INT) STRICT");
        executeUpdate("ALTER ROUTINE rt_o(IN INT, OUT INT) COST 9");
        String ddl = getCreateSql("PROCEDURE", "RT_O");
        assertTrue(ddl, ddl.contains(" STRICT"));
        assertTrue(ddl, ddl.contains(" COST 9"));
        executeQuery("CALL rt_o(3)");
        assertEquals(3, getIntValue(1, true));
        executeUpdate("DROP ROUTINE rt_o(IN INT, OUT INT)");
        assertNull(getCreateSql("PROCEDURE", "RT_O"));
    }

    @Override
    public void setUpBefore() {
        super.setUpBefore();
        executeUpdate("CREATE TABLE IF NOT EXISTS rt_t(id INT)");
    }

    // 从SCRIPT里找出指定对象的CREATE语句，name是对象名(大写);
    // 函数在主schema时DDL里不带schema前缀，过程带
    private String getCreateSql(String kind, String name) {
        String prefix = "CREATE " + kind + " ";
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql == null || !sql.startsWith(prefix)) {
                    continue;
                }
                String rest = sql.substring(prefix.length());
                if (rest.startsWith(name + "(") || rest.contains("." + name + "(")) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
