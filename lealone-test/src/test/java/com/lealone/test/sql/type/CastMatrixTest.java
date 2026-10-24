/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.type;

import java.sql.ResultSet;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * CAST 矩阵：数值 ↔ 字符串 ↔ 时间 ↔ UUID 互转、空串转数值、DECIMAL 溢出、浮点转 DECIMAL(p,s)。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。已确认不符合期望的断言放在 {@code @Ignore("BUG: ...")} 里。
 */
public class CastMatrixTest extends SqlAssertTestBase {

    @Test
    public void numberToStringAndBack() {
        assertQueryEquals("123", "SELECT CAST(123 AS VARCHAR)");
        assertQueryEquals("-1", "SELECT CAST(-1 AS VARCHAR)");
        assertQueryEquals(123, "SELECT CAST('123' AS INT)");
        assertQueryEquals(-123, "SELECT CAST('-123' AS INT)");
        // 允许前后空白
        assertQueryEquals(12, "SELECT CAST(' 12 ' AS INT)");
        assertQueryEquals(1.5, "SELECT CAST('1.5' AS DOUBLE)");
    }

    @Test
    public void stringToNumberErrors() {
        // 空串/全空白/带杂质/非数字，都是"数据转换错误" 22018
        assertSqlFails("SELECT CAST('' AS INT)", ErrorCode.DATA_CONVERSION_ERROR_1);
        assertSqlFails("SELECT CAST('' AS BIGINT)", ErrorCode.DATA_CONVERSION_ERROR_1);
        assertSqlFails("SELECT CAST('   ' AS INT)", ErrorCode.DATA_CONVERSION_ERROR_1);
        assertSqlFails("SELECT CAST('12abc' AS INT)", ErrorCode.DATA_CONVERSION_ERROR_1);
        assertSqlFails("SELECT CAST('abc' AS DOUBLE)", ErrorCode.DATA_CONVERSION_ERROR_1);
    }

    @Test
    public void decimalCasts() {
        assertQueryEquals(2.00, "SELECT CAST(1.999 AS DECIMAL(5,2))");
        assertQueryEquals(2, "SELECT CAST(1.999 AS DECIMAL(5,0))");
        assertQueryEquals(1.234, "SELECT CAST(1.234 AS DECIMAL(10,3))");
        assertQueryEquals(1.2345678901, "SELECT CAST(1.23456789012345678 AS DECIMAL(20,10))");
        assertQueryEquals(new java.math.BigDecimal("12345678901234567890"),
                "SELECT CAST(12345678901234567890 AS DECIMAL(20,0))");
    }

    @Test
    public void decimalOverflowErrors() {
        // 数值超出 DECIMAL(p,s) 声明精度：22003 NUMERIC_VALUE_OUT_OF_RANGE_1
        // 注: H2 对 CAST 溢出返回 22001，Lealone 返回 22003，两者都是 SQLException，这里固定 Lealone 现行行为
        assertSqlFails("SELECT CAST(999.99 AS DECIMAL(3,1))",
                ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT CAST(1e40 AS DECIMAL(5,2))", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        // 写入 DECIMAL 列时溢出走的是 22001 VALUE_TOO_LONG_2（与 H2 一致）
        executeUpdate("DROP TABLE IF EXISTS cm_dec");
        executeUpdate("CREATE TABLE cm_dec(d decimal(4,2))");
        executeUpdate("INSERT INTO cm_dec VALUES(99.99)");
        assertSqlFails("INSERT INTO cm_dec VALUES(999.99)", ErrorCode.VALUE_TOO_LONG_2);
        assertQueryEquals(99.99, "SELECT d FROM cm_dec");
        executeUpdate("DROP TABLE cm_dec");
    }

    @Test
    public void floatingPointToDecimal() {
        assertQueryEquals(1.2346, "SELECT CAST(CAST(1.23456789 AS DOUBLE) AS DECIMAL(10,4))");
        assertQueryEquals(1.01, "SELECT CAST(CAST(1.005 AS REAL) AS DECIMAL(10,2))");
        assertQueryEquals(1.01, "SELECT CAST(CAST(1.005 AS DECIMAL(10,3)) AS DECIMAL(10,2))");
    }

    @Test
    public void temporalCasts() {
        assertQueryEquals("2020-01-02", "SELECT CAST(CAST('2020-01-02' AS DATE) AS VARCHAR)");
        assertQueryEquals("2020-01-02",
                "SELECT CAST(CAST(CAST('2020-01-02 03:04:05' AS TIMESTAMP) AS DATE) AS VARCHAR)");
        assertQueryEquals("03:04:05",
                "SELECT CAST(CAST(CAST('2020-01-02 03:04:05' AS TIMESTAMP) AS TIME) AS VARCHAR)");
        assertQueryEquals("2020-01-02 03:04:05.0",
                "SELECT CAST(CAST('2020-01-02 03:04:05' AS TIMESTAMP) AS VARCHAR)");
        assertQueryEquals("2020-01-02",
                "SELECT CAST(CAST(CAST('2020-01-02T03:04:05' AS TIMESTAMP) AS DATE) AS VARCHAR)");
        assertSqlFails("SELECT CAST('2020-13-01' AS DATE)", ErrorCode.INVALID_DATETIME_CONSTANT_2);
    }

    @Test
    public void uuidCasts() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertQueryEquals(uuid, "SELECT CAST(CAST('" + uuid + "' AS UUID) AS VARCHAR)");
        assertSqlFails("SELECT CAST('zz' AS UUID)", ErrorCode.DATA_CONVERSION_ERROR_1);
    }

