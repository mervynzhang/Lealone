/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

/**
 * 过程的命名实参扩充: OUT 参数的命名占位(a => null)、VARIADIC 参数、数组参数，
 * 以及"只差 OUT 模式的重载"要按入参签名判重(和 PostgreSQL 一样报 90150)。
 */
public class ProcedureNamedOutVariadicTest extends SqlTestBase {

    public ProcedureNamedOutVariadicTest() {
        setEmbedded(true);
    }

    // a => null 里的 a 是OUT参数，只占位不求值
    @Test
    public void testNamedOutPlaceholder() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS nov_p");
        executeUpdate("CREATE PROCEDURE nov_p(OUT a INT, IN b INT, IN c INT) AS 'SELECT ? - ?'");

        // 位置调用: OUT位置写NULL占位
        executeQuery("CALL nov_p(NULL, 7, 4)");
        assertEquals(3, getIntValue(1, true));

        // 命名调用: 全部写名字
        executeQuery("CALL nov_p(a => NULL, b => 10, c => 4)");
        assertEquals(6, getIntValue(1, true));

        // OUT占位不求值
        executeQuery("CALL nov_p(a => 1/0, b => 10, c => 4)");
        assertEquals(6, getIntValue(1, true));

        // 命名实参的顺序可以任意写
        executeQuery("CALL nov_p(c => 4, b => 10, a => NULL)");
        assertEquals(6, getIntValue(1, true));

        // 不确定的参数名报函数不存在
        executeQueryThanAssertErrorCode("CALL nov_p(x => NULL)", ErrorCode.FUNCTION_NOT_FOUND_1);
        executeUpdate("DROP PROCEDURE nov_p");
    }

    // VARIADIC 参数把余下的实参打包成一个数组
    @Test
    public void testVariadicParameter() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS nov_v");
        executeUpdate("CREATE PROCEDURE nov_v(OUT a INT, VARIADIC b INT[]) "
                + "AS 'SELECT array_length(?, 1)'");
        executeQuery("CALL nov_v(NULL, 11, 12, 13)");
        assertEquals(3, getIntValue(1, true));
        // 只有固定参数时VARIADIC是空数组
        executeQuery("CALL nov_v(NULL)");
        assertEquals(0, getIntValue(1, true));

        // 固定参数和VARIADIC参数一起按位置绑定: 第1个?是a, 第2个?是打包好的数组
        executeUpdate("DROP PROCEDURE IF EXISTS nov_v2");
        executeUpdate("CREATE PROCEDURE nov_v2(a INT, VARIADIC b INT[]) "
                + "AS 'SELECT ? + array_length(?, 1)'");
        executeQuery("CALL nov_v2(1, 2, 3, 4)");
        assertEquals(4, getIntValue(1, true));

        // 只有VARIADIC参数的过程
        executeUpdate("DROP PROCEDURE IF EXISTS nov_v3");
        executeUpdate("CREATE PROCEDURE nov_v3(VARIADIC b INT[]) AS 'SELECT array_length(?, 1)'");
        executeQuery("CALL nov_v3(1, 2, 3)");
        assertEquals(3, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE nov_v3");

        // DDL里要写出VARIADIC，重启或SCRIPT回放后还能认出来
        String ddl = getCreateSql("NOV_V");
        assertTrue(ddl, ddl.contains(" VARIADIC B INT[]"));
        executeUpdate("DROP PROCEDURE nov_v");
        executeUpdate(ddl);
        executeQuery("CALL nov_v(NULL, 1, 2)");
        assertEquals(2, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE nov_v");

        executeUpdate("DROP PROCEDURE nov_v2");
    }

    // VARIADIC 的写法校验: 必须是最后一个参数、必须是数组、只能是IN
    @Test
    public void testVariadicValidation() throws Exception {
        executeUpdateThanAssertErrorCode(
                "CREATE PROCEDURE nov_e1(a VARIADIC INT[], b OUT INT) AS 'SELECT 1'",
                ErrorCode.SYNTAX_ERROR_1);
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE nov_e2(a VARIADIC INT) AS 'SELECT 1'",
                ErrorCode.SYNTAX_ERROR_1);
        // 名字写在VARIADIC前面也支持
        executeUpdate("DROP PROCEDURE IF EXISTS nov_e3");
        executeUpdate("CREATE PROCEDURE nov_e3(VARIADIC a INT[]) AS 'SELECT array_length(?, 1)'");
        executeQuery("CALL nov_e3(1, 2)");
        assertEquals(2, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE nov_e3");
    }

    // 普通数组参数(不是VARIADIC)要按数组传
    @Test
    public void testArrayParameter() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS nov_a");
        executeUpdate("CREATE PROCEDURE nov_a(a INT[]) AS 'SELECT array_length(?, 1)'");
        executeQuery("CALL nov_a(ARRAY[1, 2, 3, 4])");
        assertEquals(4, getIntValue(1, true));
        executeUpdate("DROP PROCEDURE nov_a");
    }

    // 只差OUT模式的重载: 入参签名相同，PG也报重复
    @Test
    public void testOutOnlyOverloadIsDuplicate() throws Exception {
        executeUpdate("DROP PROCEDURE IF EXISTS nov_d");
        executeUpdate("DROP PROCEDURE IF EXISTS nov_d2");
        executeUpdate("CREATE PROCEDURE nov_d(OUT a INT, IN b INT) AS 'SELECT ?'");
        // 入参都是1个INT, 只是OUT有无不同, 算同一个签名
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE nov_d(b INT) AS 'SELECT 1'",
                ErrorCode.PROCEDURE_ALREADY_EXISTS_1);
        // 入参个数不同就不算重复
        executeUpdate("CREATE PROCEDURE nov_d2(b INT) AS 'SELECT 1'");
        executeUpdate("CREATE PROCEDURE nov_d2(OUT a INT) AS 'SELECT 1'");
        executeUpdateThanAssertErrorCode("CREATE PROCEDURE nov_d2(a INT) AS 'SELECT 1'",
                ErrorCode.PROCEDURE_ALREADY_EXISTS_1);
        executeUpdate("DROP PROCEDURE nov_d");
        // 两个重载参数个数一样, 要写IN/OUT模式才能指定删哪个
        executeUpdate("DROP PROCEDURE nov_d2(IN INT)");
        executeUpdate("DROP PROCEDURE nov_d2(OUT INT)");
    }

    // 从SCRIPT里找出指定过程的CREATE语句
    private String getCreateSql(String name) {
        String pattern = "." + name + "(";
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SCRIPT")) {
            while (rs.next()) {
                String sql = rs.getString(1);
                if (sql != null && sql.startsWith("CREATE PROCEDURE ") && sql.contains(pattern)) {
                    return sql;
                }
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        return null;
    }
}
