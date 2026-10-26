/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.lealone.common.util.MapUtils;

/**
 * 锁住 scheduler_count 的真实读取路径: MapUtils.getSchedulerCount 只读配置 map,
 * 没有配置时回落到 Runtime.availableProcessors()。
 *
 * 高核数/高并发下的对比数据见 docs/PERF_SCHEDULER.md; 功能测试共用配置里必须显式给
 * 一个小值, 否则在无 cgroup 限制的 CI runner 上会按 availableProcessors() 起上百个
 * 调度线程(AOTE 还会按该值建 TransactionManager/RedoLog 数组)。
 */
public class SchedulerConfigTest {

    @Test
    public void testDefaultSchedulerCountIsAvailableProcessors() {
        assertEquals(Runtime.getRuntime().availableProcessors(),
                MapUtils.getSchedulerCount(new HashMap<String, String>()));
    }

    @Test
    public void testSchedulerCountCanBeOverridden() {
        Map<String, String> config = new HashMap<>();
        config.put("scheduler_count", "8");
        assertEquals(8, MapUtils.getSchedulerCount(config));

        config.put("scheduler_count", "0");
        assertEquals(1, MapUtils.getSchedulerCount(config));
    }

    @Test
    public void testFunctionalTestConfigUsesSmallSchedulerCount() throws Exception {
        String content = readTestConfig();
        assertFalse("功能测试配置不应写死高核机器上的核数 192",
                content.contains("scheduler_count: 192"));
        Matcher m = Pattern.compile("scheduler_count\\s*:\\s*(\\d+)").matcher(content);
        assertTrue("lealone-test.sql 必须显式配置 scheduler_count", m.find());
        int schedulerCount = Integer.parseInt(m.group(1));
        assertTrue("功能测试的 scheduler_count 应是小值, 实际是 " + schedulerCount,
                schedulerCount >= 1 && schedulerCount <= 8);
    }

    private static String readTestConfig() throws Exception {
        try (InputStream in = SchedulerConfigTest.class.getResourceAsStream("/lealone-test.sql")) {
            assertNotNull("classpath 上找不到 lealone-test.sql", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buff = new byte[4096];
            int len;
            while ((len = in.read(buff)) > 0) {
                out.write(buff, 0, len);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }
}
