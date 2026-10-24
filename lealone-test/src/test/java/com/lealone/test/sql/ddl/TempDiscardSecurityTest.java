/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * DISCARD TEMP 的级联删除只对admin生效。
 * 否则普通用户只要建一个和过程体里字符串同名的临时序列(或者临时表)，再执行DISCARD TEMP，
 * 就能把别人建的持久过程删掉(按名字匹配的级联删除无法分辨是不是真的依赖)。
 */
public class TempDiscardSecurityTest extends SqlTestBase {

    public TempDiscardSecurityTest() {
        setEmbedded(true);
    }

    @Test
    public void testNonAdminCannotDropPersistentRoutine() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS tds_p");
        executeUpdate("DROP FUNCTION IF EXISTS tds_f");
        executeUpdate("DROP USER IF EXISTS tds_u1");
        executeUpdate("CREATE USER tds_u1 PASSWORD 'p'");
        executeUpdate("CREATE PROCEDURE tds_p() AS 'SELECT nextval(''tds_seq'')'");
        executeUpdate("CREATE FUNCTION tds_f() RETURNS INT AS 'SELECT nextval(''tds_seq'')'");

        // 普通用户建一个同名临时序列，再DISCARD TEMP
        Connection c = getConnection("tds_u1", "p");
        Statement s = c.createStatement();
        s.executeUpdate("CREATE TEMP SEQUENCE tds_seq");
        s.executeUpdate("DISCARD TEMP");
        s.close();
        c.close();
        // getConnection(user,password)会改写TestBase里共享的连接参数，用完恢复成root
        getConnection("root", "").close();

        // 持久过程和函数都还在(调用时报临时序列不存在，而不是过程不存在)
        executeQueryThanAssertErrorCode("CALL tds_p()", ErrorCode.SEQUENCE_NOT_FOUND_1);
        executeQueryThanAssertErrorCode("SELECT tds_f()", ErrorCode.SEQUENCE_NOT_FOUND_1);

        executeUpdate("DROP PROCEDURE tds_p");
        executeUpdate("DROP FUNCTION tds_f");
        executeUpdate("DROP USER tds_u1");
    }

    // admin执行DISCARD TEMP仍然级联删掉引用临时对象的例程(既有行为不变)
    @Test
    public void testAdminCascadeStillWorks() throws Exception {
        executeUpdate("DROP FUNCTION IF EXISTS tds_g");
        executeUpdate("CREATE TEMP SEQUENCE tds_seq2");
        executeUpdate("CREATE FUNCTION tds_g() RETURNS INT AS 'SELECT nextval(''tds_seq2'')'");
        executeUpdate("DISCARD TEMP");
        executeQueryThanAssertErrorCode("SELECT tds_g()", ErrorCode.FUNCTION_NOT_FOUND_1);
    }
}
