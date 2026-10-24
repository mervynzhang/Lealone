/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.client;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import com.lealone.test.UnitTestBase;

/**
 * 超时规则必须在超时时把线程栈打出来：这类偶发挂死如果只报"超时 300 秒"，根本没法定位
 * （本套件里同一处挂死偶发出现过两次，每次都没有栈可看，只能重跑碰运气）。
 */
public class DiagnosticTimeoutTest extends UnitTestBase {

    @Test
    public void testDumpsThreadsOnTimeout() throws Throwable {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        Throwable failure = null;
        try {
            Statement slow = new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    Thread.sleep(5000);
                }
            };
            Statement st = DiagnosticTimeout.seconds(1).apply(slow,
                    Description.createTestDescription(DiagnosticTimeoutTest.class, "dumpProbe"));
            try {
                st.evaluate();
            } catch (Throwable t) {
                failure = t;
            }
        } finally {
            System.setOut(old);
        }
        String out = buf.toString("UTF-8");
        assertNotNull("超时必须仍然抛出异常(不能吞掉)", failure);
        assertTrue("缺少用例名标记, 实际输出: " + out.substring(0, Math.min(200, out.length())),
                out.contains("用例超时线程栈"));
        assertTrue("缺少线程栈内容", out.contains("at "));
        assertTrue("缺少线程栈结束标记", out.contains("线程栈结束"));
        assertTrue("超时异常本身必须保留", failure.getClass().getName().contains("TestTimedOutException"));
    }
}
