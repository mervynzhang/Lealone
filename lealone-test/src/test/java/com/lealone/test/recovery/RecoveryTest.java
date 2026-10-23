/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.lealone.db.LealoneDatabase;

/**
 * Crash-recovery tests: a child JVM is SIGKILLed at a scripted point, then this
 * JVM reopens the same base directory and checks the surviving state.
 */
public class RecoveryTest {

    @Before
    public void requireExplicitRun() {
        // 这些测试会重启JVM并依赖全局状态(系统库的用户表)，和其它测试同JVM跑会互相污染，所以默认跳过
        Assume.assumeTrue("用 -Drecovery.run=true 显式运行崩溃恢复测试", Boolean.getBoolean("recovery.run"));
    }

    private static final String URL = "jdbc:lealone:embed:recovery";
    private static final File RECOVERY_ROOT = new File("target/recovery").getAbsoluteFile();
    private static boolean lealoneInitialized;

    @After
    public void closeDatabases() {
        if (lealoneInitialized) {
            try {
                LealoneDatabase.getInstance().closeAllDatabases(true);
            } catch (Throwable t) {
                // the test already produced its own assertion failure, if any
            }
        }
    }

    @Ignore("已知bug: kill -9之后，已经返回成功的自动提交记录会丢失(instant模式也会丢)")
    @Test
    public void testCommitDurability() throws Exception {
        File dir = newDir("commit-durability");
        ChildResult r = runChild("commit-durability", dir, "instant", "committed 100", 100);
        try (Connection conn = open(dir)) {
            List<Integer> tableIds = ids(conn, "select id from t");
            List<Integer> indexIds = ids(conn, "select id from t where v >= ''");
            System.out.println("RESULT commit-durability: childSelf=" + r.selfCount() + ", table="
                    + tableIds.size() + ", index=" + indexIds.size());
            assertEquals("secondary index count != table count", tableIds.size(), indexIds.size());
            assertEquals("all 100 acknowledged rows must survive kill -9 (instant sync)", 100,
                    tableIds.size());
        }
    }

    @Ignore("已知bug: 同上，崩溃后已提交的基线记录会丢失")
    @Test
    public void testTxnAtomicity() throws Exception {
        File dir = newDir("txn-atomicity");
        runChild("txn-atomicity", dir, "instant", "txn-open", 100);
        try (Connection conn = open(dir)) {
            int table = ids(conn, "select id from t").size();
            int index = ids(conn, "select id from t where v >= ''").size();
            int updated = ids(conn, "select id from t where v = 'UPDATED'").size();
            System.out.println("RESULT txn-atomicity: table=" + table + ", index=" + index
                    + ", updated=" + updated);
            assertEquals("secondary index count != table count", table, index);
            assertEquals("baseline rows", 100, table);
            assertEquals("uncommitted updates must not be visible", 0, updated);
        }
    }

    @Ignore("已知bug: 崩溃恢复会丢失已提交记录，子进程提交路径还会抛undoLog为null的NPE")
    @Test
    public void testBulkLoad() throws Exception {
        File dir = newDir("bulk-load");
        int n = 20000;
        int target = 5000 + new Random().nextInt(10) * 500; // 5000..9500
        ChildResult r = runChild("bulk-load", dir, "instant", "progress " + target, n);
        int acked = r.lastProgress();
        assertTrue("did not see progress marker, last=" + acked, acked >= target);
        try (Connection conn = open(dir)) {
            List<Integer> tableIds = ids(conn, "select id from t");
            Set<Integer> tableSet = new HashSet<>(tableIds);
            List<Integer> indexIds = ids(conn, "select id from t where v >= ''");
            Set<Integer> indexSet = new HashSet<>(indexIds);
            System.out.println("RESULT bulk-load: acked=" + acked + ", table=" + tableIds.size()
                    + ", index=" + indexIds.size() + ", tableSet=" + tableSet.size() + ", indexSet=" + indexSet.size());
            assertEquals("duplicate primary keys", tableIds.size(), tableSet.size());
            for (int i = 0; i < acked; i++) {
                assertTrue("acknowledged row " + i + " is missing after kill -9", tableSet.contains(i));
            }
            assertEquals("secondary index count != table count", tableIds.size(), indexIds.size());
            assertEquals("secondary index entries != table rows", tableSet, indexSet);
            for (int id : indexSet) {
                assertTrue("index entry " + id + " is outside the workload", id >= 0 && id < n);
            }
        }
    }

    @Test
    public void testAfterCheckpoint() throws Exception {
        File dir = newDir("after-checkpoint");
        runChild("checkpoint", dir, "instant", "checkpoint-done", 1000);
        try (Connection conn = open(dir)) {
            int table = ids(conn, "select id from t").size();
            System.out.println("RESULT after-checkpoint: table=" + table);
            assertEquals("rows must survive a kill after checkpoint", 1000, table);
            assertIndexConsistent(conn);
        }
    }

