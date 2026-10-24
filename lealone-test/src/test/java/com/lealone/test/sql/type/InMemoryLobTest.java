/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.type;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 内存库(persistent=false)下的 Lob 创建：内存库没有持久化的数据库级 LobStorage，
 * 但 session 的 DataHandler 不能返回 null 的 LobStorage，否则 setClob/setCharacterStream、
 * 函数返回 Reader/Clob、函数返回带 CLOB 列的 ResultSet、FILE_READ 等路径全部 NPE。
 * <p>
 * 内存库的 Lob 是内存内联的(见 Database.getMaxLengthInplaceLob)，不写 chunk、不落盘。
 */
public class InMemoryLobTest extends TestBase {

    private static final byte[] BLOB_PS = "blob-ps".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOB_STREAM = "blob-stream".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOB_API = "blob-api".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOB_FILE = "file-read".getBytes(StandardCharsets.UTF_8);

    @Test
    public void run() throws Exception {
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        String url = "jdbc:lealone:embed:InMemoryLobTest_" + System.nanoTime()
                + ";user=root;password=;PERSISTENT=false";
        File file = new File(TEST_DIR, "in_memory_lob_read.txt");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), BLOB_FILE);
        String fileName = file.getAbsolutePath().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("CREATE TABLE t(a int primary key, v varchar(20), c clob, b blob)");
                s.executeUpdate("INSERT INTO t(a, v) VALUES(1, 'x')");
                // 函数返回 Reader，值来自嵌套连接的 VARCHAR 列
                s.executeUpdate("CREATE ALIAS f_reader AS $$ java.io.Reader f_reader(java.sql.Connection c, int p)"
                        + " throws Exception { java.sql.ResultSet r = c.createStatement()"
                        + ".executeQuery(\"SELECT v FROM t WHERE a=\" + p); r.next();"
                        + " java.io.Reader v = r.getCharacterStream(1); return v; } $$");
                // 函数返回 Clob
                s.executeUpdate("CREATE ALIAS f_clob AS $$ java.sql.Clob f_clob(java.sql.Connection c, int p)"
                        + " throws Exception { java.sql.ResultSet r = c.createStatement()"
                        + ".executeQuery(\"SELECT CAST('clob-obj' AS CLOB) FROM t WHERE a=\" + p);"
                        + " r.next(); return r.getClob(1); } $$");
                // 函数返回带 CLOB 列的 ResultSet
                s.executeUpdate("CREATE ALIAS f_rs_clob AS $$ java.sql.ResultSet f_rs_clob(java.sql.Connection c, int p)"
                        + " throws Exception { return c.createStatement()"
                        + ".executeQuery(\"SELECT CAST('clob-rs' AS CLOB) FROM t WHERE a=\" + p); } $$");
            }

            // 1. PreparedStatement.setClob
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET c=? WHERE a=1")) {
                ps.setClob(1, new StringReader("clob-ps"));
                assertEquals(1, ps.executeUpdate());
            }
            assertEquals("clob-ps", queryString(conn, "SELECT c FROM t WHERE a=1"));

            // 2. PreparedStatement.setCharacterStream
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET c=? WHERE a=1")) {
                ps.setCharacterStream(1, new StringReader("clob-stream"));
                assertEquals(1, ps.executeUpdate());
            }
            assertEquals("clob-stream", queryString(conn, "SELECT c FROM t WHERE a=1"));

            // 3. PreparedStatement.setBlob
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET b=? WHERE a=1")) {
                ps.setBlob(1, new java.io.ByteArrayInputStream(BLOB_PS));
                assertEquals(1, ps.executeUpdate());
            }
            assertBytes(BLOB_PS, queryBytes(conn, "SELECT b FROM t WHERE a=1"));

            // 4. PreparedStatement.setBinaryStream
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET b=? WHERE a=1")) {
                ps.setBinaryStream(1, new java.io.ByteArrayInputStream(BLOB_STREAM));
                assertEquals(1, ps.executeUpdate());
            }
            assertBytes(BLOB_STREAM, queryBytes(conn, "SELECT b FROM t WHERE a=1"));

            // 5. Connection.createClob() + setString
            Clob clob = conn.createClob();
            clob.setString(1, "clob-api");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET c=? WHERE a=1")) {
                ps.setClob(1, clob);
                assertEquals(1, ps.executeUpdate());
            }
            assertEquals("clob-api", queryString(conn, "SELECT c FROM t WHERE a=1"));

            // 6. Connection.createBlob() + setBytes
            Blob blob = conn.createBlob();
            blob.setBytes(1, BLOB_API);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET b=? WHERE a=1")) {
                ps.setBlob(1, blob);
                assertEquals(1, ps.executeUpdate());
            }
            assertBytes(BLOB_API, queryBytes(conn, "SELECT b FROM t WHERE a=1"));

            // 7. 函数返回 Reader
            assertEquals("x", queryString(conn, "SELECT f_reader(1)"));

            // 8. 函数返回 Clob
            assertEquals("clob-obj", queryString(conn, "SELECT f_clob(1)"));

            // 9. 函数返回带 CLOB 列的 ResultSet
            assertEquals("clob-rs", queryString(conn, "SELECT * FROM f_rs_clob(1)"));

            // 10. FILE_READ，BLOB 和 CLOB 两种形式
            assertBytes(BLOB_FILE, queryBytes(conn, "SELECT FILE_READ('" + fileName + "')"));
            assertEquals("file-read",
                    queryString(conn, "SELECT FILE_READ('" + fileName + "', 'UTF-8')"));
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue("no row returned for: " + sql, rs.next());
            String v = rs.getString(1);
            assertFalse("more than one row returned for: " + sql, rs.next());
            return v;
        }
    }

    private static byte[] queryBytes(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue("no row returned for: " + sql, rs.next());
            byte[] v = rs.getBytes(1);
            assertFalse("more than one row returned for: " + sql, rs.next());
            return v;
        }
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        assertTrue("expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual),
                Arrays.equals(expected, actual));
    }
}
