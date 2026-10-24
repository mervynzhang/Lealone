/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Constants;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SqlFunction;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;

/**
 * 用PL/pgSQL作为函数体的存储函数，对应 CREATE FUNCTION ... LANGUAGE plpgsql。
 * db模块的SqlFunction只负责参数和返回值，具体的解释执行放在sql模块，
 * 所以这里用子类的方式接入，避免db模块反向依赖sql模块。
 */
public class PlpgSqlFunction extends SqlFunction {

    public static final String LANGUAGE = "plpgsql";

    // 函数体解析结果，第一次调用时解析并缓存
    private volatile PlpgSqlAst.Block compiledBody;

    public PlpgSqlFunction(Schema schema, int id, String name) {
        super(schema, id, name);
        setLanguage(LANGUAGE);
    }

    @Override
    protected Value executeFunction(ServerSession session, Value[] values) {
        PlpgSqlAst.Block block = getCompiledBody();
        // 函数体内的SQL在独立的嵌套会话里执行，事务以当前会话的事务为父事务；
        // SECURITY DEFINER时这个嵌套会话用的是属主身份
        ServerSession nestedSession = createNestedSession(session);
        String schemaName = session.getCurrentSchemaName();
        if (schemaName != null && !Constants.SCHEMA_MAIN.equals(schemaName))
            nestedSession.setCurrentSchemaName(schemaName);
        PlpgSqlInterpreter interpreter = new PlpgSqlInterpreter(nestedSession, block,
                getResultInfo());
        try {
            return interpreter.execute(getParameterNames(), getParameterTypes(), values);
        } catch (PlpgSqlException e) {
            // 离开函数时把PL/pgSQL异常转换成带SQLSTATE的DbException
            throw DbException.convert(
                    new SQLException(e.getMessage(), e.getSqlState(), ErrorCode.GENERAL_ERROR_1));
        }
    }

    private PlpgSqlResultInfo getResultInfo() {
        int returnType = getReturnType();
        boolean setReturning = isSetReturning();
        boolean returnsTable = isReturnsTable();
        int[] parameterModes = getParameterModes();
        int[] outputIndexes = getOutputIndexes();
        String[] columnNames = null;
        int[] columnTypes = null;
        long[] columnPrecisions = null;
        int[] columnScales = null;
        if (setReturning) {
            // SETOF <表名>也有列名；SETOF标量时返回null，列名用函数名
            columnNames = getReturnColumnNames();
            columnTypes = getReturnColumnTypes();
            columnPrecisions = getReturnColumnPrecisions();
            columnScales = getReturnColumnScales();
        }
        return new PlpgSqlResultInfo(getName(), returnType, setReturning, returnsTable,
                parameterModes, outputIndexes, columnNames, columnTypes, columnPrecisions,
                columnScales);
    }

    @Override
    public void invalidate() {
        compiledBody = null;
        super.invalidate();
    }

    private PlpgSqlAst.Block getCompiledBody() {
        PlpgSqlAst.Block block = compiledBody;
        if (block == null) {
            block = PlpgSqlParser.parse(getBody());
            compiledBody = block;
        }
        return block;
    }
}
