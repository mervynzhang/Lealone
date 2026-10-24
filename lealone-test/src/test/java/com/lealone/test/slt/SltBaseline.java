/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Baseline / allowlist bookkeeping for the sqllogictest subset.
 * <p>
 * Two committed text files drive the regression gate:
 * <ul>
 * <li>{@code allowlist.txt} - failure signatures that are known dialect gaps ({@code ALL}
 * quantifier, SQLite-only DDL, ...). They are subtracted before the comparison and are never
 * treated as regressions. Each rule carries the reason it is out of scope.</li>
 * <li>{@code baseline.txt} - for every file, the number of <em>non allowlisted</em> query failures
 * the current build still produces. CI fails only when a file produces <em>more</em> failures than
 * its baseline; producing fewer is reported as a stale baseline (the bug was fixed, please update
 * the number) but does not fail the build.</li>
 * </ul>
 */
public final class SltBaseline {

    /** One allowlist rule: a Java regex matched with {@code find()} against Failure.signature(). */
    public static final class Rule {
        public final String id;
        public final String regex;
        public final String reason;
        final Pattern pattern;

        Rule(String id, String regex, String reason) {
            this.id = id;
            this.regex = regex;
            this.reason = reason;
            this.pattern = Pattern.compile(regex);
        }

        @Override
        public String toString() {
            return id + ": " + reason;
        }
    }

    /** A regression or a "fix me" warning for one file. */
    public static final class Problem {
        public final String file;
        public final String kind; // NEW | TIMEOUT | MISSING | SETUP | STALE
        public final String message;

        Problem(String file, String kind, String message) {
            this.file = file;
            this.kind = kind;
            this.message = message;
        }
    }

    private SltBaseline() {
    }

    // ------------------------------------------------------------------------------------------
    // loading
    // ------------------------------------------------------------------------------------------

    /** Format: {@code id <TAB> regex <TAB> reason}; blank lines and {@code #} lines are comments. */
    public static List<Rule> loadAllowlist(Path file) throws IOException {
        return loadAllowlist(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static List<Rule> loadAllowlist(String text) throws IOException {
        List<Rule> rules = new ArrayList<>();
        try (Reader r = new StringReader(text)) {
            BufferedReader br = new BufferedReader(r);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("#"))
                    continue;
                String[] p = line.split("\t");
                if (p.length < 3)
                    throw new IOException("bad allowlist line (need id<TAB>regex<TAB>reason): " + line);
                try {
                    rules.add(new Rule(p[0].trim(), p[1], p[2].trim()));
                } catch (PatternSyntaxException e) {
                    throw new IOException("bad regex in allowlist line: " + line + " -> " + e.getMessage());
                }
            }
        }
        return rules;
    }

    /**
     * Baseline 内容: 每个文件的失败个数(给报表用) + 未允许失败的 key 集合(给门禁用)。
     * <p>
     * 只比个数时, 同一个文件里"修好一条、又坏掉另一条"会因为个数相抵而判为 OK(假绿);
     * 比 key 集合就能发现新增的失败。
     */
    public static final class Baseline {
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public final Map<String, Set<String>> keys = new LinkedHashMap<>();

        public int countOf(String file) {
            Integer c = counts.get(file);
            return c == null ? 0 : c;
        }

        public Set<String> keysOf(String file) {
            Set<String> s = keys.get(file);
            return s == null ? Collections.emptySet() : s;
        }
    }

