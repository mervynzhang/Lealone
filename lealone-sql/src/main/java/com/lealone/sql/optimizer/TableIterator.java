/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.optimizer;

import com.lealone.db.index.Cursor;
import com.lealone.db.lock.Lock;
import com.lealone.db.lock.Lockable;
import com.lealone.db.row.Row;
import com.lealone.db.row.SearchRow;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Table;

public class TableIterator {

    private final ServerSession session;
    private final TableFilter tableFilter;
    private final Table table;
    private Row oldRow;
    private Cursor cursor;

    public TableIterator(ServerSession session, TableFilter tableFilter) {
        this.session = session;
        this.tableFilter = tableFilter;
        this.table = tableFilter.getTable();
    }

    public void setCursor(Cursor cursor) {
        this.cursor = cursor;
    }

    public void start() {
        tableFilter.startQuery(session);
        reset();
    }

    public void reset() {
        // 语句重试必须从头重扫：这里不清旧行的话，下一次next()会用旧行rebuild并直接返回true，
        // 而游标还停在起始位置，随后又会走到同一行，导致同一行在一次语句执行里被处理两次
        oldRow = null;
        tableFilter.reset();
    }

    public boolean next() {
        if (oldRow != null) {
            Row r = oldRow;
            oldRow = null;
            // 当发生行锁时可以直接用tableFilter的当前值重试
            if (tableFilter.rebuildSearchRow(session, r) != null)
                return true;
        }
        if (cursor == null) {
            return tableFilter.next();
        } else {
            return cursor.next();
        }
    }

    public Row getRow() {
        if (cursor == null) {
            return tableFilter.get();
        } else {
            SearchRow found = cursor.getSearchRow();
            return tableFilter.getTable().getRow(session, found.getKey());
        }
    }

    public int tryLockRow() {
        Row oldRow = getRow();
        if (oldRow == null) { // 已经删除了
            return -1;
        }
        // 总是使用最原始的那个row对象锁，
        // 因为在遍历的时候如果其他事务未提交会为当前事务创建一个新的row对象，不能在新的row对象上加锁
        Lock lock = oldRow.getLock();
        if (lock != null) {
            Lockable lockable = lock.getLockable();
            if (lockable != null && lockable != oldRow) {
                oldRow = (Row) lock.getLockable();
            }
        }
        Object oldValue = oldRow.getLockedValue();
        int ret = table.tryLockRow(session, oldRow);
        if (ret < 0) { // 已经删除或过期了
            if (ret == -2) {// 记录已经过期
                // 新值被临时放到lock中存放
                // 重新调用oldRow.getLock()，因为会改变
                this.oldRow = (Row) oldRow.getLock().getLockable();
            }
            return -1;
        } else if (ret == 0) { // 被其他事务锁住了
            this.oldRow = oldRow;
            return 0;
        }
        if (oldValue != oldRow.getLockedValue()) { // isRowChanged
            this.oldRow = oldRow;
            return -1;
        }
        // 锁成功后要用真正加锁的行对象刷新tableFilter，
        // 否则后续读到的是加锁前遍历得到的副本，会基于旧值更新导致丢失更新
        tableFilter.rebuildSearchRow(session, oldRow);
        return 1;
    }

    public void onLockedException() {
        oldRow = getRow();
    }
}
