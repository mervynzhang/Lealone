/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.expression;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * {@code expr = ANY(array)} / {@code expr <> ALL(array)} 以及其它比较运算符的 ANY/ALL 形式。
 * <p>
 * 右侧既可以是数组值（Lealone 用行值构造器 {@code (1, 2, 3)}、{@code ('a', 'b')} 表示数组），
 * 也可以是插件函数常返回的逗号分隔字符串，例如 PostgreSQL 的 current_schemas()。
 * NULL 按 SQL 三值逻辑处理。
 */
public class ConditionAnyTest extends SqlAssertTestBase {

    @Test
    public void anyWithArrayValue() {
        assertQueryTrue("SELECT 1 = ANY((1, 2, 3))");
        assertQueryFalse("SELECT 4 = ANY((1, 2, 3))");
        assertQueryTrue("SELECT 3 >= ANY((1, 2, 3))");
        assertQueryTrue("SELECT 1 < ANY((1, 2, 3))");
        assertQueryFalse("SELECT 0 > ANY((1, 2, 3))");

        // 空数组：ANY 恒为 FALSE
        assertQueryFalse("SELECT 1 = ANY(())");
        assertQueryFalse("SELECT NULL = ANY(())");

        // 行值(row value)数组
        assertQueryTrue("SELECT (1, 2) = ANY(((1, 2), (3, 4)))");
        assertQueryFalse("SELECT (1, 5) = ANY(((1, 2), (3, 4)))");
        assertQueryNull("SELECT (1, NULL) = ANY(((1, 2), (3, 4)))");
    }

    @Test
    public void allWithArrayValue() {
        assertQueryTrue("SELECT 2 <> ALL((1, 3, 4))");
        assertQueryFalse("SELECT 2 <> ALL((1, 2, 3))");
        assertQueryTrue("SELECT 2 > ALL((1, 0))");
        assertQueryFalse("SELECT 2 > ALL((1, 3))");
        assertQueryTrue("SELECT 1 <= ALL((1, 2, 3))");

        // 空数组：ALL 恒为 TRUE
        assertQueryTrue("SELECT 1 <> ALL(())");
        assertQueryTrue("SELECT NULL <> ALL(())");
    }

    @Test
    public void nullUsesThreeValuedLogic() {
        // 命中元素时即使还有 NULL 也是 TRUE
        assertQueryTrue("SELECT 1 = ANY((1, NULL))");
        // 没命中且存在 NULL 时是 UNKNOWN，不能静默当成 FALSE (FALSE OR UNKNOWN = UNKNOWN)
        assertQueryNull("SELECT 2 = ANY((1, NULL))");
        assertQueryNull("SELECT 2 = ANY((3, NULL))");
        assertQueryNull("SELECT NULL = ANY((1, 2))");
        // ALL 同理：只要没有确定为 FALSE，NULL 元素会让结果变成 UNKNOWN
        assertQueryNull("SELECT 2 <> ALL((1, NULL))");
        assertQueryNull("SELECT 2 > ALL((1, NULL))");
        assertQueryNull("SELECT NULL <> ALL((1, 2))");
        // 已经有元素决定了 FALSE，NULL 不再影响结果 (FALSE AND UNKNOWN = FALSE)
        assertQueryFalse("SELECT 2 <> ALL((1, 2, NULL))");
    }

    @Test
    public void notAnyBecomesAll() {
        assertQueryFalse("SELECT NOT (2 = ANY((1, 2)))");
        assertQueryTrue("SELECT NOT (2 = ANY((1, 3)))");
        assertQueryNull("SELECT NOT (2 = ANY((1, NULL)))");
        assertQueryFalse("SELECT NOT (2 <> ALL((1, 3)))");
        assertQueryTrue("SELECT NOT (2 <> ALL((1, 2)))");
    }

    @Test
    public void commaSeparatedStringRightSide() {
        // 插件把数组实现成逗号分隔字符串时也要能用
        assertQueryTrue("SELECT 1 = ANY('1,2,3')");
        assertQueryFalse("SELECT 4 = ANY('1,2,3')");
        assertQueryTrue("SELECT 'b' = ANY('a,b,c')");
        // 允许逗号后有空格
        assertQueryTrue("SELECT 'b' = ANY('a, b, c')");
        assertQueryFalse("SELECT 'd' = ANY('a, b, c')");
        assertQueryTrue("SELECT 2 <> ALL('1,3,4')");
        assertQueryFalse("SELECT 2 <> ALL('1,2,3')");
    }

