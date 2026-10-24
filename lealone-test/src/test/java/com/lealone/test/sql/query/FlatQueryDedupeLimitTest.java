/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 单表查询的去重表有上限(防止内存无界增长)。超过上限后不再去重，但行不能因此丢失：
 * 这里用超过上限的行数验证结果行数仍然正确。
 */
public class FlatQueryDedupeLimitTest extends SqlTestBase {

    private static final int ROWS = 100_100;

    @Test
    public void testRowsNotLostBeyondDedupeLimit() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS fqdl_t");
        executeUpdate("CREATE TABLE fqdl_t(id int primary key, a int)");
        conn.setAutoCommit(false);
        // 批量插入, 否则 10 万条单行插入太慢
        StringBuilder batch = new StringBuilder();
        for (int i = 1; i <= ROWS; i++) {
            if (batch.length() > 0)
                batch.append(',');
            batch.append('(').append(i).append(',').append(i % 10).append(')');
            if (i % 500 == 0 || i == ROWS) {
                executeUpdate("INSERT INTO fqdl_t(id, a) VALUES " + batch);
                batch.setLength(0);
            }
        }
        conn.commit();
        conn.setAutoCommit(true);

        executeQuery("SELECT id, a FROM fqdl_t");
        int count = 0;
        try {
            boolean hasRow = true;
            while (hasRow) {
                rs.getInt(1);
                count++;
                hasRow = rs.next();
            }
        } finally {
            closeResultSet();
        }
        assertEquals(ROWS, count);

        executeQuery("SELECT COUNT(*) FROM fqdl_t WHERE a = 3");
        try {
            assertEquals(ROWS / 10, rs.getInt(1));
        } finally {
            closeResultSet();
        }
    }
}
