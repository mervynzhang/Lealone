/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.pg;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.lealone.db.Constants;
import com.lealone.storage.fs.FileUtils;

/**
 * PostgreSQL 官方函数/存储过程回归测试(function/procedure 部分)的转换版，作为"对齐 PG"的度量尺。
 * <p>
 * 脚本在 {@code src/test/resources/pg/*.sql}，格式见 {@link PgRegressRunner}；不支持的用例按
 * {@code src/test/resources/pg/skip-list.txt} 里的清单跳过并记录原因。清单的完整说明、
 * 统计和已知缺陷见 {@code docs/PG_REGRESS.md}。
 * <p>
 * 单独运行(不需要 PG 源码，约 1 秒): <br>
 * {@code mvn -o -pl lealone-test test -Dtest=PgRegressTest}
 * <p>
 * 可选: 传 {@code -Dpg.srcdir=<PG>/src/test/regress/sql} 时额外核对 skip 清单里记录的 PG 行号
 * 范围确实存在、语句数和清单声明的一致，用来维护清单，CI 里不需要 PG 源码。
 * <p>
 * 没有任何断言失败才算通过；skip 条目不参与失败统计，但清单和脚本会对账(清单里的用例在脚本里
 * 找不到就失败)，所以"删用例变绿"这条路是堵死的。
 */
public class PgRegressTest {

    private static final String RESOURCE_DIR = "pg";
    private static final String SOURCE_DIR = "src/test/resources/pg";
    // 本套件专用的库目录，每轮开始先清掉，不和 target/test-data 下的其它测试共用
    private static final String BASE_DIR = "." + File.separator + "target" + File.separator
            + "test-data" + File.separator + "pgregress";

    private static File dir(String property, String resource, String sourceDir) throws Exception {
        String value = System.getProperty(property);
        if (value != null && !value.trim().isEmpty())
            return new File(value.trim());
        URL url = PgRegressTest.class.getResource("/" + resource);
        if (url != null && "file".equals(url.getProtocol()))
            return new File(url.toURI());
        File file = new File(sourceDir);
        assertTrue("找不到目录: " + sourceDir, file.isDirectory());
        return file;
    }

    @Test
    public void runPgRegress() throws Exception {
        File scriptDir = dir("pg.dir", RESOURCE_DIR, SOURCE_DIR);
        File skipFile = System.getProperty("pg.skip") != null ? new File(System.getProperty("pg.skip"))
                : new File(scriptDir, "skip-list.txt");
        // SysProperties.setBaseDir 在属性已存在时不会覆盖旧值，所以这里直接改属性并在结束时还原，
        // 避免把同一个 JVM 里后面跑的测试也带到我这个会被删掉的目录里
        String baseDirKey = Constants.PROJECT_NAME_PREFIX + "base.dir";
        String oldBaseDir = System.getProperty(baseDirKey);
        File baseDir = new File(BASE_DIR).getAbsoluteFile();
        if (baseDir.isDirectory())
            FileUtils.deleteRecursive(baseDir.getAbsolutePath(), false);
        // 每个脚本文件一个持久化库，放在本套件专用的目录里(下一轮开始时先清掉)
        System.setProperty(baseDirKey, baseDir.getPath());
        long start = System.currentTimeMillis();
        PgRegressRunner runner;
        PgSkipList skipList;
        try {
            skipList = PgSkipList.load(skipFile);
            String pgSrcDir = System.getProperty("pg.srcdir");
            if (pgSrcDir != null && !pgSrcDir.trim().isEmpty())
                skipList.verifyAgainstPostgresSources(new File(pgSrcDir.trim()));
            runner = new PgRegressRunner(skipList);
            runner.runDirectory(scriptDir);
        } finally {
            if (oldBaseDir == null)
                System.clearProperty(baseDirKey);
            else
                System.setProperty(baseDirKey, oldBaseDir);
            // 库目录不在结束时删: 连接关掉后后台还有 save 线程在收尾, 立刻删文件会打印
            // "Failed to execute save" 的无害噪音; 下一轮开始时统一清掉, target 目录本来就不入库
        }
        long cost = System.currentTimeMillis() - start;

        StringBuilder buff = new StringBuilder();
        buff.append("\n[pgregress] 脚本目录: ").append(scriptDir.getAbsolutePath()).append('\n');
        for (PgRegressRunner.FileResult r : runner.getResults()) {
            buff.append(String.format(
                    "[pgregress]   %-24s 用例 %2d(执行 %2d, skip %2d)  脚本语句 %3d(通过 %3d, 失败 %d)"
                            + "  被skip用例占 %3d 条%n", r.file, r.getCases(), r.runCases,
                    r.skippedCases, r.statements, r.passed, r.errors.size(), r.skippedStatements));
        }
        buff.append(String.format(
                "[pgregress] 合计: 用例 %d (执行 %d, skip %d), 脚本语句 %d (通过 %d, 失败 %d), 耗时 %dms%n",
                runner.getCases(), runner.getRunCases(), runner.getSkippedCaseCount(),
                runner.getStatements(), runner.getPassed(), runner.getFailed(), cost));
        buff.append(String.format("[pgregress] skip 清单: %d 条用例, 点名跳过的 PG 语句 %d 条%n",
                skipList.getEntries().size(), skipList.getPgStatements()));
        for (PgSkipList.FileTotal total : skipList.getFileTotals()) {
            if (total.sampled)
                buff.append(String.format(
                        "[pgregress]   %-24s PG 共 %4d 条语句, 抽样转换(清单里点名跳过的代表性语句 %d 条)%n",
                        total.file, total.statements, skipList.getPgStatements(total.file)));
            else
                buff.append(String.format("[pgregress]   %-24s PG 共 %4d 条语句, 其中 %3d 条在 skip 清单里%n",
                        total.file, total.statements, skipList.getPgStatements(total.file)));
        }
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (PgSkipList.Entry e : skipList.getEntries().values()) {
            Integer count = byReason.get(e.reason);
            byReason.put(e.reason, count == null ? 1 : count.intValue() + 1);
        }
        for (Map.Entry<String, Integer> e : byReason.entrySet())
            buff.append(String.format("[pgregress]   %2d 条  %s%n", e.getValue(), e.getKey()));

        List<String> problems = new ArrayList<>();
        problems.addAll(skipList.getErrors());
        problems.addAll(runner.getStructureErrors());
        problems.addAll(runner.getAllErrors());
        if (!problems.isEmpty()) {
            StringBuilder fail = new StringBuilder();
            fail.append("PG回归测试失败: 用例 ").append(runner.getCases()).append(" (执行 ")
                    .append(runner.getRunCases()).append(", skip ")
                    .append(runner.getSkippedCaseCount()).append("), 脚本语句失败 ")
                    .append(runner.getFailed()).append(", 结构问题 ")
                    .append(skipList.getErrors().size() + runner.getStructureErrors().size())
                    .append(" 处\n");
            for (String p : problems)
                fail.append(p).append('\n');
            fail.append(buff);
            fail(fail.toString());
        }
        System.out.println(buff);
    }
}
