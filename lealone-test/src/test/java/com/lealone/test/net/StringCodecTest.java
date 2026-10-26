/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Test;

import com.lealone.db.DataBuffer;
import com.lealone.db.session.Session;
import com.lealone.net.NetBuffer;
import com.lealone.net.TransferInputStream;
import com.lealone.net.TransferOutputStream;
import com.lealone.net.WritableChannel;

/**
 * 字符串协议编解码的字节级回归测试 + 批量往返性能护栏。
 *
 * 线上格式(不能变): int 字符数(-1 表示 null), 后面按 UTF-16BE 写每个 char,
 * 和 DataOutputStream.writeInt + writeChar 的字节完全一致。
 */
public class StringCodecTest {

    private static class CapturingChannel implements WritableChannel {

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getPort() {
            return 1;
        }

        @Override
        public String getLocalHost() {
            return "localhost";
        }

        @Override
        public int getLocalPort() {
            return 2;
        }

        @Override
        public void setInputBuffer(NetBuffer inputBuffer) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public void read() {
        }

        @Override
        public void write(NetBuffer.WritableBuffer buffer) {
            ByteBuffer bb = buffer.getByteBuffer();
            byte[] a = new byte[bb.remaining()];
            bb.get(a);
            bytes.write(a, 0, a.length);
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder buff = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++)
            buff.append(s);
        return buff.toString();
    }

    /**
     * 用生产代码把一组字符串写进一个响应包, 返回完整的包(4 字节包长 + 9 字节包头 + 负载)。
     */
    private static byte[] writePacket(String[] strings) throws IOException {
        CapturingChannel channel = new CapturingChannel();
        NetBuffer buffer = new NetBuffer(DataBuffer.create());
        TransferOutputStream out = new TransferOutputStream(channel, buffer);
        out.writeResponseHeader(null, 1, Session.STATUS_OK);
        for (String s : strings)
            out.writeString(s);
        out.flush();
        return channel.bytes.toByteArray();
    }

    /** 按协议格式手工构造期望的负载字节。 */
    private static byte[] expectedPayload(String[] strings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String s : strings) {
            int len = s == null ? -1 : s.length();
            out.write(len >>> 24);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
            if (s != null) {
                for (int i = 0; i < len; i++) {
                    char c = s.charAt(i);
                    out.write(c >>> 8);
                    out.write(c);
                }
            }
        }
        return out.toByteArray();
    }

    private static TransferInputStream inputStream(byte[] payload) {
        DataBuffer dataBuffer = DataBuffer.create(payload.length);
        dataBuffer.put(payload);
        dataBuffer.getAndFlipBuffer();
        return new TransferInputStream(new NetBuffer(dataBuffer));
    }

    private static void assertRoundTrip(String[] strings) throws IOException {
        byte[] packet = writePacket(strings);
        int packetLength = ((packet[0] & 0xff) << 24) | ((packet[1] & 0xff) << 16)
                | ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        assertEquals(packet.length - 4, packetLength);
        // 1 字节状态 + 4 字节 packetId + 4 字节 status
        byte[] payload = Arrays.copyOfRange(packet, 4 + 9, packet.length);
        assertArrayEquals("wire format changed", expectedPayload(strings), payload);

        TransferInputStream in = inputStream(payload);
        for (int i = 0; i < strings.length; i++) {
            if (strings[i] == null)
                assertNull(in.readString());
            else
                assertEquals("string #" + i, strings[i], in.readString());
        }
        assertEquals("leftover bytes", 0, in.getDataInputStream().available());
    }

    @Test
    public void testWireFormatAndRoundTrip() throws IOException {
        String longString = repeat("0123456789", 10000); // 100_000 chars
        String[] strings = { null, "", "abc", "中文测试", "😀🚀", // 4 字节 UTF-8 的 emoji
                "\uD800", "\uDC00", "a\uD800b", // 不成对的代理项也必须逐码元可逆
                longString };
        assertRoundTrip(strings);
        assertRoundTrip(new String[] { "x" });
    }

    @Test
    public void testLengthBoundaries() throws IOException {
        for (int len : new int[] { 0, 1, 2, 3, 255, 256, 1023, 1024, 65535, 65536 }) {
            String[] strings = { repeat("a", len), repeat("中", len) };
            assertRoundTrip(strings);
        }
    }

    @Test(expected = NullPointerException.class)
    public void testPutCharsRejectsNull() {
        DataBuffer.create().putChars(null);
    }

    @Test
    public void testReadStringRejectsIllegalLength() throws IOException {
        DataBuffer dataBuffer = DataBuffer.create(4);
        dataBuffer.putInt((Integer.MAX_VALUE >> 1) + 1);
        dataBuffer.getAndFlipBuffer();
        TransferInputStream in = new TransferInputStream(new NetBuffer(dataBuffer));
        try {
            in.readString();
            fail("超长字符串长度必须被拒绝");
        } catch (IOException e) {
            assertTrue("异常信息应说明非法长度: " + e.getMessage(),
                    e.getMessage().contains("非法的字符串长度"));
        }
    }

    @Test
    public void testReadAtEndReturnsMinusOne() throws IOException {
        DataBuffer dataBuffer = DataBuffer.create(1);
        dataBuffer.put((byte) 0x5a);
        dataBuffer.getAndFlipBuffer();
        TransferInputStream in = new TransferInputStream(new NetBuffer(dataBuffer));
        assertEquals(0x5a, in.getDataInputStream().read());
        // read() 与 read(byte[], off, len) 的到尾语义必须一致: 返回 -1,
        // 而不是抛未受检的 BufferUnderflowException。
        assertEquals(-1, in.getDataInputStream().read());
        assertEquals(-1, in.getDataInputStream().read(new byte[1]));
        assertEquals(-1, in.getDataInputStream().read(new byte[1], 0, 1));
        try {
            in.getDataInputStream().readByte();
            fail("DataInputStream.readByte 在到尾时应抛 EOFException");
        } catch (EOFException e) {
            // expected
        }
    }

    /**
     * 100k × 100 字符的批量往返。旧实现每字符两次 ensureCapacity/虚调用,
     * 这里用宽松阈值 3s 防止再退化回逐字符路径(新实现本机 ~0.2s)。
     */
    @Test
    public void testBulkRoundTripPerformance() throws IOException {
        int count = 100_000;
        String value = repeat("abcdefghij", 10); // 100 chars
        String[] strings = new String[count];
        Arrays.fill(strings, value);

        long start = System.nanoTime();
        byte[] packet = writePacket(strings);
        TransferInputStream in = inputStream(
                Arrays.copyOfRange(packet, 4 + 9, packet.length));
        for (int i = 0; i < count; i++)
            assertTrue(value.equals(in.readString()));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[StringCodecTest] " + count + " x " + value.length()
                + " chars round-trip: " + millis + " ms");
        assertTrue("字符串批量编解码耗时 " + millis + "ms, 超过 3000ms, 疑似退化回逐字符路径",
                millis < 3000);
    }
}
