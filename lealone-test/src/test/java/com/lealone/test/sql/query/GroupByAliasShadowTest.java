/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.query;

import org.junit.Test;

import com.lealone.test.sql.SqlTestBase;

/**
 * GROUP BY 里的名字要和输入列绑定, 不能因为某个输出列的别名"恰好"同名就绑到那个输出表达式上。
 * 出问题的写法(SLTE语料 slt_good_12.test 里就是这样):
 * SELECT col1 AS col0, -col0 AS col1 FROM t GROUP BY col1, col0
 * 期望按 (col1, col0) 分 3 组, 以前只返回 2 组(静默少行)。
 */
public class GroupByAliasShadowTest extends SqlTestBase {

    public GroupByAliasShadowTest() {
        setEmbedded(true);
    }

    @Test
    public void testGroupByColumnWinsOverAlias() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS gbas_t");
        executeUpdate("CREATE TABLE gbas_t(col0 int, col1 int, col2 int)");
        executeUpdate("INSERT INTO gbas_t VALUES(83, 0, 38)");
        executeUpdate("INSERT INTO gbas_t VALUES(26, 0, 79)");
        executeUpdate("INSERT INTO gbas_t VALUES(43, 81, 24)");

        // 输入列优先于别名: 必须按 (col1, col0) 分 3 组
        assertEquals("别名遮蔽输入列时不能少分组", 3,
                count("SELECT col1 AS col0, -col0 AS col1 FROM gbas_t GROUP BY col1, col0"));
        // 不遮蔽时同样 3 组
        assertEquals(3, count("SELECT col1 AS a, col0 AS b FROM gbas_t GROUP BY col1, col0"));
        // 纯别名分组(GROUP BY 里写的不是输入列)仍然要能用
        assertEquals(3, count("SELECT col0 AS x FROM gbas_t GROUP BY x"));
        assertEquals(3, count("SELECT col0 AS x FROM gbas_t GROUP BY col0"));
    }

    private int count(String sql) throws Exception {
        executeQuery(sql);
        int n = 0;
        try {
            boolean hasRow = true;
            while (hasRow) {
                n++;
                hasRow = rs.next();
            }
        } finally {
            closeResultSet();
        }
        return n;
    }
}
