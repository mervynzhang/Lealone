/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.client.command;

import java.util.ArrayList;
import java.util.List;

import org.lealone.client.result.ClientResult;
import org.lealone.client.result.RowCountDeterminedClientResult;
import org.lealone.client.session.ClientSession;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.Trace;
import org.lealone.common.util.Utils;
import org.lealone.db.CommandParameter;
import org.lealone.db.SysProperties;
import org.lealone.db.async.AsyncCallback;
import org.lealone.db.async.Future;
import org.lealone.db.result.Result;
import org.lealone.db.value.Value;
import org.lealone.net.TransferInputStream;
import org.lealone.server.protocol.Packet;
import org.lealone.server.protocol.batch.BatchStatementPreparedUpdate;
import org.lealone.server.protocol.batch.BatchStatementUpdateAck;
import org.lealone.server.protocol.ps.PreparedStatementClose;
import org.lealone.server.protocol.ps.PreparedStatementGetMetaData;
import org.lealone.server.protocol.ps.PreparedStatementGetMetaDataAck;
import org.lealone.server.protocol.ps.PreparedStatementPrepare;
import org.lealone.server.protocol.ps.PreparedStatementPrepareAck;
import org.lealone.server.protocol.ps.PreparedStatementPrepareReadParams;
import org.lealone.server.protocol.ps.PreparedStatementPrepareReadParamsAck;
import org.lealone.server.protocol.ps.PreparedStatementQuery;
import org.lealone.server.protocol.ps.PreparedStatementUpdate;
import org.lealone.server.protocol.statement.StatementQueryAck;
import org.lealone.server.protocol.statement.StatementUpdateAck;

public class ClientPreparedSQLCommand extends ClientSQLCommand {

    private ArrayList<CommandParameter> parameters;

    public ClientPreparedSQLCommand(ClientSession session, String sql, int fetchSize) {
        super(session, sql, fetchSize);
        // commandId重新prepare时会变，但是parameters不会变
        parameters = Utils.newSmallArrayList();
    }

    @Override
    public int getType() {
        return CLIENT_PREPARED_SQL_COMMAND;
    }

    @Override
    public Future<Boolean> prepare(boolean readParams) {
        AsyncCallback<Boolean> ac = AsyncCallback.createSingleThreadCallback();
        // Prepared SQL的ID，每次执行时都发给后端
        commandId = session.getNextId();
        if (readParams) {
            PreparedStatementPrepareReadParams packet = new PreparedStatementPrepareReadParams(commandId,
                    sql);
            Future<PreparedStatementPrepareReadParamsAck> f = session.send(packet);
            f.onComplete(ar -> {
                if (ar.isFailed()) {
                    ac.setAsyncResult(ar.getCause());
                } else {
                    PreparedStatementPrepareReadParamsAck ack = ar.getResult();
                    isQuery = ack.isQuery;
                    parameters = new ArrayList<>(ack.params);
                    ac.setAsyncResult(isQuery);
                }
            });
        } else {
            PreparedStatementPrepare packet = new PreparedStatementPrepare(commandId, sql);
            Future<PreparedStatementPrepareAck> f = session.send(packet);
            f.onComplete(ar -> {
                if (ar.isFailed()) {
                    ac.setAsyncResult(ar.getCause());
                } else {
                    PreparedStatementPrepareAck ack = ar.getResult();
                    isQuery = ack.isQuery;
                    ac.setAsyncResult(isQuery);
                }
            });
        }
        return ac;
    }

    private void prepareIfRequired() {
        session.checkClosed();
        if (commandId <= session.getCurrentId() - SysProperties.SERVER_CACHED_OBJECTS) {
            // object is too old - we need to prepare again
            prepare(false);
        }
    }

    @Override
    public ArrayList<CommandParameter> getParameters() {
        return parameters;
    }

    @Override
    public Future<Result> getMetaData() {
        if (!isQuery) {
            return Future.succeededFuture(null);
        }
        prepareIfRequired();
        AsyncCallback<Result> ac = AsyncCallback.createSingleThreadCallback();
        try {
            Future<PreparedStatementGetMetaDataAck> f = session
                    .send(new PreparedStatementGetMetaData(commandId));
            f.onComplete(ar -> {
                if (ar.isSucceeded()) {
                    try {
                        PreparedStatementGetMetaDataAck ack = ar.getResult();
                        ClientResult result = new RowCountDeterminedClientResult(session,
                                (TransferInputStream) ack.in, -1, ack.columnCount, 0, 0);
                        ac.setAsyncResult(result);
                    } catch (Throwable t) {
                        ac.setAsyncResult(t);
                    }
                } else {
                    ac.setAsyncResult(ar.getCause());
                }
            });
        } catch (Throwable t) {
            ac.setAsyncResult(t);
        }
        return ac;
    }

    @Override
    protected Future<Result> query(int maxRows, boolean scrollable, int fetch, int resultId) {
        Packet packet = new PreparedStatementQuery(resultId, maxRows, fetch, scrollable, commandId,
                getValues());
        return session.<Result, StatementQueryAck> send(packet, ack -> {
            return getQueryResult(ack, fetch, resultId);
        });
    }

    @Override
    public Future<Integer> executeUpdate() {
        try {
            Packet packet = new PreparedStatementUpdate(commandId, getValues());
            // 如果不给send方法传递packetId，它会自己创建一个，所以这里的调用是安全的
            return session.<Integer, StatementUpdateAck> send(packet, ack -> {
                return ack.updateCount;
            });
        } catch (Throwable t) {
            return failedFuture(t);
        }
    }

    private Value[] getValues() {
        checkParameters();
        prepareIfRequired();
        int size = parameters.size();
        Value[] values = new Value[size];
        for (int i = 0; i < size; i++) {
            values[i] = parameters.get(i).getValue();
        }
        return values;
    }

    private void checkParameters() {
        for (CommandParameter p : parameters) {
            p.checkSet();
        }
    }

    @Override
    public void close() {
        if (session == null || session.isClosed()) {
            return;
        }
        int packetId = session.getNextId();
        session.traceOperation("COMMAND_CLOSE", packetId);
        try {
            session.send(new PreparedStatementClose(commandId));
        } catch (Exception e) {
            session.getTrace().error(e, "close session");
        }
        if (parameters != null) {
            try {
                for (CommandParameter p : parameters) {
                    Value v = p.getValue();
                    if (v != null) {
                        v.close();
                    }
                }
            } catch (DbException e) {
                session.getTrace().error(e, "close command parameters");
            }
            parameters = null;
        }
        session = null;
    }

    @Override
    public String toString() {
        return sql + Trace.formatParams(getParameters());
    }

    public AsyncCallback<int[]> executeBatchPreparedSQLCommands(List<Value[]> batchParameters) {
        AsyncCallback<int[]> ac = AsyncCallback.createSingleThreadCallback();
        try {
            Future<BatchStatementUpdateAck> f = session.send(new BatchStatementPreparedUpdate(commandId,
                    batchParameters.size(), batchParameters));
            f.onComplete(ar -> {
                if (ar.isSucceeded()) {
                    ac.setAsyncResult(ar.getResult().results);
                } else {
                    ac.setAsyncResult(ar.getCause());
                }
            });
        } catch (Exception e) {
            ac.setAsyncResult(e);
        }
        return ac;
    }
}