    @Test
    public void anyAllWithTableColumnsAndParameters() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS condition_any_t");
        executeUpdate("CREATE TABLE condition_any_t(id int, name varchar(10), csv varchar(20))");
        executeUpdate("INSERT INTO condition_any_t VALUES(1, 'a', '1,3,5')");
        executeUpdate("INSERT INTO condition_any_t VALUES(2, 'b', '1,3,5')");
        executeUpdate("INSERT INTO condition_any_t VALUES(3, 'c', '1,3,5')");
        executeUpdate("INSERT INTO condition_any_t VALUES(4, 'd', '1,3,5')");
        executeUpdate("INSERT INTO condition_any_t VALUES(5, 'e', '1,3,5')");

        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((1, 3, 5))");
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY('1,3,5')");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM condition_any_t WHERE id <> ALL((1, 2, 3))");
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE name = ANY('a,c,e')");
        // 右侧是表列(逐行求值)，不是常量
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY(csv)");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM condition_any_t WHERE id <> ALL(csv)");
        // 含 NULL 且不命中：UNKNOWN 被 WHERE 过滤掉，不能算成命中
        assertQueryEquals(1L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((2, NULL))");
        assertQueryEquals(0L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((9, NULL))");
        // getSQL() 要能打印出 ANY(...)，供 EXPLAIN/重编译使用
        assertPlanContains("SELECT * FROM condition_any_t WHERE id = ANY((1, 2, 3))", "ANY(");

        // 逗号分隔字符串参数
        try (PreparedStatement ps = conn
                .prepareStatement("SELECT COUNT(*) FROM condition_any_t WHERE id = ANY(?)")) {
            ps.setString(1, "2,4");
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
            }
        }
        // 数组参数
        try (PreparedStatement ps = conn
                .prepareStatement("SELECT COUNT(*) FROM condition_any_t WHERE id = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("INTEGER", new Object[] { 1, 3, 5 }));
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(3, r.getInt(1));
            }
        }
    }

    /**
     * pgjdbc 在 getColumns() 等元数据查询里会发
     * {@code n.nspname = ANY(current_schemas(true))}。这里用 PG_CATALOG 表模拟，
     * current_schemas(true) 换成等价的、返回逗号分隔字符串的普通表达式
     * （真实函数由 PostgreSQL 插件实现，不属于核心库）。
     */
    @Test
    public void pgjdbcCurrentSchemasQuery() {
        executeUpdate("CREATE SCHEMA IF NOT EXISTS PG_CATALOG");
        executeUpdate("DROP TABLE IF EXISTS PG_CATALOG.PG_NAMESPACE");
        executeUpdate("DROP TABLE IF EXISTS PG_CATALOG.PG_TYPE");
        executeUpdate("CREATE TABLE PG_CATALOG.PG_NAMESPACE(OID INT PRIMARY KEY, NSPNAME VARCHAR(64))");
        executeUpdate("CREATE TABLE PG_CATALOG.PG_TYPE(OID INT PRIMARY KEY, TYPNAME VARCHAR(64), "
                + "TYPNAMESPACE INT)");
        executeUpdate("INSERT INTO PG_CATALOG.PG_NAMESPACE VALUES(11, 'PG_CATALOG')");
        executeUpdate("INSERT INTO PG_CATALOG.PG_NAMESPACE VALUES(2200, 'PUBLIC')");
        executeUpdate("INSERT INTO PG_CATALOG.PG_TYPE VALUES(23, 'INT4', 2200)");
        try {
            String query = "SELECT n.nspname = ANY(CONCAT('PG_CATALOG', ',PUBLIC')), "
                    + "n.nspname, t.typname " //
                    + "FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n "
                    + "ON t.typnamespace = n.oid WHERE t.oid = 23";
            assertQueryEquals(Boolean.TRUE, query);
            assertEquals("PUBLIC", queryObject(query, 2));
            assertEquals("INT4", queryObject(query, 3));
        } finally {
            executeUpdate("DROP TABLE IF EXISTS PG_CATALOG.PG_TYPE");
            executeUpdate("DROP TABLE IF EXISTS PG_CATALOG.PG_NAMESPACE");
        }
    }
}
