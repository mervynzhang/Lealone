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
 * TCP 端到端回归: 10 万行 × 85 字符 varchar 批量插入 + 中文/emoji/长字符串往返。
 *
 * 背景: 客户端 SO_SNDBUF 只有 8k 时, 一个批量 INSERT 请求会被拆成很多个小块,
 * 每块都要等对端 ACK, 撞上 TCP 延迟 ACK 的 40ms, 100k 行批量插入会慢到 ~9s
 * (64k 或自适应上调后 ~0.3s, 均为本机独立实例实测)。这里不写绝对墙钟阈值,
 * 而是在同一次运行里先跑 1 万行取每行耗时基线, 再跑 10 万行, 断言每行耗时不超过
 * 基线的 5 倍; 共享 9210 服务端在 2 核 CI 上的整体变慢不会误报, 只有随行数超线性
 * 退化(延迟 ACK/逐字符路径)才会翻红。
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

    private long insertBatch(String tableName, int rows, String pad) throws Exception {
        stmt.executeUpdate("DROP TABLE IF EXISTS " + tableName);
        stmt.executeUpdate("CREATE TABLE " + tableName
                + "(id bigint primary key, k int, pad varchar(200))");
        conn.setAutoCommit(false);
        long start = System.nanoTime();
        try (PreparedStatement ps = conn
                .prepareStatement("INSERT INTO " + tableName + "(id, k, pad) VALUES(?, ?, ?)")) {
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

        long expectedLength = 0;
        for (int i = 1; i <= rows; i++)
            expectedLength += pad.length() + String.valueOf(i).length();
        try (Statement s = conn.createStatement();
                ResultSet rs = s
                        .executeQuery("SELECT count(*), sum(length(pad)) FROM " + tableName)) {
            assertTrue(rs.next());
            assertEquals(rows, rs.getInt(1));
            assertEquals(expectedLength, rs.getLong(2));
        }
        return millis;
    }

    @Test
    public void testBatchInsert100k() throws Exception {
        String pad = repeat("abcdefghij", 8) + "abcde"; // 85 chars
        int baselineRows = 10_000;
        int rows = 100_000;

        long baselineMillis = insertBatch("perf_str_baseline", baselineRows, pad);
        long millis = insertBatch("perf_str", rows, pad);
        System.out.println("[TcpStringInsertPerfTest] " + baselineRows + " rows baseline: "
                + baselineMillis + " ms, " + rows + " rows insert: " + millis + " ms");

        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT count(*) FROM perf_str WHERE pad = '" + pad + "100000'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        // 10 万行是 1 万行的 10 倍, 每行耗时允许放宽到基线的 5 倍(总耗时 50 倍)。
        assertTrue("10 万行插入 " + millis + "ms, 超过 " + baselineRows + " 行基线 " + baselineMillis
                + "ms 的 50 倍(每行耗时为基线的 "
                + String.format("%.2f", (double) millis / rows / ((double) baselineMillis / baselineRows))
                + " 倍), 疑似随行数超线性退化", millis <= baselineMillis * 50L);
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
