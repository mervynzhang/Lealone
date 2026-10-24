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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 并发扫描不能返回重复行(端到端护栏)。4k 页的表在并发 insert/delete/checkpoint 时反复扫描主索引。
 *
 * 历史修复：
 * - BTreeCursor 曾经在子页被切割后从父节点第一个子页重扫，实测 1 万行的表扫出 9 万多行；
 *   已修(按最后返回的 key 从根重新定位)。
 * - 扫描曾返回已删除的墓碑行(全 NULL 行)：StandardPrimaryIndex 游标跳过墓碑，
 *   TableFilter 跳过列值为 null 的行(二级索引还要物化确认基表行还在)。
 * - 语句重试时 TableIterator 没清旧行，同一行在一次语句执行里被处理两次，进而在二级索引上
 *   留下陈旧/孤儿索引项；同时二级索引游标改为"主表当前值 == 索引项保存值"才返回。
 *
 * 现状(实测)：二级索引扫描 + 索引列并发 UPDATE 的重复已由 QFlat 按行主键去重修复。
 * 本用例覆盖的主键范围扫描在并发 checkout 下仍偶发重复(约 1/万次)，成因不同(重扫时结果未清空)。
 */
public class ScanDuplicateTest extends TestBase {

    private static final AtomicInteger RUN_ID = new AtomicInteger();
    private static final int INSERT_COUNT = 20000;
    private static final long RUN_MILLIS = 15000;
    // 并发采样允许的最大重复行数，用于容忍已知的1行残留竞态窗
    private static final long MAX_DELTA = 3;

    @Test(timeout = 120000)
    public void concurrentScanMustNotReturnDuplicateRows() throws Exception {
        String db = "scandup_" + System.currentTimeMillis() + "_" + RUN_ID.incrementAndGet();
        String table = "t_scan";
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicLong inserted = new AtomicLong();
        AtomicLong deleted = new AtomicLong();
        AtomicLong scans = new AtomicLong();
        AtomicLong maxDelta = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (Connection admin = open(db)) {
            try (Statement s = admin.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + table);
                s.executeUpdate("CREATE TABLE " + table
                        + " (id int primary key, k int, payload varchar(200)) PARAMETERS(page_size='4k')");
                s.executeUpdate("CREATE INDEX idx_scan_k ON " + table + "(k)");
            }

            Thread inserter = new Thread(() -> {
                try (Connection c = open(db);
                        PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO " + table + "(id, k, payload) VALUES(?, ?, ?)")) {
                    setLockTimeout(c);
                    String payload = repeat('x', 120);
                    for (int id = 1; id <= INSERT_COUNT && !stopped.get(); id++) {
                        ps.setInt(1, id);
                        ps.setInt(2, id);
                        ps.setString(3, payload + id);
                        if (ps.executeUpdate() != 1)
                            throw new AssertionError(
                                    "insert of id=" + id + " affected != 1 row");
                        inserted.incrementAndGet();
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "scan-inserter");

            Thread deleter = new Thread(() -> {
                try (Connection c = open(db);
                        PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM " + table + " WHERE id = ?")) {
                    setLockTimeout(c);
                    int next = 1;
                    while (next <= INSERT_COUNT / 2 && !stopped.get()) {
                        // 等到这条insert已经提交返回再从另一个连接删
                        while (inserted.get() < next && !stopped.get() && failure.get() == null)
                            Thread.sleep(1);
                        if (failure.get() != null)
                            break;
                        ps.setInt(1, next);
                        int n = ps.executeUpdate();
                        if (n == 0) { // DELETE的事务快照可能比INSERT的提交更早，重试一次
                            ps.setInt(1, next);
                            n = ps.executeUpdate();
                        }
                        if (n == 1) {
                            deleted.incrementAndGet();
                            next++;
                        } else if (inserted.get() < next) {
                            Thread.sleep(1);
                        } else {
                            throw new AssertionError("id=" + next
                                    + " has been inserted but DELETE affected 0 rows twice");
                        }
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "scan-deleter");

            Thread checkpoint = new Thread(() -> {
                try (Connection c = open(db); Statement s = c.createStatement()) {
                    setLockTimeout(c);
                    while (!stopped.get()) {
                        s.executeUpdate("CHECKPOINT");
                        Thread.sleep(100);
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "scan-checkpoint");

            Thread scanner = new Thread(() -> {
                try (Connection c = open(db); Statement s = c.createStatement()) {
                    setLockTimeout(c);
                    while (!stopped.get()) {
                        // 必须在同一条语句里取完整结果再判重，
                        // 两条聚合语句会各自取快照，并发删除时对比它们没有意义
                        try (ResultSet rs = s.executeQuery(
                                "SELECT id FROM " + table + " WHERE id >= 0")) {
                            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                            long count = 0;
                            while (rs.next()) {
                                count++;
                                if (!seen.add(rs.getInt(1)))
                                    throw new AssertionError(
                                            "same statement returned duplicate row, id="
                                                    + rs.getInt(1) + ", rows read=" + count);
                                if (count > INSERT_COUNT)
                                    throw new AssertionError("scan returned " + count
                                            + " rows but only " + INSERT_COUNT + " were inserted");
                            }
                            scans.incrementAndGet();
                            maxDelta.accumulateAndGet(count - seen.size(), Math::max);
                        }
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "scan-reader");

            inserter.start();
            deleter.start();
            checkpoint.start();
            scanner.start();
            inserter.join(RUN_MILLIS + 60000);
            Thread.sleep(RUN_MILLIS);
            stopped.set(true);
            scanner.join(30000);
            deleter.join(30000);
            checkpoint.join(10000);
            if (inserter.isAlive() || scanner.isAlive() || deleter.isAlive() || checkpoint.isAlive())
                fail("a scan test thread did not stop: inserter=" + inserter.isAlive() + ", scanner="
                        + scanner.isAlive() + ", deleter=" + deleter.isAlive() + ", checkpoint="
                        + checkpoint.isAlive());

            // 停下来之后主表与"扫描结果"必须完全一致
            long count = queryCount(admin,
                    "SELECT COUNT(*) FROM " + table + " WHERE id >= 0");
            long distinct = queryCount(admin,
                    "SELECT COUNT(DISTINCT id) FROM " + table + " WHERE id >= 0");
            long expected = inserted.get() - deleted.get();
            assertEquals("table scan and distinct count disagrees after the run", count, distinct);
            assertEquals("row count mismatch: inserted=" + inserted.get() + ", deleted="
                    + deleted.get(), expected, count);
        }
        if (failure.get() != null)
            throw new AssertionError("concurrent scan failed after " + scans.get() + " scans: "
                    + failure.get(), failure.get());
        System.out.println("scan duplicate test: scans=" + scans.get() + ", maxDelta="
                + maxDelta.get() + ", inserted=" + inserted.get() + ", deleted="
                + deleted.get());
    }

    private static void setFailure(AtomicReference<Throwable> failure, AtomicBoolean stopped,
            Throwable e) {
        if (failure.compareAndSet(null, e))
            stopped.set(true);
    }

    private static Connection open(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=");
    }

    private static void setLockTimeout(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("SET LOCK_TIMEOUT 10000");
        }
    }

    private static long queryCount(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            sb.append(ch);
        return sb.toString();
    }
}
