/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.query;

import java.util.ArrayList;

import com.lealone.agent.SystemOutline;
import com.lealone.agent.SystemOutlineNode;
import com.lealone.db.async.AsyncResultHandler;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.plugin.PluginManager;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.result.ResultTarget;
import com.lealone.db.session.SessionStatus;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.expression.WindowFunction;
import com.lealone.sql.operator.Operator;
import com.lealone.sql.operator.OperatorFactory;

public class YieldableSelect extends YieldableQueryBase implements WindowFunction.YieldController {

    private final Select select;
    private final ResultTarget target;
    private final int olapThreshold;
    private boolean olapDisabled;
    private Operator queryOperator;
    // 本地算子(OLAP 插件算子不一定是 QOperator)。窗口缓冲只在本地算子上，
    // 且窗口查询在构造函数里就禁用了 OLAP，所以这里的引用始终有效。
    private QOperator qOperator;
    // 扫描结束后窗口列的缓冲行和分步计算器（null 表示没有正在进行的窗口计算）
    private ArrayList<Value[]> windowRows;
    private WindowFunction.WindowComputation windowComputation;

    public YieldableSelect(Select select, int maxRows, boolean scrollable,
            AsyncResultHandler<Result> asyncHandler, ResultTarget target) {
        super(select, maxRows, scrollable, asyncHandler);
        this.select = select;
        this.target = target;
        this.olapThreshold = session.getOlapThreshold();
        if (select.hasWindowFunctions()) {
            // 窗口函数要在行缓冲上计算，不能中途切换到 olap 算子
            disableOlap();
        }
    }

    @Override
    public boolean yieldIfNeeded(int rowNumber) {
        if (!olapDisabled && olapThreshold > 0 && rowNumber > olapThreshold) {
            olapDisabled = true;
            boolean yield = super.yieldIfNeeded(rowNumber);
            Operator olapOperator = createOlapOperator();
            if (olapOperator != null) {
                queryOperator = olapOperator;
                yield = true; // olapOperator创建成功后让出执行权
                session.setStatus(SessionStatus.STATEMENT_YIELDED);
            }
            return yield;
        }
        return super.yieldIfNeeded(rowNumber);
    }

    // 一些像QDistinct这样的Operator无需从oltp转到olap，可以禁用olap
    public void disableOlap() {
        olapDisabled = true;
    }

    private Operator createOlapOperator() {
        Operator olapOperator = null;
        String olapOperatorFactoryName = session.getOlapOperatorFactoryName();
        if (olapOperatorFactoryName == null) {
            olapOperatorFactoryName = "olap";
        }
        OperatorFactory operatorFactory = PluginManager.getPlugin(OperatorFactory.class,
                olapOperatorFactoryName);
        if (operatorFactory != null) {
            olapOperator = operatorFactory.createOperator(select, queryOperator.getLocalResult());
            olapOperator.start();
            olapOperator.copyStatus(queryOperator);
        }
        return olapOperator;
    }

    @Override
    protected void startInternal() {
        SystemOutline.createNode(SystemOutlineNode.Select_start);
        // select.getTopTableFilter().lock(session, select.isForUpdate);
        select.fireBeforeSelectTriggers();
        queryOperator = qOperator = createQueryOperator();
        queryOperator.start();
    }

    @Override
    protected void stopInternal() {
        // 执行startInternal抛异常时queryOperator可能为null
        if (queryOperator != null) {
            // 异常/取消时不要再把窗口缓冲算完再抛（O(n log n) 白白占用 scheduler 线程）
            if (pendingException != null && qOperator != null) {
                qOperator.abortWindow();
            }
            queryOperator.stop();
        }
        windowComputation = null;
        windowRows = null;
    }

    @Override
    protected void executeInternal() {
        SystemOutline.createNode(SystemOutlineNode.Select_execute);
        while (true) {
            session.setStatus(SessionStatus.STATEMENT_RUNNING);
            if (windowComputation != null) {
                // 窗口计算的可恢复步骤：step() 内部每处理一行都会 yieldIfNeeded，
                // 这里只负责在让出/计算完成之间做状态切换
                if (windowComputation.step()) {
                    windowComputation = null;
                    // 窗口列已经写回缓冲行，交给真正的结果目标（LocalResult）。
                    // 必须在 stop()/localResult.done() 之前完成
                    qOperator.finishWindowRows(windowRows);
                    windowRows = null;
                    completeQuery();
                    break;
                }
                if (session.getStatus() == SessionStatus.STATEMENT_YIELDED
                        || session.getStatus() == SessionStatus.WAITING) {
                    return;
                }
                continue;
            }
            try {
                queryOperator.run();
            } catch (RuntimeException e) {
                if (DbObjectLock.LOCKED_EXCEPTION == e) {
                    queryOperator.onLockedException();
                } else {
                    throw e;
                }
            }
            if (queryOperator.isStopped()) {
                // 有窗口函数时先取出缓冲行，分步计算窗口列后再结束查询；
                // 不能在这里直接标记完成，也不能把计算放到 stop() 里做
                if (qOperator.hasWindowBuffer()) {
                    windowRows = qOperator.takeWindowRows();
                    windowComputation = select.createWindowComputation(windowRows, this);
                    continue;
                }
                completeQuery();
                break;
            }
            if (session.getStatus() == SessionStatus.STATEMENT_YIELDED
                    || session.getStatus() == SessionStatus.WAITING) {
                return;
            }
        }
    }

