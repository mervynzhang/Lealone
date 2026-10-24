/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.Result;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.StatementBase;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ValueExpression;

/**
 * 在PL/pgSQL解释器里执行内嵌SQL和表达式。
 * 表达式中的PL/pgSQL变量在解析期被替换成常量表达式，
 * 因此表名、SET的列名、INSERT的列名等位置不会被误当成变量。
 */
public class PlpgSqlSqlExecutor {

    private final ServerSession session;
    // 声明类型文本到Value类型常量的缓存
    private final Map<String, Integer> typeCache = new HashMap<>();

    public PlpgSqlSqlExecutor(ServerSession session) {
        this.session = session;
    }

    /**
     * 执行SELECT &lt;expression&gt;，返回第一行第一列，没有行时返回NULL。
     */
    public Value evalExpression(String expression, PlpgSqlVariableResolver resolver) {
        if (expression == null || expression.trim().isEmpty())
            return ValueNull.INSTANCE;
        List<Value[]> rows = query("SELECT " + expression, resolver, 1);
        if (rows.isEmpty())
            return ValueNull.INSTANCE;
        Value[] row = rows.get(0);
        return row.length == 0 ? ValueNull.INSTANCE : row[0];
    }

    /**
     * 执行查询，最多取maxRows行；maxRows小于等于0表示不限。
     */
    public List<Value[]> query(String sql, PlpgSqlVariableResolver resolver, int maxRows) {
        return queryWithColumns(sql, resolver, maxRows).rows;
    }

    /**
     * 执行查询并返回列名/列类型，SELECT * INTO record变量需要这些元数据。
     */
    public QueryRows queryWithColumns(String sql, PlpgSqlVariableResolver resolver, int maxRows) {
        PreparedSQLStatement statement = prepare(sql, resolver);
        if (!statement.isQuery()) {
            statement.close();
            throw DbException.get(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY);
        }
        Result result = statement.query(0);
        try {
            ArrayList<Value[]> rows = new ArrayList<>();
            if (result == null)
                return new QueryRows(rows, new String[0], new int[0]);
            int columnCount = result.getVisibleColumnCount();
            String[] columnNames = new String[columnCount];
            int[] columnTypes = new int[columnCount];
            for (int i = 0; i < columnCount; i++) {
                columnNames[i] = result.getColumnName(i);
                columnTypes[i] = result.getColumnType(i);
            }
            while ((maxRows <= 0 || rows.size() < maxRows) && result.next()) {
                // currentRow返回的数组可能会被复用，并且可能包含排序用的内部列，只复制可见列
                Value[] current = result.currentRow();
                Value[] row = new Value[columnCount];
                System.arraycopy(current, 0, row, 0, Math.min(columnCount, current.length));
                rows.add(row);
            }
            return new QueryRows(rows, columnNames, columnTypes);
        } finally {
            if (result != null)
                result.close();
            statement.close();
        }
    }

    /**
     * 执行查询并丢弃结果，PERFORM语句使用；返回读取到的行数。
     */
    public int executeQuery(String sql, PlpgSqlVariableResolver resolver) {
        PreparedSQLStatement statement = prepare(sql, resolver);
        if (!statement.isQuery()) {
            statement.close();
            throw DbException.get(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY);
        }
        Result result = statement.query(0);
        int count = 0;
        try {
            if (result != null) {
                while (result.next()) {
                    count++;
                }
            }
            return count;
        } finally {
            if (result != null)
                result.close();
            statement.close();
        }
    }

    /**
     * 执行一条SQL语句，是查询就丢弃结果，否则执行更新。
     */
    public int execute(String sql, PlpgSqlVariableResolver resolver) {
        PreparedSQLStatement statement = prepare(sql, resolver);
        try {
            if (statement.isQuery()) {
                Result result = statement.query(0);
                int count = 0;
                try {
                    if (result != null) {
                        while (result.next()) {
                            // PL/pgSQL里直接写SELECT而不加INTO时结果被丢弃
                            count++;
                        }
                    }
                } finally {
                    if (result != null)
                        result.close();
                }
                // DML ... RETURNING也走查询分支，返回行数供FOUND/ROW_COUNT使用
                return count;
            }
            return statement.update();
        } finally {
            statement.close();
        }
    }

