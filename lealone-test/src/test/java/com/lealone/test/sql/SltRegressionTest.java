/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql;

import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for the real (non dialect) defects found by the sqllogictest run.
 * <p>
 * Every method is one bug, with the minimal SQL that reproduced it in the suite.
 */
public class SltRegressionTest extends SqlTestBase {

    public SltRegressionTest() {
        setEmbedded(true);
    }

    @Before
    public void init() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS slt_t");
        executeUpdate("CREATE TABLE slt_t(a INTEGER)");
        executeUpdate("INSERT INTO slt_t VALUES(1),(1),(2),(3)");
    }

    // 搜索式CASE的第一个参数是null占位符，BuiltInFunction.getCost不能假设参数都非null
    @Test
    public void searchedCaseGetCost() throws Exception {
        sql = "SELECT (CASE WHEN COUNT(*)=1 THEN 1 END) NOT BETWEEN 0 AND 5";
        assertFalse(getBooleanValue(1, true));
    }

    // NULLIF(x, y)的结果类型和第一个参数一致，不能因为第二个参数是REAL就把整数除法变成实数除法
    @Test
    public void nullIfResultType() throws Exception {
        sql = "SELECT NULLIF(1, CAST(NULL AS REAL)) / 2";
        assertEquals(0, getIntValue(1, true));
        sql = "SELECT NULLIF(5, CAST(NULL AS REAL)) / 2";
        assertEquals(2, getIntValue(1, true));
    }

    // CASE/COALESCE里没有被选中的分支不能求值，聚合函数也不能因此被算两次
    @Test
    public void lazyCaseAndCoalesce() throws Exception {
        sql = "SELECT CASE WHEN 1=1 THEN 5 ELSE 1/0 END";
        assertEquals(5, getIntValue(1, true));
        sql = "SELECT COALESCE(5, 1/0)";
        assertEquals(5, getIntValue(1, true));
        sql = "SELECT SUM(CASE WHEN 1=1 THEN 5 ELSE 1/0 END) FROM slt_t";
        assertEquals(20, getIntValue(1, true));
        sql = "SELECT SUM(COALESCE(5, 1/0)) FROM slt_t";
        assertEquals(20, getIntValue(1, true));
    }

    // 带DISTINCT的聚合只统计一次
    @Test
    public void distinctAggregateInCase() throws Exception {
        sql = "SELECT CASE WHEN 1=1 THEN SUM(DISTINCT a) ELSE 0 END FROM slt_t";
        assertEquals(6, getIntValue(1, true));
        sql = "SELECT CASE WHEN COUNT(*)>0 THEN AVG(DISTINCT a) ELSE 0 END FROM slt_t";
        assertEquals(2, getIntValue(1, true));
        sql = "SELECT CASE WHEN 1=1 THEN COUNT(DISTINCT a) ELSE 0 END FROM slt_t";
        assertEquals(3, getIntValue(1, true));
    }
}
