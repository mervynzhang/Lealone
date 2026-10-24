/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import java.util.ArrayList;

import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.db.DbObjectType;
import com.lealone.db.auth.Right;
import com.lealone.db.lock.DbObjectLock;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;

/**
 * 用一组SQL语句实现的存储过程，过程体在第一次执行时才被解析，
 * 解析成什么由sql模块决定，db模块只把它当成一个不透明的对象缓存起来，
 * 这样db模块不用反向依赖sql模块。
 * 
 * @author zhh
 */
public class SqlProcedure extends SchemaObjectBase {

    // 参数模式，和SqlFunction保持一致
    public static final int PARAM_IN = 0;
    public static final int PARAM_OUT = 1;
    public static final int PARAM_INOUT = 2;

    private final String body;
    // 过程体语言: null/SQL表示SQL串, plpgsql表示由PL/pgSQL解释器执行
    private final String language;
    // 过程体是BEGIN ATOMIC ... END这种SQL标准函数体(可以有多条语句)
    private final boolean atomicBody;
    private final Column[] parameters;
    private final int[] parameterModes;
    // 参数默认值: parameterDefaults是DEFAULT表达式的SQL文本, parameterDefaultValues是CREATE时算好的值
    private final String[] parameterDefaults;
    private final Value[] parameterDefaultValues;
    // 必须提供的最少实参个数(有默认值的入参只能集中在末尾)
    private final int minInputParameterCount;
    private final int[] inputIndexes; // 入参(IN/INOUT)在参数列表中的下标
    private final int[] outputIndexes; // OUT/INOUT参数在参数列表中的下标
    // 最后一个入参是VARIADIC数组参数，调用时可以展开成任意多个实参
    private final boolean variadic;
    private final int variadicComponentType; // VARIADIC数组元素类型，没有VARIADIC时是-1

    // ---- PostgreSQL的过程属性，可以通过ALTER PROCEDURE修改，和函数的属性是同一套 ----
    private String volatility = SqlFunction.VOLATILE;
    private boolean strict;
    private boolean securityDefiner;
    private boolean leakproof;
    private boolean window;
    private Double cost;
    private Double rows;
    private String ownerName;
    // 每个元素是{参数名, 值}，值可能为null(表示DEFAULT)
    private final ArrayList<String[]> configItems = new ArrayList<>();

    // 过程体的解析结果，由sql模块负责填充和读取
    private volatile Object compiledBody;

    public SqlProcedure(Schema schema, int id, String name, String body, String language,
            boolean atomicBody, Column[] parameters, int[] parameterModes,
            String[] parameterDefaults, Value[] parameterDefaultValues, boolean variadic,
            int variadicComponentType) {
        super(schema, id, name);
        this.body = body;
        this.language = language;
        this.atomicBody = atomicBody;
        this.parameters = parameters;
        this.parameterModes = parameterModes;
        this.parameterDefaults = parameterDefaults;
        this.parameterDefaultValues = parameterDefaultValues;
        this.variadic = variadic;
        this.variadicComponentType = variadicComponentType;
        int inputCount = 0;
        int outputCount = 0;
        for (int mode : parameterModes) {
            if (mode != PARAM_OUT)
                inputCount++;
            if (mode != PARAM_IN)
                outputCount++;
        }
        inputIndexes = new int[inputCount];
        outputIndexes = new int[outputCount];
        int i = 0;
        int o = 0;
        for (int p = 0; p < parameterModes.length; p++) {
            if (parameterModes[p] != PARAM_OUT)
                inputIndexes[i++] = p;
            if (parameterModes[p] != PARAM_IN)
                outputIndexes[o++] = p;
        }
        int min = 0;
        if (parameterDefaults != null) {
            for (int p = 0; p < inputIndexes.length; p++) {
                if (parameterDefaults[inputIndexes[p]] == null) {
                    min = p + 1;
                }
            }
        }
        minInputParameterCount = min;
    }

    public int getMinInputParameterCount() {
        return minInputParameterCount;
    }

