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
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL兼容的只读函数目录INFORMATION_SCHEMA.PG_PROC和内置函数pg_get_functiondef。
 */
public class FunctionCatalogTest extends SqlTestBase {

    public FunctionCatalogTest() {
        setEmbedded(true);
    }

    @Test
    public void testPgProcColumns() throws Exception {
        executeUpdate("CREATE FUNCTION pgc_a(a TEXT, b DATE) RETURNS BOOLEAN AS $$ SELECT TRUE $$");
        executeUpdate("CREATE FUNCTION pgc_b(a INT[]) RETURNS INT AS $$ SELECT 1 $$");
        executeUpdate("CREATE FUNCTION pgc_c() RETURNS BOOLEAN IMMUTABLE AS $$ SELECT FALSE $$");
        executeUpdate("CREATE ALIAS pgc_alias FOR 'java.lang.System.currentTimeMillis'");

        executeQuery("SELECT prorettype, proargtypes, provolatile, proisstrict "
                + "FROM INFORMATION_SCHEMA.PG_PROC WHERE upper(proname) = 'PGC_A'");
        assertEquals("BOOLEAN", rs.getString(1));
        assertEquals("{TEXT,DATE}", rs.getString(2));
        assertEquals("v", rs.getString(3));
        assertEquals(false, rs.getBoolean(4));

        executeQuery("SELECT prorettype, proargtypes FROM INFORMATION_SCHEMA.PG_PROC "
                + "WHERE upper(proname) = 'PGC_B'");
        assertEquals("INT", rs.getString(1));
        assertEquals("{INT[]}", rs.getString(2));

        executeQuery("SELECT provolatile FROM INFORMATION_SCHEMA.PG_PROC WHERE upper(proname) = 'PGC_C'");
        assertEquals("i", rs.getString(1));

        // CREATE ALIAS不是SQL函数，不出现在PG_PROC里
        executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.PG_PROC "
                + "WHERE upper(proname) = 'PGC_ALIAS'");
        assertEquals(0, getIntValue(1));
    }

    @Test
    public void testPgProcAfterAlter() throws Exception {
        executeUpdate("CREATE FUNCTION pgc_d(a INT) RETURNS BOOLEAN AS $$ SELECT TRUE $$");
        executeUpdate("CREATE FUNCTION pgc_e(a INT) RETURNS BOOLEAN AS $$ SELECT TRUE $$");
        executeUpdate("CREATE FUNCTION pgc_f(a INT) RETURNS BOOLEAN AS $$ SELECT TRUE $$");
        executeUpdate("ALTER FUNCTION pgc_d(INT) IMMUTABLE");
        executeUpdate("ALTER FUNCTION pgc_e(INT) STRICT");
        executeUpdate("ALTER FUNCTION pgc_f(INT) SECURITY DEFINER");
        executeUpdate("ALTER FUNCTION pgc_f(INT) LEAKPROOF");
        executeQuery("SELECT provolatile FROM INFORMATION_SCHEMA.PG_PROC WHERE upper(proname) = 'PGC_D'");
        assertEquals("i", rs.getString(1));

        executeQuery("SELECT provolatile, proisstrict FROM INFORMATION_SCHEMA.PG_PROC "
                + "WHERE upper(proname) = 'PGC_E'");
        assertEquals("v", rs.getString(1));
        assertEquals(true, rs.getBoolean(2));

        executeQuery("SELECT prosecdef, proleakproof FROM INFORMATION_SCHEMA.PG_PROC "
                + "WHERE upper(proname) = 'PGC_F'");
        assertEquals(true, rs.getBoolean(1));
        assertEquals(true, rs.getBoolean(2));
    }

    @Test
    public void testPgGetFunctionDef() throws Exception {
        executeUpdate("CREATE FUNCTION pgc_g(a INT) RETURNS BOOLEAN STABLE AS $$ SELECT a > 0 $$");
        executeQuery("SELECT pg_get_functiondef('pgc_g')");
        String sql = getStringValue(1);
        assertTrue(sql.startsWith("CREATE FUNCTION"));
        assertTrue(sql.contains("PGC_G"));
        assertTrue(sql.contains("STABLE"));
        assertTrue(sql.contains("a > 0"));

        executeQueryThanAssertErrorCode("SELECT pg_get_functiondef('pgc_not_exists')",
                ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1);

        // 有重载且只给函数名时无法确定是哪一个
        executeUpdate("CREATE FUNCTION pgc_g(a VARCHAR) RETURNS BOOLEAN AS $$ SELECT TRUE $$");
        executeQueryThanAssertErrorCode("SELECT pg_get_functiondef('pgc_g')",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
    }

    @Test
    public void testScriptAndRestart() throws Exception {
        executeUpdate("CREATE FUNCTION pgc_h(a INT) RETURNS INT IMMUTABLE AS $$ SELECT a + 1 $$");
        executeUpdate("CREATE FUNCTION pgc_h(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$");

        StringBuilder script = new StringBuilder();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line.toUpperCase().contains("FUNCTION PGC_H(")) {
                    script.append(line).append('\n');
                }
            }
        }
        assertTrue(script.length() > 0);

        // SCRIPT回放后PG_PROC和pg_get_functiondef都要能查到
        String dir = newTestDir("function_catalog_replay");
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
                try (ResultSet rs = s.executeQuery("SELECT proargtypes FROM INFORMATION_SCHEMA.PG_PROC "
                        + "WHERE upper(proname) = 'PGC_H' ORDER BY proargtypes")) {
                    assertTrue(rs.next());
                    assertEquals("{INT}", rs.getString(1));
                    assertTrue(rs.next());
                    assertEquals("{VARCHAR}", rs.getString(1));
                }
                try (ResultSet rs = s.executeQuery("SELECT pg_get_functiondef('pgc_h')")) {
                    rs.next();
                    fail("pgc_h有两个重载，按名字取DDL应该报错");
                } catch (Exception e) {
                    // 两个重载时按名字取DDL是歧义的
                    assertTrue(e.getMessage().contains("90152"));
                }
            }
        }

        // 重启同一个库，目录和函数定义都还在
        String restartDir = newTestDir("function_catalog_restart");
        String restartUrl = "jdbc:lealone:embed:" + restartDir + "/"
                + restartDir.substring(restartDir.lastIndexOf('/') + 1) + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(restartUrl)) {
            try (Statement s = conn2.createStatement()) {
                s.executeUpdate("CREATE FUNCTION pgc_i(a INT) RETURNS INT AS $$ SELECT a * 2 $$");
            }
        }
        try (Connection conn2 = DriverManager.getConnection(restartUrl)) {
            try (Statement s = conn2.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT proname FROM INFORMATION_SCHEMA.PG_PROC "
                        + "WHERE upper(proname) = 'PGC_I'")) {
                    assertTrue(rs.next());
                }
                try (ResultSet rs = s.executeQuery("SELECT pg_get_functiondef('pgc_i')")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getString(1).contains("PGC_I"));
                }
            }
        }
    }

    private static String newTestDir(String name) {
        String dir = new File(TestBase.TEST_DIR, name + "_" + System.currentTimeMillis())
                .getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        return dir;
    }
}
