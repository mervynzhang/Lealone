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
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.expression.Expression;

/**
 * PostgreSQL的generate_series(start, stop[, step])。
 * 出现在FROM里时SQLParserBase会直接优化成RangeTable，这里的函数实现
 * 主要给目标列表里的SRF展开(ProjectSet算子)用。
 */
public class SeriesFunction extends BuiltInFunction {

    public static final int GENERATE_SERIES = 401;

    public static void init() {
        addFunctionNotDeterministic("GENERATE_SERIES", GENERATE_SERIES, VAR_ARGS,
                Value.RESULT_SET);
    }

    SeriesFunction(Database database, FunctionInfo info) {
        super(database, info);
    }

    @Override
    protected void checkParameterCount(int len) {
        if (len < 2 || len > 3) {
            throw DbException.get(ErrorCode.INVALID_PARAMETER_COUNT_2, getName(), "2..3");
        }
    }

    @Override
    public boolean isSetReturning() {
        return true;
    }

    @Override
    public Value getValue(ServerSession session) {
        return getSeries(session, args);
    }

    @Override
    public ValueResultSet getValueForColumnList(ServerSession session, Expression[] args) {
        // 列定义是固定的(一列X), 不用执行函数体, 实参可能是当前行的列
        return ValueResultSet.get(createResultSet(args));
    }

    private Value getSeries(ServerSession session, Expression[] args) {
        long start = getLong(session, args[0]);
        long stop = getLong(session, args[1]);
        long step = args.length > 2 ? getLong(session, args[2]) : 1;
        SimpleResultSet simple = createResultSet(args);
        if (step == 0) {
            throw DbException.get(ErrorCode.INVALID_VALUE_2, "step", "0");
        }
        if (step > 0) {
            for (long i = start; i <= stop; i += step) {
                simple.addRow(new Object[] { ValueLong.get(i).getObject() });
            }
        } else {
            for (long i = start; i >= stop; i += step) {
                simple.addRow(new Object[] { ValueLong.get(i).getObject() });
            }
        }
        return ValueResultSet.get(simple);
    }

    private static long getLong(ServerSession session, Expression e) {
        Value v = e.getValue(session);
        return v == ValueNull.INSTANCE ? 0 : v.getLong();
    }

    private static SimpleResultSet createResultSet(Expression[] args) {
        SimpleResultSet simple = new SimpleResultSet();
        simple.setAutoClose(false);
        DataType dataType = DataType.getDataType(Value.LONG);
        simple.addColumn("X", DataType.convertTypeToSQLType(Value.LONG),
                MathUtils.convertLongToInt(dataType.defaultPrecision), dataType.defaultScale);
        return simple;
    }
}
