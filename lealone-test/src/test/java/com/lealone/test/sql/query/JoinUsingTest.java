/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

// JOIN支持USING子句，语义等价于ON a.col = b.col，并且SELECT *时USING列只输出一次
public class JoinUsingTest extends SqlTestBase {

    public JoinUsingTest() {
        setEmbedded(true);
    }

    @Test
    public void run() throws Exception {
        init();
        testInnerJoin();
        testLeftJoin();
        testRightJoin();
        testMultipleColumns();
        testAliasAndDerivedTable();
        testError();
    }

    private void init() {
        executeUpdate("DROP TABLE IF EXISTS JoinUsingA");
        executeUpdate("DROP TABLE IF EXISTS JoinUsingB");
        executeUpdate("CREATE TABLE JoinUsingA(pk int NOT NULL PRIMARY KEY, id int, name varchar(10))");
        executeUpdate("CREATE TABLE JoinUsingB(pk2 int NOT NULL PRIMARY KEY, id int, name2 varchar(10))");
        executeUpdate("INSERT INTO JoinUsingA(pk, id, name) VALUES(1, 1, 'a1')");
        executeUpdate("INSERT INTO JoinUsingA(pk, id, name) VALUES(2, 2, 'a2')");
        executeUpdate("INSERT INTO JoinUsingA(pk, id, name) VALUES(3, 3, 'a3')");
        executeUpdate("INSERT INTO JoinUsingB(pk2, id, name2) VALUES(1, 2, 'b2')");
        executeUpdate("INSERT INTO JoinUsingB(pk2, id, name2) VALUES(2, 3, 'b3')");
        executeUpdate("INSERT INTO JoinUsingB(pk2, id, name2) VALUES(3, 4, 'b4')");
    }

    private String getColumnLabels() throws Exception {
        int count = rs.getMetaData().getColumnCount();
        StringBuilder buff = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1)
                buff.append(',');
            buff.append(rs.getMetaData().getColumnLabel(i));
        }
        return buff.toString();
    }

    // USING列在SELECT *里只输出一次
    private void testInnerJoin() throws Exception {
        sql = "SELECT * FROM JoinUsingA JOIN JoinUsingB USING (id)";
        executeQuery();
        assertEquals("PK,ID,NAME,PK2,NAME2", getColumnLabels());
        assertEquals(2, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(2));
        assertFalse(rs.next());
        closeResultSet();

        sql = "SELECT * FROM JoinUsingA INNER JOIN JoinUsingB USING (id)";
        executeQuery();
        assertEquals("PK,ID,NAME,PK2,NAME2", getColumnLabels());
        closeResultSet();

        // 和ON的写法结果一样
        sql = "SELECT count(*) FROM JoinUsingA JOIN JoinUsingB USING (id)";
        assertEquals(2, getIntValue(1, true));
        sql = "SELECT count(*) FROM JoinUsingA JOIN JoinUsingB ON JoinUsingA.id = JoinUsingB.id";
        assertEquals(2, getIntValue(1, true));
    }

    // LEFT JOIN时USING列取左边表的值
    private void testLeftJoin() throws Exception {
        sql = "SELECT * FROM JoinUsingA LEFT JOIN JoinUsingB USING (id) ORDER BY pk";
        executeQuery();
        assertEquals("PK,ID,NAME,PK2,NAME2", getColumnLabels());
        assertEquals(1, rs.getInt(1));
        assertEquals(1, rs.getInt(2));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(2));
        assertEquals(1, rs.getInt(4));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(2));
        assertEquals(2, rs.getInt(4));
        assertFalse(rs.next());
        closeResultSet();
    }

    // RIGHT JOIN时USING列取右边表的值
    private void testRightJoin() throws Exception {
        sql = "SELECT * FROM JoinUsingA RIGHT JOIN JoinUsingB USING (id) ORDER BY pk2";
        executeQuery();
        assertEquals("PK2,ID,NAME2,PK,NAME", getColumnLabels());
        assertEquals(1, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        assertEquals(2, rs.getInt(4));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals(3, rs.getInt(2));
        assertEquals(3, rs.getInt(4));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals(4, rs.getInt(2));
        assertNull(rs.getObject(4));
        assertFalse(rs.next());
        closeResultSet();
    }

    // 多个USING列
    private void testMultipleColumns() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS JoinUsingC");
        executeUpdate("DROP TABLE IF EXISTS JoinUsingD");
        executeUpdate("CREATE TABLE JoinUsingC(pk int NOT NULL PRIMARY KEY, id int, code int, v1 int)");
        executeUpdate("CREATE TABLE JoinUsingD(pk2 int NOT NULL PRIMARY KEY, id int, code int, v2 int)");
        executeUpdate("INSERT INTO JoinUsingC(pk, id, code, v1) VALUES(1, 1, 10, 100)");
        executeUpdate("INSERT INTO JoinUsingC(pk, id, code, v1) VALUES(2, 1, 20, 200)");
        executeUpdate("INSERT INTO JoinUsingC(pk, id, code, v1) VALUES(3, 2, 10, 300)");
        executeUpdate("INSERT INTO JoinUsingD(pk2, id, code, v2) VALUES(1, 1, 10, 100)");
        executeUpdate("INSERT INTO JoinUsingD(pk2, id, code, v2) VALUES(2, 2, 10, 200)");
        executeUpdate("INSERT INTO JoinUsingD(pk2, id, code, v2) VALUES(3, 1, 30, 300)");

        sql = "SELECT count(*) FROM JoinUsingC JOIN JoinUsingD USING (id, code)";
        // 只有(1,10)和(2,10)能匹配上
        assertEquals(2, getIntValue(1, true));

        sql = "SELECT * FROM JoinUsingC JOIN JoinUsingD USING (id, code)";
        executeQuery();
        assertEquals("PK,ID,CODE,V1,PK2,V2", getColumnLabels());
        closeResultSet();

        // 和ON的写法结果一样
        sql = "SELECT count(*) FROM JoinUsingC JOIN JoinUsingD"
                + " ON JoinUsingC.id = JoinUsingD.id AND JoinUsingC.code = JoinUsingD.code";
        assertEquals(2, getIntValue(1, true));
    }

    // USING和表别名、派生表列别名一起用
    private void testAliasAndDerivedTable() throws Exception {
        sql = "SELECT a.id, b.id FROM JoinUsingA a JOIN JoinUsingB b USING (id)";
        executeQuery();
        assertEquals(2, rs.getInt(1));
        assertEquals(2, rs.getInt(2));
        closeResultSet();

        sql = "SELECT count(*) FROM JoinUsingA a JOIN JoinUsingB b USING (id) WHERE a.pk > 0";
        assertEquals(2, getIntValue(1, true));

        sql = "SELECT * FROM (SELECT 1, 'x') t(a, b) JOIN (SELECT 1, 'y') u(a, c) USING (a)";
        executeQuery();
        assertEquals("A,B,C", getColumnLabels());
        assertEquals(1, rs.getInt(1));
        assertEquals("x", rs.getString(2));
        assertEquals("y", rs.getString(3));
        closeResultSet();
    }

    private void testError() throws Exception {
        // USING的列必须两边都有
        sql = "SELECT * FROM JoinUsingA JOIN JoinUsingB USING (pk)";
        executeQueryThanAssertErrorCode(sql, ErrorCode.COLUMN_NOT_FOUND_1);

        sql = "SELECT * FROM JoinUsingA JOIN JoinUsingB USING (nonexist)";
        executeQueryThanAssertErrorCode(sql, ErrorCode.COLUMN_NOT_FOUND_1);
    }
}
