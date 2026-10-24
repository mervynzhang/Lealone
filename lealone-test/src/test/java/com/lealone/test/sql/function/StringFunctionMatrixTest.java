/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlAssertTestBase;

/**
 * 字符串函数矩阵：ASCII/LEFT/RIGHT/LPAD/RPAD/TRIM 变体/SUBSTRING/LOCATE/REPLACE/REPEAT/SPACE/
 * INSERT/QUOTE_IDENT/BIT_LENGTH/OCTET_LENGTH/CONCAT_WS。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。已确认不符合期望的断言放在 {@code @Ignore("BUG: ...")} 里。
 */
public class StringFunctionMatrixTest extends SqlAssertTestBase {

    @Test
    public void asciiFunction() {
        assertQueryEquals(65, "SELECT ASCII('A')");
        assertQueryEquals(97, "SELECT ASCII('abc')");
        // 空串没有第一个字符，返回 NULL（与 H2 一致）
        assertQueryNull("SELECT ASCII('')");
        assertQueryNull("SELECT ASCII(NULL)");
    }

    @Test
    public void leftRightFunctions() {
        assertQueryEquals("abc", "SELECT LEFT('abcdef', 3)");
        assertQueryEquals("ab", "SELECT LEFT('ab', 5)");
        assertQueryEquals("", "SELECT LEFT('abc', 0)");
        assertQueryEquals("abcdef", "SELECT LEFT('abcdef', 100)");
        assertQueryNull("SELECT LEFT(NULL, 2)");

        assertQueryEquals("def", "SELECT RIGHT('abcdef', 3)");
        assertQueryEquals("ab", "SELECT RIGHT('ab', 5)");
        assertQueryEquals("", "SELECT RIGHT('abc', 0)");
        assertQueryNull("SELECT RIGHT(NULL, 2)");
    }

    @Test
    public void paddingFunctions() {
        assertQueryEquals("000x", "SELECT LPAD('x', 4, '0')");
        assertQueryEquals("x000", "SELECT RPAD('x', 4, '0')");
        assertQueryEquals("  ab", "SELECT LPAD('ab', 4, ' ')");
        assertQueryEquals("ab  ", "SELECT RPAD('ab', 4, ' ')");
        // 目标长度比原串短时截断
        assertQueryEquals("ab", "SELECT LPAD('abc', 2, '0')");
        assertQueryEquals("ab", "SELECT RPAD('abc', 2, '0')");
        assertQueryNull("SELECT LPAD(NULL, 3, '0')");
        assertQueryNull("SELECT RPAD(NULL, 3, '0')");
    }

    @Test
    public void trimVariants() {
        assertQueryEquals("ab", "SELECT TRIM('  ab  ')");
        assertQueryEquals("ab  ", "SELECT LTRIM('  ab  ')");
        assertQueryEquals("  ab", "SELECT RTRIM('  ab  ')");
        assertQueryEquals("ab", "SELECT TRIM(BOTH 'x' FROM 'xxabxx')");
        assertQueryEquals("abxx", "SELECT TRIM(LEADING 'x' FROM 'xxabxx')");
        assertQueryEquals("xxab", "SELECT TRIM(TRAILING 'x' FROM 'xxabxx')");
        assertQueryEquals("ab", "SELECT TRIM(LEADING FROM '  ab')");
        assertQueryNull("SELECT TRIM(NULL)");
    }

    @Test
    public void substringFunctions() {
        assertQueryEquals("bcd", "SELECT SUBSTRING('abcdef', 2, 3)");
        assertQueryEquals("bcdef", "SELECT SUBSTRING('abcdef', 2)");
        assertQueryEquals("bcd", "SELECT SUBSTRING('abcdef' FROM 2 FOR 3)");
        assertQueryEquals("", "SELECT SUBSTRING('abcdef', 2, 0)");
        assertQueryEquals("abcdef", "SELECT SUBSTRING('abcdef', 1, 100)");
        assertQueryNull("SELECT SUBSTRING(NULL, 2, 3)");
    }

