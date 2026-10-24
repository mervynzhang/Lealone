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
 * PostgreSQL风格存储函数的参数机制: OUT/INOUT参数、STRICT、VARIADIC、调用期类型转换。
 */
public class PgFunctionParamTest extends SqlTestBase {

    public PgFunctionParamTest() {
        setEmbedded(true);
    }

    // 单个OUT参数组成返回行, RETURNS可以省略
    @Test
    public void testOutParameter() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_one(IN a INT, OUT b INT) AS $$ SELECT a + 1 $$");
        executeQuery("SELECT p_out_one(1)");
        assertEquals(2, getIntValue(1));

        // OUT参数可以省略名字
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_out_unnamed(IN a INT, OUT INT) AS $$ SELECT a * 2 $$");
        executeQuery("SELECT p_out_unnamed(3)");
        assertEquals(6, getIntValue(1));

        // 只有OUT参数, 没有入参
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_only(OUT b INT) AS $$ SELECT 42 $$");
        executeQuery("SELECT p_out_only()");
        assertEquals(42, getIntValue(1));

        // RETURNS和OUT参数类型一致时允许同时写
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_returns(IN a INT, OUT b INT) RETURNS INT "
                + "AS $$ SELECT a - 1 $$");
        executeQuery("SELECT p_out_returns(3)");
        assertEquals(2, getIntValue(1));

        // RETURNS和OUT参数类型不一致时报错
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION p_out_bad(IN a INT, OUT b INT) RETURNS VARCHAR AS $$ SELECT a $$",
                ErrorCode.SYNTAX_ERROR_1);

        // 没有OUT参数时RETURNS不能省略
        executeUpdateThanAssertErrorCode("CREATE FUNCTION p_no_returns(a INT) AS $$ SELECT a $$",
                ErrorCode.SYNTAX_ERROR_1);

        // OUT参数不算入参, 多传一个参数报函数找不到
        executeQueryThanAssertErrorCode("SELECT p_out_one(1, 2)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // 多个OUT参数组成一行, 可以当成只有一行的表查询
    @Test
    public void testMultipleOutParameters() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_multi(IN a INT, OUT x INT, OUT y INT) "
                + "AS $$ SELECT a, a * 2 $$");
        executeQuery("SELECT * FROM p_out_multi(5)");
        assertEquals(5, getIntValue(1));
        assertEquals(10, getIntValue(2));

        // OUT参数按声明顺序组成返回列, 和入参的位置无关
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_order(OUT x INT, IN a INT, OUT y INT) "
                + "AS $$ SELECT a + 1, a + 2 $$");
        executeQuery("SELECT * FROM p_out_order(10)");
        assertEquals(11, getIntValue(1));
        assertEquals(12, getIntValue(2));

        // 多个OUT参数不能再用RETURNS
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION p_out_multi_bad(OUT x INT, OUT y INT) RETURNS INT AS $$ SELECT 1 $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // INOUT参数既是入参也是返回列
    @Test
    public void testInOutParameter() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_inout_double(INOUT x INT) AS $$ SELECT x * 2 $$");
        executeQuery("SELECT p_inout_double(21)");
        assertEquals(42, getIntValue(1));

        // IN和INOUT可以混用
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_inout_mix(IN a INT, INOUT b INT) AS $$ SELECT a + b $$");
        executeQuery("SELECT p_inout_mix(1, 2)");
        assertEquals(3, getIntValue(1));

        // INOUT参数不能省略
        executeQueryThanAssertErrorCode("SELECT p_inout_mix(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // OUT/INOUT参数要能通过SCRIPT语句持久化并原样回放
    @Test
    public void testOutParameterPersist() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_out_persist(IN a INT, OUT b INT) AS $$ SELECT a * 7 $$");
        executeUpdate("CREATE OR REPLACE FUNCTION p_inout_persist(INOUT x INT) AS $$ SELECT x + 1 $$");
        String outSql = getCreateSql("P_OUT_PERSIST");
        String inoutSql = getCreateSql("P_INOUT_PERSIST");
        assertTrue(outSql, outSql.contains("OUT "));
        assertFalse(outSql, outSql.contains("RETURNS "));
        assertTrue(inoutSql, inoutSql.contains("INOUT "));

        executeUpdate("DROP FUNCTION p_out_persist");
        executeUpdate("DROP FUNCTION p_inout_persist");
        executeUpdate(outSql);
        executeUpdate(inoutSql);
        executeQuery("SELECT p_out_persist(6)");
        assertEquals(42, getIntValue(1));
        executeQuery("SELECT p_inout_persist(41)");
        assertEquals(42, getIntValue(1));
    }

    // 从SCRIPT的输出里找出指定函数的CREATE FUNCTION语句
    private String getCreateSql(String functionName) {
        String prefix = "CREATE FUNCTION " + functionName;
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