    @Test
    public void booleanCasts() {
        assertQueryEquals("TRUE", "SELECT CAST(TRUE AS VARCHAR)");
        assertQueryEquals("FALSE", "SELECT CAST(FALSE AS VARCHAR)");
        assertQueryEquals(Boolean.TRUE, "SELECT CAST('true' AS BOOLEAN)");
        assertQueryEquals(Boolean.TRUE, "SELECT CAST('T' AS BOOLEAN)");
        assertQueryEquals(Boolean.FALSE, "SELECT CAST('F' AS BOOLEAN)");
        assertQueryEquals(Boolean.TRUE, "SELECT CAST(1 AS BOOLEAN)");
        assertQueryEquals(Boolean.FALSE, "SELECT CAST(0 AS BOOLEAN)");
        assertQueryEquals(Boolean.TRUE, "SELECT CAST('yes' AS BOOLEAN)");
        assertQueryEquals(Boolean.TRUE, "SELECT CAST(123 AS BOOLEAN)");
    }

    @Test
    public void nullAndStringLengthCasts() {
        assertQueryNull("SELECT CAST(NULL AS INT)");
        assertQueryNull("SELECT CAST(NULL AS VARCHAR)");
        assertQueryNull("SELECT CAST(NULL AS DATE)");
        assertQueryNull("SELECT CAST(NULL AS UUID)");
        // VARCHAR(n) 超长时静默截断
        assertQueryEquals("ab", "SELECT CAST('abcdef' AS VARCHAR(2))");
        assertQueryEquals("abc", "SELECT CAST('abc' AS VARCHAR(10))");
    }

    @Test
    public void integerRangeCasts() {
        assertSqlFails("SELECT CAST(200 AS TINYINT)", ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertSqlFails("SELECT CAST(12345678901234567890 AS BIGINT)",
                ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1);
        assertQueryEquals(-128, "SELECT CAST(-128 AS TINYINT)");
        assertQueryEquals(127, "SELECT CAST(127 AS TINYINT)");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // BUG: CAST 到 CHAR(n) 不做空格填充，声明长度被忽略。
    // 期望: CAST('abc' AS CHAR(5)) = 'abc  ' 且长度为 5（H2 返回 CHARACTER(5) 'abc  '）；
    // 实际: 'abc'，长度 3，元数据里类型长度也是 3。
    @Ignore("BUG: CAST('abc' AS CHAR(5)) is not space padded to length 5")
    @Test
    public void charCastPadsToDeclaredLength() {
        assertQueryEquals("abc  ", "SELECT CAST('abc' AS CHAR(5))");
        assertQueryEquals(5L, "SELECT CHAR_LENGTH(CAST('abc' AS CHAR(5)))");
        assertQueryEquals("1  ", "SELECT CAST(1 AS CHAR(3))");
    }

    // BUG: CAST(... AS DECIMAL(p,s)) 的结果精度不是声明的 p，而是按实际值位数缩水。
    // 期望: 列元数据精度 = 5（H2 返回 DECIMAL(5,2)）；实际: 3。
    @Test
    public void decimalCastKeepsDeclaredPrecision() throws Exception {
        try (ResultSet r = stmt.executeQuery("SELECT CAST(1.999 AS DECIMAL(5,2))")) {
            r.next();
            assertEquals(5, r.getMetaData().getPrecision(1));
            assertEquals(2, r.getMetaData().getScale(1));
        }
    }

    // BUG: 非法的 UUID 字符串被当成十六进制数解析，而不是报错。
    // 期望: CAST('abc' AS UUID) 抛 22018（H2 为 Data conversion error converting "abc"）；
    // 实际: 返回 00000000-0000-0000-0000-000000000abc。
    // 非法UUID必须报转换错误
    @Test
    public void invalidUuidStringIsRejected() {
        assertSqlFails("SELECT CAST('abc' AS UUID)", ErrorCode.DATA_CONVERSION_ERROR_1);
    }
}
