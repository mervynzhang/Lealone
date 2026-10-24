/*
 * Copyright 2004-2024 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 */
package com.lealone.sql.dml;

import com.lealone.db.session.ServerSession;

/**
 * SET/RESET SESSION AUTHORIZATION，只切换当前用户，不改变登录用户。
 */
public class SetSessionAuthorization extends SetStatement {

    // null表示RESET，恢复成登录用户
    private String userName;

    public SetSessionAuthorization(ServerSession session) {
        super(session);
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    @Override
    protected String getSettingName() {
        return "SESSION AUTHORIZATION";
    }

    @Override
    public int update() {
        if (userName == null) {
            // RESET SESSION AUTHORIZATION
            session.setCurrentUser(session.getSessionUser());
        } else {
            // PostgreSQL的规则是"初始登录用户必须是超级用户"才能切换当前用户，
            // 所以这里检查登录用户而不是当前用户，否则切到普通用户后就切不回来了
            session.getSessionUser().checkAdmin();
            session.setCurrentUser(session.getDatabase().getUser(session, userName));
        }
        return 0;
    }
}
