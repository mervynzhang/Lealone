/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.logging.impl;

import java.io.File;
import java.util.Map;

import com.lealone.common.logging.LoggerFactory;
import com.lealone.common.trace.Trace;
import com.lealone.common.trace.TraceSystem;
import com.lealone.common.util.MapUtils;
import com.lealone.db.SysProperties;

public class DefaultLoggerFactory extends LoggerFactory {

    private final TraceSystem traceSystem;
    private final Trace consoleTrace;

    public DefaultLoggerFactory(Map<String, String> parameters) {
        String type = MapUtils.getString(parameters, "type", "console");
        String file = null;
        if (type.equalsIgnoreCase("file")) {
            file = new File(SysProperties.getBaseDir(), "lealone.log").getAbsolutePath();
        }
        traceSystem = new TraceSystem(file);
        consoleTrace = file == null ? traceSystem.getTrace((String) null) : null;
        int level = getlevel(parameters);
        traceSystem.setLevelFile(level);
        traceSystem.setLevelSystemOut(level);
    }

    @Override
    public DefaultLogger createLogger(String name) {
        if (consoleTrace != null)
            return new DefaultLogger(consoleTrace);
        int pos = name.lastIndexOf('.');
        if (pos >= 0)
            name = name.substring(pos + 1);
        return new DefaultLogger(traceSystem.getTrace(name));
    }

    private static int getlevel(Map<String, String> parameters) {
        int level = TraceSystem.INFO;
        switch (MapUtils.getString(parameters, "level", "info").toLowerCase()) {
        case "info":
        case "warn":
        case "warning":
            level = TraceSystem.INFO;
            break;
        case "error":
            level = TraceSystem.ERROR;
            break;
        case "debug":
        case "trace":
            level = TraceSystem.DEBUG;
            break;
        case "off":
            level = TraceSystem.OFF;
            break;
        default:
            level = TraceSystem.INFO;
            break;
        }
        return level;
    }
}
