/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;

import org.junit.Test;

import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.value.ReadonlyBlob;
import com.lealone.db.value.ReadonlyClob;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueLob;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueUuid;
import com.lealone.db.value.ValueResultSet;
import com.lealone.test.sql.SqlTestBase;

/**
 * SimpleResultSet 里带 CLOB/BLOB 列时的取值。
 * <p>
 * Value.getObject() 对 CLOB 返回 Reader、对 BLOB 返回 InputStream，老代码在 getString/getBytes 里
 * 直接强转成 Clob/byte[]，于是 SETOF/RETURNS TABLE 函数结果集这类"Value 塞进 SimpleResultSet"的路径
 * 一取值就 50000（ValueResultSet.getString 走 DataType.readValue(null, ...) 时最明显）。
 */
public class LobResultSetTest extends SqlTestBase {

    private static final String TEXT = "hello";
    private static final byte[] BYTES = { 'a', 'b', 'c' };

    public LobResultSetTest() {
        setEmbedded(true);
    }

    // Value.getObject() 给出的 Reader/InputStream(引擎自己造的 Lob 值就是这样存的)
    @Test
    public void testReaderAndInputStream() throws Exception {
        Value clob = ValueLob.createSmallLob(Value.CLOB, TEXT.getBytes(StandardCharsets.UTF_8));
        Value blob = ValueLob.createSmallLob(Value.BLOB, BYTES);
        assertTrue(clob.getObject() instanceof Reader);
        assertTrue(blob.getObject() instanceof InputStream);

        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(clob.getObject(), blob.getObject());
        rs.addRow(null, null);
        assertTrue(rs.next());
        // 老代码在CLOB列上强转Clob: ClassCastException BufferedReader cannot be cast to Clob
        assertEquals(TEXT, rs.getString(1));
        // Reader只能读一次, 读过的值要缓存回当前行, 第二次还能拿到
        assertEquals(TEXT, rs.getString(1));
        // 老代码在BLOB列上强转byte[]: ClassCastException ByteArrayInputStream cannot be cast to byte[]
        assertArrayEquals(BYTES, rs.getBytes(2));
        assertArrayEquals(BYTES, rs.getBytes(2));
        assertEquals(TEXT, rs.getString("C"));
        assertArrayEquals(BYTES, rs.getBytes("B"));
        assertTrue(rs.getObject(1) instanceof Clob);
        assertTrue(rs.getObject(2) instanceof Blob);
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        assertTrue(rs.wasNull());
        assertNull(rs.getBytes(2));
        assertTrue(rs.wasNull());
        assertFalse(rs.next());
    }

    // 流式取值也要能用, 值读出来之后换成String/byte[], 再读还是同样的内容
    @Test
    public void testStreams() throws Exception {
        Value clob = ValueLob.createSmallLob(Value.CLOB, TEXT.getBytes(StandardCharsets.UTF_8));
        Value blob = ValueLob.createSmallLob(Value.BLOB, BYTES);

        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(clob.getObject(), blob.getObject());
        assertTrue(rs.next());
        assertEquals(TEXT, readString(rs.getCharacterStream(1)));
        assertArrayEquals(BYTES, readBytes(rs.getBinaryStream(2)));
        // Stream读过之后getString/getBytes仍然能拿到值
        assertEquals(TEXT, rs.getString(1));
        assertArrayEquals(BYTES, rs.getBytes(2));

        SimpleResultSet rs2 = newSimpleResultSet();
        rs2.addRow(new StringReader(TEXT), new ByteArrayInputStream(BYTES));
        assertTrue(rs2.next());
        assertEquals(TEXT, readString(rs2.getCharacterStream(1)));
        assertArrayEquals(BYTES, readBytes(rs2.getBinaryStream(2)));
        assertEquals("((hello, abc))", ValueResultSet.get(rs2).getString());
    }

