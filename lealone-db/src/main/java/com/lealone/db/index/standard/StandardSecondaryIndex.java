/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.index.standard;

import java.util.HashMap;
import java.util.Map;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.index.Cursor;
import com.lealone.db.index.Index;
import com.lealone.db.index.IndexColumn;
import com.lealone.db.index.IndexOperator;
import com.lealone.db.index.IndexType;
import com.lealone.db.lock.Lockable;
import com.lealone.db.result.SortOrder;
import com.lealone.db.row.Row;
import com.lealone.db.row.SearchRow;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.StandardTable;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueEnum;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.storage.Storage;
import com.lealone.storage.StorageMap;
import com.lealone.storage.StorageSetting;
import com.lealone.transaction.Transaction;
import com.lealone.transaction.TransactionMap;
import com.lealone.transaction.TransactionMapCursor;

/**
 * @author H2 Group
 * @author zhh
 */
public class StandardSecondaryIndex extends StandardDataIndex<IndexKey, IndexKey> {

    private Long lastIndexedRowKey;
    private boolean building;

    public StandardSecondaryIndex(ServerSession session, StandardTable table, int id, String indexName,
            IndexType indexType, IndexColumn[] indexColumns) {
        super(table, id, indexName, indexType, indexColumns);
        if (!database.isStarting()) {
            checkIndexColumnTypes(indexColumns);
        }
        dataMap = openMap(session, mapName);
    }

    private TransactionMap<IndexKey, IndexKey> openMap(ServerSession session, String mapName) {
        int[] sortTypes = new int[indexColumns.length];
        for (int i = 0; i < indexColumns.length; i++) {
            sortTypes[i] = indexColumns[i].sortType;
        }
        IndexKeyType keyType = IndexKeyType.create(database.getCompareMode(), sortTypes, this);
        Storage storage = database.getStorage(table.getStorageEngine());
        Map<String, String> parameters = table.getParameters();
        if (!table.isPersistIndexes()) {
            parameters = new HashMap<>(parameters);
            parameters.put(StorageSetting.IN_MEMORY.name(), "1");
        }
        TransactionMap<IndexKey, IndexKey> map = session.getTransaction().openMap(mapName, keyType,
                keyType, storage, parameters);
        if (!keyType.equals(map.getKeyType())) {
            throw DbException.getInternalError("Incompatible key type");
        }
        return map;
    }

    @Override
    public void setLastIndexedRowKey(Long rowKey) {
        lastIndexedRowKey = rowKey;
    }

    @Override
    public Long getLastIndexedRowKey() {
        return lastIndexedRowKey;
    }

