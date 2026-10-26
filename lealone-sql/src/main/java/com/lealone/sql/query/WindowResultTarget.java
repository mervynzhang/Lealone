/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import java.util.ArrayList;

import com.lealone.db.result.ResultTarget;
import com.lealone.db.value.Value;

/**
 * 有窗口函数时，查询算子先把行写到这个目标里缓存起来，等所有行都读完之后
 * 再计算窗口列，最后才交给真正的目标（LocalResult 或上层 ResultTarget）。
 * <p>
 * 窗口函数天生需要看到整个分区，没法逐行流式计算，所以这里必须先物化。
 */
class WindowResultTarget implements ResultTarget {

    private final ArrayList<Value[]> rows = new ArrayList<>();

    ArrayList<Value[]> getRows() {
        return rows;
    }

    @Override
    public boolean addRow(Value[] values) {
        rows.add(values);
        return true;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }
}
