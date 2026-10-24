/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL语义的LEAST/GREATEST: 忽略NULL(全NULL才返回NULL)、支持多参数、
 * 结果类型按参数类型的公共类型归并。
 */
public class LeastGreatestTest extends SqlTestBase {

    public LeastGreatestTest() {
        setEmbedded(true);
    }

    // 字符串、数值、布尔、单参数
    @Test
    public void testBasic() throws Exception {
        assertEquals("a", getStringValue("SELECT least('a', 'b')"));
        assertEquals("c", getStringValue("SELECT greatest('b', 'a', 'c')"));
        // 单参数
        assertEquals("x", getStringValue("SELECT least('x')"));
        assertEquals(7, getIntValue("SELECT greatest(7)"));

        // 多个数值参数
        assertEquals(1, getIntValue("SELECT least(3, 1, 2)"));
        assertEquals(3, getIntValue("SELECT greatest(3, 1, 2)"));
        // 整数和DECIMAL归并成DECIMAL
        assertEquals(1.5, getDoubleValue("SELECT least(1.5, 2)"), 0);
        assertEquals(2.5, getDoubleValue("SELECT greatest(1, 2.5)"), 0);
        // 布尔也是可比较的
        assertFalse(getBooleanValue("SELECT least(TRUE, FALSE)"));
        assertTrue(getBooleanValue("SELECT greatest(TRUE, FALSE)"));

        // 结果类型: 数值按公共类型, 字符串是VARCHAR
        assertEquals("DECIMAL", getColumnTypeName("SELECT greatest(1, 2.5)"));
        assertEquals("VARCHAR", getColumnTypeName("SELECT least('a', 'b')"));
        assertEquals("abc", getStringValue("SELECT least('abc', 'abd')"));
    }

    // NULL语义: 忽略NULL, 全部是NULL才返回NULL
    @Test
    public void testNullSemantics() throws Exception {
        executeQuery("SELECT least(1, NULL, 2), greatest(1, NULL, 2)");
        assertEquals(1, getIntValue(1));
        assertEquals(2, getIntValue(2));

        executeQuery("SELECT least(NULL, 'b'), greatest(NULL, 'b')");
        assertEquals("b", getStringValue(1));
        assertEquals("b", getStringValue(2));

        executeQuery("SELECT least(NULL, NULL), greatest(NULL, NULL)");
        assertNull(getStringValue(1));
        assertNull(getStringValue(2));

        executeQuery("SELECT least(NULL)");
        assertNull(getStringValue(1));

        // NULL在前在后都一样
        executeQuery("SELECT least(NULL, 2, 1), greatest(NULL, 2, 1)");
        assertEquals(1, getIntValue(1));
        assertEquals(2, getIntValue(2));
    }

    // 日期、时间戳和列参数
    @Test
    public void testDatesAndColumns() throws Exception {
        executeQuery("SELECT least(DATE '2020-01-02', DATE '2020-01-01'), "
                + "greatest(TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-02 00:00:00')");
        assertEquals("2020-01-01", getStringValue(1));
        assertEquals("2020-01-02 00:00:00.0", getStringValue(2));

        executeUpdate("DROP TABLE IF EXISTS lg_pair");
        executeUpdate("CREATE TABLE lg_pair(id INT PRIMARY KEY, a VARCHAR(10), b VARCHAR(10))");
        executeUpdate("INSERT INTO lg_pair VALUES(1, 'a', 'b'), (2, 'c', 'a'), "
                + "(3, NULL, 'z'), (4, NULL, NULL)");
        // 逐行的least/greatest, NULL被忽略
        assertEquals("[a:b, a:c, z:z, null:null]",
                queryRowList("SELECT least(a, b), greatest(a, b) FROM lg_pair ORDER BY id").toString());
        // 能用在WHERE/排序里
        assertEquals("[1, 2]",
                queryIntList("SELECT id FROM lg_pair WHERE least(a, b) = 'a' ORDER BY id").toString());
        assertEquals("[4]", queryIntList(
                "SELECT id FROM lg_pair WHERE least(a, b) IS NULL ORDER BY id").toString());
        assertEquals("[1, 2]",
                queryIntList("SELECT id FROM lg_pair WHERE a IS NOT NULL "
                        + "ORDER BY greatest(a, b), id").toString());
    }

    // 用在存储过程的实参里(PG的create_procedure.sql用least(...)调过程)
    @Test
    public void testInCallAndExpressions() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS lg_proc");
        executeUpdate("CREATE PROCEDURE lg_proc(a VARCHAR, b VARCHAR) AS 'SELECT ? = ?'");
        executeQuery("CALL lg_proc(least('a', 'b'), 'a')");
        assertTrue(getBooleanValue(1));

        // 和CASE/比较组合
        executeQuery("SELECT CASE WHEN least(1, 2) = 1 THEN 'ok' ELSE 'bad' END");
        assertEquals("ok", getStringValue(1));

        // 参数个数至少一个
        executeQueryThanAssertErrorCode("SELECT least()", ErrorCode.INVALID_PARAMETER_COUNT_2);

        // 类型不能互相转换时报错(PostgreSQL这里是42883, Lealone用数据转换错误)
        executeQueryThanAssertErrorCode("SELECT least('a', 1)",
                ErrorCode.DATA_CONVERSION_ERROR_1);
    }

    private int getIntValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private double getDoubleValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getDouble(1);
        }
    }

    private boolean getBooleanValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private String getStringValue(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private String getColumnTypeName(String sql) throws Exception {
        try (ResultSet result = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            return meta.getColumnTypeName(1);
        }
    }

    private ArrayList<Integer> queryIntList(String sql) throws Exception {
        ArrayList<Integer> list = new ArrayList<>();
        try (ResultSet result = stmt.executeQuery(sql)) {
            while (result.next()) {
                list.add(result.getInt(1));
            }
        }
        return list;
    }

    private ArrayList<String> queryRowList(String sql) throws Exception {
        ArrayList<String> list = new ArrayList<>();
        try (ResultSet result = stmt.executeQuery(sql)) {
            int columnCount = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder buff = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        buff.append(':');
                    }
                    buff.append(result.getString(i));
                }
                list.add(buff.toString());
            }
        }
        return list;
    }
}
