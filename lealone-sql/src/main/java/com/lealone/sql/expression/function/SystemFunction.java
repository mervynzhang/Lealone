/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression.function;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.JdbcUtils;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.common.util.Utils;
import com.lealone.db.Database;
import com.lealone.db.Mode;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.command.Command;
import com.lealone.db.schema.FunctionAlias;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.Sequence;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.util.Csv;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueList;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueMap;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.db.value.ValueString;
import com.lealone.sql.LealoneSQLParser;
import com.lealone.sql.expression.Expression;
import com.lealone.sql.expression.ExpressionColumn;
import com.lealone.sql.expression.SequenceValue;
import com.lealone.sql.expression.ValueExpression;
import com.lealone.sql.expression.Variable;
import com.lealone.storage.fs.FileUtils;

/**
 * Implementation of the system functions.
 * 
 * @author H2 Group
 * @author zhh
 */
public class SystemFunction extends BuiltInFunction {

    public static final int DATABASE = 150, USER = 151, CURRENT_USER = 152, IDENTITY = 153,
            SCOPE_IDENTITY = 154, AUTOCOMMIT = 155, READONLY = 156, DATABASE_PATH = 157,
            LOCK_TIMEOUT = 158, DISK_SPACE_USED = 159, PG_GET_FUNCTIONDEF = 160;

    public static final int IFNULL = 200, CASEWHEN = 201, CONVERT = 202, CAST = 203, COALESCE = 204,
            NULLIF = 205, CASE = 206, NEXTVAL = 207, CURRVAL = 208, ARRAY_GET = 209, CSVREAD = 210,
            CSVWRITE = 211, MEMORY_FREE = 212, MEMORY_USED = 213, TRANSACTION_ISOLATION_LEVEL = 214,
            SCHEMA = 215, SESSION_ID = 216, ARRAY_LENGTH = 217, GREATEST = 218, LEAST = 219,
            CANCEL_SESSION = 220, SET = 221, FILE_READ = 222, TRANSACTION_ID = 223, TRUNCATE_VALUE = 224,
            NVL2 = 225, DECODE = 226, ARRAY_CONTAINS = 227, ARRAY_APPEND = 228;

    public static final int VERSION = 229;
    public static final int ROW_NUMBER = 230;

