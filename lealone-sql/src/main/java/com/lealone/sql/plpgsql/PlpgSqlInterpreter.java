/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.plpgsql.PlpgSqlAst.Assignment;
import com.lealone.sql.plpgsql.PlpgSqlAst.Block;
import com.lealone.sql.plpgsql.PlpgSqlAst.Branch;
import com.lealone.sql.plpgsql.PlpgSqlAst.Continue;
import com.lealone.sql.plpgsql.PlpgSqlAst.Declaration;
import com.lealone.sql.plpgsql.PlpgSqlAst.Exit;
import com.lealone.sql.plpgsql.PlpgSqlAst.For;
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

/**
 * PL/pgSQL语法树的解释执行器。
 * 每个函数调用创建一个实例，内部使用嵌套session执行SQL，
 * 变量保存在PlpgSqlScope作用域链里。
 */
public class PlpgSqlInterpreter {

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
    private final Block body;
    private final int returnType;
    private final PlpgSqlSqlExecutor sqlExecutor;

    private PlpgSqlScope scope;
    // FOUND魔法变量，DML/PERFORM/SELECT INTO之后由解释器设置
    private PlpgSqlVariable foundVariable;
    // 最近一条SQL语句处理的行数，GET DIAGNOSTICS ROW_COUNT用
    private long lastRowCount;
    // 正在被EXCEPTION handler处理的异常，RAISE;用它重新抛出
    private RuntimeException currentException;

    public PlpgSqlInterpreter(ServerSession session, Block body, int returnType) {
        this.session = session;
        this.body = body;
        this.returnType = returnType;
        this.sqlExecutor = new PlpgSqlSqlExecutor(session);
    }

