/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.IOUtils;
import com.lealone.db.Constants;
import com.lealone.db.scheduler.SchedulerFactory;
import com.lealone.storage.fs.FilePath;
import com.lealone.storage.fs.FileUtils;

//子类通常只需要实现openMap方法
public abstract class StorageBase implements Storage {

    protected static final String TEMP_NAME_PREFIX = Constants.NAME_SEPARATOR + "temp"
            + Constants.NAME_SEPARATOR;

    protected final Map<StorageEventListener, StorageEventListener> listeners = new ConcurrentHashMap<>();
    protected final Map<String, StorageMap<?, ?>> maps = new ConcurrentHashMap<>();
    protected final Map<String, Object> config;
    protected boolean closed;

    protected SchedulerFactory schedulerFactory;

    public StorageBase(Map<String, Object> config) {
        this.config = config;
    }

    @Override
    public void closeMap(String name) {
        StorageMap<?, ?> map = maps.remove(name);
        if (map != null)
            map.close();
    }

    @Override
    public boolean hasMap(String name) {
        return maps.containsKey(name);
    }

    @Override
    public StorageMap<?, ?> getMap(String name) {
        return maps.get(name);
    }

    @Override
    public Set<String> getMapNames() {
        return new HashSet<>(maps.keySet());
    }

    @Override
    public String nextTemporaryMapName() {
        int i = 0;
        String name = null;
        while (true) {
            name = TEMP_NAME_PREFIX + i++;
            if (!maps.containsKey(name))
                return name;
        }
    }

    @Override
    public String getStoragePath() {
        return (String) config.get(StorageSetting.STORAGE_PATH.name());
    }

    @Override
    public boolean isInMemory() {
        return false;
    }

    @Override
    public long getDiskSpaceUsed() {
        long total = 0;
        for (StorageMap<?, ?> map : maps.values())
            total += map.getDiskSpaceUsed();
        return total;
    }

    @Override
    public long getMemorySpaceUsed() {
        long total = 0;
        for (StorageMap<?, ?> map : maps.values())
            total += map.getMemorySpaceUsed();
        return total;
    }

    @Override
    public void save() {
        for (StorageMap<?, ?> map : maps.values())
            map.save();
    }

    @Override
    public void drop() {
        close();
        if (!isInMemory())
            FileUtils.deleteRecursive(getStoragePath(), false);
    }

    @Override
    public void backupTo(String fileName, Long lastDate) {
        backupTo(fileName, StorageMapFilter.createLastDateFilter(lastDate));
    }

    @Override
    public void backupTo(String baseDir, ZipOutputStream out, Long lastDate) {
        backupTo(baseDir, out, StorageMapFilter.createLastDateFilter(lastDate));
    }

    @Override
    public void backupTo(String fileName, StorageMapFilter filter) {
        if (isInMemory())
            return;
        save();
        backupFiles(fileName, filter);
    }

    @Override
    public void backupTo(String baseDir, ZipOutputStream out, StorageMapFilter filter) {
        if (isInMemory())
            return;
        save();
        try {
            backupFiles(baseDir, out, filter);
        } catch (IOException e) {
            throw DbException.convertIOException(e, "");
        }
    }

    @Override
    public void backupTo(String baseDir, BackupContext ctx) {
        if (isInMemory())
            return;
        save();
        try {
            backupFiles(baseDir, ctx);
        } catch (IOException e) {
            throw DbException.convertIOException(e, "");
        }
    }

    @Override
    public void close() {
        for (StorageEventListener listener : listeners.values())
            listener.beforeClose(this);
        listeners.clear();
        save();
        closeImmediately();
    }

