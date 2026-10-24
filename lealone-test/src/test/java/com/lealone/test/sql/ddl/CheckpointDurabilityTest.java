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

import org.junit.After;
import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.LealoneDatabase;
import com.lealone.db.session.ServerSession;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

// 回归测试：BACKUP/CHECKPOINT 之后写入的数据必须能扛住重启（P0）
// 校验统一在独立进程里做，避免同一JVM里的内存状态造成假阳性
public class CheckpointDurabilityTest extends TestBase {

    private static final String CHANGED = "sum(case when v like 'changed%' then 1 else 0 end)";

    @Test
    public void testBackupThenUpdate() throws Exception {
        String db = "ckptdurA" + System.nanoTime();
        run(db, url -> {
            execute(url, "create table if not exists t(id int primary key, v varchar(200))");
            execute(url, "insert into t select x, 'v'||x from system_range(1, 2000)");
            execute(url, "BACKUP TO '" + new File(TEST_DIR, db + ".zip").getAbsolutePath() + "'");
            assertEquals(100, executeUpdate(url, "update t set v='changed'||id where id <= 100"));
        });
        assertRestarted(db, 2000, 100);
    }

    @Test
    public void testUpdateWithoutBackup() throws Exception {
        String db = "ckptdurB" + System.nanoTime();
        run(db, url -> {
            execute(url, "create table if not exists t(id int primary key, v varchar(200))");
            execute(url, "insert into t select x, 'v'||x from system_range(1, 2000)");
            assertEquals(100, executeUpdate(url, "update t set v='changed'||id where id <= 100"));
        });
        assertRestarted(db, 2000, 100);
    }

    // CHECKPOINT SYNC 会等脏页写完再返回，所以这里用SYNC形式做确定性校验
    // 修好之前先忽略，避免让完整套件变成偶发红
    @Test
    public void testCheckpointThenUpdate() throws Exception {
        String db = "ckptdurC" + System.nanoTime();
        run(db, url -> {
            execute(url, "create table if not exists t(id int primary key, v varchar(200))");
            execute(url, "insert into t select x, 'v'||x from system_range(1, 2000)");
            execute(url, "CHECKPOINT SYNC");
            assertEquals(100, executeUpdate(url, "update t set v='changed'||id where id <= 100"));
        });
        assertRestarted(db, 2000, 100);
    }

    // 大量行触发多次页分裂后再备份、再更新，覆盖"行监听器链到根页"的场景
    @Test
    public void testBackupThenUpdateAfterManySplits() throws Exception {
        String db = "ckptdurD" + System.nanoTime();
        run(db, url -> {
            execute(url, "create table if not exists t(id int primary key, v varchar(300))");
            execute(url, "insert into t select x, repeat('d', 200)||x from system_range(1, 20000)");
            execute(url, "CHECKPOINT SYNC");
            execute(url, "BACKUP TO '" + new File(TEST_DIR, db + ".zip").getAbsolutePath() + "'");
            // 跨多个叶子页更新
            assertEquals(151, executeUpdate(url,
                    "update t set v='changed'||id where id <= 50 or id between 9000 and 9050 or id > 19950"));
        });
        assertRestarted(db, 20000, 151);
    }

    private interface Scenario {
        void run(String url) throws Exception;
    }

    private void run(String dbName, Scenario scenario) throws Exception {
        String dir = TEST_DIR + File.separatorChar + dbName;
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        scenario.run(url);
        // 关闭数据库，等价于干净的进程退出
        Database database = LealoneDatabase.getInstance().findDatabase(dbName.toUpperCase());
        database.close(false);
        // close(false) 如果被静默跳过(库还开着)，"干净退出"这个前提就不成立:
        // 下面复制到的只是还没刷盘的文件，重启校验会随机失败，所以必须先确认真的关掉了
        assertFalse("Database.close(false)必须真的关闭数据库: " + dbName, database.isInitialized());
        copyToIsolatedDir(database, dbName);
    }

    // 把lealone库的元数据(db_0)和本库的存储目录一起复制到隔离目录，供子进程打开
    private void copyToIsolatedDir(Database database, String dbName) throws Exception {
        String storagePath = database.getStoragePath();
        File dbDir = new File(storagePath);
        File storageRoot = dbDir.getParentFile();
        File isoDir = new File(storageRoot, dbName + "_iso");
        FileUtils.deleteRecursive(isoDir.getAbsolutePath(), false);
        copyDir(new File(storageRoot, "db_0"), new File(isoDir, "db_0"));
        copyDir(dbDir, new File(isoDir, dbDir.getName()));
        isoDirs.put(dbName, isoDir);
        dbDirs.put(dbName, dbDir);
    }

