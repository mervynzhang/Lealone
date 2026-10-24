/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 触发器函数里再改同一张表会无限递归，这里要求报明确的错误(带嵌套深度信息)，
 * 不能直接抛 StackOverflowError。
 */
public class TriggerRecursionTest extends SqlTestBase {

    public TriggerRecursionTest() {
        setEmbedded(true);
    }

    @Test
    public void testSelfTriggeringRecursion() throws Exception {
        executeUpdate("CREATE TABLE IF NOT EXISTS trt(id INT)");
        executeUpdate("DELETE FROM trt");
        executeUpdate("DROP FUNCTION IF EXISTS trt_f");
        executeUpdate("CREATE FUNCTION trt_f() RETURNS TRIGGER AS $$ "
                + "BEGIN INSERT INTO trt VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("DROP TRIGGER IF EXISTS trt_f1");
        executeUpdate("CREATE TRIGGER trt_f1 BEFORE INSERT ON trt FOR EACH ROW "
                + "EXECUTE FUNCTION trt_f()");
        Statement s = conn.createStatement();
        try {
            s.executeUpdate("INSERT INTO trt VALUES(1)");
            fail("自触发应该报递归深度超限");
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.GENERAL_ERROR_1);
            assertTrue(e.getMessage(), e.getMessage().contains("trigger recursion depth"));
        } finally {
            s.close();
        }
        // 递归失败后表里不能留下数据
        executeQuery("SELECT COUNT(*) FROM trt");
        assertEquals(0, getIntValue(1));
        executeUpdate("DROP TRIGGER trt_f1");
        executeUpdate("DROP FUNCTION trt_f");
    }
}
