/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.transaction;

import java.util.List;
import java.util.function.LongSupplier;

import com.lealone.db.Constants;
import com.lealone.db.RunMode;
import com.lealone.db.plugin.PluggableEngine;
import com.lealone.db.plugin.PluginManager;
import com.lealone.db.scheduler.InternalScheduler;
import com.lealone.storage.StorageMap;

public interface TransactionEngine extends PluggableEngine {

    public static TransactionEngine getDefaultTransactionEngine() {
        return PluginManager.getPlugin(TransactionEngine.class,
                Constants.DEFAULT_TRANSACTION_ENGINE_NAME);
    }

    default Transaction beginTransaction() {
        return beginTransaction(RunMode.CLIENT_SERVER);
    }

    default Transaction beginTransaction(int isolationLevel) {
        return beginTransaction(RunMode.CLIENT_SERVER, isolationLevel);
    }

    default Transaction beginTransaction(RunMode runMode) {
        return beginTransaction(runMode, Transaction.IL_READ_COMMITTED);
    }

    default Transaction beginTransaction(RunMode runMode, int isolationLevel) {
        return beginTransaction(runMode, isolationLevel, null);
    }

    Transaction beginTransaction(RunMode runMode, int isolationLevel, InternalScheduler scheduler);

    boolean supportsMVCC();

    void checkpoint();

    /**
     * 设置执行checkpoint前的回调。checkpoint会保存各个StorageMap并且截断redo log，
     * 在此之前必须先执行完所有还没执行的异步操作，比如非唯一索引的批量索引操作，
     * 否则会丢失索引记录。
     * 回调返回还没执行完的异步操作所属事务的最小事务id，checkpoint不能把
     * lastTransactionId设置得比它还大，否则这些操作的redo log会被提前截断。
     */
    default void setBeforeCheckpointHandler(LongSupplier handler) {
    }

    default long runBeforeCheckpointHandler() {
        return Long.MAX_VALUE;
    }

    default void recover(StorageMap<?, ?> map, List<StorageMap<?, ?>> indexMaps) {
    }

    default Runnable getFsyncService() {
        return null;
    }

    default boolean containsRepeatableReadTransactions() {
        return false;
    }

    default List<? extends Transaction> currentTransactions() {
        return null;
    }

    default void addGcTask(GcTask gcTask) {
    }

    default void removeGcTask(GcTask gcTask) {
    }

    interface GcTask {
        void gc(TransactionEngine te);
    }
}
