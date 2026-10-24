/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

/**
 * SQL解析器在解析表达式时用来查找PL/pgSQL变量。
 * 找不到时返回null，表示这个标识符是普通的列名或函数名。
 */
public interface PlpgSqlVariableResolver {

    PlpgSqlVariable findVariable(String name, boolean quoted);
}
