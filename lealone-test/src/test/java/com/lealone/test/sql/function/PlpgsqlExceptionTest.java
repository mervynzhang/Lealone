/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

/**
 * PL/pgSQL的EXCEPTION handler对应PostgreSQL的隐式子事务:
 * 进入带EXCEPTION段的块时打一个保存点，handler捕获异常之前块内的DML修改全部回滚，
 * 块正常结束/handler里的DML则保留。
 */
public class PlpgsqlExceptionTest extends SqlTestBase {

    private static final String CHECK_DB = "plpg_exc_db";

    public PlpgsqlExceptionTest() {
        setEmbedded(true);
    }

    // RAISE之前插入的行，被handler捕获之后必须回滚掉
    @Test
    public void testHandlerRollsBackBlockDml() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_raise() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plpx_raise()");
        assertEquals(0, getIntValue(1, true));
        assertEquals(0, countRows());

        // handler自己的INSERT要保留
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_handler_dml() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   INSERT INTO plpx_t VALUES(2, 'handler');" //
                + "   RETURN 1;" //
                + " END; $$");
        executeQuery("SELECT plpx_handler_dml()");
        assertEquals(1, getIntValue(1, true));
        assertEquals("handler", queryName(2));
        assertEquals(1, countRows());
        dropFunction("plpx_raise");
        dropFunction("plpx_handler_dml");
    }

    // SQL错误(不只是RAISE)也要回滚块内的DML
    @Test
    public void testSqlErrorRollsBackBlockDml() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_div() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   PERFORM 1 / 0;" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN division_by_zero THEN" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plpx_div()");
        assertEquals(0, getIntValue(1, true));
        assertEquals(0, countRows());
        dropFunction("plpx_div");
    }

    // 块正常结束时不能误回滚
    @Test
    public void testNormalExitKeepsDml() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_ok() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plpx_ok()");
        assertEquals(1, getIntValue(1, true));
        assertEquals(1, countRows());
        dropFunction("plpx_ok");
    }

    // 没有EXCEPTION段的块不打保存点: 正常结束保留DML，出错时由外层handler一起回滚
    @Test
    public void testBlockWithoutExceptionSection() throws Exception {
        prepareTable();
        // 内层块没有EXCEPTION段，正常结束时DML保留
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_nohandler_ok() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   BEGIN" //
                + "     INSERT INTO plpx_t VALUES(1, 'inner');" //
                + "   END;" //
                + "   INSERT INTO plpx_t VALUES(2, 'outer');" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plpx_nohandler_ok()");
        assertEquals(1, getIntValue(1, true));
        assertEquals(2, countRows());

        // 内层块没有EXCEPTION段，出错后由外层handler回滚到外层块入口
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_nohandler_err() RETURNS INT "
                + "LANGUAGE plpgsql AS $$"
                + " DECLARE n INT;" //
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(10, 'outer-before');" //
                + "   BEGIN" //
                + "     INSERT INTO plpx_t VALUES(11, 'inner');" //
                + "     RAISE EXCEPTION 'boom';" //
                + "   END;" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   SELECT COUNT(*) INTO n FROM plpx_t;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpx_nohandler_err()");
        // 两层块的DML都回滚了，只剩前面留下的2行
        assertEquals(2, getIntValue(1, true));
        assertEquals(2, countRows());
        dropFunction("plpx_nohandler_ok");
        dropFunction("plpx_nohandler_err");
    }

    // 循环里的块: 每一轮都有独立的保存点
    @Test
    public void testBlockInLoop() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_loop() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT; n INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 1..3 LOOP" //
                + "     BEGIN" //
                + "       INSERT INTO plpx_t VALUES(i, 'loop');" //
                + "       RAISE EXCEPTION 'boom %', i;" //
                + "     EXCEPTION WHEN OTHERS THEN" //
                + "       n := n + 1;" //
                + "     END;" //
                + "   END LOOP;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpx_loop()");
        assertEquals(3, getIntValue(1, true));
        assertEquals(0, countRows());

        // 循环里的handler插入补偿行时，每轮insert都要保留
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_loop_keep() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE i INT; n INT := 0;" //
                + " BEGIN" //
                + "   FOR i IN 1..3 LOOP" //
                + "     BEGIN" //
                + "       INSERT INTO plpx_t VALUES(i, 'loop');" //
                + "       RAISE EXCEPTION 'boom %', i;" //
                + "     EXCEPTION WHEN OTHERS THEN" //
                + "       INSERT INTO plpx_t VALUES(i + 10, 'handler');" //
                + "       n := n + 1;" //
                + "     END;" //
                + "   END LOOP;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpx_loop_keep()");
        assertEquals(3, getIntValue(1, true));
        assertEquals(3, countRows());
        assertEquals("handler", queryName(13));
        dropFunction("plpx_loop");
        dropFunction("plpx_loop_keep");
    }

    // 嵌套块: 内层handler只回滚内层块，外层块之前的DML要保留
    @Test
    public void testNestedBlocks() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_nested() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE n INT;" //
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'outer');" //
                + "   BEGIN" //
                + "     INSERT INTO plpx_t VALUES(2, 'inner');" //
                + "     RAISE EXCEPTION 'boom';" //
                + "   EXCEPTION WHEN OTHERS THEN" //
                + "     INSERT INTO plpx_t VALUES(3, 'inner-handler');" //
                + "   END;" //
                + "   SELECT COUNT(*) INTO n FROM plpx_t;" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpx_nested()");
        // 只有内层块的INSERT被回滚，外层的1和内层handler的3都在
        assertEquals(2, getIntValue(1, true));
        assertEquals(2, countRows());
        assertEquals("outer", queryName(1));
        assertEquals("inner-handler", queryName(3));
        dropFunction("plpx_nested");
    }

    // handler里再抛异常: 外层handler捕获时要把内层块和内层handler的修改一起回滚
    @Test
    public void testHandlerRaisesAgain() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_reraise() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'outer');" //
                + "   BEGIN" //
                + "     INSERT INTO plpx_t VALUES(2, 'inner');" //
                + "     RAISE EXCEPTION 'boom';" //
                + "   EXCEPTION WHEN OTHERS THEN" //
                + "     INSERT INTO plpx_t VALUES(3, 'inner-handler');" //
                + "     RAISE EXCEPTION 'boom again';" //
                + "   END;" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   INSERT INTO plpx_t VALUES(4, 'outer-handler');" //
                + "   RETURN 0;" //
                + " END; $$");
        executeQuery("SELECT plpx_reraise()");
        assertEquals(0, getIntValue(1, true));
        // 外层handler捕获时回滚到外层块入口: 外层块的1、内层块、内层handler全部回滚，只剩外层handler的4
        assertEquals(1, countRows());
        assertEquals("outer-handler", queryName(4));
        dropFunction("plpx_reraise");
    }

    // 回滚之后同一个主键可以再插入(handler里重写同一行)
    @Test
    public void testHandlerReinsertSameKey() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_reinsert() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   INSERT INTO plpx_t VALUES(1, 'handler');" //
                + "   RETURN 1;" //
                + " END; $$");
        executeQuery("SELECT plpx_reinsert()");
        assertEquals(1, getIntValue(1, true));
        assertEquals(1, countRows());
        assertEquals("handler", queryName(1));
        dropFunction("plpx_reinsert");
    }

    // 没有被handler捕获的异常: 整个函数体的修改都要回滚
    @Test
    public void testUnhandledExceptionRollsBackFunction() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_unhandled() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + "   RETURN 1;" //
                + " END; $$");
        SQLException e = getSqlException("SELECT plpx_unhandled()", conn);
        assertNotNull(e);
        assertEquals(0, countRows());
        dropFunction("plpx_unhandled");
    }

    // 外层事务的回滚/提交照常工作
    @Test
    public void testOuterTransaction() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_txn() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   INSERT INTO plpx_t VALUES(2, 'handler');" //
                + "   RETURN 1;" //
                + " END; $$");
        // 外层事务回滚，handler的INSERT也要一起回滚
        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO plpx_t VALUES(3, 'before')");
        executeQuery("SELECT plpx_txn()");
        assertEquals(1, getIntValue(1, true));
        conn.rollback();
        conn.setAutoCommit(true);
        // 用另一个连接确认外层回滚连函数体里的修改一起回滚了
        assertEquals(0, countRowsInNewConnection());

        // 外层事务提交后handler的INSERT要持久化
        conn.setAutoCommit(false);
        executeQuery("SELECT plpx_txn()");
        assertEquals(1, getIntValue(1, true));
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(1, countRows());
        assertEquals("handler", queryName(2));
        dropFunction("plpx_txn");
    }

    // handler里的RETURN仍然直接返回
    @Test
    public void testHandlerReturn() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_handler_return() RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(1, 'block');" //
                + "   RAISE EXCEPTION 'boom';" //
                + "   RETURN 1;" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   RETURN 7;" //
                + " END; $$");
        executeQuery("SELECT plpx_handler_return()");
        assertEquals(7, getIntValue(1, true));
        assertEquals(0, countRows());
        dropFunction("plpx_handler_return");
    }

    // 递归调用: 每层函数调用有自己的嵌套事务和保存点，handler在那层回滚
    @Test
    public void testRecursiveFunction() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_rec(n INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   IF n <= 0 THEN RETURN 0; END IF;" //
                + "   INSERT INTO plpx_t VALUES(n, 'rec');" //
                + "   IF n = 3 THEN RAISE EXCEPTION 'boom at %', n; END IF;" //
                + "   RETURN plpx_rec(n - 1);" //
                + " EXCEPTION WHEN OTHERS THEN" //
                + "   INSERT INTO plpx_t VALUES(n + 100, 'rec-handler');" //
                + "   RETURN n;" //
                + " END; $$");
        executeQuery("SELECT plpx_rec(3)");
        assertEquals(3, getIntValue(1, true));
        // 抛异常的这层插的行被回滚，只剩handler插的103
        assertEquals(1, countRows());
        assertEquals("rec-handler", queryName(103));
        dropFunction("plpx_rec");
    }

    // 一条语句里多次调用函数: 每次调用的函数体事务各自独立
    @Test
    public void testMultipleCallsInOneStatement() throws Exception {
        prepareTable();
        executeUpdate("CREATE OR REPLACE FUNCTION plpx_one(a INT) RETURNS INT LANGUAGE plpgsql AS $$"
                + " BEGIN" //
                + "   INSERT INTO plpx_t VALUES(a, 'x');" //
                + "   RETURN a;" //
                + " END; $$");
        executeQuery("SELECT SUM(plpx_one(a)) FROM (VALUES 1, 2, 3) v(a)");
        assertEquals(6, getIntValue(1, true));
        assertEquals(3, countRows());
        dropFunction("plpx_one");
    }

    private void prepareTable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS plpx_t");
        executeUpdate("CREATE TABLE plpx_t(id INT PRIMARY KEY, name VARCHAR(20))");
    }

    private int countRows() throws Exception {
        executeQuery("SELECT COUNT(*) FROM plpx_t");
        return getIntValue(1, true);
    }

    // 新连接里数行数，避免在事务里查询影响后面的rollback
    private int countRowsInNewConnection() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:lealone:embed:" + TestBase.TEST_DIR
                + "/" + TestBase.DEFAULT_DB_NAME + ";user=root;password=");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT COUNT(*) FROM plpx_t")) {
            r.next();
            return r.getInt(1);
        }
    }

    private String queryName(int id) throws Exception {
        executeQuery("SELECT name FROM plpx_t WHERE id = " + id);
        return rs.getString(1);
    }

    private void dropFunction(String name) {
        executeUpdate("DROP FUNCTION IF EXISTS " + name);
    }

    private static SQLException getSqlException(String sql, Connection c) {
        try (Statement statement = c.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return null;
            }
        } catch (SQLException e) {
            return e;
        }
    }
}
