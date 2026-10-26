/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.condition;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueString;

/**
 * PostgreSQL 风格一维数组字面量的最小解析器，只用于 {@code expr = ANY('...')} 的字符串右值。
 * <p>
 * 调用约定：传入的字符串已经去掉首尾空白，并且以 '{' 开头（否则调用方应当把它当单值处理）。
 * 支持以下子集：
 * <ul>
 * <li>{@code {a,b,c}}：未加引号的元素去掉两端空白；</li>
 * <li>{@code {"a,b","c"}}：双引号元素里的逗号、空白、花括号都是元素内容；</li>
 * <li>双引号内的 {@code \"} 和 {@code \\} 转义；</li>
 * <li>{@code NULL}（不带引号、大小写不敏感）表示 SQL NULL，{@code "NULL"} 是普通字符串；</li>
 * <li>{@code {}}：空数组。</li>
 * </ul>
 * 解析失败（括号不配对、空元素、结尾多余的逗号、多维数组等）一律抛
 * {@link ErrorCode#MALFORMED_ARRAY_LITERAL_1}，绝不退化成"把整串当一个元素"，
 * 否则用户写错字面量时只会看到"没匹配上"这种静默错值。
 */
final class ArrayLiteralParser {

    private final String literal;
    private final ArrayList<Value> values = new ArrayList<>();
    // 下一个待读字符的下标
    private int pos;

    private ArrayLiteralParser(String literal) {
        this.literal = literal;
    }

    /**
     * @param literal 已去掉首尾空白、以 '{' 开头的数组字面量
     * @return 解析出来的元素，元素只可能是 ValueString 或 ValueNull
     */
    static Value[] parse(String literal) {
        return new ArrayLiteralParser(literal).parseAll();
    }

    private Value[] parseAll() {
        int length = literal.length();
        pos = 1; // 跳过开头的 '{'
        skipWhitespace();
        if (pos == length) {
            throw malformed("missing '}'");
        }
        if (literal.charAt(pos) == '}') {
            pos++;
            checkEnd();
            return new Value[0];
        }
        while (true) {
            skipWhitespace();
            if (pos == length) {
                throw malformed("missing '}'");
            }
            if (literal.charAt(pos) == '}') {
                // 例如 {a,} 或 {a,,b}
                throw malformed("empty element");
            }
            values.add(readElement());
            skipWhitespace();
            if (pos == length) {
                throw malformed("missing '}'");
            }
            char c = literal.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                break;
            }
            throw malformed("expected ',' or '}'");
        }
        checkEnd();
        return values.toArray(new Value[0]);
    }

    private Value readElement() {
        char c = literal.charAt(pos);
        if (c == '{') {
            throw malformed("multi-dimensional arrays are not supported");
        }
        if (c == '"') {
            return readQuotedElement();
        }
        return readUnquotedElement();
    }

    /** 双引号元素：内容原样保留，只有 \" 和 \\（以及其它 \x 形式）做反转义 */
    private Value readQuotedElement() {
        pos++; // 跳过开头的双引号
        StringBuilder buff = new StringBuilder();
        while (true) {
            if (pos == literal.length()) {
                throw malformed("missing closing '\"'");
            }
            char c = literal.charAt(pos++);
            if (c == '"') {
                return ValueString.get(buff.toString());
            } else if (c == '\\') {
                if (pos == literal.length()) {
                    throw malformed("'\\' at the end of the literal");
                }
                buff.append(literal.charAt(pos++));
            } else {
                buff.append(c);
            }
        }
    }

    /** 未加引号的元素：到 ',' 或 '}' 为止，两端空白去掉，NULL 表示 SQL NULL */
    private Value readUnquotedElement() {
        int start = pos;
        while (pos < literal.length()) {
            char c = literal.charAt(pos);
            if (c == ',' || c == '}') {
                break;
            }
            if (c == '{') {
                throw malformed("multi-dimensional arrays are not supported");
            }
            pos++;
        }
        String s = literal.substring(start, pos).trim();
        if (s.isEmpty()) {
            throw malformed("empty element");
        }
        if (s.equalsIgnoreCase("NULL")) {
            return ValueNull.INSTANCE;
        }
        return ValueString.get(s);
    }

    private void skipWhitespace() {
        while (pos < literal.length() && Character.isWhitespace(literal.charAt(pos))) {
            pos++;
        }
    }

    private void checkEnd() {
        if (pos != literal.length()) {
            // 例如 {a}b
            throw malformed("unexpected character after '}'");
        }
    }

    private DbException malformed(String detail) {
        return DbException.get(ErrorCode.MALFORMED_ARRAY_LITERAL_1, literal, detail);
    }
}
