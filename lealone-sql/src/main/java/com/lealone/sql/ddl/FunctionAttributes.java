/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;

/**
 * CREATE FUNCTION和ALTER FUNCTION里可选的函数属性。
 * 字段为null表示这次没有写这个属性，这样ALTER FUNCTION只改用户写出来的那一项。
 */
public class FunctionAttributes {

    private String volatility;
    private Boolean strict; // true表示STRICT，false表示CALLED ON NULL INPUT
    private Boolean securityDefiner;
    private Boolean leakproof;
    private Boolean window;
    private Double cost;
    private Double rows;
    private String owner;
    // 每个元素是{参数名, 值}，值可能为null(表示DEFAULT)
    private final ArrayList<String[]> configItems = new ArrayList<>();

    public String getVolatility() {
        return volatility;
    }

    public void setVolatility(String volatility) {
        this.volatility = volatility;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }

    public Boolean getSecurityDefiner() {
        return securityDefiner;
    }

    public void setSecurityDefiner(Boolean securityDefiner) {
        this.securityDefiner = securityDefiner;
    }

    public Boolean getLeakproof() {
        return leakproof;
    }

    public void setLeakproof(Boolean leakproof) {
        this.leakproof = leakproof;
    }

    public Boolean getWindow() {
        return window;
    }

    public void setWindow(Boolean window) {
        this.window = window;
    }

    public Double getCost() {
        return cost;
    }

    public void setCost(Double cost) {
        this.cost = cost;
    }

    public Double getRows() {
        return rows;
    }

    public void setRows(Double rows) {
        this.rows = rows;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public ArrayList<String[]> getConfigItems() {
        return configItems;
    }

    public void addConfigItem(String name, String value) {
        configItems.add(new String[] { name, value });
    }
}
