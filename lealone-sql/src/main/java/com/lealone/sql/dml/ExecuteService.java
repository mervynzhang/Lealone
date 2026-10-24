/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import com.lealone.db.async.Future;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.service.Service;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueMap;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueString;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;

/**
 * This class represents the statement
 * EXECUTE SERVICE.
 */
public class ExecuteService extends ExecuteStatement {

    private final String serviceName;
    private final String methodName;
    private final Expression[] resultExpressions;

    public ExecuteService(ServerSession session, String serviceName, String methodName) {
        super(session);
        this.serviceName = serviceName;
        this.methodName = methodName;
        ExpressionColumn c = new ExpressionColumn(session.getDatabase(),
                new Column(serviceName + "." + methodName + "()", Value.STRING));
        resultExpressions = new Expression[] { c };
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public Future<Result> getMetaData() {
        LocalResult result = new LocalResult(session, resultExpressions, 1);
        result.done();
        return Future.succeededFuture(result);
    }

    @Override
    public int update() {
        execute();
        return 0;
    }

    @Override
    public Result query(int maxRows) {
        setCurrentRowNumber(1);
        Value v = execute();
        // 结果列的类型要跟实际返回值一致，否则数组之类的返回值在客户端取不到
        ExpressionColumn c = new ExpressionColumn(session.getDatabase(),
                new Column(serviceName + "." + methodName + "()", v.getType()));
        Value[] row = { v };
        LocalResult result = new LocalResult(session, new Expression[] { c }, 1);
        result.addRow(row);
        result.done();
        return result;
    }

    private Value execute() {
        int size = expressions.size();
        Value[] methodArgs = new Value[size];
        for (int i = 0; i < size; i++) {
            methodArgs[i] = expressions.get(i).getValue(session);
        }
        Object obj = Service.execute(session, serviceName, methodName, methodArgs);
        Value v = DataType.convertToValue(session, obj, ValueNull.UNKNOWN);
        // 结果列声明为STRING，Map结果按JSON返回
        if (v.getType() == Value.MAP) {
            return ValueString.get(((ValueMap) v).getJson());
        }
        return v;
    }
}
