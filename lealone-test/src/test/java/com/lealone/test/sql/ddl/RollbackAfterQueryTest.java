/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 事务里执行过一次查询之后，ROLLBACK 必须仍然撤销之前的 DML。
 * <p>
 * 这是一个真实的 P0（静默错数据），根因不在 MVCC/undo：插桩显示 rollback 确实执行了、
 * undo 记录也确实回滚了，**另一个会话**回滚后立刻就能看到数据已恢复，只有**本会话**还读到旧结果 ——
 * 因为会话的语句缓存复用了回滚前的语句/游标状态（实测同一条 {@code SELECT COUNT(*)} 被复用）。
 * 修复见 {@code ServerSession.rollback()}：事务有改动时清一次语句缓存。
 * <p>
 * 复现（去掉修复即失败：{@code ROLLBACK 后必须恢复成 2 行, 实际 1}）：
 * <pre>
 * conn.setAutoCommit(false);
 * DELETE FROM t WHERE id = 1;
 * SELECT COUNT(*) FROM t;   // 事务内查询一次
 * conn.rollback();
 * SELECT COUNT(*) FROM t;   // 必须是 2
 * </pre>
 */
public class RollbackAfterQueryTest extends TestBase {


    @Test
    public void rollbackMustUndoDmlEvenAfterQuery() throws Exception {
        try (Connection c = DriverManager.getConnection(getURL());
                Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS raq_t");
            s.executeUpdate("CREATE TABLE raq_t(id int primary key)");
            s.executeUpdate("INSERT INTO raq_t VALUES(1),(2)");
            c.setAutoCommit(false);
            s.executeUpdate("DELETE FROM raq_t WHERE id = 1");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM raq_t")) {
                rs.next();
                if (rs.getInt(1) != 1)
                    throw new AssertionError("事务内应该看到删除已生效, 实际 " + rs.getInt(1));
            }
            c.rollback();
            c.setAutoCommit(true);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM raq_t")) {
                rs.next();
                if (rs.getInt(1) != 2)
                    throw new AssertionError("ROLLBACK 后必须恢复成 2 行, 实际 " + rs.getInt(1));
            }
        }
    }
}
