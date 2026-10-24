/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.condition.Comparison;
import com.lealone.sql.expression.visitor.ExpressionVisitor;
import com.lealone.sql.optimizer.IndexCondition;
import com.lealone.sql.optimizer.TableFilter;

/**
 * An expression representing a constant value.
 */
public class ValueExpression extends Expression {

    /**
     * The expression represents ValueNull.INSTANCE.
     */
    private static final Object NULL = new ValueExpression(ValueNull.INSTANCE, -1, -1, -1);

    /**
     * This special expression represents the default value. It is used for
     * UPDATE statements of the form SET COLUMN = DEFAULT. The value is
     * ValueNull.INSTANCE, but should never be accessed.
     */
    private static final Object DEFAULT = new ValueExpression(ValueNull.INSTANCE, -1, -1, -1);

    private final Value value;

    // 常量折叠后仍然保留声明类型的精度和标度，-1表示使用值自身的
    private final long precision;
    private final int scale;
    private final int displaySize;

    private ValueExpression(Value value, long precision, int scale, int displaySize) {
        this.value = value;
        this.precision = precision;
        this.scale = scale;
        this.displaySize = displaySize;
    }

    /**
     * Get the NULL expression.
     *
     * @return the NULL expression
     */
    public static ValueExpression getNull() {
        return (ValueExpression) NULL;
    }

    /**
     * Get the DEFAULT expression.
     *
     * @return the DEFAULT expression
     */
    public static ValueExpression getDefault() {
        return (ValueExpression) DEFAULT;
    }

    /**
     * Create a new expression with the given value.
     *
     * @param value the value
     * @return the expression
     */
    public static ValueExpression get(Value value) {
        if (value == ValueNull.INSTANCE) {
            return getNull();
        }
        return new ValueExpression(value, -1, -1, -1);
    }

    /**
     * Create a new expression with the given value and the declared precision and scale.
     *
     * @param value the value
     * @param precision the declared precision
     * @param scale the declared scale
     * @param displaySize the declared display size
     * @return the expression
     */
    public static ValueExpression get(Value value, long precision, int scale, int displaySize) {
        if (value == ValueNull.INSTANCE) {
            return getNull();
        }
        return new ValueExpression(value, precision, scale, displaySize);
    }

    @Override
    public Value getValue(ServerSession session) {
        return value;
    }

    @Override
    public int getType() {
        return value.getType();
    }

    @Override
    public void createIndexConditions(ServerSession session, TableFilter filter) {
        if (value.getType() == Value.BOOLEAN && !value.getBoolean()) {
            filter.addIndexCondition(IndexCondition.get(Comparison.FALSE, null, this));
        }
    }

    @Override
    public Expression getNotIfPossible(ServerSession session) {
        return new Comparison(session, Comparison.EQUAL, this,
                ValueExpression.get(ValueBoolean.get(false)));
    }

    @Override
    public Expression optimize(ServerSession session) {
        return this;
    }

    @Override
    public boolean isConstant() {
        return true;
    }

    @Override
    public boolean isValueSet() {
        return true;
    }

    @Override
    public int getScale() {
        return scale >= 0 ? scale : value.getScale();
    }

    @Override
    public long getPrecision() {
        return precision >= 0 ? precision : value.getPrecision();
    }

    @Override
    public int getDisplaySize() {
        return displaySize >= 0 ? displaySize : value.getDisplaySize();
    }

    @Override
    public String getSQL() {
        if (this == DEFAULT) {
            return "DEFAULT";
        } else {
            return value.getSQL();
        }
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append(getSQL());
    }

    @Override
    public int getCost() {
        return 0;
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        if (getType() == Value.ARRAY) {
            return getExpressionColumns(session, (ValueArray) getValue(session));
        }
        return super.getExpressionColumns(session);
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitValueExpression(this);
    }
}
