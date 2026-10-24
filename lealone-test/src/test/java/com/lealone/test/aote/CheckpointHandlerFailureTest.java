/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.aote;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.transaction.Transaction;
import com.lealone.transaction.TransactionMap;

/**
 * checkpoint 前的"把异步索引操作尽量执行完"这一步失败时，必须放弃本次 checkpoint。
 * <p>
 * 如果失败被当成"没有待处理事务"(旧实现返回 Long.MAX_VALUE)，checkpoint 会用当前的
 * lastTransactionId 去截断 redo log，那些还没写进索引 map 的索引操作就永久丢了：
 * 重启后全表扫描正常，但走索引的查询查不到这些记录。
 */
public class CheckpointHandlerFailureTest extends AoteTestBase {

    private long waitDirty(TransactionMap<String, String> map, long deadline) {
        long dirty = map.collectDirtyMemory();
        while (dirty > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            dirty = map.collectDirtyMemory();
        }
        return dirty;
    }

    // 处理器抛异常时必须抛出去，不能静默变成"没有待处理事务"
    @Test
    public void testHandlerFailureIsNotSwallowed() {
        te.setBeforeCheckpointHandler(() -> {
            throw new IllegalArgumentException("boom");
        });
        try {
            te.runBeforeCheckpointHandler();
            fail("前置处理器失败时必须抛异常，不能返回 Long.MAX_VALUE 当成没有待处理事务");
        } catch (DbException e) {
            assertTrue(e.getMessage().contains("before checkpoint handler"));
        } finally {
            te.setBeforeCheckpointHandler(null);
        }
    }

    // 处理器失败时放弃本次检查点: 脏页不会被保存，redo log 也不会被截断
    @Test
    public void testCheckpointSkippedWhenHandlerFails() {
        Transaction t = te.beginTransaction();
        TransactionMap<String, String> map = t.openMap(mapName + "ckpt", storage);
        map.put("k1", "v1");
        t.commit();

        te.setBeforeCheckpointHandler(() -> {
            throw new IllegalStateException("boom");
        });
        try {
            te.checkpoint(true); // 不应对调用方抛异常
            long dirty = waitDirty(map, System.currentTimeMillis() + 500);
            assertTrue("处理器失败时检查点必须放弃保存, 但脏页被保存了", dirty > 0);
        } finally {
            te.setBeforeCheckpointHandler(null);
        }

        // 处理器恢复之后检查点还能正常完成, 数据不受影响
        te.checkpoint(true);
        assertEquals("v1", map.get("k1"));
    }
}
