/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.lealone.agent.SystemOutline;
import com.lealone.agent.SystemOutlineNode;
import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.Database;
import com.lealone.db.DbObject;
import com.lealone.db.DbObjectBase;
import com.lealone.db.DbObjectType;
import com.lealone.db.SysProperties;
import com.lealone.db.TransactionalDbObjects;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.auth.User;
import com.lealone.db.constraint.Constraint;
import com.lealone.db.index.Index;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.plugin.PluggableEngine;
import com.lealone.db.row.Row;
import com.lealone.db.service.Service;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.CreateTableData;
import com.lealone.db.table.StandardTable;
import com.lealone.db.table.Table;
import com.lealone.db.table.TableFactory;
import com.lealone.storage.StorageEngine;

/**
 * A schema as created by the SQL statement
 * CREATE SCHEMA
 *
 * @author H2 Group
 * @author zhh
 */
public class Schema extends DbObjectBase {

    /**
     * The set of returned unique names that are not yet stored. It is used to
     * avoid returning the same unique name twice when multiple threads
     * concurrently create objects.
     */
    private final HashSet<String> temporaryUniqueNames = new HashSet<>();

    private final TransactionalDbObjects[] dbObjectsArray = //
            new TransactionalDbObjects[DbObjectType.TYPES.length];
    private final DbObjectLock[] locks = new DbObjectLock[DbObjectType.TYPES.length];

    private final User owner;
    private final boolean system;

    /**
     * Create a new schema object.
     *
     * @param database the database
     * @param id the object id
     * @param schemaName the schema name
     * @param owner the owner of the schema
     * @param system if this is a system schema (such a schema can not be dropped)
     */
    public Schema(Database database, int id, String schemaName, User owner, boolean system) {
        super(database, id, schemaName);
        for (DbObjectType type : DbObjectType.TYPES) {
            if (type.isSchemaObject) {
                dbObjectsArray[type.value] = new TransactionalDbObjects();
                locks[type.value] = new DbObjectLock(type);
            }
        }
        this.owner = owner;
        this.system = system;
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.SCHEMA;
    }

    /**
     * Get the owner of this schema.
     *
     * @return the owner
     */
    public User getOwner() {
        return owner;
    }

    /**
     * Check if this schema can be dropped. System schemas can not be dropped.
     *
     * @return true if it can be dropped
     */
    public boolean canDrop() {
        return !system;
    }

    @Override
    public String getCreateSQL() {
        if (system) {
            return null;
        }
        StatementBuilder sql = new StatementBuilder();
        sql.append("CREATE SCHEMA IF NOT EXISTS ").append(getSQL()).append(" AUTHORIZATION ")
                .append(owner.getSQL());
        return sql.toString();
    }

    @Override
    public List<? extends DbObject> getChildren() {
        return getAll();
    }

    @Override
    public void removeChildrenAndResources(ServerSession session, DbObjectLock lock) {
        // 删除顺序不能乱，因为可能有依赖
        removeSchemaObjects(session, lock, DbObjectType.TRIGGER);
        removeSchemaObjects(session, lock, DbObjectType.CONSTRAINT);

        // There can be dependencies between tables e.g. using computed columns,
        // so we might need to loop over them multiple times.
        boolean runLoopAgain = false;
        do {
            runLoopAgain = false;
            HashMap<String, DbObject> tablesAndViews = getDbObjects(DbObjectType.TABLE_OR_VIEW);
            if (tablesAndViews != null) {
                // Loop over a copy because the map is modified underneath us.
                for (DbObject obj : new ArrayList<>(tablesAndViews.values())) {
                    Table table = (Table) obj;
                    // Check for null because multiple tables might be deleted
                    // in one go underneath us.
                    if (table.getName() != null) {
                        if (database.getDependentTable(table, table) == null) {
                            table.getSchema().remove(session, table, lock);
                        } else {
                            runLoopAgain = true;
                        }
                    }
                }
            }
        } while (runLoopAgain);

        removeSchemaObjects(session, lock, DbObjectType.INDEX);
        removeSchemaObjects(session, lock, DbObjectType.SEQUENCE);
        removeSchemaObjects(session, lock, DbObjectType.CONSTANT);
        removeSchemaObjects(session, lock, DbObjectType.FUNCTION_ALIAS);
        removeSchemaObjects(session, lock, DbObjectType.AGGREGATE);
        removeSchemaObjects(session, lock, DbObjectType.COMPOSITE_TYPE);
        removeSchemaObjects(session, lock, DbObjectType.USER_DATATYPE);
        removeSchemaObjects(session, lock, DbObjectType.SERVICE);
        super.removeChildrenAndResources(session, lock);
    }

