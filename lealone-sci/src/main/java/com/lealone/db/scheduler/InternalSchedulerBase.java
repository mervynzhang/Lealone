/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.lealone.db.link.LinkableBase;
import com.lealone.db.link.LinkableList;
import com.lealone.db.session.InternalSession;
import com.lealone.db.session.Session;
import com.lealone.db.session.SessionInfo;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.storage.page.PageOperation;
import com.lealone.transaction.PendingTransaction;

public abstract class InternalSchedulerBase extends SchedulerBase implements InternalScheduler {

    protected final AtomicReferenceArray<Scheduler> waitingSchedulers;
    protected final AtomicBoolean hasWaitingSchedulers = new AtomicBoolean(false);

    public InternalSchedulerBase(int id, String name, int schedulerCount, Map<String, String> config) {
        super(id, name, schedulerCount, config);
        waitingSchedulers = new AtomicReferenceArray<>(schedulerCount);
    }

    @Override
    public InternalSession getCurrentSession() {
        return (InternalSession) currentSession;
    }

    @Override
    public void addSession(InternalSession session) {
    }

    @Override
    public void removeSession(InternalSession session) {
    }

    @Override
    public void addSessionInitTask(Object task) {
    }

    @Override
    public void addSessionInfo(SessionInfo si) {
    }

    @Override
    public void removeSessionInfo(SessionInfo si) {
    }

    @Override
    public void validateSession(boolean isUserAndPasswordCorrect) {
    }

    // --------------------- 实现 SchedulerListener.Factory 接口 ---------------------

    @Override
    public <R> SchedulerListener<R> createSchedulerListener() {
        return new SchedulerListener<R>() {
            @Override
            public R await() {
                for (;;) {
                    if (completed)
                        break;
                    runMiscTasks();
                    runPageOperationTasks();
                    if (completed)
                        break;
                    runEventLoop();
                }
                if (exception != null)
                    throw exception;
                return result;
            }

            @Override
            public void wakeUp() {
                InternalSchedulerBase.this.wakeUp();
            }
        };
    }

    protected void runPageOperationTasks() {
    }

    // --------------------- 实现 PageOperation 相关代码 ---------------------

    @Override
    public void handlePageOperation(PageOperation po) {
    }

    @Override
    public void addWaitingScheduler(Scheduler scheduler) {
        int id = scheduler.getId();
        if (id >= 0) {
            waitingSchedulers.set(id, scheduler);
            hasWaitingSchedulers.set(true);
        }
    }

    @Override
    public void wakeUpWaitingSchedulers() {
        if (hasWaitingSchedulers.compareAndSet(true, false)) {
            for (int i = 0, length = waitingSchedulers.length(); i < length; i++) {
                Scheduler scheduler = waitingSchedulers.get(i);
                if (scheduler != null) {
                    scheduler.wakeUp();
                    waitingSchedulers.compareAndSet(i, scheduler, null);
                }
            }
        }
    }

    @Override
    public void wakeUpWaitingSchedulers(boolean reset) {
        if (reset) {
            wakeUpWaitingSchedulers();
        } else if (hasWaitingSchedulers.get()) {
            for (int i = 0, length = waitingSchedulers.length(); i < length; i++) {
                Scheduler scheduler = waitingSchedulers.get(i);
                if (scheduler != null) {
                    scheduler.wakeUp();
                }
            }
        }
    }

    // --------------------- 跟 PendingTransaction 相关 ---------------------

    // 存放还没有给客户端发送响应结果的事务
    protected final LinkableList<PendingTransaction> pendingTransactions = new LinkableList<>();

    // runPendingTransactions和addTransaction已经确保只有一个调度线程执行，所以是单线程安全的
    @Override
    public void runPendingTransactions() {
        if (pendingTransactions.isEmpty())
            return;
        PendingTransaction pt = pendingTransactions.getHead();
        while (pt != null && pt.isSynced()) {
            if (!pt.isCompleted()) {
                try {
                    pt.getTransaction().asyncCommitComplete();
                } catch (Throwable t) {
                    handleException("Failed to run pending transaction: " + pt, t);
                }
            }
            pt = pt.getNext();
            pendingTransactions.decrementSize();
            pendingTransactions.setHead(pt);
            PendingTransaction.decrement();
        }
        if (pendingTransactions.getHead() == null) {
            pendingTransactions.setTail(null);
        }
    }