    /**
     * 格式(v2): {@code file <TAB> path <TAB> count [<TAB> observed]} 与
     * {@code fail <TAB> path <TAB> key [<TAB> subtype <TAB> line <TAB> sql(仅供参考)]}。
     * 旧格式(只有 file 行)仍然兼容: 这时按个数比较。
     */
    public static Baseline loadBaseline(Path file) throws IOException {
        return loadBaseline(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static Baseline loadBaseline(String text) throws IOException {
        Baseline b = new Baseline();
        try (Reader r = new StringReader(text)) {
            BufferedReader br = new BufferedReader(r);
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                if (line.trim().isEmpty() || line.trim().startsWith("#"))
                    continue;
                String[] p = line.split("\t");
                String tag = p[0].trim();
                if (tag.equals("fail")) {
                    if (p.length < 3)
                        throw new IOException("bad baseline fail line " + n + ": " + line);
                    b.keys.computeIfAbsent(p[1].trim(), k -> new TreeSet<>()).add(p[2].trim());
                } else if (tag.equals("file")) {
                    if (p.length < 3)
                        throw new IOException("bad baseline file line " + n + ": " + line);
                    try {
                        b.counts.put(p[1].trim(), Integer.parseInt(p[2].trim()));
                    } catch (NumberFormatException e) {
                        throw new IOException("bad baseline count on line " + n + ": " + line);
                    }
                } else {
                    // 旧格式: <file><TAB><count>[<TAB><observed>], 按个数比较
                    if (p.length < 2)
                        throw new IOException("bad baseline line " + n
                                + " (need file<TAB>count): " + line);
                    try {
                        b.counts.put(tag, Integer.parseInt(p[1].trim()));
                    } catch (NumberFormatException e) {
                        throw new IOException("bad baseline count on line " + n + ": " + line);
                    }
                }
            }
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------
    // classification and gate
    // ------------------------------------------------------------------------------------------

    /** Marks allowlisted failures, counts unexpected ones and compares them with the baseline. */
    public static void classify(List<SltRunner.FileResult> results, List<Rule> rules,
            Baseline baseline) {
        for (SltRunner.FileResult r : results) {
            r.allowlisted = 0;
            r.gatedFail = 0;
            for (SltRunner.Failure f : r.failures) {
                f.allowRule = "";
                if (!f.gated())
                    continue;
                r.gatedFail++;
                String sig = f.signature();
                for (Rule rule : rules) {
                    if (rule.pattern.matcher(sig).find()) {
                        f.allowRule = rule.id;
                        r.allowlisted++;
                        break;
                    }
                }
            }
            r.unexpected = r.gatedFail - r.allowlisted;
            r.unexpectedKeys.clear();
            for (SltRunner.Failure f : r.failures)
                if (f.gated() && f.allowRule.isEmpty())
                    r.unexpectedKeys.add(f.key());
            r.baseline = baseline.countOf(r.file);
            Set<String> baseKeys = baseline.keysOf(r.file);
            r.baselineByCountOnly = baseKeys.isEmpty() && r.baseline > 0;
            r.baselineKeys.clear();
            r.baselineKeys.addAll(baseKeys);
            r.newKeys.clear();
            r.staleKeys.clear();
            if (r.baselineByCountOnly) {
                // 旧格式(只有个数): 只能按个数比较
                r.delta = r.unexpected - r.baseline;
            } else {
                for (String k : r.unexpectedKeys)
                    if (!baseKeys.contains(k))
                        r.newKeys.add(k);
                for (String k : baseKeys)
                    if (!r.unexpectedKeys.contains(k))
                        r.staleKeys.add(k);
                r.delta = r.newKeys.size();
                if (r.delta == 0 && !r.staleKeys.isEmpty())
                    r.delta = -r.staleKeys.size();
            }
            if (r.timeout)
                r.status = "TIMEOUT";
            else if (!r.setupError.isEmpty())
                r.status = "SETUP";
            else if (r.delta > 0)
                r.status = "NEW(+" + r.delta + ")";
            else if (r.delta < 0)
                r.status = "STALE(" + r.delta + ")";
            else
                r.status = "OK";
        }
    }

    /** Per file delta table: one line per file, plus the new failures below it. */
    public static String deltaTable(List<SltRunner.FileResult> results, boolean details) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-42s %8s %8s %6s %7s %7s %6s %6s  %s%n", "file", "queries",
                "pass", "fail", "allowed", "unexp", "base", "delta", "status"));
        sb.append("-".repeat(110)).append('\n');
        int queries = 0, pass = 0, fail = 0, allowed = 0, unexp = 0, base = 0, deltaSum = 0;
        for (SltRunner.FileResult r : results) {
            queries += r.qTotal;
            pass += r.qPass;
            fail += r.gatedFail;
            allowed += r.allowlisted;
            unexp += r.unexpected;
            base += r.baseline;
            deltaSum += r.delta;
            sb.append(String.format("%-42s %8d %8d %6d %7d %7d %6d %+6d  %s%n",
                    shorten(r.file, 42), r.qTotal, r.qPass, r.gatedFail, r.allowlisted, r.unexpected,
                    r.baseline, r.delta, r.status));
            if (details) {
                String extra = detailBlock(r);
                if (!extra.isEmpty())
                    sb.append(extra);
            }
        }
        sb.append("-".repeat(110)).append('\n');
        // 汇总必须用"每文件 delta 之和": 按签名比较时, 一个文件的失败被另一个文件的修复抵消,
        // 用 unexp-base 会算成 0 而误报 PASS(端到端红对照就是在这一点上暴露的)
        sb.append(String.format("%-42s %8d %8d %6d %7d %7d %6d %+6d  %s%n", "TOTAL", queries, pass, fail,
                allowed, unexp, base, deltaSum,
                deltaSum == 0 ? "PASS" : (deltaSum > 0 ? "FAIL" : "STALE")));
        return sb.toString();
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : "..." + s.substring(s.length() - (n - 3));
    }

