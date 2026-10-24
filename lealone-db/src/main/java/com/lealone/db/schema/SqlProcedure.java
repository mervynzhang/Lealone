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

/**
 * 用一组SQL语句实现的存储过程，过程体在第一次执行时才被解析，
 * 解析成什么由sql模块决定，db模块只把它当成一个不透明的对象缓存起来，
 * 这样db模块不用反向依赖sql模块。
 * 
 * @author zhh
 */
public class SqlProcedure extends SchemaObjectBase {

    private final String body;
    private final Column[] parameters;
    // 过程体的解析结果，由sql模块负责填充和读取
    private volatile Object compiledBody;

    public SqlProcedure(Schema schema, int id, String name, String body, Column[] parameters) {
        super(schema, id, name);
        this.body = body;
        this.parameters = parameters;
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.PROCEDURE;
    }

    public String getBody() {
        return body;
    }

    public int getParameterCount() {
        return parameters.length;
    }

    public Column getParameter(int i) {
        return parameters[i];
    }

    public Object getCompiledBody() {
        return compiledBody;
    }

    // 过程体的解析结果跟解析它的会话绑定，所以每个会话各存一份
    public void setCompiledBody(Object compiledBody) {
        this.compiledBody = compiledBody;
    }

    @Override
    public String getCreateSQL() {
        StatementBuilder buff = new StatementBuilder("CREATE PROCEDURE ");
        buff.append(getSQL()).append('(');
        for (int i = 0, size = parameters.length; i < size; i++)
            buff.appendExceptFirst(", ").append(parameters[i].getCreateSQL(false));
        buff.append(") AS ").append(StringUtils.quoteStringSQL(body));
        return buff.toString();
    }

    @Override
    public String getDropSQL() {
        return "DROP PROCEDURE IF EXISTS " + getSQL();
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("RENAME");
    }
}
