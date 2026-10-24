/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.db.Constants;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.IExpression;

/**
 * 用一条SQL查询语句作为函数体的用户自定义函数，对应PostgreSQL风格的CREATE FUNCTION。
 * 函数体在创建时已把参数名替换成'?'，运行时按出现顺序绑定参数值，
 * 取结果集第一行第一列作为返回值，没有行时返回NULL。
 *
 * 参数支持PostgreSQL的IN/OUT/INOUT模式：
 * OUT/INOUT参数组成函数返回的行，只有一个这样的参数时函数返回一个标量值，
 * 有多个时函数返回一个只含一行的结果集(可以在FROM子句里当成表使用)；
 * 存在OUT/INOUT参数时可以省略RETURNS子句。
 */
public class SqlFunction extends FunctionAlias {

    /** 普通入参 */
    public static final int PARAM_IN = 0;
    /** 只作为返回列的出参，调用时不需要传值 */
    public static final int PARAM_OUT = 1;
    /** 既是入参也是返回列 */
    public static final int PARAM_INOUT = 2;

    // JavaMethod的构造器要求一个Method对象，
    // 这里只是借用它计算默认字段，getValue等方法都已被覆盖
    private static final Method DUMMY_METHOD = getDummyMethod();

    private String[] parameterNames; // 全部参数(按声明顺序)
    private String[] parameterTypes; // 参数的SQL类型文本
    private int[] parameterTypeIds; // 对应Value的类型
    private int[] parameterComponentTypes; // 数组参数的元素类型，-1表示不是数组
    private int[] parameterModes; // PARAM_IN/PARAM_OUT/PARAM_INOUT
    private int[] inputIndexes; // 入参在参数列表中的下标
    private int[] outputIndexes; // 返回列对应的参数下标
    private int inputParameterCount;

    private String returnTypeSql;
    private int returnType;
    private boolean hasDeclaredReturnType;

    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;

    private JavaMethod sqlJavaMethod;

    public SqlFunction(Schema schema, int id, String name) {
        super(schema, id, name);
    }

