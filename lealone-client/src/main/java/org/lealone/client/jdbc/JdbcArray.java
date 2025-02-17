/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.client.jdbc;

import org.lealone.common.trace.TraceObjectType;
import org.lealone.db.value.ArrayBase;
import org.lealone.db.value.Value;

/**
 * Represents an ARRAY value.
 */
public class JdbcArray extends ArrayBase {

    private final JdbcConnection conn;

    /**
     * INTERNAL
     */
    JdbcArray(JdbcConnection conn, Value value, int id) {
        this.conn = conn;
        this.value = value;
        this.trace = conn.getTrace(TraceObjectType.ARRAY, id);
    }

    @Override
    protected void checkClosed() {
        conn.checkClosed();
        super.checkClosed();
    }
}
