/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.visitor;

import java.util.ArrayList;
import java.util.List;

import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.condition.ConditionExists;
import com.lealone.sql.expression.condition.ConditionInSelect;
import com.lealone.sql.expression.subquery.SubQuery;
import com.lealone.sql.optimizer.TableFilter;
import com.lealone.sql.query.Query;

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
        return visitNestedQuery(e.getQuery());
    }

    @Override
    public Boolean visitConditionExists(ConditionExists e) {
        return visitNestedQuery(e.getQuery());
    }

    @Override
    public Boolean visitConditionInSelect(ConditionInSelect e) {
        return e.getLeft().accept(this) && visitNestedQuery(e.getQuery());
    }

    /**
     * 展开嵌套子查询时把嵌套查询自己的TableFilter也算成"自己的列"：
     * 它向内层的引用不算关联，只有引用更外层查询的列才判为关联。
     * 之前这里直接返回true(当作非关联)，导致"关联只发生在最内层"的查询被漏判，
     * 中间层子查询的结果被跨外层行复用，从第二行起返回上一行的过期值。
     */
    private Boolean visitNestedQuery(Query query) {
        List<TableFilter> nestedFilters = query.getFilters();
        if (nestedFilters == null || nestedFilters.isEmpty())
            return query.accept(this);
        List<TableFilter> all = new ArrayList<>(filters != null ? filters : List.<TableFilter> of());
        all.addAll(nestedFilters);
        return query.accept(new CorrelatedVisitor(all));
    }
}
