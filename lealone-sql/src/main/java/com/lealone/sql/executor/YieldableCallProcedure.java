/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.executor;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Constants;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.async.AsyncResult;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.async.AsyncTask;
import com.lealone.db.auth.User;
import com.lealone.db.command.CommandParameter;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.SessionStatus;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.PreparedSQLStatement.YieldableCommand;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.dml.CallProcedure;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.plpgsql.PlpgSqlAst;
import com.lealone.sql.plpgsql.PlpgSqlException;
import com.lealone.sql.plpgsql.PlpgSqlInterpreter;
import com.lealone.sql.plpgsql.PlpgSqlParser;
import com.lealone.sql.plpgsql.PlpgSqlResultInfo;

/**
 * 执行一个存储过程。过程体里的每条SQL语句都编译成一个子yieldable，
 * 由子yieldable的完成回调串成一条链，写法跟StatementList处理多条语句时完全一样。
 * 过程体在独立的嵌套会话里执行，嵌套会话的事务以当前会话的事务为父事务，
 * 所以过程体的所有语句最后一起提交，中途失败则整体回滚。
 * 
 * @author zhh
 */
public class YieldableCallProcedure<T> extends YieldableBase<T> {

    private final CallProcedure call;
    private final SqlProcedure procedure;
    private final int maxRows;
    private final boolean query; // 结果为Result还是更新数

    private ServerSession nestedSession;
    private String[] statements; // 过程体拆分后得到的SQL语句
    private PreparedSQLStatement.Yieldable<?> yieldable;
    private Value[] argValues;
    // PL/pgSQL过程体的执行结果(OUT/INOUT参数组成的一行ValueArray)
    private Value plpgSqlValue;
    private int[] inputArgIndexes; // 第i个入参(IN/INOUT)对应的实参下标
    private Result result;
    private int updateCount;
    private int index;

    public YieldableCallProcedure(CallProcedure call, int maxRows, boolean query,
            AsyncResultHandler<T> asyncHandler) {
        super(call, asyncHandler);
        this.call = call;
        this.procedure = call.getProcedure();
        this.maxRows = maxRows;
        this.query = query;
    }

    @Override
    protected void startInternal() {
        // 有授权记录时才检查EXECUTE权限，没有授权历史的对象保持"人人可调用"
        session.checkExecuteRight(procedure);
        evalArgs();
        // SECURITY DEFINER的过程以属主身份在嵌套会话里执行(和definer函数一致)，属主默认是创建者
        if (procedure.isSecurityDefiner()) {
            nestedSession = session.createNestedSession(getOwnerUser());
        } else {
            nestedSession = session.createNestedSession();
        }
        if (session.isAutoCommit())
            nestedSession.setAutoCommit(false);
        if (procedure.isPlpgSql()) {
            // PL/pgSQL过程体和plpgsql函数一样同步解释执行, 执行完再走完成回调
            plpgSqlValue = executePlpgSqlBody();
            onComplete(null);
            return;
        }
        statements = splitBody(procedure.getBody());
        createNextYieldable();
    }

    // SECURITY DEFINER过程的属主，属主默认是创建者
    private User getOwnerUser() {
        String owner = procedure.getOwnerName() != null ? procedure.getOwnerName()
                : session.getUser().getName();
        return session.getDatabase().getUser(session, owner);
    }

    // 在嵌套会话里解释执行PL/pgSQL过程体，返回OUT/INOUT参数组成的一行(没有OUT/INOUT时是NULL)
    private Value executePlpgSqlBody() {
        PlpgSqlAst.Block block = (PlpgSqlAst.Block) procedure.getCompiledBody();
        if (block == null) {
            block = PlpgSqlParser.parse(procedure.getBody());
            procedure.setCompiledBody(block);
        }
        String schemaName = session.getCurrentSchemaName();
        if (schemaName != null && !Constants.SCHEMA_MAIN.equals(schemaName))
            nestedSession.setCurrentSchemaName(schemaName);
        PlpgSqlInterpreter interpreter = new PlpgSqlInterpreter(nestedSession, block,
                createResultInfo());
        try {
            return interpreter.execute(getPlpgSqlParameterNames(), getPlpgSqlParameterTypes(),
                    getPlpgSqlParameterValues());
        } catch (PlpgSqlException e) {
            // 离开过程时把PL/pgSQL异常转换成带SQLSTATE的DbException
            throw DbException.convert(new SQLException(e.getMessage(), e.getSqlState(),
                    ErrorCode.GENERAL_ERROR_1));
        }
    }

