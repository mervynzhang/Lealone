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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * Concurrent upsert + checkpoint invariants.
 *
 * Workers hammer a handful of hot rows while a separate connection runs
 * CHECKPOINT every 100ms. The exact invariant is:
 *
 *     sum(v) == number of successfully committed v=v+1 statements
 *
 * Any lost update shows up as sum(v) < successes. The secondary index on v
 * must return the same row set as a full table scan, and a close/reopen of the
 * database must preserve the values.
 *
 * A second test stresses page splits: one thread inserts sequential keys, one
 * deletes from the low end, and a third runs checkpoints, all on a table with a
 * 4k page size (i.e. many page splits).
 */
public class ConcurrencyCheckpointTest extends TestBase {

    private static final java.util.concurrent.atomic.AtomicInteger RUN_ID =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int THREADS = 8;
    private static final int ITERATIONS = 2000;
    private static final int HOT_ROWS = 4;

    // ------------------------------------------------------------------
    // 8 threads x 2000 updates, autocommit (one transaction per update)
    // ------------------------------------------------------------------

    @Test(timeout = 180000)
    public void updateStressAutocommit() throws Exception {
        updateStress(unique("concatomic"), true);
    }

    // ------------------------------------------------------------------
    // same load, but each worker uses an explicit transaction per update
    // ------------------------------------------------------------------

    @Test(timeout = 180000)
    public void updateStressExplicitTransactions() throws Exception {
        updateStress(unique("concexplicit"), false);
    }

