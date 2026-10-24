/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 聚合边界：空表聚合、全 NULL 列、GROUP BY 含 NULL 键、HAVING、ORDER BY 聚合、
 * COUNT(DISTINCT)、GROUP_CONCAT 的 NULL 与分隔符。
 * <p>
 * 本类目前全部为“应当通过”的断言：这些边界场景 Lealone 的行为与 H2 一致。
 * AVG 的整数截断缺陷（属于值语义）在 ValueSemanticsTest 里用 @Ignore 固定。
 */
public class AggregateEdgeTest extends SqlAssertTestBase {

    private void initEmptyTable() {
        executeUpdate("DROP TABLE IF EXISTS ae_t");
        executeUpdate("CREATE TABLE ae_t(id int primary key, g int, x int, s varchar(10), b bigint)");
    }

    private void initRows() {
        initEmptyTable();
        executeUpdate("INSERT INTO ae_t VALUES(1, 1, NULL, 'a', 1)");
        executeUpdate("INSERT INTO ae_t VALUES(2, 1, NULL, NULL, 2)");
        executeUpdate("INSERT INTO ae_t VALUES(3, NULL, 5, 'c', 3)");
        executeUpdate("INSERT INTO ae_t VALUES(4, NULL, 7, 'd', 4)");
    }

    @Test
    public void emptyTableAggregates() {
        initEmptyTable();
        assertQueryEquals(0L, "SELECT COUNT(*) FROM ae_t");
        assertQueryEquals(0L, "SELECT COUNT(x) FROM ae_t");
        assertQueryEquals(0L, "SELECT COUNT(DISTINCT x) FROM ae_t");
        // COUNT 之外，空表的聚合结果都是 NULL
        assertQueryNull("SELECT SUM(x) FROM ae_t");
        assertQueryNull("SELECT AVG(x) FROM ae_t");
        assertQueryNull("SELECT MIN(x) FROM ae_t");
        assertQueryNull("SELECT MAX(x) FROM ae_t");
        assertQueryNull("SELECT MIN(s) FROM ae_t");
        assertQueryNull("SELECT GROUP_CONCAT(s) FROM ae_t");
    }

    @Test
    public void allNullColumnAggregates() {
        initEmptyTable();
        executeUpdate("INSERT INTO ae_t VALUES(1, 1, NULL, 'a', 1)");
        executeUpdate("INSERT INTO ae_t VALUES(2, 1, NULL, 'b', 2)");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM ae_t");
        // COUNT(col) 不统计 NULL
        assertQueryEquals(0L, "SELECT COUNT(x) FROM ae_t");
        assertQueryEquals(0L, "SELECT COUNT(DISTINCT x) FROM ae_t");
        assertQueryNull("SELECT SUM(x) FROM ae_t");
        assertQueryNull("SELECT AVG(x) FROM ae_t");
        assertQueryNull("SELECT MIN(x) FROM ae_t");
        assertQueryNull("SELECT MAX(x) FROM ae_t");
    }

    @Test
    public void aggregatesSkipNulls() {
        initRows();
        assertQueryEquals(4L, "SELECT COUNT(*) FROM ae_t");
        assertQueryEquals(2L, "SELECT COUNT(x) FROM ae_t");
        assertQueryEquals(2L, "SELECT COUNT(g) FROM ae_t");
        assertQueryEquals(12L, "SELECT SUM(x) FROM ae_t");
        assertQueryEquals(5, "SELECT MIN(x) FROM ae_t");
        assertQueryEquals(7, "SELECT MAX(x) FROM ae_t");
        // 5 和 7 的平均值正好是整数，不受 AVG 截断缺陷影响
        assertQueryEquals(6, "SELECT AVG(x) FROM ae_t");
        assertQueryEquals(4L, "SELECT SUM(1) FROM ae_t");
    }

