/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.Database;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.result.SortOrder;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueDouble;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.aggregate.Aggregate;
import com.lealone.sql.expression.subquery.SubQuery;
import com.lealone.sql.expression.visitor.ExpressionVisitor;
import com.lealone.sql.expression.visitor.VoidExpressionVisitor;
import com.lealone.sql.query.Select;
import com.lealone.sql.query.SelectUnion;

/**
 * A window function call, as in {@code row_number() OVER (PARTITION BY a ORDER BY b)}.
 * <p>
 * 这里只实现聚合/编号类窗口函数中最常用的一个子集：
 * <ul>
 * <li>{@code ROW_NUMBER()}、{@code RANK()}、{@code DENSE_RANK()}</li>
 * <li>{@code COUNT(*) }、{@code COUNT(expr)}、{@code SUM}、{@code MIN}、{@code MAX}、{@code AVG}</li>
 * </ul>
 * {@code PARTITION BY} 和 {@code ORDER BY} 都可以省略；{@code OVER ()} 表示整张表一个分区。
 * 不支持窗口帧（ROWS/RANGE/GROUPS）、命名窗口、{@code DISTINCT} 参数，也不支持带 {@code ORDER BY}
 * 的聚合窗口（那是 running aggregate 语义，跟"整个分区聚合一次"不同，宁可报错也不给错结果）。
 * <p>
 * 执行方式：不做流式窗口算子。查询先把所有行物化，窗口函数用到的表达式（聚合参数、分区键、排序键）
 * 在解析阶段就作为隐藏列追加到 select 列表末尾，所以每一行物化时这些值已经算好（见
 * {@link #prepareColumns(ArrayList)}）。物化完成后按 (分区键, 排序键) 对行号做一次稳定排序，
 * 再逐个分区写回窗口列（见 {@link #compute(ArrayList, ServerSession)}）。
 * <p>
 * 语义约定：
 * <ul>
 * <li>{@code ROW_NUMBER()} 在分区内按 ORDER BY 升序从 1 开始编号；没有 ORDER BY 时按扫描顺序，
 * 结果不保证稳定（与 PG 一致）。</li>
 * <li>窗口的 ORDER BY 不会改变最终结果的输出顺序，需要输出顺序请在外层写 ORDER BY。</li>
 * <li>窗口 ORDER BY 的 NULL 默认位置按 PG 的约定：ASC 时 NULLS LAST，DESC 时 NULLS FIRST
 * （Lealone 的普通 ORDER BY 默认是 ASC 时 NULLS FIRST，两者不同，这里选 PG 语义）。</li>
 * </ul>
 */
public class WindowFunction extends Expression {

    // 编号类窗口函数
    public static final int ROW_NUMBER = 1;
    public static final int RANK = 2;
    public static final int DENSE_RANK = 3;

    // 聚合类窗口函数
    public static final int COUNT_ALL = 4;
    public static final int COUNT = 5;
    public static final int SUM = 6;
    public static final int MIN = 7;
    public static final int MAX = 8;
    public static final int AVG = 9;

    private final int type;
    // 聚合窗口的参数，COUNT(*) 时为 null
    private final Expression on;

    private ArrayList<Expression> partitionBy;
    private ArrayList<SelectOrderBy> orderBy;

    private int dataType;
    private int scale;
    private long precision;
    private int displaySize;

    // 在 select 表达式列表中的位置（可见列）
    private int outputColumnIndex;
    // 聚合参数对应的隐藏列，-1 表示没有（COUNT(*)）
    private int argColumnIndex = -1;
    // 分区键、排序键对应的隐藏列
    private int[] partitionColumnIndexes;
    private int[] orderColumnIndexes;
    private int[] orderSortTypes;

    // (分区键 + 排序键) 的排序规则，用于把同一个分区的行排到一起
    private SortOrder sortOrder;
    // 只比较分区键，用于判断分区边界
    private SortOrder partitionSortOrder;
    // 只比较排序键，用于判断 peer group（RANK/DENSE_RANK）
    private SortOrder orderSortOrder;

    public WindowFunction(int type, Expression on) {
        this.type = type;
        this.on = on;
    }

