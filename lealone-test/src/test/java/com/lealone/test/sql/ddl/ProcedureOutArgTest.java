/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * PostgreSQL里OUT位置的实参只占位、不求值，所以CALL p(1/0)不会报除零错误；
 * INOUT位置的实参仍然要求值。
 */
public class ProcedureOutArgTest extends SqlAssertTestBase {

    // OUT位置的实参不求值
    @Test
    public void testOutArgumentNotEvaluated() throws Exception {
        tryExecuteUpdate("DROP TABLE IF EXISTS poa_t");
        executeUpdate("CREATE TABLE poa_t(id INT, t VARCHAR(10))");
        executeUpdate("CREATE OR REPLACE PROCEDURE poa_p1(OUT a INT) AS $$"
                + " INSERT INTO poa_t VALUES(1, 'a'); SELECT 1; $$");

        // NULL占位
        assertEquals(1, queryInt("CALL poa_p1(NULL)"));
        // OUT位置的实参不求值: 1/0不会报错
        assertEquals(1, queryInt("CALL poa_p1(1/0)"));
        // 实参个数也可以省略OUT参数
        assertEquals(1, queryInt("CALL poa_p1()"));
        // IN + OUT混合时, OUT位置同样不求值
        executeUpdate("CREATE OR REPLACE PROCEDURE poa_p2(IN x INT, OUT y INT) AS $$ SELECT x + 1; $$");
        assertEquals(2, queryInt("CALL poa_p2(4)"));
        assertEquals(2, queryInt("CALL poa_p2(4, 1/0)"));
        executeUpdate("DROP PROCEDURE poa_p1");
        executeUpdate("DROP PROCEDURE poa_p2");
    }

    // INOUT位置的实参仍然要求值
    @Test
    public void testInoutArgumentEvaluated() throws Exception {
        executeUpdate("CREATE OR REPLACE PROCEDURE poa_p3(INOUT a INT) AS $$ SELECT 7; $$");
        assertEquals(7, queryInt("CALL poa_p3(3)"));
        assertSqlFails("CALL poa_p3(1/0)", ErrorCode.DIVISION_BY_ZERO_1);
        executeUpdate("DROP PROCEDURE poa_p3");
    }

    private int queryInt(String sql) throws Exception {
        Object o = queryObject(sql);
        return ((Number) o).intValue();
    }
}
