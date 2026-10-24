/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 关联子查询的判定：只有真正引用外层查询列的查询才不能缓存结果。
 * <p>
 * 这里锁定一个曾经返回错数据的缺陷：当"与外层的关联只发生在最内层子查询"时，
 * 中间那层子查询被判成非关联，结果被跨外层行复用，于是第二行之后开始返回上一行的值。
 * 根因是 CorrelatedVisitor 把嵌套子查询整段跳过(直接返回true)，见 CorrelatedVisitor.visitNestedQuery。
 */
public class CorrelatedSubqueryTest extends SqlAssertTestBase {

    private void initTables() {
        executeUpdate("DROP TABLE IF EXISTS cs_o");
        executeUpdate("DROP TABLE IF EXISTS cs_m");
        executeUpdate("DROP TABLE IF EXISTS cs_n");
        executeUpdate("CREATE TABLE cs_o(a int primary key)");
        executeUpdate("CREATE TABLE cs_m(k int)");
        executeUpdate("CREATE TABLE cs_n(d int, e int)");
        executeUpdate("INSERT INTO cs_o VALUES(1), (2), (3)");
        executeUpdate("INSERT INTO cs_m VALUES(1), (2), (2), (3)");
        executeUpdate("INSERT INTO cs_n VALUES(1,1), (2,2), (3,3)");
    }

    // 依次校验 (a, 子查询结果) 序列
    private void assertPairs(String sql, int[][] expected) throws Exception {
        // 注意: SqlTestBase.executeQuery 内部已经调用过一次 rs.next()，这里不要再推进
        executeQuery(sql);
        try {
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i][0], rs.getInt(1));
                assertEquals(expected[i][1], rs.getInt(2));
                boolean hasMore = rs.next();
                if (i + 1 < expected.length)
                    assertTrue(hasMore);
                else
                    assertFalse(hasMore);
            }
        } finally {
            closeResultSet();
        }
    }

    // 关联只在最内层子查询里出现，中间层必须跟着外层行重新求值
    @Test
    public void testCorrelationOnlyInInnermostQuery() throws Exception {
        initTables();
        assertPairs("SELECT a, (SELECT COUNT(*) FROM cs_m WHERE cs_m.k IN "
                + "(SELECT d FROM cs_n WHERE cs_n.e = cs_o.a)) FROM cs_o ORDER BY a",
                new int[][] { { 1, 1 }, { 2, 2 }, { 3, 1 } });
    }

    // EXISTS 形式的嵌套关联同样不能被复用
    @Test
    public void testCorrelationInNestedExists() throws Exception {
        initTables();
        assertPairs("SELECT a, (SELECT COUNT(*) FROM cs_m WHERE EXISTS "
                + "(SELECT 1 FROM cs_n WHERE cs_n.d = cs_m.k AND cs_n.e = cs_o.a)) "
                + "FROM cs_o ORDER BY a", new int[][] { { 1, 1 }, { 2, 2 }, { 3, 1 } });
    }

    // 对照组: 单层关联本来就应该对，改判定时不能把它弄坏
    @Test
    public void testSingleLevelCorrelationStillWorks() throws Exception {
        initTables();
        assertPairs("SELECT a, (SELECT COUNT(*) FROM cs_m WHERE cs_m.k = cs_o.a) "
                + "FROM cs_o ORDER BY a", new int[][] { { 1, 1 }, { 2, 2 }, { 3, 1 } });
    }

    // 对照组: 真正不关联的子查询每一行都该是同一个值
    @Test
    public void testUncorrelatedSubqueryStillWorks() throws Exception {
        initTables();
        assertPairs("SELECT a, (SELECT COUNT(*) FROM cs_m) FROM cs_o ORDER BY a",
                new int[][] { { 1, 4 }, { 2, 4 }, { 3, 4 } });
    }
}
