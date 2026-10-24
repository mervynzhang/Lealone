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

    // 大结果集游标：OPEN不复制行，顺序方向流式读，回退方向走LocalResult的落盘缓冲
    @Test
    public void testCursorStreaming() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_cur_big");
        executeUpdate("CREATE TABLE plp_cur_big(a INT)");
        // 超过maxMemoryRows(默认10万)让结果落到临时文件
        executeUpdate("INSERT INTO plp_cur_big SELECT X FROM SYSTEM_RANGE(1, 150000)");

        executeUpdate(
                "CREATE OR REPLACE FUNCTION plp_cur_big_sum(firstN INT) RETURNS INT LANGUAGE plpgsql AS $$"
                        + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_big ORDER BY a;" //
                        + "   r RECORD; n INT := 0; i INT;" //
                        + " BEGIN" //
                        + "   OPEN c;" //
                        + "   FOR i IN 1 .. firstN LOOP" //
                        + "     FETCH c INTO r;" //
                        + "     n := n + r.a;" //
                        + "   END LOOP;" //
                        + "   CLOSE c;" //
                        + "   RETURN n;" //
                        + " END; $$");
        executeQuery("SELECT plp_cur_big_sum(3)");
        assertEquals(6, getIntValue(1));

        // FETCH ALL只对剩余行计数，不把整个结果集搬进内存
        executeUpdate(
                "CREATE OR REPLACE FUNCTION plp_cur_big_count() RETURNS INT LANGUAGE plpgsql AS $$"
                        + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_big ORDER BY a;" //
                        + "   r RECORD; n INT;" //
                        + " BEGIN" //
                        + "   OPEN c;" //
                        + "   FETCH c INTO r;" //
                        + "   FETCH ALL FROM c INTO r;" //
                        + "   GET DIAGNOSTICS n = ROW_COUNT;" //
                        + "   CLOSE c;" //
                        + "   RETURN n;" //
                        + " END; $$");
        executeQuery("SELECT plp_cur_big_count()");
        assertEquals(149999, getIntValue(1));

        // 回退方向：ABSOLUTE/PRIOR/BACKWARD/FIRST/LAST，落盘结果也要能从头重放
        executeUpdate(
                "CREATE OR REPLACE FUNCTION plp_cur_big_backward() RETURNS INT LANGUAGE plpgsql AS $$"
                        + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_big ORDER BY a;" //
                        + "   r RECORD; s INT := 0;" //
                        + " BEGIN" //
                        + "   OPEN c;" //
                        + "   FETCH c INTO r; s := s + r.a;" // 1
                        + "   FETCH ABSOLUTE 100000 FROM c INTO r; s := s + r.a;" // 100000
                        + "   FETCH PRIOR FROM c INTO r; s := s + r.a;" // 99999
                        + "   FETCH BACKWARD 2 FROM c INTO r; s := s + r.a;" // 99997
                        + "   FETCH FIRST FROM c INTO r; s := s + r.a;" // 1
                        + "   FETCH LAST FROM c INTO r; s := s + r.a;" // 150000
                        + "   CLOSE c;" //
                        + "   RETURN s;" //
                        + " END; $$");
        executeQuery("SELECT plp_cur_big_backward()");
        assertEquals(1 + 100000 + 99999 + 99997 + 1 + 150000, getIntValue(1));
    }

    // 门户要跨语句存活：函数退出时把结果物化，之后在同一个事务里还能继续FETCH
    @Test
    public void testCursorPortalAcrossStatements() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_cur_portal");
        executeUpdate("CREATE TABLE plp_cur_portal(a INT)");
        executeUpdate("INSERT INTO plp_cur_portal SELECT X FROM SYSTEM_RANGE(1, 150000)");
        executeUpdate(
                "CREATE OR REPLACE FUNCTION plp_portal_open(n INT) RETURNS refcursor LANGUAGE plpgsql AS $$"
                        + " DECLARE rc refcursor; r RECORD; i INT;" //
                        + " BEGIN" //
                        + "   OPEN rc FOR SELECT a FROM plp_cur_portal ORDER BY a;" //
                        + "   FOR i IN 1 .. n LOOP FETCH rc INTO r; END LOOP;" //
                        + "   RETURN rc;" //
                        + " END; $$");
        executeUpdate(
                "CREATE OR REPLACE FUNCTION plp_portal_fetch(rc refcursor) RETURNS INT LANGUAGE plpgsql AS $$"
                        + " DECLARE x INT; BEGIN FETCH NEXT FROM rc INTO x; RETURN x; END; $$");
        conn.setAutoCommit(false);
        try {
            String portal;
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT plp_portal_open(10)");
                rs.next();
                portal = rs.getString(1);
                rs.close();
            }
            assertNotNull(portal);
            // 同一事务里的下一条语句继续读同一个门户
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT plp_portal_fetch('" + portal + "')");
                rs.next();
                assertEquals(11, rs.getInt(1));
                rs.close();
            }
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
        }
        // 事务结束后门户被关闭
        assertSqlState("34000", "SELECT plp_portal_fetch('" + "plp_gone" + "')");
    }

    // DECLARE CURSOR / OPEN / FETCH / CLOSE 和refcursor门户
    @Test
    public void testCursor() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_cur_t");
        executeUpdate("CREATE TABLE plp_cur_t(a INT, b INT)");
        executeUpdate("INSERT INTO plp_cur_t VALUES(5, 10)");
        executeUpdate("INSERT INTO plp_cur_t VALUES(50, 100)");
        executeUpdate("INSERT INTO plp_cur_t VALUES(500, 1000)");

        // 基本用法：OPEN/FETCH NEXT/CLOSE，FETCH之后FOUND和ROW_COUNT
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_basic() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_t ORDER BY a; x INT; n INT;" //
                + " BEGIN" //
                + "   OPEN c;" //
                + "   FETCH c INTO x;" //
                + "   IF NOT FOUND THEN RETURN -1; END IF;" //
                + "   n := x;" //
                + "   FETCH NEXT FROM c INTO x;" //
                + "   n := n + x;" //
                + "   CLOSE c;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_basic()");
        assertEquals(55, getIntValue(1));

        // 方向：LAST/FIRST/PRIOR/ABSOLUTE/ALL
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_direction() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_t ORDER BY a; x INT; s INT := 0;" //
                + " BEGIN" //
                + "   OPEN c;" //
                + "   FETCH LAST FROM c INTO x; s := x;" //
                + "   FETCH FIRST FROM c INTO x; s := s + x;" //
                + "   FETCH ABSOLUTE 2 FROM c INTO x; s := s + x;" //
                + "   FETCH PRIOR FROM c INTO x; s := s + x;" //
                + "   CLOSE c;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_direction()");
        assertEquals(560, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_all() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_t ORDER BY a;" //
                + "   x INT; n INT := 0;" //
                + " BEGIN" //
                + "   OPEN c;" //
                + "   FETCH ALL FROM c INTO x;" //
                + "   GET DIAGNOSTICS n = ROW_COUNT;" //
                + "   CLOSE c;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_all()");
        assertEquals(3, getIntValue(1));

        // 参数化游标 + 命名实参
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_param(p1 INT, p2 INT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR (minA INT, minB INT) FOR SELECT * FROM plp_cur_t WHERE a > minA AND b > minB;" //
                + "   r RECORD;" //
                + " BEGIN" //
                + "   OPEN c(p1, p2);" //
                + "   FETCH c INTO r;" //
                + "   CLOSE c;" //
                + "   RETURN FOUND;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_param(20000, 20000)");
        assertFalse(getBooleanValue(1));
        executeQuery("SELECT plp_cur_param(20, 20)");
        assertTrue(getBooleanValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_named(p1 INT, p2 INT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR (minA INT, minB INT) FOR SELECT * FROM plp_cur_t WHERE a > minA AND b > minB;" //
                + "   r RECORD;" //
                + " BEGIN" //
                + "   OPEN c(minB := p2, minA := p1);" //
                + "   FETCH c INTO r;" //
                + "   CLOSE c;" //
                + "   RETURN FOUND;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_named(20, 20)");
        assertTrue(getBooleanValue(1));
        executeQuery("SELECT plp_cur_named(20000, 20000)");
        assertFalse(getBooleanValue(1));

        // FOR rec IN c LOOP
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_loop() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a, b FROM plp_cur_t ORDER BY a; r RECORD; s INT := 0;" //
                + " BEGIN" //
                + "   OPEN c;" //
                + "   FOR r IN c LOOP" //
                + "     s := s + r.a + r.b;" //
                + "   END LOOP;" //
                + "   CLOSE c;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_cur_loop()");
        assertEquals(1665, getIntValue(1));

        // refcursor：函数返回门户名，另一个函数按名字FETCH
        executeUpdate("CREATE OR REPLACE FUNCTION plp_refcursor_new() RETURNS refcursor LANGUAGE plpgsql AS $$"
                + " DECLARE rc refcursor;" //
                + " BEGIN" //
                + "   OPEN rc FOR SELECT a FROM plp_cur_t ORDER BY a;" //
                + "   RETURN rc;" //
                + " END; $$");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_refcursor_use(rc refcursor) RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT;" //
                + " BEGIN" //
                + "   FETCH NEXT FROM rc INTO x;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plp_refcursor_use(plp_refcursor_new())");
        assertEquals(5, getIntValue(1));

        // 指定名字的门户：传参进去，OPEN ... FOR，返回同一个名字
        executeUpdate("CREATE OR REPLACE FUNCTION plp_refcursor_named(rc refcursor) RETURNS refcursor LANGUAGE plpgsql AS $$"
                + " BEGIN OPEN rc FOR SELECT a FROM plp_cur_t ORDER BY a; RETURN rc; END; $$");
        executeQuery("SELECT plp_refcursor_named('plp_portal1')");
        assertEquals("plp_portal1", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION plp_refcursor_fetch_named() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE rc refcursor; x INT;" //
                + " BEGIN" //
                + "   rc := plp_refcursor_named('plp_portal2');" //
                + "   FETCH NEXT FROM rc INTO x;" //
                + "   FETCH NEXT FROM rc INTO x;" //
                + "   CLOSE rc;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plp_refcursor_fetch_named()");
        assertEquals(50, getIntValue(1));

        // 未打开就FETCH报34000，重复OPEN报55000
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_notopen() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_t; x INT;" //
                + " BEGIN FETCH c INTO x; RETURN x; END; $$");
        assertSqlState("34000", "SELECT plp_cur_notopen()");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_cur_reopen() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE c CURSOR FOR SELECT a FROM plp_cur_t; x INT;" //
                + " BEGIN OPEN c; OPEN c; RETURN 0; END; $$");
        assertSqlState("55000", "SELECT plp_cur_reopen()");
    }

    // FOR rec IN <query> LOOP：查询结果逐行赋给record/row变量
    @Test
    public void testForInQuery() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_q_t");
        executeUpdate("CREATE TABLE plp_q_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_q_t VALUES(1, 'a')");
        executeUpdate("INSERT INTO plp_q_t VALUES(2, 'b')");
        executeUpdate("INSERT INTO plp_q_t VALUES(3, 'c')");

        // %ROWTYPE变量做循环目标
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_rowtype() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_q_t%ROWTYPE; s INT := 0;" //
                + " BEGIN" //
                + "   FOR r IN SELECT * FROM plp_q_t ORDER BY id LOOP" //
                + "     s := s + r.id;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_for_q_rowtype()");
        assertEquals(6, getIntValue(1));

        // plain record做循环目标，只取部分列时按查询的列绑定行结构
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_record() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r RECORD; s VARCHAR(100) := '';" //
                + " BEGIN" //
                + "   FOR r IN SELECT id, name FROM plp_q_t ORDER BY id LOOP" //
                + "     s := s || r.name;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_for_q_record()");
        assertEquals("abc", getStringValue(1));

        // 没有声明的目标按PostgreSQL的规则隐式声明成record变量
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_implicit() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE s INT := 0;" //
                + " BEGIN" //
                + "   FOR r IN SELECT id FROM plp_q_t ORDER BY id LOOP" //
                + "     s := s + r.id;" //
                + "   END LOOP;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_for_q_implicit()");
        assertEquals(6, getIntValue(1));

        // EXIT和带标签的CONTINUE在查询循环里同样生效
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_exit() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r RECORD; s INT := 0;" //
                + " BEGIN" //
                + "   <<outer>>" //
                + "   FOR r IN SELECT id FROM plp_q_t ORDER BY id LOOP" //
                + "     CONTINUE outer WHEN r.id = 2;" //
                + "     EXIT WHEN r.id > 2;" //
                + "     s := s + r.id;" //
                + "   END LOOP outer;" //
                + "   RETURN s;" //
                + " END; $$");
        executeQuery("SELECT plp_for_q_exit()");
        assertEquals(1, getIntValue(1));

        // 循环结束时按是否迭代过设置FOUND
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_found(p INT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$"
                + " DECLARE r RECORD;" //
                + " BEGIN" //
                + "   FOR r IN SELECT id FROM plp_q_t WHERE id > p LOOP" //
                + "     RETURN TRUE;" //
                + "   END LOOP;" //
                + "   RETURN FOUND;" //
                + " END; $$");
        executeQuery("SELECT plp_for_q_found(0)");
        assertTrue(getBooleanValue(1));
        executeQuery("SELECT plp_for_q_found(99)");
        assertFalse(getBooleanValue(1));

        // 查询循环 + RETURN NEXT 返回SETOF <表行类型>
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_setof() RETURNS SETOF plp_q_t LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_q_t%ROWTYPE;" //
                + " BEGIN" //
                + "   FOR r IN SELECT * FROM plp_q_t ORDER BY id LOOP" //
                + "     RETURN NEXT r;" //
                + "   END LOOP;" //
                + "   RETURN;" //
                + " END; $$");
        executeQuery("SELECT COUNT(*) FROM plp_for_q_setof()");
        assertEquals(3, getIntValue(1));

        // 目标变量不是record/row时报42804
        executeUpdate("CREATE OR REPLACE FUNCTION plp_for_q_badtarget() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN SELECT id FROM plp_q_t LOOP" //
                + "     RETURN i;" //
                + "   END LOOP;" //
                + "   RETURN 0;" //
                + " END; $$");
        assertSqlState("42804", "SELECT plp_for_q_badtarget()");
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
                + "   PERFORM * FROM plp_found_t WHERE a = 7777;" //
                + "   IF NOT FOUND THEN r := r + 64; END IF;" //
                + "   RETURN r;" //
                + " END; $$");
        executeQuery("SELECT plp_found()");
        assertEquals(127, getIntValue(1));

        // 空DECLARE段也要能解析
        executeUpdate("CREATE OR REPLACE FUNCTION plp_found_empty_declare() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE" //
                + " BEGIN" //
                + "   INSERT INTO plp_found_t VALUES(2);" //
                + "   IF FOUND THEN RETURN 1; END IF;" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plp_found_empty_declare()");
        assertEquals(1, getIntValue(1));
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

        // 给record字段赋值
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_field_set() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r record;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_rec_t WHERE id = 1;" //
                + "   r.name := 'changed';" //
                + "   RETURN r.name;" //
                + " END; $$");
        executeQuery("SELECT plp_rec_field_set()");
        assertEquals("changed", getStringValue(1));

        // record变量名和聚合函数同名时，函数调用不能被当成record变量
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_func_name() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE sum record;" //
                + " BEGIN" //
                + "   SELECT * INTO sum FROM plp_rec_t WHERE id = 1;" //
                + "   RETURN (SELECT SUM(id) FROM plp_rec_t WHERE id = 1);" //
                + " END; $$");
        executeQuery("SELECT plp_rec_func_name()");
        assertEquals(1, getIntValue(1));

        // 没有行时%ROWTYPE是一行NULL，字段读取得到NULL
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_norow(p INT) RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_rec_t WHERE id = p;" //
                + "   RETURN COALESCE(r.name, 'NULL');" //
                + " END; $$");
        executeQuery("SELECT plp_rec_norow(99)");
        assertEquals("NULL", getStringValue(1));

        // 从未赋值的plain record取字段才是55000
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_unassigned() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r record;" //
                + " BEGIN RETURN r.name; END; $$");
        assertSqlState("55000", "SELECT plp_rec_unassigned()");

        // %ROWTYPE声明后字段就是NULL
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_rowtype_null() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE;" //
                + " BEGIN RETURN COALESCE(r.name, 'NULL'); END; $$");
        executeQuery("SELECT plp_rec_rowtype_null()");
        assertEquals("NULL", getStringValue(1));

        // plain record可以按新查询重新绑定行结构
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_rebind() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r record;" //
                + " BEGIN" //
                + "   SELECT id, name INTO r FROM plp_rec_t WHERE id = 1;" //
                + "   SELECT id, name, CAST(42 AS INT) AS extra INTO r FROM plp_rec_t WHERE id = 1;" //
                + "   RETURN r.extra;" //
                + " END; $$");
        executeQuery("SELECT plp_rec_rebind()");
        assertEquals(42, getIntValue(1));

        // %ROWTYPE数组支持按下标写字段和读字段
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_array() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE r plp_rec_t%ROWTYPE[];" //
                + " BEGIN r[1].id := 7; RETURN r[1].id; END; $$");
        executeQuery("SELECT plp_rec_array()");
        assertEquals(7, getIntValue(1));

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

        // 局部变量遮蔽OUT参数时，返回的仍是OUT参数而不是同名局部变量
        executeUpdate("CREATE OR REPLACE FUNCTION plp_out_shadow(OUT j INT) AS $$"
                + " DECLARE j INT := 99; BEGIN RETURN; END $$ LANGUAGE plpgsql");
        executeQuery("SELECT plp_out_shadow() IS NULL");
        assertTrue(getBooleanValue(1));
    }

    // RETURNS TABLE：RETURN QUERY和RETURN NEXT都往结果集追加行
    @Test
    public void testReturnsTable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_setof_t");
        executeUpdate("CREATE TABLE plp_setof_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_setof_t VALUES(1, 'a'), (2, 'b'), (3, 'c')");

        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_query(p INT) RETURNS TABLE(id INT, name VARCHAR) LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RETURN QUERY SELECT id, name FROM plp_setof_t WHERE id >= p ORDER BY id;" //
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

        // 单列表可以用表达式，多列可以用record变量
        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_one() RETURNS TABLE(id INT) LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN NEXT 5; RETURN NEXT 6; END; $$");
        assertEquals("[5, 6]",
                queryIntList("SELECT * FROM plp_table_one() ORDER BY 1").toString());

        executeUpdate("CREATE OR REPLACE FUNCTION plp_table_record(p INT) RETURNS TABLE(id INT, name VARCHAR) LANGUAGE plpgsql AS $$"
                + " DECLARE r record;" //
                + " BEGIN" //
                + "   SELECT * INTO r FROM plp_setof_t t WHERE t.id = p;" //
                + "   RETURN NEXT r;" //
                + " END; $$");
        assertEquals("[2:b]",
                queryRowList("SELECT id, name FROM plp_table_record(2)").toString());

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
        // 值上下文里整个record用ValueArray表示
        executeQuery("SELECT plp_multi_out(42)");
        assertEquals("(43, foo)", getStringValue(1));
        executeQuery("SELECT (plp_multi_out(42)).j");
        assertEquals(43, getIntValue(1));
        executeQuery("SELECT (plp_multi_out(42)).k");
        assertEquals("foo", getStringValue(1));
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

    // 标签和表名/别名同名时，label.column要回落到SQL解析
    @Test
    public void testLabelFallsBackToSql() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_label_alias_t");
        executeUpdate("CREATE TABLE plp_label_alias_t(id INT)");
        executeUpdate("INSERT INTO plp_label_alias_t VALUES(7)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_label_alias() RETURNS INT LANGUAGE plpgsql AS $$"
                + " <<plp_label_alias_t>>" //
                + " DECLARE x INT;" //
                + " BEGIN" //
                + "   SELECT plp_label_alias_t.id INTO x FROM plp_label_alias_t;" //
                + "   RETURN x;" //
                + " END; $$");
        executeQuery("SELECT plp_label_alias()");
        assertEquals(7, getIntValue(1));
    }

    // RETURN QUERY和FOR循环也要按PG语义更新FOUND
    @Test
    public void testFoundAfterReturnQueryAndFor() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_found_q_t");
        executeUpdate("CREATE TABLE plp_found_q_t(a INT)");
        executeUpdate("INSERT INTO plp_found_q_t VALUES(1)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_found_return_query(p INT) RETURNS SETOF INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   RETURN QUERY SELECT a FROM plp_found_q_t WHERE a = p;" //
                + "   IF FOUND THEN RETURN NEXT 99; END IF;" //
                + "   RETURN NEXT 1;" //
                + " END; $$");
        assertEquals("[1, 1, 99]",
                queryIntList("SELECT * FROM plp_found_return_query(1) ORDER BY 1").toString());
        assertEquals("[1]",
                queryIntList("SELECT * FROM plp_found_return_query(2) ORDER BY 1").toString());

        executeUpdate("CREATE OR REPLACE FUNCTION plp_found_for(n INT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   FOR i IN 1..n LOOP" //
                + "     PERFORM * FROM plp_found_q_t WHERE a = 9999;" //
                + "   END LOOP;" //
                + "   RETURN FOUND;" //
                + " END; $$");
        executeQuery("SELECT plp_found_for(1)");
        assertTrue(getBooleanValue(1));
        executeQuery("SELECT plp_found_for(0)");
        assertFalse(getBooleanValue(1));
    }

    // ROW_COUNT是最近一条SQL命令的值，utility语句为0，INTO只算第一行
    @Test
    public void testRowCountRecentStatement() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_rc_t2");
        executeUpdate("CREATE TABLE plp_rc_t2(a INT)");
        executeUpdate("INSERT INTO plp_rc_t2 VALUES(1), (2), (3)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rc_utility() RETURNS BIGINT LANGUAGE plpgsql AS $$"
                + " DECLARE n BIGINT;" //
                + " BEGIN" //
                + "   INSERT INTO plp_rc_t2 VALUES(4);" //
                + "   CREATE TABLE IF NOT EXISTS plp_rc_tmp(a INT);" //
                + "   GET DIAGNOSTICS n = ROW_COUNT;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_rc_utility()");
        assertEquals(0, getLongValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION plp_rc_into() RETURNS BIGINT LANGUAGE plpgsql AS $$"
                + " DECLARE x INT; n BIGINT;" //
                + " BEGIN" //
                + "   SELECT a INTO x FROM plp_rc_t2;" //
                + "   GET DIAGNOSTICS n = ROW_COUNT;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_rc_into()");
        assertEquals(1, getLongValue(1));
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

    // record变量支持IS [NOT] NULL和RAISE整行
    @Test
    public void testRecordIsNullAndRaise() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_rec_null_t");
        executeUpdate("CREATE TABLE plp_rec_null_t(id INT, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_rec_null_t VALUES(1, 'alice')");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_nullshapes() RETURNS INT "
                + "LANGUAGE plpgsql AS $$ DECLARE r plp_rec_null_t%ROWTYPE; n INT := 0;" //
                + " BEGIN" //
                + "   IF r IS NULL THEN n := n + 1; END IF;" //
                + "   SELECT * INTO r FROM plp_rec_null_t WHERE id = 1;" //
                + "   IF r IS NOT NULL THEN n := n + 2; END IF;" //
                + "   RAISE NOTICE 'row = %', r;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plp_rec_nullshapes()");
        assertEquals(3, getIntValue(1));

        // 未赋值的plain record也是NULL
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_plain_null() RETURNS BOOLEAN "
                + "LANGUAGE plpgsql AS $$ DECLARE r record; BEGIN RETURN r IS NULL; END; $$");
        executeQuery("SELECT plp_rec_plain_null()");
        assertTrue(getBooleanValue(1));
    }

    // 自定义复合类型可以创建、使用和删除
    @Test
    public void testCreateCompositeType() throws Exception {
        executeUpdate("DROP TYPE IF EXISTS plp_ct");
        executeUpdate("CREATE TYPE plp_ct AS (a INT, b VARCHAR)");
        executeUpdate("CREATE FUNCTION plp_ct_f(i INT) RETURNS plp_ct LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN ROW(i, 'x')::plp_ct; END; $$");
        executeQuery("SELECT (plp_ct_f(1)).b");
        assertEquals("x", getStringValue(1));
        executeUpdate("DROP FUNCTION plp_ct_f");
        executeUpdate("DROP TYPE plp_ct");
    }

    // RETURNS RECORD由OUT参数定义返回行，支持SELECT *和复合字段访问
    @Test
    public void testReturnsRecord() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_ret(i INT, OUT a INT, OUT b VARCHAR) "
                + "RETURNS RECORD LANGUAGE plpgsql AS $$" //
                + " BEGIN a := i; b := 'x'; END; $$");
        executeQuery("SELECT plp_rec_ret(1)");
        assertEquals("(1, x)", getStringValue(1));
        try (java.sql.ResultSet result = stmt.executeQuery("SELECT * FROM plp_rec_ret(1)")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertEquals("x", result.getString(2));
            assertEquals("A", result.getMetaData().getColumnName(1));
            assertEquals("B", result.getMetaData().getColumnName(2));
            assertFalse(result.next());
        }
        executeQuery("SELECT (plp_rec_ret(2)).a");
        assertEquals(2, getIntValue(1));
        executeQuery("SELECT (plp_rec_ret(3)).b");
        assertEquals("x", getStringValue(1));
        executeQuery("SELECT (plp_rec_ret(4)).a + 1");
        assertEquals(5, getIntValue(1));

        // 单OUT参数也能用RETURNS RECORD返回一列结果集
        executeUpdate("CREATE OR REPLACE FUNCTION plp_rec_one(i INT, OUT a INT) "
                + "RETURNS RECORD LANGUAGE plpgsql AS $$ BEGIN a := i * 2; END; $$");
        assertEquals("[6]", queryIntList("SELECT * FROM plp_rec_one(3)").toString());
        executeQuery("SELECT plp_rec_one(3)");
        assertEquals("(6)", getStringValue(1));
        executeQuery("SELECT (plp_rec_one(3)).a");
        assertEquals(6, getIntValue(1));
        String oneSql = getCreateSql("PLP_REC_ONE");
        assertNotNull(oneSql);
        assertTrue(oneSql, oneSql.contains("RETURNS RECORD"));
        executeUpdate("DROP FUNCTION plp_rec_one");
        executeUpdate(oneSql);
        executeQuery("SELECT (plp_rec_one(3)).a");
        assertEquals(6, getIntValue(1));

        // getCreateSQL必须带上RETURNS RECORD并能回放
        String createSql = getCreateSql("PLP_REC_RET");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains("RETURNS RECORD"));
        executeUpdate("DROP FUNCTION plp_rec_ret");
        executeUpdate(createSql);
        assertEquals("[8:x]",
                queryRowList("SELECT a, b FROM plp_rec_ret(8)").toString());

        // SQL语言的函数同样可以用RETURNS RECORD
        executeUpdate("CREATE OR REPLACE FUNCTION plp_sql_rec(i INT, OUT a INT, OUT b VARCHAR) "
                + "RETURNS RECORD LANGUAGE SQL AS $$ SELECT i, 'y' $$");
        assertEquals("[9:y]",
                queryRowList("SELECT a, b FROM plp_sql_rec(9)").toString());
        executeQuery("SELECT (plp_sql_rec(9)).a");
        assertEquals(9, getIntValue(1));

        // 没有OUT参数时RETURNS RECORD没有行结构，明确不支持
        executeUpdateThanAssertErrorCode(
                "CREATE FUNCTION plp_rec_noout() RETURNS RECORD LANGUAGE plpgsql AS $$ BEGIN RETURN; END; $$",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // RETURNS SETOF <表名>返回表的所有列
    @Test
    public void testReturnsSetofTable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_row_t");
        executeUpdate("CREATE TABLE plp_row_t(id INT PRIMARY KEY, name VARCHAR(20))");
        executeUpdate("INSERT INTO plp_row_t VALUES(1, 'a'), (2, 'b')");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_all_rows() RETURNS SETOF plp_row_t "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN QUERY SELECT * FROM plp_row_t ORDER BY id; END; $$");
        assertEquals("[1:a, 2:b]",
                queryRowList("SELECT id, name FROM plp_all_rows() ORDER BY id").toString());

        String createSql = getCreateSql("PLP_ALL_ROWS");
        assertNotNull(createSql);
        assertTrue(createSql, createSql.contains("SETOF"));
        executeUpdate("DROP FUNCTION plp_all_rows");
        executeUpdate(createSql);
        assertEquals("[1:a, 2:b]",
                queryRowList("SELECT id, name FROM plp_all_rows() ORDER BY id").toString());

        // 带引号的表名也能解析和回放
        executeUpdate("DROP TABLE IF EXISTS \"plp_quoted_t\"");
        executeUpdate("CREATE TABLE \"plp_quoted_t\"(id INT)");
        executeUpdate("INSERT INTO \"plp_quoted_t\" VALUES(5)");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_quoted_rows() RETURNS SETOF \"plp_quoted_t\" "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN QUERY SELECT * FROM \"plp_quoted_t\"; END; $$");
        assertEquals("[5]", queryIntList("SELECT id FROM plp_quoted_rows()").toString());

        // SQL语言的函数同样可以返回SETOF <表名>
        executeUpdate("CREATE OR REPLACE FUNCTION plp_sql_rows() RETURNS SETOF plp_row_t "
                + "LANGUAGE SQL AS $$ SELECT * FROM plp_row_t $$");
        assertEquals("[1:a, 2:b]",
                queryRowList("SELECT id, name FROM plp_sql_rows() ORDER BY id").toString());

        // RETURN NEXT用record变量给表行类型追加一行
        executeUpdate("CREATE OR REPLACE FUNCTION plp_next_row(p INT) RETURNS SETOF plp_row_t "
                + "LANGUAGE plpgsql AS $$ DECLARE r record;" //
                + " BEGIN SELECT * INTO r FROM plp_row_t WHERE plp_row_t.id = p; RETURN NEXT r; END; $$");
        assertEquals("[2:b]",
                queryRowList("SELECT id, name FROM plp_next_row(2)").toString());
    }

    // %ROWTYPE数组支持声明、下标读写和字段访问
    @Test
    public void testRowTypeArray() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plp_arr_t");
        executeUpdate("CREATE TABLE plp_arr_t(id INT, name VARCHAR(20))");
        executeUpdate("CREATE OR REPLACE FUNCTION plp_arr1() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE arr plp_arr_t%ROWTYPE[];" //
                + " BEGIN arr[1].id := 11; RETURN arr[1].id; END; $$");
        executeQuery("SELECT plp_arr1()");
        assertEquals(11, getIntValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION plp_arr2() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE arr plp_arr_t[]%ROWTYPE;" //
                + " BEGIN arr[1] := ROW(2, 'b'); RETURN arr[1].name; END; $$");
        executeQuery("SELECT plp_arr2()");
        assertEquals("b", getStringValue(1));
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
        String prefix = "CREATE FUNCTION " + functionName + "(";
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
