/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.dml;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.lealone.db.Database;
import com.lealone.db.async.Future;
import com.lealone.db.result.LocalResult;
import com.lealone.db.result.Result;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueString;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.StatementBase;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.optimizer.TableFilter;
import com.lealone.sql.query.Select;

/**
 * This class represents the statement
 * EXPLAIN
 */
public class Explain extends ManipulationStatement {

    private StatementBase command;
    private LocalResult result;
    private boolean executeCommand;
    private boolean costs = true;
    private boolean verbose;

    public Explain(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.EXPLAIN;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    public void setCommand(StatementBase command) {
        this.command = command;
    }

    public void setExecuteCommand(boolean executeCommand) {
        this.executeCommand = executeCommand;
    }

    // EXPLAIN (COSTS OFF): 不输出代价和统计信息
    public void setCosts(boolean costs) {
        this.costs = costs;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public Future<Result> getMetaData() {
        Result r = query(-1);
        return Future.succeededFuture(r);
    }

    @Override
    public PreparedSQLStatement prepare() {
        command.prepare();
        return this;
    }

    @Override
    public Result query(int maxRows) {
        Column column = new Column("PLAN", Value.STRING);
        Database db = session.getDatabase();
        ExpressionColumn expr = new ExpressionColumn(db, column);
        Expression[] expressions = { expr };
        result = new LocalResult(session, expressions, 1);
        if (maxRows >= 0) {
            String plan;
            if (executeCommand) {
                db.statisticsStart();
                if (command.isQuery()) {
                    command.query(maxRows);
                } else {
                    command.update();
                }
                plan = command.getPlanSQL();
                plan = formatPlan(plan);
                Map<String, Integer> statistics = db.statisticsEnd();
                // COSTS OFF时不输出统计信息
                if (statistics != null && costs) {
                    int total = 0;
                    for (Entry<String, Integer> e : statistics.entrySet()) {
                        total += e.getValue();
                    }
                    if (total > 0) {
                        statistics = new TreeMap<String, Integer>(statistics);
                        StringBuilder buff = new StringBuilder();
                        if (statistics.size() > 1) {
                            buff.append("total: ").append(total).append('\n');
                        }
                        for (Entry<String, Integer> e : statistics.entrySet()) {
                            int value = e.getValue();
                            int percent = (int) (100L * value / total);
                            buff.append(e.getKey()).append(": ").append(value);
                            if (statistics.size() > 1) {
                                buff.append(" (").append(percent).append("%)");
                            }
                            buff.append('\n');
                        }
                        plan += "\n/*\n" + buff.toString() + "*/";
                    }
                }
            } else {
                plan = formatPlan(command.getPlanSQL());
            }
            add(plan);
        }
        result.done();
        return result;
    }

    // 按EXPLAIN的选项加工执行计划: COSTS OFF去掉代价/统计信息，VERBOSE追加每张表的细节
    private String formatPlan(String plan) {
        if (!costs) {
            plan = removeCostComments(plan);
        }
        if (verbose) {
            plan += "\n" + getVerboseInfo();
        }
        // 去掉行尾多余的空行
        int end = plan.length();
        while (end > 0 && Character.isWhitespace(plan.charAt(end - 1))) {
            end--;
        }
        return plan.substring(0, end);
    }

    // 去掉 /* scanCount: N */ 这类代价注释
    private static String removeCostComments(String plan) {
        StringBuilder buff = new StringBuilder(plan.length());
        int index = 0;
        while (true) {
            int start = plan.indexOf("/* scanCount:", index);
            if (start < 0) {
                buff.append(plan, index, plan.length());
                break;
            }
            int end = plan.indexOf("*/", start);
            if (end < 0) {
                buff.append(plan, index, plan.length());
                break;
            }
            // 连同前面的缩进和后面的换行一起去掉
            int lineStart = start;
            while (lineStart > index && plan.charAt(lineStart - 1) == ' ') {
                lineStart--;
            }
            buff.append(plan, index, lineStart);
            index = end + 2;
            if (index < plan.length() && plan.charAt(index) == '\n') {
                index++;
            }
        }
        // 去掉行尾多余的空行
        return buff.toString().replaceAll("\\n\\s*\\n", "\n");
    }

    // VERBOSE: 每张表的扫描方式/行数/过滤条件
    private String getVerboseInfo() {
        if (!(command instanceof Select)) {
            return "/* VERBOSE: " + command.getSQL() + " */";
        }
        StringBuilder buff = new StringBuilder();
        buff.append("/* VERBOSE\n");
        ArrayList<TableFilter> filters = ((Select) command).getFilters();
        for (int i = 0; i < filters.size(); i++) {
            TableFilter filter = filters.get(i);
            buff.append("    table: ").append(filter.getTable().getName());
            if (!filter.getTableAlias().equals(filter.getTable().getName())) {
                buff.append(" alias: ").append(filter.getTableAlias());
            }
            if (filter.getIndex() != null) {
                buff.append(" index: ").append(filter.getIndex().getName());
            }
            buff.append(" rows: ").append(filter.getTable().getRowCountApproximation());
            buff.append('\n');
        }
        return buff.append("*/").toString();
    }

    private void add(String text) {
        Value[] row = { ValueString.get(text) };
        result.addRow(row);
    }
}
