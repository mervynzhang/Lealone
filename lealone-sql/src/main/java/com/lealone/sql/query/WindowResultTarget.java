/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.ResultTarget;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;

/**
 * 有窗口函数时，查询算子先把行写到这个目标里缓存起来，等所有行都读完之后
 * 再计算窗口列，最后才交给真正的目标（LocalResult 或上层 ResultTarget）。
 * <p>
 * 窗口函数天生需要看到整个分区，没法逐行流式计算，所以这里必须先物化。
 * <p>
 * 内存上界：最多缓存 {@code MAX_MEMORY_ROWS} 行（与 {@code LocalResult} 使用同一个设置，
 * 默认 {@code Constants.DEFAULT_MAX_MEMORY_ROWS} = 100000）。超限时抛
 * {@link ErrorCode#WINDOW_FUNCTION_BUFFER_EXCEEDS_MAX_MEMORY_ROWS_1}，
 * 而不是像普通结果集那样落盘：窗口计算需要对行做随机访问（按分区键/排序键重排），
 * 落盘后的行流不能再随机访问，所以这里选择明确的资源不足错误，绝不无界增长。
 * 计算阶段每个窗口函数额外使用的内存是 O(行数) 的两个 int 数组（见
 * {@code WindowFunction.Computation}），同一时刻只算一个窗口函数。
 */
class WindowResultTarget implements ResultTarget {

    private final int maxMemoryRows;
    private final ArrayList<Value[]> rows = new ArrayList<>();

    WindowResultTarget(ServerSession session) {
        if (session == null) {
            maxMemoryRows = Integer.MAX_VALUE;
        } else {
            maxMemoryRows = session.getDatabase().getMaxMemoryRows();
        }
    }

    ArrayList<Value[]> getRows() {
        return rows;
    }

    @Override
    public boolean addRow(Value[] values) {
        if (rows.size() >= maxMemoryRows) {
            throw DbException.get(ErrorCode.WINDOW_FUNCTION_BUFFER_EXCEEDS_MAX_MEMORY_ROWS_1,
                    String.valueOf(maxMemoryRows));
        }
        rows.add(values);
        return true;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }
}
