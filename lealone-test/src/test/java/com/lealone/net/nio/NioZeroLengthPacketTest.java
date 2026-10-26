/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.lealone.db.ConnectionSetting;
import com.lealone.db.DataBuffer;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.AsyncConnection;
import com.lealone.net.NetBuffer;
import com.lealone.net.NetEventLoop;
import com.lealone.net.WritableChannel;

/**
 * 核心网络层的包边界回归测试：协议里 payload 长度为 0 的包（包头里的长度字段 == 0，
 * 例如 PostgreSQL 扩展协议里的 Sync('S')/Flush('H')）在包头跨 TCP read 边界时不能被丢掉。
 * <p>
 * 测试不依赖任何插件：直接用 loopback socket + 一个自定义的 4 字节长度包头协议驱动
 * {@link NioEventLoop#read(SelectionKey)}，由测试代码精确控制每次 read 能看到多少字节。
 * 包头被拆成"前 k 个字节"和"剩余字节"两次 read，正是触发 bug 的路径：
 * 补完包头后 packetLength == 0，旧代码把它当成"还没读到 packetLength"，
 * 于是这个包永远不会交给 {@link AsyncConnection#handle}。
 */
public class NioZeroLengthPacketTest {

    /** 4 字节长度包头，长度字段只表示 payload 长度，不含包头本身。 */
    private static final int HEADER_SIZE = 4;

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    /**
     * 包头在 TCP read 边界上的每一种切分方式（1+3、2+2、3+1）都必须把 0 长度包交给 handler。
     * 修复前 k=1/2/3 三种切法都会丢包（收到 0 个），修复后各收到 1 个。
     */
    @Test(timeout = 30000)
    public void testZeroLengthPacketSplitAtEveryHeaderBoundary() throws Exception {
        for (int split = 1; split <= HEADER_SIZE - 1; split++) {
            try (Harness h = new Harness(null)) {
                h.write(new byte[split]); // 包头的前 split 个字节
                h.readUntilNewPartialHeader();

                // 包头剩余字节 + 下一个包的第一个字节（让补完包头后的那次 read 能立即返回）
                byte[] rest = new byte[HEADER_SIZE - split + 1];
                h.write(rest);
                h.readUntilNewPartialHeader();

                assertEquals("split=" + split + " 时 0 长度 payload 包被丢掉了", 1,
                        h.conn.packetLengths.size());
                assertEquals(Integer.valueOf(0), h.conn.packetLengths.get(0));
                assertNull(h.conn.exception);
            }
        }
    }

    /**
     * 强复现：在同一个连接上连续发送 5000 个 0 长度包，并且每个包头都跨两次 read
     * （每次只让网络层看到包头的前 1 个字节，再看到剩余 3 个字节）。
     * 修复前收到 0/5000，修复后收到 5000/5000。
     */
    @Test(timeout = 60000)
    public void testPipelineOf5000ZeroLengthPacketsWithSplitHeaders() throws Exception {
        int packetCount = 5000;
        try (Harness h = new Harness(null)) {
            h.write(new byte[1]); // 第 0 个包头的第 1 个字节
            h.readUntilNewPartialHeader();

            for (int i = 1; i < packetCount; i++) {
                // 补完上一个包头（3 字节）+ 下一个包头的第 1 个字节
                h.write(new byte[HEADER_SIZE]);
                h.readUntilNewPartialHeader();
            }
            // 补完最后一个包头 + 一个后续包头的首字节，保证最后一次 read 不阻塞
            h.write(new byte[HEADER_SIZE]);
            h.readUntilNewPartialHeader();

            assertEquals("5000 个 0 长度 payload 包全部都必须被 handler 收到", packetCount,
                    h.conn.packetLengths.size());
            for (int len : h.conn.packetLengths)
                assertEquals(0, len);
            assertNull(h.conn.exception);
        }
    }

    /** 一次 read 里有多个 0 长度包（包头不跨边界）时不能丢包，这是修复前的正常路径。 */
    @Test(timeout = 30000)
    public void testZeroLengthPacketsInSingleRead() throws Exception {
        int packetCount = 100;
        try (Harness h = new Harness(null)) {
            byte[] bytes = new byte[packetCount * HEADER_SIZE];
            h.write(bytes);
            h.client.shutdownOutput();
            h.readUntilClosed();

            assertEquals(packetCount, h.conn.packetLengths.size());
            for (int len : h.conn.packetLengths)
                assertEquals(0, len);
            assertNull(h.conn.exception);
        }
    }

    /** 0 长度包和数据包混在一次 read 里，顺序和 payload 都不能错。 */
    @Test(timeout = 30000)
    public void testMixedZeroLengthAndDataPacketsInSingleRead() throws Exception {
        try (Harness h = new Harness(null)) {
            h.write(frame());
            h.write(frame(1, 2, 3));
            h.write(frame());
            h.write(frame(10, 20, 30, 40, 50));
            h.client.shutdownOutput();
            h.readUntilClosed();

            assertEquals(Arrays.asList(0, 3, 0, 5), h.conn.packetLengths);
            assertEquals(Arrays.asList(0L, 6L, 0L, 150L), h.conn.payloadSums);
            assertNull(h.conn.exception);
        }
    }

