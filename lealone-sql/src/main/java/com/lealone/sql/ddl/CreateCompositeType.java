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
import com.lealone.db.schema.CompositeType;
import com.lealone.db.schema.Schema;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * CREATE TYPE name AS (a INT, b VARCHAR, ...)
 */
public class CreateCompositeType extends SchemaStatement {

    private String typeName;
    private Column[] columns;
    private boolean ifNotExists;

    public CreateCompositeType(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_DOMAIN;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.COMPOSITE_TYPE, session);
        if (lock == null)
            return -1;

        if (schema.findCompositeType(session, typeName) != null) {
            if (ifNotExists)
                return 0;
            throw DbException.get(ErrorCode.USER_DATA_TYPE_ALREADY_EXISTS_1, typeName);
        }
        if (schema.findUserDataType(session, typeName) != null) {
            throw DbException.get(ErrorCode.USER_DATA_TYPE_ALREADY_EXISTS_1, typeName);
        }
        int id = getObjectId();
        CompositeType type = new CompositeType(schema, id, typeName);
        type.setColumns(columns);
        schema.add(session, type, lock);
        return 0;
    }
}
