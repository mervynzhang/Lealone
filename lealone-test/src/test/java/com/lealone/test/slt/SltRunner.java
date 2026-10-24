/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A minimal sqllogictest (https://www.sqlite.org/sqllogictest/,
 * checkout gregrahn/sqllogictest) runner for Lealone.
 * <p>
 * Embedded mode only: every test file gets its own freshly created Lealone database
 * ({@code jdbc:lealone:embed:<dbName>}) below {@code -Dlealone.base.dir}, no TCP server and no
 * ports are involved.
 * <p>
 * The runner understands the records that Lealone can meaningfully execute:
 * {@code statement ok|error}, {@code query <types> [nosort|rowsort|valuesort] [label-x]},
 * literal results, {@code N values hashing to <md5>}, {@code hash-threshold}, {@code skipif} /
 * {@code onlyif} (the runner identifies itself as {@code postgresql} / {@code lealone}) and
 * {@code halt}. Everything else in the suite (e.g. SQLite/MySQL specific DDL) is executed as
 * written so that the resulting error shows up as an ordinary failure row.
 * <p>
 * Output is machine readable: one JSON object per test file plus summary.tsv / failures.tsv.
 * {@link SltBaseline} turns that into a pass/fail decision against the committed baseline.
 * <p>
 * Command line usage:
 *
 * <pre>
 * SltRunner --dir=/tmp/sqllogictest/test --files=src/test/resources/slt/files.txt
 *           --out=target/slt-report --baseline=src/test/resources/slt/baseline.txt
 *           --allowlist=src/test/resources/slt/allowlist.txt --timeout=120
 * </pre>
 */
public final class SltRunner {

    public static final String DRIVER = "com.lealone.client.jdbc.JdbcDriver";
    public static final String DEFAULT_SUITE_DIR = "/tmp/sqllogictest/test";
    /** Results of {@code skipif}/{@code onlyif} conditional records. */
    private static final Set<String> OURS = new HashSet<>(Arrays.asList("postgresql", "lealone"));
    /** Detects the ALL quantifier in the unclipped SQL, see {@link Failure#sqlHasAll}. */
    private static final Pattern ALL_TOKEN = Pattern.compile("\\bALL\\b");

