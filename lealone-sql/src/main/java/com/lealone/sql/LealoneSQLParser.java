/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql;

import java.util.ArrayList;

import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.dml.Call;
import com.lealone.sql.dml.CallProcedure;
import com.lealone.sql.expression.Expression;

public class LealoneSQLParser extends SQLParserBase {

    public LealoneSQLParser(ServerSession session) {
        super(session);
    }

    // CALL procedureName(...) 先按存储过程解析，找不到再退回原来的函数调用表达式，
    // 这样H2遗留的CALL语法和新的存储过程语法可以共存。
    // 同名的过程可能有多个重载，先拿到所有候选，等prepare阶段按实参类型消解
    @Override
    protected Call parseCall() {
        ArrayList<SqlProcedure> procedures = null;
        if (currentTokenType == IDENTIFIER) {
            procedures = getSchema(session.getCurrentSchemaName()).findProcedures(session,
                    currentToken);
        }
        if (procedures == null || procedures.isEmpty())
            return super.parseCall();

        CallProcedure command = new CallProcedure(session, procedures, parseProcedureArgs());
        currentStatement = command;
        return command;
    }

    private Expression[] parseProcedureArgs() {
        read(); // 跳过过程名
        ArrayList<Expression> argList = new ArrayList<>();
        if (readIf("(")) {
            if (!readIf(")")) {
                do {
                    argList.add(readExpression());
                } while (readIf(","));
                read(")");
            }
        }
        return argList.toArray(new Expression[0]);
    }

    /**
     * 判断过程体里是否有查询语句。只切词不解析语句，避免解析WITH时在会话里创建临时视图。
     * CALL语句按查询执行时Statement.execute()才能拿到过程体最后一条查询的结果集。
     */
    public static boolean hasQueryStatement(ServerSession session, String body) {
        return checkQueryStatement(session, body, false);
    }

    /**
     * 判断过程体里最后一条语句是不是查询语句。有OUT/INOUT参数的过程靠最后一条查询返回OUT值。
     */
    public static boolean hasTrailingQueryStatement(ServerSession session, String body) {
        return checkQueryStatement(session, body, true);
    }

    /**
     * 只切词判断过程体里有没有CALL语句，不解析语句，避免解析时在会话里创建临时视图等副作用。
     */
    public static boolean hasCallStatement(ServerSession session, String body) {
        LealoneSQLParser parser = new LealoneSQLParser(session);
        parser.initialize(body);
        parser.read();
        boolean statementStart = true;
        while (parser.currentTokenType != END) {
            if (statementStart) {
                if ("CALL".equalsIgnoreCase(parser.currentToken))
                    return true;
                statementStart = false;
            }
            if (parser.currentTokenType == KEYWORD && ";".equals(parser.currentToken)) {
                statementStart = true;
            }
            parser.read();
        }
        return false;
    }

    private static boolean checkQueryStatement(ServerSession session, String body,
            boolean onlyLast) {
        LealoneSQLParser parser = new LealoneSQLParser(session);
        parser.initialize(body);
        parser.read();
        boolean statementStart = true;
        boolean query = false;
        while (parser.currentTokenType != END) {
            if (statementStart) {
                String token = parser.currentToken;
                query = "SELECT".equalsIgnoreCase(token) || "VALUES".equalsIgnoreCase(token)
                        || "TABLE".equalsIgnoreCase(token) || "WITH".equalsIgnoreCase(token)
                        || "(".equals(token);
                if (query && !onlyLast) {
                    return true;
                }
                statementStart = false;
            }
            if (parser.currentTokenType == KEYWORD && ";".equals(parser.currentToken)) {
                statementStart = true;
            }
            parser.read();
        }
        return query;
    }

    /**
     * 把用分号分隔的多条SQL语句拆开。用解析器判断一条语句到哪结束，
     * 这样字符串常量里出现分号也不会切错。空的过程体返回空数组。
     */
    public static String[] splitStatements(ServerSession session, String body) {
        ArrayList<String> statements = new ArrayList<>();
        if (body != null && !body.trim().isEmpty()) {
            String sql = body;
            while (true) {
                StatementBase statement = (StatementBase) session.createParser().parse(sql);
                if (statement instanceof StatementList) {
                    StatementList list = (StatementList) statement;
                    statements.add(list.getFirstStatement().getSQL());
                    sql = skipStatementSeparator(list.getRemaining());
                    if (sql.isEmpty()) {
                        break;
                    }
                } else {
                    statements.add(statement.getSQL());
                    break;
                }
            }
        }
        return statements.toArray(new String[0]);
    }

    /**
     * 只按分号把多条SQL语句切开，不做语义解析。
     * 函数体/过程体里的表可能是函数体自己刚建出来的(比如函数体里先CREATE TABLE再INSERT)，
     * 这时用splitStatements会因为表还不存在而报错，所以这种场景要用原文切分。
     */
    public static String[] splitStatementsRaw(ServerSession session, String body) {
        ArrayList<String> statements = new ArrayList<>();
        if (body == null || body.trim().isEmpty())
            return statements.toArray(new String[0]);
        LealoneSQLParser parser = new LealoneSQLParser(session);
        parser.initialize(body);
        parser.read();
        int depth = 0; // BEGIN ... END / CASE ... END 的嵌套层数
        int start = 0;
        while (parser.currentTokenType != END) {
            String token = parser.currentToken;
            if (depth == 0 && parser.currentTokenType == KEYWORD && ";".equals(token)) {
                addStatement(statements, body.substring(start, parser.lastParseIndex));
                start = parser.parseIndex;
            } else if ("BEGIN".equalsIgnoreCase(token) || "CASE".equalsIgnoreCase(token)) {
                depth++;
            } else if ("END".equalsIgnoreCase(token) && depth > 0) {
                depth--;
            }
            parser.read();
        }
        addStatement(statements, body.substring(start));
        return statements.toArray(new String[0]);
    }

    private static void addStatement(ArrayList<String> statements, String sql) {
        String s = sql.trim();
        if (!s.isEmpty() && !";".equals(s)) {
            statements.add(s);
        }
    }

    private static String skipStatementSeparator(String sql) {
        int i = 0;
        for (int len = sql.length(); i < len; i++) {
            char c = sql.charAt(i);
            if (c != ';' && !Character.isWhitespace(c)) {
                break;
            }
        }
        return sql.substring(i);
    }
}