    private void completeQuery() {
        // 查询结果已经增加到target了
        if (target != null) {
            session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        } else if (queryOperator.getLocalResult() != null) {
            LocalResult r = queryOperator.getLocalResult();
            setResult(r, r.getRowCount());
            select.resultCache.setResult(r);
            session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        }
    }


    private QOperator createQueryOperator() {
        LocalResult result;
        ResultTarget to;
        QOperator queryOperator;
        int limitRows = getLimitRows(maxRows);
        LocalResult cachedResult = select.resultCache.getResult(maxRows); // 不直接用limitRows
        boolean fromCache = cachedResult != null;
        if (cachedResult != null) {
            result = cachedResult;
            to = cachedResult;
            queryOperator = new QCache(select, cachedResult);
        } else {
            result = createLocalResultIfNeeded(limitRows);
            to = result != null ? result : target;
            if (limitRows != 0) {
                if (select.isQuickAggregateQuery) {
                    queryOperator = new QAggregateQuick(select);
                } else if (select.isGroupQuery) {
                    if (select.isGroupSortedQuery) {
                        queryOperator = new QGroupSorted(select);
                    } else {
                        if (select.groupIndex == null) { // 忽视select.havingIndex
                            queryOperator = new QAggregate(select);
                        } else {
                            queryOperator = new QGroup(select);
                        }
                    }
                } else if (select.isDistinctQuery) {
                    queryOperator = new QDistinct(select);
                } else {
                    queryOperator = new QFlat(select);
                }
            } else {
                queryOperator = new QEmpty(select);
            }
        }
        queryOperator.columnCount = select.expressions.size();
        queryOperator.maxRows = limitRows;
        queryOperator.target = target;
        if (select.hasWindowFunctions() && !fromCache) {
            // 缓存里的行已经算过窗口列了，只有真正要扫描时才需要缓冲
            queryOperator.enableWindowBuffering(to);
        } else {
            queryOperator.result = to;
        }
        queryOperator.localResult = result;
        queryOperator.yieldableSelect = this;
        return queryOperator;
    }

    private int getLimitRows(int maxRows) {
        // 按JDBC规范的要求，当调用java.sql.Statement.setMaxRows时，
        // 如果maxRows是0，表示不限制行数，相当于没有调用过setMaxRows一样，
        // 如果小余0，已经在客户端抛了无效参数异常，所以这里统一处理: 当limitRows小于0时表示不限制行数。
        int limitRows = maxRows == 0 ? -1 : maxRows;
        if (select.limitExpr != null) {
            // 如果在select语句中又指定了limit子句，那么用它覆盖maxRows
            // 注意limit 0表示不取任何记录，跟maxRows为0时刚好相反
            Value v = select.limitExpr.getValue(session);
            int l = v == ValueNull.INSTANCE ? -1 : v.getInt();
            if (limitRows < 0) {
                limitRows = l;
            } else if (l >= 0) {
                limitRows = Math.min(l, limitRows);
            }
        }
        return limitRows;
    }

    private LocalResult createLocalResultIfNeeded(int limitRows) {
        LocalResult result = null;
        if (target == null //
                || !session.getDatabase().getSettings().optimizeInsertFromSelect //
                || target != null && !target.optimizeInsertFromSelect()) {
            result = createLocalResult(result);
        }
        if (select.sort != null && (!select.sortUsingIndex || select.distinct)) {
            result = createLocalResult(result);
            result.setSortOrder(select.sort);
        }
        if (select.distinct && !select.isDistinctQuery) {
            result = createLocalResult(result);
            result.setDistinct();
        }
        if (select.isGroupQuery && !select.isGroupSortedQuery) {
            result = createLocalResult(result);
        }
        if (limitRows >= 0 || select.offsetExpr != null) {
            result = createLocalResult(result);
        }
        if (select.hasWindowFunctions()) {
            // 窗口函数必须先拿到全部行，强制物化
            result = createLocalResult(result);
        }
        return result;
    }

    private LocalResult createLocalResult(LocalResult old) {
        return old != null ? old
                : new LocalResult(session, select.expressionArray, select.visibleColumnCount,
                        select.rawExpressionInfoList);
    }
}
