/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * TRUNCATE 直接清空底层存储、不进 undo log，没法回滚。在未提交的事务里执行它会让会话进入
 * 不一致状态：实测 "TRUNCATE -> INSERT -> ROLLBACK" 之后，后续 INSERT 会被静默丢掉
 * （数据还在，再插入的行不生效，重新打开库也一样）。
 * 所以现在**明确拒绝**在未提交事务里执行 TRUNCATE，自动提交下照常可用。
 */
public class TruncateNotRollbackableTest extends SqlTestBase {

    public TruncateNotRollbackableTest() {
        setEmbedded(true);
    }

    @Test
    public void testTruncateIsNotRollbackable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS tnr_t");
        executeUpdate("CREATE TABLE tnr_t(id int primary key)");
        executeUpdate("INSERT INTO tnr_t VALUES(1)");
        executeUpdate("INSERT INTO tnr_t VALUES(2)");
        assertEquals(2, count());

        // 未提交事务里执行 TRUNCATE 必须被明确拒绝(它不可回滚, 否则会静默丢数据)
        conn.setAutoCommit(false);
        boolean rejected = false;
        try {
            executeUpdate("TRUNCATE TABLE tnr_t");
        } catch (Exception e) {
            rejected = true;
            assertTrue("错误信息要说清原因: " + e.getMessage(), e.getMessage().contains("rollback"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
        assertTrue("事务里执行 TRUNCATE 必须报错", rejected);
        assertEquals("被拒绝后数据必须完好", 2, count());

        // 拒绝之后会话仍然正常: 插入与回滚都不受影响
        executeUpdate("INSERT INTO tnr_t VALUES(3)");
        assertEquals(3, count());
        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO tnr_t VALUES(4)");
        conn.rollback();
        conn.setAutoCommit(true);
        assertEquals("事务回滚仍然正常", 3, count());

        // 自动提交下照常可用
        executeUpdate("TRUNCATE TABLE tnr_t");
        assertEquals("自动提交下 TRUNCATE 正常生效", 0, count());
    }

    private int count() throws Exception {
        executeQuery("SELECT COUNT(*) FROM tnr_t");
        try {
            return rs.getInt(1);
        } finally {
            closeResultSet();
        }
    }
}