    /**
     * 把一个普通聚合函数(Aggregate)转换成等价的窗口聚合函数。
     *
     * @param aggregateType Aggregate.COUNT_ALL / COUNT / SUM / MIN / MAX / AVG
     * @param aggregate 解析出来的聚合函数节点，用来取参数
     * @param distinct 是否写了 DISTINCT
     * @throws DbException 如果是不支持的聚合函数或者带了 DISTINCT
     */
    public static WindowFunction fromAggregate(int aggregateType, Aggregate aggregate,
            boolean distinct) {
        int type;
        switch (aggregateType) {
        case Aggregate.COUNT_ALL:
            type = COUNT_ALL;
            break;
        case Aggregate.COUNT:
            type = COUNT;
            break;
        case Aggregate.SUM:
            type = SUM;
            break;
        case Aggregate.MIN:
            type = MIN;
            break;
        case Aggregate.MAX:
            type = MAX;
            break;
        case Aggregate.AVG:
            type = AVG;
            break;
        default:
            throw DbException.getUnsupportedException(
                    "window function: " + aggregate.getSQL());
        }
        if (distinct) {
            throw DbException.getUnsupportedException(
                    "DISTINCT in window function: " + aggregate.getSQL());
        }
        return new WindowFunction(type, aggregate.getOn());
    }

    public Expression getOn() {
        return on;
    }

    public ArrayList<Expression> getPartitionBy() {
        return partitionBy;
    }

    public void setPartitionBy(ArrayList<Expression> partitionBy) {
        this.partitionBy = partitionBy;
    }

    public ArrayList<SelectOrderBy> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(ArrayList<SelectOrderBy> orderBy) {
        this.orderBy = orderBy;
    }

    public boolean isAggregate() {
        return type >= COUNT_ALL;
    }

    /**
     * 带 ORDER BY 的聚合窗口在 PG 里是 running aggregate（默认帧到当前行），
     * 本子集只支持一次性算出整个分区的聚合值，所以这种写法必须拒绝，不能静默返回错误的数字。
     */
    public void checkSupported() {
        if (isAggregate() && orderBy != null && !orderBy.isEmpty()) {
            throw DbException.getUnsupportedException("window aggregate with ORDER BY (running "
                    + "aggregate frame semantics), use PARTITION BY only: " + getSQL());
        }
    }

    public void setOutputColumnIndex(int outputColumnIndex) {
        this.outputColumnIndex = outputColumnIndex;
    }

    /**
     * 把窗口用到的表达式作为隐藏列追加到 select 的表达式列表末尾。
     * 这样每行物化时这些值就已经算好，计算窗口时按列索引直接取即可，
     * 不需要在物化之后重新求值（物化之后表行已经不在当前行了）。
     */
    public void prepareColumns(ArrayList<Expression> expressions) {
        if (on != null) {
            argColumnIndex = addHiddenColumn(expressions, on);
        }
        if (partitionBy != null && !partitionBy.isEmpty()) {
            int size = partitionBy.size();
            partitionColumnIndexes = new int[size];
            for (int i = 0; i < size; i++) {
                partitionColumnIndexes[i] = addHiddenColumn(expressions, partitionBy.get(i));
            }
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            int size = orderBy.size();
            orderColumnIndexes = new int[size];
            orderSortTypes = new int[size];
            for (int i = 0; i < size; i++) {
                SelectOrderBy o = orderBy.get(i);
                orderColumnIndexes[i] = addHiddenColumn(expressions, o.expression);
                int sortType = o.descending ? SortOrder.DESCENDING : SortOrder.ASCENDING;
                if (o.nullsFirst) {
                    sortType += SortOrder.NULLS_FIRST;
                } else if (o.nullsLast) {
                    sortType += SortOrder.NULLS_LAST;
                } else {
                    // PG 的默认位置：ASC 时 NULL 最大(NULLS LAST)，DESC 时 NULL 最小(NULLS FIRST)
                    sortType += o.descending ? SortOrder.NULLS_FIRST : SortOrder.NULLS_LAST;
                }
                orderSortTypes[i] = sortType;
            }
        }
    }

    private static int addHiddenColumn(ArrayList<Expression> expressions, Expression e) {
        int index = expressions.size();
        expressions.add(e);
        return index;
    }

