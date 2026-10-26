/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zzh
 */
package com.lealone.test.net;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.AsyncConnection;
import com.lealone.net.AsyncConnectionManager;
import com.lealone.net.WritableChannel;
import com.lealone.net.nio.NioNetServer;

/**
 * 服务端接受的连接必须关闭 Nagle（TCP_NODELAY）。
 * <p>
 * 只在客户端侧设 TCP_NODELAY 是不够的：PostgreSQL 协议一次响应要分多个小包写
 * （RowDescription / DataRow / CommandComplete / ReadyForQuery），服务端开着 Nagle 时
 * 后面的小包会被延迟 ACK 拖住，实测一次往返从 0.15ms 变成 44ms。
 */
public class ServerTcpNoDelayTest {

    private static class RecordingConnectionManager implements AsyncConnectionManager {
        volatile WritableChannel acceptedChannel;
        volatile CountDownLatch latch = new CountDownLatch(1);
        volatile NioNetServer server;
        volatile boolean accepting;

        @Override
        public AsyncConnection createConnection(WritableChannel writableChannel, boolean isServer,
                Scheduler scheduler) {
            acceptedChannel = writableChannel;
            latch.countDown();
            return null;
        }

        @Override
        public void removeConnection(AsyncConnection conn) {
        }

        @Override
        public void registerAccepter(java.nio.channels.ServerSocketChannel serverSocketChannel) {
            accepting = true;
            Thread t = new Thread(() -> {
                while (accepting) {
                    server.accept(null);
                }
            }, "nodelay-accepter");
            t.setDaemon(true);
            t.start();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    public void acceptedSocketHasTcpNoDelay() throws Exception {
        RecordingConnectionManager manager = new RecordingConnectionManager();
        NioNetServer server = new NioNetServer();
        manager.server = server;
        int port = freePort();
        Map<String, String> config = new HashMap<>();
        config.put("host", "127.0.0.1");
        config.put("port", Integer.toString(port));
        config.put("name", "nodelay-test");
        server.init(config);
        server.setConnectionManager(manager);
        server.start();
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setTcpNoDelay(true);
            if (!manager.latch.await(10, TimeUnit.SECONDS))
                throw new AssertionError("服务端没有接受连接");
            WritableChannel channel = manager.acceptedChannel;
            Field f = channel.getClass().getDeclaredField("channel");
            f.setAccessible(true);
            SocketChannel serverSideChannel = (SocketChannel) f.get(channel);
            if (!serverSideChannel.getOption(StandardSocketOptions.TCP_NODELAY))
                throw new AssertionError("服务端接受的连接必须关闭 Nagle(TCP_NODELAY)");
        } finally {
            manager.accepting = false;
            server.stop();
        }
    }
}
