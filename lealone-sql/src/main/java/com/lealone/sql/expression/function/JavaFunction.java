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

    public JavaFunction(FunctionAlias functionAlias, Expression[] args) {
        this.functionAlias = functionAlias;
        this.candidates = null;
        this.javaMethod = functionAlias.findJavaMethod(args);
        this.args = args;
        this.untypedArguments = getUntypedArguments(args);
        checkArgumentTypes();
    }

    /**
     * 名字对应多个重载函数时，按实参的静态类型选一个。
     * 实参类型还没确定时(比如实参是NULL)等到optimize阶段再消解。
     */
    public JavaFunction(ArrayList<FunctionAlias> candidates, Expression[] args) {
        this.candidates = candidates;
        this.args = args;
        this.untypedArguments = getUntypedArguments(args);
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
        FunctionAlias best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (int i = 0; i < candidates.size(); i++) {
            FunctionAlias candidate = candidates.get(i);
            int score = getMatchScore(candidate, argumentTypeIds);
            if (score == SqlFunction.MATCH_NONE) {
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
                ambiguous = false;
            } else if (score == bestScore) {
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
    }

    // 只有一个候选函数时也要检查实参类型能不能转，类型不兼容就当函数不存在(和PostgreSQL一致)；
    // CREATE ALIAS建的Java函数保持原来的宽松转换，不做检查
    private void checkArgumentTypes() {
        if (!(functionAlias instanceof SqlFunction)) {
            return;
        }
        if (((SqlFunction) functionAlias).getMatchScore(getArgumentTypeIds()) == SqlFunction.MATCH_NONE) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, getSignatureText());
        }
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
        return getJavaMethod().getValue(session, args, false);
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
        appendArgs(sql);
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
        Value v = getJavaMethod().getValue(session, args, true);
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