    @Test
    public void groupByWithNullKey() {
        initRows();
        // NULL 键自成一组，而且只能有一组
        assertQueryEquals(2L, "SELECT COUNT(*) FROM (SELECT g FROM ae_t GROUP BY g) t");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM ae_t GROUP BY g HAVING g IS NULL");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM ae_t GROUP BY g HAVING g = 1");
        assertQueryEquals(2L,
                "SELECT COUNT(*) FROM (SELECT g, COUNT(*) c FROM ae_t GROUP BY g HAVING c = 2) t");
    }

    @Test
    public void havingClause() {
        initRows();
        assertQueryEquals(2L,
                "SELECT COUNT(*) FROM (SELECT g FROM ae_t GROUP BY g HAVING COUNT(*) > 1) t");
        assertQueryEquals(0L,
                "SELECT COUNT(*) FROM (SELECT g FROM ae_t GROUP BY g HAVING COUNT(*) > 2) t");
        // HAVING 里可以用没有出现在 select 列表里的聚合
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM (SELECT g FROM ae_t GROUP BY g HAVING SUM(x) > 5) t");
    }

    @Test
    public void orderByAggregate() {
        initRows();
        // 按聚合结果排序：两组的 count 都是 2
        assertQueryEquals(2L,
                "SELECT COUNT(*) FROM (SELECT g, COUNT(*) c FROM ae_t GROUP BY g ORDER BY c DESC) t");
        // 按聚合表达式排序（不是别名）
        assertQueryEquals(2L,
                "SELECT COUNT(*) FROM (SELECT g FROM ae_t GROUP BY g ORDER BY COUNT(*) DESC) t");
        assertQueryEquals(7,
                "SELECT MAX(x) FROM (SELECT g, MAX(x) x FROM ae_t GROUP BY g ORDER BY MAX(x)) t");
    }

    @Test
    public void countAndSumDistinct() {
        initRows();
        // COUNT(DISTINCT) 不统计 NULL
        assertQueryEquals(1L, "SELECT COUNT(DISTINCT g) FROM ae_t");
        assertQueryEquals(2L, "SELECT COUNT(DISTINCT x) FROM ae_t");
        assertQueryEquals(12L, "SELECT SUM(DISTINCT x) FROM ae_t");
        assertQueryEquals(3L, "SELECT COUNT(DISTINCT s) FROM ae_t");
    }

    @Test
    public void groupConcat() {
        initRows();
        // 默认分隔符是逗号，NULL 被跳过
        assertQueryEquals("a,c,d", "SELECT GROUP_CONCAT(s) FROM ae_t");
        assertQueryEquals("a-c-d", "SELECT GROUP_CONCAT(s SEPARATOR '-') FROM ae_t");
        // ORDER BY 决定拼接顺序
        assertQueryEquals("d,c,a", "SELECT GROUP_CONCAT(s ORDER BY id DESC SEPARATOR ',') FROM ae_t");
        assertQueryEquals("a,c,d", "SELECT GROUP_CONCAT(DISTINCT s) FROM ae_t");
        // 按组拼接：NULL 组是 c,d
        assertQueryEquals("c,d",
                "SELECT GROUP_CONCAT(s) FROM ae_t GROUP BY g HAVING g IS NULL");
        assertQueryNull("SELECT GROUP_CONCAT(s) FROM ae_t WHERE 1=0");
    }

    @Test
    public void minMaxOnString() {
        initRows();
        assertQueryEquals("a", "SELECT MIN(s) FROM ae_t");
        assertQueryEquals("d", "SELECT MAX(s) FROM ae_t");
    }

    @Test
    public void aggregateOnWrongTypeFails() {
        initRows();
        // SUM/AVG 只能作用于数值类型
        assertSqlFails("SELECT SUM(s) FROM ae_t", ErrorCode.SUM_OR_AVG_ON_WRONG_DATATYPE_1);
        assertSqlFails("SELECT AVG(s) FROM ae_t", ErrorCode.SUM_OR_AVG_ON_WRONG_DATATYPE_1);
    }

    @Test
    public void aggregateWithFalsePredicate() {
        initRows();
        assertQueryEquals(0L, "SELECT COUNT(*) FROM ae_t WHERE 1=0");
        assertQueryNull("SELECT SUM(x) FROM ae_t WHERE 1=0");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM ae_t WHERE x IS NULL");
    }
}
