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

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 临时序列、DISCARD TEMP和SET search_path语法。
 */
public class TempObjectTest extends SqlTestBase {

    public TempObjectTest() {
        setEmbedded(true);
    }

    @Test
    public void testTempSequenceSessionIsolation() throws Exception {
        executeUpdate("CREATE TEMP SEQUENCE temp_seq1");
        executeQuery("SELECT nextval('temp_seq1')");
        assertEquals(1, getIntValue(1));
        executeQuery("SELECT nextval('temp_seq1')");
        assertEquals(2, getIntValue(1));

        // 另一个会话看不到当前会话的临时序列
        try (Connection c2 = DriverManager.getConnection(getURL());
                Statement s2 = c2.createStatement()) {
            try (ResultSet rs = s2.executeQuery("SELECT nextval('temp_seq1')")) {
                fail("临时序列不应该对别的会话可见");
            } catch (Exception e) {
                assertErrorCode(e, ErrorCode.SEQUENCE_NOT_FOUND_1);
            }
        }

        // 会话关掉后临时序列自动清理
        String dir = newTestDir("temp_seq_close");
        String url = "jdbc:lealone:embed:" + dir + "/" + dir.substring(dir.lastIndexOf('/') + 1)
                + ";user=root;password=";
        try (Connection c2 = DriverManager.getConnection(url); Statement s2 = c2.createStatement()) {
            s2.executeUpdate("CREATE TEMP SEQUENCE temp_seq2");
            try (ResultSet rs = s2.executeQuery("SELECT nextval('temp_seq2')")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        try (Connection c2 = DriverManager.getConnection(url); Statement s2 = c2.createStatement()) {
            try (ResultSet rs = s2.executeQuery("SELECT nextval('temp_seq2')")) {
                fail("会话结束后临时序列应该已经清理");
            } catch (Exception e) {
                assertErrorCode(e, ErrorCode.SEQUENCE_NOT_FOUND_1);
            }
        }
    }

    @Test
    public void testDiscardTempDropsDependentRoutine() throws Exception {
        executeUpdate("CREATE TEMP SEQUENCE temp_seq3");
        executeUpdate("CREATE FUNCTION temp_func1() RETURNS INT AS $$ SELECT nextval('temp_seq3') $$");
        // 临时对象不能进SCRIPT
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String line = rs.getString(1).toUpperCase();
                // 函数体里会出现临时序列的名字，但SCRIPT里不能有临时序列的CREATE语句
                assertFalse(line.startsWith("CREATE TEMPORARY SEQUENCE") && line.contains("TEMP_SEQ3"));
                assertFalse(line.startsWith("CREATE SEQUENCE") && line.contains("TEMP_SEQ3"));
            }
        }

        executeUpdate("DISCARD TEMP");
        executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.ROUTINES "
                + "WHERE routine_name = 'TEMP_FUNC1'");
        assertEquals(0, getIntValue(1));
        executeQueryThanAssertErrorCode("SELECT nextval('temp_seq3')",
                ErrorCode.SEQUENCE_NOT_FOUND_1);
    }

    @Test
    public void testSearchPathAlias() throws Exception {
        executeUpdate("CREATE SCHEMA search_path_test");
        executeUpdate("CREATE TABLE search_path_test.sp_table(a INT)");
        executeUpdate("INSERT INTO search_path_test.sp_table VALUES (1)");
        executeUpdate("CREATE FUNCTION search_path_test.sp_func() RETURNS INT "
                + "AS $$ SELECT 2 $$");
        executeUpdate("SET search_path TO search_path_test, public");
        executeQuery("SELECT sp_func()");
        assertEquals(2, getIntValue(1));
        executeQuery("SELECT a FROM sp_table");
        assertEquals(1, getIntValue(1));

        executeUpdate("SET search_path TO public");
        executeQueryThanAssertErrorCode("SELECT sp_func()", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    @Test
    public void testCreateUserWithoutPassword() throws Exception {
        executeUpdate("CREATE USER temp_nopass_user");
        executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.USERS "
                + "WHERE NAME = 'TEMP_NOPASS_USER'");
        assertEquals(1, getIntValue(1));
        executeUpdate("DROP USER temp_nopass_user");
    }

    private static String newTestDir(String name) {
        String dir = new java.io.File(com.lealone.test.TestBase.TEST_DIR,
                name + "_" + System.currentTimeMillis()).getAbsolutePath();
        com.lealone.storage.fs.FileUtils.deleteRecursive(dir, false);
        new java.io.File(dir).mkdirs();
        return dir;
    }
}
