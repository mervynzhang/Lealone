/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * PostgreSQL的集合返回函数: unnest()/generate_series()，以及ARRAY[...]字面量。
 * 这几个函数出现在FROM里时走FunctionTable，所以能直接和WITH ORDINALITY、
 * 列别名列表组合。
 */
public class PgRangeFuncTest extends SqlTestBase {

    public PgRangeFuncTest() {
        setEmbedded(true);
    }

    // unnest(anyarray): 一个数组展开成多行
    @Test
    public void testUnnestSingleArray() throws Exception {
        executeQuery("SELECT * FROM unnest(array[1, 2, 3])");
        assertEquals("[1, 2, 3]", queryIntList("SELECT * FROM unnest(array[1, 2, 3])").toString());
        assertEquals("[a, b]", queryStringList("SELECT * FROM unnest(array['a', 'b'])").toString());

        // 列名和PostgreSQL一样用函数名
        try (ResultSet result = stmt.executeQuery("SELECT * FROM unnest(array[1, 2])")) {
            ResultSetMetaData meta = result.getMetaData();
            assertEquals(1, meta.getColumnCount());
            assertEquals("UNNEST", meta.getColumnName(1));
        }
        // 空数组和NULL数组都没有行
        assertEquals("[0]", queryIntList("SELECT count(*) FROM unnest(array[])").toString());
        assertEquals("[0]",
                queryIntList("SELECT count(*) FROM unnest(CAST(NULL AS INT))").toString());
    }

    // 多个数组并行展开，短的补NULL
    @Test
    public void testUnnestMultipleArrays() throws Exception {
        executeQuery("SELECT * FROM unnest(array[1, 2], array['a', 'b'])");
        assertEquals(1, getIntValue(1));
        assertEquals("a", getStringValue(2));
        executeQuery("SELECT * FROM unnest(array[1, 2], array['a', 'b', 'c'])");
        assertEquals("[2]",
                queryIntList("SELECT count(*) FROM unnest(array[1, 2], array['a', 'b'])")
                        .toString());
        assertEquals("[3]",
                queryIntList("SELECT count(*) FROM unnest(array[1, 2], array['a', 'b', 'c'])")
                        .toString());
        // 第3行第一个数组没有值，补NULL
        executeQuery("SELECT * FROM unnest(array[1, 2], array['a', 'b', 'c']) WHERE UNNEST2 = 'c'");
        assertNull(getStringValue(1));
        assertEquals("c", getStringValue(2));
        // 两个数组长度一样时没有NULL
        assertEquals("[1, 2]",
                queryIntList("SELECT UNNEST FROM unnest(array[1, 2], array['a', 'b'])").toString());
    }

    // unnest和WITH ORDINALITY、列别名列表组合
    @Test
    public void testUnnestWithOrdinality() throws Exception {
        executeQuery(
                "SELECT a, ord FROM unnest(array['a', 'b']) WITH ORDINALITY AS z(a, ord)");
        assertEquals("a", getStringValue(1));
        assertEquals(1, getIntValue(2));
        assertEquals("[a, b]",
                queryStringList("SELECT a FROM unnest(array['a', 'b']) WITH ORDINALITY AS z(a, ord)")
                        .toString());
        assertEquals("[1, 2]",
                queryIntList("SELECT ord FROM unnest(array['a', 'b']) WITH ORDINALITY AS z(a, ord)")
                        .toString());

        // 浮点数组
        executeQuery("SELECT a, ord FROM unnest(array[1.0::float8]) WITH ORDINALITY AS z(a, ord)");
        assertEquals("1.0", getStringValue(1));
        assertEquals(1, getIntValue(2));
    }

    // generate_series(int, int[, int]) 和WITH ORDINALITY
    @Test
    public void testGenerateSeries() throws Exception {
        executeQuery("SELECT * FROM generate_series(1, 5)");
        assertEquals(1, getIntValue(1));
        assertEquals("[1, 2, 3, 4, 5]",
                queryIntList("SELECT * FROM generate_series(1, 5)").toString());
        assertEquals("[1, 4, 7, 10]",
                queryIntList("SELECT * FROM generate_series(1, 10, 3)").toString());
        // 负的步长
        assertEquals("[5, 3, 1]",
                queryIntList("SELECT * FROM generate_series(5, 1, -2)").toString());
        // 空区间
        assertEquals("[0]",
                queryIntList("SELECT count(*) FROM generate_series(5, 1)").toString());

        // 行号从1开始
        assertEquals("[1, 2, 3, 4]",
                queryIntList("SELECT ordinality FROM generate_series(11, 14) WITH ORDINALITY s")
                        .toString());
        executeQuery("SELECT * FROM generate_series(11, 14) WITH ORDINALITY s");
        assertEquals(2, rs.getMetaData().getColumnCount());
        assertEquals(11, getIntValue(1));
        assertEquals(1, getIntValue(2));
    }

    // PostgreSQL的ARRAY[...]字面量
    @Test
    public void testArrayLiteral() throws Exception {
        executeQuery("SELECT array[1, 2, 3]");
        assertEquals("(1, 2, 3)", getStringValue(1));
        // 常量表达式也可以(先折叠成常量)
        executeQuery("SELECT array[1.0::float8]");
        assertEquals("(1.0)", getStringValue(1));
        executeQuery("SELECT array['a' || 'b']");
        assertEquals("(ab)", getStringValue(1));
        // 数组字面量可以直接传给unnest
        assertEquals("[1, 2]",
                queryIntList("SELECT * FROM unnest(array[1, 2])").toString());
        // 不是常量的元素不支持
        executeUpdate("CREATE TABLE IF NOT EXISTS prf_t(id INT)");
        // 列不是常量: 解析期就报错(而不是拼出一个没有意义的数组)
        try {
            executeQuery("SELECT array[id] FROM prf_t");
            fail("ARRAY[...] with a column element must fail");
        } catch (DbException e) {
            // 解析期拿不到列类型/值, 报错即可
        }
    }

    // select列表里的setof函数要展开成多行，Lealone不支持，要明确报错而不是返回一个值
    @Test
    public void testSetReturningInSelectListNotSupported() throws Exception {
        executeQueryThanAssertErrorCode("SELECT unnest(array[1, 2, 3])",
                ErrorCode.FEATURE_NOT_SUPPORTED_1);
    }

    // 参数不是数组时报类型错误
    @Test
    public void testUnnestInvalidArgument() throws Exception {
        executeQueryThanAssertErrorCode("SELECT * FROM unnest(1)",
                ErrorCode.DATA_CONVERSION_ERROR_1);
    }

    private ArrayList<Integer> queryIntList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<Integer> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getInt(1));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private ArrayList<String> queryStringList(String sql) {
        try (Statement s = conn.createStatement(); ResultSet result = s.executeQuery(sql)) {
            ArrayList<String> list = new ArrayList<>();
            while (result.next()) {
                list.add(result.getString(1));
            }
            return list;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }
}
