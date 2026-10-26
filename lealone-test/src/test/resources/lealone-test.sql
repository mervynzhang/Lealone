-- Copyright Lealone Database Group.
-- Licensed under the Server Side Public License, v 1.
-- Initial Developer: zhh

set lealone (
    base_dir: 'target/test-data',
    listen_address: '127.0.0.1',
    log: (
        type: 'console',
        level: 'info'
    ),
    scheduler: (
        -- 功能测试不关心并发度, 显式给一个小值, 避免在无 cgroup 限制的高核 CI runner 上
        -- 按 availableProcessors() 起上百个调度线程(AOTE 还会按该值建
        -- TransactionManager/RedoLog 数组)。并发/吞吐结论见 docs/PERF_SCHEDULER.md。
        scheduler_count: 4,
        prefer_batch_write: false,
        max_packet_count_per_loop: 1000, -- 每次循环最多读取多少个数据包，默认20
    ),
    storage_engine: (
        name: 'AOSE',
        enabled: true,
        page_size: '16k',
        cache_size: '32m', -- 每个 btree 的缓存大小
        compress: 'no', -- no、lzf、deflate 或 0、1、2
    ),
    transaction_engine: (
        name: 'AOTE',
        enabled: true ,
        log_sync_type: 'periodic' -- 取值：instant,no_sync,periodic，默认是periodic
    ),
    sql_engine: (
        name: 'Lealone',
        enabled: true,
    ),
    protocol_server_engine: (
        name: 'TCP',
        enabled: true,
        port: 9210,
        allow_others: true,
        -- white_list: '127.0.0.4,127.0.0.2',
        ssl: false,
        session_timeout: -1
    ),
    protocol_server_engine: (
        name: 'HTTP',
        enabled: false,
        port: 8080,
        allow_others: true,
        ssl: false,
        -- default_database: 'test',
        session_timeout: -1
    )
)
