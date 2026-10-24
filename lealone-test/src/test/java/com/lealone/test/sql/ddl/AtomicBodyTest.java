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
 * SQL标准的函数体 BEGIN ATOMIC ... END：过程和函数都能用，里面可以有多条SQL语句，
 * 最后一条查询的结果就是过程的OUT/INOUT值或函数的返回值。
 */
public class AtomicBodyTest extends SqlTestBase {

    public AtomicBodyTest() {
        setEmbedded(true);
    }

    private void init() {
        executeUpdate("DROP PROCEDURE IF EXISTS pab1");
        executeUpdate("DROP PROCEDURE IF EXISTS pab2");
        executeUpdate("DROP PROCEDURE IF EXISTS pab3");
        executeUpdate("DROP FUNCTION IF EXISTS fab1");
        executeUpdate("DROP FUNCTION IF EXISTS fab2");
        executeUpdate("DROP FUNCTION IF EXISTS fab3");
        executeUpdate("DROP FUNCTION IF EXISTS fre1");
        executeUpdate("DROP FUNCTION IF EXISTS fre2");
        executeUpdate("DROP FUNCTION IF EXISTS fre3");
        executeUpdate("DROP FUNCTION IF EXISTS fre4");
        executeUpdate("DROP FUNCTION IF EXISTS fre5");
        executeUpdate("DROP TABLE IF EXISTS pab_t");
        executeUpdate("CREATE TABLE pab_t(id int primary key, v int)");
    }

    @Test
    public void run() throws Exception {
        init();
        testProcedureAtomicBody();
        testProcedureAtomicBodyWithOutParams();
        testFunctionAtomicBody();
        testFunctionAtomicBodyRows();
        testReturnBody();
        testUnsupported();
        testScriptRoundTrip();
        init();
    }

    private void testProcedureAtomicBody() throws Exception {
        executeUpdate("CREATE PROCEDURE pab1() BEGIN ATOMIC SELECT 1; END");
        executeQuery("CALL pab1()");
        assertEquals(1, getIntValue(1, true));

        // 多条语句顺序执行，最后一条查询作为结果
        executeUpdate("CREATE PROCEDURE pab2() BEGIN ATOMIC "
                + "INSERT INTO pab_t VALUES(1, 10); INSERT INTO pab_t VALUES(2, 20); SELECT 30; END");
        executeQuery("CALL pab2()");
        assertEquals(30, getIntValue(1, true));
        executeQuery("SELECT COUNT(*) FROM pab_t");
        assertEquals(2, getIntValue(1, true));

        // 空的BEGIN ATOMIC块什么都不做
        executeUpdate("CREATE PROCEDURE pab3() BEGIN ATOMIC END");
        executeUpdate("CALL pab3()");
        executeQuery("SELECT COUNT(*) FROM pab_t");
        assertEquals(2, getIntValue(1, true));
    }

    // OUT/INOUT参数和BEGIN ATOMIC一起用
    private void testProcedureAtomicBodyWithOutParams() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS pab4");
        executeUpdate("CREATE PROCEDURE pab4(IN x INT, OUT y INT) BEGIN ATOMIC SELECT ? * 3; END");
        executeQuery("CALL pab4(2)");
        assertEquals(6, getIntValue(1, true));