    private void updateStress(String db, boolean autocommit) throws Exception {
        String table = "counter";
        try (Connection admin = openAdmin(db)) {
            try (Statement s = admin.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + table);
                s.executeUpdate("CREATE TABLE " + table + " (id int primary key, v int)");
                for (int i = 0; i < HOT_ROWS; i++)
                    s.executeUpdate("INSERT INTO " + table + "(id, v) VALUES(" + i + ", 0)");
                s.executeUpdate("CREATE INDEX idx_counter_v ON " + table + "(v)");
            }

            CheckpointRunner checkpoint = new CheckpointRunner(db, true);
            checkpoint.start();

            UpdateWorker[] workers = new UpdateWorker[THREADS];
            for (int i = 0; i < THREADS; i++)
                workers[i] = new UpdateWorker(db, table, autocommit, i);
            for (UpdateWorker w : workers)
                w.start();
            try {
                joinWorkers(workers);
            } finally {
                // Always stop the checkpoint thread, even if a worker hung, so it
                // cannot keep mutating/checkpointing a wedged database.
                checkpoint.stop();
            }
            checkpoint.checkErrors();

            long expected = 0;
            long failures = 0;
            for (UpdateWorker w : workers) {
                expected += w.success.get();
                failures += w.failures.get();
            }
            String context = "expected successful increments=" + expected + ", failed=" + failures
                    + ", threads=" + THREADS + ", iterations=" + ITERATIONS + ", hotRows=" + HOT_ROWS
                    + (firstWorkerError(workers) != null ? ", first error=" + firstWorkerError(workers)
                            : "");

            // Force the table side through the primary index (id >= 0), otherwise the
            // optimizer may answer SUM(v)/COUNT(*) from the secondary index on v and
            // both sides of the comparison would be equally stale.
            long actualSum = queryLong(admin, "SELECT SUM(v) FROM " + table + " WHERE id >= 0");
            long rows = queryLong(admin, "SELECT COUNT(*) FROM " + table + " WHERE id >= 0");
            long distinctRows = queryLong(admin,
                    "SELECT COUNT(DISTINCT id) FROM " + table + " WHERE id >= 0");
            assertEquals("lost update at scale: sum(v) must equal the number of committed "
                    + "increments. " + context, expected, actualSum);
            assertEquals("hot row count changed. " + context, HOT_ROWS, rows);
            assertEquals("duplicate/missing hot rows. " + context, rows, distinctRows);
            assertEquals("some increments failed; the invariant is only meaningful for a clean "
                    + "run. " + context, 0, failures);

            // index / table agreement for the hot column
            List<Integer> tableIds = queryInts(admin,
                    "SELECT id FROM " + table + " ORDER BY id");
            List<Integer> indexIds = queryInts(admin,
                    "SELECT id FROM " + table + " WHERE v >= 0 ORDER BY id");
            assertEquals("secondary index on v disagrees with the table scan. " + context,
                    tableIds, indexIds);
            long tableCount = queryLong(admin,
                    "SELECT COUNT(*) FROM " + table + " WHERE id >= 0");
            long indexCount = queryLong(admin, "SELECT COUNT(*) FROM " + table + " WHERE v >= 0");
            assertEquals("COUNT(*) via index disagrees with COUNT(*) via table. " + context,
                    tableCount, indexCount);
            assertEquals("index/table count must equal the hot row count. " + context, HOT_ROWS,
                    indexCount);
            long indexSum = queryLong(admin, "SELECT SUM(v) FROM " + table + " WHERE v >= 0");
            assertEquals("secondary index on v holds stale values (covering index scan before "
                    + "reopen). " + context, actualSum, indexSum);
            checkIndexPlan(admin, "SELECT COUNT(*) FROM " + table + " WHERE v >= 0");

            System.out.println("update stress (" + (autocommit ? "autocommit" : "explicit") + "): "
                    + "expected=" + expected + ", sum(v)=" + actualSum + ", rows=" + rows
                    + ", indexCount=" + indexCount + ", indexSum=" + indexSum + ", failures="
                    + failures);

            // persistence: close every connection, shut the database down, reopen it
            admin.close();
            shutdownDatabase(db);
            try (Connection reopened = openAdmin(db)) {
                long sumAfter = queryLong(reopened,
                        "SELECT SUM(v) FROM " + table + " WHERE id >= 0");
                long rowsAfter = queryLong(reopened,
                        "SELECT COUNT(*) FROM " + table + " WHERE id >= 0");
                List<Integer> idsAfter = queryInts(reopened,
                        "SELECT id FROM " + table + " ORDER BY id");
                assertEquals("sum(v) changed after reopen. " + context, actualSum, sumAfter);
                assertEquals("row count changed after reopen. " + context, rows, rowsAfter);
                assertEquals("row set changed after reopen. " + context, tableIds, idsAfter);
                assertEquals("index/table disagreement after reopen. " + context, sumAfter,
                        queryLong(reopened, "SELECT SUM(v) FROM " + table + " WHERE v >= 0"));
                System.out.println("reopen: sum(v)=" + sumAfter + ", rows=" + rowsAfter
                        + " (unchanged)");
            }
        }
    }

    // ------------------------------------------------------------------
    // page-split stressor: sequential inserts + low-end deletes + checkpoints
    // ------------------------------------------------------------------

    @Test(timeout = 180000)
    public void pageSplitStress() throws Exception {
        String db = unique("concpagesplit");
        String table = "pagesplit";
        int insertCount = 10000;
        int deleteCount = 4000;
        try (Connection admin = openAdmin(db)) {
            try (Statement s = admin.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + table);
                s.executeUpdate("CREATE TABLE " + table
                        + " (id int primary key, k int, payload varchar(200)) ENGINE "
                        + DEFAULT_STORAGE_ENGINE_NAME + " PARAMETERS(page_size='4k')");
                s.executeUpdate("CREATE INDEX idx_pagesplit_k ON " + table + "(k)");
            }

            CheckpointRunner checkpoint = new CheckpointRunner(db, false);
            AtomicLong inserted = new AtomicLong();
            AtomicLong deleted = new AtomicLong();
            AtomicReference<Throwable> inserterError = new AtomicReference<>();
            AtomicReference<Throwable> deleterError = new AtomicReference<>();

            Thread inserter = new Thread(() -> {
                try (Connection c = DriverManager.getConnection(url(db));
                        PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO " + table + "(id, k, payload) VALUES(?, ?, ?)")) {
                    setLockTimeout(c);
                    String payload = repeat('x', 120);
                    for (int id = 1; id <= insertCount; id++) {
                        ps.setInt(1, id);
                        ps.setInt(2, id);
                        ps.setString(3, payload + id);
                        if (ps.executeUpdate() != 1)
                            throw new AssertionError("insert of id=" + id + " affected != 1 row");
                        inserted.incrementAndGet();
                    }
                } catch (Throwable e) {
                    inserterError.set(e);
                }
            }, "page-inserter");
            inserter.setDaemon(true);

            Thread deleter = new Thread(() -> {
                try (Connection c = DriverManager.getConnection(url(db));
                        PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM " + table + " WHERE id = ?")) {
                    setLockTimeout(c);
                    int next = 1;
                    while (deleted.get() < deleteCount) {
                        ps.setInt(1, next);
                        int n = ps.executeUpdate();
                        if (n == 1) {
                            deleted.incrementAndGet();
                            next++;
                        } else if (inserterError.get() != null) {
                            break;
                        } else if (inserted.get() < next) {
                            // inserter has not reached this key yet
                            Thread.sleep(1);
                        } else {
                            throw new AssertionError("id=" + next
                                    + " has been inserted but DELETE affected 0 rows");
                        }
                    }
                } catch (Throwable e) {
                    deleterError.set(e);
                }
            }, "page-deleter");
            deleter.setDaemon(true);

            checkpoint.start();
            inserter.start();
            deleter.start();
            inserter.join(60000);
            if (inserter.isAlive())
                fail("inserter did not finish within 60s, inserted=" + inserted.get());
            deleter.join(60000);
            if (deleter.isAlive())
                fail("deleter did not finish within 60s, deleted=" + deleted.get());
            checkpoint.stop();
            checkpoint.checkErrors();
            if (inserterError.get() != null)
                throw new AssertionError("inserter failed: " + inserterError.get(),
                        inserterError.get());
            if (deleterError.get() != null)
                throw new AssertionError("deleter failed: " + deleterError.get(),
                        deleterError.get());

            long expected = inserted.get() - deleted.get();
            long rows = queryLong(admin, "SELECT COUNT(*) FROM " + table + " WHERE id >= 0");
            long distinctRows = queryLong(admin,
                    "SELECT COUNT(DISTINCT id) FROM " + table + " WHERE id >= 0");
            long indexCount = queryLong(admin, "SELECT COUNT(*) FROM " + table + " WHERE k >= 0");
            List<Integer> tableIds = queryInts(admin, "SELECT id FROM " + table + " ORDER BY id");
            List<Integer> indexIds = queryInts(admin,
                    "SELECT id FROM " + table + " WHERE k >= 0 ORDER BY id");
            String diff = indexDiff(tableIds, indexIds);
            String context = "inserted=" + inserted.get() + ", deleted=" + deleted.get()
                    + ", expected=" + expected + ", actual count=" + rows + ", indexCount="
                    + indexCount + ", idDiff={" + diff + "}";
            assertEquals("page-split count mismatch. " + context, expected, rows);
            assertEquals("duplicate/missing rows after page splits. " + context, rows,
                    distinctRows);
            assertEquals("secondary index/table disagreement after page splits. " + context,
                    rows, indexCount);
            assertEquals("index row set differs from table scan after page splits. " + context,
                    tableIds, indexIds);
            checkIndexPlan(admin, "SELECT COUNT(*) FROM " + table + " WHERE k >= 0");
            System.out.println("page split: " + context);
        }
    }

    // ------------------------------------------------------------------
    // workers
    // ------------------------------------------------------------------

    private static final class UpdateWorker {
        final String db;
        final String table;
        final boolean autocommit;
        final int id;
        final Thread thread;
        final AtomicLong success = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile Connection connection;

        UpdateWorker(String db, String table, boolean autocommit, int id) {
            this.db = db;
            this.table = table;
            this.autocommit = autocommit;
            this.id = id;
            this.thread = new Thread(this::run, "counter-worker-" + id);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            try (Connection c = DriverManager.getConnection(url(db));
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + table + " SET v = v + 1 WHERE id = ?")) {
                connection = c;
                setLockTimeout(c);
                c.setAutoCommit(autocommit);
                for (int i = 0; i < ITERATIONS; i++) {
                    ps.setInt(1, i % HOT_ROWS);
                    try {
                        int n = ps.executeUpdate();
                        if (n != 1)
                            throw new AssertionError("update of hot row " + (i % HOT_ROWS)
                                    + " affected " + n + " rows, expected 1");
                        if (!autocommit)
                            c.commit();
                        success.incrementAndGet();
                    } catch (SQLException e) {
                        failures.incrementAndGet();
                        if (error.compareAndSet(null, e))
                            e.printStackTrace();
                        if (!autocommit) {
                            try {
                                c.rollback();
                            } catch (SQLException ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        }
    }

    private static final class CheckpointRunner {
        final String db;
        final boolean withAnalyze;
        final Thread thread;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicLong checkpoints = new AtomicLong();
        final AtomicLong analyzes = new AtomicLong();
        final AtomicReference<Throwable> checkpointError = new AtomicReference<>();
        final AtomicReference<Throwable> analyzeError = new AtomicReference<>();

        CheckpointRunner(String db, boolean withAnalyze) {
            this.db = db;
            this.withAnalyze = withAnalyze;
            this.thread = new Thread(this::run, "checkpoint-" + db);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped.set(true);
            try {
                thread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void checkErrors() {
            if (thread.isAlive())
                fail("checkpoint thread did not stop, checkpoints=" + checkpoints.get()
                        + ", error=" + checkpointError.get());
            if (checkpointError.get() != null)
                throw new AssertionError("CHECKPOINT failed after " + checkpoints.get()
                        + " successful calls: " + checkpointError.get(), checkpointError.get());
            if (analyzeError.get() != null)
                throw new AssertionError("ANALYZE failed after " + analyzes.get()
                        + " successful calls: " + analyzeError.get(), analyzeError.get());
        }

        private void run() {
            try (Connection c = DriverManager.getConnection(url(db));
                    Statement s = c.createStatement()) {
                setLockTimeout(c);
                while (!stopped.get()) {
                    try {
                        s.executeUpdate("CHECKPOINT");
                        checkpoints.incrementAndGet();
                    } catch (Throwable e) {
                        checkpointError.compareAndSet(null, e);
                        e.printStackTrace();
                    }
                    // ANALYZE is available in this engine; run it on every tick too.
                    if (withAnalyze) {
                        try {
                            s.executeUpdate("ANALYZE");
                            analyzes.incrementAndGet();
                        } catch (Throwable e) {
                            analyzeError.compareAndSet(null, e);
                            e.printStackTrace();
                        }
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                checkpointError.compareAndSet(null, e);
                e.printStackTrace();
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The whole 8x2000 workload normally takes well under a second. If a worker
     * is still alive after 30s it is stuck inside JDBC (observed: waiting forever
     * in JdbcFuture.get after the session started waiting for a row lock), so dump
     * the stacks and fail instead of blocking the build.
     */
    private static void joinWorkers(UpdateWorker[] workers) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        boolean hung = false;
        for (UpdateWorker w : workers) {
            long left = deadline - System.currentTimeMillis();
            if (left > 0)
                w.thread.join(left);
            if (w.thread.isAlive())
                hung = true;
        }
        if (hung) {
            StringBuilder sb = new StringBuilder("concurrency worker hang (deadline 30s):");
            for (UpdateWorker w : workers) {
                sb.append("\n  worker ").append(w.id).append(" success=").append(w.success.get())
                        .append(" failures=").append(w.failures.get()).append(" alive=")
                        .append(w.thread.isAlive()).append(" error=").append(w.error.get());
            }
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces()
                    .entrySet()) {
                Thread t = e.getKey();
                if (t.getName().startsWith("counter-worker")) {
                    sb.append("\nSTACK ").append(t.getName()).append(" state=").append(t.getState());
                    for (StackTraceElement el : e.getValue())
                        sb.append("\n    at ").append(el);
                }
            }
            // Best-effort cleanup: close the wedged connections from this thread so
            // their server sessions/transactions are released even though the worker
            // threads are stuck in JdbcFuture.get().
            for (UpdateWorker w : workers) {
                Connection c = w.connection;
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            fail(sb.toString());
        }
        for (UpdateWorker w : workers) {
            if (w.error.get() != null)
                throw new AssertionError("worker " + w.id + " failed: " + w.error.get(),
                        w.error.get());
        }
    }

    private static Throwable firstWorkerError(UpdateWorker[] workers) {
        for (UpdateWorker w : workers)
            if (w.error.get() != null)
                return w.error.get();
        return null;
    }

    private static void setLockTimeout(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("SET LOCK_TIMEOUT 10000");
        }
    }

    private static String unique(String base) {
        return base + "_" + System.currentTimeMillis() + "_" + RUN_ID.incrementAndGet();
    }

    private static String url(String db) {
        return "jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=";
    }

    private static Connection openAdmin(String db) throws SQLException {
        return DriverManager.getConnection(url(db));
    }

    /**
     * Shut the database down through LealoneDatabase so that the next connection
     * really re-opens it from storage instead of reusing the live Database object.
     */
    private static void shutdownDatabase(String db) throws SQLException {
        try (Connection c = DriverManager.getConnection(url("lealone"));
                Statement s = c.createStatement()) {
            s.executeUpdate("SHUTDOWN DATABASE " + db);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private static List<Integer> queryInts(Connection c, String sql) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next())
                list.add(rs.getInt(1));
        }
        return list;
    }

    private static String indexDiff(List<Integer> tableIds, List<Integer> indexIds) {
        java.util.Set<Integer> t = new java.util.HashSet<>(tableIds);
        java.util.Set<Integer> i = new java.util.HashSet<>(indexIds);
        List<Integer> missing = new ArrayList<>();
        List<Integer> extra = new ArrayList<>();
        for (Integer id : t)
            if (!i.contains(id))
                missing.add(id);
        for (Integer id : i)
            if (!t.contains(id))
                extra.add(id);
        java.util.Collections.sort(missing);
        java.util.Collections.sort(extra);
        return "missingFromIndex=" + sample(missing) + ", extraInIndex=" + sample(extra);
    }

    private static String sample(List<Integer> list) {
        int n = Math.min(10, list.size());
        return list.subList(0, n) + (list.size() > n ? "... (" + list.size() + " total)" : "");
    }

    private static void checkIndexPlan(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
            if (rs.next())
                System.out.println("plan: " + rs.getString(1).replace('\n', ' '));
        }
    }

    private static String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            sb.append(ch);
        return sb.toString();
    }
}
