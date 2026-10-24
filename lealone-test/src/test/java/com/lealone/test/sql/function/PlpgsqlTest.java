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
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL风格的PL/pgSQL存储函数(过程式函数体)。
 */
public class PlpgsqlTest extends SqlTestBase {

    public PlpgsqlTest() {
        setEmbedded(true);
    }

    // DECLARE变量、:=赋值、IF/ELSE、RETURN
    @Test
    public void testBasicBlockAndIf() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_basic(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 0;" //
                + " BEGIN" //
                + "   x := a * 2;" //
                + "   IF x > 10 THEN x := 10; ELSE x := x + 1; END IF;" //
                + "   RETURN x;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_basic(6)");
        assertEquals(10, getIntValue(1));
        executeQuery("SELECT plp_basic(5)");
        assertEquals(11, getIntValue(1));

        // 没有参数的函数
        executeUpdate("CREATE OR REPLACE FUNCTION plp_noarg() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN 42; END; $$");
        executeQuery("SELECT plp_noarg()");
        assertEquals(42, getIntValue(1));
    }

    // 变量初始化、DEFAULT、CONSTANT、=赋值、%TYPE
    @Test
    public void testDeclareAssignmentAndType() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_t");
        executeUpdate("CREATE TABLE plp_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_t VALUES(1, 'alice')");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_decl(a INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE" //
                + "   v plp_t.name%TYPE;" //
                + "   n INT DEFAULT 5;" //
                + "   c CONSTANT INT := 3;" //
                + " BEGIN" //
                + "   SELECT name INTO v FROM plp_t WHERE id = a;" //
                + "   n = n + 1;" // 支持=赋值
                + "   RETURN v || '/' || (n + c);" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_decl(1)");
        assertEquals("alice/9", getStringValue(1));

        // CONSTANT变量不能被赋值
        executeUpdate("CREATE OR REPLACE FUNCTION plp_const() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CONSTANT INT := 3; BEGIN c := 4; RETURN c; END; $$");
        assertSqlState("22005", "SELECT plp_const()");
    }

    // 嵌套块，内层变量遮蔽外层变量
    @Test
    public void testNestedBlockAndShadowing() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_nested(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 1;" //
                + " BEGIN" //
                + "   DECLARE x INT := 10;" //
                + "   BEGIN" //
                + "     x := x + a;" //
                + "   END;" //
                + "   RETURN x;" // 外层x还是1
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_nested(5)");
        assertEquals(1, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_nested2(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 1;" //
                + " BEGIN" //
                + "   DECLARE y INT := x + 10;" //
                + "   BEGIN" //
                + "     x := y + a;" //
                + "   END;" //
                + "   RETURN x;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_nested2(5)");
        assertEquals(16, getIntValue(1));
    }

    // IF/ELSIF/ELSE
    @Test
    public void testIfElsifElse() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_if(a INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   IF a < 0 THEN RETURN 'neg';" //
                + "   ELSIF a = 0 THEN RETURN 'zero';" //
                + "   ELSE RETURN 'pos'; END IF;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_if(-1), plp_if(0), plp_if(1)");
        assertEquals("neg", getStringValue(1));
        assertEquals("zero", getStringValue(2));
        assertEquals("pos", getStringValue(3));
    }

    // 内嵌DML和SELECT ... INTO
    @Test
    public void testEmbeddedDmlAndSelectInto() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_dml");
        executeUpdate("CREATE TABLE plp_dml(id INT PRIMARY KEY, v INT)");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_insert(p_id INT, p_v INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plp_dml(id, v) VALUES(p_id, p_v);" //
                + "   RETURN p_v;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_insert(1, 100)");
        assertEquals(100, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_update(p_id INT, p_delta INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE old_v INT := 0;" //
                + " BEGIN" //
                + "   SELECT v INTO old_v FROM plp_dml WHERE id = p_id;" //
                + "   UPDATE plp_dml SET v = old_v + p_delta WHERE id = p_id;" //
                + "   RETURN old_v + p_delta;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_update(1, 5)");
        assertEquals(105, getIntValue(1));
        executeQuery("SELECT v FROM plp_dml WHERE id = 1");
        assertEquals(105, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_delete(p_id INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT := 0;" //
                + " BEGIN" //
                + "   DELETE FROM plp_dml WHERE id = p_id;" //
                + "   SELECT count(*) INTO n FROM plp_dml;" //
                + "   RETURN n;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_delete(1)");
        assertEquals(0, getIntValue(1));
    }

    // SELECT INTO STRICT没有行时抛no_data_found
    @Test
    public void testSelectIntoStrict() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_strict");
        executeUpdate("CREATE TABLE plp_strict(id INT, v INT)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_strict_f(p_id INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE v0 INT := 0;" //
                + " BEGIN" //
                + "   SELECT v INTO STRICT v0 FROM plp_strict WHERE id = p_id;" //
                + "   RETURN v0;" //
                + " END;" //
                + " $$");
        assertSqlState("P0002", "SELECT plp_strict_f(1)");

        // 多行时抛too_many_rows
        executeUpdate("INSERT INTO plp_strict VALUES(2, 1), (2, 2)");
        assertSqlState("P0003", "SELECT plp_strict_f(2)");
    }

    // LANGUAGE子句可以写在AS后面，RAISE EXCEPTION SQLSTATE的旧写法
    @Test
    public void testLanguageAfterBodyAndRaiseSqlState() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_lang(a INT) RETURNS INT AS $$" //
                + " BEGIN RETURN a + 1; END;" //
                + " $$ LANGUAGE plpgsql");
        executeQuery("SELECT plp_lang(1)");
        assertEquals(2, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_raise_state(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION SQLSTATE '22012';" //
                + "   RETURN a;" //
                + " EXCEPTION WHEN SQLSTATE '22012' THEN" //
                + "   RETURN 6;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_raise_state(1)");
        assertEquals(6, getIntValue(1));
    }

    // LOOP/WHILE/FOR/EXIT/CONTINUE
    @Test
    public void testLoops() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_loop(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0; i INT := 0;" //
                + " BEGIN" //
                + "   LOOP" //
                + "     i := i + 1;" //
                + "     EXIT WHEN i > n;" //
                + "     CONTINUE WHEN i % 2 = 0;" //
                + "     s := s + i;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_loop(5)");
        assertEquals(9, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_while(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0; i INT := 0;" //
                + " BEGIN" //
                + "   WHILE i < n LOOP" //
                + "     i := i + 1;" //
                + "     s := s + i;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_while(4)");
        assertEquals(10, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_for(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 1..n LOOP" //
                + "     s := s + i;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_for(5)");
        assertEquals(15, getIntValue(1));

        // REVERSE从第一个表达式开始递减，所以范围写成n..1
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_rev(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN REVERSE n..1 LOOP" //
                + "     s := s + i;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_for_rev(5)");
        assertEquals(15, getIntValue(1));
    }

    // RAISE NOTICE继续执行；RAISE EXCEPTION中断并带SQLSTATE
    @Test
    public void testRaise() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_notice(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE NOTICE 'notice %', a;" //
                + "   RETURN a + 1;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_notice(2)");
        assertEquals(3, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_raise(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'boom'; RETURN a; END; $$");
        assertEquals("P0001", getSqlState("SELECT plp_raise(1)"));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_raise_code(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'custom' USING ERRCODE = '22012'; RETURN a; END; $$");
        assertEquals("22012", getSqlState("SELECT plp_raise_code(1)"));
    }

    // EXCEPTION WHEN ... THEN ...
    @Test
    public void testExceptionHandlers() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_exc_div(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   BEGIN" //
                + "     RETURN a / 0;" //
                + "   EXCEPTION WHEN division_by_zero THEN" //
                + "     RETURN 99;" //
                + "   END;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_exc_div(10)");
        assertEquals(99, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_exc_others(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION 'oops';" //
                + "   RETURN a;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 7;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_exc_others(1)");
        assertEquals(7, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_exc_state(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION 'custom' USING ERRCODE = '22012';" //
                + "   RETURN a;" //
                + " EXCEPTION WHEN SQLSTATE '22012' THEN" //
                + "   RETURN 8;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_exc_state(1)");
        assertEquals(8, getIntValue(1));

        // 没有匹配的handler时异常继续往外抛
        executeUpdate("CREATE OR REPLACE FUNCTION plp_exc_nomatch(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION 'unhandled';" //
                + "   RETURN a;" //
                + " EXCEPTION WHEN division_by_zero THEN" //
                + "   RETURN 1;" //
                + " END;" //
                + " $$");
        assertEquals("P0001", getSqlState("SELECT plp_exc_nomatch(1)"));
    }

    // 运行期的控制流/常量错误
    @Test
    public void testRuntimeErrors() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_exit_bad() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN EXIT no_such_label; RETURN 1; END; $$");
        assertSqlState("42601", "SELECT plp_exit_bad()");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_const_into() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CONSTANT INT := 1; BEGIN SELECT 2 INTO c; RETURN c; END; $$");
        assertSqlState("22005", "SELECT plp_const_into()");
    }

    // RETURNS VOID：可以省略RETURN，RETURN;合法，RETURN expr非法
    @Test
    public void testReturnsVoid() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_void(a INT) RETURNS VOID LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE NOTICE 'void %', a; END; $$");
        executeQuery("SELECT plp_void(1)");
        assertNull(rs.getObject(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_void_ret() RETURNS VOID LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN; END; $$");
        executeQuery("SELECT plp_void_ret()");
        assertNull(rs.getObject(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_void_bad() RETURNS VOID LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN 1; END; $$");
        assertSqlState("2F005", "SELECT plp_void_bad()");
    }

    // Lealone引擎的SQLSTATE要能映射到PostgreSQL条件名
    @Test
    public void testExceptionConditionAliases() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_alias_table(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 0;" //
                + " BEGIN" //
                + "   SELECT v INTO x FROM plp_not_exist_table;" //
                + "   RETURN x;" //
                + " EXCEPTION WHEN undefined_table THEN" //
                + "   RETURN 1;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_alias_table(1)");
        assertEquals(1, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_alias_cast(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RETURN 'abc'::INT;" //
                + " EXCEPTION WHEN invalid_text_representation THEN" //
                + "   RETURN 2;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_alias_cast(1)");
        assertEquals(2, getIntValue(1));
    }

    // PERFORM丢弃查询结果
    @Test
    public void testPerform() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_perform_t");
        executeUpdate("CREATE TABLE plp_perform_t(id INT)");
        executeUpdate("INSERT INTO plp_perform_t VALUES(1), (2)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_perform(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   PERFORM a * 2;" //
                + "   PERFORM count(*) FROM plp_perform_t;" //
                + "   RETURN a + 1;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_perform(3)");
        assertEquals(4, getIntValue(1));
    }

    // getCreateSQL生成的语句要能重建函数，并且能持久化
    @Test
    public void testCreateSqlRoundTripAndPersistence() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_persist(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := a;" //
                + " BEGIN x := x * 7; RETURN x; END;" //
                + " $$");
        executeQuery("SELECT plp_persist(6)");
        assertEquals(42, getIntValue(1));

        String createSql = getCreateSql("PLP_PERSIST");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.toLowerCase().contains("language plpgsql"));

        executeUpdate("DROP FUNCTION plp_persist");
        executeUpdate(createSql);
        executeQuery("SELECT plp_persist(6)");
        assertEquals(42, getIntValue(1));

        closeResultSet();
        stmt.close();
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        executeQuery("SELECT plp_persist(6)");
        assertEquals(42, getIntValue(1));
    }

    private String getSqlState(String sql) throws Exception {
        try {
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
            }
            fail(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private void assertSqlState(String expected, String sql) throws Exception {
        assertEquals(expected, getSqlState(sql));
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
