/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.HashMap;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.schema.CompositeType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
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
    // tab%ROWTYPE声明的行类型，字段结构固定；plain record每次赋值可以换行结构
    private boolean rowTypeDeclared;
    // tab%ROWTYPE[]数组：每个下标对应一行，行值同样用ValueArray表示
    private boolean recordArray;
    private HashMap<Integer, Value[]> recordArrayValues;
    // 复合类型参数，字段访问用CompositeType的列元数据
    private CompositeType compositeType;
    // DECLARE c CURSOR声明的游标，状态放在cursorState里
    private boolean cursor;
    private PlpgSqlCursor cursorState;
    // refcursor变量，值就是门户名
    private boolean refcursor;

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

    public boolean isCursor() {
        return cursor;
    }

    public void setCursor(boolean cursor) {
        this.cursor = cursor;
    }

    public PlpgSqlCursor getCursorState() {
        return cursorState;
    }

    public void setCursorState(PlpgSqlCursor cursorState) {
        this.cursorState = cursorState;
    }

    public boolean isRefcursor() {
        return refcursor;
    }

    public void setRefcursor(boolean refcursor) {
        this.refcursor = refcursor;
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
        if (recordArray)
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record array variable " + name + " must be used as arr[index]");
        if (record)
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record variable " + name + " can only be used as record.field");
        return value;
    }

    public boolean isRecord() {
        return record;
    }

    public boolean isRecordArray() {
        return recordArray;
    }

    public boolean isComposite() {
        return compositeType != null;
    }

    public CompositeType getCompositeType() {
        return compositeType;
    }

    public void setCompositeType(CompositeType compositeType) {
        this.compositeType = compositeType;
    }

    public void setRecordArray(boolean recordArray) {
        this.recordArray = recordArray;
    }

    public void declareRecordArrayFields(String[] fieldNames, int[] fieldTypes) {
        this.recordArray = true;
        this.recordFieldNames = fieldNames.clone();
        this.recordFieldTypes = fieldTypes.clone();
        this.recordArrayValues = new HashMap<>();
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

    public boolean isRowTypeDeclared() {
        return rowTypeDeclared;
    }

    public void setRowTypeDeclared(boolean rowTypeDeclared) {
        this.rowTypeDeclared = rowTypeDeclared;
    }

    public void declareRecordFields(String[] fieldNames, int[] fieldTypes) {
        this.record = true;
        this.recordFieldNames = fieldNames.clone();
        this.recordFieldTypes = fieldTypes.clone();
        this.recordFieldValues = new Value[fieldNames.length];
        resetRecordValues();
        this.recordAssigned = false;
    }

    // %ROWTYPE声明后字段就是全NULL的一行
    public void markRecordAssigned() {
        if (recordFieldValues == null)
            return;
        resetRecordValues();
        this.recordAssigned = true;
    }

    private void resetRecordValues() {
        for (int i = 0; i < recordFieldValues.length; i++)
            recordFieldValues[i] = ValueNull.INSTANCE;
    }

    // SELECT INTO一整行到record变量
    public void assignRecord(String[] fieldNames, int[] fieldTypes, Value[] values) {
        if (!rowTypeDeclared) {
            // plain record每次赋值都按查询结果重新绑定行结构
            this.record = true;
            this.recordFieldNames = fieldNames.clone();
            this.recordFieldTypes = fieldTypes.clone();
            this.recordFieldValues = new Value[fieldNames.length];
            resetRecordValues();
        }
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

    // 查询没有行时把record的字段都置NULL；%ROWTYPE和已知查询结构都仍是一行NULL
    public void invalidateRecord(String[] fieldNames, int[] fieldTypes) {
        if (!rowTypeDeclared && fieldNames != null) {
            this.record = true;
            this.recordFieldNames = fieldNames.clone();
            this.recordFieldTypes = fieldTypes.clone();
            this.recordFieldValues = new Value[fieldNames.length];
        }
        if (recordFieldValues != null) {
            resetRecordValues();
            this.recordAssigned = true;
        } else {
            this.recordAssigned = false;
        }
    }

    // 读复合类型变量的一个字段
    public Value getCompositeField(String fieldName) {
        int index = compositeType.findColumn(fieldName);
        if (index < 0)
            throw PlpgSqlException.raise("42703",
                    "composite variable \"" + name + "\" has no field \"" + fieldName + "\"");
        Value v = value;
        if (v == null || v == ValueNull.INSTANCE)
            return ValueNull.INSTANCE;
        return ((ValueArray) v).getList()[index];
    }

    // 读数组元素；未赋值时返回一行全NULL，和PostgreSQL的未初始化行数组一致
    public Value getRecordArrayElement(int index) {
        checkArrayIndex(index);
        Value[] row = recordArrayValues.get(index);
        if (row == null) {
            row = new Value[recordFieldNames.length];
            for (int i = 0; i < row.length; i++)
                row[i] = ValueNull.INSTANCE;
        }
        return ValueArray.get(row);
    }

    public Value getRecordArrayField(int index, String fieldName) {
        checkArrayIndex(index);
        Value[] row = recordArrayValues.get(index);
        if (row == null)
            return ValueNull.INSTANCE;
        int fieldIndex = findRecordFieldIndex(fieldName);
        return row[fieldIndex];
    }

    public void setRecordArrayElement(int index, Value value) {
        checkArrayIndex(index);
        if (value == null || value == ValueNull.INSTANCE) {
            Value[] row = new Value[recordFieldNames.length];
            for (int i = 0; i < row.length; i++)
                row[i] = ValueNull.INSTANCE;
            recordArrayValues.put(index, row);
            return;
        }
        if (value.getType() != Value.ARRAY)
            throw PlpgSqlException.raise("42804",
                    "record array " + name + " element must be a row value");
        Value[] list = ((ValueArray) value).getList();
        if (list.length != recordFieldNames.length)
            throw PlpgSqlException.raise("42804", "record array " + name + " element has "
                    + list.length + " fields but expected " + recordFieldNames.length);
        Value[] row = new Value[list.length];
        for (int i = 0; i < list.length; i++) {
            Value v = list[i] == null ? ValueNull.INSTANCE : list[i];
            if (recordFieldTypes[i] != Value.UNKNOWN)
                v = v.convertTo(recordFieldTypes[i]);
            row[i] = v;
        }
        recordArrayValues.put(index, row);
    }

    public void setRecordArrayField(int index, String fieldName, Value value) {
        checkArrayIndex(index);
        if (value == null)
            value = ValueNull.INSTANCE;
        Value[] row = recordArrayValues.get(index);
        if (row == null) {
            row = new Value[recordFieldNames.length];
            for (int i = 0; i < row.length; i++)
                row[i] = ValueNull.INSTANCE;
            recordArrayValues.put(index, row);
        }
        int fieldIndex = findRecordFieldIndex(fieldName);
        if (recordFieldTypes[fieldIndex] != Value.UNKNOWN)
            value = value.convertTo(recordFieldTypes[fieldIndex]);
        row[fieldIndex] = value;
    }

    private void checkArrayIndex(int index) {
        if (index < 1)
            throw PlpgSqlException.raise("2202E", "array subscript outside bounds: " + index);
    }

    private int findRecordFieldIndex(String fieldName) {
        for (int i = 0; i < recordFieldNames.length; i++) {
            if (recordFieldNames[i].equalsIgnoreCase(fieldName))
                return i;
        }
        throw PlpgSqlException.raise("42703",
                "record array \"" + name + "\" has no field \"" + fieldName + "\"");
    }

    public Value getRecordField(String fieldName) {
        int index = findRecordField(fieldName);
        return recordFieldValues[index];
    }

    public int getRecordFieldCount() {
        return recordFieldNames == null ? 0 : recordFieldNames.length;
    }

    public Value getRecordField(int index) {
        if (!recordAssigned)
            throw PlpgSqlException.raise("55000",
                    "record \"" + name + "\" is not assigned yet");
        if (index < 0 || index >= recordFieldValues.length)
            throw PlpgSqlException.raise("42703",
                    "record \"" + name + "\" has no field " + (index + 1));
        return recordFieldValues[index];
    }

    // 复合值是否整体为NULL：未赋值或所有字段都是NULL
    public boolean isRecordNull() {
        if (!recordAssigned || recordFieldValues == null)
            return true;
        for (Value fieldValue : recordFieldValues) {
            if (fieldValue != null && fieldValue != ValueNull.INSTANCE)
                return false;
        }
        return true;
    }

    // RAISE NOTICE '%', r时按PostgreSQL的(字段值,字段值)格式输出整行
    public String getRecordString() {
        if (!recordAssigned)
            throw PlpgSqlException.raise("55000",
                    "record \"" + name + "\" is not assigned yet");
        StringBuilder buff = new StringBuilder();
        buff.append('(');
        for (int i = 0; i < recordFieldValues.length; i++) {
            if (i > 0)
                buff.append(',');
            Value fieldValue = recordFieldValues[i];
            if (fieldValue != null && fieldValue != ValueNull.INSTANCE)
                buff.append(fieldValue.getString());
        }
        buff.append(')');
        return buff.toString();
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
        if (recordArray)
            throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                    "PL/pgSQL record array variable " + name + " must be assigned as arr[index]");
        if (record) {
            if (value == ValueNull.INSTANCE) {
                if (rowTypeDeclared && recordFieldValues != null) {
                    resetRecordValues();
                    recordAssigned = true;
                } else {
                    recordAssigned = false;
                }
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
            if (rowTypeDeclared && recordFieldValues != null) {
                resetRecordValues();
                recordAssigned = true;
            } else {
                recordAssigned = false;
            }
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
