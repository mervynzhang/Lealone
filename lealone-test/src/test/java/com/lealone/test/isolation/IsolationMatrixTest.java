/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.isolation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.TestBase;

/**
 * 4x4 isolation-level conformance matrix for the AOTE engine.
 *
 * Each cell uses two independent connections (A and B), each owned by its own
 * thread (an embedded Lealone connection binds the current thread to a
 * scheduler, so a connection must be created and used by the same thread).
 * The interleaving is driven by one-shot gates; every gate wait has a 5s
 * timeout and every test method has a 30s hard timeout.
 *
 * Both connections are opened with the isolation level under test,
 * autoCommit=false, and OPTIMIZE_REUSE_RESULTS=0 so that re-executing the same
 * SELECT really re-reads the table instead of reusing a cached result.
 */
public class IsolationMatrixTest extends TestBase {

    private static final int TIMEOUT_SECONDS = 30;

    // ------------------------------------------------------------------
    // dirty read: A must not see B's uncommitted write (except READ_UNCOMMITTED)
    // ------------------------------------------------------------------

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void dirtyRead_readUncommitted() throws Exception {
        dirtyRead(Connection.TRANSACTION_READ_UNCOMMITTED, "isodirty_ru");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void dirtyRead_readCommitted() throws Exception {
        dirtyRead(Connection.TRANSACTION_READ_COMMITTED, "isodirty_rc");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void dirtyRead_repeatableRead() throws Exception {
        dirtyRead(Connection.TRANSACTION_REPEATABLE_READ, "isodirty_rr");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void dirtyRead_serializable() throws Exception {
        dirtyRead(Connection.TRANSACTION_SERIALIZABLE, "isodirty_ser");
    }

    private void dirtyRead(int level, String db) throws Exception {
        String table = "t_dirty";
        try (Connection admin = openAdmin(db)) {
            initTable(admin, table, "(1, 10)");

            Gate aReadInitial = new Gate();
            Gate bUpdated = new Gate();
            Gate aReadDirty = new Gate();
            Gate bCommitted = new Gate();
            AtomicInteger dirtyValue = new AtomicInteger(Integer.MIN_VALUE);

            Actor a = new Actor("A", db, level, c -> {
                int first = queryInt(c, "SELECT v FROM " + table + " WHERE id = 1");
                assertEquals("A must see the committed initial value", 10, first);
                aReadInitial.open();
                bUpdated.await("B to finish its uncommitted UPDATE");
                dirtyValue.set(queryInt(c, "SELECT v FROM " + table + " WHERE id = 1"));
                aReadDirty.open();
                bCommitted.await("B to commit its UPDATE");
                c.rollback();
            });
            Actor b = new Actor("B", db, level, c -> {
                aReadInitial.await("A's initial read");
                update(c, "UPDATE " + table + " SET v = 20 WHERE id = 1"); // uncommitted
                bUpdated.open();
                aReadDirty.await("A to read while the write is uncommitted");
                c.commit();
                bCommitted.open();
            });

            a.start();
            b.start();
            try {
                aReadInitial.await("A to read the initial value");
            } catch (AssertionError e) {
                throw withActorErrors(e, a, b);
            }
            bUpdated.await("B to write the row without committing");
            aReadDirty.await(
                    "A to finish reading B's uncommitted value (a read hang here would be a bug)");
            bCommitted.await("B to commit");
            a.awaitDone();
            b.awaitDone();
            a.rethrow();
            b.rethrow();

            int observed = dirtyValue.get();
            if (level == Connection.TRANSACTION_READ_UNCOMMITTED) {
                // The standard permits dirty reads at READ_UNCOMMITTED. This engine
                // actually returns the uncommitted value; pin that down explicitly.
                assertEquals("READ_UNCOMMITTED is allowed to see the dirty value 20; "
                        + "if this changes, update the documented engine behaviour", 20, observed);
            } else {
                assertEquals("dirty read at " + levelName(level)
                        + ": A saw B's uncommitted value (expected the old committed value 10)",
                        10, observed);
            }
            System.out.println("dirty read @ " + levelName(level) + ": " + observed);
            dropTable(admin, table);
        }
    }

    // ------------------------------------------------------------------
    // non-repeatable read: A reads, B commits an update, A re-reads
    // ------------------------------------------------------------------

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void nonRepeatableRead_readUncommitted() throws Exception {
        nonRepeatableRead(Connection.TRANSACTION_READ_UNCOMMITTED, "isonrr_ru");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void nonRepeatableRead_readCommitted() throws Exception {
        nonRepeatableRead(Connection.TRANSACTION_READ_COMMITTED, "isonrr_rc");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void nonRepeatableRead_repeatableRead() throws Exception {
        nonRepeatableRead(Connection.TRANSACTION_REPEATABLE_READ, "isonrr_rr");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void nonRepeatableRead_serializable() throws Exception {
        nonRepeatableRead(Connection.TRANSACTION_SERIALIZABLE, "isonrr_ser");
    }

    private void nonRepeatableRead(int level, String db) throws Exception {
        String table = "t_nrr";
        try (Connection admin = openAdmin(db)) {
            initTable(admin, table, "(1, 10)");
            Gate aReadFirst = new Gate();
            Gate bCommitted = new Gate();
            Gate aReadSecond = new Gate();
            AtomicInteger secondValue = new AtomicInteger(Integer.MIN_VALUE);

            Actor a = new Actor("A", db, level, c -> {
                int first = queryInt(c, "SELECT v FROM " + table + " WHERE id = 1");
                assertEquals("first read must see 10", 10, first);
                aReadFirst.open();
                bCommitted.await("B to commit its update");
                secondValue.set(queryInt(c, "SELECT v FROM " + table + " WHERE id = 1"));
                aReadSecond.open();
                c.rollback();
            });
            Actor b = new Actor("B", db, level, c -> {
                aReadFirst.await("A's first read");
                update(c, "UPDATE " + table + " SET v = 20 WHERE id = 1");
                c.commit();
                bCommitted.open();
            });

            a.start();
            b.start();
            aReadFirst.await("A's first read");
            bCommitted.await("B's update + commit");
            aReadSecond.await("A's second read");
            a.awaitDone();
            b.awaitDone();
            a.rethrow();
            b.rethrow();

            int observed = secondValue.get();
            switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED:
            case Connection.TRANSACTION_READ_COMMITTED:
                // Allowed to change at these two levels; this engine does see the
                // new committed value, so assert exactly that.
                assertEquals(levelName(level) + " may see the new committed value; engine returned "
                        + observed, 20, observed);
                break;
            default:
                assertEquals(levelName(level) + ": non-repeatable read, A's second read changed to "
                        + observed + " (must stay 10)", 10, observed);
            }
            System.out.println("non-repeatable read @ " + levelName(level) + ": " + observed);
            dropTable(admin, table);
        }
    }

    // ------------------------------------------------------------------
    // phantom read: A counts, B inserts+commits, A counts again
    // ------------------------------------------------------------------

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void phantomRead_readUncommitted() throws Exception {
        phantomRead(Connection.TRANSACTION_READ_UNCOMMITTED, "isoph_ru");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void phantomRead_readCommitted() throws Exception {
        phantomRead(Connection.TRANSACTION_READ_COMMITTED, "isoph_rc");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void phantomRead_repeatableRead() throws Exception {
        phantomRead(Connection.TRANSACTION_REPEATABLE_READ, "isoph_rr");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void phantomRead_serializable() throws Exception {
        phantomRead(Connection.TRANSACTION_SERIALIZABLE, "isoph_ser");
    }

    private void phantomRead(int level, String db) throws Exception {
        String table = "t_phantom";
        try (Connection admin = openAdmin(db)) {
            initTable(admin, table, "(1, 10), (2, 20), (3, 30)");
            Gate aCountFirst = new Gate();
            Gate bCommitted = new Gate();
            Gate aCountSecond = new Gate();
            AtomicInteger firstCount = new AtomicInteger(-1);
            AtomicInteger secondCount = new AtomicInteger(-1);
            AtomicInteger secondScan = new AtomicInteger(-1);

            Actor a = new Actor("A", db, level, c -> {
                firstCount.set(queryInt(c, "SELECT COUNT(*) FROM " + table));
                aCountFirst.open();
                bCommitted.await("B to insert and commit a new row");
                secondCount.set(queryInt(c, "SELECT COUNT(*) FROM " + table));
                // Same transaction, same snapshot boundary: count the rows through a
                // normal scan as well. This distinguishes a genuine phantom from the
                // COUNT(*) quick-aggregate path, which reads the physical map size.
                secondScan.set(scanRowCount(c, "SELECT id FROM " + table));
                aCountSecond.open();
                c.rollback();
            });
            Actor b = new Actor("B", db, level, c -> {
                aCountFirst.await("A's first COUNT(*)");
                update(c, "INSERT INTO " + table + "(id, v) VALUES(4, 40)");
                c.commit();
                bCommitted.open();
            });

            a.start();
            b.start();
            aCountFirst.await("A's first COUNT(*)");
            bCommitted.await("B's insert + commit");
            aCountSecond.await("A's second COUNT(*)");
            a.awaitDone();
            b.awaitDone();
            a.rethrow();
            b.rethrow();

            assertEquals("first COUNT(*) must be 3", 3, firstCount.get());
            int observed = secondCount.get();
            int scanned = secondScan.get();
            switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED:
            case Connection.TRANSACTION_READ_COMMITTED:
                // Phantoms are allowed at these levels; this engine does see the
                // new row through both the aggregate and the scan, so pin that.
                assertEquals(levelName(level) + " may see a phantom, engine returned " + observed,
                        4, observed);
                assertEquals("COUNT(*) and the row scan must agree. count=" + observed
                        + ", scan=" + scanned, observed, scanned);
                break;
            case Connection.TRANSACTION_REPEATABLE_READ:
                // The SQL standard allows phantoms at REPEATABLE_READ. The row scan
                // correctly stays at 3 (snapshot), but the unqualified COUNT(*)
                // quick-aggregate path returns the physical row count 4. Record the
                // actual behaviour and flag the internal inconsistency; either 3 or
                // 4 is standard-compliant here, so do not fail this cell.
                assertEquals("the row scan must stay stable at REPEATABLE_READ", 3, scanned);
                assertTrue("REPEATABLE_READ COUNT(*) should be 3 or 4, was " + observed,
                        observed == 3 || observed == 4);
                if (observed != scanned)
                    System.out.println("WARNING: REPEATABLE_READ is internally inconsistent: "
                            + "COUNT(*)=" + observed + " but row scan=" + scanned
                            + " in the same transaction (COUNT(*) quick aggregate uses the "
                            + "physical map size)");
                break;
            default:
                // SERIALIZABLE must be stable: two identical COUNT(*) queries in one
                // transaction may not see a row inserted and committed in between.
                // The row scan is stable at 3, so the aggregate must be 3 too.
                assertEquals("SERIALIZABLE phantom: second COUNT(*) is " + observed
                        + " while the row scan returned " + scanned
                        + " (a serializable transaction must still see 3)", 3, observed);
                assertEquals("SERIALIZABLE row scan must still see 3", 3, scanned);
            }
            System.out.println("phantom read @ " + levelName(level) + ": count=" + observed
                    + ", scan=" + scanned);
            dropTable(admin, table);
        }
    }

    // ------------------------------------------------------------------
    // lost update: both read 0, both v=v+1, both commit
    // ------------------------------------------------------------------

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void lostUpdate_readUncommitted() throws Exception {
        lostUpdate(Connection.TRANSACTION_READ_UNCOMMITTED, "isolost_ru");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void lostUpdate_readCommitted() throws Exception {
        lostUpdate(Connection.TRANSACTION_READ_COMMITTED, "isolost_rc");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void lostUpdate_repeatableRead() throws Exception {
        lostUpdate(Connection.TRANSACTION_REPEATABLE_READ, "isolost_rr");
    }

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void lostUpdate_serializable() throws Exception {
        lostUpdate(Connection.TRANSACTION_SERIALIZABLE, "isolost_ser");
    }

    private void lostUpdate(int level, String db) throws Exception {
        String table = "t_lost";
        try (Connection admin = openAdmin(db)) {
            initTable(admin, table, "(1, 0)");
            Gate aSelected = new Gate();
            Gate bSelected = new Gate();
            Gate aUpdated = new Gate();
            Gate bUpdateStarted = new Gate();
            Gate bUpdateDone = new Gate();
            Gate aMayCommit = new Gate();
            Gate aCommitted = new Gate();
            AtomicInteger aRead = new AtomicInteger(-1);
            AtomicInteger bRead = new AtomicInteger(-1);
            AtomicReference<Throwable> bUpdateError = new AtomicReference<>();

            Actor a = new Actor("A", db, level, c -> {
                aRead.set(queryInt(c, "SELECT v FROM " + table + " WHERE id = 1"));
                aSelected.open();
                bSelected.await("B's read");
                update(c, "UPDATE " + table + " SET v = v + 1 WHERE id = 1");
                aUpdated.open();
                aMayCommit.await("main thread to release A's commit after observing B blocked");
                c.commit();
                aCommitted.open();
            });
            Actor b = new Actor("B", db, level, c -> {
                bRead.set(queryInt(c, "SELECT v FROM " + table + " WHERE id = 1"));
                bSelected.open();
                aSelected.await("A's read");
                aUpdated.await("A's uncommitted UPDATE");
                bUpdateStarted.open();
                try {
                    // Second writer: must block on A's row lock (or fail with a
                    // lock error), it must not silently overwrite from stale data.
                    update(c, "UPDATE " + table + " SET v = v + 1 WHERE id = 1");
                    bUpdateDone.open();
                    aCommitted.await("A's commit");
                    c.commit();
                } catch (Throwable e) {
                    bUpdateError.set(e);
                    bUpdateDone.open();
                    try {
                        c.rollback();
                    } catch (Throwable ignored) {
                    }
                }
            });

            a.start();
            b.start();
            aSelected.await("A's read");
            bSelected.await("B's read");
            aUpdated.await("A's UPDATE");
            bUpdateStarted.await("B to start its UPDATE");
            // Observation window (not a synchronization point): while A holds the
            // row lock, B's UPDATE must not complete. If it completes, B wrote
            // without waiting for A, i.e. the row lock conflict was not real.
            boolean bFinished = bUpdateDone.await(300, TimeUnit.MILLISECONDS);
            aMayCommit.open();
            aCommitted.await("A's commit");
            bUpdateDone.await("B's UPDATE to finish (it should wake up after A commits)");
            a.awaitDone();
            b.awaitDone();
            a.rethrow();
            // If B failed the connection is rolled back; otherwise it committed, so
            // only a lock/timeout error is acceptable here.
            if (bUpdateError.get() != null) {
                assertTrue("B failed with a non-lock error: " + bUpdateError.get(),
                        isLockError(bUpdateError.get()));
            }
            b.rethrow();

            int finalValue = queryInt(admin, "SELECT v FROM " + table + " WHERE id = 1");
            System.out.println("lost update @ " + levelName(level) + ": final=" + finalValue
                    + ", A read=" + aRead.get() + ", B read=" + bRead.get()
                    + ", B blocked=" + !bFinished + ", B error=" + bUpdateError.get());
            assertEquals("both writers must have read the initial value 0", 0, aRead.get());
            assertEquals("both writers must have read the initial value 0", 0, bRead.get());
            assertTrue(levelName(level) + " must not silently lose an update: final=" + finalValue
                    + ", B blocked=" + !bFinished + ", B error=" + bUpdateError.get(),
                    finalValue == 2 || (finalValue == 1 && isLockError(bUpdateError.get())));
            assertFalse("second writer completed its UPDATE while A still held the row lock; "
                    + "the conflict was not enforced", bFinished && bUpdateError.get() == null);
            dropTable(admin, table);
        }
    }

    // ------------------------------------------------------------------
    // infrastructure
    // ------------------------------------------------------------------

    private interface Script {
        void run(Connection c) throws Exception;
    }

    /** A thread that owns exactly one JDBC connection for the whole scenario. */
    private static final class Actor {
        final String name;
        final String db;
        final int level;
        final Script script;
        final Thread thread;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        Actor(String name, String db, int level, Script script) {
            this.name = name;
            this.db = db;
            this.level = level;
            this.script = script;
            this.thread = new Thread(this::run, "isolation-" + name);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            try (Connection c = open(db, level)) {
                script.run(c);
            } catch (Throwable e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        }

        void awaitDone() {
            try {
                if (!done.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("TIMEOUT (5s): actor " + name + " did not finish");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for actor " + name);
            }
        }

        void rethrow() {
            Throwable e = error.get();
            if (e == null)
                return;
            if (e instanceof AssertionError)
                throw (AssertionError) e;
            throw new AssertionError("actor " + name + " failed: " + e, e);
        }
    }

    private static AssertionError withActorErrors(AssertionError e, Actor... actors) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        for (Actor a : actors) {
            Throwable t = a.error.get();
            if (t != null)
                sb.append("\n  [actor ").append(a.name).append(" failed: ").append(t).append(']');
        }
        return new AssertionError(sb.toString(), e);
    }

    private static Connection open(String db, int level) throws SQLException {
        Connection c = DriverManager.getConnection(url(db));
        try (Statement s = c.createStatement()) {
            s.executeUpdate("SET OPTIMIZE_REUSE_RESULTS 0");
            // Generous lock timeout so the lost-update test observes blocking
            // rather than an artificial timeout; the test commits A long before
            // this expires.
            s.executeUpdate("SET LOCK_TIMEOUT 5000");
        }
        c.setTransactionIsolation(level);
        c.setAutoCommit(false);
        return c;
    }

    private static Connection openAdmin(String db) throws SQLException {
        return DriverManager.getConnection(url(db));
    }

    private static String url(String db) {
        // Embedded only. The database stays open for the JVM lifetime; each test
        // method drops its table in cleanup. (DB_CLOSE_DELAY=0 is deliberately not
        // used: closing a brand-new database from the GC thread races with its
        // first session and makes openDatabase() fail with "object already exists".)
        return "jdbc:lealone:embed:" + joinDirs(db) + ";user=root;password=";
    }

    private static void initTable(Connection admin, String table, String values)
            throws SQLException {
        try (Statement s = admin.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS " + table);
            s.executeUpdate("CREATE TABLE " + table + " (id int primary key, v int)");
            s.executeUpdate("INSERT INTO " + table + "(id, v) VALUES " + values);
        }
    }

    private static void dropTable(Connection admin, String table) {
        try (Statement s = admin.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS " + table);
        } catch (SQLException e) {
            System.out.println("cleanup: cannot drop " + table + ": " + e);
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("query returned no row: " + sql);
            return rs.getInt(1);
        }
    }

    private static int scanRowCount(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = 0;
            while (rs.next())
                n++;
            return n;
        }
    }

    // ------------------------------------------------------------------
    // lock timeout: a waiter must fail after LOCK_TIMEOUT instead of waiting forever
    // ------------------------------------------------------------------

    @Test(timeout = TIMEOUT_SECONDS * 1000L)
    public void lockTimeout_blocksAtMostLockTimeout() throws Exception {
        String db = "isolock";
        String table = "t_lock";
        try (Connection admin = openAdmin(db)) {
            initTable(admin, table, "(1, 0)");
            try (Connection holder = openAdmin(db)) {
                holder.setAutoCommit(false);
                update(holder, "UPDATE " + table + " SET v = v + 1 WHERE id = 1");
                long start = System.currentTimeMillis();
                try (Connection waiter = openAdmin(db)) {
                    update(waiter, "UPDATE " + table + " SET v = v + 1 WHERE id = 1");
                    fail("the second writer must not succeed while the row lock is held");
                } catch (SQLException e) {
                    long waited = System.currentTimeMillis() - start;
                    assertTrue("expected LOCK_TIMEOUT but was: " + e,
                            e.getMessage() != null && e.getMessage().contains("Timeout trying to lock"));
                    assertTrue("must give up after about LOCK_TIMEOUT, waited " + waited + " ms",
                            waited >= 1000 && waited < 15000);
                    System.out.println("lock timeout after " + waited + " ms: " + e.getMessage());
                } finally {
                    holder.rollback();
                }
            }
            dropTable(admin, table);
        }
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static String levelName(int level) {
        switch (level) {
        case Connection.TRANSACTION_READ_UNCOMMITTED:
            return "READ_UNCOMMITTED";
        case Connection.TRANSACTION_READ_COMMITTED:
            return "READ_COMMITTED";
        case Connection.TRANSACTION_REPEATABLE_READ:
            return "REPEATABLE_READ";
        case Connection.TRANSACTION_SERIALIZABLE:
            return "SERIALIZABLE";
        default:
            return "UNKNOWN(" + level + ")";
        }
    }

    private static boolean isLockError(Throwable e) {
        while (e != null) {
            if (e instanceof SQLException) {
                int code = ((SQLException) e).getErrorCode();
                if (code == ErrorCode.LOCK_TIMEOUT_1 || code == ErrorCode.DEADLOCK_1)
                    return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private static final class Gate {
        private final CountDownLatch latch = new CountDownLatch(1);

        void open() {
            latch.countDown();
        }

        void await(String what) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("TIMEOUT (5s): waiting for " + what);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }

        boolean await(long millis, TimeUnit unit) {
            try {
                return latch.await(millis, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while observing a gate");
            }
        }
    }
}
