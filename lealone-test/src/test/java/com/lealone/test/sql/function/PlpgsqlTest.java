/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
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

    // FOR循环的边界/步长校验、WITH ... SELECT INTO、无handler时RAISE;、无标签块带结束标签
    @Test
    public void testMoreParserAndRuntimeChecks() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_neg() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN FOR i IN 1..3 BY -1 LOOP NULL; END LOOP; RETURN 1; END; $$");
        assertSqlState("22023", "SELECT plp_for_neg()");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_null() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN FOR i IN 1..NULL LOOP NULL; END LOOP; RETURN 1; END; $$");
        assertSqlState("22004", "SELECT plp_for_null()");

        // 上界接近Integer.MAX_VALUE时不能溢出死循环
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_big() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 2147483646..2147483647 LOOP" //
                + "     s := s + (i - 2147483646);" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_for_big()");
        assertEquals(1, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_with_into() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := 0;" //
                + " BEGIN" //
                + "   WITH q AS (SELECT 7 AS a) SELECT a INTO x FROM q;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plp_with_into()");
        assertEquals(7, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_raise_noexc() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE; END; $$");
        assertSqlState("0Z002", "SELECT plp_raise_noexc()");

        // 没有标签的块不能写END label
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION plp_bad_endlabel() RETURNS INT LANGUAGE plpgsql AS $$"
                        + " BEGIN BEGIN NULL; END foo; RETURN 1; END; $$",
                ErrorCode.SYNTAX_ERROR_2);
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

        // PostgreSQL的条件名也可以是SQLSTATE类名，42xxx都能匹配
        executeUpdate("CREATE OR REPLACE FUNCTION plp_alias_class(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   SELECT v FROM plp_not_exist_table2;" //
                + "   RETURN 0;" //
                + " EXCEPTION WHEN syntax_error_or_access_rule_violation THEN" //
                + "   RETURN 3;" //
                + " END;" //
                + " $$");
        executeQuery("SELECT plp_alias_class(1)");
        assertEquals(3, getIntValue(1));
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

    // FOUND魔法变量：DML/PERFORM/SELECT INTO之后的值
    @Test
    public void testFoundVariable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_found_t");
        executeUpdate("CREATE TABLE plp_found_t(a INT)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_found() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r INT := 0; n INT := 0;" //
                + " BEGIN" //
                + "   INSERT INTO plp_found_t VALUES(1);" //
                + "   IF FOUND THEN r := r + 1; END IF;" //
                + "   UPDATE plp_found_t SET a = 100 WHERE a = 1;" //
                + "   IF FOUND THEN r := r + 2; END IF;" //
                + "   DELETE FROM plp_found_t WHERE a = 9999;" //
                + "   IF NOT FOUND THEN r := r + 4; END IF;" //
                + "   PERFORM * FROM plp_found_t;" //
                + "   IF FOUND THEN r := r + 8; END IF;" //
                + "   SELECT a INTO n FROM plp_found_t WHERE a = 8888;" //
                + "   IF NOT FOUND THEN r := r + 16; END IF;" //
                + "   SELECT a INTO n FROM plp_found_t WHERE a = 100;" //
                + "   IF FOUND THEN r := r + 32; END IF;" //
                + "   RETURN r;" //
                + " END; $$");
        executeQuery("SELECT plp_found()");
        assertEquals(63, getIntValue(1));
    }

    // record和%ROWTYPE变量：SELECT * INTO整行、r.col取字段
    @Test
    public void testRecordAndRowType() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_rec_t");
        executeUpdate("CREATE TABLE plp_rec_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_rec_t VALUES(1, 'alice')");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_rowtype(p INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_rec_t WHERE id = p;" //
                + "   RETURN r.name;" //
                + " END; $$");
        executeQuery("SELECT plp_rec_rowtype(1)");
        assertEquals("alice", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_record(p INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r record;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_rec_t WHERE id = p;" //
                + "   RAISE NOTICE 'record name = %', r.name;" //
                + "   RETURN r.name;" //
                + " END; $$");
        executeQuery("SELECT plp_rec_record(1)");
        assertEquals("alice", getStringValue(1));

        // 没有行时record未赋值，取字段报55000
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_norow(p INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_rec_t WHERE id = p;" //
                + "   RETURN r.name;" //
                + " END; $$");
        assertSqlState("55000", "SELECT plp_rec_norow(99)");

        // record当标量用要给出明确的不支持错误，不能漏内部CAST语法错
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_scalar(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE;" //
                + " BEGIN RETURN r; END; $$");
        executeQueryThanAssertErrorCode("SELECT plp_rec_scalar(1)",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);

        // 不存在的表%ROWTYPE给出可读的不支持错误
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_badtype() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_no_such_table%ROWTYPE;" //
                + " BEGIN RETURN 1; END; $$");
        executeQueryThanAssertErrorCode("SELECT plp_rec_badtype()",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // 块标签限定变量名：outerblock.param1
    @Test
    public void testBlockLabelQualifiedVariable() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_label() RETURNS INT LANGUAGE plpgsql AS $$"
                + " <<outerblock>>" //
                + " DECLARE param1 INT := 1;" //
                + " BEGIN" //
                + "   DECLARE param1 INT := 10;" //
                + "   BEGIN" //
                + "     outerblock.param1 := 2;" //
                + "     RAISE NOTICE 'outer=%', outerblock.param1;" //
                + "     RETURN outerblock.param1 + param1;" //
                + "   END;" //
                + " END; $$");
        executeQuery("SELECT plp_label()");
        assertEquals(12, getIntValue(1));

        // 带标签的循环变量也能被限定引用
        executeUpdate("CREATE OR REPLACE FUNCTION plp_label_loop() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   <<lp>> FOR i IN 1..3 LOOP" //
                + "     s := s + lp.i;" //
                + "   END LOOP lp;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_label_loop()");
        assertEquals(6, getIntValue(1));
    }

    // 有OUT/INOUT参数时，裸RETURN;和自然结束都返回OUT参数的值
    @Test
    public void testOutParameterBareReturn() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_out_bare(i INT, OUT j INT) AS $$"
                + " BEGIN j := i + 1; RETURN; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT plp_out_bare(42)");
        assertEquals(43, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_inout_natural(INOUT i INT) AS $$"
                + " BEGIN i := i + 1; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT plp_inout_natural(42)");
        assertEquals(43, getIntValue(1));

        // 显式RETURN表达式仍然可用
        executeUpdate("CREATE OR REPLACE FUNCTION plp_out_value(i INT, OUT j INT) AS $$"
                + " BEGIN j := i + 1; RETURN j * 2; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT plp_out_value(42)");
        assertEquals(86, getIntValue(1));
    }

    // RETURNS TABLE：RETURN QUERY和RETURN NEXT都往结果集追加行
    @Test
    public void testReturnsTable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_setof_t");
        executeUpdate("CREATE TABLE plp_setof_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_setof_t VALUES(1, 'a'), (2, 'b'), (3, 'c')");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_query(p INT) RETURNS TABLE(id INT, name VARCHAR) LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RETURN QUERY SELECT t.id, t.name FROM plp_setof_t t WHERE t.id >= p ORDER BY t.id;" //
                + " END; $$");
        assertEquals("[2:b, 3:c]", queryRowList(
                "SELECT id, name FROM plp_table_query(2) ORDER BY id").toString());

        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_next() RETURNS TABLE(id INT, name VARCHAR) LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   id := 1; name := 'x'; RETURN NEXT;" //
                + "   id := 2; name := 'y'; RETURN NEXT;" //
                + " END; $$");
        assertEquals("[1:x, 2:y]",
                queryRowList("SELECT id, name FROM plp_table_next() ORDER BY id").toString());

        // 声明了TABLE列但一行也没返回时是空结果集
        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_empty() RETURNS TABLE(id INT) LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN; END; $$");
        assertEquals("[]", queryIntList("SELECT * FROM plp_table_empty()").toString());
    }

    // RETURNS SETOF标量：RETURN NEXT逐行累积
    @Test
    public void testReturnsSetofScalar() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_setof_scalar(a INT, b INT) RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT;" //
                + " BEGIN" //
                + "   FOR i IN a..b LOOP" //
                + "     RETURN NEXT i + 1;" //
                + "   END LOOP;" //
                + "   RETURN;" //
                + " END; $$");
        assertEquals("[2, 3, 4]",
                queryIntList("SELECT * FROM plp_setof_scalar(1, 3) ORDER BY 1").toString());

        executeUpdate("CREATE OR REPLACE FUNCTION plp_setof_query() RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN QUERY SELECT id FROM plp_setof_t ORDER BY id; END; $$");
        assertEquals("[1, 2, 3]",
                queryIntList("SELECT * FROM plp_setof_query() ORDER BY 1").toString());

        // 没有RETURN;时执行到函数结尾也返回已累积的行
        executeUpdate("CREATE OR REPLACE FUNCTION plp_setof_natural() RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN NEXT 1; RETURN NEXT 2; END; $$");
        assertEquals("[1, 2]",
                queryIntList("SELECT * FROM plp_setof_natural() ORDER BY 1").toString());
    }

    // 多个OUT参数组成一行结果集
    @Test
    public void testMultipleOutParameters() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_multi_out(i INT, OUT j INT, OUT k VARCHAR) AS $$"
                + " BEGIN" //
                + "   j := i + 1;" //
                + "   k := 'foo';" //
                + " END $$ LANGUAGE plpgsql");
        try (java.sql.ResultSet result = stmt.executeQuery("SELECT * FROM plp_multi_out(42)")) {
            assertTrue(result.next());
            assertEquals(43, result.getInt(1));
            assertEquals("foo", result.getString(2));
            assertEquals("J", result.getMetaData().getColumnName(1));
            assertEquals("K", result.getMetaData().getColumnName(2));
            assertFalse(result.next());
        }

        // 显式RETURN;和自然结束等价
        executeUpdate("CREATE OR REPLACE FUNCTION plp_multi_out2(i INT, OUT j INT, OUT k INT) AS $$"
                + " BEGIN j := i * 2; k := i * 3; RETURN; END $$ LANGUAGE plpgsql");
        assertEquals("[4:6]", queryRowList("SELECT j, k FROM plp_multi_out2(2)").toString());
    }

    // 非SETOF函数用RETURN NEXT、SETOF函数用RETURN表达式都要报2F005
    @Test
    public void testInvalidReturnForms() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_bad_next() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN NEXT 1; RETURN 1; END; $$");
        assertSqlState("2F005", "SELECT plp_bad_next()");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_bad_setof_return() RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN 1; END; $$");
        assertSqlState("2F005", "SELECT * FROM plp_bad_setof_return()");
    }

    // plpgsql的SETOF/TABLE函数要能通过SCRIPT重建
    @Test
    public void testSetofRoundTrip() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_rt_t");
        executeUpdate("CREATE TABLE plp_rt_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_rt_t VALUES(1, 'a'), (2, 'b')");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_rt(p INT) RETURNS TABLE(id INT, name VARCHAR) LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN QUERY SELECT t.id, t.name FROM plp_rt_t t WHERE t.id >= p ORDER BY t.id; END; $$");
        String createSql = getCreateSql("PLP_TABLE_RT");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains("RETURNS TABLE"));
        assertTrue(createSql, createSql.toLowerCase().contains("language plpgsql"));
        executeUpdate("DROP FUNCTION plp_table_rt");
        executeUpdate(createSql);
        assertEquals("[2:b]", queryRowList("SELECT id, name FROM plp_table_rt(2)").toString());

        executeUpdate("CREATE OR REPLACE FUNCTION plp_setof_rt() RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN NEXT 1; RETURN NEXT 2; END; $$");
        String setofSql = getCreateSql("PLP_SETOF_RT");
        assertNotNull(setofSql);
        assertTrue(setofSql, setofSql.contains("RETURNS SETOF INT"));
        executeUpdate("DROP FUNCTION plp_setof_rt");
        executeUpdate(setofSql);
        assertEquals("[1, 2]",
                queryIntList("SELECT * FROM plp_setof_rt() ORDER BY 1").toString());
    }

    // GET DIAGNOSTICS ... = ROW_COUNT
    @Test
    public void testGetDiagnosticsRowCount() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_rc_t");
        executeUpdate("CREATE TABLE plp_rc_t(a INT)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_row_count() RETURNS BIGINT LANGUAGE plpgsql AS $$"
                + " DECLARE n BIGINT := 0;" //
                + " BEGIN" //
                + "   INSERT INTO plp_rc_t VALUES(1), (2), (3);" //
                + "   GET DIAGNOSTICS n = ROW_COUNT;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_row_count()");
        assertEquals(3, getLongValue(1));
    }

    private ArrayList<Integer> queryIntList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<Integer> list = new ArrayList<>();
            while (result.next())
                list.add(result.getInt(1));
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryRowList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next())
                list.add(result.getInt(1) + ":" + result.getString(2));
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
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
