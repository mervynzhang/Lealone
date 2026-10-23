/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

/**
 * 回归测试 github issue #282:
 * 1) SCRIPT TO导出的dump不能含INFORMATION_SCHEMA等系统schema的DDL，
 *    否则RUNSCRIPT回放第一条就报"Access to the schema INFORMATION_SCHEMA is denied"，用户表一条都建不出来；
 * 2) SCRIPT TO ... CIPHER AES PASSWORD必须真正加密(不能只大十几字节、正文还是明文)，
 *    正确的密码能回放，错误的密码不能把数据导进去。
 */
public class ScriptRoundTripTest extends TestBase {

    private static final java.util.concurrent.atomic.AtomicInteger RUN_ID = new java.util.concurrent.atomic.AtomicInteger();

    @Test
    public void testScriptRoundTrip() throws Exception {
        // 每次用独立目录，避免上次运行的库文件残留(库目录名相同会导致表已存在)
        String dir = new File(TEST_DIR, "script_roundtrip_" + System.currentTimeMillis() + "_"
                + RUN_ID.incrementAndGet()).getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        String url1 = "jdbc:lealone:embed:" + dir + "/db1;user=root;password=";

        File plain = new File(dir, "dump.sql");
        File encrypted = new File(dir, "dump_enc.sql");
        plain.delete();
        encrypted.delete();

        try (Connection conn = DriverManager.getConnection(url1)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("create table t_user(id int primary key, name varchar(50))");
                s.executeUpdate("insert into t_user values(1, 'alice'), (2, 'bob')");
                s.executeUpdate("create table t_order(id int primary key, amount int)");
                s.executeUpdate("insert into t_order values(7, 700)");
            }
            try (Statement s = conn.createStatement()) {
                s.execute("SCRIPT TO '" + plain.getAbsolutePath() + "'");
            }
            try (Statement s = conn.createStatement()) {
                s.execute("SCRIPT TO '" + encrypted.getAbsolutePath()
                        + "' CIPHER AES PASSWORD 'secret'");
            }
        }

        // 1) 明文dump里不能有系统schema的DDL
        String script = new String(Files.readAllBytes(plain.toPath()), StandardCharsets.UTF_8);
        assertFalse("dump must not export system schema DDL: " + script,
                script.toUpperCase().contains("INFORMATION_SCHEMA"));
        assertTrue("dump must contain the user table", script.contains("T_USER"));

        // 2) 明文dump能完整回放
        String url2 = "jdbc:lealone:embed:" + dir + "/db2;user=root;password=";
        try (Connection conn = DriverManager.getConnection(url2)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("RUNSCRIPT FROM '" + plain.getAbsolutePath() + "'");
            }
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("select count(*) from t_user")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("select count(*) from t_order")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }

        // 3) 加密dump必须真的加密: 不能grep到表名/数据，且带加密文件头
        byte[] encryptedBytes = Files.readAllBytes(encrypted.toPath());
        String encryptedText = new String(encryptedBytes, StandardCharsets.ISO_8859_1);
        assertFalse("encrypted dump must not contain plain table name", encryptedText.contains("T_USER"));
        assertFalse("encrypted dump must not contain plain data", encryptedText.contains("alice"));
        assertTrue("encrypted dump must not be bigger by only a few bytes than the plain one",
                encryptedBytes.length >= plain.length() + 4096);
        assertTrue("encrypted dump must start with the encryption header",
                encryptedText.startsWith("LealoneEncrypt"));

        // 4) 正确密码能回放，错误密码不能把数据导进去
        String url3 = "jdbc:lealone:embed:" + dir + "/db3;user=root;password=";
        try (Connection conn = DriverManager.getConnection(url3)) {
            try (Statement s = conn.createStatement()) {
                try {
                    s.executeUpdate("RUNSCRIPT FROM '" + encrypted.getAbsolutePath()
                            + "' CIPHER AES PASSWORD 'wrong-password'");
                    fail("RUNSCRIPT with a wrong password must not succeed");
                } catch (Exception e) {
                    // 期望: 解密失败(RUNSCRIPT必须失败)。具体错误取决于解出来的垃圾数据:
                    // 要么是"Encryption error in file"，要么是读加密块时的EOFException，
                    // 两种都表示"解不出来"，不能有任何SQL被执行
                }
            }
            // 不管报不报错，错误密码都不能把表/数据导进去
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery(
                            "select count(*) from information_schema.tables where table_name = 'T_USER'")) {
                rs.next();
                assertEquals("wrong password must not create the user table", 0, rs.getInt(1));
            }
        }
        String url4 = "jdbc:lealone:embed:" + dir + "/db4;user=root;password=";
        try (Connection conn = DriverManager.getConnection(url4)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("RUNSCRIPT FROM '" + encrypted.getAbsolutePath()
                        + "' CIPHER AES PASSWORD 'secret'");
            }
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("select count(*) from t_user")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("select name from t_user where id = 1")) {
                rs.next();
                assertEquals("alice", rs.getString(1));
            }
        }
    }
}
