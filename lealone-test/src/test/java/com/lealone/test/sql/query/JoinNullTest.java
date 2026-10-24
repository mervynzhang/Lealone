/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 连接与 NULL：LEFT/RIGHT/NATURAL JOIN 的 NULL 扩展、ON vs WHERE、三表连接。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。已确认不符合期望的断言放在 {@code @Ignore("BUG: ...")} 里。
 */
public class JoinNullTest extends SqlAssertTestBase {

    private void init() {
        executeUpdate("DROP TABLE IF EXISTS jn_3");
        executeUpdate("DROP TABLE IF EXISTS jn_2");
        executeUpdate("DROP TABLE IF EXISTS jn_1");
        executeUpdate("CREATE TABLE jn_1(id int primary key, v varchar(10))");
        executeUpdate("CREATE TABLE jn_2(id int primary key, v varchar(10))");
        executeUpdate("CREATE TABLE jn_3(id int primary key, v varchar(10))");
        executeUpdate("INSERT INTO jn_1 VALUES(1, 'a')");
        executeUpdate("INSERT INTO jn_1 VALUES(2, 'b')");
        executeUpdate("INSERT INTO jn_1 VALUES(3, NULL)");
        executeUpdate("INSERT INTO jn_2 VALUES(2, 'b')");
        executeUpdate("INSERT INTO jn_2 VALUES(3, 'c')");
        executeUpdate("INSERT INTO jn_2 VALUES(4, NULL)");
        executeUpdate("INSERT INTO jn_3 VALUES(2, 'b')");
        executeUpdate("INSERT INTO jn_3 VALUES(3, 'c')");
    }

    @Test
    public void innerJoinDropsUnmatchedRows() {
        init();
        assertQueryEquals(2L, "SELECT COUNT(*) FROM jn_1 a JOIN jn_2 b ON a.id = b.id");
        assertQueryEquals(2L, "SELECT COUNT(*) FROM jn_1 a, jn_2 b WHERE a.id = b.id");
        // NULL 键（这里用 v 模拟）不参与等值匹配
        assertQueryEquals(0L, "SELECT COUNT(*) FROM jn_1 a JOIN jn_2 b ON a.v = b.v AND a.id = 4");
    }

    @Test
    public void leftJoinNullExtension() {
        init();
        assertQueryEquals(3L, "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id");
        // 未匹配的左侧行，右表列全部是 NULL
        assertQueryNull("SELECT b.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE a.id = 1");
        assertQueryEquals(2, "SELECT b.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE a.id = 2");
        // 用右表列做 IS NULL 可以找出反连接（anti join）
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE b.id IS NULL");
        assertQueryEquals(1, "SELECT a.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE b.id IS NULL");
    }

    @Test
    public void rightJoinNullExtension() {
        init();
        assertQueryEquals(3L, "SELECT COUNT(*) FROM jn_1 a RIGHT JOIN jn_2 b ON a.id = b.id");
        assertQueryNull("SELECT a.id FROM jn_1 a RIGHT JOIN jn_2 b ON a.id = b.id WHERE b.id = 4");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a RIGHT JOIN jn_2 b ON a.id = b.id WHERE a.id IS NULL");
    }

    @Test
    public void naturalJoin() {
        init();
        // NATURAL JOIN 按同名列 (id, v) 连接，NULL 永不等于 NULL
        assertQueryEquals(1L, "SELECT COUNT(*) FROM jn_1 a NATURAL JOIN jn_2 b");
        assertQueryEquals(2, "SELECT a.id FROM jn_1 a NATURAL JOIN jn_2 b");
        assertQueryEquals("b", "SELECT a.v FROM jn_1 a NATURAL JOIN jn_2 b");
        // 输出列合并：id, v 各只出现一次（否则下面的 v 会是歧义列）
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM (SELECT * FROM jn_1 a NATURAL JOIN jn_2 b) t");
        assertQueryEquals("b",
                "SELECT v FROM (SELECT * FROM jn_1 a NATURAL JOIN jn_2 b) t");
    }

    @Test
    public void onVersusWhereForOuterJoin() {
        init();
        // 额外条件放在 ON 里：左表所有行都保留，不满足条件的行右侧补 NULL
        assertQueryEquals(3L,
                "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id AND b.v = 'c'");
        assertQueryNull("SELECT b.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id AND b.v = 'c'"
                + " WHERE a.id = 2");
        // 同样的条件放在 WHERE 里：NULL 扩展行会被过滤掉
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE b.v = 'c'");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id WHERE b.v IS NULL");
    }

    @Test
    public void threeTableJoins() {
        init();
        assertQueryEquals(2L, "SELECT COUNT(*) FROM jn_1 a JOIN jn_2 b ON a.id = b.id"
                + " JOIN jn_3 c ON b.id = c.id");
        // 链式 LEFT JOIN：左表三行都保留
        assertQueryEquals(3L, "SELECT COUNT(*) FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id"
                + " LEFT JOIN jn_3 c ON b.id = c.id");
        assertQueryNull("SELECT c.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id"
                + " LEFT JOIN jn_3 c ON b.id = c.id WHERE a.id = 1");
        assertQueryEquals(3, "SELECT c.id FROM jn_1 a LEFT JOIN jn_2 b ON a.id = b.id"
                + " LEFT JOIN jn_3 c ON b.id = c.id WHERE a.id = 3");
    }

    @Test
    public void crossJoinAndAntiJoin() {
        init();
        assertQueryEquals(9L, "SELECT COUNT(*) FROM jn_1 a CROSS JOIN jn_2 b");
        // 无 NULL 的 id 上，NOT IN 与 NOT EXISTS 等价
        assertQueryEquals(1L, "SELECT COUNT(*) FROM jn_1 a WHERE a.id NOT IN (SELECT id FROM jn_2)");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a WHERE NOT EXISTS (SELECT 1 FROM jn_2 b WHERE b.id = a.id)");
        assertQueryEquals(1, "SELECT a.id FROM jn_1 a WHERE a.id NOT IN (SELECT id FROM jn_2)");
    }

    @Test
    public void joinOnNullSafePredicate() {
        init();
        // IS NOT DISTINCT FROM 是 NULL 安全的等值比较，可以把 NULL 也连起来
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM jn_1 a JOIN jn_2 b ON a.v IS NOT DISTINCT FROM b.v AND a.id = 3");
        assertQueryEquals(0L,
                "SELECT COUNT(*) FROM jn_1 a JOIN jn_2 b ON a.v = b.v AND a.id = 3");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // BUG: 不支持 FULL OUTER JOIN，解析阶段就报 42000 语法错误。
    // 期望: 返回左右两边都保留、未匹配侧补 NULL 的结果（H2 支持 FULL OUTER JOIN）；实际: 语法错误。
    @Ignore("BUG: FULL OUTER JOIN is a syntax error (42000)")
    @Test
    public void fullOuterJoin() {
        init();
        assertQueryEquals(4L, "SELECT COUNT(*) FROM jn_1 a FULL OUTER JOIN jn_2 b ON a.id = b.id");
        assertQueryNull("SELECT a.id FROM jn_1 a FULL OUTER JOIN jn_2 b ON a.id = b.id WHERE b.id = 4");
    }
}
