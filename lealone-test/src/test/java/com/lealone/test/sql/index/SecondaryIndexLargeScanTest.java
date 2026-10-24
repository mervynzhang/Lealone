/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.index;

import java.util.HashSet;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 二级索引扫描要能处理超过"行快照上限"的大表：每个 rowKey 一条快照会让内存随行数无界增长，
 * 所以外层加了上限(与结果集按 rowKey 去重的上限一致)。这里验证超限之后：
 * ① 一行都不少；② 单线程下一行都不重复。
 */
public class SecondaryIndexLargeScanTest extends SqlTestBase {

    private static final int ROWS = 120_000;

    public SecondaryIndexLargeScanTest() {
        setEmbedded(true);
    }

    @Test
    public void testScanBeyondSnapshotLimit() throws Exception {
        executeUpdate("SET QUERY_CACHE_SIZE 0");
        executeUpdate("DROP TABLE IF EXISTS sils_t");
        executeUpdate("CREATE TABLE sils_t(id int primary key, k int, pad varchar(100))");
        conn.setAutoCommit(false);
        StringBuilder batch = new StringBuilder();
        for (int i = 1; i <= ROWS; i++) {
            if (batch.length() > 0)
                batch.append(',');
            batch.append('(').append(i).append(',').append(i % 1000).append(",'p").append(i).append("')");
            if (i % 500 == 0 || i == ROWS) {
                executeUpdate("INSERT INTO sils_t VALUES " + batch);
                batch.setLength(0);
            }
        }
        conn.commit();
        conn.setAutoCommit(true);
        executeUpdate("CREATE INDEX sils_t_k_idx ON sils_t(k)");
        waitIndexBuilt();

        HashSet<Integer> seen = new HashSet<>();
        int count = 0;
        boolean duplicated = false;
        executeQuery("SELECT id FROM sils_t ORDER BY k, id");
        try {
            boolean hasRow = true;
            while (hasRow) {
                int id = rs.getInt(1);
                if (!seen.add(id))
                    duplicated = true;
                count++;
                hasRow = rs.next();
            }
        } finally {
            closeResultSet();
        }
        assertFalse("索引扫描不能返回重复行", duplicated);
        assertEquals(ROWS, count);
        assertEquals(ROWS, seen.size());
    }

    private void waitIndexBuilt() throws Exception {
        for (int i = 0; i < 300; i++) {
            executeQuery("SELECT COUNT(*) FROM sils_t WHERE k = 7");
            int n;
            try {
                n = rs.getInt(1);
            } finally {
                closeResultSet();
            }
            if (n == ROWS / 1000)
                return;
            Thread.sleep(10);
        }
        fail("二级索引没有在3秒内建好");
    }
}
