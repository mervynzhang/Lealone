package com.lealone.test.aote;

import org.junit.Test;
import com.lealone.transaction.Transaction;

/**
 * 重复回滚同一个事务的语义。
 * <p>
 * 一个事务实例只会被回滚一次（session 级的 rollback 之后就把 transaction 置空了），
 * 所以重复回滚属于编程错误，应该像原来那样抛 "Transaction is closed"，
 * 而不是静默当空操作——静默会掩盖调用方的逻辑错误。
 * <p>
 * 历史说明：当初加静默守卫那次提交(137de5658)的理由在**当时**并非无稽之谈——
 * 那时 removeTransaction 还不是幂等的(无条件下减计数、无条件清 bit 槽位)，
 * 而 rollback 的 finally 在 checkNotClosed 抛异常后仍会执行，确实会重复递减、
 * 还可能清掉已被新事务复用的槽位。第二天 2793b7322 把 removeTransaction 改成幂等，
 * 才从根本上消除这个危害；此后守卫就成了冗余，并且把编程错误静默掉，所以回退了。
 * <p>
 * 这个用例锁定"即使重复回滚，也不会有副作用"这个前提：
 * removeTransaction 两个实现都做了幂等检查（槽位 tid 不匹配 / map remove 返回 null），
 * unlock 在第一次回滚时就把 locks 置 null，所以第二次的 finally 是空操作，
 * 不会重复递减事务计数，也不会解掉其他事务的锁。
 */
public class DoubleRollbackTest extends AoteTestBase {

    @Test
    public void testDoubleRollbackHasNoSideEffect() {
        int before = te.currentTransactions().size();

        Transaction t = te.beginTransaction();
        assertEquals(before + 1, te.currentTransactions().size());

        t.rollback();
        int afterRollback = te.currentTransactions().size();
        assertEquals(before, afterRollback);

        String message = null;
        try {
            t.rollback();
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        assertNotNull("重复回滚应该抛 IllegalStateException", message);
        assertTrue("异常信息应说明事务已关闭: " + message, message.indexOf("Transaction is closed") >= 0);

        // 重复回滚不能改变活跃事务数(否则会重复递减计数、或把槽位被复用的事务移除掉)
        assertEquals(afterRollback, te.currentTransactions().size());

        // 引擎仍可正常开启新事务
        Transaction t2 = te.beginTransaction();
        assertEquals(before + 1, te.currentTransactions().size());
        t2.rollback();
        assertEquals(before, te.currentTransactions().size());
    }
}
