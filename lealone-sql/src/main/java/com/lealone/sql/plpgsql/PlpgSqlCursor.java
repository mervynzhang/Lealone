/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.List;

import com.lealone.db.api.CursorPortal;
import com.lealone.db.result.Result;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;

/**
 * PL/pgSQL游标的运行时状态。OPEN时把查询结果流式搬进游标自己的存储，然后立刻关掉引擎的Result：
 * 行数不超过maxMemoryRows时留在内存里，超过之后写进游标自己的tuplestore(引擎临时StorageMap，会落盘)，
 * 所以大游标保持打开时堆占用也不是"整个结果集"。
 * FETCH任何方向都按行号直接取，回退不用重放。
 */
public class PlpgSqlCursor implements CursorPortal {

    private final String name;
    private String query;
    private List<String> parameterNames;
    private List<String> parameterTypes;
    // 小结果集直接留在内存里，这样小游标不用付落盘代价
    private List<Value[]> memoryRows;
    // 大结果集落到游标自己的tuplestore，关闭时连临时文件一起删掉
    private PlpgSqlTuplestore tuplestore;
    private String[] columnNames;
    private int[] columnTypes;
    private int rowCount;
    // 当前行下标，-1表示在第一行之前，rowCount表示在最后一行之后
    private int position = -1;
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

    public void open(ServerSession session, Result result, String[] columnNames,
            int[] columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.position = -1;
        this.currentRow = null;
        this.rowCount = 0;
        this.open = true;
        try {
            dump(session, result);
        } catch (RuntimeException e) {
            // 搬行过程中出错要立刻释放已经落盘的临时map
            closeStorage();
            this.open = false;
            throw e;
        }
    }

    // OPEN时把引擎结果流式搬进游标自己的存储，然后立刻关掉引擎的Result
    private void dump(ServerSession session, Result result) {
        if (result == null)
            return;
        int memoryLimit = session.getDatabase().getMaxMemoryRows();
        int columnCount = columnNames == null ? 0 : columnNames.length;
        try {
            while (result.next()) {
                Value[] row = result.currentRow();
                Value[] values = copyRow(row, columnCount);
                if (tuplestore == null && rowCount < memoryLimit) {
                    if (memoryRows == null)
                        memoryRows = new ArrayList<>();
                    memoryRows.add(values);
                } else {
                    if (tuplestore == null)
                        switchToTuplestore(session, columnCount);
                    tuplestore.add(values);
                }
                rowCount++;
            }
        } finally {
            result.close();
        }
        if (tuplestore != null)
            tuplestore.flush();
    }

    // 超过内存阈值时才建tuplestore，并把已经攒在内存里的行搬过去
    private void switchToTuplestore(ServerSession session, int columnCount) {
        tuplestore = new PlpgSqlTuplestore(session, columnCount);
        if (memoryRows != null) {
            for (int i = 0; i < memoryRows.size(); i++)
                tuplestore.add(memoryRows.get(i));
            memoryRows = null;
        }
    }

    // 结果行可能带排序用的内部列，只保留可见列；结果里的数组可能被复用，一律复制一份
    private Value[] copyRow(Value[] row, int columnCount) {
        int count = row == null ? 0 : row.length;
        if (count > columnCount)
            count = columnCount;
        Value[] values = new Value[count];
        if (row != null)
            System.arraycopy(row, 0, values, 0, count);
        return values;
    }

    public int size() {
        return rowCount;
    }

    // 按行号取行，内存和tuplestore两条路
    private Value[] rowAt(int pos) {
        if (pos < 0 || pos >= rowCount)
            return null;
        if (memoryRows != null)
            return memoryRows.get(pos);
        if (tuplestore == null)
            return null;
        return tuplestore.get(pos);
    }

    public Value[] currentRow() {
        return open ? currentRow : null;
    }

    public void close() {
        closeStorage();
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

    // 释放游标自己的存储(内存行 + 落盘临时map，后者连临时文件一起删掉)
    private void closeStorage() {
        if (tuplestore != null) {
            tuplestore.close();
            tuplestore = null;
        }
        if (memoryRows != null) {
            memoryRows.clear();
            memoryRows = null;
        }
        this.rowCount = 0;
    }

    // FETCH的方向，返回这次实际经过的行数
    public int move(String direction, int count) {
        if (!open)
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
            Value[] row = rowAt(position + 1);
            if (row == null) {
                position = rowCount;
                currentRow = null;
                return fetched;
            }
            position++;
            fetched++;
            currentRow = row;
        }
        return fetched;
    }

    // 定位到指定行，-1表示第一行之前；tuplestore按行号直接定位，不需要重放
    private void seekTo(int target) {
        if (target < -1)
            target = -1;
        if (target > rowCount)
            target = rowCount;
        if (target >= 0 && target < rowCount) {
            currentRow = rowAt(target);
            position = currentRow == null ? rowCount : target;
        } else {
            position = target;
            currentRow = null;
        }
    }

}
