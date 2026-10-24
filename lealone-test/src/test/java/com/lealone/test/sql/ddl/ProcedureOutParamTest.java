/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 存储过程的OUT/INOUT参数：CALL返回一行，OUT/INOUT的值来自过程体最后一条查询的第一行。
 */
public class ProcedureOutParamTest extends SqlTestBase {

    public ProcedureOutParamTest() {
        setEmbedded(true);
    }

    private void init() {
        executeUpdate("DROP PROCEDURE IF EXISTS pou1");
        executeUpdate("DROP PROCEDURE IF EXISTS pou2");
        executeUpdate("DROP PROCEDURE IF EXISTS pou3");
        executeUpdate("DROP PROCEDURE IF EXISTS pou4");
        executeUpdate("DROP PROCEDURE IF EXISTS pou5");
        executeUpdate("DROP PROCEDURE IF EXISTS pou6");
        executeUpdate("DROP PROCEDURE IF EXISTS pou3b");
        executeUpdate("DROP TABLE IF EXISTS pou_t");
        executeUpdate("CREATE TABLE pou_t(id int primary key, name varchar(20))");
        executeUpdate("INSERT INTO pou_t VALUES(1, 'a')");
    }

    @Test
    public void run() throws Exception {
        init();
        testOutAndInout();
        testOutWithInAfterIt();
        testOutOnlyAndUnnamed();
        testInOnlyUnchanged();
        testOutRequiresTrailingQuery();
        testLanguage();
        testScriptRoundTrip();
        init();
    }

    // 同一个过程里既有IN、OUT也有INOUT，OUT/INOUT的值来自最后一条查询
    private void testOutAndInout() throws Exception {
        executeUpdate("CREATE PROCEDURE pou1(IN x INT, OUT y INT, INOUT z VARCHAR) "
                + "AS 'SELECT ? * 2, ? || ''!'''");
        // 只给入参(IN/INOUT)
        executeQuery("CALL pou1(3, 'a')");
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(2, md.getColumnCount());
        assertEquals("Y", md.getColumnLabel(1));
        assertEquals("Z", md.getColumnLabel(2));
        assertEquals(6, getIntValue(1, false));
        assertEquals("a!", getStringValue(2, true));

        // PostgreSQL写法：给OUT参数写NULL占位
        executeQuery("CALL pou1(4, NULL, 'b')");
        assertEquals(8, getIntValue(1, false));
        assertEquals("b!", getStringValue(2, true));
    }

    // OUT参数后面还有IN参数，实参按入参顺序对应
    private void testOutWithInAfterIt() throws Exception {
        executeUpdate("CREATE PROCEDURE pou2(OUT a INT, IN b INT, IN c INT) AS 'SELECT ? - ?'");
        executeQuery("CALL pou2(7, 4)");
        assertEquals(3, getIntValue(1, true));
        executeQuery("CALL pou2(NULL, 8, 2)");
        assertEquals(6, getIntValue(1, true));
    }

    // 单个OUT参数也返回一行; OUT参数可以不写名字
    private void testOutOnlyAndUnnamed() throws Exception {
        executeUpdate("CREATE PROCEDURE pou3(OUT a INT) "
                + "AS 'INSERT INTO pou_t VALUES(2, ''b''); SELECT 1'");
        executeQuery("CALL pou3(NULL)");
        assertEquals(1, getIntValue(1, true));
        // 过程体的写入对调用方可见
        executeQuery("SELECT COUNT(*) FROM pou_t");
        assertEquals(2, getIntValue(1, true));

        executeUpdate("CREATE PROCEDURE pou3b(OUT a INT) AS 'SELECT 2'");
        executeQuery("CALL pou3b()");
        assertEquals(2, getIntValue(1, true));

        executeUpdate("CREATE PROCEDURE pou4(OUT INT) AS 'SELECT 7'");
        executeQuery("CALL pou4()");
        assertEquals(7, getIntValue(1, true));

        executeUpdate("CREATE PROCEDURE pou5(INOUT a INT) AS 'SELECT ? + 1'");
        executeQuery("CALL pou5(1)");
        assertEquals(2, getIntValue(1, true));
    }

    // 只有IN参数的过程行为和以前一样: 更新语句返回更新数
    private void testInOnlyUnchanged() throws Exception {
        executeUpdate("CREATE PROCEDURE pou6(IN x INT) AS 'INSERT INTO pou_t VALUES(?, ''x'')'");
        executeUpdate("CALL pou6(9)");
        executeQuery("SELECT COUNT(*) FROM pou_t WHERE id = 9");
        assertEquals(1, getIntValue(1, true));
    }

    // 有OUT/INOUT参数时，过程体最后一条语句必须是查询，否则建过程时就报错
    private void testOutRequiresTrailingQuery() throws Exception {
        executeUpdateThanAssertErrorCode(
                "CREATE PROCEDURE bad1(OUT a INT) AS 'INSERT INTO pou_t VALUES(3, ''c'')'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // 查询在中间、最后一条是更新语句也不行
        executeUpdateThanAssertErrorCode(
                "CREATE PROCEDURE bad2(OUT a INT) AS 'SELECT 2; INSERT INTO pou_t VALUES(5, ''e'')'",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // LANGUAGE子句: SQL可以用，PL/pgSQL的过程体不支持
    private void testLanguage() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS pou_lang");
        executeUpdate("CREATE PROCEDURE pou_lang(OUT a INT) LANGUAGE SQL AS 'SELECT 42'");
        executeQuery("CALL pou_lang()");
        assertEquals(42, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE pou_lang");

        executeUpdateThanAssertErrorCode(
                "CREATE PROCEDURE bad4(IN x INT) LANGUAGE plpgsql "
                        + "AS $$ BEGIN INSERT INTO pou_t VALUES(x, 'p'); END $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // 带OUT/INOUT参数的过程要能写进SCRIPT再回放，关库重开后也要能用
    private void testScriptRoundTrip() throws Exception {
        String ddl = getCreateProcedureSql("POU1");
        assertNotNull(ddl);
        assertTrue(ddl, ddl.contains("OUT Y"));
        assertTrue(ddl, ddl.contains("INOUT Z"));
        executeUpdate("DROP PROCEDURE pou1");
        executeUpdate(ddl);
        executeQuery("CALL pou1(5, 'c')");
        assertEquals(10, getIntValue(1, false));
        assertEquals("c!", getStringValue(2, true));

        // 关库重开
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        executeQuery("CALL pou1(6, 'd')");
        assertEquals(12, getIntValue(1, false));
        assertEquals("d!", getStringValue(2, true));
        executeQuery("CALL pou2(9, 1)");
        assertEquals(8, getIntValue(1, true));
    }

    private String getCreateProcedureSql(String procedureName) {
        String prefix = "CREATE PROCEDURE PUBLIC." + procedureName + "(";
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql != null && sql.startsWith(prefix)) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
