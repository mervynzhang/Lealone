/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;

/**
 * PL/pgSQL函数体的词法分析器。
 * 一次性把整个函数体切成token列表，parser按token在源串中的起止下标
 * 直接截取表达式和SQL语句的原文，这样PL/pgSQL的词法分析不用理解SQL语法。
 */
public class PlpgSqlLexer {

    public enum TokenType {
        IDENTIFIER, // 标识符，加双引号时quoted为true
        STRING, // 单引号字符串或美元引用字符串
        NUMBER, // 数字
        OPERATOR, // 操作符和标点
        END // 输入结束
    }

    public static class Token {

        public final TokenType type;
        // 源串中的原始文本，字符串和标识符都带引号
        public final String text;
        // 语义值，字符串是去引号后的内容，双引号标识符是去引号后的名字
        public final String value;
        public final int start;
        public final int end;
        public final boolean quoted;
        public final int line;
        public final int column;

        public Token(TokenType type, String text, String value, int start, int end, boolean quoted,
                int line, int column) {
            this.type = type;
            this.text = text;
            this.value = value;
            this.start = start;
            this.end = end;
            this.quoted = quoted;
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return type + "(" + text + ")";
        }
    }

    private static final String[] MULTI_CHAR_OPERATORS = { ":=", "::", "..", "<<", ">>", "<=", ">=",
            "<>", "!=", "||", "=>" };

    private final String source;
    private final int length;

    private int index;
    private int line = 1;
    private int column = 1;

    public PlpgSqlLexer(String source) {
        this.source = source == null ? "" : source;
        this.length = this.source.length();
    }

    public ArrayList<Token> tokenize() {
        ArrayList<Token> tokens = new ArrayList<>();
        while (true) {
            Token token = readToken();
            tokens.add(token);
            if (token.type == TokenType.END)
                return tokens;
        }
    }

    private Token readToken() {
        skipWhitespaceAndComment();
        if (index >= length)
            return newToken(TokenType.END, "", "", index, index, false, line, column);

        int start = index;
        int startLine = line;
        int startColumn = column;
        char c = source.charAt(index);

        if (c == '\'') {
            String value = readQuotedString();
            return newToken(TokenType.STRING, source.substring(start, index), value, start, index,
                    false, startLine, startColumn);
        }
        if (c == '"') {
            String value = readQuotedIdentifier();
            return newToken(TokenType.IDENTIFIER, source.substring(start, index), value, start,
                    index, true, startLine, startColumn);
        }
        if (c == '$') {
            Token dollarToken = readDollarQuotedString(start, startLine, startColumn);
            if (dollarToken != null)
                return dollarToken;
            advance();
            return newToken(TokenType.OPERATOR, "$", "$", start, index, false, startLine,
                    startColumn);
        }
        if (isIdentifierStart(c)) {
            advance();
            while (index < length) {
                char c2 = source.charAt(index);
                if (isIdentifierPart(c2))
                    advance();
                else
                    break;
            }
            String text = source.substring(start, index);
            return newToken(TokenType.IDENTIFIER, text, text, start, index, false, startLine,
                    startColumn);
        }
        if (isDigit(c) || (c == '.' && index + 1 < length && isDigit(source.charAt(index + 1)))) {
            return readNumber(start, startLine, startColumn);
        }
        // 字符串常量都没了，剩下的按操作符处理，多个字符的操作符要最长匹配
        for (String operator : MULTI_CHAR_OPERATORS) {
            if (source.startsWith(operator, index)) {
                advance(operator.length());
                return newToken(TokenType.OPERATOR, operator, operator, start, index, false,
                        startLine, startColumn);
            }
        }
        advance();
        String text = source.substring(start, index);
        return newToken(TokenType.OPERATOR, text, text, start, index, false, startLine,
                startColumn);
    }

