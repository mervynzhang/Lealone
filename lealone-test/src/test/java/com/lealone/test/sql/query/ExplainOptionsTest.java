/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * EXPLAIN的括号选项: COSTS/VERBOSE/ANALYZE/FORMAT。
 * COSTS OFF时不输出代价和统计信息，VERBOSE时追加每张表的扫描细节。
 */
public class ExplainOptionsTest extends SqlTestBase {

    public ExplainOptionsTest() {
        setEmbedded(true);
    }

    // 裸EXPLAIN(和H2/PostgreSQL一样)输出执行计划
    @Test
    public void testExplain() throws Exception {
        executeQuery("EXPLAIN SELECT 1");
        assertTrue(getStringValue(1).contains("SELECT"));
        // EXPLAIN PLAN FOR是Oracle兼容写法
        executeQuery("EXPLAIN PLAN FOR SELECT 1");
        assertTrue(getStringValue(1).contains("SELECT"));
    }

    // COSTS OFF: 不输出scanCount/统计信息
    @Test
    public void testExplainCosts() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS eo_t");
        executeUpdate("CREATE TABLE eo_t(id INT PRIMARY KEY, name VARCHAR(10))");
        executeUpdate("INSERT INTO eo_t VALUES(1, 'a'), (2, 'b')");

        // 带统计信息的EXPLAIN ANALYZE里有scanCount
        executeQuery("EXPLAIN ANALYZE SELECT * FROM eo_t");
        assertTrue(getStringValue(1).contains("scanCount"));

        executeQuery("EXPLAIN ANALYZE SELECT * FROM eo_t");
        String costsOff = executeExplain("EXPLAIN (ANALYZE, COSTS OFF) SELECT * FROM eo_t");
        assertFalse(costsOff, costsOff.contains("scanCount"));
        assertFalse(costsOff, costsOff.contains("cost="));

        // COSTS OFF时执行计划本身还在
        String plan = executeExplain("EXPLAIN (COSTS OFF) SELECT * FROM eo_t");
        assertTrue(plan, plan.contains("EO_T"));
        // COSTS ON和默认一样
        String costsOn = executeExplain("EXPLAIN (COSTS ON) SELECT * FROM eo_t");
        assertEquals(executeExplain("EXPLAIN SELECT * FROM eo_t"), costsOn);
    }

    // VERBOSE: 追加每张表/索引/行数的细节
    @Test
    public void testExplainVerbose() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS eo_t2");
        executeUpdate("CREATE TABLE eo_t2(id INT PRIMARY KEY)");
        String plan = executeExplain("EXPLAIN (VERBOSE) SELECT * FROM eo_t2");
        assertTrue(plan, plan.contains("VERBOSE"));
        assertTrue(plan, plan.contains("table: EO_T2"));
        // 括号里的多个选项可以任意顺序组合
        String plan2 = executeExplain("EXPLAIN (COSTS OFF, VERBOSE) SELECT * FROM eo_t2");
        assertTrue(plan2, plan2.contains("VERBOSE"));
        assertFalse(plan2, plan2.contains("scanCount"));
        // 不带括号也能写VERBOSE
        assertTrue(executeExplain("EXPLAIN VERBOSE SELECT * FROM eo_t2").contains("VERBOSE"));
    }

    // FORMAT只支持TEXT，其它选项报语法错
    @Test
    public void testExplainOptionsSyntax() throws Exception {
        executeQuery("EXPLAIN (FORMAT TEXT) SELECT 1");
        executeQueryThanAssertErrorCode("EXPLAIN (FORMAT JSON) SELECT 1",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        executeQueryThanAssertErrorCode("EXPLAIN (NO_SUCH_OPTION) SELECT 1",
                ErrorCode.SYNTAX_ERROR_2);
    }

    private String executeExplain(String sql) throws Exception {
        executeQuery(sql);
        return getStringValue(1);
    }
}
