package com.lealone.test.db;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.TestBase;

/** 常量折叠时的错误分类: 数据类错误可以容忍, 其它错误必须抛出。 */
public class DataExceptionTest extends TestBase {

    @Test
    public void dataErrorsAreRecognized() {
        assertTrue(DbException.isDataException(DbException.get(ErrorCode.DIVISION_BY_ZERO_1)));
        assertTrue(DbException.isDataException(DbException.get(ErrorCode.DATA_CONVERSION_ERROR_1)));
        assertTrue(DbException.isDataException(DbException.get(ErrorCode.VALUE_TOO_LONG_2)));
        assertTrue(DbException.isDataException(DbException.get(ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1)));
    }

    @Test
    public void nonDataErrorsAreNotSwallowed() {
        assertFalse(DbException.isDataException(DbException.get(ErrorCode.PARAMETER_NOT_SET_1)));
        assertFalse(DbException.isDataException(DbException.get(ErrorCode.SYNTAX_ERROR_1, "x")));
        assertFalse(DbException.isDataException(new RuntimeException("boom")));
    }
}
