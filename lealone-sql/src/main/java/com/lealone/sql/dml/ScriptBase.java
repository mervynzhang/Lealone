/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.dml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.lealone.common.compress.CompressTool;
import com.lealone.common.exceptions.DbException;
import com.lealone.common.security.SHA256;
import com.lealone.common.util.IOUtils;
import com.lealone.db.Constants;
import com.lealone.db.Database;
import com.lealone.db.SysProperties;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.expression.Expression;
import com.lealone.storage.fs.FileStorage;
import com.lealone.storage.fs.FileStorageInputStream;
import com.lealone.storage.fs.FileStorageOutputStream;
import com.lealone.storage.fs.FileUtils;

/**
 * This class is the base for RunScript and GenScript.
 */
public abstract class ScriptBase extends ManipulationStatement {

    /**
     * The default name of the script file if .zip compression is used.
     */
    private static final String SCRIPT_SQL = "script.sql";

    /**
     * The output stream.
     */
    protected OutputStream out;

    /**
     * The input stream.
     */
    protected InputStream in;

    /**
     * The file name (if set).
     */
    private Expression fileNameExpr;

    private Expression password;

    private String fileName;

    private String cipher;
    private FileStorage fileStorage;
    private String compressionAlgorithm;

    public ScriptBase(ServerSession session) {
        super(session);
    }

    public void setCipher(String c) {
        cipher = c;
    }

    protected boolean isEncrypted() {
        return cipher != null;
    }

    /**
     * 加密脚本的固定标记，用来在RUNSCRIPT时确定密码是否正确。
     * 没有它的时候，密码错误会把解出来的垃圾数据当SQL执行，报错位置随机，
     * 有时甚至没有任何语句被执行而"静默成功"。
     */
    protected static final byte[] SCRIPT_MAGIC = "LealoneScript\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * 在加密脚本的开头写入标记(标记本身也在加密流里)。
     */
    protected void writeMagic() throws java.io.IOException {
        out.write(SCRIPT_MAGIC, 0, SCRIPT_MAGIC.length);
    }

    /**
     * 校验加密脚本开头的标记，密码不对时报明确的加密错误。
     */
    protected void verifyMagic() {
        byte[] buff = new byte[SCRIPT_MAGIC.length];
        int len = 0;
        try {
            while (len < buff.length) {
                int n = in.read(buff, len, buff.length - len);
                if (n < 0)
                    break;
                len += n;
            }
        } catch (java.io.IOException e) {
            throw com.lealone.common.exceptions.DbException.convertIOException(e, getFileName());
        }
        boolean ok = len == buff.length;
        if (ok) {
            for (int i = 0; i < buff.length; i++)
                if (buff[i] != SCRIPT_MAGIC[i])
                    ok = false;
        }
        if (!ok)
            throw com.lealone.common.exceptions.DbException
                    .get(com.lealone.db.api.ErrorCode.FILE_ENCRYPTION_ERROR_1, getFileName());
    }

    public void setPassword(Expression password) {
        this.password = password;
    }

    public void setFileNameExpr(Expression file) {
        this.fileNameExpr = file;
    }

    protected String getFileName() {
        if (fileNameExpr != null && fileName == null) {
            fileName = fileNameExpr.optimize(session).getValue(session).getString();
            if (fileName == null || fileName.trim().length() == 0) {
                fileName = "script.sql";
            }
            // 如果是一个不存在且不包含目录的文件名，自动加上ScriptDirectory
            if (!new File(fileName).exists() && FileUtils.getName(fileName).equals(fileName)) {
                String dir = SysProperties.getScriptDirectory();
                fileName = FileUtils.getDirWithSeparator(dir) + fileName;
            }
        }
        return fileName;
    }

    /**
     * Delete the target file.
     */
    void deleteStore() {
        String file = getFileName();
        if (file != null) {
            FileUtils.delete(file);
        }
    }

    private void initStore() {
        Database db = session.getDatabase();
        byte[] key = null;
        if (cipher != null && password != null) {
            char[] pass = password.optimize(session).getValue(session).getString().toCharArray();
            key = SHA256.getKeyPasswordHash("script", pass);
        }
        String file = getFileName();
        fileStorage = FileStorage.open(db, file, "rw", cipher, key);
        fileStorage.setCheckedWriting(false);
    }

    /**
     * Open the output stream.
     */
    void openOutput() {
        String file = getFileName();
        if (file == null) {
            return;
        }
        if (isEncrypted()) {
            initStore();
            out = new FileStorageOutputStream(fileStorage, session.getDatabase(), compressionAlgorithm);
            // always use a big buffer, otherwise end-of-block is written a lot
            out = new BufferedOutputStream(out, Constants.IO_BUFFER_SIZE_COMPRESS);
        } else {
            OutputStream o;
            try {
                o = FileUtils.newOutputStream(file, false);
            } catch (IOException e) {
                throw DbException.convertIOException(e, null);
            }
            out = new BufferedOutputStream(o, Constants.IO_BUFFER_SIZE);
            out = CompressTool.wrapOutputStream(out, compressionAlgorithm, SCRIPT_SQL);
        }
    }

    /**
     * Open the input stream.
     */
    void openInput() {
        String file = getFileName();
        if (file == null) {
            return;
        }
        if (isEncrypted()) {
            initStore();
            in = new FileStorageInputStream(fileStorage, session.getDatabase(),
                    compressionAlgorithm != null, false);
        } else {
            InputStream inStream;
            try {
                inStream = FileUtils.newInputStream(file);
            } catch (IOException e) {
                throw DbException.convertIOException(e, file);
            }
            in = new BufferedInputStream(inStream, Constants.IO_BUFFER_SIZE);
            in = CompressTool.wrapInputStream(in, compressionAlgorithm, SCRIPT_SQL);
            if (in == null) {
                throw DbException.get(ErrorCode.FILE_NOT_FOUND_1, SCRIPT_SQL + " in " + file);
            }
        }
    }

    /**
     * Close input and output streams.
     */
    void closeIO() {
        IOUtils.closeSilently(out);
        out = null;
        IOUtils.closeSilently(in);
        in = null;
        if (fileStorage != null) {
            fileStorage.closeSilently();
            fileStorage = null;
        }
    }

    @Override
    public boolean needRecompile() {
        return false;
    }

    public void setCompressionAlgorithm(String algorithm) {
        this.compressionAlgorithm = algorithm;
    }
}
