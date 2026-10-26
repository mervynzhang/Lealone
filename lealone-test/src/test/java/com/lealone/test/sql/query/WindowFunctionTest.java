/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 窗口函数最小子集的语义测试：{@code ROW_NUMBER()/RANK()/DENSE_RANK()} 和
 * {@code COUNT/SUM/MIN/MAX/AVG(...) OVER (...)}。
 * <p>
 * 支持 {@code OVER ()}、{@code PARTITION BY}、{@code ORDER BY}（含 ASC/DESC/NULLS FIRST|LAST），
 * 不支持窗口帧（ROWS/RANGE/GROUPS）、命名窗口、窗口函数与 GROUP BY/DISTINCT 混用，
 * 也不支持窗口函数出现在非顶层 select 列（WHERE/HAVING/ORDER BY/表达式内部）。
 */
public class WindowFunctionTest extends SqlAssertTestBase {

    private void initTable() {
        executeUpdate("DROP TABLE IF EXISTS window_t");
        executeUpdate("CREATE TABLE window_t(id int primary key, k int, v int)");
        executeUpdate("INSERT INTO window_t VALUES(1, 1, 10)");
        executeUpdate("INSERT INTO window_t VALUES(2, 1, 20)");
        executeUpdate("INSERT INTO window_t VALUES(3, 2, 10)");
        executeUpdate("INSERT INTO window_t VALUES(4, 2, 30)");
        executeUpdate("INSERT INTO window_t VALUES(5, 3, 40)");
    }

    /** 把结果集的某一列读成 List，按结果集顺序 */
    private List<Object> column(String querySql, int column) {
        return rows(querySql).stream().map(r -> r[column - 1]).collect(java.util.stream.Collectors
                .toList());
    }