    private static String detailBlock(SltRunner.FileResult r) {
        String reason = null;
        if (r.timeout)
            reason = "runner timed out after " + r.ms + " ms (file dropped from the curated list?)";
        else if (!r.setupError.isEmpty())
            reason = "setup error: " + r.setupError;
        else if (r.delta > 0)
            reason = r.newKeys.isEmpty()
                    ? "unexpected failures = " + r.unexpected + ", baseline = " + r.baseline
                    : "new failure signature(s) not in the baseline: " + r.newKeys.size();
        else if (r.delta < 0)
            reason = r.baselineByCountOnly
                    ? "fewer failures than the baseline (" + r.unexpected + " < " + r.baseline
                            + "), the bug may be fixed: update baseline.txt"
                    : "baseline signature(s) no longer failing: " + r.staleKeys.size()
                            + ", update baseline.txt";
        if (reason == null)
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append("    ! ").append(r.file).append(": ").append(reason).append('\n');
        int shown = 0;
        for (SltRunner.Failure f : r.failures) {
            if (!f.gated() || !f.allowRule.isEmpty())
                continue;
            if (shown++ >= 8) {
                sb.append("    ... more failures in failures.tsv\n");
                break;
            }
            sb.append("      line ").append(f.line).append(" [").append(f.subtype).append("] ")
                    .append(abbrev(f.sql, 150)).append('\n');
            sb.append("        expected: ").append(abbrev(f.expected, 120)).append('\n');
            sb.append("        actual  : ").append(abbrev(f.actual, 120)).append('\n');
            if (!f.detail.isEmpty())
                sb.append("        detail  : ").append(abbrev(f.detail, 150)).append('\n');
        }
        return sb.toString();
    }

    private static String abbrev(String s, int n) {
        if (s == null)
            return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** Hard problems that must fail CI: new failures, timeouts, missing files, setup errors. */
    public static List<Problem> regressions(List<SltRunner.FileResult> results, boolean includeStale) {
        List<Problem> out = new ArrayList<>();
        for (SltRunner.FileResult r : results) {
            if (r.timeout)
                out.add(new Problem(r.file, "TIMEOUT", "file timed out after " + r.ms + " ms"));
            else if (r.status.equals("MISSING"))
                out.add(new Problem(r.file, "MISSING", r.setupError));
            else if (!r.setupError.isEmpty())
                out.add(new Problem(r.file, "SETUP", r.setupError));
            else if (r.delta > 0)
                out.add(new Problem(r.file, "NEW", r.delta + " new failure(s) beyond baseline "
                        + r.baseline + " (see failures.tsv)"));
            else if (includeStale && r.delta < 0)
                out.add(new Problem(r.file, "STALE", "only " + r.unexpected + " failure(s), baseline "
                        + r.baseline + " - the bug may be fixed"));
        }
        return out;
    }

    /** Human readable usage count per allowlist rule, so dialect noise stays visible. */
    public static String ruleUsage(List<SltRunner.FileResult> results, List<Rule> rules) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (Rule r : rules)
            hits.put(r.id, 0);
        for (SltRunner.FileResult r : results)
            for (SltRunner.Failure f : r.failures)
                if (!f.allowRule.isEmpty())
                    hits.merge(f.allowRule, 1, Integer::sum);
        StringBuilder sb = new StringBuilder("[slt] allowlisted failures by rule:\n");
        for (Rule r : rules)
            sb.append(String.format("  %-14s %8d  %s%n", r.id, hits.getOrDefault(r.id, 0), r.reason));
        return sb.toString();
    }

    /** Regenerates the baseline text from a run; used by -Dslt.updateBaseline=true. */
    public static String renderBaseline(List<SltRunner.FileResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# sqllogictest baseline for the Lealone build.\n");
        sb.append("# Format:\n");
        sb.append("#   file <TAB> path <TAB> expected non-allowlisted failures [<TAB> observed]\n");
        sb.append("#   fail <TAB> path <TAB> key [<TAB> subtype <TAB> line <TAB> sql(only for reading)]\n");
        sb.append("# The 'fail' lines are what the gate compares, so replacing one failure with another\n");
        sb.append("# in the same file is detected even when the per-file count stays the same.\n");
        sb.append("# Regenerate with: mvn -o test -pl lealone-test -Pslt -Dtest=SltTest");
        sb.append(" -Dslt.updateBaseline=true\n");
        for (SltRunner.FileResult r : results) {
            if (r.timeout || !r.setupError.isEmpty())
                sb.append("# WARNING: run did not complete cleanly for this file: ")
                        .append(r.timeout ? "timeout" : r.setupError).append('\n');
            List<SltRunner.Failure> unexpected = new ArrayList<>();
            for (SltRunner.Failure f : r.failures)
                if (f.gated() && f.allowRule.isEmpty())
                    unexpected.add(f);
            sb.append("file\t").append(r.file).append('\t').append(unexpected.size()).append('\t')
                    .append(r.gatedFail).append('\n');
            unexpected.sort((x, y) -> x.key().compareTo(y.key()));
            for (SltRunner.Failure f : unexpected)
                sb.append("fail\t").append(r.file).append('\t').append(f.key()).append('\t')
                        .append(f.subtype).append('\t').append(f.line).append('\t')
                        .append(abbrev(f.sql, 120).replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }
}
