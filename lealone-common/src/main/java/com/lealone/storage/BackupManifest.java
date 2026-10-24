/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.lealone.common.exceptions.DbException;

// 备份包的清单，记录包类型、基准包id以及每个条目的大小和crc32，用于增量备份和链式恢复
public class BackupManifest {

    public static final String FILE_NAME = "BACKUP.MANIFEST";
    public static final String BASE_FILE_NAME = "BASE.MANIFEST";
    public static final String TYPE_FULL = "FULL";
    public static final String TYPE_INCREMENTAL = "INCREMENTAL";

    private static final int VERSION = 1;

    public static class Entry {
        public final String name;
        public final long size;
        public final long crc32;
        public final int chunkId;
        public final int sequence;

        public Entry(String name, long size, long crc32) {
            this.name = name;
            this.size = size;
            this.crc32 = crc32;
            String[] a = parseChunkFile(name);
            this.chunkId = a == null ? -1 : Integer.parseInt(a[0]);
            this.sequence = a == null ? -1 : Integer.parseInt(a[1]);
        }

        Entry(String name, long size, long crc32, int chunkId, int sequence) {
            this.name = name;
            this.size = size;
            this.crc32 = crc32;
            this.chunkId = chunkId;
            this.sequence = sequence;
        }
    }

    private String type = TYPE_FULL;
    private String baseId;
    private String id;
    private long created = System.currentTimeMillis();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private final Map<String, Entry> entryMap = new HashMap<>();

    public String getType() {
        return type;
    }

    public boolean isIncremental() {
        return TYPE_INCREMENTAL.equals(type);
    }

    public String getId() {
        return id;
    }

    public String getBaseId() {
        return baseId;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public Set<String> getDeleted() {
        return deleted;
    }

    public boolean contains(String name) {
        return entryMap.containsKey(name);
    }

    public Entry getEntry(String name) {
        return entryMap.get(name);
    }

    public void addEntry(Entry e) {
        entries.add(e);
        entryMap.put(e.name, e);
    }

    public void addDeleted(String name) {
        deleted.add(name);
    }

    // chunk文件名格式: c_[chunkId]_[sequence].db
    public static String[] parseChunkFile(String name) {
        int pos = name.lastIndexOf('/');
        String fileName = pos < 0 ? name : name.substring(pos + 1);
        if (!fileName.startsWith("c_") || !fileName.endsWith(".db")) {
            return null;
        }
        String str = fileName.substring(2, fileName.length() - 3);
        int p = str.indexOf('_');
        if (p <= 0) {
            return null;
        }
        try {
            Integer.parseInt(str.substring(0, p));
            Integer.parseInt(str.substring(p + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[] { str.substring(0, p), str.substring(p + 1) };
    }

    public void setIncremental(String baseId) {
        this.type = TYPE_INCREMENTAL;
        this.baseId = baseId;
    }

    // 先算id再写入，id的取值不包含id行本身
    public void computeId(byte[] baseManifestBytes) {
        StringBuilder buff = new StringBuilder();
        buff.append("VERSION=").append(VERSION).append('\n');
        buff.append("TYPE=").append(type).append('\n');
        if (baseId != null) {
            buff.append("BASE_ID=").append(baseId).append('\n');
        }
        buff.append("CREATED=").append(created).append('\n');
        for (Entry e : entries) {
            buff.append("ENTRY=").append(e.name).append('\t').append(e.size).append('\t')
                    .append(e.crc32).append('\t').append(e.chunkId).append('\t').append(e.sequence)
                    .append('\n');
        }
        for (String name : deleted) {
            buff.append("DEL=").append(name).append('\n');
        }
        byte[] bytes = buff.toString().getBytes(StandardCharsets.UTF_8);
        this.id = sha256(bytes);
        this.text = buff.toString();
        this.baseManifestBytes = baseManifestBytes;
    }

    private String text;
    private byte[] baseManifestBytes;

    public void writeTo(ZipOutputStream out) throws IOException {
        if (baseManifestBytes != null) {
            out.putNextEntry(new ZipEntry(BASE_FILE_NAME));
            out.write(baseManifestBytes);
            out.closeEntry();
        }
        byte[] bytes = (text + "ID=" + id + "\n").getBytes(StandardCharsets.UTF_8);
        out.putNextEntry(new ZipEntry(FILE_NAME));
        out.write(bytes);
        out.closeEntry();
    }

    public static BackupManifest parse(byte[] bytes) {
        BackupManifest m = new BackupManifest();
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder buff = new StringBuilder();
        String declaredId = null;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int pos = line.indexOf('=');
            if (pos < 0) {
                continue;
            }
            String key = line.substring(0, pos);
            String value = line.substring(pos + 1);
            if ("ID".equals(key)) {
                declaredId = value;
                continue;
            }
            buff.append(line).append('\n');
            switch (key) {
            case "TYPE":
                m.type = value;
                break;
            case "BASE_ID":
                m.baseId = value;
                break;
            case "CREATED":
                m.created = Long.parseLong(value);
                break;
            case "ENTRY": {
                String[] a = value.split("\t");
                if (a.length >= 5) {
                    m.addEntry(new Entry(a[0], Long.parseLong(a[1]), Long.parseLong(a[2]),
                            Integer.parseInt(a[3]), Integer.parseInt(a[4])));
                } else if (a.length >= 3) {
                    m.addEntry(new Entry(a[0], Long.parseLong(a[1]), Long.parseLong(a[2])));
                }
                break;
            }
            case "DEL":
                m.deleted.add(value);
                break;
            default:
                break;
            }
        }
        m.text = buff.toString();
        m.id = declaredId;
        // 校验清单本身没有被篡改
        if (declaredId != null && !declaredId.equals(sha256(buff.toString().getBytes(StandardCharsets.UTF_8)))) {
            throw DbException.getInternalError("backup manifest is corrupted, id mismatch");
        }
        return m;
    }

    public static BackupManifest read(File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            byte[] bytes = readEntry(zip, FILE_NAME);
            if (bytes == null) {
                throw DbException.getInternalError("backup manifest not found: " + zipFile);
            }
            return parse(bytes);
        }
    }

    public static byte[] readManifestBytes(File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            return readEntry(zip, FILE_NAME);
        }
    }

    public static BackupManifest readBase(File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            byte[] bytes = readEntry(zip, BASE_FILE_NAME);
            return bytes == null ? null : parse(bytes);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            return readAll(in);
        }
    }

    // 清单本身不大，可以整体读入内存
    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buff = new byte[8192];
        int read;
        while ((read = in.read(buff)) > 0) {
            out.write(buff, 0, read);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder buff = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                buff.append(Character.forDigit((b >> 4) & 0xf, 16));
                buff.append(Character.forDigit(b & 0xf, 16));
            }
            return buff.toString();
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    public static long crc32(byte[] bytes, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(bytes, off, len);
        return crc.getValue();
    }

    public static Set<String> entryNames(File zipFile) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
        }
        return names;
    }
}
