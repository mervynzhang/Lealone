/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER PROCEDURE
 */
public class AlterProcedure extends SchemaStatement {

    private String procedureName;
    private boolean ifExists;
    // ALTER PROCEDURE name(类型列表)里的参数类型和参数模式，null表示没写
    private int[] argumentTypeIds;
    private int[] argumentModes;
    private FunctionAttributes attributes;
    private String newName;
    private String ownerName;
    private String setConfigName;
    private String setConfigValue;
    private String resetConfigName;
    private boolean resetAll;

    public AlterProcedure(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_PROCEDURE;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public void setArgumentTypeIds(int[] argumentTypeIds) {
        this.argumentTypeIds = argumentTypeIds;
    }

    // 每个位置是参数模式，-1表示这一位没有写IN/OUT/INOUT
    public void setArgumentModes(int[] argumentModes) {
        this.argumentModes = argumentModes;
    }

    public void setAttributes(FunctionAttributes attributes) {
        this.attributes = attributes;
    }

    public void setNewName(String newName) {
        this.newName = newName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public void setConfigItem(String name, String value) {
        this.setConfigName = name;
        this.setConfigValue = value;
    }

    public void setResetConfigName(String name) {
        this.resetConfigName = name;
    }

    public void setResetAll(boolean resetAll) {
        this.resetAll = resetAll;
    }

    // 按名字和参数类型找要修改的过程，有重载时必须写参数类型
    private SqlProcedure findProcedure(ArrayList<SqlProcedure> list) {
        if (list.isEmpty()) {
            return null;
        }
        if (argumentTypeIds == null) {
            if (list.size() > 1) {
                throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, procedureName);
            }
            return list.get(0);
        }
        boolean hasModes = hasArgumentModes();
        SqlProcedure found = null;
        for (SqlProcedure p : list) {
            // 先按完整参数列表(含OUT/INOUT)匹配，和DROP PROCEDURE的口径一致；
            // 一个参数模式都没写时也接受只写入参的写法
            boolean match = p.matchesDropSignature(argumentTypeIds, argumentModes);
            if (!match && !hasModes) {
                match = p.matchesSignature(argumentTypeIds);
            }
            if (match) {
                if (found != null) {
                    throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, procedureName);
                }
                found = p;
            }
        }
        return found;
    }

    // 参数列表里有没有显式写IN/OUT/INOUT
    private boolean hasArgumentModes() {
        if (argumentModes == null) {
            return false;
        }
        for (int mode : argumentModes) {
            if (mode != -1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.PROCEDURE, session);
        if (lock == null)
            return -1;

        ArrayList<SqlProcedure> list = schema.findProcedures(session, procedureName);
        SqlProcedure procedure = findProcedure(list);
        if (procedure == null) {
            if (ifExists) {
                return 0;
            }
            // 名字不存在和参数类型对不上都算过程不存在，和DROP PROCEDURE一致
            throw DbException.get(ErrorCode.PROCEDURE_NOT_FOUND_1, procedureName);
        }
        Database db = session.getDatabase();
        if (newName != null) {
            if (!schema.findProcedures(session, newName).isEmpty()) {
                throw DbException.get(ErrorCode.PROCEDURE_ALREADY_EXISTS_1, newName);
            }
            // schema对象的改名要走Schema.rename，Database.renameDatabaseObject只处理非schema对象
            schema.rename(session, procedure, newName, lock);
            return 0;
        }
        if (attributes != null) {
            attributes.applyTo(procedure);
        }
        if (procedure.isSecurityDefiner() && procedure.getOwnerName() == null) {
            // SECURITY DEFINER时属主默认是执行ALTER的用户
            procedure.setOwnerName(session.getUser().getName());
        }
        if (ownerName != null) {
            // 属主用户必须存在；属主只记录不生效，Lealone没有过程属主/权限模型
            db.getUser(session, ownerName);
            procedure.setOwnerName(ownerName);
        }
        if (setConfigName != null) {
            procedure.removeConfigItem(setConfigName);
            procedure.addConfigItem(setConfigName, setConfigValue);
        }
        if (resetConfigName != null) {
            procedure.removeConfigItem(resetConfigName);
        }
        if (resetAll) {
            procedure.clearConfigItems();
        }
        db.updateMeta(session, procedure);
        return 0;
    }
}
