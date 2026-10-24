/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression.function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.MathUtils;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.index.Index;
import com.lealone.db.index.IndexColumn;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.schema.Schema;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.table.TableType;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.IExpression;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;

/**
 * A table backed by a system or user-defined function that returns a result set.
 * 
 * @author H2 Group
 * @author zhh
 */
public class FunctionTable extends Table {

    private final Function function;
    private final Expression functionExpr;
    private final boolean recordReturning;
    private boolean withOrdinality;
    private String[] columnAliases;
    private LocalResult cachedResult;
    private Value cachedValue;

    public FunctionTable(Schema schema, ServerSession session, Function function) {
        super(schema, 0, function.getName(), false, true);
        this.function = function;
        functionExpr = function.optimize(session);
        recordReturning = function.isRecordReturning();
        int type = function.getType();
        if (type == Value.RESULT_SET || recordReturning) {
            ValueResultSet template = function.getValueForColumnList(session, function.getArgs());
            if (template == null) {
                throw DbException.get(ErrorCode.FUNCTION_MUST_RETURN_RESULT_SET_1,
                        function.getName());
            }
            setColumns(readColumns(template.getResultSet()));
        } else {
            // 标量函数也能出现在FROM里, 当成只有一行一列的表, 列名用函数名(和PostgreSQL一致)
            setColumns(new Column[] { createColumn(function.getName(), type) });
        }
    }

