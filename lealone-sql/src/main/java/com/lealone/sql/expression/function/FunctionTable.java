/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression.function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

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
    private LocalResult cachedResult;
    private Value cachedValue;

    public FunctionTable(Schema schema, ServerSession session, Function function) {
        super(schema, 0, function.getName(), false, true);
        this.function = function;
        functionExpr = function.optimize(session);
        int type = function.getType();
        if (type == Value.RESULT_SET) {
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

    // 派生表支持列别名列表，如 (VALUES(1, 2), (3, 4)) t(a, b)，用它重命名函数的输出列
    public void setColumnAliases(String[] columnAliases) {
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
        return function.getSQL();
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
        if (v.getType() != Value.RESULT_SET) {
            return createScalarResult(session, v);
        }
        ValueResultSet value = (ValueResultSet) v;
        if (cachedResult != null && cachedValue == value) {
            cachedResult.reset();
            return cachedResult;
        }
        ResultSet rs = value.getResultSet();
        LocalResult result = LocalResult.read(session, Expression.getExpressionColumns(session, rs), rs,
                0);
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
        return createScalarResultSet(v);
    }

    private Value getFunctionValue(ServerSession session) {
        Value v = functionExpr.getValue(session);
        return v == null ? ValueNull.INSTANCE : v;
    }

    // 标量函数: 一行一列, 返回NULL时也是一行
    private LocalResult createScalarResult(ServerSession session, Value v) {
        IExpression[] expressions = createExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        result.addRow(new Value[] { v });
        result.done();
        return result;
    }

    private ResultSet createScalarResultSet(Value v) {
        Column column = getColumns()[0];
        SimpleResultSet simple = new SimpleResultSet();
        simple.setAutoClose(false);
        simple.addColumn(column.getName(), DataType.convertTypeToSQLType(column.getType()),
                MathUtils.convertLongToInt(column.getPrecision()), column.getScale());
        simple.addRow(v.getObject());
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
