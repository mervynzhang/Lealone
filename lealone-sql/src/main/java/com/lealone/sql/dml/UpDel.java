/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.DataHandler;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.result.Result;
import com.lealone.db.row.Row;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Table;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.executor.YieldableBase;
import com.lealone.sql.executor.YieldableLoopUpdateBase;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.evaluator.AlwaysTrueEvaluator;
import com.lealone.sql.expression.evaluator.ExpressionEvaluator;
import com.lealone.sql.expression.evaluator.ExpressionInterpreter;
import com.lealone.sql.optimizer.TableFilter;
import com.lealone.sql.optimizer.TableIterator;

// update和delete的基类
public abstract class UpDel extends ManipulationStatement {

    protected TableFilter tableFilter;
    protected Expression condition;

    /**
     * The limit expression as specified in the LIMIT or TOP clause.
     */
    protected Expression limitExpr;

    public UpDel(ServerSession session) {
        super(session);
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    public void setTableFilter(TableFilter tableFilter) {
        this.tableFilter = tableFilter;
    }

    @Override
    public TableFilter getTableFilter() {
        return tableFilter;
    }

    public void setCondition(Expression condition) {
        this.condition = condition;
    }

    public void setLimit(Expression limit) {
        this.limitExpr = limit;
    }

    @Override
    public int getPriority() {
        if (getCurrentRowNumber() > 0)
            return priority;

        priority = NORM_PRIORITY - 1;
        return priority;
    }

    @Override
    public int update() {
        return syncExecute(createYieldableUpdate(null));
    }

    // 带RETURNING时按查询语句执行；同步入口供PL/pgSQL等同步执行的场景使用
    @Override
    public Result query(int maxRows) {
        return syncExecute(createYieldableQuery(maxRows, false, null));
    }

    @Override
    public YieldableBase<Result> createYieldableQuery(int maxRows, boolean scrollable,
            AsyncResultHandler<Result> asyncHandler) {
        return new YieldableReturningQuery(this, maxRows, scrollable, asyncHandler);
    }

    protected void appendPlanSQL(StatementBuilder buff) {
        buff.setEnclosed(false);
        if (condition != null) {
            buff.append("\nWHERE ");
            condition.getSQL(buff);
        }
        if (limitExpr != null) {
            buff.append("\nLIMIT ");
            limitExpr.getSQL(buff);
        }
        buff.setEnclosed(true);
        appendReturningSQL(buff);
    }

    protected static abstract class YieldableUpDel extends YieldableLoopUpdateBase {

        protected final Table table;
        private final int limitRows; // 如果是0，表示不删除任何记录；如果小于0，表示没有限制
        private final ExpressionEvaluator conditionEvaluator;
        private final TableIterator tableIterator;

        public YieldableUpDel(UpDel statement, AsyncResultHandler<Integer> asyncHandler) {
            super(statement, asyncHandler);
            table = statement.tableFilter.getTable();
            int limitRows = -1;
            if (statement.limitExpr != null) {
                Value v = statement.limitExpr.getValue(session);
                if (v != ValueNull.INSTANCE) {
                    limitRows = v.getInt();
                }
            }
            this.limitRows = limitRows;

            if (limitRows == 0) {
                tableIterator = new TableIterator(session, statement.tableFilter) {
                    @Override
                    public boolean next() {
                        return false;
                    }
                };
            } else {
                tableIterator = new TableIterator(session, statement.tableFilter);
            }

            if (statement.condition == null)
                conditionEvaluator = new AlwaysTrueEvaluator();
            else
                conditionEvaluator = new ExpressionInterpreter(session, statement.condition);
        }

        protected abstract int getRightMask();

        protected abstract int getTriggerType();

        protected abstract boolean upDel(Row oldRow);

        @Override
        protected void startInternal() {
            session.getUser().checkRight(table, getRightMask());
            table.fire(session, getTriggerType(), true);
            tableIterator.start();
        }

        @Override
        protected void stopInternal() {
            table.fire(session, getTriggerType(), false);
        }

        @Override
        public void reset() {
            loopEnd = false;
            resetInternal();
        }

        private void resetInternal() {
            statement.setCurrentRowNumber(0);
            tableIterator.reset();
        }

        @Override
        protected void executeLoopUpdate() {
            try {
                if (table.containsLargeObject()) {
                    DataHandler dh = session.getDataHandler();
                    session.setDataHandler(table.getDataHandler()); // lob字段通过FILE_READ函数赋值时会用到
                    try {
                        executeLoopUpdate0();
                    } finally {
                        session.setDataHandler(dh);
                    }
                } else {
                    executeLoopUpdate0();
                }
            } catch (RuntimeException e) {
                if (DbObjectLock.LOCKED_EXCEPTION == e) {
                    tableIterator.onLockedException();
                } else {
                    throw e;
                }
            }
        }

        private void executeLoopUpdate0() {
            while (tableIterator.next() && pendingException == null) {
                // 不能直接return，执行完一次后再return，否则执行next()得到的记录被跳过了，会产生严重的问题
                boolean yield = yieldIfNeeded(++loopCount);
                if (conditionEvaluator.getBooleanValue()) {
                    int ret = tableIterator.tryLockRow();
                    if (ret < 0) {
                        continue;
                    } else if (ret == 0) { // 被其他事务锁住了
                        return;
                    }
                    Row row = tableIterator.getRow();
                    if (upDel(row)) {
                        if (limitRows > 0 && updateCount >= limitRows) {
                            break;
                        }
                    }
                }
                if (yield)
                    return;
            }
            onLoopEnd();
        }
    }
}
