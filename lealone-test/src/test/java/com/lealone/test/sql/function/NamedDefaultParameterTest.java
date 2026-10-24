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
 * 参数的 DEFAULT 值(CREATE FUNCTION f(a int, b int default 10))，
 * 以及调用时的命名实参(f(b => 2, a => 1))。
 */
public class NamedDefaultParameterTest extends SqlTestBase {

    public NamedDefaultParameterTest() {
        setEmbedded(true);
    }

    @Test
    public void testDefaultParameters() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION nd_add(a INT, b INT DEFAULT 10) RETURNS INT "
                + "AS 'SELECT a + b'");
        assertEquals(11, queryInt("SELECT nd_add(1)"));
        assertEquals(3, queryInt("SELECT nd_add(1, 2)"));

        // 各种常量默认值
        executeUpdate("CREATE OR REPLACE FUNCTION nd_text(a INT, b TEXT DEFAULT 'x') RETURNS TEXT "
                + "AS 'SELECT a || b'");
        assertEquals("1x", queryString("SELECT nd_text(1)"));
        assertEquals("1y", queryString("SELECT nd_text(1, 'y')"));

        executeUpdate("CREATE OR REPLACE FUNCTION nd_dec(a DECIMAL, b DECIMAL DEFAULT 2.5) "
                + "RETURNS DECIMAL AS 'SELECT a + b'");
        executeUpdate("CREATE OR REPLACE FUNCTION nd_bool(a INT, b BOOLEAN DEFAULT TRUE) "
                + "RETURNS BOOLEAN AS 'SELECT b'");
        executeQuery("SELECT nd_bool(1)");
        assertTrue(getBooleanValue(1));

        // 所有入参都有默认值
        executeUpdate("CREATE OR REPLACE FUNCTION nd_all(a INT DEFAULT 1, b INT DEFAULT 2) "
                + "RETURNS INT AS 'SELECT a * 10 + b'");
        assertEquals(12, queryInt("SELECT nd_all()"));
        assertEquals(32, queryInt("SELECT nd_all(3)"));

        // 重载: 实参个数完全对上的优先, 不需要用默认值补齐
        executeUpdate("CREATE OR REPLACE FUNCTION nd_ov(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdate("CREATE OR REPLACE FUNCTION nd_ov(a INT, b INT DEFAULT 100) RETURNS INT "
                + "AS 'SELECT a + b'");
        assertEquals(1, queryInt("SELECT nd_ov(1)"));
        assertEquals(3, queryInt("SELECT nd_ov(1, 2)"));

        // 实参太少: 没有默认值的入参必须提供
        executeUpdateThanAssertErrorCode("SELECT nd_add()", ErrorCode.FUNCTION_NOT_FOUND_1);
        // 有默认值的入参之后不能再出现没有默认值的入参
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION nd_bad(a INT DEFAULT 1, b INT) RETURNS INT AS 'SELECT a + b'",
                ErrorCode.SYNTAX_ERROR_1);
        // 默认值必须是常量
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION nd_bad(a INT DEFAULT RANDOM()) RETURNS INT AS 'SELECT a'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // 默认值要能转换成参数类型
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION nd_bad(a INT DEFAULT 'abc') RETURNS INT AS 'SELECT a'",
                ErrorCode.DATA_CONVERSION_ERROR_1);

        // 默认值要写进DDL里, SCRIPT能看到
        assertTrue(getFunctionDDL("ND_ADD").contains("DEFAULT 10"));
    }

    @Test
    public void testDefaultParametersInProcedures() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS nd_proc_out");
        executeUpdate("CREATE TABLE nd_proc_out(a INT, b TEXT)");
        executeUpdate("DROP PROCEDURE IF EXISTS nd_p1");
        executeUpdate("CREATE PROCEDURE nd_p1(a INT, b TEXT DEFAULT 'x') "
                + "AS 'insert into nd_proc_out values(?, ?)'");
        assertEquals(1, executeUpdate("CALL nd_p1(1)"));
        assertEquals(1, executeUpdate("CALL nd_p1(2, 'y')"));
        executeQuery("SELECT * FROM nd_proc_out ORDER BY a");
        assertEquals(1, getIntValue(1));
        rs.next();
        assertEquals(2, getIntValue(1));

        // 有OUT参数的过程也能有默认值
        executeUpdate("DROP PROCEDURE IF EXISTS nd_p2");
        executeUpdate("CREATE PROCEDURE nd_p2(OUT r INT, a INT, b INT DEFAULT 5) "
                + "AS 'SELECT ? + ?'");
        assertEquals(6, queryInt("CALL nd_p2(1)"));

        // 缺的实参没有默认值时报错
        executeUpdateThanAssertErrorCode("CALL nd_p2()", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("DROP TABLE nd_proc_out");
        executeUpdate("DROP PROCEDURE nd_p1");
        executeUpdate("DROP PROCEDURE nd_p2");
    }

    @Test
    public void testNamedArguments() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION nd_mul(a INT, b INT) RETURNS INT "
                + "AS 'SELECT a * 10 + b'");
        assertEquals(12, queryInt("SELECT nd_mul(b => 2, a => 1)"));
        assertEquals(12, queryInt("SELECT nd_mul(1, b => 2)"));
        assertEquals(12, queryInt("SELECT nd_mul(a := 1, b := 2)"));

        // 命名实参和默认值一起用
        executeUpdate("CREATE OR REPLACE FUNCTION nd_mul2(a INT DEFAULT 3, b INT DEFAULT 4) "
                + "RETURNS INT AS 'SELECT a * 10 + b'");
        assertEquals(32, queryInt("SELECT nd_mul2(b => 2)"));

        // 位置实参不能写在命名实参后面
        executeUpdateThanAssertErrorCode("SELECT nd_mul(a => 1, 2)",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        // 参数名不存在
        executeUpdateThanAssertErrorCode("SELECT nd_mul(c => 2)", ErrorCode.FUNCTION_NOT_FOUND_1);
        // 同一个参数不能给两次
        executeUpdateThanAssertErrorCode("SELECT nd_mul(a => 1, a => 2)",
                ErrorCode.FUNCTION_NOT_FOUND_1);

        // 命名实参的写法要能出现在视图定义里(SCRIPT回放时位置不会变)
        executeUpdate("DROP VIEW IF EXISTS nd_view");
        executeUpdate("CREATE VIEW nd_view AS SELECT nd_mul(b => 2, a => 1) AS x");
        assertEquals(12, queryInt("SELECT x FROM nd_view"));
        String viewSql = getViewDDL("ND_VIEW");
        assertTrue(viewSql, viewSql.contains("=>"));
        executeUpdate("DROP VIEW nd_view");
    }

    @Test
    public void testNamedArgumentsInProcedures() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS nd_named_out");
        executeUpdate("CREATE TABLE nd_named_out(a INT, b TEXT)");
        executeUpdate("DROP PROCEDURE IF EXISTS nd_p3");
        executeUpdate("CREATE PROCEDURE nd_p3(a INT, b TEXT DEFAULT 'x') "
                + "AS 'insert into nd_named_out values(?, ?)'");
        assertEquals(1, executeUpdate("CALL nd_p3(10, b => 'Hello')"));
        assertEquals(1, executeUpdate("CALL nd_p3(b => 'World', a => 20)"));
        executeQuery("SELECT * FROM nd_named_out ORDER BY a");
        assertEquals(10, getIntValue(1));
        assertEquals("Hello", getStringValue(2));
        rs.next();
        assertEquals(20, getIntValue(1));
        assertEquals("World", getStringValue(2));

        // 有OUT参数的过程也能用命名实参
        executeUpdate("DROP PROCEDURE IF EXISTS nd_p4");
        executeUpdate("CREATE PROCEDURE nd_p4(OUT r INT, a INT, b INT DEFAULT 5) "
                + "AS 'SELECT ? + ?'");
        assertEquals(5, queryInt("CALL nd_p4(b => 3, a => 2)"));
        // 过程不认识的名字
        executeUpdateThanAssertErrorCode("CALL nd_p3(c => 'x')",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("DROP TABLE nd_named_out");
        executeUpdate("DROP PROCEDURE nd_p3");
        executeUpdate("DROP PROCEDURE nd_p4");
    }

    // DEFAULT参数和命名实参的DDL要能原样回放(SCRIPT导出后RUNSCRIPT到另一个库)
    @Test
    public void testScriptReplay() throws Exception {
        String dir = new File(TEST_DIR, "named_default_replay_" + System.nanoTime()).getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        File dump = new File(dir, "dump.sql");
        String dumpPath = dump.getAbsolutePath().replace('\\', '/');
        try (Connection c1 = DriverManager.getConnection(
                "jdbc:lealone:embed:" + dir + "/named_default_replay_db1;user=root;password=");
                Statement s1 = c1.createStatement()) {
            s1.executeUpdate("CREATE TABLE t(id INT, v TEXT)");
            s1.executeUpdate("CREATE FUNCTION replay_add(a INT, b INT DEFAULT 10) RETURNS INT "
                    + "AS 'SELECT a + b'");
            s1.executeUpdate("CREATE PROCEDURE replay_p(a INT, b TEXT DEFAULT 'x') "
                    + "AS 'insert into t values(?, ?)'");
            s1.execute("SCRIPT TO '" + dumpPath + "'");
        }
        String url = "jdbc:lealone:embed:" + dir + "/named_default_replay_db2;user=root;password=";
        try (Connection c2 = DriverManager.getConnection(url);
                Statement s2 = c2.createStatement()) {
            s2.executeUpdate("RUNSCRIPT FROM '" + dumpPath + "'");
            try (ResultSet r = s2.executeQuery("SELECT replay_add(1)")) {
                assertTrue(r.next());
                assertEquals(11, r.getInt(1));
            }
            try (ResultSet r = s2.executeQuery("SELECT replay_add(b => 2, a => 1)")) {
                assertTrue(r.next());
                assertEquals(3, r.getInt(1));
            }
            s2.executeUpdate("CALL replay_p(b => 'y', a => 7)");
            try (ResultSet r = s2.executeQuery("SELECT id, v FROM t")) {
                assertTrue(r.next());
                assertEquals(7, r.getInt(1));
                assertEquals("y", r.getString(2));
            }
        } finally {
            FileUtils.deleteRecursive(dir, false);
        }
    }

    private int queryInt(String sql) throws Exception {
        executeQuery(sql);
        return getIntValue(1, true);
    }

    private String queryString(String sql) throws Exception {
        executeQuery(sql);
        return getStringValue(1, true);
    }

    private String getFunctionDDL(String name) throws Exception {
        try (ResultSet result = stmt.executeQuery("SCRIPT")) {
            while (result.next()) {
                String sql = result.getString(1);
                if (sql != null && sql.startsWith("CREATE FUNCTION ")
                        && sql.contains(name + "(")) {
                    return sql;
                }
            }
        }
        return "";
    }

    private String getViewDDL(String name) throws Exception {
        try (ResultSet result = stmt.executeQuery("SCRIPT")) {
            while (result.next()) {
                String sql = result.getString(1);
                if (sql != null && sql.startsWith("CREATE FORCE VIEW ")
                        && sql.contains(name + "(")) {
                    return sql;
                }
            }
        }
        return "";
    }
}
