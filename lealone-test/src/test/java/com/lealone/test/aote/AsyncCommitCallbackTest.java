/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.aote;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.lealone.transaction.Transaction;
import com.lealone.transaction.TransactionMap;
import com.lealone.transaction.aote.AOTransaction;

/**
 * 异步提交的回调必须"恰好执行一次"：
 * 既不能丢(丢了语句永远等不到响应, 客户端挂死)，也不能重复(重复了客户端会收到两次响应)。
 * <p>
 * 同一条语句可能被重复 stop，此时事务已经提交结束，提交路径仍会补调一次 asyncCommitComplete，
 * 因此回调必须取走再置空。
 */
public class AsyncCommitCallbackTest extends AoteTestBase {

    private Transaction beginWithData(String key) {
        Transaction t = te.beginTransaction();
        TransactionMap<String, String> map = t.openMap(mapName, storage);
        map.put(key, "v" + key);
        return t;
    }

    // 回调不能丢：事务已经结束后再次异步提交，仍然要给出回调
    @Test
    public void testCallbackNotLostOnFinishedTransaction() throws Exception {
        Transaction t = beginWithData("1");
        CountDownLatch first = new CountDownLatch(1);
        t.asyncCommit(first::countDown);
        if (!first.await(30, TimeUnit.SECONDS))
            fail("第一次异步提交没有回调，语句会永远等不到响应");

        // 同一条语句被重复 stop 时会再走一次异步提交
        AtomicInteger second = new AtomicInteger();
        CountDownLatch secondLatch = new CountDownLatch(1);
        t.asyncCommit(() -> {
            second.incrementAndGet();
            secondLatch.countDown();
        });
        if (!secondLatch.await(30, TimeUnit.SECONDS))
            fail("事务已经结束后再次异步提交没有回调，语句会挂死");
        assertEquals(1, second.get());
    }

    // 回调不能重复：重复调用 asyncCommitComplete 时，第一次的回调不能再跑一遍
    @Test
    public void testCallbackRunsExactlyOnce() throws Exception {
        Transaction t = beginWithData("2");
        AtomicInteger count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        t.asyncCommit(() -> {
            count.incrementAndGet();
            latch.countDown();
        });
        if (!latch.await(30, TimeUnit.SECONDS))
            fail("异步提交没有回调");
        assertEquals(1, count.get());

        // 重复 stop 的补调路径：再调一次不能把第一次的回调又跑一遍
        ((AOTransaction) t).asyncCommitComplete();
        assertEquals(1, count.get());
    }
}
