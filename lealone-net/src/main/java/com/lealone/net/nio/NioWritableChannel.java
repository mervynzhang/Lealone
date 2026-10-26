/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.AsyncConnection;
import com.lealone.net.NetBuffer;
import com.lealone.net.NetBuffer.WritableBuffer;
import com.lealone.net.NetEventLoop;
import com.lealone.net.WritableChannel;

public class NioWritableChannel implements WritableChannel {

    // 单个待发送缓冲超过该阈值时, 把本连接的 SO_SNDBUF 自适应上调一次。
    // 典型场景是 batch=500 的 INSERT, 一个请求约 100k, 8k 发送缓冲会把它拆成
    // 十几个小块, 每块都要等对端 ACK, 撞上 TCP 延迟 ACK 会慢 20 倍以上。
    static final int ADAPTIVE_SEND_BUFFER_THRESHOLD = 32 * 1024;
    static final int ADAPTIVE_SEND_BUFFER_SIZE = 64 * 1024;

    private final String host;
    private final int port;
    private final String localHost;
    private final int localPort;

    private final LinkedList<WritableBuffer> buffers = new LinkedList<>();
    private final SocketChannel channel;
    private NetEventLoop eventLoop;
    private SelectionKey selectionKey; // 注册成功了才设置

    private AsyncConnection conn;
    private NetBuffer inputBuffer;
    private boolean closed;

    // 只在没有显式配置 SOCKET_SEND_BUFFER_SIZE 的客户端连接上开启, 见 NioNetClient。
    private volatile boolean adaptiveSendBuffer;
    // 上调是幂等的, 用 CAS 保证每个连接最多调用一次 setSendBufferSize。
    private final AtomicBoolean sendBufferAdjusted = new AtomicBoolean();

    public NioWritableChannel(Scheduler scheduler, SocketChannel channel) throws IOException {
        this.channel = channel;
        if (scheduler != null)
            this.eventLoop = (NetEventLoop) scheduler.getNetEventLoop();
        SocketAddress sa = channel.getRemoteAddress();
        if (sa instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) sa;
            host = address.getHostString();
            port = address.getPort();
        } else {
            host = "";
            port = -1;
        }
        sa = channel.getLocalAddress();
        if (sa instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) sa;
            localHost = address.getHostString();
            localPort = address.getPort();
        } else {
            localHost = "";
            localPort = -1;
        }
    }

    public void setAsyncConnection(AsyncConnection conn) {
        this.conn = conn;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getLocalHost() {
        return localHost;
    }

    @Override
    public int getLocalPort() {
        return localPort;
    }

    @Override
    public void setInputBuffer(NetBuffer inputBuffer) {
        this.inputBuffer = inputBuffer;
    }

    @Override
    public List<WritableBuffer> getBuffers() {
        return buffers;
    }

    @Override
    public void addBuffer(WritableBuffer buffer) {
        buffers.add(buffer);
    }

    @Override
    public boolean isBio() {
        return eventLoop == null;
    }

    @Override
    public SocketChannel getSocketChannel() {
        return channel;
    }

    @Override
    public NetEventLoop getEventLoop() {
        return eventLoop;
    }

    @Override
    public void setEventLoop(NetEventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    @Override
    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    @Override
    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        if (eventLoop != null) {
            eventLoop.closeChannel(this);
            selectionKey = null;
            recycleBuffers(buffers);
        } else {
            conn = null;
            inputBuffer = null;
            Socket socket = channel.socket();
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
            }
        }
    }

    @Override
    public void read() {
        if (closed)
            return;
        try {
            ByteBuffer buffer = inputBuffer.getByteBuffer();
            int packetLengthByteCount = conn.getPacketLengthByteCount();
            readFully(buffer, packetLengthByteCount);
            int packetLength = conn.getPacketLength(buffer);
            buffer.flip();
            if (packetLength > buffer.capacity())
                buffer = inputBuffer.getDataBuffer().growCapacity(packetLength);
            readFully(buffer, packetLength);
            conn.handle(inputBuffer, false);
        } catch (Exception e) {
            conn.handleException(e);
            close();
        } finally {
            // 出异常关闭后会变null
            if (inputBuffer != null)
                inputBuffer.recycle();
        }
    }

    private void readFully(ByteBuffer buffer, int len) throws IOException {
        buffer.limit(len);
        // 要在循环里一直读满为止，如果只读一次返回的结果不一定是len
        while (len > 0) {
            int read = channel.read(buffer);
            if (read < 0)
                break;
            len -= read;
        }
        buffer.flip();
    }

    void setAdaptiveSendBuffer(boolean adaptiveSendBuffer) {
        this.adaptiveSendBuffer = adaptiveSendBuffer;
    }

    // 只把本连接的内核发送缓冲上调一次, 不改变协议和连接状态:
    // 已经显式配置得更大时不缩小; 任何异常都吞掉, 一次 setsockopt 失败不能让连接断开。
    void adjustSendBufferIfNeeded(int pendingBytes) {
        if (!adaptiveSendBuffer || pendingBytes < ADAPTIVE_SEND_BUFFER_THRESHOLD
                || !sendBufferAdjusted.compareAndSet(false, true))
            return;
        try {
            Socket socket = channel.socket();
            if (socket == null || socket.getSendBufferSize() >= ADAPTIVE_SEND_BUFFER_SIZE)
                return;
            socket.setSendBufferSize(ADAPTIVE_SEND_BUFFER_SIZE);
        } catch (Throwable t) {
            // 上调失败时保持原来的缓冲区, 连接继续可用
        }
    }

    @Override
    public void write(WritableBuffer buffer) {
        if (closed)
            return;
        adjustSendBufferIfNeeded(buffer.getByteBuffer().remaining());
        if (eventLoop != null) {
            eventLoop.write(this, buffer);
        } else {
            ByteBuffer bb = buffer.getByteBuffer();
            int remaining = bb.remaining();
            try {
                // 一定要用while循环来写，否则会丢数据！
                while (remaining > 0) {
                    long written = channel.write(bb);
                    remaining -= written;
                }
            } catch (Exception e) {
                conn.handleException(e);
                close();
            } finally {
                buffer.recycle();
            }
        }
    }

    public static void recycleBuffers(List<WritableBuffer> buffers) {
        if (buffers != null) {
            for (WritableBuffer buffer : buffers)
                buffer.recycle();
            buffers.clear();
        }
    }
}
