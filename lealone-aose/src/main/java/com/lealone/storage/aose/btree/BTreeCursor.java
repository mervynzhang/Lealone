/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage.aose.btree;

import com.lealone.agent.SystemOutline;
import com.lealone.agent.SystemOutlineNode;
import com.lealone.storage.CursorParameters;
import com.lealone.storage.StorageMapCursor;
import com.lealone.storage.aose.btree.page.Page;
import com.lealone.storage.aose.btree.page.PageReference;

/**
 * A cursor to iterate over elements in ascending order.
 * 
 * @param <K> the key type
 * @param <V> the value type
 * 
 * @author H2 Group
 * @author zhh
 */
public class BTreeCursor<K, V> implements StorageMapCursor<K, V> {

    private final BTreeMap<K, ?> map;
    private final CursorParameters<K> parameters;
    private CursorPos pos;

    private K key;
    private V value;

    public BTreeCursor(BTreeMap<K, ?> map, CursorParameters<K> parameters) {
        SystemOutline.createNode(SystemOutlineNode.BTreeCursor);
        this.map = map;
        this.parameters = parameters;
        // 定位到>=from的第一个leaf page
        min(map.getRootPage(), parameters.from);
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean next() {
        SystemOutline.createNode(SystemOutlineNode.BTreeCursor_next);
        if (hasNext()) {
            int index = pos.index++;
            key = (K) pos.page.getKey(index);
            if (parameters.allColumns)
                value = (V) pos.page.getValue(index, true);
            else
                value = (V) pos.page.getValue(index, parameters.columnIndexes);
            return true;
        }
        return false;
    }

    private boolean hasNext() {
        while (pos != null) {
            if (pos.index < pos.page.getKeyCount()) {
                return true;
            }
            pos = pos.parent;
            if (pos == null) {
                return false;
            }
            if (pos.index < map.getChildPageCount(pos.page)) {
                PageReference childRef = pos.page.getChildPageReference(pos.index);
                Page child = childRef.getOrReadPage();
                // 取页后如果拿到的不是这个ref自己的页面，说明ref在遍历过程中被切割或删除了，
                // 它已经重定向到父节点，从父节点第一个子页往下找会把左边已经遍历过的记录再遍历一遍，
                // 只能按最后返回的key从根节点重新定位
                if (child != childRef.getPage()) {
                    reposition();
                    continue;
                }
                pos.index++;
                min(child, null);
                // 保证只向前推进：定位到的第一个key必须大于已经返回过的key，
                // 否则说明父page对象在遍历过程中被替换过(过期的父节点)，按key重新定位
                if (key != null && pos != null && pos.page.getKeyCount() > 0
                        && map.getKeyType().compare(pos.page.getKey(pos.index), key) <= 0) {
                    reposition();
                }
            }
        }
        return false;
    }

    private void reposition() {
        K from = key;
        pos = null;
        if (from == null) {
            min(map.getRootPage(), parameters.from);
            return;
        }
        min(map.getRootPage(), from);
        // min定位到>=from的第一个位置，跳过已经返回过的那个key
        while (pos != null && pos.index < pos.page.getKeyCount()
                && map.getKeyType().compare(pos.page.getKey(pos.index), from) <= 0) {
            pos.index++;
        }
    }

    /**
     * Fetch the next entry that is equal or larger than the given key, starting
     * from the given page. This method retains the stack.
     * 
     * @param p the page to start
     * @param from the key to search
     */
    private void min(Page p, K from) {
        while (true) {
            if (p.isLeaf()) {
                int x = from == null ? 0 : p.binarySearch(from);
                if (x < 0) {
                    x = -x - 1;
                }
                pos = new CursorPos(p, x, pos);
                break;
            }
            int x = from == null ? 0 : p.getPageIndex(from);
            pos = new CursorPos(p, x + 1, pos);
            p = p.getChildPage(x);
        }
    }

    private static class CursorPos {
        /**
         * The current page.
         */
        final Page page;

        /**
         * The current index.
         */
        int index;

        /**
         * The position in the parent page, if any.
         */
        final CursorPos parent;

        CursorPos(Page page, int index, CursorPos parent) {
            this.page = page;
            this.index = index;
            this.parent = parent;
        }
    }
}