    @Ignore("测试自身限制: 子进程和父进程共用JVM级的base dir，跟其它测试一起跑时会互相影响(table not found)")
    @Test
    public void testLobWrite() throws Exception {
        File dir = newDir("lob-write");
        runChild("lob", dir, "instant", "lob-progress 2", 0);
        try (Connection conn = open(dir)) {
            assertEquals("committed baseline row", 1, ids(conn, "select id from lob_t where id = 1").size());
            String baseline = readClob(conn, 1);
            assertEquals("committed LOB length", 1024 * 1024, baseline.length());
            assertAll(baseline, 'A');

            int inFlight = ids(conn, "select id from lob_t where id = 2").size();
            System.out.println("RESULT lob-write: baseline=" + baseline.length() + ", inflightRow=" + inFlight);
            if (inFlight == 1) {
                String lob = readClob(conn, 2); // must not throw / must not be short
                assertEquals("in-flight LOB length", 5 * 1024 * 1024, lob.length());
                assertAll(lob, 'B');
            }

            List<Integer> tableIds = ids(conn, "select id from lob_t");
            List<Integer> indexIds = ids(conn, "select id from lob_t where tag >= ''");
            assertEquals("LOB table/index count mismatch", tableIds.size(), indexIds.size());
            assertEquals("LOB table/index ids mismatch", new HashSet<>(tableIds),
                    new HashSet<>(indexIds));
        }
    }

    @Test
    public void testLogSyncPeriodic() throws Exception {
        File dir = newDir("log-sync-periodic");
        runChild("log-sync", dir, "periodic", "committed 1000", 1000);
        try (Connection conn = open(dir)) {
            List<Integer> tableIds = ids(conn, "select id from t");
            Set<Integer> tableSet = new HashSet<>(tableIds);
            int max = -1;
            for (int id : tableSet) {
                assertTrue("id out of range: " + id, id >= 0 && id < 1000);
                max = Math.max(max, id);
            }
            List<Integer> indexIds = ids(conn, "select id from t where v >= ''");
            Set<Integer> indexSet = new HashSet<>(indexIds);
            System.out.println("RESULT log-sync-periodic: table=" + tableIds.size() + ", max=" + max
                    + ", index=" + indexIds.size());
            assertEquals("duplicate primary keys", tableIds.size(), tableSet.size());
            assertEquals("periodic visible rows must be a prefix (no holes)", max + 1, tableSet.size());
            for (int i = 0; i <= max; i++) {
                assertTrue("prefix gap at " + i, tableSet.contains(i));
            }
            assertEquals("secondary index count != table count", tableIds.size(), indexIds.size());
            assertEquals("secondary index entries != table rows", tableSet, indexSet);
        }
    }

    @Ignore("已知bug: instant模式仍然会丢已提交记录，异步提交路径还会抛transaction为null的NPE")
    @Test
    public void testLogSyncInstant() throws Exception {
        File dir = newDir("log-sync-instant");
        ChildResult r = runChild("log-sync", dir, "instant", "committed 100", 100);
        try (Connection conn = open(dir)) {
            int table = ids(conn, "select id from t").size();
            int index = ids(conn, "select id from t where v >= ''").size();
            System.out.println("RESULT log-sync-instant: childSelf=" + r.selfCount() + ", table="
                    + table + ", index=" + index);
            assertEquals("secondary index count != table count", table, index);
            assertEquals("instant sync must not lose acknowledged rows", 100, table);
        }
    }

    // ------------------------------------------------------------------

    private static void assertIndexConsistent(Connection conn) throws SQLException {
        List<Integer> tableIds = ids(conn, "select id from t");
        List<Integer> indexIds = ids(conn, "select id from t where v >= ''");
        Set<Integer> tableSet = new HashSet<>(tableIds);
        Set<Integer> indexSet = new HashSet<>(indexIds);
        assertEquals("secondary index count != table count", tableIds.size(), indexIds.size());
        assertEquals("duplicate primary keys", tableIds.size(), tableSet.size());
        assertEquals("secondary index entries != table rows", tableSet, indexSet);
    }

    private static void assertAll(String s, char expected) {
        for (int i = 0; i < s.length(); i += 65536) {
            assertEquals("LOB content at " + i, expected, s.charAt(i));
        }
    }

