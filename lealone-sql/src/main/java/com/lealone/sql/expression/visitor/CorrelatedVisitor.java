/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.visitor;

import java.util.List;

import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.condition.ConditionExists;
import com.lealone.sql.expression.condition.ConditionInSelect;
import com.lealone.sql.expression.subquery.SubQuery;
import com.lealone.sql.optimizer.TableFilter;

/**
 * 检查一个查询是否引用了外层查询的列，也就是判断它是不是关联子查询。
 * <p>
 * 判断依据: 如果某个列不属于当前查询自己的TableFilter，说明它引用的是外层查询的列。
 * 嵌套子查询不在这里展开，因为嵌套子查询对内层查询列的引用不算是内层查询的外层引用。
 */
public class CorrelatedVisitor extends BooleanExpressionVisitor {

    private final List<TableFilter> filters;

    public CorrelatedVisitor(List<TableFilter> filters) {
        this.filters = filters;
    }

    @Override
    public Boolean visitExpressionColumn(ExpressionColumn e) {
        TableFilter tableFilter = e.getTableFilter();
        // 无法确定列的来源时按关联列处理，宁可不用缓存也不能返回错误的结果
        return tableFilter != null && filters != null && filters.contains(tableFilter);
    }

    @Override
    public Boolean visitSubQuery(SubQuery e) {
        return true;
    }

    @Override
    public Boolean visitConditionExists(ConditionExists e) {
        return true;
    }

    @Override
    public Boolean visitConditionInSelect(ConditionInSelect e) {
        // 只看左边的表达式，不展开右边的子查询
        return e.getLeft().accept(this);
    }
}
