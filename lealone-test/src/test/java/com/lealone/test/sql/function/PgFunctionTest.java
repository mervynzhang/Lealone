/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL风格的存储函数(用SQL查询语句作为函数体)。
 */
public class PgFunctionTest extends SqlTestBase {

    public PgFunctionTest() {
        setEmbedded(true);
    }

    // 基本定义 + 调用、参数按名字引用、字符串字面量不被替换、IMMUTABLE
    @Test
    public void testCreateAndCall() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION add_one(a INT) RETURNS INT LANGUAGE SQL IMMUTABLE "
                + "AS $$ SELECT a + 1 $$");
        executeQuery("SELECT add_one(1)");
        assertEquals(2, getIntValue(1));

        // 同一个参数在函数体里出现多次
        executeUpdate(
                "CREATE OR REPLACE FUNCTION twice(a INT) RETURNS INT AS $$ SELECT a * 10 + a $$");
        executeQuery("SELECT twice(2)");
        assertEquals(22, getIntValue(1));

        // 没加双引号的参数名大小写不敏感
        executeUpdate(
                "CREATE OR REPLACE FUNCTION upper_param(A INT) RETURNS INT AS $$ SELECT a * 3 $$");
        executeQuery("SELECT upper_param(2)");
        assertEquals(6, getIntValue(1));

        // 参数名只出现在字符串字面量里时不能被替换
        executeUpdate("CREATE OR REPLACE FUNCTION only_literal(a INT) RETURNS VARCHAR AS "
                + "$$ SELECT 'a ? a' $$");
        executeQuery("SELECT only_literal(9)");
        assertEquals("a ? a", getStringValue(1));

        // 没有行时返回NULL
        executeUpdate("CREATE OR REPLACE FUNCTION find_name(a INT) RETURNS VARCHAR AS "
                + "$$ SELECT name FROM pgf_user WHERE id = a $$");
        executeQuery("SELECT find_name(1), find_name(99)");
        assertEquals("alice", getStringValue(1));
        assertNull(getStringValue(2));

        // VALUES/TABLE 也能作为函数体
        executeUpdate("CREATE OR REPLACE FUNCTION val(a INT) RETURNS INT AS $$ VALUES (a * 10) $$");
        executeQuery("SELECT val(4)");
        assertEquals(40, getIntValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION tab() RETURNS INT AS $$ TABLE pgf_user $$");
        executeQuery("SELECT tab()");
        assertEquals(1, getIntValue(1));

        // IMMUTABLE/STABLE等价于deterministic, 默认是VOLATILE;
        // 持久化时保留用户写的那个关键字, STABLE不再归一化成IMMUTABLE
        String immutableSql = getCreateSql("ADD_ONE");
        assertTrue(immutableSql, immutableSql.contains(" IMMUTABLE "));
        String volatileSql = getCreateSql("TWICE");
        assertTrue(volatileSql, volatileSql.contains(" VOLATILE "));
        executeUpdate(
                "CREATE OR REPLACE FUNCTION stable_f(a INT) RETURNS INT STABLE AS $$ SELECT a $$");
        assertTrue(getCreateSql("STABLE_F").contains(" STABLE "));

        // 参数个数不匹配时报函数找不到
        executeQueryThanAssertErrorCode("SELECT add_one(1, 2)", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // OR REPLACE覆盖旧定义, IF NOT EXISTS存在时直接跳过
    @Test
    public void testOrReplaceAndIfNotExists() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION rp(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeQuery("SELECT rp(1)");
        assertEquals(2, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION rp(a INT) RETURNS INT AS $$ SELECT a + 100 $$");
        executeQuery("SELECT rp(1)");
        assertEquals(101, getIntValue(1));

        // 定义已存在且没加OR REPLACE会报错
        executeUpdateThanAssertErrorCode("CREATE FUNCTION rp(a INT) RETURNS INT AS $$ SELECT a $$",
                ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1);

        // IF NOT EXISTS不报错, 也不改变已有的定义
        executeUpdate("CREATE FUNCTION IF NOT EXISTS rp(a INT) RETURNS INT AS $$ SELECT a + 1000 $$");
        executeQuery("SELECT rp(1)");
        assertEquals(101, getIntValue(1));
    }

    // DROP FUNCTION
    @Test
    public void testDropFunction() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION df(a INT) RETURNS INT AS $$ SELECT a $$");
        executeQuery("SELECT df(1)");
        assertEquals(1, getIntValue(1));

        executeUpdate("DROP FUNCTION df");
        executeQueryThanAssertErrorCode("SELECT df(1)", ErrorCode.FUNCTION_NOT_FOUND_1);

        // 删除不存在的函数: 不加IF EXISTS报错, 加了不报错
        executeUpdateThanAssertErrorCode("DROP FUNCTION df", ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1);
        executeUpdate("DROP FUNCTION IF EXISTS df");
    }

    // getCreateSQL生成的语句要能被自己的parser原样回放
    @Test
    public void testCreateSqlRoundTrip() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION rt(a INT) RETURNS VARCHAR LANGUAGE SQL IMMUTABLE "
                + "AS $$\n  SELECT name FROM pgf_user WHERE id = a\n$$");
        executeQuery("SELECT rt(2)");
        assertEquals("bob", getStringValue(1));
        String createSql = getCreateSql("RT");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains(" IMMUTABLE "));

        // 删掉再用getCreateSQL生成的语句重建
        executeUpdate("DROP FUNCTION rt");
        executeQueryThanAssertErrorCode("SELECT rt(2)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate(createSql);
        executeQuery("SELECT rt(2)");
        assertEquals("bob", getStringValue(1));
    }

    // 建库->关库->重开->调用, 验证函数被持久化
    @Test
    public void testPersistence() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION persist(a INT) RETURNS INT AS $$ SELECT a * 7 $$");
        executeQuery("SELECT persist(6)");
        assertEquals(42, getIntValue(1));

        closeResultSet();
        stmt.close();
        conn.close();

        conn = getConnection();
        stmt = conn.createStatement();
        executeQuery("SELECT persist(6)");
        assertEquals(42, getIntValue(1));
        assertNotNull(getCreateSql("PERSIST"));
    }

    private void createTable() {
        executeUpdate("DROP TABLE IF EXISTS pgf_user");
        executeUpdate("CREATE TABLE pgf_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pgf_user VALUES(1, 'alice'), (2, 'bob')");
    }

    // 从SCRIPT的输出里找出指定函数的CREATE FUNCTION语句
    private String getCreateSql(String functionName) {
        String prefix = "CREATE FUNCTION " + functionName;
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
