/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.concurrency;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * ANALYZE (SELECTIVITY aggregation) must not fail or return bogus values when the
 * table scan runs into tombstones of deleted rows.
 *
 * 这是回归护栏，不是那个 NPE 的稳定复现用例：该 NPE 只在 page split + checkpoint
 * 并发时才出现（ANALYZE 作为杂项任务在调度线程执行，扫到没有锁的墓碑行）。
 */
public class AnalyzeTombstoneTest extends TestBase {

    @Test(timeout = 120000)
    public void analyzeAfterDelete() throws Exception {
        String db = "analyzetomb_" + System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=")) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("CREATE TABLE t (id int primary key, k int, payload varchar(200))"
                        + " PARAMETERS(page_size='4k')");
                s.executeUpdate("CREATE INDEX idx_t_k ON t(k)");
                for (int i = 1; i <= 4000; i++)
                    s.executeUpdate("INSERT INTO t(id, k, payload) VALUES(" + i + ", " + i + ", 'p"
                            + i + "')");
                // 删掉一半行，让扫描时能读到墓碑行
                s.executeUpdate("DELETE FROM t WHERE id <= 2000");
                s.executeUpdate("CHECKPOINT");

                checkSelectivity(s, "SELECT SELECTIVITY(ID), SELECTIVITY(K), SELECTIVITY(PAYLOAD)"
                        + " FROM t");
                checkSelectivity(s, "SELECT SELECTIVITY(ID), SELECTIVITY(K), SELECTIVITY(PAYLOAD)"
                        + " FROM t LIMIT 1 SAMPLE_SIZE 1000");
                // 内部就是上面那条SQL，NPE 曾经在这里被抛出
                s.executeUpdate("ANALYZE");
                checkSelectivity(s, "SELECT SELECTIVITY(ID), SELECTIVITY(K), SELECTIVITY(PAYLOAD)"
                        + " FROM t");

                long count = queryLong(s, "SELECT COUNT(*) FROM t WHERE k >= 0");
                assertEquals(2000, count);
            }
        }
        // 关闭再打开，让页从磁盘读回来，墓碑行会以 columns=null 的Row出现
        shutdownDatabase(db);
        try (Connection conn = DriverManager.getConnection(
                "jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=");
                Statement s = conn.createStatement()) {
            assertEquals(2000, queryLong(s, "SELECT COUNT(*) FROM t WHERE k >= 0"));
            checkSelectivity(s, "SELECT SELECTIVITY(ID), SELECTIVITY(K), SELECTIVITY(PAYLOAD)"
                    + " FROM t");
            s.executeUpdate("ANALYZE");
            checkSelectivity(s, "SELECT SELECTIVITY(ID), SELECTIVITY(K), SELECTIVITY(PAYLOAD)"
                    + " FROM t");
        }
    }

    private static void shutdownDatabase(String db) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:lealone:embed:" + joinDirs("lealone") + ";user=root;password=");
                Statement s = c.createStatement()) {
            s.executeUpdate("SHUTDOWN DATABASE " + db);
        }
    }

    private static void checkSelectivity(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue("no row returned: " + sql, rs.next());
            for (int i = 1; i <= 3; i++) {
                int selectivity = rs.getInt(i);
                assertTrue("selectivity out of range: " + selectivity + ", sql: " + sql,
                        selectivity >= 0 && selectivity <= 100);
            }
        }
    }

    private static long queryLong(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
