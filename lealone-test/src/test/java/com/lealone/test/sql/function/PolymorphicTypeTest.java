/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.function;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlTestBase;

public class PolymorphicTypeTest extends SqlTestBase {

    public PolymorphicTypeTest() {
        setEmbedded(true);
    }

    @Test
    public void testAnyElement() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION poly_any(a anyelement) RETURNS anyelement "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN a; END; $$");
        executeQuery("SELECT poly_any(1)");
        assertEquals(1, getIntValue(1));
        executeQuery("SELECT poly_any('x')");
        assertEquals("x", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION poly_any_sql(a anyelement) RETURNS anyelement "
                + "LANGUAGE sql AS $$ SELECT a $$");
        executeQuery("SELECT poly_any_sql(2)");
        assertEquals(2, getIntValue(1));
        executeQuery("SELECT poly_any_sql('y')");
        assertEquals("y", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION poly_nonarray(a anynonarray) "
                + "RETURNS anynonarray LANGUAGE sql AS $$ SELECT a $$");
        executeQuery("SELECT poly_nonarray(3)");
        assertEquals(3, getIntValue(1));
    }

    @Test
    public void testOverloadPreference() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION poly_over(a int) RETURNS int LANGUAGE sql "
                + "AS $$ SELECT a + 1 $$");
        executeUpdate("CREATE OR REPLACE FUNCTION poly_over(a anyelement) RETURNS anyelement "
                + "LANGUAGE sql AS $$ SELECT a $$");
        executeQuery("SELECT poly_over(1)");
        assertEquals(2, getIntValue(1));
        executeQuery("SELECT poly_over(CAST('x' AS VARCHAR))");
        assertEquals("x", getStringValue(1));
    }

    @Test
    public void testBadPoly() throws Exception {
        try {
            executeUpdate("CREATE OR REPLACE FUNCTION poly_bad(a anyarray) RETURNS anyelement "
                    + "LANGUAGE sql AS $$ SELECT a[1] $$");
            fail("expected failure");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("anyelement"));
        }
    }

    @Test
    public void testProcedure() throws Exception {
        executeUpdate("CREATE OR REPLACE PROCEDURE poly_proc(a int, b anyelement) "
                + "AS $$ SELECT NULL::int; $$");
        executeUpdate("CALL poly_proc(1, 2)");

        executeUpdate("CREATE OR REPLACE PROCEDURE poly_proc2(INOUT a anyelement, OUT b anyelement) "
                + "AS $$ SELECT ?, 1; $$");
        executeQuery("CALL poly_proc2(1, NULL)");
        assertEquals(1, getIntValue(1));
        assertEquals(1, getIntValue(2));
    }

    @Test
    public void testEmptyBody() throws Exception {
        executeUpdate("SET check_function_bodies = FALSE");
        executeUpdate("CREATE OR REPLACE FUNCTION poly_empty(a anyelement) RETURNS anyarray "
                + "LANGUAGE sql AS ''");
        executeQueryThanAssertErrorCode("SELECT poly_empty(0)",
                ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY);
        executeUpdate("SET check_function_bodies = TRUE");
    }

    @Test
    public void testAnyArray() throws Exception {
        executeUpdate("CREATE OR REPLACE FUNCTION poly_arr(a anyarray) RETURNS anyarray "
                + "LANGUAGE sql AS $$ SELECT a $$");
        executeQuery("SELECT array_append(ARRAY[1, 2], 3)");
        assertEquals("(1, 2, 3)", getStringValue(1));
        executeQuery("SELECT array_length(ARRAY[1, 2, 3], 1)");
        assertEquals(3, getIntValue(1));
        executeQuery("SELECT poly_arr(ARRAY[1, 2])");
        assertEquals("(1, 2)", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION poly_append(a anyarray, b anyelement) "
                + "RETURNS anyarray LANGUAGE sql "
                + "AS $$ SELECT array_append(a, b) || array_append(a, b) $$");
        executeQuery("SELECT poly_append(ARRAY[1, 2], 3)");
        assertEquals("(1, 2, 3, 1, 2, 3)", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION poly_poly(a anyelement, b anyarray) "
                + "RETURNS anyarray LANGUAGE plpgsql AS $$ BEGIN RETURN b; END; $$");
    }
}
