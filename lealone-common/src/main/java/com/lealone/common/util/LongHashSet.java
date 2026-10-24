/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.util;

/**
 * 避免装箱开销的 long 集合，开放寻址实现。
 */
public class LongHashSet {

    private long[] keys;
    private boolean[] used;
    private int mask;
    private int size;

    public LongHashSet(int expected) {
        int cap = 16;
        while (cap < expected * 2)
            cap <<= 1;
        keys = new long[cap];
        used = new boolean[cap];
        mask = cap - 1;
    }

    public int size() {
        return size;
    }

    public boolean add(long k) {
        int i = hash(k);
        while (used[i]) {
            if (keys[i] == k)
                return false;
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = k;
        if (++size * 2 > keys.length)
            resize();
        return true;
    }

    private int hash(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    private void resize() {
        long[] oldKeys = keys;
        boolean[] oldUsed = used;
        int cap = oldKeys.length << 1;
        keys = new long[cap];
        used = new boolean[cap];
        mask = cap - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++)
            if (oldUsed[i])
                add(oldKeys[i]);
    }
}
