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
    // 这样H2遗留的CALL语法和新的存储过程语法可以共存
    @Override
    protected Call parseCall() {
        SqlProcedure procedure = null;
        if (currentTokenType == IDENTIFIER)
            procedure = getSchema(session.getCurrentSchemaName()).findProcedure(session, currentToken);
        if (procedure == null)
            return super.parseCall();

        CallProcedure command = new CallProcedure(session, procedure, parseProcedureArgs());
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

}
