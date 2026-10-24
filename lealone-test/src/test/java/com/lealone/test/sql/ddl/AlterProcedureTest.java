/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * ALTER PROCEDURE: 属性(和ALTER FUNCTION同一套)、RENAME TO、OWNER TO、SET/RESET参数，
 * 以及参数类型列表参与的签名校验。属性必须写进过程DDL，SCRIPT回放和关库重启后都还在。
 */
public class AlterProcedureTest extends SqlTestBase {

    public AlterProcedureTest() {
        setEmbedded(true);
    }

    @Test
    public void testAlterAttributes() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS apr_a(INT)");
        executeUpdate("CREATE PROCEDURE apr_a(a INT) LANGUAGE SQL AS 'INSERT INTO apr_t VALUES(?)'");

        executeUpdate("ALTER PROCEDURE apr_a(INT) STRICT");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" STRICT"));

        executeUpdate("ALTER PROCEDURE apr_a(INT) IMMUTABLE");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" IMMUTABLE"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) STABLE");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" STABLE"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) VOLATILE");
        // VOLATILE是默认值，不用写进DDL
        assertFalse(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" VOLATILE"));

        executeUpdate("ALTER PROCEDURE apr_a(INT) SECURITY DEFINER");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" SECURITY DEFINER"));
        // SECURITY DEFINER时属主默认是执行ALTER的用户，要一起持久化
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" OWNER TO ROOT"));

        executeUpdate("ALTER PROCEDURE apr_a(INT) LEAKPROOF");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" LEAKPROOF"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) WINDOW");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" WINDOW"));

        executeUpdate("ALTER PROCEDURE apr_a(INT) COST 42");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" COST 42"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) ROWS 7");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" ROWS 7"));

        executeUpdate("ALTER PROCEDURE apr_a(INT) SET work_mem = '4MB'");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" SET WORK_MEM = '4MB'"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) SET search_path = s1, public");
        assertTrue(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" SET SEARCH_PATH = S1, PUBLIC"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) RESET search_path");
        assertFalse(getCreateSql("APR_A"), getCreateSql("APR_A").contains("SEARCH_PATH"));
        executeUpdate("ALTER PROCEDURE apr_a(INT) RESET ALL");
        assertFalse(getCreateSql("APR_A"), getCreateSql("APR_A").contains(" SET "));
        // 属性不能影响调用
        executeUpdate("CALL apr_a(1)");
        executeQuery("SELECT COUNT(*) FROM apr_t");
        assertEquals(1, getIntValue(1));
        executeUpdate("DROP PROCEDURE apr_a(INT)");
    }

    @Test
    public void testAlterRenameAndOwner() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS apr_old(INT)");
        executeUpdate("CREATE PROCEDURE apr_old(a INT) AS 'INSERT INTO apr_t VALUES(?)'");
        executeUpdate("ALTER PROCEDURE apr_old(INT) RENAME TO apr_new");
        executeUpdate("CALL apr_new(2)");
        executeQueryThanAssertErrorCode("CALL apr_old(2)", ErrorCode.FUNCTION_NOT_FOUND_1);
        assertNull(getCreateSql("APR_OLD"));
        assertTrue(getCreateSql("APR_NEW"), getCreateSql("APR_NEW").contains(".APR_NEW("));

        // 改名到一个已经存在的过程上报错
        executeUpdate("DROP PROCEDURE IF EXISTS apr_other(INT)");
        executeUpdate("CREATE PROCEDURE apr_other(a INT) AS 'INSERT INTO apr_t VALUES(?)'");
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_other(INT) RENAME TO apr_new",
                ErrorCode.PROCEDURE_ALREADY_EXISTS_1);

        // OWNER TO只记录不生效，但必须能持久化
        executeUpdate("ALTER PROCEDURE apr_new(INT) OWNER TO root");
        assertTrue(getCreateSql("APR_NEW"), getCreateSql("APR_NEW").contains(" OWNER TO ROOT"));
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_new(INT) OWNER TO no_such_user",
                ErrorCode.USER_NOT_FOUND_1);

        executeUpdate("ALTER PROCEDURE IF EXISTS no_such_procedure(INT) STRICT");
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE no_such_procedure(INT) STRICT",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdate("DROP PROCEDURE apr_new(INT)");
        executeUpdate("DROP PROCEDURE apr_other(INT)");
    }

    // 有重载时必须写参数类型，参数类型的口径和DROP PROCEDURE一致(含OUT/INOUT)
    @Test
    public void testSignatureList() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS apr_sig(INT)");
        executeUpdate("DROP PROCEDURE IF EXISTS apr_sig(VARCHAR)");
        executeUpdate("CREATE PROCEDURE apr_sig(a INT) AS 'INSERT INTO apr_t VALUES(?)'");
        executeUpdate("CREATE PROCEDURE apr_sig(a VARCHAR) AS 'INSERT INTO apr_t VALUES(LENGTH(?))'");

        // 多个重载时没写参数类型报歧义
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_sig STRICT",
                ErrorCode.AMBIGUOUS_FUNCTION_NAME_1);
        executeUpdate("ALTER PROCEDURE apr_sig(INT) STRICT");
        // 类型对不上等于过程不存在
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_sig(TEXT) STRICT",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdate("ALTER PROCEDURE IF EXISTS apr_sig(TEXT) VOLATILE");
        String intDdl = getCreateSql("APR_SIG", "A INT");
        assertTrue(intDdl, intDdl.contains(" STRICT"));

        // 带OUT参数的过程: 只写入参和写完整参数列表都能匹配
        executeUpdate("DROP PROCEDURE IF EXISTS apr_out");
        executeUpdate("CREATE PROCEDURE apr_out(IN a INT, OUT b INT) AS 'SELECT a'");
        executeUpdate("ALTER PROCEDURE apr_out(INT) LEAKPROOF");
        executeUpdate("ALTER PROCEDURE apr_out(IN INT, OUT INT) COST 5");
        String ddl = getCreateSql("APR_OUT");
        assertTrue(ddl, ddl.contains(" LEAKPROOF"));
        assertTrue(ddl, ddl.contains(" COST 5"));
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_out(IN INT, OUT VARCHAR) STRICT",
                ErrorCode.PROCEDURE_NOT_FOUND_1);

        // 函数和过程的ALTER不能互相串用
        executeUpdate("DROP FUNCTION IF EXISTS apr_fn(INT)");
        executeUpdate("CREATE FUNCTION apr_fn(a INT) RETURNS INT AS 'SELECT a'");
        executeUpdateThanAssertErrorCode("ALTER PROCEDURE apr_fn(INT) STRICT",
                ErrorCode.PROCEDURE_NOT_FOUND_1);
        executeUpdateThanAssertErrorCode("ALTER FUNCTION apr_sig(INT) STABLE",
                ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1);

        executeUpdate("DROP PROCEDURE apr_sig(INT)");
        executeUpdate("DROP PROCEDURE apr_sig(VARCHAR)");
        executeUpdate("DROP PROCEDURE apr_out");
        executeUpdate("DROP FUNCTION apr_fn(INT)");
    }

    // 属性要写进DDL: SCRIPT回放出来的DDL能重建出同样的过程，关库重开后属性还在
    @Test
    public void testAttributesPersistence() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS apr_persist(INT)");
        executeUpdate("CREATE PROCEDURE apr_persist(a INT) LANGUAGE SQL "
                + "AS 'INSERT INTO apr_t VALUES(?)'");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) IMMUTABLE");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) STRICT");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) SECURITY DEFINER");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) COST 11");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) ROWS 3");
        executeUpdate("ALTER PROCEDURE apr_persist(INT) SET work_mem = '1MB'");
        String ddl = getCreateSql("APR_PERSIST");
        assertTrue(ddl, ddl.contains(" IMMUTABLE "));
        assertTrue(ddl, ddl.contains(" STRICT "));
        assertTrue(ddl, ddl.contains(" SECURITY DEFINER"));
        assertTrue(ddl, ddl.contains(" COST 11"));
        assertTrue(ddl, ddl.contains(" ROWS 3"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '1MB'"));

        // DDL能原样回放
        executeUpdate("DROP PROCEDURE apr_persist(INT)");
        executeUpdate(ddl);
        String ddl2 = getCreateSql("APR_PERSIST");
        assertEquals(ddl, ddl2);

        // 关库重开后属性还在，过程还能调用
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        String ddl3 = getCreateSql("APR_PERSIST");
        assertTrue(ddl3, ddl3.contains(" IMMUTABLE "));
        assertTrue(ddl3, ddl3.contains(" STRICT "));
        assertTrue(ddl3, ddl3.contains(" SECURITY DEFINER"));
        assertTrue(ddl3, ddl3.contains(" COST 11"));
        assertTrue(ddl3, ddl3.contains(" ROWS 3"));
        assertTrue(ddl3, ddl3.contains(" SET WORK_MEM = '1MB'"));
        executeUpdate("CALL apr_persist(3)");
        executeUpdate("DROP PROCEDURE apr_persist(INT)");
    }

    @Test
    public void testCreateWithAttributes() throws Exception {
        // CREATE PROCEDURE也能直接写这些属性，和函数同一套
        executeUpdate("DROP PROCEDURE IF EXISTS apr_c(INT)");
        executeUpdate("CREATE PROCEDURE apr_c(a INT) LANGUAGE SQL STRICT COST 9 "
                + "SET work_mem = '2MB' AS 'INSERT INTO apr_t VALUES(?)'");
        String ddl = getCreateSql("APR_C");
        assertTrue(ddl, ddl.contains(" STRICT"));
        assertTrue(ddl, ddl.contains(" COST 9"));
        assertTrue(ddl, ddl.contains(" SET WORK_MEM = '2MB'"));
        executeUpdate("DROP PROCEDURE apr_c(INT)");
    }

    private void createTableIfNotExists() {
        executeUpdate("CREATE TABLE IF NOT EXISTS apr_t(id INT)");
    }

    @Override
    public void setUpBefore() {
        super.setUpBefore();
        createTableIfNotExists();
        executeUpdate("DELETE FROM apr_t");
    }

    // 从SCRIPT的输出里找出指定过程的CREATE PROCEDURE语句，有重载时还要给出参数类型
    private String getCreateSql(String procedureName) {
        return getCreateSql(procedureName, null);
    }

    // signature是参数列表里的原文，例如 "A INT"
    private String getCreateSql(String procedureName, String signature) {
        String prefix = "CREATE PROCEDURE ";
        String name = "." + procedureName + "(";
        String fullSignature = signature == null ? null : name + signature + ")";
        try (Statement s = conn.createStatement(); ResultSet scriptRs = s.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String sql = scriptRs.getString(1);
                if (sql != null && sql.startsWith(prefix) && sql.contains(name)
                        && (fullSignature == null || sql.contains(fullSignature))) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
