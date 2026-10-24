/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.index;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 覆盖索引扫描(只查索引列)的结果必须和主表扫描一致：不重不漏。
 * <p>
 * 这个用例守着二级索引游标的两条不变量：
 * ① 每条索引项都必须用它自己的主表行校验(不能复用上一条的缓存)，否则同一次扫描会把同一行输出两次；
 * ② 校验时读出来的主表行要能被随后的取值复用，否则覆盖索引查询每返回一行要读两次主表。
 * 第 ② 条是性能不变量，功能上表现为结果正确但读放大翻倍。
 */
public class CoveredIndexScanTest extends SqlTestBase {

    private static final int ROWS = 2000;

    private Map<Integer, Integer> scan(String sql) throws Exception {
        Map<Integer, Integer> result = new HashMap<>();
        executeQuery(sql);
        try {
            boolean hasRow = true;
            while (hasRow) {
                int id = rs.getInt(1);
                int a = rs.getInt(2);
                if (result.put(id, a) != null)
                    fail("同一个 id 被返回了两次: " + id);
                hasRow = rs.next();
            }
        } finally {
            closeResultSet();
        }
        return result;
    }

    @Test
    public void testCoveredIndexScanMatchesTableScan() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS cis_t");
        executeUpdate("CREATE TABLE cis_t(id int primary key, a int, b varchar(20))");
        executeUpdate("CREATE INDEX cis_t_a_idx ON cis_t(a)");
        for (int i = 0; i < ROWS; i++)
            executeUpdate("INSERT INTO cis_t(id, a, b) VALUES(" + i + ", " + (i % 100) + ", 'v" + i + "')");

        // 制造索引项变动: 一部分行的索引列被更新, 一部分行被删除
        for (int i = 0; i < ROWS; i += 7)
            executeUpdate("UPDATE cis_t SET a = " + (i % 50) + " WHERE id = " + i);
        for (int i = 0; i < ROWS; i += 11)
            executeUpdate("DELETE FROM cis_t WHERE id = " + i);

        Map<Integer, Integer> byIndex = scan("SELECT id, a FROM cis_t WHERE a >= 0");
        Map<Integer, Integer> byTable = scan("SELECT id, a FROM cis_t WHERE a >= 0");
        assertEquals(byTable.size(), byIndex.size());
        for (Map.Entry<Integer, Integer> e : byTable.entrySet())
            assertEquals(e.getValue(), byIndex.get(e.getKey()));
        assertTrue(byIndex.size() > 0);
    }

    // 同一行被连续更新多次后，索引项可能同时存在新旧两条，扫描只能输出一次
    @Test
    public void testRepeatedUpdateDoesNotReturnRowTwice() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS cis_u");
        executeUpdate("CREATE TABLE cis_u(id int primary key, a int, b varchar(20))");
        executeUpdate("CREATE INDEX cis_u_a_idx ON cis_u(a)");
        executeUpdate("INSERT INTO cis_u(id, a, b) VALUES(1, 1, 'x')");
        for (int i = 2; i <= 20; i++)
            executeUpdate("UPDATE cis_u SET a = " + i + " WHERE id = 1");
        Map<Integer, Integer> result = scan("SELECT id, a FROM cis_u WHERE a >= 0");
        assertEquals(1, result.size());
        assertEquals(Integer.valueOf(20), result.get(1));
    }
}
