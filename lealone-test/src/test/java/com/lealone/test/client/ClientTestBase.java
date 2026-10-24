/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.client;

import org.junit.Rule;
import org.junit.rules.Timeout;

import com.lealone.test.sql.SqlTestBase;

public abstract class ClientTestBase extends SqlTestBase {

    // 客户端用例是走TCP的: 一旦连接的事件循环注册失败(比如连接已经被关掉、SelectionKey被cancel),
    // 这个session就再也收不到响应, 调用方会一直等下去 —— 整个套件跟着挂死(实测偶发, 率约1/5)。
    // 给每个客户端用例加超时, 把"挂死"变成"明确的失败", 至少不会让一轮测试白跑。
    @Rule
    public Timeout globalTimeout = Timeout.seconds(300);

    protected ClientTestBase() {
    }

    protected ClientTestBase(String dbName) {
        super(dbName);
    }
}
