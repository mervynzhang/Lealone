/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.concurrency;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 二级索引扫描的吞吐量基准，用来量化"每条索引项都回主表校验"的代价。
 * 不是断言性测试，只打印数字。
 */
public class SecondaryIndexScanBenchTest extends TestBase {

    private static final int ROWS = 200000;
    private static final int WARMUP = 3;
    private static final int ROUNDS = 10;
    // 每次范围扫描覆盖的行数
    private static final int RANGE = 1000;

    @Test
    public void indexScanThroughput() throws Exception {
        String db = "sibench_" + System.currentTimeMillis();
        String table = "t_bench";
        try (Connection c = open(db)) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE " + table
                        + " (id int primary key, k int, payload varchar(200))");
                s.executeUpdate("CREATE INDEX idx_bench_k ON " + table + "(k)");
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table + "(id, k, payload) VALUES(?, ?, ?)")) {
                String payload = repeat('x', 120);
                for (int i = 1; i <= ROWS; i++) {
                    ps.setInt(1, i);
                    ps.setInt(2, i);
                    ps.setString(3, payload);
                    ps.addBatch();
                    if (i % 5000 == 0)
                        ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);

            // 范围扫描：走二级索引，每次扫 RANGE 行
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, payload FROM " + table + " WHERE k >= ? AND k < ?")) {
                for (int i = 0; i < WARMUP; i++)
                    rangeScan(ps, 0);
                long rows = 0;
                long t0 = System.nanoTime();
                for (int i = 0; i < ROUNDS; i++) {
                    long start = (long) (Math.random() * (ROWS - RANGE));
                    rows += rangeScan(ps, (int) start);
                }
                report("rangeScan", t0, ROUNDS, rows);
            }

            // 等值探查：走二级索引，每次 1 行
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, payload FROM " + table + " WHERE k = ?")) {
                for (int i = 0; i < WARMUP * 100; i++)
                    probe(ps, i);
                long t0 = System.nanoTime();
                int n = 2000;
                for (int i = 0; i < n; i++)
                    probe(ps, i);
                report("equalityProbe", t0, n, n);
            }
        }
    }

    private static long rangeScan(PreparedStatement ps, int start) throws SQLException {
        ps.setInt(1, start);
        ps.setInt(2, start + RANGE);
        long n = 0;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rs.getInt(1);
                rs.getString(2);
                n++;
            }
        }
        return n;
    }

    private static void probe(PreparedStatement ps, int k) throws SQLException {
        ps.setInt(1, k);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rs.getInt(1);
                rs.getString(2);
            }
        }
    }

    private static void report(String name, long t0, int ops, long rows) {
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        System.out.printf("BENCH %s: ops=%d rows=%d total=%.1fms perOp=%.3fms rowsPerSec=%.0f%n",
                name, ops, rows, ms, ms / ops, rows / (ms / 1000.0));
    }

    private static Connection open(String db) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=");
    }

    private static String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            sb.append(ch);
        return sb.toString();
    }
}
