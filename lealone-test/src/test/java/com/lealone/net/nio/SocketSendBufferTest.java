/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.lealone.net.NetClientBase;

public class SocketSendBufferTest {

    private static class RecordingSocket extends Socket {

        private int sendBufferSize = -1;
        private int receiveBufferSize = -1;

        @Override
        public void setSendBufferSize(int size) {
            sendBufferSize = size;
        }

        @Override
        public void setReceiveBufferSize(int size) {
            receiveBufferSize = size;
        }

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public void setKeepAlive(boolean on) {
        }

        @Override
        public void setReuseAddress(boolean on) {
        }

        @Override
        public void setSoTimeout(int timeout) {
        }
    }

    private static void initSocket(Socket socket, Map<String, String> config) throws Exception {
        Method m = NetClientBase.class.getDeclaredMethod("initSocket", Socket.class, Map.class);
        m.setAccessible(true);
        m.invoke(new NioNetClient(), socket, config);
    }

    @Test
    public void testDefaultSendBufferIs8k() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        initSocket(socket, new HashMap<>());
        assertEquals(8 * 1024, socket.sendBufferSize);
        assertEquals(16 * 1024, socket.receiveBufferSize);
    }

    @Test
    public void testSendBufferCanBeConfigured() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        Map<String, String> config = new HashMap<>();
        config.put("SOCKET_SEND_BUFFER_SIZE", String.valueOf(64 * 1024));
        initSocket(socket, config);
        assertEquals(64 * 1024, socket.sendBufferSize);
    }

    @Test
    public void testAdaptiveSendBufferOnlyWithoutExplicitConfig() {
        assertTrue(NioNetClient.isAdaptiveSendBuffer(new HashMap<>()));
        Map<String, String> config = new HashMap<>();
        config.put("SOCKET_SEND_BUFFER_SIZE", "8192");
        assertFalse(NioNetClient.isAdaptiveSendBuffer(config));
    }

    @Test
    public void testAdaptiveSendBufferRaisesOnceForLargePendingWrite() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
                SocketChannel channel = SocketChannel.open(server.getLocalSocketAddress());
                Socket peer = server.accept()) {
            // 模拟 initSocket 先设成默认 8k
            channel.socket().setSendBufferSize(8 * 1024);
            NioWritableChannel writableChannel = new NioWritableChannel(null, channel);
            writableChannel.setAdaptiveSendBuffer(true);

            writableChannel.adjustSendBufferIfNeeded(NioWritableChannel.ADAPTIVE_SEND_BUFFER_THRESHOLD - 1);
            assertEquals(8 * 1024, channel.socket().getSendBufferSize());

            writableChannel.adjustSendBufferIfNeeded(NioWritableChannel.ADAPTIVE_SEND_BUFFER_THRESHOLD);
            assertEquals(NioWritableChannel.ADAPTIVE_SEND_BUFFER_SIZE,
                    channel.socket().getSendBufferSize());

            // 幂等: 后续更大的写不会再改缓冲区
            writableChannel.adjustSendBufferIfNeeded(Integer.MAX_VALUE);
            assertEquals(NioWritableChannel.ADAPTIVE_SEND_BUFFER_SIZE,
                    channel.socket().getSendBufferSize());
        }
    }
}
