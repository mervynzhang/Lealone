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
    // DROP PROCEDURE name(类型列表)里的参数类型和参数模式，null表示没写
    private int[] argumentTypeIds;
    private int[] argumentModes;

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

    public void setArgumentTypeIds(int[] argumentTypeIds) {
        this.argumentTypeIds = argumentTypeIds;
    }

    // 每个位置是参数模式，-1表示这一位没有写IN/OUT/INOUT
    public void setArgumentModes(int[] argumentModes) {
        this.argumentModes = argumentModes;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        ArrayList<SqlProcedure> list = schema.findProcedures(session, procedureName);
        if (list.isEmpty()) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.PROCEDURE_NOT_FOUND_1, procedureName);
            }
            return 0;
        }
        SqlProcedure procedure = null;
        if (argumentTypeIds == null) {
            // 多个重载时没写参数类型就不知道删哪一个，和PostgreSQL一样报歧义
            if (list.size() > 1) {
                throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, procedureName);
            }
            procedure = list.get(0);
        } else {
            // 带参数类型时按完整参数列表匹配；两个重载都能匹配上时同样报歧义
            for (SqlProcedure p : list) {
                if (p.matchesDropSignature(argumentTypeIds, argumentModes)) {
                    if (procedure != null) {
                        throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, procedureName);
                    }
                    procedure = p;
                }
            }
            if (procedure == null && !ifExists) {
                throw DbException.get(ErrorCode.PROCEDURE_NOT_FOUND_1, procedureName);
            }
        }
        if (procedure != null) {
            schema.remove(session, procedure, lock);
        }
        return 0;
    }
}
