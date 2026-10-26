/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.expression;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.lealone.client.jdbc.JdbcConnection;
import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.query.Select;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * {@code expr = ANY(array)} / {@code expr <> ALL(array)} 以及其它比较运算符的 ANY/ALL 形式。
 * <p>
 * 右侧既可以是数组值（Lealone 用行值构造器 {@code (1, 2, 3)}、{@code ('a', 'b')} 表示数组），
 * 也可以是 PostgreSQL 风格的花括号数组字面量，例如 {@code '{a,b,c}'}。
 * <p>
 * <b>只有花括号包裹的字符串常量才按元素拆分</b>；普通字符串常量（即使含逗号）按单值处理。
 * 这条规则来自第三方评审 H1：以前 {@code 'Doe, John' = ANY('Doe, John')} 会把右侧按逗号拆成
 * {@code 'Doe'} 和 {@code 'John'}，静默错值。花括号字面量解析失败时报
 * {@link ErrorCode#MALFORMED_ARRAY_LITERAL_1}，不会退化成单值。
 * <p>
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
    public void braceArrayLiteralRightSide() {
        // PG 风格数组字面量：只有花括号包裹的字符串才按元素拆分
        assertQueryTrue("SELECT 1 = ANY('{1,2,3}')");
        assertQueryFalse("SELECT 4 = ANY('{1,2,3}')");
        assertQueryTrue("SELECT 2 <> ALL('{1,3,4}')");
        assertQueryFalse("SELECT 2 <> ALL('{1,2,3}')");
        assertQueryTrue("SELECT 'a' = ANY('{a,b,c}')");
        assertQueryFalse("SELECT 'd' = ANY('{a,b,c}')");
        // 未加引号的元素两端空白会被去掉（与 PG array_in 一致）
        assertQueryTrue("SELECT 'b' = ANY('{a, b , c}')");
        // 每个元素前后的空白也算数组的一部分，不影响识别
        assertQueryTrue("SELECT 1 = ANY(' {1,2,3} ')");
        // 双引号元素里的逗号是元素内容，不是分隔符
        assertQueryFalse("SELECT 'b' = ANY('{\"a,b\",\"c\"}')");
        assertQueryTrue("SELECT 'a,b' = ANY('{\"a,b\",\"c\"}')");
        assertQueryTrue("SELECT 'c' = ANY('{\"a,b\", \"c\"}')");
        // 双引号内的转义：\" 和 \\
        assertQueryTrue("SELECT 'a\"b' = ANY('{\"a\\\"b\"}')");
        assertQueryTrue("SELECT 'c\\d' = ANY('{\"c\\\\d\"}')");
        assertQueryFalse("SELECT 'c\\\\d' = ANY('{\"c\\\\d\"}')");
        // NULL 元素参与三值逻辑；带双引号的 "NULL" 是普通字符串
        assertQueryNull("SELECT 'x' = ANY('{NULL}')");
        assertQueryTrue("SELECT 'x' = ANY('{NULL,x}')");
        assertQueryTrue("SELECT 'NULL' = ANY('{\"NULL\"}')");
        // 空数组：ANY 恒为 FALSE，ALL 恒为 TRUE
        assertQueryFalse("SELECT 1 = ANY('{}')");
        assertQueryFalse("SELECT NULL = ANY('{}')");
        assertQueryTrue("SELECT 1 <> ALL('{}')");
        assertQueryTrue("SELECT NULL <> ALL('{}')");
    }

    @Test
    public void plainStringIsSingleValue() {
        // 第三方评审 H1 的关键回归：普通字符串常量不再按逗号隐式拆分
        assertQueryTrue("SELECT 'Doe, John' = ANY('Doe, John')");
        assertQueryFalse("SELECT 'Doe' = ANY('Doe, John')");
        assertQueryFalse("SELECT 'John' = ANY('Doe, John')");
        // 单值语义同样作用于 ALL：'Doe' 与唯一的元素 'Doe, John' 不相等
        assertQueryFalse("SELECT 'Doe, John' <> ALL('Doe, John')");
        assertQueryTrue("SELECT 'Doe' <> ALL('Doe, John')");
        // 空字符串是"一个空字符串元素"，不是空数组（空数组写 '{}'）
        assertQueryTrue("SELECT '' = ANY('')");
        assertQueryFalse("SELECT 'a' = ANY('')");
        // 想表达"只含一个带逗号的元素"要显式加花括号和双引号
        assertQueryTrue("SELECT 'Doe, John' = ANY('{\"Doe, John\"}')");
        // 不以 { 开头的字符串即使以 } 结尾也还是单值
        assertQueryTrue("SELECT 'a}' = ANY('a}')");
        // 数值列遇上 '1,2,3' 这种单值字符串是数据转换错误，不会静默"没匹配上"
        assertEquals(ErrorCode.DATA_CONVERSION_ERROR_1,
                assertSqlFails("SELECT 1 = ANY('1,2,3')").getErrorCode());
    }

    @Test
    public void malformedArrayLiteralIsRejected() {
        // 花括号字面量解析失败必须报明确错误，不能静默退化成单值
        SQLException e = assertSqlFails("SELECT 1 = ANY('{1,2')");
        assertEquals(ErrorCode.MALFORMED_ARRAY_LITERAL_1, e.getErrorCode());
        assertSqlFails("SELECT 1 = ANY('{1,2}b')");
        assertSqlFails("SELECT 1 = ANY('{a,,b}')");
        assertSqlFails("SELECT 1 = ANY('{a,}')");
        assertSqlFails("SELECT 1 = ANY('{\"a}')");
        assertSqlFails("SELECT 1 = ANY('{{1,2},{3,4}}')");
        assertSqlFails("SELECT 'b' = ANY('{\"a\"b}')");
        // 表列/参数的坏字面量走逐行求值路径，同样必须报错，不能静默当单值
        executeUpdate("DROP TABLE IF EXISTS condition_any_bad");
        executeUpdate("CREATE TABLE condition_any_bad(v varchar(20))");
        executeUpdate("INSERT INTO condition_any_bad VALUES('{a,b')");
        SQLException runtime = assertSqlFails(
                "SELECT COUNT(*) FROM condition_any_bad WHERE 'a' = ANY(v)");
        assertEquals(ErrorCode.MALFORMED_ARRAY_LITERAL_1, runtime.getErrorCode());
        executeUpdate("DROP TABLE IF EXISTS condition_any_bad");
    }

    @Test
    public void anyAllWithTableColumnsAndParameters() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS condition_any_t");
        executeUpdate("DROP TABLE IF EXISTS condition_any_names");
        executeUpdate("CREATE TABLE condition_any_t(id int, name varchar(30), csv varchar(20))");
        executeUpdate("INSERT INTO condition_any_t VALUES(1, 'a', '{1,3,5}')");
        executeUpdate("INSERT INTO condition_any_t VALUES(2, 'b', '{1,3,5}')");
        executeUpdate("INSERT INTO condition_any_t VALUES(3, 'c', '{1,3,5}')");
        executeUpdate("INSERT INTO condition_any_t VALUES(4, 'd', '{1,3,5}')");
        executeUpdate("INSERT INTO condition_any_t VALUES(5, 'e', '{1,3,5}')");

        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((1, 3, 5))");
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY('{1,3,5}')");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM condition_any_t WHERE id <> ALL((1, 2, 3))");
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE name = ANY('{a,c,e}')");
        // 逗号字符串是单值，不会命中任何单个 name
        assertQueryEquals(0L, "SELECT COUNT(*) FROM condition_any_t WHERE name = ANY('a,c,e')");
        // 右侧是表列(逐行求值)，列里存的是花括号数组字面量
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY(csv)");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM condition_any_t WHERE id <> ALL(csv)");
        // 含 NULL 且不命中：UNKNOWN 被 WHERE 过滤掉，不能算成命中
        assertQueryEquals(1L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((2, NULL))");
        assertQueryEquals(0L, "SELECT COUNT(*) FROM condition_any_t WHERE id = ANY((9, NULL))");
        // 常量数组在优化阶段改写成 IN(...)（这样能用索引），非常量右侧仍打印 ANY(...)
        assertPlanContains("SELECT * FROM condition_any_t WHERE id = ANY((1, 2, 3))", "IN(");
        assertPlanContains("SELECT * FROM condition_any_t WHERE id = ANY(csv)", "ANY(");

        // 表列场景：'{Doe, John}'（两个元素）与 'Doe, John'（一个值）语义不同，且都正确
        executeUpdate("CREATE TABLE condition_any_names(name varchar(30))");
        executeUpdate("INSERT INTO condition_any_names VALUES('Doe')");
        executeUpdate("INSERT INTO condition_any_names VALUES('John')");
        executeUpdate("INSERT INTO condition_any_names VALUES('Doe, John')");
        assertQueryEquals(2L,
                "SELECT COUNT(*) FROM condition_any_names WHERE name = ANY('{Doe, John}')");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM condition_any_names WHERE name = ANY('Doe, John')");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM condition_any_names WHERE name = ANY('{\"Doe, John\"}')");
        // 右侧是表列时逐行求值：逗号只是值的一部分，每行都比自己
        assertQueryEquals(3L, "SELECT COUNT(*) FROM condition_any_names WHERE name = ANY(name)");
        // 常量花括号字面量同样改写成 IN(...)，能用上索引
        assertPlanContains(
                "SELECT * FROM condition_any_names WHERE name = ANY('{Doe, John}')", "IN(");

        // 花括号数组字面量作为参数
        try (PreparedStatement ps = conn
                .prepareStatement("SELECT COUNT(*) FROM condition_any_t WHERE id = ANY(?)")) {
            ps.setString(1, "{2,4}");
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
            }
        }
        // 普通字符串参数按单值处理，不按逗号拆分
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM condition_any_names WHERE name = ANY(?)")) {
            ps.setString(1, "Doe, John");
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
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
     * {@code n.nspname = ANY(current_schemas(true))}。PostgreSQL 插件会先把这条语句改写成
     * {@code ANY(SELECT ...)}（见插件 PgSQLParser），所以核心库这边只需要支持数组语义。
     * 这里用 PG_CATALOG 表模拟，右侧换成运行时拼出来的花括号数组字面量，覆盖"非常量字符串
     * 右值也要按数组字面量解析"这条路径。
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
            String query = "SELECT n.nspname = ANY(CONCAT('{PG_CATALOG', ',PUBLIC}')), "
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

    /**
     * CREATE VIEW / CTE 存的是条件表达式的 getSQL()（经由 Select.getPlanSQL），
     * 所以 getSQL() 必须能打印出可重新解析的 ANY/ALL。
     * <p>
     * 回归：以前 ExpressionList 的括号会被 Select.getPlanSQL 的 setEnclosed(false) 吞掉，
     * ANY((1, 3)) 被打印成 ANY(1, 3)，于是视图能建成功、一查就报语法错
     * （CTE 同理）。这里既验证视图/CTE 的端到端结果，也直接重新解析 getSQL() 文本。
     */
    @Test
    public void viewAndCteRoundTrip() throws Exception {
        executeUpdate("DROP VIEW IF EXISTS condition_any_v");
        executeUpdate("DROP VIEW IF EXISTS condition_any_v_all");
        executeUpdate("DROP VIEW IF EXISTS condition_any_v_row");
        executeUpdate("DROP VIEW IF EXISTS condition_any_v_dyn");
        executeUpdate("DROP TABLE IF EXISTS condition_any_rt");
        executeUpdate("CREATE TABLE condition_any_rt(id int primary key, name varchar(10))");
        executeUpdate("INSERT INTO condition_any_rt VALUES(1, 'a')");
        executeUpdate("INSERT INTO condition_any_rt VALUES(2, 'b')");
        executeUpdate("INSERT INTO condition_any_rt VALUES(3, 'c')");
        try {
            // 视图：ANY + IN 列表形式
            executeUpdate("CREATE VIEW condition_any_v AS SELECT id, name FROM condition_any_rt "
                    + "WHERE id = ANY((1, 3))");
            assertEquals(Arrays.asList(1, 3), intColumn("SELECT id FROM condition_any_v ORDER BY id"));

            // 视图：<> ALL
            executeUpdate("CREATE VIEW condition_any_v_all AS SELECT id FROM condition_any_rt "
                    + "WHERE id <> ALL((1, 3))");
            assertEquals(Arrays.asList(2), intColumn("SELECT id FROM condition_any_v_all"));

            // 视图：行值(row value)的 ANY
            executeUpdate("CREATE VIEW condition_any_v_row AS SELECT id FROM condition_any_rt "
                    + "WHERE (id, name) = ANY(((1, 'a'), (3, 'c')))");
            assertEquals(Arrays.asList(1, 3), intColumn("SELECT id FROM condition_any_v_row ORDER BY id"));

            // 视图：右侧不是常量的行值 ANY（不会被改写成 IN，必须保留 ANY 的括号）
            executeUpdate("DROP VIEW IF EXISTS condition_any_v_dyn");
            executeUpdate("CREATE VIEW condition_any_v_dyn AS SELECT id FROM condition_any_rt "
                    + "WHERE (id, name) = ANY(((id, name), (3, 'c')))");
            assertEquals(Arrays.asList(1, 2, 3),
                    intColumn("SELECT id FROM condition_any_v_dyn ORDER BY id"));

            // 常量数组的 ANY 在 optimize 里改写成 IN 后也要能用主键索引，而不是 tableScan
            String plan = explain("SELECT * FROM condition_any_rt WHERE id = ANY((1, 3))");
            assertTrue(plan, plan.contains("IN("));
            assertFalse(plan, plan.contains("tableScan"));

            // CTE：同样会保存/重新解析 getPlanSQL
            assertEquals(Arrays.asList(1, 3), intColumn("WITH cte AS (SELECT id FROM condition_any_rt "
                    + "WHERE id = ANY((1, 3))) SELECT id FROM cte ORDER BY id"));
            assertEquals(Arrays.asList(2), intColumn("WITH cte AS (SELECT id FROM condition_any_rt "
                    + "WHERE id <> ALL((1, 3))) SELECT id FROM cte"));
            // CTE + 窗口函数：两个 getPlanSQL 修复点一起走一遍
            assertEquals(Arrays.asList(1, 2, 3), intColumn("WITH cte AS (SELECT id, "
                    + "ROW_NUMBER() OVER (ORDER BY id) AS rn FROM condition_any_rt "
                    + "WHERE id = ANY((1, 2, 3))) SELECT rn FROM cte ORDER BY id"));

            // 直接断言 getSQL() 能被重新 parse：把条件 SQL 拼回一条完整的 SELECT 再执行
            ServerSession session = (ServerSession) ((JdbcConnection) conn).getSession();
            // 非常量右侧：ConditionAny 保持原样，ExpressionList 的括号必须打印出来
            Select select = (Select) session.prepareStatement("SELECT * FROM condition_any_rt "
                    + "WHERE id = ANY((id + 1, id))");
            String conditionSQL = conditionSQL(select.getCondition());
            assertTrue("right side ExpressionList must keep its parentheses: " + conditionSQL,
                    conditionSQL.contains("ANY(("));
            assertEquals(3L, queryObject("SELECT COUNT(*) FROM condition_any_rt WHERE "
                    + conditionSQL));

            select = (Select) session.prepareStatement("SELECT * FROM condition_any_rt "
                    + "WHERE (id, name) = ANY(((id, name), (3, 'c')))");
            conditionSQL = conditionSQL(select.getCondition());
            assertTrue("both sides must keep their parentheses: " + conditionSQL,
                    conditionSQL.contains("ANY((") && conditionSQL.startsWith("(ID, NAME)"));
            assertEquals(3L, queryObject("SELECT COUNT(*) FROM condition_any_rt WHERE "
                    + conditionSQL));

            // 常量数组/花括号数组字面量被优化成 IN，仍然必须可重新解析且语义不变
            select = (Select) session.prepareStatement("SELECT * FROM condition_any_rt "
                    + "WHERE id = ANY((1, 3, 5))");
            conditionSQL = conditionSQL(select.getCondition());
            assertTrue(conditionSQL, conditionSQL.contains("IN("));
            assertEquals(2L, queryObject("SELECT COUNT(*) FROM condition_any_rt WHERE "
                    + conditionSQL));

            select = (Select) session.prepareStatement("SELECT * FROM condition_any_rt "
                    + "WHERE id = ANY('{1, 3}')");
            conditionSQL = conditionSQL(select.getCondition());
            assertTrue(conditionSQL, conditionSQL.contains("IN("));
            assertEquals(2L, queryObject("SELECT COUNT(*) FROM condition_any_rt WHERE "
                    + conditionSQL));
        } finally {
            executeUpdate("DROP VIEW IF EXISTS condition_any_v");
            executeUpdate("DROP VIEW IF EXISTS condition_any_v_all");
            executeUpdate("DROP VIEW IF EXISTS condition_any_v_row");
            executeUpdate("DROP VIEW IF EXISTS condition_any_v_dyn");
            executeUpdate("DROP TABLE IF EXISTS condition_any_rt");
        }
    }

    /** 按 Select.getPlanSQL 的方式打印条件（顶层不加外层括号），用于验证可重新解析 */
    private static String conditionSQL(Expression condition) {
        StatementBuilder buff = new StatementBuilder();
        buff.setEnclosed(false);
        condition.getSQL(buff);
        return buff.toString();
    }

    private List<Integer> intColumn(String querySql) {
        List<Integer> list = new ArrayList<>();
        try (ResultSet r = stmt.executeQuery(querySql)) {
            while (r.next()) {
                list.add(r.getInt(1));
            }
        } catch (java.sql.SQLException e) {
            throw DbException.convert(e);
        }
        return list;
    }
}
