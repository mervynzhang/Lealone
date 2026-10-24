/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.dml;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

public class CallTest extends SqlTestBase {
    @Test
    public void run() throws Exception {
        executeUpdate("drop table IF EXISTS CallTest");
        executeUpdate("create table IF NOT EXISTS CallTest(id int, name varchar(500), b boolean)");

        executeUpdate("insert into CallTest(id, name, b) values(1, 'a1', true)");
        executeUpdate("insert into CallTest(id, name, b) values(2, 'a2', false)");
        executeUpdate("insert into CallTest(id, name, b) values(3, 'a3', false)");

        sql = "CALL select * from CallTest where id=1";
        sql = "CALL TABLE(ID INT=(1, 2), NAME VARCHAR=('Hello', 'World'))";
        printResultSet();

        // CALL只调过程, 函数要放在SELECT里调用(和PostgreSQL一致, Lealone报90153)
        executeQueryThanAssertErrorCode("CALL CURTIME()",
                com.lealone.db.api.ErrorCode.NOT_A_PROCEDURE_1);
        executeQueryThanAssertErrorCode("CALL ABS(-100)",
                com.lealone.db.api.ErrorCode.NOT_A_PROCEDURE_1);

        sql = "SELECT ABS(-100)";
        executeQuery(sql);
        assertEquals(100, getIntValue(1, true));
    }
}
