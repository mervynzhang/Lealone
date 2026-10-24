/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 执行计划断言：索引命中（等值/IN/范围/BETWEEN）、ORDER BY 走索引、无索引时 tableScan、OR 退化为 tableScan。
 * <p>
 * EXPLAIN 的输出里，索引名出现在形如 PUBLIC.INDEX_NAME 的注释中，
 * 全表扫描表现为 PUBLIC.TABLE_NAME.tableScan，索引有序输出带 "index sorted" 标记。
 */
public class ExplainPlanTest extends SqlAssertTestBase {

    private void init() {
        executeUpdate("DROP TABLE IF EXISTS ep_t2");
        executeUpdate("DROP TABLE IF EXISTS ep_t");
        executeUpdate("CREATE TABLE ep_t(id int primary key, name varchar(100), age int, unindexed int)");
        executeUpdate("CREATE INDEX ep_t_name_idx ON ep_t(name)");
        executeUpdate("CREATE INDEX ep_t_age_idx ON ep_t(age)");
        executeUpdate("INSERT INTO ep_t VALUES(1, 'a', 10, 1)");
        executeUpdate("INSERT INTO ep_t VALUES(2, 'b', 20, 2)");
        executeUpdate("INSERT INTO ep_t VALUES(3, 'c', 30, 3)");

        executeUpdate("CREATE TABLE ep_t2(id int primary key, a int, b int)");
        executeUpdate("CREATE INDEX ep_t2_ab_idx ON ep_t2(a, b)");
        executeUpdate("INSERT INTO ep_t2 VALUES(1, 1, 1)");
        executeUpdate("INSERT INTO ep_t2 VALUES(2, 2, 2)");

        // 只有主键索引的表：非主键列上的条件只能走全表扫描
        executeUpdate("DROP TABLE IF EXISTS ep_t3");
        executeUpdate("CREATE TABLE ep_t3(id int primary key, x int)");
        executeUpdate("INSERT INTO ep_t3 VALUES(1, 1)");
        executeUpdate("INSERT INTO ep_t3 VALUES(2, 2)");
    }

    @Test
    public void equalityUsesIndex() {
        init();
        String sql = "SELECT * FROM ep_t WHERE name = 'b'";
        assertPlanContains(sql, "EP_T_NAME_IDX");
        assertPlanNotContains(sql, "tableScan");
    }

    @Test
    public void inListUsesIndex() {
        init();
        String sql = "SELECT * FROM ep_t WHERE name IN ('a', 'c')";
        assertPlanContains(sql, "EP_T_NAME_IDX");
        assertPlanNotContains(sql, "tableScan");
        // IN 列表在主键上同样走主键索引
        String pkSql = "SELECT * FROM ep_t WHERE id IN (1, 2)";
        assertPlanContains(pkSql, "PRIMARY_KEY");
        assertPlanNotContains(pkSql, "tableScan");
    }

    @Test
    public void rangeUsesIndex() {
        init();
        String sql = "SELECT * FROM ep_t WHERE age > 15";
        assertPlanContains(sql, "EP_T_AGE_IDX");
        assertPlanNotContains(sql, "tableScan");

        String betweenSql = "SELECT * FROM ep_t WHERE age BETWEEN 10 AND 20";
        assertPlanContains(betweenSql, "EP_T_AGE_IDX");
        assertPlanNotContains(betweenSql, "tableScan");

        String countSql = "SELECT COUNT(*) FROM ep_t WHERE age > 0";
        assertPlanContains(countSql, "EP_T_AGE_IDX");
        assertPlanNotContains(countSql, "tableScan");
    }

    @Test
    public void orderByUsesIndex() {
        init();
        String sql = "SELECT * FROM ep_t ORDER BY name";
        assertPlanContains(sql, "EP_T_NAME_IDX");
        assertPlanContains(sql, "index sorted");
        assertPlanNotContains(sql, "tableScan");

        // 复合索引可以同时满足 ORDER BY a, b
        String composite = "SELECT * FROM ep_t2 ORDER BY a, b";
        assertPlanContains(composite, "EP_T2_AB_IDX");
        assertPlanContains(composite, "index sorted");
        assertPlanNotContains(composite, "tableScan");
    }

    @Test
    public void groupByUsesIndex() {
        init();
        String sql = "SELECT name, COUNT(*) FROM ep_t GROUP BY name";
        assertPlanContains(sql, "EP_T_NAME_IDX");
        assertPlanContains(sql, "group sorted");
        assertPlanNotContains(sql, "tableScan");
    }

    @Test
    public void noIndexMeansTableScan() {
        init();
        String sql = "SELECT * FROM ep_t3 WHERE x = 1";
        assertPlanContains(sql, "tableScan");

        // OR 的两边各有一个索引时，目前会退化成全表扫描
        String orSql = "SELECT * FROM ep_t WHERE name = 'b' OR age = 10";
        assertPlanContains(orSql, "tableScan");
        assertPlanNotContains(orSql, "EP_T_NAME_IDX");
        assertPlanNotContains(orSql, "EP_T_AGE_IDX");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 H2 实测：ORDER BY ... DESC 走反向索引扫描）
    // ------------------------------------------------------------------

    // ORDER BY ... DESC 走不了索引，退化成全表扫描+排序，结果正确只是慢。
    // 根因: getSortIndex要求索引列的sortType与ORDER BY方向完全一致，而游标没有反向扫描能力。
    @Ignore("PERF: ORDER BY name DESC needs a reverse cursor; sort order result is still correct")
    @Test
    public void orderByDescUsesIndex() {
        init();
        String sql = "SELECT * FROM ep_t ORDER BY name DESC";
        assertPlanContains(sql, "EP_T_NAME_IDX");
        assertPlanNotContains(sql, "tableScan");
    }
}
