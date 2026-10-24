/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.dml;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

public class SequenceLockedExceptionTest extends SqlTestBase {

    // worker 线程里一旦抛异常, 旧实现只打印堆栈不 countDown, 主线程会永久等待(surefire 也没有超时)
    private final AtomicReference<Throwable> workerError = new AtomicReference<>();

    private void awaitWorkers(CountDownLatch latch) throws Exception {
        if (!latch.await(30, TimeUnit.SECONDS))
            fail("worker 线程没有在 30 秒内完成");
        Throwable t = workerError.get();
        if (t != null)
            throw new AssertionError("worker 线程执行失败: " + t, t);
    }

    @Test(timeout = 60000)
    public void run() throws Exception {
        executeUpdate("DROP SEQUENCE IF EXISTS seq_locked");
        executeUpdate("CREATE SEQUENCE IF NOT EXISTS seq_locked");
        executeUpdate("DROP TABLE IF EXISTS SequenceLockedExceptionTest");
        executeUpdate("CREATE TABLE IF NOT EXISTS SequenceLockedExceptionTest (f1 int, f2 int)");

        CountDownLatch latch = new CountDownLatch(2);
        executeInNewThread(latch,
                "insert into SequenceLockedExceptionTest values(1, seq_locked.NEXTVAL)");
        executeInNewThread(latch,
                "insert into SequenceLockedExceptionTest values(2, seq_locked.NEXTVAL)");
        awaitWorkers(latch);
        sql = "SELECT count(*) FROM SequenceLockedExceptionTest where f1>=1";
        assertEquals(2, getIntValue(1, true));

        latch = new CountDownLatch(2);
        executeInNewThread(latch, "select seq_locked.NEXTVAL");
        executeInNewThread(latch, "select seq_locked.NEXTVAL");
        awaitWorkers(latch);
        sql = "SELECT seq_locked.NEXTVAL";
        assertEquals(5, getIntValue(1, true));

        latch = new CountDownLatch(2);
        executeInNewThread(latch,
                "update SequenceLockedExceptionTest set f2=seq_locked.NEXTVAL where f1=1");
        executeInNewThread(latch,
                "update SequenceLockedExceptionTest set f2=seq_locked.NEXTVAL where f1=2");
        awaitWorkers(latch);
        sql = "SELECT seq_locked.CURRVAL";
        assertEquals(7, getIntValue(1, true));
        sql = "SELECT count(*) FROM SequenceLockedExceptionTest where f2>=5";
        assertEquals(2, getIntValue(1, true));
    }

    private void executeInNewThread(CountDownLatch latch, String sql) {
        new Thread(() -> {
            Connection conn = null;
            Statement stmt = null;
            try {
                conn = getConnection();
                stmt = conn.createStatement();
                stmt.executeUpdate("set lock_timeout 10000");
                conn.setAutoCommit(false);
                stmt.execute(sql);
                conn.commit();
            } catch (Throwable e) {
                workerError.compareAndSet(null, e);
            } finally {
                // 无论成功失败都必须放行主线程, 否则测试会永久挂死
                latch.countDown();
                close(stmt);
                close(conn);
            }
        }).start();
    }

    private static void close(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // 关闭失败不影响测试结论
            }
        }
    }
}
