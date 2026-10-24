/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.SQLException;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * PostgreSQL在CREATE阶段就拒绝"SQL过程体里调用带OUT/INOUT参数的过程"这种写法
 * (0A000 calling procedures with output arguments is not supported in SQL functions)，
 * Lealone 对齐成同样的错误码和错误信息；调用只有IN参数的过程不受影响。
 */
public class ProcedureNestedCallOutTest extends SqlAssertTestBase {

    @Test
    public void testNestedCallWithOutputParametersRejected() throws Exception {
        executeUpdate("CREATE OR REPLACE PROCEDURE pnc_p1(INOUT a INT, INOUT b INT) AS $$ SELECT 1, 2; $$");

        // 报告缺陷里的写法: 带OUT/INOUT参数的过程体里嵌套CALL
        SQLException e = assertSqlFails(
                "CREATE OR REPLACE PROCEDURE pnc_p2(INOUT b INT, INOUT a INT) AS $$"
                        + " CALL pnc_p1(?, ?) $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        assertTrue("error message must match PostgreSQL: " + e.getMessage(),
                e.getMessage().contains(
                        "calling procedures with output arguments is not supported in SQL functions"));

        // 调用方自己没有OUT参数, 过程体里调用带OUT参数的过程同样在CREATE阶段被拒绝
        assertSqlFails("CREATE OR REPLACE PROCEDURE pnc_p3(IN x INT) AS $$ CALL pnc_p1(?, ?); $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // CALL在过程体中间、最后一条是查询, 也一样
        assertSqlFails(
                "CREATE OR REPLACE PROCEDURE pnc_p4(INOUT a INT) AS $$ CALL pnc_p1(?, ?); SELECT 9 $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
        // plpgsql过程体不受影响(PG里plpgsql可以直接对INOUT变量做CALL)
        executeUpdate("CREATE OR REPLACE PROCEDURE pnc_p5(INOUT a INT) LANGUAGE plpgsql AS $$"
                + " BEGIN a := 1; END; $$");

        // 调用只有IN参数的过程仍然允许
        executeUpdate("CREATE OR REPLACE PROCEDURE pnc_p6(IN x INT) AS $$ SELECT x $$");
        executeUpdate("CREATE OR REPLACE PROCEDURE pnc_p7(IN y INT) AS $$"
                + " CALL pnc_p6(y); SELECT y $$");
        executeUpdate("DROP PROCEDURE pnc_p7");
        executeUpdate("DROP PROCEDURE pnc_p6");
        executeUpdate("DROP PROCEDURE pnc_p5");
        executeUpdate("DROP PROCEDURE pnc_p1");
    }
}
