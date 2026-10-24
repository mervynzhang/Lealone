/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.schema;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.common.util.Utils;
import com.lealone.db.Constants;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.api.Trigger;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.row.Row;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.util.SourceCompiler;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;

/**
 * A trigger is created using the statement
 * CREATE TRIGGER
 *
 * @author H2 Group
 * @author zhh
 */
public class TriggerObject extends SchemaObjectBase {

    /**
     * The default queue size.
     */
    public static final int DEFAULT_QUEUE_SIZE = 1024;

    private boolean insteadOf;
    private boolean before;
    private int typeMask;
    private boolean rowBased;
    private boolean onRollback;
    // TODO trigger: support queue and noWait = false as well
    private int queueSize = DEFAULT_QUEUE_SIZE;
    private boolean noWait;
    private Table table;
    // PostgreSQL的EXECUTE PROCEDURE函数触发器，和Java类触发器二选一
    private String triggerFunctionName;
    private String triggerClassName;
    private String triggerSource;
    private Trigger triggerCallback;

    public TriggerObject(Schema schema, int id, String name, Table table) {
        super(schema, id, name);
        this.table = table;
        setTemporary(table.isTemporary());
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.TRIGGER;
    }

    public void setBefore(boolean before) {
        this.before = before;
    }

    public void setInsteadOf(boolean insteadOf) {
        this.insteadOf = insteadOf;
    }

    private synchronized void load() {
        // 函数触发器由fireRow直接调用SqlFunction，不需要加载Java回调
        if (triggerFunctionName != null)
            return;
        if (triggerCallback != null) {
            return;
        }
        try {
            ServerSession sysSession = database.getSystemSession();
            Connection c2 = sysSession.createNestedConnection(false);
            Object obj;
            if (triggerClassName != null) {
                obj = Utils.newInstance(triggerClassName);
            } else {
                obj = loadFromSource();
            }
            triggerCallback = (Trigger) obj;
            triggerCallback.init(c2, getSchema().getName(), getName(), table.getName(), before,
                    typeMask);
        } catch (Throwable e) {
            // try again later
            triggerCallback = null;
            throw DbException.get(ErrorCode.ERROR_CREATING_TRIGGER_OBJECT_3, e, getName(),
                    triggerClassName != null ? triggerClassName : "..source..", e.toString());
        }
    }