    /**
     * 把声明类型解析成Value类型常量，支持tab.col%TYPE。
     * 注意：只保留基础类型，不保留VARCHAR(n)/NUMERIC(p,s)的长度和精度。
     */
    public int resolveType(String typeName) {
        if (typeName == null)
            return Value.UNKNOWN;
        String type = typeName.trim();
        if (type.isEmpty())
            return Value.UNKNOWN;
        // record/%ROWTYPE不是标量类型，不能拼成CAST(NULL AS ...)
        String compactType = compactTypeName(type).toLowerCase();
        if (compactType.equals("record") || compactType.startsWith("record[")
                || compactType.contains("%rowtype"))
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record/%ROWTYPE type is not a scalar type: " + typeName);
        Integer cached = typeCache.get(type);
        if (cached != null)
            return cached.intValue();

        int valueType;
        String compact = type.replaceAll("\\s+", "");
        if (compact.toLowerCase().endsWith("%type")) {
            // 去掉结尾的%TYPE，剩下tab.col
            String prefix = compact.substring(0, compact.length() - 5);
            int dot = prefix.lastIndexOf('.');
            if (dot <= 0 || dot == prefix.length() - 1) {
                throw DbException.getSyntaxError(typeName, 0, "invalid %TYPE declaration");
            }
            String table = prefix.substring(0, dot);
            String column = prefix.substring(dot + 1);
            valueType = queryColumnType("SELECT " + column + " FROM " + table + " WHERE 1=0");
        } else {
            // 借助SQL引擎自己的类型解析，用户定义类型也能处理
            valueType = queryColumnType("SELECT CAST(NULL AS " + type + ")");
        }
        typeCache.put(type, Integer.valueOf(valueType));
        return valueType;
    }

