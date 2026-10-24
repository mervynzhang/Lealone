/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.executor;

import com.lealone.db.async.AsyncResult;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.result.ResultTarget;
import com.lealone.db.row.Row;
import com.lealone.db.session.SessionStatus;
import com.lealone.db.table.Table;
import com.lealone.db.value.Value;
import com.lealone.sql.StatementBase;

public abstract class YieldableLoopUpdateBase extends YieldableUpdateBase {

    protected int updateCount;
    protected int loopCount;
    protected boolean loopEnd;
    private int pendingOperationCount;
    // 带RETURNING的DML用它收集写成功后返回的行，普通DML为null
    protected ResultTarget returningTarget;

    public YieldableLoopUpdateBase(StatementBase statement, AsyncResultHandler<Integer> asyncHandler) {
        super(statement, asyncHandler);
    }

    @Override
    protected void executeInternal() {
        while (!loopEnd && pendingException == null) {
            session.setStatus(SessionStatus.STATEMENT_RUNNING);
            executeLoopUpdate();
            if (session.needYieldOrWait()) {
                return;
            }
        }
        handleResult();
    }

    protected abstract void executeLoopUpdate();

    private void handleResult() {
        if (loopEnd && pendingOperationCount <= 0) {
            setResult(updateCount);
            session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        }
    }

    protected void onLoopEnd() {
        // 循环已经结束了，但是异步更新可能没有完成，所以先把状态改成STATEMENT_RUNNING，避免调度器空转
        session.setStatus(SessionStatus.STATEMENT_RUNNING);
        loopEnd = true;
    }

    public void setReturningTarget(ResultTarget returningTarget) {
        this.returningTarget = returningTarget;
    }

    // 写成功后调用，把RETURNING表达式算出来的值写到结果集
    protected void addReturningRow(Row row) {
        if (returningTarget == null || row == null)
            return;
        Value[] values = statement.evalReturning(row);
        if (values != null)
            returningTarget.addRow(values);
    }

    // MERGE更新分支已经从内层UPDATE拿到了返回行的值
    protected void addReturningRow(Value[] values) {
        if (returningTarget != null && values != null)
            returningTarget.addRow(values);
    }

    protected void onPendingOperationStart() {
        pendingOperationCount++;
    }

    // 执行回调的线程跟执行命令的线程都是同一个
    protected void onPendingOperationComplete(AsyncResult<Integer> ar) {
        if (ar.isSucceeded()) {
            updateCount++;
        } else {
            setPendingException(ar.getCause());
        }
        pendingOperationCount--;
        handleResult();
    }

    protected boolean fireBeforeRow(Table table, Row oldRow, Row newRow) {
        try {
            // 有可能抛出异常
            return table.fireBeforeRow(session, oldRow, newRow); // INSTEAD OF触发器会返回true
        } catch (Throwable e) {
            setPendingException(e);
            return true;
        }
    }

    protected void fireAfterRow(Table table, Row oldRow, Row newRow) {
        try {
            // 有可能抛出异常
            table.fireAfterRow(session, oldRow, newRow, false);
        } catch (Throwable e) {
            setPendingException(e);
        }
    }
}
