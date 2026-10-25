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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.lealone.db.Database;
import com.lealone.db.LealoneDatabase;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

/**
 * SLT 回归门禁必须把 statement 失败也算进去。
 * <p>
 * 旧实现只统计 query 失败(kind != "statement"), 所以脚本里的 DDL/DML 坏掉时门禁依然 PASS ——
 * 例如 CREATE INDEX 写错了列名, 门禁照样绿。这里用一个小脚本直接验证门禁会变红。
 */
public class SltGateStatementTest extends TestBase {

    private static String savedBaseDir;

    // SltRunner 的静态初始化会在 lealone.base.dir 未设置时把它改成 target/slt-db。
    // 这个属性是整个 JVM 共享的, 于是同一个套件里后跑的用例(比如 CheckpointDurabilityTest
    // 要复制 db_0 目录)会用错存储目录, 表现为重启后更新丢失。这里在加载 SltRunner 之前
    // 先把目录固定成和普通用例一致, 用完再还原。
    @BeforeClass
    public static void pinBaseDir() {
        savedBaseDir = System.getProperty("lealone.base.dir");
        if (savedBaseDir == null)
            System.setProperty("lealone.base.dir",
                    new File(TEST_BASE_DIR).getAbsolutePath());
    }

    // 这个用例要在进程内起一个嵌入式库并跑 SltRunner, 用完必须关掉并删目录(见 closeSltDatabase)。
    // 它曾经把"Database.close 被内部session静默挡掉"这个P0触发成普通套件的偶发红(FIX_LOG 第27项),
    // 那个缺陷修好之后本用例恢复在普通套件里运行。
    @AfterClass
    public static void restoreBaseDir() {
        if (savedBaseDir == null)
            System.clearProperty("lealone.base.dir");
        else
            System.setProperty("lealone.base.dir", savedBaseDir);
    }

    // 用完必须把这里创建的嵌入式库彻底关掉并删掉目录: 否则它会留在 LealoneDatabase 里,
    // 后台检查点仍然会去保存它的脏页, 而目录可能已被其它用例删掉,
    // 于是整轮检查点被日志刷屏(甚至影响同一 JVM 里其它库的落盘)。
    @AfterClass
    public static void closeSltDatabase() {
        String dbName = SltRunner.dbNameFor("gate_statement.test").toUpperCase();
        Database db = LealoneDatabase.getInstance().findDatabase(dbName);
        if (db != null) {
            String path = db.getStoragePath();
            db.close(false);
            if (path != null)
                FileUtils.deleteRecursive(path, false);
        }
    }

    @Test
    public void testStatementFailureTurnsGateRed() throws Exception {
        Path dir = Files.createTempDirectory("slt-gate-stmt");
        Path sqlFile = dir.resolve("gate_statement.test");
        String script = "statement ok\n" //
                + "create table gs_t(id int primary key, v int)\n" //
                + "\n" //
                + "# 这条语句必然失败(表不存在), 门禁必须报出来\n" //
                + "statement ok\n" //
                + "insert into gs_no_such_table values(1)\n" //
                + "\n" //
                + "query I\n" //
                + "select 1\n" //
                + "----\n" //
                + "1\n" //
                + "\n";
        Files.write(sqlFile, script.getBytes(StandardCharsets.UTF_8));

        SltRunner.FileResult r = SltRunner.runFile(sqlFile, "gate_statement.test", "slt_gate_stmt");
        assertTrue("脚本里那条必然失败的 statement 应该被记录", !r.failures.isEmpty());

        boolean hasStatementFailure = false;
        for (SltRunner.Failure f : r.failures) {
            if ("statement".equals(f.kind)) {
                hasStatementFailure = true;
                assertTrue("statement 失败必须计入门禁(旧实现这里返回 false)", f.gated());
            }
        }
        assertTrue("应该有一条 statement 失败", hasStatementFailure);

        List<SltRunner.FileResult> results = Collections.singletonList(r);
        SltBaseline.Baseline emptyBaseline = new SltBaseline.Baseline();
        SltBaseline.classify(results, Collections.emptyList(), emptyBaseline);
        assertTrue("statement 失败必须让门禁的 gated 失败数大于0", r.gatedFail > 0);
        assertTrue("statement 失败必须让 delta 大于0(门禁变红)", r.delta > 0);
    }
}
