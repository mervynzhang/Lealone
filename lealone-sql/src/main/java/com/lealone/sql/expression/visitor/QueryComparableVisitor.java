/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression.visitor;

import com.lealone.sql.expression.Rownum;
import com.lealone.sql.expression.SequenceValue;
import com.lealone.sql.expression.WindowFunction;
import com.lealone.sql.expression.function.Function;

public class QueryComparableVisitor extends BooleanExpressionVisitor {

    @Override
    public Boolean visitRownum(Rownum e) {
        return false;
    }

    // 窗口函数列不是可下推的查询条件(它的值要等整张表物化后才算出来)
    @Override
    public Boolean visitWindowFunction(WindowFunction e) {
        return false;
    }

    @Override
    public Boolean visitSequenceValue(SequenceValue e) {
        return false;
    }

    @Override
    public Boolean visitFunction(Function e) {
        return super.visitFunction(e) && e.isDeterministic();
    }
}
