/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.sql.expression;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.schema.Sequence;
import com.lealone.db.session.ServerSession;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueLong;
import com.lealone.sql.expression.visitor.ExpressionVisitor;

/**
 * Wraps a sequence when used in a statement.
 */
public class SequenceValue extends Expression {

    private Sequence sequence;

    public SequenceValue(Sequence sequence) {
        this.sequence = sequence;
    }

    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public Value getValue(ServerSession session) {
        if (sequence.isInvalid()) {
            // 序列被失效(比如并发 drop/alter 之后旧对象还挂在语句里)时, 重新按名字查一次;
            // 如果查不到说明它确实已经不在了 —— 这时必须报"序列不存在", 不能把 null 赋回去
            // 再用它调用 getNext, 那样用户看到的是裸空指针(实测在并发用例里偶发出现过)。
            Sequence newSequence = sequence.getNewSequence(session);
            if (newSequence == null)
                throw DbException.get(ErrorCode.SEQUENCE_NOT_FOUND_1, sequence.getName());
            sequence = newSequence;
        }
        long value = sequence.getNext(session);
        session.setLastIdentity(value);
        return ValueLong.get(value);
    }

    @Override
    public int getType() {
        return Value.LONG;
    }

    @Override
    public Expression optimize(ServerSession session) {
        return this;
    }

    @Override
    public int getScale() {
        return 0;
    }

    @Override
    public long getPrecision() {
        return ValueInt.PRECISION;
    }

    @Override
    public int getDisplaySize() {
        return ValueInt.DISPLAY_SIZE;
    }

    @Override
    public void getSQL(StatementBuilder sql) {
        sql.append("(NEXT VALUE FOR ").append(sequence.getSQL()).append(')');
    }

    @Override
    public int getCost() {
        return 1;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitSequenceValue(this);
    }
}
