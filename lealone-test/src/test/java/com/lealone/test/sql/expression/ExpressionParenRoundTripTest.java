/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.expression;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 行值/列表/子查询/算术表达式的括号在 getSQL()（以及 plan SQL、视图定义）里必须始终保留，
 * 否则落盘后无法重新解析成等价语义。
 * <p>
 * 背景：{@code Select.getPlanSQL()} 打印 WHERE 之前会对 StatementBuilder 调用
 * {@code setEnclosed(false)}（目的是去掉整条条件最外层那一对冗余括号），
 * 而这个开关是"继承给整棵子树"的，于是所有依赖 {@code enBegin()/enEnd()} 输出括号的节点都被吞掉括号：
 * <pre>
 *   (ID, NAME) = (1, 'a')                  ->  ID, NAME = 1, 'a'
 *   (ID, NAME) IN ((1, 'a'), (2, 'b'))     ->  ID, NAME IN(1, 'a', 2, 'b')
 *   ID = (SELECT ...)                      ->  ID = SELECT ...
 *   (ID + 1) * 2 = 4                       ->  ID + 2 = 4     （语义悄悄变了！）
 * </pre>
 * 视图/CTE 存的正是这段文本，所以 CREATE VIEW 能成功、一查就报语法错，甚至静默返回错误的结果。
 * <p>
 * 这里用"批量 round-trip"的方式回归：每组表达式都要
 * <ol>
 * <li>建视图能查出正确结果；</li>
 * <li>从 {@code information_schema.views.view_definition} 取出落盘的定义 SQL，
 * 断言里面该有的括号在，并且把它当作新的查询重新执行能得到同样的结果；</li>
 * <li>作为 CTE 使用（CTE 内部同样要落盘成 plan SQL 再重新解析）也能查出正确结果。</li>
 * </ol>
 */
public class ExpressionParenRoundTripTest extends SqlAssertTestBase {

    /**
     * 每组用例：{名字, 查询 SQL, 期望的 id 列表, 落盘定义里必须出现的片段(没有要求时传 null)}
     * <p>
     * rt(id, name, v) = (1,'a',10), (2,'b',20), (3,'c',30)；bt(rid, nm) = (1,'a'), (2,'b'), (3,'c')
     */
    private static final String[][] CASES = {
            // ---------- 标量比较（多余的括号仍然不能输出） ----------
            { "scalar_eq", "SELECT id FROM rt WHERE id = 1", "1", "WHERE ID = 1" },
            { "scalar_and", "SELECT id FROM rt WHERE name = 'a' AND id = 1", "1",
                    "WHERE NAME = 'a'" },
            // ---------- 行值比较 ----------
            { "row_eq", "SELECT id FROM rt WHERE (id, name) = (1, 'a')", "1", "(ID, NAME) = (1, 'a')" },
            { "row_gt", "SELECT id FROM rt WHERE (id, v) > (1, 15)", "2,3", "(ID, V) > (1, 15)" },
            { "row_expr", "SELECT id FROM rt WHERE (id, id + 1) = (1, 2)", "1",
                    "(ID, ID + 1) = (1, 2)" },
            // ---------- 行值 + IN 列表 ----------
            { "row_in", "SELECT id FROM rt WHERE (id, name) IN ((1, 'a'), (3, 'c'))", "1,3",
                    "(ID, NAME) IN((1, 'a'), (3, 'c'))" },
            { "row_in_nonconst", "SELECT id FROM rt WHERE (id, name) IN ((id, 'a'), (2, 'b'))", "1,2",
                    "(ID, NAME) IN((ID, 'a'), (2, 'b'))" },
            // ---------- 行值 + 子查询 ----------
            { "row_subquery", "SELECT id FROM rt WHERE id IN (SELECT rid FROM bt WHERE rid > 1)", "2,3",
                    "IN(" },
            { "row_eq_subquery",
                    "SELECT id FROM rt WHERE (id, name) = (SELECT rid, nm FROM bt WHERE rid = 1)", "1",
                    "(ID, NAME) = (" },
            { "scalar_eq_subquery",
                    "SELECT id FROM rt WHERE id = (SELECT rid FROM bt WHERE rid = 1)", "1",
                    "ID = (" },
            // ---------- 行值 + AND / OR / NOT 嵌套 ----------
            { "and_row_in", "SELECT id FROM rt WHERE id < 3 AND (id, name) IN ((1, 'a'), (3, 'c'))",
                    "1", "(ID, NAME) IN" },
            { "or_row_in", "SELECT id FROM rt WHERE id = 1 OR (id, name) IN ((2, 'b'), (3, 'c'))",
                    "1,2,3", "(ID, NAME) IN" },
            { "and_or_row_in",
                    "SELECT id FROM rt WHERE (id = 1 OR id = 2) AND (id, name) IN ((1, 'a'), (2, 'b'))",
                    "1,2", "(ID, NAME) IN" },
            { "not_row_eq", "SELECT id FROM rt WHERE NOT ((id, name) = (1, 'a'))", "2,3",
                    "(ID, NAME) <>" },
            { "not_row_in", "SELECT id FROM rt WHERE NOT ((id, name) IN ((1, 'a')))", "2,3",
                    "(ID, NAME)" },
            { "not_row_in_and", "SELECT id FROM rt WHERE NOT (name LIKE 'a%') "
                    + "AND (id, name) IN ((2, 'b'), (3, 'c'))", "2,3", "(ID, NAME) IN" },
            // ---------- ANY / ALL ----------
            { "row_eq_any", "SELECT id FROM rt WHERE (id, name) = ANY(((1, 'a'), (3, 'c')))", "1,3",
                    "ANY((" },
            // ---------- 算术：= / * / - 的优先级靠括号表达，不能吞 ----------
            { "arith_mul", "SELECT id FROM rt WHERE (id + 1) * 2 = 4", "1", "(ID + 1) * 2" },
            { "arith_minus", "SELECT id FROM rt WHERE id - (v - 11) = 2", "1", "ID - (V - 11)" },
            { "arith_in", "SELECT id FROM rt WHERE (id + 1) * 2 IN (4, 6)", "1,2", "(ID + 1) * 2" },
            // ---------- 派生表 ----------
            { "derived_row_value",
                    "SELECT x.id FROM (SELECT id, name FROM rt) x WHERE (x.id, x.name) = (1, 'a')",
                    "1", "(X.ID, X.NAME) = (1, 'a')" }, };

