/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import com.lealone.db.table.StandardTable;
import com.lealone.sql.optimizer.TableFilter;

// 最普通的查询
class QFlat extends QOperator {

    QFlat(Select select) {
        super(select);
    }

    // 没有嵌套连接时，一条语句里同一个主表行只应产出一行：
    // 并发写下索引项可能让同一行被读到两次
    private static final int MAX_DEDUPE_KEYS = 100000; // 与结果集内存行数上限一致
    private com.lealone.common.util.LongHashSet emittedKeys;

    @Override
    public void run() {
        boolean single = isSingleTableScan();
        while (next()) {
            boolean yield = yieldIfNeeded(++loopCount);
            if (conditionEvaluator.getBooleanValue()) {
                if (select.isForUpdate && !tryLockRow()) {
                    return; // 锁记录失败
                }
                if (!isDuplicated(single)) {
                    emitRow();
                    if (canBreakLoop()) {
                        break;
                    }
                }
            }
            if (yield)
                return;
        }
        loopEnd = true;
    }

    // 单表扫描并且不是for update时，需要按主键去重
    protected boolean isSingleTableScan() {
        TableFilter tf = select.getTopTableFilter();
        return tf.getJoin() == null && tf.getNestedJoin() == null && !select.isForUpdate
                && tf.getTable() instanceof StandardTable;
    }

    protected boolean isDuplicated(boolean single) {
        if (!single) {
            return false;
        }
        long key = tableIterator.getCurrentKey();
        if (key < 0) {
            return false;
        }
        if (emittedKeys == null)
            emittedKeys = new com.lealone.common.util.LongHashSet(16);
        return emittedKeys.size() < MAX_DEDUPE_KEYS && !emittedKeys.add(key);
    }

    // 把当前行的投影结果加到结果集里，子类可以改成一个输入行产出多行(比如SRF展开)
    protected void emitRow() {
        result.addRow(createRow());
        rowCount++;
    }
}
