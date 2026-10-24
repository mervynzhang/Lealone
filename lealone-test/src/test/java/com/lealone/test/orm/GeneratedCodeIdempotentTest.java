/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.orm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * 代码生成必须幂等：生成结果写进被跟踪的源码目录(测试编译期就要引用这些模型类)，
 * 如果每次跑测试都重写一遍，工作区就会被弄脏(整篇替换)，只读检出还会直接失败。
 * <p>
 * 这里锁住两条：① 生成的文件用LF换行(和仓库里已提交的一致)；② 再生成一次内容不变、文件不被重写。
 */
public class GeneratedCodeIdempotentTest extends OrmTestBase {

    private static final File USER_JAVA = new File(
            "src/test/java/com/lealone/test/orm/generated/User.java");

    @Test
    public void testGeneratedCodeIsStable() throws Exception {
        assertTrue("生成的模型类应该在源码目录里: " + USER_JAVA.getAbsolutePath(), USER_JAVA.exists());

        SqlScript.createUserTable(this);
        byte[] first = Files.readAllBytes(USER_JAVA.toPath());
        assertEquals("生成文件必须用LF换行, 不能混入\\r", -1, indexOf(first, (byte) '\r'));

        long mtime = USER_JAVA.lastModified();
        Thread.sleep(1100); // 让文件系统时间的分辨率不再成为干扰
        SqlScript.createUserTable(this);
        byte[] second = Files.readAllBytes(USER_JAVA.toPath());

        assertEquals("再生成一次内容必须完全一致", new String(first, StandardCharsets.UTF_8),
                new String(second, StandardCharsets.UTF_8));
        assertEquals("内容没变就不该重写文件(否则会改mtime/弄脏工作区)", mtime,
                USER_JAVA.lastModified());
    }

    private static int indexOf(byte[] bytes, byte target) {
        for (int i = 0; i < bytes.length; i++)
            if (bytes[i] == target)
                return i;
        return -1;
    }
}
