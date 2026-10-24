/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL风格的函数DDL属性: CREATE FUNCTION的各种属性子句、ALTER FUNCTION、
 * DROP FUNCTION的参数类型列表、COMMENT ON FUNCTION。
 * 每个属性都要验证能持久化(SCRIPT里的DDL)并能被自己的parser原样回放。
 */
public class PgFunctionAttrTest extends SqlTestBase {

    public PgFunctionAttrTest() {
        setEmbedded(true);
    }

    // CREATE FUNCTION里一次写全所有属性，再删掉用SCRIPT里的DDL重建
    @Test
    public void testCreateAttributes() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_all(a INT) RETURNS VARCHAR LANGUAGE SQL STABLE "
                + "CALLED ON NULL INPUT SECURITY DEFINER LEAKPROOF WINDOW COST 42 ROWS 7 "
                + "SET search_path = s1, public SET work_mem = '4MB' "
                + "AS $$ SELECT name FROM pga_user WHERE id = a $$");

        executeQuery("SELECT a_all(1)");
        assertEquals("alice", getStringValue(1));
        // 属性都要出现在DDL里
        String ddl = getCreateSql("A_ALL");
        assertTrue(ddl, ddl.contains(" STABLE "));
        assertTrue(ddl, ddl.contains(" CALLED ON NULL INPUT "));
        assertTrue(ddl, ddl.contains(" SECURITY DEFINER"));
        assertTrue(ddl, ddl.contains(" LEAKPROOF"));
        assertTrue(ddl, ddl.contains(" WINDOW"));
        assertTrue(ddl, ddl.contains(" COST 42"));
        assertTrue(ddl, ddl.contains(" ROWS 7"));
        assertTrue(ddl, ddl.contains(" SET SEARCH_PATH = S1, PUBLIC"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '4MB'"));

        // 删掉再用DDL重建，重建出来的函数还能用、属性还在
        executeUpdate("DROP FUNCTION a_all");
        executeUpdate(ddl);
        executeQuery("SELECT a_all(2)");
        assertEquals("bob", getStringValue(1));
        String ddl2 = getCreateSql("A_ALL");
        assertTrue(ddl2, ddl2.contains(" COST 42"));
        assertTrue(ddl2, ddl2.contains(" SET WORK_MEM = '4MB'"));

        // SETOF函数也能带属性，并且同样能回放
        executeUpdate("CREATE OR REPLACE FUNCTION a_setof(a INT) RETURNS SETOF INT STABLE STRICT "
                + "COST 3 AS $$ SELECT id FROM pga_user WHERE id >= a $$");
        String setofDdl = getCreateSql("A_SETOF");
        assertTrue(setofDdl, setofDdl.contains(" STABLE "));
        assertTrue(setofDdl, setofDdl.contains(" STRICT "));
        assertTrue(setofDdl, setofDdl.contains(" COST 3"));
        executeUpdate("DROP FUNCTION a_setof");
        executeUpdate(setofDdl);
        executeQuery("SELECT * FROM a_setof(1)");
        assertEquals(1, getIntValue(1));
        int rowCount = 1;
        while (next()) {
            rowCount++;
        }
        assertEquals(2, rowCount);
    }

    // ALTER FUNCTION改易变性、STRICT/CALLED ON NULL INPUT
    @Test
    public void testAlterVolatilityAndNullInput() throws Exception {
        createTable();
        // 函数体在入参为NULL时返回固定值，用来区分STRICT(不执行函数体)和CALLED ON NULL INPUT
        executeUpdate("CREATE OR REPLACE FUNCTION a_null(a INT) RETURNS VARCHAR AS "
                + "$$ SELECT COALESCE((SELECT name FROM pga_user WHERE id = a), 'was-null') $$");
        executeQuery("SELECT a_null(1), a_null(NULL)");
        assertEquals("alice", getStringValue(1));
        assertEquals("was-null", getStringValue(2));

        executeUpdate("ALTER FUNCTION a_null STRICT");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" STRICT "));
        executeQuery("SELECT a_null(NULL)");
        assertNull(getStringValue(1));

        executeUpdate("ALTER FUNCTION a_null CALLED ON NULL INPUT");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" CALLED ON NULL INPUT "));
        executeQuery("SELECT a_null(NULL)");
        assertEquals("was-null", getStringValue(1));

        // RETURNS NULL ON NULL INPUT是STRICT的标准SQL写法
        executeUpdate("ALTER FUNCTION a_null RETURNS NULL ON NULL INPUT");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" STRICT "));

        executeUpdate("ALTER FUNCTION a_null IMMUTABLE");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" IMMUTABLE "));
        executeUpdate("ALTER FUNCTION a_null STABLE");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" STABLE "));
        executeUpdate("ALTER FUNCTION a_null VOLATILE");
        assertTrue(getCreateSql("A_NULL"), getCreateSql("A_NULL").contains(" VOLATILE "));
    }

    // ALTER FUNCTION改SECURITY/LEAKPROOF/WINDOW
    @Test
    public void testAlterSecurityAndOthers() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_sec(a INT) RETURNS INT AS $$ SELECT a $$");

        executeUpdate("ALTER FUNCTION a_sec SECURITY DEFINER");
        assertTrue(getCreateSql("A_SEC"), getCreateSql("A_SEC").contains(" SECURITY DEFINER"));
        executeUpdate("ALTER FUNCTION a_sec SECURITY INVOKER");
        assertFalse(getCreateSql("A_SEC"), getCreateSql("A_SEC").contains(" SECURITY "));

        executeUpdate("ALTER FUNCTION a_sec LEAKPROOF");
        assertTrue(getCreateSql("A_SEC"), getCreateSql("A_SEC").contains(" LEAKPROOF"));
        executeUpdate("ALTER FUNCTION a_sec NOT LEAKPROOF");
        assertFalse(getCreateSql("A_SEC"), getCreateSql("A_SEC").contains(" LEAKPROOF"));

        executeUpdate("ALTER FUNCTION a_sec WINDOW");
        assertTrue(getCreateSql("A_SEC"), getCreateSql("A_SEC").contains(" WINDOW"));
        executeQuery("SELECT a_sec(3)");
        assertEquals(3, getIntValue(1));
    }

    // ALTER FUNCTION改COST/ROWS/SET/RESET
    @Test
    public void testAlterCostRowsAndSet() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_cost(a INT) RETURNS INT AS $$ SELECT a $$");

        executeUpdate("ALTER FUNCTION a_cost COST 42");
        assertTrue(getCreateSql("A_COST"), getCreateSql("A_COST").contains(" COST 42"));
        executeUpdate("ALTER FUNCTION a_cost COST 0.5");
        assertTrue(getCreateSql("A_COST"), getCreateSql("A_COST").contains(" COST 0.5"));
        executeUpdate("ALTER FUNCTION a_cost ROWS 100");
        assertTrue(getCreateSql("A_COST"), getCreateSql("A_COST").contains(" ROWS 100"));

        executeUpdate("ALTER FUNCTION a_cost SET search_path = s1, public");
        String ddl = getCreateSql("A_COST");
        assertTrue(ddl, ddl.contains(" SET SEARCH_PATH = S1, PUBLIC"));
        executeUpdate("ALTER FUNCTION a_cost SET work_mem = '8MB'");
        ddl = getCreateSql("A_COST");
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '8MB'"));

        executeUpdate("ALTER FUNCTION a_cost RESET search_path");
        ddl = getCreateSql("A_COST");
        assertFalse(ddl, ddl.contains("SEARCH_PATH"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '8MB'"));
        executeUpdate("ALTER FUNCTION a_cost RESET ALL");
        assertFalse(getCreateSql("A_COST"), getCreateSql("A_COST").contains(" SET "));
    }

    // ALTER FUNCTION RENAME TO / OWNER TO
    @Test
    public void testAlterRenameAndOwner() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_old(a INT) RETURNS INT AS $$ SELECT a + 1 $$");
        executeUpdate("ALTER FUNCTION a_old RENAME TO a_new");
        executeQuery("SELECT a_new(1)");
        assertEquals(2, getIntValue(1));
        executeQueryThanAssertErrorCode("SELECT a_old(1)", ErrorCode.FUNCTION_NOT_FOUND_1);
        assertNull(getCreateSql("A_OLD"));
        assertTrue(getCreateSql("A_NEW"), getCreateSql("A_NEW").contains("FUNCTION A_NEW("));

        // 改名到一个已经存在的函数上报错
        executeUpdate("CREATE OR REPLACE FUNCTION a_other(a INT) RETURNS INT AS $$ SELECT a $$");
        executeUpdateThanAssertErrorCode("ALTER FUNCTION a_other RENAME TO a_new",
                ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1);

        // OWNER TO只记录不生效，但必须能持久化
        executeUpdate("ALTER FUNCTION a_new OWNER TO root");
        assertTrue(getCreateSql("A_NEW"), getCreateSql("A_NEW").contains(" OWNER TO ROOT"));
        executeUpdateThanAssertErrorCode("ALTER FUNCTION a_new OWNER TO no_such_user",
                ErrorCode.USER_NOT_FOUND_1);

        executeUpdate("ALTER FUNCTION IF EXISTS no_such_function STRICT");
        executeUpdateThanAssertErrorCode("ALTER FUNCTION no_such_function STRICT",
                ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1);
    }

    // ALTER FUNCTION/DROP FUNCTION的参数类型列表参与签名校验
    @Test
    public void testSignatureList() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_sig(a INT, b VARCHAR) RETURNS INT AS $$ SELECT a $$");

        // 类型对不上时函数等于不存在
        executeUpdateThanAssertErrorCode("ALTER FUNCTION a_sig(TEXT) STRICT",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("ALTER FUNCTION a_sig(INT, INT) STRICT",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("ALTER FUNCTION a_sig(INT, VARCHAR) STRICT");
        assertTrue(getCreateSql("A_SIG"), getCreateSql("A_SIG").contains(" STRICT "));
        // IF EXISTS时类型对不上也不报错
        executeUpdate("ALTER FUNCTION IF EXISTS a_sig(TEXT) VOLATILE");
        assertTrue(getCreateSql("A_SIG"), getCreateSql("A_SIG").contains(" STRICT "));

        executeUpdateThanAssertErrorCode("DROP FUNCTION a_sig(TEXT, TEXT)",
                ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("DROP FUNCTION IF EXISTS a_sig(TEXT, TEXT)");
        executeQuery("SELECT a_sig(1, 'x')");
        executeUpdate("DROP FUNCTION a_sig(INT, VARCHAR)");
        executeQueryThanAssertErrorCode("SELECT a_sig(1, 'x')", ErrorCode.FUNCTION_NOT_FOUND_1);
    }

    // COMMENT ON FUNCTION
    @Test
    public void testCommentOnFunction() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_cmt(a INT) RETURNS INT AS $$ SELECT a $$");
        executeUpdate("COMMENT ON FUNCTION a_cmt(INT) IS 'my comment'");
        executeQuery("SELECT remarks FROM information_schema.function_aliases "
                + "WHERE alias_name = 'A_CMT'");
        assertEquals("my comment", getStringValue(1));

        // COMMENT ON ALIAS是同一个机制，两者可以互相覆盖
        executeUpdate("COMMENT ON ALIAS a_cmt IS 'changed'");
        executeQuery("SELECT remarks FROM information_schema.function_aliases "
                + "WHERE alias_name = 'A_CMT'");
        assertEquals("changed", getStringValue(1));

        // 注释要能持久化，换成DDL重建后还在
        String commentSql = null;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String sql = rs.getString(1);
                if (sql != null && sql.startsWith("COMMENT ON") && sql.contains("A_CMT")) {
                    commentSql = sql;
                }
            }
        }
        assertNotNull(commentSql);
        executeUpdate("DROP FUNCTION a_cmt");
        executeUpdate("CREATE FUNCTION a_cmt(a INT) RETURNS INT AS $$ SELECT a $$");
        executeUpdate(commentSql);
        executeQuery("SELECT remarks FROM information_schema.function_aliases "
                + "WHERE alias_name = 'A_CMT'");
        assertEquals("changed", getStringValue(1));
    }

    // SECURITY DEFINER以属主身份执行函数体，SECURITY INVOKER以调用者身份执行
    @Test
    public void testSecurityDefiner() throws Exception {
        createTable();
        executeUpdate("DROP USER IF EXISTS pga_u");
        executeUpdate("CREATE USER pga_u PASSWORD 'p'");
        executeUpdate("CREATE OR REPLACE FUNCTION a_def(a INT) RETURNS VARCHAR SECURITY DEFINER "
                + "AS $$ SELECT name FROM pga_user WHERE id = a $$");
        executeUpdate("CREATE OR REPLACE FUNCTION a_inv(a INT) RETURNS VARCHAR "
                + "AS $$ SELECT name FROM pga_user WHERE id = a $$");
        // 没写OWNER TO时属主默认是创建者，并且要写进DDL
        assertTrue(getCreateSql("A_DEF"), getCreateSql("A_DEF").contains(" SECURITY DEFINER"));
        assertTrue(getCreateSql("A_DEF"), getCreateSql("A_DEF").contains(" OWNER TO ROOT"));

        // 调用者pga_u没有pga_user的SELECT权限
        Connection u = getConnection("pga_u", "p");
        Statement us = u.createStatement();
        try {
            ResultSet urs = us.executeQuery("SELECT a_inv(1)");
            urs.next();
            fail("SECURITY INVOKER function should be denied");
        } catch (SQLException e) {
            assertErrorCode(e, ErrorCode.NOT_ENOUGH_RIGHTS_FOR_1);
        }
        try (ResultSet urs = us.executeQuery("SELECT a_def(1)")) {
            urs.next();
            assertEquals("alice", urs.getString(1));
        }

        // 改成SECURITY DEFINER后，同一个调用者也能用
        executeUpdate("ALTER FUNCTION a_inv SECURITY DEFINER");
        assertTrue(getCreateSql("A_INV"), getCreateSql("A_INV").contains(" OWNER TO ROOT"));
        try (ResultSet urs = us.executeQuery("SELECT a_inv(2)")) {
            urs.next();
            assertEquals("bob", urs.getString(1));
        }
        us.close();
        u.close();

        // 关库重开后DEFINER仍然以属主身份执行(靠DDL里的OWNER TO回放)
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        u = getConnection("pga_u", "p");
        us = u.createStatement();
        try (ResultSet urs = us.executeQuery("SELECT a_def(2)")) {
            urs.next();
            assertEquals("bob", urs.getString(1));
        }
        us.close();
        u.close();
    }

    // 关库重开后属性还在(验证sys表里的DDL能回放)
    @Test
    public void testAttributesPersistence() throws Exception {
        createTable();
        executeUpdate("CREATE OR REPLACE FUNCTION a_persist(a INT) RETURNS INT LANGUAGE SQL IMMUTABLE "
                + "STRICT SECURITY DEFINER LEAKPROOF COST 11 ROWS 3 SET work_mem = '1MB' "
                + "AS $$ SELECT a * 2 $$");
        executeQuery("SELECT a_persist(2)");
        assertEquals(4, getIntValue(1));

        closeResultSet();
        stmt.close();
        conn.close();

        conn = getConnection();
        stmt = conn.createStatement();
        executeQuery("SELECT a_persist(3)");
        assertEquals(6, getIntValue(1));
        String ddl = getCreateSql("A_PERSIST");
        assertTrue(ddl, ddl.contains(" IMMUTABLE "));
        assertTrue(ddl, ddl.contains(" STRICT "));
        assertTrue(ddl, ddl.contains(" SECURITY DEFINER"));
        assertTrue(ddl, ddl.contains(" LEAKPROOF"));
        assertTrue(ddl, ddl.contains(" COST 11"));
        assertTrue(ddl, ddl.contains(" ROWS 3"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '1MB'"));
    }

    private void createTable() {
        executeUpdate("DROP TABLE IF EXISTS pga_user");
        executeUpdate("CREATE TABLE pga_user(id INT PRIMARY KEY, name VARCHAR(50))");
        executeUpdate("INSERT INTO pga_user VALUES(1, 'alice'), (2, 'bob')");
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
