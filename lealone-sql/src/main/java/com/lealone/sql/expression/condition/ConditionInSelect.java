/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression.condition;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.db.Database;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.subquery.SubQueryResult;
import com.lealone.sql.expression.visitor.ExpressionVisitor;
import com.lealone.sql.expression.visitor.ExpressionVisitorFactory;
import com.lealone.sql.expression.visitor.NotFromResolverVisitor;
import com.lealone.sql.optimizer.IndexCondition;
import com.lealone.sql.optimizer.TableFilter;
import com.lealone.sql.query.Query;

/**
 * An 'in' condition with a subquery, as in WHERE ID IN(SELECT ...)
 */
public class ConditionInSelect extends Condition {

    private final Database database;
    private Expression left;
    private final Query query;
    private final boolean all;
    private final int compareType;
    private SubQueryResult rows;
    // 是否是关联子查询，关联子查询的结果不能重复使用
    private boolean correlated;

    public ConditionInSelect(Database database, Expression left, Query query, boolean all,
            int compareType) {
        this.database = database;
        this.left = left;
        this.query = query;
        this.all = all;
        this.compareType = compareType;
    }

    public Expression getLeft() {
        return left;
    }

    public Query getQuery() {
        return query;
    }

    @Override
    public Value getValue(ServerSession session) {
        query.setSession(session);
        if (correlated) {
            // 关联子查询的结果依赖于外层查询的当前行，每一行都必须重新执行子查询
            SubQueryResult r = new SubQueryResult(query, 0);
            try {
                return getValue(r, session);
            } finally {
                r.close();
            }
        }
        if (rows == null) {
            rows = new SubQueryResult(query, 0);
            session.addTemporaryResult(rows);
        } else {
            rows.reset();
        }
        return getValue(rows, session);
    }

    private Value getValue(SubQueryResult rows, ServerSession session) {
        Value l = left.getValue(session);
        if (rows.getRowCount() == 0) {
            return ValueBoolean.get(all);
        } else if (l == ValueNull.INSTANCE) {
            return l;
        }
        if (!session.getDatabase().getSettings().optimizeInSelect) {
            return getValueSlow(rows, l);
        }
        if (all || (compareType != Comparison.EQUAL && compareType != Comparison.EQUAL_NULL_SAFE)) {
            return getValueSlow(rows, l);
        }
        int dataType = rows.getColumnType(0);
        if (dataType == Value.NULL) {
            return ValueBoolean.get(false);
        }
        l = l.convertTo(dataType);
        if (rows.containsDistinct(new Value[] { l })) {
            return ValueBoolean.get(true);
        }
        if (rows.containsDistinct(new Value[] { ValueNull.INSTANCE })) {
            return ValueNull.INSTANCE;
        }
        return ValueBoolean.get(false);
    }

    private Value getValueSlow(SubQueryResult rows, Value l) {
        // this only returns the correct result if the result has at least one
        // row, and if l is not null
        boolean hasNull = false;
        boolean result = all;
        while (rows.next()) {
            boolean value;
            Value r = rows.currentRow()[0];
            if (r == ValueNull.INSTANCE) {
                value = false;
                hasNull = true;
            } else {
                value = Comparison.compareNotNull(database, l, r, compareType);
            }
            if (!value && all) {
                result = false;
                break;
            } else if (value && !all) {
                result = true;
                break;
            }
        }
        if (!result && hasNull) {
            return ValueNull.INSTANCE;
        }
        return ValueBoolean.get(result);
    }

    @Override
    public Expression optimize(ServerSession session) {
        left = left.optimize(session);
        query.prepare();
        if (query.getColumnCount() != 1) {
            throw DbException.get(ErrorCode.SUBQUERY_IS_NOT_SINGLE_COLUMN);
        }
        correlated = query.isCorrelated();
        // Can not optimize: the data may change
        return this;
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.enBegin();
        left.getSQL(sql);
        sql.append(' ');
        if (all) {
            sql.append(Comparison.getCompareOperator(compareType)).append(" ALL");
        } else {
            if (compareType != Comparison.EQUAL)
                sql.append(Comparison.getCompareOperator(compareType)).append(" SOME");
            else
                sql.append("IN");
        }
        sql.append("(\n").append(StringUtils.indent(query.getPlanSQL(), 4, false)).append(')');
        sql.enEnd();
    }

    @Override
    public int getCost() {
        return left.getCost() + query.getCostAsExpression();
    }

    @Override
    public void createIndexConditions(ServerSession session, TableFilter filter) {
        if (!session.getDatabase().getSettings().optimizeInList) {
            return;
        }
        if (!(left instanceof ExpressionColumn)) {
            return;
        }
        ExpressionColumn l = (ExpressionColumn) left;
        if (filter != l.getTableFilter()) {
            return;
        }
        NotFromResolverVisitor visitor = ExpressionVisitorFactory.getNotFromResolverVisitor(filter);
        if (!query.accept(visitor)) {
            return;
        }
        filter.addIndexCondition(IndexCondition.getInQuery(l, query));
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitConditionInSelect(this);
    }
}
