/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PG回归套件暴露的SQL函数/存储过程缺陷的回归用例。
 */
public class PgFunctionDefectTest extends SqlTestBase {

    public PgFunctionDefectTest() {
        setEmbedded(true);
    }

    // RETURNS SETOF VOID: 和PostgreSQL一致, 不返回任何行(也不能是Java序列化字节)
    @Test
    public void testSetofVoid() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION sv2() RETURNS SETOF VOID AS $$ SELECT 1 $$");
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM sv2()")) {
            assertFalse(rs.next());
        }
        // VOID不关心函数体返回多少列
        executeUpdate("CREATE OR REPLACE FUNCTION sv3() RETURNS SETOF VOID AS $$ SELECT 1, 2 $$");
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM sv3()")) {
            assertFalse(rs.next());
        }
        // 标量VOID仍然正常
        executeUpdate("CREATE OR REPLACE FUNCTION sv4() RETURNS VOID AS $$ SELECT 1, 2 $$");
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT sv4()")) {
            assertTrue(rs.next());
            assertNull(rs.getString(1));
        }
    }

    // 声明了标量返回类型时, 函数体返回多列要在建函数时报错, 不能静默取第一列
    @Test
    public void testScalarBodyColumnCount() throws Exception {
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION f_multi_col() RETURNS INT AS $$ SELECT 1, 2, 3 $$",
                ErrorCode.DATA_CONVERSION_ERROR_1);
        // 建函数失败后不能留下半成品
        executeQueryThanAssertErrorCode("SELECT f_multi_col()", ErrorCode.FUNCTION_NOT_FOUND_1);

        // 单列函数体正常
        executeUpdate("CREATE OR REPLACE FUNCTION f_one_col() RETURNS INT AS $$ SELECT 1 $$");
        executeQuery("SELECT f_one_col()");
        assertEquals(1, getIntValue(1));
    }

    // 建函数时做最小校验: prepare一次(不执行), 语法/列名/常量类型错误提前报出来
    @Test
    public void testCreateTimeBodyValidation() throws Exception {
        // 常量类型不匹配
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION f_bad_type() RETURNS INT AS $$ SELECT 'not an integer' $$",
                ErrorCode.DATA_CONVERSION_ERROR_1);
        // 语法错误
        executeUpdateThanAssertErrorCode("CREATE FUNCTION f_bad_syntax() RETURNS INT AS $$ SELECT FROM $$",
                ErrorCode.SYNTAX_ERROR_2);
        // 列名不存在
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION f_bad_column() RETURNS INT AS $$ SELECT no_such_column $$",
                ErrorCode.COLUMN_NOT_FOUND_1);
        // 表不存在
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION f_bad_table() RETURNS INT AS $$ SELECT id FROM no_such_table $$",
                ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        // setof/table函数体也做prepare校验(只是结果列形状留到调用时校验)
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION f_bad_setof() RETURNS TABLE(a INT) AS $$ SELECT FROM $$",
                ErrorCode.SYNTAX_ERROR_2);

        // 校验不执行函数体: 存表能查到数据后建函数成功, 但不会把查询结果算出来
        executeUpdate("DROP TABLE IF EXISTS f_check_t");
        executeUpdate("CREATE TABLE f_check_t(id INT PRIMARY KEY)");
        executeUpdate("CREATE OR REPLACE FUNCTION f_ok(a INT) RETURNS INT "
                + "AS $$ SELECT id FROM f_check_t WHERE id = a $$");
        executeQuery("SELECT f_ok(1)");
        assertNull(getStringValue(1));
        executeUpdate("INSERT INTO f_check_t VALUES(1)");
        executeQuery("SELECT f_ok(1)");
        assertEquals(1, getIntValue(1));

        // 原名函数已存在时IF NOT EXISTS直接跳过, 不校验新函数体
        executeUpdate("CREATE OR REPLACE FUNCTION f_keep(a INT) RETURNS INT AS $$ SELECT a $$");
        executeUpdate("CREATE FUNCTION IF NOT EXISTS f_keep(a INT) RETURNS INT "
                + "AS $$ SELECT 'not an integer' $$");
        executeQuery("SELECT f_keep(3)");
        assertEquals(3, getIntValue(1));
    }

    // 函数体是WITH ... SELECT时参数要能正确绑定
    @Test
    public void testWithParameter() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION f_with(a INT) RETURNS INT "
                + "AS $$ WITH x AS (SELECT a * 2 AS v) SELECT v FROM x $$");
        executeQuery("SELECT f_with(5)");
        assertEquals(10, getIntValue(1));

        // 参数在CTE和外层查询里各出现一次
        executeUpdate("CREATE OR REPLACE FUNCTION f_with2(a INT) RETURNS INT "
                + "AS $$ WITH x AS (SELECT a * 2 AS v) SELECT v + a FROM x $$");
        executeQuery("SELECT f_with2(5)");
        assertEquals(15, getIntValue(1));

        // 同一个函数的两次调用(参数值不同)
        executeQuery("SELECT f_with(7)");
        assertEquals(14, getIntValue(1));

        // 多个CTE引用同一个参数，CTE的SQL里参数是全局编号
        executeUpdate("CREATE OR REPLACE FUNCTION f_with3(a INT) RETURNS INT AS "
                + "$$ WITH x AS (SELECT a * 2 AS v), y AS (SELECT a * 3 AS w) "
                + "SELECT v + w FROM x, y $$");
        executeQuery("SELECT f_with3(5)");
        assertEquals(25, getIntValue(1));

        // 用PreparedStatement传参
        try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT f_with(?)")) {
            ps.setInt(1, 6);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1));
            }
        }
    }

    // 过程体里嵌套CALL时, 多条语句各自独立绑定参数
    @Test
    public void testNestedCallParameters() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS cp_test");
        executeUpdate("CREATE TABLE cp_test(a INT, b VARCHAR(20))");
        executeUpdate("DROP PROCEDURE IF EXISTS dp1");
        executeUpdate("DROP PROCEDURE IF EXISTS dp3");
        executeUpdate("CREATE PROCEDURE dp1(x VARCHAR) AS 'INSERT INTO cp_test VALUES(1, ?)'");
        executeUpdate("CREATE PROCEDURE dp3(y VARCHAR) AS 'CALL dp1(?); CALL dp1(?)'");
        executeUpdate("CALL dp3('b')");
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT a, b FROM cp_test ORDER BY b")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals("b", rs.getString(2));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals("b", rs.getString(2));
            assertFalse(rs.next());
        }
    }

    // CALL的 过程体是查询时, Statement.execute()要返回true并且getResultSet()能拿到行
    @Test
    public void testCallExecuteResultSet() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS ep_select");
        executeUpdate("DROP PROCEDURE IF EXISTS ep_insert");
        executeUpdate("DROP PROCEDURE IF EXISTS ep_multi");
        executeUpdate("DROP TABLE IF EXISTS ep_t");
        executeUpdate("CREATE TABLE ep_t(id INT PRIMARY KEY)");
        executeUpdate("CREATE PROCEDURE ep_select() AS 'SELECT 5'");
        executeUpdate("CREATE PROCEDURE ep_insert() AS 'INSERT INTO ep_t VALUES(1)'");
        executeUpdate("CREATE PROCEDURE ep_multi() AS 'INSERT INTO ep_t VALUES(2); SELECT 6'");

        try (Statement s = conn.createStatement()) {
            assertTrue(s.execute("CALL ep_select()"));
            try (ResultSet rs = s.getResultSet()) {
                assertNotNull(rs);
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
                assertFalse(rs.next());
            }

            // 只有更新语句的过程仍然按更新语句执行
            assertFalse(s.execute("CALL ep_insert()"));
            assertEquals(1, s.getUpdateCount());

            // 最后一条是查询的多语句过程体
            assertTrue(s.execute("CALL ep_multi()"));
            try (ResultSet rs = s.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(6, rs.getInt(1));
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }
}
