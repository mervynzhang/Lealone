/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP PROCEDURE
 * 
 * @author zhh
 */
public class DropProcedure extends SchemaStatement {

    private String procedureName;
    private boolean ifExists;

    public DropProcedure(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_PROCEDURE;
    }

    public void setProcedureName(String name) {
        this.procedureName = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        SqlProcedure procedure = schema.findProcedure(session, procedureName);
        if (procedure == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.PROCEDURE_NOT_FOUND_1, procedureName);
            }
        } else {
            schema.remove(session, procedure, lock);
        }
        return 0;
    }
}
