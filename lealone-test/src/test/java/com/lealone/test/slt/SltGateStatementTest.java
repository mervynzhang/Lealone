/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * SLT 回归门禁必须把 statement 失败也算进去。
 * <p>
 * 旧实现只统计 query 失败(kind != "statement"), 所以脚本里的 DDL/DML 坏掉时门禁依然 PASS ——
 * 例如 CREATE INDEX 写错了列名, 门禁照样绿。这里用一个小脚本直接验证门禁会变红。
 */
public class SltGateStatementTest extends TestBase {

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
        Map<String, Integer> emptyBaseline = Collections.emptyMap();
        SltBaseline.classify(results, Collections.emptyList(), emptyBaseline);
        assertTrue("statement 失败必须让门禁的 gated 失败数大于0", r.gatedFail > 0);
        assertTrue("statement 失败必须让 delta 大于0(门禁变红)", r.delta > 0);
    }
}