    private void createTables() {
        executeUpdate("DROP VIEW IF EXISTS v_rt");
        executeUpdate("DROP TABLE IF EXISTS rt");
        executeUpdate("DROP TABLE IF EXISTS bt");
        executeUpdate("CREATE TABLE rt(id int primary key, name varchar(10), v int)");
        executeUpdate("INSERT INTO rt VALUES(1, 'a', 10)");
        executeUpdate("INSERT INTO rt VALUES(2, 'b', 20)");
        executeUpdate("INSERT INTO rt VALUES(3, 'c', 30)");
        executeUpdate("CREATE TABLE bt(rid int primary key, nm varchar(10))");
        executeUpdate("INSERT INTO bt VALUES(1, 'a')");
        executeUpdate("INSERT INTO bt VALUES(2, 'b')");
        executeUpdate("INSERT INTO bt VALUES(3, 'c')");
    }

    private void dropTables() {
        for (String[] c : CASES) {
            executeUpdate("DROP VIEW IF EXISTS " + viewName(c));
            executeUpdate("DROP VIEW IF EXISTS " + viewName(c) + "_re");
            executeUpdate("DROP VIEW IF EXISTS " + viewName(c) + "_cte");
        }
        executeUpdate("DROP TABLE IF EXISTS ck_paren");
        executeUpdate("DROP TABLE IF EXISTS bt");
        executeUpdate("DROP TABLE IF EXISTS rt");
    }

    private static String viewName(String[] c) {
        return "v_" + c[0];
    }

