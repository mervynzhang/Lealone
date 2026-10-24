/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.executor;

import java.util.ArrayList;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.command.CommandParameter;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.SessionStatus;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueInt;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.PreparedSQLStatement.YieldableCommand;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.StatementBase;
import com.lealone.sql.StatementList;
import com.lealone.sql.dml.CallProcedure;
import com.lealone.sql.expression.Expression;

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
        evalArgs();
        nestedSession = session.createNestedSession();
        if (session.isAutoCommit())
            nestedSession.setAutoCommit(false);
        statements = splitBody(procedure.getBody());
        createNextYieldable();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void executeInternal() {
        // yieldable不为null时过程体还没执行完，保持RUNNING状态避免调度器重复调度本类，
        // 过程体由子yieldable的完成回调继续驱动
        if (yieldable == null) {
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
        yieldable = stmt.createYieldableUpdate(ar -> {
            if (ar.isSucceeded()) {
                updateCount += ar.getResult();
                onStatementComplete();
            } else {
                onComplete(ar.getCause());
            }
        });
        nestedSession.setYieldableCommand(new YieldableCommand(-1, yieldable, -1));
    }

    private void createYieldableQuery(PreparedSQLStatement stmt) {
        yieldable = stmt.createYieldableQuery(maxRows, false, ar -> {
            if (ar.isSucceeded()) {
                if (result != null)
                    result.close();
                result = ar.getResult();
                onStatementComplete();
            } else {
                onComplete(ar.getCause());
            }
        });
        nestedSession.setYieldableCommand(new YieldableCommand(-1, yieldable, -1));
    }

    private void onStatementComplete() {
        nestedSession.getSessionInfo().submitTask(this::createNextYieldable);
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
        argValues = new Value[args.length];
        for (int i = 0, len = args.length; i < len; i++)
            argValues[i] = args[i].getValue(session);
    }

    // 过程体是一个用分号分隔的SQL语句串，这里用解析器自己判断一条语句到哪结束，
    // 这样字符串常量里出现分号也不会切错
    private String[] splitBody(String body) {
        ArrayList<String> list = new ArrayList<>();
        String sql = body;
        while (true) {
            StatementBase s = (StatementBase) session.createParser().parse(sql);
            if (s instanceof StatementList) {
                StatementList list2 = (StatementList) s;
                list.add(list2.getFirstStatement().getSQL());
                sql = skipStatementSeparator(list2.getRemaining());
                if (sql.isEmpty())
                    break;
            } else {
                list.add(s.getSQL());
                break;
            }
        }
        return list.toArray(new String[0]);
    }

    private static String skipStatementSeparator(String sql) {
        int i = 0;
        for (int len = sql.length(); i < len; i++) {
            char c = sql.charAt(i);
            if (c != ';' && !Character.isWhitespace(c))
                break;
        }
        return sql.substring(i);
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

    // 过程体的每条语句各自独立绑定参数：语句里的第i个?对应第i个实参
    private void bindParameters(PreparedSQLStatement stmt) {
        List<? extends CommandParameter> params = stmt.getParameters();
        if (params == null)
            return;
        for (int i = 0, size = params.size(); i < size && i < argValues.length; i++)
            params.get(i).setValue(argValues[i], false);
    }
}
