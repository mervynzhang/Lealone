/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.recovery;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import com.lealone.transaction.TransactionEngine;

/**
 * Child-JVM writer for the crash-recovery harness.
 *
 * Every scenario prints a marker and flushes stdout immediately before entering
 * the final sleep; the parent kills this JVM with SIGKILL at that point.
 */
public class CrashWriter {

    private static final String DB_NAME = "recovery";
    private static final int LOB_TOTAL = 5 * 1024 * 1024;
    private static final int LOB_BASELINE = 1024 * 1024;

    public static void main(String[] args) {
        try {
            String scenario = args[0];
            String baseDir = args[1];
            String syncType = args[2];
            int n = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
            System.setProperty("lealone.base.dir", baseDir);
            System.setProperty("java.io.tmpdir", baseDir + File.separator + "tmp");
            initEngine(baseDir, syncType);
            try (Connection conn = DriverManager.getConnection("jdbc:lealone:embed:" + DB_NAME, "root", "")) {
                conn.setAutoCommit(true);
                run(scenario, conn, n);
            }
        } catch (Throwable t) {
            System.out.println("CHILD-ERROR " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(2);
        }
    }

    private static void initEngine(String baseDir, String syncType) {
        Map<String, String> config = new HashMap<>();
        config.put("base_dir", baseDir);
        config.put("redo_log_dir", "redo_log");
        config.put("log_sync_type", syncType);
        config.put("embedded", "true");
        // Shorten the async log-sync loop so the kill does not depend on a 3s sleep.
        config.put("log_sync_service_loop_interval", "10");
        TransactionEngine te = TransactionEngine.getDefaultTransactionEngine();
        if (te == null) {
            throw new IllegalStateException("AOTE transaction engine not found");
        }
        te.init(config);
    }

    private static void run(String scenario, Connection conn, int n) throws Exception {
        switch (scenario) {
        case "commit-durability":
        case "log-sync":
            createTable(conn);
            insertAuto(conn, n);
            printSelfCount(conn);
            print("committed " + n);
            break;
        case "txn-atomicity":
            createTable(conn);
            insertAuto(conn, 100);
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE t SET v = 'UPDATED' WHERE id < 100");
            }
            print("txn-open");
            break;
        case "bulk-load":
            createTable(conn);
            try (Statement s = conn.createStatement()) {
                for (int i = 0; i < n; i++) {
                    s.executeUpdate("INSERT INTO t VALUES(" + i + ", 'v" + i + "')");
                    if ((i + 1) % 500 == 0) {
                        print("progress " + (i + 1));
                    }
                }
            }
            printSelfCount(conn);
            print("committed " + n);
            break;
        case "checkpoint":
            createTable(conn);
            // One explicit transaction: the checkpoint test is about the checkpoint,
            // not about the autocommit/index race that the commit-durability test covers.
            conn.setAutoCommit(false);
            insertAuto(conn, n);
            conn.commit();
            conn.setAutoCommit(true);
            Thread.sleep(500);
            print("inserted " + n);
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("checkpoint");
            }
            // CHECKPOINT only queues an async checkpoint. Give it time to finish so the
            // kill really happens after the checkpoint (the scenario's intent).
            Thread.sleep(1000);
            print("checkpoint-done");
            break;
        case "lob":
            runLob(conn);
            break;
        default:
            throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        Thread.sleep(60000);
    }

    private static void createTable(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS t(id int primary key, v varchar)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_t_v ON t(v)");
        }
    }

    private static void insertAuto(Connection conn, int n) throws Exception {
        // A fresh SQL string per row: reusing one PreparedStatement across autocommit
        // transactions hits "Transaction is closed" in this build.
        try (Statement s = conn.createStatement()) {
            for (int i = 0; i < n; i++) {
                s.executeUpdate("INSERT INTO t VALUES(" + i + ", 'v" + i + "')");
            }
        }
    }

    private static void runLob(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS lob_t(id int primary key, tag varchar, c clob)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_lob_tag ON lob_t(tag)");
        }
        StringBuilder small = new StringBuilder(LOB_BASELINE);
        for (int i = 0; i < LOB_BASELINE; i++) {
            small.append('A');
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO lob_t VALUES(1, 'baseline', ?)")) {
            ps.setString(1, small.toString());
            ps.executeUpdate();
        }
        print("lob-committed");

        StringBuilder big = new StringBuilder(LOB_TOTAL);
        for (int i = 0; i < LOB_TOTAL; i++) {
            big.append('B');
        }
        // The parent waits for the progress marker and SIGKILLs while the 5 MB
        // value is being written. If the insert finishes first the row must be
        // fully readable; otherwise it must be absent or complete.
        print("lob-start");
        Thread progress = new Thread(() -> {
            try {
                for (int i = 1; i <= 10; i++) {
                    Thread.sleep(50);
                    print("lob-progress " + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lob-progress");
        progress.setDaemon(true);
        progress.start();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO lob_t VALUES(2, 'inflight', ?)")) {
            ps.setString(1, big.toString());
            ps.executeUpdate();
        }
        print("lob-done");
    }

    private static void printSelfCount(Connection conn) {
        try {
            print("selfcount " + count(conn, "select count(*) from t"));
        } catch (Throwable t) {
            // COUNT(*) itself can hit an engine bug (negative transaction count);
            // it must not prevent the child from reaching its kill marker.
            print("selfcount-error " + t);
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void print(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
