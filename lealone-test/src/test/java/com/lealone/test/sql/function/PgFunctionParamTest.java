/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Ignore;
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

    // STRICT: 任一入参为NULL时不执行函数体, 直接返回NULL
    @Test
    public void testStrict() throws Exception {
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_strict_div(a INT) RETURNS INT STRICT AS $$ SELECT 1 / a $$");
        executeQuery("SELECT p_strict_div(2)");
        assertEquals(0, getIntValue(1));

        // 入参是0时函数体会真的执行, 报除零错误
        executeQueryThanAssertErrorCode("SELECT p_strict_div(0)", ErrorCode.DIVISION_BY_ZERO_1);

        // 入参是NULL时函数体不执行, 所以不会除零, 直接返回NULL
        executeQuery("SELECT p_strict_div(NULL)");
        assertNull(getStringValue(1));

        // 多个入参时任一为NULL就不执行
        executeUpdate("CREATE OR REPLACE FUNCTION p_strict_add(a INT, b INT) RETURNS INT STRICT "
                + "AS $$ SELECT a + b $$");
        executeQuery("SELECT p_strict_add(1, NULL), p_strict_add(NULL, 1), p_strict_add(1, 2)");
        assertNull(getStringValue(1));
        assertNull(getStringValue(2));
        assertEquals(3, getIntValue(3));

        // STRICT作用于入参, OUT参数不算入参
        executeUpdate("CREATE OR REPLACE FUNCTION p_strict_out(IN a INT, OUT b INT) STRICT "
                + "AS $$ SELECT a + 1 $$");
        executeQuery("SELECT p_strict_out(NULL)");
        assertNull(getStringValue(1));

        // 标准SQL写法 RETURNS NULL ON NULL INPUT 等价于STRICT
        executeUpdate("CREATE OR REPLACE FUNCTION p_strict_sql(a INT) RETURNS INT "
                + "RETURNS NULL ON NULL INPUT AS $$ SELECT 1 / a $$");
        executeQuery("SELECT p_strict_sql(NULL)");
        assertNull(getStringValue(1));

        // STRICT要能被持久化和回放
        String createSql = getCreateSql("P_STRICT_DIV");
        assertTrue(createSql, createSql.contains(" STRICT "));
        executeUpdate("DROP FUNCTION p_strict_div");
        executeUpdate(createSql);
        executeQuery("SELECT p_strict_div(4)");
        assertEquals(0, getIntValue(1));
    }

    // VARIADIC: 最后一个参数是数组, 调用时可变个实参展开
    @Test
    public void testVariadic() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_var_count(VARIADIC nums INT[]) RETURNS INT "
                + "AS $$ SELECT ARRAY_LENGTH(nums) $$");
        executeQuery("SELECT p_var_count(1, 2, 3)");
        assertEquals(3, getIntValue(1));
        executeQuery("SELECT p_var_count(9)");
        assertEquals(1, getIntValue(1));
        // 一个实参都不传时是空数组
        executeQuery("SELECT p_var_count()");
        assertEquals(0, getIntValue(1));

        // 和普通参数混用
        executeUpdate("CREATE OR REPLACE FUNCTION p_var_mix(IN a INT, VARIADIC nums INT[]) "
                + "RETURNS INT AS $$ SELECT a + ARRAY_LENGTH(nums) $$");
        executeQuery("SELECT p_var_mix(10, 1, 2)");
        assertEquals(12, getIntValue(1));

        // 展开后的元素按数组元素类型使用
        executeUpdate("CREATE OR REPLACE FUNCTION p_var_first(VARIADIC nums INT[]) RETURNS INT "
                + "AS $$ SELECT ARRAY_GET(nums, 1) $$");
        executeQuery("SELECT p_var_first(7, 8, 9)");
        assertEquals(7, getIntValue(1));

        // VARIADIC必须是最后一个参数
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION p_var_bad(VARIADIC nums INT[], b INT) RETURNS INT AS $$ SELECT 1 $$",
                ErrorCode.SYNTAX_ERROR_1);
        // VARIADIC参数必须是数组类型
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION p_var_bad2(VARIADIC nums INT) RETURNS INT AS $$ SELECT 1 $$",
                ErrorCode.SYNTAX_ERROR_1);
        // 固定参数不能少
        executeQueryThanAssertErrorCode("SELECT p_var_mix()", ErrorCode.FUNCTION_NOT_FOUND_1);

        // VARIADIC要能被持久化和回放
        String createSql = getCreateSql("P_VAR_COUNT");
        assertTrue(createSql, createSql.contains("VARIADIC "));
        executeUpdate("DROP FUNCTION p_var_count");
        executeUpdate(createSql);
        executeQuery("SELECT p_var_count(1, 2)");
        assertEquals(2, getIntValue(1));
    }

    // 调用期类型转换: 实参按参数声明的类型转换, 类型不兼容时报错
    @Test
    public void testArgumentTypeConversion() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_conv_int(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        // 字符串实参按声明类型转成INT
        executeQuery("SELECT p_conv_int('5')");
        assertEquals(6, getIntValue(1));
        // 不能转成INT的实参报类型转换错误
        executeQueryThanAssertErrorCode("SELECT p_conv_int('abc')",
                ErrorCode.DATA_CONVERSION_ERROR_1);
        executeQueryThanAssertErrorCode("SELECT p_conv_int('2020-01-02')",
                ErrorCode.DATA_CONVERSION_ERROR_1);

        // 按声明类型转成VARCHAR
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_conv_str(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a $$");
        executeQuery("SELECT p_conv_str(42)");
        assertEquals("42", getStringValue(1));

        // 字符串转DATE
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_conv_date(a DATE) RETURNS DATE AS $$ SELECT a $$");
        executeQuery("SELECT p_conv_date('2020-01-02')");
        assertEquals("2020-01-02", getStringValue(1));

        // INOUT参数也要经过转换
        executeUpdate(
                "CREATE OR REPLACE FUNCTION p_conv_inout(INOUT x INT) AS $$ SELECT x * 2 $$");
        executeQuery("SELECT p_conv_inout('21')");
        assertEquals(42, getIntValue(1));

        // 非字面量的实参在运行时转换
        executeUpdate("DROP TABLE IF EXISTS p_conv_t");
        executeUpdate("CREATE TABLE p_conv_t(id INT PRIMARY KEY, s VARCHAR(20))");
        executeUpdate("INSERT INTO p_conv_t VALUES(1, '10')");
        executeQuery("SELECT p_conv_int(s) FROM p_conv_t WHERE id = 1");
        assertEquals(11, getIntValue(1));

        // VARIADIC展开出来的每个实参也按数组元素类型转换
        executeUpdate("CREATE OR REPLACE FUNCTION p_var_count(VARIADIC nums INT[]) RETURNS INT "
                + "AS $$ SELECT ARRAY_LENGTH(nums) $$");
        executeUpdate("CREATE OR REPLACE FUNCTION p_var_first(VARIADIC nums INT[]) RETURNS INT "
                + "AS $$ SELECT ARRAY_GET(nums, 1) $$");
        executeQueryThanAssertErrorCode("SELECT p_var_count('a')",
                ErrorCode.DATA_CONVERSION_ERROR_1);
        executeQuery("SELECT p_var_first('7', '8')");
        assertEquals(7, getIntValue(1));
    }

    // 按参数类型重载目前不支持: schema里的函数对象以名字做唯一键
    @Test
    public void testOverloadNotSupported() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION p_ov(a INT) RETURNS VARCHAR AS $$ SELECT 'int' $$");
        executeQuery("SELECT p_ov(1)");
        assertEquals("int", getStringValue(1));

        // 同名但参数类型不同的函数不能并存
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION p_ov(a VARCHAR) RETURNS VARCHAR AS $$ SELECT 'varchar' $$",
                ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1);
    }

    // PostgreSQL允许 f(int) 和 f(varchar) 并存, Lealone目前做不到, 见 docs/PG_FUNCTION.md
    @Ignore("schema对象按名字做唯一键(TransactionalDbObjects/SCRIPT回放都以getName()为key), "
            + "同名函数会互相覆盖; 按签名存储需要改动通用对象存储层与解析/DDL/持久化多处, 超出本次范围")
    @Test
    public void testOverloadByParameterType() throws Exception {
        executeUpdate(
                "CREATE FUNCTION p_ov2(a INT) RETURNS VARCHAR AS $$ SELECT 'int' $$");
        executeUpdate(
                "CREATE FUNCTION p_ov2(a VARCHAR) RETURNS VARCHAR AS $$ SELECT 'varchar' $$");
        executeQuery("SELECT p_ov2(1), p_ov2('x')");
        assertEquals("int", getStringValue(1));
        assertEquals("varchar", getStringValue(2));
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
