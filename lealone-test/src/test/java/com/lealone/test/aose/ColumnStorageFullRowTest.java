/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.aose;

import org.junit.Test;

import com.lealone.storage.aose.AOStorageEngine;
import com.lealone.storage.aose.btree.page.PageStorageMode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 列存表必须能整行读取：游标不裁剪列(columnIndexes 为 null)时要加载所有列页，
 * 否则读出来的整行全是 null，SCRIPT 导出、建索引等走"整行读"的路径都会出错。
 */
public class ColumnStorageFullRowTest extends SqlTestBase {

    private static final String TABLE = "cs_full_row";

    private void createColumnTable() {
        executeUpdate("DROP TABLE IF EXISTS " + TABLE);
        executeUpdate("CREATE TABLE " + TABLE
                + "(pk int primary key, f1 varchar, f2 int) Engine " + AOStorageEngine.NAME
                + " PARAMETERS(page_storage_mode='" + PageStorageMode.COLUMN_STORAGE.name()
                + "', page_size='16k')");
    }

    private void insertRows(int count) {
        for (int i = 1; i <= count; i++)
            executeUpdate("INSERT INTO " + TABLE + " VALUES(" + i + ", 'v" + i + "', " + (i * 10) + ")");
    }

    // 整行读: 每一列都必须读到真实值, 不能是 null
    @Test
    public void testFullRowRead() throws Exception {
        createColumnTable();
        insertRows(50);

        executeQuery("SELECT pk, f1, f2 FROM " + TABLE + " WHERE pk = 7");
        try {
            assertEquals(7, rs.getInt(1));
            assertEquals("v7", rs.getString(2));
            assertEquals(70, rs.getInt(3));
        } finally {
            closeResultSet();
        }
    }

    // SCRIPT 导出走的是不裁剪列的整行读, 列存表不能抛空指针
    @Test
    public void testScriptExport() throws Exception {
        createColumnTable();
        insertRows(20);
        executeQuery("SCRIPT");
        int lines = 0;
        try {
            boolean hasRow = true;
            while (hasRow) {
                String line = rs.getString(1);
                if (line != null && line.contains("INSERT INTO"))
                    lines++;
                hasRow = rs.next();
            }
        } finally {
            closeResultSet();
        }
        // SCRIPT 会把多行合并成一条INSERT, 这里只要求导出了插入语句且没抛空指针
        assertTrue("SCRIPT 里应该包含插入语句", lines > 0);
    }

    // 重启后(关闭数据库会触发 checkpoint 保存)列存表的数据必须完整
    @Test
    public void testCheckpointKeepsColumnValues() throws Exception {
        createColumnTable();
        insertRows(50);
        executeUpdate("CHECKPOINT SYNC");

        executeQuery("SELECT COUNT(*), SUM(f2) FROM " + TABLE);
        try {
            assertEquals(50, rs.getInt(1));
            assertEquals(50 * 51 / 2 * 10, rs.getInt(2));
        } finally {
            closeResultSet();
        }
    }
}
