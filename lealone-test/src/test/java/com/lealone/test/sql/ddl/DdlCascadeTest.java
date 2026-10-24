/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.Test;

import com.lealone.db.api.ErrorCode;
import com.lealone.test.sql.SqlAssertTestBase;

/**
 * DDL 级联与依赖：视图依赖的 ALTER/DROP COLUMN、DROP VIEW CASCADE/RESTRICT、DDL 之后 prepared statement 缓存失效。
 * <p>
 * 期望值以 SQL 标准 / H2 语义为准。曾经不符合期望的断言修复后保留为回归用例。
 */
public class DdlCascadeTest extends SqlAssertTestBase {

    private void cleanup() {
        tryExecuteUpdate("DROP VIEW IF EXISTS dc_v3");
        tryExecuteUpdate("DROP VIEW IF EXISTS dc_v2");
        tryExecuteUpdate("DROP VIEW IF EXISTS dc_v1");
        tryExecuteUpdate("DROP TABLE IF EXISTS dc_t");
        tryExecuteUpdate("DROP TABLE IF EXISTS dc_ps");
    }

    private void initViewChain() {
        executeUpdate("CREATE TABLE dc_t(a int primary key, b int)");
        executeUpdate("INSERT INTO dc_t VALUES(1, 10)");
        executeUpdate("CREATE VIEW dc_v1 AS SELECT a, b FROM dc_t");
        executeUpdate("CREATE VIEW dc_v2 AS SELECT a FROM dc_v1");
        executeUpdate("CREATE VIEW dc_v3 AS SELECT a FROM dc_v2");
    }

    @Test
    public void dropTableWithDependentViewFails() {
        cleanup();
        initViewChain();
        assertSqlFails("DROP TABLE dc_t", ErrorCode.CANNOT_DROP_2);
        // 失败之后表、视图都还在，而且仍然可用
        assertQueryEquals(1L, "SELECT COUNT(*) FROM dc_t");
        assertQueryEquals(1L, "SELECT COUNT(*) FROM dc_v3");
        cleanup();
    }

