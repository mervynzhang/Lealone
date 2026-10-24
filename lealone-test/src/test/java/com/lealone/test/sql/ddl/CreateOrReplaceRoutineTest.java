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
 * CREATE OR REPLACE 的跨类型规则和 WINDOW 属性:
 * 同名同签名可以覆盖(函数覆盖函数、过程覆盖过程)，但函数和过程不能互转(42P13/90154)；
 * 过程不接受 WINDOW 属性(WINDOW 只用于函数)。
 */
public class CreateOrReplaceRoutineTest extends SqlTestBase {

    public CreateOrReplaceRoutineTest() {
        setEmbedded(true);
    }

    @Test
    public void testOrReplaceProcedure() throws Exception {
        executeUpdate("CREATE TABLE IF NOT EXISTS cor_t(id INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS cor_p(INT)");
        executeUpdate("CREATE PROCEDURE cor_p(a INT) AS 'INSERT INTO cor_t VALUES(1)'");
        executeUpdate("CALL cor_p(1)");
        executeUpdate("DELETE FROM cor_t");

        // OR REPLACE 用新定义覆盖同名同入参签名的过程
        executeUpdate("CREATE OR REPLACE PROCEDURE cor_p(a INT) AS 'INSERT INTO cor_t VALUES(2)'");
        executeUpdate("CALL cor_p(1)");
        executeQuery("SELECT id FROM cor_t");
        assertEquals(2, getIntValue(1));

        // 不带 OR REPLACE 还是报 90150，IF NOT EXISTS 放过
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE cor_p(a INT) AS 'SELECT 3'",
                ErrorCode.PROCEDURE_ALREADY_EXISTS_1);
        executeUpdate("CREATE PROCEDURE IF NOT EXISTS cor_p(a INT) AS 'SELECT 3'");

        // 参数类型不同的重载不受 OR REPLACE 影响
        executeUpdate("CREATE OR REPLACE PROCEDURE cor_p(a VARCHAR) AS 'SELECT LENGTH(?)'");
        executeQuery("CALL cor_p('abc')");
        assertEquals(3, getIntValue(1, true));

        executeUpdate("DROP PROCEDURE cor_p(INT)");
        executeUpdate("DROP PROCEDURE cor_p(VARCHAR)");
    }

    // 函数和过程不能互转，两个方向都报 42P13
    @Test
    public void testCannotChangeRoutineKind() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS cor_f(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS cor_f(INT)");
        executeUpdate("CREATE FUNCTION cor_f(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdateThanAssertErrorCode("CREATE OR REPLACE PROCEDURE cor_f(a INT) AS 'SELECT 1'",
                ErrorCode.CANNOT_CHANGE_ROUTINE_KIND_1);
        // 函数还在
        executeQuery("SELECT cor_f(5)");
        assertEquals(5, getIntValue(1, true));

        executeUpdate("DROP FUNCTION cor_f(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS cor_f(INT)");
        executeUpdate("CREATE PROCEDURE cor_f(a INT) AS 'SELECT 1'");
        executeUpdateThanAssertErrorCode(
                "CREATE OR REPLACE FUNCTION cor_f(a INT) RETURNS INT AS 'SELECT a'",
                ErrorCode.CANNOT_CHANGE_ROUTINE_KIND_1);
        executeUpdate("DROP PROCEDURE cor_f(INT)");
    }

    // WINDOW 只用于函数: 函数可以建/改, 过程在CREATE/ALTER PROCEDURE/ALTER ROUTINE 里都拒绝
    @Test
    public void testWindowOnlyForFunction() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS cor_w(INT)");
        executeUpdate("CREATE FUNCTION cor_w(a INT) RETURNS INT LANGUAGE SQL AS 'SELECT $1'");
        executeUpdate("ALTER FUNCTION cor_w(INT) WINDOW");
        String ddl = getCreateSql("cor_w");
        assertTrue(ddl, ddl.contains(" WINDOW"));

        executeUpdateThanAssertErrorCode(
                "CREATE OR REPLACE PROCEDURE cor_wp(a INT) LANGUAGE SQL WINDOW AS 'SELECT 1'",
                ErrorCode.SYNTAX_ERROR_1);
        executeUpdate("CREATE PROCEDURE cor_wp(a INT) LANGUAGE SQL STRICT AS 'SELECT 1'");
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE cor_wp(INT) WINDOW",
                ErrorCode.SYNTAX_ERROR_1);
        executeUpdateThanAssertErrorCode("ALTER ROUTINE cor_wp(INT) WINDOW",
                ErrorCode.SYNTAX_ERROR_1);
        // 过程没有属性SQL时DDL里也不会出现WINDOW
        String procedureDdl = getCreateSql("cor_wp");
        assertFalse(procedureDdl, procedureDdl.contains(" WINDOW"));

        executeUpdate("DROP FUNCTION cor_w(INT)");
        executeUpdate("DROP PROCEDURE cor_wp(INT)");
    }

    // 从SCRIPT里找出指定函数/过程的CREATE语句
    private String getCreateSql(String name) {
        String upperName = name.toUpperCase();
        String pattern = "." + upperName + "(";
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String sql = rs.getString(1);
                if (sql == null) {
                    continue;
                }
                if (sql.startsWith("CREATE FUNCTION ")
                        && (sql.startsWith("CREATE FUNCTION " + upperName + "(")
                                || sql.contains(pattern))) {
                    return sql;
                }
                if (sql.startsWith("CREATE PROCEDURE ") && sql.contains(pattern)) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
