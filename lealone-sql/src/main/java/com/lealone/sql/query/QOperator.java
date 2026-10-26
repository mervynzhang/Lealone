/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import java.util.ArrayList;

import com.lealone.db.result.LocalResult;
import com.lealone.db.result.ResultTarget;
import com.lealone.db.row.Row;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ValueExpression;
import com.lealone.sql.expression.evaluator.AlwaysTrueEvaluator;
import com.lealone.sql.expression.evaluator.ExpressionEvaluator;
import com.lealone.sql.expression.evaluator.ExpressionInterpreter;
import com.lealone.sql.operator.Operator;
import com.lealone.sql.optimizer.TableFilter;
import com.lealone.sql.optimizer.TableIterator;

// 由子类实现具体的查询操作
public abstract class QOperator implements Operator {

    protected final Select select;
    protected final TableFilter topTableFilter;
    protected final ServerSession session;
    protected final ExpressionEvaluator conditionEvaluator;
    protected final TableIterator tableIterator;

    protected boolean hasNext;
    protected Row oldRow;

    protected int columnCount;
    protected ResultTarget target;
    protected ResultTarget result;
    protected LocalResult localResult;
    protected int maxRows; // 实际返回的最大行数
    protected long limitRows; // 有可能超过maxRows
    protected int sampleSize;
    protected int rowCount; // 满足条件的记录数
    protected int loopCount; // 循环次数，有可能大于rowCount
    protected boolean loopEnd;

    protected YieldableSelect yieldableSelect;

    // 有窗口函数时，行先缓存到 windowTarget，等全部读完后再计算窗口列
    private WindowResultTarget windowTarget;
    private ResultTarget windowRealTarget;

    public QOperator(Select select) {
        this.select = select;
        topTableFilter = select.getTopTableFilter();
        session = select.getSession();
        tableIterator = new TableIterator(session, select.getTopTableFilter());
        Expression c = select.condition;
        // 没有查询条件或者查询条件是常量时看看是否能演算为true,false在IndexCursor.isAlwaysFalse()中已经处理了
        if (c == null || (c instanceof ValueExpression && c.getValue(session).getBoolean())) {
            conditionEvaluator = new AlwaysTrueEvaluator();
        } else {
            conditionEvaluator = createConditionEvaluator(c);
        }
    }

    // 允许子类覆盖
    public ExpressionEvaluator createConditionEvaluator(Expression c) {
        return new ExpressionInterpreter(session, c);
    }

    public boolean yieldIfNeeded(int rowNumber) {
        return yieldableSelect.yieldIfNeeded(rowNumber);
    }

    /**
     * 打开窗口函数需要的行缓冲：result 先指向缓冲区，等扫描结束后由执行器分步计算窗口列，
     * 算完再把缓冲的行交给真正的目标。
     */
    public void enableWindowBuffering(ResultTarget to) {
        windowRealTarget = to;
        windowTarget = new WindowResultTarget(session);
        result = windowTarget;
    }

    /** 是否还有等待计算窗口列的缓冲行 */
    public boolean hasWindowBuffer() {
        return windowTarget != null;
    }

    /**
     * 取出缓冲的行交给执行器计算窗口列；同时解除 result 引用，
     * 避免 stop() 再走一次整表计算。
     */
    public ArrayList<Value[]> takeWindowRows() {
        if (windowTarget == null) {
            return null;
        }
        ArrayList<Value[]> rows = windowTarget.getRows();
        windowTarget = null;
        return rows;
    }

    /**
     * 窗口列已经算完，把行写给真正的目标。必须在 localResult.done()/setLimit 之前调用。
     */
    public void finishWindowRows(ArrayList<Value[]> rows) {
        ResultTarget to = windowRealTarget;
        windowRealTarget = null;
        if (to == null || rows == null) {
            return;
        }
        for (Value[] row : rows) {
            to.addRow(row);
        }
    }

    /**
     * 异常/取消时丢弃窗口缓冲：不再做排序/分区扫描，也不再写结果。
     */
    public void abortWindow() {
        windowTarget = null;
        windowRealTarget = null;
    }

    public boolean canBreakLoop() {
        // 有窗口函数时必须读完全部行才能计算（窗口语义要求看到整个分区，
        // LIMIT 不能提前截断；缓冲大小由 WindowResultTarget 的 maxMemoryRows 限制）
        if (windowTarget != null) {
            return false;
        }
        // 不需要排序时，如果超过行数限制了可以退出循环
        if ((select.sort == null || select.sortUsingIndex) && limitRows > 0 && rowCount >= limitRows) {
            return true;
        }
        // 超过采样数也可以退出循环
        if (sampleSize > 0 && rowCount >= sampleSize) {
            return true;
        }
        return false;
    }

    protected boolean next() {
        return tableIterator.next();
    }

    protected boolean tryLockRow() {
        return tableIterator.tryLockRow() > 0;
    }

    @Override
    public void start() {
        limitRows = maxRows;
        // 并不会按offset先跳过前面的行数，而是limitRows加上offset，读够limitRows+offset行，然后再从result中跳
        // 因为可能需要排序，offset是相对于最后的结果来说的，而不是排序前的结果
        // limitRows must be long, otherwise we get an int overflow
        // if limitRows is at or near Integer.MAX_VALUE
        // limitRows is never 0 here
        if (limitRows > 0 && select.offsetExpr != null) {
            int offset = select.offsetExpr.getValue(session).getInt();
            if (offset > 0) {
                limitRows += offset;
            }
            if (limitRows < 0) {
                // Overflow
                limitRows = Long.MAX_VALUE;
            }
        }
        rowCount = 0;
        select.setCurrentRowNumber(0);
        sampleSize = select.getSampleSizeValue(session);
        tableIterator.start();
    }

    @Override
    public void run() {
    }

    @Override
    public void stop() {
        // 正常路径下窗口列已经在 executeInternal 的分步计算里算完并 flush 过了。
        // 走到这里还有缓冲，说明扫描/计算中途异常或取消，直接丢弃：
        // 不能在 stop() 里再做一次 O(n log n) 的排序/聚合（异常路径会把整表算完）。
        abortWindow();
        if (select.offsetExpr != null) {
            localResult.setOffset(select.offsetExpr.getValue(session).getInt());
        }
        if (maxRows >= 0) {
            localResult.setLimit(maxRows);
        }
        handleLocalResult();
    }

    void handleLocalResult() {
        if (localResult != null) {
            localResult.done();
            if (target != null) {
                while (localResult.next()) {
                    target.addRow(localResult.currentRow());
                }
                localResult.close();
            }
        }
    }

    @Override
    public boolean isStopped() {
        return loopEnd;
    }

    @Override
    public LocalResult getLocalResult() {
        return localResult;
    }

    public Value[] createRow() {
        Value[] row = new Value[columnCount];
        for (int i = 0; i < columnCount; i++) {
            Expression expr = select.expressions.get(i);
            row[i] = expr.getValue(session);
        }
        return row;
    }

    @Override
    public void onLockedException() {
        tableIterator.onLockedException();
    }

    public void copyStatusTo(QOperator o) {
        o.columnCount = columnCount;
        o.target = target;
        o.result = result;
        o.localResult = localResult;
        o.maxRows = maxRows;
        o.limitRows = limitRows;
        o.sampleSize = sampleSize;
        o.rowCount = rowCount;
        o.loopCount = loopCount;
        o.yieldableSelect = yieldableSelect;
    }
}