    @Override
    public void closeImmediately() {
        closed = true;

        for (StorageMap<?, ?> map : maps.values())
            map.close();

        maps.clear();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void registerEventListener(StorageEventListener listener) {
        listeners.put(listener, listener);
    }

    @Override
    public void unregisterEventListener(StorageEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public SchedulerFactory getSchedulerFactory() {
        return schedulerFactory;
    }

    @Override
    public void setSchedulerFactory(SchedulerFactory schedulerFactory) {
        this.schedulerFactory = schedulerFactory;
    }

    protected InputStream getInputStream(String mapName, FilePath file) throws IOException {
        return file.newInputStream();
    }

    private void backupFiles(String toFile, StorageMapFilter filter) {
        try (ZipOutputStream out = createZipOutputStream(toFile)) {
            backupFiles(null, out, filter);
        } catch (IOException e) {
            throw DbException.convertIOException(e, toFile);
        }
    }

    private void backupFiles(String baseDir, ZipOutputStream out, StorageMapFilter filter)
            throws IOException {
        if (baseDir != null)
            baseDir = new File(baseDir).getCanonicalPath().replace('\\', '/');
        String path = new File(getStoragePath()).getCanonicalPath(); // 可能是一个文件或目录
        FilePath p = FilePath.get(path);
        if (p.isDirectory()) {
            String pathShortName = path.replace('\\', '/');
            if (baseDir != null && pathShortName.startsWith(baseDir))
                pathShortName = pathShortName.substring(baseDir.length() + 1);
            else
                pathShortName = pathShortName.substring(pathShortName.lastIndexOf('/') + 1);
            FilePath dir = FilePath.get(path);
            for (FilePath map : dir.newDirectoryStream()) {
                String mapName = map.getName();
                String entryNameBase = pathShortName + "/" + mapName;
                for (FilePath file : map.newDirectoryStream()) {
                    if (filter.accept(mapName, file))
                        backupFile(out, getInputStream(mapName, file),
                                entryNameBase + "/" + file.getName());
                }
            }
        } else {
            backupFile(out, p.newInputStream(), p.getName());
        }
    }

    private static void backupFile(ZipOutputStream out, InputStream in, String entryName)
            throws IOException {
        if (in == null)
            return;
        out.putNextEntry(new ZipEntry(entryName));
        IOUtils.copyAndCloseInput(in, out);
        out.closeEntry();
    }

    private void backupFiles(String baseDir, BackupContext ctx) throws IOException {
        if (baseDir != null)
            baseDir = new File(baseDir).getCanonicalPath().replace('\\', '/');
        String path = new File(getStoragePath()).getCanonicalPath();
        FilePath p = FilePath.get(path);
        if (p.isDirectory()) {
            String pathShortName = path.replace('\\', '/');
            if (baseDir != null && pathShortName.startsWith(baseDir))
                pathShortName = pathShortName.substring(baseDir.length() + 1);
            else
                pathShortName = pathShortName.substring(pathShortName.lastIndexOf('/') + 1);
            FilePath dir = FilePath.get(path);
            for (FilePath map : dir.newDirectoryStream()) {
                String mapName = map.getName();
                String entryNameBase = pathShortName + "/" + mapName;
                for (FilePath file : map.newDirectoryStream()) {
                    backupFile(ctx, entryNameBase + "/" + file.getName(), mapName, file);
                }
            }
        } else {
            backupFile(ctx, p.getName(), null, p);
        }
    }

    // 全量备份不需要先算crc再决定写不写: 每个文件都要写, 所以边写边算crc, 只读一遍。
    // 增量备份必须先算出size/crc才能和基准清单比对, 只有变化的文件才写入zip, 这时才需要两遍。
    private void backupFile(BackupContext ctx, String entryName, String mapName, FilePath file)
            throws IOException {
        if (!ctx.isIncremental()) {
            CRC32 crc = new CRC32();
            long size = 0;
            ZipOutputStream out = ctx.getZipOutputStream();
            try (InputStream in = open(mapName, file)) {
                if (in == null)
                    return;
                out.putNextEntry(new ZipEntry(entryName));
                byte[] buff = new byte[8192];
                int read;
                while ((read = in.read(buff)) > 0) {
                    crc.update(buff, 0, read);
                    out.write(buff, 0, read);
                    size += read;
                }
                out.closeEntry();
            }
            ctx.addPresent(entryName);
            ctx.addEntry(entryName, size, crc.getValue());
            return;
        }
        // 增量备份: 先流式计算size和crc32，只有新增或变化的文件才写入zip，全程不把文件读进内存
        long size;
        long crc32;
        CRC32 crc = new CRC32();
        size = 0;
        try (InputStream in = open(mapName, file)) {
            if (in == null)
                return;
            byte[] buff = new byte[8192];
            int read;
            while ((read = in.read(buff)) > 0) {
                crc.update(buff, 0, read);
                size += read;
            }
        }
        crc32 = crc.getValue();
        ctx.addPresent(entryName);
        if (!ctx.shouldWrite(entryName, size, crc32))
            return;
        ZipOutputStream out = ctx.getZipOutputStream();
        try (InputStream in = open(mapName, file)) {
            out.putNextEntry(new ZipEntry(entryName));
            byte[] buff = new byte[8192];
            int read;
            while ((read = in.read(buff)) > 0) {
                out.write(buff, 0, read);
            }
            out.closeEntry();
        }
        ctx.addEntry(entryName, size, crc32);
    }

    private InputStream open(String mapName, FilePath file) throws IOException {
        if (mapName == null)
            return file.newInputStream();
        return getInputStream(mapName, file);
    }

    public static ZipOutputStream createZipOutputStream(String fileName) throws IOException {
        if (!fileName.toLowerCase().endsWith(".zip"))
            fileName += ".zip";
        return new ZipOutputStream(FileUtils.newOutputStream(fileName, false));
    }
}
