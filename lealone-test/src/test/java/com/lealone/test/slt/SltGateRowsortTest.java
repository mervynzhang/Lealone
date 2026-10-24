/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.lealone.db.Database;
import com.lealone.db.LealoneDatabase;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

/**
 * {@code rowsort} / {@code valuesort} 的规范是"两侧都排序后再比较"。
 * 门禁原来只对实际结果排序、期望值仍按文件顺序比，于是期望值不是升序的用例会被误判成失败
 * （噪音大了以后真正的问题容易淹掉）。这里用一个小脚本验证：期望值乱序但集合相同必须 PASS。
 */
public class SltGateRowsortTest extends TestBase {

    private static String savedBaseDir;

    @BeforeClass
    public static void pinBaseDir() {
        savedBaseDir = System.getProperty("lealone.base.dir");
        if (savedBaseDir == null)
            System.setProperty("lealone.base.dir", new File(TEST_BASE_DIR).getAbsolutePath());
    }

    @AfterClass
    public static void restoreBaseDir() {
        if (savedBaseDir == null)
            System.clearProperty("lealone.base.dir");
        else
            System.setProperty("lealone.base.dir", savedBaseDir);
        String dbName = SltRunner.dbNameFor("gate_rowsort.test").toUpperCase();
        Database db = LealoneDatabase.getInstance().findDatabase(dbName);
        if (db != null) {
            String path = db.getStoragePath();
            db.close(false);
            if (path != null)
                FileUtils.deleteRecursive(path, false);
        }
    }

    @Test
    public void testRowsortComparesSortedExpected() throws Exception {
        Path dir = Files.createTempDirectory("slt-gate-rowsort");
        Path sqlFile = dir.resolve("gate_rowsort.test");
        // 期望值故意写成乱序(3,1,2); rowsort 语义下这仍然应该 PASS
        String script = "statement ok\n" //
                + "create table gr_t(id int primary key)\n" //
                + "\n" //
                + "statement ok\n" //
                + "insert into gr_t values(1)\n" //
                + "\n" //
                + "statement ok\n" //
                + "insert into gr_t values(2)\n" //
                + "\n" //
                + "statement ok\n" //
                + "insert into gr_t values(3)\n" //
                + "\n" //
                + "query I rowsort\n" //
                + "select id from gr_t\n" //
                + "----\n" //
                + "3\n" //
                + "1\n" //
                + "2\n" //
                + "\n";
        Files.write(sqlFile, script.getBytes(StandardCharsets.UTF_8));
        SltRunner.FileResult r = SltRunner.runFile(sqlFile, "gate_rowsort.test", "slt_gate_rowsort");
        StringBuilder sb = new StringBuilder();
        for (SltRunner.Failure f : r.failures)
            sb.append(f.kind).append(':').append(f.detail).append("; ");
        assertEquals("rowsort 下期望值乱序但集合相同必须 PASS, 实际失败: " + sb, 0, r.failures.size());
    }
}