    @Override
    public void setBuilding(boolean building) {
        this.building = building;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @SuppressWarnings("unchecked")
    private StorageMap<IndexKey, IndexKey> getStorageMap() {
        return (StorageMap<IndexKey, IndexKey>) dataMap.getRawMap();
    }

    @Override
    public void add(ServerSession session, Row row, AsyncResultHandler<Integer> handler) {
        final IndexKey key = convertToKey(row);
        if (session.isFastPath()) {
            getStorageMap().put(key, key, AsyncResultHandler.emptyHandler());
            return;
        }
        final TransactionMap<IndexKey, IndexKey> map = getTransactionMap(session);
        map.addIfAbsent(key, key, ar -> {
            if (ar.isSucceeded() && ar.getResult().intValue() == Transaction.OPERATION_DATA_DUPLICATE) {
                // 违反了唯一性，
                // 或者byte/short/int/long类型的primary key + 约束字段构成的索引
                // 因为StandardPrimaryIndex和StandardSecondaryIndex的add是异步并行执行的，
                // 有可能先跑StandardSecondaryIndex先，所以可能得到相同的索引key，
                // 这时StandardPrimaryIndex和StandardSecondaryIndex都会检测到重复key的异常。
                DbException e = getDuplicateKeyException(key.toString());
                onException(handler, e);
            } else {
                handler.handle(ar);
            }
        });
    }

    @Override
    public void update(ServerSession session, Row oldRow, Row newRow, Value[] oldColumns,
            int[] updateColumns, boolean isLockedBySelf, AsyncResultHandler<Integer> handler) {
        boolean needUpdate = false;
        // row key不同了都要更新索引
        if (oldRow.getKey() != newRow.getKey()) {
            needUpdate = true;
        } else {
            Value[] newColumns = newRow.getColumns();
            // 只有索引字段被更新时且新值和旧值不同时才更新索引
            for (Column c : columns) {
                int cid = c.getColumnId();
                if (StandardPrimaryIndex.containsColumn(updateColumns, cid)) {
                    if (oldColumns[cid].compareTo(newColumns[cid]) != 0) {
                        needUpdate = true;
                        break;
                    }
                }
            }
        }
        if (needUpdate)
            super.update(session, oldRow, newRow, oldColumns, updateColumns, isLockedBySelf, handler);
        else
            onComplete(handler);
    }

    @Override
    public void remove(ServerSession session, Row row, Value[] oldColumns, boolean isLockedBySelf,
            AsyncResultHandler<Integer> handler) {
        IndexKey key = convertToKey(row, oldColumns);
        if (session.isFastPath()) {
            getStorageMap().remove(key, AsyncResultHandler.emptyHandler());
            return;
        }
        TransactionMap<IndexKey, IndexKey> map = getTransactionMap(session);
        Lockable lockable = map.getLockableValue(key);
        if (lockable == null) { // 旧索引项已经不存在，不能让remove失败挡住后面的add
            onComplete(handler);
            return;
        }
        if (!isLockedBySelf && map.isLocked(lockable))
            onComplete(handler, map.addWaitingTransaction(lockable));
        else
            onComplete(handler, map.tryRemove(key, lockable, isLockedBySelf));
    }

    private void runIndexOperations(ServerSession session) {
        IndexOperator indexOperator = getIndexOperator();
        if (indexOperator != null && indexOperator.hasPendingIndexOperation()) {
            indexOperator.run(session);
        }
    }

    @Override
    public Cursor find(ServerSession session, SearchRow first, SearchRow last) {
        runIndexOperations(session);
        IndexKey min = convertToKey(first);
        if (min != null) {
            min.setKey(Long.MIN_VALUE);
        }
        TransactionMap<IndexKey, IndexKey> map = getTransactionMap(session);
        if (isBuilding()) {
            TransactionMapCursor<IndexKey, IndexKey> tmCursor;
            Long lastKey = lastIndexedRowKey;
            // 这个循环确保tmCursor的快照跟lastIndexedRowKey一致，
            // 也就是tmCursor中的最rowKey就是lastIndexedRowKey
            while (true) {
                tmCursor = map.cursor(min);
                if (lastKey != lastIndexedRowKey) {
                    lastKey = lastIndexedRowKey;
                } else {
                    break;
                }
            }
            if (lastKey != null) {
                Row f = table.getTemplateRow();
                f.setKey(lastKey.longValue() + 1);
                Index scan = table.getScanIndex(session);
                Cursor cursor = scan.find(session, f, null);
                return new SsiBuildingCursor(session, tmCursor, last, cursor);
            } else if (map.getRawSize() == 0) {
                // 索引正准备异步创建，但是还没有任何数据时，直接走ScanIndex
                return table.getScanIndex(session).find(session, null, null);
            } else {
                return new SsiRegularCursor(session, tmCursor, last);
            }
        } else {
            return new SsiRegularCursor(session, map.cursor(min), last);
        }
    }

    public IndexKey convertToKey(SearchRow r) {
        if (r == null)
            return null;
        return convertToKey(r, r.getColumns());
    }

    private IndexKey convertToKey(SearchRow r, Value[] columnArray) {
        if (r == null)
            return null;
        int len = columns.length;
        Value[] array = new Value[len];
        for (int i = 0; i < len; i++) {
            Column c = columns[i];
            int idx = c.getColumnId();
            Value v = columnArray[idx];
            if (v != null) {
                if (c.isEnumType()) {
                    try {
                        array[i] = c.convert(v);
                    } catch (Throwable t) {
                        array[i] = ValueEnum.get(-1);
                    }
                } else {
                    array[i] = v.convertTo(c.getType());
                }
            }
        }
        return IndexKey.create(r.getKey(), array);
    }

    @Override
    public double getCost(ServerSession session, int[] masks, SortOrder sortOrder) {
        try {
            return 10 * getCostRangeIndex(masks, dataMap.getRawSize(), sortOrder);
        } catch (IllegalStateException e) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED, e);
        }
    }

    @Override
    public SearchRow findFirstOrLast(ServerSession session, boolean first) {
        runIndexOperations(session);
        TransactionMap<IndexKey, IndexKey> map = getTransactionMap(session);
        IndexKey key = first ? map.firstKey() : map.lastKey();
        while (true) {
            if (key == null) {
                return null;
            }
            if (key.getColumns()[0] != ValueNull.INSTANCE) {
                break;
            }
            key = first ? map.higherKey(key) : map.lowerKey(key);
        }
        return convertToSearchRow(key);
    }

    @Override
    public boolean supportsDistinctQuery() {
        return true;
    }

    @Override
    public Cursor findDistinct(ServerSession session) {
        runIndexOperations(session);
        return new SsiDistinctCursor(session);
    }

    @Override
    public boolean needRebuild() {
        try {
            return dataMap.getRawSize() == 0;
        } catch (IllegalStateException e) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED, e);
        }
    }

    /**
     * Convert array of values to a SearchRow.
     *
     * @param iKey the index key
     * @return the row
     */
    private SearchRow convertToSearchRow(IndexKey iKey) {
        Value[] array = iKey.getColumns();
        int len = array.length;
        SearchRow searchRow = table.getTemplateRow();
        searchRow.setKey(iKey.getKey());
        Column[] cols = getColumns();
        for (int i = 0; i < len; i++) {
            Column c = cols[i];
            int idx = c.getColumnId();
            Value v = array[i];
            if (c.isEnumType())
                v = c.convert(v);
            searchRow.setValue(idx, v);
        }
        int idx = table.getScanIndex(null).getMainIndexColumn();
        if (idx >= 0) {
            Column c = table.getColumn(idx);
            Value v = c.convert(ValueLong.get(iKey.getKey()));
            searchRow.setValue(idx, v);
        }
        return searchRow;
    }

    private abstract class StandardSecondaryIndexCursor extends StandardDataIndexCursor {

        private final ServerSession session;
        private SearchRow searchRow;
        private Row row;
        // 单条语句/单个游标内"rowKey -> 首次见到时的索引列值"，必须是不可变的列值数组，
        // 不能缓存Row对象(行是原地更新的，缓存对象本身会被改掉，等于没有快照)
        private HashMap<Long, Value[]> rowSnapshots;

        public StandardSecondaryIndexCursor(ServerSession session) {
            this.session = session;
        }

        @Override
        public Row get() {
            return get(null);
        }

        @Override
        public Row get(int[] columnIndexes) {
            if (row == null && searchRow != null) {
                row = table.getRow(session, searchRow.getKey(), columnIndexes);
            }
            return row;
        }

        @Override
        public SearchRow getSearchRow() {
            return searchRow;
        }

        @Override
        public boolean isSearchRowUpToDate() {
            return false;
        }

        @Override
        public boolean next() {
            searchRow = nextSearchRow();
            // 不能在这里把 row 清空: nextSearchRow 校验索引项时(见 isRowStillMatching)已经把这一行的
            // 主表记录读出来了, 清空会让随后 get() 再读一次同一个主表行, 覆盖索引查询的读放大直接翻倍。
            // 每条索引项开始校验时 getIndexKey 都会自己清 row, 所以这里不清也不会读到上一条的行。
            return searchRow != null;
        }

        protected SearchRow createSearchRow(IndexKey key) {
            if (key == null)
                return null;
            else
                return convertToSearchRow(key);
        }

        protected IndexKey getIndexKey(TransactionMapCursor<IndexKey, IndexKey> tmCursor) {
            // 每条索引项都必须用它自己对应的主表行来校验，不能复用上一条的缓存
            row = null;
            IndexKey current = tmCursor.getKey();
            // 索引项可能是墓碑(正在被删)或残留的孤儿项，只有主表行当前值还等于它时才返回，
            // 否则该行会通过更新后的索引项再产出一次，同一个rowKey会返回两次
            if (current.getLockedValue() == null) {
                IndexKey old = tmCursor.getValue();
                if (old == null || !isRowStillMatching(old))
                    return null;
                return old;
            }
            return isRowStillMatching(current) ? current : null;
        }

        // 主表行当前的索引列值是否还等于这条索引项保存的值
        private boolean isRowStillMatching(IndexKey indexKey) {
            long rowKey = indexKey.getKey();
            if (rowSnapshots == null)
                rowSnapshots = new HashMap<>();
            Value[] currentColumns;
            if (rowSnapshots.containsKey(rowKey)) {
                // 同一rowKey在本次扫描里已经见过，沿用第一次见到时的列值，
                // 避免扫描期间该行被更新后，旧索引项和新索引项都通过校验而输出两次
                currentColumns = rowSnapshots.get(rowKey);
            } else {
                // 复用游标里缓存的这一行，避免和后续TableFilter取值时重复读一次主表
                if (row == null)
                    row = table.getRow(session, rowKey);
                IndexKey currentKey = row == null ? null : convertToKey(row);
                currentColumns = currentKey == null ? null : currentKey.getColumns();
                rowSnapshots.put(rowKey, currentColumns);
            }
            if (currentColumns == null)
                return false;
            Value[] oldColumns = indexKey.getColumns();
            if (oldColumns == null || currentColumns == null
                    || oldColumns.length != currentColumns.length)
                return false;
            for (int i = 0; i < oldColumns.length; i++) {
                if (oldColumns[i].compareTo(currentColumns[i]) != 0)
                    return false;
            }
            return true;
        }

        protected abstract SearchRow nextSearchRow();
    }

    private class SsiRegularCursor extends StandardSecondaryIndexCursor {

        private final TransactionMapCursor<IndexKey, IndexKey> tmCursor;
        private final SearchRow last;

        public SsiRegularCursor(ServerSession session, TransactionMapCursor<IndexKey, IndexKey> tmCursor,
                SearchRow last) {
            super(session);
            this.tmCursor = tmCursor;
            this.last = last;
        }

        @Override
        protected SearchRow nextSearchRow() {
            while (tmCursor.next()) {
                IndexKey current = getIndexKey(tmCursor);
                if (current == null)
                    continue; // 不匹配的索引项要跳过，不能直接结束扫描(会漏行)
                SearchRow searchRow = createSearchRow(current);
                if (searchRow == null)
                    continue;
                if (last != null && compareRows(searchRow, last) > 0)
                    return null;
                return searchRow;
            }
            return null;
        }
    }

    private class SsiBuildingCursor extends StandardSecondaryIndexCursor {

        private TransactionMapCursor<IndexKey, IndexKey> tmCursor;
        private final SearchRow last;
        private final Cursor primaryCursor;

        public SsiBuildingCursor(ServerSession session,
                TransactionMapCursor<IndexKey, IndexKey> tmCursor, SearchRow last,
                Cursor primaryCursor) {
            super(session);
            this.tmCursor = tmCursor;
            this.last = last;
            this.primaryCursor = primaryCursor;
        }

        @Override
        protected SearchRow nextSearchRow() {
            SearchRow searchRow;
            if (tmCursor != null) {
                if (tmCursor.next()) {
                    IndexKey current = getIndexKey(tmCursor);
                    searchRow = createSearchRow(current);
                    if (searchRow != null && last != null && compareRows(searchRow, last) > 0) {
                        searchRow = null;
                    }
                } else {
                    searchRow = null;
                }
                if (searchRow != null) {
                    return searchRow;
                } else {
                    tmCursor = null;
                }
            }
            if (primaryCursor.next()) {
                searchRow = primaryCursor.get();
            } else {
                searchRow = null;
            }
            return searchRow;
        }
    }

    private class SsiDistinctCursor extends StandardSecondaryIndexCursor {

        private final TransactionMap<IndexKey, IndexKey> map;
        private IndexKey oldKey;

        public SsiDistinctCursor(ServerSession session) {
            super(session);
            this.map = getTransactionMap(session);
        }

        @Override
        protected SearchRow nextSearchRow() {
            IndexKey current = map.higherKey(oldKey); // oldKey从null开始，此时返回第一个元素
            if (current != null) {
                oldKey = IndexKey.create(Long.MAX_VALUE, current.getColumns());
            }
            return createSearchRow(current);
        }
    }
}
