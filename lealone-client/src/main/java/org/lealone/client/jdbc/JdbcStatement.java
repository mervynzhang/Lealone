/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.lealone.client.command.ClientSQLCommand;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.TraceObjectType;
import org.lealone.common.util.Utils;
import org.lealone.db.Command;
import org.lealone.db.ConnectionInfo;
import org.lealone.db.SysProperties;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.async.Future;
import org.lealone.db.result.Result;
import org.lealone.db.session.Session;
import org.lealone.sql.SQLCommand;

/**
 * Represents a statement.
 * 
 * @author H2 Group
 * @author zhh
 */
public class JdbcStatement extends JdbcWrapper implements Statement {

    protected JdbcConnection conn;
    protected final Session session;
    protected final int resultSetType;
    protected final int resultSetConcurrency;
    protected final boolean closedByResultSet;
    protected JdbcResultSet resultSet;
    protected int maxRows;
    protected int fetchSize = SysProperties.SERVER_RESULT_SET_FETCH_SIZE;
    protected int updateCount;
    private Command executingCommand;
    private ArrayList<String> batchCommands;
    private boolean escapeProcessing = true;

    JdbcStatement(JdbcConnection conn, int id, int resultSetType, int resultSetConcurrency) {
        this(conn, id, resultSetType, resultSetConcurrency, false);
    }

    JdbcStatement(JdbcConnection conn, int id, int resultSetType, int resultSetConcurrency,
            boolean closedByResultSet) {
        this.conn = conn;
        this.session = conn.getSession();
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.closedByResultSet = closedByResultSet;
        this.trace = conn.getTrace(TraceObjectType.STATEMENT, id);
    }