    private Trigger loadFromSource() {
        SourceCompiler compiler = database.getCompiler();
        synchronized (compiler) {
            String fullClassName = Constants.USER_PACKAGE + ".trigger." + getName();
            compiler.setSource(fullClassName, triggerSource);
            try {
                Method m = compiler.getMethod(fullClassName);
                if (m.getParameterTypes().length > 0) {
                    throw new IllegalStateException("No parameters are allowed for a trigger");
                }
                return (Trigger) m.invoke(null);
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1, e, triggerSource);
            }
        }
    }

    /**
     * Set the trigger class name and load the class if possible.
     *
     * @param triggerClassName the name of the trigger class
     * @param force whether exceptions (due to missing class or access rights)
     *            should be ignored
     */
    public void setTriggerClassName(String triggerClassName, boolean force) {
        this.setTriggerAction(triggerClassName, null, force);
    }

    /**
     * Set the trigger source code and compile it if possible.
     *
     * @param source the source code of a method returning a {@link Trigger}
     * @param force whether exceptions (due to syntax error)
     *            should be ignored
     */
    public void setTriggerSource(String source, boolean force) {
        this.setTriggerAction(null, source, force);
    }

    private void setTriggerAction(String triggerClassName, String source, boolean force) {
        this.triggerClassName = triggerClassName;
        this.triggerSource = source;
        try {
            load();
        } catch (DbException e) {
            if (!force) {
                throw e;
            }
        }
    }

    /**
     * Call the trigger class if required. This method does nothing if the
     * trigger is not defined for the given action. This method is called before
     * or after any rows have been processed, once for each statement.
     *
     * @param session the session
     * @param type the trigger type
     * @param beforeAction if this method is called before applying the changes
     */
    public void fire(ServerSession session, int type, boolean beforeAction) {
        if (rowBased || before != beforeAction || (typeMask & type) == 0) {
            return;
        }
        // 函数触发器只支持FOR EACH ROW，语句级路径没有回调
        if (triggerFunctionName != null)
            return;
        load();
        Connection c2 = session.createNestedConnection(false);
        boolean old = false;
        if (type != Trigger.SELECT) {
            old = session.setCommitOrRollbackDisabled(true);
        }
        long identity = session.getLastScopeIdentity();
        try {
            triggerCallback.fire(c2, null, null);
        } catch (Throwable e) {
            throw DbException.get(ErrorCode.ERROR_EXECUTING_TRIGGER_3, e, getName(),
                    triggerClassName != null ? triggerClassName : "..source..", e.toString());
        } finally {
            session.setLastScopeIdentity(identity);
            if (type != Trigger.SELECT) {
                session.setCommitOrRollbackDisabled(old);
            }
        }
    }

    private static Object[] convertToObjectList(Row row) {
        if (row == null) {
            return null;
        }
        int len = row.getColumnCount();
        Object[] list = new Object[len];
        for (int i = 0; i < len; i++) {
            list[i] = row.getValue(i).getObject();
        }
        return list;
    }

    /**
     * Call the fire method of the user-defined trigger class if required. This
     * method does nothing if the trigger is not defined for the given action.
     * This method is called before or after a row is processed, possibly many
     * times for each statement.
     *
     * @param session the session
     * @param oldRow the old row
     * @param newRow the new row
     * @param beforeAction true if this method is called before the operation is
     *            applied
     * @param rollback when the operation occurred within a rollback
     * @return true if no further action is required (for 'instead of' triggers)
     */
    public boolean fireRow(ServerSession session, Row oldRow, Row newRow, boolean beforeAction,
            boolean rollback) {
        if (!rowBased || before != beforeAction) {
            return false;
        }
        if (rollback && !onRollback) {
            return false;
        }
        load();
        Object[] oldList;
        Object[] newList;
        boolean fire = false;
        if ((typeMask & Trigger.INSERT) != 0) {
            if (oldRow == null && newRow != null) {
                fire = true;
            }
        }
        if ((typeMask & Trigger.UPDATE) != 0) {
            if (oldRow != null && newRow != null) {
                fire = true;
            }
        }
        if ((typeMask & Trigger.DELETE) != 0) {
            if (oldRow != null && newRow == null) {
                fire = true;
            }
        }
        if (!fire) {
            return false;
        }
        if (triggerFunctionName != null)
            return fireFunctionRow(session, oldRow, newRow, beforeAction);
        oldList = convertToObjectList(oldRow);
        newList = convertToObjectList(newRow);
        Object[] newListBackup;
        if (before && newList != null) {
            newListBackup = new Object[newList.length];
            System.arraycopy(newList, 0, newListBackup, 0, newList.length);
        } else {
            newListBackup = null;
        }
        Connection c2 = session.createNestedConnection(false);
        boolean old = session.isAutoCommit();
        boolean oldDisabled = session.setCommitOrRollbackDisabled(true);
        long identity = session.getLastScopeIdentity();
        try {
            session.setAutoCommit(false);
            triggerCallback.fire(c2, oldList, newList);
            if (newListBackup != null) {
                for (int i = 0; i < newList.length; i++) {
                    Object o = newList[i];
                    if (o != newListBackup[i]) {
                        Value v = DataType.convertToValue(session, o, Value.UNKNOWN);
                        newRow.setValue(i, v);
                    }
                }
            }
        } catch (Exception e) {
            if (onRollback) {
                // ignore
            } else {
                throw DbException.convert(e);
            }
        } finally {
            session.setLastScopeIdentity(identity);
            session.setCommitOrRollbackDisabled(oldDisabled);
            session.setAutoCommit(old);
        }
        return insteadOf;
    }

    private SqlFunction getTriggerFunction(ServerSession session) {
        FunctionAlias function = getSchema().findFunction(session, triggerFunctionName);
        if (!(function instanceof SqlFunction) || !((SqlFunction) function).isTriggerReturn()) {
            throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1,
                    "trigger function " + triggerFunctionName);
        }
        return (SqlFunction) function;
    }

    private static Value[] convertToValueList(Row row) {
        if (row == null)
            return null;
        Value[] values = new Value[row.getColumnCount()];
        for (int i = 0; i < values.length; i++)
            values[i] = row.getValue(i);
        return values;
    }

    // 触发器函数的嵌套深度上限
    private static final int MAX_TRIGGER_DEPTH = 32;

    // 执行PL/pgSQL触发器函数；RETURN NULL取消BEFORE行操作，RETURN NEW/OLD写回新行
    private boolean fireFunctionRow(ServerSession session, Row oldRow, Row newRow,
            boolean beforeAction) {
        SqlFunction function = getTriggerFunction(session);
        Column[] columns = table.getColumns();
        String[] names = new String[columns.length];
        int[] types = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            names[i] = columns[i].getName();
            types[i] = columns[i].getType();
        }
        Value[] oldValues = convertToValueList(oldRow);
        Value[] newValues = convertToValueList(newRow);
        // 触发器函数里再改同一张表会无限递归, 这里加深度上限, 免得直接StackOverflowError
        if (session.increaseTriggerDepth() > MAX_TRIGGER_DEPTH) {
            session.decreaseTriggerDepth();
            throw DbException.get(ErrorCode.GENERAL_ERROR_1,
                    "trigger recursion depth exceeds " + MAX_TRIGGER_DEPTH);
        }
        boolean oldAutoCommit = session.isAutoCommit();
        boolean oldDisabled = session.setCommitOrRollbackDisabled(true);
        long identity = session.getLastScopeIdentity();
        boolean cancel = false;
        try {
            session.setAutoCommit(false);
            Value result = function.executeTrigger(session, names, types, oldValues, newValues);
            if (beforeAction && newRow != null) {
                if (result == null || result == ValueNull.INSTANCE) {
                    cancel = true;
                } else if (result.getType() != Value.ARRAY) {
                    throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                            "trigger function must return NEW, OLD or NULL");
                } else {
                    Value[] row = ((ValueArray) result).getList();
                    if (row.length != newRow.getColumnCount()) {
                        throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                                "trigger function returns " + row.length + " columns, expected "
                                        + newRow.getColumnCount());
                    }
                    for (int i = 0; i < row.length; i++)
                        newRow.setValue(i, row[i]);
                }
            }
        } catch (Exception e) {
            if (!onRollback)
                throw DbException.convert(e);
        } finally {
            session.decreaseTriggerDepth();
            session.setLastScopeIdentity(identity);
            session.setCommitOrRollbackDisabled(oldDisabled);
            session.setAutoCommit(oldAutoCommit);
        }
        return cancel;
    }

    /**
     * Set the trigger type.
     *
     * @param typeMask the type
     */
    public void setTypeMask(int typeMask) {
        this.typeMask = typeMask;
    }

    public void setRowBased(boolean rowBased) {
        this.rowBased = rowBased;
    }

    public void setQueueSize(int size) {
        this.queueSize = size;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setNoWait(boolean noWait) {
        this.noWait = noWait;
    }

    public boolean isNoWait() {
        return noWait;
    }

    public void setOnRollback(boolean onRollback) {
        this.onRollback = onRollback;
    }

    public String getTypeNameList() {
        StatementBuilder buff = new StatementBuilder();
        if ((typeMask & Trigger.INSERT) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("INSERT");
        }
        if ((typeMask & Trigger.UPDATE) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("UPDATE");
        }
        if ((typeMask & Trigger.DELETE) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("DELETE");
        }
        if ((typeMask & Trigger.SELECT) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("SELECT");
        }
        if (onRollback) {
            buff.appendExceptFirst(", ");
            buff.append("ROLLBACK");
        }
        return buff.toString();
    }

    @Override
    public String getCreateSQL() {
        boolean functionTrigger = triggerFunctionName != null;
        StringBuilder buff = new StringBuilder(
                functionTrigger ? "CREATE TRIGGER " : "CREATE FORCE TRIGGER ");
        buff.append(getSQL());
        if (insteadOf) {
            buff.append(" INSTEAD OF ");
        } else if (before) {
            buff.append(" BEFORE ");
        } else {
            buff.append(" AFTER ");
        }
        buff.append(getTypeNameList());
        buff.append(" ON ").append(table.getSQL());
        if (rowBased) {
            buff.append(" FOR EACH ROW");
        }
        if (functionTrigger) {
            buff.append(" EXECUTE PROCEDURE ")
                    .append(database.quoteIdentifier(triggerFunctionName)).append("()");
        } else {
            if (noWait) {
                buff.append(" NOWAIT");
            } else {
                buff.append(" QUEUE ").append(queueSize);
            }
            if (triggerClassName != null) {
                buff.append(" CALL ").append(database.quoteIdentifier(triggerClassName));
            } else {
                buff.append(" AS ").append(StringUtils.quoteStringSQL(triggerSource));
            }
        }
        return buff.toString();
    }

    @Override
    public void removeChildrenAndResources(ServerSession session, DbObjectLock lock) {
        table.removeTrigger(this);
        if (triggerCallback != null) {
            try {
                triggerCallback.remove();
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
    }

    /**
     * Get the table of this trigger.
     *
     * @return the table
     */
    public Table getTable() {
        return table;
    }

    /**
     * Check if this is a before trigger.
     *
     * @return true if it is
     */
    public boolean isBefore() {
        return before;
    }

    public void setTriggerFunctionName(String triggerFunctionName) {
        this.triggerFunctionName = triggerFunctionName;
    }

    public String getTriggerFunctionName() {
        return triggerFunctionName;
    }

    /**
     * Get the trigger class name.
     *
     * @return the class name
     */
    public String getTriggerClassName() {
        return triggerClassName;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    /**
     * Close the trigger.
     */
    public void close() throws SQLException {
        if (triggerCallback != null) {
            triggerCallback.close();
        }
    }

    /**
     * Check whether this is a select trigger.
     *
     * @return true if it is
     */
    public boolean isSelectTrigger() {
        return (typeMask & Trigger.SELECT) != 0;
    }

    @Override
    public void invalidate() {
        table = null;
        triggerFunctionName = null;
        triggerClassName = null;
        triggerSource = null;
        triggerCallback = null;
        super.invalidate();
    }
}
