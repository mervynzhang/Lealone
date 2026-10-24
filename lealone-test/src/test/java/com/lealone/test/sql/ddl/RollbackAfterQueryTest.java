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

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 已知 P0 缺陷（FIX_LOG #51/#52 的共同根因，已收敛成最小复现）：
 * **事务里执行一次查询之后，ROLLBACK 就不再撤销之前的 DML。**
 * <p>
 * 最小复现（去掉 {@code @Ignore} 即可看到失败）：
 * <pre>
 * conn.setAutoCommit(false);
 * DELETE FROM t WHERE id = 1;
 * SELECT COUNT(*) FROM t;   // 事务内查询一次
 * conn.rollback();          // 删除没有被撤销, COUNT(*) 仍然是 1
 * </pre>
 * 不查询、直接 rollback 是正常的；一旦中间查一次就丢。
 * <p>
 * 本轮用插桩确认了两点：
 * <ol>
 * <li>{@code UndoLog.rollbackTo/commit} 在整个过程中**一次都没被调用** —— 不是 undo 记录被提前清掉；</li>
 * <li>{@code ServerSession.rollback()} 也没有被调用 —— ROLLBACK 根本没走到引擎的事务回滚逻辑。</li>
 * </ol>
 * 另外这两点只在测试用的连接参数下出现（{@code NET_FACTORY_NAME=bio} + {@code NETWORK_TIMEOUT}），
 * 直接用默认参数连 embed 不复现 —— 所以下一步要查客户端 ROLLBACK 的发送/处理链路
 * （{@code JdbcConnection.rollback()} 的 packet 路径与协议版本判断），而不是 MVCC/undo。
 */
public class RollbackAfterQueryTest extends TestBase {




    @Ignore("FIX_LOG #52: 未修复的已知缺陷(事务内查询后 ROLLBACK 不生效)")
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
