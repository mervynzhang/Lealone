/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

import com.lealone.storage.BackupManifest;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

/**
 * 全量备份改成"边写边算crc"之后，备份清单里仍必须记录每个条目的大小和crc32：
 * 恢复时要靠它校验数据完整性。这里直接读备份包里的 BACKUP.MANIFEST 断言这一点。
 */
public class BackupManifestCrcTest extends TestBase {

    @Test
    public void testManifestRecordsSizeAndCrcForEveryEntry() throws Exception {
        String dir = new File(TEST_DIR, "backup_crc").getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        String url = "jdbc:lealone:embed:" + dir + ";user=root;password=";
        File zip = new File(dir + "/full.zip");

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url)) {
            try (java.sql.Statement s = conn.createStatement()) {
                s.execute("create table if not exists crc_test(id int primary key, v varchar(100))");
                s.execute("insert into crc_test select x, 'v'||x from system_range(1, 200)");
                s.execute("CHECKPOINT SYNC");
                s.execute("BACKUP TO '" + zip.getAbsolutePath() + "'");
            }
        }

        byte[] manifestBytes = null;
        Set<String> dataEntries = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory())
                    continue;
                if (e.getName().endsWith(BackupManifest.FILE_NAME)) {
                    try (InputStream in = zf.getInputStream(e)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buff = new byte[8192];
                        int read;
                        while ((read = in.read(buff)) > 0)
                            out.write(buff, 0, read);
                        manifestBytes = out.toByteArray();
                    }
                } else {
                    dataEntries.add(e.getName());
                }
            }
        }
        assertTrue("备份包里必须有清单文件", manifestBytes != null);
        assertTrue("备份包里必须有数据条目", !dataEntries.isEmpty());

        BackupManifest manifest = BackupManifest.parse(manifestBytes);
        assertEquals(BackupManifest.TYPE_FULL, manifest.getType());
        for (String name : dataEntries) {
            BackupManifest.Entry e = manifest.getEntry(name);
            assertTrue("清单里缺少条目: " + name, e != null);
            assertTrue("条目大小必须大于0: " + name, e.size > 0);
            assertTrue("条目必须记录crc32: " + name, e.crc32 != 0);
        }
    }
}
