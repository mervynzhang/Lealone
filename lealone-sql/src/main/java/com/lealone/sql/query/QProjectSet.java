/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import com.lealone.db.result.Result;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;

/**
 * 目标列表里的setof函数展开，类似PostgreSQL的ProjectSet算子。
 * 一个输入行先算出各个setof函数的结果集(复用FROM里的FunctionTable链路)，
 * 然后按最长的那个对齐逐行输出，短的补NULL，非setof列的值每一行都复制一份。
 */
class QProjectSet extends QFlat {

    // 每个setof函数对应一个展开列
    private final SrfExpression[] srfExpressions;
    private final int[] srfIndexes;

    // 当前输入行的展开状态，可能中途让出执行权，下次继续
    private Result[] srfResults;
    private Value[] pendingRow;
    private int pendingRowCount;
    private int pendingIndex;

    QProjectSet(Select select) {
        super(select);
        srfExpressions = select.getSrfExpressions();
        srfIndexes = select.getSrfIndexes();
    }

    @Override
    public void start() {
        // 已经展开成多行了，不需要再从oltp转到olap
        yieldableSelect.disableOlap();
        super.start();
    }

    @Override
    public void run() {
        boolean single = isSingleTableScan();
        while (true) {
            // 上次让出执行权时还没展开完的输入行先展开
            if (srfResults != null) {
                if (!expandPendingRow()) {
                    return; // 又让出了
                }
                srfResults = null;
                if (canBreakLoop()) {
                    break;
                }
            }
            if (!next()) {
                break;
            }
            boolean yield = yieldIfNeeded(++loopCount);
            if (conditionEvaluator.getBooleanValue()) {
                if (select.isForUpdate && !tryLockRow()) {
                    return; // 锁记录失败
                }
                if (!isDuplicated(single)) {
                    initPendingRow();
                }
            }
            if (yield) {
                return;
            }
        }
        loopEnd = true;
    }

    // 算出当前输入行里各个setof函数的结果集，非setof列先取值(和PostgreSQL一样每行只算一次)
    private void initPendingRow() {
        srfResults = new Result[srfExpressions.length];
        pendingRowCount = 0;
        pendingIndex = 0;
        for (int i = 0; i < srfExpressions.length; i++) {
            Result r = srfExpressions[i].getFunctionTable().getResult(session);
            srfResults[i] = r;
            if (r.getRowCount() > pendingRowCount) {
                pendingRowCount = r.getRowCount();
            }
        }
        pendingRow = new Value[columnCount];
        for (int i = 0; i < columnCount; i++) {
            if (!isSrfColumn(i)) {
                pendingRow[i] = select.expressions.get(i).getValue(session);
            }
        }
    }

    // 展开当前输入行，返回true表示展开完了，false表示中途让出
    private boolean expandPendingRow() {
        while (pendingIndex < pendingRowCount) {
            Value[] row = new Value[columnCount];
            System.arraycopy(pendingRow, 0, row, 0, columnCount);
            for (int i = 0; i < srfExpressions.length; i++) {
                row[srfIndexes[i]] = nextSrfValue(i);
            }
            result.addRow(row);
            rowCount++;
            pendingIndex++;
            if (canBreakLoop()) {
                return true;
            }
            if (yieldIfNeeded(++loopCount)) {
                return false;
            }
        }
        return true;
    }

    // 结果集不够长时(多个setof函数长度不一样)补NULL
    private Value nextSrfValue(int index) {
        Result r = srfResults[index];
        if (pendingIndex >= r.getRowCount()) {
            return ValueNull.INSTANCE;
        }
        r.next();
        return r.currentRow()[0];
    }

    private boolean isSrfColumn(int index) {
        for (int i = 0; i < srfIndexes.length; i++) {
            if (srfIndexes[i] == index) {
                return true;
            }
        }
        return false;
    }
}