    /**
     * 在 optimize() 之后调用，为 (分区键, 排序键) 建立排序规则。
     */
    public void prepareSortOrder(ServerSession session) {
        Database db = session.getDatabase();
        int pn = partitionColumnIndexes == null ? 0 : partitionColumnIndexes.length;
        int on = orderColumnIndexes == null ? 0 : orderColumnIndexes.length;
        if (pn + on == 0) {
            // 没有 PARTITION BY 也没有 ORDER BY：整张表一个分区，按扫描顺序
            return;
        }
        int[] indexes = new int[pn + on];
        int[] sortTypes = new int[pn + on];
        for (int i = 0; i < pn; i++) {
            indexes[i] = partitionColumnIndexes[i];
            // 分区键只用于分组，升降序无意义
            sortTypes[i] = SortOrder.ASCENDING;
        }
        for (int i = 0; i < on; i++) {
            indexes[pn + i] = orderColumnIndexes[i];
            sortTypes[pn + i] = orderSortTypes[i];
        }
        sortOrder = new SortOrder(db, indexes, sortTypes, null);
        if (pn > 0) {
            partitionSortOrder = new SortOrder(db, partitionColumnIndexes, new int[pn], null);
        }
        if (on > 0) {
            orderSortOrder = new SortOrder(db, orderColumnIndexes, orderSortTypes, null);
        }
    }

    /**
     * 在物化后的行上计算本窗口函数，结果直接写回每行的窗口列。
     *
     * @param rows 物化后的行（包含隐藏列）
     * @param session 当前会话
     */
    public void compute(ArrayList<Value[]> rows, ServerSession session) {
        int rowCount = rows.size();
        if (rowCount == 0) {
            return;
        }
        Integer[] order = new Integer[rowCount];
        for (int i = 0; i < rowCount; i++) {
            order[i] = i;
        }
        if (sortOrder != null) {
            // 稳定排序：ORDER BY 相等的行之间保持扫描顺序
            Arrays.sort(order, new RowComparator(rows, sortOrder));
        }
        int start = 0;
        while (start < rowCount) {
            int end;
            if (partitionSortOrder == null) {
                end = rowCount; // 整张表一个分区
            } else {
                end = start + 1;
                Value[] first = rows.get(order[start]);
                while (end < rowCount
                        && partitionSortOrder.compare(first, rows.get(order[end])) == 0) {
                    end++;
                }
            }
            computePartition(rows, order, start, end, session);
            start = end;
        }
    }

    private void computePartition(ArrayList<Value[]> rows, Integer[] order, int start, int end,
            ServerSession session) {
        switch (type) {
        case ROW_NUMBER:
            for (int i = start; i < end; i++) {
                setWindowValue(rows.get(order[i]), ValueInt.get(i - start + 1));
            }
            return;
        case RANK:
        case DENSE_RANK: {
            int rank = 1;
            int denseRank = 1;
            for (int i = start; i < end; i++) {
                if (i > start && !isPeer(rows.get(order[i - 1]), rows.get(order[i]))) {
                    rank = i - start + 1;
                    denseRank++;
                }
                setWindowValue(rows.get(order[i]), ValueInt.get(type == RANK ? rank : denseRank));
            }
            return;
        }
        default: // 聚合窗口：整个分区聚合一次，结果写到分区的每一行
            Value value = aggregate(rows, order, start, end, session);
            for (int i = start; i < end; i++) {
                setWindowValue(rows.get(order[i]), value);
            }
        }
    }

    private void setWindowValue(Value[] row, Value value) {
        row[outputColumnIndex] = value;
    }

    private boolean isPeer(Value[] a, Value[] b) {
        if (orderSortOrder == null) {
            // 没有 ORDER BY：整个分区的行互为 peer (PG 的 rank() over () 全是 1)
            return true;
        }
        return orderSortOrder.compare(a, b) == 0;
    }

