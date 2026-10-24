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

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.sql.SqlTestBase;

/**
 * 只写类型不写名字的参数(PostgreSQL的 CREATE FUNCTION f(int))，
 * 以及函数体里用 $1/$2 按位置引用入参。
 */
public class UnnamedParameterTest extends SqlTestBase {

    public UnnamedParameterTest() {
        setEmbedded(true);
    }

    @Test
    public void testUnnamedFunctionParameters() throws Exception {
        executeUpdate(
                "CREATE OR REPLACE FUNCTION up_sum(INT, INT) RETURNS INT AS 'SELECT $1 + $2'");
        assertEquals(3, queryInt("SELECT up_sum(1, 2)"));

        // 具名参数和无名参数可以混用，$n 按入参顺序编号
        executeUpdate(
                "CREATE OR REPLACE FUNCTION up_mix(a INT, TEXT) RETURNS TEXT AS 'SELECT a || $2'");
        assertEquals("1x", queryString("SELECT up_mix(1, 'x')"));

        // $1 后面可以跟数组下标和CAST，和PostgreSQL一样
        executeUpdate(
                "CREATE OR REPLACE FUNCTION up_array(TEXT[]) RETURNS INT AS 'SELECT $1[1]::INT'");
        assertEquals(1, queryInt("SELECT up_array(('1','2','3'))"));

        // OUT参数不参与$n编号，$1是第一个入参
        executeUpdate(
                "CREATE OR REPLACE FUNCTION up_out(OUT x INT, INT) RETURNS INT AS 'SELECT $1'");
        assertEquals(7, queryInt("SELECT * FROM up_out(7)"));

        // 无名参数和具名参数一起出现在SETOF函数里
        executeUpdate("CREATE OR REPLACE FUNCTION up_setof(INT) RETURNS SETOF INT "
                + "AS 'SELECT $1 UNION ALL SELECT $1 + 1'");
        executeQuery("SELECT * FROM up_setof(5)");
        assertEquals(5, getIntValue(1));
        rs.next();
        assertEquals(6, getIntValue(1));
    }

    @Test
    public void testPositionalParameterErrors() throws Exception {
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION up_bad(INT) RETURNS INT AS 'SELECT $2'",
                ErrorCode.SYNTAX_ERROR_1);
        // 报错信息要点明是哪个位置参数，不能是 Table ")" not found 这种误导性错误
        try {
            executeUpdate("CREATE FUNCTION up_bad(INT) RETURNS INT AS 'SELECT $2'");
            fail();
        } catch (RuntimeException e) {
            Throwable cause = getRootCause(e);
            assertTrue(cause.getMessage(), cause.getMessage().contains("there is no parameter $2"));
        }
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION up_bad(INT) RETURNS INT AS 'SELECT $0'",
                ErrorCode.SYNTAX_ERROR_1);
        // 函数体里的 ? 仍然不允许(会和参数替换产生的?混淆)
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION up_bad(INT) RETURNS INT AS 'SELECT ?'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    @Test
    public void testUnnamedProcedureParameters() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS up_proc");
        executeUpdate("CREATE PROCEDURE up_proc(INT) AS 'SELECT ?'");
        executeQuery("CALL up_proc(5)");
        assertEquals(5, getIntValue(1));

        // 过程体里也能用$1
        executeUpdate("DROP PROCEDURE IF EXISTS up_proc2");
        executeUpdate("CREATE PROCEDURE up_proc2(INT) AS 'SELECT $1 + 1'");
        executeQuery("CALL up_proc2(5)");
        assertEquals(6, getIntValue(1));

        // 带OUT参数的过程，无名IN参数在过程体里用?
        executeUpdate("DROP PROCEDURE IF EXISTS up_proc3");
        executeUpdate("CREATE PROCEDURE up_proc3(OUT r INT, INT) AS 'SELECT ? * 2'");
        executeQuery("CALL up_proc3(5)");
        assertEquals(10, getIntValue(1));
    }

    private int queryInt(String sql) throws Exception {
        executeQuery(sql);
        return getIntValue(1, true);
    }

    private String queryString(String sql) throws Exception {
        executeQuery(sql);
        return getStringValue(1, true);
    }

    // 无名参数和$n的DDL要能原样回放(SCRIPT导出后RUNSCRIPT到另一个库)
    @Test
    public void testScriptReplay() throws Exception {
        // 用独立的库，避免把别的测试类建的对象一起导出
        String dir = new File(TEST_DIR, "unnamed_param_replay_" + System.nanoTime()).getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        File dump = new File(dir, "dump.sql");
        String dumpPath = dump.getAbsolutePath().replace('\\', '/');
        try (Connection c1 = DriverManager.getConnection(
                "jdbc:lealone:embed:" + dir + "/unnamed_param_replay_db1;user=root;password=");
                Statement s1 = c1.createStatement()) {
            s1.executeUpdate("CREATE FUNCTION replay_fn(INT, TEXT) RETURNS TEXT "
                    + "AS 'SELECT $2 || $1'");
            s1.executeUpdate("CREATE PROCEDURE replay_proc(OUT r INT, INT) AS 'SELECT ? + 100'");
            s1.execute("SCRIPT TO '" + dumpPath + "'");
        }
        String url = "jdbc:lealone:embed:" + dir + "/unnamed_param_replay_db2;user=root;password=";
        try (Connection c2 = DriverManager.getConnection(url);
                Statement s2 = c2.createStatement()) {
            s2.executeUpdate("RUNSCRIPT FROM '" + dumpPath + "'");
            try (ResultSet r = s2.executeQuery("SELECT replay_fn(7, 'x')")) {
                assertTrue(r.next());
                assertEquals("x7", r.getString(1));
            }
            try (ResultSet r = s2.executeQuery("CALL replay_proc(5)")) {
                assertTrue(r.next());
                assertEquals(105, r.getInt(1));
            }
        } finally {
            FileUtils.deleteRecursive(dir, false);
        }
    }
}
