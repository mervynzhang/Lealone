/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zzh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.Test;

import com.lealone.db.api.Trigger;
import com.lealone.db.value.ValueInt;
import com.lealone.sql.StatementBase;
import com.lealone.test.db.DbObjectTestBase;

/**
 * 语句因 DDL 而重新编译时，必须保留已经设置好的参数值。
 * <p>
 * 这个问题最早是通过全文检索插件暴露的：插件在触发器里执行 DDL(建 FT.* 元数据表)，
 * 使正在执行的 INSERT 语句失效；重新编译出来的新语句参数是空的，执行时报
 * {@code Parameter "#1" is not set}。
 */
public class PreparedStatementRecompileTest extends DbObjectTestBase {

    /** 空触发器: 只为了让建触发器时的 DDL 修改表的元数据，使已缓存的语句失效。 */
    public static class NoopTrigger implements Trigger {

        @Override
        public void init(Connection conn, String schemaName, String triggerName, String tableName,
                boolean before, int type) throws SQLException {
        }

        @Override
        public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        }

        @Override
        public void close() throws SQLException {
        }

        @Override
        public void remove() throws SQLException {
        }
    }

    @Test
    public void recompiledStatementKeepsParameterValues() {
        executeUpdate("DROP TABLE IF EXISTS pr_t");
        executeUpdate("CREATE TABLE pr_t(a int primary key, b int)");

        StatementBase st = (StatementBase) session.prepareStatement("INSERT INTO pr_t(a, b) VALUES(?, ?)");
        st.getParameters().get(0).setValue(ValueInt.get(1));

        // DDL 使 modificationMetaId 变化，下一次执行前语句要重新编译
        // ALTER TABLE 会修改表的元数据(modificationMetaId 变化), 让缓存的语句失效
        executeUpdate("ALTER TABLE pr_t ADD COLUMN c int");

        st.getParameters().get(1).setValue(ValueInt.get(20));
        StatementBase recompiled = st.recompileIfRequired();
        if (recompiled == st)
            throw new AssertionError("DDL 之后语句应该重新编译");
        if (!recompiled.getParameters().get(0).isValueSet())
            throw new AssertionError("重新编译之后第 1 个参数的值丢失了");
        if (!recompiled.getParameters().get(1).isValueSet())
            throw new AssertionError("重新编译之后第 2 个参数的值丢失了");

        recompiled.executeUpdate();

        int b = getInt("SELECT b FROM pr_t WHERE a=1", 1);
        if (b != 20)
            throw new AssertionError("插入的行参数值不对, 期望 20, 实际 " + b);

        executeUpdate("DROP TABLE pr_t");

    }
}
