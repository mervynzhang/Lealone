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
import com.lealone.db.result.Result;
import com.lealone.db.schema.CompositeType;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.plpgsql.PlpgSqlFunction;
import com.lealone.sql.plpgsql.PlpgSqlParser;
import com.lealone.sql.query.Query;

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
    // RETURNS RECORD由OUT/INOUT参数定义返回行
    private boolean returnsRecord;
    // RETURNS SETOF <表名>时原样保留表名用于SCRIPT回放
    private String setofTableName;
    // RETURNS comp / RETURNS SETOF comp
    private CompositeType compositeReturnType;
    private boolean compositeSetof;
    private String[] columnNames;
    private String[] columnTypeSqls;
    private int[] columnTypes;
    private long[] columnPrecisions;
    private int[] columnScales;
    private String language = "SQL";
    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;
    // BEGIN ATOMIC ... END函数体，每条语句一个bodySql
    private String[] atomicBodySqls;
    private int[][] atomicBodyParameterIndexes;
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
     * 设置RETURNS RECORD，返回行的形状由OUT/INOUT参数决定。
     */
    public void setRecordReturnType() {
        returnsRecord = true;
    }

    public void setCompositeReturnType(CompositeType type, boolean setof) {
        this.compositeReturnType = type;
        this.compositeSetof = setof;
    }

    /**
     * 设置RETURNS SETOF <表名>，结果列就是表的列。
     */
    public void setSetofTableReturnType(Table table) {
        Column[] columns = table.getColumns();
        int count = columns.length;
        String[] names = new String[count];
        String[] typeSqls = new String[count];
        int[] types = new int[count];
        long[] precisions = new long[count];
        int[] scales = new int[count];
        for (int i = 0; i < count; i++) {
            Column column = columns[i];
            names[i] = column.getName();
            int type = column.getType();
            types[i] = type;
            String typeSql = column.getOriginalSQL();
            if (typeSql == null)
                typeSql = DataType.getDataType(type).name;
            typeSqls[i] = typeSql;
            precisions[i] = column.getPrecision();
            scales[i] = column.getScale();
        }
        returnColumns = true;
        // getCreateSQL里用不带schema的表名，回放时按当前schema解析
        setofTableName = session.getDatabase().quoteIdentifier(table.getName());
        columnNames = names;
        columnTypeSqls = typeSqls;
        columnTypes = types;
        columnPrecisions = precisions;
        columnScales = scales;
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
    // BEGIN ATOMIC函数体: 每条语句一个bodySql，以及这条语句里每个'?'对应的参数下标
    public void setAtomicBody(String[] bodySqls, int[][] bodyParameterIndexes) {
        this.atomicBodySqls = bodySqls;
        this.atomicBodyParameterIndexes = bodyParameterIndexes;
    }

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

    // PL/pgSQL解释器用CAST解析参数类型，数组类型(含VARIADIC)的写法还解析不了
    private boolean hasArrayParameter() {
        for (int i = 0; i < parameterTypeIds.length; i++) {
            if (parameterTypeIds[i] == Value.ARRAY) {
                // 复合类型参数也是Value.ARRAY，但它是record而不是数组
                if (parameterTypes[i] == null
                        || schema.findCompositeType(session, parameterTypes[i]) == null)
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
        // SECURITY DEFINER时属主默认是创建者，要持久化下来，重启后才能继续以属主身份执行
        if (function.isSecurityDefiner() && function.getOwnerName() == null) {
            function.setOwnerName(session.getUser().getName());
        }
        for (String[] item : attributes.getConfigItems()) {
            function.addConfigItem(item[0], item[1]);
        }
    }

    private int getOutputParameterCount() {
        int count = 0;
        for (int mode : parameterModes) {
            if (mode != SqlFunction.PARAM_IN) {
                count++;
            }
        }
        return count;
    }

    // 只有一个OUT/INOUT参数时它就是标量返回值
    private int getSingleOutputParameterIndex() {
        for (int i = 0; i < parameterModes.length; i++) {
            if (parameterModes[i] != SqlFunction.PARAM_IN) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 建函数时对SQL函数体做最小校验：prepare一次(不执行)，把语法/列名错误提前报出来，
     * 和PostgreSQL的check_function_bodies一样。标量返回值额外校验列数和常量类型，
     * setof/table和多个OUT参数的结果列形状在调用时校验。
     */
    private void validateSqlBody() {
        // 数据库启动时回放sys(meta)记录, 这时FUNCTION_ALIAS先于TABLE_OR_VIEW创建(DbObjectType.createOrder),
        // 函数体引用的表还没建出来, 必须跳过校验, 否则重启会失败; 等价于PG的check_function_bodies=off。
        // 这个判断不能删!
        if (session.getDatabase().isStarting())
            return;
        int outputCount = getOutputParameterCount();
        int declaredType = Value.UNKNOWN;
        if (!returnColumns && outputCount <= 1) {
            if (outputCount == 1) {
                // 有RETURNS时以RETURNS为准，它和OUT参数类型不一致的情况由SqlFunction.setReturnType报错
                declaredType = returnTypeSql != null ? returnType
                        : parameterTypeIds[getSingleOutputParameterIndex()];
            } else if (returnTypeSql != null) {
                declaredType = returnType;
            }
        }
        ServerSession nestedSession = session.createNestedSession();
        nestedSession.setCurrentSchemaName(session.getCurrentSchemaName());
        PreparedSQLStatement stmt = nestedSession.prepareStatement(bodySql);
        // DML函数体必须带RETURNING才能当查询用；VOID可以忽略最后一条非查询语句
        if (!stmt.isQuery()) {
            if (declaredType == Value.NULL)
                return;
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "function body must be a query or a DML statement with RETURNING");
        }
        Result meta = stmt.getMetaData().get();
        if (meta == null)
            return;
        // 只有标量返回值才在建函数时校验结果列的个数和常量类型,
        // VOID不关心函数体返回什么
        if (declaredType == Value.UNKNOWN || declaredType == Value.NULL)
            return;
        int columnCount = meta.getVisibleColumnCount();
        if (columnCount != 1) {
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                    "function body must return one column, but returns " + columnCount);
        }
        checkConstantReturnValue(stmt, declaredType);
    }

    // 函数体返回常量时，提前检查能否转换成声明的返回类型(比如 'not an integer' 不能作为INT返回)
    private void checkConstantReturnValue(PreparedSQLStatement stmt, int declaredType) {
        if (!(stmt instanceof Query))
            return;
        ArrayList<Expression> expressions = ((Query) stmt).getExpressions();
        if (expressions == null || expressions.size() != 1)
            return;
        Expression expr = expressions.get(0).getNonAliasExpression();
        if (!expr.isConstant())
            return;
        Value v = expr.getValue(session);
        if (v == null || v.getType() == Value.NULL)
            return;
        try {
            v.convertTo(declaredType);
        } catch (DbException e) {
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1, e,
                    "function body returns " + v.getSQL() + ", can not convert to the declared type");
        }
    }

    // 只有入参参与函数签名，OUT参数不影响重载
    private int[] getInputParameterTypeIds() {
        int count = 0;
        for (int mode : parameterModes) {
            if (mode != SqlFunction.PARAM_OUT) {
                count++;
            }
        }
        int[] types = new int[count];
        int i = 0;
        for (int p = 0; p < parameterModes.length; p++) {
            if (parameterModes[p] != SqlFunction.PARAM_OUT) {
                types[i++] = parameterTypeIds[p];
            }
        }
        return types;
    }

    // 找出名字和参数类型都相同的函数，也就是会被这次CREATE替换掉的那个重载
    private FunctionAlias findSameSignature() {
        int[] types = getInputParameterTypeIds();
        for (FunctionAlias f : schema.findFunctions(session, functionName)) {
            if (f instanceof SqlFunction && ((SqlFunction) f).matchesSignature(types)) {
                return f;
            }
        }
        return null;
    }

    // 名字已经被Java别名占用时不能再建同名函数
    private boolean isNameUsedByAlias() {
        FunctionAlias f = schema.findFunction(session, functionName);
        return f != null && !(f instanceof SqlFunction);
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        boolean plpgsql = PlpgSqlFunction.LANGUAGE.equalsIgnoreCase(language);
        // 已经有同签名的函数或者名字被别名占用，并且加了IF NOT EXISTS时什么都不用做；
        // 参数类型不同的重载不受影响，仍然要创建
        if (!plpgsql && ifNotExists && (findSameSignature() != null || isNameUsedByAlias()))
            return 0;
        // 先校验语言和函数体，非法时不改动已有的函数
        if (returnsRecord && getOutputParameterCount() == 0) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "RETURNS RECORD requires OUT parameters");
        }
        if (compositeReturnType != null && getOutputParameterCount() > 0) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "RETURNS composite type can not be used with OUT parameters");
        }
        if (plpgsql) {
            PlpgSqlParser.parse(body);
            // PL/pgSQL的数组参数还没有实现
            if (hasArrayParameter()) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "array parameters in PL/pgSQL function");
            }
        } else if (!"SQL".equalsIgnoreCase(language)) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "LANGUAGE " + language);
        } else {
            validateSqlBody();
        }
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.FUNCTION_ALIAS, session);
        if (lock == null)
            return -1;

        // 只替换参数类型相同的重载，其它重载保持不变
        FunctionAlias old = findSameSignature();
        if (old == null && isNameUsedByAlias()) {
            // 名字被Java别名占用时保持原来的行为: 不加OR REPLACE就报已存在
            old = schema.findFunction(session, functionName);
        }
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
            if (setofTableName != null)
                function.setSetofTableName(setofTableName);
        }
        if (returnsRecord)
            function.setReturnsRecord(true);
        if (compositeReturnType != null) {
            function.setCompositeReturnType(compositeReturnType, compositeSetof);
        } else {
            function.setReturnType(returnTypeSql, returnType);
        }
        function.setBody(body, bodySql, bodyParameterIndexes);
        if (atomicBodySqls != null) {
            function.setAtomicBody(atomicBodySqls, atomicBodyParameterIndexes);
        }
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