    private Value aggregate(ArrayList<Value[]> rows, Integer[] order, int start, int end,
            ServerSession session) {
        long count = 0;
        Value value = null;
        for (int i = start; i < end; i++) {
            Value v = null;
            if (type != COUNT_ALL) {
                v = rows.get(order[i])[argColumnIndex];
                if (v == ValueNull.INSTANCE) {
                    continue;
                }
            }
            count++;
            switch (type) {
            case SUM:
                if (value == null) {
                    value = v.convertTo(dataType);
                } else {
                    value = value.add(v.convertTo(value.getType()));
                }
                break;
            case AVG:
                if (value == null) {
                    value = v.convertTo(DataType.getAddProofType(dataType));
                } else {
                    value = value.add(v.convertTo(value.getType()));
                }
                break;
            case MIN:
                if (value == null || session.getDatabase().compare(v, value) < 0) {
                    value = v;
                }
                break;
            case MAX:
                if (value == null || session.getDatabase().compare(v, value) > 0) {
                    value = v;
                }
                break;
            default: // COUNT / COUNT_ALL
                break;
            }
        }
        if (type == COUNT || type == COUNT_ALL) {
            return ValueLong.get(count);
        }
        if (value == null) {
            return ValueNull.INSTANCE;
        }
        if (type == AVG) {
            value = divide(value, count);
        }
        return value.convertTo(dataType);
    }

    private static Value divide(Value a, long by) {
        if (by == 0) {
            return ValueNull.INSTANCE;
        }
        int type = Value.getHigherOrder(a.getType(), Value.LONG);
        Value b = ValueLong.get(by).convertTo(type);
        return a.convertTo(type).divide(b);
    }

    private static final class RowComparator implements Comparator<Integer> {

        private final ArrayList<Value[]> rows;
        private final SortOrder sortOrder;

        RowComparator(ArrayList<Value[]> rows, SortOrder sortOrder) {
            this.rows = rows;
            this.sortOrder = sortOrder;
        }

        @Override
        public int compare(Integer a, Integer b) {
            return sortOrder.compare(rows.get(a), rows.get(b));
        }
    }

    /**
     * 检查一个表达式树里是否包含窗口函数。
     */
    public static boolean contains(Expression e) {
        WindowFunctionFinder finder = new WindowFunctionFinder();
        e.accept(finder);
        return finder.found;
    }

    private static final class WindowFunctionFinder extends VoidExpressionVisitor {

        boolean found;

        @Override
        public Void visitWindowFunction(WindowFunction e) {
            found = true;
            return null;
        }

        // 子查询有自己的 select 列表，里面的窗口函数由子查询自己检查，这里不进去
        @Override
        public Void visitSubQuery(SubQuery e) {
            return null;
        }

        @Override
        public Void visitSelect(Select s) {
            return null;
        }

        @Override
        public Void visitSelectUnion(SelectUnion su) {
            return null;
        }
    }

    @Override
    public Value getValue(ServerSession session) {
        // 窗口函数的值需要整个分区，不可能在扫描单行时算出来；
        // 行先被物化并填入 NULL，随后 compute() 会把真正的值写回到这一列。
        return ValueNull.INSTANCE;
    }

    @Override
    public int getType() {
        return dataType;
    }

    @Override
    public int getScale() {
        return scale;
    }

    @Override
    public long getPrecision() {
        return precision;
    }

    @Override
    public int getDisplaySize() {
        return displaySize;
    }

    @Override
    public int getNullable() {
        switch (type) {
        case ROW_NUMBER:
        case RANK:
        case DENSE_RANK:
        case COUNT:
        case COUNT_ALL:
            return Column.NOT_NULLABLE;
        default:
            return Column.NULLABLE;
        }
    }

