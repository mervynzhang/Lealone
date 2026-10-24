/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.expression;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 谓词与三值逻辑：AND/OR/NOT 真值表、IS [NOT] NULL、行值、IN/NOT IN、LIKE ESCAPE、CASE。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。已确认不符合标准的断言放在 {@code @Ignore("BUG: ...")} 里。
 */
public class PredicateLogicTest extends SqlAssertTestBase {

    private static final String[] VALUES = { "TRUE", "FALSE", "NULL" };

    private void assertTruth(String expr, Boolean expected) {
        if (expected == null)
            assertQueryNull("SELECT " + expr);
        else
            assertQueryEquals(expected, "SELECT " + expr);
    }

    @Test
    public void andTruthTable() {
        Boolean[][] table = { //
                { Boolean.TRUE, Boolean.FALSE, null }, // TRUE AND ..
                { Boolean.FALSE, Boolean.FALSE, Boolean.FALSE }, // FALSE AND ..
                { null, Boolean.FALSE, null } }; // NULL AND ..
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                assertTruth(VALUES[i] + " AND " + VALUES[j], table[i][j]);
    }

    @Test
    public void orTruthTable() {
        Boolean[][] table = { //
                { Boolean.TRUE, Boolean.TRUE, Boolean.TRUE }, // TRUE OR ..
                { Boolean.TRUE, Boolean.FALSE, null }, // FALSE OR ..
                { Boolean.TRUE, null, null } }; // NULL OR ..
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                assertTruth(VALUES[i] + " OR " + VALUES[j], table[i][j]);
    }

    @Test
    public void notTruthTable() {
        assertQueryFalse("SELECT NOT TRUE");
        assertQueryTrue("SELECT NOT FALSE");
        assertQueryNull("SELECT NOT NULL");
        assertQueryNull("SELECT NULL = NULL");
        assertQueryNull("SELECT TRUE = NULL");
        assertQueryTrue("SELECT TRUE = TRUE");
    }

    @Test
    public void whereClauseFiltersUnknown() {
        executeUpdate("DROP TABLE IF EXISTS pl_t");
        executeUpdate("CREATE TABLE pl_t(id int primary key, flag boolean)");
        executeUpdate("INSERT INTO pl_t VALUES(1, TRUE)");
        executeUpdate("INSERT INTO pl_t VALUES(2, FALSE)");
        executeUpdate("INSERT INTO pl_t VALUES(3, NULL)");
        // WHERE 只保留 TRUE，FALSE 和 UNKNOWN 都被过滤掉
        assertQueryEquals(1L, "SELECT COUNT(*) FROM pl_t WHERE flag");
        assertQueryEquals(1L, "SELECT COUNT(*) FROM pl_t WHERE NOT flag");
        assertQueryEquals(0L, "SELECT COUNT(*) FROM pl_t WHERE NULL");
        assertQueryEquals(0L, "SELECT COUNT(*) FROM pl_t WHERE NOT NULL");
        assertQueryEquals(3L, "SELECT COUNT(*) FROM pl_t WHERE flag OR TRUE");
        executeUpdate("DROP TABLE pl_t");
    }

    @Test
    public void isNullPredicate() {
        assertQueryTrue("SELECT NULL IS NULL");
        assertQueryFalse("SELECT NULL IS NOT NULL");
        assertQueryFalse("SELECT 1 IS NULL");
        assertQueryTrue("SELECT 1 IS NOT NULL");
        assertQueryFalse("SELECT '' IS NULL");
        assertQueryFalse("SELECT 0 IS NULL");
        // IN 列表里的 NULL 不能被当成"普通值"
        assertQueryTrue("SELECT 1 IN (1, NULL)");
        assertQueryNull("SELECT 2 IN (1, NULL)");
        assertQueryNull("SELECT 2 NOT IN (1, NULL)");
        assertQueryNull("SELECT 1 NOT IN (2, NULL)");
        assertQueryFalse("SELECT 1 NOT IN (1, NULL)");
    }

    @Test
    public void emptyInList() {
        // 空 IN 列表在 H2 里是合法语法：IN () 恒为 FALSE，NOT IN () 恒为 TRUE
        assertQueryFalse("SELECT 1 IN ()");
        assertQueryTrue("SELECT 1 NOT IN ()");
        assertQueryFalse("SELECT NULL IN ()");
    }

    @Test
    public void inEmptySubQuery() {
        assertQueryFalse("SELECT 1 IN (SELECT 1 WHERE 1=0)");
        assertQueryTrue("SELECT 1 NOT IN (SELECT 1 WHERE 1=0)");
        assertQueryFalse("SELECT EXISTS(SELECT 1 WHERE 1=0)");
        assertQueryTrue("SELECT EXISTS(SELECT 1)");
    }

