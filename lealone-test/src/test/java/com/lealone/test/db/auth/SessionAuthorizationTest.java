/*
 * Copyright 2004-2024 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 */
package com.lealone.test.db.auth;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * SET/RESET SESSION AUTHORIZATION 的用例。
 */
public class SessionAuthorizationTest extends SqlTestBase {

    @Test
    public void run() throws Exception {
        executeQuery("SELECT CURRENT_USER");
        String loginUser = getStringValue(1);
        executeUpdate("CREATE USER IF NOT EXISTS sa_u1 PASSWORD 'p1'");
        try {
            // 切到另一个用户后 CURRENT_USER 跟着变
            executeUpdate("SET SESSION AUTHORIZATION sa_u1");
            executeQuery("SELECT CURRENT_USER");
            assertEquals("SA_U1", getStringValue(1));
            // 再切回来
            executeUpdate("SET SESSION AUTHORIZATION " + loginUser);
            executeQuery("SELECT CURRENT_USER");
            assertEquals(loginUser, getStringValue(1));

            // RESET 恢复成登录用户
            executeUpdate("SET SESSION AUTHORIZATION sa_u1");
            executeUpdate("RESET SESSION AUTHORIZATION");
            executeQuery("SELECT CURRENT_USER");
            assertEquals(loginUser, getStringValue(1));

            // 不存在的用户
            executeQueryThanAssertErrorCode("SET SESSION AUTHORIZATION sa_not_exist",
                    ErrorCode.USER_NOT_FOUND_1);
            // 语法错误要报语法错, 不能报别的
            executeQueryThanAssertErrorCode("RESET FOO", ErrorCode.SYNTAX_ERROR_2);
        } finally {
            executeUpdate("RESET SESSION AUTHORIZATION");
            executeUpdate("DROP USER IF EXISTS sa_u1");
        }
    }
}