    public static void init() {
        addFunctionNotDeterministic("DATABASE", DATABASE, 0, Value.STRING);
        addFunctionNotDeterministic("USER", USER, 0, Value.STRING);
        addFunctionNotDeterministic("CURRENT_USER", CURRENT_USER, 0, Value.STRING);
        addFunctionNotDeterministic("IDENTITY", IDENTITY, 0, Value.LONG);
        addFunctionNotDeterministic("SCOPE_IDENTITY", SCOPE_IDENTITY, 0, Value.LONG);
        addFunctionNotDeterministic("IDENTITY_VAL_LOCAL", IDENTITY, 0, Value.LONG);
        addFunctionNotDeterministic("LAST_INSERT_ID", IDENTITY, 0, Value.LONG);
        addFunctionNotDeterministic("LASTVAL", IDENTITY, 0, Value.LONG);
        addFunctionNotDeterministic("AUTOCOMMIT", AUTOCOMMIT, 0, Value.BOOLEAN);
        addFunctionNotDeterministic("READONLY", READONLY, 0, Value.BOOLEAN);
        addFunction("DATABASE_PATH", DATABASE_PATH, 0, Value.STRING);
        addFunctionNotDeterministic("LOCK_TIMEOUT", LOCK_TIMEOUT, 0, Value.INT);
        addFunctionWithNull("IFNULL", IFNULL, 2, Value.NULL);
        addFunctionWithNull("ISNULL", IFNULL, 2, Value.NULL);
        addFunctionWithNull("CASEWHEN", CASEWHEN, 3, Value.NULL);
        addFunctionWithNull("CONVERT", CONVERT, 1, Value.NULL);
        addFunctionWithNull("CAST", CAST, 1, Value.NULL);
        addFunctionWithNull("TRUNCATE_VALUE", TRUNCATE_VALUE, 3, Value.NULL);
        addFunctionWithNull("COALESCE", COALESCE, VAR_ARGS, Value.NULL);
        addFunctionWithNull("NVL", COALESCE, VAR_ARGS, Value.NULL);
        addFunctionWithNull("NVL2", NVL2, 3, Value.NULL);
        addFunctionWithNull("NULLIF", NULLIF, 2, Value.NULL);
        addFunctionWithNull("CASE", CASE, VAR_ARGS, Value.NULL);
        addFunctionNotDeterministic("NEXTVAL", NEXTVAL, VAR_ARGS, Value.LONG);
        addFunctionNotDeterministic("CURRVAL", CURRVAL, VAR_ARGS, Value.LONG);
        addFunction("ARRAY_GET", ARRAY_GET, 2, Value.STRING);
        addFunction("ARRAY_APPEND", ARRAY_APPEND, 2, Value.ARRAY);
        addFunction("ARRAY_CONTAINS", ARRAY_CONTAINS, 2, Value.BOOLEAN, false, true);
        addFunction("CSVREAD", CSVREAD, VAR_ARGS, Value.RESULT_SET, false, false);
        addFunction("CSVWRITE", CSVWRITE, VAR_ARGS, Value.INT, false, false);
        addFunctionNotDeterministic("MEMORY_FREE", MEMORY_FREE, 0, Value.INT);
        addFunctionNotDeterministic("MEMORY_USED", MEMORY_USED, 0, Value.INT);
        addFunctionNotDeterministic("TRANSACTION_ISOLATION_LEVEL", TRANSACTION_ISOLATION_LEVEL, 0,
                Value.INT);
        addFunctionNotDeterministic("SCHEMA", SCHEMA, 0, Value.STRING);
        addFunctionNotDeterministic("SESSION_ID", SESSION_ID, 0, Value.INT);
        addFunction("ARRAY_LENGTH", ARRAY_LENGTH, VAR_ARGS, Value.INT);
        addFunctionWithNull("LEAST", LEAST, VAR_ARGS, Value.NULL);
        addFunctionWithNull("GREATEST", GREATEST, VAR_ARGS, Value.NULL);
        addFunctionNotDeterministic("CANCEL_SESSION", CANCEL_SESSION, 1, Value.BOOLEAN);
        addFunction("SET", SET, 2, Value.NULL, false, false);
        addFunction("FILE_READ", FILE_READ, VAR_ARGS, Value.NULL, false, false);
        addFunctionNotDeterministic("TRANSACTION_ID", TRANSACTION_ID, 0, Value.STRING);
        addFunctionWithNull("DECODE", DECODE, VAR_ARGS, Value.NULL);
        addFunctionNotDeterministic("DISK_SPACE_USED", DISK_SPACE_USED, 1, Value.LONG);
        // PostgreSQL兼容函数: 按函数名返回CREATE FUNCTION的DDL文本;
        // 结果依赖schema里的函数定义(DDL可变), 所以不能当确定性函数在prepare阶段折叠
        addFunctionNotDeterministic("PG_GET_FUNCTIONDEF", PG_GET_FUNCTIONDEF, 1, Value.STRING);
        addFunction("VERSION", VERSION, 0, Value.STRING);

        // pseudo function
        addFunctionWithNull("ROW_NUMBER", ROW_NUMBER, 0, Value.LONG);

        // LEALONE_VERSION是VERSION的别名
        addFunction("LEALONE_VERSION", Function.getFunctionInfo("VERSION"));
    }

    protected SystemFunction(Database database, FunctionInfo info) {
        super(database, info);
    }

    @Override
    protected Value getValue0(ServerSession session) {
        Value result;
        switch (info.type) {
        case DATABASE:
            result = ValueString.get(database.getShortName());
            break;
        case USER:
        case CURRENT_USER:
            result = ValueString.get(session.getUser().getName());
            break;
        case IDENTITY:
            result = ValueLong.get(session.getLastIdentity());
            break;
        case SCOPE_IDENTITY:
            result = ValueLong.get(session.getLastScopeIdentity());
            break;
        case AUTOCOMMIT:
            result = ValueBoolean.get(session.isAutoCommit());
            break;
        case READONLY:
            result = ValueBoolean.get(database.isReadOnly());
            break;
        case DATABASE_PATH: {
            String path = database.getDatabasePath();
            result = path == null ? (Value) ValueNull.INSTANCE : ValueString.get(path);
            break;
        }
        case LOCK_TIMEOUT:
            result = ValueInt.get(session.getLockTimeout());
            break;
        case MEMORY_FREE:
            session.getUser().checkAdmin();
            result = ValueInt.get(Utils.getMemoryFree());
            break;
        case MEMORY_USED:
            session.getUser().checkAdmin();
            result = ValueInt.get(Utils.getMemoryUsed());
            break;
        case TRANSACTION_ISOLATION_LEVEL:
            result = ValueInt.get(session.getTransactionIsolationLevel());
            break;
        case SCHEMA:
            result = ValueString.get(session.getCurrentSchemaName());
            break;
        case SESSION_ID:
            result = ValueInt.get(session.getId());
            break;
        case TRANSACTION_ID: {
            result = session.getTransactionId();
            break;
        }
        case VERSION:
            result = ValueString.get(session.getVersion());
            break;
        default:
            throw getUnsupportedException();
        }
        return result;
    }

