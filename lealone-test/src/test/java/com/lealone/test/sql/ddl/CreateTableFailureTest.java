/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 建表失败时的清理只能影响失败的那张表：同一条语句在 schema.add 之后失败时，
 * 失败路径要按同一个 lock 做即时删除，不能把同一事务里之前创建的对象一起回滚掉。
 */
public class CreateTableFailureTest extends SqlTestBase {

    private void initTables() {
        executeUpdate("DROP TABLE IF EXISTS ctf_keep");
        executeUpdate("DROP TABLE IF EXISTS ctf_keep2");
        executeUpdate("DROP TABLE IF EXISTS ctf_bad");
    }

    // 直接查表判断存在性: INFORMATION_SCHEMA 里的名字是大写的, 按小写字面量比较匹配不到
    private boolean tableExists(String name) {
        try {
            executeQuery("SELECT COUNT(*) FROM " + name);
            closeResultSet();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 自动提交: 先建表成功, 再让一条建表语句失败, 前一张表必须还在
    @Test
    public void testFailedCreateKeepsPreviousTable() throws Exception {
        initTables();
        executeUpdate("CREATE TABLE ctf_keep(id int primary key)");
        try {
            // 主键重复导致的失败发生在建表语句内部
            executeUpdate("CREATE TABLE ctf_bad(id int primary key, id int)");
            fail("expected create table failure");
        } catch (Exception e) {
            // expected
        }
        assertTrue("失败语句不能把同一事务里之前创建的表回滚掉", tableExists("ctf_keep"));
        assertFalse(tableExists("ctf_bad"));
    }

    // 显式事务里回滚: 事务中创建的表要消失, 事务之前就存在的表必须还在
    @Test
    public void testRollbackAfterFailedCreateInTransaction() throws Exception {
        initTables();
        executeUpdate("CREATE TABLE ctf_keep2(id int primary key)");

        conn.setAutoCommit(false);
        try {
            executeUpdate("CREATE TABLE ctf_tx_ok(id int primary key)");
            try {
                executeUpdate("CREATE TABLE ctf_bad(id int primary key, id int)");
                fail("expected create table failure");
            } catch (Exception e) {
                // expected
            }
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
        }
        assertFalse("事务里创建的表应该被回滚掉", tableExists("ctf_tx_ok"));
        assertTrue("事务之前创建的表不能被回滚掉", tableExists("ctf_keep2"));
        // 回滚之后还能继续正常建表
        executeUpdate("CREATE TABLE ctf_bad(id int primary key)");
        assertTrue(tableExists("ctf_bad"));
    }
}
