/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

/**
 * PL/pgSQL里的RAISE EXCEPTION异常。
 * SQLSTATE是5位字符串，默认P0001(raise_exception)。
 * 不使用DbException是因为它的构造器是私有的，而且这个异常只在PL/pgSQL内部
 * 用于EXCEPTION匹配，离开函数时会被转换成带SQLSTATE的DbException。
 */
public class PlpgSqlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_SQL_STATE = "P0001";

    private final String sqlState;

    public PlpgSqlException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState == null ? DEFAULT_SQL_STATE : sqlState;
    }

    public String getSqlState() {
        return sqlState;
    }

    public static PlpgSqlException raise(String sqlState, String message) {
        return new PlpgSqlException(sqlState, message);
    }

    public static PlpgSqlException noDataFound() {
        return new PlpgSqlException("P0002", "query returned no rows");
    }

    public static PlpgSqlException tooManyRows() {
        return new PlpgSqlException("P0003", "query returned more than one row");
    }

    public static PlpgSqlException notNullViolation(String variableName) {
        return new PlpgSqlException("23502", "null value in variable " + variableName
                + " violates not-null constraint");
    }
}
