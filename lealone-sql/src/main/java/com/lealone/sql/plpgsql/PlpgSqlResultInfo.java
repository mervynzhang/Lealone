/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

/**
 * PL/pgSQL函数的返回形态：标量、VOID、RETURNS RECORD或多个OUT参数组成的一行，
 * 或者RETURNS SETOF/RETURNS TABLE声明的结果集。
 */
public class PlpgSqlResultInfo {

    public final String functionName;
    public final int returnType;
    // RETURNS RECORD(OUT参数组成一行record)
    public final boolean returnsRecord;
    // RETURNS SETOF/RETURNS TABLE
    public final boolean setReturning;
    public final boolean returnsTable;
    // RETURNS TRIGGER
    public final boolean trigger;
    // 参数模式和OUT/INOUT参数下标
    public final int[] parameterModes;
    public final int[] outputIndexes;
    // 过程体也是用这个类描述返回形态: 没有OUT/INOUT时什么都不返回, 有OUT/INOUT时返回一行
    public final boolean procedure;
    // SETOF/TABLE结果列；SETOF标量的columnNames为null，列名用函数名
    public final String[] columnNames;
    public final int[] columnTypes;
    public final long[] columnPrecisions;
    public final int[] columnScales;

    public PlpgSqlResultInfo(String functionName, int returnType, boolean returnsRecord,
            boolean setReturning, boolean returnsTable, boolean trigger, int[] parameterModes,
            int[] outputIndexes, String[] columnNames, int[] columnTypes, long[] columnPrecisions,
            int[] columnScales, boolean procedure) {
        this.functionName = functionName;
        this.returnType = returnType;
        this.returnsRecord = returnsRecord;
        this.setReturning = setReturning;
        this.returnsTable = returnsTable;
        this.trigger = trigger;
        this.parameterModes = parameterModes;
        this.outputIndexes = outputIndexes;
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.columnPrecisions = columnPrecisions;
        this.columnScales = columnScales;
        this.procedure = procedure;
    }

    public boolean hasOutputParameters() {
        return outputIndexes.length > 0;
    }

    public boolean hasMultipleOutputParameters() {
        return outputIndexes.length > 1;
    }

    public String getColumnName(int i) {
        return columnNames == null || i >= columnNames.length ? functionName : columnNames[i];
    }
}
