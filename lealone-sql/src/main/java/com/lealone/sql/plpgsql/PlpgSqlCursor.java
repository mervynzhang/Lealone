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
 * PL/pgSQL游标的运行时状态，三档路径：
 * 1) 顺序流式(默认)：OPEN只执行查询并持有引擎Result，FETCH NEXT/FORWARD/ALL直接next()读，不复制行；
 * 2) 回退需求触发一次物化：第一次收到回退类FETCH时才reset()后把结果搬进游标自己的存储
 *    (行数不超过maxMemoryRows留内存，超过才落tuplestore)，然后关掉引擎Result，之后都按行号取；
 * 3) 跨语句存活：refcursor门户在函数退出(最外层语句结束)时如果还活着，就物化并关掉引擎Result，
 *    这样"游标保持打开"的常驻堆不是整个结果集。
 */
public class PlpgSqlCursor implements CursorPortal {

    private final String name;
    private String query;
    private List<String> parameterNames;
    private List<String> parameterTypes;
    // 物化时需要会话(取maxMemoryRows、开临时map)
    private ServerSession session;
    // 模式1：引擎结果，顺序读直接用
    private Result result;
    // 模式2/3：物化后的存储，小的留内存，大的落tuplestore
    private List<Value[]> memoryRows;
    private PlpgSqlTuplestore tuplestore;
    private String[] columnNames;
    private int[] columnTypes;
    private int rowCount;
    private boolean materialized;
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

    // OPEN只执行查询并持有结果，真正的搬运推迟到需要回退或者门户要活到语句之外时
    public void open(ServerSession session, Result result, String[] columnNames,
            int[] columnTypes) {
        this.session = session;
        this.result = result;
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.position = -1;
        this.currentRow = null;
        this.materialized = false;
        int count = result == null ? 0 : result.getRowCount();
        this.rowCount = count < 0 ? 0 : count;
        this.open = true;
    }

    /**
     * 把引擎Result搬进游标自己的存储并立刻关掉Result。
     * 行数不超过maxMemoryRows时只留在内存(小游标不建临时map)，超过才写tuplestore(会落盘)。
     */
    public void materialize() {
        if (!open || materialized)
            return;
        materialized = true;
        int current = position;
        if (result == null) {
            rowCount = 0;
            position = -1;
            currentRow = null;
            return;
        }
        int memoryLimit = session.getDatabase().getMaxMemoryRows();
        int columnCount = columnNames == null ? 0 : columnNames.length;
        int counted = 0;
        try {
            result.reset();
            while (result.next()) {
                Value[] row = result.currentRow();
                if (tuplestore == null && counted < memoryLimit) {
                    if (memoryRows == null)
                        memoryRows = new ArrayList<>();
                    memoryRows.add(copyRow(row, columnCount));
                } else {
                    if (tuplestore == null)
                        switchToTuplestore(columnCount);
                    // tuplestore会立刻把行序列化，不需要先复制一份
                    tuplestore.add(visibleRow(row, columnCount));
                }
                counted++;
            }
        } finally {
            result.close();
            result = null;
        }
        if (tuplestore != null)
            tuplestore.flush();
        rowCount = counted;
        // 物化前读到哪一行，物化后还停在哪一行
        if (current >= 0 && current < rowCount) {
            currentRow = rowAt(current);
            position = currentRow == null ? rowCount : current;
        } else {
            position = current < 0 ? -1 : rowCount;
            currentRow = null;
        }
    }

    // 超过内存阈值时才建tuplestore，并把已经攒在内存里的行搬过去
    private void switchToTuplestore(int columnCount) {
        tuplestore = new PlpgSqlTuplestore(session, columnCount);
        if (memoryRows != null) {
            for (int i = 0; i < memoryRows.size(); i++)
                tuplestore.add(memoryRows.get(i));
            memoryRows = null;
        }
    }

    // 结果行可能带排序用的内部列，只保留可见列；可见列没少就直接用结果里的数组
    private static Value[] visibleRow(Value[] row, int columnCount) {
        if (row == null || row.length <= columnCount)
            return row;
        Value[] values = new Value[columnCount];
        System.arraycopy(row, 0, values, 0, columnCount);
        return values;
    }

    // 留在内存里的行必须复制，结果里的数组会被复用
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
        if (!materialized && result != null) {
            int count = result.getRowCount();
            if (count >= 0)
                return count;
        }
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
        this.materialized = false;
        this.session = null;
    }

    @Override
    public void closePortal() {
        close();
    }

    // 释放游标自己的存储(引擎Result + 内存行 + 落盘临时map，后者连临时文件一起删掉)
    private void closeStorage() {
        if (result != null) {
            result.close();
            result = null;
        }
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
        // 回退类的方向第一次用到时，才把结果物化一次
        if (!materialized && isRewindDirection(dir))
            materialize();
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

    // 需要回到已经读过的行，只有物化之后才能做
    private static boolean isRewindDirection(String dir) {
        return "PRIOR".equals(dir) || "BACKWARD".equals(dir) || "FIRST".equals(dir)
                || "LAST".equals(dir) || "ABSOLUTE".equals(dir) || "RELATIVE".equals(dir)
                || "BACKWARD_ALL".equals(dir);
    }

    // 顺序读，走到末尾后停在"最后一行之后"，返回真正取到的行数
    private int fetchForward(int count) {
        int fetched = 0;
        if (!materialized) {
            // 模式1：直接顺序读引擎结果，不复制行(结果数组在下次next()前有效)
            for (int i = 0; i < count; i++) {
                if (result == null || !result.next()) {
                    position = rowCount;
                    currentRow = null;
                    return fetched;
                }
                position++;
                fetched++;
                currentRow = result.currentRow();
            }
            return fetched;
        }
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

    // 定位到指定行，-1表示第一行之前；物化之后按行号直接定位，不需要重放
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
