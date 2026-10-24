/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER FUNCTION
 */
public class AlterSqlFunction extends SchemaStatement {

    private String functionName;
    private boolean ifExists;
    // DROP FUNCTION/ALTER FUNCTION里可选的参数类型列表，null表示没写
    private int[] argumentTypeIds;
    private FunctionAttributes attributes;
    private String newName;
    private String ownerName;
    private String setConfigName;
    private String setConfigValue;
    private String resetConfigName;
    private boolean resetAll;

    public AlterSqlFunction(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_FUNCTION;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public void setArgumentTypeIds(int[] argumentTypeIds) {
        this.argumentTypeIds = argumentTypeIds;
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

    @Override
    public int update() {
        session.getUser().checkAdmin();
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.FUNCTION_ALIAS, session);
        if (lock == null)
            return -1;

        FunctionAlias function = schema.findFunction(session, functionName);
        if (function == null) {
            if (ifExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1, functionName);
        }
        if (!(function instanceof SqlFunction)) {
            // CREATE ALIAS建的Java函数没有这些属性
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "ALTER FUNCTION on Java alias: " + functionName);
        }
        SqlFunction sqlFunction = (SqlFunction) function;
        if (!sqlFunction.matchesSignature(argumentTypeIds)) {
            if (ifExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, sqlFunction.getSignature());
        }
        Database db = session.getDatabase();
        if (newName != null) {
            if (schema.findFunction(session, newName) != null) {
                throw DbException.get(ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1, newName);
            }
            // schema对象的改名要走Schema.rename，Database.renameDatabaseObject只处理非schema对象
            schema.rename(session, sqlFunction, newName, lock);
            return 0;
        }
        applyAttributes(sqlFunction);
        if (ownerName != null) {
            // 属主用户必须存在；属主只记录不生效，Lealone没有函数属主/权限模型
            db.getUser(session, ownerName);
            sqlFunction.setOwnerName(ownerName);
        }
        if (setConfigName != null) {
            sqlFunction.removeConfigItem(setConfigName);
            sqlFunction.addConfigItem(setConfigName, setConfigValue);
        }
        if (resetConfigName != null) {
            sqlFunction.removeConfigItem(resetConfigName);
        }
        if (resetAll) {
            sqlFunction.clearConfigItems();
        }
        db.updateMeta(session, sqlFunction);
        return 0;
    }

    private void applyAttributes(SqlFunction function) {
        if (attributes == null) {
            return;
        }
        if (attributes.getVolatility() != null) {
            function.setVolatility(attributes.getVolatility());
        }
        if (attributes.getStrict() != null) {
            function.setStrict(attributes.getStrict().booleanValue());
        }
        if (attributes.getSecurityDefiner() != null) {
            function.setSecurityDefiner(attributes.getSecurityDefiner().booleanValue());
        }
        if (attributes.getLeakproof() != null) {
            function.setLeakproof(attributes.getLeakproof().booleanValue());
        }
        if (attributes.getWindow() != null) {
            function.setWindow(attributes.getWindow().booleanValue());
        }
        if (attributes.getCost() != null) {
            function.setCost(attributes.getCost());
        }
        if (attributes.getRows() != null) {
            function.setRows(attributes.getRows());
        }
        for (String[] item : attributes.getConfigItems()) {
            function.removeConfigItem(item[0]);
            function.addConfigItem(item[0], item[1]);
        }
    }
}
