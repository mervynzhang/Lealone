/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.client;

import org.junit.Rule;
import org.junit.rules.TestRule;

import com.lealone.test.sql.SqlTestBase;

public abstract class ClientTestBase extends SqlTestBase {

    // 客户端用例是走TCP的: 一旦连接的事件循环注册失败(比如连接已经被关掉、SelectionKey被cancel),
    // 这个session就再也收不到响应, 调用方会一直等下去 —— 整个套件跟着挂死(实测偶发, 率约1/5)。
    // 给每个客户端用例加超时, 把"挂死"变成"明确的失败", 至少不会让一轮测试白跑。
    // 超时时还要把**所有线程栈**打出来: 只报"超时300秒"没法定位, 而线程栈能直接看出卡在哪一步
    // (实测本套件里这个挂死偶发出现过两次, 每次都没有栈可看, 只能重跑)。
    @Rule
    public TestRule globalTimeout = DiagnosticTimeout.seconds(300);

    protected ClientTestBase() {
    }

    protected ClientTestBase(String dbName) {
        super(dbName);
    }
}
