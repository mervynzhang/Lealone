/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.schema.CompositeType;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.ValueExpression;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * PL/pgSQL复合类型变量作为表达式，保留CompositeType列元数据供(c).field使用。
 */
public class PlpgSqlCompositeVariableExpression extends Expression {

    private final PlpgSqlVariable variable;

    public PlpgSqlCompositeVariableExpression(PlpgSqlVariable variable) {
        this.variable = variable;
    }

    @Override
    public Value getValue(ServerSession session) {
        return variable.getValue();
    }

    @Override
    public int getType() {
        return Value.ARRAY;
    }

    @Override
    public Expression optimize(ServerSession session) {
        return this;
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        CompositeType type = variable.getCompositeType();
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
        return variable.getName();
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append(variable.getName());
    }

    @Override
    public int getCost() {
        return 1;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        // 委托给普通常量表达式，避免通用visitor把visitExpression再打回accept
        return ValueExpression.get(variable.getValue()).accept(visitor);
    }
}
