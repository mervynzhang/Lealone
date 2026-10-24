/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.ddl;

import java.util.ArrayList;

import com.lealone.db.schema.SqlFunction;
import com.lealone.db.schema.SqlProcedure;

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

    /**
     * 把这次写出来的属性应用到函数上，没写的属性保持不变。
     */
    public void applyTo(SqlFunction function) {
        if (volatility != null) {
            function.setVolatility(volatility);
        }
        if (strict != null) {
            function.setStrict(strict.booleanValue());
        }
        if (securityDefiner != null) {
            function.setSecurityDefiner(securityDefiner.booleanValue());
        }
        if (leakproof != null) {
            function.setLeakproof(leakproof.booleanValue());
        }
        if (window != null) {
            function.setWindow(window.booleanValue());
        }
        if (cost != null) {
            function.setCost(cost);
        }
        if (rows != null) {
            function.setRows(rows);
        }
        for (String[] item : configItems) {
            function.removeConfigItem(item[0]);
            function.addConfigItem(item[0], item[1]);
        }
    }

    /**
     * 把这次写出来的属性应用到过程上，没写的属性保持不变。
     */
    public void applyTo(SqlProcedure procedure) {
        if (volatility != null) {
            procedure.setVolatility(volatility);
        }
        if (strict != null) {
            procedure.setStrict(strict.booleanValue());
        }
        if (securityDefiner != null) {
            procedure.setSecurityDefiner(securityDefiner.booleanValue());
        }
        if (leakproof != null) {
            procedure.setLeakproof(leakproof.booleanValue());
        }
        if (window != null) {
            procedure.setWindow(window.booleanValue());
        }
        if (cost != null) {
            procedure.setCost(cost);
        }
        if (rows != null) {
            procedure.setRows(rows);
        }
        for (String[] item : configItems) {
            procedure.removeConfigItem(item[0]);
            procedure.addConfigItem(item[0], item[1]);
        }
    }

    public ArrayList<String[]> getConfigItems() {
        return configItems;
    }

    public void addConfigItem(String name, String value) {
        configItems.add(new String[] { name, value });
    }
}