    // 从函数返回的结果集元数据里读出列定义
    private static Column[] readColumns(ResultSet rs) {
        try {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            Column[] cols = new Column[columnCount];
            for (int i = 0; i < columnCount; i++) {
                cols[i] = new Column(meta.getColumnName(i + 1),
                        DataType.getValueTypeFromResultSet(meta, i + 1), meta.getPrecision(i + 1),
                        meta.getScale(i + 1), meta.getColumnDisplaySize(i + 1));
            }
            return cols;
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    private static Column createColumn(String name, int type) {
        DataType dataType = DataType.getDataType(type);
        return new Column(name, type, dataType.defaultPrecision, dataType.defaultScale,
                dataType.defaultDisplaySize);
    }

    @Override
    public TableType getTableType() {
        return TableType.FUNCTION_TABLE;
    }

    /**
     * WITH ORDINALITY: 在函数返回的列后面追加一列从1开始的行号(bigint)。
     */
    public boolean isWithOrdinality() {
        return withOrdinality;
    }

    // 列别名列表要能出现在重新生成的SQL里(如CREATE VIEW)，否则列名会丢
    public String[] getColumnAliases() {
        return columnAliases;
    }

    public void setWithOrdinality() {
        if (withOrdinality) {
            return;
        }
        withOrdinality = true;
        Column[] oldColumns = getColumns();
        Column[] newColumns = new Column[oldColumns.length + 1];
        System.arraycopy(oldColumns, 0, newColumns, 0, oldColumns.length);
        newColumns[oldColumns.length] = createColumn("ORDINALITY", Value.LONG);
        setColumns(newColumns);
    }

    // 派生表支持列别名列表，如 (VALUES(1, 2), (3, 4)) t(a, b)，用它重命名函数的输出列
    public void setColumnAliases(String[] columnAliases) {
        this.columnAliases = columnAliases.clone();
        Column[] oldColumns = getColumns();
        if (columnAliases.length > oldColumns.length) {
            throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
        }
        Column[] newColumns = new Column[oldColumns.length];
        for (int i = 0; i < oldColumns.length; i++) {
            Column c = oldColumns[i];
            if (i < columnAliases.length) {
                c = new Column(columnAliases[i], c.getType(), c.getPrecision(), c.getScale(),
                        c.getDisplaySize());
            }
            newColumns[i] = c;
        }
        setColumns(newColumns);
    }

    @Override
    public Index getScanIndex(ServerSession session) {
        return new FunctionIndex(this, IndexColumn.wrap(columns));
    }

    @Override
    public long getMaxDataModificationId() {
        return database.getModificationDataId();
    }

    @Override
    public boolean isDeterministic() {
        return function.isDeterministic();
    }

    @Override
    public boolean canReference() {
        return false;
    }

    @Override
    public boolean canDrop() {
        throw DbException.getInternalError();
    }

    @Override
    public boolean canGetRowCount() {
        return false;
    }

    @Override
    public long getRowCount(ServerSession session) {
        return Long.MAX_VALUE;
    }

    @Override
    public long getRowCountApproximation() {
        return Long.MAX_VALUE;
    }

    @Override
    public String getCreateSQL() {
        return null;
    }

    @Override
    public String getDropSQL() {
        return null;
    }

    @Override
    public String getSQL() {
        String sql = function.getSQL();
        if (withOrdinality) {
            sql += " WITH ORDINALITY";
        }
        return sql;
    }

    /**
     * Read the result from the function. This method buffers the result in a
     * temporary file.
     *
     * @param session the session
     * @return the result
     */
    public Result getResult(ServerSession session) {
        Value v = getFunctionValue(session);
        if (recordReturning) {
            return createRecordResult(session, v);
        }
        if (v.getType() != Value.RESULT_SET) {
            return createScalarResult(session, v);
        }
        ValueResultSet value = (ValueResultSet) v;
        if (cachedResult != null && cachedValue == value) {
            cachedResult.reset();
            return cachedResult;
        }
        LocalResult result;
        if (withOrdinality) {
            result = readWithOrdinality(session, value.getResultSet());
        } else {
            ResultSet rs = value.getResultSet();
            result = LocalResult.read(session, Expression.getExpressionColumns(session, rs), rs, 0);
        }
        if (function.isDeterministic()) {
            cachedResult = result;
            cachedValue = value;
        }
        return result;
    }

    /**
     * Read the result set from the function. This method doesn't cache.
     *
     * @param session the session
     * @return the result set
     */
    public ResultSet getResultSet(ServerSession session) {
        Value v = getFunctionValue(session);
        if (v.getType() == Value.RESULT_SET) {
            return ((ValueResultSet) v).getResultSet();
        }
        if (recordReturning) {
            return createRecordResultSet(v);
        }
        return createScalarResultSet(v);
    }

    private Value getFunctionValue(ServerSession session) {
        Value v = functionExpr.getValue(session);
        return v == null ? ValueNull.INSTANCE : v;
    }

    // 多OUT函数: record是一行多列, 从ValueArray取值; 严格函数返回NULL时当成一行全NULL
    private LocalResult createRecordResult(ServerSession session, Value v) {
        int columnCount = getColumns().length - (withOrdinality ? 1 : 0);
        Value[] row;
        if (v.getType() == Value.ARRAY) {
            row = ((ValueArray) v).getList();
        } else {
            row = new Value[columnCount];
            Arrays.fill(row, ValueNull.INSTANCE);
        }
        IExpression[] expressions = createExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        result.addRow(appendOrdinality(row));
        result.done();
        return result;
    }

    // 函数返回结果集时逐行读出来, WITH ORDINALITY时追加行号列
    private LocalResult readWithOrdinality(ServerSession session, ResultSet rs) {
        Column[] cols = getColumns();
        int columnCount = cols.length - 1;
        IExpression[] expressions = createExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        try {
            for (int i = 0; rs.next(); i++) {
                Value[] row = new Value[cols.length];
                for (int j = 0; j < columnCount; j++) {
                    row[j] = DataType.readValue(session, rs, j + 1, cols[j].getType());
                }
                row[columnCount] = ValueLong.get(i + 1);
                result.addRow(row);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        result.done();
        return result;
    }

    private Value[] appendOrdinality(Value[] row) {
        if (!withOrdinality) {
            return row;
        }
        Value[] values = new Value[row.length + 1];
        System.arraycopy(row, 0, values, 0, row.length);
        values[row.length] = ValueLong.get(1);
        return values;
    }

    private ResultSet createRecordResultSet(Value v) {
        int columnCount = getColumns().length - (withOrdinality ? 1 : 0);
        Value[] row;
        if (v.getType() == Value.ARRAY) {
            row = ((ValueArray) v).getList();
        } else {
            row = new Value[columnCount];
            Arrays.fill(row, ValueNull.INSTANCE);
        }
        Value[] values = appendOrdinality(row);
        SimpleResultSet simple = createSimpleResultSet(getColumns());
        Object[] objects = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            objects[i] = values[i].getString();
        }
        simple.addRow(objects);
        return simple;
    }

    // 标量函数: 一行一列, 返回NULL时也是一行
    private LocalResult createScalarResult(ServerSession session, Value v) {
        IExpression[] expressions = createExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        result.addRow(appendOrdinality(new Value[] { v }));
        result.done();
        return result;
    }

    private ResultSet createScalarResultSet(Value v) {
        Value[] values = appendOrdinality(new Value[] { v });
        SimpleResultSet simple = createSimpleResultSet(getColumns());
        Object[] objects = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            objects[i] = values[i].getString();
        }
        simple.addRow(objects);
        return simple;
    }

    private static SimpleResultSet createSimpleResultSet(Column[] cols) {
        SimpleResultSet simple = new SimpleResultSet();
        simple.setAutoClose(false);
        for (int i = 0; i < cols.length; i++) {
            Column column = cols[i];
            simple.addColumn(column.getName(), DataType.convertTypeToSQLType(column.getType()),
                    MathUtils.convertLongToInt(column.getPrecision()), column.getScale());
        }
        return simple;
    }

    private IExpression[] createExpressions() {
        Column[] cols = getColumns();
        IExpression[] expressions = new IExpression[cols.length];
        for (int i = 0; i < cols.length; i++) {
            expressions[i] = new ExpressionColumn(database, cols[i]);
        }
        return expressions;
    }

    boolean isBufferResultSetToLocalTemp() {
        return function.isBufferResultSetToLocalTemp();
    }
}
