/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.async.Future;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.executor.YieldableBase;
import com.lealone.sql.executor.YieldableCallProcedure;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;

/**
 * This class represents the statement
 * CALL procedureName(...)
 * 
 * @author zhh
 */
public class CallProcedure extends Call {

    private final SqlProcedure procedure;
    private final Expression[] args;
    private final Expression[] resultExpressions;
    // OUT/INOUT参数对应的结果列
    private final Expression[] outputExpressions;
    // 过程体里是否有查询语句: -1还没判断, 0没有, 1有
    private int bodyHasQuery = -1;

    public CallProcedure(ServerSession session, SqlProcedure procedure, Expression[] args) {
        super(session);
        this.procedure = procedure;
        this.args = args;
        ExpressionColumn c = new ExpressionColumn(session.getDatabase(),
                new Column(procedure.getName() + "()", Value.INT));
        resultExpressions = new Expression[] { c };
        int outputCount = procedure.getOutputParameterCount();
        if (outputCount > 0) {
            outputExpressions = new Expression[outputCount];
            for (int i = 0; i < outputCount; i++) {
                int index = procedure.getOutputParameterIndex(i);
                Column column = procedure.getParameter(index);
                outputExpressions[i] = new ExpressionColumn(session.getDatabase(), column);
            }
        } else {
            outputExpressions = resultExpressions;
        }
    }

    public SqlProcedure getProcedure() {
        return procedure;
    }

    public Expression[] getArgs() {
        return args;
    }

    public Expression[] getResultExpressions() {
        return resultExpressions;
    }

    // CALL的结果列：有OUT/INOUT参数时是OUT/INOUT参数，否则是过程名+更新数
    public Expression[] getOutputExpressions() {
        return outputExpressions;
    }

    @Override
    public int getType() {
        return SQLStatement.CALL;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    // 过程体里有查询语句时按查询语句执行，这样Statement.execute()也能通过getResultSet()拿到结果集；
    // 只有更新语句的过程仍然按更新语句执行，保持原来的更新计数语义。
    // 有OUT/INOUT参数的过程总是返回一行，所以也按查询语句执行
    @Override
    public boolean isQuery() {
        if (procedure.hasOutputParameters())
            return true;
        if (bodyHasQuery < 0) {
            bodyHasQuery = LealoneSQLParser.hasQueryStatement(session, procedure.getBody()) ? 1 : 0;
        }
        return bodyHasQuery == 1;
    }

    @Override
    public PreparedSQLStatement prepare() {
        for (int i = 0, len = args.length; i < len; i++)
            args[i] = args[i].optimize(session);
        return this;
    }

    @Override
    public Future<Result> getMetaData() {
        Expression[] expressions = getOutputExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        result.done();
        return Future.succeededFuture(result);
    }

    // 过程体里可能有查询语句，默认按查询语句的方式执行，内部会把最后一条查询的结果集返回给客户端
    @Override
    public int update() {
        return syncExecute(createYieldableUpdate(null));
    }

    @Override
    public Result query(int maxRows) {
        return syncExecute(createYieldableQuery(maxRows, false, null));
    }

    @Override
    public YieldableBase<Result> createYieldableQuery(int maxRows, boolean scrollable,
            AsyncResultHandler<Result> asyncHandler) {
        return new YieldableCallProcedure<>(this, maxRows, true, asyncHandler);
    }

    @Override
    public YieldableBase<Integer> createYieldableUpdate(AsyncResultHandler<Integer> asyncHandler) {
        return new YieldableCallProcedure<>(this, -1, false, asyncHandler);
    }
}
