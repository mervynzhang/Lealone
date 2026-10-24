/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.transaction.aote.tm;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.lealone.transaction.aote.AOTransaction;
import com.lealone.transaction.aote.AOTransactionEngine;

public class ConcurrentTransactionManager extends TransactionManager {

    // key: transactionId
    private final ConcurrentHashMap<Long, AOTransaction> currentTransactions = new ConcurrentHashMap<>();
    private final AtomicInteger currentTransactionCount = new AtomicInteger();

    public ConcurrentTransactionManager(AOTransactionEngine te) {
        super(te);
    }

    @Override
    public AOTransaction removeTransaction(long tid, int bitIndex) {
        AOTransaction t = currentTransactions.remove(tid);
        // 并发提交时可能已经被其他线程移除，不能重复递减，否则计数可能变成负数
        if (t == null)
            return null;
        currentTransactionCount.decrementAndGet();
        super.removeTransaction(t);
        return t;
    }

    @Override
    public void addTransaction(AOTransaction t) {
        currentTransactionCount.incrementAndGet();
        currentTransactions.put(t.getTransactionId(), t);
    }

    @Override
    public void currentTransactions(List<AOTransaction> list) {
        list.addAll(currentTransactions.values());
    }

    @Override
    public int currentTransactionCount() {
        return currentTransactionCount.get();
    }
}
