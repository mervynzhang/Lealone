/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db;

import org.junit.Test;

import com.lealone.db.Database;
import com.lealone.db.LealoneDatabase;
import com.lealone.db.session.ServerSession;
import com.lealone.test.sql.SqlTestBase;

/**
 * 库是否"可以自动关闭"只能看真正的用户连接。
 * 引擎内部session(表分析、异步索引操作)如果也算进去，只要后台干过一次活，
 * 这个库就再也不会自动关闭：存储一直开着、文件一直被占着。
 */
public class DatabaseCloseIfNeededTest extends SqlTestBase {

    private static final String DB = "db_close_if_needed";

    public DatabaseCloseIfNeededTest() {
        super(DB);
        setEmbedded(true);
    }

    @Test
    public void testInternalSessionDoesNotBlockClose() throws Exception {
        executeUpdate("SET DB_CLOSE_DELAY 0");
        executeUpdate("DROP TABLE IF EXISTS cin_t");
        executeUpdate("CREATE TABLE cin_t(id int primary key)");
        executeUpdate("INSERT INTO cin_t VALUES(1)");

        Database db = findDatabase();
        assertNotNull(db);
        // 模拟引擎内部session: 不带ConnectionInfo
        ServerSession internal = db.createSession(db.getSystemUser());
        try {
            conn.close();
            assertTrue("只剩内部session时, 库应该可以自动关闭", db.closeIfNeeded());
        } finally {
            try {
                internal.close();
            } catch (Exception e) {
                // 库已经关了, 忽略
            }
        }
    }

    private Database findDatabase() {
        for (Database d : LealoneDatabase.getInstance().getDatabases()) {
            if (d.getShortName().equalsIgnoreCase(DB))
                return d;
        }
        fail("找不到测试用的库");
        return null;
    }
}
