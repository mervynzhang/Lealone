/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db;

import org.junit.Test;

import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.index.Cursor;
import com.lealone.db.row.Row;
import com.lealone.db.table.StandardTable;

/**
 * 只带主键的行(列值为 null)交给 removeRow 时，删除必须仍然正确：
 * 维护二级索引要用真实的列值，否则按索引列算 key 时会空指针（并发扫描用例里偶发过
 * "Cannot load from object array because columnArray is null"），或者删错/漏删索引项。
 */
public class IndexRemoveKeyOnlyRowTest extends DbObjectTestBase {

    @Test
    public void testRemoveRowWithKeyOnlyRow() throws Exception {
        executeUpdate("drop table if exists kr_t");
        executeUpdate("create table kr_t(id int primary key, k int, payload varchar(50))");
        executeUpdate("insert into kr_t values(1, 7, 'x')");
        executeUpdate("insert into kr_t values(2, 8, 'y')");
        executeUpdate("create index idx_kr_k on kr_t(k)");
        waitIndexBuilt();

        StandardTable table = (StandardTable) schema.findTableOrView(session, "kr_t");
        long key = findRowKey(table, 1);
        assertTrue("应该能找到id=1的行", key >= 0);

        // 只带主键、列值为null的行: 模拟某些计划路径只读主键的情形
        Row keyOnlyRow = new Row(key, null);
        table.removeRow(session, keyOnlyRow, false, AsyncResultHandler.emptyHandler());
        waitRowCount(1);

        assertEquals(1, rowCount("select count(*) from kr_t"));
        assertEquals(0, rowCount("select count(*) from kr_t where id = 1"));
        // 二级索引项必须一起删掉, 不能留下孤儿项
        assertEquals(0, rowCount("select count(*) from kr_t where k = 7"));
        assertEquals(1, rowCount("select count(*) from kr_t where k = 8"));
    }

    private int rowCount(String sql) {
        return getInt(sql, 1);
    }

    private long findRowKey(StandardTable table, int id) {
        Cursor cursor = table.getScanIndex(session).find(session, null, null);
        while (cursor.next()) {
            Row row = (Row) cursor.get();
            if (row.getColumns()[0].getInt() == id)
                return row.getKey();
        }
        return -1;
    }

    private void waitIndexBuilt() throws Exception {
        for (int i = 0; i < 200; i++) {
            if (rowCount("select count(*) from kr_t where k = 7") == 1)
                return;
            Thread.sleep(10);
        }
        fail("二级索引没有在2秒内建好");
    }

    private void waitRowCount(int expected) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (rowCount("select count(*) from kr_t") == expected)
                return;
            Thread.sleep(10);
        }
        assertEquals("删除没有在3秒内完成", expected, rowCount("select count(*) from kr_t"));
    }
}
