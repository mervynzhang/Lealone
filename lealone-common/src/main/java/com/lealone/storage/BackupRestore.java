/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.lealone.common.exceptions.DbException;

// 把备份包恢复到目标目录，增量包要求目标目录里已经有基准包的内容，失败时不留下半成品
public class BackupRestore {

    private static final String TMP_DIR = ".restore_tmp";

    public static void restore(File zipFile, File targetDir) throws IOException {
        if (!zipFile.exists()) {
            throw DbException.getInternalError("backup file not found: " + zipFile);
        }
        BackupManifest manifest = BackupManifest.read(zipFile);
        if (manifest.isIncremental()) {
            BackupManifest base = BackupManifest.readBase(zipFile);
            if (base == null) {
                throw DbException.getInternalError("incremental backup has no base manifest: " + zipFile);
            }
            if (!manifest.getBaseId().equals(base.getId())) {
                throw DbException.getInternalError(
                        "base manifest id mismatch, backup chain is broken: " + zipFile);
            }
            // 基准包必须已经完整恢复过
            verifyApplied(base, targetDir);
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File tmpDir = new File(targetDir, TMP_DIR);
        deleteRecursive(tmpDir);
        tmpDir.mkdirs();
        boolean ok = false;
        try {
            extractAndVerify(zipFile, manifest, tmpDir);
            // 先按删除清单删掉基准包里有、现在已淘汰的旧chunk，避免残留
            for (String name : manifest.getDeleted()) {
                File f = new File(targetDir, name);
                if (f.exists() && !f.delete()) {
                    throw DbException.getInternalError("can not delete outdated chunk: " + f);
                }
            }
            moveAll(tmpDir, targetDir);
            ok = true;
        } finally {
            deleteRecursive(tmpDir);
            if (!ok) {
                // 失败时已经写入的文件由调用方按manifest重新恢复，这里只保证不残留临时文件
            }
        }
    }

    private static void extractAndVerify(File zipFile, BackupManifest manifest, File tmpDir)
            throws IOException {
        byte[] buff = new byte[8192];
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (BackupManifest.FILE_NAME.equals(name) || BackupManifest.BASE_FILE_NAME.equals(name)) {
                    continue;
                }
                BackupManifest.Entry me = manifest.getEntry(name);
                if (me == null) {
                    throw DbException.getInternalError("entry not found in manifest: " + name);
                }
                File f = new File(tmpDir, name);
                File parent = f.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                CRC32 crc = new CRC32();
                long size = 0;
                try (OutputStream out = new FileOutputStream(f)) {
                    int read;
                    while ((read = in.read(buff)) > 0) {
                        out.write(buff, 0, read);
                        crc.update(buff, 0, read);
                        size += read;
                    }
                }
                if (size != me.size || crc.getValue() != me.crc32) {
                    throw DbException.getInternalError("entry is corrupted, size or crc32 mismatch: " + name);
                }
            }
        }
    }

    private static void verifyApplied(BackupManifest base, File targetDir) throws IOException {
        byte[] buff = new byte[8192];
        for (BackupManifest.Entry e : base.getEntries()) {
            File f = new File(targetDir, e.name);
            if (!f.exists()) {
                throw DbException.getInternalError("backup chain is incomplete, missing: " + e.name);
            }
            CRC32 crc = new CRC32();
            long size = 0;
            try (InputStream in = new FileInputStream(f)) {
                int read;
                while ((read = in.read(buff)) > 0) {
                    crc.update(buff, 0, read);
                    size += read;
                }
            }
            if (size != e.size || crc.getValue() != e.crc32) {
                throw DbException.getInternalError("backup chain is broken, file changed: " + e.name);
            }
        }
    }

    private static void moveAll(File fromDir, File toDir) throws IOException {
        File[] files = fromDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            File to = new File(toDir, f.getName());
            if (f.isDirectory()) {
                to.mkdirs();
                moveAll(f, to);
            } else {
                if (to.exists() && !to.delete()) {
                    throw DbException.getInternalError("can not overwrite: " + to);
                }
                if (!f.renameTo(to)) {
                    throw DbException.getInternalError("can not move: " + f);
                }
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File c : files) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
