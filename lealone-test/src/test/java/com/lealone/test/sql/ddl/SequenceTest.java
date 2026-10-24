/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 序列：min/max/cycle/耗尽、cache、DEFAULT 里用序列、并发 nextval。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。已确认不符合期望的断言放在 {@code @Ignore("BUG: ...")} 里。
 */
public class SequenceTest extends SqlAssertTestBase {

    private void drop(String name) {
        tryExecuteUpdate("DROP SEQUENCE IF EXISTS " + name);
    }

    @Test
    public void nextvalIncrements() {
        drop("seq_t1");
        executeUpdate("CREATE SEQUENCE seq_t1 START WITH 1 INCREMENT BY 1");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_t1')");
        assertQueryEquals(2L, "SELECT NEXTVAL('seq_t1')");
        assertQueryEquals(3L, "SELECT NEXTVAL('seq_t1')");
        assertQueryEquals(3L, "SELECT CURRVAL('seq_t1')");
        drop("seq_t1");
    }

    @Test
    public void nextValueForSyntax() {
        drop("seq_t2");
        executeUpdate("CREATE SEQUENCE seq_t2 START WITH 10 INCREMENT BY 2");
        // 标准语法 NEXT VALUE FOR 与 NEXTVAL('..') 等价
        assertQueryEquals(10L, "SELECT NEXT VALUE FOR seq_t2");
        assertQueryEquals(12L, "SELECT NEXTVAL('seq_t2')");
        assertQueryEquals(14L, "SELECT NEXT VALUE FOR seq_t2");
        drop("seq_t2");
    }

    @Test
    public void restartAndIncrement() {
        drop("seq_t3");
        executeUpdate("CREATE SEQUENCE seq_t3 START WITH 1 INCREMENT BY 1");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_t3')");
        executeUpdate("ALTER SEQUENCE seq_t3 RESTART WITH 5");
        assertQueryEquals(5L, "SELECT NEXTVAL('seq_t3')");
        // 与 H2 相同：修改 increment 之后，下一个值先按旧 increment 走一步，再按新 increment
        executeUpdate("ALTER SEQUENCE seq_t3 INCREMENT BY 2");
        assertQueryEquals(6L, "SELECT NEXTVAL('seq_t3')");
        assertQueryEquals(8L, "SELECT NEXTVAL('seq_t3')");
        drop("seq_t3");
    }

    @Test
    public void descendingSequence() {
        drop("seq_t4");
        executeUpdate(
                "CREATE SEQUENCE seq_t4 START WITH 100 INCREMENT BY -1 MINVALUE 1 MAXVALUE 100 CACHE 1");
        assertQueryEquals(100L, "SELECT NEXTVAL('seq_t4')");
        assertQueryEquals(99L, "SELECT NEXTVAL('seq_t4')");
        assertQueryEquals(98L, "SELECT NEXTVAL('seq_t4')");
        drop("seq_t4");
    }

    @Test
    public void sequenceAsColumnDefault() {
        drop("seq_t5");
        executeUpdate("CREATE SEQUENCE seq_t5 START WITH 1 INCREMENT BY 1");
        executeUpdate("DROP TABLE IF EXISTS seq_t5_tab");
        executeUpdate("CREATE TABLE seq_t5_tab(id bigint default NEXTVAL('seq_t5'), name varchar(10))");
        executeUpdate("INSERT INTO seq_t5_tab(name) VALUES('a')");
        executeUpdate("INSERT INTO seq_t5_tab(name) VALUES('b')");
        assertQueryEquals(1L, "SELECT id FROM seq_t5_tab WHERE name = 'a'");
        assertQueryEquals(2L, "SELECT id FROM seq_t5_tab WHERE name = 'b'");
        // 显式给值时不会消耗序列
        executeUpdate("INSERT INTO seq_t5_tab(id, name) VALUES(100, 'c')");
        assertQueryEquals(3L, "SELECT NEXTVAL('seq_t5')");
        executeUpdate("DROP TABLE seq_t5_tab");
        drop("seq_t5");
    }

