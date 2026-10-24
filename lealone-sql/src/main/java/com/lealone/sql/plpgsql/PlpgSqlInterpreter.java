/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.common.util.MathUtils;
import com.lealone.db.api.CursorPortal;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.schema.CompositeType;
import com.lealone.db.schema.Schema;
import com.lealone.db.table.Column;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueString;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.plpgsql.PlpgSqlAst.Assignment;
import com.lealone.sql.plpgsql.PlpgSqlAst.Block;
import com.lealone.sql.plpgsql.PlpgSqlAst.Branch;
import com.lealone.sql.plpgsql.PlpgSqlAst.Continue;
import com.lealone.sql.plpgsql.PlpgSqlAst.Declaration;
import com.lealone.sql.plpgsql.PlpgSqlAst.Exit;
import com.lealone.sql.plpgsql.PlpgSqlAst.CloseCursor;
import com.lealone.sql.plpgsql.PlpgSqlAst.FetchCursor;
import com.lealone.sql.plpgsql.PlpgSqlAst.For;
import com.lealone.sql.plpgsql.PlpgSqlAst.OpenCursor;
import com.lealone.sql.plpgsql.PlpgSqlAst.GetDiagnostics;
import com.lealone.sql.plpgsql.PlpgSqlAst.Handler;
import com.lealone.sql.plpgsql.PlpgSqlAst.If;
import com.lealone.sql.plpgsql.PlpgSqlAst.Loop;
import com.lealone.sql.plpgsql.PlpgSqlAst.NestedBlock;
import com.lealone.sql.plpgsql.PlpgSqlAst.Node;
import com.lealone.sql.plpgsql.PlpgSqlAst.NullStatement;
import com.lealone.sql.plpgsql.PlpgSqlAst.Perform;
import com.lealone.sql.plpgsql.PlpgSqlAst.Raise;
import com.lealone.sql.plpgsql.PlpgSqlAst.Return;
import com.lealone.sql.plpgsql.PlpgSqlAst.SqlExec;
import com.lealone.sql.plpgsql.PlpgSqlAst.While;
import com.lealone.transaction.Transaction;
import com.lealone.transaction.Transaction;

/**
 * PL/pgSQL语法树的解释执行器。
 * 每个函数调用创建一个实例，内部使用嵌套session执行SQL，
 * 变量保存在PlpgSqlScope作用域链里。
 */
public class PlpgSqlInterpreter {

    private static final Logger logger = LoggerFactory.getLogger(PlpgSqlInterpreter.class);

    // 这三个异常用来实现RETURN/EXIT/CONTINUE的控制流跳转
    private static class ReturnSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final Value value;

