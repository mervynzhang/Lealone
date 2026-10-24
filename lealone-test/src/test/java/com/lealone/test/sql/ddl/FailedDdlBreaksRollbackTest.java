/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 已知缺陷（FIX_LOG #52）：一次**失败的** {@code CREATE TRIGGER} 之后，同一连接上的事务回滚静默失效。
 * <p>
 * 复现步骤（已用独立探针确认，包含"删除未提交"的对照）：
 * <pre>
 * CREATE TABLE m(id int primary key); INSERT INTO m VALUES(1);
 * CREATE TRIGGER trg BEFORE DELETE ON m FOR EACH ROW CALL "NoSuchClass";  -- 必然失败
 * BEGIN; DELETE FROM m WHERE id = 1;
 *   -- 另一个连接仍能看到 1 行，说明删除确实在事务里、没有提交
 * ROLLBACK;
 *   -- 期望恢复成 1 行；实际是 0 行 —— undo 记录已经没了
 * </pre>
 * 对照：同样流程换成"失败的 CREATE TABLE"或普通 DML，回滚都是正常的，所以问题出在
 * {@code CreateTrigger.update()} 的失败路径（`TriggerObject.setTriggerClassName` 抛异常，
 * 位于 `lock.addHandler(...)` 与 `schema.add(...)` 之前）。
 * <p>
 * 未修复，先标记 {@code @Ignore}；修复后去掉即可作为回归用例。
 */
public class FailedDdlBreaksRollbackTest extends SqlTestBase {

    public FailedDdlBreaksRollbackTest() {
        setEmbedded(true);
    }



    @Ignore("FIX_LOG #52: 未修复的已知缺陷(失败的CREATE TRIGGER之后事务回滚失效)")
    @Test
    public void testRollbackStillWorksAfterFailedDdl() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS fdb_m");
        executeUpdate("CREATE TABLE fdb_m(id int primary key)");
        executeUpdate("INSERT INTO fdb_m VALUES(1)");
        try {
            executeUpdate("CREATE TRIGGER fdb_trg BEFORE DELETE ON fdb_m FOR EACH ROW"
                    + " CALL \"NoSuchClassForTest\"");
            fail("建触发器应该失败");
        } catch (Exception e) {
            // 预期失败
        }

        conn.setAutoCommit(false);
        try {
            executeUpdate("DELETE FROM fdb_m WHERE id = 1");
            assertEquals("删除应该在事务内生效", 0, count());
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
        assertEquals("回滚后数据必须恢复", 1, count());

        // 后续事务也应正常
        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO fdb_m VALUES(2)");
        conn.rollback();
        conn.setAutoCommit(true);
        assertEquals("后续事务回滚仍然正常", 1, count());
    }

    private int count() throws Exception {
        executeQuery("SELECT COUNT(*) FROM fdb_m");
        try {
            return rs.getInt(1);
        } finally {
            closeResultSet();
        }
    }
}
