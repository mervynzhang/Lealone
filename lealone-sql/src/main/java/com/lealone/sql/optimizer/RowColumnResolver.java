/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.optimizer;

import com.lealone.db.row.Row;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.Session;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.IExpression;
import com.lealone.sql.query.Select;

/**
 * 用一行数据做列解析，DML的RETURNING表达式用它取值。
 */
public class RowColumnResolver extends ColumnResolverBase {

    private final Table table;
    private final TableFilter tableFilter;
    private Row row;

    public RowColumnResolver(ServerSession session, Table table, String alias) {
        this.table = table;
        // rightsChecked为true，DML语句本身已经检查过权限
        this.tableFilter = new TableFilter(session, table, alias, true, null);
    }

    public void setRow(Row row) {
        this.row = row;
    }

    @Override
    public String getTableAlias() {
        return tableFilter.getTableAlias();
    }

    @Override
    public String getSchemaName() {
        return table.getSchema().getName();
    }

    @Override
    public Column[] getColumns() {
        return table.getColumns();
    }

    @Override
    public Value getValue(Column column) {
        Value v = row == null ? null : row.getValue(column.getColumnId());
        return v == null ? ValueNull.INSTANCE : v;
    }

    @Override
    public TableFilter getTableFilter() {
        return tableFilter;
    }

    @Override
    public Select getSelect() {
        return null;
    }

    @Override
    public Value getExpressionValue(Session session, IExpression e, Object data) {
        row = (Row) data;
        return e.getValue(session);
    }
}
