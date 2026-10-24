/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;
import java.util.HashMap;

import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.RoutineDependencyAnalyzer;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SchemaObject;
import com.lealone.db.schema.Sequence;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Table;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DISCARD TEMP
 */
public class DiscardTemp extends DefinitionStatement {

    public DiscardTemp(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.DISCARD_TEMP;
    }

    @Override
    public int update() {
        ArrayList<String> names = new ArrayList<>();
        for (Table table : session.getLocalTempTables()) {
            names.add(table.getName());
        }
        for (Sequence sequence : session.getLocalTempSequences()) {
            names.add(sequence.getName());
        }
        // 先删掉依赖临时对象的函数/过程，避免留下悬空引用。
        // 级联删除的是持久例程，和DROP FUNCTION/PROCEDURE一样要admin权限：
        // 普通用户可以随便建临时序列/临时表，如果按名字匹配就能删别人的过程，
        // 建一个和过程体里字符串同名的临时序列再DISCARD TEMP就能删掉别人的持久过程。
        ArrayList<SchemaObject> routines = new ArrayList<>();
        if (session.getUser().isAdmin()) {
            routines = RoutineDependencyAnalyzer.findRoutinesReferencingNames(session, names);
        }
        HashMap<String, DbObjectLock> locks = new HashMap<>();
        for (SchemaObject routine : routines) {
            Schema schema = routine.getSchema();
            String key = schema.getName() + ":" + routine.getType().value;
            DbObjectLock lock = locks.get(key);
            if (lock == null) {
                lock = schema.tryExclusiveLock(routine.getType(), session);
                if (lock == null) {
                    return -1;
                }
                locks.put(key, lock);
            }
            // DROP FUNCTION/PROCEDURE的依赖检查会看到被临时对象引用的例程，这里直接删，
            // 因为DISCARD TEMP就是级联清理
            schema.remove(session, routine, lock);
        }
        for (Table table : session.getLocalTempTables()) {
            session.removeLocalTempTable(table);
        }
        for (Sequence sequence : session.getLocalTempSequences()) {
            session.removeLocalTempSequence(sequence);
        }
        return 0;
    }
}
