/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

public class TriggerFunctionTest extends SqlTestBase {

    public TriggerFunctionTest() {
        setEmbedded(true);
    }

    @Test
    public void testRowTriggers() throws Exception {
        executeUpdate("CREATE TABLE tr_room(roomno INT)");
        executeUpdate("CREATE TABLE tr_wslot(roomno INT)");
        executeUpdate("CREATE FUNCTION tr_room_au() RETURNS TRIGGER AS $$ " //
                + "BEGIN " //
                + "IF NEW.roomno != OLD.roomno THEN " //
                + "UPDATE tr_wslot SET roomno = NEW.roomno WHERE roomno = OLD.roomno; " //
                + "END IF; " //
                + "RETURN NEW; " //
                + "END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_room_au AFTER UPDATE ON tr_room FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_room_au()");
        conn.commit();
        executeUpdate("CREATE FUNCTION tr_wslot_biu() RETURNS TRIGGER AS $$ " //
                + "BEGIN " //
                + "IF NOT EXISTS (SELECT 1 FROM tr_room WHERE roomno = NEW.roomno) THEN " //
                + "RAISE EXCEPTION 'Room % does not exist', NEW.roomno; " //
                + "END IF; " //
                + "RETURN NEW; " //
                + "END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_wslot_biu BEFORE INSERT OR UPDATE ON tr_wslot "
                + "FOR EACH ROW EXECUTE PROCEDURE tr_wslot_biu()");
        conn.commit();

        executeUpdate("INSERT INTO tr_room VALUES(1)");
        executeUpdate("INSERT INTO tr_room VALUES(2)");
        executeUpdate("INSERT INTO tr_wslot VALUES(1)");
        executeUpdate("UPDATE tr_room SET roomno = 2 WHERE roomno = 1");
        executeQuery("SELECT roomno FROM tr_wslot");
        assertEquals(2, getIntValue(1));
        executeUpdateThanAssertErrorCode("INSERT INTO tr_wslot VALUES(5)",
                com.lealone.db.api.ErrorCode.GENERAL_ERROR_1);
    }

    @Test
    public void testBeforeTriggerWritesBackNewRow() throws Exception {
        executeUpdate("CREATE TABLE tr_mod(id INT, name VARCHAR(20))");
        executeUpdate("CREATE FUNCTION tr_mod() RETURNS TRIGGER AS $$ " //
                + "BEGIN NEW.name := 'x' || NEW.name; RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_mod BEFORE INSERT ON tr_mod FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_mod()");
        conn.commit();
        executeUpdate("INSERT INTO tr_mod VALUES(1, 'a')");
        executeQuery("SELECT name FROM tr_mod");
        assertEquals("xa", getStringValue(1));
    }

    @Test
    public void testReturnOldAllowsDelete() throws Exception {
        executeUpdate("CREATE TABLE tr_del(id INT)");
        executeUpdate("CREATE TABLE tr_del_audit(id INT)");
        executeUpdate("CREATE FUNCTION tr_del() RETURNS TRIGGER AS $$ " //
                + "BEGIN INSERT INTO tr_del_audit VALUES(OLD.id); RETURN OLD; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_del BEFORE DELETE ON tr_del FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_del()");
        conn.commit();
        executeUpdate("INSERT INTO tr_del VALUES(1),(2)");
        executeUpdate("DELETE FROM tr_del WHERE id = 1");
        executeQuery("SELECT COUNT(*) FROM tr_del");
        assertEquals(1, getIntValue(1));
        executeQuery("SELECT id FROM tr_del_audit");
        assertEquals(1, getIntValue(1));
    }

    @Test
    public void testTriggerPersistsAfterReopen() throws Exception {
        executeUpdate("CREATE TABLE tr_persist(id INT)");
        executeUpdate("CREATE TABLE tr_persist_audit(id INT)");
        executeUpdate("CREATE FUNCTION tr_persist() RETURNS TRIGGER AS $$ " //
                + "BEGIN INSERT INTO tr_persist_audit VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_persist BEFORE INSERT ON tr_persist FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_persist()");
        conn.commit();
        // 关闭再打开同一个库，函数和触发器要从库里恢复
        conn.close();
        conn = getConnection();
        stmt = conn.createStatement();
        executeUpdate("INSERT INTO tr_persist VALUES(9)");
        executeQuery("SELECT COUNT(*) FROM tr_persist_audit");
        assertEquals(1, getIntValue(1));
    }

    @Test
    public void testTriggerRollbackDoesNotLeak() throws Exception {
        executeUpdate("CREATE TABLE tr_tx(id INT)");
        executeUpdate("CREATE TABLE tr_tx_audit(id INT)");
        executeUpdate("CREATE FUNCTION tr_tx() RETURNS TRIGGER AS $$ " //
                + "BEGIN INSERT INTO tr_tx_audit VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_tx BEFORE INSERT ON tr_tx FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_tx()");
        conn.commit();
        conn.setAutoCommit(false);
        executeUpdate("INSERT INTO tr_tx VALUES(1)");
        conn.rollback();
        conn.setAutoCommit(true);
        executeQuery("SELECT COUNT(*) FROM tr_tx");
        assertEquals(0, getIntValue(1));
        executeQuery("SELECT COUNT(*) FROM tr_tx_audit");
        assertEquals(0, getIntValue(1));
    }

    @Test
    public void testBeforeTriggerFailureRollsBackAudit() throws Exception {
        executeUpdate("CREATE TABLE tr_atomic(id INT PRIMARY KEY)");
        executeUpdate("CREATE TABLE tr_atomic_audit(id INT)");
        executeUpdate("CREATE FUNCTION tr_atomic() RETURNS TRIGGER AS $$ " //
                + "BEGIN INSERT INTO tr_atomic_audit VALUES(NEW.id); RETURN NEW; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_atomic BEFORE INSERT ON tr_atomic FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_atomic()");
        conn.commit();
        executeUpdate("INSERT INTO tr_atomic VALUES(1)");
        try {
            executeUpdate("INSERT INTO tr_atomic VALUES(1)");
            fail();
        } catch (RuntimeException e) {
            // 主键重复，第二条INSERT失败
        }
        executeQuery("SELECT COUNT(*) FROM tr_atomic_audit");
        assertEquals(1, getIntValue(1));
    }

    @Test
    public void testReturnNullCancelsBeforeRow() throws Exception {
        executeUpdate("CREATE TABLE tr_skip(id INT)");
        executeUpdate("CREATE FUNCTION tr_skip() RETURNS TRIGGER AS $$ " //
                + "BEGIN RETURN NULL; END; $$ LANGUAGE plpgsql");
        executeUpdate("CREATE TRIGGER tr_skip BEFORE INSERT ON tr_skip FOR EACH ROW "
                + "EXECUTE PROCEDURE tr_skip()");
        executeUpdate("INSERT INTO tr_skip VALUES(1)");
        executeQuery("SELECT COUNT(*) FROM tr_skip");
        assertEquals(0, getIntValue(1));
    }
}
