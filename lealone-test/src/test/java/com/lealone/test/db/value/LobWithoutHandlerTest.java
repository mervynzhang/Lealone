/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db.value;

import java.io.InputStream;
import java.lang.reflect.Field;

import org.junit.Test;

import com.lealone.db.value.Value;
import com.lealone.db.value.ValueLob;
import com.lealone.test.UnitTestBase;

/**
 * 已存储的 LOB(不在内存、也不是临时文件)却没有 data handler 时，
 * 以前直接抛 "NullPointerException: Cannot invoke getLobStorage() because this.handler is null"，
 * 完全看不出是哪张表哪个 LOB(全量套件里偶发过一次 SCRIPT 导出失败就是这个)。
 * 现在必须抛带 lobId/tableId 的内部错误。
 */
public class LobWithoutHandlerTest extends UnitTestBase {

    @Test
    public void testMissingHandlerReportsContext() throws Exception {
        // lobId=123, tableId=7 的"已存储LOB"
        ValueLob lob = ValueLob.create(Value.BLOB, null, 7, 123, null, 100);
        Field handler = ValueLob.class.getDeclaredField("handler");
        handler.setAccessible(true);
        handler.set(lob, null);
        try {
            InputStream in = lob.getInputStream();
            in.close();
            fail("没有 data handler 的已存储LOB不应该能读出输入流");
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            assertTrue("错误信息里要带 lobId, 实际: " + msg, msg.contains("123"));
            assertTrue("错误信息里要带 tableId, 实际: " + msg, msg.contains("7"));
            assertFalse("不应该还是裸空指针", e instanceof NullPointerException);
        }
    }
}
