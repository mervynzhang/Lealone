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
 * "已存储的LOB没有data handler"这个状态确实会出现（DataBuffer.readValue 反序列化出来就是 null）。
 * 会话提交/回滚时会调用 unlink(v.getHandler())，以前这里会直接空指针；
 * 没有LOB存储可操作时应该保持原状（还挂在原表上），而不是崩掉。
 */
public class LobUnlinkWithoutHandlerTest extends UnitTestBase {

    @Test
    public void testUnlinkWithoutHandler() {
        ValueLob lob = ValueLob.create(Value.BLOB, null, 7, 123, null, 100);
        lob.unlink(null); // 修复前: NullPointerException
        assertEquals("没有handler时表id不应该被改动", 7, lob.getTableId());
        assertTrue("LOB仍然算挂在表上", lob.isLinked());

        // 正常有handler的路径不受影响
        ValueLob small = ValueLob.createSmallLob(Value.BLOB, new byte[] { 1, 2 });
        small.unlink(null);
        assertEquals(0, small.getTableId());
    }
}
