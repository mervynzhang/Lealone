/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

public class CteTest extends SqlTestBase {

    public CteTest() {
        setEmbedded(true);
    }

    // CTE里引用的表
    private void createCteTable() {
        executeUpdate("DROP TABLE IF EXISTS CteTest");
        executeUpdate("CREATE TABLE CteTest(pk int NOT NULL PRIMARY KEY, name varchar(10), v int)");
        executeUpdate("INSERT INTO CteTest(pk, name, v) VALUES(1, 'a', 10)");
        executeUpdate("INSERT INTO CteTest(pk, name, v) VALUES(2, 'a', 20)");
        executeUpdate("INSERT INTO CteTest(pk, name, v) VALUES(3, 'b', 30)");
    }

    @Test
    public void run() throws Exception {
        createCteTable();
        testSimpleCte();
        testMultipleCte();
        testCteWithColumnNames();
        testCteOnTable();
    }

    @Test
    public void recursiveCte() throws Exception {
        createCteTable();
        testRecursiveCte();
    }

    @Test
    public void recursiveCteOnTable() throws Exception {
        createCteTable();
        sql = "WITH RECURSIVE r(n) AS (SELECT pk FROM CteTest UNION ALL"
                + " SELECT n + 10 FROM r WHERE n < 3) SELECT n FROM r";
        executeQuery();
        int count = 1; // executeQuery已经定位到第一行
        while (next())
            count++;
        closeResultSet();
        assertEquals(5, count);
    }

    // 非递归CTE可以省略列名列表
    private void testSimpleCte() throws Exception {
        sql = "WITH x AS (SELECT 1) SELECT * FROM x";
        assertEquals(1, getIntValue(1, true));

        sql = "WITH x AS (SELECT 1 AS a) SELECT a FROM x";
        assertEquals(1, getIntValue(1, true));

        sql = "WITH x AS (SELECT 1 AS a) SELECT x.a FROM x";
        assertEquals(1, getIntValue(1, true));

        sql = "WITH x AS (SELECT 2 AS a) SELECT a FROM x";
        assertEquals(2, getIntValue(1, true));

        // EXPLAIN语句走的也是parseWith
        sql = "EXPLAIN WITH x AS (SELECT 1 AS a) SELECT a FROM x";
        executeQuery();
        closeResultSet();
    }

    // 多个CTE用逗号分隔，后面的CTE可以引用前面的CTE
    private void testMultipleCte() throws Exception {
        sql = "WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a, b FROM x, y";
        executeQuery();
        assertEquals(1, getIntValue(1, false));
        assertEquals(2, getIntValue(2, true));

        sql = "WITH x AS (SELECT 1 AS a), y AS (SELECT a + 1 AS b FROM x) SELECT b FROM y";
        assertEquals(2, getIntValue(1, true));

        // 同一个CTE在主查询里使用多次
        sql = "WITH x AS (SELECT 1 AS a) SELECT x1.a, x2.a FROM x x1, x x2";
        executeQuery();
        assertEquals(1, getIntValue(1, false));
        assertEquals(1, getIntValue(2, true));

        sql = "WITH x AS (SELECT 3 AS a), y AS (SELECT a * 2 AS b FROM x), z AS (SELECT b + 1 AS c FROM y)"
                + " SELECT c FROM z";
        assertEquals(7, getIntValue(1, true));
    }

    // CTE也可以显式指定列名列表
    private void testCteWithColumnNames() throws Exception {
        sql = "WITH x(a, b) AS (SELECT 1, 2) SELECT a, b FROM x";
        executeQuery();
        assertEquals(1, getIntValue(1, false));
        assertEquals(2, getIntValue(2, true));

        sql = "WITH x(a, b) AS (SELECT 1, 2) SELECT b FROM x WHERE a = 1";
        assertEquals(2, getIntValue(1, true));
    }

    // CTE查询真实的表
    private void testCteOnTable() throws Exception {
        sql = "WITH x AS (SELECT name, sum(v) s FROM CteTest GROUP BY name) SELECT s FROM x WHERE name = 'a'";
        assertEquals(30, getIntValue(1, true));

        sql = "WITH x AS (SELECT pk, name FROM CteTest WHERE v > 10) SELECT count(*) FROM x";
        assertEquals(2, getIntValue(1, true));

        // CTE和真实的表做JOIN
        sql = "WITH x AS (SELECT name, sum(v) s FROM CteTest GROUP BY name)"
                + " SELECT count(*) FROM x JOIN CteTest c ON x.name = c.name";
        assertEquals(3, getIntValue(1, true));
    }

    // 递归CTE的行为保持不变
    private void testRecursiveCte() throws Exception {
        sql = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 5)"
                + " SELECT n FROM r";
        executeQuery();
        StringBuilder buff = new StringBuilder();
        do {
            if (buff.length() > 0)
                buff.append(',');
            buff.append(getStringValue(1, false));
        } while (next());
        closeResultSet();
        assertEquals("1,2,3,4,5", buff.toString());
    }
}
