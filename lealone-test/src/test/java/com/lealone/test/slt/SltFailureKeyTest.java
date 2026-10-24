/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import org.junit.Test;

import com.lealone.test.UnitTestBase;

/**
 * 失败签名必须由**完整SQL**生成。门禁里 sql 字段是截断到 500 字符的(用于显示),
 * 如果签名也用它, 那么两条"前500字符相同、只有末尾不同"的失败会算出同一个签名 ——
 * 门禁会把新增失败当成已登记而放行(假绿)。
 */
public class SltFailureKeyTest extends UnitTestBase {

    @Test
    public void testKeyUsesFullSql() {
        StringBuilder head = new StringBuilder("SELECT col0 FROM tab0 WHERE ");
        while (head.length() < 600)
            head.append("col1 + ");

        SltRunner.Failure a = failure("exception", head.toString() + "1");
        SltRunner.Failure b = failure("exception", head.toString() + "2");

        assertNotEquals("只有末尾不同的两条失败不能算出同一个签名", a.key(), b.key());
        assertEquals("同一条SQL的签名必须稳定", a.key(), failure("exception", head.toString() + "1").key());
        assertTrue("显示用的SQL仍然要截断", a.sql.length() < a.sqlFull.length());
    }

    private SltRunner.Failure failure(String subtype, String sql) {
        SltRunner.Failure f = new SltRunner.Failure();
        f.subtype = subtype;
        f.sql = sql.length() <= 500 ? sql : sql.substring(0, 500) + "...";
        f.sqlFull = sql;
        return f;
    }
}
