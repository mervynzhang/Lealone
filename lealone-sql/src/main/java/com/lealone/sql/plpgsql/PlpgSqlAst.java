/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.List;

/**
 * PL/pgSQL 函数体的语法树。
 * 语法树只在内存中使用，函数体本身仍然以原始文本持久化，
 * 每次数据库启动后第一次调用函数时重新解析。
 * 表达式和SQL语句都保留原始文本，执行时再交给SQL引擎，
 * 这样PL/pgSQL的词法分析器只需要正确切分出语句边界，
 * 不用重复实现SQL语法。
 */
public final class PlpgSqlAst {

    private PlpgSqlAst() {
    }

    /** 所有语法树节点的基类 */
    public static abstract class Node {

        // 节点在函数体文本中的起始下标，用于报错
        public final int position;

        protected Node(int position) {
            this.position = position;
        }
    }

    /** 一个BEGIN...END块，可以带声明部分、异常处理部分和标签 */
    public static class Block extends Node {

        public String label;
        public final List<Declaration> declarations = new ArrayList<>();
        public final List<Node> statements = new ArrayList<>();
        public final List<Handler> handlers = new ArrayList<>();

        public Block(int position) {
            super(position);
        }
    }

    /** 嵌套块作为一条普通语句出现 */
    public static class NestedBlock extends Node {

        public final Block block;

        public NestedBlock(int position, Block block) {
            super(position);
            this.block = block;
        }
    }

    /** DECLARE部分的一个变量声明 */
    public static class Declaration extends Node {

        public String name;
        public boolean nameQuoted;
        public boolean constant;
        public boolean notNull;
        // 声明的类型文本，可能带精度，也可能以%TYPE结尾
        public String typeName;
        // 初始化表达式，没有初始化时为null
        public String defaultExpression;

        public Declaration(int position) {
            super(position);
        }
    }

    /** 变量赋值，例如 x := a * 2 */
    public static class Assignment extends Node {

        // 块标签限定目标 outerblock.param1
        public String qualifier;
        public boolean qualifierQuoted;
        public String target;
        public boolean targetQuoted;
        // record数组元素赋值：arr[index] 或 arr[index].field
        public String arrayIndex;
        public String arrayField;
        public boolean arrayFieldQuoted;
        public String expression;

        public Assignment(int position) {
            super(position);
        }
    }

    /** IF语句，第一个分支的条件就是IF后面的条件 */
    public static class If extends Node {

        public final List<Branch> branches = new ArrayList<>();
        public final List<Node> elseStatements = new ArrayList<>();

        public If(int position) {
            super(position);
        }
    }

    /** IF/ELSIF的一个分支 */
    public static class Branch {

        public final String condition;
        public final List<Node> statements = new ArrayList<>();

        public Branch(String condition) {
            this.condition = condition;
        }
    }

    /** RETURN语句，expression为null表示RETURN; */
    public static class Return extends Node {

        public String expression;
        // RETURN NEXT，expression为null时表示用OUT/TABLE变量组一行
        public boolean next;
        // RETURN QUERY，expression是查询文本
        public boolean query;

        public Return(int position) {
            super(position);
        }
    }

    /** 内嵌的SQL语句，SQL文本原样保存，SELECT ... INTO也在执行时才拆分 */
    public static class SqlExec extends Node {

        public final String sql;

        public SqlExec(int position, String sql) {
            super(position);
            this.sql = sql;
        }
    }

    /** GET DIAGNOSTICS语句，目前只支持ROW_COUNT */
    public static class GetDiagnostics extends Node {

        public String target;
        public boolean targetQuoted;
        // 诊断项，统一用大写，例如ROW_COUNT
        public String item;

        public GetDiagnostics(int position) {
            super(position);
        }
    }

    /** PERFORM语句，执行查询但丢弃结果 */
    public static class Perform extends Node {

        public final String query;

        public Perform(int position, String query) {
            super(position);
            this.query = query;
        }
    }

    /** 无条件循环 LOOP ... END LOOP */
    public static class Loop extends Node {

        public String label;
        public final List<Node> statements = new ArrayList<>();

        public Loop(int position) {
            super(position);
        }
    }

    /** WHILE循环 */
    public static class While extends Node {

        public String label;
        public String condition;
        public final List<Node> statements = new ArrayList<>();

        public While(int position) {
            super(position);
        }
    }

    /** FOR循环：整数范围(FOR i IN start..end)或者查询结果集(FOR rec IN <query>) */
    public static class For extends Node {

        public String label;
        public String variable;
        public boolean variableQuoted;
        public boolean reverse;
        // 查询循环的SQL，整数范围循环时为null
        public String query;
        public String startExpression;
        public String endExpression;
        // BY子句的步长表达式，没有BY子句时为null
        public String stepExpression;
        public final List<Node> statements = new ArrayList<>();

        public For(int position) {
            super(position);
        }
    }

    /** EXIT [label] [WHEN condition] */
    public static class Exit extends Node {

        public String label;
        public String condition;

        public Exit(int position) {
            super(position);
        }
    }

    /** CONTINUE [label] [WHEN condition] */
    public static class Continue extends Node {

        public String label;
        public String condition;

        public Continue(int position) {
            super(position);
        }
    }

    /** NULL语句，什么也不做 */
    public static class NullStatement extends Node {

        public NullStatement(int position) {
            super(position);
        }
    }

    /** RAISE语句 */
    public static class Raise extends Node {

        // NOTICE/EXCEPTION/WARNING/INFO/LOG/DEBUG，RAISE;重新抛出时是null
        public String level;
        // 格式字符串，没有格式字符串时为null
        public String message;
        // 格式字符串中%对应的参数表达式
        public final List<String> arguments = new ArrayList<>();
        // USING ERRCODE = 'xxxxx' 或 SQLSTATE 'xxxxx' 指定的SQLSTATE，可为null
        public String sqlState;

        public Raise(int position) {
            super(position);
        }
    }

    /** EXCEPTION部分的一个WHEN分支 */
    public static class Handler {

        // OTHERS、条件名或者SQLSTATE代码，统一由PlpgSqlCondition规范化
        public final List<String> conditions = new ArrayList<>();
        public final List<Node> statements = new ArrayList<>();
    }
}