    /** 长度恰好等于 maxPacketSize 的包必须被接受（边界值不能误判为超长）。 */
    @Test(timeout = 30000)
    public void testPacketAtMaxPacketSize() throws Exception {
        int maxPacketSize = 64;
        Map<String, String> config = new HashMap<>();
        config.put(ConnectionSetting.MAX_PACKET_SIZE.name(), Integer.toString(maxPacketSize));
        try (Harness h = new Harness(config)) {
            byte[] payload = new byte[maxPacketSize];
            for (int i = 0; i < payload.length; i++)
                payload[i] = (byte) (i + 1);
            byte[] header = header(payload.length);

            // 先只发一半 payload，强制走"payload 未读完"的分支，再补发剩余部分
            h.write(concat(header, Arrays.copyOf(payload, maxPacketSize / 2)));
            h.readOnce();
            assertEquals(0, h.conn.packetLengths.size());

            h.write(Arrays.copyOfRange(payload, maxPacketSize / 2, maxPacketSize));
            h.readOnce();

            assertEquals(1, h.conn.packetLengths.size());
            assertEquals(Integer.valueOf(maxPacketSize), h.conn.packetLengths.get(0));
            assertEquals(expectedSum(payload), h.conn.payloadSums.get(0).longValue());
            assertNull(h.conn.exception);
        }
    }

