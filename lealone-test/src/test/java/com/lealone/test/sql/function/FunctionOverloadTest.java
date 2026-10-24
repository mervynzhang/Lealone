/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.TimeZone;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * 同名函数按参数类型重载。消解规则对齐PostgreSQL：
 * 完全匹配优先，其次是可以隐式转换的类型，都没有匹配时报函数不存在(90022)；
 * 多个重载的匹配程度相同且无法用“未知类型优先按字符串类型解析”区分时报歧义(90152)。
 */
public class FunctionOverloadTest extends SqlTestBase {

    public FunctionOverloadTest() {
        setEmbedded(true);
    }

    // 同参数个数、不同参数类型
    @Test
    public void testOverloadByParameterType() throws Exception {
        executeUpdate("CREATE FUNCTION fo(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeUpdate("CREATE FUNCTION fo(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$");
        executeQuery("SELECT fo(1)");
        assertEquals(2, getIntValue(1));
        executeQuery("SELECT fo('x')");
        assertEquals("x!", getStringValue(1));

        // 只删掉int版本，varchar版本还在
        executeUpdate("DROP FUNCTION fo(INT)");
        // int到varchar不是隐式转换，PostgreSQL里 f(1) 找不到函数
        executeQueryThanAssertErrorCode("SELECT fo(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("SELECT fo('x')");
        assertEquals("x!", getStringValue(1));
    }

    // 参数个数不同的重载
    @Test
    public void testOverloadByParameterCount() throws Exception {
        executeUpdate("CREATE FUNCTION fc(a INT) RETURNS INT AS $$ SELECT a * 2 $$");
        executeUpdate("CREATE FUNCTION fc(a INT, b INT) RETURNS INT AS $$ SELECT a * b $$");
        executeUpdate("CREATE FUNCTION fc(a INT, b INT, c INT) RETURNS INT AS $$ SELECT a + b + c $$");
        executeQuery("SELECT fc(3), fc(3, 4), fc(1, 2, 3)");
        assertEquals(6, getIntValue(1));
        assertEquals(12, getIntValue(2));
        assertEquals(6, getIntValue(3));

        executeUpdate("DROP FUNCTION fc(INT, INT)");
        executeQueryThanAssertErrorCode("SELECT fc(3, 4)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("SELECT fc(3), fc(1, 2, 3)");
        assertEquals(6, getIntValue(1));
        assertEquals(6, getIntValue(2));
    }

    // DROP FUNCTION name(types) 只删指定的重载
    @Test
    public void testDropBySignature() throws Exception {
        executeUpdate("CREATE FUNCTION fd(a INT) RETURNS INT AS $$ SELECT 1 $$");
        executeUpdate("CREATE FUNCTION fd(a BIGINT) RETURNS INT AS $$ SELECT 2 $$");
        executeUpdate("CREATE FUNCTION fd(a VARCHAR) RETURNS INT AS $$ SELECT 3 $$");
        executeQuery("SELECT fd(1), fd(CAST(1 AS BIGINT)), fd('x')");
        assertEquals(1, getIntValue(1));
        assertEquals(2, getIntValue(2));
        assertEquals(3, getIntValue(3));

        executeUpdate("DROP FUNCTION fd(BIGINT)");
        executeQuery("SELECT fd(1), fd('x')");
        assertEquals(1, getIntValue(1));
        assertEquals(3, getIntValue(2));
        // BIGINT实参不会隐式转成INT
        executeQueryThanAssertErrorCode("SELECT fd(CAST(1 AS BIGINT))",
                ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // DROP FUNCTION 不写参数类型: 只有一个重载时能删，多个重载时报歧义
    @Test
    public void testDropWithoutSignature() throws Exception {
        executeUpdate("CREATE FUNCTION fe(a INT) RETURNS INT AS $$ SELECT 1 $$");
        executeUpdate("DROP FUNCTION fe");
        executeQueryThanAssertErrorCode("SELECT fe(1)", ErrorCode.FUNCTION_NOT_FOUND_1);

        executeUpdate("CREATE FUNCTION fe2(a INT) RETURNS INT AS $$ SELECT 1 $$");
        executeUpdate("CREATE FUNCTION fe2(a VARCHAR) RETURNS INT AS $$ SELECT 2 $$");
        executeUpdateThanAssertErrorCode("DROP FUNCTION fe2", ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        // 删掉一个之后名字就唯一了
        executeUpdate("DROP FUNCTION fe2(VARCHAR)");
        executeUpdate("DROP FUNCTION fe2");
        executeQueryThanAssertErrorCode("SELECT fe2(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // CREATE OR REPLACE 只替换参数类型相同的那个重载
    @Test
    public void testCreateOrReplaceOnlySameSignature() throws Exception {
        executeUpdate("CREATE FUNCTION fg(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeUpdate("CREATE FUNCTION fg(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$");
        executeUpdate("CREATE OR REPLACE FUNCTION fg(a INT) RETURNS INT AS $$ SELECT a + 100 $$");
        executeQuery("SELECT fg(1), fg('x')");
        assertEquals(101, getIntValue(1));
        assertEquals("x!", getStringValue(2));

        // 同参数类型重复创建(不带OR REPLACE)还是报已存在
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION fg(a INT) RETURNS INT AS $$ SELECT a + 200 $$",
                ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1);
        // IF NOT EXISTS 只跳过同参数类型的，不同参数类型的重载仍然要建
        executeUpdate(
                "CREATE FUNCTION IF NOT EXISTS fg(a INT) RETURNS INT AS $$ SELECT a + 300 $$");
        executeUpdate(
                "CREATE FUNCTION IF NOT EXISTS fg(a BIGINT) RETURNS BIGINT AS $$ SELECT a + 7 $$");
        executeQuery("SELECT fg(1), fg(CAST(1 AS BIGINT))");
        assertEquals(101, getIntValue(1));
        assertEquals(8L, getLongValue(2));
    }

    // 没有类型的字面量和NULL的消解规则
    @Test
    public void testUntypedLiteralAndNullArgument() throws Exception {
        // 字符串字面量在PostgreSQL里没有类型，按参数类型解析
        executeUpdate("CREATE FUNCTION fi(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeQuery("SELECT fi('5')");
        assertEquals(6, getIntValue(1));
        executeQueryThanAssertErrorCode("SELECT fi('abc')", ErrorCode.DATA_CONVERSION_ERROR_1);

        // 有多个重载时，未知类型优先选字符串类型的重载
        executeUpdate("CREATE FUNCTION fj(a INT) RETURNS VARCHAR AS $$ SELECT 'int' $$");
        executeUpdate("CREATE FUNCTION fj(a VARCHAR) RETURNS VARCHAR AS $$ SELECT 'varchar' $$");
        executeQuery("SELECT fj(NULL), fj('x')");
        assertEquals("varchar", getStringValue(1));
        assertEquals("varchar", getStringValue(2));
        // 写了类型就按类型消解
        executeQuery("SELECT fj(CAST(NULL AS INT))");
        assertEquals("int", getStringValue(1));

        // 没有字符串类型的重载可挑时，NULL实参是歧义的
        executeUpdate("CREATE FUNCTION fk(a INT) RETURNS VARCHAR AS $$ SELECT 'int' $$");
        executeUpdate("CREATE FUNCTION fk(a DATE) RETURNS VARCHAR AS $$ SELECT 'date' $$");
        executeQueryThanAssertErrorCode("SELECT fk(NULL)", ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeQuery("SELECT fk(1), fk(CAST('2020-01-02' AS DATE))");
        assertEquals("int", getStringValue(1));
        assertEquals("date", getStringValue(2));
    }

    // 运行期默认时区被改过之后(比如同一个JVM里别的用例调用TimeZone.setDefault)，
    // 隐式转换的结果也必须和显式CAST逐字节一致
    @Test
    public void testDateTimeConversionAfterTimeZoneChange() throws Exception {
        executeUpdate("CREATE FUNCTION fz(a TIMESTAMP) RETURNS TIMESTAMP AS $$ SELECT a $$");
        executeUpdate("CREATE FUNCTION fz2(a DATE) RETURNS DATE AS $$ SELECT a $$");
        TimeZone defaultZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"));
            executeQuery("SELECT fz(CAST('2020-01-02' AS DATE)), "
                    + "CAST(CAST('2020-01-02' AS DATE) AS TIMESTAMP)");
            assertEquals(getStringValue(2), getStringValue(1));
            assertEquals(rs.getTimestamp(2).getTime(), rs.getTimestamp(1).getTime());
            // DATE参数按墙上时间原样传进函数体
            executeQuery("SELECT fz2(CAST('2020-01-02' AS DATE)), CAST('2020-01-02' AS DATE)");
            assertEquals(getStringValue(2), getStringValue(1));
        } finally {
            TimeZone.setDefault(defaultZone);
        }
    }

    // SETOF/RETURNS TABLE的时间列不能物化成java.sql.Date/Time/Timestamp再转回来，
    // 否则运行期改过默认时区后结果会整体偏移。
    // DateTimeUtils缓存的是第一次用到时的Calendar，所以换两个不同的时区各验一遍:
    // 至少有一个和缓存里的时区不同，能把"隐式转换和显式CAST不一致"暴露出来
    @Test
    public void testSetofAndTableDateTimeAfterTimeZoneChange() throws Exception {
        executeUpdate("CREATE FUNCTION sz_ts(i INT) RETURNS SETOF TIMESTAMP AS "
                + "$$ SELECT TIMESTAMP '2020-01-02 00:00:00' $$");
        executeUpdate(
                "CREATE FUNCTION sz_td(i INT) RETURNS SETOF DATE AS $$ SELECT DATE '2020-01-02' $$");
        executeUpdate(
                "CREATE FUNCTION sz_tt(i INT) RETURNS SETOF TIME AS $$ SELECT TIME '03:04:05' $$");
        executeUpdate("CREATE FUNCTION sz_tn(i INT) RETURNS SETOF TIMESTAMP AS "
                + "$$ SELECT TIMESTAMP '2020-01-02 03:04:05.123456789' $$");
        executeUpdate("CREATE FUNCTION sz_tbl(i INT) RETURNS TABLE(x TIMESTAMP, d DATE, t TIME) AS "
                + "$$ SELECT TIMESTAMP '2020-01-02 00:00:00', DATE '2020-01-02', TIME '03:04:05' $$");
        executeUpdate("CREATE FUNCTION sz_pts(i INT) RETURNS SETOF TIMESTAMP AS $$ "
                + "BEGIN RETURN NEXT TIMESTAMP '2020-01-02 00:00:00'; END $$ LANGUAGE plpgsql");

        TimeZone defaultZone = TimeZone.getDefault();
        try {
            checkSetofDateTime("GMT+8");
            checkSetofDateTime("America/Los_Angeles");
        } finally {
            TimeZone.setDefault(defaultZone);
        }
    }

    private void checkSetofDateTime(String zone) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        // 期望值取引擎自己的显式CAST: 字符串、时间戳毫秒数、纳秒数都要逐字节一致
        executeQuery("SELECT CAST(TIMESTAMP '2020-01-02 00:00:00' AS TIMESTAMP), "
                + "CAST(TIMESTAMP '2020-01-02 03:04:05.123456789' AS TIMESTAMP), "
                + "CAST(DATE '2020-01-02' AS DATE), CAST(TIME '03:04:05' AS TIME)");
        String expectedTs = getStringValue(1);
        long expectedTsMillis = rs.getTimestamp(1).getTime();
        String expectedNanosTs = getStringValue(2);
        long expectedNanosMillis = rs.getTimestamp(2).getTime();
        int expectedNanos = rs.getTimestamp(2).getNanos();
        String expectedDate = getStringValue(3);
        long expectedDateMillis = rs.getDate(3).getTime();
        String expectedTime = getStringValue(4);
        long expectedTimeMillis = rs.getTime(4).getTime();

        // setof TIMESTAMP
        executeQuery("SELECT * FROM sz_ts(1)");
        assertEquals(expectedTs, getStringValue(1));
        assertEquals(expectedTsMillis, rs.getTimestamp(1).getTime());
        // 带纳秒的TIMESTAMP
        executeQuery("SELECT * FROM sz_tn(1)");
        assertEquals(expectedNanosTs, getStringValue(1));
        assertEquals(expectedNanosMillis, rs.getTimestamp(1).getTime());
        assertEquals(expectedNanos, rs.getTimestamp(1).getNanos());
        // WITH ORDINALITY
        executeQuery("SELECT * FROM sz_ts(1) WITH ORDINALITY");
        assertEquals(expectedTs, getStringValue(1));
        assertEquals(1, getIntValue(2));
        // setof DATE / TIME
        executeQuery("SELECT * FROM sz_td(1)");
        assertEquals(expectedDate, getStringValue(1));
        assertEquals(expectedDateMillis, rs.getDate(1).getTime());
        executeQuery("SELECT * FROM sz_tt(1)");
        assertEquals(expectedTime, getStringValue(1));
        assertEquals(expectedTimeMillis, rs.getTime(1).getTime());
        // plpgsql的setof
        executeQuery("SELECT * FROM sz_pts(1)");
        assertEquals(expectedTs, getStringValue(1));
        // RETURNS TABLE的三个时间列
        executeQuery("SELECT * FROM sz_tbl(1)");
        assertEquals(expectedTs, getStringValue(1));
        assertEquals(expectedDate, getStringValue(2));
        assertEquals(expectedTime, getStringValue(3));
        assertEquals(expectedTsMillis, rs.getTimestamp(1).getTime());
        assertEquals(expectedDateMillis, rs.getDate(2).getTime());
        assertEquals(expectedTimeMillis, rs.getTime(3).getTime());
    }

    // 隐式转换矩阵: 只允许PostgreSQL里implicit的类型对，其余必须显式CAST
    @Test
    public void testImplicitConversionMatrix() throws Exception {
        // 整数加宽可以隐式转换
        executeUpdate("CREATE FUNCTION fm1(a BIGINT) RETURNS BIGINT AS $$ SELECT a $$");
        executeQuery("SELECT fm1(1)");
        assertEquals(1L, getLongValue(1));
        executeUpdate("CREATE FUNCTION fm2(a INT) RETURNS INT AS $$ SELECT a $$");
        executeQuery("SELECT fm2(CAST(1 AS SMALLINT))");
        assertEquals(1, getIntValue(1));
        // 变窄不行
        executeQueryThanAssertErrorCode("SELECT fm2(CAST(1 AS BIGINT))",
                ErrorCode.FUNCTION_NOT_FOUND_1);

        // 整数转DECIMAL/DOUBLE可以隐式转换
        executeUpdate("CREATE FUNCTION fm3(a DECIMAL(10,2)) RETURNS DECIMAL(10,2) AS $$ SELECT a $$");
        executeQuery("SELECT fm3(1)");
        assertEquals(1, getIntValue(1));
        // DOUBLE/REAL转DECIMAL不行
        executeQueryThanAssertErrorCode("SELECT fm3(CAST(1 AS DOUBLE))",
                ErrorCode.FUNCTION_NOT_FOUND_1);

        // DATE可以隐式转TIMESTAMP，反过来不行
        executeUpdate("CREATE FUNCTION fm4(a TIMESTAMP) RETURNS TIMESTAMP AS $$ SELECT a $$");
        // 时刻字符串依赖JVM默认时区(同一时刻在不同时区渲染出来的字符串不同)，
        // 这里改成和引擎自己做的同一个转换比较，任何时区下都成立
        executeQuery("SELECT fm4(CAST('2020-01-02' AS DATE)), "
                + "CAST(CAST('2020-01-02' AS DATE) AS TIMESTAMP)");
        assertEquals(getStringValue(2), getStringValue(1));
        // 时间戳数值也必须逐字节一致: 隐式转换不能过Timestamp/epoch那套时区换算
        assertEquals(rs.getTimestamp(2).getTime(), rs.getTimestamp(1).getTime());
        assertEquals(rs.getTimestamp(2).getNanos(), rs.getTimestamp(1).getNanos());
        assertEquals(Types.TIMESTAMP, rs.getMetaData().getColumnType(1));
        executeUpdate("CREATE FUNCTION fm5(a DATE) RETURNS DATE AS $$ SELECT a $$");
        executeQueryThanAssertErrorCode("SELECT fm5(CAST('2020-01-02 03:04:05' AS TIMESTAMP))",
                ErrorCode.FUNCTION_NOT_FOUND_1);

        // 字符串类型之间可以隐式转换
        executeUpdate("CREATE FUNCTION fm6(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a $$");
        executeQuery("SELECT fm6(CAST(1 AS CHAR(3)))");
        assertEquals("1", getStringValue(1));
        // 字符串和数字之间不行，必须显式CAST
        executeUpdate("CREATE FUNCTION fm7(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a $$");
        executeQueryThanAssertErrorCode("SELECT fm7(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("SELECT fm7(CAST(1 AS VARCHAR))");
        assertEquals("1", getStringValue(1));
        executeUpdate("CREATE FUNCTION fm8(a INT) RETURNS INT AS $$ SELECT a $$");
        executeUpdate("CREATE TABLE IF NOT EXISTS fo_t(s VARCHAR(10))");
        executeUpdate("DELETE FROM fo_t");
        executeUpdate("INSERT INTO fo_t VALUES('10')");
        executeQueryThanAssertErrorCode("SELECT fm8(s) FROM fo_t", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("SELECT fm8(CAST(s AS INT)) FROM fo_t");
        assertEquals(10, getIntValue(1));

        // BOOLEAN和整数之间不行
        executeUpdate("CREATE FUNCTION fm9(a INT) RETURNS INT AS $$ SELECT a $$");
        executeQueryThanAssertErrorCode("SELECT fm9(TRUE)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // SCRIPT导出的脚本要包含每个重载，回放到新库后全部可调用
    @Test
    public void testScriptRoundTrip() throws Exception {
        executeUpdate("CREATE FUNCTION fs(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeUpdate("CREATE FUNCTION fs(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$");

        StringBuilder script = new StringBuilder();
        int count = 0;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line.toUpperCase().contains("FUNCTION FS(")) {
                    script.append(line).append('\n');
                    count++;
                }
            }
        }
        assertEquals("script must contain both overloads: " + script, 2, count);

        // 回放到一个新库
        String dir = newTestDir("overload_replay");
        String url = "jdbc:lealone:embed:" + dir + "/" + dir.substring(dir.lastIndexOf('/') + 1)
                + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement()) {
                for (String line : script.toString().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        s.executeUpdate(line);
                    }
                }
            }
            try (Statement s = conn2.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT fs(1), fs('x')")) {
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                    assertEquals("x!", rs.getString(2));
                }
            }
        }
    }

    // 关库重启之后所有重载都还在(meta表回放)
    @Test
    public void testOverloadsSurviveRestart() throws Exception {
        String dir = newTestDir("overload_restart");
        String url = "jdbc:lealone:embed:" + dir + "/" + dir.substring(dir.lastIndexOf('/') + 1)
                + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement()) {
                s.executeUpdate("CREATE FUNCTION fr(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
                s.executeUpdate(
                        "CREATE FUNCTION fr(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$");
                s.executeUpdate("CREATE FUNCTION fr(a INT, b INT) RETURNS INT AS $$ SELECT a * b $$");
            }
        }
        // 重新打开同一个库，三个重载都要在
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement();
                    ResultSet rs = s.executeQuery("SELECT fr(1), fr('x'), fr(2, 3)")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
                assertEquals("x!", rs.getString(2));
                assertEquals(6, rs.getInt(3));
            }
            // 重启后仍然能按签名删除
            try (Statement s = conn2.createStatement()) {
                s.executeUpdate("DROP FUNCTION fr(INT, INT)");
            }
            try (Statement s = conn2.createStatement();
                    ResultSet rs = s.executeQuery("SELECT fr(1), fr('x')")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
                assertEquals("x!", rs.getString(2));
            }
        }
    }

    // CREATE ALIAS(Java函数)的解析和宽松转换不能受重载影响
    @Test
    public void testCreateAliasUnaffected() throws Exception {
        executeUpdate("CREATE ALIAS my_sqrt FOR 'java.lang.Math.sqrt'");
        executeQuery("SELECT my_sqrt(4), my_sqrt('4'), my_sqrt(TRUE)");
        assertEquals(2.0, getDoubleValue(1), 0.0);
        assertEquals(2.0, getDoubleValue(2), 0.0);
        assertEquals(1.0, getDoubleValue(3), 0.0);
        executeQueryThanAssertErrorCode("SELECT my_sqrt('abc')", ErrorCode.DATA_CONVERSION_ERROR_1);
        executeUpdate("DROP ALIAS my_sqrt");
        executeQueryThanAssertErrorCode("SELECT my_sqrt(4)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // 每次用独立目录，避免上次运行的库文件残留
    private static String newTestDir(String name) {
        String dir = new File(TestBase.TEST_DIR, name + "_" + System.currentTimeMillis())
                .getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        return dir;
    }
}
