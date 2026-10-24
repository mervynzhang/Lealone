/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import org.junit.Ignore;
import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * 已知缺陷（FIX_LOG #51）：事务里删除父行触发 {@code ON DELETE CASCADE} 后回滚，
 * **父行会恢复，被级联删掉的子行却永久丢失**。
 * <p>
 * 复现步骤（已用独立探针确认）：
 * <pre>
 * CREATE TABLE p(id int primary key);
 * CREATE TABLE ch(id int primary key, pid int, FOREIGN KEY(pid) REFERENCES p(id) ON DELETE CASCADE);
 * INSERT INTO p VALUES(1),(2); INSERT INTO ch VALUES(11,1),(12,1),(13,2);
 * BEGIN; DELETE FROM p WHERE id = 1; ROLLBACK;
 * -- 期望子行 11,12 恢复；实际只剩 13（父行 1 恢复了）
 * </pre>
 * 对照：不带外键的普通删除在同一事务里回滚是正常的，说明问题出在级联路径
 * （级联删除走的是 {@code ConstraintReferential.updateWithSkipCheck} → {@code PreparedSQLStatement.update()}）。
 * <p>
 * 因为这是**尚未修复**的缺陷，用例先标记为 {@code @Ignore}，避免让套件常红；
 * 修复后去掉 {@code @Ignore} 即可作为回归用例。
 */
public class CascadeDeleteRollbackTest extends SqlTestBase {

    public CascadeDeleteRollbackTest() {
        setEmbedded(true);
    }


    @Ignore("FIX_LOG #51: 未修复的已知缺陷(级联删除回滚后子行丢失)")
    @Test
    public void testCascadeDeleteIsRollbackable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS cdr_ch");
        executeUpdate("DROP TABLE IF EXISTS cdr_p");
        executeUpdate("CREATE TABLE cdr_p(id int primary key)");
        executeUpdate("CREATE TABLE cdr_ch(id int primary key, pid int,"
                + " FOREIGN KEY(pid) REFERENCES cdr_p(id) ON DELETE CASCADE)");
        executeUpdate("INSERT INTO cdr_p VALUES(1),(2)");
        executeUpdate("INSERT INTO cdr_ch VALUES(11,1),(12,1),(13,2)");

        conn.setAutoCommit(false);
        executeUpdate("DELETE FROM cdr_p WHERE id = 1");
        assertEquals("级联删除在事务内生效", 1, count("cdr_ch"));
        conn.rollback();
        conn.setAutoCommit(true);

        assertEquals("回滚后父行必须恢复", 2, count("cdr_p"));
        assertEquals("回滚后子行必须恢复", 3, count("cdr_ch"));
    }

    private int count(String table) throws Exception {
        executeQuery("SELECT COUNT(*) FROM " + table);
        try {
            return rs.getInt(1);
        } finally {
            closeResultSet();
        }
    }
}
