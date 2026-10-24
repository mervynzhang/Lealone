/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.List;

import com.lealone.db.api.CursorPortal;
import com.lealone.db.value.Value;

/**
 * PL/pgSQL游标的运行时状态。查询结果在OPEN时一次性物化，
 * FETCH只移动位置，不会重新执行查询。
 */
public class PlpgSqlCursor implements CursorPortal {

    private final String name;
    private String query;
    private List<String> parameterNames;
    private List<String> parameterTypes;
    private List<Value[]> rows;
    private String[] columnNames;
    private int[] columnTypes;
    // 当前行下标，-1表示在第一行之前，rows.size()表示在最后一行之后
    private int position = -1;
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

    public List<Value[]> getRows() {
        return rows;
    }

    public String[] getColumnNames() {
        return columnNames;
    }

    public int[] getColumnTypes() {
        return columnTypes;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void open(PlpgSqlSqlExecutor.QueryRows queryRows) {
        this.rows = queryRows.rows;
        this.columnNames = queryRows.columnNames;
        this.columnTypes = queryRows.columnTypes;
        this.position = -1;
        this.open = true;
    }

    public int size() {
        return rows == null ? 0 : rows.size();
    }

    public Value[] currentRow() {
        if (!open || rows == null || position < 0 || position >= rows.size())
            return null;
        return rows.get(position);
    }

    public void close() {
        this.rows = null;
        this.columnNames = null;
        this.columnTypes = null;
        this.position = -1;
        this.open = false;
    }

    @Override
    public void closePortal() {
        close();
    }

    // FETCH的方向，返回这次实际取到的行数
    public int move(String direction, int count) {
        int size = size();
        String dir = direction == null ? "NEXT" : direction.toUpperCase();
        int old = position;
        if ("NEXT".equals(dir) || "FORWARD".equals(dir)) {
            if (count <= 0)
                count = 1;
            position = position + count;
        } else if ("PRIOR".equals(dir) || "BACKWARD".equals(dir)) {
            if (count <= 0)
                count = 1;
            position = position - count;
        } else if ("FIRST".equals(dir)) {
            position = size == 0 ? -1 : 0;
        } else if ("LAST".equals(dir)) {
            position = size == 0 ? -1 : size - 1;
        } else if ("ABSOLUTE".equals(dir)) {
            position = count >= 0 ? count - 1 : size + count;
        } else if ("RELATIVE".equals(dir)) {
            position = position + count;
        } else if ("ALL".equals(dir) || "FORWARD_ALL".equals(dir)) {
            position = size == 0 ? -1 : size - 1;
        } else if ("BACKWARD_ALL".equals(dir)) {
            position = size == 0 ? -1 : 0;
        } else {
            position = position + 1;
        }
        // 越界时停到第一行之前或者最后一行之后，和PostgreSQL一致
        if (position < -1)
            position = -1;
        if (position > size)
            position = size;
        int moved = position - old;
        if (moved < 0)
            moved = -moved;
        return moved;
    }

    public List<Value[]> allRows() {
        return rows == null ? new ArrayList<>() : rows;
    }
}