    /** 把结果集读成 List<Object[]>，每行一个数组 */
    private List<Object[]> rows(String querySql) {
        List<Object[]> list = new ArrayList<>();
        try (ResultSet r = stmt.executeQuery(querySql)) {
            int count = r.getMetaData().getColumnCount();
            while (r.next()) {
                Object[] row = new Object[count];
                for (int i = 0; i < count; i++) {
                    row[i] = r.getObject(i + 1);
                    if (r.wasNull()) {
                        row[i] = null;
                    }
                }
                list.add(row);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return list;
    }

    private void assertColumnEquals(List<Object> expected, String querySql, int column) {
        List<Object> actual = column(querySql, column);
        assertEquals("column " + column + " of [" + querySql + "]", expected, actual);
    }

    @Test
    public void testRowNumberOrderBy() throws Exception {
        initTable();
        // a) 无分区，按 ORDER BY 升序编号，从 1 开始
        assertEquals(Arrays.asList(1, 2, 3, 4, 5),
                column("SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_t", 1));
        // DESC：结果没有外层 ORDER BY，行按扫描顺序输出，所以编号是倒着的
        assertEquals(Arrays.asList(5, 4, 3, 2, 1),
                column("SELECT ROW_NUMBER() OVER (ORDER BY id DESC) AS rn FROM window_t", 1));
        // 编号跟着 ORDER BY 走：id 最小的编号 1
        List<Object[]> list = rows(
                "SELECT id, ROW_NUMBER() OVER (ORDER BY id DESC) AS rn FROM window_t");
        assertEquals(5, list.size());
        for (Object[] row : list) {
            // id=5 编号1, id=1 编号5
            assertEquals(6 - (Integer) row[0], row[1]);
        }
        // ORDER BY 非唯一列时按扫描顺序决定相等的行(不校验相等行之间的先后)
        assertEquals(Arrays.asList(1, 2, 3, 4, 5),
                column("SELECT ROW_NUMBER() OVER (ORDER BY k) AS rn FROM window_t", 1));
    }

    @Test
    public void testRowNumberPartitionBy() throws Exception {
        initTable();
        // b) 分区内编号
        List<Object[]> list = rows("SELECT k, id, ROW_NUMBER() OVER (PARTITION BY k ORDER BY id DESC) AS rn "
                + "FROM window_t ORDER BY k, id");
        assertEquals(5, list.size());
        // k=1: (1)->2, (2)->1 ; k=2: (3)->2, (4)->1 ; k=3: (5)->1
        int[][] expected = { { 1, 1, 2 }, { 1, 2, 1 }, { 2, 3, 2 }, { 2, 4, 1 }, { 3, 5, 1 } };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], list.get(i)[0]);
            assertEquals(expected[i][1], list.get(i)[1]);
            assertEquals(expected[i][2], list.get(i)[2]);
        }
        // 多列分区键
        assertEquals(Arrays.asList(1, 1, 1, 1, 1), column(
                "SELECT ROW_NUMBER() OVER (PARTITION BY k, v ORDER BY id) AS rn FROM window_t", 1));
        // 分区里只有一行
        assertEquals(1, queryObject(
                "SELECT ROW_NUMBER() OVER (PARTITION BY k ORDER BY id) AS rn FROM window_t WHERE k = 3"));
        // 没写 ORDER BY：分区内仍然是 1..n，只是顺序不确定
        List<Object> rn = column(
                "SELECT ROW_NUMBER() OVER (PARTITION BY k) AS rn FROM window_t ORDER BY k, id", 1);
        assertEquals(Arrays.asList(1, 2, 1, 2, 1), rn);
    }

    @Test
    public void testEmptyWindow() throws Exception {
        initTable();
        // d) OVER ()：等价于整张表一个分区
        assertColumnEquals(Arrays.asList(1, 2, 3, 4, 5),
                "SELECT ROW_NUMBER() OVER () AS rn FROM window_t", 1);
        // 空窗口 + 聚合
        assertColumnEquals(Arrays.asList(5L, 5L, 5L, 5L, 5L),
                "SELECT COUNT(*) OVER () AS c FROM window_t", 1);
        assertColumnEquals(Arrays.asList(110L, 110L, 110L, 110L, 110L),
                "SELECT SUM(v) OVER () AS s FROM window_t", 1);
    }

    @Test
    public void testEmptyAndSingleRowTable() throws Exception {
        // c) 空表
        executeUpdate("DROP TABLE IF EXISTS window_empty_t");
        executeUpdate("CREATE TABLE window_empty_t(id int primary key, k int)");
        assertTrue(rows("SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_empty_t")
                .isEmpty());
        assertTrue(rows("SELECT id, ROW_NUMBER() OVER (PARTITION BY k ORDER BY id) AS rn "
                + "FROM window_empty_t").isEmpty());
        assertTrue(rows("SELECT id, COUNT(*) OVER () AS c FROM window_empty_t").isEmpty());
        // 单行
        executeUpdate("INSERT INTO window_empty_t VALUES(1, 7)");
        List<Object[]> list = rows(
                "SELECT id, ROW_NUMBER() OVER (PARTITION BY k ORDER BY id) AS rn, "
                        + "COUNT(*) OVER (PARTITION BY k) AS c FROM window_empty_t");
        assertEquals(1, list.size());
        assertEquals(1, list.get(0)[1]);
        assertEquals(1L, list.get(0)[2]);
        executeUpdate("DROP TABLE IF EXISTS window_empty_t");
    }

    @Test
    public void testRankAndDenseRank() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS window_rank_t");
        executeUpdate("CREATE TABLE window_rank_t(id int primary key, v int)");
        executeUpdate("INSERT INTO window_rank_t VALUES(1, 10)");
        executeUpdate("INSERT INTO window_rank_t VALUES(2, 10)");
        executeUpdate("INSERT INTO window_rank_t VALUES(3, 20)");
        executeUpdate("INSERT INTO window_rank_t VALUES(4, 20)");
        executeUpdate("INSERT INTO window_rank_t VALUES(5, 30)");
        try {
            assertColumnEquals(Arrays.asList(1, 1, 3, 3, 5),
                    "SELECT RANK() OVER (ORDER BY v) AS r FROM window_rank_t", 1);
            assertColumnEquals(Arrays.asList(1, 1, 2, 2, 3),
                    "SELECT DENSE_RANK() OVER (ORDER BY v) AS r FROM window_rank_t", 1);
            // ROW_NUMBER 在相等的行之间也各不相同
            assertColumnEquals(Arrays.asList(1, 2, 3, 4, 5),
                    "SELECT ROW_NUMBER() OVER (ORDER BY v) AS r FROM window_rank_t", 1);
            // 按 v 分区后每个分区内部全是 peer，rank 都是 1
            assertColumnEquals(Arrays.asList(1, 1, 1, 1, 1),
                    "SELECT RANK() OVER (PARTITION BY v ORDER BY v) AS r FROM window_rank_t", 1);
            // 没有 ORDER BY 时所有行互为 peer
            assertColumnEquals(Arrays.asList(1, 1, 1, 1, 1),
                    "SELECT RANK() OVER (PARTITION BY v) AS r FROM window_rank_t", 1);
            // NULL 在 ORDER BY 里的位置跟 PG 一致(ASC 默认 NULLS LAST)
            executeUpdate("INSERT INTO window_rank_t VALUES(6, NULL)");
            assertColumnEquals(Arrays.asList(1, 1, 3, 3, 5, 6),
                    "SELECT RANK() OVER (ORDER BY v) AS r FROM window_rank_t", 1);
            // NULLS FIRST：NULL 那一行 rank=1，其余整体后移
            assertColumnEquals(Arrays.asList(2, 2, 4, 4, 6, 1),
                    "SELECT RANK() OVER (ORDER BY v NULLS FIRST) AS r FROM window_rank_t", 1);
        } finally {
            executeUpdate("DROP TABLE IF EXISTS window_rank_t");
        }
    }

    @Test
    public void testAggregates() throws Exception {
        initTable();
        // COUNT(*) / COUNT(expr) / SUM / MIN / MAX / AVG
        assertColumnEquals(Arrays.asList(2L, 2L, 2L, 2L, 1L),
                "SELECT COUNT(*) OVER (PARTITION BY k) AS c FROM window_t ORDER BY id", 1);
        assertColumnEquals(Arrays.asList(30L, 30L, 40L, 40L, 40L),
                "SELECT SUM(v) OVER (PARTITION BY k) AS s FROM window_t ORDER BY id", 1);
        assertColumnEquals(Arrays.asList(10, 10, 10, 10, 40),
                "SELECT MIN(v) OVER (PARTITION BY k) AS m FROM window_t ORDER BY id", 1);
        assertColumnEquals(Arrays.asList(20, 20, 30, 30, 40),
                "SELECT MAX(v) OVER (PARTITION BY k) AS m FROM window_t ORDER BY id", 1);
        assertColumnEquals(Arrays.asList(15.0, 15.0, 20.0, 20.0, 40.0),
                "SELECT AVG(v) OVER (PARTITION BY k) AS a FROM window_t ORDER BY id", 1);
        // COUNT(expr) 跳过 NULL
        executeUpdate("DROP TABLE IF EXISTS window_null_t");
        executeUpdate("CREATE TABLE window_null_t(id int primary key, v int)");
        executeUpdate("INSERT INTO window_null_t VALUES(1, 1)");
        executeUpdate("INSERT INTO window_null_t VALUES(2, NULL)");
        executeUpdate("INSERT INTO window_null_t VALUES(3, 3)");
        try {
            assertColumnEquals(Arrays.asList(3L, 3L, 3L),
                    "SELECT COUNT(*) OVER () AS c FROM window_null_t", 1);
            assertColumnEquals(Arrays.asList(2L, 2L, 2L),
                    "SELECT COUNT(v) OVER () AS c FROM window_null_t", 1);
            assertColumnEquals(Arrays.asList(4L, 4L, 4L),
                    "SELECT SUM(v) OVER () AS s FROM window_null_t", 1);
        } finally {
            executeUpdate("DROP TABLE IF EXISTS window_null_t");
        }
        // 多个窗口函数共存(各自独立分区/排序)
        List<Object[]> list = rows("SELECT id, "
                + "ROW_NUMBER() OVER (ORDER BY id) AS rn, "
                + "COUNT(*) OVER (PARTITION BY k) AS c, "
                + "SUM(v) OVER () AS s FROM window_t ORDER BY id");
        assertEquals(5, list.size());
        assertEquals(2L, list.get(0)[2]); // k=1 有 2 行
        assertEquals(110L, list.get(0)[3]);
        assertEquals(1L, list.get(4)[2]); // k=3 只有 1 行
    }

    /**
     * e) 真实目标：pgjdbc {@code DatabaseMetaData.getColumns()} 里的窗口片段。
     * <p>
     * pgjdbc 的查询是 8 个 pg_catalog 表 join 后放到派生表里算
     * {@code row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum}，
     * 外层再引用这个 attnum 作为 ORDINAL_POSITION 并 ORDER BY relname, attnum。
     * 这里用普通表复刻同样的形状（join + 派生表 + 同名 attnum 列）。
     */
    @Test
    public void testPgJdbcGetColumnsShape() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS window_pg_class");
        executeUpdate("DROP TABLE IF EXISTS window_pg_attribute");
        executeUpdate("CREATE TABLE window_pg_class(oid int primary key, relname varchar(20), "
                + "relnamespace int default 2200, relkind varchar(2) default 'r')");
        executeUpdate("CREATE TABLE window_pg_attribute(attrelid int, attnum int, "
                + "attname varchar(20), attisdropped boolean)");
        executeUpdate("INSERT INTO window_pg_class(oid, relname) VALUES(1, 't1')");
        executeUpdate("INSERT INTO window_pg_class(oid, relname) VALUES(2, 't2')");
        executeUpdate("INSERT INTO window_pg_attribute VALUES(1, 1, 'a', false)");
        executeUpdate("INSERT INTO window_pg_attribute VALUES(1, 3, 'b', false)");
        executeUpdate("INSERT INTO window_pg_attribute VALUES(1, 5, 'c', true)");
        executeUpdate("INSERT INTO window_pg_attribute VALUES(2, 1, 'x', false)");
        executeUpdate("INSERT INTO window_pg_attribute VALUES(2, 2, 'y', false)");
        try {
            // 1) 窗口直接在 join 的 select 列里
            List<Object[]> list = rows("SELECT c.relname, a.attname, "
                    + "row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum "
                    + "FROM window_pg_class c JOIN window_pg_attribute a "
                    + "ON (a.attrelid = c.oid) WHERE a.attnum > 0 AND NOT a.attisdropped "
                    + "ORDER BY c.relname, attnum");
            assertEquals(4, list.size());
            String[] expected = { "t1/a/1", "t1/b/2", "t2/x/1", "t2/y/2" };
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i],
                        list.get(i)[0] + "/" + list.get(i)[1] + "/" + list.get(i)[2]);
            }

            // 2) pgjdbc 的真实形状：窗口在派生表里，外层 WHERE true ... ORDER BY
            String sql = "SELECT relname, attname, attnum FROM ("
                    + "  SELECT c.relname, a.attname,"
                    + "    row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum,"
                    + "    a.attnum AS raw_attnum"
                    + "  FROM window_pg_class c JOIN window_pg_attribute a ON (a.attrelid = c.oid)"
                    + "  WHERE a.attnum > 0 AND NOT a.attisdropped"
                    + ") c WHERE true AND attname LIKE ?"
                    + " ORDER BY relname, attnum";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "%");
                // 同一语句执行两次，走计划缓存/重编译路径
                for (int round = 0; round < 2; round++) {
                    try (ResultSet r = ps.executeQuery()) {
                        List<String> got = new ArrayList<>();
                        while (r.next()) {
                            got.add(r.getString(1) + "/" + r.getString(2) + "/" + r.getInt(3));
                        }
                        assertEquals(Arrays.asList(expected), got);
                    }
                }
            }
            // 3) 更接近 pgjdbc 原文：再加一张 LEFT JOIN 的 pg_attrdef，走 pg_catalog 模式名
            executeUpdate("CREATE SCHEMA IF NOT EXISTS WINDOW_PG_CATALOG");
            executeUpdate("DROP TABLE IF EXISTS WINDOW_PG_CATALOG.PG_NAMESPACE");
            executeUpdate("DROP TABLE IF EXISTS WINDOW_PG_CATALOG.PG_ATTRDEF");
            executeUpdate("CREATE TABLE WINDOW_PG_CATALOG.PG_NAMESPACE(OID INT PRIMARY KEY, "
                    + "NSPNAME VARCHAR(64))");
            executeUpdate("CREATE TABLE WINDOW_PG_CATALOG.PG_ATTRDEF(ADRELID INT, ADNUM INT, "
                    + "ADBIN VARCHAR(64))");
            executeUpdate("INSERT INTO WINDOW_PG_CATALOG.PG_NAMESPACE VALUES(2200, 'PUBLIC')");
            executeUpdate("INSERT INTO WINDOW_PG_CATALOG.PG_ATTRDEF VALUES(1, 1, 'nextval')");
            String pgSql = "SELECT nspname, relname, attname, attnum FROM ("
                    + " SELECT n.nspname, c.relname, a.attname,"
                    + "  row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum,"
                    + "  a.attnum AS raw_attnum, def.adbin AS adsrc"
                    + " FROM WINDOW_PG_CATALOG.PG_NAMESPACE n"
                    + " JOIN window_pg_class c ON (c.relnamespace = n.oid)"
                    + " JOIN window_pg_attribute a ON (a.attrelid = c.oid)"
                    + " LEFT JOIN WINDOW_PG_CATALOG.PG_ATTRDEF def"
                    + "  ON (a.attrelid = def.adrelid AND a.attnum = def.adnum)"
                    + " WHERE c.relkind IN ('r','p','v','f','m') AND a.attnum > 0"
                    + "  AND NOT a.attisdropped"
                    + ") c WHERE true AND attname LIKE ?"
                    + " ORDER BY nspname, relname, attnum";
            try (PreparedStatement ps = conn.prepareStatement(pgSql)) {
                ps.setString(1, "%");
                try (ResultSet r = ps.executeQuery()) {
                    List<String> got = new ArrayList<>();
                    while (r.next()) {
                        got.add(r.getString(2) + "/" + r.getString(3) + "/" + r.getInt(4));
                    }
                    assertEquals(Arrays.asList("t1/a/1", "t1/b/2", "t2/x/1", "t2/y/2"), got);
                }
            }
            executeUpdate("DROP TABLE IF EXISTS WINDOW_PG_CATALOG.PG_ATTRDEF");
            executeUpdate("DROP TABLE IF EXISTS WINDOW_PG_CATALOG.PG_NAMESPACE");
        } finally {
            executeUpdate("DROP TABLE IF EXISTS window_pg_attribute");
            executeUpdate("DROP TABLE IF EXISTS window_pg_class");
        }
    }

    @Test
    public void testPlanSQLAndRecompile() throws Exception {
        initTable();
        // getSQL() 要能打印 OVER (...), EXPLAIN 可见
        assertPlanContains("SELECT id, ROW_NUMBER() OVER (PARTITION BY k ORDER BY id) AS rn "
                + "FROM window_t", "OVER (PARTITION BY K ORDER BY ID)");
        // 同一个语句连续执行两次(计划缓存/重新编译)
        for (int i = 0; i < 2; i++) {
            assertEquals(Arrays.asList(1, 2, 3, 4, 5),
                    column("SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_t", 1));
        }
        // 参数
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_t WHERE id > ?")) {
            ps.setInt(1, 3);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
                assertFalse(r.next());
            }
            ps.setInt(1, 0);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
            }
        }
        // 外层 ORDER BY 与窗口 ORDER BY 可以同时使用
        List<Object[]> list = rows(
                "SELECT id, ROW_NUMBER() OVER (ORDER BY v) AS rn FROM window_t ORDER BY id DESC");
        assertEquals(5, list.size());
        assertEquals(5, list.get(0)[0]);
        // id=5 的 v=40 最大，按 v 升序编号是 5
        assertEquals(5, list.get(0)[1]);

        // LIMIT/OFFSET 作用在窗口计算之后
        list = rows("SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_t "
                + "ORDER BY rn LIMIT 2");
        assertEquals(2, list.size());
        assertEquals(1, list.get(0)[1]);
        assertEquals(2, list.get(1)[1]);
        list = rows("SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM window_t "
                + "ORDER BY rn LIMIT 2 OFFSET 2");
        assertEquals(2, list.size());
        assertEquals(3, list.get(0)[1]);
        assertEquals(4, list.get(1)[1]);
        // LIMIT 不能截断窗口计算(否则 COUNT(*) OVER () 会变成 2)
        list = rows("SELECT COUNT(*) OVER () AS c FROM window_t LIMIT 2");
        assertEquals(2, list.size());
        assertEquals(5L, list.get(0)[0]);
        assertEquals(5L, list.get(1)[0]);
    }

    @Test
    public void testUnsupportedShapes() throws Exception {
        initTable();
        // 窗口帧不支持
        assertTrue(assertSqlFails("SELECT SUM(v) OVER (PARTITION BY k ORDER BY id "
                + "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM window_t").getMessage()
                        .toUpperCase().contains("NOT SUPPORTED"));
        // 聚合窗口带 ORDER BY 是 running 语义，子集不实现，必须明确报错而不是静默给全分区结果
        assertTrue(assertSqlFails("SELECT SUM(v) OVER (ORDER BY id) FROM window_t").getMessage()
                .toUpperCase().contains("NOT SUPPORTED"));
        // 窗口函数不能出现在 WHERE / 表达式内部 / GROUP BY 查询里
        assertSqlFails("SELECT id FROM window_t WHERE ROW_NUMBER() OVER (ORDER BY id) = 1");
        assertSqlFails("SELECT ROW_NUMBER() OVER (ORDER BY id) + 1 FROM window_t");
        assertSqlFails("SELECT k, COUNT(*) FROM window_t GROUP BY k HAVING "
                + "ROW_NUMBER() OVER (ORDER BY k) = 1");
        // 不支持的窗口函数名
        assertSqlFails("SELECT LEAD(v) OVER (ORDER BY id) FROM window_t");
    }
}