    private static String readClob(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("select c from lob_t where id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue("row " + id + " disappeared", rs.next());
                try {
                    return rs.getString(1);
                } catch (SQLException e) {
                    throw new SQLException("half-readable LOB for row " + id + ": " + e, e);
                }
            }
        }
    }

    private static int count(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue("no result for " + sql, rs.next());
            return rs.getInt(1);
        }
    }

    private static List<Integer> ids(Connection conn, String sql) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                list.add(rs.getInt(1));
            }
        }
        return list;
    }

    private static Connection open(File dir) throws SQLException {
        System.setProperty("lealone.base.dir", dir.getAbsolutePath());
        System.setProperty("java.io.tmpdir", new File(dir, "tmp").getAbsolutePath());
        if (lealoneInitialized) {
            try {
                LealoneDatabase.getInstance().closeAllDatabases(true);
            } catch (Throwable t) {
                // ignore and try to re-anchor below
            }
            // LealoneDatabase and the AOTE engine are JVM-global and keep the base dir
            // used by the first connection. Re-anchor the system database at this
            // scenario's directory before opening its (crashed) store.
            LealoneDatabase.getInstance().copy();
        }
        Connection conn = DriverManager.getConnection(URL, "root", "");
        lealoneInitialized = true;
        return conn;
    }

    private static File newDir(String name) throws IOException {
        String rootPath = RECOVERY_ROOT.getCanonicalPath();
        File dir = new File(RECOVERY_ROOT, name);
        String dirPath = dir.getCanonicalPath();
        if (!dirPath.startsWith(rootPath + File.separator)) {
            throw new IOException("refusing to delete outside " + rootPath + ": " + dirPath);
        }
        if (dir.exists()) {
            Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                }
            });
        }
        if (!dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        new File(dir, "tmp").mkdirs();
        return dir;
    }

    private static ChildResult runChild(String scenario, File dir, String syncType, String marker,
            int n) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Xmx640m");
        cmd.add("-Dlealone.base.dir=" + dir.getAbsolutePath());
        cmd.add("-Djava.io.tmpdir=" + new File(dir, "tmp").getAbsolutePath());
        cmd.add("-cp");
        cmd.add(childClasspath());
        cmd.add(CrashWriter.class.getName());
        cmd.add(scenario);
        cmd.add(dir.getAbsolutePath());
        cmd.add(syncType);
        cmd.add(Integer.toString(n));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                // process was killed
            }
        }, "crash-writer-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            boolean found = false;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                if (contains(lines, marker)) {
                    found = true;
                    break;
                }
                if (!p.isAlive()) {
                    break;
                }
                Thread.sleep(10);
            }
            if (!found) {
                throw new AssertionError("child '" + scenario + "' did not print [" + marker
                        + "] within 10s. Output:\n" + tail(lines));
            }
            int lastProgress = -1;
            synchronized (lines) {
                for (String line : lines) {
                    if (line.startsWith("progress ")) {
                        lastProgress = Integer.parseInt(line.substring("progress ".length()).trim());
                    }
                }
            }
            kill(p);
            return new ChildResult(new ArrayList<>(lines), lastProgress);
        } finally {
            kill(p);
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reader.join(2000);
        }
    }

    private static boolean contains(List<String> lines, String marker) {
        synchronized (lines) {
            for (String line : lines) {
                if (line.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String tail(List<String> lines) {
        synchronized (lines) {
            int from = Math.max(0, lines.size() - 40);
            StringBuilder b = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                b.append(lines.get(i)).append('\n');
            }
            return b.toString();
        }
    }

    private static void kill(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroyForcibly();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                Process killer = Runtime.getRuntime()
                        .exec(new String[] { "kill", "-9", Long.toString(p.pid()) });
                killer.waitFor(2, TimeUnit.SECONDS);
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // best effort
        }
    }

    private static String javaBinary() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    private static String childClasspath() {
        String cp = System.getProperty("surefire.test.class.path");
        String jcp = System.getProperty("java.class.path", "");
        if (cp == null || cp.isEmpty()) {
            cp = jcp;
        } else if (!jcp.isEmpty() && !cp.contains(jcp)) {
            cp = cp + File.pathSeparator + jcp;
        }
        File testClasses = new File("target/test-classes").getAbsoluteFile();
        if (!cp.contains(testClasses.getPath())) {
            cp = testClasses.getPath() + File.pathSeparator + cp;
        }
        File classes = new File("target/classes").getAbsoluteFile();
        if (!cp.contains(classes.getPath())) {
            cp = classes.getPath() + File.pathSeparator + cp;
        }
        return cp;
    }

    private static final class ChildResult {
        private final List<String> lines;
        private final int lastProgress;

        ChildResult(List<String> lines, int lastProgress) {
            this.lines = lines;
            this.lastProgress = lastProgress;
        }

        int lastProgress() {
            return lastProgress;
        }

        int selfCount() {
            synchronized (lines) {
                for (String line : lines) {
                    if (line.startsWith("selfcount ")) {
                        return Integer.parseInt(line.substring("selfcount ".length()).trim());
                    }
                }
            }
            return -1;
        }
    }
}
