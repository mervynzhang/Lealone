/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.ResultSet;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

public class CompositeTypeTest extends SqlTestBase {

    public CompositeTypeTest() {
        setEmbedded(true);
    }

    @Test
    public void testRowTypeArray() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS arr_t");
        executeUpdate("CREATE TABLE arr_t(id INT, name VARCHAR(20))");
        executeUpdate("CREATE OR REPLACE FUNCTION arr_test() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE arr arr_t%ROWTYPE[];" //
                + " BEGIN" //
                + "   arr[1].id := 10;" //
                + "   arr[1].name := 'x';" //
                + "   arr[2] := ROW(20, 'y');" //
                + "   RETURN arr[1].id + arr[2].id;" //
                + " END; $$");
        executeQuery("SELECT arr_test()");
        assertEquals(30, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION arr_test3() RETURNS INT LANGUAGE plpgsql AS $$"
                + " DECLARE arr arr_t%ROWTYPE[]; i INT := 2;" //
                + " BEGIN arr[i].id := 22; RETURN arr[i].id; END; $$");
        executeQuery("SELECT arr_test3()");
        assertEquals(22, getIntValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION arr_setof() RETURNS SETOF arr_t LANGUAGE plpgsql AS $$"
                + " DECLARE arr arr_t%ROWTYPE[];" //
                + " BEGIN arr[1] := ROW(3, 'c'); RETURN NEXT arr[1]; END; $$");
        executeQuery("SELECT * FROM arr_setof()");
        assertEquals(3, getIntValue(1));
        assertEquals("c", getStringValue(2));

        executeUpdate("CREATE OR REPLACE FUNCTION arr_test2() RETURNS VARCHAR LANGUAGE plpgsql AS $$"
                + " DECLARE arr arr_t[]%ROWTYPE;" //
                + " BEGIN" //
                + "   arr[1] := ROW(1, 'a');" //
                + "   RETURN arr[1].name;" //
                + " END; $$");
        executeQuery("SELECT arr_test2()");
        assertEquals("a", getStringValue(1));
    }

    @Test
    public void testCompositeType() throws Exception {
        executeUpdate("DROP TYPE IF EXISTS comp");
        executeUpdate("CREATE TYPE comp AS (a INT, b VARCHAR(20))");
        executeQuery("SELECT CAST(ROW(1, 'x') AS comp)");
        executeUpdate("CREATE OR REPLACE FUNCTION comp_plp(i INT) RETURNS comp LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN ROW(i, 'x')::comp; END; $$");
        executeUpdate("CREATE OR REPLACE FUNCTION comp_sql(i INT) RETURNS comp LANGUAGE sql "
                + "AS $$ SELECT i, 'y' $$");

        executeQuery("SELECT comp_plp(1)");
        assertEquals("(1, x)", getStringValue(1));
        executeQuery("SELECT (comp_plp(1)).a");
        assertEquals(1, getIntValue(1));
        executeQuery("SELECT (comp_sql(2)).b");
        assertEquals("y", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_atomic(i INT) RETURNS comp LANGUAGE sql "
                + "BEGIN ATOMIC SELECT i, 'z'; END");
        executeQuery("SELECT (comp_atomic(2)).b");
        assertEquals("z", getStringValue(1));
        try (ResultSet result = stmt.executeQuery("SELECT * FROM comp_plp(3)")) {
            assertTrue(result.next());
            assertEquals(3, result.getInt(1));
            assertEquals("x", result.getString(2));
            assertEquals("A", result.getMetaData().getColumnName(1));
            assertEquals("B", result.getMetaData().getColumnName(2));
            assertFalse(result.next());
        }
        executeQuery("SELECT CAST(ROW(4, 'z') AS comp)");
        assertEquals("(4, z)", getStringValue(1));
        executeQuery("SELECT (CAST(ROW(5, 'w') AS comp)).a");
        assertEquals(5, getIntValue(1));
        executeQuery("SELECT (ROW(8, 'z')::comp).b");
        assertEquals("z", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_arg(c comp) RETURNS INT LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN (c).a; END; $$");
        executeQuery("SELECT comp_arg(ROW(7, 'q')::comp)");
        assertEquals(7, getIntValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_arg2(c comp) RETURNS VARCHAR LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN c.b; END; $$");
        executeQuery("SELECT comp_arg2(ROW(7, 'q')::comp)");
        assertEquals("q", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_arg_sql(c comp) RETURNS INT LANGUAGE sql "
                + "AS $$ SELECT (c).a $$");
        executeQuery("SELECT comp_arg_sql(ROW(9, 'r')::comp)");
        assertEquals(9, getIntValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_arg_sql2(c comp) RETURNS VARCHAR LANGUAGE sql "
                + "AS $$ SELECT c.b $$");
        executeQuery("SELECT comp_arg_sql2(ROW(9, 'r')::comp)");
        assertEquals("r", getStringValue(1));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_sql_ret(c comp) RETURNS comp LANGUAGE sql "
                + "AS $$ SELECT c $$");
        executeQuery("SELECT comp_sql_ret(ROW(10, 'p')::comp)");
        assertEquals("(10, p)", getStringValue(1));
        executeQuery("SELECT (comp_sql_ret(ROW(10, 'p')::comp)).b");
        assertEquals("p", getStringValue(1));

        executeUpdate("CREATE OR REPLACE FUNCTION comp_setof() RETURNS SETOF comp LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN QUERY SELECT 1, 'a' UNION ALL SELECT 2, 'b'; END; $$");
        try (ResultSet result = stmt.executeQuery("SELECT * FROM comp_setof() ORDER BY a")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertEquals("a", result.getString(2));
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            assertEquals("b", result.getString(2));
            assertFalse(result.next());
        }
        executeUpdate("CREATE OR REPLACE FUNCTION comp_setof_sql() RETURNS SETOF comp LANGUAGE sql "
                + "AS $$ SELECT 3, 'c' $$");
        executeQuery("SELECT * FROM comp_setof_sql()");
        assertEquals(3, getIntValue(1));
        assertEquals("c", getStringValue(2));
        executeUpdate("CREATE OR REPLACE FUNCTION comp_setof_next() RETURNS SETOF comp "
                + "LANGUAGE plpgsql AS $$ BEGIN RETURN NEXT ROW(5, 'n')::comp; END; $$");
        executeQuery("SELECT * FROM comp_setof_next()");
        assertEquals(5, getIntValue(1));
        assertEquals("n", getStringValue(2));

        boolean sawType = false;
        boolean sawReturn = false;
        try (ResultSet result = stmt.executeQuery("SCRIPT")) {
            while (result.next()) {
                String line = result.getString(1);
                if (line != null && line.startsWith("CREATE TYPE"))
                    sawType = true;
                if (line != null && line.contains("RETURNS COMP"))
                    sawReturn = true;
            }
        }
        assertTrue(sawType);
        assertTrue(sawReturn);

        executeUpdate("DROP FUNCTION comp_plp");
        executeUpdate("DROP FUNCTION comp_sql");
        executeUpdate("DROP FUNCTION comp_atomic");

        executeUpdate("DROP FUNCTION comp_arg");
        executeUpdate("DROP FUNCTION comp_arg2");
        executeUpdate("DROP FUNCTION comp_arg_sql");
        executeUpdate("DROP FUNCTION comp_arg_sql2");
        executeUpdate("DROP FUNCTION comp_sql_ret");
        executeUpdate("DROP FUNCTION comp_setof");
        executeUpdate("DROP FUNCTION comp_setof_sql");
        executeUpdate("DROP FUNCTION comp_setof_next");
        executeUpdate("DROP TYPE comp");
    }
}
