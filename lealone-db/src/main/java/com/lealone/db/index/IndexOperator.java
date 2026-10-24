/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.index;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.db.Database;
import com.lealone.db.DbObjectType;
import com.lealone.db.async.AsyncPeriodicTask;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.link.LinkableBase;
import com.lealone.db.link.LinkableList;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.row.Row;
import com.lealone.db.scheduler.InternalScheduler;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.db.scheduler.SchedulerFactory;
import com.lealone.db.scheduler.SchedulerTaskManager;
import com.lealone.db.scheduler.SchedulerThread;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.StandardTable;
import com.lealone.db.value.Value;
import com.lealone.transaction.Transaction;

public class IndexOperator implements Runnable, SchedulerTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(IndexOperator.class);

    private final InternalScheduler scheduler;
    private final StandardTable table;
    private final Index index;
    private final ServerSession session; // 这个session只用于加锁
    private final ServerSession ioSession; // 这个session只用于执行索引操作
    private final AsyncPeriodicTask task;

    private final LinkableList<IndexOperation>[] pendingIosArray;
    private final boolean[] taskManagerAdded;
    private final AtomicLong indexOperationSize = new AtomicLong(0);
    private final AtomicBoolean indexOperationErrorLogged = new AtomicBoolean(false);
    private final DbObjectLock lock = new DbObjectLock(DbObjectType.INDEX);

    @SuppressWarnings("unchecked")
    public IndexOperator(InternalScheduler scheduler, StandardTable table, Index index) {
        this.scheduler = scheduler;
        this.table = table;
        this.index = index;
        Database db = table.getDatabase();
        session = db.createSession(db.getSystemUser(), scheduler);
        ioSession = db.createSession(db.getSystemUser(), scheduler);
        ioSession.setRedoLogEnabled(false);
        task = new AsyncPeriodicTask(0, 100, this);
        scheduler.addPeriodicTask(task);
        pendingIosArray = new LinkableList[scheduler.getSchedulerFactory().getSchedulerCount()];
        taskManagerAdded = new boolean[pendingIosArray.length];
    }

    public boolean hasPendingIndexOperation() {
        return indexOperationSize.get() > 0;
    }

    /**
     * 把还没有执行的索引操作尽量执行完，返回还留在队列里的事务的最小事务id。
     * 非唯一索引的写操作是异步批量执行的，执行checkpoint或关闭数据库时如果不先flush，
     * 索引map会被保存成不完整的状态，而checkpoint同时会截断redo log，
     * 重启后索引里就会永久丢失这部分记录(全表扫描正常，走索引的等值查询查不到)。
     * 如果有些索引操作所属的事务还没有提交，它们不能执行，checkpoint必须把
     * lastTransactionId限制在这些事务之前，让redo log在重启时把这些索引操作重做一遍。
     */
    public long flush() {
        if (!hasPendingIndexOperation() && !scheduler.hasPendingPageOperations())
            return Long.MAX_VALUE;
        if (SchedulerThread.currentScheduler() == scheduler) {
            // 当前线程就是这个索引操作绑定的调度器线程，直接执行，否则等待自己会死锁
            run();
            return getMinPendingTransactionId();
        }
        long deadline = System.currentTimeMillis() + 5000;
        // 索引操作只是把page operation提交给调度器，所以还要等page operation真正执行完
        while ((hasExecutableIndexOperation() || scheduler.hasPendingPageOperations())
                && System.currentTimeMillis() < deadline) {
            task.resetLast(); // 让周期任务马上执行，不用等下一次100ms周期
            scheduler.wakeUp();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return getMinPendingTransactionId();
            }
        }
        return getMinPendingTransactionId();
    }

    // 是否有可以立即执行的索引操作(已经提交或已经回滚)，未提交的事务会阻塞它后面的索引操作
    private boolean hasExecutableIndexOperation() {
        for (int i = 0, len = pendingIosArray.length; i < len; i++) {
            LinkableList<IndexOperation> pendingIos = pendingIosArray[i];
            if (pendingIos == null)
                continue;
            IndexOperation io = pendingIos.getHead();
            while (io != null && io.isCompleted())
                io = io.getNext();
            if (io != null && io.getStatus() != 0)
                return true;
        }
        return false;
    }

    // 还没有执行完的索引操作所属事务的最小事务id，没有就返回Long.MAX_VALUE
    private long getMinPendingTransactionId() {
        long minTid = Long.MAX_VALUE;
        for (int i = 0, len = pendingIosArray.length; i < len; i++) {
            LinkableList<IndexOperation> pendingIos = pendingIosArray[i];
            if (pendingIos == null)
                continue;
            IndexOperation io = pendingIos.getHead();
            while (io != null) {
                if (!io.isCompleted()) {
                    try {
                        long tid = io.getTransaction().getTransactionId();
                        if (tid < minTid)
                            minTid = tid;
                    } catch (Exception e) {
                        // 事务已经关闭时忽略
                    }
                }
                io = io.getNext();
            }
        }
        return minTid;
    }

    private void addIndexOperation(InternalScheduler currentScheduler, IndexOperation io) {
        // 加索引操作的线程(客户端线程或调度线程)可能跟gcCompletedTasks所在的调度线程不同，
        // 不加锁时LinkableList会被并发修改，索引操作会丢失
        int schedulerId = currentScheduler.getId();
        boolean needAddTaskManager = false;
        synchronized (pendingIosArray) {
            LinkableList<IndexOperation> pendingIos = pendingIosArray[schedulerId];
            if (pendingIos == null) {
                pendingIos = new LinkableList<>();
                pendingIosArray[schedulerId] = pendingIos;
            }
            if (!taskManagerAdded[schedulerId]) {
                taskManagerAdded[schedulerId] = true;
                needAddTaskManager = true;
            }
            pendingIos.add(io);
        }
        indexOperationSize.incrementAndGet();
        // 不能持pendingIosArray的锁去注册task manager，scheduler的taskManagers链表有自己的锁，
        // 反过来scheduler执行gcCompletedTasks时也会用pendingIosArray的锁
        if (needAddTaskManager)
            currentScheduler.addTaskManager(this);

        // 调用wakeUp会降低一点点性能
        // if (currentScheduler != scheduler)
        // scheduler.wakeUp();
    }

    @Override
    public boolean gcCompletedTasks(InternalScheduler scheduler) {
        synchronized (pendingIosArray) {
            LinkableList<IndexOperation> pendingIos = pendingIosArray[scheduler.getId()];
            if (pendingIos == null)
                return true;
            else if (pendingIos.isEmpty()) {
                pendingIosArray[scheduler.getId()] = null;
                taskManagerAdded[scheduler.getId()] = false;
                return true;
            }
            IndexOperation io = pendingIos.getHead();
            while (io != null && io.isCompleted()) {
                io = io.getNext();
                pendingIos.decrementSize();
                pendingIos.setHead(io);
            }
            if (pendingIos.getHead() == null) {
                pendingIos.setTail(null);
                pendingIosArray[scheduler.getId()] = null;
                taskManagerAdded[scheduler.getId()] = false;
                return true;
            }
            return false;
        }
    }

    private void cancelTask() {
        task.cancel();
        scheduler.removePeriodicTask(task);
        synchronized (pendingIosArray) {
            for (int i = 0; i < pendingIosArray.length; i++) {
                pendingIosArray[i] = null;
                taskManagerAdded[i] = false;
            }
        }
        indexOperationSize.set(0);
    }

    @Override
    public void run() {
        run(session);
    }

    public void run(ServerSession session) {
        if (indexOperationSize.get() <= 0)
            return;
        if (!lock.tryExclusiveLock(session)) {
            if (session != this.session)
                throw DbObjectLock.LOCKED_EXCEPTION;
            return;
        }
        if (table.isInvalid()) { // 比如已经drop了
            cancelTask();
            return;
        }
        int count = 0;
        try {
            AtomicInteger index = new AtomicInteger(0);
            // 要用session的当前调度器
            InternalScheduler currentScheduler = session.getScheduler();
            Scheduler[] schedulers = currentScheduler.getSchedulerFactory().getSchedulers();
            int schedulerCount = pendingIosArray.length;
            while (indexOperationSize.get() > 0) {
                long lastSize = indexOperationSize.get();
                IndexOperation[] lastIos = new IndexOperation[schedulerCount];
                IndexOperation[] ios = new IndexOperation[schedulerCount];
                outer: for (int i = 0; i < schedulerCount; i++) {
                    LinkableList<IndexOperation> pendingIos = pendingIosArray[i];
                    if (pendingIos == null)
                        continue;
                    IndexOperation io = pendingIos.getHead();
                    while (io != null) {
                        if (io.isCompleted()) {
                            io = io.getNext();
                            continue;
                        }
                        int status = getStatus(io);
                        if (status == 0) {
                            // 如果当前事务未提交，可以在当前事务中直接执行索引操作
                            if (io.transaction == session.getTransaction()) {
                                io.run(this.index, session);
                                io.setCompleted(true);
                                indexOperationSize.decrementAndGet();
                                io = io.getNext();
                                continue;
                            } else {
                                continue outer; // 未提交，不能进行后续处理
                            }
                        } else if (status < 0) {
                            io.setCompleted(true); // 已经回滚，直接废弃
                            indexOperationSize.decrementAndGet();
                            continue;
                        }
                        ios[i] = io;
                        break;
                    }
                }
                // 找出提交时间戳最小的IndexOperation
                IndexOperation io = nextIndexOperation(ios, index);
                while (io != null) {
                    run(io);
                    int i = index.get();
                    lastIos[i] = io;
                    // 索引项的写入是异步的页操作：先结束本批次，让调度线程执行完这些页操作，
                    // 否则同一个索引的下一个操作读不到上一个操作刚写入的索引项，旧索引项会残留
                    if (scheduler.hasPendingPageOperations()) {
                        schedulers[i].wakeUp();
                        task.resetLast(); // 让周期任务马上执行，不用等下一次100ms周期
                        scheduler.wakeUp();
                        return;
                    }
                    while (true) {
                        io = io.getNext();
                        if (io == null) {
                            ios[i] = null;
                            break;
                        }
                        // 索引操作链表中的下一个索引操作对应的父事务若是没有提交就不能执行它了
                        int status = getStatus(io);
                        if (status == 0) {
                            ios[i] = null; // 未提交，不能进行后续处理
                            break;
                        } else if (status < 0) {
                            io.setCompleted(true); // 已经回滚，直接废弃
                            indexOperationSize.decrementAndGet();
                            continue;
                        } else {
                            ios[i] = io;
                            break;
                        }
                    }
                    if ((++count & 127) == 0) {
                        if (currentScheduler.yieldIfNeeded(null))
                            return;
                    }
                    io = nextIndexOperation(ios, index);
                }
                for (int i = 0; i < schedulerCount; i++) {
                    if (lastIos[i] == null) {
                        continue;
                    }
                    schedulers[i].wakeUp();
                }
                // 所有的事务都没提交，直接退出
                if (lastSize == indexOperationSize.get())
                    break;
            }
        } catch (Throwable t) {
            if (table.isInvalid()) {
                cancelTask();
            } else {
                throw DbException.convert(t);
            }
        } finally {
            if (session == this.session) {
                session.asyncCommit();
            } else {
                session.removeLock(lock);
                lock.unlockFast();
            }
        }
    }

    private IndexOperation nextIndexOperation(IndexOperation[] ios, AtomicInteger index) {
        IndexOperation minIo = null;
        long minCommitTimestamp = Long.MAX_VALUE;
        for (int i = 0, len = ios.length; i < len; i++) {
            IndexOperation io = ios[i];
            while (io != null) {
                if (io.isCompleted()) {
                    io = io.getNext();
                    ios[i] = io;
                    continue;
                }
                if (io.getTransaction().getCommitTimestamp() < minCommitTimestamp) {
                    minCommitTimestamp = io.getTransaction().getCommitTimestamp();
                    minIo = io;
                    index.set(i);
                }
                break;
            }
        }
        return minIo;
    }

    // getStatus读的maxCommittedLogId不是volatile的，并发时可能一直读到旧值，
    // 导致已经提交的索引操作被当成未提交(一直不执行)或者当成已回滚(被丢弃)，所以先用commitTimestamp判断
    private static int getStatus(IndexOperation io) {
        if (io.getTransaction().getCommitTimestamp() > 0)
            return 1;
        return io.getStatus();
    }

    private void run(IndexOperation io) {
        // 在没有可重复读事务的情况下，对索引执行insert或delete操作可以不用走事务层，直接走存储层
        // 页操作还没执行完时不能走存储层快路径，否则同一个索引key上的操作可能乱序
        boolean fastPath = !(table.getDatabase().getTransactionEngine()
                .containsRepeatableReadTransactions() || io instanceof UIO
                || scheduler.hasPendingPageOperations());
        ioSession.setFastPath(fastPath);
        if (!fastPath) {
            Transaction transaction = ioSession.getTransaction();
            transaction.setParentTransaction(io.getTransaction());
        }
        try {
            io.run(index, ioSession);
        } catch (Exception e) {
            if (table.isInvalid()) {
                cancelTask();
            } else if (indexOperationErrorLogged.compareAndSet(false, true)) {
                logger.warn("Failed to run index operation: " + table.getName() + " "
                        + index.getName() + " " + io.getClass().getSimpleName() + " rowKey="
                        + io.rowKey, e);
            }
        } finally {
            io.setCompleted(true);
            indexOperationSize.decrementAndGet();
            if (!fastPath) {
                // 同步提交，索引操作真正生效之后才算完成，保证后续操作能看到它
                ioSession.commit();
            }
        }
    }

    public static void addIndexOperation(ServerSession session, StandardTable table, IndexOperation io) {
        // 0是合法的rowKey，只有AIO在自增主键场景下rowKey要等主键写成功才确定，才需要用lastIdentity回填；
        // UIO/RIO的rowKey一定有效，用lastIdentity覆盖会把索引操作当成别的行的
        if (io instanceof AIO && io.rowKey == 0)
            io.rowKey = session.getLastIdentity();
        // 同一个row key的索引操作必须由同一个调度器按顺序执行，否则并发更新同一个索引key时会丢更新
        InternalScheduler target = getSchedulerByRowKey(session.getScheduler(), io.rowKey);
        List<IndexOperator> indexOperators = table.getIndexOperators();
        for (int i = 0, size = indexOperators.size(); i < size; i++) {
            indexOperators.get(i).addIndexOperation(target, (i == 0 ? io : io.copy()));
        }
    }

    // 只交给这个索引自己的IndexOperator执行，不再广播给表上的其他索引
    public static void addIndexOperation(ServerSession session, StandardTable table, Index index,
            IndexOperation io) {
        InternalScheduler target = getSchedulerByRowKey(session.getScheduler(), io.rowKey);
        index.getIndexOperator().addIndexOperation(target, io);
    }

    private static InternalScheduler getSchedulerByRowKey(InternalScheduler current, long rowKey) {
        SchedulerFactory factory = current.getSchedulerFactory();
        int count = factory.getSchedulerCount();
        if (count <= 1)
            return current;
        Scheduler s = factory.getScheduler((int) Math.floorMod(rowKey, count));
        return s instanceof InternalScheduler ? (InternalScheduler) s : current;
    }

    public static IndexOperation createAIO(long rowKey, Value[] columns) {
        return new AIO(rowKey, columns);
    }

    public static IndexOperation createUIO(long oldRowKey, long newRowKey, Value[] oldColumns,
            Value[] newColumns, int[] updateColumns) {
        return new UIO(oldRowKey, newRowKey, oldColumns, newColumns, updateColumns);
    }

    public static IndexOperation createRIO(long rowKey, Value[] columns) {
        return new RIO(rowKey, columns);
    }

    public static abstract class IndexOperation extends LinkableBase<IndexOperation> {

        long rowKey; // addRow的场景需要回填
        final Value[] columns;

        Transaction transaction;
        int savepointId;

        public IndexOperation(long rowKey, Value[] columns) {
            this.rowKey = rowKey;
            this.columns = columns;
        }

        public int getSavepointId() {
            return savepointId;
        }

        public Transaction getTransaction() {
            return transaction;
        }

        public void setTransaction(Transaction transaction) {
            this.transaction = transaction;
            this.savepointId = transaction.getSavepointId();
        }

        public int getStatus() {
            return transaction.getStatus(savepointId);
        }

        private boolean completed;

        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public abstract void run(Index index, ServerSession session);

        public abstract IndexOperation copy();

        public IndexOperation copy(IndexOperation io) {
            io.transaction = transaction;
            io.savepointId = savepointId;
            return io;
        }
    }

    private static class AIO extends IndexOperation {

        public AIO(long rowKey, Value[] columns) {
            super(rowKey, columns);
        }

        @Override
        public void run(Index index, ServerSession session) {
            index.add(session, new Row(rowKey, columns), AsyncResultHandler.emptyHandler());
        }

        @Override
        public IndexOperation copy() {
            return super.copy(new AIO(rowKey, columns));
        }
    }

    private static class UIO extends IndexOperation {

        final long oldRowKey;
        final Value[] oldColumns;
        final int[] updateColumns;

        public UIO(long oldRowKey, long newRowKey, Value[] oldColumns, Value[] newColumns,
                int[] updateColumns) {
            super(newRowKey, newColumns);
            this.oldRowKey = oldRowKey;
            this.oldColumns = oldColumns;
            this.updateColumns = updateColumns;
        }

        @Override
        public void run(Index index, ServerSession session) {
            index.update(session, new Row(oldRowKey, oldColumns), new Row(rowKey, columns), oldColumns,
                    updateColumns, true, AsyncResultHandler.emptyHandler());
        }

        @Override
        public IndexOperation copy() {
            return super.copy(new UIO(oldRowKey, rowKey, oldColumns, columns, updateColumns));
        }
    }

    private static class RIO extends IndexOperation {

        public RIO(long rowKey, Value[] columns) {
            super(rowKey, columns);
        }

        @Override
        public void run(Index index, ServerSession session) {
            index.remove(session, new Row(rowKey, columns), columns, true,
                    AsyncResultHandler.emptyHandler());
        }

        @Override
        public IndexOperation copy() {
            return super.copy(new RIO(rowKey, columns));
        }
    }
}
