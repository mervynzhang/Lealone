/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression.function;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.Constants;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.ValueExpression;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * This class wraps a user-defined function.
 * 
 * @author H2 Group
 * @author zhh
 */
public class JavaFunction extends Function {

    // 只有单候选时直接用它，同名重载时用candidates消解
    private FunctionAlias functionAlias;
    private final ArrayList<FunctionAlias> candidates;
    private FunctionAlias.JavaMethod javaMethod;
    private final boolean[] untypedArguments;
    // 每个实参的参数名，位置实参对应null
    private final String[] argNames;
    // 命名实参按参数名重排、并用默认值补齐后的实参，没有命名实参时是null
    private Expression[] orderedArgs;

    public JavaFunction(FunctionAlias functionAlias, Expression[] args, String[] argNames) {
        this.functionAlias = functionAlias;
        this.candidates = null;
        this.args = args;
        this.argNames = argNames;
        this.untypedArguments = getUntypedArguments(args);
        if (hasNamedArguments() && !(functionAlias instanceof SqlFunction)) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        this.javaMethod = functionAlias.findJavaMethod(args);
        checkArgumentTypes();
        if (hasNamedArguments()) {
            orderedArgs = buildOrderedArgs((SqlFunction) functionAlias);
        }
    }

    /**
     * 名字对应多个重载函数时，按实参的静态类型选一个。
     * 实参类型还没确定时(比如实参是NULL)等到optimize阶段再消解。
     */
    public JavaFunction(ArrayList<FunctionAlias> candidates, Expression[] args, String[] argNames) {
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

    // 命名实参要按参数名重排, 省略的入参用默认值补齐, 这样执行时还是按位置绑定
    private Expression[] buildOrderedArgs(SqlFunction function) {
        int[] positions = function.mapArgumentPositions(argNames);
        if (positions == null) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        int inputCount = function.getInputParameterCount();
        Expression[] ordered = new Expression[inputCount];
        for (int i = 0; i < args.length; i++) {
            ordered[positions[i]] = args[i];
        }
        for (int i = 0; i < inputCount; i++) {
            if (ordered[i] == null) {
                ordered[i] = ValueExpression.get(function.getDefaultInputValue(i));
            }
        }
        return ordered;
    }

    // 实际执行时用的实参: 命名实参已经重排并补齐默认值
    private Expression[] getExecutionArgs() {
        resolveOverload();
        return orderedArgs != null ? orderedArgs : args;
    }

    // 字符串字面量在PostgreSQL里是没有类型的，可以按参数声明的类型解析，
    // 这里记录下来，实参optimize成常量之后也还能按“没有类型”参与重载消解
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
            argumentTypeIds[i] = untypedArguments[i] ? Value.UNKNOWN : getType(args[i]);
        }
        return argumentTypeIds;
    }

    // 列表达式在mapColumns之前还拿不到类型，这时先当成UNKNOWN，等optimize之后再消解
    private static int getType(Expression expr) {
        if (expr instanceof ExpressionColumn && ((ExpressionColumn) expr).getColumn() == null) {
            return Value.UNKNOWN;
        }
        return expr.getType();
    }

    // 按实参类型在候选函数里消解重载，匹配程度最好的那个胜出，一样好就算歧义
    private void resolveOverload() {
        if (javaMethod != null || candidates == null) {
            return;
        }
        int[] argumentTypeIds = getArgumentTypeIds();
        boolean named = hasNamedArguments();
        FunctionAlias best = null;
        int bestScore = Integer.MAX_VALUE;
        int bestDefaultCount = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (int i = 0; i < candidates.size(); i++) {
            FunctionAlias candidate = candidates.get(i);
            int score;
            int defaultCount;
            if (named) {
                // 命名实参只支持带参数名的SqlFunction
                if (!(candidate instanceof SqlFunction)) {
                    continue;
                }
                SqlFunction function = (SqlFunction) candidate;
                int[] positions = function.mapArgumentPositions(argNames);
                score = function.getMatchScore(argumentTypeIds, positions);
                defaultCount = function.getInputParameterCount() - argumentTypeIds.length;
            } else {
                score = getMatchScore(candidate, argumentTypeIds);
                defaultCount = getDefaultCount(candidate, argumentTypeIds.length);
            }
            if (score == SqlFunction.MATCH_NONE) {
                continue;
            }
            // 匹配程度相同时优先选不需要用默认值补齐的那个重载
            if (defaultCount < 0) {
                defaultCount = 0;
            }
            if (score < bestScore || (score == bestScore && defaultCount < bestDefaultCount)) {
                bestScore = score;
                bestDefaultCount = defaultCount;
                best = candidate;
                ambiguous = false;
            } else if (score == bestScore && defaultCount == bestDefaultCount) {
                ambiguous = true;
            }
        }
        // PostgreSQL在匹配程度相同时报歧义错误，这里同样不猜
        if (ambiguous) {
            throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, getSignatureText());
        }
        if (best == null) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        functionAlias = best;
        javaMethod = best.findJavaMethod(args);
        if (named) {
            orderedArgs = buildOrderedArgs((SqlFunction) best);
        }
    }

    // 只有一个候选函数时也要检查实参类型能不能转，类型不兼容就当函数不存在(和PostgreSQL一致)；
    // CREATE ALIAS建的Java函数保持原来的宽松转换，不做检查
    private void checkArgumentTypes() {
        if (!(functionAlias instanceof SqlFunction)) {
            return;
        }
        SqlFunction function = (SqlFunction) functionAlias;
        int score;
        if (hasNamedArguments()) {
            int[] positions = function.mapArgumentPositions(argNames);
            score = function.getMatchScore(getArgumentTypeIds(), positions);
        } else {
            score = function.getMatchScore(getArgumentTypeIds());
        }
        if (score == SqlFunction.MATCH_NONE) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
    }

    // 用默认值补齐的入参个数, 越少说明这个重载越贴合实参个数
    private static int getDefaultCount(FunctionAlias alias, int argumentCount) {
        if (alias instanceof SqlFunction) {
            int count = ((SqlFunction) alias).getInputParameterCount() - argumentCount;
            return count > 0 ? count : 0;
        }
        return 0;
    }

    private static int getMatchScore(FunctionAlias alias, int[] argumentTypeIds) {
        if (alias instanceof SqlFunction) {
            return ((SqlFunction) alias).getMatchScore(argumentTypeIds);
        }
        // CREATE ALIAS建的Java函数不参与类型消解，参数个数对上就算匹配
        for (FunctionAlias.JavaMethod m : alias.getJavaMethods()) {
            int count = m.getParameterCount();
            if (count == argumentTypeIds.length
                    || (m.isVarArgs() && count <= argumentTypeIds.length + 1)) {
                return SqlFunction.MATCH_UNTYPED;
            }
        }
        return SqlFunction.MATCH_NONE;
    }

    // 用于报错信息的调用签名，例如 F(INT, VARCHAR)
    private String getSignatureText() {
        StatementBuilder buff = new StatementBuilder(getName()).append('(');
        int[] argumentTypeIds = getArgumentTypeIds();
        for (int i = 0; i < args.length; i++) {
            buff.appendExceptFirst(", ");
            buff.append(argumentTypeIds[i] == Value.UNKNOWN ? "UNKNOWN"
                    : DataType.getDataType(argumentTypeIds[i]).name);
        }
        return buff.append(')').toString();
    }

    private FunctionAlias.JavaMethod getJavaMethod() {
        resolveOverload();
        if (javaMethod == null) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
        return javaMethod;
    }

    public FunctionAlias getFunctionAlias() {
        resolveOverload();
        return functionAlias;
    }

    @Override
    public Value getValue(ServerSession session) {
        return getJavaMethod().getValue(session, getExecutionArgs(), false);
    }

    @Override
    public int getType() {
        return getJavaMethod().getDataType();
    }

    @Override
    public Expression optimize(ServerSession session) {
        boolean allConst = optimizeArgs(session);
        // 实参optimize之后类型才确定，这时候再消解重载或者检查类型
        if (candidates != null) {
            resolveOverload();
        } else {
            checkArgumentTypes();
        }
        if (allConst) {
            return ValueExpression.get(getValue(session));
        }
        return this;
    }

    @Override
    public int getScale() {
        return DataType.getDataType(getType()).defaultScale;
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
        FunctionAlias functionAlias = getFunctionAlias();
        if (!functionAlias.getSchema().getName().equals(Constants.SCHEMA_MAIN)) {
            sql.append(LealoneSQLParser.quoteIdentifier(functionAlias.getSchema().getName()))
                    .append('.');
        }
        sql.append(LealoneSQLParser.quoteIdentifier(functionAlias.getName())).append('(');
        // 命名实参要原样写回去, 否则重排之后位置变了
        sql.resetCount();
        for (int i = 0; i < args.length; i++) {
            sql.appendExceptFirst(", ");
            if (argNames != null && argNames[i] != null) {
                sql.append(LealoneSQLParser.quoteIdentifier(argNames[i])).append(" => ");
            }
            args[i].getSQL(sql);
        }
        sql.append(')');
    }

    @Override
    public String getName() {
        if (functionAlias != null) {
            return functionAlias.getName();
        }
        return candidates.get(0).getName();
    }

    @Override
    public ValueResultSet getValueForColumnList(ServerSession session, Expression[] args) {
        Value v = getJavaMethod().getValue(session, getExecutionArgs(), true);
        return v == ValueNull.INSTANCE ? null : (ValueResultSet) v;
    }

    @Override
    public int getCost() {
        int cost = getJavaMethod().hasConnectionParam() ? 25 : 5;
        for (Expression e : args) {
            cost += e.getCost();
        }
        return cost;
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        if (isRecordReturning()) {
            // record的列名/类型来自OUT参数，不能用ValueArray的C1/C2占位列名
            ValueResultSet rs = getValueForColumnList(session, getArgs());
            if (rs != null)
                return getExpressionColumns(session, rs.getResultSet());
        }
        switch (getType()) {
        case Value.RESULT_SET:
            ValueResultSet rs = getValueForColumnList(session, getArgs());
            return getExpressionColumns(session, rs.getResultSet());
        case Value.ARRAY:
            return getExpressionColumns(session, (ValueArray) getValue(session));
        }
        return super.getExpressionColumns(session);
    }

    @Override
    public int getFunctionType() {
        return -1;
    }

    @Override
    public boolean isDeterministic() {
        return getFunctionAlias().isDeterministic();
    }

    @Override
    public boolean isRecordReturning() {
        return functionAlias.isRecordReturning();
    }

    @Override
    public boolean isSetReturning() {
        return getFunctionAlias().isSetReturning();
    }

    @Override
    boolean isBufferResultSetToLocalTemp() {
        return getFunctionAlias().isBufferResultSetToLocalTemp();
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitJavaFunction(this);
    }
}
