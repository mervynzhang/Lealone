/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * A list of expressions, as in (ID, NAME).
 * The result of this expression is an array.
 */
public class ExpressionList extends Expression {

    private final Expression[] list;

    public ExpressionList(Expression[] list) {
        this.list = list;
    }

    public Expression[] getList() {
        return list;
    }

    @Override
    public Value getValue(ServerSession session) {
        Value[] v = new Value[list.length];
        for (int i = 0; i < list.length; i++) {
            v[i] = list[i].getValue(session);
        }
        return ValueArray.get(v);
    }

    @Override
    public int getType() {
        return Value.ARRAY;
    }

    @Override
    public Expression optimize(ServerSession session) {
        // 行值构造器不能折叠成常量，否则 (a, b) IS NULL 之类无法区分行值和普通的数组值
        for (int i = 0; i < list.length; i++) {
            list[i] = list[i].optimize(session);
        }
        return this;
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
    public void getSQL(StatementBuilder sql) {
        sql.enBegin();
        sql.resetCount();
        for (Expression e : list) {
            sql.appendExceptFirst(", ");
            e.getSQL(sql);
        }
        if (list.length == 1) {
            sql.append(',');
        }
        sql.enEnd();
    }

    @Override
    public int getCost() {
        int cost = 1;
        for (Expression e : list) {
            cost += e.getCost();
        }
        return cost;
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        ExpressionColumn[] expr = new ExpressionColumn[list.length];
        for (int i = 0; i < list.length; i++) {
            Expression e = list[i];
            Column col = new Column("C" + (i + 1), e.getType(), e.getPrecision(), e.getScale(),
                    e.getDisplaySize());
            expr[i] = new ExpressionColumn(session.getDatabase(), col);
        }
        return expr;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitExpressionList(this);
    }
}
