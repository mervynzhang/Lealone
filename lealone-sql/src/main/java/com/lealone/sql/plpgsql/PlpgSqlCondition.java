/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lealone.common.exceptions.DbException;

/**
 * PL/pgSQL异常处理里的条件名与SQLSTATE的映射和匹配。
 * 条件可以写成OTHERS、PostgreSQL条件名(如division_by_zero)或者5位SQLSTATE。
 */
public final class PlpgSqlCondition {

    private static final Map<String, String> NAME_TO_SQL_STATE = new HashMap<>();

    static {
        add("raise_exception", "P0001");
        add("no_data_found", "P0002");
        add("too_many_rows", "P0003");
        add("division_by_zero", "22012");
        add("numeric_value_out_of_range", "22003");
        add("invalid_text_representation", "22P02");
        add("string_data_right_truncation", "22001");
        add("null_value_not_allowed", "22004");
        add("unique_violation", "23505");
        add("not_null_violation", "23502");
        add("foreign_key_violation", "23503");
        add("check_violation", "23514");
        add("exclusion_violation", "23P01");
        add("invalid_cursor_state", "24000");
        add("invalid_transaction_state", "25000");
        add("invalid_sql_statement_name", "26000");
        add("invalid_authorization_specification", "28000");
        add("dependent_privilege_descriptors_still_exist", "2B000");
        add("invalid_transaction_termination", "2D000");
        add("sql_routine_exception", "2F000");
        add("invalid_cursor_name", "34000");
        add("external_routine_exception", "38000");
        add("savepoint_exception", "3B000");
        add("invalid_catalog_name", "3D000");
        add("invalid_schema_name", "3F000");
        add("transaction_rollback", "40000");
        add("syntax_error_or_access_rule_violation", "42000");
        add("syntax_error", "42601");
        add("insufficient_privilege", "42501");
        add("undefined_column", "42703");
        add("undefined_function", "42883");
        add("undefined_table", "42P01");
        add("duplicate_table", "42P07");
        add("duplicate_column", "42701");
        add("datatype_mismatch", "42804");
        add("case_not_found", "20000");
        add("cardinality_violation", "21000");
        add("program_limit_exceeded", "54000");
        add("query_canceled", "57014");
        add("admin_shutdown", "57P01");
        add("crash_shutdown", "57P02");
        add("cannot_connect_now", "57P03");
        add("io_error", "58030");
        add("internal_error", "XX000");
    }

    private PlpgSqlCondition() {
    }

    private static void add(String name, String sqlState) {
        NAME_TO_SQL_STATE.put(name, sqlState);
    }

    /**
     * 把parser保存的条件文本归一化成OTHERS标记、SQLSTATE码或条件名。
     */
    public static String normalize(String condition) {
        if (condition == null)
            return null;
        String c = condition.trim();
        if (c.isEmpty())
            return c;
        if (c.equalsIgnoreCase("others"))
            return "others";
        // 5位SQLSTATE原样返回，统一转成大写
        if (c.length() == 5) {
            boolean allValid = true;
            for (int i = 0; i < 5; i++) {
                char ch = c.charAt(i);
                if (!(Character.isLetterOrDigit(ch)))
                    allValid = false;
            }
            if (allValid)
                return c.toUpperCase();
        }
        String sqlState = toSqlState(c);
        return sqlState != null ? sqlState : c.toLowerCase();
    }

    /**
     * 判断handler的条件列表是否匹配捕获到的SQLSTATE。
     */
    public static boolean matches(List<String> conditions, String sqlState) {
        if (conditions == null || conditions.isEmpty())
            return false;
        for (String condition : conditions) {
            if ("others".equalsIgnoreCase(condition))
                return true;
            String normalized = normalize(condition);
            if (sqlState == null) {
                continue;
            }
            if (normalized != null && normalized.equalsIgnoreCase(sqlState))
                return true;
        }
        return false;
    }

    /**
     * 条件名到SQLSTATE的映射，不认识的返回null。
     */
    public static String toSqlState(String conditionName) {
        if (conditionName == null)
            return null;
        return NAME_TO_SQL_STATE.get(conditionName.trim().toLowerCase());
    }

    /**
     * 从捕获的异常里取SQLSTATE，取不到时返回null。
     */
    public static String getSqlState(Throwable t) {
        if (t == null)
            return null;
        if (t instanceof PlpgSqlException)
            return ((PlpgSqlException) t).getSqlState();
        if (t instanceof DbException) {
            try {
                return ((DbException) t).getSQLException().getSQLState();
            } catch (Throwable e) {
                return null;
            }
        }
        if (t instanceof java.sql.SQLException)
            return ((java.sql.SQLException) t).getSQLState();
        return null;
    }
}
