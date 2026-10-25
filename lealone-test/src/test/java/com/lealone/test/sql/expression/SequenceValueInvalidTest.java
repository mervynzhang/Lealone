/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.expression;

import java.lang.reflect.Field;

import org.junit.Test;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.Database;
import com.lealone.db.DbObjectBase;
import com.lealone.db.LealoneDatabase;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.Sequence;
import com.lealone.db.session.ServerSession;
import com.lealone.sql.expression.SequenceValue;
import com.lealone.test.sql.SqlTestBase;

/**
 * 序列被失效（并发 drop/alter 之后语句里还挂着旧对象）、并且按名字也找不回来时，
 * 必须报"序列不存在"，不能把 {@code getNewSequence()} 返回的 null 赋回字段再调用 {@code getNext} ——
 * 那样用户看到的是裸空指针。
 * <p>
 * 实测来源：全量套件里 {@code SequenceLockedExceptionTest} 偶发
 * {@code NullPointerException: Cannot invoke "Sequence.getNext(...)" because "this.sequence" is null}。
 */
public class SequenceValueInvalidTest extends SqlTestBase {

    public SequenceValueInvalidTest() {
        setEmbedded(true);
    }

    @Test
    public void testReportsSequenceNotFoundInsteadOfNpe() throws Exception {
        executeUpdate("DROP SEQUENCE IF EXISTS sv_lost");
        executeUpdate("CREATE SEQUENCE sv_lost START WITH 1 INCREMENT BY 1");
        executeQuery("SELECT NEXT VALUE FOR sv_lost");
        closeResultSet();

        Database db = LealoneDatabase.getInstance().findDatabase(dbName.toUpperCase());
        assertNotNull("要能拿到当前库", db);
        ServerSession session = db.createSession(db.getSystemUser());
        try {
            Schema schema = db.findSchema(session, "PUBLIC");
            Sequence seq = schema.findSequence(session, "SV_LOST");
            assertNotNull("先要能拿到真实的序列对象", seq);

            // 造出"旧对象已失效、按名字也找不回来"的状态：
            // 1) 把序列从 schema 里删掉 → getNewSequence() 会返回 null
            executeUpdate("DROP SEQUENCE sv_lost");
            // 2) 把旧对象的 id 置成 -1 → isInvalid() 为 true（invalidate() 会把 name 也清掉，所以直接改 id）
            Field idField = DbObjectBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.setInt(seq, -1);

            Throwable caught = null;
            try {
                new SequenceValue(seq).getValue(session);
            } catch (Throwable t) {
                caught = t;
            }
            assertNotNull("必须抛异常", caught);
            assertTrue("必须是 DbException 而不是裸 NPE, 实际: " + caught, caught instanceof DbException);
            DbException de = (DbException) caught;
            assertEquals("错误码应是序列不存在", ErrorCode.SEQUENCE_NOT_FOUND_1, de.getErrorCode());
        } finally {
            session.close();
        }
    }
}
