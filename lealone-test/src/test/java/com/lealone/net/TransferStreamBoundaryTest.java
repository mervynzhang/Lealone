/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

/**
 * 字符串协议读写两端的长度上限必须对称: 读端允许 0..2^30-1 个字符,
 * 写端在同一个边界上拒绝, 避免 len << 1 溢出成负数。
 */
public class TransferStreamBoundaryTest {

    @Test
    public void testWriteStringLengthLimit() throws IOException {
        TransferOutputStream.checkStringLength(Integer.MAX_VALUE >> 1);
        try {
            TransferOutputStream.checkStringLength((Integer.MAX_VALUE >> 1) + 1);
            fail("超过读端上限的字符串长度必须被拒绝");
        } catch (IOException e) {
            assertTrue("异常信息应说明协议上限: " + e.getMessage(),
                    e.getMessage().contains("字符串长度超出协议上限"));
        }
        try {
            TransferOutputStream.checkStringLength(-1);
            fail("负长度必须被拒绝");
        } catch (IOException e) {
            // expected
        }
    }
}
