/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.async.Future;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.executor.YieldableBase;
import com.lealone.sql.executor.YieldableCallProcedure;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.ValueExpression;

/**
 * This class represents the statement
 * CALL procedureName(...)
 * 
 * @author zhh
 */
public class CallProcedure extends Call {

    // 解析时先拿到同名的所有候选过程，prepare阶段再按实参类型消解
    private SqlProcedure procedure;
    private final ArrayList<SqlProcedure> candidates;
    private Expression[] args;
    // 每个实参的参数名，位置实参对应null
    private final String[] argNames;
    // OUT/INOUT参数对应的结果列
    private Expression[] outputExpressions;
    // 过程名+更新数的结果列
    private Expression[] resultExpressions;
    // 字符串字面量在PostgreSQL里没有类型，重载消解时按参数声明的类型解析
    private final boolean[] untypedArguments;
    private boolean resolved;
    // 过程体里是否有查询语句: -1还没判断, 0没有, 1有
    private int bodyHasQuery = -1;

    public CallProcedure(ServerSession session, ArrayList<SqlProcedure> candidates, Expression[] args,
            String[] argNames) {
        super(session);
        this.candidates = candidates;
        this.args = args;
        this.argNames = argNames;
        this.untypedArguments = getUntypedArguments(args);
    }

    private boolean hasNamedArguments() {
        if (argNames == null) {
            return false;
        }
        for (int i = 0; i < argNames.length; i++) {
            if (argNames[i] != null) {
                return true;
            }
        }
        return false;
    }

    // 命名实参按参数名重排、省略的入参用默认值补齐，重排后按入参顺序绑定
    private void orderNamedArguments(SqlProcedure p) {
        int[] positions = p.mapArgumentPositions(argNames);
        if (positions == null) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        int inputCount = p.getInputParameterCount();
        Expression[] ordered = new Expression[inputCount];
        for (int i = 0; i < args.length; i++) {
            ordered[positions[i]] = args[i];
        }
        for (int i = 0; i < inputCount; i++) {
            if (ordered[i] == null) {
                ordered[i] = ValueExpression.get(p.getDefaultInputValue(i));
            }
        }
        args = ordered;
    }

    private static boolean[] getUntypedArguments(Expression[] args) {
        boolean[] untyped = new boolean[args.length];
        for (int i = 0; i < args.length; i++) {
            untyped[i] = args[i] instanceof ValueExpression && args[i].getType() == Value.STRING;
        }
        return untyped;
    }

