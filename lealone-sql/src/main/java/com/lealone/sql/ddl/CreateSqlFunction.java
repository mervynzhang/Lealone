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
import com.lealone.db.value.Value;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.plpgsql.PlpgSqlFunction;
import com.lealone.sql.plpgsql.PlpgSqlParser;

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
    // RETURNS SETOF/RETURNS TABLE声明的结果列
    private boolean returnColumns;
    private boolean returnsTable;
    private String[] columnNames;
    private String[] columnTypeSqls;
    private int[] columnTypes;
    private long[] columnPrecisions;
    private int[] columnScales;
    private String language = "SQL";
    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;
    private boolean deterministic;
    private boolean strict;
    private String volatility;
    private FunctionAttributes attributes;
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

    public void setLanguage(String language) {
        this.language = language;
    }

    /**
     * 设置RETURNS SETOF声明的结果列。
     */
    public void setSetofReturnType(String columnTypeSql, int columnType, long columnPrecision,
            int columnScale) {
        returnColumns = true;
        columnTypeSqls = new String[] { columnTypeSql };
        columnTypes = new int[] { columnType };
        columnPrecisions = new long[] { columnPrecision };
        columnScales = new int[] { columnScale };
    }

    /**
     * 设置RETURNS TABLE声明的结果列。
     */
    public void setTableReturnType(String[] columnNames, String[] columnTypeSqls, int[] columnTypes,
            long[] columnPrecisions, int[] columnScales) {
        returnColumns = true;
        returnsTable = true;
        this.columnNames = columnNames;
        this.columnTypeSqls = columnTypeSqls;
        this.columnTypes = columnTypes;
        this.columnPrecisions = columnPrecisions;
        this.columnScales = columnScales;
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

    public void setVolatility(String volatility) {
        this.volatility = volatility;
    }

    public void setAttributes(FunctionAttributes attributes) {
        this.attributes = attributes;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setOrReplace(boolean orReplace) {
        this.orReplace = orReplace;
    }

    // OUT/INOUT参数组成返回行，只支持标量返回的语言不能有多个
    private int getOutputParameterCount() {
        int count = 0;
        for (int mode : parameterModes) {
            if (mode != SqlFunction.PARAM_IN) {
                count++;
            }
        }
        return count;
    }

    // PL/pgSQL解释器用CAST解析参数类型，数组类型(含VARIADIC)的写法还解析不了
    private boolean hasArrayParameter() {
        for (int typeId : parameterTypeIds) {
            if (typeId == Value.ARRAY) {
                return true;
            }
        }
        return false;
    }

    private void applyAttributes(SqlFunction function) {
        if (attributes == null) {
            return;
        }
        if (attributes.getSecurityDefiner() != null) {
            function.setSecurityDefiner(attributes.getSecurityDefiner().booleanValue());
        }
        if (attributes.getLeakproof() != null) {
            function.setLeakproof(attributes.getLeakproof().booleanValue());
        }
        if (attributes.getWindow() != null) {
            function.setWindow(attributes.getWindow().booleanValue());
        }
        function.setCost(attributes.getCost());
        function.setRows(attributes.getRows());
        function.setOwnerName(attributes.getOwner());
        for (String[] item : attributes.getConfigItems()) {
            function.addConfigItem(item[0], item[1]);
        }
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        boolean plpgsql = PlpgSqlFunction.LANGUAGE.equalsIgnoreCase(language);
        // 先校验语言和函数体，非法时不改动已有的函数
        if (plpgsql) {
            PlpgSqlParser.parse(body);
            // PL/pgSQL只支持标量值，结果集返回值和数组参数都没有实现
            if (returnColumns || getOutputParameterCount() > 1 || hasArrayParameter()) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "SETOF/TABLE, multiple OUT or array parameters in PL/pgSQL function");
            }
        } else if (!"SQL".equalsIgnoreCase(language)) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "LANGUAGE " + language);
        }
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
        SqlFunction function;
        if (plpgsql) {
            function = new PlpgSqlFunction(schema, id, functionName);
        } else {
            function = new SqlFunction(schema, id, functionName);
        }
        function.setLanguage(language);
        function.setParameters(parameterNames, parameterTypes, parameterTypeIds,
                parameterComponentTypes, parameterModes, variadic);
        // setof/table的结果列要先设置，setReturnType里要检查它和OUT/INOUT参数是否冲突
        if (returnColumns) {
            function.setReturnColumns(returnsTable, columnNames, columnTypeSqls, columnTypes,
                    columnPrecisions, columnScales);
        }
        function.setReturnType(returnTypeSql, returnType);
        function.setBody(body, bodySql, bodyParameterIndexes);
        function.setDeterministic(deterministic);
        // 易变性要原样保留IMMUTABLE/STABLE/VOLATILE，保证SCRIPT回放不丢属性
        if (volatility != null) {
            function.setVolatility(volatility);
        } else {
            function.setVolatility(deterministic ? SqlFunction.IMMUTABLE : SqlFunction.VOLATILE);
        }
        function.setStrict(strict);
        applyAttributes(function);
        schema.add(session, function, lock);
        return 0;
    }
}
