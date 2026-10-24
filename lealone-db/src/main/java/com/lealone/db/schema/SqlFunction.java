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
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.IExpression;

/**
 * 用一条SQL查询语句作为函数体的用户自定义函数，对应PostgreSQL风格的CREATE FUNCTION。
 * 函数体在创建时已把参数名替换成'?'，运行时按出现顺序绑定参数值，
 * 取结果集第一行第一列作为返回值，没有行时返回NULL。
 */
public class SqlFunction extends FunctionAlias {

    // JavaMethod的构造器要求一个Method对象，
    // 这里只是借用它计算默认字段，getValue等方法都已被覆盖
    private static final Method DUMMY_METHOD = getDummyMethod();

    private String[] parameterNames;
    private String[] parameterTypes;
    private String returnTypeSql;
    private int returnType;
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

    public void setParameters(String[] parameterNames, String[] parameterTypes) {
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
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

    @Override
    public JavaMethod[] getJavaMethods() {
        return new JavaMethod[] { getSqlJavaMethod() };
    }

    @Override
    public JavaMethod findJavaMethod(IExpression[] args) {
        if (args.length != parameterNames.length) {
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
            buff.append(database.quoteIdentifier(parameterNames[i])).append(' ');
            buff.append(parameterTypes[i]);
        }
        buff.append(") RETURNS ").append(returnTypeSql);
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

    private Value executeBody(ServerSession session, IExpression[] args) {
        int len = args.length;
        Value[] values = new Value[len];
        for (int i = 0; i < len; i++) {
            values[i] = args[i].getValue(session);
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

    private final class SqlJavaMethod extends JavaMethod {

        SqlJavaMethod() {
            super(DUMMY_METHOD, 0);
        }

        @Override
        public int getParameterCount() {
            return parameterNames.length;
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
            Class<?>[] classes = new Class<?>[parameterNames.length];
            Arrays.fill(classes, Value.class);
            return classes;
        }

        @Override
        public Value getValue(ServerSession session, IExpression[] args, boolean columnList) {
            return executeBody(session, args);
        }
    }
}
