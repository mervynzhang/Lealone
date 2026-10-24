/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.dml;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * session级的查询结果缓存(QueryResultCache)用Database的modificationDataId判断结果是否过期。
 * 显式事务里先执行过查询、再回滚时，回滚也必须让缓存失效，
 * 否则回滚之后的同一条SQL(比如COUNT(*))还会返回回滚前的旧结果，看起来像"回滚没生效"。
 */
public class QueryResultCacheTest extends SqlTestBase {

    public QueryResultCacheTest() {
        setEmbedded(true);
    }

    // 回滚前缓存过的COUNT(*)必须失效
    @Test
    public void testCountAfterRollback() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS qrc_t");
        executeUpdate("CREATE TABLE qrc_t(id INT PRIMARY KEY)");

        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO qrc_t VALUES(1)");
        // 事务里先查一次，建立session的查询结果缓存
        executeQuery("SELECT COUNT(*) FROM qrc_t");
        assertEquals(1, getIntValue(1, true));
        conn.rollback();
        conn.setAutoCommit(true);
        // 同一条SQL必须反映回滚后的结果
        executeQuery("SELECT COUNT(*) FROM qrc_t");
        assertEquals(0, getIntValue(1, true));
        // 新session里也是一样
        assertEquals(0, countInNewSession());
    }

    // 回滚前缓存过的普通查询结果也必须失效
    @Test
    public void testSelectAfterRollback() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS qrc_t2");
        executeUpdate("CREATE TABLE qrc_t2(id INT PRIMARY KEY)");

        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO qrc_t2 VALUES(1)");
        executeUpdate("INSERT INTO qrc_t2 VALUES(2)");
        executeQuery("SELECT id FROM qrc_t2 ORDER BY id");
        assertEquals(1, getIntValue(1, true));
        conn.rollback();
        conn.setAutoCommit(true);
        // 回滚之后不应该再看到缓存的两行
        executeQuery("SELECT COUNT(*) FROM qrc_t2");
        assertEquals(0, getIntValue(1, true));
        // 同一条查询(命中缓存)不能再返回回滚掉的两行
        executeQuery("SELECT id FROM qrc_t2 ORDER BY id");
        assertFalse("cached rows must be invalidated after rollback", rs.next());
        rs.close();
        assertEquals(0, countInNewSession("qrc_t2"));
    }

    // 提交之后缓存同样要失效，不能只处理回滚
    @Test
    public void testCommitInvalidatesCache() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS qrc_t3");
        executeUpdate("CREATE TABLE qrc_t3(id INT PRIMARY KEY)");
        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO qrc_t3 VALUES(1)");
        executeQuery("SELECT COUNT(*) FROM qrc_t3");
        assertEquals(1, getIntValue(1, true));
        conn.commit();
        conn.setAutoCommit(true);
        executeUpdate("INSERT INTO qrc_t3 VALUES(2)");
        executeQuery("SELECT COUNT(*) FROM qrc_t3");
        assertEquals(2, getIntValue(1, true));
    }

    private int countInNewSession() throws Exception {
        return countInNewSession("qrc_t");
    }

    private int countInNewSession(String tableName) throws Exception {
        String url = "jdbc:lealone:embed:" + TestBase.TEST_DIR + "/" + TestBase.DEFAULT_DB_NAME
                + ";user=root;password=";
        try (Connection c = java.sql.DriverManager.getConnection(url);
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            r.next();
            return r.getInt(1);
        }
    }
}