    public boolean hasDefaultParameters() {
        return parameterDefaults != null;
    }

    // 第inputIndex个入参的参数名(按入参顺序)
    public String getInputParameterName(int inputIndex) {
        return parameters[inputIndexes[inputIndex]].getName();
    }

    /**
     * 按参数名找入参下标，找不到返回-1。
     */
    public int findInputParameterIndex(String name) {
        for (int i = 0; i < inputIndexes.length; i++) {
            if (parameters[inputIndexes[i]].getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按参数名找OUT参数下标，找不到返回-1。命名实参里写OUT参数名时只当占位符，不求值。
     */
    public int findOutputParameterIndex(String name) {
        for (int i = 0; i < outputIndexes.length; i++) {
            int index = outputIndexes[i];
            if (parameterModes[index] == PARAM_OUT
                    && parameters[index].getName().equalsIgnoreCase(name)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 把命名实参映射成入参下标。位置实参必须写在命名实参前面，名字必须存在而且不能重复，
     * 省略的入参必须有默认值。返回null表示这个调用匹配不上。
     */
    public int[] mapArgumentPositions(String[] argNames) {
        int count = argNames.length;
        // VARIADIC过程只支持位置实参
        if (variadic || count > inputIndexes.length + outputIndexes.length) {
            return null;
        }
        int[] positions = new int[count];
        boolean[] used = new boolean[inputIndexes.length];
        int positional = 0;
        boolean named = false;
        for (int i = 0; i < count; i++) {
            String name = argNames[i];
            if (name == null) {
                // 位置实参必须写在命名实参前面
                if (named || positional >= inputIndexes.length) {
                    return null;
                }
                positions[i] = positional;
                used[positional++] = true;
            } else {
                named = true;
                int index = findInputParameterIndex(name);
                if (index >= 0) {
                    if (used[index]) {
                        return null;
                    }
                    positions[i] = index;
                    used[index] = true;
                } else if (findOutputParameterIndex(name) >= 0) {
                    // OUT参数的命名占位: 不参与类型匹配, 也不求值
                    positions[i] = -1;
                } else {
                    return null;
                }
            }
        }
        // 省略的入参必须有默认值
        for (int i = 0; i < inputIndexes.length; i++) {
            if (!used[i] && getDefaultInputValue(i) == null) {
                return null;
            }
        }
        return positions;
    }

    /**
     * 命名实参的匹配程度: positions[i]是第i个实参对应的入参下标。
     */
    public int getMatchScore(int[] argumentTypeIds, int[] positions) {
        if (positions == null) {
            return SqlFunction.MATCH_NONE;
        }
        int score = 0;
        for (int i = 0; i < argumentTypeIds.length; i++) {
            if (positions[i] < 0) {
                // OUT参数的命名占位
                continue;
            }
            int parameterType = parameters[inputIndexes[positions[i]]].getType();
            int match = SqlFunction.getTypeMatch(argumentTypeIds[i], parameterType);
            if (match == SqlFunction.MATCH_NONE) {
                return SqlFunction.MATCH_NONE;
            }
            score += match;
        }
        return score;
    }

    // VARIADIC过程的匹配: 前固定参数按参数列表对应(OUT参数只占位), 余下的实参按数组元素类型匹配
    private int getVariadicMatchScore(int[] argumentTypeIds) {
        int count = argumentTypeIds.length;
        int fixedCount = getFixedParameterCount();
        if (count < fixedCount) {
            return SqlFunction.MATCH_NONE;
        }
        int score = 0;
        for (int i = 0; i < count; i++) {
            int parameterType = i < fixedCount ? parameters[i].getType() : variadicComponentType;
            int polymorphicType = i < fixedCount ? parameters[i].getPolymorphicType()
                    : Column.POLY_NONE;
            int match = SqlFunction.getPolymorphicMatch(argumentTypeIds[i], polymorphicType);
            if (match == SqlFunction.MATCH_NONE) {
                return SqlFunction.MATCH_NONE;
            }
            if (match == SqlFunction.MATCH_POLYMORPHIC_UNDECIDED) {
                match = SqlFunction.getTypeMatch(argumentTypeIds[i], parameterType);
            }
            if (match == SqlFunction.MATCH_NONE) {
                return SqlFunction.MATCH_NONE;
            }
            score += match;
        }
        return score;
    }

    // 第inputIndex个入参的默认值, 没有默认值时返回null
    public Value getDefaultInputValue(int inputIndex) {
        if (parameterDefaultValues == null) {
            return null;
        }
        return parameterDefaultValues[inputIndexes[inputIndex]];
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.PROCEDURE;
    }

    public String getBody() {
        return body;
    }

    public String getLanguage() {
        return language;
    }

    // 过程体是PL/pgSQL，由PL/pgSQL解释器执行(和plpgsql函数同一套)
    public boolean isPlpgSql() {
        return "PLPGSQL".equalsIgnoreCase(language);
    }

    // BEGIN ATOMIC ... END形式的函数体，过程体里可以有多条SQL语句，最后一条查询的结果就是返回值
    public boolean isAtomicBody() {
        return atomicBody;
    }

    public int getParameterCount() {
        return parameters.length;
    }

    public Column getParameter(int i) {
        return parameters[i];
    }

    public int getParameterMode(int i) {
        return parameterModes[i];
    }

    public int getInputParameterCount() {
        return inputIndexes.length;
    }

    public int getInputParameterIndex(int i) {
        return inputIndexes[i];
    }

    public boolean isVariadic() {
        return variadic;
    }

    public int getVariadicComponentType() {
        return variadicComponentType;
    }

    // VARIADIC参数前面固定参数的个数
    public int getFixedParameterCount() {
        return parameters.length - 1;
    }

    public boolean hasOutputParameters() {
        return outputIndexes.length > 0;
    }

    public int getOutputParameterCount() {
        return outputIndexes.length;
    }

    public int getOutputParameterIndex(int i) {
        return outputIndexes[i];
    }

    public Object getCompiledBody() {
        return compiledBody;
    }

    // 过程体的解析结果跟解析它的会话绑定，所以每个会话各存一份
    public void setCompiledBody(Object compiledBody) {
        this.compiledBody = compiledBody;
    }

    public String getVolatility() {
        return volatility;
    }

    public void setVolatility(String volatility) {
        this.volatility = volatility == null ? SqlFunction.VOLATILE : volatility;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
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
     * 入参(IN/INOUT)的类型id列表，OUT参数不参与重载和签名。
     */
    public int[] getInputParameterTypeIds() {
        int[] types = new int[inputIndexes.length];
        for (int i = 0; i < inputIndexes.length; i++) {
            types[i] = parameters[inputIndexes[i]].getType();
        }
        return types;
    }

    public int getInputParameterPolymorphicType(int inputIndex) {
        return parameters[inputIndexes[inputIndex]].getPolymorphicType();
    }

    public int[] getInputPolymorphicTypes() {
        int[] types = new int[inputIndexes.length];
        for (int i = 0; i < inputIndexes.length; i++)
            types[i] = parameters[inputIndexes[i]].getPolymorphicType();
        return types;
    }

    /**
     * 同名过程按入参类型重载，所以schema里存过程对象的key要带上入参签名。
     * 入参类型相同的过程对应同一个key。
     */
    public String getOverloadKey() {
        return getOverloadKey(getName());
    }

    public String getOverloadKey(String name) {
        StatementBuilder buff = new StatementBuilder(name).append('(');
        for (int i = 0; i < inputIndexes.length; i++) {
            buff.appendExceptFirst(",");
            buff.append(parameters[inputIndexes[i]].getType());
            int poly = parameters[inputIndexes[i]].getPolymorphicType();
            if (poly != Column.POLY_NONE)
                buff.append('/').append('P').append(poly);
        }
        return buff.append(')').toString();
    }

    /**
     * 入参类型和给定的类型列表完全一致，用于CREATE时判断是否已经有同签名的过程。
     */
    public boolean matchesSignature(int[] argumentTypeIds) {
        return matchesSignature(argumentTypeIds, null);
    }

    public boolean matchesSignature(int[] argumentTypeIds, int[] polymorphicTypes) {
        if (argumentTypeIds == null) {
            return true;
        }
        if (argumentTypeIds.length != inputIndexes.length) {
            return false;
        }
        for (int i = 0; i < argumentTypeIds.length; i++) {
            Column column = parameters[inputIndexes[i]];
            if (argumentTypeIds[i] != column.getType()) {
                return false;
            }
            if (polymorphicTypes != null && i < polymorphicTypes.length
                    && polymorphicTypes[i] != column.getPolymorphicType()) {
                return false;
            }
        }
        return true;
    }

    /**
     * DROP PROCEDURE name(类型列表)按声明的完整参数列表匹配，OUT参数也参与匹配；
     * modes为null或者某个位置是-1时表示这一位没写参数模式。
     */
    public boolean matchesDropSignature(int[] typeIds, int[] modes) {
        if (typeIds == null || typeIds.length != parameters.length) {
            return false;
        }
        for (int i = 0; i < typeIds.length; i++) {
            if (typeIds[i] != parameters[i].getType()) {
                return false;
            }
            if (modes != null && modes[i] != -1 && modes[i] != parameterModes[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算实参类型和过程声明的匹配程度，规则和函数重载完全一样。
     * 实参可以只给入参，也可以像PostgreSQL那样给OUT参数写占位，所以两种实参个数都接受。
     */
    public int getMatchScore(int[] argumentTypeIds) {
        if (argumentTypeIds == null) {
            return SqlFunction.MATCH_NONE;
        }
        int count = argumentTypeIds.length;
        if (variadic) {
            return getVariadicMatchScore(argumentTypeIds);
        }
        int inputCount = inputIndexes.length;
        // 实参覆盖所有参数(含OUT占位)时按完整参数列表比较，否则只比较入参
        boolean overAllParameters = count == parameters.length && count != inputCount;
        // 有默认值的入参可以省略, 所以实参个数是一个区间
        if (!overAllParameters && (count < minInputParameterCount || count > inputCount)) {
            return SqlFunction.MATCH_NONE;
        }
        int score = 0;
        for (int i = 0; i < count; i++) {
            Column column = overAllParameters ? parameters[i] : parameters[inputIndexes[i]];
            int match = SqlFunction.getPolymorphicMatch(argumentTypeIds[i],
                    column.getPolymorphicType());
            if (match == SqlFunction.MATCH_NONE)
                return SqlFunction.MATCH_NONE;
            if (match == SqlFunction.MATCH_POLYMORPHIC_UNDECIDED)
                match = SqlFunction.getTypeMatch(argumentTypeIds[i], column.getType());
            if (match == SqlFunction.MATCH_NONE) {
                return SqlFunction.MATCH_NONE;
            }
            score += match;
        }
        return score;
    }

    // 用于报错信息的签名，例如 P(INT, VARCHAR)
    public String getSignature() {
        StatementBuilder buff = new StatementBuilder(getName()).append('(');
        for (int i = 0; i < inputIndexes.length; i++) {
            buff.appendExceptFirst(", ");
            buff.append(getParameterTypeSQL(inputIndexes[i]));
        }
        return buff.append(')').toString();
    }

    /**
     * 参数声明的类型文本(含OUT参数)，INFORMATION_SCHEMA.PARAMETERS用。
     */
    public String getParameterTypeSQL(int index) {
        String typeSql = parameters[index].getOriginalSQL();
        if (typeSql != null) {
            return typeSql;
        }
        return DataType.getDataType(parameters[index].getType()).name;
    }

    @Override
    public String getCreateSQL() {
        StatementBuilder buff = new StatementBuilder("CREATE PROCEDURE ");
        buff.append(getSQL()).append('(');
        for (int i = 0, size = parameters.length; i < size; i++) {
            buff.appendExceptFirst(", ");
            int mode = parameterModes[i];
            if (variadic && i == getFixedParameterCount()) {
                buff.append("VARIADIC ");
            } else if (mode == PARAM_OUT) {
                buff.append("OUT ");
            } else if (mode == PARAM_INOUT) {
                buff.append("INOUT ");
            }
            buff.append(parameters[i].getCreateSQL(false));
            if (parameterDefaults != null && parameterDefaults[i] != null) {
                buff.append(" DEFAULT ").append(parameterDefaults[i]);
            }
        }
        buff.append(')');
        // 只有plpgsql要显式写出LANGUAGE, SQL是默认值
        if (isPlpgSql()) {
            buff.append(" LANGUAGE ").append(language);
        }
        if (atomicBody) {
            // 每条语句都要有分号(和PostgreSQL一样)，否则回放时END会被当成上一个语句的别名
            buff.append(" BEGIN ATOMIC ");
            if (!body.isEmpty()) {
                buff.append(body).append("; ");
            }
            buff.append("END");
        } else {
            buff.append(" AS ").append(StringUtils.quoteStringSQL(body));
        }
        appendAttributes(buff);
        return buff.toString();
    }

    // 过程属性只写用户设置过的项，默认值不写，没有属性的过程DDL和以前完全一样
    private void appendAttributes(StatementBuilder buff) {
        if (!SqlFunction.VOLATILE.equalsIgnoreCase(volatility)) {
            buff.append(' ').append(volatility);
        }
        if (strict) {
            buff.append(" STRICT");
        }
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
            buff.append(" COST ").append(SqlFunction.formatNumber(cost.doubleValue()));
        }
        if (rows != null) {
            buff.append(" ROWS ").append(SqlFunction.formatNumber(rows.doubleValue()));
        }
        for (String[] item : configItems) {
            buff.append(" SET ").append(database.quoteIdentifier(item[0])).append(" = ");
            if (item[1] != null) {
                buff.append(item[1]);
            } else {
                buff.append("DEFAULT");
            }
        }
        if (ownerName != null) {
            buff.append(" OWNER TO ").append(database.quoteIdentifier(ownerName));
        }
    }

    // 重载过程要带上完整参数签名(含IN/OUT模式)才能唯一确定要删哪个，
    // SCRIPT回放时也不会因为同名重载报歧义
    @Override
    public String getDropSQL() {
        return "DROP PROCEDURE IF EXISTS " + getSQL() + '(' + getParameterSignatureSQL() + ')';
    }

    /**
     * 完整参数签名(含IN/OUT/INOUT模式)，用于DROP PROCEDURE和GRANT里的参数类型列表。
     */
    public String getParameterSignatureSQL() {
        StatementBuilder buff = new StatementBuilder();
        for (int i = 0; i < parameters.length; i++) {
            buff.appendExceptFirst(", ");
            int mode = parameterModes[i];
            if (variadic && i == getFixedParameterCount()) {
                buff.append("VARIADIC ");
            } else if (mode == PARAM_OUT) {
                buff.append("OUT ");
            } else if (mode == PARAM_INOUT) {
                buff.append("INOUT ");
            } else {
                buff.append("IN ");
            }
            buff.append(getParameterTypeSQL(i));
        }
        return buff.toString();
    }

    @Override
    public void removeChildrenAndResources(ServerSession session, DbObjectLock lock) {
        // 删过程时要一起删掉引用它的授权，否则SCRIPT里会留下指向不存在过程的GRANT
        for (Right right : database.getAllRights()) {
            if (right.getGrantedObject() == this) {
                database.removeDatabaseObject(session, right, lock);
            }
        }
        super.removeChildrenAndResources(session, lock);
    }

    @Override
    public void checkRename() {
        // 支持ALTER PROCEDURE ... RENAME TO
    }
}
