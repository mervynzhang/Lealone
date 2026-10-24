/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.Database;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * 复合值字段访问，例如 (f(1)).a。
 * f返回只含一行的结果集时，按列名取出该行的字段值。
 */
public class CompositeFieldExpression extends Expression {

    private final Database database;
    private final Expression base;
    private final String fieldName;
    private final int fieldIndex;
    private final int type;
    private final long precision;
    private final int scale;
    private final int displaySize;

    private CompositeFieldExpression(Database database, Expression base, String fieldName,
            int fieldIndex, int type, long precision, int scale, int displaySize) {
        this.database = database;
        this.base = base;
        this.fieldName = fieldName;
        this.fieldIndex = fieldIndex;
        this.type = type;
        this.precision = precision;
        this.scale = scale;
        this.displaySize = displaySize;
    }

    /**
     * 解析base的结果列，构造(expr).field表达式。
     */
    public static Expression create(ServerSession session, Database database, Expression base,
            String fieldName) {
        Expression[] columns = base.getExpressionColumns(session);
        if (columns == null) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "composite field access requires a row-returning function: " + base.getSQL());
        }
        for (int i = 0; i < columns.length; i++) {
            Expression columnExpression = columns[i];
            if (!(columnExpression instanceof ExpressionColumn))
                continue;
            ExpressionColumn expressionColumn = (ExpressionColumn) columnExpression;
            if (database.equalsIdentifiers(fieldName, expressionColumn.getColumnName())) {
                Column column = expressionColumn.getColumn();
                if (column == null)
                    continue;
                return new CompositeFieldExpression(database, base, fieldName, i, column.getType(),
                        column.getPrecision(), column.getScale(), column.getDisplaySize());
            }
        }
        throw DbException.get(ErrorCode.COLUMN_NOT_FOUND_1, fieldName);
    }

    public Expression getBase() {
        return base;
    }

    @Override
    public Value getValue(ServerSession session) {
        Value value = base.getValue(session);
        if (value == null || value == ValueNull.INSTANCE)
            return ValueNull.INSTANCE;
        if (value.getType() != Value.RESULT_SET) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "composite field access requires a row-returning function: " + base.getSQL());
        }
        ResultSet resultSet = ((ValueResultSet) value).getResultSet();
        try {
            if (!resultSet.next())
                return ValueNull.INSTANCE;
            Object object = resultSet.getObject(fieldIndex + 1);
            if (object == null)
                return ValueNull.INSTANCE;
            return DataType.convertToValue(session, object, type);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public Expression optimize(ServerSession session) {
        Expression optimizedBase = base.optimize(session);
        if (optimizedBase == base)
            return this;
        return new CompositeFieldExpression(database, optimizedBase, fieldName, fieldIndex, type,
                precision, scale, displaySize);
    }

    @Override
    public int getScale() {
        return scale;
    }

    @Override
    public long getPrecision() {
        return precision;
    }

    @Override
    public int getDisplaySize() {
        return displaySize;
    }

    @Override
    public String getColumnName() {
        return fieldName;
    }

    @Override
    public String getAlias() {
        return fieldName;
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append('(');
        base.getSQL(sql);
        sql.append(").");
        sql.append(LealoneSQLParser.quoteIdentifier(fieldName));
    }

    @Override
    public int getCost() {
        return base.getCost() + 1;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitCompositeField(this);
    }
}
