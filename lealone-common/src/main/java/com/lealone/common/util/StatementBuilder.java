/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.common.util;

/**
 * A utility class to build a statement. In addition to the methods supported by
 * StringBuilder, it allows to add a text only in the second iteration. This
 * simplified constructs such as:
 * <pre>
 * StringBuilder buff = new StringBuilder();
 * for (int i = 0; i &lt; args.length; i++) {
 *     if (i &gt; 0) {
 *         buff.append(&quot;, &quot;);
 *     }
 *     buff.append(args[i]);
 * }
 * </pre>
 * to
 * <pre>
 * StatementBuilder buff = new StatementBuilder();
 * for (String s : args) {
 *     buff.appendExceptFirst(&quot;, &quot;);
 *     buff.append(a);
 * }
 *</pre>
 */
public class StatementBuilder {

    private final StringBuilder builder = new StringBuilder();
    private int index;
    private boolean distributed;
    private boolean enclosed = true;

    /**
     * Create a new builder.
     */
    public StatementBuilder() {
        // nothing to do
    }

    /**
     * Create a new builder.
     *
     * @param string the initial string
     */
    public StatementBuilder(String string) {
        builder.append(string);
    }

    public boolean isDistributed() {
        return distributed;
    }

    public void setDistributed(boolean distributed) {
        this.distributed = distributed;
    }

    public boolean isEnclosed() {
        return enclosed;
    }

    /**
     * 打开/关闭“可省略的括号”。
     * <p>
     * 注意这里只是<b>可选</b>括号：某些位置（例如整条 WHERE 条件的最外层）多一对括号是冗余的，
     * 打印 plan SQL 时可以省掉。但这个开关会沿调用链继承下去，因此只能用来控制“在当前上下文里
     * 可有可无”的括号；一旦某个括号是重新解析所必需的（行值/列表构造、标量子查询、嵌套的
     * 算术表达式、NOT 里的 AND/OR 组合等），就必须用 {@link #enBegin(boolean)} 传 true 强制输出，
     * 否则 enclosed=false 会把子节点的括号一起吞掉，打印出的 SQL 无法再解析成等价语义。
     */
    public void setEnclosed(boolean enclosed) {
        this.enclosed = enclosed;
    }

    /**
     * 输出一对“可省略”的括号：是否真的输出由 {@link #setEnclosed(boolean)} 决定。
     * 与 {@code enBegin(false)} 等价。
     */
    public StatementBuilder enBegin() {
        return enBegin(false);
    }

    /**
     * 输出一对括号。
     *
     * @param required true 表示这对括号是重新解析所必需的，无视 enclosed 开关，始终输出
     */
    public StatementBuilder enBegin(boolean required) {
        if (required || enclosed)
            append('(');
        return this;
    }

    /**
     * 与 {@link #enBegin()} 配对。
     */
    public StatementBuilder enEnd() {
        return enEnd(false);
    }

    /**
     * 与 {@link #enBegin(boolean)} 配对，required 必须与对应的 enBegin 一致。
     *
     * @param required true 表示这对括号是重新解析所必需的，无视 enclosed 开关，始终输出
     */
    public StatementBuilder enEnd(boolean required) {
        if (required || enclosed)
            append(')');
        return this;
    }

    /**
     * Append a text.
     *
     * @param s the text to append
     * @return itself
     */
    public StatementBuilder append(String s) {
        builder.append(s);
        return this;
    }

    /**
     * Append a character.
     *
     * @param c the character to append
     * @return itself
     */
    public StatementBuilder append(char c) {
        builder.append(c);
        return this;
    }

    /**
     * Append a number.
     *
     * @param x the number to append
     * @return itself
     */
    public StatementBuilder append(long x) {
        builder.append(x);
        return this;
    }

    /**
     * Reset the loop counter.
     *
     * @return itself
     */
    public StatementBuilder resetCount() {
        index = 0;
        return this;
    }

    /**
     * Append a text, but only if appendExceptFirst was never called.
     *
     * @param s the text to append
     */
    public StatementBuilder appendOnlyFirst(String s) {
        if (index == 0) {
            builder.append(s);
        }
        return this;
    }

    /**
     * Append a text, except when this method is called the first time.
     *
     * @param s the text to append
     */
    public StatementBuilder appendExceptFirst(String s) {
        if (index++ > 0) {
            builder.append(s);
        }

        return this;
    }

    @Override
    public String toString() {
        return builder.toString();
    }

    /**
     * Get the length.
     *
     * @return the length
     */
    public int length() {
        return builder.length();
    }

}
