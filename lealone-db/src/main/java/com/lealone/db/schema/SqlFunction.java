/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.MathUtils;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.db.Constants;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.auth.User;
import com.lealone.db.result.SimpleResultSet;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueResultSet;
import com.lealone.sql.IExpression;

/**
 * 用一条SQL查询语句作为函数体的用户自定义函数，对应PostgreSQL风格的CREATE FUNCTION。
 * 函数体在创建时已把参数名替换成'?'，运行时按出现顺序绑定参数值。
 * 标量函数取结果集第一行第一列作为返回值，没有行时返回NULL；
 * RETURNS SETOF/RETURNS TABLE声明的函数返回整个结果集，可以在FROM子句里当表函数用。
 *
 * 参数支持PostgreSQL的IN/OUT/INOUT模式：
 * OUT/INOUT参数组成函数返回的行，只有一个这样的参数时函数返回一个标量值，
 * 有多个时函数返回一个只含一行的结果集(可以在FROM子句里当成表使用)；
 * 存在OUT/INOUT参数时可以省略RETURNS子句。
 * RETURNS SETOF/RETURNS TABLE和OUT/INOUT参数不能同时使用(和PostgreSQL一致)。
 * 函数体也可以写成LANGUAGE plpgsql的PL/pgSQL块，这时由子类PlpgSqlFunction解释执行。
 */
public class SqlFunction extends FunctionAlias {

    /** 普通入参 */
    public static final int PARAM_IN = 0;
    /** 只作为返回列的出参，调用时不需要传值 */
    public static final int PARAM_OUT = 1;
    /** 既是入参也是返回列 */
    public static final int PARAM_INOUT = 2;

    /** 易变性关键字，PostgreSQL的默认值是VOLATILE */
    public static final String VOLATILE = "VOLATILE";
    public static final String STABLE = "STABLE";
    public static final String IMMUTABLE = "IMMUTABLE";

    // JavaMethod的构造器要求一个Method对象，
    // 这里只是借用它计算默认字段，getValue等方法都已被覆盖
    private static final Method DUMMY_METHOD = getDummyMethod();

    private String[] parameterNames; // 全部参数(按声明顺序)
    private String[] parameterTypes; // 参数的SQL类型文本
    private int[] parameterTypeIds; // 对应Value的类型
    private int[] parameterComponentTypes; // 数组参数的元素类型，-1表示不是数组
    private int[] parameterModes; // PARAM_IN/PARAM_OUT/PARAM_INOUT
    private int[] inputIndexes; // 入参在参数列表中的下标
    private int[] outputIndexes; // 返回列对应的参数下标
    private int inputParameterCount;
    private boolean variadic;
    private boolean strict;

    private String returnTypeSql;
    private int returnType;
    private boolean hasDeclaredReturnType;

    // RETURNS SETOF/RETURNS TABLE声明的结果列，标量函数时都为null
    private boolean returnsTable;
    private String[] columnNames;
    private String[] columnTypeSqls;
    private int[] columnTypes;
    private long[] columnPrecisions;
    private int[] columnScales;

    private String language = "SQL";
    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;

    // ---- PostgreSQL的函数属性，都可以通过ALTER FUNCTION修改 ----
    private String volatility = VOLATILE;
    private boolean securityDefiner;
    private boolean leakproof;
    private boolean window;
    private Double cost;
    private Double rows;
    private String ownerName;
    // 每个元素是{参数名, 值}，值可能为null(表示DEFAULT)
    private final ArrayList<String[]> configItems = new ArrayList<>();

    private JavaMethod sqlJavaMethod;

    public SqlFunction(Schema schema, int id, String name) {
        super(schema, id, name);
    }

