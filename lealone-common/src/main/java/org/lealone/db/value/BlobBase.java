/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db.value;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.TraceObject;
import org.lealone.common.util.IOUtils;
import org.lealone.db.Constants;
import org.lealone.db.api.ErrorCode;

/**
 * Represents a BLOB value.
 */
public abstract class BlobBase extends TraceObject implements Blob {

    protected Value value;

    public Value getValue() {
        return value;
    }

    /**
     * Returns the length.
     *
     * @return the length
     * @throws SQLException
     */
    @Override
    public long length() throws SQLException {
        try {
            debugCodeCall("length");
            checkClosed();
            if (value.getType() == Value.BLOB) {
                long precision = value.getPrecision();
                if (precision > 0) {
                    return precision;
                }
            }
            return IOUtils.copyAndCloseInput(value.getInputStream(), null);
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * [Not supported] Truncates the object.
     *
     * @param len the new length
     * @throws SQLException
     */
    @Override
    public void truncate(long len) throws SQLException {
        throw unsupported("LOB update");
    }

    /**
     * Returns some bytes of the object.
     *
     * @param pos the index, the first byte is at position 1
     * @param length the number of bytes
     * @return the bytes, at most length bytes
     * @throws SQLException
     */
    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        try {
            if (isDebugEnabled()) {
                debugCode("getBytes(" + pos + ", " + length + ");");
            }
            checkClosed();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = value.getInputStream();
            try {
                IOUtils.skipFully(in, pos - 1);
                IOUtils.copy(in, out, length);
            } finally {
                in.close();
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Fills the Blob. This is only supported for new, empty Blob objects that
     * were created with Connection.createBlob(). The position
     * must be 1, meaning the whole Blob data is set.
     *
     * @param pos where to start writing (the first byte is at position 1)
     * @param bytes the bytes to set
     * @return the length of the added data
     */
    @Override
    public abstract int setBytes(long pos, byte[] bytes) throws SQLException;

    /**
     * [Not supported] Sets some bytes of the object.
     *
     * @param pos the write position
     * @param bytes the bytes to set
     * @param offset the bytes offset
     * @param len the number of bytes to write
     * @return how many bytes have been written
     * @throws SQLException
     */
    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        throw unsupported("LOB update");
    }

    /**
     * Returns the input stream.
     *
     * @return the input stream
     * @throws SQLException
     */
    @Override
    public InputStream getBinaryStream() throws SQLException {
        try {
            debugCodeCall("getBinaryStream");
            checkClosed();
            return value.getInputStream();
        } catch (Exception e) {
            throw logAndConvert(e);
        }
    }

    /**
     * Get a writer to update the Blob. This is only supported for new, empty
     * Blob objects that were created with Connection.createBlob(). The Blob is
     * created in a separate thread, and the object is only updated when
     * OutputStream.close() is called. The position must be 1, meaning the whole
     * Blob data is set.
     *
     * @param pos where to start writing (the first byte is at position 1)
     * @return an output stream
     */
    @Override
    public abstract OutputStream setBinaryStream(long pos) throws SQLException;

    /**
     * [Not supported] Searches a pattern and return the position.
     *
     * @param pattern the pattern to search
     * @param start the index, the first byte is at position 1
     * @return the position (first byte is at position 1), or -1 for not found
     * @throws SQLException
     */
    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("position(" + quoteBytes(pattern) + ", " + start + ");");
        }
        if (Constants.BLOB_SEARCH) {
            try {
                checkClosed();
                if (pattern == null) {
                    return -1;
                }
                if (pattern.length == 0) {
                    return 1;
                }
                // TODO performance: blob pattern search is slow
                BufferedInputStream in = new BufferedInputStream(value.getInputStream());
                IOUtils.skipFully(in, start - 1);
                int pos = 0;
                int patternPos = 0;
                while (true) {
                    int x = in.read();
                    if (x < 0) {
                        break;
                    }
                    if (x == (pattern[patternPos] & 0xff)) {
                        if (patternPos == 0) {
                            in.mark(pattern.length);
                        }
                        if (patternPos == pattern.length) {
                            return pos - patternPos;
                        }
                        patternPos++;
                    } else {
                        if (patternPos > 0) {
                            in.reset();
                            pos -= patternPos;
                        }
                    }
                    pos++;
                }
                return -1;
            } catch (Exception e) {
                throw logAndConvert(e);
            }
        }
        throw unsupported("LOB search");
    }

    /**
     * [Not supported] Searches a pattern and return the position.
     *
     * @param blobPattern the pattern to search
     * @param start the index, the first byte is at position 1
     * @return the position (first byte is at position 1), or -1 for not found
     * @throws SQLException
     */
    @Override
    public long position(Blob blobPattern, long start) throws SQLException {
        if (isDebugEnabled()) {
            debugCode("position(blobPattern, " + start + ");");
        }
        if (Constants.BLOB_SEARCH) {
            try {
                checkClosed();
                if (blobPattern == null) {
                    return -1;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                InputStream in = blobPattern.getBinaryStream();
                while (true) {
                    int x = in.read();
                    if (x < 0) {
                        break;
                    }
                    out.write(x);
                }
                return position(out.toByteArray(), start);
            } catch (Exception e) {
                throw logAndConvert(e);
            }
        }
        throw unsupported("LOB subset");
    }

    /**
     * Release all resources of this object.
     */
    @Override
    public void free() {
        debugCodeCall("free");
        value = null;
    }

    /**
     * [Not supported] Returns the input stream, starting from an offset.
     *
     * @param pos where to start reading
     * @param length the number of bytes that will be read
     * @return the input stream to read
     * @throws SQLException
     */
    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        throw unsupported("LOB update");
    }

    protected void checkClosed() {
        if (value == null) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED);
        }
    }

    /**
     * INTERNAL
     */
    @Override
    public String toString() {
        return getTraceObjectName() + ": " + (value == null ? "null" : value.getTraceSQL());
    }
}