    // 过程只有两种返回形态: 没有OUT/INOUT时什么都不返回, 有OUT/INOUT时返回一行
    private PlpgSqlResultInfo createResultInfo() {
        int count = procedure.getParameterCount();
        int[] parameterModes = new int[count];
        for (int i = 0; i < count; i++)
            parameterModes[i] = procedure.getParameterMode(i);
        int outputCount = procedure.getOutputParameterCount();
        int[] outputIndexes = new int[outputCount];
        for (int i = 0; i < outputCount; i++)
            outputIndexes[i] = procedure.getOutputParameterIndex(i);
        return new PlpgSqlResultInfo(procedure.getName(), Value.NULL, false, false, false, false,
                parameterModes, outputIndexes, null, null, null, null, true);
    }

    private String[] getPlpgSqlParameterNames() {
        int count = procedure.getParameterCount();
        String[] names = new String[count];
        for (int i = 0; i < count; i++)
            names[i] = procedure.getParameter(i).getName();
        return names;
    }

    private String[] getPlpgSqlParameterTypes() {
        int count = procedure.getParameterCount();
        String[] types = new String[count];
        for (int i = 0; i < count; i++) {
            Column column = procedure.getParameter(i);
            String typeSql = column.getOriginalSQL();
            types[i] = typeSql != null ? typeSql
                    : com.lealone.db.value.DataType.getDataType(column.getType()).name;
        }
        return types;
    }

