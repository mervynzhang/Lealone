/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.ddl;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.auth.Right;
import com.lealone.db.schema.Schema;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Table;
import com.lealone.sql.SQLStatement;

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
        // TRUNCATE 直接清空底层存储(不进undo log), 没法回滚。在未提交的事务里执行它会让会话进入
        // 不一致状态: 实测 "TRUNCATE -> INSERT -> ROLLBACK" 之后, 后面的 INSERT 会被静默丢掉
        // (数据在, 再插入的行不生效, 重开库也一样)。与其悄悄丢数据, 不如明确拒绝。
        if (!session.isAutoCommit()) {
            throw DbException.get(ErrorCode.COMMIT_ROLLBACK_NOT_ALLOWED,
                    "TRUNCATE TABLE cannot run inside a transaction because it is not rollback-able;"
                            + " commit or rollback first, or use DELETE FROM instead");
        }
        if (!table.canTruncate()) {
            throw DbException.get(ErrorCode.CANNOT_TRUNCATE_1, table.getSQL());
        }
        if (!table.tryExclusiveLock(session))
            return -1;

        table.truncate(session);
        return 0;
    }
}
