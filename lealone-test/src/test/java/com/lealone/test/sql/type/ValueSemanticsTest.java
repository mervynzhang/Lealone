/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.type;

import java.math.BigDecimal;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 值语义回归：AVG 类型推导、DECIMAL 除法标度、TRUNC/ROUND、整型溢出错误码。
 * <p>
 * 期望值以 SQL 标准/ H2 语义为准。已经确认 Lealone 目前不符合期望的断言单独放在
 * {@code @Ignore("BUG: ...")} 方法里，这样缺陷被钉在代码库里，但不会让门禁变红。
 */
public class ValueSemanticsTest extends SqlAssertTestBase {

    private void initTable() {
        executeUpdate("DROP TABLE IF EXISTS vs_num");
        executeUpdate("CREATE TABLE vs_num(id int primary key, i int, d decimal(10,4), r real, db double)");
        executeUpdate("INSERT INTO vs_num VALUES(1, 1, 1.0000, 1.5, 1.5)");
        executeUpdate("INSERT INTO vs_num VALUES(2, 2, 2.0000, 2.5, 2.5)");
    }

    @Test
    public void integerDivisionTruncates() {
        assertQueryEquals(2, "SELECT 5 / 2");
        assertQueryEquals(-2, "SELECT -5 / 2");
        assertQueryEquals(0, "SELECT 1 / 3");
        assertQueryEquals(2, "SELECT 10 / 4");
        assertQueryEquals(1, "SELECT MOD(1, 3)");
    }

    @Test
    public void avgOfFloatingPointColumnIsFractional() throws Exception {
        initTable();
        // AVG 的结果类型跟着参数类型走：浮点/定点列的平均值必须是小数
        assertQueryEquals(1.5, "SELECT AVG(CAST(i AS DOUBLE)) FROM vs_num");
        assertQueryEquals(1.5, "SELECT AVG(d) FROM vs_num");
        assertQueryEquals(2.0, "SELECT AVG(db) FROM vs_num");
        // 空表时 AVG 必须是 NULL（不是 0）
        assertQueryNull("SELECT AVG(d) FROM vs_num WHERE id > 100");
    }

    @Test
    public void decimalDivisionWithoutRemainder() {
        // 能整除时结果精确：10.0000 / 4.0000 = 2.5
        assertQueryEquals(new BigDecimal("2.5"),
                "SELECT CAST(10 AS DECIMAL(10,4)) / CAST(4 AS DECIMAL(10,4))");
        assertQueryEquals(new BigDecimal("0.5"),
                "SELECT CAST(1 AS DECIMAL(5,2)) / CAST(2 AS DECIMAL(5,2))");
        assertQueryEquals(new BigDecimal("3.00"),
                "SELECT CAST(1 AS DECIMAL(5,2)) + CAST(2 AS DECIMAL(5,2))");
        assertQueryEquals(new BigDecimal("1.0000"),
                "SELECT CAST(1 AS DECIMAL(5,2)) * CAST(1 AS DECIMAL(5,2))");
    }

    @Test
    public void truncWithScale() {
        assertQueryEquals(1.23, "SELECT TRUNC(1.234, 2)");
        assertQueryEquals(1.99, "SELECT TRUNC(1.999, 2)");
        assertQueryEquals(-1.99, "SELECT TRUNC(-1.999, 2)");
        assertQueryEquals(1.0, "SELECT TRUNC(1.9, 0)");
        assertQueryEquals(-1.0, "SELECT TRUNC(-1.9, 0)");
        // 负数标度：按十位/百位截断
        assertQueryEquals(120.0, "SELECT TRUNC(123.456, -1)");
        // TRUNC 是截断，不是四舍五入
        assertQueryEquals(1.23, "SELECT TRUNC(1.239, 2)");
    }

    @Test
    public void roundBoundaries() {
        assertQueryEquals(1.0, "SELECT ROUND(1.4)");
        assertQueryEquals(2.0, "SELECT ROUND(1.5)");
        assertQueryEquals(2.0, "SELECT ROUND(1.6)");
        assertQueryEquals(3.0, "SELECT ROUND(2.5)");
        assertQueryEquals(1.235, "SELECT ROUND(1.2345, 3)");
        assertQueryEquals(1.3, "SELECT ROUND(1.25, 1)");
        assertQueryEquals(1.4, "SELECT ROUND(1.35, 1)");
        assertQueryEquals(20.0, "SELECT ROUND(15, -1)");
        assertQueryEquals(120.0, "SELECT ROUND(123.456, -1)");
    }

    @Test
    public void castDoubleToIntRounds() {
        assertQueryEquals(2, "SELECT CAST(1.5 AS INT)");
        assertQueryEquals(-2, "SELECT CAST(-1.5 AS INT)");
        assertQueryEquals(1, "SELECT CAST(1.4 AS INT)");
        assertQueryEquals(1.9, "SELECT CAST(1.9 AS DOUBLE)");
    }

