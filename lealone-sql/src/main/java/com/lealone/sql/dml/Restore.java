/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import java.io.File;
import java.io.IOException;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;
import com.lealone.storage.BackupRestore;

/**
 * This class represents the statement RESTORE
 */
public class Restore extends ManipulationStatement {

    private String fileName;
    private String targetDir;

    public Restore(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.BACKUP;
    }

    @Override
    public boolean needRecompile() {
        return false;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setTargetDir(String targetDir) {
        this.targetDir = targetDir;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        try {
            BackupRestore.restore(new File(fileName), new File(targetDir));
        } catch (IOException e) {
            throw DbException.convertIOException(e, fileName);
        }
        return 0;
    }
}
