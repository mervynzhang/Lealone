/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 目标列表里的setof函数(PostgreSQL的ProjectSet语义):
 * 单个SRF按自己的行数展开，多个SRF按最长的那个对齐补NULL，非SRF列每一行都复制。
 */
public class ProjectSetTest extends SqlTestBase {

    public ProjectSetTest() {
        setEmbedded(true);
    }

    // 单个SRF: 行数等于函数返回的行数
    @Test
    public void testSingleSrf() throws Exception {
        assertEquals("[1, 2, 3]", queryStringList("SELECT unnest(array[1, 2, 3]) AS x").toString());
        assertEquals("[1, 2, 3, 4]",
                queryStringList("SELECT generate_series(1, 4) AS x").toString());
        assertEquals("[1, 4, 7, 10]",
                queryStringList("SELECT generate_series(1, 10, 3) AS x").toString());
        // 展开后的列类型是元素类型，不是RESULT_SET
        executeQuery("SELECT unnest(array[1, 2, 3]) AS x");
        assertEquals(java.sql.Types.INTEGER, rs.getMetaData().getColumnType(1));
        // 空数组没有行
        assertEquals("[]", queryStringList("SELECT unnest(array[])").toString());
    }

    // 非SRF列每一行都复制一份，常量和普通列都一样
    @Test
    public void testWithOtherColumns() throws Exception {
        assertEquals("[1:v, 2:v, 3:v]",
                queryStringList2("SELECT generate_series(1, 3), 'v'").toString());
        assertEquals("[1:9]", queryStringList2("SELECT 1, unnest(array[9])").toString());
        createTable();
        // 每条记录各自展开，非SRF列的值保持不变
        assertEquals("[1:1, 1:2, 2:1, 2:2]",
                queryStringList2("SELECT id, unnest(array[1, 2]) AS v FROM ps_t ORDER BY id, v")
                        .toString());
        // 和聚合共存时: SRF在FROM里，目标列表用聚合结果(等价写法)
        assertEquals("[3]", queryIntList("SELECT count(*) FROM unnest(array[1, 2, 3])").toString());
    }

    // 多个SRF: 按最长的对齐，短的补NULL
    @Test
    public void testMultipleSrfs() throws Exception {
        // executeQuery内部已经next过一次
        executeQuery("SELECT unnest(array[1, 2]), unnest(array['a', 'b', 'c']) AS b");
        String[][] expected = { { "1", "a" }, { "2", "b" }, { null, "c" } };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], rs.getString(1));
            assertEquals(expected[i][1], rs.getString(2));
            if (i + 1 < expected.length) {
                assertTrue(rs.next());
            }
        }
        assertFalse(rs.next());
        assertEquals("[3]",
                queryIntList("SELECT count(*) FROM (SELECT unnest(array[1, 2]) AS x, "
                        + "unnest(array['a', 'b', 'c']) AS y) z").toString());
        // 两个SRF长度相同时没有NULL
        assertEquals("[1:a, 2:b]",
                queryStringList2("SELECT unnest(array[1, 2]), unnest(array['a', 'b'])").toString());
    }

    // SRF展开在ORDER BY之前、LIMIT之前计算(和PostgreSQL一致)
    @Test
    public void testOrderByAndLimit() throws Exception {
        assertEquals("[1, 2, 3]",
                queryStringList("SELECT unnest(array[3, 1, 2]) AS x ORDER BY x").toString());
        assertEquals("[3, 2, 1]",
                queryStringList("SELECT unnest(array[3, 1, 2]) AS x ORDER BY x DESC").toString());
        assertEquals("[1, 2]",
                queryStringList("SELECT unnest(array[3, 1, 2]) AS x ORDER BY x LIMIT 2").toString());
        // DISTINCT在展开之后去重
        assertEquals("[1, 2]",
                queryStringList("SELECT DISTINCT unnest(array[1, 1, 2]) AS x ORDER BY x").toString());
    }

    // 用户定义的SETOF函数也能在目标列表里展开
    @Test
    public void testUserSetofFunction() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION ps_setof(a INT) RETURNS SETOF INT LANGUAGE SQL "
                + "AS $$ SELECT x FROM system_range(1, a) $$");
        assertEquals("[1, 2, 3]", queryIntList("SELECT ps_setof(3)").toString());
        createTable();
        assertEquals("[1:1, 1:2, 2:1, 2:2]",
                queryStringList2("SELECT id, ps_setof(2) AS v FROM ps_t ORDER BY id, v")
                        .toString());
    }

    // 多列SRF和GROUP BY里的SRF暂时不支持，要明确报错
    @Test
    public void testUnsupportedCombinations() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION ps_multi(a INT) RETURNS TABLE(id INT, name VARCHAR(10)) "
                + "LANGUAGE SQL AS $$ SELECT x, 'v' FROM system_range(1, a) $$");
        executeQueryThanAssertErrorCode("SELECT ps_multi(2)", ErrorCode.FEATURE_NOT_SUPPORTED_1);
        executeQueryThanAssertErrorCode(
                "SELECT unnest(array[1, 2]) AS x GROUP BY x", ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    private void createTable() {
        executeUpdate("DROP TABLE IF EXISTS ps_t");
        executeUpdate("CREATE TABLE ps_t(id INT PRIMARY KEY)");
        executeUpdate("INSERT INTO ps_t VALUES(1), (2)");
    }

    private ArrayList<Integer> queryIntList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<Integer> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getInt(1));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryStringList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getString(1));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryStringList2(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getString(1) + ":" + result.getString(2));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }
}
