/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.pg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbSetting;
import com.lealone.db.SysProperties;
import com.lealone.storage.fs.FileUtils;

/**
 * PostgreSQL 函数/存储过程回归脚本的执行引擎。
 * <p>
 * 脚本格式和 {@code com.lealone.test.script.SqlScriptTest} 一样(H2 风格): 语句以 ';' 结束，
 * 后面跟着以 '&gt;' 开头的期望输出。查询是 列名 / 分隔线 / 数据行... / rows: N；
 * 一行一列可以用 &gt;&gt; value；更新语句用 &gt; update count: N，没有更新计数时用 &gt; ok；
 * 异常用 &gt; exception 错误码。
 * <p>
 * 在 H2 风格之上多了两个标记:
 * <ul>
 * <li>{@code -- @case <id>}: 一个 PG 用例的开始，用例是 skip 清单和统计的单位；</li>
 * <li>{@code -- @pg <PG源文件>:<行号范围>}: 这个用例抄自 PG 回归测试的哪一段，
 * 每个用例都必须写，避免"来路不明"的用例。</li>
 * </ul>
 * 一个脚本文件对应一个独立的嵌入式库，和 PG 回归一个测试文件跑一次一样：文件内的用例按顺序
 * 共享对象(先建函数后面调用)，文件之间互不影响。跑完删除库目录，不留下数据。
 */
public class PgRegressRunner {

    private static final String DRIVER = "com.lealone.client.jdbc.JdbcDriver";

    /** 一个 -- @case 块，包括它的语句和期望输出。 */
    public static final class PgCase {
        public final String file;
        public final String id;
        public final int line;
        public String pgRef = "";
        public final List<String> lines = new ArrayList<>();
        public boolean skipped;
        public int statements;

        PgCase(String file, String id, int line) {
            this.file = file;
            this.id = id;
            this.line = line;
        }
    }

    /** 一个脚本文件的执行结果。 */
    public static final class FileResult {
        public final String file;
        public int runCases;
        public int skippedCases;
        public int statements;
        public int passed;
        public int skippedStatements;
        public final List<String> errors = new ArrayList<>();

        FileResult(String file) {
            this.file = file;
        }

        public int getCases() {
            return runCases + skippedCases;
        }
    }

    /** 按行喂给执行器，跳过空行和注释，支持 putBack(期望输出被提前读出来时用)。 */
    private static final class LineSource {
        private final List<String> lines;
        private final Deque<String> putBack = new ArrayDeque<>();
        private int index;

        LineSource(List<String> lines) {
            this.lines = lines;
        }

        /** @return 下一行有效内容，没有时返回 null */
        String readLine() {
            while (true) {
                String line;
                if (!putBack.isEmpty())
                    line = putBack.pollFirst();
                else {
                    if (index >= lines.size())
                        return null;
                    line = lines.get(index++).trim();
                }
                if (line.isEmpty() || line.startsWith("--"))
                    continue;
                return line;
            }
        }

        void putBack(String line) {
            putBack.addFirst(line);
        }
    }

    private final PgSkipList skipList;
    private final List<FileResult> results = new ArrayList<>();
    private final Map<String, String> caseFiles = new LinkedHashMap<>();
    private final Map<String, PgCase> skippedCases = new LinkedHashMap<>();
    private final List<String> structureErrors = new ArrayList<>();

    private LineSource source;
    private String fileName;
    private PgCase currentCase;
    private final List<String[]> rows = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int statementsInCase;
    private int failedInCase;

    public PgRegressRunner(PgSkipList skipList) {
        this.skipList = skipList;
    }

    public List<FileResult> getResults() {
        return results;
    }

    public List<String> getStructureErrors() {
        return structureErrors;
    }

    public Map<String, PgCase> getSkippedCasesById() {
        return skippedCases;
    }

    public int getCases() {
        int n = 0;
        for (FileResult r : results)
            n += r.getCases();
        return n;
    }

    public int getRunCases() {
        int n = 0;
        for (FileResult r : results)
            n += r.runCases;
        return n;
    }

    public int getSkippedCaseCount() {
        int n = 0;
        for (FileResult r : results)
            n += r.skippedCases;
        return n;
    }

    public int getStatements() {
        int n = 0;
        for (FileResult r : results)
            n += r.statements;
        return n;
    }

    public int getPassed() {
        int n = 0;
        for (FileResult r : results)
            n += r.passed;
        return n;
    }

    public int getFailed() {
        int n = 0;
        for (FileResult r : results)
            n += r.statements - r.passed;
        return n;
    }

    public int getSkippedStatements() {
        int n = 0;
        for (FileResult r : results)
            n += r.skippedStatements;
        return n;
    }