    // getClob/getBlob 不能把Reader/InputStream直接交给调用方
    @Test
    public void testClobAndBlob() throws Exception {
        Value clob = ValueLob.createSmallLob(Value.CLOB, TEXT.getBytes(StandardCharsets.UTF_8));
        Value blob = ValueLob.createSmallLob(Value.BLOB, BYTES);

        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(clob.getObject(), blob.getObject());
        assertTrue(rs.next());
        Clob c = rs.getClob(1);
        assertEquals(TEXT, c.getSubString(1, TEXT.length()));
        assertEquals(TEXT.length(), c.length());
        Blob b = rs.getBlob(2);
        assertArrayEquals(BYTES, b.getBytes(1, BYTES.length));
        assertEquals(BYTES.length, b.length());
        // 再取一次还是同样的值
        assertEquals(TEXT, rs.getClob(1).getSubString(1, TEXT.length()));
        assertArrayEquals(BYTES, rs.getBlob(2).getBytes(1, BYTES.length));
    }

    // 直接把Value放进SimpleResultSet也要能用
    @Test
    public void testValueInResultSet() throws Exception {
        Value clob = ValueLob.createSmallLob(Value.CLOB, TEXT.getBytes(StandardCharsets.UTF_8));
        Value blob = ValueLob.createSmallLob(Value.BLOB, BYTES);

        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(clob, blob);
        assertTrue(rs.next());
        assertEquals(TEXT, rs.getString(1));
        assertArrayEquals(BYTES, rs.getBytes(2));
        assertEquals(TEXT, readString(rs.getCharacterStream(1)));
        assertEquals(TEXT, rs.getClob(1).getSubString(1, TEXT.length()));
        assertArrayEquals(BYTES, rs.getBlob(2).getBytes(1, BYTES.length));
        assertTrue(rs.getObject(1) instanceof ReadonlyClob);
        assertTrue(rs.getObject(2) instanceof ReadonlyBlob);
        // ValueResultSet.getString 内部用 DataType.readValue(null, ...), 走的就是上面的取值方法
        rs.beforeFirst();
        assertEquals("((hello, abc))", ValueResultSet.get(rs).getString());
    }

