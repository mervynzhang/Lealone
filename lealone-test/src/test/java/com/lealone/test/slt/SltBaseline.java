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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Format: {@code file <TAB> expected_non_allowlisted_failures [<TAB> observed_total]}. */
    public static Map<String, Integer> loadBaseline(Path file) throws IOException {
        return loadBaseline(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static Map<String, Integer> loadBaseline(String text) throws IOException {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Reader r = new StringReader(text)) {
            BufferedReader br = new BufferedReader(r);
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                if (line.trim().isEmpty() || line.trim().startsWith("#"))
                    continue;
                String[] p = line.split("\t");
                if (p.length < 2)
                    throw new IOException("bad baseline line " + n + " (need file<TAB>count): " + line);
                try {
                    m.put(p[0].trim(), Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException e) {
                    throw new IOException("bad baseline count on line " + n + ": " + line);
                }
            }
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // classification and gate
    // ------------------------------------------------------------------------------------------

    /** Marks allowlisted failures, counts unexpected ones and compares them with the baseline. */
    public static void classify(List<SltRunner.FileResult> results, List<Rule> rules,
            Map<String, Integer> baseline) {
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
            Integer b = baseline.get(r.file);
            r.baseline = b == null ? 0 : b;
            r.delta = r.unexpected - r.baseline;
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
        int queries = 0, pass = 0, fail = 0, allowed = 0, unexp = 0, base = 0;
        for (SltRunner.FileResult r : results) {
            queries += r.qTotal;
            pass += r.qPass;
            fail += r.gatedFail;
            allowed += r.allowlisted;
            unexp += r.unexpected;
            base += r.baseline;
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
        sb.append(String.format("%-42s %8d %8d %6d %7d %7d %6d %+6d  %s%n", "TOTAL", queries, pass, fail,
                allowed, unexp, base, unexp - base,
                unexp - base == 0 ? "PASS" : (unexp - base > 0 ? "FAIL" : "STALE")));
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
            reason = "unexpected failures = " + r.unexpected + ", baseline = " + r.baseline;
        else if (r.delta < 0)
            reason = "fewer failures than the baseline (" + r.unexpected + " < " + r.baseline
                    + "), the bug may be fixed: update baseline.txt";
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
        sb.append("# Columns: file <TAB> expected non-allowlisted QUERY failures <TAB>");
        sb.append(" observed total query failures (informational).\n");
        sb.append("# Regenerate with: mvn -o test -pl lealone-test -Pslt -Dtest=SltTest");
        sb.append(" -Dslt.updateBaseline=true\n");
        sb.append("# Only query failures are gated; 'statement' records are executed but never counted.\n");
        for (SltRunner.FileResult r : results) {
            if (r.timeout || !r.setupError.isEmpty())
                sb.append("# WARNING: run did not complete cleanly for this file: ")
                        .append(r.timeout ? "timeout" : r.setupError).append('\n');
            sb.append(r.file).append('\t').append(r.unexpected).append('\t').append(r.gatedFail)
                    .append('\n');
        }
        return sb.toString();
    }
}