    /**
     * 执行函数体，返回RETURN语句的值；没有RETURN时返回NULL。
     */
    public Value execute(String[] parameterNames, String[] parameterTypes,
            Value[] parameterValues) {
        PlpgSqlScope parameterScope = new PlpgSqlScope(null);
        scope = parameterScope;
        try {
            declareParameters(parameterNames, parameterTypes, parameterValues);
            declareFoundVariable(parameterScope);
            try {
                executeBlock(body);
                // 非void函数执行到结尾还没有RETURN，PostgreSQL会报错
                if (returnType != Value.UNKNOWN && returnType != Value.NULL)
                    throw PlpgSqlException.raise("2F005",
                            "control reached end of function without RETURN");
                return ValueNull.INSTANCE;
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
        for (int i = 0; i < parameterNames.length; i++) {
            PlpgSqlVariable variable = new PlpgSqlVariable(parameterNames[i], false, false, false);
            int type = Value.UNKNOWN;
            if (parameterTypes != null && i < parameterTypes.length)
                type = resolveType(parameterTypes[i]);
            variable.setType(type);
            Value value = parameterValues != null && i < parameterValues.length
                    ? parameterValues[i]
                    : ValueNull.INSTANCE;
            variable.setInitialValue(value);
            scope.declare(variable);
        }
    }

    private void executeBlock(Block block) {
        PlpgSqlScope blockScope = new PlpgSqlScope(scope);
        scope = blockScope;
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
                if (!handleException(block, e))
                    throw e;
            } catch (DbException e) {
                if (!handleException(block, e))
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
            variable.setType(resolveType(declaration.typeName));
            if (declaration.constant && declaration.defaultExpression == null)
                throw PlpgSqlException.raise("42601",
                        "constant " + declaration.name + " must have a default value");
            // 默认值表达式在变量加入作用域之前求值，这样同名变量可以引用外层的
            Value value = declaration.defaultExpression == null ? ValueNull.INSTANCE
                    : sqlExecutor.evalExpression(declaration.defaultExpression, scope);
            scope.declare(variable);
            variable.setInitialValue(value);
        }
    }

    private int resolveType(String typeName) {
        try {
            return sqlExecutor.resolveType(typeName);
        } catch (DbException e) {
            throw e;
        }
    }

    // 注意：这里没有实现PostgreSQL的隐式子事务回滚，handler执行前不会撤销块的DML修改
    private boolean handleException(Block block, RuntimeException e) {
        if (block.handlers.isEmpty())
            return false;
        String sqlState = PlpgSqlCondition.getSqlState(e);
        for (Handler handler : block.handlers) {
            if (PlpgSqlCondition.matches(handler.conditions, sqlState)) {
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
        } else if (node instanceof NullStatement) {
            // NULL语句什么也不做
        } else {
            throw DbException
                    .getInternalError("unknown PL/pgSQL node: " + node.getClass().getName());
        }
    }

    private void executeAssignment(Assignment node) {
        PlpgSqlVariable variable = findVariable(node.target, node.targetQuoted);
        variable.checkCanAssign();
        Value value = sqlExecutor.evalExpression(node.expression, scope);
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
        if (node.expression == null) {
            if (returnType != Value.UNKNOWN && returnType != Value.NULL)
                throw PlpgSqlException.raise("2F005",
                        "RETURN; cannot be used in a function returning a value");
            throw new ReturnSignal(ValueNull.INSTANCE);
        }
        if (returnType == Value.NULL)
            throw PlpgSqlException.raise("2F005",
                    "RETURN cannot have a parameter in function with void return type");
        Value value = sqlExecutor.evalExpression(node.expression, scope);
        if (returnType != Value.UNKNOWN)
            value = value.convertTo(returnType);
        throw new ReturnSignal(value);
    }

    private void executeSql(SqlExec node) {
        PlpgSqlSqlExecutor.SelectInto selectInto = PlpgSqlSqlExecutor.splitSelectInto(node.sql);
        if (selectInto != null) {
            executeSelectInto(selectInto);
            return;
        }
        String firstWord = firstWord(node.sql);
        int count = sqlExecutor.execute(node.sql, scope);
        // INSERT/UPDATE/DELETE/MERGE之后更新FOUND和ROW_COUNT
        if (isDml(firstWord)) {
            lastRowCount = count;
            setFound(count > 0);
        }
    }

    private static boolean isDml(String word) {
        return "INSERT".equalsIgnoreCase(word) || "UPDATE".equalsIgnoreCase(word)
                || "DELETE".equalsIgnoreCase(word) || "MERGE".equalsIgnoreCase(word)
                || "REPLACE".equalsIgnoreCase(word) || "UPSERT".equalsIgnoreCase(word);
    }

    private void executeSelectInto(PlpgSqlSqlExecutor.SelectInto selectInto) {
        List<Value[]> rows = sqlExecutor.query(selectInto.query, scope, 2);
        lastRowCount = rows.size();
        if (rows.isEmpty()) {
            if (selectInto.strict)
                throw PlpgSqlException.noDataFound();
            for (String target : selectInto.targets) {
                PlpgSqlVariable variable = findVariable(target, false);
                variable.checkCanAssign();
                variable.setValue(ValueNull.INSTANCE);
            }
            setFound(false);
            return;
        }
        if (selectInto.strict && rows.size() > 1)
            throw PlpgSqlException.tooManyRows();
        Value[] row = rows.get(0);
        if (row.length != selectInto.targets.length)
            throw PlpgSqlException.raise("42804",
                    "SELECT INTO has " + row.length + " columns but " + selectInto.targets.length
                            + " variables");
        for (int i = 0; i < row.length; i++) {
            PlpgSqlVariable variable = findVariable(selectInto.targets[i], false);
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
            for (long i = start; delta > 0 ? i <= end : i >= end; i += delta) {
                variable.setValue(ValueInt.get((int) i));
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
        System.out.println(level + ":  " + message);
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
                    Value value = sqlExecutor.evalExpression(node.arguments.get(argIndex++), scope);
                    buff.append(valueToString(value));
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
