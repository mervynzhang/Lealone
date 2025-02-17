/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.net.bio;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import org.lealone.common.util.MapUtils;
import org.lealone.db.ConnectionSetting;
import org.lealone.db.Constants;
import org.lealone.db.async.AsyncCallback;
import org.lealone.db.scheduler.Scheduler;
import org.lealone.net.AsyncConnection;
import org.lealone.net.AsyncConnectionManager;
import org.lealone.net.NetClientBase;
import org.lealone.net.NetNode;
import org.lealone.net.TcpClientConnection;

class BioNetClient extends NetClientBase {

    BioNetClient() {
        super(true);
    }

    @Override
    protected void createConnectionInternal(Map<String, String> config, NetNode node,
            AsyncConnectionManager connectionManager, AsyncCallback<AsyncConnection> ac,
            Scheduler scheduler) {
        InetSocketAddress inetSocketAddress = node.getInetSocketAddress();
        int networkTimeout = MapUtils.getInt(config, ConnectionSetting.NETWORK_TIMEOUT.name(),
                Constants.DEFAULT_NETWORK_TIMEOUT);
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setSoTimeout(networkTimeout);
            initSocket(socket, config);
            socket.connect(inetSocketAddress, networkTimeout);

            AsyncConnection conn;
            BioWritableChannel writableChannel = new BioWritableChannel(config, socket,
                    inetSocketAddress);
            if (connectionManager != null) {
                conn = connectionManager.createConnection(writableChannel, false, null);
            } else {
                conn = new TcpClientConnection(writableChannel, this, 1);
            }
            conn.setInetSocketAddress(inetSocketAddress);
            addConnection(inetSocketAddress, conn);
            ac.setAsyncResult(conn);
        } catch (Exception e) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable t) {
                }
            }
            ac.setAsyncResult(e);
        }
    }
}