    static {
        // Must be set before any Lealone class is loaded; the Maven profile normally passes it on
        // the JVM command line, this is only the fallback for direct runs.
        if (System.getProperty("lealone.base.dir") == null) {
            System.setProperty("lealone.base.dir",
                    Paths.get("target", "slt-db").toAbsolutePath().toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // data model
    // ------------------------------------------------------------------------------------------

    /** One failed record (a query comparison or a statement that behaved unexpectedly). */
    public static final class Failure {
        public String file;
        public int line;
        public String kind; // query | label | statement
        public String subtype; // exception | value | row_count | hash_mismatch | column_count | ...
        public String sql;
        public String expected;
        public String actual;
        public String detail;
        /** Id of the allowlist rule that covers this failure, empty when it is a new failure. */
        public String allowRule = "";
        /**
         * True when the <em>unclipped</em> SQL contains an ALL token. The reported {@link #sql} is
         * clipped, so long generated expressions can lose the very token that explains the failure;
         * the flag keeps allowlist rules that need to see "ALL" working.
         */
        public boolean sqlHasAll;

        /**
         * 稳定的失败标识: 只由 subtype + SQL 决定, 不含 detail/行号。
         * 用于把 baseline 从"每文件失败个数"升级成"失败集合", 这样同一文件里
         * "修好一条、又坏掉另一条"也能被发现(只比计数时二者相抵, 门禁会假绿)。
         */
        public String key() {
            String text = subtype + "\t" + sql;
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(16);
                for (int i = 0; i < 8; i++)
                    sb.append(String.format("%02x", d[i]));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Text the allowlist regular expressions are matched against. */
        public String signature() {
            return subtype + "\t" + sql + "\t" + detail + (sqlHasAll ? "\tALL" : "");
        }

        public boolean gated() {
            // statement 失败必须同样计入回归门禁: 旧实现把 kind=="statement" 排除在外,
            // 结果 SLT 脚本里的 DDL/DML(建索引、建表、插入)坏掉了门禁依然是 PASS,
            // 只有 query 的失败才会计数 —— 这等于给"有第三方回归保护"一个假的保证。
            return true;
        }
    }

    /** Aggregated result of one sqllogictest file. */
    public static final class FileResult {
        public String file; // path relative to the suite directory
        public int records, executed, skipped;
        public int qTotal, qPass, qFail;
        public int qException, qRowCount, qValue, qHash, qColumnCount;
        public int stmtOkPass, stmtOkFail, stmtErrPass, stmtErrFail;
        public int labelChecks, labelFails;
        public long ms;
        public boolean timeout;
        public String setupError = "";
        // baseline(签名集合)相关: 未允许失败的key集合, 以及相对baseline新增/消失的key
        public final java.util.List<String> unexpectedKeys = new ArrayList<>();
        public final java.util.Set<String> baselineKeys = new java.util.TreeSet<>();
        public final java.util.Set<String> newKeys = new java.util.TreeSet<>();
        public final java.util.Set<String> staleKeys = new java.util.TreeSet<>();
        /** True when the baseline entry for this file only has a count (old format). */
        public boolean baselineByCountOnly;
        public final Map<String, Integer> skippedBy = new TreeMap<>();
        public final List<Failure> failures = new ArrayList<>();

        // filled in by SltBaseline.classify()
        public int allowlisted, gatedFail, unexpected, baseline = -1, delta;
        public String status = "OK";

        public int totalFailures() {
            return failures.size();
        }
    }

    private static final class Rec {
        int line;
        final List<String[]> conds = new ArrayList<>();
        String type; // statement | query | halt | hash-threshold
        String arg = ""; // ok/error | type-string
        String sort = "nosort";
        String label = "";
        String sql = "";
        final List<String> expected = new ArrayList<>();
    }

    // ------------------------------------------------------------------------------------------
    // sqllogictest parser
    // ------------------------------------------------------------------------------------------

    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++)
            if (!Character.isWhitespace(s.charAt(i)))
                return false;
        return true;
    }

    private static String deCR(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** Parses the record stream of one .test file. Unknown headers are ignored. */
    static List<Rec> parse(List<String> raw) {
        List<Rec> recs = new ArrayList<>();
        int i = 0, n = raw.size();
        while (i < n) {
            String line = deCR(raw.get(i));
            if (isBlank(line) || line.startsWith("#")) {
                i++;
                continue;
            }
            Rec r = new Rec();
            r.line = i + 1;
            while (line.startsWith("skipif") || line.startsWith("onlyif")) {
                String[] tok = line.trim().split("\\s+");
                if (tok.length >= 2)
                    r.conds.add(new String[] { tok[0], tok[1] });
                i++;
                while (i < n && deCR(raw.get(i)).startsWith("#"))
                    i++;
                if (i >= n)
                    break;
                line = deCR(raw.get(i));
            }
            if (i >= n)
                break;
            int hp = line.indexOf('#');
            if (hp >= 0)
                line = line.substring(0, hp);
            if (line.startsWith("halt")) {
                r.type = "halt";
                recs.add(r);
                i++;
                continue;
            }
            if (line.startsWith("hash-threshold")) {
                r.type = "hash-threshold";
                String[] tok = line.trim().split("\\s+");
                if (tok.length > 1)
                    r.arg = tok[1];
                recs.add(r);
                i++;
                continue;
            }
            if (line.startsWith("statement")) {
                String[] tok = line.trim().split("\\s+");
                r.type = "statement";
                r.arg = tok.length > 1 ? tok[1] : "";
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    String l = deCR(raw.get(i));
                    if (isBlank(l))
                        break;
                    if (l.startsWith("#")) {
                        i++;
                        continue;
                    }
                    if (sb.length() > 0)
                        sb.append('\n');
                    sb.append(l);
                    i++;
                }
                r.sql = sb.toString();
                recs.add(r);
                continue;
            }
            if (line.startsWith("query")) {
                String[] tok = line.trim().split("\\s+");
                r.type = "query";
                r.arg = tok.length > 1 ? tok[1] : "";
                for (int k = 2; k < tok.length; k++) {
                    if (tok[k].equals("nosort") || tok[k].equals("rowsort") || tok[k].equals("valuesort"))
                        r.sort = tok[k];
                    else if (tok[k].startsWith("label"))
                        r.label = tok[k];
                }
                i++;
                StringBuilder sb = new StringBuilder();
                boolean divider = false;
                while (i < n) {
                    String l = deCR(raw.get(i));
                    if (isBlank(l))
                        break;
                    if (l.startsWith("#")) {
                        i++;
                        continue;
                    }
                    if (l.equals("----")) {
                        divider = true;
                        i++;
                        break;
                    }
                    if (sb.length() > 0)
                        sb.append('\n');
                    sb.append(l);
                    i++;
                }
                r.sql = sb.toString();
                if (divider) {
                    while (i < n) {
                        String l = deCR(raw.get(i));
                        if (isBlank(l))
                            break;
                        if (l.startsWith("#")) {
                            i++;
                            continue;
                        }
                        r.expected.add(l);
                        i++;
                    }
                }
                recs.add(r);
                continue;
            }
            i++; // unknown header, ignore the line
        }
        return recs;
    }

    // ------------------------------------------------------------------------------------------
    // value rendering / result comparison
    // ------------------------------------------------------------------------------------------

    private static String render(ResultSet rs, int col, char t) throws SQLException {
        Object o = rs.getObject(col);
        if (o == null)
            return "NULL";
        if (t == 'T') {
            String s;
            try {
                s = rs.getString(col);
            } catch (SQLException e) {
                s = String.valueOf(o);
            }
            if (s == null)
                return "NULL";
            if (s.isEmpty())
                return "(empty)";
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c < ' ' || c > '~' ? '@' : c);
            }
            return sb.toString();
        }
        if (t == 'I') {
            long v;
            if (o instanceof Boolean)
                v = ((Boolean) o) ? 1L : 0L;
            else if (o instanceof Double || o instanceof Float)
                v = (long) ((Number) o).doubleValue();
            else if (o instanceof java.math.BigDecimal)
                v = ((java.math.BigDecimal) o).longValue();
            else if (o instanceof Number)
                v = ((Number) o).longValue();
            else {
                try {
                    v = Long.parseLong(String.valueOf(o).trim());
                } catch (NumberFormatException e) {
                    return String.valueOf(o);
                }
            }
            return Long.toString(v);
        }
        // 'R'
        double d;
        if (o instanceof Boolean)
            d = ((Boolean) o) ? 1.0 : 0.0;
        else if (o instanceof Number)
            d = ((Number) o).doubleValue();
        else {
            try {
                d = Double.parseDouble(String.valueOf(o).trim());
            } catch (NumberFormatException e) {
                return String.valueOf(o);
            }
        }
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String md5(MessageDigest md, List<String> vals) {
        md.reset();
        for (String v : vals) {
            md.update(v.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte x : d)
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    // 期望值是一行一个值(SLT 里每行值之间用空格分隔, 但这里每个值单独一行存放),
    // 按 sort 方式排序后返回新的列表
    private static List<String> sortExpected(List<String> expected, String mode) {
        if (mode.equals("valuesort")) {
            List<String> all = new ArrayList<>(expected);
            all.sort(String::compareTo);
            return all;
        }
        // rowsort: 一个值一行, 按值排序即可(单列场景两类排序等价)
        List<String> all = new ArrayList<>(expected);
        all.sort(String::compareTo);
        return all;
    }

    private static void sortRows(List<String[]> rows, String mode) {
        if (mode.equals("rowsort")) {
            rows.sort((a, b) -> {
                int n = Math.min(a.length, b.length);
                for (int i = 0; i < n; i++) {
                    int c = a[i].compareTo(b[i]);
                    if (c != 0)
                        return c;
                }
                return Integer.compare(a.length, b.length);
            });
        } else if (mode.equals("valuesort")) {
            List<String> all = new ArrayList<>();
            for (String[] r : rows)
                all.addAll(Arrays.asList(r));
            all.sort(String::compareTo);
            int w = rows.isEmpty() ? 0 : rows.get(0).length;
            rows.clear();
            if (w > 0)
                for (int i = 0; i < all.size(); i += w) {
                    String[] r = new String[Math.min(w, all.size() - i)];
                    for (int j = 0; j < r.length; j++)
                        r[j] = all.get(i + j);
                    rows.add(r);
                }
        }
    }

    private static String join3(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size() && i < 3; i++) {
            if (i > 0)
                sb.append(" | ");
            sb.append(v.get(i));
        }
        if (v.size() > 3)
            sb.append(" | ...(").append(v.size()).append(" total)");
        String s = sb.toString();
        return s.length() > 320 ? s.substring(0, 320) + "..." : s;
    }

    private static String clip(String s, int n) {
        if (s == null)
            return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String tsv(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String json(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ')
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // executing one file
    // ------------------------------------------------------------------------------------------

    /** Turns a suite relative file name into a database name that is safe on every file system. */
    public static String dbNameFor(String relFile) {
        StringBuilder sb = new StringBuilder("slt_");
        for (char c : relFile.toCharArray()) {
            if (Character.isLetterOrDigit(c))
                sb.append(c);
            else if (c == '/' || c == '.' || c == '-' || c == '_')
                sb.append('_');
        }
        return sb.length() > 60 ? sb.substring(0, 60) : sb.toString();
    }

    /** Report file base name for one suite file, e.g. random/expr/slt_good_1 -> random_expr_slt_good_1. */
    public static String slugFor(String relFile) {
        String s = relFile.endsWith(".test") ? relFile.substring(0, relFile.length() - 5) : relFile;
        return s.replace('/', '_').replace('\\', '_').replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /**
     * Executes one sqllogictest file against a brand new embedded database.
     *
     * @param file    absolute path to the .test file
     * @param relFile file name relative to the suite directory (only used for reporting)
     * @param dbName  name of the fresh embedded database
     */
    public static FileResult runFile(Path file, String relFile, String dbName) {
        FileResult res = new FileResult();
        res.file = relFile;
        long t0 = System.currentTimeMillis();
        List<String> raw;
        try {
            raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            res.setupError = "cannot read " + file + ": " + e;
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        List<Rec> recs = parse(raw);
        res.records = recs.size();
        int skipped = 0, executed = 0, hashExpected = 0, litExpected = 0, labelChecks = 0,
                labelFails = 0;
        int stmtOkPass = 0, stmtOkFail = 0, stmtErrPass = 0, stmtErrFail = 0;
        int qTotal = 0, qPass = 0, qFail = 0;
        int fEx = 0, fCol = 0, fRow = 0, fVal = 0, fHash = 0;
        int hashThreshold = 8;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        Connection conn = null;
        try {
            Class.forName(DRIVER);
            Properties p = new Properties();
            p.setProperty("user", "root");
            p.setProperty("password", "");
            conn = DriverManager.getConnection("jdbc:lealone:embed:" + dbName, p);
            for (Rec r : recs) {
                boolean skip = false;
                for (String[] c : r.conds) {
                    boolean match = OURS.contains(c[1].toLowerCase(Locale.ROOT));
                    if ((c[0].equals("skipif") && match) || (c[0].equals("onlyif") && !match))
                        skip = true;
                    if (skip) {
                        res.skippedBy.merge(c[0] + " " + c[1], 1, Integer::sum);
                        break;
                    }
                }
                if (skip) {
                    skipped++;
                    continue;
                }
                executed++;
                if (r.type.equals("halt"))
                    break;
                if (r.type.equals("hash-threshold")) {
                    try {
                        hashThreshold = Integer.parseInt(r.arg.trim());
                    } catch (Exception ignore) {
                    }
                    continue;
                }
                if (r.type.equals("statement")) {
                    boolean expectError = r.arg.equals("error");
                    boolean gotError = false;
                    Exception ex = null;
                    try (Statement st = conn.createStatement()) {
                        st.execute(r.sql);
                    } catch (SQLException e) {
                        gotError = true;
                        ex = e;
                    } catch (RuntimeException e) {
                        gotError = true;
                        ex = e;
                    }
                    if (expectError) {
                        if (gotError)
                            stmtErrPass++;
                        else {
                            stmtErrFail++;
                            Failure f = new Failure();
                            f.file = relFile;
                            f.kind = "statement";
                            f.subtype = "expected_error_got_ok";
                            f.line = r.line;
                            f.sql = clip(r.sql, 500);
                            f.expected = "error";
                            f.actual = "ok";
                            f.detail = "";
                            f.sqlHasAll = ALL_TOKEN.matcher(r.sql).find();
                            res.failures.add(f);
                        }
                    } else {
                        if (!gotError)
                            stmtOkPass++;
                        else {
                            stmtOkFail++;
                            Failure f = new Failure();
                            f.file = relFile;
                            f.kind = "statement";
                            f.subtype = "stmt_error";
                            f.line = r.line;
                            f.sql = clip(r.sql, 500);
                            f.expected = "ok";
                            f.actual = "error";
                            f.detail = clip(ex == null ? "" : ex.getClass().getSimpleName() + ": "
                                    + ex.getMessage(), 300);
                            f.sqlHasAll = ALL_TOKEN.matcher(r.sql).find();
                            res.failures.add(f);
                        }
                    }
                    continue;
                }
                if (!r.type.equals("query"))
                    continue;
                qTotal++;
                char[] types = r.arg.toCharArray();
                boolean hashed = false;
                Failure f = null;
                List<String> flat = new ArrayList<>();
                List<String> expectedView = r.expected;
                if (r.expected.size() == 1
                        && r.expected.get(0).matches("\\d+ values hashing to [0-9a-f]{32}")) {
                    hashExpected++;
                    hashed = true;
                } else
                    litExpected++;
                try (Statement st = conn.createStatement()) {
                    try (ResultSet rs = st.executeQuery(r.sql)) {
                        ResultSetMetaData m = rs.getMetaData();
                        if (m.getColumnCount() != types.length) {
                            f = failure(relFile, r, "column_count", "types=" + r.arg,
                                    "columns=" + m.getColumnCount(),
                                    "type-string length != result column count");
                            fCol++;
                        } else {
                            List<String[]> rows = new ArrayList<>();
                            while (rs.next()) {
                                String[] row = new String[types.length];
                                for (int c = 0; c < types.length; c++)
                                    row[c] = render(rs, c + 1, types[c]);
                                rows.add(row);
                            }
                            sortRows(rows, r.sort);
                            // rowsort/valuesort 的规范是"两侧都按同样方式排序后再比较";
                            // 只排实际结果会让"期望值不是升序"的用例误判(而且 sort=... 的语义也没落实)
                            List<String> expectedSorted = r.expected;
                            if (r.sort.equals("rowsort") || r.sort.equals("valuesort"))
                                expectedSorted = sortExpected(r.expected, r.sort);
                            for (String[] row : rows)
                                flat.addAll(Arrays.asList(row));
                            String hash = null;
                            if (hashed || !r.label.isEmpty())
                                hash = md5(md, flat);
                            if (!r.label.isEmpty()) {
                                labelChecks++;
                                String prev = labels.putIfAbsent(r.label, hash);
                                if (prev != null && !prev.equals(hash)) {
                                    labelFails++;
                                    Failure lf = new Failure();
                                    lf.file = relFile;
                                    lf.kind = "label";
                                    lf.subtype = "label_mismatch";
                                    lf.line = r.line;
                                    lf.sql = clip(r.sql, 500);
                                    lf.expected = "hash " + prev;
                                    lf.actual = "hash " + hash;
                                    lf.detail = "label " + r.label + " sees different results";
                                    res.failures.add(lf);
                                }
                            }
                            if (hashed) {
                                String[] parts = r.expected.get(0).split(" ");
                                long wantN = Long.parseLong(parts[0]);
                                String wantH = parts[4];
                                if (wantN != flat.size() || !wantH.equals(hash)) {
                                    f = failure(relFile, r, "hash_mismatch",
                                            wantN + " values md5=" + wantH,
                                            flat.size() + " values md5=" + hash + " first: "
                                                    + join3(flat),
                                            "type=" + r.arg + " sort=" + r.sort);
                                    fHash++;
                                }
                            } else if (expectedSorted.isEmpty()) {
                                if (!flat.isEmpty()) {
                                    f = failure(relFile, r, "row_count", "0 rows",
                                            (flat.size() + " values: " + join3(flat)),
                                            "expected empty result");
                                    fRow++;
                                }
                            } else if (expectedSorted.size() != flat.size()) {
                                f = failure(relFile, r, "row_count",
                                        expectedSorted.size() + " values: " + join3(expectedSorted),
                                        flat.size() + " values: " + join3(flat),
                                        "type=" + r.arg + " sort=" + r.sort);
                                fRow++;
                            } else {
                                for (int i = 0; i < flat.size(); i++) {
                                    if (!expectedSorted.get(i).equals(flat.get(i))) {
                                        f = failure(relFile, r, "value",
                                                "[" + i + "] " + expectedSorted.get(i),
                                                "[" + i + "] " + flat.get(i) + "  (all: "
                                                        + join3(flat) + ")",
                                                "type=" + r.arg + " sort=" + r.sort
                                                        + " expectedAll="
                                                        + join3(r.expected));
                                        fVal++;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    f = failure(relFile, r, "exception",
                            join3(expectedView),
                            "SQLException",
                            clip(e.getClass().getSimpleName() + ": " + e.getMessage(), 300) + " [type="
                                    + r.arg + " sort=" + r.sort + "]");
                    fEx++;
                }
                if (f != null) {
                    res.failures.add(f);
                    qFail++;
                } else
                    qPass++;
            }
        } catch (Throwable t) {
            res.setupError = clip(t.getClass().getSimpleName() + ": " + t.getMessage(), 300);
        } finally {
            if (conn != null)
                try {
                    conn.close();
                } catch (Exception ignore) {
                }
        }
        res.skipped = skipped;
        res.executed = executed;
        res.qTotal = qTotal;
        res.qPass = qPass;
        res.qFail = qFail;
        res.qException = fEx;
        res.qRowCount = fRow;
        res.qValue = fVal;
        res.qHash = fHash;
        res.qColumnCount = fCol;
        res.stmtOkPass = stmtOkPass;
        res.stmtOkFail = stmtOkFail;
        res.stmtErrPass = stmtErrPass;
        res.stmtErrFail = stmtErrFail;
        res.labelChecks = labelChecks;
        res.labelFails = labelFails;
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    private static Failure failure(String relFile, Rec r, String subtype, String expected,
            String actual, String detail) {
        Failure f = new Failure();
        f.file = relFile;
        f.kind = "query";
        f.subtype = subtype;
        f.line = r.line;
        f.sql = clip(r.sql, 500);
        f.expected = expected;
        f.actual = actual;
        f.detail = detail;
        f.sqlHasAll = ALL_TOKEN.matcher(r.sql).find();
        return f;
    }

    /**
     * Runs one file in a daemon thread and gives up after {@code timeoutSec} seconds. A sqllogictest
     * file that hangs (e.g. a join that never returns) is reported as {@code TIMEOUT} instead of
     * blocking the whole run.
     */
    public static FileResult runFileWithTimeout(Path file, String relFile, String dbName,
            int timeoutSec) {
        final FileResult[] box = new FileResult[1];
        long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> box[0] = runFile(file, relFile, dbName), "slt-" + dbName);
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutSec > 0 ? timeoutSec * 1000L : 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive() || box[0] == null) {
            FileResult r = new FileResult();
            r.file = relFile;
            r.timeout = t.isAlive();
            r.ms = System.currentTimeMillis() - t0;
            if (!r.timeout)
                r.setupError = "runner thread produced no result";
            r.status = "TIMEOUT";
            return r;
        }
        return box[0];
    }

    // ------------------------------------------------------------------------------------------
    // machine readable output
    // ------------------------------------------------------------------------------------------

    /** Writes &lt;slug&gt;.json per file plus summary.tsv and failures.tsv into {@code outDir}. */
    public static void writeReport(Path outDir, List<FileResult> results) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder summary = new StringBuilder();
        summary.append("file\tqueries\tpass\tfail\tallowlisted\tunexpected\tbaseline\tdelta")
                .append("\tstmt_fail\tskipped\tms\tstatus\n");
        StringBuilder fails = new StringBuilder();
        fails.append("file\tline\tkind\tsubtype\tallow_rule\tsql\texpected\tactual\tdetail\n");
        for (FileResult r : results) {
            String slug = slugFor(r.file);
            Files.write(outDir.resolve(slug + ".json"),
                    (jsonFor(r) + "\n").getBytes(StandardCharsets.UTF_8));
            summary.append(r.file).append('\t').append(r.qTotal).append('\t').append(r.qPass)
                    .append('\t').append(r.gatedFail).append('\t').append(r.allowlisted).append('\t')
                    .append(r.unexpected).append('\t').append(r.baseline).append('\t')
                    .append(r.delta).append('\t').append(r.stmtOkFail + r.stmtErrFail).append('\t')
                    .append(r.skipped).append('\t').append(r.ms).append('\t').append(r.status)
                    .append('\n');
            for (Failure f : r.failures) {
                fails.append(f.file).append('\t').append(f.line).append('\t').append(f.kind)
                        .append('\t').append(f.subtype).append('\t').append(f.allowRule).append('\t')
                        .append(tsv(f.sql)).append('\t').append(tsv(f.expected)).append('\t')
                        .append(tsv(f.actual)).append('\t').append(tsv(f.detail)).append('\n');
            }
        }
        Files.write(outDir.resolve("summary.tsv"), summary.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(outDir.resolve("failures.tsv"),
                fails.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonFor(FileResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"file\":\"").append(json(r.file)).append('"');
        sb.append(",\"records\":").append(r.records);
        sb.append(",\"executed\":").append(r.executed);
        sb.append(",\"skipped\":").append(r.skipped);
        sb.append(",\"queries\":").append(r.qTotal);
        sb.append(",\"pass\":").append(r.qPass);
        sb.append(",\"fail\":").append(r.qFail);
        sb.append(",\"fail_exception\":").append(r.qException);
        sb.append(",\"fail_value\":").append(r.qValue);
        sb.append(",\"fail_row_count\":").append(r.qRowCount);
        sb.append(",\"fail_hash\":").append(r.qHash);
        sb.append(",\"fail_column_count\":").append(r.qColumnCount);
        sb.append(",\"stmt_ok_pass\":").append(r.stmtOkPass);
        sb.append(",\"stmt_ok_fail\":").append(r.stmtOkFail);
        sb.append(",\"stmt_err_pass\":").append(r.stmtErrPass);
        sb.append(",\"stmt_err_fail\":").append(r.stmtErrFail);
        sb.append(",\"label_checks\":").append(r.labelChecks);
        sb.append(",\"label_fails\":").append(r.labelFails);
        sb.append(",\"allowlisted\":").append(r.allowlisted);
        sb.append(",\"unexpected\":").append(r.unexpected);
        sb.append(",\"baseline\":").append(r.baseline);
        sb.append(",\"delta\":").append(r.delta);
        sb.append(",\"status\":\"").append(json(r.status)).append('"');
        sb.append(",\"timeout\":").append(r.timeout);
        sb.append(",\"ms\":").append(r.ms);
        sb.append(",\"setup_error\":\"").append(json(r.setupError)).append('"');
        sb.append(",\"skipped_by\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : r.skippedBy.entrySet()) {
            if (!first)
                sb.append(',');
            first = false;
            sb.append('"').append(json(e.getKey())).append("\":").append(e.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // command line entry point (the JUnit wrapper uses the methods above directly)
    // ------------------------------------------------------------------------------------------

    /** Reads a file list, ignoring blank lines and {@code #} comments. */
    public static List<String> readFileList(Path listFile) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#"))
                continue;
            out.add(s);
        }
        return out;
    }

    /** Deletes a directory tree; refused unless the name looks like an SLT scratch directory. */
    public static void resetBaseDir(Path baseDir) throws IOException {
        Path abs = baseDir.toAbsolutePath().normalize();
        if (!abs.toString().contains("slt")) // safety net against a wrong -Dlealone.base.dir
            throw new IOException("refusing to delete " + abs + " (path does not contain 'slt')");
        if (Files.exists(abs))
            deleteRecursively(abs);
        Files.createDirectories(abs);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.list(p)) {
                for (Path c : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator)
                    deleteRecursively(c);
            }
        }
        Files.deleteIfExists(p);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : args) {
            if (!a.startsWith("--"))
                continue;
            int eq = a.indexOf('=');
            if (eq < 0)
                m.put(a.substring(2), "true");
            else
                m.put(a.substring(2, eq), a.substring(eq + 1));
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        if (a.containsKey("help")) {
            System.out.println("usage: SltRunner --dir=<suite/test dir> --files=<file list>"
                    + " --out=<report dir> [--baseline=<baseline.txt>] [--allowlist=<allowlist.txt>]"
                    + " [--timeout=120]");
            return;
        }
        Path dir = Paths.get(a.getOrDefault("dir", DEFAULT_SUITE_DIR));
        Path baseDir = Paths.get(a.getOrDefault("base-dir",
                System.getProperty("lealone.base.dir", "target/slt-db")));
        Path outDir = Paths.get(a.getOrDefault("out", "target/slt-report"));
        int timeout = Integer.parseInt(a.getOrDefault("timeout", "120"));
        List<String> files;
        if (a.containsKey("files")) {
            files = readFileList(Paths.get(a.get("files")));
        } else {
            files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".test"))
                        .forEach(p -> files.add(dir.relativize(p).toString().replace('\\', '/')));
            }
            files.sort(String::compareTo);
        }
        if (!Files.isDirectory(dir)) {
            System.out.println("[slt] SKIP: suite directory not found: " + dir.toAbsolutePath());
            return;
        }
        resetBaseDir(baseDir);
        System.out.println("[slt] suite=" + dir.toAbsolutePath() + " files=" + files.size()
                + " baseDir=" + baseDir.toAbsolutePath() + " timeout=" + timeout + "s");
        List<FileResult> results = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (String rel : files) {
            Path f = dir.resolve(rel);
            if (!Files.isRegularFile(f)) {
                FileResult r = new FileResult();
                r.file = rel;
                r.setupError = "file not found: " + f;
                r.status = "MISSING";
                results.add(r);
                continue;
            }
            FileResult r = runFileWithTimeout(f, rel, dbNameFor(rel), timeout);
            results.add(r);
            System.out.println("[slt] " + rel + ": " + r.qTotal + " queries, " + r.qPass + " pass, "
                    + r.gatedFail + " fail, " + r.ms + "ms" + (r.timeout ? " TIMEOUT" : ""));
        }
        List<SltBaseline.Rule> rules = new ArrayList<>();
        SltBaseline.Baseline baseline = new SltBaseline.Baseline();
        if (a.containsKey("allowlist") && Files.isRegularFile(Paths.get(a.get("allowlist"))))
            rules = SltBaseline.loadAllowlist(Paths.get(a.get("allowlist")));
        if (a.containsKey("baseline") && Files.isRegularFile(Paths.get(a.get("baseline"))))
            baseline = SltBaseline.loadBaseline(Paths.get(a.get("baseline")));
        SltBaseline.classify(results, rules, baseline);
        writeReport(outDir, results);
        String table = SltBaseline.deltaTable(results, true);
        System.out.println(table);
        Files.write(outDir.resolve("delta.txt"), table.getBytes(StandardCharsets.UTF_8));
        System.out.println("[slt] total wall time " + (System.currentTimeMillis() - t0) + " ms, report in "
                + outDir.toAbsolutePath());
        int bad = SltBaseline.regressions(results, true).size();
        System.out.println("[slt] " + (bad == 0 ? "PASS (no new failures)" : ("FAIL: " + bad + " file(s)")));
        if (bad != 0)
            System.exit(1);
    }
}
