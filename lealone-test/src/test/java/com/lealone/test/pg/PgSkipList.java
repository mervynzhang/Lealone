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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PG 回归用例的 skip 清单。
 * <p>
 * 清单是交付物的一部分: 每一条记录“哪个 PG 用例、来自 PG 源文件的哪一段、包含多少条 PG 语句、
 * 为什么现在跑不了”。被 skip 的用例仍然留在 {@code pg/*.sql} 脚本里(带期望输出)，只是不执行，
 * 因此跳过的内容永远可查；把用例从脚本里删掉会让清单条目找不到对应的 {@code -- @case} 而报错。
 * <p>
 * 文件格式(制表符分隔，和 {@code slt/allowlist.txt} 一致): <br>
 * {@code <case-id> \t <PG源文件:行号范围> \t <该段的PG语句数> \t <原因>}
 */
public class PgSkipList {

    public static final class Entry {
        public final String id;
        public final String pgRef;
        public final int pgStatements;
        public final String reason;

        Entry(String id, String pgRef, int pgStatements, String reason) {
            this.id = id;
            this.pgRef = pgRef;
            this.pgStatements = pgStatements;
            this.reason = reason;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    /** 头部声明的 PG 原文件总量: 文件名、语句数、是否整文件都不适用。 */
    public static final class FileTotal {
        public final String file;
        public final int statements;
        public final boolean skipAll;

        FileTotal(String file, int statements, boolean skipAll) {
            this.file = file;
            this.statements = statements;
            this.skipAll = skipAll;
        }
    }

    // 头部声明的 PG 原文件总语句数(每个 PG 文件一行 # @pg-total 文件名 语句数 converted|skip-all)
    private final Map<String, FileTotal> fileTotals = new LinkedHashMap<>();

    public Map<String, Entry> getEntries() {
        return entries;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<FileTotal> getFileTotals() {
        return new ArrayList<>(fileTotals.values());
    }

    /** 某个 PG 文件里被 skip 清单点名的语句数。 */
    public int getPgStatements(String file) {
        int n = 0;
        for (Entry e : entries.values()) {
            int colon = e.pgRef.lastIndexOf(':');
            if (colon > 0 && e.pgRef.substring(0, colon).equals(file))
                n += e.pgStatements;
        }
        return n;
    }

    public static PgSkipList load(File file) throws IOException {
        PgSkipList list = new PgSkipList();
        if (!file.isFile())
            throw new IOException("找不到 skip 清单: " + file.getAbsolutePath());
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.startsWith("# @pg-total ")) {
                String[] parts = line.substring("# @pg-total ".length()).trim().split("\\s+");
                int count = -1;
                try {
                    count = Integer.parseInt(parts[1]);
                } catch (RuntimeException e) {
                    count = -1;
                }
                if (parts.length < 2 || count <= 0) {
                    list.errors.add(file.getName() + ':' + lineNumber + " @pg-total 格式不对: " + line);
                    continue;
                }
                boolean skipAll = parts.length > 2 && "skip-all".equals(parts[2]);
                list.fileTotals.put(parts[0], new FileTotal(parts[0], count, skipAll));
                continue;
            }
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            String[] parts = raw.split("\t");
            if (parts.length < 4) {
                list.errors.add(file.getName() + ':' + lineNumber
                        + " skip 清单一行必须有4列(用例id, PG来源, PG语句数, 原因): " + line);
                continue;
            }
            String id = parts[0].trim();
            String pgRef = parts[1].trim();
            String count = parts[2].trim();
            String reason = parts[3].trim();
            if (id.isEmpty() || pgRef.isEmpty()) {
                list.errors.add(file.getName() + ':' + lineNumber + " 用例id或PG来源为空: " + line);
                continue;
            }
            if (!pgRef.contains(":")) {
                list.errors.add(file.getName() + ':' + lineNumber
                        + " PG来源必须写成 <文件>:<起始行>-<结束行>: " + pgRef);
                continue;
            }
            int statements;
            try {
                statements = Integer.parseInt(count);
            } catch (NumberFormatException e) {
                statements = -1;
            }
            if (statements <= 0) {
                list.errors.add(file.getName() + ':' + lineNumber + " PG语句数必须是正整数: " + count);
                continue;
            }
            if (reason.length() < 8) {
                list.errors.add(file.getName() + ':' + lineNumber + " 原因太笼统，必须写具体的缺失能力: "
                        + reason);
                continue;
            }
            if (list.entries.containsKey(id)) {
                list.errors.add(file.getName() + ':' + lineNumber + " 用例id重复: " + id);
                continue;
            }
            list.entries.put(id, new Entry(id, pgRef, statements, reason));
        }
        return list;
    }

    public int getPgStatements() {
        int n = 0;
        for (Entry e : entries.values())
            n += e.pgStatements;
        return n;
    }

    /**
     * 可选校验: 给了本地 PG 源码目录时，逐条核对 PG 来源的行号范围存在、并且该范围内的
     * 顶层语句数和清单里写的一致。用于维护清单，不影响 CI(CI 里没有 PG 源码)。
     */
    public void verifyAgainstPostgresSources(File sqlDir) throws IOException {
        for (Entry e : entries.values()) {
            String ref = e.pgRef;
            int colon = ref.lastIndexOf(':');
            String name = ref.substring(0, colon);
            String range = ref.substring(colon + 1);
            File f = new File(sqlDir, name);
            if (!f.isFile()) {
                errors.add("skip 清单 " + e.id + " 引用了不存在的 PG 文件: " + name);
                continue;
            }
            int start;
            int end;
            try {
                String[] r = range.split("-");
                start = Integer.parseInt(r[0].trim());
                end = r.length > 1 ? Integer.parseInt(r[1].trim()) : start;
            } catch (RuntimeException ex) {
                errors.add("skip 清单 " + e.id + " 的行号范围写错了: " + range);
                continue;
            }
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (start < 1 || end > lines.size() || start > end) {
                errors.add("skip 清单 " + e.id + " 的行号范围超出 " + name + "(共" + lines.size()
                        + "行): " + range);
                continue;
            }
            int actual = countStatements(String.join("\n", lines.subList(start - 1, end)));
            if (actual != e.pgStatements) {
                errors.add("skip 清单 " + e.id + " 声明 " + e.pgStatements + " 条PG语句, 实际 "
                        + name + ':' + range + " 有 " + actual + " 条");
            }
        }
    }

    /**
     * 统计一段 SQL 里的顶层语句数(以分号结束)。能处理行注释、单引号、双引号标识符、
     * 美元引用($$ 和 $tag$)，psql 元命令(反斜杠开头)不算语句。
     */
    public static int countStatements(String sql) {
        int count = 0;
        boolean inStatement = false;
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n')
                    i++;
                continue;
            }
            if (c == '\n' || c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }
            if (c == '\\' && !inStatement) {
                // psql 元命令，整行不算 SQL 语句
                while (i < len && sql.charAt(i) != '\n')
                    i++;
                continue;
            }
            if (c == '\'') {
                inStatement = true;
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inStatement = true;
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '$') {
                int tagEnd = i + 1;
                while (tagEnd < len && (Character.isLetterOrDigit(sql.charAt(tagEnd))
                        || sql.charAt(tagEnd) == '_'))
                    tagEnd++;
                if (tagEnd < len && sql.charAt(tagEnd) == '$') {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    inStatement = true;
                    i = close < 0 ? len : close + tag.length();
                    continue;
                }
            }
            if (c == ';') {
                if (inStatement)
                    count++;
                inStatement = false;
                i++;
                continue;
            }
            inStatement = true;
            i++;
        }
        return count;
    }
}
