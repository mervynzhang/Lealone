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
import com.lealone.db.schema.CompositeType;
import com.lealone.db.schema.Schema;
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
            ArrayList<Integer> parameterIndexes, String[] parameterTypes,
            int[] inputParameterIndexes) {
        return parse(session, body, parameterNames, parameterIndexes, true, parameterTypes,
                inputParameterIndexes);
    }

    /**
     * BEGIN ATOMIC函数体里的一条语句：不要求是查询语句，但不允许出现'?'。
     */
    static String parseAtomicBody(ServerSession session, String body,
            ArrayList<String> parameterNames, ArrayList<Integer> parameterIndexes,
            String[] parameterTypes, int[] inputParameterIndexes) {
        return parse(session, body, parameterNames, parameterIndexes, false, parameterTypes,
                inputParameterIndexes);
    }

    private static String parse(ServerSession session, String body,
            ArrayList<String> parameterNames, ArrayList<Integer> parameterIndexes,
            boolean singleQuery, String[] parameterTypes, int[] inputParameterIndexes) {
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
            // PostgreSQL的位置参数: $是PARAMETER token, 后面紧跟数字token
            if (tokenTypes.get(i) == PARAMETER && "$".equals(tokens.get(i))
                    && isPositionalParameter(body, tokenTypes, positions, i)) {
                int n = Integer.parseInt(getTokenText(body, positions, i + 1));
                if (n < 1 || n > inputParameterIndexes.length) {
                    // 和PostgreSQL一样: 引用了不存在的位置参数时在CREATE阶段就报错
                    throw DbException.get(ErrorCode.SYNTAX_ERROR_1, "there is no parameter $" + n);
                }
                int[] position = positions.get(i);
                bodySql.append(body, copied, position[0]);
                bodySql.append('?');
                copied = positions.get(i + 1)[1];
                parameterIndexes.add(inputParameterIndexes[n - 1]);
                i++; // 跳过数字token
                continue;
            }
            if (tokenTypes.get(i) != IDENTIFIER) {
                continue;
            }
            int index = getParameterIndex(parameterNames, tokens.get(i), quotedFlags.get(i));
            if (index < 0) {
                continue;
            }
            // 限定名(如t.a)中的标识符不是参数引用；复合类型参数的c.b要当成字段访问
            if (isDotToken(tokens, tokenTypes, i - 1)) {
                continue;
            }
            String compositeType = getCompositeTypeSql(session, parameterTypes, index);
            if (compositeType == null && isDotToken(tokens, tokenTypes, i + 1)) {
                continue;
            }
            int[] position = positions.get(i);
            bodySql.append(body, copied, position[0]);
            // 复合类型参数不能只留一个未知类型的?，否则(c).field无法解析
            if (compositeType == null)
                bodySql.append('?');
            else
                bodySql.append("(CAST(? AS ").append(compositeType).append("))");
            copied = position[1];
            parameterIndexes.add(index);
        }
        bodySql.append(body, copied, body.length());
        return bodySql.toString();
    }

    // 单语句函数体: 只能是查询、DML或者DDL/utility语句，并且不允许出现分号和'?';
    // 是不是查询语句、返回类型对不对，由CreateSqlFunction.validateSqlBody在建函数时校验
    // $1这种位置参数: $和数字两个token必须紧挨着
    private static boolean isPositionalParameter(String body, ArrayList<Integer> tokenTypes,
            ArrayList<int[]> positions, int i) {
        if (i + 1 >= positions.size() || tokenTypes.get(i + 1) != VALUE) {
            return false;
        }
        String number = getTokenText(body, positions, i + 1);
        if (number.isEmpty()) {
            return false;
        }
        for (int j = 0; j < number.length(); j++) {
            if (number.charAt(j) < '0' || number.charAt(j) > '9') {
                return false;
            }
        }
        return positions.get(i)[1] == positions.get(i + 1)[0];
    }

    private static String getTokenText(String body, ArrayList<int[]> positions, int i) {
        int[] position = positions.get(i);
        return body.substring(position[0], position[1]);
    }

    private static void checkBody(ArrayList<String> tokens, ArrayList<Integer> tokenTypes) {
        String firstToken = tokens.get(0);
        if (!isSupportedBodyStart(firstToken)) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "function body must be a query, a DML or a DDL statement: " + firstToken);
        }
        for (int i = 0, size = tokens.size(); i < size; i++) {
            if (tokenTypes.get(i) == KEYWORD && ";".equals(tokens.get(i))) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "function body must be a single query");
            }
            if (tokenTypes.get(i) == PARAMETER && !"$".equals(tokens.get(i))) {
                // 函数体里的'?'会和参数名替换出来的'?'混淆，直接拒绝
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "? is not allowed in function body");
            }
        }
    }

    private static boolean isSupportedBodyStart(String token) {
        return "(".equals(token) || "SELECT".equalsIgnoreCase(token)
                || "VALUES".equalsIgnoreCase(token) || "TABLE".equalsIgnoreCase(token)
                || "WITH".equalsIgnoreCase(token) || "INSERT".equalsIgnoreCase(token)
                || "UPDATE".equalsIgnoreCase(token) || "DELETE".equalsIgnoreCase(token)
                || "MERGE".equalsIgnoreCase(token) || "CREATE".equalsIgnoreCase(token)
                || "ALTER".equalsIgnoreCase(token) || "DROP".equalsIgnoreCase(token)
                || "TRUNCATE".equalsIgnoreCase(token) || "SET".equalsIgnoreCase(token)
                || "CALL".equalsIgnoreCase(token);
    }

    private static void checkNoParameterToken(ArrayList<String> tokens,
            ArrayList<Integer> tokenTypes) {
        for (int i = 0, size = tokens.size(); i < size; i++) {
            if (tokenTypes.get(i) == PARAMETER && !"$".equals(tokens.get(i))) {
                // 函数体里的'?'会和参数名替换出来的'?'混淆，直接拒绝
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "? is not allowed in function body");
            }
        }
    }

    private static String getCompositeTypeSql(ServerSession session, String[] parameterTypes, int index) {
        if (session == null || parameterTypes == null || index >= parameterTypes.length)
            return null;
        String typeName = parameterTypes[index];
        if (typeName == null || typeName.endsWith("[]"))
            return null;
        String schemaName = session.getCurrentSchemaName();
        if (schemaName == null)
            return null;
        Schema schema = session.getDatabase().findSchema(session, schemaName);
        if (schema == null)
            return null;
        CompositeType type = schema.findCompositeType(session, typeName);
        return type == null ? null : session.getDatabase().quoteIdentifier(type.getName());
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
