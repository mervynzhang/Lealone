/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.io.File;
import java.io.FileInputStream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

// 回归测试 github issue #282: 同一进程内第二次BACKUP TO会静默产生空备份包
public class BackupTest extends TestBase {

    private static final String DB_NAME = "backup";

    @Test
    public void testBackup() throws Exception {
        String dir = new File(TEST_DIR, "backup").getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        Class.forName("com.lealone.client.jdbc.JdbcDriver");

        File zip1 = new File(dir + "/backup1.zip");
        File zip2 = new File(dir + "/backup2.zip");
        File zip3 = new File(dir + "/backup3.zip");

        try (Connection conn = DriverManager.getConnection(url)) {
            execute(conn, "create table if not exists backup_test(id int primary key, v varchar(100))");
            execute(conn, "insert into backup_test select x, 'v'||x from system_range(1, 100)");
            assertEquals(100, queryCount(conn, "select count(*) from backup_test"));
            execute(conn, "BACKUP TO '" + zip1.getAbsolutePath() + "'");
            // 同一进程内第二次备份，修复前这里是0字节的包
            execute(conn, "BACKUP TO '" + zip2.getAbsolutePath() + "'");

            // 第一次备份后再写入50行并checkpoint，第二次备份要包含新数据，且不能毒化下一次备份
            execute(conn, "insert into backup_test select x, 'v'||x from system_range(101, 150)");
            execute(conn, "CHECKPOINT");
            execute(conn, "BACKUP TO '" + zip3.getAbsolutePath() + "'");
        }


        Map<String, Long> entries1 = readZip(zip1);
        Map<String, Long> entries2 = readZip(zip2);
        Map<String, Long> entries3 = readZip(zip3);
        // 两次备份的条目数相同、条目名相同，且总字节数都大于0
        assertEquals(entries1.size(), entries2.size());
        assertEquals(sortedNames(entries1), sortedNames(entries2));
        assertTrue(totalBytes(entries1) > 0);
        assertTrue("第二次备份不能是空包", totalBytes(entries2) > 0);
        assertEquals(totalBytes(entries1), totalBytes(entries2));
        // 第三次备份包含新写入的数据，应该比前两次大
        assertTrue(totalBytes(entries3) > totalBytes(entries1));

        // 每个条目都不能是0字节
        for (Map.Entry<String, Long> e : entries2.entrySet()) {
            assertTrue("备份条目不能是空文件: " + e.getKey(), e.getValue() > 0);
        }
    }

    private static void execute(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private static int queryCount(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // 真正读条目内容并累计字节数，ZipEntry.getSize()对流式写入的条目返回-1
    private static Map<String, Long> readZip(File zip) throws Exception {
        Map<String, Long> map = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buff = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                long size = 0;
                int read;
                while ((read = in.read(buff)) > 0) {
                    size += read;
                }
                map.put(e.getName(), size);
            }
        }
        return map;
    }

    private static List<String> sortedNames(Map<String, Long> entries) {
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);
        return names;
    }

    private static long totalBytes(Map<String, Long> entries) {
        long total = 0;
        for (Long size : entries.values()) {
            total += size;
        }
        return total;
    }

}
