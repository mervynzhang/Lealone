/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * CREATE PROCEDURE
 * 
 * @author zhh
 */
public class CreateProcedure extends SchemaStatement {

    private String procedureName;
    private String body;
    private boolean ifNotExists;
    private final ArrayList<Column> parameters = new ArrayList<>();

    public CreateProcedure(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_PROCEDURE;
    }

    public void setProcedureName(String name) {
        this.procedureName = name;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void addParameter(Column parameter) {
        parameters.add(parameter);
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        if (schema.findProcedure(session, procedureName) != null) {
            if (ifNotExists)
                return 0;
            throw DbException.get(ErrorCode.PROCEDURE_ALREADY_EXISTS_1, procedureName);
        }
        SqlProcedure procedure = new SqlProcedure(schema, getObjectId(), procedureName, body,
                parameters.toArray(new Column[0]));
        schema.add(session, procedure, lock);
        return 0;
    }
}
