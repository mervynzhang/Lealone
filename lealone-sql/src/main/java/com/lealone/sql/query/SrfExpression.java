/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.function.Function;
import com.lealone.sql.expression.function.FunctionTable;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * 目标列表里的setof函数(如select unnest(array[1,2,3]))。
 * 展开后的每一行由QProjectSet算子填进来，这里只负责提供列定义和当前行的值，
 * 所以结果集的列类型是函数结果集的列类型，而不是RESULT_SET。
 */
class SrfExpression extends Expression {

    private final Function function;
    private final FunctionTable functionTable;
    private final Column column;
    private final String alias;
    private final String columnName;
    private Value value = ValueNull.INSTANCE;

    SrfExpression(Function function, FunctionTable functionTable, String alias, String columnName) {
        this.function = function;
        this.functionTable = functionTable;
        this.column = functionTable.getColumns()[0];
        this.alias = alias;
        this.columnName = columnName;
    }

    FunctionTable getFunctionTable() {
        return functionTable;
    }

    public Function getFunction() {
        return function;
    }

    // QProjectSet展开到某一行时把当前值放进来
    void setValue(Value value) {
        this.value = value == null ? ValueNull.INSTANCE : value;
    }

    @Override
    public Value getValue(ServerSession session) {
        return value;
    }

    @Override
    public int getType() {
        return column.getType();
    }

    @Override
    public long getPrecision() {
        return column.getPrecision();
    }

    @Override
    public int getScale() {
        return column.getScale();
    }

    @Override
    public int getDisplaySize() {
        return column.getDisplaySize();
    }

    @Override
    public String getAlias() {
        return alias;
    }

    @Override
    public String getColumnName() {
        return columnName;
    }

    @Override
    public Expression optimize(ServerSession session) {
        return this;
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        function.getSQL(sql);
    }

    @Override
    public int getCost() {
        return function.getCost();
    }

    // 交给被包装的setof函数处理: 默认的accept会回到visitExpression形成死循环
    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return function.accept(visitor);
    }
}
