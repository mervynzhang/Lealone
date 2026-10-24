/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP ALIAS
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropFunctionAlias extends SchemaStatement {

    private String aliasName;
    private boolean ifExists;
    // DROP FUNCTION name(types)里的参数类型，null表示没写
    private int[] argumentTypeIds;

    public DropFunctionAlias(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_ALIAS;
    }

    public void setAliasName(String name) {
        this.aliasName = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public void setArgumentTypeIds(int[] argumentTypeIds) {
        this.argumentTypeIds = argumentTypeIds;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.FUNCTION_ALIAS, session);
        if (lock == null)
            return -1;

        ArrayList<FunctionAlias> list = schema.findFunctions(session, aliasName);
        if (list.isEmpty()) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1, aliasName);
            }
            return 0;
        }
        FunctionAlias functionAlias = null;
        if (argumentTypeIds == null) {
            // 多个重载时没写参数类型就不知道删哪一个，和PostgreSQL一样报歧义
            if (list.size() > 1) {
                throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, aliasName);
            }
            functionAlias = list.get(0);
        } else {
            for (FunctionAlias f : list) {
                if (f instanceof SqlFunction) {
                    if (((SqlFunction) f).matchesSignature(argumentTypeIds)) {
                        functionAlias = f;
                        break;
                    }
                } else {
                    // CREATE ALIAS建的Java函数不看参数类型，和以前一样直接删
                    functionAlias = f;
                    break;
                }
            }
            if (functionAlias == null && !ifExists) {
                throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, aliasName);
            }
        }
        if (functionAlias != null) {
            schema.remove(session, functionAlias, lock);
        }
        return 0;
    }
}