    private static Method getDummyMethod() {
        try {
            return SqlFunction.class.getDeclaredMethod("dummyMethod");
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    private static void dummyMethod() {
    }

    public void setParameters(String[] parameterNames, String[] parameterTypes, int[] parameterTypeIds,
            int[] parameterComponentTypes, int[] parameterModes) {
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.parameterTypeIds = parameterTypeIds;
        this.parameterComponentTypes = parameterComponentTypes;
        this.parameterModes = parameterModes;

        int inputCount = 0;
        int outputCount = 0;
        for (int mode : parameterModes) {
            if (mode != PARAM_OUT) {
                inputCount++;
            }
            if (mode != PARAM_IN) {
                outputCount++;
            }
        }
        inputIndexes = new int[inputCount];
        outputIndexes = new int[outputCount];
        int i = 0;
        int o = 0;
        for (int p = 0; p < parameterModes.length; p++) {
            if (parameterModes[p] != PARAM_OUT) {
                inputIndexes[i++] = p;
            }
            if (parameterModes[p] != PARAM_IN) {
                outputIndexes[o++] = p;
            }
        }
        inputParameterCount = inputCount;
    }

    /**
     * 设置返回类型。当有OUT/INOUT参数时 returnTypeSql 可以为null，由OUT参数推导。
     */
    public void setReturnType(String returnTypeSql, int returnType) {
        this.hasDeclaredReturnType = returnTypeSql != null;
        if (hasDeclaredReturnType) {
            this.returnTypeSql = returnTypeSql;
            this.returnType = returnType;
        }
        if (outputIndexes.length == 1) {
            int index = outputIndexes[0];
            int outType = parameterTypeIds[index];
            if (hasDeclaredReturnType && returnType != outType) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1,
                        "RETURNS type does not match OUT parameter: " + parameterNames[index]);
            }
            this.returnType = outType;
            this.returnTypeSql = parameterTypes[index];
        } else if (outputIndexes.length > 1) {
            if (hasDeclaredReturnType) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "RETURNS can not be used with multiple OUT parameters");
            }
            // 多个OUT参数组成返回行，用只含一行/多列的结果集表示
            this.returnType = Value.RESULT_SET;
            this.returnTypeSql = null;
        } else if (!hasDeclaredReturnType) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1,
                    "RETURNS is required when there is no OUT parameter");
        }
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

    @Override
    public JavaMethod[] getJavaMethods() {
        return new JavaMethod[] { getSqlJavaMethod() };
    }

    @Override
    public JavaMethod findJavaMethod(IExpression[] args) {
        if (args.length != inputParameterCount) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1,
                    getName() + " (parameter count: " + args.length + ")");
        }
        return getSqlJavaMethod();
    }

    private synchronized JavaMethod getSqlJavaMethod() {
        if (sqlJavaMethod == null) {
            sqlJavaMethod = new SqlJavaMethod();
        }
        return sqlJavaMethod;
    }

    @Override
    public String getCreateSQL() {
        StatementBuilder buff = new StatementBuilder("CREATE FUNCTION ");
        buff.append(getSQL()).append('(');
        for (int i = 0; i < parameterNames.length; i++) {
            buff.appendExceptFirst(", ");
            if (parameterModes[i] == PARAM_OUT) {
                buff.append("OUT ");
            } else if (parameterModes[i] == PARAM_INOUT) {
                buff.append("INOUT ");
            }
            buff.append(database.quoteIdentifier(parameterNames[i])).append(' ');
            buff.append(parameterTypes[i]);
        }
        buff.append(')');
        if (outputIndexes.length == 0) {
            buff.append(" RETURNS ").append(returnTypeSql);
        }
        buff.append(isDeterministic() ? " IMMUTABLE" : " VOLATILE");
        buff.append(" AS ").append(StringUtils.quoteStringSQL(body));
        return buff.toString();
    }

    @Override
    public String getDropSQL() {
        return "DROP FUNCTION IF EXISTS " + getSQL();
    }

    @Override
    public void invalidate() {
        sqlJavaMethod = null;
        super.invalidate();
    }

    private Value executeBody(ServerSession session, IExpression[] args, boolean columnList) {
        if (columnList && returnType == Value.RESULT_SET) {
            // 只需要返回列的定义，不需要真正执行函数体
            return createEmptyResultSet();
        }
        Value[] values = new Value[parameterNames.length];
        Arrays.fill(values, ValueNull.INSTANCE);
        for (int i = 0; i < inputParameterCount; i++) {
            values[inputIndexes[i]] = args[i].getValue(session);
        }
        Connection conn = session.createNestedConnection(false);
        try {
            // 函数体里的表名按调用方当前的schema解析
            String schemaName = session.getCurrentSchemaName();
            if (!Constants.SCHEMA_MAIN.equals(schemaName)) {
                conn.setSchema(schemaName);
            }
            PreparedStatement prep = conn.prepareStatement(bodySql);
            try {
                for (int i = 0; i < bodyParameterIndexes.length; i++) {
                    prep.setObject(i + 1, values[bodyParameterIndexes[i]]);
                }
                ResultSet rs = prep.executeQuery();
                try {
                    if (returnType == Value.RESULT_SET) {
                        return createRecord(session, rs);
                    }
                    if (!rs.next()) {
                        return ValueNull.INSTANCE;
                    }
                    Value v = DataType.convertToValue(session, rs.getObject(1), returnType);
                    return v.convertTo(returnType);
                } finally {
                    rs.close();
                }
            } finally {
                prep.close();
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    // 多个OUT参数时，把函数体返回的第一行包装成一个结果集
    private Value createRecord(ServerSession session, ResultSet rs) throws SQLException {
        SimpleResultSet simple = createResultSet();
        if (rs.next()) {
            Object[] row = new Object[outputIndexes.length];
            for (int i = 0; i < outputIndexes.length; i++) {
                int type = parameterTypeIds[outputIndexes[i]];
                Value v = DataType.convertToValue(session, rs.getObject(i + 1), type);
                row[i] = v.convertTo(type).getObject();
            }
            simple.addRow(row);
        }
        return ValueResultSet.get(simple);
    }

    private Value createEmptyResultSet() {
        return ValueResultSet.get(createResultSet());
    }

    private SimpleResultSet createResultSet() {
        SimpleResultSet simple = new SimpleResultSet();
        for (int index : outputIndexes) {
            DataType dataType = DataType.getDataType(parameterTypeIds[index]);
            simple.addColumn(parameterNames[index], dataType.sqlType, (int) dataType.defaultPrecision,
                    dataType.defaultScale);
        }
        return simple;
    }

    private final class SqlJavaMethod extends JavaMethod {

        SqlJavaMethod() {
            super(DUMMY_METHOD, 0);
        }

        @Override
        public int getParameterCount() {
            return inputParameterCount;
        }

        @Override
        public int getDataType() {
            return returnType;
        }

        @Override
        public boolean hasConnectionParam() {
            return false;
        }

        @Override
        public Class<?>[] getColumnClasses() {
            Class<?>[] classes = new Class<?>[inputParameterCount];
            Arrays.fill(classes, Value.class);
            return classes;
        }

        @Override
        public Value getValue(ServerSession session, IExpression[] args, boolean columnList) {
            return executeBody(session, args, columnList);
        }
    }
}