    @Test
    public void locateAndPosition() {
        assertQueryEquals(3, "SELECT LOCATE('cd', 'abcdef')");
        assertQueryEquals(0, "SELECT LOCATE('zz', 'abcdef')");
        // 第三个参数是起始位置
        assertQueryEquals(6, "SELECT LOCATE('c', 'abcabc', 4)");
        assertQueryEquals(2, "SELECT POSITION('b' IN 'abc')");
        assertQueryEquals(0, "SELECT POSITION('z' IN 'abc')");
    }

    @Test
    public void replaceRepeatAndSpace() {
        assertQueryEquals("aXcaXc", "SELECT REPLACE('abcabc', 'b', 'X')");
        assertQueryEquals("abc", "SELECT REPLACE('abc', 'z', 'X')");
        assertQueryEquals("ababab", "SELECT REPEAT('ab', 3)");
        assertQueryEquals("", "SELECT REPEAT('ab', 0)");
        assertQueryEquals("   x", "SELECT SPACE(3) || 'x'");
        assertQueryEquals("aXef", "SELECT INSERT('abcdef', 2, 3, 'X')");
    }

    @Test
    public void concatFunctions() {
        assertQueryEquals("a-b", "SELECT CONCAT_WS('-', 'a', NULL, 'b')");
        assertQueryEquals("a-b", "SELECT CONCAT_WS('-', 'a', 'b')");
        assertQueryEquals("ab", "SELECT CONCAT('a', NULL, 'b')");
        assertQueryEquals("axb", "SELECT 'a' || 'x' || 'b'");
        assertQueryNull("SELECT 'a' || NULL");
    }

    @Test
    public void lengthAndCaseFunctions() {
        assertQueryEquals(3L, "SELECT CHAR_LENGTH('abc')");
        assertQueryEquals(3L, "SELECT LENGTH('abc')");
        assertQueryEquals(0L, "SELECT LENGTH('')");
        assertQueryNull("SELECT LENGTH(NULL)");
        assertQueryEquals("abc", "SELECT LOWER('AbC')");
        assertQueryEquals("ABC", "SELECT UPPER('abc')");
        assertQueryNull("SELECT UPPER(NULL)");
        assertQueryEquals("A", "SELECT CHAR(65)");
        assertQueryEquals("aXc", "SELECT REGEXP_REPLACE('abc', 'b', 'X')");
    }

    // ------------------------------------------------------------------
    // 以下为已确认缺陷（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // BUG: OCTET_LENGTH / BIT_LENGTH 按 UTF-16 code unit 计数（每个字符 2 字节 16 位），
    // 而不是按实际编码字节数。ASCII 串 'abc' 应该是 3 字节 / 24 位。
    // 期望: OCTET_LENGTH('abc')=3, BIT_LENGTH('abc')=24（H2 返回值）；
    // 实际: 6 / 48；'中' 期望 3/24（UTF-8），实际 2/16。
    @Ignore("BUG: OCTET_LENGTH/BIT_LENGTH count UTF-16 code units (abc -> 6/48) instead of bytes (3/24)")
    @Test
    public void octetAndBitLength() {
        assertQueryEquals(3L, "SELECT OCTET_LENGTH('abc')");
        assertQueryEquals(24L, "SELECT BIT_LENGTH('abc')");
        assertQueryEquals(3L, "SELECT OCTET_LENGTH('中')");
        assertQueryEquals(24L, "SELECT BIT_LENGTH('中')");
    }

    // BUG: 没有 QUOTE_IDENT 函数（90022 FUNCTION_NOT_FOUND_1）。
    // 期望: QUOTE_IDENT('a"b') 返回带双引号且内部引号翻倍的标识符 "a""b"（H2 返回值）；
    // 实际: 报 90022 FUNCTION_NOT_FOUND_1，Function "QUOTE_IDENT" not found。
    @Ignore("BUG: QUOTE_IDENT is not implemented (90022); expected \"a\"\"b\"")
    @Test
    public void quoteIdent() {
        assertQueryEquals("\"a\"\"b\"", "SELECT QUOTE_IDENT('a\"b')");
        assertQueryEquals("\"abc\"", "SELECT QUOTE_IDENT('abc')");
    }
}
