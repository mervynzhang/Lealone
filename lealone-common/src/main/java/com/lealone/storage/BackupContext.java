/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipOutputStream;

// 备份过程中收集条目信息，最后写入manifest；有基准包时只写新增或变化的文件，并算出删除清单
public class BackupContext {

    private final ZipOutputStream out;
    private final BackupManifest base;
    private final BackupManifest baseManifestCopy;
    private final byte[] baseManifestBytes;
    private final BackupManifest result = new BackupManifest();
    private final Set<String> present = new HashSet<>();

    public BackupContext(ZipOutputStream out, BackupManifest base, byte[] baseManifestBytes) {
        this.out = out;
        this.base = base;
        this.baseManifestCopy = base;
        this.baseManifestBytes = baseManifestBytes;
    }

    public ZipOutputStream getZipOutputStream() {
        return out;
    }

    public boolean isIncremental() {
        return base != null;
    }

    public boolean shouldWrite(String name, long size, long crc32) {
        if (base == null) {
            return true;
        }
        BackupManifest.Entry e = base.getEntry(name);
        return e == null || e.size != size || e.crc32 != crc32;
    }

    public void addPresent(String name) {
        present.add(name);
    }

    public void addEntry(String name, long size, long crc32) {
        result.addEntry(new BackupManifest.Entry(name, size, crc32));
    }

    public void addDeleted(String name) {
        result.addDeleted(name);
    }

    public void finish() throws IOException {
        if (base != null) {
            result.setIncremental(base.getId());
            for (BackupManifest.Entry e : base.getEntries()) {
                if (!present.contains(e.name)) {
                    result.addDeleted(e.name);
                }
            }
        }
        result.computeId(baseManifestCopy == null ? null : baseManifestBytes);
        result.writeTo(out);
    }

    public BackupManifest getResult() {
        return result;
    }
}
