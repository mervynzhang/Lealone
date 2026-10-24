/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.api;

/**
 * 会话级的游标门户。PL/pgSQL的refcursor在事务内可见，事务结束时由会话统一关闭。
 */
public interface CursorPortal {

    void closePortal();
}
