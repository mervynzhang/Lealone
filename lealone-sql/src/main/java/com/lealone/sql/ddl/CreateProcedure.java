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
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.ParsedSQLStatement;
import com.lealone.sql.dml.CallProcedure;

/**
 * This class represents the statement
 * CREATE PROCEDURE
 * 
 * @author zhh
 */
public class CreateProcedure extends SchemaStatement {

    private String procedureName;
    private String body;
    private String language;
    private boolean atomicBody;
    // CREATE PROCEDURE里可选的属性，和函数的属性是同一套
    private FunctionAttributes attributes;
    private boolean ifNotExists;
    // CREATE OR REPLACE PROCEDURE: 删掉旧的同名同签名过程再重新创建
    private boolean orReplace;
    // 最后一个入参是VARIADIC数组参数, variadicComponentType是数组元素类型
    private boolean variadic;
    private int variadicComponentType = -1;
    private final ArrayList<Column> parameters = new ArrayList<>();
    private final ArrayList<Integer> parameterModes = new ArrayList<>();
    // 参数默认值: SQL文本和CREATE时算好的值, 没有默认值的位置是null
    private final ArrayList<String> parameterDefaults = new ArrayList<>();
    private final ArrayList<Value> parameterDefaultValues = new ArrayList<>();

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

    public void setLanguage(String language) {
        this.language = language;
    }

    private boolean isPlpgSql() {
        return "PLPGSQL".equalsIgnoreCase(language);
    }

    // BEGIN ATOMIC ... END形式的函数体
    public void setAtomicBody(boolean atomicBody) {
        this.atomicBody = atomicBody;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setOrReplace(boolean orReplace) {
        this.orReplace = orReplace;
    }

    public void setAttributes(FunctionAttributes attributes) {
        this.attributes = attributes;
    }

    public void setVariadicParameter(int componentType) {
        this.variadic = true;
        this.variadicComponentType = componentType;
    }

    public boolean hasVariadicParameter() {
        return variadic;
    }

    public void addParameter(Column parameter, int mode, String defaultSql, Value defaultValue) {
        parameters.add(parameter);
        parameterModes.add(mode);
        parameterDefaults.add(defaultSql);
        parameterDefaultValues.add(defaultValue);
    }

    private int[] toIntArray(ArrayList<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++)
            array[i] = list.get(i);
        return array;
    }

    // 过程体在建过程时就校验语法，不能留到CALL时才报错;
    // 过程体里可以执行DDL，引用的表可能是过程体自己刚建出来的，这种推迟到CALL时校验
    private void validateBody() {
        if (isPlpgSql() || session.getDatabase().isStarting())
            return;
        ServerSession nestedSession = session.createNestedSession();
        nestedSession.setCurrentSchemaName(session.getCurrentSchemaName());
        String[] statements = LealoneSQLParser.splitStatementsRaw(session, body);
        for (String sql : statements) {
            try {
                nestedSession.prepareStatement(sql);
            } catch (DbException e) {
                if (e.getErrorCode() == ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1)
                    continue;
                throw e;
            }
        }
    }

    // 有OUT/INOUT参数的过程，OUT/INOUT的值来自过程体最后一条查询，所以过程体必须以查询语句结尾;
    // plpgsql过程体例外, OUT/INOUT参数就是plpgsql里的变量, 由过程体自己赋值
    private void checkOutputParameters() {
        if (isPlpgSql())
            return;
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

    // SQL过程体里调用带OUT/INOUT参数的过程时输出值会被丢掉, PostgreSQL在CREATE阶段就报FEATURE_NOT_SUPPORTED
    private void checkCallWithOutputParameters() {
        if (isPlpgSql() || body == null)
            return;
        if (!LealoneSQLParser.hasCallStatement(session, body))
            return;
        for (String sql : LealoneSQLParser.splitStatements(session, body)) {
            ParsedSQLStatement statement = session.createParser().parse(sql);
            if (statement instanceof CallProcedure
                    && ((CallProcedure) statement).hasOutputParameters()) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "calling procedures with output arguments is not supported in SQL functions");
            }
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

    private int[] getInputPolymorphicTypes() {
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
                types[i++] = parameters.get(p).getPolymorphicType();
            }
        }
        return types;
    }

    // 找出入参类型都相同的已有过程，也就是和这次CREATE冲突的那个重载
    private SqlProcedure findSameSignature(int[] inputTypeIds) {
        int[] polyTypes = getInputPolymorphicTypes();
        for (SqlProcedure p : schema.findProcedures(session, procedureName)) {
            if (p.matchesSignature(inputTypeIds, polyTypes)) {
                return p;
            }
        }
        return null;
    }

    // 同名的函数已经有相同的入参签名时不能再建过程, PostgreSQL报"不能改变例程类型"
    private void checkRoutineKind(int[] inputTypeIds) {
        for (FunctionAlias f : schema.findFunctions(session, procedureName)) {
            if (f instanceof SqlFunction && ((SqlFunction) f).matchesSignature(inputTypeIds)) {
                throw DbException.get(ErrorCode.CANNOT_CHANGE_ROUTINE_KIND_1, procedureName);
            }
        }
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        // 参数类型不同的重载可以共存，只有入参签名完全相同的才算重复
        int[] inputTypeIds = getInputParameterTypeIds();
        // 和PostgreSQL一样, 用OR REPLACE时不允许把同签名的函数改成过程
        if (orReplace) {
            checkRoutineKind(inputTypeIds);
        }
        SqlProcedure old = findSameSignature(inputTypeIds);
        if (old != null) {
            if (ifNotExists)
                return 0;
            if (!orReplace) {
                throw DbException.get(ErrorCode.PROCEDURE_ALREADY_EXISTS_1, procedureName);
            }
            // 先删掉旧的过程再创建新的，同一个事务里完成
            schema.remove(session, old, lock);
        }
        // PL/pgSQL只有BEGIN ... END块, 没有SQL标准的BEGIN ATOMIC体
        if (isPlpgSql() && atomicBody) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "BEGIN ATOMIC procedure body with LANGUAGE " + language);
        }
        checkCallWithOutputParameters();
        checkOutputParameters();
        validateBody();
        String[] defaultSqls = new String[parameterDefaults.size()];
        Value[] defaultValues = new Value[parameterDefaultValues.size()];
        parameterDefaults.toArray(defaultSqls);
        parameterDefaultValues.toArray(defaultValues);
        SqlProcedure procedure = new SqlProcedure(schema, getObjectId(), procedureName, body,
                language, atomicBody, parameters.toArray(new Column[0]), toIntArray(parameterModes),
                defaultSqls, defaultValues, variadic, variadicComponentType);
        if (attributes != null) {
            attributes.applyTo(procedure);
        }
        // SECURITY DEFINER时属主默认是创建者，要持久化下来
        if (procedure.isSecurityDefiner() && procedure.getOwnerName() == null) {
            procedure.setOwnerName(session.getUser().getName());
        }
        schema.add(session, procedure, lock);
        return 0;
    }
}