        executeUpdate("DROP PROCEDURE IF EXISTS pab5");
        // 过程体里每条语句的?都从第一个入参开始按位置绑定(既有约定，不跨语句连续编号)
        executeUpdate("CREATE PROCEDURE pab5(IN x INT, IN y INT, IN z INT) BEGIN ATOMIC "
                + "INSERT INTO pab_t VALUES(?, ?); SELECT ? + 1; END");
        executeQuery("CALL pab5(3, 30, 5)");
        assertEquals(4, getIntValue(1, true));
        executeQuery("SELECT v FROM pab_t WHERE id = 3");
        assertEquals(30, getIntValue(1, true));
    }

    private void testFunctionAtomicBody() throws Exception {
        executeUpdate("CREATE FUNCTION fab1(a INT) RETURNS INT BEGIN ATOMIC SELECT a + 1; END");
        executeQuery("SELECT fab1(1)");
        assertEquals(2, getIntValue(1, true));

        // 多条语句时最后一条查询是返回值
        executeUpdate("CREATE FUNCTION fab2(a INT) RETURNS INT BEGIN ATOMIC "
                + "INSERT INTO pab_t VALUES(10 + a, a); SELECT a; SELECT a * 10; END");
        executeQuery("SELECT fab2(5)");
        assertEquals(50, getIntValue(1, true));
        executeQuery("SELECT v FROM pab_t WHERE id = 15");
        assertEquals(5, getIntValue(1, true));

        // 属性和BEGIN ATOMIC可以一起用
        executeUpdate("CREATE FUNCTION fab3(a INT) RETURNS INT IMMUTABLE STRICT "
                + "BEGIN ATOMIC SELECT a * 2; END");
        executeQuery("SELECT fab3(4)");
        assertEquals(8, getIntValue(1, true));
        executeQuery("SELECT fab3(NULL)");
        assertNull(getStringValue(1, true));
        assertTrue(getCreateFunctionSql("FAB3"), getCreateFunctionSql("FAB3")
                .contains("BEGIN ATOMIC SELECT a * 2; END"));
    }

    // RETURNS TABLE/VOID的函数也能用BEGIN ATOMIC
    private void testFunctionAtomicBodyRows() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS fab4");
        executeUpdate("CREATE FUNCTION fab4(a INT) RETURNS TABLE(x INT) BEGIN ATOMIC "
                + "SELECT a; SELECT a * 10; END");
        sql = "SELECT * FROM fab4(4)";
        executeQuery();
        // 最后一条查询的结果集就是返回值，前面的查询结果被丢弃
        assertEquals(40, getIntValue(1, false));
        assertFalse(next());
        closeResultSet();

        executeUpdate("DROP FUNCTION IF EXISTS fab5");
        executeUpdate("CREATE FUNCTION fab5() RETURNS VOID BEGIN ATOMIC "
                + "INSERT INTO pab_t VALUES(99, 99); END");
        executeQuery("SELECT fab5()");
        assertNull(getStringValue(1, true));
        executeQuery("SELECT v FROM pab_t WHERE id = 99");
        assertEquals(99, getIntValue(1, true));
    }

    // SQL标准函数体: RETURN <表达式> / RETURN QUERY <查询> / BEGIN RETURN ...; END
    private void testReturnBody() throws Exception {
        // 单表达式形式，等价于 SELECT <表达式>
        executeUpdate("CREATE FUNCTION fre1(a INT) RETURNS INT RETURN a + 1");
        executeQuery("SELECT fre1(4)");
        assertEquals(5, getIntValue(1, true));
        // 带语言的完整写法
        executeUpdate("CREATE FUNCTION fre2(a TEXT, b DATE) RETURNS BOOLEAN LANGUAGE SQL "
                + "RETURN a = 'abcd' AND b > DATE '2001-01-01'");
        executeQuery("SELECT fre2('abcd', DATE '2001-02-01')");
        assertEquals(true, getBooleanValue(1, true));
        // 表达式里可以是子查询
        executeUpdate("CREATE FUNCTION fre3() RETURNS INT RETURN (SELECT count(*) FROM pab_t)");
        executeUpdate("DELETE FROM pab_t");
        executeUpdate("INSERT INTO pab_t VALUES(1, 1)");
        executeUpdate("INSERT INTO pab_t VALUES(2, 2)");
        executeQuery("SELECT fre3()");
        assertEquals(2, getIntValue(1, true));
        // RETURNS TABLE + RETURN QUERY
        executeUpdate("CREATE FUNCTION fre4() RETURNS TABLE(x INT) RETURN QUERY SELECT 7");
        executeQuery("SELECT * FROM fre4()");
        assertEquals(7, getIntValue(1, true));
        // 不带ATOMIC的标准块体，以及块体里的空语句(;;)
        executeUpdate("CREATE FUNCTION fre5(a INT) RETURNS INT BEGIN RETURN a * 3; END");
        executeQuery("SELECT fre5(3)");
        assertEquals(9, getIntValue(1, true));
        executeUpdate("DROP FUNCTION fre5");
        executeUpdate("CREATE FUNCTION fre5(a INT) RETURNS INT BEGIN RETURN -a; END");
        executeQuery("SELECT fre5(3)");
        assertEquals(-3, getIntValue(1, true));
        // RETURNS VOID + RETURN null
        executeUpdate("DROP FUNCTION IF EXISTS fre6");
        executeUpdate("CREATE FUNCTION fre6() RETURNS VOID RETURN null");
        executeQuery("SELECT fre6()");
        assertNull(getStringValue(1, true));

        // SCRIPT里单表达式形式被规范化成 AS 'SELECT ...'，块体形式还是BEGIN ATOMIC
        String ddl = getCreateFunctionSql("FRE1");
        assertNotNull(ddl);
        assertTrue(ddl, ddl.contains("AS 'SELECT a + 1'"));
        String ddl5 = getCreateFunctionSql("FRE5");
        assertNotNull(ddl5);
        assertTrue(ddl5, ddl5.contains("BEGIN ATOMIC SELECT -a; END"));
    }

    private void testUnsupported() throws Exception {
        // PL/pgSQL不支持BEGIN ATOMIC
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION bad1(a INT) RETURNS INT LANGUAGE plpgsql "
                        + "BEGIN ATOMIC SELECT a; END",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // 函数的BEGIN ATOMIC不能是空的
        executeUpdateThanAssertErrorCode("CREATE FUNCTION bad2() RETURNS INT BEGIN ATOMIC END",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // 有OUT参数的过程体是空的BEGIN ATOMIC块时没有查询语句
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE bad5(OUT a INT) BEGIN ATOMIC END",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // BEGIN ATOMIC里面的语句在CREATE时就要能解析出来，不能留到调用时才报错
        executeUpdateThanAssertErrorCode("CREATE FUNCTION bad3() RETURNS INT BEGIN ATOMIC "
                + "SELECT * FROM no_such_table_xxx; END", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE bad4() BEGIN ATOMIC SELEC 1; END",
                ErrorCode.SYNTAX_ERROR_2);
    }

    // SCRIPT回放 + 关库重开
    private void testScriptRoundTrip() throws Exception {
        String procedureDdl = getCreateProcedureSql("PAB2");
        assertNotNull(procedureDdl);
        assertTrue(procedureDdl, procedureDdl.contains("BEGIN ATOMIC"));
        String functionDdl = getCreateFunctionSql("FAB2");
        assertNotNull(functionDdl);
        assertTrue(functionDdl, functionDdl.contains("BEGIN ATOMIC"));

        executeUpdate("DELETE FROM pab_t");
        executeUpdate("DROP PROCEDURE pab2");
        executeUpdate("DROP FUNCTION fab2");
        executeUpdate(procedureDdl);
        executeUpdate(functionDdl);
        executeQuery("CALL pab2()");
        assertEquals(30, getIntValue(1, true));
        executeQuery("SELECT fab2(6)");
        assertEquals(60, getIntValue(1, true));

        // 关库重开
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        executeUpdate("DELETE FROM pab_t");
        executeQuery("CALL pab2()");
        assertEquals(30, getIntValue(1, true));
        executeQuery("SELECT fab2(7)");
        assertEquals(70, getIntValue(1, true));
        executeQuery("SELECT fab1(1)");
        assertEquals(2, getIntValue(1, true));
    }

    private String getCreateProcedureSql(String procedureName) {
        return getCreateSql("CREATE PROCEDURE PUBLIC." + procedureName + "(");
    }

    private String getCreateFunctionSql(String functionName) {
        return getCreateSql("CREATE FUNCTION " + functionName + "(");
    }

    private String getCreateSql(String prefix) {
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