    public List<String> getAllErrors() {
        List<String> all = new ArrayList<>();
        for (FileResult r : results)
            all.addAll(r.errors);
        return all;
    }

    /** 递归执行目录下所有 .sql 脚本，按文件名排序。 */
    public void runDirectory(File dir) throws Exception {
        List<File> files = new ArrayList<>();
        collect(dir, files);
        if (files.isEmpty())
            throw new IOException("没有找到 PG 回归脚本: " + dir.getAbsolutePath());
        Class.forName(DRIVER);
        String runId = Long.toString(System.nanoTime());
        for (File file : files)
            runFile(dir, file, runId);
        // skip 清单里的条目必须都能对上脚本里的用例，否则说明用例被删了或 id 写错了
        for (PgSkipList.Entry entry : skipList.getEntries().values()) {
            if (!skippedCases.containsKey(entry.id))
                structureErrors.add("skip 清单条目 \"" + entry.id + "\" 找不到对应的用例(-- @case)，"
                        + "用例被删除或 id 写错了; 原因原本是: " + entry.reason);
        }
    }

    private static void collect(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null)
            return;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory())
                collect(child, files);
            else if (child.getName().endsWith(".sql"))
                files.add(child);
        }
    }

    private void runFile(File dir, File file, String runId) throws Exception {
        fileName = file.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1)
                .replace(File.separatorChar, '/');
        FileResult result = new FileResult(fileName);
        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", "");
        // 每个脚本文件一个独立的库；用持久化库是因为内存库(persistent=false)下
        // 返回 VARCHAR 的函数会因为没有 LobStorage 而抛 NPE (已作为产品缺陷记录)
        properties.setProperty(DbSetting.PERSISTENT.name(), "true");
        String dbName = "pgregress_" + slug(fileName) + '_' + runId;
        String url = "jdbc:lealone:embed:" + dbName;
        try (Connection c = DriverManager.getConnection(url, properties);
                Statement s = c.createStatement()) {
            for (PgCase pgCase : parse(file)) {
                currentCase = pgCase;
                if (caseFiles.containsKey(pgCase.id))
                    structureErrors.add("用例id重复: \"" + pgCase.id + "\" 同时出现在 "
                            + caseFiles.get(pgCase.id) + " 和 " + fileName);
                else
                    caseFiles.put(pgCase.id, fileName);
                if (pgCase.pgRef.isEmpty())
                    structureErrors.add(fileName + ':' + pgCase.line + " 用例 \"" + pgCase.id
                            + "\" 缺少 -- @pg 来源行");
                if (skipList.getEntries().containsKey(pgCase.id)) {
                    pgCase.skipped = true;
                    pgCase.statements = PgSkipList.countStatements(String.join("\n", pgCase.lines));
                    result.skippedCases++;
                    result.skippedStatements += pgCase.statements;
                    skippedCases.put(pgCase.id, pgCase);
                    continue;
                }
                result.runCases++;
                executeCase(s, pgCase, result);
            }
        } finally {
            currentCase = null;
            File dbDir = new File(SysProperties.getBaseDir(), dbName);
            if (dbDir.isDirectory())
                FileUtils.deleteRecursive(dbDir.getAbsolutePath(), false);
        }
        results.add(result);
    }

    // 把一个文件切成一串 -- @case 块，块内保留原始行(语句和期望输出)
    private static List<PgCase> parse(File file) throws IOException {
        List<PgCase> cases = new ArrayList<>();
        PgCase current = null;
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("-- @case ")) {
                current = new PgCase(file.getName(), line.substring("-- @case ".length()).trim(), i + 1);
                cases.add(current);
                continue;
            }
            if (current == null) {
                if (!line.isEmpty() && !line.startsWith("--"))
                    throw new IOException(file.getName() + ':' + (i + 1) + " 的第一条语句前缺少 -- @case");
                continue;
            }
            if (line.startsWith("-- @pg ")) {
                current.pgRef = line.substring("-- @pg ".length()).trim();
                continue;
            }
            current.lines.add(lines.get(i));
        }
        return cases;
    }

    private void executeCase(Statement stat, PgCase pgCase, FileResult result) throws Exception {
        source = new LineSource(pgCase.lines);
        statementsInCase = 0;
        failedInCase = 0;
        errors.clear();
        try {
            runStatements(stat);
        } finally {
            source = null;
        }
        pgCase.statements = statementsInCase;
        result.statements += statementsInCase;
        result.passed += statementsInCase - failedInCase;
        result.errors.addAll(errors);
    }

    private void runStatements(Statement stat) throws Exception {
        StringBuilder buff = new StringBuilder();
        for (String line; (line = source.readLine()) != null;) {
            if (line.startsWith(">")) {
                addError("<SQL语句>", line, null);
                continue;
            }
            buff.append(line).append('\n');
            // BEGIN ATOMIC ... END里面的分号不是语句结束, 要等END出现
            if (line.endsWith(";") && countOpenBlocks(buff.toString()) == 0) {
                String sql = buff.toString().trim();
                sql = sql.substring(0, sql.length() - 1);
                buff.setLength(0);
                process(stat, sql);
            }
        }
        if (buff.length() > 0) {
            addError("<用';'结束的语句>", buff.toString().replace('\n', ' '), null);
            failedInCase++;
        }
    }

    // BEGIN ATOMIC ... END和CASE ... END里面还有分号, 要等END出现后才算一条语句结束;
    // 字符串常量、$$字符串、双引号标识符和注释里的关键字不算
    private static int countOpenBlocks(String text) {
        int depth = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = skipQuoted(text, i, '\'');
            } else if (c == '"') {
                i = skipQuoted(text, i, '"');
            } else if (c == '$') {
                int next = skipDollarQuoted(text, i);
                if (next < 0)
                    return -1;
                i = next;
            } else if (c == '-' && i + 1 < len && text.charAt(i + 1) == '-') {
                i = skipLineComment(text, i);
            } else if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i);
            } else if (isWordStart(c)) {
                int end = i + 1;
                while (end < len && isWordPart(text.charAt(end)))
                    end++;
                String word = text.substring(i, end);
                if (word.equalsIgnoreCase("begin")) {
                    int j = skipSpaces(text, end);
                    if (startsWithIgnoreCase(text, j, "atomic")) {
                        depth++;
                        i = j + "atomic".length();
                        continue;
                    }
                    // 不带ATOMIC的BEGIN ... END也是SQL标准块体;
                    // begin;/begin transaction是事务语句，不能当成块体
                    if (isBodyBegin(text, j)) {
                        depth++;
                        i = j;
                        continue;
                    }
                } else if (word.equalsIgnoreCase("case")) {
                    depth++;
                } else if (word.equalsIgnoreCase("end") && depth > 0) {
                    depth--;
                }
                i = end;
            } else {
                i++;
            }
            // 字符串/注释/$$还没闭合时整段都还在语句里，不能切分
            if (i < 0)
                return -1;
        }
        return depth;
    }

    private static boolean isBodyBegin(String text, int index) {
        if (index >= text.length())
            return false;
        if (text.charAt(index) == ';')
            return false;
        return !startsWithIgnoreCase(text, index, "transaction")
                && !startsWithIgnoreCase(text, index, "work")
                && !startsWithIgnoreCase(text, index, "isolation")
                && !startsWithIgnoreCase(text, index, "read")
                && !startsWithIgnoreCase(text, index, "not")
                && !startsWithIgnoreCase(text, index, "deferrable");
    }

    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1;
        int len = text.length();
        while (i < len) {
            if (text.charAt(i) == quote) {
                // 两个连续引号是转义
                if (i + 1 < len && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    // 跳过$$...$$或者$tag$...$tag$，不是这种形式时返回-1
    private static int skipDollarQuoted(String text, int start) {
        int len = text.length();
        int i = start + 1;
        while (i < len && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'))
            i++;
        if (i >= len || text.charAt(i) != '$')
            return -1;
        String tag = text.substring(start, i + 1);
        int end = text.indexOf(tag, i + 1);
        return end < 0 ? -1 : end + tag.length();
    }

    private static int skipLineComment(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end + 1;
    }

    private static int skipBlockComment(String text, int start) {
        int end = text.indexOf("*/", start + 2);
        return end < 0 ? -1 : end + 2;
    }

    private static int skipSpaces(String text, int start) {
        int i = start;
        while (i < text.length() && Character.isWhitespace(text.charAt(i)))
            i++;
        return i;
    }

    private static boolean startsWithIgnoreCase(String text, int start, String other) {
        if (start + other.length() > text.length())
            return false;
        return text.regionMatches(true, start, other, 0, other.length());
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void process(Statement stat, String sql) throws Exception {
        statementsInCase++;
        int before = errors.size();
        try {
            if (stat.execute(sql))
                writeResultSet(stat.getResultSet());
            else {
                // Lealone 的 DDL 返回的更新计数是 0，只有没有更新计数时才输出 ok
                int count = stat.getUpdateCount();
                writeResult(count < 0 ? "ok" : "update count: " + count, null);
            }
        } catch (Exception e) {
            writeResult("exception " + getErrorCode(e), e.toString());
        }
        if (errors.size() > before)
            failedInCase++;
    }

    private static int getErrorCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DbException)
                return ((DbException) t).getErrorCode();
            if (t instanceof SQLException)
                return ((SQLException) t).getErrorCode();
        }
        return -1;
    }

    private void writeResultSet(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int len = meta.getColumnCount();
        int[] max = new int[len];
        rows.clear();
        while (rs.next()) {
            String[] row = new String[len];
            for (int i = 0; i < len; i++) {
                String data = formatString(rs.getString(i + 1));
                if (max[i] < data.length())
                    max[i] = data.length();
                row[i] = data;
            }
            rows.add(row);
        }
        String[] head = new String[len];
        for (int i = 0; i < len; i++) {
            String label = formatString(meta.getColumnLabel(i + 1));
            if (max[i] < label.length())
                max[i] = label.length();
            head[i] = label;
        }
        rs.close();
        String line = source.readLine();
        if (line != null && line.startsWith(">> "))
            checkSingleValue(line);
        else {
            if (line != null)
                source.putBack(line);
            checkRows(max, head);
        }
    }

    private void checkSingleValue(String expected) {
        String actual;
        if (rows.isEmpty())
            actual = "<no result>";
        else if (rows.size() > 1)
            actual = "<" + rows.size() + " rows>";
        else if (rows.get(0).length != 1)
            actual = "<row with " + rows.get(0).length + " values>";
        else
            actual = rows.get(0)[0];
        if (!expected.equals((">> " + actual).trim()))
            addError(expected, ">> " + actual, null);
    }

    // 期望输出依次是: 列名、分隔线、数据行、行数标记
    private void checkRows(int[] max, String[] head) {
        writeResult(format(head, max), null);
        writeResult(format(null, max), null);
        List<String> expectedRows = new ArrayList<>();
        String marker = null;
        while (true) {
            String line = source.readLine();
            if (line == null)
                break;
            if (line.startsWith("> rows (ordered): ") || line.startsWith("> rows: ")) {
                marker = line;
                break;
            }
            if (line.startsWith("> "))
                expectedRows.add(line.length() > 2 ? line.substring(2).trim() : "");
            else if (line.equals(">"))
                expectedRows.add("");
            else {
                // 不是期望输出，放回去按 SQL 语句处理，避免后面的期望值全部错位
                source.putBack(line);
                break;
            }
        }
        List<String> actualRows = new ArrayList<>(rows.size());
        for (String[] row : rows)
            actualRows.add(format(row, max).trim());
        if (marker == null) {
            addError("<rows: N>", "<nothing>", null);
            return;
        }
        int expectedCount = parseCount(marker);
        if (expectedCount != actualRows.size())
            addError(marker, "> rows: " + actualRows.size(), null);
        if (!marker.startsWith("> rows (ordered): ")) {
            Collections.sort(expectedRows);
            Collections.sort(actualRows);
        }
        for (int i = 0; i < expectedRows.size() && i < actualRows.size(); i++) {
            if (!expectedRows.get(i).equals(actualRows.get(i)))
                addError("> " + expectedRows.get(i), "> " + actualRows.get(i), null);
        }
    }

    private static int parseCount(String marker) {
        try {
            return Integer.parseInt(marker.substring(marker.lastIndexOf(':') + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void writeResult(String actual, String detail) {
        String expected = source.readLine();
        String got = ("> " + actual).trim();
        if (expected == null || !expected.equals(got))
            addError(expected, got, detail);
        if (expected != null && !expected.startsWith(">"))
            source.putBack(expected);
    }

    private void addError(String expected, String got, String detail) {
        StringBuilder buff = new StringBuilder();
        buff.append(fileName).append(" [case ").append(currentCase == null ? "?" : currentCase.id)
                .append(']').append('\n');
        buff.append("  exp: ").append(expected).append('\n');
        buff.append("  got: ").append(got);
        if (detail != null)
            buff.append('\n').append("  why: ").append(detail);
        errors.add(buff.toString());
    }

    private static String formatString(String s) {
        if (s == null)
            return "null";
        s = s.replace("\r\n", "\n").replace('\n', ' ');
        while (true) {
            String s2 = s.replace("  ", " ");
            if (s2.length() == s.length())
                return s;
            s = s2;
        }
    }

    private static String format(String[] row, int[] max) {
        int length = max.length;
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0)
                buff.append(' ');
            if (row == null) {
                for (int j = 0; j < max[i]; j++)
                    buff.append('-');
            } else {
                buff.append(row[i]);
                if (i < length - 1) {
                    for (int j = row[i].length(); j < max[i]; j++)
                        buff.append(' ');
                }
            }
        }
        return buff.toString();
    }

    private static String slug(String name) {
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < name.length() && buff.length() < 32; i++) {
            char c = name.charAt(i);
            buff.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return buff.toString();
    }
}
