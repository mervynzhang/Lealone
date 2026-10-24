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

import com.lealone.test.TestBase;
import com.lealone.storage.fs.FileUtils;

public class CompositeTypePersistenceTest extends TestBase {

    @Test
    public void testRestartAndScript() throws Exception {
        String dir = new File(TEST_DIR, "composite_persist_" + System.currentTimeMillis())
                .getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        String dbSuffix = Long.toString(System.nanoTime());
        String url1 = "jdbc:lealone:embed:" + dir + "/comp1" + dbSuffix + ";user=root;password=";
        File dump = new File(dir, "dump.sql");
        dump.delete();
        try (Connection conn = DriverManager.getConnection(url1)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("CREATE TYPE comp AS (a INT, b VARCHAR(20))");
                s.executeUpdate("CREATE TABLE comp_t(id INT PRIMARY KEY, c comp)");
                s.executeUpdate("INSERT INTO comp_t VALUES(1, ROW(2, 'x')::comp)");
                s.executeUpdate("CREATE FUNCTION comp_f(i INT) RETURNS comp LANGUAGE plpgsql "
                        + "AS $$ BEGIN RETURN ROW(i, 'y')::comp; END; $$");
                s.execute("SCRIPT TO '" + dump.getAbsolutePath() + "'");
            }
        }

        // 关闭最后一个连接再打开同一个库，验证CompositeType从meta表重建
        try (Connection conn = DriverManager.getConnection(url1)) {
            try (Statement s = conn.createStatement();
                    ResultSet r = s.executeQuery("SELECT (comp_f(3)).a")) {
                assertTrue(r.next());
                assertEquals(3, r.getInt(1));
            }
            try (Statement s = conn.createStatement();
                    ResultSet r = s.executeQuery("SELECT c FROM comp_t WHERE id = 1")) {
                assertTrue(r.next());
                assertEquals("(2, x)", r.getString(1));
            }
        }

        // SCRIPT里的复合类型必须排在函数之前，且能完整回放到新库
        String script = new String(java.nio.file.Files.readAllBytes(dump.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        int typePos = script.indexOf("CREATE TYPE");
        int funcPos = script.indexOf("CREATE FUNCTION");
        assertTrue(script, typePos >= 0 && funcPos > typePos);
        String url2 = "jdbc:lealone:embed:" + dir + "/comp2" + dbSuffix + ";user=root;password=";
        try (Connection conn = DriverManager.getConnection(url2)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("RUNSCRIPT FROM '" + dump.getAbsolutePath() + "'");
            }
            try (Statement s = conn.createStatement();
                    ResultSet r = s.executeQuery("SELECT (comp_f(4)).b")) {
                assertTrue(r.next());
                assertEquals("y", r.getString(1));
            }
            try (Statement s = conn.createStatement();
                    ResultSet r = s.executeQuery("SELECT c FROM comp_t WHERE id = 1")) {
                assertTrue(r.next());
                assertEquals("(2, x)", r.getString(1));
            }
        }
    }
}
