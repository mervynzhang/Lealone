/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 存储过程：CREATE PROCEDURE / CALL / DROP PROCEDURE。
 * <p>
 * 过程体是用分号分隔的SQL语句串，参数用 ? 按位置引用，整个过程是一条语句粒度的原子单元。
 */
public class StoredProcedureTest extends SqlTestBase {

    public StoredProcedureTest() {
        setEmbedded(true);
    }

    private void init() {
        executeUpdate("DROP PROCEDURE IF EXISTS sp_ins");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_multi");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_query");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_bad");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_txn");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_ddl");
        executeUpdate("DROP PROCEDURE IF EXISTS sp_ddl_bad");
        executeUpdate("DROP TABLE IF EXISTS sp_ddl_t");
        executeUpdate("DROP TABLE IF EXISTS sp_t");
        executeUpdate("CREATE TABLE sp_t(id int primary key, v int)");
    }

    @Test
    public void run() throws Exception {
        init();
        testMultiStatement();
        testParameter();
        testQueryBody();
        testAtomicity();
        testDdlInBody();
        testRejectUnsupported();
        testDrop();
        init();
    }

    private void testMultiStatement() throws Exception {
        executeUpdate("CREATE PROCEDURE sp_multi() AS "
                + "'INSERT INTO sp_t VALUES(1, 10); INSERT INTO sp_t VALUES(2, 20)'");
        sql = "CALL sp_multi()";
        assertEquals(2, getIntValue(1, true));
        sql = "SELECT COUNT(*) FROM sp_t";
        assertEquals(2, getIntValue(1, true));
    }

    private void testParameter() throws Exception {
        executeUpdate("CREATE PROCEDURE sp_ins(pk int, val int) AS 'INSERT INTO sp_t VALUES(?, ?)'");
        sql = "CALL sp_ins(3, 30)";
        assertEquals(1, getIntValue(1, true));
        // 过程体的写入对调用方可见
        sql = "SELECT v FROM sp_t WHERE id = 3";
        assertEquals(30, getIntValue(1, true));
    }

    private void testQueryBody() throws Exception {
        executeUpdate("CREATE PROCEDURE sp_query() AS 'SELECT id FROM sp_t ORDER BY id'");
        sql = "CALL sp_query()";
        executeQuery();
        assertEquals(1, getIntValue(1, false));
        assertTrue(next());
        assertEquals(2, getIntValue(1, false));
        assertTrue(next());
        assertEquals(3, getIntValue(1, true));
    }

    // 过程体里第二条语句失败时，第一条也要回滚
    private void testAtomicity() throws Exception {
        executeUpdate("CREATE PROCEDURE sp_bad() AS "
                + "'INSERT INTO sp_t VALUES(9, 90); INSERT INTO sp_t VALUES(1, 91)'");
        executeQueryThanAssertErrorCode("CALL sp_bad()", com.lealone.db.api.ErrorCode.DUPLICATE_KEY_1);
        sql = "SELECT COUNT(*) FROM sp_t WHERE id = 9";
        assertEquals(0, getIntValue(1, true));
    }

    // 过程体里可以执行DDL，建过程时就校验语法，引用不到的表推迟到CALL时校验
    private void testDdlInBody() throws Exception {
        executeUpdate("CREATE PROCEDURE sp_ddl() BEGIN ATOMIC "
                + "CREATE TABLE sp_ddl_t(id INT); INSERT INTO sp_ddl_t VALUES(1); END");
        executeUpdate("CALL sp_ddl()");
        executeQuery("SELECT count(*) FROM sp_ddl_t");
        assertEquals(1, getIntValue(1, true));
        // 过程体里的语法错误在建过程时就报出来
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE sp_ddl_bad() BEGIN ATOMIC SELEC 1; END",
                com.lealone.db.api.ErrorCode.SYNTAX_ERROR_2);
        // 表已经建过时CALL才报错
        try {
            executeUpdate("CALL sp_ddl()");
            fail();
        } catch (Exception e) {
            assertErrorCode(e, com.lealone.db.api.ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1);
        }
    }

    private void testRejectUnsupported() throws Exception {
        // 过程体里不允许事务语句
        executeUpdate("CREATE PROCEDURE sp_txn() AS 'COMMIT'");
        try {
            executeUpdate("CALL sp_txn()");
            fail();
        } catch (Exception e) {
            // FEATURE_NOT_SUPPORTED
        }
        executeUpdate("DROP PROCEDURE sp_txn");
        // 过程不能当函数在表达式里用
        try {
            executeUpdate("SELECT sp_ins(5, 50)");
            fail();
        } catch (Exception e) {
            assertErrorCode(e, com.lealone.db.api.ErrorCode.FUNCTION_NOT_FOUND_1);
        }
    }

    private void testDrop() throws Exception {
        executeUpdate("DROP PROCEDURE sp_ins");
        try {
            executeUpdate("CALL sp_ins(4, 40)");
            fail();
        } catch (Exception e) {
            // PROCEDURE_NOT_FOUND
        }
        // 不存在时 DROP IF EXISTS 不报错
        executeUpdate("DROP PROCEDURE IF EXISTS sp_ins");
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sp_t")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 3);
        }
    }
}