    @Test
    public void sequenceWithCache() {
        drop("seq_t6");
        executeUpdate("CREATE SEQUENCE seq_t6 START WITH 1 INCREMENT BY 1 CACHE 10");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_t6')");
        assertQueryEquals(2L, "SELECT NEXTVAL('seq_t6')");
        assertQueryEquals(1L,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = 'SEQ_T6'");
        assertQueryEquals(2L,
                "SELECT CURRENT_VALUE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = 'SEQ_T6'");
        drop("seq_t6");
    }

    @Test
    public void concurrentNextval() throws Exception {
        drop("seq_t7");
        executeUpdate("CREATE SEQUENCE seq_t7 START WITH 1 INCREMENT BY 1 CACHE 100");
        final int threads = 4;
        final int perThread = 25;
        final Set<Long> all = new HashSet<>();
        final CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final String url = getURL();
            new Thread(() -> {
                Set<Long> mine = new HashSet<>();
                try (Connection c = DriverManager.getConnection(url);
                        Statement st = c.createStatement()) {
                    for (int k = 0; k < perThread; k++) {
                        try (ResultSet r = st.executeQuery("SELECT NEXTVAL('seq_t7')")) {
                            if (r.next())
                                mine.add(r.getLong(1));
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    synchronized (all) {
                        all.addAll(mine);
                    }
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        // 每个 nextval 必须拿到唯一值，不能重复
        assertEquals(threads * perThread, all.size());
        assertQueryEquals((long) threads * perThread, "SELECT NEXTVAL('seq_t7') - 1");
        drop("seq_t7");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // BUG: 带 CYCLE 的序列到 MAXVALUE 之后不回绕，而是抛 90009 SEQUENCE_ATTRIBUTES_INVALID。
    // 期望: 1,2,3 之后回到 1（H2 行为）；实际: 第 4 次 NEXTVAL 报
    // "Unable to create or alter sequence ... invalid attributes (start value "4", max value "3")"。
    @Ignore("BUG: CYCLE does not wrap around; Nth+1 NEXTVAL fails with 90009 instead of returning MINVALUE")
    @Test
    public void cycleWrapsAtBoundary() {
        drop("seq_c1");
        executeUpdate("CREATE SEQUENCE seq_c1 START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 3 CYCLE");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_c1')");
        assertQueryEquals(2L, "SELECT NEXTVAL('seq_c1')");
        assertQueryEquals(3L, "SELECT NEXTVAL('seq_c1')");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_c1')");
        drop("seq_c1");
    }

    // BUG: 不带 CYCLE 的序列耗尽时错误码是 90009（属性非法），而不是 90006 SEQUENCE_EXHAUSTED。
    // 期望: 90006 "Sequence has run out of numbers"（H2 行为）；实际: 90009。
    @Ignore("BUG: exhausted sequence raises 90009 SEQUENCE_ATTRIBUTES_INVALID instead of 90006 SEQUENCE_EXHAUSTED")
    @Test
    public void exhaustedSequenceErrorCode() {
        drop("seq_c2");
        executeUpdate("CREATE SEQUENCE seq_c2 START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 3");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_c2')");
        assertQueryEquals(2L, "SELECT NEXTVAL('seq_c2')");
        assertQueryEquals(3L, "SELECT NEXTVAL('seq_c2')");
        assertSqlFails("SELECT NEXTVAL('seq_c2')", ErrorCode.SEQUENCE_EXHAUSTED);
        drop("seq_c2");
    }

    // BUG: 只容纳两个值的序列（MINVALUE 1 MAXVALUE 2）在 CREATE 时就被拒绝。
    // 期望: 可以创建，nextval 依次返回 1、2，第 3 次才耗尽（H2 行为）；
    // 实际: 90009，因为 Sequence.isValid 要求 abs(increment) < maxValue - minValue（应该允许 <=）。
    @Ignore("BUG: CREATE SEQUENCE with MINVALUE 1 MAXVALUE 2 is rejected by an over-strict range check")
    @Test
    public void twoValueRangeIsAllowed() {
        drop("seq_c3");
        executeUpdate("CREATE SEQUENCE seq_c3 START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 2");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_c3')");
        assertQueryEquals(2L, "SELECT NEXTVAL('seq_c3')");
        drop("seq_c3");
    }

    // BUG: 不支持标准的 CURRENT VALUE FOR <sequence> 语法。
    // 期望: SELECT CURRENT VALUE FOR seq 返回最近一次 nextval 的值（H2 行为）；
    // 实际: 42000 语法错误，只能用 Lealone 私有的 CURRVAL('seq')。
    @Ignore("BUG: CURRENT VALUE FOR <sequence> is a syntax error (42000); only CURRVAL('seq') works")
    @Test
    public void currentValueForSyntaxIsSupported() {
        drop("seq_c4");
        executeUpdate("CREATE SEQUENCE seq_c4 START WITH 1 INCREMENT BY 1");
        assertQueryEquals(1L, "SELECT NEXTVAL('seq_c4')");
        assertQueryEquals(1L, "SELECT CURRENT VALUE FOR seq_c4");
        drop("seq_c4");
    }
}
