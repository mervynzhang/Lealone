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
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP TYPE name
 */
public class DropCompositeType extends SchemaStatement {

    private String typeName;
    private boolean ifExists;

    public DropCompositeType(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_DOMAIN;
    }

    public void setTypeName(String name) {
        this.typeName = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.COMPOSITE_TYPE, session);
        if (lock == null)
            return -1;

        CompositeType type = schema.findCompositeType(session, typeName);
        if (type == null) {
            if (!ifExists)
                throw DbException.get(ErrorCode.USER_DATA_TYPE_NOT_FOUND_1, typeName);
        } else {
            schema.remove(session, type, lock);
        }
        return 0;
    }
}
