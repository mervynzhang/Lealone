/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.List;

import com.lealone.db.api.CursorPortal;
import com.lealone.db.result.Result;
import com.lealone.db.value.Value;

/**
 * PL/pgSQL游标的运行时状态。OPEN时只执行查询并持有结果(Result)，不把行复制进内存；
 * 行数超过maxMemoryRows时由LocalResult自己落盘，所以大结果集不会撑爆堆。
 * 顺序方向(NEXT/FORWARD)一路next()读；回退方向先reset()再前进到目标行，
 * 缓冲区就是LocalResult本身(可能已落盘)，回退不会额外占用内存。
 */
public class PlpgSqlCursor implements CursorPortal {

    private final String name;
    private String query;
    private List<String> parameterNames;
    private List<String> parameterTypes;
    // 查询结果，行数超过阈值时LocalResult会把行落到临时文件
    private Result result;
    private String[] columnNames;
    private int[] columnTypes;
    // 当前行下标，-1表示在第一行之前，rowCount表示在最后一行之后
    private int position = -1;
    // 当前行，Result.currentRow()返回的数组在下次next()之前都有效，解释器拿到后立刻用掉
    private Value[] currentRow;
    private boolean open;

    public PlpgSqlCursor(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<String> getParameterNames() {
        return parameterNames;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public void setParameters(List<String> parameterNames, List<String> parameterTypes) {
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
    }

    public boolean isOpen() {
        return open;
    }

    public String[] getColumnNames() {
        return columnNames;
    }

    public int[] getColumnTypes() {
        return columnTypes;
    }

    public void open(Result result, String[] columnNames, int[] columnTypes) {
        this.result = result;
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.position = -1;
        this.currentRow = null;
        this.open = true;
    }

    public int size() {
        if (result == null)
            return 0;
        int count = result.getRowCount();
        return count < 0 ? 0 : count;
    }

    public Value[] currentRow() {
        return open ? currentRow : null;
    }

    public void close() {
        if (result != null) {
            // 落盘结果持有临时文件，关闭游标时必须释放
            result.close();
            result = null;
        }
        this.columnNames = null;
        this.columnTypes = null;
        this.position = -1;
        this.currentRow = null;
        this.open = false;
    }

    @Override
    public void closePortal() {
        close();
    }

    // FETCH的方向，返回这次实际经过的行数
    public int move(String direction, int count) {
        if (!open || result == null)
            return 0;
        String dir = direction == null ? "NEXT" : direction.toUpperCase();
        int old = position;
        int fetched = 0;
        if ("NEXT".equals(dir) || "FORWARD".equals(dir)) {
            fetched = fetchForward(count <= 0 ? 1 : count);
        } else if ("PRIOR".equals(dir) || "BACKWARD".equals(dir)) {
            seekTo(position - (count <= 0 ? 1 : count));
        } else if ("FIRST".equals(dir)) {
            seekTo(0);
        } else if ("LAST".equals(dir)) {
            seekTo(size() - 1);
        } else if ("ABSOLUTE".equals(dir)) {
            seekTo(count >= 0 ? count - 1 : size() + count);
        } else if ("RELATIVE".equals(dir)) {
            seekTo(position + count);
        } else if ("ALL".equals(dir) || "FORWARD_ALL".equals(dir)) {
            fetched = fetchForward(Integer.MAX_VALUE);
        } else if ("BACKWARD_ALL".equals(dir)) {
            seekTo(-1);
        } else {
            fetched = fetchForward(1);
        }
        // 顺序方向返回真正取到的行数(FETCH ALL的ROW_COUNT用它)，回退方向返回位置变化量
        if (fetched > 0)
            return fetched;
        int moved = position - old;
        return moved < 0 ? -moved : moved;
    }

    // 顺序读，走到末尾后停在"最后一行之后"，返回真正取到的行数
    private int fetchForward(int count) {
        int fetched = 0;
        for (int i = 0; i < count; i++) {
            if (!result.next()) {
                position = size();
                currentRow = null;
                return fetched;
            }
            position++;
            fetched++;
            currentRow = visibleRow(result.currentRow());
        }
        return fetched;
    }

    // 定位到指定行，-1表示第一行之前；回退时从头重放(LocalResult落盘后同样支持reset)
    private void seekTo(int target) {
        if (target < -1)
            target = -1;
        int count = size();
        if (target > count)
            target = count;
        if (target < position) {
            result.reset();
            position = -1;
            currentRow = null;
        }
        if (target >= 0 && target < count) {
            fetchForward(target - position);
        } else {
            position = target;
            currentRow = null;
        }
    }

    // 结果行可能带排序用的内部列，可见列更少时复制一份，否则直接用结果里的数组(避免逐行分配)
    private Value[] visibleRow(Value[] row) {
        if (row == null || columnNames == null || row.length <= columnNames.length)
            return row;
        Value[] copy = new Value[columnNames.length];
        System.arraycopy(row, 0, copy, 0, columnNames.length);
        return copy;
    }
}
