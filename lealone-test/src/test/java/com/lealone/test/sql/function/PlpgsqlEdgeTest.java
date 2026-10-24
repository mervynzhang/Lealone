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

import com.lealone.test.sql.SqlTestBase;

/**
 * PL/pgSQL边界用例：语句切分、变量作用域与替换、SELECT INTO、循环标签、
 * 异常传播、%TYPE、递归、类型转换以及CREATE OR REPLACE/DROP后的重建。
 */
public class PlpgsqlEdgeTest extends SqlTestBase {

    public PlpgsqlEdgeTest() {
        setEmbedded(true);
    }

    // 分号出现在字符串和注释里时不能影响PL/pgSQL的语句切分
    @Test
    public void testSemicolonsInsideStringsAndComments() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_semicolon() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE s VARCHAR(20) := 'a;b';"
                + " BEGIN"
                + "   -- END;BEGIN; line comment with semicolon\n"
                + "   /* END; BEGIN; block comment with semicolon */\n"
                + "   RAISE NOTICE 'x;y';"
                + "   RETURN s;"
                + " END; $$");
        executeQuery("SELECT plpe_semicolon()");
        assertEquals("a;b", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_semicolon_literal() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN 'a;b'; END; $$");
        executeQuery("SELECT plpe_semicolon_literal()");
        assertEquals("a;b", getStringValue(1));

        // 字符串里的END/BEGIN关键字也不能提前结束语句
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_semicolon_keyword() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN 'END;BEGIN'; END; $$");
        executeQuery("SELECT plpe_semicolon_keyword()");
        assertEquals("END;BEGIN", getStringValue(1));

        dropFunction("plpe_semicolon");
        dropFunction("plpe_semicolon_literal");
        dropFunction("plpe_semicolon_keyword");
    }

    // RAISE里的美元引用、单引号转义和格式串
    @Test
    public void testRaiseDollarQuoteAndEscapedQuote() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_raise_dollar() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION $msg$dollar;quote %$msg$, 7; RETURN 0; END; $$");
        assertSqlState("P0001", "SELECT plpe_raise_dollar()");
        assertErrorMessageContains("dollar;quote 7", "SELECT plpe_raise_dollar()");

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_raise_quote() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'it''s %;ok', 5; RETURN 0; END; $$");
        assertSqlState("P0001", "SELECT plpe_raise_quote()");
        assertErrorMessageContains("it's 5;ok", "SELECT plpe_raise_quote()");

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_raise_pct() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION '100%% %', 5; RETURN 0; END; $$");
        assertErrorMessageContains("100% 5", "SELECT plpe_raise_pct()");

        // 没有格式化参数时%%也要折叠成一个%
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_raise_pct_noarg() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION '100%% done'; RETURN 0; END; $$");
        assertErrorMessageContains("100% done", "SELECT plpe_raise_pct_noarg()");

        dropFunction("plpe_raise_dollar");
        dropFunction("plpe_raise_quote");
        dropFunction("plpe_raise_pct");
        dropFunction("plpe_raise_pct_noarg");
    }

    // 声明小写、SQL里用大写引用；双引号变量保持大小写
    @Test
    public void testVariableCaseAndQuotedVariable() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_case_var() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE total INT := 5;"
                + " BEGIN RETURN TOTAL + 1; END; $$");
        executeQuery("SELECT plpe_case_var()");
        assertEquals(6, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_quoted_var() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE \"V\" INT := 7;"
                + " BEGIN \"V\" := \"V\" + 1; RETURN \"V\"; END; $$");
        executeQuery("SELECT plpe_quoted_var()");
        assertEquals(8, getIntValue(1));

        dropFunction("plpe_case_var");
        dropFunction("plpe_quoted_var");
    }

    // 表达式位置变量优先；表名、INSERT列名、UPDATE的SET列名不能被替换成变量
    @Test
    public void testVariableColumnAndTableNameSeparation() throws Exception {
        dropFunction("plpe_shadow_fn");
        executeUpdate("DROP TABLE IF EXISTS plpe_shadow");
        executeUpdate("CREATE TABLE plpe_shadow(id INT PRIMARY KEY, val INT)");
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_shadow_fn(p_id INT, p_val INT) RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE id INT := 999; val INT := 888; plpe_shadow INT := 5;"
                + " BEGIN"
                + "   INSERT INTO plpe_shadow(id, val) VALUES(p_id, p_val);"
                + "   UPDATE plpe_shadow SET val = 200;"
                + "   RETURN val + plpe_shadow;"
                + " END; $$");
        executeQuery("SELECT plpe_shadow_fn(2, 50)");
        assertEquals(893, getIntValue(1));

        executeQuery("SELECT count(*) FROM plpe_shadow");
        assertEquals(1, getIntValue(1));
        executeQuery("SELECT val FROM plpe_shadow WHERE id = 2");
        assertEquals(200, getIntValue(1));

        dropFunction("plpe_shadow_fn");
        executeUpdate("DROP TABLE IF EXISTS plpe_shadow");
    }

    // SELECT INTO多列、无行时置NULL、INTO STRICT多行报P0003
    @Test
    public void testSelectIntoMultipleRowsAndStrict() throws Exception {
        dropFunction("plpe_into_multi");
        dropFunction("plpe_into_strict_multi");
        executeUpdate("DROP TABLE IF EXISTS plpe_into");
        executeUpdate("CREATE TABLE plpe_into(id INT PRIMARY KEY, a INT, b VARCHAR(10))");
        executeUpdate("INSERT INTO plpe_into VALUES(1, 10, 'x'), (2, 20, 'y')");

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_into_multi(p_id INT) RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE x INT; y VARCHAR(10);"
                + " BEGIN"
                + "   SELECT a, b INTO x, y FROM plpe_into WHERE id = p_id;"
                + "   IF x IS NULL AND y IS NULL THEN RETURN 'null'; END IF;"
                + "   RETURN x || ':' || y;"
                + " END; $$");
        executeQuery("SELECT plpe_into_multi(1)");
        assertEquals("10:x", getStringValue(1));
        executeQuery("SELECT plpe_into_multi(99)");
        assertEquals("null", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_into_strict_multi() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 0;"
                + " BEGIN SELECT a INTO STRICT x FROM plpe_into; RETURN x; END; $$");
        assertSqlState("P0003", "SELECT plpe_into_strict_multi()");

        dropFunction("plpe_into_multi");
        dropFunction("plpe_into_strict_multi");
        executeUpdate("DROP TABLE IF EXISTS plpe_into");
    }

    // 嵌套循环的EXIT/CONTINUE标签
    @Test
    public void testLabeledLoops() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_loop_continue() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;"
                + " BEGIN"
                + "   <<outer>> FOR i IN 1..3 LOOP"
                + "     <<inner>> FOR j IN 1..3 LOOP"
                + "       CONTINUE outer WHEN j = 2;"
                + "       s := s + i * 10 + j;"
                + "     END LOOP inner;"
                + "   END LOOP outer;"
                + "   RETURN s;"
                + " END; $$");
        executeQuery("SELECT plpe_loop_continue()");
        assertEquals(63, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_loop_exit() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0; i INT := 0;"
                + " BEGIN"
                + "   <<outer>> LOOP"
                + "     i := i + 1;"
                + "     LOOP"
                + "       EXIT outer WHEN i = 3;"
                + "       s := s + 1;"
                + "       EXIT;"
                + "     END LOOP;"
                + "   END LOOP outer;"
                + "   RETURN s;"
                + " END; $$");
        executeQuery("SELECT plpe_loop_exit()");
        assertEquals(2, getIntValue(1));

        dropFunction("plpe_loop_continue");
        dropFunction("plpe_loop_exit");
    }

    // 内层块的异常向外传播；handler里的RETURN能直接返回
    @Test
    public void testNestedExceptionPropagationAndHandlerReturn() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_nested_exc() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   BEGIN"
                + "     RAISE EXCEPTION 'inner';"
                + "   EXCEPTION WHEN division_by_zero THEN RETURN 1;"
                + "   END;"
                + "   RETURN 2;"
                + " EXCEPTION WHEN OTHERS THEN RETURN 3;"
                + " END; $$");
        executeQuery("SELECT plpe_nested_exc()");
        assertEquals(3, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_handler_return() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   BEGIN RAISE EXCEPTION 'inner'; EXCEPTION WHEN OTHERS THEN RETURN 5; END;"
                + " END; $$");
        executeQuery("SELECT plpe_handler_return()");
        assertEquals(5, getIntValue(1));

        dropFunction("plpe_nested_exc");
        dropFunction("plpe_handler_return");
    }

    // %TYPE可以带schema前缀，也可以带空白
    @Test
    public void testPercentTypeWithSchemaAndWhitespace() throws Exception {
        dropFunction("plpe_type_name");
        dropFunction("plpe_type_id");
        executeUpdate("DROP TABLE IF EXISTS plpe_type");
        executeUpdate("CREATE TABLE plpe_type(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plpe_type VALUES(1, 'alice')");

        executeQuery("SELECT SCHEMA()");
        String schema = getStringValue(1);
        closeResultSet();
        assertNotNull(schema);

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_type_name(p_id INT) RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE v " + schema + " . plpe_type . name % TYPE;"
                + " BEGIN"
                + "   SELECT name INTO v FROM plpe_type WHERE id = p_id;"
                + "   RETURN v;"
                + " END; $$");
        executeQuery("SELECT plpe_type_name(1)");
        assertEquals("alice", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_type_id(p_id INT) RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE v plpe_type.id %TYPE; n INT := 1;"
                + " BEGIN"
                + "   SELECT id INTO v FROM plpe_type WHERE id = p_id;"
                + "   RETURN v + n;"
                + " END; $$");
        executeQuery("SELECT plpe_type_id(1)");
        assertEquals(2, getIntValue(1));

        dropFunction("plpe_type_name");
        dropFunction("plpe_type_id");
        executeUpdate("DROP TABLE IF EXISTS plpe_type");
    }

    // DECLARE的初始化表达式能看到函数参数
    @Test
    public void testParameterVisibleInDeclarationInitializer() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_param_init(a INT, b INT) RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := a + b; y INT := x * 2;"
                + " BEGIN RETURN y; END; $$");
        executeQuery("SELECT plpe_param_init(2, 3)");
        assertEquals(10, getIntValue(1));

        dropFunction("plpe_param_init");
    }

    // 函数体内的SQL可以递归调用函数本身
    @Test
    public void testRecursiveCalls() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_fact(n INT) RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   IF n <= 1 THEN RETURN 1; END IF;"
                + "   RETURN n * plpe_fact(n - 1);"
                + " END; $$");
        executeQuery("SELECT plpe_fact(5)");
        assertEquals(120, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_fib(n INT) RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   IF n < 2 THEN RETURN n; END IF;"
                + "   RETURN plpe_fib(n - 1) + plpe_fib(n - 2);"
                + " END; $$");
        executeQuery("SELECT plpe_fib(6)");
        assertEquals(8, getIntValue(1));

        dropFunction("plpe_fact");
        dropFunction("plpe_fib");
    }

    // 字符串拼接、NULL传播、numeric到int的赋值和返回转换
    @Test
    public void testStringNullAndReturnConversion() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_concat() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 'a' || 'b'; END; $$");
        executeQuery("SELECT plpe_concat()");
        assertEquals("ab", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_null_concat() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 'x' || NULL; END; $$");
        executeQuery("SELECT plpe_null_concat()");
        assertNull(getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_null_return() RETURNS VARCHAR "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$");
        executeQuery("SELECT plpe_null_return()");
        assertNull(getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_dec_to_int() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 3.7; END; $$");
        executeQuery("SELECT plpe_dec_to_int()");
        assertEquals(4, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_decl_round() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ DECLARE x INT := 2.6; BEGIN RETURN x; END; $$");
        executeQuery("SELECT plpe_decl_round()");
        assertEquals(3, getIntValue(1));

        dropFunction("plpe_concat");
        dropFunction("plpe_null_concat");
        dropFunction("plpe_null_return");
        dropFunction("plpe_dec_to_int");
        dropFunction("plpe_decl_round");
    }

    // CREATE OR REPLACE覆盖已经执行过的函数，DROP后用getCreateSQL重建
    @Test
    public void testCreateOrReplaceAndDropRecreate() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plpe_replace() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 1; END; $$");
        executeQuery("SELECT plpe_replace()");
        assertEquals(1, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_replace() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 2; END; $$");
        executeQuery("SELECT plpe_replace()");
        assertEquals(2, getIntValue(1));
        closeResultSet();

        String createSql = getCreateSql("PLPE_REPLACE");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.toLowerCase().contains("language plpgsql"));
        executeUpdate("DROP FUNCTION plpe_replace");
        executeUpdate(createSql);
        executeQuery("SELECT plpe_replace()");
        assertEquals(2, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plpe_replace() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN 3; END; $$");
        executeQuery("SELECT plpe_replace()");
        assertEquals(3, getIntValue(1));

        dropFunction("plpe_replace");
    }

    private void dropFunction(String name) {
        executeUpdate("DROP FUNCTION IF EXISTS " + name);
    }

    private SQLException getSqlException(String sql) {
        try (Statement statement = conn.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                fail(sql);
            }
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private void assertSqlState(String expected, String sql) {
        SQLException e = getSqlException(sql);
        assertNotNull(sql, e);
        assertEquals(expected, e.getSQLState());
    }

    private void assertErrorMessageContains(String expected, String sql) {
        SQLException e = getSqlException(sql);
        assertNotNull(sql, e);
        String message = e.getMessage();
        assertNotNull(message);
        assertTrue(message, message.contains(expected));
    }

    // 从SCRIPT的输出里找出指定函数的CREATE FUNCTION语句
    private String getCreateSql(String functionName) throws SQLException {
        String prefix = "CREATE FUNCTION " + functionName;
        try (Statement statement = conn.createStatement();
                ResultSet scriptRs = statement.executeQuery("SCRIPT")) {
            while (scriptRs.next()) {
                String createSql = scriptRs.getString(1);
                if (createSql != null
                        && createSql.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return createSql;
                }
            }
        }
        return null;
    }
}
