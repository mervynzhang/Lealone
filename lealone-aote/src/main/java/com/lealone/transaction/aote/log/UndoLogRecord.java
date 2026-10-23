/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.transaction.aote.log;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lealone.agent.SystemOutline;
import com.lealone.agent.SystemOutlineNode;
import com.lealone.db.DataBuffer;
import com.lealone.db.lock.Lock;
import com.lealone.db.lock.Lockable;
import com.lealone.db.value.ValueString;
import com.lealone.storage.FormatVersion;
import com.lealone.storage.StorageMap;
import com.lealone.storage.StorageMap.RedoLogBuffer;
import com.lealone.transaction.aote.AOTransactionEngine;
import com.lealone.transaction.aote.TransactionalValue;

public abstract class UndoLogRecord {

    protected final StorageMap<Object, ?> map;
    protected final Object key;
    protected final Lockable lockable;
    protected final Object oldValue;
    protected boolean undone;

    UndoLogRecord next;
    UndoLogRecord prev;

    /**
     * 本记录是否是"复活墓碑"(把已删除的记录重新写活)。
     * 复活前的状态是"已删除"，所以不能用 oldValue 判断该怎么回滚：
     * oldValue 传 null 会被当成 insert(回滚时物理删除)，传新值又会被恢复成新值。
     * 也不能往 oldValue 里塞别的类型：row map 的 oldValue 必须是 Value[]，
     * 提交时要按 Value[] 计算内存(pageUsedMemory)，否则会抛 ClassCastException。
     * 所以只用这个标记影响回滚方式。
     */
    private boolean revivedFromDelete;
    private long revivedOldRowId = Long.MIN_VALUE;

    public UndoLogRecord setRevivedFromDelete(long oldRowId) {
        this.revivedFromDelete = true;
        this.revivedOldRowId = oldRowId;
        return this;
    }

    @SuppressWarnings("unchecked")
    public UndoLogRecord(StorageMap<?, ?> map, Object key, Lockable lockable, Object oldValue) {
        this.map = (StorageMap<Object, ?>) map;
        this.key = key;
        this.lockable = lockable;
        this.oldValue = oldValue;
    }

    public void setUndone(boolean undone) {
        this.undone = undone;
    }

    public boolean isUndone() {
        return undone;
    }

    // 按 key 查到的记录是否就是当前这条(唯一索引下同键可能有多条)
    private boolean isRemovable(Object key) {
        Object found = map.get(key);
        return found == null || found == lockable;
    }

    /**
     * 索引项的key就是它自己，用lockable自身去删除时compare(a,a)直接返回0，能稳定命中。
     */
    private Object removalKey() {
        if (lockable.isIndexKey())
            return lockable;
        return key;
    }

    // 回滚插入的记录时把它从页里删掉
    private void removeInsertedKey() {
        // 页操作可能还没执行完，此时lockable还没有关联到页，什么都没插入，不用删
        Lock lock = lockable.getLock();
        if (lock != null && lock.getPageListener() != null)
            lock.getPageListener().getPageReference().remove(removalKey());
    }

    protected boolean ignore() {
        // 事务取消或map关闭或删除时直接忽略
        return undone || map.isClosed();
    }

    protected abstract void commitUpdate();

    // 调用这个方法时事务已经提交，redo日志已经写完，这里只是在内存中更新到最新值
    public void commit(AOTransactionEngine te) {
        SystemOutline.createNode(SystemOutlineNode.UndoLogRecord_commit);
        if (ignore())
            return;

        if (oldValue == null) { // insert
            TransactionalValue.commit(true, map, key, lockable);
        } else if (lockable.getLockedValue() == null) { // delete
            if (!te.containsRepeatableReadTransactions()) {
                // 唯一索引比较时不看 rowId，如果索引里同时存在同键的另一条记录，
                // 按 key 删除可能删掉别人，所以先确认按 key 找到的就是这条记录
                if (isRemovable(key))
                    lockable.getPageListener().getPageReference().remove(removalKey());
            } else {
                map.decrementSize(); // 要减去1
                TransactionalValue.commit(false, map, key, lockable);
            }
        } else { // update
            commitUpdate();
        }
    }

    // 当前事务开始rollback了，调用这个方法在内存中撤销之前的更新
    public void rollback(AOTransactionEngine te) {
        if (ignore())
            return;

        if (revivedFromDelete) {
            // 撤销"复活"：恢复成墓碑，并把 rowId 换回被删除那一行的
            if (lockable.isIndexKey() && revivedOldRowId != Long.MIN_VALUE)
                lockable.setKey(revivedOldRowId);
            lockable.setLockedValue(null);
        } else if (oldValue == null) {
            removeInsertedKey();
        } else {
            TransactionalValue.rollback(oldValue, lockable);
        }
    }

