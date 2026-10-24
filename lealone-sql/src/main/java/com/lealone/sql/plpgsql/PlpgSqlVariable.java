/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
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
    // record/%ROWTYPE变量用字段名、字段类型和字段值表示一行
    private boolean record;
    private String[] recordFieldNames;
    private int[] recordFieldTypes;
    private Value[] recordFieldValues;
    // SELECT INTO没有行时record变成未赋值状态，访问字段会报错
    private boolean recordAssigned;
    // RETURNS TABLE的结果列变量，RETURN QUERY里同名标识符优先按查询的列解析
    private boolean tableColumn;

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
        if (record)
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record variable " + name + " can only be used as record.field");
        return value;
    }

    public boolean isRecord() {
        return record;
    }

    public boolean isTableColumn() {
        return tableColumn;
    }

    public void setTableColumn(boolean tableColumn) {
        this.tableColumn = tableColumn;
    }

    public void setRecord(boolean record) {
        this.record = record;
    }

    public void declareRecordFields(String[] fieldNames, int[] fieldTypes) {
        this.record = true;
        this.recordFieldNames = fieldNames.clone();
        this.recordFieldTypes = fieldTypes.clone();
        this.recordFieldValues = new Value[fieldNames.length];
        for (int i = 0; i < this.recordFieldValues.length; i++)
            this.recordFieldValues[i] = ValueNull.INSTANCE;
        this.recordAssigned = false;
    }

    // SELECT INTO一整行到record变量
    public void assignRecord(String[] fieldNames, int[] fieldTypes, Value[] values) {
        if (this.recordFieldNames == null)
            declareRecordFields(fieldNames, fieldTypes);
        if (values.length != this.recordFieldNames.length)
            throw PlpgSqlException.raise("42804",
                    "SELECT INTO record " + name + " has " + values.length + " columns but "
                            + this.recordFieldNames.length + " fields");
        for (int i = 0; i < values.length; i++) {
            Value v = values[i] == null ? ValueNull.INSTANCE : values[i];
            if (this.recordFieldTypes[i] != Value.UNKNOWN)
                v = v.convertTo(this.recordFieldTypes[i]);
            this.recordFieldValues[i] = v;
        }
        this.recordAssigned = true;
    }

    // 查询没有行时把record标记成未赋值，字段定义保持不变
    public void invalidateRecord(String[] fieldNames, int[] fieldTypes) {
        if (this.recordFieldNames == null && fieldNames != null)
            declareRecordFields(fieldNames, fieldTypes);
        this.recordAssigned = false;
    }

    public Value getRecordField(String fieldName) {
        int index = findRecordField(fieldName);
        return recordFieldValues[index];
    }

    public void setRecordField(String fieldName, Value value) {
        int index = findRecordField(fieldName);
        if (value == null)
            value = ValueNull.INSTANCE;
        if (recordFieldTypes[index] != Value.UNKNOWN)
            value = value.convertTo(recordFieldTypes[index]);
        recordFieldValues[index] = value;
    }

    private int findRecordField(String fieldName) {
        if (!recordAssigned)
            throw PlpgSqlException.raise("55000",
                    "record \"" + name + "\" is not assigned yet");
        for (int i = 0; i < recordFieldNames.length; i++) {
            if (recordFieldNames[i].equalsIgnoreCase(fieldName))
                return i;
        }
        throw PlpgSqlException.raise("42703",
                "record \"" + name + "\" has no field \"" + fieldName + "\"");
    }

    // 赋值时先按变量声明的类型转换，再检查CONSTANT和NOT NULL约束
    public void setValue(Value value) {
        if (value == null)
            value = ValueNull.INSTANCE;
        if (record) {
            if (value == ValueNull.INSTANCE) {
                recordAssigned = false;
                return;
            }
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record variable " + name + " can only be assigned by SELECT * INTO");
        }
        if (type != Value.UNKNOWN)
            value = value.convertTo(type);
        if (notNull && value == ValueNull.INSTANCE)
            throw PlpgSqlException.notNullViolation(name);
        this.value = value;
    }

    public void setInitialValue(Value value) {
        if (value == null)
            value = ValueNull.INSTANCE;
        if (record) {
            recordAssigned = false;
            return;
        }
        if (type != Value.UNKNOWN)
            value = value.convertTo(type);
        if (notNull && value == ValueNull.INSTANCE)
            throw PlpgSqlException.notNullViolation(name);
        this.value = value;
    }

    public void checkCanAssign() {
        if (constant)
            throw PlpgSqlException.raise("22005", "assignment to constant variable " + name);
    }
}
