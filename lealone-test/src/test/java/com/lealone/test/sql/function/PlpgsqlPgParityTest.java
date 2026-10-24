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
 * 对照PostgreSQL官方回归测试(src/test/regress/sql/plpgsql.sql)提炼的PL/pgSQL端到端用例。
 * 只使用当前实现支持的语法子集，期望值全部按PostgreSQL语义给出，
 * 用来独立验证Lealone解释器的行为是否和PostgreSQL一致。
 * 函数统一用plpp_前缀，表统一用plpp_前缀，函数先DROP IF EXISTS、表先DROP TABLE IF EXISTS清理。
 */
public class PlpgsqlPgParityTest extends SqlTestBase {

    public PlpgsqlPgParityTest() {
        setEmbedded(true);
    }

    // 整数除法向0截断、取模符号跟被除数一致，NULL参与表达式和IF条件
    @Test
    public void testIntegerArithmeticAndNullConditions() throws Exception {
        dropFunctions("plpp_div", "plpp_mod", "plpp_if_null", "plpp_eq_null");

        executeUpdate("CREATE FUNCTION plpp_div(a INT, b INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN a / b; END; $$");
        executeUpdate("CREATE FUNCTION plpp_mod(a INT, b INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN a % b; END; $$");
        // PostgreSQL: 7/2=3, -7/2=-3, 7/-2=-3；-7%2=-1, 7%-2=1
        executeQuery("SELECT plpp_div(7, 2), plpp_div(-7, 2), plpp_div(7, -2),"
                + " plpp_mod(-7, 2), plpp_mod(7, -2)");
        assertEquals(3, getIntValue(1));
        assertEquals(-3, getIntValue(2));
        assertEquals(-3, getIntValue(3));
        assertEquals(-1, getIntValue(4));
        assertEquals(1, getIntValue(5));

        // NULL参与算术运算，结果还是NULL
        executeQuery("SELECT plpp_div(1, NULL), plpp_mod(NULL, 2)");
        assertNull(getStringValue(1));
        assertNull(getStringValue(2));

        executeUpdate("CREATE FUNCTION plpp_if_null(a INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE n INT;" // 没有初始化的变量是NULL
                + " BEGIN" //
                + "   IF a > 0 THEN RETURN 'pos';" //
                + "   ELSIF n > 0 THEN RETURN 'bad';" //
                + "   ELSE RETURN 'else'; END IF;" //
                + " END; $$");
        // IF条件为NULL时按false处理，走ELSE分支
        executeQuery("SELECT plpp_if_null(5), plpp_if_null(0), plpp_if_null(NULL)");
        assertEquals("pos", getStringValue(1));
        assertEquals("else", getStringValue(2));
        assertEquals("else", getStringValue(3));

        // 用=和NULL比较同样得到NULL，也按false处理
        executeUpdate("CREATE FUNCTION plpp_eq_null(a INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " BEGIN IF a = NULL THEN RETURN 'eq'; ELSE RETURN 'ne'; END IF; END; $$");
        executeQuery("SELECT plpp_eq_null(1), plpp_eq_null(NULL)");
        assertEquals("ne", getStringValue(1));
        assertEquals("ne", getStringValue(2));
    }

    // x := x + 1连续赋值；变量出现在SQL表达式的WHERE里；限定名引用真正的表列
    @Test
    public void testAssignmentAndVariableInSqlExpression() throws Exception {
        dropFunctions("plpp_inc", "plpp_where_var", "plpp_qualified");
        executeUpdate("DROP TABLE IF EXISTS plpp_var");
        executeUpdate("CREATE TABLE plpp_var(id INT PRIMARY KEY, v INT)");
        executeUpdate("INSERT INTO plpp_var VALUES(1, 100), (2, 200), (3, 300)");

        executeUpdate("CREATE FUNCTION plpp_inc(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := a;" //
                + " BEGIN" //
                + "   x := x + 1;" //
                + "   x := x + 1;" //
                + "   x := x + 1;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plpp_inc(10)");
        assertEquals(13, getIntValue(1));

        // 变量lim出现在WHERE里，被替换成变量值2而不是列名
        executeUpdate("CREATE FUNCTION plpp_where_var(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE lim INT := 2; n INT := 0;" //
                + " BEGIN" //
                + "   SELECT count(*) INTO n FROM plpp_var WHERE id <= lim;" //
                + "   RETURN n + p;" //
                + " END; $$");
        executeQuery("SELECT plpp_where_var(10)");
        assertEquals(12, getIntValue(1));

        // 加了表名限定的v是真正的表列，没有限定的v是变量
        executeUpdate("CREATE FUNCTION plpp_qualified(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE v INT := 42;" //
                + " BEGIN" //
                + "   RETURN (SELECT plpp_var.v FROM plpp_var WHERE id = p) + v;" //
                + " END; $$");
        executeQuery("SELECT plpp_qualified(1)");
        assertEquals(142, getIntValue(1));
    }

    // Lealone采用PostgreSQL的plpgsql.variable_conflict=use_variable语义：
    // 表达式里的无限定标识符优先当变量，而不是像PG默认那样报42702
    @Test
    public void testVariableConflictUsesVariable() throws Exception {
        dropFunctions("plpp_conflict");
        executeUpdate("DROP TABLE IF EXISTS plpp_vc");
        executeUpdate("CREATE TABLE plpp_vc(id INT PRIMARY KEY, v INT)");
        executeUpdate("INSERT INTO plpp_vc VALUES(1, 100)");

        executeUpdate("CREATE FUNCTION plpp_conflict(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE v INT := 42; r INT := 0;" //
                + " BEGIN" //
                + "   SELECT v INTO r FROM plpp_vc WHERE id = p;" //
                + "   RETURN r;" //
                + " END; $$");
        // SELECT列表里的v被替换成变量值42，而不是表列的值100
        executeQuery("SELECT plpp_conflict(1)");
        assertEquals(42, getIntValue(1));
    }

    // FOR循环边界、REVERSE方向、循环变量遮蔽块里的同名变量
    @Test
    public void testForLoopBoundsAndReverse() throws Exception {
        dropFunctions("plpp_for_empty", "plpp_for_rev", "plpp_for_rev_empty",
                "plpp_for_shadow");

        executeUpdate("CREATE FUNCTION plpp_for_empty() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 1..0 LOOP n := n + 1; END LOOP;" // 下界大于上界，一次都不执行
                + "   FOR i IN 3..1 LOOP n := n + 1; END LOOP;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpp_for_empty()");
        assertEquals(0, getIntValue(1));

        // REVERSE的起点是第一个表达式，终点是第二个表达式，所以3..1依次是3、2、1
        executeUpdate("CREATE FUNCTION plpp_for_rev() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE s VARCHAR := '';" //
                + " BEGIN" //
                + "   FOR i IN REVERSE 3..1 LOOP s := s || i; END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plpp_for_rev()");
        assertEquals("321", getStringValue(1));

        // REVERSE 1..3的起点1小于终点3，一次都不执行
        executeUpdate("CREATE FUNCTION plpp_for_rev_empty() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN REVERSE 1..3 LOOP n := n + 1; END LOOP;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpp_for_rev_empty()");
        assertEquals(0, getIntValue(1));

        // FOR的循环变量只在循环内有效，不改变块里声明的同名变量
        executeUpdate("CREATE FUNCTION plpp_for_shadow() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT := 100; n INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 1..3 LOOP n := n + i; END LOOP;" //
                + "   RETURN i;" //
                + " END; $$");
        executeQuery("SELECT plpp_for_shadow()");
        assertEquals(100, getIntValue(1));
    }

    // WHILE条件为NULL时退出循环；EXIT/CONTINUE带WHEN，WHEN为NULL时按false处理
    @Test
    public void testWhileNullAndExitContinueWhen() throws Exception {
        dropFunctions("plpp_while_null", "plpp_exit_when", "plpp_continue_when");

        executeUpdate("CREATE FUNCTION plpp_while_null() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT; i INT := 0;" // n是NULL
                + " BEGIN" //
                + "   WHILE n < 10 LOOP i := i + 1; END LOOP;" // 条件为NULL，一次都不执行
                + "   RETURN i;" //
                + " END; $$");
        executeQuery("SELECT plpp_while_null()");
        assertEquals(0, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_exit_when(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT := 0;" //
                + " BEGIN" //
                + "   LOOP" //
                + "     i := i + 1;" //
                + "     EXIT WHEN i > n;" //
                + "     EXIT WHEN NULL;" // NULL按false处理，不退出
                + "     EXIT WHEN i < 0;" //
                + "   END LOOP;" //
                + "   RETURN i;" //
                + " END; $$");
        executeQuery("SELECT plpp_exit_when(5)");
        assertEquals(6, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_continue_when(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0; i INT := 0;" //
                + " BEGIN" //
                + "   WHILE i < n LOOP" //
                + "     i := i + 1;" //
                + "     CONTINUE WHEN i % 2 = 0;" //
                + "     s := s + i;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plpp_continue_when(6)");
        assertEquals(9, getIntValue(1)); // 1 + 3 + 5
    }

    // 嵌套循环+标签，EXIT/CONTINUE带标签，END LOOP后面也写标签
    @Test
    public void testNestedLoopsWithLabels() throws Exception {
        dropFunctions("plpp_exit_outer", "plpp_continue_outer");

        executeUpdate("CREATE FUNCTION plpp_exit_outer() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT := 0;" //
                + " BEGIN" //
                + "   <<outer>> FOR i IN 1..3 LOOP" //
                + "     <<inner>> FOR j IN 1..3 LOOP" //
                + "       n := n + 1;" //
                + "       IF j = 2 THEN EXIT outer; END IF;" //
                + "     END LOOP inner;" //
                + "   END LOOP outer;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpp_exit_outer()");
        assertEquals(2, getIntValue(1)); // j=1时n=1，j=2时n=2后退出外层循环

        executeUpdate("CREATE FUNCTION plpp_continue_outer() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   <<outer>> FOR i IN 1..3 LOOP" //
                + "     FOR j IN 1..3 LOOP" //
                + "       CONTINUE outer WHEN j = 2;" //
                + "       s := s + i * 10 + j;" //
                + "     END LOOP;" //
                + "   END LOOP outer;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plpp_continue_outer()");
        assertEquals(63, getIntValue(1)); // 11 + 21 + 31
    }

    // SELECT INTO多列；没有行时目标变量置NULL；非STRICT多行时取第一行
    @Test
    public void testSelectIntoMultiColumnAndNoRow() throws Exception {
        dropFunctions("plpp_into_multi", "plpp_into_first", "plpp_into_order_other");
        executeUpdate("DROP TABLE IF EXISTS plpp_into");
        executeUpdate("CREATE TABLE plpp_into(id INT PRIMARY KEY, a INT, b VARCHAR(10))");
        executeUpdate("INSERT INTO plpp_into VALUES(1, 10, 'aa'), (2, 20, 'bb')");

        executeUpdate("CREATE FUNCTION plpp_into_multi(p INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := -1; y VARCHAR := 'init'; r VARCHAR := '';" //
                + " BEGIN" //
                + "   SELECT a, b INTO x, y FROM plpp_into WHERE id = p;" //
                + "   IF x IS NULL THEN r := 'x=NULL'; ELSE r := 'x=' || x; END IF;" //
                + "   IF y IS NULL THEN r := r || ',y=NULL'; ELSE r := r || ',y=' || y; END IF;" //
                + "   RETURN r;" //
                + " END; $$");
        executeQuery("SELECT plpp_into_multi(1)");
        assertEquals("x=10,y=aa", getStringValue(1));
        // 没有行时所有目标变量都置成NULL，而不是保留初值
        executeQuery("SELECT plpp_into_multi(99)");
        assertEquals("x=NULL,y=NULL", getStringValue(1));

        // 非STRICT时多行只取第一行，ORDER BY按降序排
        executeUpdate("CREATE FUNCTION plpp_into_first() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := -1;" //
                + " BEGIN" //
                + "   SELECT a INTO x FROM plpp_into ORDER BY a DESC;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plpp_into_first()");
        assertEquals(20, getIntValue(1));

        // PostgreSQL允许ORDER BY引用没有出现在SELECT列表里的列
        executeUpdate("CREATE FUNCTION plpp_into_order_other() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := -1;" //
                + " BEGIN" //
                + "   SELECT a INTO x FROM plpp_into ORDER BY id;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plpp_into_order_other()");
        assertEquals(10, getIntValue(1));
    }

    // SELECT INTO STRICT：没有行抛P0002，多于一行抛P0003
    @Test
    public void testSelectIntoStrict() throws Exception {
        dropFunctions("plpp_strict_one", "plpp_strict_many");
        executeUpdate("DROP TABLE IF EXISTS plpp_strict");
        executeUpdate("CREATE TABLE plpp_strict(id INT PRIMARY KEY, a INT)");
        executeUpdate("INSERT INTO plpp_strict VALUES(1, 10), (2, 20)");

        executeUpdate("CREATE FUNCTION plpp_strict_one(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := -1;" //
                + " BEGIN" //
                + "   SELECT a INTO STRICT x FROM plpp_strict WHERE id = p;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plpp_strict_one(2)");
        assertEquals(20, getIntValue(1));
        assertSqlState("P0002", "SELECT plpp_strict_one(99)");

        executeUpdate("CREATE FUNCTION plpp_strict_many() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := -1;" //
                + " BEGIN" //
                + "   SELECT a INTO STRICT x FROM plpp_strict;" //
                + "   RETURN x;" //
                + " END; $$");
        assertSqlState("P0003", "SELECT plpp_strict_many()");
    }

    // 内层块捕获异常后外层继续执行；handler里变量保持出错时的值
    @Test
    public void testExceptionInnerBlockThenOuterContinues() throws Exception {
        dropFunctions("plpp_exc_inner", "plpp_exc_outer");

        executeUpdate("CREATE FUNCTION plpp_exc_inner() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r VARCHAR := '';" //
                + " BEGIN" //
                + "   r := 'a';" //
                + "   BEGIN" //
                + "     r := r || 'b';" //
                + "     RAISE EXCEPTION 'inner' USING ERRCODE = '22012';" //
                + "     r := r || 'X';" // 抛异常后这条不会执行
                + "   EXCEPTION WHEN division_by_zero THEN" //
                + "     r := r || 'c';" //
                + "   END;" //
                + "   r := r || 'd';" //
                + "   RETURN r;" //
                + " END; $$");
        executeQuery("SELECT plpp_exc_inner()");
        assertEquals("abcd", getStringValue(1));

        // 内层没有匹配的handler时异常继续往外抛，由外层OTHERS捕获
        executeUpdate("CREATE FUNCTION plpp_exc_outer() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   BEGIN" //
                + "     RAISE EXCEPTION 'boom';" //
                + "   EXCEPTION WHEN division_by_zero THEN RETURN 'inner'; END;" //
                + "   RETURN 'after';" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 'outer';" //
                + " END; $$");
        executeQuery("SELECT plpp_exc_outer()");
        assertEquals("outer", getStringValue(1));
    }

    // handler里的RETURN直接结束函数；WHEN SQLSTATE精确匹配；未匹配的异常继续上抛
    @Test
    public void testExceptionHandlerReturnAndUnmatched() throws Exception {
        dropFunctions("plpp_exc_return", "plpp_exc_sqlstate", "plpp_exc_unmatched");

        executeUpdate("CREATE FUNCTION plpp_exc_return() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   BEGIN" //
                + "     RAISE EXCEPTION 'e';" //
                + "   EXCEPTION WHEN OTHERS THEN RETURN 'handled'; END;" //
                + "   RETURN 'after';" //
                + " END; $$");
        executeQuery("SELECT plpp_exc_return()");
        assertEquals("handled", getStringValue(1));

        executeUpdate("CREATE FUNCTION plpp_exc_sqlstate() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION 'custom' USING ERRCODE = '22012';" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN SQLSTATE '22012' THEN" //
                + "   RETURN 8;" //
                + " END; $$");
        executeQuery("SELECT plpp_exc_sqlstate()");
        assertEquals(8, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_exc_unmatched() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE EXCEPTION 'unhandled';" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN division_by_zero THEN RETURN 2;" //
                + " END; $$");
        assertSqlState("P0001", "SELECT plpp_exc_unmatched()");
    }

    // RAISE NOTICE不中断执行；RAISE EXCEPTION默认SQLSTATE是P0001
    @Test
    public void testRaiseLevelsAndDefaultSqlState() throws Exception {
        dropFunctions("plpp_notice", "plpp_raise_default", "plpp_raise_errcode");

        executeUpdate("CREATE FUNCTION plpp_notice(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RAISE NOTICE 'a=% double=%', a, a * 2;" //
                + "   RAISE WARNING 'still running';" //
                + "   RETURN a + 1;" //
                + " END; $$");
        executeQuery("SELECT plpp_notice(7)");
        assertEquals(8, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_raise_default() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'boom'; RETURN 0; END; $$");
        assertSqlState("P0001", "SELECT plpp_raise_default()");

        executeUpdate("CREATE FUNCTION plpp_raise_errcode() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'dup' USING ERRCODE = '23505'; RETURN 0; END; $$");
        assertSqlState("23505", "SELECT plpp_raise_errcode()");
    }

    // RETURN表达式按函数声明的返回类型转换：INT到BIGINT、VARCHAR到VARCHAR
    @Test
    public void testReturnTypeConversion() throws Exception {
        dropFunctions("plpp_ret_bigint", "plpp_ret_varchar");

        executeUpdate("CREATE FUNCTION plpp_ret_bigint(a INT) RETURNS BIGINT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN a; END; $$");
        executeQuery("SELECT plpp_ret_bigint(42)");
        assertEquals(42L, getLongValue(1));
        // 返回类型是BIGINT，INT最大值加1不会溢出
        executeQuery("SELECT plpp_ret_bigint(2147483647) + 1");
        assertEquals(2147483648L, getLongValue(1));

        executeUpdate("CREATE FUNCTION plpp_ret_varchar(s VARCHAR) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN s || '!'; END; $$");
        executeQuery("SELECT plpp_ret_varchar('hi'), plpp_ret_varchar('')");
        assertEquals("hi!", getStringValue(1));
        assertEquals("!", getStringValue(2));
    }

    // 参数可以在表达式、WHERE和DECLARE的默认值里引用
    @Test
    public void testParameterInExpressionWhereAndDeclare() throws Exception {
        dropFunctions("plpp_param_expr", "plpp_param_default", "plpp_param_where");
        executeUpdate("DROP TABLE IF EXISTS plpp_param");
        executeUpdate("CREATE TABLE plpp_param(id INT PRIMARY KEY, v INT)");
        executeUpdate("INSERT INTO plpp_param VALUES(1, 10), (2, 20), (3, 30)");

        executeUpdate("CREATE FUNCTION plpp_param_expr(p INT, q INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN p * 10 + q; END; $$");
        executeQuery("SELECT plpp_param_expr(2, 3)");
        assertEquals(23, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_param_default(p INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT := p + 1; y INT := x * 2;" // 默认值里能看到参数和前面声明的变量
                + " BEGIN RETURN y; END; $$");
        executeQuery("SELECT plpp_param_default(3)");
        assertEquals(8, getIntValue(1));

        executeUpdate("CREATE FUNCTION plpp_param_where(lo INT, hi INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT := 0; s INT := 0;" //
                + " BEGIN" //
                + "   SELECT count(*), sum(v) INTO n, s FROM plpp_param WHERE id >= lo AND id <= hi;" //
                + "   RETURN n * 100 + s;" //
                + " END; $$");
        executeQuery("SELECT plpp_param_where(1, 2)");
        assertEquals(230, getIntValue(1));
    }

    // 同一个SELECT里多次调用同一个函数，每次调用的局部变量互不影响
    @Test
    public void testIndependentFunctionInvocations() throws Exception {
        dropFunctions("plpp_state");

        executeUpdate("CREATE FUNCTION plpp_state(a INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE s VARCHAR := 'start';" //
                + " BEGIN" //
                + "   s := s || ':' || a;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plpp_state(1), plpp_state(2), plpp_state(1)");
        assertEquals("start:1", getStringValue(1));
        assertEquals("start:2", getStringValue(2));
        assertEquals("start:1", getStringValue(3));

        // 连续调用之间也没有状态残留
        executeQuery("SELECT plpp_state(9)");
        assertEquals("start:9", getStringValue(1));
        executeQuery("SELECT plpp_state(9)");
        assertEquals("start:9", getStringValue(1));
    }

    private void dropFunctions(String... names) {
        for (String name : names)
            executeUpdate("DROP FUNCTION IF EXISTS " + name);
    }

    private void assertSqlState(String expected, String sql) throws Exception {
        assertEquals("SQLSTATE of: " + sql, expected, getSqlState(sql));
    }

    // 执行SQL并直接返回异常的SQLSTATE，语句正常执行完时返回null
    private String getSqlState(String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet resultSet = s.executeQuery(sql)) {
            while (resultSet.next()) {
                // 能正常执行完就说明没有抛出异常
            }
        } catch (SQLException e) {
            return e.getSQLState();
        }
        return null;
    }
}