    /** 超过 maxPacketSize 的包必须被拒绝，不能继续按坏长度解析后续字节。 */
    @Test(timeout = 30000)
    public void testPacketLargerThanMaxPacketSizeRejected() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put(ConnectionSetting.MAX_PACKET_SIZE.name(), "64");
        try (Harness h = new Harness(config)) {
            h.write(header(65));
            h.readOnce();

            assertNotNull("超过 maxPacketSize 必须抛异常并关闭连接", h.conn.exception);
            assertTrue(h.conn.exception.getMessage(),
                    h.conn.exception.getMessage().contains("Packet too large"));
            assertTrue("连接必须被关闭", h.channel.isClosed());
        }
    }

    /**
     * 负的 payload 长度（例如 PG 包头里 length &lt; 4）必须被拒绝。
     * 修复前 -1 还能通过 checkPacketLength，随后 buffer.position() 会往回退，把后续字节当包头解析。
     */
    @Test(timeout = 30000)
    public void testNegativePacketLengthRejected() throws Exception {
        byte[] header = header(-1);
        try (Harness h = new Harness(null)) {
            // 包头也跨一次 read 边界，确保补包头路径同样会拒绝负长度
            h.write(Arrays.copyOf(header, 1));
            h.readUntilNewPartialHeader();
            h.write(Arrays.copyOfRange(header, 1, HEADER_SIZE));
            h.readOnce();

            assertNotNull("负的包长度必须抛异常并关闭连接", h.conn.exception);
            assertTrue(h.conn.exception.getMessage(),
                    h.conn.exception.getMessage().contains("Invalid packet length"));
            assertTrue("连接必须被关闭", h.channel.isClosed());
        }
    }

    // ------------------------------------------------------------------
    // 测试用协议/连接
    // ------------------------------------------------------------------

    /** 4 字节长度包头 + payload，payload 长度可以为 0。 */
    private static byte[] header(int payloadLength) {
        return new byte[] { (byte) (payloadLength >>> 24), (byte) (payloadLength >>> 16),
                (byte) (payloadLength >>> 8), (byte) payloadLength };
    }

    private static byte[] frame(int... payload) {
        byte[] bytes = new byte[HEADER_SIZE + payload.length];
        System.arraycopy(header(payload.length), 0, bytes, 0, HEADER_SIZE);
        for (int i = 0; i < payload.length; i++)
            bytes[HEADER_SIZE + i] = (byte) payload[i];
        return bytes;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] bytes = new byte[a.length + b.length];
        System.arraycopy(a, 0, bytes, 0, a.length);
        System.arraycopy(b, 0, bytes, a.length, b.length);
        return bytes;
    }

    private static long expectedSum(byte[] payload) {
        long sum = 0;
        for (byte b : payload)
            sum += b & 0xff;
        return sum;
    }

    /** 只实现 NioEventLoop 构造/读路径真正用到的 Scheduler 方法。 */
    private static Scheduler createScheduler(final NetBuffer inputBuffer,
            final NetBuffer outputBuffer, final NetEventLoop[] eventLoopHolder) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getInputBuffer".equals(name))
                    return inputBuffer;
                if ("getOutputBuffer".equals(name))
                    return outputBuffer;
                if ("getNetEventLoop".equals(name))
                    return eventLoopHolder[0];
                if ("toString".equals(name))
                    return "NioZeroLengthPacketTestScheduler";
                if ("hashCode".equals(name))
                    return System.identityHashCode(proxy);
                if ("equals".equals(name))
                    return proxy == args[0];
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class)
                    return false;
                if (returnType == int.class)
                    return 0;
                if (returnType == long.class)
                    return 0L;
                if (returnType == short.class)
                    return (short) 0;
                if (returnType == byte.class)
                    return (byte) 0;
                if (returnType == char.class)
                    return (char) 0;
                if (returnType == float.class)
                    return 0F;
                if (returnType == double.class)
                    return 0D;
                return null;
            }
        };
        return (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(),
                new Class<?>[] { Scheduler.class }, handler);
    }

    /**
     * 测试用连接：包头是 4 字节 payload 长度；handle 时严格消费 getPacketLength() 记下的长度，
     * 并记录包长度与 payload 校验和。
     */
    private static final class TestConnection extends AsyncConnection {
        final List<Integer> packetLengths = new ArrayList<>();
        final List<Long> payloadSums = new ArrayList<>();
        volatile Exception exception;
        private int pendingLength;

        TestConnection(WritableChannel writableChannel) {
            super(writableChannel, true);
        }

        @Override
        public void handle(NetBuffer buffer, boolean autoRecycle) {
            int length = pendingLength;
            packetLengths.add(length);
            long sum = 0;
            for (int i = 0; i < length; i++)
                sum += buffer.getUnsignedByte();
            payloadSums.add(sum);
        }

        @Override
        public int getPacketLength(ByteBuffer buffer) {
            pendingLength = buffer.getInt();
            return pendingLength;
        }

        @Override
        public void handleException(Exception e) {
            exception = e;
        }
    }

    /** 不注册 Selector，只提供一个带 NioAttachment 的 SelectionKey，供直接调用 read()。 */
    private static final class TestSelectionKey extends SelectionKey {
        private final SocketChannel channel;
        private final Object attachment;
        private boolean valid = true;
        private int interestOps = SelectionKey.OP_READ;

        TestSelectionKey(SocketChannel channel, Object attachment) {
            this.channel = channel;
            this.attachment = attachment;
            attach(attachment);
        }

        @Override
        public SocketChannel channel() {
            return channel;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void cancel() {
            valid = false;
        }

        @Override
        public int interestOps() {
            return interestOps;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            interestOps = ops;
            return this;
        }

        @Override
        public int readyOps() {
            return SelectionKey.OP_READ;
        }
    }

    /**
     * 一条真实 loopback TCP 连接 + 一个 NioEventLoop。server 端 channel 保持阻塞模式，
     * 这样测试可以精确控制每次 read() 能读到哪些字节（写多少就读到多少）。
     */
    private static final class Harness implements AutoCloseable {
        final ServerSocketChannel serverSocket;
        final SocketChannel client;
        final SocketChannel server;
        final NioEventLoop eventLoop;
        final NioWritableChannel channel;
        final TestConnection conn;
        final SelectionKey key;
        final NioAttachment attachment;

        Harness(Map<String, String> config) throws IOException {
            serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client = SocketChannel.open(serverSocket.getLocalAddress());
            server = serverSocket.accept();
            client.configureBlocking(true);
            server.configureBlocking(true);
            client.socket().setTcpNoDelay(true);
            server.socket().setTcpNoDelay(true);

            NetBuffer inputBuffer = new NetBuffer(DataBuffer.createDirect());
            NetBuffer outputBuffer = new NetBuffer(DataBuffer.createDirect());
            NetEventLoop[] eventLoopHolder = new NetEventLoop[1];
            Scheduler scheduler = createScheduler(inputBuffer, outputBuffer, eventLoopHolder);
            eventLoop = new NioEventLoop(scheduler, 10, config == null ? new HashMap<>() : config);
            eventLoopHolder[0] = eventLoop;

            channel = new NioWritableChannel(scheduler, server);
            conn = new TestConnection(channel);
            channel.setAsyncConnection(conn);
            key = new TestSelectionKey(server, new NioAttachment(conn));
            channel.setSelectionKey(key);
            eventLoop.addChannel(channel);
            attachment = (NioAttachment) key.attachment();
        }

        void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining())
                client.write(buffer);
        }

        /** 调用一次 NioEventLoop.read()（阻塞 socket 保证至少消费到刚写入的数据）。 */
        void readOnce() {
            eventLoop.read(key);
        }

        /**
         * 一直 read，直到补包头路径产生了"下一个包头"的部分缓冲。
         * 用 inBuffer 对象身份判断状态推进：同一个 inBuffer 表示这次补包头只读到了部分剩余字节。
         */
        void readUntilNewPartialHeader() {
            NetBuffer before = attachment.inBuffer;
            for (int i = 0; i < 1000; i++) {
                eventLoop.read(key);
                if (attachment.inBuffer != null && attachment.inBuffer != before
                        && attachment.packetLength == -1) {
                    return;
                }
            }
            fail("read 没有推进到新的部分包头状态, before=" + before + ", now=" + attachment.inBuffer);
        }

        /** 读完所有数据并等到对端 EOF 关闭连接（客户端已 shutdownOutput）。 */
        void readUntilClosed() {
            for (int i = 0; i < 10000 && !conn.isClosed(); i++)
                eventLoop.read(key);
            assertTrue("没有读到 EOF/连接没有被关闭", conn.isClosed());
        }

        @Override
        public void close() throws IOException {
            client.close();
            server.close();
            serverSocket.close();
        }
    }
}
