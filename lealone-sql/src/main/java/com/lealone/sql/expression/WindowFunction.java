/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.expression;

import java.util.ArrayList;

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
 * 在解析阶段就作为隐藏列追加到 select 列表末尾（相同 SQL 的隐藏列会去重），所以每一行物化时这些值
 * 已经算好（见 {@link #prepareColumns(ArrayList, Database, ArrayList)}）。物化完成后按
 * (分区键, 排序键) 对行号做一次稳定排序，再逐个分区写回窗口列。排序和分区扫描都是
 * {@link WindowComputation#step() step()} 驱动的可恢复步骤，每处理一行就调用
 * {@link YieldController#yieldIfNeeded(int)}，不会在 scheduler 线程里一次跑完整个大结果集。
 * <p>
 * 内存上界：输入行先缓存在 {@code WindowResultTarget} 里，最多
 * {@code MAX_MEMORY_ROWS} 行（默认 {@code Constants.DEFAULT_MAX_MEMORY_ROWS} = 100000），
 * 超过上限会抛资源不足错误而不是无界增长。计算阶段同一个时刻只算一个窗口函数，额外内存是
 * 一个 {@code int[n]} 排序索引加一个 {@code int[n]} 归并缓冲（约 8 字节/行），算完立即释放；
 * 聚合窗口的中间状态是 O(1)。{@code LIMIT} 不能减少窗口需要读取的行数（窗口语义要求看到
 * 整个分区），所以 {@code SELECT ... OVER (...) LIMIT 1} 仍然最多占用上述缓冲。
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
     * <p>
     * 隐藏列按 SQL 去重（仿 {@code Query.initOrder}）：多个窗口函数共用同一个排序键/分区键，
     * 或者窗口键本身就是可见列时，不再重复追加，避免同一表达式在每行里存两份。
     *
     * @param expressions select 的表达式列表，隐藏列会追加到末尾
     * @param db 用于标识符大小写不敏感比较
     * @param expressionSQL 已有的表达式 SQL，用来查找可以复用的列（找到后会把新列 SQL 也加进去）
     */
    public void prepareColumns(ArrayList<Expression> expressions, Database db,
            ArrayList<String> expressionSQL) {
        if (on != null) {
            argColumnIndex = addHiddenColumn(expressions, on, db, expressionSQL);
        }
        if (partitionBy != null && !partitionBy.isEmpty()) {
            int size = partitionBy.size();
            partitionColumnIndexes = new int[size];
            for (int i = 0; i < size; i++) {
                partitionColumnIndexes[i] = addHiddenColumn(expressions, partitionBy.get(i), db,
                        expressionSQL);
            }
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            int size = orderBy.size();
            orderColumnIndexes = new int[size];
            orderSortTypes = new int[size];
            for (int i = 0; i < size; i++) {
                SelectOrderBy o = orderBy.get(i);
                orderColumnIndexes[i] = addHiddenColumn(expressions, o.expression, db, expressionSQL);
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

    private static int addHiddenColumn(ArrayList<Expression> expressions, Expression e, Database db,
            ArrayList<String> expressionSQL) {
        String sql = e.getSQL();
        for (int i = 0, size = expressionSQL.size(); i < size; i++) {
            if (db.equalsIdentifiers(expressionSQL.get(i), sql)) {
                // 已经有同 SQL 的可见列/隐藏列，直接复用，保证多个窗口共用同一列
                return i;
            }
        }
        int index = expressions.size();
        expressions.add(e);
        expressionSQL.add(sql);
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
     * 由执行器提供的让出钩子。窗口计算按 {@link WindowComputation#step()} 分步推进，
     * 每处理一行/一个排序元素就调用一次 {@code yieldIfNeeded}，让 scheduler 有机会
     * 切到其它 session，同时触发周期性的 {@code checkCanceled()}。
     */
    public interface YieldController {
        /**
         * @param rowNumber 已处理的行/元素计数（用于 scheduler 的周期性检查）
         * @return true 表示调用方应立刻返回，下一次再继续 step()
         */
        boolean yieldIfNeeded(int rowNumber);
    }

    /**
     * 所有窗口函数的分步计算器：一个窗口函数算完再算下一个，每次 step() 有固定预算。
     * <p>
     * 内存：输入行由调用方（WindowResultTarget）限制在 MAX_MEMORY_ROWS 以内；
     * 这里同一时刻只为一个窗口函数分配 {@code int[n]} 排序索引和 {@code int[n]} 归并缓冲。
     */
    public static final class WindowComputation {

        private final ArrayList<WindowFunction> functions;
        private final ArrayList<Value[]> rows;
        private final ServerSession session;
        private final YieldController yieldController;
        private int functionIndex;
        private Computation current;

        public WindowComputation(ArrayList<WindowFunction> functions, ArrayList<Value[]> rows,
                ServerSession session, YieldController yieldController) {
            this.functions = functions;
            this.rows = rows;
            this.session = session;
            this.yieldController = yieldController;
        }

        /**
         * 执行一小批工作。
         *
         * @return true 表示所有窗口函数都已经算完；false 表示还需要继续调用 step()
         *         （预算用完，或者 scheduler 要求让出——后者可以通过会话状态区分）
         */
        public boolean step() {
            while (functionIndex < functions.size()) {
                if (current == null) {
                    current = functions.get(functionIndex).new Computation(rows, session,
                            yieldController);
                }
                if (!current.step()) {
                    return false;
                }
                current = null;
                functionIndex++;
            }
            return true;
        }

        public boolean isDone() {
            return functionIndex >= functions.size();
        }
    }

    /**
     * 单个窗口函数的可恢复计算过程。
     * <p>
     * 排序用 int[] 下标做自底向上的稳定归并排序（ORDER BY 相等的行保持扫描顺序），
     * 不产生 Integer 装箱对象；归并和分区扫描都可以在中途停下来，下一次 step() 从
     * 保存的状态继续。这样大结果集的排序/聚合不会整块跑在 stop() 里。
     */
    private final class Computation {

        // 一次 step() 最多处理的行/元素数。即使没有 scheduler，也会定期回到
        // executeInternal 检查会话状态，单个 step 不会无界执行。
        private static final int STEP_BUDGET = 1024;

        private final ArrayList<Value[]> rows;
        private final ServerSession session;
        private final YieldController yieldController;
        private final int rowCount;
        private final boolean aggregate;

        // 排序索引：int[] 而不是 Integer[]（每个窗口函数 4 字节/行，且没有装箱对象）
        private int[] order;
        private int[] temp;

        private int progress;
        private boolean sorting;
        private int sortWidth;
        private int sortLo;
        private int mergeMid;
        private int mergeHi;
        private int mergeI;
        private int mergeJ;
        private int mergeK;
        private int copyPos;
        private boolean merging;

        private boolean done;
        private int computePos;
        private int partStart;
        private int rank;
        private int denseRank;

        // 聚合窗口的两阶段状态：先扫完整个分区并累加，再把结果写回分区内的每一行
        private boolean aggWriting;
        private int aggScanPos;
        private int aggWritePos;
        private int partEnd = -1;
        private long aggCount;
        private Value aggValue;
        private Value aggResult;

        Computation(ArrayList<Value[]> rows, ServerSession session,
                YieldController yieldController) {
            this.rows = rows;
            this.session = session;
            this.yieldController = yieldController;
            this.rowCount = rows.size();
            this.aggregate = isAggregate();
            if (rowCount == 0) {
                done = true;
                return;
            }
            order = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                order[i] = i;
            }
            if (sortOrder != null && rowCount > 1) {
                temp = new int[rowCount];
                sorting = true;
                sortWidth = 1;
                sortLo = 0;
                startSortRun();
            }
        }

        private void startSortRun() {
            mergeMid = Math.min(sortLo + sortWidth, rowCount);
            mergeHi = Math.min(sortLo + 2 * sortWidth, rowCount);
            merging = mergeMid < mergeHi;
            mergeI = sortLo;
            mergeJ = mergeMid;
            mergeK = sortLo;
            copyPos = sortLo;
        }

        boolean step() {
            if (done) {
                return true;
            }
            // 即使当前没有 scheduler/不需要让出，也要响应 Statement.cancel()
            session.checkCanceled();
            int budget = STEP_BUDGET;
            while (sorting && budget > 0) {
                budget--;
                if (stepSort()) {
                    return false;
                }
            }
            if (sorting) {
                return false;
            }
            while (!done && budget > 0) {
                budget--;
                if (stepCompute()) {
                    return false;
                }
            }
            return done;
        }

        private boolean yieldNow() {
            return yieldController.yieldIfNeeded(++progress);
        }

        /** 处理一次归并写入，返回 true 表示需要让出 */
        private boolean stepSort() {
            int[] src = order;
            int[] dst = temp;
            if (merging) {
                int i = mergeI;
                int j = mergeJ;
                if (i < mergeMid && (j >= mergeHi
                        || sortOrder.compare(rows.get(src[i]), rows.get(src[j])) <= 0)) {
                    dst[mergeK++] = src[i++];
                    mergeI = i;
                } else {
                    dst[mergeK++] = src[j++];
                    mergeJ = j;
                }
                if (mergeK == mergeHi) {
                    finishSortRun();
                }
            } else {
                dst[copyPos] = src[copyPos];
                copyPos++;
                if (copyPos == mergeHi) {
                    finishSortRun();
                }
            }
            return yieldNow();
        }

        private void finishSortRun() {
            sortLo = mergeHi;
            if (sortLo >= rowCount) {
                // 这一趟归并完成，交换源/目标数组
                int[] t = order;
                order = temp;
                temp = t;
                if (sortWidth > rowCount / 2) {
                    sortWidth = rowCount;
                } else {
                    sortWidth <<= 1;
                }
                sortLo = 0;
                if (sortWidth >= rowCount) {
                    sorting = false;
                    temp = null; // 排序完成，立即释放归并缓冲
                } else {
                    startSortRun();
                }
            } else {
                startSortRun();
            }
        }

        /** 处理一行窗口值计算，返回 true 表示需要让出 */
        private boolean stepCompute() {
            if (computePos >= rowCount) {
                done = true;
                return false;
            }
            if (aggregate) {
                return stepAggregate();
            }
            int i = computePos;
            Value[] row = rows.get(order[i]);
            if (i == partStart) {
                rank = 1;
                denseRank = 1;
            } else if (partitionSortOrder != null
                    && partitionSortOrder.compare(rows.get(order[i - 1]), row) != 0) {
                // 分区边界：排序时分区键在最前面，所以分区一定是连续的
                partStart = i;
                rank = 1;
                denseRank = 1;
            } else if ((type == RANK || type == DENSE_RANK)
                    && !isPeer(rows.get(order[i - 1]), row)) {
                rank = i - partStart + 1;
                denseRank++;
            }
            int value;
            switch (type) {
            case ROW_NUMBER:
                value = i - partStart + 1;
                break;
            case RANK:
                value = rank;
                break;
            default: // DENSE_RANK
                value = denseRank;
                break;
            }
            setWindowValue(row, ValueInt.get(value));
            computePos++;
            if (computePos >= rowCount) {
                done = true;
            }
            return yieldNow();
        }

        private boolean stepAggregate() {
            if (!aggWriting) {
                if (aggScanPos == partStart) {
                    initAggregate();
                } else if (partitionSortOrder != null
                        && partitionSortOrder.compare(rows.get(order[aggScanPos - 1]),
                                rows.get(order[aggScanPos])) != 0) {
                    // 当前分区已经扫完，下一行属于下一个分区；转到写回阶段
                    partEnd = aggScanPos;
                    aggResult = finishAggregate();
                    aggWriting = true;
                    aggWritePos = partStart;
                    return false;
                }
                accumulate(rows.get(order[aggScanPos]));
                aggScanPos++;
                if (aggScanPos >= rowCount) {
                    partEnd = rowCount;
                    aggResult = finishAggregate();
                    aggWriting = true;
                    aggWritePos = partStart;
                }
                return yieldNow();
            }
            // 把聚合结果写到分区内的每一行
            setWindowValue(rows.get(order[aggWritePos]), aggResult);
            aggWritePos++;
            if (aggWritePos >= partEnd) {
                partStart = partEnd;
                computePos = partEnd;
                aggScanPos = partEnd;
                aggWriting = false;
                partEnd = -1;
                aggResult = null;
                if (partStart >= rowCount) {
                    done = true;
                }
            }
            return yieldNow();
        }

        private void initAggregate() {
            aggCount = 0;
            aggValue = null;
        }

        private void accumulate(Value[] row) {
            Value v = null;
            if (type != COUNT_ALL) {
                v = row[argColumnIndex];
                if (v == ValueNull.INSTANCE) {
                    return;
                }
            }
            aggCount++;
            switch (type) {
            case SUM:
                if (aggValue == null) {
                    aggValue = v.convertTo(dataType);
                } else {
                    aggValue = aggValue.add(v.convertTo(aggValue.getType()));
                }
                break;
            case AVG:
                if (aggValue == null) {
                    aggValue = v.convertTo(DataType.getAddProofType(dataType));
                } else {
                    aggValue = aggValue.add(v.convertTo(aggValue.getType()));
                }
                break;
            case MIN:
                if (aggValue == null || session.getDatabase().compare(v, aggValue) < 0) {
                    aggValue = v;
                }
                break;
            case MAX:
                if (aggValue == null || session.getDatabase().compare(v, aggValue) > 0) {
                    aggValue = v;
                }
                break;
            default: // COUNT / COUNT_ALL
                break;
            }
        }

        private Value finishAggregate() {
            if (type == COUNT || type == COUNT_ALL) {
                return ValueLong.get(aggCount);
            }
            if (aggValue == null) {
                return ValueNull.INSTANCE;
            }
            if (type == AVG) {
                return divide(aggValue, aggCount).convertTo(dataType);
            }
            return aggValue.convertTo(dataType);
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

    private static Value divide(Value a, long by) {
        if (by == 0) {
            return ValueNull.INSTANCE;
        }
        int type = Value.getHigherOrder(a.getType(), Value.LONG);
        Value b = ValueLong.get(by).convertTo(type);
        return a.convertTo(type).divide(b);
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
        // 聚合参数可能是一个还没被 optimize 的表达式(例如 MIN(v + 1))。
        // 隐藏列虽然也会被 optimize，但 visible 列的 WindowFunction 先于隐藏列优化，
        // 这里必须先 optimize 参数再取类型，否则 getType() 可能返回 UNKNOWN：
        // MIN/MAX 会把结果转成 NULL，AVG 会选错结果类型。
        Expression arg = type >= SUM ? on.optimize(session) : null;
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
            dataType = arg.getType();
            scale = arg.getScale();
            precision = arg.getPrecision();
            displaySize = arg.getDisplaySize();
            break;
        case SUM: {
            dataType = arg.getType();
            scale = arg.getScale();
            precision = arg.getPrecision();
            displaySize = arg.getDisplaySize();
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
            dataType = arg.getType();
            scale = arg.getScale();
            precision = arg.getPrecision();
            displaySize = arg.getDisplaySize();
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
