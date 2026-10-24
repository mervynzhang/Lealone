/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.script;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Properties;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbSetting;
import com.lealone.test.TestBase;

/**
 * H2风格的SQL脚本测试(精简版)，运行时递归遍历src/test/resources/sql目录加载所有.sql文件。
 * <p>
 * 每个SQL语句以';'结束，后面跟着以'&gt;'开头的期望输出: <br>
 * 查询: 列名 / '-'分隔线 / 数据行... / rows: N(无序比较) 或 rows (ordered): N(按顺序比较) <br>
 * 一行一列时可以用 &gt;&gt; value 直接比较值；更新语句用 &gt; update count: N，
 * 没有更新计数时用 &gt; ok；异常用 &gt; exception 23505，只写数字错误码。
 * <p>
 * Lealone的DDL执行后更新计数是0，所以DDL的期望输出是 update count: 0。
 * 不支持H2脚本里的?参数和@reconnect等指令。只收录当前Lealone确实通过的语句，
 * 已知差异要么按Lealone的实际行为写期望值并加LEALONE注释说明，要么不收录。
 */
public class SqlScriptTest extends TestBase {

    private static final String DRIVER = "com.lealone.client.jdbc.JdbcDriver";
    // 脚本在classpath和源码目录下的位置
    private static final String RESOURCE_DIR = "sql";
    private static final String SOURCE_DIR = "src/test/resources/sql";

    private Connection conn;
    private Statement stat;
    private BufferedReader in;
    private String fileName;
    private int lineNumber;
    // 已经读取但还需要重新处理的行
    private final Deque<String> putBack = new ArrayDeque<>();
    // 当前结果集的全部行
    private final List<String[]> rows = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int statementCount;

    @Test
    public void runSqlScripts() throws Exception {
        File dir = findScriptDir();
        List<String> files = new ArrayList<>();
        collectScriptFiles(dir, dir, files);
        assertFalse("没有找到SQL脚本: " + dir.getAbsolutePath(), files.isEmpty());
        // 每次运行用不同的数据库名，避免上一次运行留下的数据影响结果
        String runId = Long.toString(System.nanoTime());
        for (String file : files)
            runScript(dir, file, runId);
        System.out.println("[sqlscript] " + files.size() + "个脚本, " + statementCount + "条语句");
        if (!errors.isEmpty()) {
            StringBuilder buff = new StringBuilder();
            buff.append("SQL脚本测试失败 ").append(errors.size()).append("处:\n");
            for (String error : errors)
                buff.append(error).append('\n');
            fail(buff.toString());
        }
    }

    // 优先从classpath目录加载，IDE里classpath不是目录时退回源码目录
    private static File findScriptDir() throws Exception {
        String dir = System.getProperty("sqlscript.dir");
        if (dir != null)
            return new File(dir);
        URL url = SqlScriptTest.class.getResource("/" + RESOURCE_DIR);
        if (url != null && "file".equals(url.getProtocol()))
            return new File(url.toURI());
        File file = new File(SOURCE_DIR);
        assertTrue("找不到SQL脚本目录: " + SOURCE_DIR, file.isDirectory());
        return file;
    }

    private static void collectScriptFiles(File root, File dir, List<String> files) {
        File[] children = dir.listFiles();
        if (children == null)
            return;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory())
                collectScriptFiles(root, child, files);
            else if (child.getName().endsWith(".sql")) {
                String path = child.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                files.add(path.replace(File.separatorChar, '/'));
            }
        }
    }

    private void runScript(File dir, String file, String runId) throws Exception {
        fileName = file;
        lineNumber = 0;
        putBack.clear();
        Properties properties = new Properties();
        properties.setProperty("user", DEFAULT_USER);
        properties.setProperty("password", DEFAULT_PASSWORD);
        // 每个脚本用一个独立的内存数据库，互相不影响，跑完也不会留下数据目录
        properties.setProperty(DbSetting.PERSISTENT.name(), "false");
        Class.forName(DRIVER);
        // embedded模式，不依赖TCP端口
        String url = "jdbc:lealone:embed:sqlscript_" + slug(file) + "_" + runId;
        try (Connection c = DriverManager.getConnection(url, properties);
                Statement s = c.createStatement();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(new File(dir, file)), StandardCharsets.UTF_8))) {
            conn = c;
            stat = s;
            in = reader;
            runStatements();
        } finally {
            conn = null;
            stat = null;
            in = null;
        }
    }

    private static String slug(String file) {
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < file.length() && buff.length() < 40; i++) {
            char c = file.charAt(i);
            buff.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return buff.toString();
    }

    private void runStatements() throws Exception {
        StringBuilder buff = new StringBuilder();
        for (String line; (line = readLine()) != null;) {
            if (line.startsWith(">"))
                addError("<SQL语句>", line, null);
            else if (line.endsWith(";")) {
                buff.append(line, 0, line.length() - 1);
                String sql = buff.toString().trim();
                buff.setLength(0);
                process(sql);
            } else
                buff.append(line).append('\n');
        }
        assertTrue(fileName + " 最后有未用';'结束的语句: " + buff, buff.length() == 0);
    }

    private void process(String sql) throws Exception {
        statementCount++;
        try {
            if (stat.execute(sql))
                writeResultSet(stat.getResultSet());
            else {
                // Lealone的DDL返回的更新计数是0，只有没有更新计数时才输出ok
                int count = stat.getUpdateCount();
                writeResult(count < 0 ? "ok" : "update count: " + count, null);
            }
        } catch (Exception e) {
            writeResult("exception " + getErrorCode(e), e.toString());
        }
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
        String line = readLine();
        if (line != null && line.startsWith(">> "))
            checkSingleValue(line);
        else {
            if (line != null)
                putBack.addFirst(line);
            checkRows(max, head);
        }
    }

    // >> value 只比较一行一列时的值
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
    private void checkRows(int[] max, String[] head) throws IOException {
        writeResult(format(head, max), null);
        writeResult(format(null, max), null);
        List<String> expectedRows = new ArrayList<>();
        String marker = null;
        while (true) {
            String line = readLine();
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
                // 不是期望输出，放回去按SQL语句处理，避免后面的期望值全部错位
                putBack.addFirst(line);
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

    private void writeResult(String actual, String detail) throws IOException {
        String expected = readLine();
        String got = ("> " + actual).trim();
        if (expected == null || !expected.equals(got))
            addError(expected, got, detail);
        if (expected != null && !expected.startsWith(">"))
            putBack.addFirst(expected);
    }

    private void addError(String expected, String got, String detail) {
        StringBuilder buff = new StringBuilder();
        buff.append(fileName).append(':').append(lineNumber).append('\n');
        buff.append("  exp: ").append(expected).append('\n');
        buff.append("  got: ").append(got);
        if (detail != null)
            buff.append('\n').append("  why: ").append(detail);
        errors.add(buff.toString());
    }

    // 读取一行有效内容，跳过空行、注释行，处理putBack里的行
    private String readLine() throws IOException {
        while (true) {
            String line;
            if (!putBack.isEmpty())
                line = putBack.pollFirst();
            else {
                line = in.readLine();
                if (line == null)
                    return null;
                lineNumber++;
                line = line.trim();
            }
            if (line.isEmpty() || line.startsWith("--"))
                continue;
            return line;
        }
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
}
