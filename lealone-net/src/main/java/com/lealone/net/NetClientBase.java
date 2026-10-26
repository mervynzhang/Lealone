/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lealone.common.util.MapUtils;
import com.lealone.common.util.ShutdownHookUtils;
import com.lealone.db.ConnectionSetting;
import com.lealone.db.Constants;
import com.lealone.db.async.AsyncCallback;
import com.lealone.db.async.Future;
import com.lealone.db.scheduler.Scheduler;

public abstract class NetClientBase implements NetClient {

    public NetClientBase() {
        ShutdownHookUtils.addShutdownHook(this, () -> {
            close();
        });
    }

    // 使用InetSocketAddress为key而不是字符串，是因为像localhost和127.0.0.1这两种不同格式实际都是同一个意思，
    // 如果用字符串，就会产生两条AsyncConnection，这是没必要的。
    private final Map<InetSocketAddress, AsyncConnectionPool> asyncConnections = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected abstract void createConnectionInternal(Map<String, String> config, NetNode node, //
            AsyncConnectionManager connectionManager, AsyncCallback<AsyncConnection> ac,
            Scheduler scheduler);

    @Override
    public Future<AsyncConnection> createConnection(Map<String, String> config, NetNode node,
            AsyncConnectionManager connectionManager, Scheduler scheduler) {
        InetSocketAddress inetSocketAddress = node.getInetSocketAddress();
        AsyncConnection asyncConnection = getConnection(config, inetSocketAddress);
        if (asyncConnection == null) {
            AsyncCallback<AsyncConnection> ac = AsyncCallback.create(true);
            createConnectionInternal(config, node, connectionManager, ac, scheduler);
            return ac;
        } else {
            return Future.succeededFuture(asyncConnection);
        }
    }

    @Override
    public AsyncConnection getConnection(InetSocketAddress inetSocketAddress) {
        AsyncConnectionPool pool = asyncConnections.get(inetSocketAddress);
        return pool == null ? null : pool.getConnection();
    }

    private AsyncConnection getConnection(Map<String, String> config,
            InetSocketAddress inetSocketAddress) {
        AsyncConnectionPool pool = asyncConnections.get(inetSocketAddress);
        return pool == null ? null : pool.getConnection(config);
    }

    @Override
    public void removeConnection(AsyncConnection conn) {
        if (conn == null)
            return;
        AsyncConnectionPool pool = asyncConnections.get(conn.getInetSocketAddress());
        if (pool != null) {
            pool.removeConnection(conn);
            if (pool.isEmpty()) {
                asyncConnections.remove(conn.getInetSocketAddress());
            }
        }
        if (!conn.isClosed())
            conn.close();
    }

    @Override
    public void removeConnection(InetSocketAddress inetSocketAddress) {
        AsyncConnectionPool pool = asyncConnections.remove(inetSocketAddress);
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public void addConnection(InetSocketAddress inetSocketAddress, AsyncConnection conn) {
        checkClosed();
        AsyncConnectionPool pool = asyncConnections.get(inetSocketAddress);
        if (pool == null) {
            pool = new AsyncConnectionPool();
            AsyncConnectionPool old = asyncConnections.putIfAbsent(inetSocketAddress, pool);
            if (old != null)
                pool = old;
        }
        pool.addConnection(conn);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true))
            return;
        for (AsyncConnectionPool pool : asyncConnections.values()) {
            pool.close();
        }
        asyncConnections.clear();
    }

    protected void checkClosed() {
        if (isClosed()) {
            throw new RuntimeException("NetClient is closed");
        }
    }

    @Override
    public void checkTimeout(long currentTime) {
        if (!asyncConnections.isEmpty()) {
            for (AsyncConnectionPool pool : asyncConnections.values()) {
                pool.checkTimeout(currentTime);
            }
        }
    }

    protected void initSocket(Socket socket, Map<String, String> config) throws SocketException {
        int socketRecvBuffer = MapUtils.getInt(config, ConnectionSetting.SOCKET_RECV_BUFFER_SIZE.name(),
                16 * 1024);
        // 默认保持 8k: SO_SNDBUF 是每个连接的内核发送缓冲, 而且一旦显式设置,
        // Linux 的 tcp_wmem 自动调优就失效了。按评审口径, 64k 时 1k/10k 连接的
        // 不可换出内核内存约 160MB/1.6GB, 旧值 8k 约 48MB/480MB。
        // 需要更大发送缓冲的批量写不要改默认值: NioWritableChannel 会在单个待发送
        // 缓冲区超过 32k 时按连接自适应上调一次到 64k; 也可以在 JDBC URL /
        // 连接参数里用 SOCKET_SEND_BUFFER_SIZE 显式指定(此时不再自适应)。
        // 自带用例 batch=500 的单个请求实测约 103.8KB, 8k/64k 分别会拆成约 13/2 块;
        // 这里只是减少拆分次数, 并不保证请求一次进缓冲区。实测 100k 行 × 85 字符
        // 经 TCP 批量插入: 固定 8k 约 9.0-9.2s, 64k 或自适应上调后约 0.27-0.33s。
        int socketSendBuffer = MapUtils.getInt(config, ConnectionSetting.SOCKET_SEND_BUFFER_SIZE.name(),
                8 * 1024);
        socket.setReceiveBufferSize(socketRecvBuffer);
        socket.setSendBufferSize(socketSendBuffer);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReuseAddress(true);

        int networkTimeout = MapUtils.getInt(config, ConnectionSetting.NETWORK_TIMEOUT.name(),
                Constants.DEFAULT_NETWORK_TIMEOUT);
        socket.setSoTimeout(networkTimeout);
    }
}
