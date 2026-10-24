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

    public CallProcedure(ServerSession session, SqlProcedure procedure, Expression[] args) {
        super(session);
        this.procedure = procedure;
        this.args = args;
        ExpressionColumn c = new ExpressionColumn(session.getDatabase(),
                new Column(procedure.getName() + "()", Value.INT));
        resultExpressions = new Expression[] { c };
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

    @Override
    public int getType() {
        return SQLStatement.CALL;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    // 存储过程按更新语句的方式执行，过程体里的查询结果集仍然可以通过executeQuery拿到
    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public PreparedSQLStatement prepare() {
        for (int i = 0, len = args.length; i < len; i++)
            args[i] = args[i].optimize(session);
        return this;
    }

    @Override
    public Future<Result> getMetaData() {
        LocalResult result = new LocalResult(session, resultExpressions, 1);
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
