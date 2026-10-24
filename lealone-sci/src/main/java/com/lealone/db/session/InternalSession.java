/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.session;

import com.lealone.db.lock.Lock;
import com.lealone.db.scheduler.InternalScheduler;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.sql.PreparedSQLStatement.YieldableCommand;
import com.lealone.transaction.Transaction;

public interface InternalSession extends Session {

    public default SessionStatus getStatus() {
        return SessionStatus.TRANSACTION_NOT_START;
    }

    public default void setStatus(SessionStatus sessionStatus) {
    }

    default void setLockedBy(SessionStatus sessionStatus, Transaction lockedBy, Object lockedObject) {
    }

    default Transaction getTransaction() {
        return null;
    }

    default void addLock(Lock lock) {
    }

    default void removeLock(Lock lock) {
    }

    default void addWaitingScheduler(Scheduler scheduler) {
    }

    default void wakeUpWaitingSchedulers() {
    }

    default boolean compareAndSet(SessionStatus expect, SessionStatus update) {
        return false;
    }

    // 等锁超时检查，超时返回错误(事务已经回滚)，没超时返回null。只能在执行语句的线程里调用
    default Throwable getLockTimeoutError() {
        return null;
    }

    // 只判断等锁是否超时，不改变session状态，可以在其他线程里调用
    default boolean isLockWaitTimeout() {
        return false;
    }

    // 等锁超时后把session标成就绪，让重试语句的线程自己抛超时错误
    default boolean retryForLockTimeout() {
        return false;
    }

    default boolean isQueryCommand() {
        return false;
    }

    default boolean isRedoLogEnabled() {
        return true;
    }

    void init();

    @Override
    InternalScheduler getScheduler();

    void setScheduler(InternalScheduler scheduler);

    void setYieldableCommand(YieldableCommand yieldableCommand);

    YieldableCommand getYieldableCommand();

    YieldableCommand getYieldableCommand(boolean checkTimeout, TimeoutListener timeoutListener);

    public static interface TimeoutListener {
        void onTimeout(YieldableCommand c, Throwable e);
    }

    @Override
    void setSessionInfo(SessionInfo si);

    @Override
    SessionInfo getSessionInfo();
}
