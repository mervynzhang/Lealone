/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.fs;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.junit.Test;

import com.lealone.common.security.SHA256;
import com.lealone.db.Constants;
import com.lealone.storage.fs.FileStorage;
import com.lealone.storage.fs.FileStorageInputStream;
import com.lealone.storage.fs.FileStorageOutputStream;
import com.lealone.storage.fs.FileUtils;
import com.lealone.test.TestBase;

public class FileStorageTest extends FsTestBase {

    public static String joinDirs(String... dirs) {
        String path = TestBase.joinDirs(dirs);
        File dir = new File(path);
        if (!dir.getParentFile().exists())
            dir.getParentFile().mkdirs();
        return path;
    }

    @Test
    public void run() {
        FileStorage fs = FileStorage.open(null, "nio:./src/test/resources/lealone-test.sql", "r");
        assertTrue(fs.length() > 0);
    }

    @Test
    public void testEncrypt() {
        byte[] keyHash = SHA256.getKeyPasswordHash("FileStorageTest", "mypassword".toCharArray());
        String fileName = joinDirs("FileStorageTest", "EncryptFileStorageTest.dat");
        FileStorage fs = FileStorage.open(null, fileName, "rw", "XTEA", keyHash);

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            buffer.append("1234567890");
        }
        byte[] bytes = buffer.toString().getBytes();
        fs.writeFully(0, ByteBuffer.wrap(bytes));
        fs.close();

        // 在SHA256.getPBKDF2(byte[], byte[], int, int)中会清空keyHash
        keyHash = SHA256.getKeyPasswordHash("FileStorageTest", "mypassword".toCharArray());
        fs = FileStorage.open(null, fileName, "r", "XTEA", keyHash);
        ByteBuffer bb = fs.readFully(0, bytes.length);
        assertTrue(buffer.toString().equals(new String(bb.array(), bb.arrayOffset(), bytes.length)));
        fs.close();
    }

    @Test
    public void testStream() throws Exception {
        testStream("LZF");
        testStream(null);
    }

    // 块长度是垃圾数据时(比如用错误密码读加密脚本，块头是解出来的垃圾)不能按这个长度去分配内存，
    // 修复前这里抛OutOfMemoryError，会让整个数据库被强行关闭
    @Test
    public void testCorruptedChunkLength() throws Exception {
        String fileName = joinDirs("FileStorageTest", "CorruptedChunkLength.dat");
        byte[] bytes = new byte[Constants.FILE_BLOCK_SIZE * 2];
        ByteBuffer.wrap(bytes).putInt(Integer.MAX_VALUE);
        OutputStream out = FileUtils.newOutputStream(fileName, false);
        out.write(bytes);
        out.close();

        FileStorage fs = FileStorage.open(null, fileName, "r");
        FileStorageInputStream in = new FileStorageInputStream(fs, null, false, false);
        // 当成文件已结束处理，让调用者(比如RUNSCRIPT校验密码)自己去报文件损坏或密码错误
        assertEquals(-1, in.read());
        in.close();
        fs.close();
    }

    // 块长度为正但超出文件剩余范围(不是 Integer.MAX_VALUE 那种极端值)时也是同一类损坏:
    // 必须按读到末尾处理(不能按这个长度去分配内存), 同时留一条 warn 日志便于排查
    @Test
    public void testChunkLengthExceedsFileSize() throws Exception {
        String fileName = joinDirs("FileStorageTest", "ChunkLengthTooLarge.dat");
        byte[] bytes = new byte[Constants.FILE_BLOCK_SIZE * 2];
        // 声明一个比文件剩余空间大得多的块, 但还远没到 Integer.MAX_VALUE
        ByteBuffer.wrap(bytes).putInt(Constants.FILE_BLOCK_SIZE * 1024);
        OutputStream out = FileUtils.newOutputStream(fileName, false);
        out.write(bytes);
        out.close();

        FileStorage fs = FileStorage.open(null, fileName, "r");
        FileStorageInputStream in = new FileStorageInputStream(fs, null, false, false);
        assertEquals(-1, in.read());
        in.close();
        fs.close();
    }

    private void testStream(String compressionAlgorithm) throws Exception {
        boolean compression = compressionAlgorithm != null;
        String fileName = "FileStorageStreamTest.dat";
        if (compression)
            fileName = compressionAlgorithm + fileName;
        fileName = joinDirs("FileStorageTest", fileName);
        FileStorage fs = FileStorage.open(null, fileName, "rw");
        FileStorageOutputStream out = new FileStorageOutputStream(fs, null, compressionAlgorithm);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            buffer.append("1234567890");
        }
        byte[] bytes = buffer.toString().getBytes();
        out.write(bytes);
        String str = "abc1234567890def";
        out.write(str.getBytes());
        out.close();
        fs.close();

        byte[] bytes2 = new byte[bytes.length];
        fs = FileStorage.open(null, fileName, "r");
        FileStorageInputStream in = new FileStorageInputStream(fs, null, compression, false);
        in.read(bytes2);
        assertTrue(buffer.toString().equals(new String(bytes2)));
        bytes2 = new byte[str.getBytes().length];
        in.read(bytes2);
        assertTrue(str.equals(new String(bytes2)));
        in.close();
        fs.close();
    }
}
