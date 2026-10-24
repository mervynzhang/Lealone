/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * 带线程栈转储的超时规则。
 * <p>
 * 普通 {@code Timeout} 超时后只报一句"test timed out after N seconds"，而这类偶发挂死的根因
 * 恰恰要看**卡在哪个线程、哪一行**才能定位（本套件里同一个挂死已经偶发出现过两次，每次都没有栈可看）。
 * 这个规则在超时时把全部线程栈打到标准输出，再照常抛超时异常，不改变原有语义。
 */
public class DiagnosticTimeout implements TestRule {

    public static DiagnosticTimeout seconds(int seconds) {
        return new DiagnosticTimeout(seconds);
    }

    private final int seconds;

    private DiagnosticTimeout(int seconds) {
        this.seconds = seconds;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        Statement delegate = Timeout.seconds(seconds).apply(base, description);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    delegate.evaluate();
                } catch (Throwable t) {
                    if (t.getClass().getName().contains("TestTimedOutException"))
                        dumpThreads(description, t);
                    throw t;
                }
            }
        };
    }

    private static void dumpThreads(Description description, Throwable cause) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        out.println("==== 用例超时线程栈: " + description.getDisplayName() + " ====");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            out.println("---- \"" + th.getName() + "\" state=" + th.getState() + " daemon="
                    + th.isDaemon());
            for (StackTraceElement ste : e.getValue())
                out.println("      at " + ste);
        }
        out.println("==== 线程栈结束 ====");
        out.flush();
        System.out.println(sw.toString());
        cause.addSuppressed(new IllegalStateException("线程栈见标准输出"));
    }
}
