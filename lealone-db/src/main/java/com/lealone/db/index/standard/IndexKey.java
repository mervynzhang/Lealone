/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.index.standard;

import com.lealone.db.lock.Lock;
import com.lealone.db.lock.Lockable;
import com.lealone.db.lock.LockableBase;
import com.lealone.db.row.Row;
import com.lealone.db.value.Value;

public abstract class IndexKey extends LockableBase {

    private long key;

    // 索引项被删除时不再把列值清空，而是保留列值并打上删除标记(墓碑)。
    // 这样BTree还是能按原来的顺序找到这个索引项(compare始终是全序的)，
    // 页分裂、复活、回滚时也不需要额外的字段来保存删除前的列值。
    private boolean deleted;

    public IndexKey(long key) {
        this.key = key;
    }

    @Override
    public boolean isIndexKey() {
        return true;
    }

    public void setKey(long key) {
        this.key = key;
    }

    @Override
    public long getKey() {
        return key;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    // 返回索引项保存的列值，已删除的索引项也返回删除前的列值
    public abstract Value[] getColumns();

    // 返回第index列的值，已删除的索引项也返回删除前的列值
    public Value getColumn(int index) {
        Value[] columns = getColumns();
        return columns != null && index < columns.length ? columns[index] : null;
    }

    // 复制一个只用于比较的key(比如页分裂时生成的分裂键)，要保留删除标记
    public abstract IndexKey copyKey();

    @Override
    public String toString() {
        return Row.toString(key, getColumns());
    }

    public static IndexKey create(long key, Value[] columns) {
        IndexKey k;
        if (columns != null && columns.length == 1) {
            k = new SingleIndexKey(key, columns[0]);
        } else {
            k = new CompoundIndexKey(key, columns);
        }
        if (columns == null) // 没有列值的索引项只能是墓碑
            k.setDeleted(true);
        return k;
    }

    public static class SingleIndexKey extends IndexKey {

        private Value column;

        public SingleIndexKey(long key, Value column) {
            super(key);
            this.column = column;
        }

        @Override
        public Value[] getColumns() {
            return new Value[] { column };
        }

        @Override
        public Value getColumn(int index) {
            return index == 0 ? column : null;
        }

        @Override
        public void setLockedValue(Object value) {
            if (value instanceof SingleIndexKey) {
                SingleIndexKey k = (SingleIndexKey) value;
                column = k.column;
                setDeleted(k.isDeleted());
            } else if (value == null) {
                // 删除时保留列值，让BTree还能按原来的顺序找到这个墓碑
                setDeleted(true);
            } else {
                column = (Value) value;
                setDeleted(false);
            }
        }

        @Override
        public Object getLockedValue() {
            return isDeleted() ? null : column;
        }

        @Override
        public Object copy(Object oldLockedValue, Lock lock) {
            SingleIndexKey k = oldLockedValue == null //
                    ? new SingleIndexKey(getKey(), column)
                    : new SingleIndexKey(getKey(), (Value) oldLockedValue);
            k.setDeleted(oldLockedValue == null && isDeleted());
            k.setLock(lock);
            return k;
        }

        @Override
        public Lockable copySelf(Object oldLockedValue) {
            SingleIndexKey k = oldLockedValue == null //
                    ? new SingleIndexKey(getKey(), column)
                    : new SingleIndexKey(getKey(), (Value) oldLockedValue);
            k.setDeleted(oldLockedValue == null && isDeleted());
            return k;
        }

        @Override
        public IndexKey copyKey() {
            SingleIndexKey k = new SingleIndexKey(getKey(), column);
            k.setDeleted(isDeleted());
            return k;
        }
    }

    public static class CompoundIndexKey extends IndexKey {

        private Value[] columns;

        public CompoundIndexKey(long key, Value[] columns) {
            super(key);
            this.columns = columns;
        }

        @Override
        public Value[] getColumns() {
            return columns;
        }

        @Override
        public void setLockedValue(Object value) {
            if (value instanceof CompoundIndexKey) {
                CompoundIndexKey k = (CompoundIndexKey) value;
                columns = k.columns;
                setDeleted(k.isDeleted());
            } else if (value == null) {
                // 删除时保留列值，让BTree还能按原来的顺序找到这个墓碑
                setDeleted(true);
            } else {
                columns = (Value[]) value;
                setDeleted(false);
            }
        }

        @Override
        public Object getLockedValue() {
            return isDeleted() ? null : columns;
        }

        @Override
        public Object copy(Object oldLockedValue, Lock lock) {
            CompoundIndexKey k = oldLockedValue == null //
                    ? new CompoundIndexKey(getKey(), columns)
                    : new CompoundIndexKey(getKey(), (Value[]) oldLockedValue);
            k.setDeleted(oldLockedValue == null && isDeleted());
            k.setLock(lock);
            return k;
        }

        @Override
        public Lockable copySelf(Object oldLockedValue) {
            CompoundIndexKey k = oldLockedValue == null //
                    ? new CompoundIndexKey(getKey(), columns)
                    : new CompoundIndexKey(getKey(), (Value[]) oldLockedValue);
            k.setDeleted(oldLockedValue == null && isDeleted());
            return k;
        }

        @Override
        public IndexKey copyKey() {
            CompoundIndexKey k = new CompoundIndexKey(getKey(), columns);
            k.setDeleted(isDeleted());
            return k;
        }
    }
}
