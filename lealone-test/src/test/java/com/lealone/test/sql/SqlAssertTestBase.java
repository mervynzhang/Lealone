/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;

/**
 * P0/P1 SQL 语义回归测试的基类。
 * <p>
 * 在 {@link SqlTestBase} 的基础上做两件事：
 * <ol>
 * <li>固定在 embedded 模式下运行（不依赖 9210 端口），见构造函数；</li>
 * <li>提供一组单值查询断言工具，统一用 {@code rs.getObject()} + {@code rs.wasNull()} 判断 NULL，
 * 并提供 SQL 错误码断言（JDBC 层 {@link SQLException#getErrorCode()}）。</li>
 * </ol>
 * 数值比较统一走 {@link BigDecimal#compareTo}，避免 1 和 1.0、2.5 和 2.50 这类标度差异造成误报。
 */
public abstract class SqlAssertTestBase extends SqlTestBase {

    protected SqlAssertTestBase() {
        setEmbedded(true);
    }

    /** 执行查询，返回第一行第一列；NULL 返回 null（用 wasNull 判定） */
    protected Object queryObject(String querySql) {
        try (ResultSet r = stmt.executeQuery(querySql)) {
            assertTrue("no row returned for: " + querySql, r.next());
            Object o = r.getObject(1);
            if (r.wasNull())
                o = null;
            assertFalse("more than one row returned for: " + querySql, r.next());
            return o;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    /** 执行查询，返回第一行指定列；NULL 返回 null（用 wasNull 判定） */
    protected Object queryObject(String querySql, int column) {
        try (ResultSet r = stmt.executeQuery(querySql)) {
            assertTrue("no row returned for: " + querySql, r.next());
            Object o = r.getObject(column);
            if (r.wasNull())
                o = null;
            return o;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    protected void assertQueryEquals(Object expected, String querySql) {
        Object actual = queryObject(querySql);
        assertValueEquals(querySql, expected, actual);
    }

    protected void assertValueEquals(String message, Object expected, Object actual) {
        if (expected == null) {
            assertNull("expected NULL but was " + actual + " for: " + message, actual);
            return;
        }
        if (expected instanceof Number && actual instanceof Number) {
            BigDecimal e = new BigDecimal(expected.toString());
            BigDecimal a = new BigDecimal(actual.toString());
            assertEquals("expected " + expected + " but was " + actual + " for: " + message, 0,
                    e.compareTo(a));
            return;
        }
        assertEquals(message, expected, actual);
    }

    protected void assertQueryNull(String querySql) {
        assertNull("expected NULL for: " + querySql, queryObject(querySql));
    }

    protected void assertQueryTrue(String querySql) {
        assertQueryEquals(Boolean.TRUE, querySql);
    }

    protected void assertQueryFalse(String querySql) {
        assertQueryEquals(Boolean.FALSE, querySql);
    }

    protected void assertUpdateCount(int expected, String updateSql) {
        assertEquals("update count for: " + updateSql, expected, executeUpdate(updateSql));
    }

    /** 断言 SQL 执行失败，并且 JDBC 错误码等于 expectedErrorCode；返回该 SQLException */
    protected SQLException assertSqlFails(String sql, int expectedErrorCode) {
        SQLException e = assertSqlFails(sql);
        assertEquals("error code for: " + sql, expectedErrorCode, e.getErrorCode());
        return e;
    }

    /** 断言 SQL 执行失败（不关心具体错误码，用于"这类 DDL 必须被拒绝"的场景） */
    protected SQLException assertSqlFails(String sql) {
        try {
            stmt.execute(sql);
            fail("expected SQL to fail: " + sql);
            return null;
        } catch (SQLException e) {
            return e;
        } catch (RuntimeException e) {
            SQLException se = findSQLException(e);
            assertNotNull("expected SQLException in cause chain of: " + e, se);
            return se;
        }
    }

    /** 断言 SQL 可以正常执行（查询或更新都行），返回是否返回了 ResultSet */
    protected boolean assertSqlOk(String sql) {
        try {
            return stmt.execute(sql);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    protected static SQLException findSQLException(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException)
                return (SQLException) t;
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }

    protected static int errorCodeOf(Throwable e) {
        SQLException se = findSQLException(e);
        return se == null ? -1 : se.getErrorCode();
    }

    /** EXPLAIN 的执行计划文本 */
    protected String explain(String querySql) {
        return String.valueOf(queryObject("EXPLAIN " + querySql));
    }

    protected void assertPlanContains(String querySql, String expected) {
        String plan = explain(querySql);
        assertTrue("plan of [" + querySql + "] should contain [" + expected + "] but was:\n" + plan,
                plan.contains(expected));
    }

    protected void assertPlanNotContains(String querySql, String unexpected) {
        String plan = explain(querySql);
        assertFalse("plan of [" + querySql + "] should not contain [" + unexpected + "] but was:\n"
                + plan, plan.contains(unexpected));
    }
}