    // Value.getResultSet() 也是把Value.getObject()直接放进SimpleResultSet
    @Test
    public void testValueGetResultSet() throws Exception {
        Value clob = ValueLob.createSmallLob(Value.CLOB, TEXT.getBytes(StandardCharsets.UTF_8));
        try (ResultSet rs = clob.getResultSet()) {
            assertTrue(rs.next());
            assertEquals(TEXT, rs.getString(1));
            assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), rs.getBytes(1));
        }
        Value blob = ValueLob.createSmallLob(Value.BLOB, BYTES);
        try (ResultSet rs = blob.getResultSet()) {
            assertTrue(rs.next());
            assertArrayEquals(BYTES, rs.getBytes(1));
            assertEquals(new String(BYTES, StandardCharsets.UTF_8), rs.getString(1));
        }
    }

    // SQL的NULL值(ValueNull)按null处理
    @Test
    public void testValueNull() throws Exception {
        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(ValueNull.INSTANCE, ValueNull.INSTANCE);
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        assertTrue(rs.wasNull());
        assertNull(rs.getBytes(2));
        assertTrue(rs.wasNull());
        assertNull(rs.getObject(1));
        assertTrue(rs.wasNull());
        assertNull(rs.getClob(1));
        assertNull(rs.getBlob(2));
    }

    // 用户自己放Clob/Blob对象的既有用法不能被破坏
    @Test
    public void testUserClobAndBlobObjects() throws Exception {
        SimpleResultSet rs = newSimpleResultSet();
        rs.addRow(new ReadonlyClob(TEXT), new ReadonlyBlob(BYTES));
        assertTrue(rs.next());
        assertEquals(TEXT, rs.getString(1));
        assertArrayEquals(BYTES, rs.getBytes(2));
        assertEquals(TEXT, readString(rs.getCharacterStream(1)));
        assertArrayEquals(BYTES, readBytes(rs.getBinaryStream(2)));
        assertTrue(rs.getObject(1) instanceof Clob);
        assertTrue(rs.getObject(2) instanceof Blob);
        rs.beforeFirst();
        assertEquals("((hello, abc))", ValueResultSet.get(rs).getString());
    }

    // 端到端: RESULT_SET值在值上下文里靠ValueResultSet.getString()拼字符串, 老代码在这里报50000
    @Test
    public void testValueResultSetFromAlias() throws Exception {
        executeUpdate("CREATE ALIAS IF NOT EXISTS lob_alias_rs FOR \""
                + LobResultSetTest.class.getName() + ".getLobAliasResultSet\"");
        try (ResultSet result = stmt.executeQuery("SELECT lob_alias_rs()")) {
            assertTrue(result.next());
            assertEquals("((hello, abc))", result.getString(1));
            // 同一个值读两次, 第二次还是完整内容
            assertEquals("((hello, abc))", result.getString(1));
        }
        executeUpdate("DROP ALIAS IF EXISTS lob_alias_rs");
    }

    // SETOF/RETURNS TABLE 函数返回CLOB/BLOB列: 引擎把Value.getObject()放进SimpleResultSet的典型路径
    @Test
    public void testSetofFunctionWithLobColumns() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS lob_src");
        executeUpdate("CREATE TABLE lob_src(id INT, c CLOB, b BLOB)");
        executeUpdate("INSERT INTO lob_src(id, c) VALUES(1, 'hello')");
        executeUpdate("CREATE OR REPLACE FUNCTION lob_table_fn() RETURNS TABLE(c CLOB, b BLOB) AS "
                + "$$ SELECT c, b FROM lob_src $$");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM lob_table_fn()")) {
            assertTrue(result.next());
            assertEquals(TEXT, result.getString(1));
            // BLOB列这里是NULL
            assertNull(result.getBytes(2));
        }
        executeUpdate("DELETE FROM lob_src");
        executeUpdate("INSERT INTO lob_src(id, b) SELECT 2, X'616263'");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM lob_table_fn()")) {
            assertTrue(result.next());
            assertNull(result.getString(1));
            assertArrayEquals(BYTES, result.getBytes(2));
        }
        executeUpdate("DROP FUNCTION lob_table_fn");
        executeUpdate("DROP TABLE lob_src");
    }

    // UUID列的Value.getObject()是java.util.UUID, 读取方(DataType.readValue)用getBytes,
    // 修复前这里是 ClassCastException: java.util.UUID cannot be cast to [B
    @Test
    public void testUuidColumn() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS lob_uuid_src");
        executeUpdate("CREATE TABLE lob_uuid_src(id INT, v UUID)");
        executeUpdate("INSERT INTO lob_uuid_src VALUES(1, random_uuid())");
        UUID uuid;
        try (ResultSet result = stmt.executeQuery("SELECT v FROM lob_uuid_src")) {
            assertTrue(result.next());
            uuid = (UUID) result.getObject(1);
        }
        ValueUuid value = ValueUuid.get(uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits());
        SimpleResultSet rs = new SimpleResultSet();
        rs.setAutoClose(false);
        rs.addColumn("U", Types.BINARY, 16, 0);
        rs.addRow(uuid);
        assertTrue(rs.next());
        assertArrayEquals(value.getBytes(), rs.getBytes(1));
        assertArrayEquals(value.getBytes(), rs.getBytes(1));

        executeUpdate("CREATE OR REPLACE FUNCTION uuid_table_fn() RETURNS TABLE(v UUID) AS "
                + "$$ SELECT v FROM lob_uuid_src $$");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM uuid_table_fn()")) {
            assertTrue(result.next());
            // 函数结果里UUID列现在是VARBINARY, 字节内容必须和原值一致
            assertArrayEquals(value.getBytes(), result.getBytes(1));
        }
        executeUpdate("DROP FUNCTION uuid_table_fn");
        executeUpdate("DROP TABLE lob_uuid_src");
    }

    public static ResultSet getLobAliasResultSet() {
        SimpleResultSet rs = new SimpleResultSet();
        rs.setAutoClose(false);
        rs.addColumn("C", Types.CLOB, 100, 0);
        rs.addColumn("B", Types.BLOB, 100, 0);
        rs.addRow(new StringReader(TEXT), new ByteArrayInputStream(BYTES));
        return rs;
    }

    private static SimpleResultSet newSimpleResultSet() {
        SimpleResultSet rs = new SimpleResultSet();
        rs.setAutoClose(false);
        rs.addColumn("C", Types.CLOB, 100, 0);
        rs.addColumn("B", Types.BLOB, 100, 0);
        return rs;
    }

    private static String readString(Reader in) throws Exception {
        if (in == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[64];
        int len;
        while ((len = in.read(buffer)) >= 0) {
            builder.append(buffer, 0, len);
        }
        return builder.toString();
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int len;
        while ((len = in.read(buffer)) >= 0) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }
}
