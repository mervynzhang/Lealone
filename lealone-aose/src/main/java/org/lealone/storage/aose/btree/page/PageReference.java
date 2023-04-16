/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage.aose.btree.page;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.lealone.storage.page.PageOperationHandler;

public class PageReference {

    private static final AtomicReferenceFieldUpdater<PageReference, PageOperationHandler> //
    lockUpdater = AtomicReferenceFieldUpdater.newUpdater(PageReference.class, PageOperationHandler.class,
            "lockOwner");
    protected volatile PageOperationHandler lockOwner;
    private boolean dataStructureChanged; // 比如发生了切割或page从父节点中删除

    public boolean isDataStructureChanged() {
        return dataStructureChanged;
    }

    public void setDataStructureChanged(boolean dataStructureChanged) {
        this.dataStructureChanged = dataStructureChanged;
    }

    public boolean tryLock(PageOperationHandler newLockOwner) {
        if (newLockOwner == lockOwner)
            return true;
        do {
            PageOperationHandler owner = lockOwner;
            boolean ok = lockUpdater.compareAndSet(this, null, newLockOwner);
            if (!ok && owner != null) {
                owner.addWaitingHandler(newLockOwner);
            }
            if (ok)
                return true;
        } while (lockOwner == null);
        return false;
    }

    public void unlock() {
        if (lockOwner != null) {
            PageOperationHandler owner = lockOwner;
            lockOwner = null;
            owner.wakeUpWaitingHandlers();
        }
    }

    Page page;
    long pos;
    ByteBuffer buff;
    int pageLength;

    public PageReference() {
    }

    public PageReference(long pos) {
        this.pos = pos;
    }

    public PageReference(Page page, long pos) {
        this.page = page;
        this.pos = pos;
    }

    public PageReference(Page page) {
        this.page = page;
        if (page != null) {
            pos = page.getPos();
        }
    }

    public long getPos() {
        return pos;
    }

    public Page getPage() {
        return page;
    }

    public void replacePage(Page page) {
        this.page = page;
        if (page != null) {
            pos = page.getPos();
        }
    }

    @Override
    public String toString() {
        return "PageReference[ pos=" + pos + "]";
    }

    boolean isLeafPage() {
        if (page != null)
            return page.isLeaf();
        else
            return PageUtils.isLeafPage(pos);
    }

    boolean isNodePage() {
        if (page != null)
            return page.isNode();
        else
            return PageUtils.isNodePage(pos);
    }
}
