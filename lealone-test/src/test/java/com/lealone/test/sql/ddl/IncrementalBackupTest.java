/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

import com.lealone.db.LealoneDatabase;
import com.lealone.storage.BackupManifest;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

// 增量备份：manifest + 删除清单 + 链式恢复
public class IncrementalBackupTest extends TestBase {

    private static final String DB_NAME = "incbackup";

    @Test
    public void testIncrementalBackup() throws Exception {
        String dir = new File(TEST_DIR, DB_NAME).getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        Class.forName("com.lealone.client.jdbc.JdbcDriver");

        File full = new File(dir, "full.zip");
        File inc = new File(dir, "inc.zip");

        try (Connection conn = DriverManager.getConnection(url)) {
            // 大表后续不改，小表做修改，增量包应该只包含小表相关的chunk文件
            execute(conn, "create table if not exists big(id int primary key, v varchar(1000))");
            execute(conn, "insert into big select x, repeat('b', 500)||x from system_range(1, 20000)");
            execute(conn, "create table if not exists small(id int primary key, v varchar(200))");
            execute(conn, "insert into small select x, repeat('s', 100)||x from system_range(1, 100)");
            execute(conn, "CHECKPOINT");
            execute(conn, "BACKUP TO '" + full.getAbsolutePath() + "'");
            execute(conn, "update small set v = 'updated'||id where id <= 50");
            execute(conn, "delete from small where id > 90");
            execute(conn, "CHECKPOINT");
            execute(conn, "BACKUP TO '" + inc.getAbsolutePath() + "' INCREMENTAL FROM '"
                    + full.getAbsolutePath() + "'");
        }
        long fullBytes = totalBytes(full);
        long incBytes = totalBytes(inc);
        BackupManifest fullManifest = BackupManifest.read(full);
        BackupManifest incManifest = BackupManifest.read(inc);
        // 增量包必须明显小于全量包
        assertTrue("incremental backup should be much smaller: full=" + fullBytes + " inc=" + incBytes,
                incBytes * 2 < fullBytes);
        assertEquals(fullManifest.getId(), incManifest.getBaseId());
        assertTrue(incManifest.isIncremental());
        // 增量包只含变化的chunk文件
        assertTrue("incremental should contain fewer entries: full="
                + fullManifest.getEntries().size() + " inc=" + incManifest.getEntries().size(),
                incManifest.getEntries().size() < fullManifest.getEntries().size());
        for (String name : incManifest.getDeleted()) {
            assertTrue(name, fullManifest.contains(name));
        }

        // 链式恢复：先全量再增量，然后在独立进程里校验
        File isoDir = new File(TEST_DIR, DB_NAME + "_restore");
        FileUtils.deleteRecursive(isoDir.getAbsolutePath(), false);
        File storageRoot = new File(LealoneDatabase.getInstance()
                .findDatabase(DB_NAME.toUpperCase()).getStoragePath()).getParentFile();
        copyDir(new File(storageRoot, "db_0"), new File(isoDir, "db_0"));
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("RESTORE FROM '" + full.getAbsolutePath() + "' TO '"
                    + isoDir.getAbsolutePath() + "'");
            stmt.executeUpdate("RESTORE FROM '" + inc.getAbsolutePath() + "' TO '"
                    + isoDir.getAbsolutePath() + "'");
        }
        assertEquals("20000,200010000,90,4095;", queryInNewProcess(isoDir, DB_NAME,
                "select (select count(*) from big), (select sum(id) from big),"
                        + " (select count(*) from small), (select sum(id) from small)"));
        // 恢复时按删除清单删掉了旧的chunk文件
        for (String name : incManifest.getDeleted()) {
            assertFalse("outdated chunk should be deleted by restore: " + name,
                    new File(isoDir, name).exists());
        }
    }

    @Test
    public void testMassDeleteIncremental() throws Exception {
        String dir = new File(TEST_DIR, DB_NAME + "2").getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        File full = new File(dir, "full.zip");
        File inc = new File(dir, "inc.zip");
        try (Connection conn = DriverManager.getConnection(url)) {
            execute(conn, "create table if not exists t(id int primary key, v varchar(200))");
            execute(conn, "insert into t select x, repeat('x', 100)||x from system_range(1, 40000)");
            execute(conn, "CHECKPOINT");
            execute(conn, "BACKUP TO '" + full.getAbsolutePath() + "'");
            // 大批删除，让一部分旧chunk被淘汰
            execute(conn, "delete from t where id > 1000");
            execute(conn, "CHECKPOINT");
            execute(conn, "BACKUP TO '" + inc.getAbsolutePath() + "' INCREMENTAL FROM '"
                    + full.getAbsolutePath() + "'");
        }
        BackupManifest incManifest = BackupManifest.read(inc);
        assertTrue("mass delete should produce a non empty delete list", incManifest.getDeleted().size() > 0);

        File isoDir = new File(TEST_DIR, DB_NAME + "2_restore");
        FileUtils.deleteRecursive(isoDir.getAbsolutePath(), false);
        File storageRoot = new File(LealoneDatabase.getInstance()
                .findDatabase((DB_NAME + "2").toUpperCase()).getStoragePath()).getParentFile();
        copyDir(new File(storageRoot, "db_0"), new File(isoDir, "db_0"));
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("RESTORE FROM '" + full.getAbsolutePath() + "' TO '"
                    + isoDir.getAbsolutePath() + "'");
            stmt.executeUpdate("RESTORE FROM '" + inc.getAbsolutePath() + "' TO '"
                    + isoDir.getAbsolutePath() + "'");
        }
        assertEquals("1000,500500;", queryInNewProcess(isoDir, DB_NAME + "2", "select count(*), sum(id) from t"));
        for (String name : incManifest.getDeleted()) {
            assertFalse(new File(isoDir, name).exists());
        }
    }

    @Test
    public void testLastDateIsRejected() throws Exception {
        String dir = new File(TEST_DIR, DB_NAME + "3").getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        try (Connection conn = DriverManager.getConnection(url)) {
            execute(conn, "create table if not exists t(id int primary key)");
            execute(conn, "insert into t select x from system_range(1, 10)");
            try {
                execute(conn, "BACKUP TO '" + new File(dir, "ld.zip").getAbsolutePath()
                        + "' LAST DATE '2020-01-01'");
                fail("LAST DATE should not silently produce a backup package");
            } catch (Exception e) {
                assertTrue("unexpected error: " + e, e.getMessage().contains("INCREMENTAL FROM"));
            }
        }
        assertFalse("no package should be created for LAST DATE", new File(dir, "ld.zip").exists());
    }

    private static void execute(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private static long totalBytes(File zip) throws Exception {
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buff = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                int read;
                while ((read = in.read(buff)) > 0) {
                    total += read;
                }
            }
        }
        return total;
    }

    private static String queryInNewProcess(File isoDir, String dbName, String sql)
            throws Exception {
        String javaHome = System.getProperty("java.home");
        ProcessBuilder pb = new ProcessBuilder(javaHome + File.separator + "bin" + File.separator
                + "java", "-Dlealone.base.dir=" + isoDir.getAbsolutePath(), "-cp",
                System.getProperty("java.class.path"), BackupRestoreCheck.class.getName(),
                isoDir.getAbsolutePath() + "/" + dbName, sql);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        for (String line : out.split("\n")) {
            if (line.startsWith("RESULT=")) {
                return line.substring("RESULT=".length()).trim();
            }
        }
        fail("restore check failed: " + out);
        return null;
    }

    private static void copyDir(File src, File dest) throws Exception {
        FileUtils.createDirectories(dest.getAbsolutePath());
        File[] files = src.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            File to = new File(dest, f.getName());
            if (f.isDirectory()) {
                copyDir(f, to);
            } else {
                try (FileInputStream in = new FileInputStream(f);
                        FileOutputStream out = new FileOutputStream(to)) {
                    byte[] buff = new byte[8192];
                    int read;
                    while ((read = in.read(buff)) > 0) {
                        out.write(buff, 0, read);
                    }
                }
            }
        }
    }
}