    @Override
    public Expression optimize(ServerSession session) {
        // on/partitionBy/orderBy 里的表达式同时也会作为隐藏列被 optimize，
        // 这里只按当前(已 mapColumns 的)参数类型确定结果类型。
        switch (type) {
        case ROW_NUMBER:
        case RANK:
        case DENSE_RANK:
            dataType = Value.INT;
            scale = 0;
            precision = ValueInt.PRECISION;
            displaySize = ValueInt.DISPLAY_SIZE;
            break;
        case COUNT:
        case COUNT_ALL:
            dataType = Value.LONG;
            scale = 0;
            precision = ValueLong.PRECISION;
            displaySize = ValueLong.DISPLAY_SIZE;
            break;
        case MIN:
        case MAX:
            dataType = on.getType();
            scale = on.getScale();
            precision = on.getPrecision();
            displaySize = on.getDisplaySize();
            break;
        case SUM: {
            dataType = on.getType();
            scale = on.getScale();
            precision = on.getPrecision();
            displaySize = on.getDisplaySize();
            if (dataType == Value.BOOLEAN) {
                dataType = Value.LONG;
                scale = 0;
                precision = ValueLong.PRECISION;
                displaySize = ValueLong.DISPLAY_SIZE;
            } else if (dataType == Value.NULL || dataType == Value.UNKNOWN) {
                dataType = Value.DECIMAL;
            } else if (!DataType.supportsAdd(dataType)) {
                throw DbException.get(ErrorCode.SUM_OR_AVG_ON_WRONG_DATATYPE_1, getSQL());
            } else {
                dataType = DataType.getAddProofType(dataType);
            }
            break;
        }
        case AVG: {
            dataType = on.getType();
            scale = on.getScale();
            precision = on.getPrecision();
            displaySize = on.getDisplaySize();
            if (dataType == Value.NULL || dataType == Value.UNKNOWN) {
                dataType = Value.DECIMAL;
            } else if (!DataType.supportsAdd(dataType)) {
                throw DbException.get(ErrorCode.SUM_OR_AVG_ON_WRONG_DATATYPE_1, getSQL());
            } else if (dataType == Value.BYTE || dataType == Value.SHORT || dataType == Value.INT) {
                dataType = Value.DOUBLE;
                scale = 0;
                precision = ValueDouble.PRECISION;
                displaySize = ValueDouble.DISPLAY_SIZE;
            } else if (dataType == Value.LONG || dataType == Value.DECIMAL) {
                dataType = Value.DECIMAL;
                precision += 10;
                scale += 10;
            }
            break;
        }
        default:
            DbException.throwInternalError("type=" + type);
        }
        return this;
    }

    @Override
    public int getCost() {
        int cost = 1;
        if (on != null) {
            cost += on.getCost();
        }
        if (partitionBy != null) {
            for (Expression e : partitionBy) {
                cost += e.getCost();
            }
        }
        if (orderBy != null) {
            for (SelectOrderBy o : orderBy) {
                cost += o.expression.getCost();
            }
        }
        return cost;
    }

    @Override
    public String getAlias() {
        switch (type) {
        case ROW_NUMBER:
            return "ROW_NUMBER";
        case RANK:
            return "RANK";
        case DENSE_RANK:
            return "DENSE_RANK";
        case COUNT:
        case COUNT_ALL:
            return "COUNT";
        case SUM:
            return "SUM";
        case MIN:
            return "MIN";
        case MAX:
            return "MAX";
        case AVG:
            return "AVG";
        default:
            return super.getAlias();
        }
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        // 用独立的 StatementBuilder 组装，避免重置外层 builder 的 appendExceptFirst 计数器
        StatementBuilder buff = new StatementBuilder();
        buff.setDistributed(sql.isDistributed());
        switch (type) {
        case ROW_NUMBER:
            buff.append("ROW_NUMBER()");
            break;
        case RANK:
            buff.append("RANK()");
            break;
        case DENSE_RANK:
            buff.append("DENSE_RANK()");
            break;
        case COUNT_ALL:
            buff.append("COUNT(*)");
            break;
        case COUNT:
            appendFunction("COUNT", buff);
            break;
        case SUM:
            appendFunction("SUM", buff);
            break;
        case MIN:
            appendFunction("MIN", buff);
            break;
        case MAX:
            appendFunction("MAX", buff);
            break;
        case AVG:
            appendFunction("AVG", buff);
            break;
        default:
            DbException.throwInternalError("type=" + type);
        }
        buff.append(" OVER (");
        boolean needSpace = false;
        if (partitionBy != null && !partitionBy.isEmpty()) {
            buff.append("PARTITION BY ");
            int i = 0;
            for (Expression e : partitionBy) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                e.getSQL(buff);
            }
            needSpace = true;
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            if (needSpace) {
                buff.append(' ');
            }
            buff.append("ORDER BY ");
            int i = 0;
            for (SelectOrderBy o : orderBy) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                o.expression.getSQL(buff);
                if (o.descending) {
                    buff.append(" DESC");
                }
                if (o.nullsFirst) {
                    buff.append(" NULLS FIRST");
                } else if (o.nullsLast) {
                    buff.append(" NULLS LAST");
                }
            }
        }
        buff.append(')');
        sql.append(buff.toString());
    }

    private void appendFunction(String name, StatementBuilder buff) {
        buff.append(name).append('(');
        buff.append(on.getSQL());
        buff.append(')');
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitWindowFunction(this);
    }
}
