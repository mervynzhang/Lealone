/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PL/pgSQL过程体：CREATE PROCEDURE ... LANGUAGE plpgsql AS $$ ... $$，
 * OUT/INOUT参数就是PL/pgSQL里的变量，CALL返回一行。
 */
public class ProcedurePlpgsqlTest extends SqlTestBase {

    public ProcedurePlpgsqlTest() {
        setEmbedded(true);
    }

    private void init() {
        executeUpdate("DROP PROCEDURE IF EXISTS pp1");
        executeUpdate("DROP PROCEDURE IF EXISTS pp2");
        executeUpdate("DROP PROCEDURE IF EXISTS pp3");
        executeUpdate("DROP PROCEDURE IF EXISTS pp4");
        executeUpdate("DROP TABLE IF EXISTS pp_t");
        executeUpdate("CREATE TABLE pp_t(id int primary key, v int)");
    }

    @Test
    public void run() throws Exception {
        init();
        testOutAndInout();
        testInsertWithoutOutParams();
        testEarlyReturn();
        testLoopAndException();
        testUnsupported();
        testScriptRoundTrip();
        init();
    }

    // PostgreSQL原样写法: IN/OUT/INOUT + LANGUAGE plpgsql
    private void testOutAndInout() throws Exception {
        executeUpdate("CREATE PROCEDURE pp1(IN x INT, OUT y INT, INOUT z VARCHAR) LANGUAGE plpgsql "
                + "AS $$ BEGIN y := x * 2; z := z || '!'; END $$");
        executeQuery("CALL pp1(3, 'a')");
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("Y", md.getColumnLabel(1));
        assertEquals("Z", md.getColumnLabel(2));
        assertEquals(6, getIntValue(1, false));
        assertEquals("a!", getStringValue(2, true));

        // PostgreSQL写法：OUT参数写NULL占位
        executeQuery("CALL pp1(4, NULL, 'b')");
        assertEquals(8, getIntValue(1, false));
        assertEquals("b!", getStringValue(2, true));

        // 两个INOUT参数也是返回一行
        executeUpdate("CREATE PROCEDURE pp2(INOUT a INT, INOUT b INT) LANGUAGE plpgsql "
                + "AS $$ BEGIN a := a + 1; b := b * 2; END $$");
        executeQuery("CALL pp2(5, 6)");
        assertEquals(6, getIntValue(1, false));
        assertEquals(12, getIntValue(2, true));
    }

    // 没有OUT参数时CALL就是执行过程体，返回更新数
    private void testInsertWithoutOutParams() throws Exception {
        executeUpdate("CREATE PROCEDURE pp3(IN x INT) LANGUAGE plpgsql "
                + "AS $$ BEGIN INSERT INTO pp_t VALUES(x, x * 10); END $$");
        executeUpdate("CALL pp3(5)");
        executeQuery("SELECT v FROM pp_t WHERE id = 5");
        assertEquals(50, getIntValue(1, true));
    }

    // RETURN;提前结束，OUT参数的值照样返回
    private void testEarlyReturn() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS pp5");
        executeUpdate("CREATE PROCEDURE pp5(IN x INT, OUT y INT) LANGUAGE plpgsql AS $$ "
                + "BEGIN IF x < 0 THEN y := -1; RETURN; END IF; y := x + 1; END $$");
        executeQuery("CALL pp5(5)");
        assertEquals(6, getIntValue(1, true));
        executeQuery("CALL pp5(-5)");
        assertEquals(-1, getIntValue(1, true));
    }

    // 声明变量、循环、RAISE EXCEPTION
    private void testLoopAndException() throws Exception {
        executeUpdate("CREATE PROCEDURE pp4(OUT s INT) LANGUAGE plpgsql AS $$ "
                + "DECLARE i INT := 0; BEGIN s := 0; "
                + "LOOP i := i + 1; EXIT WHEN i > 3; s := s + i; END LOOP; END $$");
        executeQuery("CALL pp4()");
        assertEquals(6, getIntValue(1, true));

        executeUpdate("DROP PROCEDURE IF EXISTS pp6");
        executeUpdate("CREATE PROCEDURE pp6() LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'boom'; END $$");
        executeUpdateThanAssertErrorCode("CALL pp6()", ErrorCode.GENERAL_ERROR_1);
    }

    private void testUnsupported() throws Exception {
        // PL/pgSQL没有SQL标准的BEGIN ATOMIC函数体
        executeUpdateThanAssertErrorCode(
                "CREATE PROCEDURE bad1() LANGUAGE plpgsql BEGIN ATOMIC SELECT 1; END",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // 只支持SQL和PL/pgSQL
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE bad2() LANGUAGE c AS 'select 1'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // SCRIPT回放 + 关库重开
    private void testScriptRoundTrip() throws Exception {
        String ddl = getCreateProcedureSql("PP1");
        assertNotNull(ddl);
        assertTrue(ddl, ddl.contains("LANGUAGE PLPGSQL"));
        assertTrue(ddl, ddl.contains("OUT Y INT"));
        assertTrue(ddl, ddl.contains("INOUT Z VARCHAR"));
        executeUpdate("DROP PROCEDURE pp1");
        executeUpdate(ddl);
        executeQuery("CALL pp1(5, 'c')");
        assertEquals(10, getIntValue(1, false));
        assertEquals("c!", getStringValue(2, true));

        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        executeQuery("CALL pp1(6, 'd')");
        assertEquals(12, getIntValue(1, false));
        assertEquals("d!", getStringValue(2, true));
        executeQuery("CALL pp2(1, 2)");
        assertEquals(2, getIntValue(1, false));
        assertEquals(4, getIntValue(2, true));
    }

    private String getCreateProcedureSql(String procedureName) {
        String prefix = "CREATE PROCEDURE PUBLIC." + procedureName + "(";
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql != null && sql.startsWith(prefix)) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
