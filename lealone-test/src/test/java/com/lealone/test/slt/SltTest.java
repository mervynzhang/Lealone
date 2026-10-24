/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression gate for the curated sqllogictest subset.
 * <p>
 * The test is <b>skipped by default</b>: it only runs when {@code -Dslt.run=true} is set, which the
 * {@code slt} Maven profile does. It is also skipped (never failed) when the sqllogictest checkout
 * is not present, because the suite is not committed to the repository.
 * <p>
 * Typical invocation (see resources/slt/README.md):
 *
 * <pre>
 * mvn -o test -pl lealone-test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test
 * </pre>
 *
 * The gate compares the number of <em>non allowlisted query failures</em> per file with
 * {@code baseline.txt} and fails only when a file regresses.
 */
public class SltTest {

    private static final String DEFAULT_SUITE_DIR = SltRunner.DEFAULT_SUITE_DIR;

    /** System property / resource helpers. */
    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SltTest.class.getResourceAsStream("/slt/" + name)) {
            if (in == null)
                throw new IOException("missing classpath resource /slt/" + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @BeforeClass
    public static void requireSuite() {
        Assume.assumeTrue("sqllogictest profile not enabled (-Pslt or -Dslt.run=true), skipping SltTest",
                Boolean.getBoolean("slt.run"));
        Path dir = Paths.get(prop("slt.dir", DEFAULT_SUITE_DIR));
        String msg = "sqllogictest suite not found at " + dir.toAbsolutePath()
                + " (checkout gregrahn/sqllogictest and pass -Dslt.dir=<checkout>/test); skipping SltTest";
        if (!Files.isDirectory(dir)) {
            System.out.println("[slt] SKIP: " + msg);
            Assume.assumeTrue(msg, false);
        }
        if (!Files.isRegularFile(dir.resolve("select1.test"))) {
            System.out.println("[slt] SKIP: " + dir.toAbsolutePath()
                    + " does not look like the sqllogictest test/ directory (no select1.test)");
            Assume.assumeTrue("not a sqllogictest test directory: " + dir, false);
        }
    }

    @Test
    public void curatedSubset() throws Exception {
        Path suiteDir = Paths.get(prop("slt.dir", DEFAULT_SUITE_DIR));
        Path outDir = Paths.get(prop("slt.out", "target/slt-report"));
        int timeoutSec = Integer.parseInt(prop("slt.timeout", "120"));
        boolean updateBaseline = Boolean.getBoolean("slt.updateBaseline");

        // The curated list and the two gate files may be overridden for experiments, by default
        // they come from the committed test resources.
        String filesText = prop("slt.files", null) != null
                ? new String(Files.readAllBytes(Paths.get(prop("slt.files", null))), StandardCharsets.UTF_8)
                : resource("files.txt");
        String allowText = prop("slt.allowlist", null) != null
                ? new String(Files.readAllBytes(Paths.get(prop("slt.allowlist", null))),
                        StandardCharsets.UTF_8)
                : resource("allowlist.txt");
        String baselineText = prop("slt.baseline", null) != null
                ? new String(Files.readAllBytes(Paths.get(prop("slt.baseline", null))),
                        StandardCharsets.UTF_8)
                : resource("baseline.txt");

        List<String> files = new ArrayList<>();
        for (String line : filesText.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#"))
                files.add(s);
        }
        List<SltBaseline.Rule> rules = SltBaseline.loadAllowlist(allowText);
        SltBaseline.Baseline baseline = SltBaseline.loadBaseline(baselineText);
        for (String f : baseline.counts.keySet()) {
            if (!files.contains(f))
                System.out.println("[slt] note: baseline.txt lists " + f
                        + " which is not in files.txt (ignored)");
        }

        Path baseDir = Paths.get(prop("lealone.base.dir", "target/slt-db"));
        SltRunner.resetBaseDir(baseDir);

        System.out.println("[slt] suite  = " + suiteDir.toAbsolutePath());
        System.out.println("[slt] files  = " + files.size() + " curated file(s)");
        System.out.println("[slt] data   = " + baseDir.toAbsolutePath());
        System.out.println("[slt] report = " + outDir.toAbsolutePath());
        System.out.println("[slt] allowlist rules = " + rules.size() + ", baseline entries = "
                + baseline.counts.size());

        long t0 = System.currentTimeMillis();
        List<SltRunner.FileResult> results = new ArrayList<>();
        for (String rel : files) {
            Path f = suiteDir.resolve(rel);
            if (!Files.isRegularFile(f)) {
                SltRunner.FileResult r = new SltRunner.FileResult();
                r.file = rel;
                r.setupError = "file not found: " + f;
                r.status = "MISSING";
                results.add(r);
                System.out.println("[slt] MISSING " + rel);
                continue;
            }
            SltRunner.FileResult r = SltRunner.runFileWithTimeout(f, rel, SltRunner.dbNameFor(rel),
                    timeoutSec);
            results.add(r);
            System.out.println(String.format("[slt] %-38s %7d queries %7d pass %5d fail %7d ms%s",
                    rel, r.qTotal, r.qPass, r.gatedFail, r.ms, r.timeout ? " TIMEOUT" : ""));
        }
        long wall = System.currentTimeMillis() - t0;

        SltBaseline.classify(results, rules, baseline);
        String table = SltBaseline.deltaTable(results, true);
        System.out.println();
        System.out.println(table);
        String usage = SltBaseline.ruleUsage(results, rules);
        System.out.println(usage);
        SltRunner.writeReport(outDir, results);
        Files.write(outDir.resolve("delta.txt"),
                (table + "\n" + usage).getBytes(StandardCharsets.UTF_8));

        if (updateBaseline) {
            String newBaseline = SltBaseline.renderBaseline(results);
            Path dst = Paths.get(prop("slt.baseline.out", "src/test/resources/slt/baseline.txt"));
            Files.createDirectories(dst.toAbsolutePath().getParent());
            Files.write(dst, newBaseline.getBytes(StandardCharsets.UTF_8));
            System.out.println("[slt] baseline rewritten: " + dst.toAbsolutePath() + " ("
                    + results.size() + " files)");
            return;
        }

        int queries = 0, pass = 0, allowed = 0, unexp = 0;
        for (SltRunner.FileResult r : results) {
            queries += r.qTotal;
            pass += r.qPass;
            allowed += r.allowlisted;
            unexp += r.unexpected;
        }
        System.out.println(String.format(
                "[slt] total wall time %.1fs, %d queries, %d pass (%.2f%%), %d failures of which %d"
                        + " allowlisted and %d unexpected",
                wall / 1000.0, queries, pass, queries == 0 ? 0.0 : 100.0 * pass / queries,
                queries - pass, allowed, unexp));

        List<SltBaseline.Problem> problems = SltBaseline.regressions(results, false);
        List<SltBaseline.Problem> stale = SltBaseline.regressions(results, true);
        stale.removeAll(problems);
        if (!stale.isEmpty()) {
            StringBuilder sb = new StringBuilder("[slt] note: baseline is stale (fewer failures than"
                    + " expected, nothing to do but update baseline.txt):\n");
            for (SltBaseline.Problem p : stale)
                sb.append("  [").append(p.kind).append("] ").append(p.file).append(": ")
                        .append(p.message).append('\n');
            System.out.println(sb);
        }
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("sqllogictest regression: " + problems.size()
                    + " file(s) failed (report: " + outDir.toAbsolutePath() + ")\n");
            for (SltBaseline.Problem p : problems)
                sb.append("  [").append(p.kind).append("] ").append(p.file).append(": ")
                        .append(p.message).append('\n');
            sb.append(table);
            fail(sb.toString());
        }
    }
}