        ReturnSignal(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static class ExitSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String label;

        ExitSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    private static class ContinueSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String label;

        ContinueSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }
    }

    private final ServerSession session;
    // refcursor用的匿名门户名字里的序号
    private int unnamedPortals;
    private final Block body;
    private final PlpgSqlResultInfo resultInfo;
    private final int returnType;
    private final PlpgSqlSqlExecutor sqlExecutor;

    private PlpgSqlScope scope;
    private String[] parameterNames;
    // 触发器函数执行时的行数据，普通函数为null
    private boolean triggerMode;
    private String[] triggerColumnNames;
    private int[] triggerColumnTypes;
    private Value[] triggerOldRow;
    private Value[] triggerNewRow;
    // 直接保存OUT/INOUT参数和TABLE结果列的变量，避免同名局部变量遮蔽返回列
    private PlpgSqlVariable[] parameterVariables;
    private PlpgSqlVariable[] tableColumnVariables;
    // RETURNS SETOF/TABLE累积的结果行
    private final List<Value[]> returnedRows = new ArrayList<>();
    // FOUND魔法变量，DML/PERFORM/SELECT INTO之后由解释器设置
    private PlpgSqlVariable foundVariable;
    // 最近一条SQL语句处理的行数，GET DIAGNOSTICS ROW_COUNT用
    private long lastRowCount;
    // 正在被EXCEPTION handler处理的异常，RAISE;用它重新抛出
    private RuntimeException currentException;

    public PlpgSqlInterpreter(ServerSession session, Block body, PlpgSqlResultInfo resultInfo) {
        this.session = session;
        this.body = body;
        this.resultInfo = resultInfo;
        this.returnType = resultInfo.returnType;
        this.sqlExecutor = new PlpgSqlSqlExecutor(session);
    }

    // 触发器函数入口：把NEW/OLD作为record变量放进作用域
    public Value executeTrigger(String[] columnNames, int[] columnTypes, Value[] oldRow,
            Value[] newRow) {
        triggerMode = true;
        triggerColumnNames = columnNames;
        triggerColumnTypes = columnTypes;
        triggerOldRow = oldRow;
        triggerNewRow = newRow;
        return execute(new String[0], new String[0], new Value[0]);
    }

    /**
     * 执行函数体，返回RETURN语句的值；没有RETURN时返回NULL。
     */
    public Value execute(String[] parameterNames, String[] parameterTypes,
            Value[] parameterValues) {
        this.parameterNames = parameterNames;
        PlpgSqlScope parameterScope = new PlpgSqlScope(null);
        scope = parameterScope;
        try {
            declareParameters(parameterNames, parameterTypes, parameterValues);
            declareTriggerVariables(parameterScope);
            declareTableColumns(parameterScope);
            declareFoundVariable(parameterScope);
            try {
                executeBlock(body);
                // 自然结束时按返回形态组装返回值
                return finishWithoutReturn();
            } catch (ReturnSignal signal) {
                return signal.value;
            } catch (ExitSignal signal) {
                throw PlpgSqlException.raise("42601",
                        "EXIT cannot be used outside a loop or labeled block");
            } catch (ContinueSignal signal) {
                throw PlpgSqlException.raise("42601", "CONTINUE cannot be used outside a loop");
            }
        } finally {
            scope = null;
        }
    }

    // 函数自然结束或执行RETURN;时按返回形态组装返回值
    private Value finishWithoutReturn() {
        if (resultInfo.trigger)
            return ValueNull.INSTANCE;
        if (resultInfo.setReturning)
            return buildSetResult();
        // RETURNS RECORD和多个OUT参数都返回ValueArray形式的一行record;
        // 过程只要有OUT/INOUT参数就返回一行(单个OUT参数的过程也要一行, 和CALL的结果集对应)
        if (resultInfo.returnsRecord || resultInfo.hasMultipleOutputParameters()
                || resultInfo.procedure && resultInfo.hasOutputParameters())
            return buildOutputRecord();
        if (resultInfo.hasOutputParameters())
            return getOutputValue();
        if (returnType != Value.UNKNOWN && returnType != Value.NULL)
            throw PlpgSqlException.raise("2F005",
                    "control reached end of function without RETURN");
        return ValueNull.INSTANCE;
    }

    // 单个OUT/INOUT参数的返回值
    private Value getOutputValue() {
        int index = resultInfo.outputIndexes[0];
        PlpgSqlVariable variable = parameterVariables[index];
        Value value = variable == null ? ValueNull.INSTANCE : variable.getValue();
        if (returnType != Value.UNKNOWN && returnType != Value.NULL)
            value = value.convertTo(returnType);
        return value;
    }

    // RETURNS RECORD或多个OUT/INOUT参数组成一行record, 值用ValueArray表示
    private Value buildOutputRecord() {
        int count = resultInfo.outputIndexes.length;
        Value[] row = new Value[count];
        for (int i = 0; i < count; i++) {
            int index = resultInfo.outputIndexes[i];
            PlpgSqlVariable variable = parameterVariables[index];
            row[i] = variable == null ? ValueNull.INSTANCE : variable.getValue();
        }
        return ValueArray.get(row);
    }

    private Value buildSetResult() {
        // SETOF/TABLE：列名和类型来自函数声明
        int count = resultInfo.columnTypes == null ? 0 : resultInfo.columnTypes.length;
        String[] names = new String[count];
        int[] types = new int[count];
        long[] precisions = new long[count];
        int[] scales = new int[count];
        for (int i = 0; i < count; i++) {
            names[i] = resultInfo.getColumnName(i);
            types[i] = resultInfo.columnTypes[i];
            precisions[i] = resultInfo.columnPrecisions != null ? resultInfo.columnPrecisions[i]
                    : DataType.getDataType(types[i]).defaultPrecision;
            scales[i] = resultInfo.columnScales != null ? resultInfo.columnScales[i]
                    : DataType.getDataType(types[i]).defaultScale;
        }
        return buildResultSet(returnedRows, names, types, precisions, scales);
    }

    // 把Value行转换成SimpleResultSet，供RETURNS SETOF/TABLE和多个OUT参数使用
    private static Value buildResultSet(List<Value[]> rows, String[] names, int[] types,
            long[] precisions, int[] scales) {
        SimpleResultSet simple = new SimpleResultSet();
        simple.setAutoClose(false);
        for (int i = 0; i < types.length; i++) {
            simple.addColumn(names[i], DataType.convertTypeToSQLType(types[i]),
                    MathUtils.convertLongToInt(precisions[i]), scales[i]);
        }
        for (Value[] row : rows) {
            Object[] objects = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Value value = i < row.length ? row[i] : ValueNull.INSTANCE;
                if (value == null)
                    value = ValueNull.INSTANCE;
                if (types[i] != Value.UNKNOWN && types[i] != Value.NULL)
                    value = value.convertTo(types[i]);
                // 时间列直接放Value，物化成java.sql.Date/Time/Timestamp再读回来要按默认时区换算
                if (isDateTimeType(types[i])) {
                    objects[i] = value;
                } else {
                    objects[i] = value.getObject();
                }
            }
            simple.addRow(objects);
        }
        return ValueResultSet.get(simple);
    }

    private static boolean isDateTimeType(int type) {
        return type == Value.DATE || type == Value.TIME || type == Value.TIMESTAMP;
    }

    private void declareTriggerVariables(PlpgSqlScope parameterScope) {
        if (!resultInfo.trigger || !triggerMode)
            return;
        declareTriggerVariable(parameterScope, "NEW", triggerNewRow);
        declareTriggerVariable(parameterScope, "OLD", triggerOldRow);
    }

    private void declareTriggerVariable(PlpgSqlScope parameterScope, String name, Value[] row) {
        PlpgSqlVariable variable = new PlpgSqlVariable(name, false, false, false);
        variable.setRecord(true);
        variable.declareRecordFields(triggerColumnNames, triggerColumnTypes);
        if (row != null)
            variable.assignRecord(triggerColumnNames, triggerColumnTypes, row);
        parameterScope.declare(variable);
    }

    // RETURNS TABLE的列在函数体里也是变量，RETURN NEXT不带表达式时用它们组行
    private void declareTableColumns(PlpgSqlScope parameterScope) {
        if (!resultInfo.setReturning || !resultInfo.returnsTable || resultInfo.columnNames == null)
            return;
        tableColumnVariables = new PlpgSqlVariable[resultInfo.columnNames.length];
        for (int i = 0; i < resultInfo.columnNames.length; i++) {
            PlpgSqlVariable variable = new PlpgSqlVariable(resultInfo.columnNames[i], false, false,
                    false);
            variable.setTableColumn(true);
            int type = resultInfo.columnTypes != null && i < resultInfo.columnTypes.length
                    ? resultInfo.columnTypes[i]
                    : Value.UNKNOWN;
            variable.setType(type);
            variable.setInitialValue(ValueNull.INSTANCE);
            parameterScope.declare(variable);
            tableColumnVariables[i] = variable;
        }
    }

    // FOUND总是存在，初始为false；用户也可以给FOUND赋值
    private void declareFoundVariable(PlpgSqlScope parameterScope) {
        foundVariable = new PlpgSqlVariable("FOUND", false, false, false);
        foundVariable.setType(Value.BOOLEAN);
        foundVariable.setInitialValue(ValueBoolean.get(false));
        parameterScope.declare(foundVariable);
    }

    private void setFound(boolean found) {
        if (foundVariable != null)
            foundVariable.setValue(ValueBoolean.get(found));
    }

    private void declareParameters(String[] parameterNames, String[] parameterTypes,
            Value[] parameterValues) {
        if (parameterNames == null)
            return;
        parameterVariables = new PlpgSqlVariable[parameterNames.length];
        for (int i = 0; i < parameterNames.length; i++) {
            PlpgSqlVariable variable = new PlpgSqlVariable(parameterNames[i], false, false, false);
            int type = Value.UNKNOWN;
            Value value = parameterValues != null && i < parameterValues.length
                    ? parameterValues[i]
                    : ValueNull.INSTANCE;
            if (parameterTypes != null && i < parameterTypes.length) {
                if (isRefcursorType(parameterTypes[i]))
                    variable.setRefcursor(true);
                int poly = Column.getPolymorphicType(parameterTypes[i]);
                if (poly != Column.POLY_NONE) {
                    if (poly == Column.POLY_ANYARRAY)
                        type = Value.ARRAY;
                    else if (value != null && value != ValueNull.INSTANCE)
                        type = value.getType();
                } else {
                    CompositeType compositeType = findCompositeType(parameterTypes[i]);
                    if (compositeType != null) {
                        variable.setCompositeType(compositeType);
                        type = Value.ARRAY;
                    } else {
                        type = resolveType(parameterTypes[i]);
                    }
                }
            }
            variable.setType(type);
            variable.setInitialValue(value);
            scope.declare(variable);
            parameterVariables[i] = variable;
        }
    }

    private CompositeType findCompositeType(String typeName) {
        if (typeName == null || session == null)
            return null;
        String name = PlpgSqlSqlExecutor.compactTypeName(typeName);
        if (name.endsWith("[]"))
            return null;
        String schemaName = session.getCurrentSchemaName();
        if (schemaName == null)
            return null;
        Schema schema = session.getDatabase().findSchema(session, schemaName);
        return schema == null ? null : schema.findCompositeType(session, name);
    }

    private void executeBlock(Block block) {
        PlpgSqlScope blockScope = new PlpgSqlScope(scope);
        blockScope.setLabel(block.label);
        scope = blockScope;
        // 带EXCEPTION段的块对应PostgreSQL的隐式子事务，进入块时记下事务的保存点，
        // 没有EXCEPTION段的块不需要保存点，避免每个块都有额外开销
        int savepointId = block.handlers.isEmpty() ? -1 : getSavepointId();
        try {
            declareVariables(block);
            try {
                executeStatements(block.statements);
            } catch (ExitSignal signal) {
                // 带标签的块可以被EXIT label直接退出
                if (block.label != null && block.label.equalsIgnoreCase(signal.label))
                    return;
                throw signal;
            } catch (ContinueSignal signal) {
                throw signal;
            } catch (ReturnSignal signal) {
                throw signal;
            } catch (PlpgSqlException e) {
                if (!handleException(block, savepointId, e))
                    throw e;
            } catch (DbException e) {
                if (!handleException(block, savepointId, e))
                    throw e;
            }
        } finally {
            scope = blockScope.getParent();
        }
    }

    private void declareVariables(Block block) {
        for (Declaration declaration : block.declarations) {
            PlpgSqlVariable variable = new PlpgSqlVariable(declaration.name,
                    declaration.nameQuoted, declaration.constant, declaration.notNull);
            String typeName = declaration.typeName == null ? "" : declaration.typeName.trim();
            boolean recordArrayType = isRecordArrayType(typeName);
            boolean recordType = !recordArrayType && isRecordType(typeName);
            if (recordArrayType) {
                variable.setRecordArray(true);
                PlpgSqlSqlExecutor.QueryRows rowType = sqlExecutor
                        .resolveRowType(normalizeRowTypeName(typeName));
                variable.declareRecordArrayFields(rowType.columnNames, rowType.columnTypes);
            } else if (recordType) {
                variable.setRecord(true);
                if (!"record".equalsIgnoreCase(typeName)) {
                    // tab%ROWTYPE在声明时解析出列名和类型，解析失败时给出可读的错误
                    variable.setRowTypeDeclared(true);
                    PlpgSqlSqlExecutor.QueryRows rowType = sqlExecutor.resolveRowType(typeName);
                    variable.declareRecordFields(rowType.columnNames, rowType.columnTypes);
                    variable.markRecordAssigned();
                }
            } else if (declaration.cursor) {
                variable.setCursor(true);
                PlpgSqlCursor cursor = new PlpgSqlCursor(declaration.name);
                cursor.setQuery(declaration.cursorQuery);
                cursor.setParameters(declaration.cursorParameterNames,
                        declaration.cursorParameterTypes);
                variable.setCursorState(cursor);
            } else if (isRefcursorType(typeName)) {
                // refcursor变量的值就是门户名，真正的游标状态放在会话的门户注册表里
                variable.setRefcursor(true);
                variable.setType(Value.STRING);
            } else {
                variable.setType(resolveType(typeName));
            }
            if (declaration.constant && declaration.defaultExpression == null)
                throw PlpgSqlException.raise("42601",
                        "constant " + declaration.name + " must have a default value");
            if ((recordType || recordArrayType) && declaration.defaultExpression != null)
                throw DbException.getUnsupportedException(
                        "PL/pgSQL default value for record variable: " + declaration.name);
            // 默认值表达式在变量加入作用域之前求值，这样同名变量可以引用外层的
            Value value = !recordType && !recordArrayType && declaration.defaultExpression != null
                    ? sqlExecutor.evalExpression(declaration.defaultExpression, scope)
                    : ValueNull.INSTANCE;
            scope.declare(variable);
            if (recordType || recordArrayType) {
                if (declaration.notNull)
                    throw PlpgSqlException.notNullViolation(declaration.name);
            } else {
                variable.setInitialValue(value);
            }
        }
    }

    private static boolean isRecordType(String typeName) {
        String compact = PlpgSqlSqlExecutor.compactTypeName(typeName);
        return "record".equalsIgnoreCase(compact) || compact.toLowerCase().endsWith("%rowtype");
    }

    // t%ROWTYPE[]和t[]%ROWTYPE两种写法都表示行数组
    private static boolean isRecordArrayType(String typeName) {
        String compact = PlpgSqlSqlExecutor.compactTypeName(typeName).toLowerCase();
        if (compact.endsWith("[]")) {
            String base = compact.substring(0, compact.length() - 2);
            return base.endsWith("%rowtype");
        }
        if (compact.endsWith("%rowtype")) {
            String table = compact.substring(0, compact.length() - 8);
            return table.endsWith("[]");
        }
        return false;
    }

    private static String normalizeRowTypeName(String typeName) {
        String compact = PlpgSqlSqlExecutor.compactTypeName(typeName);
        if (compact.endsWith("[]"))
            compact = compact.substring(0, compact.length() - 2);
        if (compact.toLowerCase().endsWith("%rowtype")) {
            String table = compact.substring(0, compact.length() - 8);
            if (table.endsWith("[]"))
                table = table.substring(0, table.length() - 2);
            return table + "%ROWTYPE";
        }
        return compact;
    }

    private int resolveType(String typeName) {
        try {
            return sqlExecutor.resolveType(typeName);
        } catch (DbException e) {
            throw e;
        }
    }

    // 事务当前的保存点id，没有事务时返回-1
    private int getSavepointId() {
        Transaction transaction = session.getTransaction();
        if (transaction == null)
            return -1;
        return transaction.getSavepointId();
    }

    // 执行handler之前先回滚到块入口的保存点，撤销块内的DML修改，对应PostgreSQL的隐式子事务语义
    private void rollbackToSavepoint(int savepointId) {
        if (savepointId < 0)
            return;
        Transaction transaction = session.getTransaction();
        // 保存点之后没有新的修改就不用回滚，同时也避免undoLog还没创建时的空指针
        if (transaction == null || transaction.getSavepointId() <= savepointId)
            return;
        transaction.rollbackToSavepoint(savepointId);
    }

    private boolean handleException(Block block, int savepointId, RuntimeException e) {
        if (block.handlers.isEmpty())
            return false;
        String sqlState = PlpgSqlCondition.getSqlState(e);
        for (Handler handler : block.handlers) {
            if (PlpgSqlCondition.matches(handler.conditions, sqlState)) {
                // handler捕获异常之前，块内的修改要全部回滚
                rollbackToSavepoint(savepointId);
                RuntimeException oldException = currentException;
                currentException = e;
                try {
                    executeStatements(handler.statements);
                } finally {
                    currentException = oldException;
                }
                return true;
            }
        }
        return false;
    }

    private void executeStatements(List<Node> statements) {
        for (Node statement : statements)
            executeStatement(statement);
    }

    private void executeStatement(Node node) {
        if (node instanceof NestedBlock) {
            executeBlock(((NestedBlock) node).block);
        } else if (node instanceof Assignment) {
            executeAssignment((Assignment) node);
        } else if (node instanceof If) {
            executeIf((If) node);
        } else if (node instanceof Return) {
            executeReturn((Return) node);
        } else if (node instanceof SqlExec) {
            executeSql((SqlExec) node);
        } else if (node instanceof Perform) {
            executePerform((Perform) node);
        } else if (node instanceof GetDiagnostics) {
            executeGetDiagnostics((GetDiagnostics) node);
        } else if (node instanceof Loop) {
            executeLoop((Loop) node);
        } else if (node instanceof While) {
            executeWhile((While) node);
        } else if (node instanceof For) {
            executeFor((For) node);
        } else if (node instanceof Exit) {
            executeExit((Exit) node);
        } else if (node instanceof Continue) {
            executeContinue((Continue) node);
        } else if (node instanceof Raise) {
            executeRaise((Raise) node);
        } else if (node instanceof OpenCursor) {
            executeOpenCursor((OpenCursor) node);
        } else if (node instanceof FetchCursor) {
            executeFetchCursor((FetchCursor) node);
        } else if (node instanceof CloseCursor) {
            executeCloseCursor((CloseCursor) node);
        } else if (node instanceof NullStatement) {
            // NULL语句什么也不做
        } else {
            throw DbException
                    .getInternalError("unknown PL/pgSQL node: " + node.getClass().getName());
        }
    }

    private void executeAssignment(Assignment node) {
        PlpgSqlVariable variable;
        Value value = null;
        if (node.arrayIndex != null) {
            variable = node.qualifier == null ? findVariable(node.target, node.targetQuoted)
                    : scope.findVariableByLabel(node.qualifier, node.target, node.targetQuoted);
            if (variable == null)
                throw PlpgSqlException.raise("42703",
                        "variable " + node.target + " does not exist");
            if (!variable.isRecordArray())
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "PL/pgSQL variable " + node.target + " is not a record array");
            variable.checkCanAssign();
            int index = sqlExecutor.evalExpression(node.arrayIndex, scope).getInt();
            value = sqlExecutor.evalExpression(node.expression, scope);
            if (node.arrayField != null) {
                variable.setRecordArrayField(index, node.arrayField, value);
            } else {
                variable.setRecordArrayElement(index, value);
            }
            return;
        }
        if (node.qualifier != null) {
            variable = scope.findVariableByLabel(node.qualifier, node.target, node.targetQuoted);
            if (variable == null) {
                // outerblock.param1不是标签时可以给record变量的字段赋值 r.col := ...
                PlpgSqlVariable record = scope.findVariable(node.qualifier, node.qualifierQuoted);
                if (record != null && record.isRecord()) {
                    record.checkCanAssign();
                    value = sqlExecutor.evalExpression(node.expression, scope);
                    record.setRecordField(node.target, value);
                    return;
                }
                throw PlpgSqlException.raise("42703",
                        "variable " + node.qualifier + "." + node.target + " does not exist");
            }
        } else {
            variable = findVariable(node.target, node.targetQuoted);
        }
        variable.checkCanAssign();
        if (value == null)
            value = sqlExecutor.evalExpression(node.expression, scope);
        variable.setValue(value);
    }

    private void executeIf(If node) {
        for (Branch branch : node.branches) {
            if (evalCondition(branch.condition)) {
                executeStatements(branch.statements);
                return;
            }
        }
        executeStatements(node.elseStatements);
    }

    private boolean evalCondition(String condition) {
        Value value = sqlExecutor.evalExpression(condition, scope);
        return value.getBoolean();
    }

    private void executeReturn(Return node) {
        if (node.next) {
            executeReturnNext(node);
            return;
        }
        if (node.query) {
            executeReturnQuery(node);
            return;
        }
        if (node.expression == null) {
            // RETURN; 在SETOF/TABLE里结束结果集，在OUT参数函数里返回OUT变量的值
            throw new ReturnSignal(finishWithoutReturn());
        }
        if (resultInfo.trigger) {
            String expression = node.expression.trim();
            if ("NEW".equalsIgnoreCase(expression) || "OLD".equalsIgnoreCase(expression)) {
                PlpgSqlVariable variable = scope.findVariable(expression, false);
                if (variable == null || !variable.isRecord())
                    throw PlpgSqlException.raise("42703",
                            "trigger variable " + expression + " does not exist");
                throw new ReturnSignal(variable.getRecordValue());
            }
        }
        if (resultInfo.setReturning)
            throw PlpgSqlException.raise("2F005",
                    "RETURN cannot have a parameter in function returning set");
        if (resultInfo.returnsRecord || resultInfo.hasMultipleOutputParameters()
                || resultInfo.procedure && resultInfo.hasOutputParameters())
            throw PlpgSqlException.raise("2F005",
                    "RETURN cannot have a parameter in function with OUT parameters");
        if (returnType == Value.NULL)
            throw PlpgSqlException.raise("2F005",
                    "RETURN cannot have a parameter in function with void return type");
        Value value = sqlExecutor.evalExpression(node.expression, scope);
        if (returnType != Value.UNKNOWN)
            value = value.convertTo(returnType);
        throw new ReturnSignal(value);
    }

    // RETURN NEXT只追加一行，不结束函数
    private void executeReturnNext(Return node) {
        if (!resultInfo.setReturning)
            throw PlpgSqlException.raise("2F005",
                    "RETURN NEXT can only be used in a set-returning function");
        Value[] row = buildReturnedRow(node.expression);
        returnedRows.add(row);
    }

    // 根据表达式或OUT/TABLE变量组装一行结果
    private Value[] buildReturnedRow(String expression) {
        int count = resultInfo.columnTypes == null ? 0 : resultInfo.columnTypes.length;
        if (count == 0)
            throw PlpgSqlException.raise("2F005", "RETURN NEXT in a function without columns");
        Value[] row = new Value[count];
        if (expression == null) {
            // RETURNS TABLE：列变量里就是当前行的值
            if (!resultInfo.returnsTable)
                throw PlpgSqlException.raise("2F005",
                        "RETURN NEXT requires an expression in a set-returning function");
            for (int i = 0; i < count; i++) {
                PlpgSqlVariable variable = tableColumnVariables == null
                        || i >= tableColumnVariables.length ? null : tableColumnVariables[i];
                row[i] = variable == null ? ValueNull.INSTANCE : variable.getValue();
            }
            return row;
        }
        if (count == 1) {
            PlpgSqlVariable record = scope.findVariable(expression.trim(), false);
            if (record != null && record.isRecord()) {
                if (record.getRecordFieldCount() != 1)
                    throw PlpgSqlException.raise("42804",
                            "RETURN NEXT record has " + record.getRecordFieldCount()
                                    + " fields but 1 column expected");
                row[0] = convertColumnValue(0, record.getRecordField(0));
                return row;
            }
            Value value = sqlExecutor.evalExpression(expression, scope);
            row[0] = convertColumnValue(0, value);
            return row;
        }
        // 多列时允许表达式是一个record变量或ValueArray(例如arr[1])
        PlpgSqlVariable record = scope.findVariable(expression.trim(), false);
        if (record == null || !record.isRecord()) {
            Value value = sqlExecutor.evalExpression(expression, scope);
            if (value != null && value.getType() == Value.ARRAY) {
                Value[] list = ((ValueArray) value).getList();
                if (list.length == count) {
                    for (int i = 0; i < count; i++)
                        row[i] = convertColumnValue(i, list[i]);
                    return row;
                }
            }
            throw DbException.getUnsupportedException(
                    "PL/pgSQL RETURN NEXT with multiple columns requires a record variable");
        }
        if (record.getRecordFieldCount() != count)
            throw PlpgSqlException.raise("42804",
                    "RETURN NEXT record has " + record.getRecordFieldCount()
                            + " fields but " + count + " columns expected");
        for (int i = 0; i < count; i++)
            row[i] = convertColumnValue(i, record.getRecordField(i));
        return row;
    }

    // RETURN QUERY里隐藏RETURNS TABLE的隐式列变量，让同名列按查询表列解析
    private PlpgSqlVariableResolver hideTableColumns() {
        return new PlpgSqlVariableResolver() {
            @Override
            public PlpgSqlVariable findVariable(String name, boolean quoted) {
                PlpgSqlVariable variable = scope.findVariable(name, quoted);
                return variable != null && variable.isTableColumn() ? null : variable;
            }

            @Override
            public boolean hasLabel(String label) {
                return scope.hasLabel(label);
            }

            @Override
            public PlpgSqlVariable findVariableByLabel(String label, String name, boolean quoted) {
                PlpgSqlVariable variable = scope.findVariableByLabel(label, name, quoted);
                return variable != null && variable.isTableColumn() ? null : variable;
            }
        };
    }

    private Value convertColumnValue(int index, Value value) {
        if (value == null)
            value = ValueNull.INSTANCE;
        int type = resultInfo.columnTypes[index];
        if (type != Value.UNKNOWN && type != Value.NULL)
            value = value.convertTo(type);
        return value;
    }

    // RETURN QUERY执行查询并把所有行追加到结果集
    private void executeReturnQuery(Return node) {
        if (!resultInfo.setReturning)
            throw PlpgSqlException.raise("2F005",
                    "RETURN QUERY can only be used in a set-returning function");
        if (node.expression == null || node.expression.trim().isEmpty())
            throw PlpgSqlException.raise("42601", "RETURN QUERY requires a query");
        // RETURN QUERY里的SELECT先按查询自身的列解析，避免和TABLE结果列变量同名时被替换成变量
        PlpgSqlSqlExecutor.QueryRows queryRows = sqlExecutor.queryWithColumns(node.expression,
                hideTableColumns(), 0);
        int count = resultInfo.columnTypes == null ? 0 : resultInfo.columnTypes.length;
        if (queryRows.columnTypes.length != count)
            throw PlpgSqlException.raise("42804",
                    "RETURN QUERY has " + queryRows.columnTypes.length + " columns but "
                            + count + " expected");
        for (Value[] source : queryRows.rows) {
            Value[] row = new Value[count];
            for (int i = 0; i < count; i++)
                row[i] = convertColumnValue(i, source[i]);
            returnedRows.add(row);
        }
        lastRowCount = queryRows.rows.size();
        setFound(!queryRows.rows.isEmpty());
    }

    private void executeSql(SqlExec node) {
        PlpgSqlSqlExecutor.SelectInto selectInto = PlpgSqlSqlExecutor.splitSelectInto(node.sql);
        if (selectInto == null)
            selectInto = PlpgSqlSqlExecutor.splitReturningInto(node.sql);
        if (selectInto != null) {
            executeSelectInto(selectInto);
            return;
        }
        String firstWord = firstWord(node.sql);
        int count = sqlExecutor.execute(node.sql, scope);
        // ROW_COUNT是最近一条SQL命令处理的行数，utility语句为0
        lastRowCount = count;
        // INSERT/UPDATE/DELETE/MERGE之后更新FOUND
        if (isDml(firstWord))
            setFound(count > 0);
    }

    private static boolean isDml(String word) {
        return "INSERT".equalsIgnoreCase(word) || "UPDATE".equalsIgnoreCase(word)
                || "DELETE".equalsIgnoreCase(word) || "MERGE".equalsIgnoreCase(word)
                || "REPLACE".equalsIgnoreCase(word) || "UPSERT".equalsIgnoreCase(word);
    }

    private void executeSelectInto(PlpgSqlSqlExecutor.SelectInto selectInto) {
        PlpgSqlSqlExecutor.QueryRows queryRows = sqlExecutor.queryWithColumns(selectInto.query,
                scope, 2);
        List<Value[]> rows = queryRows.rows;
        // 非STRICT的INTO只处理第一行
        lastRowCount = rows.isEmpty() ? 0 : 1;
        // 单个record变量接收整行 SELECT * INTO r
        if (selectInto.targets.length == 1) {
            PlpgSqlVariable recordVariable = findVariable(selectInto.targets[0], false);
            if (recordVariable.isRecord()) {
                if (rows.isEmpty()) {
                    if (selectInto.strict) {
                        setFound(false);
                        throw PlpgSqlException.noDataFound();
                    }
                    recordVariable.invalidateRecord(queryRows.columnNames, queryRows.columnTypes);
                    setFound(false);
                    return;
                }
                if (selectInto.strict && rows.size() > 1) {
                    setFound(true);
                    throw PlpgSqlException.tooManyRows();
                }
                recordVariable.checkCanAssign();
                recordVariable.assignRecord(queryRows.columnNames, queryRows.columnTypes,
                        rows.get(0));
                setFound(true);
                return;
            }
        }
        if (rows.isEmpty()) {
            if (selectInto.strict) {
                setFound(false);
                throw PlpgSqlException.noDataFound();
            }
            for (String target : selectInto.targets) {
                PlpgSqlVariable variable = findVariable(target, false);
                variable.checkCanAssign();
                variable.setValue(ValueNull.INSTANCE);
            }
            setFound(false);
            return;
        }
        if (selectInto.strict && rows.size() > 1) {
            setFound(true);
            throw PlpgSqlException.tooManyRows();
        }
        Value[] row = rows.get(0);
        if (row.length != selectInto.targets.length)
            throw PlpgSqlException.raise("42804",
                    "INTO has " + row.length + " columns but " + selectInto.targets.length
                            + " variables");
        for (int i = 0; i < row.length; i++) {
            PlpgSqlVariable variable = findVariable(selectInto.targets[i], false);
            if (variable.isRecord())
                throw DbException.getUnsupportedException(
                        "PL/pgSQL multiple SELECT INTO targets contain record " + variable.getName());
            variable.checkCanAssign();
            variable.setValue(row[i]);
        }
        setFound(true);
    }

    private void executePerform(Perform node) {
        String query = node.query.trim();
        String firstWord = firstWord(query);
        if (!("SELECT".equalsIgnoreCase(firstWord) || "WITH".equalsIgnoreCase(firstWord)
                || "VALUES".equalsIgnoreCase(firstWord) || "TABLE".equalsIgnoreCase(firstWord)))
            query = "SELECT " + query;
        int count = sqlExecutor.executeQuery(query, scope);
        lastRowCount = count;
        setFound(count > 0);
    }

    private void executeGetDiagnostics(GetDiagnostics node) {
        if (!"ROW_COUNT".equalsIgnoreCase(node.item))
            throw DbException.getUnsupportedException(
                    "GET DIAGNOSTICS " + node.item + " in PL/pgSQL");
        PlpgSqlVariable variable = findVariable(node.target, node.targetQuoted);
        variable.checkCanAssign();
        variable.setValue(ValueLong.get(lastRowCount));
    }

    private static String firstWord(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i)))
            i++;
        int start = i;
        while (i < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'))
            i++;
        return sql.substring(start, i);
    }

    private void executeLoop(Loop node) {
        while (true) {
            try {
                executeStatements(node.statements);
            } catch (ContinueSignal signal) {
                if (matchesLoopLabel(node.label, signal.label))
                    continue;
                throw signal;
            } catch (ExitSignal signal) {
                if (matchesLoopLabel(node.label, signal.label))
                    return;
                throw signal;
            }
        }
    }

    private void executeWhile(While node) {
        while (evalCondition(node.condition)) {
            try {
                executeStatements(node.statements);
            } catch (ContinueSignal signal) {
                if (matchesLoopLabel(node.label, signal.label))
                    continue;
                throw signal;
            } catch (ExitSignal signal) {
                if (matchesLoopLabel(node.label, signal.label))
                    return;
                throw signal;
            }
        }
    }

    private void executeFor(For node) {
        if (node.cursorName != null) {
            executeForCursor(node);
            return;
        }
        if (node.query != null) {
            executeForQuery(node);
            return;
        }
        // 循环上下界和步长只在进入循环时求值一次，此时还看不到循环变量
        int start = evalForBound(node.startExpression);
        int end = evalForBound(node.endExpression);
        int step = 1;
        if (node.stepExpression != null) {
            step = evalForBound(node.stepExpression);
            if (step <= 0)
                throw PlpgSqlException.raise("22023",
                        "FOR loop BY value must be greater than zero");
        }

        PlpgSqlScope loopScope = new PlpgSqlScope(scope);
        loopScope.setLabel(node.label);
        PlpgSqlScope oldScope = scope;
        scope = loopScope;
        try {
            PlpgSqlVariable variable = new PlpgSqlVariable(node.variable, node.variableQuoted,
                    false, false);
            variable.setType(Value.INT);
            loopScope.declare(variable);
            // REVERSE表示从第一个表达式开始，每次减去步长，所以通常写成REVERSE n..1
            // 用long做迭代变量，避免到达Integer.MAX_VALUE附近时溢出死循环
            long delta = node.reverse ? -step : step;
            boolean iterated = false;
            for (long i = start; delta > 0 ? i <= end : i >= end; i += delta) {
                iterated = true;
                variable.setValue(ValueInt.get((int) i));
                try {
                    executeStatements(node.statements);
                } catch (ContinueSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label))
                        continue;
                    throw signal;
                } catch (ExitSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label)) {
                        // PostgreSQL在FOR循环结束时按是否迭代过设置FOUND
                        setFound(iterated);
                        return;
                    }
                    throw signal;
                }
            }
            setFound(iterated);
        } finally {
            scope = oldScope;
        }
    }

    // FOR target IN <query> LOOP：查询结果先全部取回，再逐行赋给record/%ROWTYPE变量
    private void executeForQuery(For node) {
        PlpgSqlScope loopScope = new PlpgSqlScope(scope);
        loopScope.setLabel(node.label);
        PlpgSqlScope oldScope = scope;
        scope = loopScope;
        try {
            PlpgSqlVariable target = scope.findVariable(node.variable, node.variableQuoted);
            if (target == null) {
                // 没有声明的循环目标按PostgreSQL的规则隐式声明成record变量
                target = new PlpgSqlVariable(node.variable, node.variableQuoted, false, false);
                target.setRecord(true);
                loopScope.declare(target);
            } else if (!target.isRecord()) {
                throw PlpgSqlException.raise("42804",
                        "FOR loop target " + node.variable + " must be a record or row variable");
            }
            target.checkCanAssign();
            PlpgSqlSqlExecutor.QueryRows queryRows = sqlExecutor.queryWithColumns(node.query, scope,
                    0);
            boolean iterated = false;
            for (Value[] row : queryRows.rows) {
                iterated = true;
                target.assignRecord(queryRows.columnNames, queryRows.columnTypes, row);
                try {
                    executeStatements(node.statements);
                } catch (ContinueSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label))
                        continue;
                    throw signal;
                } catch (ExitSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label)) {
                        // 和整数范围循环一样，循环结束时按是否迭代过设置FOUND
                        setFound(iterated);
                        return;
                    }
                    throw signal;
                }
            }
            setFound(iterated);
        } finally {
            scope = oldScope;
        }
    }

    private static boolean isRefcursorType(String typeName) {
        return "refcursor".equalsIgnoreCase(PlpgSqlSqlExecutor.compactTypeName(typeName));
    }

    // refcursor的门户注册表挂在最外层会话上，函数内嵌会话也能看到同一个门户
    private HashMap<String, CursorPortal> getPortalMap() {
        ServerSession portalSession = session;
        Transaction transaction = portalSession.getTransaction();
        while (transaction != null && transaction.getParentTransaction() != null) {
            Transaction parent = transaction.getParentTransaction();
            if (!(parent.getSession() instanceof ServerSession))
                break;
            portalSession = (ServerSession) parent.getSession();
            transaction = parent;
        }
        return portalSession.getCursorPortals();
    }

    private PlpgSqlCursor getCursorState(PlpgSqlVariable variable, String name) {
        if (variable.isCursor())
            return variable.getCursorState();
        Value value = variable.getValue();
        String portalName = value == null || value == ValueNull.INSTANCE ? null : value.getString();
        if (portalName == null)
            throw PlpgSqlException.raise("34000", "cursor " + name + " is not open");
        PlpgSqlCursor cursor = (PlpgSqlCursor) getPortalMap().get(portalName);
        if (cursor == null)
            throw PlpgSqlException.raise("34000", "cursor " + portalName + " does not exist");
        return cursor;
    }

    private PlpgSqlVariable findCursorVariable(String name, boolean quoted) {
        PlpgSqlVariable variable = scope.findVariable(name, quoted);
        if (variable == null || !variable.isCursor() && !variable.isRefcursor())
            throw PlpgSqlException.raise("34000", "cursor " + name + " does not exist");
        return variable;
    }

    // OPEN c / OPEN c FOR <query> / OPEN c(args)
    private void executeOpenCursor(OpenCursor node) {
        PlpgSqlVariable variable = findCursorVariable(node.name, node.nameQuoted);
        if (variable.isCursor()) {
            PlpgSqlCursor cursor = variable.getCursorState();
            if (cursor.isOpen())
                throw PlpgSqlException.raise("55000",
                        "cursor " + variable.getName() + " already open");
            String query = node.query == null ? cursor.getQuery() : node.query;
            if (query == null)
                throw PlpgSqlException.raise("55000",
                        "cursor " + variable.getName() + " has no query");
            PlpgSqlSqlExecutor.QueryRows rows = sqlExecutor.queryWithColumns(query,
                    openCursorArguments(cursor, node), 0);
            cursor.open(rows);
            return;
        }
        if (node.query == null)
            throw PlpgSqlException.raise("55000",
                    "cursor " + variable.getName() + " can only be opened with a query");
        String portalName = unnamedPortalName(variable);
        PlpgSqlSqlExecutor.QueryRows rows = sqlExecutor.queryWithColumns(node.query, scope, 0);
        PlpgSqlCursor cursor = new PlpgSqlCursor(portalName);
        cursor.open(rows);
        getPortalMap().put(portalName, cursor);
        // constant refcursor可以带初始门户名, 打开时门户名没变就不算给常量赋值
        if (!variable.isConstant() || !portalName.equals(variable.getValue().getString())) {
            variable.checkCanAssign();
            variable.setValue(ValueString.get(portalName));
        }
    }

    // 参数化游标：实参先求值，再以游标参数名建立一层作用域给查询用
    private PlpgSqlVariableResolver openCursorArguments(PlpgSqlCursor cursor, OpenCursor node) {
        List<String> names = cursor.getParameterNames();
        if (names == null || names.isEmpty()) {
            if (!node.argumentExpressions.isEmpty())
                throw PlpgSqlException.raise("42601",
                        "cursor " + cursor.getName() + " has no parameters");
            return scope;
        }
        if (node.argumentExpressions.size() > names.size())
            throw PlpgSqlException.raise("42601",
                    "too many arguments for cursor " + cursor.getName());
        Value[] values = new Value[names.size()];
        for (int i = 0; i < node.argumentExpressions.size(); i++) {
            int index = i;
            String argumentName = node.argumentNames.get(i);
            if (argumentName != null) {
                index = -1;
                for (int j = 0; j < names.size(); j++) {
                    if (names.get(j).equalsIgnoreCase(argumentName)) {
                        index = j;
                        break;
                    }
                }
                if (index < 0)
                    throw PlpgSqlException.raise("42601",
                            "cursor " + cursor.getName() + " has no parameter " + argumentName);
            }
            values[index] = sqlExecutor.evalExpression(node.argumentExpressions.get(i), scope);
        }
        PlpgSqlScope argumentScope = new PlpgSqlScope(scope);
        for (int i = 0; i < names.size(); i++) {
            PlpgSqlVariable parameter = new PlpgSqlVariable(names.get(i), false, false, false);
            parameter.setType(resolveType(cursor.getParameterTypes().get(i)));
            argumentScope.declare(parameter);
            parameter.setInitialValue(values[i] == null ? ValueNull.INSTANCE : values[i]);
        }
        return argumentScope;
    }

    private String unnamedPortalName(PlpgSqlVariable variable) {
        Value value = variable.getValue();
        if (value != null && value != ValueNull.INSTANCE) {
            String name = value.getString();
            if (name != null && !name.isEmpty())
                return name;
        }
        unnamedPortals++;
        return "<unnamed portal " + unnamedPortals + ">";
    }

    // FETCH [方向] c INTO target, ...
    private void executeFetchCursor(FetchCursor node) {
        PlpgSqlVariable variable = findCursorVariable(node.name, node.nameQuoted);
        PlpgSqlCursor cursor = getCursorState(variable, node.name);
        if (!cursor.isOpen())
            throw PlpgSqlException.raise("34000", "cursor " + node.name + " is not open");
        int count = 1;
        if (node.countExpression != null)
            count = sqlExecutor.evalExpression(node.countExpression, scope).getInt();
        int moved = cursor.move(node.direction, count);
        Value[] row = cursor.currentRow();
        if (row != null && !node.targets.isEmpty())
            assignFetchedRow(cursor, row, node);
        String direction = node.direction == null ? "NEXT" : node.direction.toUpperCase();
        if ("ALL".equals(direction) || "FORWARD_ALL".equals(direction)
                || "BACKWARD_ALL".equals(direction)) {
            lastRowCount = moved;
        } else {
            lastRowCount = row == null ? 0 : 1;
        }
        setFound(row != null);
    }

    private void assignFetchedRow(PlpgSqlCursor cursor, Value[] row, FetchCursor node) {
        String[] columnNames = cursor.getColumnNames();
        int[] columnTypes = cursor.getColumnTypes();
        if (node.targets.size() == 1) {
            PlpgSqlVariable target = findVariable(node.targets.get(0), node.targetsQuoted.get(0));
            if (target.isRecord()) {
                target.checkCanAssign();
                target.assignRecord(columnNames, columnTypes, row);
                return;
            }
            if (row.length != 1)
                throw PlpgSqlException.raise("42601", "FETCH ... INTO " + target.getName() + " has "
                        + row.length + " columns");
            target.checkCanAssign();
            target.setValue(convertFetchedValue(columnTypes, 0, row[0]));
            return;
        }
        if (row.length != node.targets.size())
            throw PlpgSqlException.raise("42601", "FETCH ... INTO has " + row.length
                    + " columns but " + node.targets.size() + " variables");
        for (int i = 0; i < row.length; i++) {
            PlpgSqlVariable target = findVariable(node.targets.get(i), node.targetsQuoted.get(i));
            target.checkCanAssign();
            target.setValue(convertFetchedValue(columnTypes, i, row[i]));
        }
    }

    private static Value convertFetchedValue(int[] columnTypes, int index, Value value) {
        if (value == null)
            return ValueNull.INSTANCE;
        if (columnTypes != null && index < columnTypes.length && columnTypes[index] != Value.UNKNOWN
                && columnTypes[index] != Value.NULL)
            return value.convertTo(columnTypes[index]);
        return value;
    }

    // CLOSE c
    private void executeCloseCursor(CloseCursor node) {
        PlpgSqlVariable variable = findCursorVariable(node.name, node.nameQuoted);
        if (variable.isCursor()) {
            variable.getCursorState().close();
            return;
        }
        Value value = variable.getValue();
        if (value != null && value != ValueNull.INSTANCE) {
            String portalName = value.getString();
            if (portalName != null)
                getPortalMap().remove(portalName);
        }
    }

    // FOR rec IN c LOOP：从游标当前位置开始逐行取，取完后游标停在最后一行之后
    private void executeForCursor(For node) {
        PlpgSqlVariable cursorVariable = findCursorVariable(node.cursorName,
                node.cursorNameQuoted);
        PlpgSqlCursor cursor = getCursorState(cursorVariable, node.cursorName);
        if (!cursor.isOpen())
            throw PlpgSqlException.raise("34000", "cursor " + node.cursorName + " is not open");
        PlpgSqlScope loopScope = new PlpgSqlScope(scope);
        loopScope.setLabel(node.label);
        PlpgSqlScope oldScope = scope;
        scope = loopScope;
        try {
            PlpgSqlVariable target = scope.findVariable(node.variable, node.variableQuoted);
            if (target == null) {
                target = new PlpgSqlVariable(node.variable, node.variableQuoted, false, false);
                target.setRecord(true);
                loopScope.declare(target);
            } else if (!target.isRecord()) {
                throw PlpgSqlException.raise("42804", "FOR loop target " + node.variable
                        + " must be a record or row variable");
            }
            boolean iterated = false;
            while (true) {
                cursor.move("NEXT", 1);
                Value[] row = cursor.currentRow();
                if (row == null)
                    break;
                iterated = true;
                target.checkCanAssign();
                target.assignRecord(cursor.getColumnNames(), cursor.getColumnTypes(), row);
                try {
                    executeStatements(node.statements);
                } catch (ContinueSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label))
                        continue;
                    throw signal;
                } catch (ExitSignal signal) {
                    if (matchesLoopLabel(node.label, signal.label)) {
                        setFound(iterated);
                        return;
                    }
                    throw signal;
                }
            }
            setFound(iterated);
        } finally {
            scope = oldScope;
        }
    }

    private int evalForBound(String expression) {
        Value value = sqlExecutor.evalExpression(expression, scope);
        if (value == null || value == ValueNull.INSTANCE)
            throw PlpgSqlException.raise("22004", "FOR loop bound cannot be NULL");
        return value.getInt();
    }

    private static boolean matchesLoopLabel(String loopLabel, String signalLabel) {
        if (signalLabel == null)
            return true;
        return loopLabel != null && loopLabel.equalsIgnoreCase(signalLabel);
    }

    private void executeExit(Exit node) {
        if (node.condition != null && !evalCondition(node.condition))
            return;
        throw new ExitSignal(node.label);
    }

    private void executeContinue(Continue node) {
        if (node.condition != null && !evalCondition(node.condition))
            return;
        throw new ContinueSignal(node.label);
    }

    private void executeRaise(Raise node) {
        if (node.level == null) {
            // RAISE; 在EXCEPTION handler里重新抛出当前异常
            if (currentException == null)
                throw PlpgSqlException.raise("0Z002", "RAISE without active exception");
            throw currentException;
        }
        String message = formatMessage(node);
        String level = node.level.toUpperCase();
        if ("EXCEPTION".equals(level)) {
            String sqlState = node.sqlState == null ? PlpgSqlException.DEFAULT_SQL_STATE
                    : node.sqlState;
            throw new PlpgSqlException(sqlState, message);
        }
        // NOTICE/WARNING/INFO/LOG/DEBUG只输出，不中断执行
        logger.info("{}:  {}", level, message);
    }

    private String formatMessage(Raise node) {
        String message = node.message == null ? "" : node.message;
        StringBuilder buff = new StringBuilder(message.length());
        int argIndex = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '%') {
                if (i + 1 < message.length() && message.charAt(i + 1) == '%') {
                    buff.append('%');
                    i++;
                } else if (argIndex < node.arguments.size()) {
                    String argument = node.arguments.get(argIndex++);
                    String recordString = formatRecordArgument(argument);
                    if (recordString != null) {
                        buff.append(recordString);
                    } else {
                        Value value = sqlExecutor.evalExpression(argument, scope);
                        buff.append(valueToString(value));
                    }
                } else {
                    throw PlpgSqlException.raise("22023",
                            "too few parameters specified for RAISE");
                }
            } else {
                buff.append(c);
            }
        }
        if (argIndex < node.arguments.size())
            throw PlpgSqlException.raise("22023", "too many parameters specified for RAISE");
        return buff.toString();
    }

    // RAISE的参数是record变量时输出整行(字段值,字段值)
    private String formatRecordArgument(String argument) {
        PlpgSqlVariable variable = scope.findVariable(argument.trim(), false);
        if (variable != null && variable.isRecord())
            return variable.getRecordString();
        return null;
    }

    private static String valueToString(Value value) {
        if (value == null || value == ValueNull.INSTANCE)
            return "<NULL>";
        return value.getString();
    }

    private PlpgSqlVariable findVariable(String name, boolean quoted) {
        PlpgSqlVariable variable = scope.findVariable(name, quoted);
        if (variable == null)
            throw PlpgSqlException.raise("42703", "variable " + name + " does not exist");
        return variable;
    }
}
