/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.function;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.MathUtils;
import com.lealone.db.Database;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ValueExpression;

/**
 * PostgreSQL的unnest(): 把一个或多个数组展开成多行。
 * 多个数组按最长的那个对齐，短的补NULL(和PostgreSQL一致)。
 */
public class UnnestFunction extends BuiltInFunction {

    public static final int UNNEST = 400;

    public static void init() {
        addFunctionNotDeterministic("UNNEST", UNNEST, VAR_ARGS, Value.RESULT_SET);
    }

    UnnestFunction(Database database, FunctionInfo info) {
        super(database, info);
    }

    @Override
    protected void checkParameterCount(int len) {
        if (len < 1) {
            throw DbException.get(ErrorCode.INVALID_PARAMETER_COUNT_2, getName(), ">0");
        }
    }

    @Override
    public boolean isSetReturning() {
        return true;
    }

    @Override
    public Value getValue(ServerSession session) {
        return getUnnest(session, args);
    }

    @Override
    public ValueResultSet getValueForColumnList(ServerSession session, Expression[] args) {
        // 实参可能引用当前行的列(解析期/准备期取不到值), 这时只按静态类型给列定义
        if (!isConstantArguments(args)) {
            SimpleResultSet simple = new SimpleResultSet();
            simple.setAutoClose(false);
            for (int i = 0; i < args.length; i++) {
                addColumn(simple, i, getStaticElementType(args[i]));
            }
            return ValueResultSet.get(simple);
        }
        return (ValueResultSet) getUnnest(session, args);
    }

    private static boolean isConstantArguments(Expression[] args) {
        for (Expression arg : args) {
            if (!(arg instanceof ValueExpression)) {
                return false;
            }
        }
        return true;
    }

    // 常量数组取第一个非NULL元素的类型; 不是常量时没法知道元素类型, 退化成字符串
    private static int getStaticElementType(Expression arg) {
        if (arg instanceof ValueExpression) {
            Value v = ((ValueExpression) arg).getValue(null);
            if (v.getType() == Value.ARRAY) {
                return getElementType(v);
            }
            if (v != ValueNull.INSTANCE) {
                return v.getType();
            }
        }
        return Value.STRING;
    }

    private Value getUnnest(ServerSession session, Expression[] args) {
        Value[] arrays = new Value[args.length];
        int rowCount = 0;
        for (int i = 0; i < args.length; i++) {
            Value v = args[i].getValue(session);
            if (v != ValueNull.INSTANCE && v.getType() != Value.ARRAY) {
                throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1, v.getSQL(),
                        "ARRAY");
            }
            arrays[i] = v;
            if (v != ValueNull.INSTANCE) {
                rowCount = Math.max(rowCount, ((ValueArray) v).getList().length);
            }
        }
        SimpleResultSet simple = new SimpleResultSet();
        simple.setAutoClose(false);
        for (int i = 0; i < arrays.length; i++) {
            addColumn(simple, i, getElementType(arrays[i]));
        }
        for (int row = 0; row < rowCount; row++) {
            Object[] objects = new Object[arrays.length];
            for (int i = 0; i < arrays.length; i++) {
                Value v = getElement(arrays[i], row);
                objects[i] = v == ValueNull.INSTANCE ? null : v.getObject();
            }
            simple.addRow(objects);
        }
        return ValueResultSet.get(simple);
    }

    private static void addColumn(SimpleResultSet simple, int index, int type) {
        DataType dataType = DataType.getDataType(type);
        simple.addColumn(getColumnName(index), DataType.convertTypeToSQLType(type),
                MathUtils.convertLongToInt(dataType.defaultPrecision), dataType.defaultScale);
    }

    // 列名用函数名(和PostgreSQL一致)，多个数组时加序号避免重名
    private static String getColumnName(int index) {
        return index == 0 ? "UNNEST" : "UNNEST" + (index + 1);
    }

    // 数组元素类型取第一个非NULL元素，空数组当成字符串
    private static int getElementType(Value array) {
        if (array == ValueNull.INSTANCE) {
            return Value.STRING;
        }
        Value[] list = ((ValueArray) array).getList();
        for (Value v : list) {
            if (v != ValueNull.INSTANCE) {
                return v.getType();
            }
        }
        return Value.STRING;
    }

    // 下标越界时补NULL
    private static Value getElement(Value array, int index) {
        if (array == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        Value[] list = ((ValueArray) array).getList();
        return index < list.length ? list[index] : ValueNull.INSTANCE;
    }
}