    /**
     * Executes a query (select statement) and returns the result set.
     * If another result set exists for this statement, this will be closed
     * (even if this statement fails).
     *
     * @param sql the SQL statement to execute
     * @return the result set
     */
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return executeQueryInternal(sql, false).get(this);
    }

    public Future<ResultSet> executeQueryAsync(String sql) {
        return executeQueryInternal(sql, true);
    }

    private JdbcAsyncCallback<ResultSet> executeQueryInternal(String sql, boolean async) {
        int id = getNextTraceId(TraceObjectType.RESULT_SET);
        if (isDebugEnabled()) {
            debugCodeAssign(TraceObjectType.RESULT_SET, id,
                    "executeQuery" + (async ? "Async" : "") + "(" + quote(sql) + ")");
        }
        JdbcAsyncCallback<ResultSet> ac = new JdbcAsyncCallback<>();
        try {
            SQLCommand command = createSQLCommand(sql, false);
            command.executeQuery(maxRows, isScrollable()).onComplete(ar -> {
                setExecutingStatement(null);
                if (ar.isSucceeded()) {
                    Result r = ar.getResult();
                    JdbcResultSet resultSet = new JdbcResultSet(JdbcStatement.this, r, id);
                    resultSet.setCommand(command); // 关闭结果集时再关闭
                    ac.setAsyncResult(resultSet);
                } else {
                    setAsyncResult(ac, ar.getCause());
                }
            });
        } catch (Throwable t) {
            setAsyncResult(ac, t);
        }
        return ac;
    }

    protected boolean isScrollable() {
        return resultSetType != ResultSet.TYPE_FORWARD_ONLY;
    }

    protected boolean isUpdatable() {
        return resultSetConcurrency == ResultSet.CONCUR_UPDATABLE;
    }

    protected boolean isClosedByResultSet() {
        return closedByResultSet;
    }

    private SQLCommand createSQLCommand(String sql, boolean prepared) throws SQLException {
        checkAndClose();
        sql = JdbcConnection.translateSQL(sql, escapeProcessing);
        SQLCommand command = session.createSQLCommand(sql, fetchSize, prepared);
        if (!prepared)
            setExecutingStatement(command);
        return command;
    }

    /**
     * Executes a statement (insert, update, delete, create, drop)
     * and returns the update count.
     * If another result set exists for this statement, this will be closed
     * (even if this statement fails).
     *
     * If auto commit is on, this statement will be committed.
     * If the statement is a DDL statement (create, drop, alter) and does not
     * throw an exception, the current transaction (if any) is committed after
     * executing the statement.
     *
     * @param sql the SQL statement
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public int executeUpdate(String sql) throws SQLException {
        if (isDebugEnabled()) {
            debugCodeCall("executeUpdate", sql);
        }
        return executeUpdateSync(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls executeUpdate(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param autoGeneratedKeys ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("executeUpdate(" + quote(sql) + ", " + autoGeneratedKeys + ");");
        }
        return executeUpdateSync(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls executeUpdate(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param columnIndexes ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("executeUpdate(" + quote(sql) + ", " + quoteIntArray(columnIndexes) + ");");
        }
        return executeUpdateSync(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls executeUpdate(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param columnNames ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("executeUpdate(" + quote(sql) + ", " + quoteArray(columnNames) + ");");
        }
        return executeUpdateSync(sql);
    }

    public Future<Integer> executeUpdateAsync(String sql) {
        if (isDebugEnabled()) {
            debugCodeCall("executeUpdateAsync", sql);
        }
        return executeUpdateInternal(sql);
    }

    private int executeUpdateSync(String sql) throws SQLException {
        return executeUpdateInternal(sql).get(this);
    }

    private JdbcAsyncCallback<Integer> executeUpdateInternal(String sql) {
        JdbcAsyncCallback<Integer> ac = new JdbcAsyncCallback<>();
        try {
            SQLCommand command = createSQLCommand(sql, false);
            command.executeUpdate().onComplete(ar -> {
                setExecutingStatement(null);
                command.close();
                if (ar.isFailed()) {
                    setAsyncResult(ac, ar.getCause());
                } else {
                    ac.setAsyncResult(ar);
                }
            });
        } catch (Throwable t) {
            setAsyncResult(ac, t);
        }
        return ac;
    }

    /**
     * Executes an arbitrary statement. If another result set exists for this
     * statement, this will be closed (even if this statement fails).
     *
     * If the statement is a create or drop and does not throw an exception, the
     * current transaction (if any) is committed after executing the statement.
     * If auto commit is on, and the statement is not a select, this statement
     * will be committed.
     *
     * @param sql the SQL statement to execute
     * @return true if a result set is available, false if not
     */
    @Override
    public boolean execute(String sql) throws SQLException {
        debugCodeCall("execute", sql);
        return executeInternal(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls execute(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param autoGeneratedKeys ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("execute(" + quote(sql) + ", " + autoGeneratedKeys + ");");
        }
        return executeInternal(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls execute(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param columnIndexes ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("execute(" + quote(sql) + ", " + quoteIntArray(columnIndexes) + ");");
        }
        return executeInternal(sql);
    }

    /**
     * Executes a statement and returns the update count.
     * This method just calls execute(String sql) internally.
     * The method getGeneratedKeys supports at most one columns and row.
     *
     * @param sql the SQL statement
     * @param columnNames ignored
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback)
     * @throws SQLException if a database error occurred or a
     *         select statement was executed
     */
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("execute(" + quote(sql) + ", " + quoteArray(columnNames) + ");");
        }
        return executeInternal(sql);
    }

    private boolean executeInternal(String sql) throws SQLException {
        // 禁用这段代码，容易遗漏，比如set命令得用executeUpdate
        // 想在客户端区分哪些sql是查询还是更新语句比较困难，还不如让服务器端来做
        // if (sql != null) {
        // sql = sql.trim();
        // if (!sql.isEmpty()) {
        // char c = Character.toUpperCase(sql.charAt(0));
        // switch (c) {
        // case 'S': // select or show
        // executeQuery(sql);
        // return true;
        // case 'I': // insert
        // case 'U': // update
        // case 'D': // delete or drop
        // case 'C': // create
        // case 'A': // alter
        // case 'M': // merge
        // executeUpdate(sql);
        // return false;
        // default:
        // break;
        // }
        // }
        // }
        JdbcAsyncCallback<Boolean> ac = new JdbcAsyncCallback<>();
        try {
            SQLCommand command = createSQLCommand(sql, true);
            command.prepare(false).onComplete(ar -> {
                if (ar.isSucceeded())
                    executeInternal(ac, command, false);
                else
                    setAsyncResult(ac, ar.getCause());
            });
        } catch (Throwable t) {
            setAsyncResult(ac, t);
        }
        return ac.get(this).booleanValue();
    }

    void executeInternal(JdbcAsyncCallback<Boolean> ac, SQLCommand command, boolean prepared) {
        setExecutingStatement(command);
        if (command.isQuery()) {
            command.executeQuery(maxRows, isScrollable()).onComplete(ar -> {
                setExecutingStatement(null);
                if (ar.isFailed()) {
                    command.close();
                    setAsyncResult(ac, ar.getCause());
                    return;
                }
                Result result = ar.getResult();
                int id = getNextTraceId(TraceObjectType.RESULT_SET);
                resultSet = new JdbcResultSet(JdbcStatement.this, result, id);
                resultSet.setCommand(command);
                // 不能立即调用command.close()，当结果集还没获取完时还需要用command获取下一批记录
                ac.setAsyncResult(true);
            });
        } else {
            command.executeUpdate().onComplete(ar -> {
                // 设置完后再调用close，否则有可能当前语句提前关闭了
                setExecutingStatement(null);
                if (!prepared)
                    command.close();
                if (ar.isFailed()) {
                    setAsyncResult(ac, ar.getCause());
                } else {
                    updateCount = ar.getResult();
                    ac.setAsyncResult(false);
                }
            });
        }
    }

    /**
     * Returns the last result set produces by this statement.
     *
     * @return the result set
     */
    @Override
    public ResultSet getResultSet() throws SQLException {
        try {
            checkClosed();
            if (resultSet != null) {
                int id = resultSet.getTraceId();
                debugCodeAssign(TraceObjectType.RESULT_SET, id, "getResultSet()");
            } else {
                debugCodeCall("getResultSet");
            }
            return resultSet;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the last update count of this statement.
     *
     * @return the update count (number of row affected by an insert,
     *         update or delete, or 0 if no rows or the statement was a
     *         create, drop, commit or rollback; -1 if the statement was a select).
     * @throws SQLException if this object is closed or invalid
     */
    @Override
    public int getUpdateCount() throws SQLException {
        try {
            debugCodeCall("getUpdateCount");
            checkClosed();
            return updateCount;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Adds a statement to the batch.
     *
     * @param sql the SQL statement
     */
    @Override
    public void addBatch(String sql) throws SQLException {
        try {
            debugCodeCall("addBatch", sql);
            checkClosed();
            sql = JdbcConnection.translateSQL(sql, escapeProcessing);
            if (batchCommands == null) {
                batchCommands = Utils.newSmallArrayList();
            }
            batchCommands.add(sql);
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Clears the batch.
     */
    @Override
    public void clearBatch() throws SQLException {
        try {
            debugCodeCall("clearBatch");
            checkClosed();
            batchCommands = null;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Executes the batch.
     * If one of the batched statements fails, this database will continue.
     *
     * @return the array of update counts
     */
    @Override
    public int[] executeBatch() throws SQLException {
        debugCodeCall("executeBatch");
        ArrayList<String> batchCommands = this.batchCommands;
        this.batchCommands = null;
        JdbcAsyncCallback<int[]> ac = new JdbcAsyncCallback<>();
        try {
            checkAndClose();
            if (batchCommands == null || batchCommands.isEmpty()) {
                return new int[0];
            }
            ConnectionInfo ci = session.getConnectionInfo();
            if (ci != null && ci.isRemote()) {
                ClientSQLCommand command = (ClientSQLCommand) session.createSQLCommand(null, -1, false);
                setExecutingStatement(command);
                command.executeBatchSQLCommands(batchCommands).onComplete(ar -> {
                    setExecutingStatement(null);
                    if (ar.isSucceeded()) {
                        ac.setAsyncResult(ar.getResult());
                    } else {
                        ac.setAsyncResult(ar.getCause());
                    }
                });
            } else {
                int size = batchCommands.size();
                int[] result = new int[size];
                AtomicReference<SQLException> nextRef = new AtomicReference<>();
                AtomicBoolean error = new AtomicBoolean(false);
                AtomicInteger counter = new AtomicInteger(size);
                for (int i = 0; i < size; i++) {
                    int index = i;
                    String sql = batchCommands.get(i);
                    executeUpdateAsync(sql).onComplete(ar -> {
                        if (ar.isSucceeded()) {
                            result[index] = ar.getResult();
                        } else {
                            result[index] = Statement.EXECUTE_FAILED;
                            error.set(true);
                            SQLException e = logAndConvert(DbException.convert(ar.getCause()));
                            SQLException next = nextRef.get();
                            if (next == null) {
                                nextRef.set(e);
                            } else {
                                e.setNextException(next);
                                nextRef.set(e);
                            }
                        }
                        if (counter.decrementAndGet() == 0) {
                            if (error.get()) {
                                ac.setAsyncResult(new JdbcBatchUpdateException(nextRef.get(), result));
                            } else {
                                ac.setAsyncResult(result);
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            setAsyncResult(ac, t);
        }
        return ac.get(this);

    }

    /**
     * Closes this statement.
     * All result sets that where created by this statement
     * become invalid after calling this method.
     */
    @Override
    public void close() throws SQLException {
        try {
            debugCodeCall("close");
            closeOldResultSet();
            if (conn != null) {
                conn = null;
            }
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns whether this statement is closed.
     *
     * @return true if the statement is closed
     */
    @Override
    public boolean isClosed() throws SQLException {
        try {
            debugCodeCall("isClosed");
            return conn == null;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Returns the connection that created this object.
     *
     * @return the connection
     */
    @Override
    public JdbcConnection getConnection() {
        debugCodeCall("getConnection");
        return conn;
    }

    /**
     * Gets the first warning reported by calls on this object.
     * This driver does not support warnings, and will always return null.
     *
     * @return null
     */
    @Override
    public SQLWarning getWarnings() throws SQLException {
        try {
            debugCodeCall("getWarnings");
            checkClosed();
            return null;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Clears all warnings. As this driver does not support warnings,
     * this call is ignored.
     */
    @Override
    public void clearWarnings() throws SQLException {
        try {
            debugCodeCall("clearWarnings");
            checkClosed();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the name of the cursor. This call is ignored.
     *
     * @param name ignored
     * @throws SQLException if this object is closed
     */
    @Override
    public void setCursorName(String name) throws SQLException {
        try {
            debugCodeCall("setCursorName", name);
            checkClosed();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the fetch direction.
     * This call is ignored by this driver.
     *
     * @param direction ignored
     * @throws SQLException if this object is closed
     */
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        try {
            debugCodeCall("setFetchDirection", direction);
            checkClosed();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the fetch direction.
     *
     * @return FETCH_FORWARD
     * @throws SQLException if this object is closed
     */
    @Override
    public int getFetchDirection() throws SQLException {
        try {
            debugCodeCall("getFetchDirection");
            checkClosed();
            return ResultSet.FETCH_FORWARD;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the maximum number of rows for a ResultSet.
     *
     * @return the number of rows where 0 means no limit
     * @throws SQLException if this object is closed
     */
    @Override
    public int getMaxRows() throws SQLException {
        try {
            debugCodeCall("getMaxRows");
            checkClosed();
            return maxRows;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the maximum number of rows for a ResultSet.
     *
     * @param maxRows the number of rows where 0 means no limit
     * @throws SQLException if this object is closed
     */
    @Override
    public void setMaxRows(int maxRows) throws SQLException {
        try {
            debugCodeCall("setMaxRows", maxRows);
            checkClosed();
            if (maxRows < 0) {
                throw DbException.getInvalidValueException("maxRows", maxRows);
            }
            this.maxRows = maxRows;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the number of rows suggested to read in one step.
     * This value cannot be higher than the maximum rows (setMaxRows)
     * set by the statement or prepared statement, otherwise an exception
     * is throws. Setting the value to 0 will set the default value.
     * The default value can be changed using the system property
     * lealone.serverResultSetFetchSize.
     *
     * @param rows the number of rows
     * @throws SQLException if this object is closed
     */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        try {
            debugCodeCall("setFetchSize", rows);
            checkClosed();
            if (rows < 0 || (rows > 0 && maxRows > 0 && rows > maxRows)) {
                throw DbException.getInvalidValueException("rows", rows);
            }
            if (rows == 0) {
                rows = SysProperties.SERVER_RESULT_SET_FETCH_SIZE;
            }
            fetchSize = rows;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the number of rows suggested to read in one step.
     *
     * @return the current fetch size
     * @throws SQLException if this object is closed
     */
    @Override
    public int getFetchSize() throws SQLException {
        try {
            debugCodeCall("getFetchSize");
            checkClosed();
            return fetchSize;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the result set concurrency created by this object.
     *
     * @return the concurrency
     */
    @Override
    public int getResultSetConcurrency() throws SQLException {
        try {
            debugCodeCall("getResultSetConcurrency");
            checkClosed();
            return resultSetConcurrency;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the result set holdability.
     *
     * @return the holdability
     */
    @Override
    public int getResultSetHoldability() throws SQLException {
        try {
            debugCodeCall("getResultSetHoldability");
            checkClosed();
            return ResultSet.HOLD_CURSORS_OVER_COMMIT;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the result set type.
     *
     * @return the type
     * @throws SQLException if this object is closed
     */
    @Override
    public int getResultSetType() throws SQLException {
        try {
            debugCodeCall("getResultSetType");
            checkClosed();
            return resultSetType;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the maximum number of bytes for a result set column.
     *
     * @return always 0 for no limit
     * @throws SQLException if this object is closed
     */
    @Override
    public int getMaxFieldSize() throws SQLException {
        try {
            debugCodeCall("getMaxFieldSize");
            checkClosed();
            return 0;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the maximum number of bytes for a result set column.
     * This method does currently do nothing for this driver.
     *
     * @param max the maximum size - ignored
     * @throws SQLException if this object is closed
     */
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        try {
            debugCodeCall("setMaxFieldSize", max);
            checkClosed();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Enables or disables processing or JDBC escape syntax.
     * See also Connection.nativeSQL.
     *
     * @param enable - true (default) or false (no conversion is attempted)
     * @throws SQLException if this object is closed
     */
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        try {
            if (isDebugEnabled()) {
                debugCode("setEscapeProcessing(" + enable + ");");
            }
            checkClosed();
            escapeProcessing = enable;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Cancels a currently running statement.
     * This method must be called from within another
     * thread than the execute method.
     * Operations on large objects are not interrupted,
     * only operations that process many rows.
     *
     * @throws SQLException if this object is closed
     */
    @Override
    public void cancel() throws SQLException {
        try {
            debugCodeCall("cancel");
            checkClosed();
            // executingCommand can be reset by another thread
            Command c = executingCommand;
            try {
                if (c != null) {
                    c.cancel();
                }
            } finally {
                setExecutingStatement(null);
            }
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Gets the current query timeout in seconds.
     * This method will return 0 if no query timeout is set.
     * The result is rounded to the next second.
     * For performance reasons, only the first call to this method
     * will query the database. If the query timeout was changed in another
     * way than calling setQueryTimeout, this method will always return
     * the last value.
     *
     * @return the timeout in seconds
     * @throws SQLException if this object is closed
     */
    @Override
    public int getQueryTimeout() throws SQLException {
        try {
            debugCodeCall("getQueryTimeout");
            checkClosed();
            return conn.getQueryTimeout();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Sets the current query timeout in seconds.
     * Changing the value will affect all statements of this connection.
     * This method does not commit a transaction,
     * and rolling back a transaction does not affect this setting.
     *
     * @param seconds the timeout in seconds - 0 means no timeout, values
     *        smaller 0 will throw an exception
     * @throws SQLException if this object is closed
     */
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        try {
            debugCodeCall("setQueryTimeout", seconds);
            checkClosed();
            if (seconds < 0) {
                throw DbException.getInvalidValueException("seconds", seconds);
            }
            conn.setQueryTimeout(seconds);
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Return a result set that contains the last generated auto-increment key
     * for this connection, if there was one. If no key was generated by the
     * last modification statement, then an empty result set is returned.
     * The returned result set only contains the data for the very last row.
     *
     * @return the result set with one row and one column containing the key
     * @throws SQLException if this object is closed
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        try {
            int id = getNextTraceId(TraceObjectType.RESULT_SET);
            if (isDebugEnabled()) {
                debugCodeAssign(TraceObjectType.RESULT_SET, id, "getGeneratedKeys()");
            }
            checkClosed();
            return conn.getGeneratedKeys(this, id);
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Moves to the next result set - however there is always only one result
     * set. This call also closes the current result set (if there is one).
     * Returns true if there is a next result set (that means - it always
     * returns false).
     *
     * @return false
     * @throws SQLException if this object is closed.
     */
    @Override
    public boolean getMoreResults() throws SQLException {
        try {
            debugCodeCall("getMoreResults");
            checkAndClose();
            return false;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Move to the next result set.
     * This method always returns false.
     *
     * @param current Statement.CLOSE_CURRENT_RESULT,
     *          Statement.KEEP_CURRENT_RESULT,
     *          or Statement.CLOSE_ALL_RESULTS
     * @return false
     */
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        try {
            debugCodeCall("getMoreResults", current);
            switch (current) {
            case Statement.CLOSE_CURRENT_RESULT:
            case Statement.CLOSE_ALL_RESULTS:
                checkAndClose();
                break;
            case Statement.KEEP_CURRENT_RESULT:
                // nothing to do
                break;
            default:
                throw DbException.getInvalidValueException("current", current);
            }
            return false;
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    protected void checkAndClose() throws SQLException {
        checkClosed();
        closeOldResultSet();
    }

    // =============================================================

    /**
     * INTERNAL.
     * Check if the statement is closed.
     *
     * @param write if the next operation is possibly writing 
     * @throws DbException if it is closed
     */
    protected void checkClosed() {
        if (conn == null) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED);
        }
        conn.checkClosed();
    }

    /**
     * INTERNAL.
     * Close an old result set if there is still one open.
     */
    protected void closeOldResultSet() throws SQLException {
        try {
            if (!closedByResultSet && resultSet != null) {
                resultSet.closeInternal();
            }
        } finally {
            resultSet = null;
            updateCount = -1;
        }
    }

    /**
     * INTERNAL.
     * Set the statement that is currently running.
     *
     * @param c the command
     */
    protected void setExecutingStatement(Command c) {
        executingCommand = c;
    }

    /**
     * Returns whether this object is poolable.
     * @return false
     */
    // ## Java 1.6 ##
    @Override
    public boolean isPoolable() {
        debugCodeCall("isPoolable");
        return false;
    }

    /**
     * Requests that this object should be pooled or not.
     * This call is ignored.
     *
     * @param poolable the requested value
     */
    // ## Java 1.6 ##
    @Override
    public void setPoolable(boolean poolable) {
        if (isDebugEnabled()) {
            debugCode("setPoolable(" + poolable + ");");
        }
    }

    // ## Java 1.7 ##
    @Override
    public void closeOnCompletion() throws SQLException {
        throw unsupported("closeOnCompletion");
    }

    // ## Java 1.7 ##
    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        throw unsupported("isCloseOnCompletion");
    }

    /**
     * INTERNAL
     */
    @Override
    public String toString() {
        return getTraceObjectName();
    }
}
