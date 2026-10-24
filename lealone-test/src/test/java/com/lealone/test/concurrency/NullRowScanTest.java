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
 * 并发删除期间扫描不能返回列值为NULL的行。
 *
 * 主索引里存的就是页上的Row对象，DELETE在未提交时就把它的columns原地置空(墓碑)。
 * 扫描线程在TableFilter.next()里校验通过后、投影取值前如果这行被删，
 * TableFilter.getValue()会走墓碑分支返回ValueNull，于是产出一行全NULL的脏数据。
 *
 * 复现办法：表要小(一轮扫描很短，行停留在窗口里的时间占比高)，
 * 同时不断delete+insert同一批id制造原地置空事件。
 */
public class NullRowScanTest extends TestBase {

    private static final AtomicInteger RUN_ID = new AtomicInteger();
    private static final int ROW_COUNT = 8;
    private static final long RUN_MILLIS = 3000;

    @Test(timeout = 60000)
    public void concurrentScanMustNotReturnNullRow() throws Exception {
        String db = "nullrow_" + System.currentTimeMillis() + "_" + RUN_ID.incrementAndGet();
        String table = "t_nullrow";
        String payload = "0123456789";

        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong scans = new AtomicLong();

        try (Connection admin = open(db)) {
            try (Statement s = admin.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + table);
                s.executeUpdate("CREATE TABLE " + table
                        + " (id int primary key, k int, payload varchar(200)) PARAMETERS(page_size='4k')");
                for (int id = 1; id <= ROW_COUNT; id++)
                    s.executeUpdate("INSERT INTO " + table + "(id, k, payload) VALUES(" + id + ", " + id
                            + ", '" + payload + "')");
            }

            Thread churner = new Thread(() -> {
                try (Connection c = open(db);
                        PreparedStatement del = c
                                .prepareStatement("DELETE FROM " + table + " WHERE id = ?");
                        PreparedStatement ins = c.prepareStatement("INSERT INTO " + table
                                + "(id, k, payload) VALUES(?, ?, ?)")) {
                    while (!stopped.get()) {
                        for (int id = 1; id <= ROW_COUNT && !stopped.get(); id++) {
                            del.setInt(1, id);
                            del.executeUpdate();
                            ins.setInt(1, id);
                            ins.setInt(2, id);
                            ins.setString(3, payload);
                            ins.executeUpdate();
                        }
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "nullrow-churner");

            Thread scanner = new Thread(() -> {
                try (Connection c = open(db); Statement s = c.createStatement()) {
                    while (!stopped.get()) {
                        try (ResultSet rs = s.executeQuery(
                                "SELECT id FROM " + table + " WHERE id >= 0")) {
                            while (rs.next()) {
                                Object o = rs.getObject(1);
                                if (o == null || rs.wasNull())
                                    throw new AssertionError(
                                            "scan returned a row with NULL id after "
                                                    + scans.get() + " scans");
                            }
                        }
                        scans.incrementAndGet();
                    }
                } catch (Throwable e) {
                    setFailure(failure, stopped, e);
                }
            }, "nullrow-reader");

            churner.start();
            scanner.start();
            try {
                Thread.sleep(RUN_MILLIS);
            } finally {
                stopped.set(true);
            }
            churner.join(30000);
            scanner.join(30000);
        }
        if (failure.get() != null)
            throw new AssertionError("scan returned a NULL row: " + failure.get(), failure.get());
        // 确认真的在并发扫描，避免配置写错导致空跑
        if (scans.get() < 1000)
            throw new AssertionError("not enough scans: " + scans.get());
    }

    private static void setFailure(AtomicReference<Throwable> failure, AtomicBoolean stopped,
            Throwable e) {
        if (failure.compareAndSet(null, e))
            stopped.set(true);
    }

    private static Connection open(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=");
    }
}
