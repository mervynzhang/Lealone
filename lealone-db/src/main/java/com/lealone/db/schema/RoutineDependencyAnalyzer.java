/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import java.util.ArrayList;
import java.util.HashSet;

import com.lealone.db.Database;
import com.lealone.db.DbObjectType;
import com.lealone.db.session.ServerSession;

/**
 * 分析函数/过程体(以及参数DEFAULT表达式)里对其它例程的引用。
 * 只做SQL文本级的调用识别，不解析参数类型，所以同名重载取第一个匹配的例程。
 */
public class RoutineDependencyAnalyzer {

    public static ArrayList<SchemaObject> getAllRoutines(ServerSession session) {
        Database db = session.getDatabase();
        ArrayList<SchemaObject> list = new ArrayList<>();
        for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.FUNCTION_ALIAS)) {
            if (obj instanceof SqlFunction) {
                list.add(obj);
            }
        }
        for (SchemaObject obj : db.getAllSchemaObjects(DbObjectType.PROCEDURE)) {
            list.add(obj);
        }
        return list;
    }

    public static String getSpecificName(SchemaObject obj) {
        return obj.getName() + "_" + obj.getId();
    }

    /**
     * source引用到的所有例程，按引用顺序去重。
     */
    public static ArrayList<SchemaObject> findReferencedRoutines(ServerSession session,
            SchemaObject source) {
        ArrayList<String> calls = new ArrayList<>();
        ArrayList<String> tokens = new ArrayList<>();
        scan(getSourceText(source), calls, tokens);
        ArrayList<SchemaObject> list = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String call : calls) {
            SchemaObject target = resolve(session, source, call);
            if (target != null && target != source
                    && seen.add(target.getType().value + ":" + target.getId())) {
                list.add(target);
            }
        }
        return list;
    }

    /**
     * 哪些例程引用了target(用于DROP FUNCTION时检查依赖)。
     */
    public static ArrayList<SchemaObject> findDependentRoutines(ServerSession session,
            SchemaObject target) {
        ArrayList<SchemaObject> list = new ArrayList<>();
        for (SchemaObject source : getAllRoutines(session)) {
            if (source == target) {
                continue;
            }
            ArrayList<String> calls = new ArrayList<>();
            ArrayList<String> tokens = new ArrayList<>();
            scan(getSourceText(source), calls, tokens);
            for (String call : calls) {
                if (call.equalsIgnoreCase(target.getName())) {
                    list.add(source);
                    break;
                }
            }
        }
        return list;
    }

    /**
     * 哪些例程的正文/DEFAULT里出现了给定的临时对象名(用于DISCARD TEMP级联删除)。
     * 这里比函数调用扫描宽松：字符串字面量(比如nextval('seq'))也算引用。
     */
    public static ArrayList<SchemaObject> findRoutinesReferencingNames(ServerSession session,
            ArrayList<String> names) {
        ArrayList<SchemaObject> list = new ArrayList<>();
        if (names.isEmpty()) {
            return list;
        }
        for (SchemaObject source : getAllRoutines(session)) {
            ArrayList<String> calls = new ArrayList<>();
            ArrayList<String> tokens = new ArrayList<>();
            scan(getSourceText(source), calls, tokens);
            for (String token : tokens) {
                if (containsIgnoreCase(names, token)) {
                    list.add(source);
                    break;
                }
            }
        }
        return list;
    }

    private static boolean containsIgnoreCase(ArrayList<String> names, String value) {
        for (String name : names) {
            if (name.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String getSourceText(SchemaObject obj) {
        StringBuilder buff = new StringBuilder();
        String[] defaults = null;
        if (obj instanceof SqlFunction) {
            SqlFunction f = (SqlFunction) obj;
            buff.append(f.getBody());
            defaults = f.getParameterDefaultsSQL();
        } else if (obj instanceof SqlProcedure) {
            SqlProcedure p = (SqlProcedure) obj;
            buff.append(p.getBody());
            defaults = p.getParameterDefaultsSQL();
        }
        if (defaults != null) {
            for (String s : defaults) {
                if (s != null) {
                    buff.append(' ').append(s);
                }
            }
        }
        return buff.toString();
    }

    private static SchemaObject resolve(ServerSession session, SchemaObject source, String name) {
        Database db = session.getDatabase();
        Schema schema = source.getSchema();
        SchemaObject target = findInSchema(session, schema, name, source instanceof SqlProcedure);
        if (target != null) {
            return target;
        }
        // 当前schema找不到时再按PUBLIC、其它schema找；分析时不依赖具体会话的search_path
        for (Schema s : db.getAllSchemas()) {
            if (s == schema) {
                continue;
            }
            target = findInSchema(session, s, name, source instanceof SqlProcedure);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static SchemaObject findInSchema(ServerSession session, Schema schema, String name,
            boolean preferProcedure) {
        if (preferProcedure) {
            ArrayList<SqlProcedure> procedures = schema.findProcedures(session, name);
            if (!procedures.isEmpty()) {
                return procedures.get(0);
            }
            ArrayList<FunctionAlias> functions = schema.findFunctions(session, name);
            if (!functions.isEmpty()) {
                return functions.get(0);
            }
        } else {
            ArrayList<FunctionAlias> functions = schema.findFunctions(session, name);
            if (!functions.isEmpty()) {
                return functions.get(0);
            }
            ArrayList<SqlProcedure> procedures = schema.findProcedures(session, name);
            if (!procedures.isEmpty()) {
                return procedures.get(0);
            }
        }
        return null;
    }

    // 扫描SQL文本：calls记录后面跟'('的标识符，tokens记录标识符和字符串字面量
    private static void scan(String sql, ArrayList<String> calls, ArrayList<String> tokens) {
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                i = readString(sql, i, tokens);
            } else if (c == '"') {
                i = readQuotedIdentifier(sql, i, calls, tokens);
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '$') {
                i = skipDollarQuoted(sql, i);
            } else if (isWordStart(c)) {
                int start = i;
                i++;
                while (i < length && isWordPart(sql.charAt(i))) {
                    i++;
                }
                String word = sql.substring(start, i);
                tokens.add(word);
                int j = skipSpaces(sql, i);
                if (j < length && sql.charAt(j) == '(') {
                    calls.add(word);
                }
            } else {
                i++;
            }
        }
    }

    private static int readString(String sql, int start, ArrayList<String> tokens) {
        StringBuilder buff = new StringBuilder();
        int i = start + 1;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                    buff.append('\'');
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            buff.append(c);
            i++;
        }
        tokens.add(buff.toString());
        return i;
    }

    private static int readQuotedIdentifier(String sql, int start, ArrayList<String> calls,
            ArrayList<String> tokens) {
        StringBuilder buff = new StringBuilder();
        int i = start + 1;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < length && sql.charAt(i + 1) == '"') {
                    buff.append('"');
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            buff.append(c);
            i++;
        }
        String word = buff.toString();
        tokens.add(word);
        int j = skipSpaces(sql, i);
        if (j < length && sql.charAt(j) == '(') {
            calls.add(word);
        }
        return i;
    }

    private static int skipLineComment(String sql, int start) {
        int i = start + 2;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start + 2;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return sql.length();
    }

    // $$...$$或$tag$...$tag$整体跳过；$1这种位置参数只跳$符号
    private static int skipDollarQuoted(String sql, int start) {
        int i = start + 1;
        while (i < sql.length() && (isWordPart(sql.charAt(i)) || sql.charAt(i) == '$')) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') {
            return start + 1;
        }
        String tag = sql.substring(start, i + 1);
        int end = sql.indexOf(tag, i + 1);
        return end < 0 ? sql.length() : end + tag.length();
    }

    private static int skipSpaces(String sql, int start) {
        int i = start;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
