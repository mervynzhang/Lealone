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
        -- 不要在这里写死 scheduler_count。这份配置也被压测复用,
        -- 写死 8 会把 16/32/64 并发的服务端压在 8 个核上:
        -- 实测 count(*) 32 客户端线程: 8 调度 37.9-41.1k op/s / p99 5.2-5.7ms,
        -- 默认(Runtime.availableProcessors(), 本 pod cgroup 配额 32) 55.0k / p99 0.79-0.82ms。
        -- 需要让测试服务轻量时用命令行/连接参数显式覆盖, 不要改这里。
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
