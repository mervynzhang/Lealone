/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.lealone.common.util.CaseInsensitiveMap;
import com.lealone.sql.config.Config;

/**
 * scheduler_count 的默认值必须是 Runtime.availableProcessors()。
 *
 * 高核数/高并发下不要改成固定值: 实测 32 客户端线程时 8 个调度线程只能跑到
 * 37.9-41.1k op/s、p99 5.2-5.7ms, 默认 32 个调度线程(x86 pod 的 cgroup 配额)是
 * 55.0k op/s、p99 0.79-0.82ms; 而 64 个调度线程在 32 核配额上会过订阅, 64 线程
 * p99 反而到 51ms。详见 docs/PERF_SCHEDULER.md。
 */
public class SchedulerConfigTest {

    @Test
    public void testDefaultSchedulerCount() {
        Config config = new Config();
        assertEquals(String.valueOf(Runtime.getRuntime().availableProcessors()),
                config.scheduler.parameters.get("scheduler_count"));
    }

    @Test
    public void testSchedulerCountCanBeOverridden() {
        Config config = new Config();
        CaseInsensitiveMap<String> parameters = new CaseInsensitiveMap<>();
        parameters.put("scheduler_count", "8");
        config.setSchedulerParameters(parameters);
        assertEquals("8", config.scheduler.parameters.get("scheduler_count"));
    }
}