    // PL/pgSQL按参数位置拿值，OUT参数的实参永远没有(调用时只能写NULL或者省略)
    private Value[] getPlpgSqlParameterValues() {
        int count = procedure.getParameterCount();
        Value[] values = new Value[count];
        Arrays.fill(values, ValueNull.INSTANCE);
        for (int i = 0; i < inputArgIndexes.length; i++) {
            values[procedure.getInputParameterIndex(i)] = argValues[inputArgIndexes[i]];
        }
        return values;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void executeInternal() {
        // yieldable不为null时过程体还没执行完，保持RUNNING状态避免调度器重复调度本类，
        // 过程体由子yieldable的完成回调继续驱动
        if (yieldable == null) {
            // 过程体执行失败时真正的错误已经记录在pendingException里，
            // 这里不能再去组装OUT/INOUT的结果行，否则会覆盖掉原始错误
            if (pendingException != null)
                return;
            // 有OUT/INOUT参数时，过程体最后一条查询的第一行就是OUT/INOUT的值
            if (procedure.hasOutputParameters())
                buildOutputResult();
            if (query) {
                // 过程体里没有查询语句时返回一行更新数，跟Call语句的行为保持一致
                if (result == null)
                    result = newLocalResult();
                setResult((T) result, result.getRowCount());
            } else {
                setResult((T) Integer.valueOf(updateCount), updateCount);
            }
        } else {
            session.setStatus(SessionStatus.STATEMENT_RUNNING);
        }
    }

    // 把过程体的OUT/INOUT值按参数声明的类型转换成一结果行
    private void buildOutputResult() {
        if (procedure.isPlpgSql()) {
            // plpgsql过程体把OUT/INOUT参数组装成一行ValueArray
            buildPlpgSqlOutputResult();
            return;
        }
        int count = procedure.getOutputParameterCount();
        if (result == null) {
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "procedure " + procedure.getName() + " has OUT/INOUT parameters, so the last"
                            + " statement of its body must be a query");
        }
        LocalResult output = new LocalResult(session, call.getOutputExpressions(), count);
        try {
            int columnCount = result.getVisibleColumnCount();
            if (columnCount < count) {
                throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                        "procedure " + procedure.getName() + " expects " + count
                                + " OUT/INOUT values, but the body returns " + columnCount
                                + " columns");
            }
            Value[] row = new Value[count];
            if (result.next()) {
                Value[] current = result.currentRow();
                for (int i = 0; i < count; i++) {
                    int index = procedure.getOutputParameterIndex(i);
                    Column column = procedure.getParameter(index);
                    Value v = current[i];
                    if (v == null)
                        v = ValueNull.INSTANCE;
                    row[i] = column.convert(v);
                }
            } else {
                // 过程体的查询没有返回行时OUT/INOUT的值都是NULL
                for (int i = 0; i < count; i++)
                    row[i] = ValueNull.INSTANCE;
            }
            output.addRow(row);
            output.done();
        } finally {
            result.close();
        }
        result = output;
    }

    // plpgsql过程体的OUT/INOUT值已经在ValueArray里，按参数类型转换后组成结果行
    private void buildPlpgSqlOutputResult() {
        int count = procedure.getOutputParameterCount();
        Value[] list = null;
        if (plpgSqlValue != null && plpgSqlValue.getType() == Value.ARRAY)
            list = ((ValueArray) plpgSqlValue).getList();
        Value[] row = new Value[count];
        for (int i = 0; i < count; i++) {
            Value v = list != null && i < list.length ? list[i] : ValueNull.INSTANCE;
            if (v == null)
                v = ValueNull.INSTANCE;
            Column column = procedure.getParameter(procedure.getOutputParameterIndex(i));
            row[i] = column.convert(v);
        }
        LocalResult output = new LocalResult(session, call.getOutputExpressions(), count);
        output.addRow(row);
        output.done();
        result = output;
    }

    // 一次只创建一个子yieldable，它结束后再通过回调创建下一个，避免递归过深
    private void createNextYieldable() {
        while (index < statements.length) {
            PreparedSQLStatement stmt = nestedSession.prepareStatement(statements[index++], -1);
            checkStatementType(stmt);
            bindParameters(stmt);
            if (stmt.isQuery())
                createYieldableQuery(stmt);
            else
                createYieldableUpdate(stmt);
            return;
        }
        onComplete(null);
    }

    private void createYieldableUpdate(PreparedSQLStatement stmt) {
        yieldable = stmt.createYieldableUpdate(new AsyncResultHandler<Integer>() {
            @Override
            public void handle(AsyncResult<Integer> ar) {
                if (ar.isSucceeded()) {
                    updateCount += ar.getResult();
                    onStatementComplete();
                } else {
                    onComplete(ar.getCause());
                }
            }
        });
        nestedSession.setYieldableCommand(new YieldableCommand(-1, yieldable, -1));
    }

    private void createYieldableQuery(PreparedSQLStatement stmt) {
        yieldable = stmt.createYieldableQuery(maxRows, false, new AsyncResultHandler<Result>() {
            @Override
            public void handle(AsyncResult<Result> ar) {
                if (ar.isSucceeded()) {
                    if (result != null)
                        result.close();
                    result = ar.getResult();
                    onStatementComplete();
                } else {
                    onComplete(ar.getCause());
                }
            }
        });
        nestedSession.setYieldableCommand(new YieldableCommand(-1, yieldable, -1));
    }

    private void onStatementComplete() {
        nestedSession.getSessionInfo().submitTask(new AsyncTask() {
            @Override
            public void run() {
                createNextYieldable();
            }
        });
    }

    private void onComplete(Throwable t) {
        yieldable = null;
        session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        if (t != null)
            setPendingException(t);
        if (session.isAutoCommit()) {
            if (t != null)
                nestedSession.rollback();
            else
                nestedSession.asyncCommit();
        }
    }

    private LocalResult newLocalResult() {
        LocalResult r = new LocalResult(session, call.getResultExpressions(), 1);
        r.addRow(new Value[] { ValueInt.get(updateCount) });
        r.done();
        return r;
    }

    private void evalArgs() {
        Expression[] args = call.getArgs();
        int inputCount = procedure.getInputParameterCount();
        int parameterCount = procedure.getParameterCount();
        if (procedure.isVariadic()) {
            evalVariadicArgs(args, parameterCount);
            return;
        }
        // 实参覆盖所有参数(含OUT占位)时跳过OUT参数，否则只按入参对应
        boolean overAllParameters = args.length == parameterCount && args.length != inputCount;
        // 有默认值的入参可以省略，所以实参个数是一个区间
        if (!overAllParameters && (args.length < procedure.getMinInputParameterCount()
                || args.length > inputCount)) {
            String expected;
            if (procedure.getMinInputParameterCount() == inputCount) {
                expected = String.valueOf(inputCount);
            } else {
                expected = procedure.getMinInputParameterCount() + ".." + inputCount;
            }
            throw DbException.get(ErrorCode.INVALID_PARAMETER_COUNT_2, procedure.getName() + "()",
                    String.valueOf(args.length), expected);
        }
        argValues = new Value[args.length];
        for (int i = 0, len = args.length; i < len; i++) {
            // PostgreSQL的OUT位置的实参只占位、不求值，所以CALL p(1/0)不会报除零错误
            if (overAllParameters && procedure.getParameterMode(i) == SqlProcedure.PARAM_OUT)
                argValues[i] = ValueNull.INSTANCE;
            else
                argValues[i] = args[i].getValue(session);
        }
        // 省略的入参用默认值补齐，补齐后argValues按入参顺序排列
        if (!overAllParameters && args.length < inputCount) {
            Value[] padded = new Value[inputCount];
            System.arraycopy(argValues, 0, padded, 0, args.length);
            for (int i = args.length; i < inputCount; i++) {
                padded[i] = procedure.getDefaultInputValue(i);
            }
            argValues = padded;
        }
        inputArgIndexes = new int[inputCount];
        for (int i = 0; i < inputCount; i++) {
            inputArgIndexes[i] = overAllParameters ? procedure.getInputParameterIndex(i) : i;
        }
    }

    // VARIADIC过程: 前固定参数按参数列表对应(OUT参数只占位), 余下的实参打包成一个数组
    private void evalVariadicArgs(Expression[] args, int parameterCount) {
        int fixedCount = procedure.getFixedParameterCount();
        if (args.length < fixedCount) {
            throw DbException.get(ErrorCode.INVALID_PARAMETER_COUNT_2, procedure.getName() + "()",
                    String.valueOf(args.length), fixedCount + "..*");
        }
        argValues = new Value[parameterCount];
        for (int i = 0; i < fixedCount; i++) {
            // PostgreSQL的OUT位置的实参只占位、不求值
            if (procedure.getParameterMode(i) == SqlProcedure.PARAM_OUT) {
                argValues[i] = ValueNull.INSTANCE;
            } else {
                argValues[i] = args[i].getValue(session);
            }
        }
        int componentType = procedure.getVariadicComponentType();
        int extraCount = args.length - fixedCount;
        Value[] list = new Value[extraCount];
        for (int i = 0; i < extraCount; i++) {
            Value v = args[fixedCount + i].getValue(session);
            list[i] = v == null || v == ValueNull.INSTANCE ? v : v.convertTo(componentType);
        }
        argValues[parameterCount - 1] = ValueArray.get(list);
        // 入参(含VARIADIC参数)在完整参数列表里的下标
        inputArgIndexes = new int[procedure.getInputParameterCount()];
        for (int i = 0; i < inputArgIndexes.length; i++) {
            inputArgIndexes[i] = procedure.getInputParameterIndex(i);
        }
    }

    // 过程体是一个用分号分隔的SQL语句串，空过程体(比如空的BEGIN ATOMIC块)没有语句
    private String[] splitBody(String body) {
        return LealoneSQLParser.splitStatementsRaw(session, body);
    }

    // 事务语句会打断ServerSession对executingStatements的计数，过程体里不允许出现
    private void checkStatementType(PreparedSQLStatement stmt) {
        switch (stmt.getType()) {
        case SQLStatement.COMMIT:
        case SQLStatement.ROLLBACK:
        case SQLStatement.ROLLBACK_TO_SAVEPOINT:
        case SQLStatement.SET_AUTOCOMMIT_TRUE:
        case SQLStatement.SET_AUTOCOMMIT_FALSE:
            throw DbException.getUnsupportedException(
                    "transaction statement in procedure: " + procedure.getName());
        }
    }

    // 过程体的每条语句各自独立绑定参数：语句里的第i个?对应第i个入参，OUT参数的实参不参与绑定
    private void bindParameters(PreparedSQLStatement stmt) {
        List<? extends CommandParameter> params = stmt.getParameters();
        if (params == null)
            return;
        int inputCount = inputArgIndexes.length;
        for (int i = 0, size = params.size(); i < size && i < inputCount; i++) {
            params.get(i).setValue(argValues[inputArgIndexes[i]], false);
        }
    }
}
