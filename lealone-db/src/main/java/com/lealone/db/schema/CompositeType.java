/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import java.util.ArrayList;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.DbObjectType;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.table.Column;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueString;

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

    // 把结果集里文本形式的复合值(如"(1, x)")还原成ValueArray
    public Value convert(String value) {
        if (value == null)
            return ValueNull.INSTANCE;
        String s = value.trim();
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')')
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                    "can not convert " + value + " to composite type " + getName());
        String content = s.substring(1, s.length() - 1);
        String[] fields = splitCompositeFields(content);
        if (fields.length != columns.length)
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                    "composite type " + getName() + " has " + columns.length + " fields, but "
                            + fields.length + " values");
        Value[] list = new Value[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i].trim();
            if (field.isEmpty() || "NULL".equalsIgnoreCase(field))
                list[i] = ValueNull.INSTANCE;
            else
                list[i] = columns[i].convert(ValueString.get(field));
        }
        return ValueArray.get(list);
    }

    private static String[] splitCompositeFields(String content) {
        ArrayList<String> list = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '\\')
                    i++;
                else if (c == '\'')
                    quoted = false;
            } else if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                list.add(content.substring(start, i));
                start = i + 1;
            }
        }
        list.add(content.substring(start));
        return list.toArray(new String[list.size()]);
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