    @Test
    public void dropTableCascadeDropsDependentViews() {
        cleanup();
        initViewChain();
        executeUpdate("DROP TABLE dc_t CASCADE");
        // 直接和间接依赖的视图都被级联删除
        assertSqlFails("SELECT * FROM dc_v1", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        assertSqlFails("SELECT * FROM dc_v2", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        assertSqlFails("SELECT * FROM dc_v3", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        cleanup();
    }

    @Test
    public void dropViewWithDependentView() {
        cleanup();
        initViewChain();
        // 默认（等价于 RESTRICT）：有依赖视图时拒绝删除
        assertSqlFails("DROP VIEW dc_v1", ErrorCode.CANNOT_DROP_2);
        assertSqlFails("DROP VIEW dc_v1 RESTRICT", ErrorCode.CANNOT_DROP_2);
        assertSqlFails("DROP VIEW dc_v2", ErrorCode.CANNOT_DROP_2);
        // 依赖视图还在
        assertQueryEquals(1L, "SELECT COUNT(*) FROM dc_v3");
        // CASCADE 只删依赖视图，不动底层表
        executeUpdate("DROP VIEW dc_v1 CASCADE");
        assertSqlFails("SELECT * FROM dc_v2", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        assertSqlFails("SELECT * FROM dc_v3", ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1);
        assertQueryEquals(1L, "SELECT COUNT(*) FROM dc_t");
        cleanup();
    }

    @Test
    public void dropColumnNotReferencedByView() {
        cleanup();
        executeUpdate("CREATE TABLE dc_t(a int primary key, b int)");
        executeUpdate("INSERT INTO dc_t VALUES(1, 10)");
        executeUpdate("CREATE VIEW dc_v1 AS SELECT a FROM dc_t");
        executeUpdate("ALTER TABLE dc_t DROP COLUMN b");
        assertQueryEquals(1, "SELECT a FROM dc_t");
        assertQueryEquals(1, "SELECT a FROM dc_v1");
        // 被删掉的列确实不存在了
        assertSqlFails("SELECT b FROM dc_t", ErrorCode.COLUMN_NOT_FOUND_1);
        cleanup();
    }

    @Test
    public void dropColumnReferencedByViewIsRejectedAndKeepsState() {
        cleanup();
        executeUpdate("CREATE TABLE dc_t(a int primary key, b int)");
        executeUpdate("INSERT INTO dc_t VALUES(1, 10)");
        executeUpdate("CREATE VIEW dc_v1 AS SELECT b FROM dc_t");
        // 被视图引用的列不能删（无论错误码是什么，这个 DDL 必须被拒绝）
        SQLException e = assertSqlFails("ALTER TABLE dc_t DROP COLUMN b");
        assertNotNull(e);
        // 拒绝之后列还在、数据还在、视图还能查
        assertQueryEquals(10, "SELECT b FROM dc_t");
        assertQueryEquals(10, "SELECT b FROM dc_v1");
        cleanup();
    }

    @Test
    public void addColumnKeepsViewValid() {
        cleanup();
        executeUpdate("CREATE TABLE dc_t(a int primary key)");
        executeUpdate("INSERT INTO dc_t VALUES(1)");
        executeUpdate("CREATE VIEW dc_v1 AS SELECT a FROM dc_t");
        executeUpdate("ALTER TABLE dc_t ADD COLUMN c varchar(10)");
        executeUpdate("UPDATE dc_t SET c = 'x'");
        assertQueryEquals("x", "SELECT c FROM dc_t");
        assertQueryEquals(1, "SELECT a FROM dc_v1");
        cleanup();
    }

    @Test
    public void preparedStatementWithoutDdl() throws Exception {
        cleanup();
        executeUpdate("CREATE TABLE dc_ps(a int)");
        executeUpdate("INSERT INTO dc_ps VALUES(1)");
        executeUpdate("INSERT INTO dc_ps VALUES(2)");
        try (PreparedStatement ps = conn.prepareStatement("SELECT a FROM dc_ps ORDER BY a")) {
            for (int i = 0; i < 3; i++) {
                try (ResultSet r = ps.executeQuery()) {
                    assertEquals(1, r.getMetaData().getColumnCount());
                    assertTrue(r.next());
                    assertEquals(1, r.getInt(1));
                    assertTrue(r.next());
                    assertEquals(2, r.getInt(1));
                    assertFalse(r.next());
                }
            }
        }
        cleanup();
    }

    @Test
    public void newPreparedStatementAfterDdl() throws Exception {
        cleanup();
        executeUpdate("CREATE TABLE dc_ps(a int)");
        executeUpdate("INSERT INTO dc_ps VALUES(1)");
        executeUpdate("ALTER TABLE dc_ps ADD COLUMN b int");
        executeUpdate("UPDATE dc_ps SET b = 9");
        // DDL 之后重新 prepare 的语句必须看到新结构
        try (PreparedStatement ps = conn.prepareStatement("SELECT a, b FROM dc_ps")) {
            try (ResultSet r = ps.executeQuery()) {
                assertEquals(2, r.getMetaData().getColumnCount());
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertEquals(9, r.getInt(2));
            }
        }
        cleanup();
    }

    // ------------------------------------------------------------------
    // 以下用例曾经是缺陷，现已修复（期望值按 SQL 标准与 H2 2.4.240 实测）
    // ------------------------------------------------------------------

    // ALTER TABLE ... DROP COLUMN 被视图引用时，报 90083 COLUMN_IS_REFERENCED_1，
    // 信息形如 "Column may be referenced by "DC_V1""，不能把 DDL 语句本身当成视图名。
    @Test
    public void dropColumnReferencedByViewErrorCode() {
        cleanup();
        executeUpdate("CREATE TABLE dc_t(a int primary key, b int)");
        executeUpdate("CREATE VIEW dc_v1 AS SELECT b FROM dc_t");
        SQLException e = assertSqlFails("ALTER TABLE dc_t DROP COLUMN b");
        assertEquals(ErrorCode.COLUMN_IS_REFERENCED_1, e.getErrorCode());
        String msg = String.valueOf(e.getMessage());
        assertTrue("message should name the dependent view DC_V1 but was: " + msg,
                msg.toUpperCase().contains("DC_V1"));
        assertFalse("message should not contain the DDL statement text but was: " + msg,
                msg.toUpperCase().contains("ALTER TABLE"));
        cleanup();
    }

    // 表结构变更后 prepared statement 必须重新编译：
    // ALTER TABLE ADD COLUMN 之后，同一条 PreparedStatement 重新执行时能看到 2 列（与 H2 一致）。
    @Test
    public void preparedStatementSeesAddedColumn() throws Exception {
        cleanup();
        executeUpdate("CREATE TABLE dc_ps(a int)");
        executeUpdate("INSERT INTO dc_ps VALUES(1)");
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dc_ps")) {
            try (ResultSet r = ps.executeQuery()) {
                assertEquals(1, r.getMetaData().getColumnCount());
            }
            executeUpdate("ALTER TABLE dc_ps ADD COLUMN b int");
            executeUpdate("UPDATE dc_ps SET b = 9");
            try (ResultSet r = ps.executeQuery()) {
                assertEquals(2, r.getMetaData().getColumnCount());
                assertTrue(r.next());
                assertEquals(9, r.getInt(2));
            }
        }
        cleanup();
    }

    // 列被删掉后，缓存里的 prepared statement 重新编译，
    // 报 42122 COLUMN_NOT_FOUND_1，而不是静默返回"旧结构 + 0 行"（与 H2 一致）。
    @Test
    public void preparedStatementAfterDroppedColumn() throws Exception {
        cleanup();
        executeUpdate("CREATE TABLE dc_ps(a int, b int)");
        executeUpdate("INSERT INTO dc_ps VALUES(1, 2)");
        try (PreparedStatement ps = conn.prepareStatement("SELECT b FROM dc_ps WHERE b=?")) {
            ps.setInt(1, 2);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
            }
            executeUpdate("ALTER TABLE dc_ps DROP COLUMN b");
            ps.setInt(1, 2);
            try (ResultSet r = ps.executeQuery()) {
                int rows = 0;
                while (r.next())
                    rows++;
                fail("expected 42122 after DROP COLUMN, but the cached statement returned " + rows
                        + " rows with " + r.getMetaData().getColumnCount() + " columns");
            } catch (SQLException e) {
                assertEquals(ErrorCode.COLUMN_NOT_FOUND_1, e.getErrorCode());
            }
        }
        cleanup();
    }

    // DROP TABLE 之后重建同名表，缓存里的 prepared statement 要重新编译，
    // 返回新表的 2 列 1 行，而不是按旧表结构返回"1 列 0 行"（与 H2 一致）。
    @Test
    public void preparedStatementAfterDropAndRecreateTable() throws Exception {
        cleanup();
        executeUpdate("CREATE TABLE dc_ps(a int)");
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dc_ps")) {
            try (ResultSet r = ps.executeQuery()) {
                assertFalse(r.next());
            }
            executeUpdate("DROP TABLE dc_ps");
            executeUpdate("CREATE TABLE dc_ps(a int, b int)");
            executeUpdate("INSERT INTO dc_ps VALUES(1, 2)");
            try (ResultSet r = ps.executeQuery()) {
                assertEquals(2, r.getMetaData().getColumnCount());
                assertTrue("stale plan swallowed the new row", r.next());
            }
        }
        cleanup();
    }

    // CREATE INDEX建在非空表上时索引是异步回填的，构建期间不能拿它来满足排序
    @Test
    public void orderByAfterCreateIndexOnExistingRows() throws Exception {
        cleanup();
        executeUpdate("DROP TABLE IF EXISTS dc_ix");
        executeUpdate("CREATE TABLE dc_ix(id int primary key, name varchar(10))");
        executeUpdate("INSERT INTO dc_ix VALUES(1, 'b'), (2, 'a'), (3, 'd'), (4, 'c')");
        executeUpdate("CREATE INDEX dc_ix_name ON dc_ix(name)");
        try (ResultSet r = stmt.executeQuery("SELECT name FROM dc_ix ORDER BY name")) {
            StringBuilder buff = new StringBuilder();
            while (r.next())
                buff.append(r.getString(1));
            assertEquals("abcd", buff.toString());
        }
        executeUpdate("DROP TABLE IF EXISTS dc_ix");
        cleanup();
    }
}
