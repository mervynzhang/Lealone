/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.index;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 索引正在异步构建时，走索引的查询结果必须和全表扫描一致。
 * <p>
 * 边建边查时二级索引游标由两段拼成：已建好的索引项 + 主表尾段。主表尾段返回的 searchRow 本身就是完整
 * 的主表行，如果游标仍然复用"上一条索引项校验时读出来的行"，调用方(按 rowKey 去重的 QFlat)就会一直
 * 拿到第一条尾段行的 rowKey，把后面所有尾段行都当成重复行丢掉，查询结果静默少行。
 * <p>
 * CREATE INDEX 是异步的，所以这个用例在建索引返回后立刻连续查询，覆盖构建期间的时间窗。
 */
public class IndexBuildingScanTest extends SqlTestBase {

    private static final int ROWS = 60_000;
    private static final int DISTINCT_VALUES = 1000;

    public IndexBuildingScanTest() {
        // 固定在 embedded 模式下运行，不依赖测试服务端口
        setEmbedded(true);
    }

    @Test
    public void testQueryDuringIndexBuildReturnsAllRows() throws Exception {
        // 关闭语句/结果缓存，否则同一条SQL会直接复用上一次的结果，测不到真正执行
        executeUpdate("SET QUERY_CACHE_SIZE 0");
        executeUpdate("DROP TABLE IF EXISTS ibs_t");
        executeUpdate("CREATE TABLE ibs_t(id int primary key, v int)");

        conn.setAutoCommit(false);
        StringBuilder batch = new StringBuilder();
        for (int i = 1; i <= ROWS; i++) {
            if (batch.length() > 0)
                batch.append(',');
            batch.append('(').append(i).append(',').append(i % DISTINCT_VALUES).append(')');
            if (i % 500 == 0 || i == ROWS) {
                executeUpdate("INSERT INTO ibs_t(id, v) VALUES " + batch);
                batch.setLength(0);
            }
        }
        conn.commit();
        conn.setAutoCommit(true);

        executeUpdate("CREATE INDEX ibs_t_v_idx ON ibs_t(v)");

        int expected = ROWS / DISTINCT_VALUES;
        // 建索引期间索引里已有的行在变多，每次查询都必须返回完整的行数，不能只返回索引里已建好的那部分
        for (int i = 0; i < 1500; i++) {
            executeQuery("SELECT COUNT(*) FROM ibs_t WHERE v = 7");
            try {
                assertEquals(expected, rs.getInt(1));
            } finally {
                closeResultSet();
            }
        }

        // 全表扫描的结果作为基准，索引建完后两者必须一致
        executeQuery("SELECT COUNT(*) FROM ibs_t WHERE v + 0 = 7");
        int byTableScan;
        try {
            byTableScan = rs.getInt(1);
        } finally {
            closeResultSet();
        }
        assertEquals(expected, byTableScan);
    }
}