    @Override
    protected Value getValue1(ServerSession session, Value v) {
        Value result;
        switch (info.type) {
        case DISK_SPACE_USED:
            result = ValueLong.get(getDiskSpaceUsed(session, v));
            break;
        case PG_GET_FUNCTIONDEF:
            result = getFunctionDef(session, v.getString());
            break;
        case CAST:
        case CONVERT: {
            v = v.convertTo(dataType);
            Mode mode = database.getMode();
            v = v.convertScale(mode.convertOnlyToSmallerScale, scale);
            v = v.convertPrecision(getPrecision(), false);
            result = v;
            break;
        }
        case ARRAY_LENGTH: {
            if (v.getType() == Value.ARRAY) {
                Value[] list = ((ValueArray) v).getList();
                result = ValueInt.get(list.length);
            } else if (v.getType() == Value.LIST) {
                List<Value> list = ((ValueList) v).getList();
                result = ValueInt.get(list.size());
            } else {
                result = ValueNull.INSTANCE;
            }
            break;
        }
        case CANCEL_SESSION: {
            result = ValueBoolean.get(cancelStatement(session, v.getInt()));
            break;
        }
        default:
            throw getUnsupportedException();
        }
        return result;
    }

    // pg_get_functiondef(name): 返回函数的CREATE FUNCTION文本，重载时要求参数类型唯一
    private static Value getFunctionDef(ServerSession session, String name) {
        FunctionAlias function = findFunction(session, name);
        if (function == null) {
            throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1, name);
        }
        return ValueString.get(function.getCreateSQL());
    }

    private static FunctionAlias findFunction(ServerSession session, String name) {
        Database db = session.getDatabase();
        ArrayList<FunctionAlias> list = db.getSchema(session, session.getCurrentSchemaName())
                .findFunctions(session, name);
        if (list.isEmpty()) {
            String[] schemaNames = session.getSchemaSearchPath();
            if (schemaNames != null) {
                for (String schemaName : schemaNames) {
                    list = db.getSchema(session, schemaName).findFunctions(session, name);
                    if (!list.isEmpty()) {
                        break;
                    }
                }
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            throw DbException.get(ErrorCode.AMBIGUOUS_FUNCTION_NAME_1, name);
        }
        return list.get(0);
    }

    private static long getDiskSpaceUsed(ServerSession session, Value v0) {
        LealoneSQLParser p = new LealoneSQLParser(session);
        String sql = v0.getString();
        Table table = p.parseTableName(sql);
        return table.getDiskSpaceUsed();
    }

    private static boolean isLazyArgs(int type) {
        switch (type) {
        case IFNULL:
        case CASEWHEN:
        case COALESCE:
        case NVL2:
        case DECODE:
        case CASE:
        case GREATEST:
        case LEAST:
            return true;
        default:
            return false;
        }
    }

    private static boolean cancelStatement(ServerSession session, int targetSessionId) {
        session.getUser().checkAdmin();
        ServerSession[] sessions = session.getDatabase().getSessions(false);
        for (ServerSession s : sessions) {
            if (s.getId() == targetSessionId) {
                Command c = s.getCurrentCommand();
                if (c == null) {
                    return false;
                }
                c.cancel();
                return true;
            }
        }
        return false;
    }

    @Override
    protected Value getValueN(ServerSession session, Expression[] args, Value[] values) {
        // CASE、COALESCE这些函数的参数不一定被求值，不能提前把参数都算出来，
        // 否则没有被选中的分支也会执行(比如除以0)，聚合函数还会被算两次
        boolean lazyArgs = isLazyArgs(info.type);
        Value v0 = getNullOrValue(session, args, values, 0);
        Value v1 = lazyArgs ? null : getNullOrValue(session, args, values, 1);
        Value v2 = lazyArgs ? null : getNullOrValue(session, args, values, 2);
        Value v3 = lazyArgs ? null : getNullOrValue(session, args, values, 3);
        Value v4 = lazyArgs ? null : getNullOrValue(session, args, values, 4);
        Value v5 = lazyArgs ? null : getNullOrValue(session, args, values, 5);
        Value result;
        switch (info.type) {
        case ARRAY_APPEND: {
            if (v0 == null || v0.getType() != Value.ARRAY) {
                result = ValueNull.INSTANCE;
                break;
            }
            Value[] list = ((ValueArray) v0).getList();
            Value[] newList = new Value[list.length + 1];
            System.arraycopy(list, 0, newList, 0, list.length);
            newList[list.length] = v1 == null ? ValueNull.INSTANCE : v1;
            result = ValueArray.get(newList);
            break;
        }
        case ARRAY_LENGTH: {
            if (v0 == null || args.length > 2) {
                result = ValueNull.INSTANCE;
                break;
            }
            if (args.length == 2
                    && (v1 == null || v1 == ValueNull.INSTANCE || v1.getInt() != 1)) {
                result = ValueNull.INSTANCE;
                break;
            }
            if (v0.getType() == Value.ARRAY) {
                result = ValueInt.get(((ValueArray) v0).getList().length);
            } else if (v0.getType() == Value.LIST) {
                // 兼容旧的ValueList路径(JSON/集合列)
                result = ValueInt.get(((ValueList) v0).getList().size());
            } else {
                result = ValueNull.INSTANCE;
            }
            break;
        }
        case NULLIF:
            // 相等返回null，不相等返回v0
            result = database.areEqual(v0, v1) ? ValueNull.INSTANCE : v0;
            break;
        case NEXTVAL: {
            Sequence sequence = getSequence(session, v0, v1);
            SequenceValue value = new SequenceValue(sequence);
            result = value.getValue(session);
            break;
        }
        case CURRVAL: {
            Sequence sequence = getSequence(session, v0, v1);
            result = ValueLong.get(sequence.getCurrentValue(session));
            break;
        }
        case CSVREAD: {
            String fileName = v0.getString();
            String columnList = v1 == null ? null : v1.getString();
            Csv csv = new Csv();
            String options = v2 == null ? null : v2.getString();
            String charset = null;
            if (options != null && options.indexOf('=') >= 0) {
                charset = csv.setOptions(options);
            } else {
                charset = options;
                String fieldSeparatorRead = v3 == null ? null : v3.getString();
                String fieldDelimiter = v4 == null ? null : v4.getString();
                String escapeCharacter = v5 == null ? null : v5.getString();
                Value v6 = getNullOrValue(session, args, values, 6);
                String nullString = v6 == null ? null : v6.getString();
                setCsvDelimiterEscape(csv, fieldSeparatorRead, fieldDelimiter, escapeCharacter);
                csv.setNullString(nullString);
            }
            char fieldSeparator = csv.getFieldSeparatorRead();
            String[] columns = StringUtils.arraySplit(columnList, fieldSeparator);
            try {
                ValueResultSet vr = ValueResultSet.get(csv.read(fileName, columns, charset));
                result = vr;
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
            break;
        }
        case CSVWRITE: {
            session.getUser().checkAdmin();
            Connection conn = session.createNestedConnection(false);
            Csv csv = new Csv();
            String options = v2 == null ? null : v2.getString();
            String charset = null;
            if (options != null && options.indexOf('=') >= 0) {
                charset = csv.setOptions(options);
            } else {
                charset = options;
                String fieldSeparatorWrite = v3 == null ? null : v3.getString();
                String fieldDelimiter = v4 == null ? null : v4.getString();
                String escapeCharacter = v5 == null ? null : v5.getString();
                Value v6 = getNullOrValue(session, args, values, 6);
                String nullString = v6 == null ? null : v6.getString();
                Value v7 = getNullOrValue(session, args, values, 7);
                String lineSeparator = v7 == null ? null : v7.getString();
                setCsvDelimiterEscape(csv, fieldSeparatorWrite, fieldDelimiter, escapeCharacter);
                csv.setNullString(nullString);
                if (lineSeparator != null) {
                    csv.setLineSeparator(lineSeparator);
                }
            }
            try {
                int rows = csv.write(conn, v0.getString(), v1.getString(), charset);
                result = ValueInt.get(rows);
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
            break;
        }
        case SET: {
            Variable var = (Variable) args[0];
            session.setVariable(var.getName(), v1);
            result = v1;
            break;
        }
        case FILE_READ: {
            session.getUser().checkAdmin();
            String fileName = v0.getString();
            boolean blob = args.length == 1;
            try {
                try (InputStream in = FileUtils.newInputStream(fileName)) {
                    if (blob) {
                        result = session.getDataHandler().getLobStorage().createBlob(in, -1);
                    } else {
                        Reader reader;
                        if (v1 == ValueNull.INSTANCE) {
                            reader = new InputStreamReader(in);
                        } else {
                            reader = new InputStreamReader(in, v1.getString());
                        }
                        result = session.getDataHandler().getLobStorage().createClob(reader, -1);
                    }
                }
            } catch (IOException e) {
                throw DbException.convertIOException(e, fileName);
            }
            break;
        }
        case TRUNCATE_VALUE: {
            result = v0.convertPrecision(v1.getLong(), v2.getBoolean());
            break;
        }
        case IFNULL: {
            result = v0;
            if (v0 == ValueNull.INSTANCE) {
                result = getNullOrValue(session, args, values, 1);
            }
            break;
        }
        case CASEWHEN: {
            Value v;
            if (v0 == ValueNull.INSTANCE || !v0.getBoolean()) {
                v = getNullOrValue(session, args, values, 2);
            } else {
                v = getNullOrValue(session, args, values, 1);
            }
            result = v.convertTo(dataType);
            break;
        }
        case DECODE: {
            int index = -1;
            for (int i = 1; i < args.length - 1; i += 2) {
                if (database.areEqual(v0, getNullOrValue(session, args, values, i))) {
                    index = i + 1;
                    break; // 要加break
                }
            }
            if (index < 0 && args.length % 2 == 0) {
                index = args.length - 1;
            }
            Value v = index < 0 ? ValueNull.INSTANCE : getNullOrValue(session, args, values, index);
            result = v.convertTo(dataType);
            break;
        }
        case NVL2: {
            Value v;
            if (v0 == ValueNull.INSTANCE) {
                v = getNullOrValue(session, args, values, 2);
            } else {
                v = getNullOrValue(session, args, values, 1);
            }
            result = v.convertTo(dataType);
            break;
        }
        case COALESCE: { // 返回第一个不为null的
            result = v0;
            for (int i = 0; i < args.length; i++) {
                Value v = getNullOrValue(session, args, values, i);
                if (!(v == ValueNull.INSTANCE)) {
                    result = v.convertTo(dataType);
                    break;
                }
            }
            break;
        }
        case GREATEST: // 最大的一个
        case LEAST: { // 最小的一个
            result = ValueNull.INSTANCE;
            for (int i = 0; i < args.length; i++) {
                Value v = getNullOrValue(session, args, values, i);
                if (!(v == ValueNull.INSTANCE)) {
                    v = v.convertTo(dataType);
                    if (result == ValueNull.INSTANCE) {
                        result = v;
                    } else {
                        int comp = database.compareTypeSafe(result, v);
                        if (info.type == GREATEST && comp < 0) {
                            result = v;
                        } else if (info.type == LEAST && comp > 0) {
                            result = v;
                        }
                    }
                }
            }
            break;
        }
        case CASE: {
            Expression then = null;
            // 像这样: SELECT SET(@v, 15), CASE WHEN @v<10 THEN 'Low' ELSE 'High' END
            // 此时v0为null
            if (v0 == null) {
                // Searched CASE expression
                // (null, when, then)
                // (null, when, then, else)
                // (null, when, then, when, then)
                // (null, when, then, when, then, else)
                for (int i = 1, len = args.length - 1; i < len; i += 2) {
                    Value when = args[i].getValue(session);
                    if (when.getBoolean()) {
                        then = args[i + 1];
                        break;
                    }
                }
            } else {
                // Simple CASE expression
                // (expr, when, then)
                // (expr, when, then, else)
                // (expr, when, then, when, then)
                // (expr, when, then, when, then, else)
                if (v0 != ValueNull.INSTANCE) {
                    for (int i = 1, len = args.length - 1; i < len; i += 2) {
                        Value when = args[i].getValue(session);
                        if (session.getDatabase().areEqual(v0, when)) {
                            then = args[i + 1];
                            break;
                        }
                    }
                }
            }
            if (then == null && args.length % 2 == 0) {
                // then = elsePart
                then = args[args.length - 1];
            }
            result = then == null ? ValueNull.INSTANCE : then.getValue(session);
            break;
        }
        case ARRAY_GET: {
            if (v0.getType() == Value.ARRAY) {
                int element = v1.getInt(); // 下标从1开始
                Value[] list = ((ValueArray) v0).getList();
                if (element < 1 || element > list.length) {
                    result = ValueNull.INSTANCE;
                } else {
                    result = list[element - 1];
                }
            } else if (v0.getType() == Value.LIST) {
                int element = v1.getInt(); // 下标从1开始
                List<Value> list = ((ValueList) v0).getList();
                if (element < 1 || element > list.size()) {
                    result = ValueNull.INSTANCE;
                } else {
                    result = list.get(element - 1);
                }
            } else if (v0.getType() == Value.MAP) {
                Map<Value, Value> map = ((ValueMap) v0).getMap();
                result = map.get(v1);
                if (result == null)
                    result = ValueNull.INSTANCE;
            } else {
                result = ValueNull.INSTANCE;
            }
            break;
        }
        case ARRAY_CONTAINS: {
            result = ValueBoolean.get(false);
            if (v0.getType() == Value.ARRAY) {
                Value[] list = ((ValueArray) v0).getList();
                if (v1 instanceof ValueArray) {
                    result = ValueBoolean.get(true);
                    Value[] list2 = ((ValueArray) v1).getList();
                    for (int i = 0; i < list2.length; i++) {
                        v1 = list2[i];
                        boolean b = false;
                        for (Value v : list) {
                            if (v.equals(v1)) {
                                b = true;
                                break;
                            }
                        }
                        if (b == false) {
                            result = ValueBoolean.get(false);
                            break;
                        }
                    }
                } else {
                    for (Value v : list) {
                        if (v.equals(v1)) {
                            result = ValueBoolean.get(true);
                            break;
                        }
                    }
                }
            } else if (v0.getType() == Value.LIST) {
                List<Value> list = ((ValueList) v0).getList();
                if (v1 instanceof ValueList) {
                    result = ValueBoolean.get(true);
                    List<Value> list2 = ((ValueList) v1).getList();
                    for (int i = 0; i < list2.size(); i++) {
                        v1 = list2.get(i);
                        boolean b = false;
                        for (Value v : list) {
                            if (v.equals(v1)) {
                                b = true;
                                break;
                            }
                        }
                        if (b == false) {
                            result = ValueBoolean.get(false);
                            break;
                        }
                    }
                } else {
                    for (Value v : list) {
                        if (v.equals(v1)) {
                            result = ValueBoolean.get(true);
                            break;
                        }
                    }
                }
            }
            break;
        }
        default:
            throw getUnsupportedException();
        }
        return result;
    }

    private Sequence getSequence(ServerSession session, Value v0, Value v1) {
        String schemaName, sequenceName;
        if (v1 == null) {
            LealoneSQLParser p = (LealoneSQLParser) session.getParser();
            String sql = v0.getString();
            Expression expr = p.parseExpression(sql);
            if (expr instanceof ExpressionColumn) {
                ExpressionColumn seq = (ExpressionColumn) expr;
                schemaName = seq.getOriginalTableAliasName();
                if (schemaName == null) {
                    schemaName = session.getCurrentSchemaName();
                    sequenceName = sql;
                } else {
                    sequenceName = seq.getColumnName();
                }
            } else {
                throw DbException.getSyntaxError(sql, 1);
            }
        } else {
            schemaName = v0.getString();
            sequenceName = v1.getString();
        }
        Schema s = database.findSchema(session, schemaName);
        if (s == null) {
            schemaName = StringUtils.toUpperEnglish(schemaName);
            s = database.getSchema(session, schemaName);
        }
        Sequence seq = s.findSequence(session, sequenceName);
        if (seq == null) {
            sequenceName = StringUtils.toUpperEnglish(sequenceName);
            seq = s.getSequence(session, sequenceName);
        }
        return seq;
    }

    private static void setCsvDelimiterEscape(Csv csv, String fieldSeparator, String fieldDelimiter,
            String escapeCharacter) {
        if (fieldSeparator != null) {
            csv.setFieldSeparatorWrite(fieldSeparator);
            if (fieldSeparator.length() > 0) {
                char fs = fieldSeparator.charAt(0);
                csv.setFieldSeparatorRead(fs);
            }
        }
        if (fieldDelimiter != null) {
            char fd = fieldDelimiter.length() == 0 ? 0 : fieldDelimiter.charAt(0);
            csv.setFieldDelimiter(fd);
        }
        if (escapeCharacter != null) {
            char ec = escapeCharacter.length() == 0 ? 0 : escapeCharacter.charAt(0);
            csv.setEscapeCharacter(ec);
        }
    }

    @Override
    protected void checkParameterCount(int len) {
        int min = 0, max = Integer.MAX_VALUE;
        switch (info.type) {
        case COALESCE:
        case CSVREAD:
        case LEAST:
        case GREATEST:
            min = 1;
            break;
        case FILE_READ:
        case ARRAY_LENGTH:
            min = 1;
            max = 2;
            break;
        case CASE:
        case CSVWRITE:
            min = 2;
            break;
        case CURRVAL:
        case NEXTVAL:
            min = 1;
            max = 2;
            break;
        case DECODE:
            min = 3;
            break;
        default:
            DbException.throwInternalError("type=" + info.type);
        }
        checkParameterCount(len, min, max);
    }

    @Override
    public Expression optimize(ServerSession session) {
        boolean allConst = optimizeArgs(session);
        int t, s, d;
        long p;
        // args可能是null(有些内置函数没有实参)
        Expression p0 = args == null || args.length < 1 ? null : args[0];
        switch (info.type) {
        case IFNULL:
        case NULLIF:
        case COALESCE:
        case LEAST:
        case GREATEST:
        case DECODE: {
            t = Value.UNKNOWN;
            s = 0;
            p = 0;
            d = 0;
            int i = 0;
            for (Expression e : args) {
                if (info.type == DECODE) {
                    if (i < 2 || ((i % 2 == 1) && (i != args.length - 1))) {
                        // decode(a, b, whenB)
                        // decode(a, b, whenB, else)
                        // decode(a, b, whenB, c, whenC)
                        // decode(a, b, whenB, c, whenC, end)
                        i++;
                        continue;
                    }
                }
                if (e != ValueExpression.getNull()) {
                    int type = e.getType();
                    if (type != Value.UNKNOWN && type != Value.NULL) {
                        t = Value.getHigherOrder(t, type);
                        s = Math.max(s, e.getScale());
                        p = Math.max(p, e.getPrecision());
                        d = Math.max(d, e.getDisplaySize());
                    }
                }
                i++;
            }
            if (info.type == NULLIF) {
                // NULLIF(x, y)等价于CASE WHEN x=y THEN NULL ELSE x END，结果类型取第一个参数
                int type = args[0].getType();
                if (type != Value.UNKNOWN && type != Value.NULL) {
                    t = type;
                    s = args[0].getScale();
                    p = args[0].getPrecision();
                    d = args[0].getDisplaySize();
                }
            }
            if (t == Value.UNKNOWN) {
                t = Value.STRING;
                s = 0;
                p = Integer.MAX_VALUE;
                d = Integer.MAX_VALUE;
            }
            break;
        }
        case CASE: {
            t = args[2].getType();
            p = args[2].getPrecision();
            s = args[2].getScale();
            d = args[2].getDisplaySize();
            for (int i = 3, len = args.length - 1; i < len; i += 2) {
                int index = i + 1;
                t = Value.getHigherOrder(t, args[index].getType());
                p = Math.max(p, args[index].getPrecision());
                s = Math.max(s, args[index].getScale());
                d = Math.max(d, args[index].getDisplaySize());
            }
            if (args.length % 2 == 0) { // elsePart
                int index = args.length - 1;
                t = Value.getHigherOrder(t, args[index].getType());
                p = Math.max(p, args[index].getPrecision());
                s = Math.max(s, args[index].getScale());
                d = Math.max(d, args[index].getDisplaySize());
            }
            break;
        }
        case CASEWHEN:
            t = Value.getHigherOrder(args[1].getType(), args[2].getType());
            p = Math.max(args[1].getPrecision(), args[2].getPrecision());
            d = Math.max(args[1].getDisplaySize(), args[2].getDisplaySize());
            s = Math.max(args[1].getScale(), args[2].getScale());
            break;
        case NVL2:
            switch (args[1].getType()) {
            case Value.STRING:
            case Value.CLOB:
            case Value.STRING_FIXED:
            case Value.STRING_IGNORECASE:
                t = args[1].getType();
                break;
            default:
                t = Value.getHigherOrder(args[1].getType(), args[2].getType());
                break;
            }
            p = Math.max(args[1].getPrecision(), args[2].getPrecision());
            d = Math.max(args[1].getDisplaySize(), args[2].getDisplaySize());
            s = Math.max(args[1].getScale(), args[2].getScale());
            break;
        case CAST:
        case CONVERT:
        case TRUNCATE_VALUE:
            // data type, precision and scale is already set
            t = dataType;
            p = precision;
            s = scale;
            d = displaySize;
            break;
        case SET: {
            Expression p1 = args[1];
            t = p1.getType();
            p = p1.getPrecision();
            s = p1.getScale();
            d = p1.getDisplaySize();
            if (!(p0 instanceof Variable)) {
                throw DbException.get(ErrorCode.CAN_ONLY_ASSIGN_TO_VARIABLE_1, p0.getSQL());
            }
            break;
        }
        case FILE_READ: {
            if (args.length == 1) {
                t = Value.BLOB;
            } else {
                t = Value.CLOB;
            }
            p = Integer.MAX_VALUE;
            s = 0;
            d = Integer.MAX_VALUE;
            break;
        }
        default:
            t = info.dataType;
            DataType type = DataType.getDataType(t);
            p = PRECISION_UNKNOWN;
            d = 0;
            s = type.defaultScale;
        }
        dataType = t;
        precision = p;
        scale = s;
        displaySize = d;
        if (allConst) {
            try {
                Value v = getValue(session);
                if (v == ValueNull.INSTANCE) {
                    if (info.type == CAST || info.type == CONVERT) {
                        return this;
                    }
                }
                // DECIMAL的精度和标度由声明决定，折叠成常量后不能按值的位数缩水
                if (t == Value.DECIMAL) {
                    return ValueExpression.get(v, p, s, d);
                }
                return ValueExpression.get(v);
            } catch (DbException e) {
                // 常量折叠时可能出错，比如 CASE/COALESCE 里不会被求值的分支有除以0，
                // 这时保留原来的表达式，等真正执行到再报错
            }
        }
        return this;
    }

    @Override
    public ValueResultSet getValueForColumnList(ServerSession session, Expression[] args) {
        if (info.type == CSVREAD) {
            String fileName = args[0].getValue(session).getString();
            if (fileName == null) {
                throw DbException.get(ErrorCode.PARAMETER_NOT_SET_1, "fileName");
            }
            String columnList = args.length < 2 ? null : args[1].getValue(session).getString();
            Csv csv = new Csv();
            String options = args.length < 3 ? null : args[2].getValue(session).getString();
            String charset = null;
            if (options != null && options.indexOf('=') >= 0) {
                charset = csv.setOptions(options);
            } else {
                charset = options;
                String fieldSeparatorRead = args.length < 4 ? null
                        : args[3].getValue(session).getString();
                String fieldDelimiter = args.length < 5 ? null : args[4].getValue(session).getString();
                String escapeCharacter = args.length < 6 ? null : args[5].getValue(session).getString();
                setCsvDelimiterEscape(csv, fieldSeparatorRead, fieldDelimiter, escapeCharacter);
            }
            char fieldSeparator = csv.getFieldSeparatorRead();
            String[] columns = StringUtils.arraySplit(columnList, fieldSeparator);
            ResultSet rs = null;
            ValueResultSet x;
            try {
                rs = csv.read(fileName, columns, charset);
                x = ValueResultSet.getCopy(rs, 0);
            } catch (SQLException e) {
                throw DbException.convert(e);
            } finally {
                JdbcUtils.closeSilently(rs);
            }
            return x;
        }
        return super.getValueForColumnList(session, args);
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append(info.name);
        if (info.type == CASE) {
            if (args[0] != null) {
                sql.append(' ');
                args[0].getSQL(sql);
            }
            for (int i = 1, len = args.length - 1; i < len; i += 2) {
                sql.append(" WHEN ");
                args[i].getSQL(sql);
                sql.append(" THEN ");
                args[i + 1].getSQL(sql);
            }
            if (args.length % 2 == 0) {
                sql.append(" ELSE ");
                args[args.length - 1].getSQL(sql);
            }
            sql.append(" END");
            return;
        }
        sql.append('(');
        switch (info.type) {
        case CAST: {
            args[0].getSQL(sql);
            sql.append(" AS ");
            sql.append(new Column(null, dataType, precision, scale, displaySize).getCreateSQL());
            break;
        }
        case CONVERT: {
            args[0].getSQL(sql);
            sql.append(',');
            sql.append(new Column(null, dataType, precision, scale, displaySize).getCreateSQL());
            break;
        }
        default:
            appendArgs(sql);
        }
        sql.append(')');
    }

    @Override
    boolean isBufferResultSetToLocalTemp() {
        return info.type == CSVREAD;
    }
}
