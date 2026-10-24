/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.Utils;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;

/**
 * 用Lealone自己的tokenizer对SQL函数体切词，把和参数名匹配的标识符替换成'?'。
 * 字符串字面量、美元引用字符串、注释都已经被tokenizer当成单个token，
 * 所以不会被替换。
 */
class SqlFunctionBodyParser extends LealoneSQLParser {

    private SqlFunctionBodyParser(ServerSession session) {
        super(session);
    }

    /**
     * 把函数体中的参数名替换成'?'。
     *
     * @param session 当前session，用于获取分词规则
     * @param body 原始的函数体
     * @param parameterNames 参数名列表
     * @param parameterIndexes 输出参数，每个'?'对应的参数下标
     * @return 替换后的函数体
     */
    static String parseBody(ServerSession session, String body, ArrayList<String> parameterNames,
            ArrayList<Integer> parameterIndexes) {
        return parse(session, body, parameterNames, parameterIndexes, true);
    }

    /**
     * BEGIN ATOMIC函数体里的一条语句：不要求是查询语句，但不允许出现'?'。
     */
    static String parseAtomicBody(ServerSession session, String body,
            ArrayList<String> parameterNames, ArrayList<Integer> parameterIndexes) {
        return parse(session, body, parameterNames, parameterIndexes, false);
    }

    private static String parse(ServerSession session, String body,
            ArrayList<String> parameterNames, ArrayList<Integer> parameterIndexes,
            boolean singleQuery) {
        SqlFunctionBodyParser parser = new SqlFunctionBodyParser(session);
        parser.initialize(body);
        parser.read();
        ArrayList<int[]> positions = Utils.newSmallArrayList(); // 每个token在函数体中的起止位置
        ArrayList<String> tokens = Utils.newSmallArrayList();
        ArrayList<Integer> tokenTypes = Utils.newSmallArrayList();
        ArrayList<Boolean> quotedFlags = Utils.newSmallArrayList();
        while (parser.currentTokenType != END) {
            int start = parser.lastParseIndex;
            while (parser.characterTypes[start] == 0) {
                start++; // 跳过空白和注释
            }
            positions.add(new int[] { start, parser.parseIndex });
            tokens.add(parser.currentToken);
            tokenTypes.add(parser.currentTokenType);
            quotedFlags.add(parser.currentTokenQuoted);
            parser.read();
        }
        if (tokens.isEmpty()) {
            throw DbException.getSyntaxError(body, 0);
        }
        if (singleQuery) {
            checkBody(tokens, tokenTypes);
        } else {
            checkNoParameterToken(tokens, tokenTypes);
        }

        int size = tokens.size();
        StringBuilder bodySql = new StringBuilder(body.length());
        int copied = 0; // 已经拷贝到bodySql的函数体位置
        for (int i = 0; i < size; i++) {
            if (tokenTypes.get(i) != IDENTIFIER) {
                continue;
            }
            int index = getParameterIndex(parameterNames, tokens.get(i), quotedFlags.get(i));
            if (index < 0) {
                continue;
            }
            // 限定名(如t.a)中的标识符不是参数引用
            if (isDotToken(tokens, tokenTypes, i - 1) || isDotToken(tokens, tokenTypes, i + 1)) {
                continue;
            }
            int[] position = positions.get(i);
            bodySql.append(body, copied, position[0]).append('?');
            copied = position[1];
            parameterIndexes.add(index);
        }
        bodySql.append(body, copied, body.length());
        return bodySql.toString();
    }

    // 函数体只能是单个查询语句，或者带RETURNING的DML语句
    private static void checkBody(ArrayList<String> tokens, ArrayList<Integer> tokenTypes) {
        boolean isQuery = false;
        String firstToken = tokens.get(0);
        if ("(".equals(firstToken) || "SELECT".equalsIgnoreCase(firstToken)
                || "VALUES".equalsIgnoreCase(firstToken) || "TABLE".equalsIgnoreCase(firstToken)
                || "WITH".equalsIgnoreCase(firstToken) || "INSERT".equalsIgnoreCase(firstToken)
                || "UPDATE".equalsIgnoreCase(firstToken) || "DELETE".equalsIgnoreCase(firstToken)
                || "MERGE".equalsIgnoreCase(firstToken)) {
            isQuery = true;
        }
        if (!isQuery) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "function body must be a query or a DML statement with RETURNING: " + firstToken);
        }
        for (int i = 0, size = tokens.size(); i < size; i++) {
            if (tokenTypes.get(i) == KEYWORD && ";".equals(tokens.get(i))) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "function body must be a single query");
            }
            if (tokenTypes.get(i) == PARAMETER) {
                // 函数体里的'?'会和参数名替换出来的'?'混淆，直接拒绝
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "? is not allowed in function body");
            }
        }
    }

    private static void checkNoParameterToken(ArrayList<String> tokens,
            ArrayList<Integer> tokenTypes) {
        for (int i = 0, size = tokens.size(); i < size; i++) {
            if (tokenTypes.get(i) == PARAMETER) {
                // 函数体里的'?'会和参数名替换出来的'?'混淆，直接拒绝
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "? is not allowed in function body");
            }
        }
    }

    private static boolean isDotToken(ArrayList<String> tokens, ArrayList<Integer> tokenTypes, int i) {
        return i >= 0 && i < tokens.size() && tokenTypes.get(i) == KEYWORD
                && ".".equals(tokens.get(i));
    }

    private static int getParameterIndex(ArrayList<String> parameterNames, String token,
            boolean quoted) {
        for (int i = 0, size = parameterNames.size(); i < size; i++) {
            String name = parameterNames.get(i);
            // 加双引号的标识符区分大小写，没加的不区分
            if (quoted ? name.equals(token) : name.equalsIgnoreCase(token)) {
                return i;
            }
        }
        return -1;
    }
}
