/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import com.lealone.db.value.Value;
import com.lealone.db.value.ValueNull;

/**
 * PL/pgSQL里的一个局部变量。
 * 变量值用Lealone的Value表示，声明时解析出的类型用于赋值时做类型转换。
 */
public class PlpgSqlVariable {

    private final String name;
    private final boolean nameQuoted;
    private final boolean constant;
    private final boolean notNull;
    // Value.UNKNOWN表示类型还没解析出来
    private int type = Value.UNKNOWN;
    private Value value = ValueNull.INSTANCE;

    public PlpgSqlVariable(String name, boolean nameQuoted, boolean constant, boolean notNull) {
        this.name = name;
        this.nameQuoted = nameQuoted;
        this.constant = constant;
        this.notNull = notNull;
    }

    public String getName() {
        return name;
    }

    public boolean isNameQuoted() {
        return nameQuoted;
    }

    public boolean isConstant() {
        return constant;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public Value getValue() {
        return value;
    }

    // 赋值时先按变量声明的类型转换，再检查CONSTANT和NOT NULL约束
    public void setValue(Value value) {
        if (value == null)
            value = ValueNull.INSTANCE;
        if (type != Value.UNKNOWN)
            value = value.convertTo(type);
        if (notNull && value == ValueNull.INSTANCE)
            throw PlpgSqlException.notNullViolation(name);
        this.value = value;
    }

    public void setInitialValue(Value value) {
        if (value == null)
            value = ValueNull.INSTANCE;
        if (type != Value.UNKNOWN)
            value = value.convertTo(type);
        this.value = value;
    }

    public void checkCanAssign() {
        if (constant)
            throw PlpgSqlException.raise("22005", "assignment to constant variable " + name);
    }
}