    @Override
    public void addPendingTransaction(PendingTransaction pt) {
        pendingTransactions.add(pt);
        PendingTransaction.increment();
    }

    @Override
    public PendingTransaction getPendingTransaction() {
        return pendingTransactions.getHead();
    }

    // --------------------- 跟 SchedulerTaskManager 相关 ---------------------

    protected final LinkableList<SchedulerTaskManagerWrapper> taskManagers = new LinkableList<>();
    // 客户端线程和调度线程都会往taskManagers里加任务，链表本身不是线程安全的
    protected final Object taskManagersLock = new Object();

    // SchedulerTaskManager需要传递给多个Scheduler，需要包装成SchedulerTaskManagerWrapper
    // 如果SchedulerTaskManager直接继承自LinkableBase，多个Scheduler修改它的next会有并发问题
    protected static class SchedulerTaskManagerWrapper extends LinkableBase<SchedulerTaskManagerWrapper>
            implements SchedulerTaskManager {

        protected SchedulerTaskManager schedulerTaskManager;

        public SchedulerTaskManagerWrapper(SchedulerTaskManager schedulerTaskManager) {
            this.schedulerTaskManager = schedulerTaskManager;
        }

        @Override
        public boolean gcCompletedTasks(InternalScheduler scheduler) {
            return schedulerTaskManager.gcCompletedTasks(scheduler);
        }
    }

    protected void gcCompletedTasks() {
        List<SchedulerTaskManagerWrapper> tasks;
        while (true) {
            synchronized (taskManagersLock) {
                if (taskManagers.isEmpty())
                    return;
                tasks = new ArrayList<>();
                for (SchedulerTaskManagerWrapper t = taskManagers.getHead(); t != null;) {
                    tasks.add(t);
                    SchedulerTaskManagerWrapper next = t.next;
                    t.next = null;
                    t = next;
                }
                taskManagers.setHead(null);
                taskManagers.setTail(null);
            }
            List<SchedulerTaskManagerWrapper> remains = new ArrayList<>();
            for (SchedulerTaskManagerWrapper t : tasks) {
                boolean completed = false;
                try {
                    // 不能持锁回调，否则会跟调用addTaskManager时持有的其他锁形成死锁
                    completed = t.gcCompletedTasks(this);
                } catch (Throwable e) {
                    handleException("Failed to gc completed task: " + t, e);
                }
                if (!completed)
                    remains.add(t);
            }
            if (remains.isEmpty())
                return;
            synchronized (taskManagersLock) {
                for (int i = 0, size = remains.size(); i < size; i++)
                    remains.get(i).next = i + 1 < size ? remains.get(i + 1) : null;
                SchedulerTaskManagerWrapper tail = taskManagers.getTail();
                if (tail == null)
                    taskManagers.setHead(remains.get(0));
                else
                    tail.next = remains.get(0);
                taskManagers.setTail(remains.get(remains.size() - 1));
            }
            // 这一轮没有处理掉任何任务，不需要再扫一遍
            if (remains.size() == tasks.size())
                return;
        }
    }

    @Override
    public void addTaskManager(SchedulerTaskManager taskManager) {
        SchedulerTaskManagerWrapper wrapper = new SchedulerTaskManagerWrapper(taskManager);
        synchronized (taskManagersLock) {
            taskManagers.add(wrapper);
        }
    }

    // --------------------- 实现 SQLStatement 相关的代码 ---------------------

    @Override
    public void executeNextStatement() {
    }

    @Override
    public boolean yieldIfNeeded(PreparedSQLStatement current) {
        return false;
    }

    @Override
    public void addSession(Session session) {
        if (session instanceof InternalSession)
            addSession((InternalSession) session);
    }

    @Override
    public void removeSession(Session session) {
        if (session instanceof InternalSession)
            removeSession((InternalSession) session);
    }
}
