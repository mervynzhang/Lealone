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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
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
    // RETURNS RECORD：返回行由OUT/INOUT参数定义
    private boolean returnsRecord;
    // RETURNS SETOF <表名>时保存表名用于getCreateSQL
    private String setofTableName;
    private String[] columnNames;
    private String[] columnTypeSqls;
    private int[] columnTypes;
    private long[] columnPrecisions;
    private int[] columnScales;

    private String language = "SQL";
    private String body;
    private String bodySql;
    private int[] bodyParameterIndexes;
    // BEGIN ATOMIC ... END函数体，每条语句一个bodySql
    private String[] atomicBodySqls;
    private int[][] atomicBodyParameterIndexes;

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
        } catch (NoSuchMethodException | SecurityException e) {
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
        // RETURNS RECORD的行结构由OUT/INOUT参数决定
        if (returnsRecord) {
            boolean setReturning = isSetReturning();
            if (setReturning) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "RETURNS RECORD cannot be used with SETOF/TABLE");
            }
            if (outputIndexes.length == 0) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1,
                        "RETURNS RECORD requires OUT parameters");
            }
            // record在值上下文里用ValueArray表示，在FROM里展开成OUT参数列
            this.returnType = Value.ARRAY;
            this.returnTypeSql = "RECORD";
            this.hasDeclaredReturnType = true;
            return;
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
            // 多个OUT参数组成一行record, 值用ValueArray表示(在FROM里仍然展开成多列)
            this.returnType = Value.ARRAY;
            this.returnTypeSql = null;
        } else if (!hasDeclaredReturnType && !setReturning) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1,
                    "RETURNS is required when there is no OUT parameter");
        }
    }

    public void setReturnsRecord(boolean returnsRecord) {
        this.returnsRecord = returnsRecord;
    }

    public void setSetofTableName(String setofTableName) {
        this.setofTableName = setofTableName;
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
     * 只比较入参的类型；没写参数类型列表时总是匹配。
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

    /**
     * 同名函数按参数类型重载，所以schema里存函数对象的key要带上入参签名。
     * 参数类型相同的函数对应同一个key，不同时各占一个key。
     */
    public String getOverloadKey() {
        return getOverloadKey(getName());
    }

    public String getOverloadKey(String name) {
        if (parameterTypeIds == null) {
            return name;
        }
        StatementBuilder buff = new StatementBuilder(name).append('(');
        for (int i = 0; i < inputParameterCount; i++) {
            int index = inputIndexes[i];
            buff.appendExceptFirst(",");
            buff.append(parameterTypeIds[index]);
            if (parameterTypeIds[index] == Value.ARRAY) {
                buff.append('/').append(parameterComponentTypes[index]);
            }
        }
        return buff.append(')').toString();
    }

    /**
     * 入参的类型id列表，用于重载消解和按签名查找函数。
     */
    public int[] getInputParameterTypeIds() {
        int[] types = new int[inputParameterCount];
        for (int i = 0; i < inputParameterCount; i++) {
            types[i] = parameterTypeIds[inputIndexes[i]];
        }
        return types;
    }

    /**
     * 数组入参的元素类型，非数组参数为-1。
     */
    public int getInputParameterComponentType(int inputIndex) {
        return parameterComponentTypes[inputIndexes[inputIndex]];
    }

    public int getInputParameterCount() {
        return inputParameterCount;
    }

    public boolean isVariadic() {
        return variadic;
    }

    /** 实参类型和参数类型完全一致，匹配程度最高 */
    public static final int MATCH_EXACT = 0;
    /** 实参类型可以隐式转成参数类型(PostgreSQL的castcontext=implicit) */
    public static final int MATCH_IMPLICIT = 1;
    /** 实参是没有类型的字面量(比如'5')或者NULL，由参数类型决定它的类型 */
    public static final int MATCH_UNTYPED = 2;
    /** 类型不兼容，需要显式CAST，这个函数不可能是被调用的重载 */
    public static final int MATCH_NONE = -1;

    /**
     * 计算实参类型和函数声明的匹配程度，返回值越小匹配越好，MATCH_NONE表示不匹配。
     * 实参个数必须和入参个数一致，VARIADIC参数可以展开成任意多个实参。
     */
    public int getMatchScore(int[] argumentTypeIds) {
        if (argumentTypeIds == null || parameterTypeIds == null) {
            return MATCH_NONE;
        }
        int fixedCount = variadic ? inputParameterCount - 1 : inputParameterCount;
        if (variadic ? argumentTypeIds.length < fixedCount : argumentTypeIds.length != fixedCount) {
            return MATCH_NONE;
        }
        int score = 0;
        for (int i = 0; i < argumentTypeIds.length; i++) {
            int index = i < fixedCount ? inputIndexes[i] : inputIndexes[fixedCount];
            int parameterType = parameterTypeIds[index];
            // VARIADIC展开出来的实参匹配的是数组元素类型
            if (variadic && i >= fixedCount) {
                parameterType = parameterComponentTypes[index];
            }
            int match = getTypeMatch(argumentTypeIds[i], parameterType);
            if (match == MATCH_NONE) {
                return MATCH_NONE;
            }
            score += match;
        }
        return score;
    }

    /**
     * 计算一个实参类型和一个参数类型的匹配程度，过程和函数共用同一套规则。
     */
    public static int getTypeMatch(int argumentType, int parameterType) {
        if (argumentType == parameterType) {
            return MATCH_EXACT;
        }
        // 实参没有类型(NULL或者字符串字面量)时由参数类型决定，
        // 和PostgreSQL一样优先选字符串类型的重载
        if (argumentType == Value.UNKNOWN || argumentType == Value.NULL) {
            // PostgreSQL的text在Lealone里映射成CLOB，所以CLOB按字符串类型对待
            return isStringLikeType(parameterType) ? MATCH_IMPLICIT : MATCH_UNTYPED;
        }
        if (parameterType == Value.UNKNOWN || parameterType == Value.NULL) {
            return MATCH_UNTYPED;
        }
        if (isImplicitConvertible(argumentType, parameterType)) {
            return MATCH_IMPLICIT;
        }
        return MATCH_NONE;
    }

    /**
     * 实参类型能否隐式转成参数类型。只覆盖PostgreSQL里castcontext为implicit的类型对，
     * 比如整数加宽、数值转浮点、字符串类型之间、DATE转TIMESTAMP；
     * 而 int转varchar、varchar转int、boolean转int 这些在PostgreSQL里都不是隐式转换，
     * 必须显式CAST，否则函数调用会报函数不存在。
     */
    public static boolean isImplicitConvertible(int fromType, int toType) {
        if (fromType == toType) {
            return true;
        }
        // 字符串类型之间可以隐式转换，比如VARCHAR到CHAR；PostgreSQL的text映射成CLOB，也归到这一类
        if (isStringLikeType(fromType) && isStringLikeType(toType)) {
            return true;
        }
        // DECIMAL在Lealone里还兼作"数值类型还没推导出来"的默认类型(比如函数体里没类型的
        // 参数占位符参与运算时就是DECIMAL)，所以DECIMAL转其它数值类型也按隐式转换处理
        if (fromType == Value.DECIMAL && isNumericType(toType)) {
            return true;
        }
        switch (toType) {
        // 整数只能往更宽的类型转
        case Value.SHORT:
            return fromType == Value.BYTE;
        case Value.INT:
            return fromType == Value.BYTE || fromType == Value.SHORT;
        case Value.LONG:
            return fromType == Value.BYTE || fromType == Value.SHORT || fromType == Value.INT;
        // 整数和REAL可以隐式转成DECIMAL/DOUBLE
        case Value.DECIMAL:
            return isIntegerType(fromType) || fromType == Value.FLOAT;
        case Value.DOUBLE:
            return isIntegerType(fromType) || fromType == Value.FLOAT || fromType == Value.DECIMAL;
        case Value.FLOAT:
            return isIntegerType(fromType);
        // PostgreSQL允许DATE隐式转TIMESTAMP，反过来不行
        case Value.TIMESTAMP:
            return fromType == Value.DATE;
        default:
            return false;
        }
    }

    private static boolean isStringLikeType(int type) {
        return DataType.isStringType(type) || type == Value.CLOB;
    }

    private static boolean isIntegerType(int type) {
        return type == Value.BYTE || type == Value.SHORT || type == Value.INT || type == Value.LONG;
    }

    private static boolean isNumericType(int type) {
        return isIntegerType(type) || type == Value.DECIMAL || type == Value.DOUBLE
                || type == Value.FLOAT;
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

    protected int[] getParameterModes() {
        return parameterModes;
    }

    protected int[] getOutputIndexes() {
        return outputIndexes;
    }

    protected boolean isReturnsTable() {
        return returnsTable;
    }

    protected String[] getReturnColumnNames() {
        return columnNames;
    }

    protected int[] getReturnColumnTypes() {
        return columnTypes;
    }

    protected long[] getReturnColumnPrecisions() {
        return columnPrecisions;
    }

    protected int[] getReturnColumnScales() {
        return columnScales;
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

    /**
     * BEGIN ATOMIC ... END函数体：多条语句顺序执行，最后一条查询的结果就是返回值。
     *
     * @param bodySqls 每条语句的SQL，参数名已经替换成'?'
     * @param bodyParameterIndexes 每条语句里每个'?'对应的参数下标
     */
    public void setAtomicBody(String[] bodySqls, int[][] bodyParameterIndexes) {
        this.atomicBodySqls = bodySqls;
        this.atomicBodyParameterIndexes = bodyParameterIndexes;
    }

    public boolean isAtomicBody() {
        return atomicBodySqls != null;
    }

    @Override
    public boolean isSetReturning() {
        return columnTypes != null;
    }

    @Override
    public boolean isRecordReturning() {
        // RETURNS RECORD和多个OUT/INOUT参数都用ValueArray表示一行record
        return returnsRecord || outputIndexes.length > 1;
    }

    protected boolean isReturnsRecord() {
        return returnsRecord;
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
        // 有OUT/INOUT参数时RETURNS可以省略，返回类型由参数推导；
        // RETURNS RECORD虽然由OUT参数定义形状，但要把RECORD关键字写回去才能回放
        if (outputIndexes.length > 0 && returnsRecord) {
            buff.append(" RETURNS RECORD");
        } else if (outputIndexes.length == 0) {
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
                    buff.append("SETOF ");
                    if (setofTableName != null)
                        buff.append(setofTableName);
                    else
                        buff.append(columnTypeSqls[0]);
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
        if (atomicBodySqls == null) {
            buff.append(" AS ").append(StringUtils.quoteStringSQL(body));
        } else {
            // SQL标准的函数体原样写回去，SCRIPT回放时还能解析成多条语句;
            // 每条语句都要有分号(和PostgreSQL一样)，否则END会被当成上一个语句的别名
            buff.append(" BEGIN ATOMIC ");
            if (!body.isEmpty()) {
                buff.append(body).append("; ");
            }
            buff.append("END");
        }
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

    // 同名函数可能有多个重载，DROP语句必须写上参数类型，否则SCRIPT回放时删不掉指定的重载
    @Override
    public String getDropSQL() {
        return "DROP FUNCTION IF EXISTS " + getSQL() + '(' + getInputParameterTypesSQL() + ')';
    }

    // DROP FUNCTION name(types)里的参数类型列表，空列表表示没有参数
    private String getInputParameterTypesSQL() {
        StatementBuilder buff = new StatementBuilder();
        for (int i = 0; i < inputParameterCount; i++) {
            int index = inputIndexes[i];
            buff.appendExceptFirst(", ");
            if (parameterTypes != null && index < parameterTypes.length) {
                buff.append(parameterTypes[index]);
            } else {
                buff.append(DataType.getDataType(parameterTypeIds[index]).name);
            }
        }
        return buff.toString();
    }

    @Override
    public void invalidate() {
        sqlJavaMethod = null;
        super.invalidate();
    }

    private Value executeBody(ServerSession session, IExpression[] args, boolean columnList) {
        boolean setReturning = isSetReturning();
        // setof/table和多个OUT参数需要返回列定义, 但只有setof/table真的返回多行
        boolean rowReturning = setReturning || isRecordReturning();
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
                    // setof/table函数给空结果集, 标量/record函数返回NULL
                    return setReturning ? createEmptyResultSet() : ValueNull.INSTANCE;
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
            if (atomicBodySqls != null) {
                return executeAtomicBody(session, conn, values);
            }
            PreparedStatement prep = conn.prepareStatement(bodySql);
            try {
                for (int i = 0; i < bodyParameterIndexes.length; i++) {
                    prep.setObject(i + 1, values[bodyParameterIndexes[i]]);
                }
                if (isPlainVoidReturn()) {
                    // RETURNS VOID时函数体可以是普通DML, 执行完丢弃结果并返回NULL
                    executeVoidBody(prep);
                    return ValueNull.INSTANCE;
                }
                ResultSet rs = prep.executeQuery();
                try {
                    return valueFromResultSet(session, rs);
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

    // RETURNS VOID且不是SETOF/RECORD时, 函数体允许是普通DML(不带RETURNING)
    private boolean isPlainVoidReturn() {
        return returnType == Value.NULL && !isSetReturning() && !isRecordReturning();
    }

    // VOID函数体既可能是查询也可能是DML, 用execute()执行后把结果丢弃
    private void executeVoidBody(PreparedStatement prep) throws SQLException {
        if (prep.execute()) {
            ResultSet rs = prep.getResultSet();
            if (rs != null)
                rs.close();
        }
    }

    // BEGIN ATOMIC ... END函数体：多条语句顺序执行，最后一条查询的结果就是返回值
    private Value executeAtomicBody(ServerSession session, Connection conn, Value[] values)
            throws SQLException {
        int last = atomicBodySqls.length - 1;
        // 标量/SETOF/多个OUT参数都要求最后一条语句是查询
        boolean lastIsQuery = isSetReturning() || returnType == Value.RESULT_SET
                || (returnType != Value.NULL && returnType != Value.UNKNOWN);
        Value result = ValueNull.INSTANCE;
        for (int i = 0; i <= last; i++) {
            PreparedStatement prep = conn.prepareStatement(atomicBodySqls[i]);
            try {
                int[] indexes = atomicBodyParameterIndexes[i];
                for (int j = 0; j < indexes.length; j++) {
                    prep.setObject(j + 1, values[indexes[j]]);
                }
                if (i == last && lastIsQuery) {
                    ResultSet rs = prep.executeQuery();
                    try {
                        result = valueFromResultSet(session, rs);
                    } finally {
                        rs.close();
                    }
                } else {
                    // 前面的语句是查询还是更新都执行掉，结果丢弃
                    prep.execute();
                }
            } finally {
                prep.close();
            }
        }
        return result;
    }

    // 把函数体查询的结果转换成返回值
    private Value valueFromResultSet(ServerSession session, ResultSet rs) throws SQLException {
        if (isSetReturning()) {
            return readSetResult(session, rs);
        }
        if (isRecordReturning()) {
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
        Value v = getBodyValue(session, rs, rs.getMetaData(), 1, returnType);
        return v.convertTo(returnType);
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
        ResultSetMetaData meta = rs.getMetaData();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                Value v = getBodyValue(session, rs, meta, i + 1, columnTypes[i]);
                v = v.convertTo(columnTypes[i]);
                // 时间列直接放Value，物化成java.sql.Date/Time/Timestamp再读回来要按默认时区换算
                row[i] = isDateTimeType(columnTypes[i]) ? v : v.getObject();
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

    // 多个OUT参数时把函数体返回的第一行组装成record, 用ValueArray表示
    private Value createRecord(ServerSession session, ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return ValueNull.INSTANCE;
        }
        ResultSetMetaData meta = rs.getMetaData();
        Value[] row = new Value[outputIndexes.length];
        for (int i = 0; i < outputIndexes.length; i++) {
            int type = parameterTypeIds[outputIndexes[i]];
            Value v = getBodyValue(session, rs, meta, i + 1, type);
            row[i] = v.convertTo(type);
        }
        return ValueArray.get(row);
    }

    /**
     * 取函数体结果集里一列的值。时间类型必须按"墙上时间"取值：java.sql.Timestamp/Date/Time
     * 表示的是epoch毫秒，转回Value时要按JVM默认时区把毫秒拆成年月日时分秒，
     * 而DateTimeUtils缓存了第一次用到时的Calendar，运行期默认时区变过之后(比如测试里改了
     * TimeZone.setDefault)就会和显式CAST(DATE AS TIMESTAMP)差一个时区偏移，
     * 所以时间列改成按结果集里的文本还原，和显式CAST一样只用墙上时间字段。
     */
    private static Value getBodyValue(ServerSession session, ResultSet rs, ResultSetMetaData meta,
            int column, int targetType) throws SQLException {
        int sourceType = getDateTimeType(meta.getColumnType(column));
        if (sourceType != Value.UNKNOWN) {
            return DataType.convertToValue(session, rs.getString(column), sourceType)
                    .convertTo(sourceType).convertTo(targetType);
        }
        return DataType.convertToValue(session, rs.getObject(column), targetType);
    }

    // 时间类型: DATE/TIME/TIMESTAMP，物化成java.sql.*再转回来会按默认时区换算
    private static boolean isDateTimeType(int type) {
        return type == Value.DATE || type == Value.TIME || type == Value.TIMESTAMP;
    }

    // 结果集里的列是时间类型时返回对应的Value类型，否则返回UNKNOWN
    private static int getDateTimeType(int sqlType) {
        switch (sqlType) {
        case Types.DATE:
            return Value.DATE;
        case Types.TIME:
            return Value.TIME;
        case Types.TIMESTAMP:
            return Value.TIMESTAMP;
        default:
            return Value.UNKNOWN;
        }
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
