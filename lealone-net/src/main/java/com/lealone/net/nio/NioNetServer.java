/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net.nio;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.AsyncConnection;
import com.lealone.net.NetServerBase;

//负责接收新的ProtocolServer连接，比如TCP、P2P
//TODO 1.支持SSL 2.支持配置参数
public class NioNetServer extends NetServerBase {

    private static final Logger logger = LoggerFactory.getLogger(NioNetServer.class);
    private ServerSocketChannel serverChannel;

    @Override
    public synchronized void start() {
        if (isStarted())
            return;
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(getHost(), getPort()));
            serverChannel.configureBlocking(false);
            connectionManager.registerAccepter(serverChannel);
        } catch (Exception e) {
            checkBindException(e, "Failed to start nio net server");
        }
        super.start();
    }

    @Override
    public synchronized void stop() {
        if (isStopped())
            return;
        super.stop();
        if (serverChannel != null) {
            try {
                serverChannel.close();
                serverChannel = null;
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void accept(Scheduler scheduler) {
        AsyncConnection conn = null;
        NioWritableChannel writableChannel = null;
        try {
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);
            // 服务端默认开着 Nagle: 一次响应分多个小包写出时(比如 PostgreSQL 协议先发
            // RowDescription 再发 DataRow/CommandComplete/ReadyForQuery), 后面的小包会被
            // 延迟 ACK 拖住, 一次往返能多出 ~40ms。客户端侧 NetClientBase 早就关了 Nagle,
            // 服务端也要关, 否则用 PG 协议这类客户端每条约慢 40ms。
            try {
                channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
            } catch (Exception e) {
                // 个别实现不支持该选项时忽略, 不影响连接建立
            }
            writableChannel = new NioWritableChannel(scheduler, channel);
            conn = createConnection(writableChannel, scheduler);
        } catch (Throwable e) {
            if (conn != null) {
                removeConnection(conn);
            }
            if (writableChannel != null) {
                NioEventLoop.closeChannelSilently(writableChannel);
            }
            // 按Ctrl+C退出时accept可能抛出异常，此时就不需要记录日志了
            if (!isStopped()) {
                logger.warn(getName() + " failed to accept connection", e);
            }
        }
    }
}
