/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.net;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * TCP 端到端回归: 10 万行 × 100 字符 varchar 批量插入 + 中文/emoji/长字符串往返。
 *
 * 背景: 客户端默认 SO_SNDBUF 只有 8k 时, 一个批量 INSERT 请求会被拆成很多个 8k 小块,
 * 每块都要等对端 ACK, 撞上 TCP 延迟 ACK 的 40ms, 100k 行 × 85 字符从 0.6s 退化到 ~20s。
 * 这里用宽松阈值 3s 做护栏(修复后本机 ~0.6s)。
 */
public class TcpStringInsertPerfTest extends SqlTestBase {

    public TcpStringInsertPerfTest() {
        super("TcpStringInsertPerfTest");
    }

    private static String repeat(String s, int n) {
        StringBuilder buff = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++)
            buff.append(s);
        return buff.toString();
    }

    @Test
    public void testBatchInsert100k() throws Exception {
        stmt.executeUpdate("DROP TABLE IF EXISTS perf_str");
        stmt.executeUpdate(
                "CREATE TABLE perf_str(id bigint primary key, k int, pad varchar(200))");
        String pad = repeat("abcdefghij", 10); // 100 chars
        int rows = 100_000;
        conn.setAutoCommit(false);
        long start = System.nanoTime();
        try (PreparedStatement ps = conn
                .prepareStatement("INSERT INTO perf_str(id, k, pad) VALUES(?, ?, ?)")) {
            for (int i = 1; i <= rows; i++) {
                ps.setLong(1, i);
                ps.setInt(2, i % 1000);
                ps.setString(3, pad + i);
                ps.addBatch();
                if (i % 500 == 0)
                    ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[TcpStringInsertPerfTest] " + rows + " rows insert: " + millis + " ms");

        long expectedLength = 0;
        for (int i = 1; i <= rows; i++)
            expectedLength += pad.length() + String.valueOf(i).length();
        try (Statement s = conn.createStatement();
                ResultSet rs = s
                        .executeQuery("SELECT count(*), sum(length(pad)) FROM perf_str")) {
            assertTrue(rs.next());
            assertEquals(rows, rs.getInt(1));
            assertEquals(expectedLength, rs.getLong(2));
        }
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT count(*) FROM perf_str WHERE pad = '" + pad + "100000'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        assertTrue("10 万行批量插入耗时 " + millis + "ms, 超过 3000ms, 疑似客户端小包被延迟 ACK 拖住",
                millis < 3000);
    }

    @Test
    public void testUnicodeAndLongStringRoundTrip() throws Exception {
        stmt.executeUpdate("DROP TABLE IF EXISTS unicode_str");
        stmt.executeUpdate("CREATE TABLE unicode_str(id int primary key, v varchar(20000))");
        String longValue = repeat("中", 5000);
        String[] values = { "中文测试", "😀🚀", "abc", "", null, longValue };
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn
                .prepareStatement("INSERT INTO unicode_str(id, v) VALUES(?, ?)")) {
            for (int i = 0; i < values.length; i++) {
                ps.setInt(1, i + 1);
                ps.setString(2, values[i]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT id, v FROM unicode_str ORDER BY id")) {
            for (int i = 0; i < values.length; i++) {
                assertTrue(rs.next());
                assertEquals(i + 1, rs.getInt(1));
                assertEquals(values[i], rs.getString(2));
            }
            assertFalse(rs.next());
        }
    }
}
