/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.aote;

import org.junit.Test;

import com.lealone.transaction.Transaction;
import com.lealone.transaction.TransactionMap;

/**
 * 事务结束之后回滚保存点属于编程错误，直接调用方应该看到异常；
 * 但语句异常处理的收尾路径必须仍然能把语句结束掉(不能挂死)。
 */
public class SavepointRollbackAfterCloseTest extends AoteTestBase {

    @Test
    public void testRollbackToSavepointAfterCommit() {
        Transaction t = te.beginTransaction();
        TransactionMap<String, String> map = t.openMap(mapName, storage);
        map.put("1", "a");
        t.commit();

        try {
            t.rollbackToSavepoint(0);
            fail("事务已经提交, 回滚保存点必须抛异常, 不能静默返回");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Transaction is closed"));
        }
    }

    @Test
    public void testRollbackToSavepointAfterRollback() {
        Transaction t = te.beginTransaction();
        TransactionMap<String, String> map = t.openMap(mapName + "2", storage);
        map.put("1", "a");
        t.rollback();

        try {
            t.rollbackToSavepoint(0);
            fail("事务已经回滚, 回滚保存点必须抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Transaction is closed"));
        }
    }

    // 引擎还能继续用: 抛异常不会把事务管理器的状态搞坏
    @Test
    public void testEngineStillUsable() {
        Transaction t = te.beginTransaction();
        t.commit();
        try {
            t.rollbackToSavepoint(0);
        } catch (IllegalStateException e) {
            // expected
        }
        t = te.beginTransaction();
        TransactionMap<String, String> map = t.openMap(mapName + "3", storage);
        map.put("k", "v");
        t.commit();
        assertEquals("v", map.get("k"));
    }
}