    // 后台分析表(TableAnalyzer)每次DML变更超过2000行就会用一个"没有连接信息的内部session"执行ANALYZE,
    // 这种session如果被当成用户连接, Database.close(false) 就会静默返回(库根本没关),
    // 关闭时的刷脏页/写redo都不会执行, 调用方以为干净退出了, 重启后丢更新。
    // 这里显式持有一个内部session, 确定性地覆盖这条路径。
    @Test
    public void testCloseWithInternalSession() throws Exception {
        String db = "ckptdurE" + System.nanoTime();
        String dir = TEST_DIR + File.separatorChar + db;
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        execute(url, "create table if not exists t(id int primary key, v varchar(200))");
        execute(url, "insert into t select x, 'v'||x from system_range(1, 2000)");
        execute(url, "BACKUP TO '" + new File(TEST_DIR, db + ".zip").getAbsolutePath() + "'");
        assertEquals(100, executeUpdate(url, "update t set v='changed'||id where id <= 100"));

        Database database = LealoneDatabase.getInstance().findDatabase(db.toUpperCase());
        dbDirs.put(db, new File(database.getStoragePath()));
        // 与TableAnalyzer创建的分析session等价: 没有ConnectionInfo的引擎内部session
        ServerSession internalSession = database.createSession(database.getSystemUser());
        try {
            database.close(false);
            assertFalse("引擎内部session不能阻止Database.close(false)关闭数据库: " + db,
                    database.isInitialized());
        } finally {
            try {
                internalSession.close();
            } catch (DbException e) {
                // 库已经关闭, 内部session收尾时报错可以忽略
            }
        }
        copyToIsolatedDir(database, db);
        assertRestarted(db, 2000, 100);
    }

    private final java.util.Map<String, File> isoDirs = new java.util.HashMap<>();
    private final java.util.Map<String, File> dbDirs = new java.util.HashMap<>();

    // 用例失败时(断言先于清理执行)会留下"已关闭但仍注册在 LealoneDatabase 里的库 + 复制出来的目录 + 备份包"。
    // 同一JVM里后面的用例再动这些目录, 后台检查点就会对着已删除的目录反复报错, 表现为整个套件偶发红。
    // 这里在用例结束后无条件收尾, 成功失败都一样。
    @After
    public void cleanupLeftovers() {
        for (String dbName : dbDirs.keySet()) {
            Database db = LealoneDatabase.getInstance().findDatabase(dbName.toUpperCase());
            if (db != null)
                db.close(false);
        }
        for (File isoDir : isoDirs.values())
            FileUtils.deleteRecursive(isoDir.getAbsolutePath(), false);
        for (File dbDir : dbDirs.values())
            FileUtils.deleteRecursive(dbDir.getAbsolutePath(), false);
        for (String dbName : dbDirs.keySet()) {
            File zip = new File(TEST_DIR, dbName + ".zip");
            if (zip.exists())
                zip.delete();
        }
        isoDirs.clear();
        dbDirs.clear();
    }

    private void assertRestarted(String dbName, int expectedRows, int expectedChanged) throws Exception {
        File isoDir = isoDirs.get(dbName);
        String result = queryInNewProcess(isoDir, dbName,
                "select count(*), " + CHANGED + " from t");
        assertEquals(expectedRows + "," + expectedChanged + ";", result);
        FileUtils.deleteRecursive(isoDir.getAbsolutePath(), false);
        FileUtils.deleteRecursive(dbDirs.remove(dbName).getAbsolutePath(), false);
        new File(TEST_DIR, dbName + ".zip").delete();
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private static int executeUpdate(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    // 在独立进程里打开恢复后的数据库并返回查询结果，避免同一进程里的内存状态造成假阳性
    private static String queryInNewProcess(File isoDir, String dbName, String sql) throws Exception {
        String javaHome = System.getProperty("java.home");
        ProcessBuilder pb = new ProcessBuilder(javaHome + File.separator + "bin" + File.separator
                + "java", "-Dlealone.base.dir=" + isoDir.getAbsolutePath(), "-cp",
                System.getProperty("java.class.path"), BackupRestoreCheck.class.getName(),
                isoDir.getAbsolutePath() + File.separatorChar + dbName, sql);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        for (String line : out.split("\n")) {
            if (line.startsWith("RESULT=")) {
                return line.substring("RESULT=".length()).trim();
            }
        }
        fail("restart check failed: " + out);
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