    // 实参的静态类型，没有类型的字面量当成UNKNOWN
    private int[] getArgumentTypeIds() {
        int[] argumentTypeIds = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            argumentTypeIds[i] = untypedArguments[i] ? Value.UNKNOWN : args[i].getType();
        }
        return argumentTypeIds;
    }

    // 按实参类型在候选过程里消解重载，规则和函数重载一致
    private void resolveOverload() {
        if (resolved) {
            return;
        }
        int[] argumentTypeIds = getArgumentTypeIds();
        boolean named = hasNamedArguments();
        SqlProcedure best = null;
        int bestScore = Integer.MAX_VALUE;
        int bestDefaultCount = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (int i = 0; i < candidates.size(); i++) {
            SqlProcedure candidate = candidates.get(i);
            int score;
            if (named) {
                score = candidate.getMatchScore(argumentTypeIds,
                        candidate.mapArgumentPositions(argNames));
            } else {
                score = candidate.getMatchScore(argumentTypeIds);
            }
            if (score == SqlFunction.MATCH_NONE) {
                continue;
            }
            // 匹配程度相同时优先选不需要用默认值补齐的那个重载
            int count = candidate.getInputParameterCount() - argumentTypeIds.length;
            int defaultCount = count > 0 ? count : 0;
            if (score < bestScore || (score == bestScore && defaultCount < bestDefaultCount)) {
                bestScore = score;
                bestDefaultCount = defaultCount;
                best = candidate;
                ambiguous = false;
            } else if (score == bestScore && defaultCount == bestDefaultCount) {
                ambiguous = true;
            }
        }
        // 多个重载的匹配程度相同时不猜，和PostgreSQL一样报歧义
        if (ambiguous) {
            throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, getSignatureText());
        }
        if (best == null) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        procedure = best;
        if (named) {
            orderNamedArguments(best);
        }
        initResultExpressions();
        resolved = true;
    }

    // 候选过程里有没有带OUT/INOUT参数、并且实参个数也对得上的重载
    public boolean hasOutputParameters() {
        for (int i = 0; i < candidates.size(); i++) {
            SqlProcedure candidate = candidates.get(i);
            if (!candidate.hasOutputParameters())
                continue;
            // 实参个数对不上的重载运行期本来也调不到
            if (candidate.getInputParameterCount() == args.length
                    || candidate.getParameterCount() == args.length)
                return true;
        }
        return false;
    }

    private void initResultExpressions() {
        ExpressionColumn c = new ExpressionColumn(session.getDatabase(),
                new Column(procedure.getName() + "()", Value.INT));
        resultExpressions = new Expression[] { c };
        int outputCount = procedure.getOutputParameterCount();
        if (outputCount > 0) {
            outputExpressions = new Expression[outputCount];
            for (int i = 0; i < outputCount; i++) {
                int index = procedure.getOutputParameterIndex(i);
                Column column = procedure.getParameter(index);
                outputExpressions[i] = new ExpressionColumn(session.getDatabase(), column);
            }
        } else {
            outputExpressions = resultExpressions;
        }
    }

    // 用于报错信息的调用签名，例如 P(INT, VARCHAR)
    private String getSignatureText() {
        StatementBuilder buff = new StatementBuilder(candidates.get(0).getName()).append('(');
        int[] argumentTypeIds = getArgumentTypeIds();
        for (int i = 0; i < argumentTypeIds.length; i++) {
            buff.appendExceptFirst(", ");
            buff.append(argumentTypeIds[i] == Value.UNKNOWN ? "UNKNOWN"
                    : DataType.getDataType(argumentTypeIds[i]).name);
        }
        return buff.append(')').toString();
    }

    public SqlProcedure getProcedure() {
        resolveOverload();
        return procedure;
    }

    public Expression[] getArgs() {
        return args;
    }

    public Expression[] getResultExpressions() {
        resolveOverload();
        return resultExpressions;
    }

    // CALL的结果列：有OUT/INOUT参数时是OUT/INOUT参数，否则是过程名+更新数
    public Expression[] getOutputExpressions() {
        resolveOverload();
        return outputExpressions;
    }

    @Override
    public int getType() {
        return SQLStatement.CALL;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    // 过程体里有查询语句时按查询语句执行，这样Statement.execute()也能通过getResultSet()拿到结果集；
    // 只有更新语句的过程仍然按更新语句执行，保持原来的更新计数语义。
    // 有OUT/INOUT参数的过程总是返回一行，所以也按查询语句执行
    @Override
    public boolean isQuery() {
        resolveOverload();
        if (procedure.hasOutputParameters())
            return true;
        if (bodyHasQuery < 0) {
            bodyHasQuery = LealoneSQLParser.hasQueryStatement(session, procedure.getBody()) ? 1 : 0;
        }
        return bodyHasQuery == 1;
    }

    @Override
    public PreparedSQLStatement prepare() {
        for (int i = 0, len = args.length; i < len; i++)
            args[i] = args[i].optimize(session);
        resolveOverload();
        return this;
    }

    @Override
    public Future<Result> getMetaData() {
        Expression[] expressions = getOutputExpressions();
        LocalResult result = new LocalResult(session, expressions, expressions.length);
        result.done();
        return Future.succeededFuture(result);
    }

    // 过程体里可能有查询语句，默认按查询语句的方式执行，内部会把最后一条查询的结果集返回给客户端
    @Override
    public int update() {
        return syncExecute(createYieldableUpdate(null));
    }

    @Override
    public Result query(int maxRows) {
        return syncExecute(createYieldableQuery(maxRows, false, null));
    }

    @Override
    public YieldableBase<Result> createYieldableQuery(int maxRows, boolean scrollable,
            AsyncResultHandler<Result> asyncHandler) {
        return new YieldableCallProcedure<>(this, maxRows, true, asyncHandler);
    }

    @Override
    public YieldableBase<Integer> createYieldableUpdate(AsyncResultHandler<Integer> asyncHandler) {
        return new YieldableCallProcedure<>(this, -1, false, asyncHandler);
    }
}
