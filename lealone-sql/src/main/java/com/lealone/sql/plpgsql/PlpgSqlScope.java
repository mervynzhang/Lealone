/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PL/pgSQL的变量作用域，嵌套块通过parent指针形成作用域链。
 * 没有加双引号的变量名大小写不敏感，加了双引号的按原样精确匹配。
 */
public class PlpgSqlScope implements PlpgSqlVariableResolver {

    private final PlpgSqlScope parent;
    private final Map<String, PlpgSqlVariable> variables = new LinkedHashMap<>();

    public PlpgSqlScope(PlpgSqlScope parent) {
        this.parent = parent;
    }

    public PlpgSqlScope getParent() {
        return parent;
    }

    public void declare(PlpgSqlVariable variable) {
        variables.put(variable.getName(), variable);
    }

    @Override
    public PlpgSqlVariable findVariable(String name, boolean quoted) {
        PlpgSqlVariable v = findInThisScope(name, quoted);
        if (v != null)
            return v;
        return parent == null ? null : parent.findVariable(name, quoted);
    }

    private PlpgSqlVariable findInThisScope(String name, boolean quoted) {
        PlpgSqlVariable v = variables.get(name);
        if (v != null && (quoted == v.isNameQuoted()))
            return v;
        if (!quoted) {
            for (PlpgSqlVariable variable : variables.values()) {
                if (!variable.isNameQuoted() && variable.getName().equalsIgnoreCase(name))
                    return variable;
            }
        }
        return null;
    }
}
