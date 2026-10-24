/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.async.AsyncResult;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.async.Future;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.row.Row;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.SessionStatus;
import com.lealone.db.table.Table;
import com.lealone.db.value.Value;
import com.lealone.sql.StatementBase;
import com.lealone.sql.executor.YieldableBase;
import com.lealone.sql.executor.YieldableLoopUpdateBase;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.optimizer.RowColumnResolver;

public abstract class ManipulationStatement extends StatementBase {

    private Table returningTable;
    private String returningAlias;
    private Expression[] returningExpressions;
    private RowColumnResolver returningResolver;

    public ManipulationStatement(ServerSession session) {
        super(session);
    }

    public void setReturning(Table table, String alias, Expression[] expressions) {
        this.returningTable = table;
        this.returningAlias = alias;
        this.returningExpressions = expressions;
    }

    public boolean isReturning() {
        return returningExpressions != null;
    }

    // 带RETURNING的DML要作为查询语句执行，这样executeQuery/execute能拿到结果集
    @Override
    public boolean isQuery() {
        return returningExpressions != null;
    }

    // 把RETURNING表达式里的列名解析到目标表，并按目标表的列做优化
    protected void prepareReturning() {
        if (returningExpressions == null || returningResolver != null)
            return;
        returningResolver = new RowColumnResolver(session, returningTable, returningAlias);
        for (int i = 0; i < returningExpressions.length; i++) {
            Expression e = returningExpressions[i];
            e.mapColumns(returningResolver, 0);
            returningExpressions[i] = e.optimize(session);
        }
    }

    // 用DML实际影响的那一行计算RETURNING表达式的值
    public Value[] evalReturning(Row row) {
        returningResolver.setRow(row);
        Value[] values = new Value[returningExpressions.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = returningExpressions[i].getValue(session);
        }
        return values;
    }

    protected void appendReturningSQL(StatementBuilder buff) {
        if (returningExpressions == null)
            return;
        buff.append("\nRETURNING ");
        buff.resetCount();
        for (Expression e : returningExpressions) {
            buff.appendExceptFirst(", ");
            buff.append(e.getSQL());
        }
    }

    @Override
    public Future<Result> getMetaData() {
        if (returningExpressions == null)
            return super.getMetaData();
        LocalResult result = new LocalResult(session, returningExpressions,
                returningExpressions.length);
        result.done();
        return Future.succeededFuture(result);
    }

    /**
     * 先按更新语句的方式执行DML，写成功后再把实际影响的行收集成结果集。
     * 返回行收集到LocalResult，超过数据库的MAX_MEMORY_ROWS限制后会自动写到临时文件。
     * 只有INSERT/UPDATE/DELETE/MERGE的基类会用到它。
     */
    protected static class YieldableReturningQuery extends YieldableBase<Result> {

        private final ManipulationStatement dml;
        private final LocalResult result;
        private YieldableLoopUpdateBase updateYieldable;
        private Throwable innerError;

        YieldableReturningQuery(ManipulationStatement dml, int maxRows, boolean scrollable,
                AsyncResultHandler<Result> asyncHandler) {
            super(dml, asyncHandler);
            this.dml = dml;
            this.result = new LocalResult(session, dml.returningExpressions,
                    dml.returningExpressions.length);
            if (maxRows > 0)
                result.setLimit(maxRows);
        }

        @Override
        protected void startInternal() {
            // 真正改数据的还是原来的更新执行器，它每写成功一行就把返回行写进result
            YieldableBase<Integer> y = dml.createYieldableUpdate(ar -> {
                if (ar.isFailed())
                    innerError = ar.getCause();
            });
            if (!(y instanceof YieldableLoopUpdateBase))
                throw DbException.getUnsupportedException("RETURNING in this execution mode");
            updateYieldable = (YieldableLoopUpdateBase) y;
            updateYieldable.setReturningTarget(result);
        }

        @Override
        protected void executeInternal() {
            // 内层yieldable负责写数据，外层负责驱动它并把结果集返回给客户端，不阻塞调度线程
            session.setStatus(SessionStatus.STATEMENT_RUNNING);
            if (!updateYieldable.isStopped())
                updateYieldable.run();
            if (!updateYieldable.isStopped())
                return;
            if (innerError != null) {
                // 内层已经记录过异常并回滚过当前命令，这里只负责把错误返回给调用方
                asyncResult = new AsyncResult<>(DbException.convert(innerError));
                return;
            }
            result.done();
            setResult(result, result.getRowCount());
            session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        }
    }
}
