/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import com.lealone.db.Database;
import com.lealone.db.index.standard.StandardDataType;
import com.lealone.db.result.SortOrder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueLong;
import com.lealone.storage.Storage;
import com.lealone.storage.StorageMap;
import com.lealone.storage.StorageMapCursor;

/**
 * 游标自己的tuplestore：行按顺序append到引擎的临时StorageMap(B树)里，
 * 复用引擎的临时map命名、值序列化和落盘机制，不另外发明磁盘格式。
 * 每写SAVE_INTERVAL行save()一次，把脏页落盘，B树缓存才能回收，堆不会随行数线性增长；
 * 关闭时remove()删除临时map(连同临时文件)。
 */
public class PlpgSqlTuplestore {

    // 每写这么多行落盘一次，让B树缓存可以回收
    private static final int SAVE_INTERVAL = 200000;

    private final StorageMap<Value, ValueArray> storageMap;
    // 顺序读用的游标，cursorIndex是它下一次要返回的行号
    private StorageMapCursor<Value, ValueArray> cursor;
    private long cursorIndex = -1;
    private long firstKey = -1;
    private int rowCount;
    private int unsavedRows;

    public PlpgSqlTuplestore(ServerSession session, int columnCount) {
        Database db = session.getDatabase();
        Storage storage = db.getStorage(db.getDefaultStorageEngineName());
        // key是行号，按升序；value是行的各列，列类型沿用结果里的实际类型
        StandardDataType keyType = new StandardDataType(db.getCompareMode(),
                new int[] { SortOrder.ASCENDING });
        int[] valueSortTypes = new int[columnCount < 0 ? 0 : columnCount];
        for (int i = 0; i < valueSortTypes.length; i++)
            valueSortTypes[i] = SortOrder.ASCENDING;
        StandardDataType valueType = new StandardDataType(db.getCompareMode(), valueSortTypes);
        this.storageMap = storage.<Value, ValueArray> openMap(storage.nextTemporaryMapName(),
                keyType, valueType, null);
    }

    // 追加一行，行号从0开始
    public void add(Value[] row) {
        Value key = storageMap.append(ValueArray.get(row));
        if (firstKey < 0)
            firstKey = ((ValueLong) key).getLong();
        rowCount++;
        if (++unsavedRows >= SAVE_INTERVAL) {
            storageMap.save();
            unsavedRows = 0;
        }
    }

    public int size() {
        return rowCount;
    }

    /**
     * 取第pos行(0基)，越界返回null；顺序访问走内部游标，随机访问按key定位。
     */
    public Value[] get(int pos) {
        if (pos < 0 || pos >= rowCount)
            return null;
        if (cursor != null && cursorIndex == pos) {
            if (cursor.next()) {
                cursorIndex = pos + 1;
                return getList(cursor.getValue());
            }
            closeCursor();
            return null;
        }
        closeCursor();
        cursor = storageMap.cursor(ValueLong.get(firstKey + pos));
        if (cursor.next()) {
            cursorIndex = pos + 1;
            return getList(cursor.getValue());
        }
        closeCursor();
        return null;
    }

    // 把最后没落盘的部分也落盘，OPEN结束和关闭前调用
    public void flush() {
        if (unsavedRows > 0) {
            storageMap.save();
            unsavedRows = 0;
        }
    }

    public void close() {
        closeCursor();
        // remove()会删掉临时map和它的临时文件
        storageMap.remove();
    }

    private void closeCursor() {
        // StorageMapCursor没有close()，丢掉引用即可，底层游标不持有额外资源
        cursor = null;
        cursorIndex = -1;
    }

    private static Value[] getList(ValueArray array) {
        return array == null ? null : array.getList();
    }
}
