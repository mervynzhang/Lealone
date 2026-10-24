/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.index;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 同一事务里"插入又删除"同一个 key：索引项必须被真正清掉，不能留下墓碑或孤儿项。
 * <p>
 * 这条路径会走到 AOTransactionMap.removeUncommittedInsert：它把本事务插入的索引项物理删除，
 * 并在删除前判断 lockable 是否已经关联到页(页操作可能还没执行完，那时什么都没插进去)。
 * 用例锁定的是行为：插删之后表为空、走索引也查不到；同 key 再插入并提交后只有一行。
 */
public class IndexInsertDeleteSameTransactionTest extends SqlTestBase {

    private void initTables() {
        executeUpdate("DROP TABLE IF EXISTS iidt_t");
        executeUpdate("CREATE TABLE iidt_t(id int primary key, a int, b int)");
        executeUpdate("CREATE UNIQUE INDEX iidt_t_uni ON iidt_t(b)");
        executeUpdate("CREATE INDEX iidt_t_a ON iidt_t(a)");
    }

    private int count(String sql) throws Exception {
        executeQuery(sql);
        try {
            return rs.getInt(1);
        } finally {
            closeResultSet();
        }
    }

    @Test
    public void testInsertThenDeleteInSameTransaction() throws Exception {
        initTables();
        getConnection().setAutoCommit(false);

        for (int i = 1; i <= 100; i++) {
            executeUpdate("INSERT INTO iidt_t(id, a, b) VALUES(" + i + ", " + (i % 10) + ", " + i + ")");
            executeUpdate("DELETE FROM iidt_t WHERE id = " + i);
            getConnection().commit();
        }
        getConnection().setAutoCommit(true);

        assertEquals(0, count("SELECT COUNT(*) FROM iidt_t"));
        // 走唯一索引和二级索引都查不到残留项
        assertEquals(0, count("SELECT COUNT(*) FROM iidt_t WHERE b = 50"));
        assertEquals(0, count("SELECT COUNT(*) FROM iidt_t WHERE a = 0"));
    }

    @Test
    public void testDeleteThenInsertSameKeyInSameTransaction() throws Exception {
        initTables();
        getConnection().setAutoCommit(false);
        executeUpdate("INSERT INTO iidt_t(id, a, b) VALUES(1, 1, 1)");
        executeUpdate("DELETE FROM iidt_t WHERE id = 1");
        // 同一个 key 再插回来，提交后必须只有一行，并且索引能查到
        executeUpdate("INSERT INTO iidt_t(id, a, b) VALUES(1, 2, 2)");
        getConnection().commit();
        getConnection().setAutoCommit(true);

        assertEquals(1, count("SELECT COUNT(*) FROM iidt_t"));
        assertEquals(1, count("SELECT COUNT(*) FROM iidt_t WHERE b = 2"));
        assertEquals(1, count("SELECT COUNT(*) FROM iidt_t WHERE a = 2"));
        assertEquals(0, count("SELECT COUNT(*) FROM iidt_t WHERE b = 1"));
    }
}
