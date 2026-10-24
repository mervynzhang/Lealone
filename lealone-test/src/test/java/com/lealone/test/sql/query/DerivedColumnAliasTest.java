/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

// 派生表支持列别名列表，如 (SELECT 1, 2) t(a, b)、(VALUES(1, 2), (3, 4)) t(a, b)
public class DerivedColumnAliasTest extends SqlTestBase {

    public DerivedColumnAliasTest() {
        setEmbedded(true);
    }

    @Test
    public void run() throws Exception {
        testDerivedSelect();
        testDerivedValues();
        testDerivedJoin();
        testError();
    }

    // (SELECT ...) T(a, b)
    private void testDerivedSelect() throws Exception {
        sql = "SELECT * FROM (SELECT 1, 2) t(a, b)";
        executeQuery();
        assertEquals("A", rs.getMetaData().getColumnLabel(1));
        assertEquals("B", rs.getMetaData().getColumnLabel(2));
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        closeResultSet();

        sql = "SELECT a, b FROM (SELECT 1, 2) t(a, b)";
        executeQuery();
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        closeResultSet();

        sql = "SELECT t.b FROM (SELECT 1, 2) t(a, b)";
        assertEquals(2, getIntValue(1, true));

        // 带AS，并且派生表自己有列别名
        sql = "SELECT * FROM (SELECT 1 AS x, 2 AS y) AS t(a, b)";
        executeQuery();
        assertEquals("A", rs.getMetaData().getColumnLabel(1));
        assertEquals("B", rs.getMetaData().getColumnLabel(2));
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        closeResultSet();

        // 列别名可以被WHERE/ORDER BY使用
        sql = "SELECT a FROM (SELECT 1, 2 UNION ALL SELECT 3, 4) t(a, b) WHERE b > 2 ORDER BY a";
        executeQuery();
        assertEquals(3, rs.getInt(1));
        assertFalse(rs.next());
        closeResultSet();

        // 嵌套派生表，每层都可以有列别名
        sql = "SELECT * FROM (SELECT * FROM (SELECT 1, 2) x(a, b)) y(c, d)";
        executeQuery();
        assertEquals("C", rs.getMetaData().getColumnLabel(1));
        assertEquals("D", rs.getMetaData().getColumnLabel(2));
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        closeResultSet();

        // 带引号的列别名，重新编译SQL时也要保留
        sql = "SELECT * FROM (SELECT * FROM (SELECT 1) x(\"a b\")) y(\"c d\")";
        executeQuery();
        assertEquals("c d", rs.getMetaData().getColumnLabel(1));
        assertEquals(1, rs.getInt(1));
        closeResultSet();

        // 派生表查询真实的表
        executeUpdate("DROP TABLE IF EXISTS DerivedColumnAliasTest");
        executeUpdate("CREATE TABLE DerivedColumnAliasTest(pk int NOT NULL PRIMARY KEY, name varchar(10))");
        executeUpdate("INSERT INTO DerivedColumnAliasTest(pk, name) VALUES(1, 'a')");
        executeUpdate("INSERT INTO DerivedColumnAliasTest(pk, name) VALUES(2, 'b')");
        sql = "SELECT id, n FROM (SELECT pk, name FROM DerivedColumnAliasTest) t(id, n) WHERE id = 2";
        executeQuery();
        assertEquals(2, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        closeResultSet();
    }

    // (VALUES (...), (...)) T(a, b)
    private void testDerivedValues() throws Exception {
        sql = "SELECT * FROM (VALUES (1, 2), (3, 4)) t(a, b)";
        executeQuery();
        assertEquals("A", rs.getMetaData().getColumnLabel(1));
        assertEquals("B", rs.getMetaData().getColumnLabel(2));
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals(4, rs.getInt(2));
        assertFalse(rs.next());
        closeResultSet();

        sql = "SELECT sum(a), max(b) FROM (VALUES (1, 2), (3, 4)) t(a, b)";
        executeQuery();
        assertEquals(4, rs.getInt(1));
        assertEquals(4, rs.getInt(2));
        closeResultSet();

        sql = "SELECT b FROM (VALUES (1, 2), (3, 4)) t(a, b) WHERE a = 3";
        assertEquals(4, getIntValue(1, true));

        // 不带括号的VALUES
        sql = "SELECT b FROM VALUES (1, 2), (3, 4) t(a, b) WHERE a = 1";
        assertEquals(2, getIntValue(1, true));

        // 没有列别名列表时保持原来的C1、C2列名
        sql = "SELECT * FROM (VALUES (1, 2))";
        executeQuery();
        assertEquals("C1", rs.getMetaData().getColumnLabel(1));
        assertEquals("C2", rs.getMetaData().getColumnLabel(2));
        closeResultSet();
    }

    // 带列别名的派生表参与JOIN
    private void testDerivedJoin() throws Exception {
        sql = "SELECT t.a, u.c FROM (SELECT 1, 2) t(a, b) JOIN (SELECT 1, 5) u(c, d) ON t.a = u.c";
        executeQuery();
        assertEquals(1, rs.getInt(1));
        assertEquals(1, rs.getInt(2));
        closeResultSet();

        executeUpdate("DROP TABLE IF EXISTS DerivedColumnAliasJoinTest");
        executeUpdate(
                "CREATE TABLE DerivedColumnAliasJoinTest(pk int NOT NULL PRIMARY KEY)");
        executeUpdate("INSERT INTO DerivedColumnAliasJoinTest(pk) VALUES(2)");
        executeUpdate("INSERT INTO DerivedColumnAliasJoinTest(pk) VALUES(3)");
        sql = "SELECT count(*) FROM (VALUES (1), (2), (3)) t(a)"
                + " JOIN DerivedColumnAliasJoinTest s ON t.a = s.pk";
        assertEquals(2, getIntValue(1, true));
    }

    private void testError() throws Exception {
        // 列别名列表不能比派生表的输出列多
        sql = "SELECT * FROM (SELECT 1) t(a, b)";
        executeQueryThanAssertErrorCode(sql, ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);

        sql = "SELECT * FROM (VALUES (1)) t(a, b)";
        executeQueryThanAssertErrorCode(sql, ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
    }
}
