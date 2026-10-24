/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.dml;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.auth.RightOwner;
import com.lealone.db.auth.Role;
import com.lealone.db.auth.User;
import com.lealone.db.session.ServerSession;

/**
 * SET ROLE / SET ROLE NONE / RESET ROLE，只切换当前会话的角色，不改变登录用户和当前用户。
 * 和PostgreSQL一样，超级用户可以切到任何角色，普通用户只能切到自己所属的角色(或自己)，
 * 不能借SET ROLE提权。
 */
public class SetRole extends SetStatement {

    // null表示SET ROLE NONE或者RESET ROLE，切回登录用户
    private String roleName;

    public SetRole(ServerSession session) {
        super(session);
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    @Override
    protected String getSettingName() {
        return "ROLE";
    }

    @Override
    public int update() {
        if (roleName == null) {
            // SET ROLE NONE / RESET ROLE
            session.setCurrentRole(null);
            return 0;
        }
        Database db = session.getDatabase();
        Role role = db.findRole(session, roleName);
        User user = role == null ? db.findUser(session, roleName) : null;
        if (role == null && user == null) {
            throw DbException.get(ErrorCode.USER_OR_ROLE_NOT_FOUND_1, roleName);
        }
        RightOwner target = role != null ? role : user;
        // SET SESSION AUTHORIZATION之后按新的当前用户算，和PostgreSQL的session user口径一致
        User currentUser = session.getUser();
        // 超级用户能切到任何角色; 普通用户只能切到自己，或者自己所属的角色
        if (!currentUser.isAdmin() && target != currentUser) {
            if (role == null || !currentUser.isRoleGranted(role)) {
                throw DbException.get(ErrorCode.INSUFFICIENT_PRIVILEGE_1,
                        "set role " + roleName);
            }
        }
        session.setCurrentRole(target);
        return 0;
    }
}