    private void removeSchemaObjects(ServerSession session, DbObjectLock lock, DbObjectType type) {
        HashMap<String, DbObject> dbObjects = getDbObjects(type);
        while (dbObjects != null && dbObjects.size() > 0) {
            SchemaObject obj = (SchemaObject) dbObjects.values().toArray()[0];
            remove(session, obj, lock);
            // 重新获取，因为调用remove方法会重新copy一份再删除
            dbObjects = getDbObjects(type);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> HashMap<String, T> getDbObjects(DbObjectType type) {
        return (HashMap<String, T>) dbObjectsArray[type.value].getDbObjects();
    }

    /**
     * 对象在schema里的存放key。函数和存储过程支持按参数类型重载，同名不同参数类型的对象用不同的key，
     * 其它对象类型仍然只用名字做key。
     */
    private static String getDbObjectKey(SchemaObject obj) {
        return getDbObjectKey(obj, obj.getName());
    }

    private static String getDbObjectKey(SchemaObject obj, String name) {
        if (obj instanceof SqlFunction) {
            return ((SqlFunction) obj).getOverloadKey(name);
        } else if (obj instanceof SqlProcedure) {
            return ((SqlProcedure) obj).getOverloadKey(name);
        }
        return name;
    }

    public DbObjectLock tryExclusiveLock(DbObjectType type, ServerSession session) {
        DbObjectLock lock = locks[type.value];
        if (lock.tryExclusiveLock(session)) {
            return lock;
        } else {
            return null;
        }
    }

    public Row tryLockSchemaObject(ServerSession session, SchemaObject obj, int errorCode) {
        return database.tryLockDbObject(session, obj, errorCode);
    }

    /**
     * Add an object to this schema.
     *
     * @param obj the object to add
     */
    // 执行DDL语句时session不为null，需要在meta表中增加一条对应的记录
    public void add(ServerSession session, SchemaObject obj, DbObjectLock lock) {
        SystemOutline.createNode(SystemOutlineNode.addSchemaObject);
        TransactionalDbObjects dbObjects = dbObjectsArray[obj.getType().value];
        int id = obj.getId();
        String key = getDbObjectKey(obj);

        if (SysProperties.CHECK) {
            if (obj.getSchema() != this) {
                DbException.throwInternalError("wrong schema");
            }
            if (session != null) {
                if (id < 0) {
                    DbException.throwInternalError("object id<0" + id);
                }
                if (!database.isObjectIdEnabled(id)) {
                    DbException.throwInternalError("object id is not enabled: " + id);
                }
            }
            if (dbObjects.containsKey(session, key))
                DbException.throwInternalError("object already exists: " + obj.getName());
        }

        // id为0的对象是系统内置的；
        // session如果为null表示只是单纯的想增加一个SchemaObject；
        // 如果数据库正在启动阶段(执行meta表中的create语句的阶段)，
        // 因为此时是单线程运行的，所以也只需简单增加一个SchemaObject即可
        if (id <= 0 || session == null || database.isStarting()) {
            dbObjects.add(key, obj);
            return;
        }

        // 先执行addMeta再执行put，因为addMeta可能会失败
        database.tryAddMeta(session, obj);
        dbObjects.copyOnAdd(session, key, obj);

        if (lock != null) {
            lock.addHandler(ar -> {
                if (ar.isSucceeded() && ar.getResult()) {
                    dbObjects.commit();
                } else {
                    database.clearObjectId(obj.getId());
                    rollbackDbObjects(dbObjects);
                }
                freeUniqueName(obj.getName());
            });
        }
    }

    public void update(ServerSession session, SchemaObject obj, Row oldRow, DbObjectLock lock) {
        TransactionalDbObjects dbObjects = dbObjectsArray[obj.getType().value];
        int id = obj.getId();

        if (SysProperties.CHECK) {
            if (obj.getSchema() != this) {
                DbException.throwInternalError("wrong schema");
            }
            if (session == null) {
                DbException.throwInternalError("session is null");
            }
            if (id < 0) {
                DbException.throwInternalError("object id<0" + id);
            }
            if (!database.isObjectIdEnabled(id)) {
                DbException.throwInternalError("object id is not enabled: " + id);
            }
        }

        database.updateMeta(session, obj, oldRow);
        dbObjects.copyOnAdd(session, getDbObjectKey(obj), obj);

        if (lock != null) {
            lock.addHandler(ar -> {
                if (ar.isSucceeded() && ar.getResult()) {
                    dbObjects.commit();
                    obj.onUpdateComplete();
                } else {
                    rollbackDbObjects(dbObjects);
                }
            });
        }
    }

    /**
     * Remove an object from this schema.
     *
     * @param obj the object to remove
     */
    public void remove(ServerSession session, SchemaObject obj, DbObjectLock lock) {
        String objName = obj.getName();
        String objKey = getDbObjectKey(obj);
        DbObjectType type = obj.getType();
        if (session != null && removeLocalTempSchemaObject(session, obj)) {
            freeUniqueName(objName);
            obj.invalidate();
        } else {
            TransactionalDbObjects dbObjects = dbObjectsArray[type.value];
            if (SysProperties.CHECK && !dbObjects.containsKey(session, objKey)) {
                DbException.throwInternalError("not found: " + objName);
            }

            if (session == null) {
                dbObjects.remove(objKey);
                removeInternal(obj);
                return;
            }

            obj.removeChildrenAndResources(session, lock);
            database.tryRemoveMeta(session, obj, lock);
            dbObjects.copyOnRemove(session, objKey);

            if (lock != null) {
                lock.addHandler(ar -> {
                    if (ar.isSucceeded() && ar.getResult()) {
                        dbObjects.commit();
                        removeInternal(obj);
                    } else {
                        rollbackDbObjects(dbObjects);
                    }
                });
            }
        }
    }

    // DDL回滚之后元数据又变回去了，要让缓存中的prepared statement重新编译
    private void rollbackDbObjects(TransactionalDbObjects dbObjects) {
        dbObjects.rollback();
        database.getNextModificationMetaId();
    }

    private void removeInternal(SchemaObject obj) {
        freeUniqueName(obj.getName());
        database.clearObjectId(obj.getId());
        obj.invalidate();
    }

    private boolean removeLocalTempSchemaObject(ServerSession session, SchemaObject obj) {
        DbObjectType type = obj.getType();
        if (type == DbObjectType.TABLE_OR_VIEW) {
            Table table = (Table) obj;
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTable(table);
                return true;
            }
        } else if (type == DbObjectType.INDEX) {
            Index index = (Index) obj;
            Table table = index.getTable();
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTableIndex(index);
                return true;
            }
        } else if (type == DbObjectType.SEQUENCE) {
            Sequence sequence = (Sequence) obj;
            if (sequence.isTemporary()) {
                session.removeLocalTempSequence(sequence);
                return true;
            }
        } else if (type == DbObjectType.CONSTRAINT) {
            Constraint constraint = (Constraint) obj;
            Table table = constraint.getTable();
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTableConstraint(constraint);
                return true;
            }
        }
        return false;
    }

    /**
     * Rename a schema object.
     *
     * @param session the session
     * @param obj the object to rename
     * @param newName the new name
     */
    public void rename(ServerSession session, SchemaObject obj, String newName, DbObjectLock lock) {
        TransactionalDbObjects dbObjects = dbObjectsArray[obj.getType().value];
        String oldName = obj.getName();
        String oldKey = getDbObjectKey(obj);
        // 函数改名时参数签名不变，新key用新名字算
        String newKey = getDbObjectKey(obj, newName);
        if (SysProperties.CHECK) {
            if (!dbObjects.containsKey(session, oldKey)) {
                DbException.throwInternalError("not found: " + oldName);
            }
            if (oldName.equals(newName) || dbObjects.containsKey(session, newKey)) {
                DbException.throwInternalError("object already exists: " + newName);
            }
        }

        obj.checkRename();
        dbObjects.copyOnRemove(session, oldKey);
        obj.rename(newName);
        dbObjects.add(newKey, obj);

        lock.addHandler(ar -> {
            if (ar.isSucceeded() && ar.getResult()) {
                dbObjects.commit();
            } else {
                obj.rename(oldName);
                rollbackDbObjects(dbObjects);
            }
            freeUniqueName(oldName);
            freeUniqueName(newName);
        });

        if (session != null)
            database.updateMetaAndFirstLevelChildren(session, obj);
    }

    @SuppressWarnings("unchecked")
    private <T> T find(DbObjectType type, ServerSession session, String name) {
        return (T) dbObjectsArray[type.value].find(session, name);
    }

    /**
     * Try to find a table or view with this name. This method returns null if
     * no object with this name exists. Local temporary tables are also returned.
     *
     * @param session the session
     * @param name the object name
     * @return the object or null
     */
    public Table findTableOrView(ServerSession session, String name) {
        Table table = find(DbObjectType.TABLE_OR_VIEW, session, name);
        if (table == null && session != null) {
            table = session.findLocalTempTable(name);
        }
        return table;
    }

    /**
     * Try to find an index with this name. This method returns null if
     * no object with this name exists.
     *
     * @param session the session
     * @param name the object name
     * @return the object or null
     */
    public Index findIndex(ServerSession session, String name) {
        Index index = find(DbObjectType.INDEX, session, name);
        if (index == null && session != null) {
            index = session.findLocalTempTableIndex(name);
        }
        return index;
    }

    /**
     * Try to find a trigger with this name. This method returns null if
     * no object with this name exists.
     *
     * @param name the object name
     * @return the object or null
     */
    public TriggerObject findTrigger(ServerSession session, String name) {
        return find(DbObjectType.TRIGGER, session, name);
    }

    /**
     * Try to find a sequence with this name. This method returns null if
     * no object with this name exists.
     *
     * @param sequenceName the object name
     * @return the object or null
     */
    public Sequence findSequence(ServerSession session, String sequenceName) {
        Sequence sequence = find(DbObjectType.SEQUENCE, session, sequenceName);
        if (sequence == null && session != null) {
            sequence = session.findLocalTempSequence(sequenceName);
        }
        return sequence;
    }

    /**
     * Try to find a constraint with this name. This method returns null if no
     * object with this name exists.
     *
     * @param session the session
     * @param name the object name
     * @return the object or null
     */
    public Constraint findConstraint(ServerSession session, String name) {
        Constraint constraint = find(DbObjectType.CONSTRAINT, session, name);
        if (constraint == null && session != null) {
            constraint = session.findLocalTempTableConstraint(name);
        }
        return constraint;
    }

    /**
     * Try to find a user defined constant with this name. This method returns
     * null if no object with this name exists.
     *
     * @param constantName the object name
     * @return the object or null
     */
    public Constant findConstant(ServerSession session, String constantName) {
        return find(DbObjectType.CONSTANT, session, constantName);
    }

    /**
     * Try to find a user defined function with this name. This method returns
     * null if no object with this name exists. 同名函数有多个重载时返回其中一个，
     * 需要区分重载时用{@link #findFunctions}。
     *
     * @param functionAlias the object name
     * @return the object or null
     */
    public FunctionAlias findFunction(ServerSession session, String functionAlias) {
        DbObject obj = dbObjectsArray[DbObjectType.FUNCTION_ALIAS.value].find(session, functionAlias);
        if (obj != null) {
            return (FunctionAlias) obj;
        }
        ArrayList<FunctionAlias> list = findFunctions(session, functionAlias);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 找出同名的所有函数(按参数类型重载)，按对象id排序，保证每次的消解结果一致。
     *
     * @param functionAlias the function name
     * @return 可能为空的名字相同的函数列表
     */
    public ArrayList<FunctionAlias> findFunctions(ServerSession session, String functionAlias) {
        ArrayList<FunctionAlias> list = new ArrayList<>();
        for (DbObject obj : dbObjectsArray[DbObjectType.FUNCTION_ALIAS.value].findAll(session)) {
            if (functionAlias.equalsIgnoreCase(obj.getName())) {
                list.add((FunctionAlias) obj);
            }
        }
        for (int i = 1; i < list.size(); i++) {
            FunctionAlias f = list.get(i);
            int id = f.getId();
            int j = i - 1;
            while (j >= 0 && list.get(j).getId() > id) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, f);
        }
        return list;
    }

    /**
     * Try to find a stored procedure with this name. This method returns
     * null if no object with this name exists. 同名过程有多个重载时返回其中一个，
     * 需要区分重载时用{@link #findProcedures}。
     *
     * @param procedureName the object name
     * @return the object or null
     */
    public SqlProcedure findProcedure(ServerSession session, String procedureName) {
        ArrayList<SqlProcedure> list = findProcedures(session, procedureName);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 找出同名的所有过程(按参数类型重载)，按对象id排序，保证每次的消解结果一致。
     *
     * @param procedureName the procedure name
     * @return 可能为空的名字相同的过程列表
     */
    public ArrayList<SqlProcedure> findProcedures(ServerSession session, String procedureName) {
        ArrayList<SqlProcedure> list = new ArrayList<>();
        for (DbObject obj : dbObjectsArray[DbObjectType.PROCEDURE.value].findAll(session)) {
            if (procedureName.equalsIgnoreCase(obj.getName())) {
                list.add((SqlProcedure) obj);
            }
        }
        for (int i = 1; i < list.size(); i++) {
            SqlProcedure p = list.get(i);
            int id = p.getId();
            int j = i - 1;
            while (j >= 0 && list.get(j).getId() > id) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, p);
        }
        return list;
    }

    public ArrayList<SqlProcedure> getAllProcedures() {
        HashMap<String, SqlProcedure> procedures = getDbObjects(DbObjectType.PROCEDURE);
        return new ArrayList<>(procedures.values());
    }

    /**
     * Get the user defined aggregate function if it exists, or null if not.
     *
     * @param name the name of the user defined aggregate function
     * @return the aggregate function or null
     */
    public UserAggregate findAggregate(ServerSession session, String name) {
        return find(DbObjectType.AGGREGATE, session, name);
    }

    /**
     * Get the user defined data type if it exists, or null if not.
     *
     * @param name the name of the user defined data type
     * @return the user defined data type or null
     */
    public UserDataType findUserDataType(ServerSession session, String name) {
        return find(DbObjectType.USER_DATATYPE, session, name);
    }

    public CompositeType findCompositeType(ServerSession session, String name) {
        return find(DbObjectType.COMPOSITE_TYPE, session, name);
    }

    public CompositeType getCompositeType(ServerSession session, String name) {
        CompositeType type = findCompositeType(session, name);
        if (type == null)
            throw DbException.get(ErrorCode.USER_DATA_TYPE_NOT_FOUND_1, name);
        return type;
    }

    public Service findService(ServerSession session, String name) {
        return find(DbObjectType.SERVICE, session, name);
    }

    public Service getService(ServerSession session, String name) {
        Service service = findService(session, name);
        if (service == null) {
            throw DbException.get(ErrorCode.SERVICE_NOT_FOUND_1, name);
        }
        return service;
    }

    /**
     * Release a unique object name.
     *
     * @param name the object name
     */
    public void freeUniqueName(String name) {
        if (name != null) {
            synchronized (temporaryUniqueNames) {
                temporaryUniqueNames.remove(name);
            }
        }
    }

    private String getUniqueName(DbObject obj, HashMap<String, ? extends DbObject> map, String prefix) {
        String hash = Integer.toHexString(obj.getName().hashCode()).toUpperCase();
        String name = null;
        synchronized (temporaryUniqueNames) {
            for (int i = 1, len = hash.length(); i < len; i++) {
                name = prefix + hash.substring(0, i);
                if (!map.containsKey(name) && !temporaryUniqueNames.contains(name)) {
                    break;
                }
                name = null;
            }
            if (name == null) {
                prefix = prefix + hash + "_";
                for (int i = 0;; i++) {
                    name = prefix + i;
                    if (!map.containsKey(name) && !temporaryUniqueNames.contains(name)) {
                        break;
                    }
                }
            }
            temporaryUniqueNames.add(name);
        }
        return name;
    }

    /**
     * Create a unique constraint name.
     *
     * @param session the session
     * @param table the constraint table
     * @return the unique name
     */
    public String getUniqueConstraintName(ServerSession session, Table table) {
        HashMap<String, ? extends DbObject> tableConstraints;
        if (table.isTemporary() && !table.isGlobalTemporary()) {
            tableConstraints = session.getLocalTempTableConstraints();
        } else {
            tableConstraints = getDbObjects(DbObjectType.CONSTRAINT);
        }
        return getUniqueName(table, tableConstraints, "CONSTRAINT_");
    }

    /**
     * Create a unique index name.
     *
     * @param session the session
     * @param table the indexed table
     * @param prefix the index name prefix
     * @return the unique name
     */
    public String getUniqueIndexName(ServerSession session, Table table, String prefix) {
        HashMap<String, ? extends DbObject> tableIndexes;
        if (table.isTemporary() && !table.isGlobalTemporary()) {
            tableIndexes = session.getLocalTempTableIndexes();
        } else {
            tableIndexes = getDbObjects(DbObjectType.INDEX);
        }
        return getUniqueName(table, tableIndexes, prefix);
    }

    /**
     * Get the table or view with the given name.
     * Local temporary tables are also returned.
     *
     * @param session the session
     * @param name the table or view name
     * @return the table or view
     * @throws DbException if no such object exists
     */
    public Table getTableOrView(ServerSession session, String name) {
        Table table = findTableOrView(session, name);
        if (table == null) {
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1, name);
        }
        return table;
    }

    /**
     * Get the index with the given name.
     *
     * @param name the index name
     * @return the index
     * @throws DbException if no such object exists
     */
    public Index getIndex(ServerSession session, String name) {
        Index index = findIndex(session, name);
        if (index == null) {
            throw DbException.get(ErrorCode.INDEX_NOT_FOUND_1, name);
        }
        return index;
    }

    /**
     * Get the constraint with the given name.
     *
     * @param name the constraint name
     * @return the constraint
     * @throws DbException if no such object exists
     */
    public Constraint getConstraint(ServerSession session, String name) {
        Constraint constraint = findConstraint(session, name);
        if (constraint == null) {
            throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, name);
        }
        return constraint;
    }

    /**
     * Get the user defined constant with the given name.
     *
     * @param constantName the constant name
     * @return the constant
     * @throws DbException if no such object exists
     */
    public Constant getConstant(ServerSession session, String constantName) {
        Constant constant = findConstant(session, constantName);
        if (constant == null) {
            throw DbException.get(ErrorCode.CONSTANT_NOT_FOUND_1, constantName);
        }
        return constant;
    }

    /**
     * Get the sequence with the given name.
     *
     * @param sequenceName the sequence name
     * @return the sequence
     * @throws DbException if no such object exists
     */
    public Sequence getSequence(ServerSession session, String sequenceName) {
        Sequence sequence = findSequence(session, sequenceName);
        if (sequence == null) {
            throw DbException.get(ErrorCode.SEQUENCE_NOT_FOUND_1, sequenceName);
        }
        return sequence;
    }

    public TriggerObject getTrigger(ServerSession session, String name) {
        TriggerObject triggerObject = findTrigger(session, name);
        if (triggerObject == null) {
            throw DbException.get(ErrorCode.TRIGGER_NOT_FOUND_1, name);
        }
        return triggerObject;
    }

    public SqlProcedure getProcedure(ServerSession session, String name) {
        ArrayList<SqlProcedure> list = findProcedures(session, name);
        if (list.isEmpty()) {
            throw DbException.get(ErrorCode.PROCEDURE_NOT_FOUND_1, name);
        }
        if (list.size() > 1) {
            // 同名过程有多个重载，看不出要操作的是哪一个
            throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, name);
        }
        return list.get(0);
    }

    public FunctionAlias getFunction(ServerSession session, String name) {
        ArrayList<FunctionAlias> list = findFunctions(session, name);
        if (list.isEmpty()) {
            throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1, name);
        }
        if (list.size() > 1) {
            // 同名函数有多个重载，看不出要操作的是哪一个
            throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, name);
        }
        return list.get(0);
    }

    public UserDataType getUserDataType(ServerSession session, String name) {
        UserDataType userDataType = findUserDataType(session, name);
        if (userDataType == null) {
            throw DbException.get(ErrorCode.USER_DATA_TYPE_NOT_FOUND_1, name);
        }
        return userDataType;
    }

    /**
     * Get all objects.
     *
     * @return a (possible empty) list of all objects
     */
    public ArrayList<SchemaObject> getAll() {
        ArrayList<SchemaObject> all = new ArrayList<>();
        for (DbObjectType type : DbObjectType.TYPES) {
            if (type.isSchemaObject) {
                HashMap<String, SchemaObject> map = getDbObjects(type);
                all.addAll(map.values());
            }
        }
        return all;
    }

    /**
     * Get all objects of the given type.
     *
     * @param type the object type
     * @return a (possible empty) list of all objects
     */
    public ArrayList<SchemaObject> getAll(DbObjectType type) {
        HashMap<String, SchemaObject> map = getDbObjects(type);
        return new ArrayList<>(map.values());
    }

    /**
     * Get all tables and views.
     *
     * @return a (possible empty) list of all objects
     */
    public ArrayList<Table> getAllTablesAndViews() {
        HashMap<String, Table> tables = getDbObjects(DbObjectType.TABLE_OR_VIEW);
        return new ArrayList<>(tables.values());
    }

    public ArrayList<Service> getAllServices() {
        HashMap<String, Service> services = getDbObjects(DbObjectType.SERVICE);
        return new ArrayList<>(services.values());
    }

    /**
     * Add a table to the schema.
     *
     * @param data the create table information
     * @return the created {@link Table} object
     */
    public Table createTable(CreateTableData data) {
        data.schema = this;
        // 用默认的数据库参数
        if (data.storageEngineName == null) {
            data.storageEngineName = database.getDefaultStorageEngineName();
        }
        if (data.storageEngineName != null) {
            StorageEngine engine = PluggableEngine.getEngine(StorageEngine.class,
                    data.storageEngineName);
            if (engine instanceof TableFactory) {
                return ((TableFactory) engine).createTable(data);
            }
            return new StandardTable(data, engine);
        }
        throw DbException.convert(new NullPointerException("table engine is null"));
    }

    public TransactionalDbObjects[] getTransactionalDbObjectsArray() {
        return dbObjectsArray;
    }
}
