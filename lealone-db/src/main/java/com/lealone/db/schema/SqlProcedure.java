/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.schema;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.db.DbObjectType;
import com.lealone.db.table.Column;
import com.lealone.db.value.DataType;

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
    private final int[] inputIndexes; // 入参(IN/INOUT)在参数列表中的下标
    private final int[] outputIndexes; // OUT/INOUT参数在参数列表中的下标

    // 过程体的解析结果，由sql模块负责填充和读取
    private volatile Object compiledBody;

    public SqlProcedure(Schema schema, int id, String name, String body, String language,
            boolean atomicBody, Column[] parameters, int[] parameterModes) {
        super(schema, id, name);
        this.body = body;
        this.language = language;
        this.atomicBody = atomicBody;
        this.parameters = parameters;
        this.parameterModes = parameterModes;
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
        }
        return buff.append(')').toString();
    }

    /**
     * 入参类型和给定的类型列表完全一致，用于CREATE时判断是否已经有同签名的过程。
     */
    public boolean matchesSignature(int[] argumentTypeIds) {
        if (argumentTypeIds == null) {
            return true;
        }
        if (argumentTypeIds.length != inputIndexes.length) {
            return false;
        }
        for (int i = 0; i < argumentTypeIds.length; i++) {
            if (argumentTypeIds[i] != parameters[inputIndexes[i]].getType()) {
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
        int inputCount = inputIndexes.length;
        // 实参覆盖所有参数(含OUT占位)时按完整参数列表比较，否则只比较入参
        boolean overAllParameters = count == parameters.length && count != inputCount;
        if (count != inputCount && !overAllParameters) {
            return SqlFunction.MATCH_NONE;
        }
        int score = 0;
        for (int i = 0; i < count; i++) {
            int parameterType = overAllParameters ? parameters[i].getType()
                    : parameters[inputIndexes[i]].getType();
            int match = SqlFunction.getTypeMatch(argumentTypeIds[i], parameterType);
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

    private String getParameterTypeSQL(int index) {
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
            if (mode == PARAM_OUT) {
                buff.append("OUT ");
            } else if (mode == PARAM_INOUT) {
                buff.append("INOUT ");
            }
            buff.append(parameters[i].getCreateSQL(false));
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
        return buff.toString();
    }

    // 重载过程要带上完整参数签名(含IN/OUT模式)才能唯一确定要删哪个，
    // SCRIPT回放时也不会因为同名重载报歧义
    @Override
    public String getDropSQL() {
        StatementBuilder buff = new StatementBuilder("DROP PROCEDURE IF EXISTS ");
        buff.append(getSQL()).append('(');
        for (int i = 0; i < parameters.length; i++) {
            buff.appendExceptFirst(", ");
            int mode = parameterModes[i];
            if (mode == PARAM_OUT) {
                buff.append("OUT ");
            } else if (mode == PARAM_INOUT) {
                buff.append("INOUT ");
            } else {
                buff.append("IN ");
            }
            buff.append(getParameterTypeSQL(i));
        }
        return buff.append(')').toString();
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("RENAME");
    }
}
