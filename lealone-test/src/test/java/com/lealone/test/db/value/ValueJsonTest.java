/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db.value;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueMap;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueString;
import com.lealone.test.TestBase;

/**
 * 服务调用返回 Map/Array 时按 JSON 输出的正确性。
 * <p>
 * 旧实现把嵌套的 Map/List 整个压成带引号的字符串，控制字符也原样输出，
 * 客户端收到的不是合法 JSON(也解析不出嵌套结构)。
 */
public class ValueJsonTest extends TestBase {

    @Test
    public void testNestedMapIsJsonObject() {
        Map<Object, Object> nested = new LinkedHashMap<>();
        nested.put("x", 2);
        Map<Object, Object> root = new LinkedHashMap<>();
        root.put("name", "alice");
        root.put("id", 1);
        root.put("nested", nested);
        String json = ValueMap.get(root).getJson();
        // ValueMap 内部用 HashMap, 不保证键顺序, 只校验结构与内容
        assertTrue(json.contains("\"name\":\"alice\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue("嵌套的Map必须输出成JSON对象: " + json, json.contains("\"nested\":{\"x\":2}"));
    }

    @Test
    public void testListIsJsonArray() {
        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add("two");
        Map<Object, Object> root = new LinkedHashMap<>();
        root.put("list", list);
        assertEquals("{\"list\":[1,\"two\"]}", ValueMap.get(root).getJson());
    }

    @Test
    public void testControlCharactersAreEscaped() {
        Map<Object, Object> root = new LinkedHashMap<>();
        root.put("ctrl", "a\u0001b\"c\\d\ne");
        String json = ValueMap.get(root).getJson();
        // 控制字符必须被转义, 否则不是合法 JSON
        assertFalse(json.contains("\u0001"));
        assertTrue(json.contains("a\\u0001b"));
        assertTrue(json.contains("\\\"c"));
        assertTrue(json.contains("\\\\d"));
        assertTrue(json.contains("\\ne"));
    }

    // 基本类型数组不能强转成 Object[]，元素为 null 也不能调 toString()
    @Test
    public void testPrimitiveAndNullArrayElements() {
        ValueArray ints = ValueArray.get(new FakeArray(new int[] { 1, 2, 3 }));
        assertEquals(3, ints.getList().length);
        assertEquals(Value.INT, ints.getList()[0].getType());
        assertEquals(1, ints.getList()[0].getInt());
        assertEquals(3, ints.getList()[2].getInt());

        ValueArray withNull = ValueArray.get(new FakeArray(new Object[] { "a", null, 5 }));
        assertEquals(3, withNull.getList().length);
        assertEquals(Value.STRING, withNull.getList()[0].getType());
        assertEquals(Value.NULL, withNull.getList()[1].getType());
        // 类型要按元素自己的 Java 类型保留, 不能全部变成字符串
        assertEquals(Value.INT, withNull.getList()[2].getType());
        assertEquals(5, withNull.getList()[2].getInt());
    }

    // 简单替代 java.sql.Array 实现, 只需要 getArray()
    private static class FakeArray implements java.sql.Array {
        private final Object array;

        FakeArray(Object array) {
            this.array = array;
        }

        @Override
        public Object getArray() {
            return array;
        }

        @Override
        public Object getArray(Map<String, Class<?>> map) {
            return array;
        }

        @Override
        public Object getArray(long index, int count) {
            return array;
        }

        @Override
        public Object getArray(long index, int count, Map<String, Class<?>> map) {
            return array;
        }

        @Override
        public int getBaseType() {
            return java.sql.Types.OTHER;
        }

        @Override
        public String getBaseTypeName() {
            return "OTHER";
        }

        @Override
        public java.sql.ResultSet getResultSet() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.ResultSet getResultSet(Map<String, Class<?>> map) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.ResultSet getResultSet(long index, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void free() {
        }

        @Override
        public String toString() {
            return "FakeArray";
        }
    }
}
