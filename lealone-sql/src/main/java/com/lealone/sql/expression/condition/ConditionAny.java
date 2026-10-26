/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.condition;

import java.util.ArrayList;

import com.lealone.common.util.StatementBuilder;
import com.lealone.db.Database;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionList;
import com.lealone.sql.expression.ValueExpression;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * A quantified comparison, as in {@code expr = ANY(array)} or {@code expr <> ALL(array)}.
 * <p>
 * The right side may be an array value, or a PostgreSQL style array literal such as
 * <code>'{a,b,c}'</code>. Only a string that starts with '{' is parsed into elements; a plain
 * string constant (even one that contains commas) is a single element. The old behavior of
 * unconditionally splitting a comma separated string silently turned {@code 'Doe, John'} into
 * {@code 'Doe'} and {@code 'John'}, so the value itself never matched (third party review H1).
 * A string that starts with '{' but is not a well formed array literal is rejected with
 * {@link com.lealone.db.api.ErrorCode#MALFORMED_ARRAY_LITERAL_1} instead of falling back to a
 * single element.
 * <p>
 * The PostgreSQL compatibility plugin no longer relies on comma separated strings: it rewrites
 * {@code n.nspname = ANY(current_schemas(true))} into {@code ANY(SELECT ...)}.
 * <p>
 * NULL is handled with SQL three-valued logic: ANY is TRUE as soon as one element matches,
 * otherwise UNKNOWN if any element is NULL, otherwise FALSE. ALL is FALSE as soon as one element
 * does not match, otherwise UNKNOWN if any element is NULL, otherwise TRUE. An empty array gives
 * FALSE for ANY and TRUE for ALL (even if the left side is NULL).
 */
public class ConditionAny extends Condition {

    private final Database database;
    private Expression left;
    private Expression right;
    // true: ALL, false: ANY (or the equivalent SOME)
    private final boolean all;
    private final int compareType;
    // 右侧是常量时只预处理一次，避免每行重新解析
    private Value[] cachedValues;
    private boolean valuesCached;

    public ConditionAny(Database database, Expression left, Expression right, boolean all,
            int compareType) {
        this.database = database;
        this.left = left;
        this.right = right;
        this.all = all;
        this.compareType = compareType;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    public boolean isAll() {
        return all;
    }

    public int getCompareType() {
        return compareType;
    }

    @Override
    public Value getValue(ServerSession session) {
        Value l = left.getValue(session);
        Value[] values = valuesCached ? cachedValues : toValueList(right.getValue(session));
        if (values == null) {
            // 右侧整体是 NULL，无法判定
            return ValueNull.INSTANCE;
        }
        if (values.length == 0) {
            // 空数组: ANY 恒为 FALSE，ALL 恒为 TRUE
            return ValueBoolean.get(all);
        }
        if (l == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        // 行值(row value)的相等比较要走三值逻辑，跟 ConditionIn 保持一致
        boolean rowValue = l instanceof ValueArray;
        boolean hasNull = false;
        boolean result = all;
        for (Value v : values) {
            if (v == ValueNull.INSTANCE) {
                hasNull = true;
                continue;
            }
            boolean compared;
            if (rowValue && v instanceof ValueArray) {
                Value cmp = Comparison.compareRowValue(database, (ValueArray) l, (ValueArray) v,
                        compareType);
                if (cmp == ValueNull.INSTANCE) {
                    hasNull = true;
                    continue;
                }
                compared = cmp.getBoolean();
            } else {
                compared = Comparison.compareNotNull(database, l, v.convertTo(l.getType()),
                        compareType);
            }
            if (all) {
                if (!compared) {
                    result = false;
                    break;
                }
            } else if (compared) {
                result = true;
                break;
            }
        }
        if (hasNull && result == all) {
            // 没有能决定结果的元素，但存在 NULL 元素
            return ValueNull.INSTANCE;
        }
        return ValueBoolean.get(result);
    }

    /**
     * Convert the right side to a list of values.
     *
     * @return null if the right side is NULL
     */
    private static Value[] toValueList(Value v) {
        if (v == ValueNull.INSTANCE) {
            return null;
        }
        if (v instanceof ValueArray) {
            return ((ValueArray) v).getList();
        }
        int type = v.getType();
        if (type == Value.STRING || type == Value.STRING_IGNORECASE
                || type == Value.STRING_FIXED) {
            String s = v.getString();
            // 只有 PG 风格的花括号字面量才按数组解析；普通字符串即使含逗号也是单个元素。
            // 以前无条件 arraySplit(s, ',') 会把 'Doe, John' 静默拆成 'Doe' 和 'John'，
            // 真正的单值反而匹配不到（第三方评审 H1）。解析失败直接抛错，不退化成单值。
            String trimmed = s.trim();
            if (trimmed.startsWith("{")) {
                return ArrayLiteralParser.parse(trimmed);
            }
            return new Value[] { v };
        }
        // 其它标量当成只含一个元素的集合
        return new Value[] { v };
    }

    @Override
    public Expression optimize(ServerSession session) {
        left = left.optimize(session);
        right = right.optimize(session);
        if (left.isConstant() && right.isConstant()) {
            return ValueExpression.get(getValue(session));
        }
        if (right.isConstant() || isConstantList(right)) {
            Value r = right.getValue(session);
            if (r != ValueNull.INSTANCE) {
                cachedValues = toValueList(r);
                valuesCached = true;
                // 常量数组的 = ANY(...) 改写成 IN(...)：这样优化器能像 IN 一样用主键/索引，
                // 而不是因为不认识 ConditionAny 退回 tableScan（ConditionIn 会进一步
                // 优化成 ConditionInConstantSet）。<> ALL 等其它比较符保留原节点，
                // 语义完全一致。
                // 行值(左侧是数组)的 IN 打印还有独立的括号问题，这里保守地只改写普通标量比较
                if (!all && compareType == Comparison.EQUAL && left.getType() != Value.ARRAY) {
                    if (cachedValues.length == 0) {
                        return ValueExpression.get(ValueBoolean.get(false));
                    }
                    ArrayList<Expression> valueExpressions = new ArrayList<>(cachedValues.length);
                    for (Value v : cachedValues) {
                        valueExpressions.add(ValueExpression.get(v));
                    }
                    return new ConditionIn(database, left, valueExpressions).optimize(session);
                }
            }
        }
        return this;
    }

    /**
     * ExpressionList 的 isConstant() 恒为 false（行值构造器不能整体折叠），
     * 但所有元素都是常量时仍然可以在优化阶段改写成 IN。
     */
    private static boolean isConstantList(Expression e) {
        if (!(e instanceof ExpressionList)) {
            return false;
        }
        for (Expression element : ((ExpressionList) e).getList()) {
            if (element instanceof ExpressionList) {
                if (!isConstantList(element)) {
                    return false;
                }
            } else if (!element.isConstant()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.enBegin();
        // 行值/数组构造（ExpressionList）的括号由 ExpressionList 自己强制输出，
        // 这里不需要再套独立的 builder：enclosed 开关只影响“可选”括号，
        // 不会再把 "(ID, NAME) = ANY(((1, 'a')))" 打成 "ID, NAME = ANY((1, 'a'))"。
        left.getSQL(sql);
        sql.append(' ').append(Comparison.getCompareOperator(compareType));
        sql.append(all ? " ALL(" : " ANY(");
        right.getSQL(sql);
        sql.append(')');
        sql.enEnd();
    }

    @Override
    public int getCost() {
        return left.getCost() + right.getCost() + 1;
    }

    /**
     * NOT (expr op ANY(...)) is expr not-op ALL(...), and vice versa.
     */
    @Override
    public Expression getNotIfPossible(ServerSession session) {
        return new ConditionAny(database, left, right, !all, getNotCompareType());
    }

    private int getNotCompareType() {
        switch (compareType) {
        case Comparison.EQUAL:
            return Comparison.NOT_EQUAL;
        case Comparison.NOT_EQUAL:
            return Comparison.EQUAL;
        case Comparison.BIGGER_EQUAL:
            return Comparison.SMALLER;
        case Comparison.BIGGER:
            return Comparison.SMALLER_EQUAL;
        case Comparison.SMALLER_EQUAL:
            return Comparison.BIGGER;
        case Comparison.SMALLER:
            return Comparison.BIGGER_EQUAL;
        default:
            return compareType;
        }
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitConditionAny(this);
    }
}
