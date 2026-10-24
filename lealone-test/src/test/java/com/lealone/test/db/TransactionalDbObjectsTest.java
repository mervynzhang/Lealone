/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.db;

import java.lang.reflect.Field;

import org.junit.Test;

import com.lealone.db.DbObject;
import com.lealone.db.TransactionalDbObjects;
import com.lealone.db.schema.Constant;
import com.lealone.db.value.ValueInt;

/**
 * 同一个事务里的多条DDL共用一份快照，但每条DDL都会注册一个提交/回滚handler，
 * 所以 TransactionalDbObjects 的 commit/rollback 只能生效一次：
 * 重复提交会把旧快照的version改写成0(破坏多版本)，重复回滚会把上一个已提交事务的快照也弹掉(对象丢失)。
 */
public class TransactionalDbObjectsTest extends DbObjectTestBase {

    @Test
    public void commitTakesEffectOnlyOnce() throws Exception {
        TransactionalDbObjects dbObjects = new TransactionalDbObjects();
        dbObjects.add(newConstant("TDO_C1"));

        // 事务1里做两条DDL，提交时两个handler各调一次commit
        session.setAutoCommit(false);
        long tid1 = session.getTransaction().getTransactionId();
        dbObjects.copyOnAdd(session, newConstant("TDO_C2"));
        dbObjects.copyOnAdd(session, newConstant("TDO_C3"));
        dbObjects.commit();
        dbObjects.commit();
        session.commit();

        assertEquals(0, versionOf(dbObjects));
        TransactionalDbObjects old = oldOf(dbObjects);
        assertNotNull("提交之后必须保留旧快照", old);
        // 重复提交不能把旧快照的version改写成0
        assertEquals(tid1, versionOf(old));
    }

    @Test
    public void rollbackTakesEffectOnlyOnce() throws Exception {
        TransactionalDbObjects dbObjects = new TransactionalDbObjects();
        dbObjects.add(newConstant("TDO_C1"));

        // 事务1: 提交两个新对象
        session.setAutoCommit(false);
        dbObjects.copyOnAdd(session, newConstant("TDO_C2"));
        dbObjects.copyOnAdd(session, newConstant("TDO_C3"));
        dbObjects.commit();
        session.commit();

        // 事务2: 把两个新对象都删掉，回滚时两个handler各调一次rollback
        session.setAutoCommit(false);
        dbObjects.copyOnRemove(session, "TDO_C2");
        dbObjects.copyOnRemove(session, "TDO_C3");
        dbObjects.rollback();
        dbObjects.rollback();
        session.rollback();
        session.setAutoCommit(true);

        // 回滚到事务开始前的状态: 三个对象都在，第二次回滚不能把已提交的对象弹掉
        assertNotNull(dbObjects.find(session, "TDO_C1"));
        assertNotNull(dbObjects.find(session, "TDO_C2"));
        assertNotNull(dbObjects.find(session, "TDO_C3"));
        assertEquals(0, versionOf(dbObjects));
    }

    private DbObject newConstant(String name) {
        return new Constant(schema, -1, name, ValueInt.get(1));
    }

    // old/version都是内部私有字段，走SQL观察不到差异，这里只能白盒断言
    private static TransactionalDbObjects oldOf(TransactionalDbObjects dbObjects) throws Exception {
        Field f = TransactionalDbObjects.class.getDeclaredField("old");
        f.setAccessible(true);
        return (TransactionalDbObjects) f.get(dbObjects);
    }

    private static long versionOf(TransactionalDbObjects dbObjects) throws Exception {
        Field f = TransactionalDbObjects.class.getDeclaredField("version");
        f.setAccessible(true);
        return f.getLong(dbObjects);
    }
}