    /**
     * 解析tab%ROWTYPE，返回表的列名和类型。
     */
    public QueryRows resolveRowType(String typeName) {
        String type = typeName == null ? "" : typeName.trim();
        String compact = compactTypeName(type);
        if (!compact.toLowerCase().endsWith("%rowtype"))
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL unsupported row type: " + typeName);
        String table = compact.substring(0, compact.length() - 8);
        if (table.isEmpty())
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL invalid row type: " + typeName);
        try {
            return queryWithColumns("SELECT * FROM " + table + " WHERE 1=0", null, 0);
        } catch (DbException e) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL unsupported row type " + typeName + ": " + e.getMessage());
        }
    }

    // 去掉类型文本里的空白，但保留引号内的空白（"my tab"%ROWTYPE）
    static String compactTypeName(String typeName) {
        StringBuilder buff = new StringBuilder(typeName.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
            if (c == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                buff.append(c);
            } else if (c == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                buff.append(c);
            } else if (Character.isWhitespace(c) && !singleQuoted && !doubleQuoted) {
                // 跳过引号外的空白
            } else {
                buff.append(c);
            }
        }
        return buff.toString();
    }

    private int queryColumnType(String sql) {
        PreparedSQLStatement statement = prepare(sql, null);
        try {
            Result result = statement.query(0);
            try {
                if (result == null || result.getVisibleColumnCount() == 0) {
                    throw DbException.getSyntaxError(sql, 0, "can not resolve type");
                }
                return result.getColumnType(0);
            } finally {
                if (result != null)
                    result.close();
            }
        } finally {
            statement.close();
        }
    }

    private PreparedSQLStatement prepare(String sql, PlpgSqlVariableResolver resolver) {
        StatementBase statement = new PlpgSqlSqlParser(session, resolver).parse(sql);
        return statement.prepare();
    }

    /**
     * 拆分SELECT ... INTO，不是SELECT ... INTO时返回null。
     */
    public static SelectInto splitSelectInto(String sql) {
        if (sql == null)
            return null;
        int length = sql.length();
        int start = skipWhitespaceAndComments(sql, 0);
        int afterSelect;
        if (wordAt(sql, start, "SELECT")) {
            afterSelect = start + 6;
        } else if (wordAt(sql, start, "WITH")) {
            // WITH ... SELECT ... INTO：跳过CTE后找顶层的SELECT
            int selectPos = findTopLevelKeyword(sql, start + 4, "SELECT");
            if (selectPos < 0)
                return null;
            afterSelect = selectPos + 6;
        } else {
            return null;
        }
        if (afterSelect < length && isIdentifierPart(sql.charAt(afterSelect)))
            return null;

        int depth = 0;
        int intoStart = -1;
        int pos = afterSelect;
        while (pos < length) {
            char c = sql.charAt(pos);
            if (c == '\'') {
                pos = skipSingleQuoted(sql, pos);
            } else if (c == '"') {
                pos = skipDoubleQuoted(sql, pos);
            } else if (c == '$') {
                int next = skipDollarQuoted(sql, pos);
                pos = next > pos ? next : pos + 1;
            } else if (c == '-' && pos + 1 < length && sql.charAt(pos + 1) == '-') {
                pos = skipLineComment(sql, pos);
            } else if (c == '/' && pos + 1 < length && sql.charAt(pos + 1) == '*') {
                pos = skipBlockComment(sql, pos);
            } else if (c == '(') {
                depth++;
                pos++;
            } else if (c == ')') {
                depth--;
                pos++;
            } else {
                if (depth == 0 && wordAt(sql, pos, "INTO")) {
                    intoStart = pos;
                    break;
                }
                pos++;
            }
        }
        if (intoStart < 0)
            return null;

        int targetPos = skipWhitespaceAndComments(sql, intoStart + 4);
        boolean strict = false;
        if (wordAt(sql, targetPos, "STRICT")) {
            strict = true;
            targetPos = skipWhitespaceAndComments(sql, targetPos + 6);
        }
        ArrayList<String> targets = new ArrayList<>();
        int targetEnd = targetPos;
        while (true) {
            targetPos = skipWhitespaceAndComments(sql, targetPos);
            if (targetPos >= length)
                break;
            String name;
            char c = sql.charAt(targetPos);
            if (c == '"') {
                int end = skipDoubleQuoted(sql, targetPos);
                name = sql.substring(targetPos + 1, end - 1).replace("\"\"", "\"");
                targetPos = end;
            } else if (isIdentifierStart(c)) {
                int end = targetPos + 1;
                while (end < length && isIdentifierPart(sql.charAt(end)))
                    end++;
                name = sql.substring(targetPos, end);
                targetPos = end;
            } else {
                break;
            }
            if (targetPos < length && sql.charAt(targetPos) == '.') {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "SELECT INTO record field: " + name);
            }
            targets.add(name);
            targetEnd = targetPos;
            int next = skipWhitespaceAndComments(sql, targetPos);
            if (next < length && sql.charAt(next) == ',') {
                targetPos = next + 1;
            } else {
                break;
            }
        }
        if (targets.isEmpty())
            throw DbException.getSyntaxError(sql, intoStart, "SELECT INTO without target");
        // 把INTO以及目标列表从查询里去掉，保留前后的SQL文本
        String query = (sql.substring(0, intoStart) + " " + sql.substring(targetEnd)).trim();
        return new SelectInto(query, strict, targets.toArray(new String[0]));
    }

    // 查找括号深度为0的关键字，跳过字符串和注释
    private static int findTopLevelKeyword(String sql, int from, String keyword) {
        int length = sql.length();
        int depth = 0;
        int pos = from;
        while (pos < length) {
            char c = sql.charAt(pos);
            if (c == '\'') {
                pos = skipSingleQuoted(sql, pos);
            } else if (c == '"') {
                pos = skipDoubleQuoted(sql, pos);
            } else if (c == '$') {
                int next = skipDollarQuoted(sql, pos);
                pos = next > pos ? next : pos + 1;
            } else if (c == '-' && pos + 1 < length && sql.charAt(pos + 1) == '-') {
                pos = skipLineComment(sql, pos);
            } else if (c == '/' && pos + 1 < length && sql.charAt(pos + 1) == '*') {
                pos = skipBlockComment(sql, pos);
            } else if (c == '(') {
                depth++;
                pos++;
            } else if (c == ')') {
                depth--;
                pos++;
            } else {
                if (depth == 0 && wordAt(sql, pos, keyword))
                    return pos;
                pos++;
            }
        }
        return -1;
    }

    public static class QueryRows {

        public final List<Value[]> rows;
        public final String[] columnNames;
        public final int[] columnTypes;

        QueryRows(List<Value[]> rows, String[] columnNames, int[] columnTypes) {
            this.rows = rows;
            this.columnNames = columnNames;
            this.columnTypes = columnTypes;
        }
    }

    public static class SelectInto {

        public final String query;
        public final boolean strict;
        public final String[] targets;

        SelectInto(String query, boolean strict, String[] targets) {
            this.query = query;
            this.strict = strict;
            this.targets = targets;
        }
    }

    // 只有表达式位置的标识符才替换成变量值，限定名/函数名/类型字面量前缀不替换
    private static class PlpgSqlSqlParser extends LealoneSQLParser {

        private final PlpgSqlVariableResolver resolver;
        // 记录最近读到的标识符，readTermObjectDot里用来判断r.col是不是record字段
        private String lastIdentifier;
        private boolean lastIdentifierQuoted;

        PlpgSqlSqlParser(ServerSession session, PlpgSqlVariableResolver resolver) {
            super(session);
            this.resolver = resolver;
        }

        @Override
        protected Expression readTerm() {
            if (currentTokenType == IDENTIFIER && resolver != null) {
                PlpgSqlVariable variable = resolver.findVariable(currentToken, currentTokenQuoted);
                if (variable != null && canSubstitute()) {
                    if (variable.isRecord())
                        throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                                "PL/pgSQL record variable " + variable.getName()
                                        + " can only be used as record.field");
                    read();
                    return ValueExpression.get(variable.getValue());
                }
                lastIdentifier = currentToken;
                lastIdentifierQuoted = currentTokenQuoted;
            } else {
                lastIdentifier = null;
            }
            return super.readTerm();
        }

        @Override
        protected Expression readTermObjectDot(String objectName) {
            if (resolver != null && lastIdentifier != null
                    && objectName.equals(lastIdentifier)) {
                PlpgSqlVariable variable = resolver.findVariable(lastIdentifier,
                        lastIdentifierQuoted);
                if (variable != null && variable.isRecord()) {
                    String fieldName = readColumnIdentifier();
                    return ValueExpression.get(variable.getRecordField(fieldName));
                }
                if (resolver.hasLabel(lastIdentifier) && currentTokenType == IDENTIFIER) {
                    // 只有label.var真的解析到变量时才替换，否则回落到SQL的alias.col/schema.table
                    String name = currentToken;
                    boolean quoted = currentTokenQuoted;
                    PlpgSqlVariable qualified = resolver.findVariableByLabel(lastIdentifier, name,
                            quoted);
                    if (qualified != null) {
                        read();
                        if (qualified.isRecord())
                            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                                    "PL/pgSQL qualified record field: " + lastIdentifier + "."
                                            + name);
                        return ValueExpression.get(qualified.getValue());
                    }
                }
            }
            return super.readTermObjectDot(objectName);
        }

        // 看当前token后面的第一个非空白字符，判断它是不是限定名、函数调用或字面量前缀
        private boolean canSubstitute() {
            int i = parseIndex;
            char[] chars = sqlCommandChars;
            while (i < chars.length && Character.isWhitespace(chars[i]))
                i++;
            if (i >= chars.length)
                return true;
            char c = chars[i];
            if (c == 0)
                return true;
            return c != '.' && c != '(' && c != '\'' && c != '"';
        }
    }

    private static boolean wordAt(String sql, int pos, String word) {
        int length = sql.length();
        if (pos < 0 || pos + word.length() > length)
            return false;
        if (!sql.regionMatches(true, pos, word, 0, word.length()))
            return false;
        if (pos > 0 && isIdentifierPart(sql.charAt(pos - 1)))
            return false;
        int end = pos + word.length();
        if (end < length && isIdentifierPart(sql.charAt(end)))
            return false;
        return true;
    }

    private static int skipWhitespaceAndComments(String sql, int pos) {
        int length = sql.length();
        while (pos < length) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && pos + 1 < length && sql.charAt(pos + 1) == '-') {
                pos = skipLineComment(sql, pos);
            } else if (c == '/' && pos + 1 < length && sql.charAt(pos + 1) == '*') {
                pos = skipBlockComment(sql, pos);
            } else {
                break;
            }
        }
        return pos;
    }

    private static int skipLineComment(String sql, int pos) {
        int length = sql.length();
        pos += 2;
        while (pos < length && sql.charAt(pos) != '\n')
            pos++;
        return pos;
    }

    private static int skipBlockComment(String sql, int pos) {
        int length = sql.length();
        pos += 2;
        int level = 1;
        while (pos < length) {
            if (pos + 1 < length && sql.charAt(pos) == '/' && sql.charAt(pos + 1) == '*') {
                level++;
                pos += 2;
            } else if (pos + 1 < length && sql.charAt(pos) == '*'
                    && sql.charAt(pos + 1) == '/') {
                level--;
                pos += 2;
                if (level == 0)
                    return pos;
            } else {
                pos++;
            }
        }
        return pos;
    }

    private static int skipSingleQuoted(String sql, int pos) {
        int length = sql.length();
        pos++;
        while (pos < length) {
            if (sql.charAt(pos) == '\'') {
                if (pos + 1 < length && sql.charAt(pos + 1) == '\'') {
                    pos += 2;
                } else {
                    return pos + 1;
                }
            } else {
                pos++;
            }
        }
        return pos;
    }

    private static int skipDoubleQuoted(String sql, int pos) {
        int length = sql.length();
        pos++;
        while (pos < length) {
            if (sql.charAt(pos) == '"') {
                if (pos + 1 < length && sql.charAt(pos + 1) == '"') {
                    pos += 2;
                } else {
                    return pos + 1;
                }
            } else {
                pos++;
            }
        }
        return pos;
    }

    // 返回美元引用字符串结束后的位置，不是合法美元引用时返回-1
    private static int skipDollarQuoted(String sql, int pos) {
        int length = sql.length();
        int tagEnd = pos + 1;
        while (tagEnd < length) {
            char c = sql.charAt(tagEnd);
            if (c == '$')
                break;
            boolean valid = tagEnd == pos + 1 ? Character.isJavaIdentifierStart(c) || c == '_'
                    : Character.isJavaIdentifierPart(c);
            if (!valid)
                return -1;
            tagEnd++;
        }
        if (tagEnd >= length || sql.charAt(tagEnd) != '$')
            return -1;
        String tag = sql.substring(pos, tagEnd + 1);
        int contentEnd = sql.indexOf(tag, tagEnd + 1);
        return contentEnd < 0 ? length : contentEnd + tag.length();
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '$' || Character.isJavaIdentifierPart(c);
    }
}
