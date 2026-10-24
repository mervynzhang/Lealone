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
 * Lealone的标识符默认大小写不敏感，所以变量查找也按大小写不敏感处理，
 * 加了双引号的变量优先按原样精确匹配。
 */
public class PlpgSqlScope implements PlpgSqlVariableResolver {

    private final PlpgSqlScope parent;
    private final Map<String, PlpgSqlVariable> variables = new LinkedHashMap<>();
    // 块标签，用于outerblock.param1这种限定引用
    private String label;

    public PlpgSqlScope(PlpgSqlScope parent) {
        this.parent = parent;
    }

    public PlpgSqlScope getParent() {
        return parent;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void declare(PlpgSqlVariable variable) {
        for (PlpgSqlVariable old : variables.values()) {
            if (old.getName().equalsIgnoreCase(variable.getName()))
                throw PlpgSqlException.raise("42723",
                        "duplicate declaration of variable " + variable.getName());
        }
        variables.put(variable.getName(), variable);
    }

    @Override
    public PlpgSqlVariable findVariable(String name, boolean quoted) {
        PlpgSqlVariable v = findInThisScope(name, quoted);
        if (v != null)
            return v;
        return parent == null ? null : parent.findVariable(name, quoted);
    }

    @Override
    public boolean hasLabel(String label) {
        if (label != null && label.equalsIgnoreCase(this.label))
            return true;
        return parent != null && parent.hasLabel(label);
    }

    @Override
    public PlpgSqlVariable findVariableByLabel(String label, String name, boolean quoted) {
        if (label != null && label.equalsIgnoreCase(this.label)) {
            // 找到指定标签的块后，按正常作用域规则从该块开始向外找
            return findVariable(name, quoted);
        }
        return parent == null ? null : parent.findVariableByLabel(label, name, quoted);
    }

    private PlpgSqlVariable findInThisScope(String name, boolean quoted) {
        PlpgSqlVariable exactButDifferentQuote = null;
        PlpgSqlVariable caseInsensitive = null;
        for (PlpgSqlVariable variable : variables.values()) {
            if (variable.getName().equals(name)) {
                // 大小写和引号都一致时直接命中
                if (quoted == variable.isNameQuoted())
                    return variable;
                if (exactButDifferentQuote == null)
                    exactButDifferentQuote = variable;
            } else if (variable.getName().equalsIgnoreCase(name) && caseInsensitive == null) {
                caseInsensitive = variable;
            }
        }
        return exactButDifferentQuote != null ? exactButDifferentQuote : caseInsensitive;
    }
}
