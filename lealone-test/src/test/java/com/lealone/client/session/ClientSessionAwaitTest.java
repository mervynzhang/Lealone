/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.client.session;

import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.test.UnitTestBase;

/**
 * 等待调度器执行任务必须**有界**。
 * <p>
 * 实测偶发：客户端调度线程全都停在 {@code NioEventLoop.select} 上、任务队列不推进，
 * 旧代码里的 {@code latch.await()} 没有超时，于是关连接时调用方永久阻塞 ——
 * 整个测试套件挂死，每次都要等满 300 秒超时才报错，而且当时没有任何线程栈可看。
 */
public class ClientSessionAwaitTest extends UnitTestBase {

    @Test
    public void testAwaitReturnsWhenCountedDown() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        long t0 = System.currentTimeMillis();
        ClientSession.awaitOrThrow(latch, 10_000, "probe");
        assertTrue("已经完成的任务必须立刻返回", System.currentTimeMillis() - t0 < 1_000);
    }

    @Test
    public void testAwaitThrowsInsteadOfBlockingForever() throws Exception {
        CountDownLatch latch = new CountDownLatch(1); // 永远不会 countDown
        long t0 = System.currentTimeMillis();
        Throwable caught = null;
        try {
            ClientSession.awaitOrThrow(latch, 200, "switch connection to bio mode");
        } catch (Throwable t) {
            caught = t;
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertNotNull("超时必须抛异常，不能永久阻塞", caught);
        assertTrue("必须是 DbException, 实际: " + caught.getClass().getName(),
                caught instanceof DbException);
        assertTrue("超时时间不能失控, 实际等了 " + elapsed + " ms", elapsed < 5_000);
        String msg = caught.getMessage();
        assertNotNull(msg);
        assertTrue("错误信息必须能看出等的是什么操作: " + msg, msg.contains("switch connection to bio mode"));
        assertTrue("错误信息必须带超时时间: " + msg, msg.contains("200"));
    }
}
