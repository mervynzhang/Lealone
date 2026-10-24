/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db.value;

import org.junit.Test;

import com.lealone.db.value.Value;
import com.lealone.db.value.ValueLob;
import com.lealone.test.UnitTestBase;

/**
 * link(dh, tabId) 在 dh 为 null 时不能空指针：反序列化出来的"已存储LOB"没有 data handler，
 * 这时没有 LOB 存储可操作，应保持原状（和 unlink 的处理一致）。
 */
public class LobLinkWithoutHandlerTest extends UnitTestBase {

    @Test
    public void testLinkWithoutHandler() {
        ValueLob stored = ValueLob.create(Value.BLOB, null, 7, 123, null, 100);
        assertSame("没有handler时link应该原样返回", stored, stored.link(null, 9));
        assertEquals("表id不应该被改动", 7, stored.getTableId());

        // 小LOB路径(全内存)也不应该崩
        ValueLob small = ValueLob.createSmallLob(Value.BLOB, new byte[] { 1, 2, 3 });
        assertNotNull(small.link(null, 9));
    }
}