    private Token readNumber(int start, int startLine, int startColumn) {
        boolean dotRead = false;
        while (index < length) {
            char c = source.charAt(index);
            if (isDigit(c)) {
                advance();
            } else if (c == '.' && !dotRead
                    && !(index + 1 < length && source.charAt(index + 1) == '.')) {
                dotRead = true;
                advance();
            } else if ((c == 'e' || c == 'E') && index + 1 < length
                    && (isDigit(source.charAt(index + 1)) || source.charAt(index + 1) == '+'
                            || source.charAt(index + 1) == '-')) {
                advance();
                if (source.charAt(index) == '+' || source.charAt(index) == '-')
                    advance();
            } else {
                break;
            }
        }
        String text = source.substring(start, index);
        return newToken(TokenType.NUMBER, text, text, start, index, false, startLine, startColumn);
    }

    // 单引号字符串，''表示一个单引号
    private String readQuotedString() {
        int start = index;
        advance(); // 跳过开头的单引号
        StringBuilder buff = new StringBuilder();
        while (index < length) {
            char c = source.charAt(index);
            if (c == '\'') {
                if (index + 1 < length && source.charAt(index + 1) == '\'') {
                    buff.append('\'');
                    advance(2);
                } else {
                    advance();
                    return buff.toString();
                }
            } else {
                buff.append(c);
                advance();
            }
        }
        throw DbException.getSyntaxError(source, start, "unterminated string");
    }

    // 双引号标识符，""表示一个双引号
    private String readQuotedIdentifier() {
        int start = index;
        advance(); // 跳过开头的双引号
        StringBuilder buff = new StringBuilder();
        while (index < length) {
            char c = source.charAt(index);
            if (c == '"') {
                if (index + 1 < length && source.charAt(index + 1) == '"') {
                    buff.append('"');
                    advance(2);
                } else {
                    advance();
                    return buff.toString();
                }
            } else {
                buff.append(c);
                advance();
            }
        }
        throw DbException.getSyntaxError(source, start, "unterminated quoted identifier");
    }

    // 美元引用字符串，$tag$...$tag$，tag可以为空
    private Token readDollarQuotedString(int start, int startLine, int startColumn) {
        int tagEnd = index + 1;
        while (tagEnd < length) {
            char c = source.charAt(tagEnd);
            if (c == '$')
                break;
            boolean valid = tagEnd == index + 1 ? Character.isJavaIdentifierStart(c) || c == '_'
                    : Character.isJavaIdentifierPart(c);
            if (!valid)
                return null;
            tagEnd++;
        }
        if (tagEnd >= length || source.charAt(tagEnd) != '$')
            return null;
        String tag = source.substring(index, tagEnd + 1); // 包含首尾的$
        int contentStart = tagEnd + 1;
        int contentEnd = source.indexOf(tag, contentStart);
        if (contentEnd < 0)
            throw DbException.getSyntaxError(source, start, "unterminated dollar-quoted string");
        advance(tag.length());
        StringBuilder buff = new StringBuilder(contentEnd - contentStart);
        while (index < contentEnd) {
            buff.append(source.charAt(index));
            advance();
        }
        advance(tag.length()); // 跳过结束标签
        return newToken(TokenType.STRING, source.substring(start, index), buff.toString(), start,
                index, false, startLine, startColumn);
    }

    private void skipWhitespaceAndComment() {
        while (index < length) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '-' && index + 1 < length && source.charAt(index + 1) == '-') {
                advance(2);
                while (index < length && source.charAt(index) != '\n')
                    advance();
            } else if (c == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                skipBlockComment();
            } else {
                return;
            }
        }
    }

    // PostgreSQL允许块注释嵌套
    private void skipBlockComment() {
        int start = index;
        advance(2);
        int level = 1;
        while (index < length) {
            if (source.startsWith("/*", index)) {
                level++;
                advance(2);
            } else if (source.startsWith("*/", index)) {
                level--;
                advance(2);
                if (level == 0)
                    return;
            } else {
                advance();
            }
        }
        throw DbException.getSyntaxError(source, start, "unterminated comment");
    }

    private void advance() {
        if (index < length) {
            if (source.charAt(index) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            index++;
        }
    }

    private void advance(int count) {
        for (int i = 0; i < count; i++)
            advance();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '$' || Character.isJavaIdentifierPart(c);
    }

    private static Token newToken(TokenType type, String text, String value, int start, int end,
            boolean quoted, int line, int column) {
        return new Token(type, text, value, start, end, quoted, line, column);
    }
}