    private static Method getDummyMethod() {
        try {
            return SqlFunction.class.getDeclaredMethod("dummyMethod");
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    private static void dummyMethod() {
    }

    public void setParameters(String[] parameterNames, String[] parameterTypes, int[] parameterTypeIds,
            int[] parameterComponentTypes, int[] parameterModes, boolean variadic) {
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.parameterTypeIds = parameterTypeIds;
        this.parameterComponentTypes = parameterComponentTypes;
        this.parameterModes = parameterModes;
        this.variadic = variadic;

        int inputCount = 0;
        int outputCount = 0;
        for (int mode : parameterModes) {
            if (mode != PARAM_OUT) {
                inputCount++;
            }
            if (mode != PARAM_IN) {
                outputCount++;
            }
        }
        inputIndexes = new int[inputCount];
        outputIndexes = new int[outputCount];
        int i = 0;
        int o = 0;
        for (int p = 0; p < parameterModes.length; p++) {
            if (parameterModes[p] != PARAM_OUT) {
                inputIndexes[i++] = p;
            }
            if (parameterModes[p] != PARAM_IN) {
                outputIndexes[o++] = p;
            }
        }
        inputParameterCount = inputCount;
    }

    /**
     * 设置返回类型。当有OUT/INOUT参数时 returnTypeSql 可以为null，由OUT参数推导。
     */
    public void setReturnType(String returnTypeSql, int returnType) {
        this.hasDeclaredReturnType = returnTypeSql != null;
        if (hasDeclaredReturnType) {
            this.returnTypeSql = returnTypeSql;
            this.returnType = returnType;
        }
        // RETURNS SETOF/RETURNS TABLE声明的结果列，和OUT/INOUT参数不能同时用
        boolean setReturning = isSetReturning();
        if (outputIndexes.length == 1) {
            int index = outputIndexes[0];
            if (setReturning) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "RETURNS SETOF/TABLE can not be used with OUT parameter: "
                                + parameterNames[index]);
            }
            int outType = parameterTypeIds[index];
            if (hasDeclaredReturnType && returnType != outType) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1,
                        "RETURNS type does not match OUT parameter: " + parameterNames[index]);
            }
            this.returnType = outType;
            this.returnTypeSql = parameterTypes[index];
        } else if (outputIndexes.length > 1) {
            if (hasDeclaredReturnType || setReturning) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "RETURNS can not be used with multiple OUT parameters");
            }
            // 多个OUT参数组成返回行，用只含一行/多列的结果集表示
            this.returnType = Value.RESULT_SET;
            this.returnTypeSql = null;
        } else if (!hasDeclaredReturnType && !setReturning) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1,
                    "RETURNS is required when there is no OUT parameter");
        }
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public boolean isStrict() {
        return strict;
    }

    public String getVolatility() {
        return volatility;
    }

    /**
     * 设置易变性。IMMUTABLE/STABLE都等价于deterministic=true，
     * 但持久化时保留用户写的那个关键字，这样SCRIPT回放不丢属性。
     */
    public void setVolatility(String volatility) {
        if (volatility == null) {
            volatility = VOLATILE;
        }
        this.volatility = volatility;
        setDeterministic(!VOLATILE.equalsIgnoreCase(volatility));
    }

    public boolean isSecurityDefiner() {
        return securityDefiner;
    }

    public void setSecurityDefiner(boolean securityDefiner) {
        this.securityDefiner = securityDefiner;
    }

    public boolean isLeakproof() {
        return leakproof;
    }

    public void setLeakproof(boolean leakproof) {
        this.leakproof = leakproof;
    }

    public boolean isWindow() {
        return window;
    }

    public void setWindow(boolean window) {
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

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public ArrayList<String[]> getConfigItems() {
        return configItems;
    }

    public void addConfigItem(String name, String value) {
        configItems.add(new String[] { name, value });
    }

    public void removeConfigItem(String name) {
        for (int i = configItems.size() - 1; i >= 0; i--) {
            if (configItems.get(i)[0].equalsIgnoreCase(name)) {
                configItems.remove(i);
            }
        }
    }

    public void clearConfigItems() {
        configItems.clear();
    }

    /**
     * 判断函数是否匹配DROP FUNCTION/ALTER FUNCTION里写的参数类型列表。
     * 不支持重载，所以只比较入参的类型；没写参数类型列表时总是匹配。
     */
    public boolean matchesSignature(int[] argumentTypeIds) {
        if (argumentTypeIds == null) {
            return true;
        }
        if (argumentTypeIds.length != inputParameterCount) {
            return false;
        }
        for (int i = 0; i < argumentTypeIds.length; i++) {
            if (argumentTypeIds[i] != parameterTypeIds[inputIndexes[i]]) {
                return false;
            }
        }
        return true;
    }

    // 用于报错信息的签名，例如 F(INT, VARCHAR)
    public String getSignature() {
        StatementBuilder buff = new StatementBuilder(getName()).append('(');
        for (int i = 0; i < inputParameterCount; i++) {
            int index = inputIndexes[i];
            buff.appendExceptFirst(", ");
            if (parameterTypes != null && index < parameterTypes.length) {
                buff.append(parameterTypes[index]);
            } else {
                buff.append(DataType.getDataType(parameterTypeIds[index]).name);
            }
        }
        return buff.append(')').toString();
    }

    @Override
    public void checkRename() {
        // 支持ALTER FUNCTION ... RENAME TO
    }

    /**
     * 设置RETURNS SETOF/RETURNS TABLE声明的结果列。
     *
     * @param returnsTable true表示RETURNS TABLE，false表示RETURNS SETOF
     * @param columnNames 结果列名，RETURNS SETOF时为null，列名就是函数名
     * @param columnTypeSqls 结果列的SQL类型，用于持久化
     * @param columnTypes 结果列的类型
     * @param columnPrecisions 结果列的精度
     * @param columnScales 结果列的scale
     */
    public void setReturnColumns(boolean returnsTable, String[] columnNames,
            String[] columnTypeSqls, int[] columnTypes, long[] columnPrecisions, int[] columnScales) {
        this.returnsTable = returnsTable;
        this.columnNames = columnNames;
        this.columnTypeSqls = columnTypeSqls;
        this.columnTypes = columnTypes;
        this.columnPrecisions = columnPrecisions;
        this.columnScales = columnScales;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "SQL" : language;
    }

    protected String getBody() {
        return body;
    }

    protected String[] getParameterNames() {
        return parameterNames;
    }

    protected String[] getParameterTypes() {
        return parameterTypes;
    }

    protected int getReturnType() {
        return returnType;
    }

    /**
     * 设置函数体。
     *
     * @param body 原始的SQL函数体，用于持久化
     * @param bodySql 参数名已替换成'?'的函数体
     * @param bodyParameterIndexes bodySql中每个'?'对应的参数下标
     */
    public void setBody(String body, String bodySql, int[] bodyParameterIndexes) {
        this.body = body;
        this.bodySql = bodySql;
        this.bodyParameterIndexes = bodyParameterIndexes;
    }

    @Override
    public boolean isSetReturning() {
        return columnTypes != null;
    }

    @Override
    public JavaMethod[] getJavaMethods() {
        return new JavaMethod[] { getSqlJavaMethod() };
    }

    @Override
    public JavaMethod findJavaMethod(IExpression[] args) {
        // 有VARIADIC参数时最后一个参数可以展开成任意多个实参
        int minCount = variadic ? inputParameterCount - 1 : inputParameterCount;
        if (variadic ? args.length < minCount : args.length != minCount) {
            throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1,
                    getName() + " (parameter count: " + args.length + ")");
        }
        return getSqlJavaMethod();
    }

    private synchronized JavaMethod getSqlJavaMethod() {
        if (sqlJavaMethod == null) {
            sqlJavaMethod = new SqlJavaMethod();
        }
        return sqlJavaMethod;
    }

    @Override
    public String getCreateSQL() {
        StatementBuilder buff = new StatementBuilder("CREATE FUNCTION ");
        buff.append(getSQL()).append('(');
        for (int i = 0; i < parameterNames.length; i++) {
            buff.appendExceptFirst(", ");
            if (parameterModes[i] == PARAM_OUT) {
                buff.append("OUT ");
            } else if (parameterModes[i] == PARAM_INOUT) {
                buff.append("INOUT ");
            }
            if (variadic && i == parameterNames.length - 1) {
                buff.append("VARIADIC ");
            }
            buff.append(database.quoteIdentifier(parameterNames[i])).append(' ');
            buff.append(parameterTypes[i]);
        }
        buff.append(')');
        // 有OUT/INOUT参数时RETURNS可以省略，返回类型由参数推导
        if (outputIndexes.length == 0) {
            buff.append(" RETURNS ");
            if (isSetReturning()) {
                if (returnsTable) {
                    buff.append("TABLE(");
                    buff.resetCount();
                    for (int i = 0; i < columnNames.length; i++) {
                        buff.appendExceptFirst(", ");
                        buff.append(database.quoteIdentifier(columnNames[i])).append(' ');
                        buff.append(columnTypeSqls[i]);
                    }
                    buff.append(')');
                } else {
                    buff.append("SETOF ").append(columnTypeSqls[0]);
                }
            } else {
                buff.append(returnTypeSql);
            }
        }
        // SQL语言的函数保持原来的DDL格式，过程式语言要显式写出LANGUAGE
        if (!"SQL".equalsIgnoreCase(language)) {
            buff.append(" LANGUAGE ").append(language);
        }
        buff.append(' ').append(volatility);
        // 显式写出STRICT的反面，保证CALLED ON NULL INPUT也能持久化
        buff.append(strict ? " STRICT" : " CALLED ON NULL INPUT");
        if (securityDefiner) {
            buff.append(" SECURITY DEFINER");
        }
        if (leakproof) {
            buff.append(" LEAKPROOF");
        }
        if (window) {
            buff.append(" WINDOW");
        }
        if (cost != null) {
            buff.append(" COST ").append(formatNumber(cost));
        }
        if (rows != null) {
            buff.append(" ROWS ").append(formatNumber(rows));
        }
        for (String[] item : configItems) {
            buff.append(" SET ").append(database.quoteIdentifier(item[0])).append(" = ");
            if (item[1] != null) {
                buff.append(item[1]);
            } else {
                buff.append("DEFAULT");
            }
        }
        buff.append(" AS ").append(StringUtils.quoteStringSQL(body));
        // 属主不是CREATE FUNCTION的标准子句，放在AS之后，只记录不生效
        if (ownerName != null) {
            buff.append(" OWNER TO ").append(database.quoteIdentifier(ownerName));
        }
        return buff.toString();
    }

    // COST/ROWS是浮点数，整数时不带小数点，读起来和PostgreSQL的DDL一致
    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    @Override
    public String getDropSQL() {
        return "DROP FUNCTION IF EXISTS " + getSQL();
    }

    @Override
    public void invalidate() {
        sqlJavaMethod = null;
        super.invalidate();
    }

    private Value executeBody(ServerSession session, IExpression[] args, boolean columnList) {
        boolean setReturning = isSetReturning();
        // setof/table和多个OUT参数都返回结果集
        boolean rowReturning = setReturning || returnType == Value.RESULT_SET;
        if (columnList && rowReturning) {
            // 只需要返回列的定义，不需要真正执行函数体
            return createEmptyResultSet();
        }
        Value[] values = new Value[parameterNames.length];
        Arrays.fill(values, ValueNull.INSTANCE);
        if (variadic) {
            int fixedCount = inputParameterCount - 1;
            for (int i = 0; i < fixedCount; i++) {
                int index = inputIndexes[i];
                values[index] = convertArgument(index, args[i].getValue(session));
            }
            values[inputIndexes[fixedCount]] = getVariadicValue(session, args, fixedCount);
        } else {
            for (int i = 0; i < inputParameterCount; i++) {
                int index = inputIndexes[i];
                values[index] = convertArgument(index, args[i].getValue(session));
            }
        }
        if (strict) {
            // 任一入参为NULL时不执行函数体, 直接返回NULL
            for (int index : inputIndexes) {
                if (values[index] == ValueNull.INSTANCE) {
                    // 返回结果集的函数给空结果集, 否则FROM里会拿到NULL
                    return rowReturning ? createEmptyResultSet() : ValueNull.INSTANCE;
                }
            }
        }
        return executeFunction(session, values);
    }

    /**
     * 执行函数体，SQL语言的函数体由子类之外的默认实现执行，
     * PL/pgSQL等过程式语言由子类覆盖这个方法。
     */
    protected Value executeFunction(ServerSession session, Value[] values) {
        Connection conn = createNestedConnection(session);
        try {
            // 函数体里的表名按调用方当前的schema解析
            String schemaName = session.getCurrentSchemaName();
            if (!Constants.SCHEMA_MAIN.equals(schemaName)) {
                conn.setSchema(schemaName);
            }
            PreparedStatement prep = conn.prepareStatement(bodySql);
            try {
                for (int i = 0; i < bodyParameterIndexes.length; i++) {
                    prep.setObject(i + 1, values[bodyParameterIndexes[i]]);
                }
                ResultSet rs = prep.executeQuery();
                try {
                    if (isSetReturning()) {
                        return readSetResult(session, rs);
                    }
                    if (returnType == Value.RESULT_SET) {
                        return createRecord(session, rs);
                    }
                    // VOID不关心函数体返回什么
                    if (returnType != Value.NULL) {
                        int columnCount = rs.getMetaData().getColumnCount();
                        if (columnCount != 1) {
                            // 不能静默地只取第一列，PostgreSQL在建函数时就会报类型不匹配
                            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1,
                                    "function " + getName() + " must return one column, but returns "
                                            + columnCount);
                        }
                    }
                    if (!rs.next()) {
                        return ValueNull.INSTANCE;
                    }
                    Value v = DataType.convertToValue(session, rs.getObject(1), returnType);
                    return v.convertTo(returnType);
                } finally {
                    rs.close();
                }
            } finally {
                prep.close();
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }

    /**
     * SECURITY DEFINER的函数体以属主的身份执行(属主默认是创建者)，其它情况还是以调用者的身份执行。
     */
    protected Connection createNestedConnection(ServerSession session) {
        if (securityDefiner) {
            return session.createNestedConnection(getOwnerUser(session), false);
        }
        return session.createNestedConnection(false);
    }

    protected ServerSession createNestedSession(ServerSession session) {
        if (securityDefiner) {
            return session.createNestedSession(getOwnerUser(session));
        }
        return session.createNestedSession();
    }

    private User getOwnerUser(ServerSession session) {
        String owner = ownerName != null ? ownerName : session.getUser().getName();
        return session.getDatabase().getUser(session, owner);
    }

    /**
     * 把setof/table函数体的结果集整个读出来。
     */
    private Value readSetResult(ServerSession session, ResultSet rs) throws SQLException {
        SimpleResultSet simple = createSimpleResultSet();
        // 和PostgreSQL一致: RETURNS SETOF VOID不返回任何行, 也不关心函数体返回什么
        if (columnTypes.length == 1 && columnTypes[0] == Value.NULL) {
            return ValueResultSet.get(simple);
        }
        int columnCount = rs.getMetaData().getColumnCount();
        if (columnCount != columnTypes.length) {
            throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
        }
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                Value v = DataType.convertToValue(session, rs.getObject(i + 1), columnTypes[i]);
                v = v.convertTo(columnTypes[i]);
                row[i] = v.getObject();
            }
            simple.addRow(row);
        }
        return ValueResultSet.get(simple);
    }

    private SimpleResultSet createSimpleResultSet() {
        SimpleResultSet simple = new SimpleResultSet();
        // 结果集可能被读取多次(比如CALL和函数表的缓存)，读到最后一行时不要自动关闭
        simple.setAutoClose(false);
        for (int i = 0; i < columnTypes.length; i++) {
            simple.addColumn(getColumnName(i), DataType.convertTypeToSQLType(columnTypes[i]),
                    MathUtils.convertLongToInt(columnPrecisions[i]), columnScales[i]);
        }
        return simple;
    }

    private String getColumnName(int i) {
        // RETURNS SETOF没有声明列名，和PostgreSQL一样用函数名
        return columnNames == null ? getName() : columnNames[i];
    }

    // 把VARIADIC参数对应的多个实参打包成一个数组值
    private Value getVariadicValue(ServerSession session, IExpression[] args, int fixedCount) {
        int parameterIndex = inputIndexes[fixedCount];
        int componentType = parameterComponentTypes[parameterIndex];
        int extraCount = args.length - fixedCount;
        if (extraCount > 0) {
            Value first = args[fixedCount].getValue(session);
            // 只传一个数组值时直接当成数组用, 不再多包一层
            if (extraCount == 1 && first.getType() == Value.ARRAY) {
                return convertArgument(parameterIndex, first);
            }
            Value[] list = new Value[extraCount];
            list[0] = convertValue(parameterIndex, first, componentType);
            for (int i = 1; i < extraCount; i++) {
                list[i] = convertValue(parameterIndex, args[fixedCount + i].getValue(session),
                        componentType);
            }
            return ValueArray.get(list);
        }
        return ValueArray.get(new Value[0]);
    }

    /**
     * 按参数声明的类型转换实参的值。PostgreSQL在函数调用时按声明类型做隐式转换，
     * 类型不兼容时报错；这里用DATA_CONVERSION_ERROR_1表示这种错误。
     */
    private Value convertArgument(int parameterIndex, Value v) {
        int type = parameterTypeIds[parameterIndex];
        int componentType = parameterComponentTypes[parameterIndex];
        if (type == Value.ARRAY) {
            if (componentType < 0 || componentType == Value.UNKNOWN || v == ValueNull.INSTANCE) {
                return v;
            }
            if (v.getType() != Value.ARRAY) {
                return convertValue(parameterIndex, v, type);
            }
            Value[] list = ((ValueArray) v).getList();
            Value[] converted = new Value[list.length];
            for (int i = 0; i < list.length; i++) {
                converted[i] = convertValue(parameterIndex, list[i], componentType);
            }
            return ValueArray.get(converted);
        }
        return convertValue(parameterIndex, v, type);
    }

    private Value convertValue(int parameterIndex, Value v, int targetType) {
        if (v == ValueNull.INSTANCE || targetType <= Value.NULL || targetType == Value.UNKNOWN
                || v.getType() == targetType) {
            return v;
        }
        try {
            return v.convertTo(targetType);
        } catch (DbException e) {
            String message = "parameter " + parameterNames[parameterIndex] + ": can not convert "
                    + v.getSQL() + " (" + DataType.getDataType(v.getType()).name + ") to "
                    + parameterTypes[parameterIndex];
            throw DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1, e, message);
        }
    }

    // 多个OUT参数时，把函数体返回的第一行包装成一个结果集
    private Value createRecord(ServerSession session, ResultSet rs) throws SQLException {
        SimpleResultSet simple = createResultSet();
        if (rs.next()) {
            Object[] row = new Object[outputIndexes.length];
            for (int i = 0; i < outputIndexes.length; i++) {
                int type = parameterTypeIds[outputIndexes[i]];
                Value v = DataType.convertToValue(session, rs.getObject(i + 1), type);
                row[i] = v.convertTo(type).getObject();
            }
            simple.addRow(row);
        }
        return ValueResultSet.get(simple);
    }

    private Value createEmptyResultSet() {
        // setof/table用声明的结果列，多个OUT参数用参数名作为列名
        return ValueResultSet.get(isSetReturning() ? createSimpleResultSet() : createResultSet());
    }

    private SimpleResultSet createResultSet() {
        SimpleResultSet simple = new SimpleResultSet();
        for (int index : outputIndexes) {
            DataType dataType = DataType.getDataType(parameterTypeIds[index]);
            simple.addColumn(parameterNames[index], dataType.sqlType, (int) dataType.defaultPrecision,
                    dataType.defaultScale);
        }
        return simple;
    }

    private final class SqlJavaMethod extends JavaMethod {

        SqlJavaMethod() {
            super(DUMMY_METHOD, 0);
        }

        @Override
        public int getParameterCount() {
            return inputParameterCount;
        }

        @Override
        public int getDataType() {
            return isSetReturning() ? Value.RESULT_SET : returnType;
        }

        @Override
        public boolean hasConnectionParam() {
            return false;
        }

        @Override
        public Class<?>[] getColumnClasses() {
            Class<?>[] classes = new Class<?>[inputParameterCount];
            Arrays.fill(classes, Value.class);
            return classes;
        }

        @Override
        public Value getValue(ServerSession session, IExpression[] args, boolean columnList) {
            return executeBody(session, args, columnList);
        }
    }
}
