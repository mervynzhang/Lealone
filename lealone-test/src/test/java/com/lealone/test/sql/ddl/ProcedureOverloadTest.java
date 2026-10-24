/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * 同名存储过程按参数类型重载。消解规则和函数重载完全一样：
 * 完全匹配优先，其次是可以隐式转换的类型，都没有匹配时报函数不存在(90022)；
 * 多个重载的匹配程度相同且无法用“未知类型优先按字符串类型解析”区分时报歧义(90152)。
 */
public class ProcedureOverloadTest extends SqlTestBase {

    public ProcedureOverloadTest() {
        setEmbedded(true);
    }

    // 同参数个数、不同参数类型
    @Test
    public void testOverloadByParameterType() throws Exception {
        executeUpdate("CREATE TABLE IF NOT EXISTS po_t(id INT, v VARCHAR(20), tag VARCHAR(20))");
        executeUpdate("DELETE FROM po_t");
        executeUpdate("CREATE PROCEDURE po_a(a INT) AS 'INSERT INTO po_t VALUES(?, null, ''int'')'");
        executeUpdate(
                "CREATE PROCEDURE po_a(a VARCHAR) AS 'INSERT INTO po_t VALUES(null, ?, ''varchar'')'");
        executeUpdate("CALL po_a(1)");
        executeUpdate("CALL po_a('x')");
        executeQuery("SELECT id, v, tag FROM po_t ORDER BY tag");
        assertEquals(1, getIntValue(1, false));
        assertEquals("int", getStringValue(3, false));
        assertTrue(next());
        assertEquals("x", getStringValue(2, false));
        assertEquals("varchar", getStringValue(3, true));

        // 只删掉int版本，varchar版本还在
        executeUpdate("DROP PROCEDURE po_a(INT)");
        // int到varchar不是隐式转换，PostgreSQL里 po_a(1) 找不到过程
        executeUpdateThanAssertErrorCode("CALL po_a(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("CALL po_a('x')");
    }

    // 参数个数不同的重载
    @Test
    public void testOverloadByParameterCount() throws Exception {
        executeUpdate("CREATE PROCEDURE po_b(a INT) AS 'SELECT ? * 2'");
        executeUpdate("CREATE PROCEDURE po_b(a INT, b INT) AS 'SELECT ? * ?'");
        executeQuery("CALL po_b(3)");
        assertEquals(6, getIntValue(1, true));
        executeQuery("CALL po_b(3, 4)");
        assertEquals(12, getIntValue(1, true));

        executeUpdate("DROP PROCEDURE po_b(INT, INT)");
        executeUpdateThanAssertErrorCode("CALL po_b(3, 4)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("CALL po_b(3)");
        assertEquals(6, getIntValue(1, true));
    }

    // DROP PROCEDURE name(types) 只删指定的重载；OUT参数也参与DROP匹配
    @Test
    public void testDropBySignature() throws Exception {
        executeUpdate("CREATE PROCEDURE po_c(a INT) AS 'SELECT 1'");
        executeUpdate("CREATE PROCEDURE po_c(a BIGINT) AS 'SELECT 2'");
        executeUpdate("CREATE PROCEDURE po_c(a VARCHAR) AS 'SELECT 3'");
        executeQuery("CALL po_c(1)");
        assertEquals(1, getIntValue(1, true));
        executeQuery("CALL po_c(CAST(1 AS BIGINT))");
        assertEquals(2, getIntValue(1, true));
        executeQuery("CALL po_c('x')");
        assertEquals(3, getIntValue(1, true));

        executeUpdate("DROP PROCEDURE po_c(BIGINT)");
        executeQueryThanAssertErrorCode("CALL po_c(CAST(1 AS BIGINT))",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("CALL po_c(1)");
        assertEquals(1, getIntValue(1, true));
        executeQuery("CALL po_c('x')");
        assertEquals(3, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE po_c(INT)");
        executeUpdate("DROP PROCEDURE po_c(VARCHAR)");
    }

    // DROP PROCEDURE不写参数类型: 只有一个重载时能删，多个重载时报歧义
    @Test
    public void testDropWithoutSignature() throws Exception {
        executeUpdate("CREATE PROCEDURE po_d(a INT) AS 'SELECT 1'");
        executeUpdate("DROP PROCEDURE po_d");
        executeUpdateThanAssertErrorCode("CALL po_d(1)", ErrorCode.FUNCTION_NOT_FOUND_1);

        executeUpdate("CREATE PROCEDURE po_d(a INT) AS 'SELECT 1'");
        executeUpdate("CREATE PROCEDURE po_d(a VARCHAR) AS 'SELECT 2'");
        executeUpdateThanAssertErrorCode("DROP PROCEDURE po_d",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        // IF EXISTS 也一样，名字不唯一时不能猜
        executeUpdateThanAssertErrorCode("DROP PROCEDURE IF EXISTS po_d",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        // 删掉一个之后名字就唯一了
        executeUpdate("DROP PROCEDURE po_d(VARCHAR)");
        executeUpdate("DROP PROCEDURE po_d");
        executeUpdateThanAssertErrorCode("CALL po_d(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // DROP时用IN/OUT模式区分参数列表完全相同的两个重载
    @Test
    public void testDropWithParameterModes() throws Exception {
        executeUpdate("CREATE PROCEDURE po_e(OUT a INT, IN b INT, IN c INT) AS 'SELECT ? - ?'");
        executeQuery("CALL po_e(null, 7, 4)");
        assertEquals(3, getIntValue(1, true));
        executeUpdate("CREATE PROCEDURE po_e(IN a INT, IN b INT, IN c INT) AS 'SELECT ? + ? - ?'");

        // 不写模式时两个重载都能匹配上
        executeUpdateThanAssertErrorCode("DROP PROCEDURE po_e(INT, INT, INT)",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdate("DROP PROCEDURE po_e(OUT INT, INT, INT)");
        // 只剩IN版本，可以按类型删除了
        executeUpdate("DROP PROCEDURE po_e(INT, INT, INT)");
        executeUpdateThanAssertErrorCode("DROP PROCEDURE po_e", ErrorCode.PROCEDURE_NOT_FOUND_1);
    }

    // 同签名重复创建报已存在，IF NOT EXISTS只跳过同签名的，不同签名仍然创建
    @Test
    public void testCreateIfNotExists() throws Exception {
        executeUpdate("CREATE PROCEDURE po_f(a INT) AS 'SELECT 1'");
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE po_f(a INT) AS 'SELECT 2'",
                ErrorCode.PROCEDURE_ALREADY_EXISTS_1);
        executeUpdate("CREATE PROCEDURE IF NOT EXISTS po_f(a INT) AS 'SELECT 2'");
        executeUpdate("CREATE PROCEDURE IF NOT EXISTS po_f(a BIGINT) AS 'SELECT 3'");
        executeQuery("CALL po_f(1)");
        assertEquals(1, getIntValue(1, true));
        executeQuery("CALL po_f(CAST(1 AS BIGINT))");
        assertEquals(3, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE po_f(INT)");
        executeUpdate("DROP PROCEDURE po_f(BIGINT)");
    }

    // 没有类型的字面量和NULL的消解规则
    @Test
    public void testUntypedLiteralAndNullArgument() throws Exception {
        executeUpdate("CREATE PROCEDURE po_g(a INT) AS 'SELECT ''int'''");
        executeUpdate("CREATE PROCEDURE po_g(a VARCHAR) AS 'SELECT ''varchar'''");
        executeQuery("CALL po_g(NULL)");
        assertEquals("varchar", getStringValue(1, true));
        executeQuery("CALL po_g('x')");
        assertEquals("varchar", getStringValue(1, true));
        // 字符串表达式在重载消解时按字符串类型处理
        executeQuery("CALL po_g('x' || 'y')");
        assertEquals("varchar", getStringValue(1, true));
        // 写了类型就按类型消解
        executeQuery("CALL po_g(CAST(NULL AS INT))");
        assertEquals("int", getStringValue(1, true));
        executeUpdate("DROP PROCEDURE po_g(INT)");
        executeUpdate("DROP PROCEDURE po_g(VARCHAR)");

        // 没有字符串类型的重载可挑时，NULL实参是歧义的
        executeUpdate("CREATE PROCEDURE po_h(a INT) AS 'SELECT 1'");
        executeUpdate("CREATE PROCEDURE po_h(a DATE) AS 'SELECT 2'");
        executeQueryThanAssertErrorCode("CALL po_h(NULL)",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeQuery("CALL po_h(1)");
        assertEquals(1, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE po_h(INT)");
        executeUpdate("DROP PROCEDURE po_h(DATE)");
    }

    // 隐式转换矩阵和函数重载共用: 整数加宽可以，变窄和跨类型都不行
    @Test
    public void testImplicitConversionMatrix() throws Exception {
        executeUpdate("CREATE PROCEDURE po_i(a BIGINT) AS 'SELECT 1'");
        executeQuery("CALL po_i(1)");
        assertEquals(1, getIntValue(1, true));

        executeUpdate("CREATE PROCEDURE po_j(a INT) AS 'SELECT 1'");
        executeQuery("CALL po_j(CAST(1 AS SMALLINT))");
        assertEquals(1, getIntValue(1, true));
        executeUpdateThanAssertErrorCode("CALL po_j(CAST(1 AS BIGINT))",
                ErrorCode.FUNCTION_NOT_FOUND_1);

        executeUpdate("CREATE PROCEDURE po_k(a VARCHAR) AS 'SELECT 1'");
        executeUpdateThanAssertErrorCode("CALL po_k(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeQuery("CALL po_k(CAST(1 AS VARCHAR))");
        executeUpdate("DROP PROCEDURE po_i");
        executeUpdate("DROP PROCEDURE po_j");
        executeUpdate("DROP PROCEDURE po_k");
    }

    // OUT参数的两种实参个数都能参与消解
    @Test
    public void testOutParameterCall() throws Exception {
        executeUpdate("CREATE PROCEDURE po_l(OUT a INT, IN b INT) AS 'SELECT ? * 2'");
        // 只给入参
        executeQuery("CALL po_l(5)");
        assertEquals(10, getIntValue(1, true));
        // PostgreSQL写法: 给OUT参数写NULL占位
        executeQuery("CALL po_l(NULL, 6)");
        assertEquals(12, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE po_l");
    }

    // CALL仍然保留原来的函数调用语法: 没有同名过程时按函数表达式处理
    @Test
    public void testCallFunctionFallback() throws Exception {
        executeQuery("CALL ABS(-3)");
        assertEquals(3, getIntValue(1, true));
        executeQuery("SELECT ABS(-4)");
        assertEquals(4, getIntValue(1, true));
    }

    // SCRIPT导出的脚本要包含每个过程重载，回放到新库后全部可调用
    @Test
    public void testScriptRoundTrip() throws Exception {
        executeUpdate("CREATE PROCEDURE po_m(a INT) AS 'SELECT 1'");
        executeUpdate("CREATE PROCEDURE po_m(a VARCHAR) AS 'SELECT 2'");
        executeUpdate("CREATE PROCEDURE po_n(OUT a INT, IN b INT) AS 'SELECT ? * 3'");

        StringBuilder script = new StringBuilder();
        int creates = 0;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT NODATA")) {
            while (rs.next()) {
                String line = rs.getString(1);
                String upper = line.toUpperCase();
                if (upper.contains("PROCEDURE PUBLIC.PO_M(")
                        || upper.contains("PROCEDURE PUBLIC.PO_N(")) {
                    script.append(line).append('\n');
                    if (upper.startsWith("CREATE"))
                        creates++;
                }
            }
        }
        assertEquals("script must contain all procedures: " + script, 3, creates);

        // 回放到一个新库
        String dir = newTestDir("procedure_overload_replay");
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
                try (ResultSet rs = s.executeQuery("CALL po_m(1)")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("CALL po_m('x')")) {
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("CALL po_n(NULL, 4)")) {
                    rs.next();
                    assertEquals(12, rs.getInt(1));
                }
            }
        }
    }

    // 关库重启之后所有过程重载都还在(meta表回放)
    @Test
    public void testOverloadsSurviveRestart() throws Exception {
        String dir = newTestDir("procedure_overload_restart");
        String url = "jdbc:lealone:embed:" + dir + "/" + dir.substring(dir.lastIndexOf('/') + 1)
                + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement()) {
                s.executeUpdate("CREATE PROCEDURE po_r(a INT) AS 'SELECT 1'");
                s.executeUpdate("CREATE PROCEDURE po_r(a VARCHAR) AS 'SELECT 2'");
                s.executeUpdate("CREATE PROCEDURE po_r(a INT, b INT) AS 'SELECT 3'");
            }
        }
        // 重新打开同一个库，三个重载都要在
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement()) {
                try (ResultSet rs = s.executeQuery("CALL po_r(1)")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("CALL po_r('x')")) {
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                }
                try (ResultSet rs = s.executeQuery("CALL po_r(2, 3)")) {
                    rs.next();
                    assertEquals(3, rs.getInt(1));
                }
                // 重启后仍然能按签名删除
                s.executeUpdate("DROP PROCEDURE po_r(INT, INT)");
                try (ResultSet rs = s.executeQuery("CALL po_r(1)")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
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
