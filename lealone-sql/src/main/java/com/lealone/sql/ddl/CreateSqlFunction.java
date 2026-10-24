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
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * CREATE FUNCTION
 */
public class CreateSqlFunction extends SchemaStatement {

    private String functionName;
    private String[] parameterNames;
    private String[] parameterTypes;
    private int[] parameterTypeIds;
    private int[] parameterComponentTypes;
    private int[] parameterModes;
    private boolean variadic;
    private String returnTypeSql;
    private int returnType;
    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;
    private boolean deterministic;
    private boolean strict;
    private boolean ifNotExists;
    private boolean orReplace;

    public CreateSqlFunction(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_ALIAS;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public void setParameters(String[] parameterNames, String[] parameterTypes, int[] parameterTypeIds,
            int[] parameterComponentTypes, int[] parameterModes, boolean variadic) {
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.parameterTypeIds = parameterTypeIds;
        this.parameterComponentTypes = parameterComponentTypes;
        this.parameterModes = parameterModes;
        this.variadic = variadic;
    }

    public void setReturnType(String returnTypeSql, int returnType) {
        this.returnTypeSql = returnTypeSql;
        this.returnType = returnType;
    }

    /**
     * 设置函数体。
     *
     * @param body 原始的SQL函数体，用于持久化
     * @param bodySql 参数名已替换成'?'的函数体
     * @param bodyParameterIndexes bodySql中每个'?'对应的参数下标
     */
    public void setBody(String body, String bodySql, int[] bodyParameterIndexes) {
        this.body = body;
        this.bodySql = bodySql;
        this.bodyParameterIndexes = bodyParameterIndexes;
    }

    public void setDeterministic(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setOrReplace(boolean orReplace) {
        this.orReplace = orReplace;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.FUNCTION_ALIAS, session);
        if (lock == null)
            return -1;

        FunctionAlias old = schema.findFunction(session, functionName);
        if (old != null) {
            if (ifNotExists) {
                return 0;
            }
            if (!orReplace) {
                throw DbException.get(ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1, functionName);
            }
            // 先删掉旧的函数再创建新的，同一个事务里完成
            schema.remove(session, old, lock);
        }
        int id = getObjectId();
        SqlFunction function = new SqlFunction(schema, id, functionName);
        function.setParameters(parameterNames, parameterTypes, parameterTypeIds,
                parameterComponentTypes, parameterModes, variadic);
        function.setReturnType(returnTypeSql, returnType);
        function.setBody(body, bodySql, bodyParameterIndexes);
        function.setDeterministic(deterministic);
        function.setStrict(strict);
        schema.add(session, function, lock);
        return 0;
    }
}
