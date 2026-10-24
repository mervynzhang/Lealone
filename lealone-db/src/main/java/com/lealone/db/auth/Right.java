/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.auth;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.DbObject;
import com.lealone.db.DbObjectBase;
import com.lealone.db.DbObjectType;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.schema.SqlProcedure;
import com.lealone.db.service.Service;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Table;

/**
 * An access right. Rights are regular database objects, but have generated
 * names.
 */
public class Right extends DbObjectBase {

    /**
     * The right bit mask that means: selecting from a table is allowed.
     */
    public static final int SELECT = 1;

    /**
     * The right bit mask that means: deleting rows from a table is allowed.
     */
    public static final int DELETE = 2;

    /**
     * The right bit mask that means: inserting rows into a table is allowed.
     */
    public static final int INSERT = 4;

    /**
     * The right bit mask that means: updating data is allowed.
     */
    public static final int UPDATE = 8;

    /**
     * The right bit mask that means: create/alter/drop schema is allowed.
     */
    public static final int ALTER_ANY_SCHEMA = 16;

    /**
     * The right bit mask that means: execute service is allowed.
     */
    public static final int EXECUTE = 32;

    /**
     * The right bit mask that means: select, insert, update, delete, execute and update
     * for this object is allowed.
     */
    public static final int ALL = SELECT | DELETE | INSERT | UPDATE | EXECUTE;

    /**
     * To whom the right is granted.
     */
    private RightOwner grantee;

    /**
     * The granted role, or null if a right was granted.
     */
    private Role grantedRole;

    /**
     * The granted right.
     */
    private int grantedRight;

    /**
     * The object. If the right is global, this is null.
     */
    private DbObject grantedObject;

    public Right(Database db, int id, RightOwner grantee, Role grantedRole) {
        super(db, id, "RIGHT_" + id);
        this.grantee = grantee;
        this.grantedRole = grantedRole;
    }

    public Right(Database db, int id, RightOwner grantee, int grantedRight, DbObject grantedObject) {
        super(db, id, "" + id);
        this.grantee = grantee;
        this.grantedRight = grantedRight;
        this.grantedObject = grantedObject;

        // TODO 如何更优雅的处理临时对象的授权(或者内存数据库中的所有对象的授权)
        // grantedObject有可能为null，如: GRANT ALTER ANY SCHEMA
        if (grantedObject != null
                && (grantedObject.isTemporary() || !grantedObject.getDatabase().isPersistent()))
            setTemporary(true);
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.RIGHT;
    }

    private static boolean appendRight(StringBuilder buff, int right, int mask, String name,
            boolean comma) {
        if ((right & mask) != 0) {
            if (comma) {
                buff.append(", ");
            }
            buff.append(name);
            return true;
        }
        return comma;
    }

    public String getRights() {
        StringBuilder buff = new StringBuilder();
        if (grantedRight == ALL) {
            buff.append("ALL");
        } else {
            boolean comma = false;
            comma = appendRight(buff, grantedRight, SELECT, "SELECT", comma);
            comma = appendRight(buff, grantedRight, DELETE, "DELETE", comma);
            comma = appendRight(buff, grantedRight, INSERT, "INSERT", comma);
            comma = appendRight(buff, grantedRight, EXECUTE, "EXECUTE", comma);
            comma = appendRight(buff, grantedRight, ALTER_ANY_SCHEMA, "ALTER ANY SCHEMA", comma);
            appendRight(buff, grantedRight, UPDATE, "UPDATE", comma);
        }
        return buff.toString();
    }

    public Role getGrantedRole() {
        return grantedRole;
    }

    public DbObject getGrantedObject() {
        return grantedObject;
    }

    public DbObject getGrantee() {
        return grantee;
    }

    @Override
    public String getCreateSQL() {
        // 0权限记录是"显式REVOKE掉了"的标记(函数/过程才有)，回放时也要还原成REVOKE
        if (grantedRole == null && grantedRight == 0 && grantedObject != null) {
            StringBuilder buff = new StringBuilder("REVOKE EXECUTE");
            appendObjectSQL(buff, grantedObject);
            buff.append(" FROM ").append(grantee.getSQL());
            return buff.toString();
        }
        StringBuilder buff = new StringBuilder();
        buff.append("GRANT ");
        if (grantedRole != null) {
            buff.append(grantedRole.getSQL());
        } else {
            buff.append(getRights());
            appendObjectSQL(buff, grantedObject);
        }
        buff.append(" TO ").append(grantee.getSQL());
        return buff.toString();
    }

    // 拼出" ON SCHEMA ..."/" ON PROCEDURE ...(签名)"这段，GRANT和REVOKE共用
    private static void appendObjectSQL(StringBuilder buff, DbObject object) {
        if (object instanceof Schema) {
            buff.append(" ON SCHEMA ").append(object.getSQL());
        } else if (object instanceof Service) {
            buff.append(" ON SERVICE ").append(object.getSQL());
        } else if (object instanceof Table) {
            buff.append(" ON ").append(object.getSQL());
        } else if (object instanceof SqlProcedure) {
            // 过程要带上完整参数签名，回放时才能定位到指定的重载
            SqlProcedure procedure = (SqlProcedure) object;
            buff.append(" ON PROCEDURE ").append(procedure.getSQL()).append('(')
                    .append(procedure.getParameterSignatureSQL()).append(')');
        } else if (object instanceof FunctionAlias) {
            buff.append(" ON FUNCTION ").append(object.getSQL());
            if (object instanceof SqlFunction) {
                buff.append('(').append(((SqlFunction) object).getInputParameterTypesSQL())
                        .append(')');
            }
        }
    }

    @Override
    public void removeChildrenAndResources(ServerSession session, DbObjectLock lock) {
        if (grantedRole != null) {
            grantee.revokeRole(grantedRole);
        } else {
            grantee.revokeRight(grantedObject);
        }
        super.removeChildrenAndResources(session, lock);
    }

    @Override
    public void invalidate() {
        grantedRole = null;
        grantedObject = null;
        grantee = null;
        super.invalidate();
    }

    @Override
    public void checkRename() {
        DbException.throwInternalError();
    }

    public void setRightMask(int rightMask) {
        grantedRight = rightMask;
    }

    public int getRightMask() {
        return grantedRight;
    }
}