    private static List<Integer> expected(String csv) {
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(Integer.valueOf(s.trim()));
        }
        return list;
    }

    /**
     * ① 建视图 → 查询结果正确；② 落盘的定义 SQL 能重新解析出同样的结果；
     * ③ 同样的表达式放进 CTE 也能查出同样的结果（CTE 也是先落盘成 plan SQL 再重新解析）。
     */
    @Test
    public void viewCteAndMetadataRoundTrip() throws Exception {
        createTables();
        try {
            for (String[] c : CASES) {
                String name = c[0];
                String query = c[1];
                List<Integer> expect = expected(c[2]);
                String marker = c[3];

                // ① 视图能建、能查
                executeUpdate("CREATE VIEW " + viewName(c) + " AS " + query);
                List<Integer> viewIds = ids("SELECT id FROM " + viewName(c) + " ORDER BY id");
                assertEquals(name + ": rows of view created as [" + query + "]", expect, viewIds);

                // ② 从元数据取出落盘的定义 SQL
                String definition = viewDefinition(viewName(c));
                assertNotNull(name + ": view_definition is missing", definition);
                if (marker != null) {
                    assertTrue(name + ": necessary parentheses are missing from the stored definition:\n"
                            + definition, definition.contains(marker));
                }
                String storedQuery = storedQuery(definition);
                // 定义 SQL 文本本身必须能被重新解析：直接把它当查询执行
                List<Integer> reparsedIds = ids(storedQuery + "\nORDER BY 1");
                assertEquals(name + ": rows after re-parsing the stored definition:\n" + storedQuery,
                        expect, reparsedIds);
                // 也可以用它再建一个视图
                executeUpdate("CREATE VIEW " + viewName(c) + "_re AS " + storedQuery);
                assertEquals(name + ": rows of the view re-created from the stored definition:\n"
                        + storedQuery, expect,
                        ids("SELECT id FROM " + viewName(c) + "_re ORDER BY id"));

                // ③ CTE：解析时同样先把定义落盘成 plan SQL，用到时再重新解析
                List<Integer> cteIds = ids("WITH c AS (" + query + ") SELECT id FROM c ORDER BY id");
                assertEquals(name + ": rows of the CTE form of [" + query + "]", expect, cteIds);
            }
        } finally {
            dropTables();
        }
    }

    /**
     * 上面 20 组表达式都放入一条 CTE + 视图链里再跑一遍（把批量用例压到一次落盘里，
     * 顺便验证多个条件同时存在时括号不会互相干扰）。
     */
    @Test
    public void combinedExpressionInOneView() {
        createTables();
        try {
            executeUpdate("CREATE VIEW v_combined AS SELECT id FROM rt WHERE "
                    + "(id, name) IN ((1, 'a'), (2, 'b'), (3, 'c')) " //
                    + "AND (id + 1) * 2 IN (4, 6, 8) " //
                    + "AND NOT ((id, v) = (9, 90)) " //
                    + "AND id IN (SELECT rid FROM bt WHERE rid >= 1)");
            assertEquals(Arrays.asList(1, 2, 3),
                    ids("SELECT id FROM v_combined ORDER BY id"));

            String definition = viewDefinition("v_combined");
            assertTrue(definition, definition.contains("(ID, NAME) IN"));
            assertTrue(definition, definition.contains("(ID + 1) * 2"));
            assertTrue(definition, definition.contains("(ID, V)"));

            String storedQuery = storedQuery(definition);
            assertEquals(Arrays.asList(1, 2, 3), ids(storedQuery + "\nORDER BY 1"));
        } finally {
            executeUpdate("DROP VIEW IF EXISTS v_combined");
            dropTables();
        }
    }

    /**
     * GROUP BY / ORDER BY / HAVING 里的行值同样靠这对括号表达结构，落盘后也要能重新解析。
     * （ORDER BY 里的行值在 plan SQL 里会打印成 "=(ID, NAME)" 这种"按表达式排序"的形式，
     * 这是 Lealone/H2 的既有语法，重新解析仍然是等价的。）
     */
    @Test
    public void groupByAndOrderByRowValue() {
        createTables();
        try {
            executeUpdate("CREATE VIEW v_group_row AS SELECT id, COUNT(*) AS c FROM rt "
                    + "GROUP BY (id, name) HAVING COUNT(*) >= 1");
            assertEquals(Arrays.asList(1, 2, 3), ids("SELECT id FROM v_group_row ORDER BY id"));
            String groupDef = storedQuery(viewDefinition("v_group_row"));
            assertTrue(groupDef, groupDef.contains("GROUP BY (ID, NAME)"));
            assertEquals(Arrays.asList(1, 2, 3), ids(groupDef + "\nORDER BY 1"));
            executeUpdate("CREATE VIEW v_group_row_re AS " + groupDef);
            assertEquals(Arrays.asList(1, 2, 3), ids("SELECT id FROM v_group_row_re ORDER BY id"));

            executeUpdate("CREATE VIEW v_order_row AS SELECT id FROM rt ORDER BY (id, name) DESC");
            assertEquals(Arrays.asList(1, 2, 3), ids("SELECT id FROM v_order_row ORDER BY id"));
            String orderDef = storedQuery(viewDefinition("v_order_row"));
            assertTrue(orderDef, orderDef.contains("(ID, NAME)"));
            assertEquals("ORDER BY 的行值落盘后仍要能排序", Arrays.asList(3, 2, 1), ids(orderDef));
        } finally {
            executeUpdate("DROP VIEW IF EXISTS v_group_row");
            executeUpdate("DROP VIEW IF EXISTS v_group_row_re");
            executeUpdate("DROP VIEW IF EXISTS v_order_row");
            dropTables();
        }
    }

    /**
     * CHECK 约束也是落盘的 DDL：从 information_schema.constraints 取出 SQL 后再执行一遍，
     * 必须仍然能约束住非法的行。
     */
    @Test
    public void checkConstraintRoundTrip() {
        executeUpdate("DROP TABLE IF EXISTS ck_paren");
        try {
            executeUpdate("CREATE TABLE ck_paren(id int, name varchar(10), v int, "
                    + "CONSTRAINT ck_paren_row CHECK((id, name) IN ((1, 'a'), (2, 'b'))), "
                    + "CONSTRAINT ck_paren_arith CHECK(v = (id + 1) * 10))");
            executeUpdate("INSERT INTO ck_paren VALUES(1, 'a', 20)");
            executeUpdate("INSERT INTO ck_paren VALUES(2, 'b', 30)");
            assertSqlFails("INSERT INTO ck_paren VALUES(3, 'c', 40)");
            assertSqlFails("INSERT INTO ck_paren VALUES(1, 'a', 21)");
            executeUpdate("DELETE FROM ck_paren");

            // 落盘的 DDL 文本
            String rowDDL = constraintSQL("CK_PAREN_ROW");
            String arithDDL = constraintSQL("CK_PAREN_ARITH");
            assertNotNull(rowDDL);
            assertNotNull(arithDDL);
            assertTrue(rowDDL, rowDDL.contains("(ID, NAME)"));
            assertTrue(arithDDL, arithDDL.contains("(ID + 1) * 10"));

            // 删掉约束后重新执行落盘的 DDL（等价于元数据里重新解析一次）
            executeUpdate("ALTER TABLE ck_paren DROP CONSTRAINT ck_paren_row");
            executeUpdate("ALTER TABLE ck_paren DROP CONSTRAINT ck_paren_arith");
            executeUpdate(rowDDL);
            executeUpdate(arithDDL);
            executeUpdate("INSERT INTO ck_paren VALUES(1, 'a', 20)");
            assertSqlFails("INSERT INTO ck_paren VALUES(3, 'c', 40)");
            assertSqlFails("INSERT INTO ck_paren VALUES(1, 'a', 21)");
        } finally {
            executeUpdate("DROP TABLE IF EXISTS ck_paren");
        }
    }

    /**
     * 反向断言：不能为了修上面那些括号而到处乱加括号。
     * 这些标量条件/SELECT 列在落盘定义里必须保持原来的简洁形式。
     */
    @Test
    public void noRedundantParentheses() {
        createTables();
        try {
            executeUpdate("CREATE VIEW v_plain AS SELECT (id) FROM rt WHERE (id) = (1) AND (name) = 'a'");
            String definition = viewDefinition("v_plain");
            String storedQuery = storedQuery(definition);
            assertFalse("redundant parentheses around a select column:\n" + definition,
                    storedQuery.contains("(ID)"));
            assertFalse("redundant parentheses around a comparison:\n" + definition,
                    storedQuery.contains("(ID = 1)"));
            assertTrue(definition, storedQuery.contains("WHERE ID = 1"));
            assertTrue(definition, storedQuery.contains("AND NAME = 'a'"));

            executeUpdate("CREATE VIEW v_plain_having AS SELECT v, COUNT(*) FROM rt GROUP BY v "
                    + "HAVING COUNT(*) > 0");
            String having = storedQuery(viewDefinition("v_plain_having"));
            assertFalse("redundant parentheses around HAVING:\n" + having,
                    having.contains("(COUNT(*) > 0)"));
            assertTrue(having, having.contains("HAVING COUNT(*) > 0"));
        } finally {
            executeUpdate("DROP VIEW IF EXISTS v_plain");
            executeUpdate("DROP VIEW IF EXISTS v_plain_having");
            dropTables();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 从 CREATE [FORCE] VIEW ... AS\n<query> 里取出落盘的那段查询 SQL */
    private static String storedQuery(String createViewSQL) {
        assertNotNull(createViewSQL);
        int i = createViewSQL.indexOf(" AS\n");
        assertTrue("unexpected view definition: " + createViewSQL, i > 0);
        return createViewSQL.substring(i + " AS\n".length());
    }

    private List<Integer> ids(String querySql) {
        List<Integer> list = new ArrayList<>();
        try (ResultSet r = stmt.executeQuery(querySql)) {
            while (r.next()) {
                list.add(r.getInt(1));
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return list;
    }

    private String viewDefinition(String viewName) {
        try (ResultSet r = stmt.executeQuery(
                "SELECT TABLE_NAME, VIEW_DEFINITION FROM information_schema.views")) {
            while (r.next()) {
                if (viewName.equalsIgnoreCase(r.getString(1))) {
                    return r.getString(2);
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }

    private String constraintSQL(String constraintName) {
        try (ResultSet r = stmt.executeQuery(
                "SELECT CONSTRAINT_NAME, SQL FROM information_schema.constraints")) {
            while (r.next()) {
                if (constraintName.equalsIgnoreCase(r.getString(1))) {
                    return r.getString(2);
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
