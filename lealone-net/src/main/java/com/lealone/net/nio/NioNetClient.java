/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net.nio;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;

import com.lealone.db.ConnectionSetting;
import com.lealone.db.DataBuffer;
import com.lealone.db.async.AsyncCallback;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.AsyncConnection;
import com.lealone.net.AsyncConnectionManager;
import com.lealone.net.AsyncConnectionPool;
import com.lealone.net.NetBuffer;
import com.lealone.net.NetClientBase;
import com.lealone.net.NetEventLoop;
import com.lealone.net.NetFactory;
import com.lealone.net.NetNode;
import com.lealone.net.TcpClientConnection;

public class NioNetClient extends NetClientBase {

    // 用户显式配置了 SOCKET_SEND_BUFFER_SIZE 时以用户配置为准, 不再自适应上调;
    // 没配置时连接先用默认 8k, 遇到大于 32k 的单个待发送缓冲再上调到 64k。
    static boolean isAdaptiveSendBuffer(Map<String, String> config) {
        return config == null || !config.containsKey(ConnectionSetting.SOCKET_SEND_BUFFER_SIZE.name());
    }

    private static class ConnectionAttachment {
        private AsyncConnectionManager connectionManager;
        private InetSocketAddress inetSocketAddress;
        private AsyncCallback<AsyncConnection> ac;
        private int maxSharedSize;
        private boolean shared;
        private boolean adaptiveSendBuffer;
    }

    @Override
    protected void createConnectionInternal(Map<String, String> config, NetNode node, //
            AsyncConnectionManager connectionManager, AsyncCallback<AsyncConnection> ac,
            Scheduler scheduler) {
        SocketChannel channel = null;
        InetSocketAddress inetSocketAddress = node.getInetSocketAddress();
        boolean block = NetFactory.isBio(config);
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(block);
            initSocket(channel.socket(), config);

            if (block) {
                channel.connect(inetSocketAddress);
                NioWritableChannel writableChannel = new NioWritableChannel(null, channel);
                writableChannel.setAdaptiveSendBuffer(isAdaptiveSendBuffer(config));
                AsyncConnection conn;
                if (connectionManager != null) {
                    conn = connectionManager.createConnection(writableChannel, false, scheduler);
                } else {
                    NetBuffer inBuffer = new NetBuffer(DataBuffer.createDirect());
                    NetBuffer outBuffer = new NetBuffer(DataBuffer.createDirect());
                    conn = new TcpClientConnection(writableChannel, this, 1, false, inBuffer, outBuffer);
                    writableChannel.setInputBuffer(inBuffer);
                }
                writableChannel.setAsyncConnection(conn);
                conn.setInetSocketAddress(inetSocketAddress);
                if (connectionManager == null || !connectionManager.addConnectionLazy())
                    addConnection(inetSocketAddress, conn);
                ac.setAsyncResult(conn);
            } else {
                NetEventLoop eventLoop = (NetEventLoop) scheduler.getNetEventLoop();
                ConnectionAttachment attachment = new ConnectionAttachment();
                attachment.connectionManager = connectionManager;
                attachment.inetSocketAddress = inetSocketAddress;
                attachment.ac = ac;
                attachment.maxSharedSize = AsyncConnectionPool.getMaxSharedSize(config);
                attachment.shared = AsyncConnectionPool.isShared(config);
                attachment.adaptiveSendBuffer = isAdaptiveSendBuffer(config);
                channel.register(eventLoop.getSelector(), SelectionKey.OP_CONNECT, attachment);
                channel.connect(inetSocketAddress);
                // 如果前面已经在执行事件循环，此时就不能再次进入事件循环
                // 否则两次删除SelectionKey会出现java.util.ConcurrentModificationException
                if (!eventLoop.isInLoop()) {
                    if (eventLoop.getSelector().selectNow() > 0) {
                        eventLoop.handleSelectedKeys();
                    }
                }
            }
        } catch (Exception e) {
            ac.setAsyncResult(e);
        }
    }

    @Override
    public void connectionEstablished(Scheduler scheduler, NetEventLoop eventLoop, SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        if (!channel.isConnectionPending())
            return;
        ConnectionAttachment attachment = (ConnectionAttachment) key.attachment();
        try {
            channel.finishConnect();
            NioWritableChannel writableChannel = new NioWritableChannel(scheduler, channel);
            writableChannel.setAdaptiveSendBuffer(attachment.adaptiveSendBuffer);
            writableChannel.setSelectionKey(key);
            AsyncConnection conn;
            if (attachment.connectionManager != null) {
                conn = attachment.connectionManager.createConnection(writableChannel, false, scheduler);
            } else {
                NetBuffer inBuffer = scheduler.getInputBuffer();
                NetBuffer outBuffer = scheduler.getOutputBuffer();
                conn = new TcpClientConnection(writableChannel, this, attachment.maxSharedSize,
                        attachment.shared, inBuffer, outBuffer);
                writableChannel.setInputBuffer(inBuffer);
            }
            writableChannel.setAsyncConnection(conn);
            eventLoop.addChannel(writableChannel);
            conn.setInetSocketAddress(attachment.inetSocketAddress);
            if (attachment.connectionManager == null
                    || !attachment.connectionManager.addConnectionLazy())
                addConnection(attachment.inetSocketAddress, conn);
            if (attachment.ac != null) {
                attachment.ac.setAsyncResult(conn);
            }
            key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
            key.attach(null);
            // 连接创建成功后在SelectionKey里重新携带一个更简单的NioAttachment，老的ConnectionAttachment会被GC掉
            channel.register(eventLoop.getSelector(), SelectionKey.OP_READ, new NioAttachment(conn));
        } catch (Exception e) {
            if (attachment.ac != null) {
                attachment.ac.setAsyncResult(e);
            }
        }
    }
}