    @Test
    public void likeWithEscape() {
        assertQueryTrue("SELECT 'a_b' LIKE 'a\\_b' ESCAPE '\\'");
        assertQueryFalse("SELECT 'axb' LIKE 'a\\_b' ESCAPE '\\'");
        assertQueryTrue("SELECT '100%' LIKE '100\\%' ESCAPE '\\'");
        assertQueryFalse("SELECT '100x' LIKE '100\\%' ESCAPE '\\'");
        assertQueryTrue("SELECT 'axb' LIKE 'a_b'");
        assertQueryTrue("SELECT 'ab' LIKE 'a%'");
        assertQueryFalse("SELECT 'ab' LIKE 'a'");
        // ESCAPE 字符后面跟普通字符时按普通字符处理
        assertQueryTrue("SELECT 'a_b' LIKE 'a!_b' ESCAPE '!'");
        assertSqlFails("SELECT 'a' LIKE 'a' ESCAPE 'ab'", 22025);
    }

    @Test
    public void caseExpression() {
        // 搜索式 CASE：条件为 UNKNOWN 时走 ELSE
        assertQueryEquals(2, "SELECT CASE WHEN NULL THEN 1 ELSE 2 END");
        assertQueryEquals(1, "SELECT CASE WHEN TRUE THEN 1 ELSE 2 END");
        assertQueryNull("SELECT CASE WHEN FALSE THEN 1 END");
        // 简单 CASE 用 = 比较，NULL 不等于 NULL
        assertQueryEquals("ne", "SELECT CASE NULL WHEN NULL THEN 'eq' ELSE 'ne' END");
        assertQueryEquals("a", "SELECT CASE 1 WHEN 1 THEN 'a' WHEN 1 THEN 'b' END");
        // 第一个匹配的分支生效
        assertQueryEquals("b", "SELECT CASE 2 WHEN 1 THEN 'a' WHEN 2 THEN 'b' END");
        // CASE 的类型取所有分支的公共类型，NULL 分支不影响
        assertQueryNull("SELECT CASE WHEN 1=1 THEN NULL ELSE 1 END");
        assertQueryEquals(3, "SELECT COALESCE(NULL, NULL, 3)");
        assertQueryNull("SELECT NULLIF(1, 1)");
        assertQueryEquals(1, "SELECT NULLIF(1, 2)");
    }

    @Test
    public void distinctFromPredicate() {
        assertQueryFalse("SELECT NULL IS DISTINCT FROM NULL");
        assertQueryTrue("SELECT 1 IS DISTINCT FROM NULL");
        assertQueryFalse("SELECT 1 IS DISTINCT FROM 1");
        assertQueryTrue("SELECT NULL IS NOT DISTINCT FROM NULL");
        assertQueryFalse("SELECT 1 IS NOT DISTINCT FROM NULL");
    }

    @Test
    public void betweenWithNull() {
        assertQueryNull("SELECT 1 BETWEEN NULL AND 2");
        assertQueryNull("SELECT 2 BETWEEN 1 AND NULL");
        assertQueryNull("SELECT 0 BETWEEN NULL AND 2");
        assertQueryTrue("SELECT 1 BETWEEN 1 AND 2");
        assertQueryFalse("SELECT 3 BETWEEN 1 AND 2");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    @Test
    public void andWithFalseOnRight() {
        assertQueryFalse("SELECT NULL AND FALSE");
        assertQueryTrue("SELECT (NULL AND FALSE) = FALSE");
        // 一致性：NOT(x) 为 true 说明 x 应该是 FALSE
        assertQueryTrue("SELECT NOT (NULL AND FALSE)");
    }

    @Test
    public void rowValueIsNull() {
        assertQueryTrue("SELECT (NULL, NULL) IS NULL");
        assertQueryTrue("SELECT (NULL, NULL, NULL) IS NULL");
        assertQueryFalse("SELECT (NULL, NULL) IS NOT NULL");
        // 只有部分字段为 NULL 时才是 FALSE，这点 Lealone 是对的
        assertQueryFalse("SELECT (1, NULL) IS NULL");
    }

    // BUG: 行值比较没有做三值逻辑，含 NULL 的行比较直接返回 TRUE/FALSE 而不是 UNKNOWN。
    // 期望: (1,NULL) = (1,NULL) 为 NULL，(NULL,2) IN ((1,2)) 为 NULL（H2 均为 null）；
    // 实际: true / false。
    @Ignore("BUG: row value comparison with NULL returns TRUE/FALSE instead of UNKNOWN (H2: null)")
    @Test
    public void rowValueComparisonIsThreeValued() {
        assertQueryNull("SELECT (1, NULL) = (1, NULL)");
        assertQueryNull("SELECT (NULL, 2) = (1, 2)");
        assertQueryNull("SELECT (NULL, 2) IN ((1, 2))");
        assertQueryTrue("SELECT (1, 2) = (1, 2)");
    }
}