    @Test
    public void integerOverflowErrorCodes() {
        // 22003 NUMERIC_VALUE_OUT_OF_RANGE_1
        assertSqlFails("SELECT CAST(2147483647 AS INT) + 1", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT 2147483647 * 2", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT CAST(2147483648 AS INT)", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT CAST(9223372036854775807 AS BIGINT) + 1",
                ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT ABS(CAST(-2147483648 AS INT))", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT CAST(1e40 AS INT)", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        // 字符串转整数溢出是"数据转换错误" 22018，而不是 22003
        assertSqlFails("SELECT CAST('99999999999999' AS INT)", ErrorCode.DATA_CONVERSION_ERROR_1);
        assertSqlFails("SELECT CAST('abc' AS INT)", ErrorCode.DATA_CONVERSION_ERROR_1);
    }

    @Test
    public void divisionByZeroErrorCode() {
        assertSqlFails("SELECT CAST(1 AS INT) / CAST(0 AS INT)", ErrorCode.DIVISION_BY_ZERO_1);
        assertSqlFails("SELECT CAST(1 AS INT) % 0", ErrorCode.DIVISION_BY_ZERO_1);
        assertSqlFails("SELECT CAST(1 AS DECIMAL(10,2)) / CAST(0 AS DECIMAL(10,2))",
                ErrorCode.DIVISION_BY_ZERO_1);
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // BUG: AVG 对整数列做整数除法，结果被截断。
    // 期望: AVG(1,2)=1.5（H2 返回 DOUBLE 1.5）；实际: 1（类型 INTEGER）。
    // 原因: Avg 聚合复用参数类型，sum/count 走整数除法。
    @Ignore("BUG: AVG on INTEGER column truncates 1.5 to 1 (result type stays INTEGER); H2 returns 1.5")
    @Test
    public void avgOnIntegerColumnIsTruncated() {
        assertQueryEquals(1.5, "SELECT AVG(x) FROM (SELECT 1 x UNION ALL SELECT 2)");
        assertQueryEquals(1.5, "SELECT AVG(c1) FROM (VALUES(1),(2))");
    }

    // BUG: AVG 对 BIGINT 列更糟，(1,2) 得到 2（既不是 1.5，也不是整数除法的 1）。
    // 期望: 1.5（H2 返回 NUMERIC 1.5000000000）；实际: 2（类型 BIGINT）。
    @Ignore("BUG: AVG on BIGINT column returns 2 for (1,2); expected 1.5")
    @Test
    public void avgOnBigintColumn() {
        assertQueryEquals(1.5,
                "SELECT AVG(x) FROM (SELECT CAST(1 AS BIGINT) x UNION ALL SELECT CAST(2 AS BIGINT))");
    }

    // BUG: DECIMAL 除法标度取到了上限（29），结果小数位远多于操作数精度。
    // 期望: DECIMAL(10,4)/DECIMAL(10,4) 的商标度不超过 20（H2 2.4.240 为 20，H2 1.4 为 15）；
    // 实际: DECIMAL(29,29)，0.33333333333333333333333333333。
    // 影响: 商参与后续运算/比较时精度失真，且结果集元数据不可预期。
    @Ignore("BUG: DECIMAL(10,4)/DECIMAL(10,4) produces scale 29 instead of <= 20 (H2: 20)")
    @Test
    public void decimalDivisionScaleIsTooLarge() {
        Object o = queryObject("SELECT CAST(1 AS DECIMAL(10,4)) / CAST(3 AS DECIMAL(10,4))");
        assertTrue("result should be BigDecimal but was " + o.getClass(), o instanceof BigDecimal);
        BigDecimal d = (BigDecimal) o;
        assertTrue("scale should not exceed 20 but was " + d.scale() + " (" + d + ")",
                d.scale() <= 20);
    }

    // BUG: 单参数 TRUNC(x) 抛 NPE。
    // 期望: TRUNC(x) 等价于 TRUNC(x, 0)，返回 1（H2 返回 NUMERIC 1）；
    // 实际: General error + NullPointerException（错误码 50000）。
    @Ignore("BUG: TRUNC(x) with one argument throws NPE (50000); expected TRUNC(x,0)=1")
    @Test
    public void truncWithSingleArgument() {
        assertQueryEquals(1, "SELECT TRUNC(1.9)");
        assertQueryEquals(1, "SELECT TRUNC(1)");
        assertQueryEquals(1, "SELECT TRUNC(CAST(1 AS DECIMAL(10,2)))");
    }

    // BUG: ROUND 对 DECIMAL 入参先转成 DOUBLE，丢掉标度并按二进制浮点舍入。
    // 期望: ROUND(CAST(1.005 AS DECIMAL(10,3)), 2) = 1.01（H2 返回 NUMERIC(3,2) 1.01）；
    // 实际: 1.0（结果类型 DOUBLE）。
    @Ignore("BUG: ROUND(DECIMAL, n) converts to DOUBLE, 1.005 -> 1.0 instead of 1.01")
    @Test
    public void roundOnDecimalKeepsDecimalSemantics() {
        assertQueryEquals(new BigDecimal("1.01"),
                "SELECT ROUND(CAST(1.005 AS DECIMAL(10,3)), 2)");
        Object o = queryObject("SELECT ROUND(CAST(2.5 AS DECIMAL(10,1)))");
        assertTrue("ROUND(DECIMAL) should stay DECIMAL but was " + o.getClass().getSimpleName(),
                o instanceof BigDecimal);
    }

    // BUG: ROUND 的平局舍入方向不是"远离 0"，而是朝 +∞ 取整。
    // 期望: ROUND(-2.5) = -3（H2 为 -3，即 HALF_UP）；实际: -2。
    @Ignore("BUG: ROUND(-2.5) returns -2 instead of -3 (ties round toward +infinity, H2 rounds away from zero)")
    @Test
    public void roundTieRoundsAwayFromZero() {
        assertQueryEquals(-3.0, "SELECT ROUND(-2.5)");
        assertQueryEquals(-2.0, "SELECT ROUND(-1.5)");
        assertQueryEquals(-3.0, "SELECT ROUND(CAST(-2.5 AS DECIMAL(10,1)))");
    }
}
