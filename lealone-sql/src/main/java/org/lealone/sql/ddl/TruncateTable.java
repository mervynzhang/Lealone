/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.auth.Right;
import org.lealone.db.schema.Schema;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Table;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * TRUNCATE TABLE
 * 
 * @author H2 Group
 * @author zhh
 */
public class TruncateTable extends SchemaStatement {

    private Table table;

    public TruncateTable(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.TRUNCATE_TABLE;
    }

    public void setTable(Table table) {
        this.table = table;
    }

    @Override
    public int update() {
        session.getUser().checkRight(table, Right.DELETE);
        if (!table.canTruncate()) {
            throw DbException.get(ErrorCode.CANNOT_TRUNCATE_1, table.getSQL());
        }
        if (!table.tryExclusiveLock(session))
            return -1;

        table.truncate(session);
        return 0;
    }
}
