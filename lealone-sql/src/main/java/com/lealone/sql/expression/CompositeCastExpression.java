/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.schema.CompositeType;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * CAST(expr AS comp) / expr::comp，把ValueArray按复合类型列定义转换。
 */
public class CompositeCastExpression extends Expression {

    private final CompositeType type;
    private final Expression expression;

    private CompositeCastExpression(CompositeType type, Expression expression) {
        this.type = type;
        this.expression = expression;
    }

    public static CompositeCastExpression create(CompositeType type, Expression expression) {
        return new CompositeCastExpression(type, expression);
    }

    public CompositeType getCompositeType() {
        return type;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public Value getValue(ServerSession session) {
        return type.convert(expression.getValue(session));
    }

    @Override
    public int getType() {
        return Value.ARRAY;
    }

    @Override
    public Expression optimize(ServerSession session) {
        Expression optimized = expression.optimize(session);
        if (optimized == expression)
            return this;
        return new CompositeCastExpression(type, optimized);
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        Column[] columns = type.getColumns();
        ExpressionColumn[] expressions = new ExpressionColumn[columns.length];
        for (int i = 0; i < columns.length; i++) {
            expressions[i] = new ExpressionColumn(session.getDatabase(), columns[i]);
        }
        return expressions;
    }

    @Override
    public int getScale() {
        return 0;
    }

    @Override
    public long getPrecision() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getDisplaySize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String getColumnName() {
        return type.getName();
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append("CAST(");
        expression.getSQL(sql);
        sql.append(" AS ");
        sql.append(type.getName());
        sql.append(')');
    }

    @Override
    public int getCost() {
        return expression.getCost() + 1;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitCompositeCast(this);
    }
}
