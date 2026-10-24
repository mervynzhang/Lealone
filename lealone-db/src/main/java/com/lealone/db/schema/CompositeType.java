/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;

/**
 * CREATE TYPE name AS (a INT, b VARCHAR, ...)创建的自定义复合类型。
 * 复合值在值上下文里用ValueArray表示，元素顺序和columns一致。
 */
public class CompositeType extends SchemaObjectBase {

    private Column[] columns;

    public CompositeType(Schema schema, int id, String name) {
        super(schema, id, name);
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.COMPOSITE_TYPE;
    }

    public Column[] getColumns() {
        return columns;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    public int findColumn(String name) {
        for (int i = 0; i < columns.length; i++) {
            if (database.equalsIdentifiers(name, columns[i].getName()))
                return i;
        }
        return -1;
    }

    // 把ValueArray按列类型转换成复合值
    public Value convert(Value value) {
        if (value == null || value == ValueNull.INSTANCE)
            return ValueNull.INSTANCE;
        if (value.getType() != Value.ARRAY) {
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                    "can not convert " + value.getSQL() + " to composite type " + getName());
        }
        Value[] list = ((ValueArray) value).getList();
        if (list.length != columns.length) {
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                    "composite type " + getName() + " has " + columns.length + " fields, but "
                            + list.length + " values");
        }
        Value[] converted = new Value[list.length];
        for (int i = 0; i < list.length; i++) {
            Value v = list[i] == null ? ValueNull.INSTANCE : list[i];
            converted[i] = columns[i].convert(v);
        }
        return ValueArray.get(converted);
    }

    @Override
    public String getCreateSQL() {
        StringBuilder sql = new StringBuilder("CREATE TYPE ");
        sql.append(getSQL()).append(" AS (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(columns[i].getCreateSQL());
        }
        sql.append(')');
        return sql.toString();
    }

    @Override
    public String getDropSQL() {
        return "DROP TYPE IF EXISTS " + getSQL();
    }
}
