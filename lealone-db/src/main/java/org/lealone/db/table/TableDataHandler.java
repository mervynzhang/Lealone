/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db.table;

import java.io.File;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.TempFileDeleter;
import org.lealone.db.DataHandler;
import org.lealone.db.Database;
import org.lealone.storage.Storage;
import org.lealone.storage.StorageBuilder;
import org.lealone.storage.StorageEngine;
import org.lealone.storage.fs.FileStorage;
import org.lealone.storage.lob.LobStorage;

public class TableDataHandler implements DataHandler {

    private final Database db;
    private final LobStorage lobStorage;

    public TableDataHandler(StandardTable table, String name) {
        db = table.getDatabase();
        String storagePath = db.getStoragePath() + File.separator + name + File.separator + "lob";
        StorageEngine storageEngine = table.getStorageEngine();
        StorageBuilder storageBuilder = db.getStorageBuilder(storageEngine, storagePath);
        Storage storage = storageBuilder.openStorage();
        lobStorage = storageEngine.getLobStorage(this, storage);
        db.getTransactionEngine().addGcTask(lobStorage);
    }

    @Override
    public String getDatabasePath() {
        return db.getDatabasePath();
    }

    @Override
    public FileStorage openFile(String name, String mode, boolean mustExist) {
        return db.openFile(name, mode, mustExist);
    }

    @Override
    public TempFileDeleter getTempFileDeleter() {
        return db.getTempFileDeleter();
    }

    @Override
    public void checkPowerOff() throws DbException {
        db.checkPowerOff();
    }

    @Override
    public void checkWritingAllowed() throws DbException {
        db.checkWritingAllowed();
    }

    @Override
    public int getMaxLengthInplaceLob() {
        return db.getMaxLengthInplaceLob();
    }

    @Override
    public String getLobCompressionAlgorithm(int type) {
        return db.getLobCompressionAlgorithm(type);
    }

    @Override
    public LobStorage getLobStorage() {
        return lobStorage;
    }

    @Override
    public boolean isTableLobStorage() {
        return true;
    }
}
