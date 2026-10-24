/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.db.table.StandardTable;
import com.lealone.db.value.Value;
import com.lealone.sql.optimizer.TableFilter;

// 最普通的查询
class QFlat extends QOperator {

    private static final Logger logger = LoggerFactory.getLogger(QFlat.class);

    QFlat(Select select) {
        super(select);
    }

    // 没有嵌套连接时，一条语句里同一个主表行只应产出一行：
    // 并发写下索引项可能让同一行被读到两次
    private static final int MAX_DEDUPE_KEYS = 100000; // 与结果集内存行数上限一致
    private com.lealone.common.util.LongHashSet emittedKeys;
    private boolean dedupeLimitWarned;

    @Override
    public void run() {
        TableFilter tf = select.getTopTableFilter();
        boolean single = tf.getJoin() == null && tf.getNestedJoin() == null && !select.isForUpdate
                && tf.getTable() instanceof StandardTable;
        while (next()) {
            boolean yield = yieldIfNeeded(++loopCount);
            if (conditionEvaluator.getBooleanValue()) {
                if (select.isForUpdate && !tryLockRow()) {
                    return; // 锁记录失败
                }
                Value[] row = createRow();
                if (single) {
                    long key = tableIterator.getCurrentKey();
                    if (key >= 0) {
                        if (emittedKeys == null)
                            emittedKeys = new com.lealone.common.util.LongHashSet(16);
                        if (emittedKeys.size() < MAX_DEDUPE_KEYS) {
                            if (!emittedKeys.add(key))
                                continue;
                        } else if (!dedupeLimitWarned) {
                            // 超过上限就不再记录key(不能为了去重让内存无界增长), 但必须留痕:
                            // 这条语句之后的重复行不会再被去掉, 出现重复时才知道是这里放弃了去重
                            dedupeLimitWarned = true;
                            logger.warn("去重key超过 {} 个, 本语句后续不再去重(并发写下可能返回重复行)",
                                    MAX_DEDUPE_KEYS);
                        }
                    }
                }
                result.addRow(row);
                rowCount++;
                if (canBreakLoop()) {
                    break;
                }
            }
            if (yield)
                return;
        }
        loopEnd = true;
    }
}