    public abstract int writeForRedo(Map<String, RedoLogBuffer> logs, int logServiceIndex,
            UndoLog undoLog);

    public static class KeyOnlyULR extends UndoLogRecord {

        public KeyOnlyULR(StorageMap<?, ?> map, Object key, Lockable lockable, Object oldValue) {
            super(map, key, lockable, oldValue);
        }

        @Override
        protected void commitUpdate() {
            // 没有update
        }

        @Override
        public int writeForRedo(Map<String, RedoLogBuffer> logs, int logServiceIndex, UndoLog undoLog) {
            // 直接结束了
            // 比如索引不用写redo log
            return 0;
        }
    }

    public static class KeyValueULR extends UndoLogRecord {

        // 写redo log时需要用它，不能直接使用lockable.getValue()，因为会变动
        private final Object newValue;
        private final int metaVersion;
        private final int logServiceIndex;

        public KeyValueULR(StorageMap<?, ?> map, Object key, Lockable lockable, Object oldValue,
                int logServiceIndex) {
            super(map, key, lockable, oldValue);
            this.newValue = lockable.getLockedValue();
            this.metaVersion = lockable.getMetaVersion();
            this.logServiceIndex = logServiceIndex;
        }

        @Override
        protected void commitUpdate() {
            int memory = map.getValueType().getColumnsMemory(lockable.getLockedValue())
                    - map.getValueType().getColumnsMemory(oldValue);
            if (memory != 0)
                lockable.getPageListener().getPageReference().addPageUsedMemory(memory);

            TransactionalValue.commit(false, map, key, lockable);
        }

        @Override // 写redo log时，不关心oldValue
        public int writeForRedo(Map<String, RedoLogBuffer> logs, int logServiceIndex, UndoLog undoLog) {
            SystemOutline.createNode(SystemOutlineNode.UndoLogRecord_writeForRedo);
            if (logServiceIndex != this.logServiceIndex || ignore() || map.isInMemory())
                return 0;
            RedoLogBuffer logBuffer = map.getRedoLogBuffer();
            if (logBuffer == null)
                return 0;
            logs.put(map.getName(), logBuffer);
            DataBuffer log = logBuffer.getLog();
            int pos = log.position();
            log.putInt(0);
            if (newValue == null) { // 删除
                if (undoLog.isMultiMaps()) {
                    log.put((byte) 2);
                    log.putVarLong(undoLog.getTransactionId());
                    writeMapNames(log, undoLog);
                } else {
                    log.put((byte) 0);
                }
                map.getKeyType().write(log, key, FormatVersion.FORMAT_VERSION);
            } else { // 增加
                if (undoLog.isMultiMaps()) {
                    log.put((byte) 3);
                    log.putVarLong(undoLog.getTransactionId());
                    writeMapNames(log, undoLog);
                } else {
                    log.put((byte) 1);
                }
                log.putVarInt(metaVersion);
                map.getKeyType().write(log, key, FormatVersion.FORMAT_VERSION);
                // 如果这里运行时出现了cast异常，可能是上层应用没有通过TransactionMap提供的api来写入最初的数据
                map.getValueType().getRawType().write(log, lockable, newValue,
                        FormatVersion.FORMAT_VERSION);
            }
            int len = log.position() - pos;
            log.putInt(pos, len - 4);
            return len;
        }

        private void writeMapNames(DataBuffer buff, UndoLog undoLog) {
            Map<StorageMap<?, ?>, AtomicBoolean> maps = undoLog.getMaps();
            buff.putVarInt(maps.size());
            for (StorageMap<?, ?> map : maps.keySet())
                ValueString.type.write(buff, map.getName());
        }
    }

    // 兼容老版本的redo log
    public static void readForRedo(ByteBuffer buff, Map<String, List<ByteBuffer>> pendingRedoLog) {
        while (buff.hasRemaining()) {
            // 此时还没有打开底层存储的map，所以只预先解析出mapName和keyValue字节数组
            String mapName = ValueString.type.read(buff, FormatVersion.FORMAT_VERSION_1);
            List<ByteBuffer> keyValues = pendingRedoLog.get(mapName);
            if (keyValues == null) {
                keyValues = new LinkedList<>();
                pendingRedoLog.put(mapName, keyValues);
            }
            int len = buff.getInt();
            byte[] keyValue = new byte[len];
            buff.get(keyValue);
            keyValues.add(ByteBuffer.wrap(keyValue));
        }
    }
}
