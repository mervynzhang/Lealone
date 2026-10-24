/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * SQL标准的INFORMATION_SCHEMA.PARAMETERS/ROUTINES/ROUTINE_ROUTINE_USAGE视图。
 */
public class InformationSchemaRoutineTest extends SqlTestBase {

    public InformationSchemaRoutineTest() {
        setEmbedded(true);
    }

    @Test
    public void testParameters() throws Exception {
        executeUpdate("CREATE FUNCTION isr_f(a INT, b VARCHAR) RETURNS INT AS $$ SELECT a $$");
        executeUpdate(
                "CREATE PROCEDURE isr_p(a INT, OUT b INT, INOUT c INT) AS 'SELECT ?, ?'");

        executeQuery("SELECT p.ordinal_position, p.parameter_mode, p.parameter_name, p.data_type, "
                + "p.parameter_default FROM INFORMATION_SCHEMA.PARAMETERS p "
                + "JOIN INFORMATION_SCHEMA.ROUTINES r ON p.specific_name = r.specific_name "
                + "AND p.specific_schema = r.specific_schema "
                + "WHERE r.routine_name = 'ISR_P' ORDER BY p.ordinal_position");
        assertEquals(1, getIntValue(1));
        assertEquals("IN", getStringValue(2));
        assertEquals("A", getStringValue(3));
        assertEquals("INT", getStringValue(4));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals("OUT", rs.getString(2));
        assertEquals("B", rs.getString(3));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals("INOUT", rs.getString(2));
        assertEquals("C", rs.getString(3));
        // DEFAULT参数还在另一条线里实现，这一列现在必须是NULL而不是报错
        assertEquals(null, rs.getString(5));

        executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.PARAMETERS p "
                + "JOIN INFORMATION_SCHEMA.ROUTINES r ON p.specific_name = r.specific_name "
                + "AND p.specific_schema = r.specific_schema WHERE r.routine_name = 'ISR_F'");
        assertEquals(2, getIntValue(1));
    }

    @Test
    public void testRoutines() throws Exception {
        executeUpdate("CREATE FUNCTION isr_r(a INT) RETURNS INT IMMUTABLE AS $$ SELECT a $$");
        executeUpdate("CREATE PROCEDURE isr_q(a INT, OUT b INT) AS 'SELECT ?, ?'");

        executeQuery("SELECT routine_type, data_type, is_deterministic, security_type, "
                + "external_language FROM INFORMATION_SCHEMA.ROUTINES WHERE routine_name = 'ISR_R'");
        assertEquals("FUNCTION", getStringValue(1));
        assertEquals("INT", getStringValue(2));
        assertEquals("YES", getStringValue(3));
        assertEquals("INVOKER", getStringValue(4));
        assertEquals("sql", getStringValue(5));

        executeQuery("SELECT routine_type, data_type, is_deterministic, external_language "
                + "FROM INFORMATION_SCHEMA.ROUTINES WHERE routine_name = 'ISR_Q'");
        assertEquals("PROCEDURE", getStringValue(1));
        assertEquals(null, getStringValue(2));
        assertEquals("NO", getStringValue(3));
        assertEquals("sql", getStringValue(4));

        // 同名重载用specific_name区分
        executeUpdate("CREATE FUNCTION isr_r(a VARCHAR) RETURNS INT AS $$ SELECT 1 $$");
        executeQuery("SELECT count(DISTINCT specific_name) FROM INFORMATION_SCHEMA.ROUTINES "
                + "WHERE routine_name = 'ISR_R'");
        assertEquals(2, getIntValue(1));
    }

    @Test
    public void testRoutineRoutineUsageIsEmpty() throws Exception {
        executeUpdate("CREATE FUNCTION isr_u1() RETURNS INT AS $$ SELECT 1 $$");
        executeUpdate("CREATE FUNCTION isr_u2() RETURNS INT AS $$ SELECT isr_u1() $$");
        // 依赖关系还没有填充，但查询必须能执行并返回0行
        executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.ROUTINE_ROUTINE_USAGE");
        assertEquals(0, getIntValue(1));
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT rru.specific_name "
                        + "FROM INFORMATION_SCHEMA.ROUTINE_ROUTINE_USAGE rru "
                        + "JOIN INFORMATION_SCHEMA.ROUTINES r ON r.specific_name = rru.specific_name")) {
            assertFalse(rs.next());
        }
    }

    @Test
    public void testScriptAndRestart() throws Exception {
        executeUpdate("CREATE FUNCTION isr_s(a INT) RETURNS INT AS $$ SELECT a $$");
        StringBuilder script = new StringBuilder();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line.toUpperCase().contains("FUNCTION ISR_S(")) {
                    script.append(line).append('\n');
                }
                // INFORMATION_SCHEMA的伪表不能出现在SCRIPT里
                assertTrue(!line.toUpperCase().contains("INFORMATION_SCHEMA.PARAMETERS"));
                assertTrue(!line.toUpperCase().contains("INFORMATION_SCHEMA.ROUTINES"));
            }
        }
        assertTrue(script.length() > 0);

        String dir = newTestDir("info_routine_replay");
        String url = "jdbc:lealone:embed:" + dir + "/" + dir.substring(dir.lastIndexOf('/') + 1)
                + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(url)) {
            try (Statement s = conn2.createStatement()) {
                for (String line : script.toString().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        s.executeUpdate(line);
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.PARAMETERS "
                        + "WHERE specific_name LIKE 'ISR_S%'")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
        }

        // 重启后视图仍然能按元数据重建
        String restartDir = newTestDir("info_routine_restart");
        String restartUrl = "jdbc:lealone:embed:" + restartDir + "/"
                + restartDir.substring(restartDir.lastIndexOf('/') + 1) + ";user=root;password=";
        try (Connection conn2 = DriverManager.getConnection(restartUrl)) {
            try (Statement s = conn2.createStatement()) {
                s.executeUpdate("CREATE FUNCTION isr_t(a INT) RETURNS INT AS $$ SELECT a $$");
            }
        }
        try (Connection conn2 = DriverManager.getConnection(restartUrl)) {
            try (Statement s = conn2.createStatement();
                    ResultSet rs = s.executeQuery("SELECT count(*) FROM INFORMATION_SCHEMA.PARAMETERS "
                            + "WHERE specific_name LIKE 'ISR_T%'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private static String newTestDir(String name) {
        String dir = new File(TestBase.TEST_DIR, name + "_" + System.currentTimeMillis())
                .getAbsolutePath();
        FileUtils.deleteRecursive(dir, false);
        new File(dir).mkdirs();
        return dir;
    }
}
