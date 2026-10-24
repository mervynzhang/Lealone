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
import com.lealone.sql.LealoneSQLParser;
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
    private boolean atomicBody;
    private boolean ifNotExists;
    private final ArrayList<Column> parameters = new ArrayList<>();
    private final ArrayList<Integer> parameterModes = new ArrayList<>();

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

    // BEGIN ATOMIC ... END形式的函数体
    public void setAtomicBody(boolean atomicBody) {
        this.atomicBody = atomicBody;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void addParameter(Column parameter, int mode) {
        parameters.add(parameter);
        parameterModes.add(mode);
    }

    private int[] toIntArray(ArrayList<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++)
            array[i] = list.get(i);
        return array;
    }

    // 有OUT/INOUT参数的过程，OUT/INOUT的值来自过程体最后一条查询，所以过程体必须以查询语句结尾
    private void checkOutputParameters() {
        int modeCount = parameterModes.size();
        boolean hasOutput = false;
        for (int i = 0; i < modeCount; i++) {
            if (parameterModes.get(i) != SqlProcedure.PARAM_IN) {
                hasOutput = true;
                break;
            }
        }
        if (!hasOutput)
            return;
        if (!LealoneSQLParser.hasTrailingQueryStatement(session, body)) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "procedure " + procedureName + " has OUT/INOUT parameters, so the last statement of"
                            + " its body must be a query, and the columns of that query are the"
                            + " OUT/INOUT values");
        }
    }

    // 只有入参参与过程签名，OUT参数不影响重载
    private int[] getInputParameterTypeIds() {
        int count = 0;
        for (int mode : parameterModes) {
            if (mode != SqlProcedure.PARAM_OUT) {
                count++;
            }
        }
        int[] types = new int[count];
        int i = 0;
        for (int p = 0; p < parameterModes.size(); p++) {
            if (parameterModes.get(p) != SqlProcedure.PARAM_OUT) {
                types[i++] = parameters.get(p).getType();
            }
        }
        return types;
    }

    // 找出入参类型都相同的已有过程，也就是和这次CREATE冲突的那个重载
    private boolean findSameSignature(int[] inputTypeIds) {
        for (SqlProcedure p : schema.findProcedures(session, procedureName)) {
            if (p.matchesSignature(inputTypeIds)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        // 参数类型不同的重载可以共存，只有入参签名完全相同的才算重复
        int[] inputTypeIds = getInputParameterTypeIds();
        if (findSameSignature(inputTypeIds)) {
            if (ifNotExists)
                return 0;
            throw DbException.get(ErrorCode.PROCEDURE_ALREADY_EXISTS_1, procedureName);
        }
        checkOutputParameters();
        SqlProcedure procedure = new SqlProcedure(schema, getObjectId(), procedureName, body,
                atomicBody, parameters.toArray(new Column[0]), toIntArray(parameterModes));
        schema.add(session, procedure, lock);
        return 0;
    }
}
