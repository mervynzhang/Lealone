/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.sql.plpgsql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;
import com.lealone.sql.plpgsql.PlpgSqlAst.Assignment;
import com.lealone.sql.plpgsql.PlpgSqlAst.Block;
import com.lealone.sql.plpgsql.PlpgSqlAst.Branch;
import com.lealone.sql.plpgsql.PlpgSqlAst.Continue;
import com.lealone.sql.plpgsql.PlpgSqlAst.Declaration;
import com.lealone.sql.plpgsql.PlpgSqlAst.Exit;
import com.lealone.sql.plpgsql.PlpgSqlAst.For;
import com.lealone.sql.plpgsql.PlpgSqlAst.GetDiagnostics;
import com.lealone.sql.plpgsql.PlpgSqlAst.Handler;
import com.lealone.sql.plpgsql.PlpgSqlAst.If;
import com.lealone.sql.plpgsql.PlpgSqlAst.Loop;
import com.lealone.sql.plpgsql.PlpgSqlAst.NestedBlock;
import com.lealone.sql.plpgsql.PlpgSqlAst.Node;
import com.lealone.sql.plpgsql.PlpgSqlAst.NullStatement;
import com.lealone.sql.plpgsql.PlpgSqlAst.Perform;
import com.lealone.sql.plpgsql.PlpgSqlAst.Raise;
import com.lealone.sql.plpgsql.PlpgSqlAst.Return;
import com.lealone.sql.plpgsql.PlpgSqlAst.SqlExec;
import com.lealone.sql.plpgsql.PlpgSqlAst.While;
import com.lealone.sql.plpgsql.PlpgSqlLexer.Token;
import com.lealone.sql.plpgsql.PlpgSqlLexer.TokenType;

/**
 * PL/pgSQL函数体的语法分析器。
 * 表达式和SQL语句只切出原文，真正的SQL语法由SQL引擎解析。
 */
public class PlpgSqlParser {

    private static final List<String> UNSUPPORTED_STATEMENTS = Arrays.asList("CASE", "EXECUTE",
            "FOREACH", "ASSERT", "OPEN", "FETCH", "CLOSE", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "START");

    private final String source;
    private final ArrayList<Token> tokens;

    private int pos;

    public PlpgSqlParser(String source) {
        this.source = source == null ? "" : source;
        this.tokens = new PlpgSqlLexer(this.source).tokenize();
    }

    /**
     * 解析整个函数体。
     */
    public static Block parse(String source) {
        return new PlpgSqlParser(source).parse();
    }

    public Block parse() {
        int start = current().start;
        String label = null;
        if (isOp("<<"))
            label = parseLabel();
        Block block = parseBlock(label, start);
        if (isOp(";"))
            next();
        if (current().type != TokenType.END)
            throw syntaxError(current(), "unexpected token after END: " + current().text);
        return block;
    }

    // 解析一个块，当前token是DECLARE或BEGIN
    private Block parseBlock(String label, int start) {
        Block block = new Block(start);
        block.label = label;
        if (isKeyword("DECLARE")) {
            next();
            parseDeclarations(block);
        }
        expectKeyword("BEGIN");
        parseStatements(block.statements, "EXCEPTION");
        if (isKeyword("EXCEPTION")) {
            next();
            parseHandlers(block);
        }
        expectKeyword("END");
        if (current().type == TokenType.IDENTIFIER) {
            // END后面只能跟和开头相同的标签，没有标签的块不能带结束标签
            String endLabel = current().value;
            if (label == null)
                throw syntaxError(current(), "end label specified for unlabeled block");
            if (!label.equalsIgnoreCase(endLabel))
                throw syntaxError(current(), "end label " + endLabel + " does not match " + label);
            next();
        }
        if (isOp(";"))
            next();
        return block;
    }

    private void parseDeclarations(Block block) {
        while (!isKeyword("BEGIN")) {
            if (current().type == TokenType.END)
                throw syntaxError(current(), "missing BEGIN");
            Declaration declaration = parseDeclaration();
            block.declarations.add(declaration);
        }
    }

    private Declaration parseDeclaration() {
        Token nameToken = expectIdentifier();
        Declaration declaration = new Declaration(nameToken.start);
        declaration.name = nameToken.value;
        declaration.nameQuoted = nameToken.quoted;
        if (isKeyword("CONSTANT")) {
            declaration.constant = true;
            next();
        }
        declaration.typeName = captureType();
        if (isKeyword("NOT")) {
            next();
            expectKeyword("NULL");
            declaration.notNull = true;
        }
        if (isOp(":=") || isOp("=")) {
            next();
            declaration.defaultExpression = captureExpression(new String[0], new String[] { ";" });
            expectOp(";");
        } else if (isKeyword("DEFAULT")) {
            next();
            declaration.defaultExpression = captureExpression(new String[0], new String[] { ";" });
            expectOp(";");
        } else {
            expectOp(";");
        }
        return declaration;
    }

    // 类型可能带精度、数组、%TYPE，甚至多个单词，因此切到声明结束符为止
    private String captureType() {
        int startToken = pos;
        int depth = 0;
        int i = pos;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (t.type == TokenType.END)
                break;
            if (t.type == TokenType.OPERATOR) {
                String text = t.text;
                if ("(".equals(text) || "[".equals(text)) {
                    depth++;
                } else if (")".equals(text) || "]".equals(text)) {
                    depth--;
                } else if (depth == 0) {
                    if (";".equals(text) || ":=".equals(text) || "=".equals(text)) {
                        String type = slice(startToken, i);
                        if (type.isEmpty())
                            throw syntaxError(t, "missing type");
                        pos = i;
                        return type;
                    }
                }
            } else if (t.type == TokenType.IDENTIFIER && !t.quoted && depth == 0) {
                if ("DEFAULT".equalsIgnoreCase(t.text)) {
                    String type = slice(startToken, i);
                    if (type.isEmpty())
                        throw syntaxError(t, "missing type");
                    pos = i;
                    return type;
                }
                if ("NOT".equalsIgnoreCase(t.text)) {
                    Token nextToken = tokenAt(i + 1);
                    if (nextToken.type == TokenType.IDENTIFIER && !nextToken.quoted
                            && "NULL".equalsIgnoreCase(nextToken.text)) {
                        String type = slice(startToken, i);
                        if (type.isEmpty())
                            throw syntaxError(t, "missing type");
                        pos = i;
                        return type;
                    }
                }
            }
            i++;
        }
        throw syntaxError(current(), "missing type or ; in declaration");
    }

    private void parseStatements(List<Node> statements, String... stopKeywords) {
        while (true) {
            Token t = current();
            if (t.type == TokenType.END)
                return;
            if (isKeyword("END") || isKeyword("EXCEPTION"))
                return;
            if (isAnyKeyword(stopKeywords))
                return;
            if (isOp(";")) {
                next();
                continue;
            }
            statements.add(parseStatement());
        }
    }

    private Node parseStatement() {
        Token t = current();
        if (isOp("<<")) {
            // 带标签的块或循环
            int start = t.start;
            String label = parseLabel();
            if (isKeyword("DECLARE") || isKeyword("BEGIN"))
                return new NestedBlock(start, parseBlock(label, start));
            if (isKeyword("LOOP"))
                return parseLoop(label);
            if (isKeyword("WHILE"))
                return parseWhile(label);
            if (isKeyword("FOR"))
                return parseFor(label);
            throw syntaxError(current(), "label can only be used before a block or loop");
        }
        if (isKeyword("DECLARE") || isKeyword("BEGIN"))
            return new NestedBlock(t.start, parseBlock(null, t.start));
        if (isKeyword("IF"))
            return parseIf();
        if (isKeyword("LOOP"))
            return parseLoop(null);
        if (isKeyword("WHILE"))
            return parseWhile(null);
        if (isKeyword("FOR"))
            return parseFor(null);
        if (isKeyword("EXIT"))
            return parseExit();
        if (isKeyword("CONTINUE"))
            return parseContinue();
        if (isKeyword("RETURN"))
            return parseReturn();
        if (isKeyword("RAISE"))
            return parseRaise();
        if (isKeyword("PERFORM"))
            return parsePerform();
        if (isKeyword("GET"))
            return parseGetDiagnostics();
        if (isKeyword("NULL")) {
            next();
            expectOp(";");
            return new NullStatement(t.start);
        }
        if (t.type == TokenType.IDENTIFIER && !t.quoted && isUnsupportedStatement(t.text))
            throw unsupported("PL/pgSQL statement: " + t.text);
        // 标识符后面跟赋值符号就是赋值语句，否则按内嵌SQL处理
        if (t.type == TokenType.IDENTIFIER && isAssignmentOperator(tokenAt(pos + 1)))
            return parseAssignment();
        return parseSqlStatement();
    }

    private static boolean isUnsupportedStatement(String text) {
        for (String s : UNSUPPORTED_STATEMENTS) {
            if (s.equalsIgnoreCase(text))
                return true;
        }
        return false;
    }

    private static boolean isAssignmentOperator(Token t) {
        return t.type == TokenType.OPERATOR && (":=".equals(t.text) || "=".equals(t.text));
    }

    private Assignment parseAssignment() {
        Token target = expectIdentifier();
        Assignment assignment = new Assignment(target.start);
        assignment.target = target.value;
        assignment.targetQuoted = target.quoted;
        if (!isAssignmentOperator(current()))
            throw syntaxError(current(), "expected := or =");
        next();
        assignment.expression = captureExpression(new String[0], new String[] { ";" });
        expectOp(";");
        return assignment;
    }

    private If parseIf() {
        If node = new If(current().start);
        expectKeyword("IF");
        String condition = captureExpression(new String[] { "THEN" }, new String[0]);
        expectKeyword("THEN");
        Branch branch = new Branch(condition);
        parseStatements(branch.statements, "ELSIF", "ELSE");
        node.branches.add(branch);
        while (isKeyword("ELSIF")) {
            next();
            String elsifCondition = captureExpression(new String[] { "THEN" }, new String[0]);
            expectKeyword("THEN");
            Branch elsifBranch = new Branch(elsifCondition);
            parseStatements(elsifBranch.statements, "ELSIF", "ELSE");
            node.branches.add(elsifBranch);
        }
        if (isKeyword("ELSE")) {
            next();
            parseStatements(node.elseStatements);
        }
        expectKeyword("END");
        expectKeyword("IF");
        if (isOp(";"))
            next();
        return node;
    }

    private Loop parseLoop(String label) {
        Loop loop = new Loop(current().start);
        loop.label = label;
        expectKeyword("LOOP");
        parseStatements(loop.statements);
        expectKeyword("END");
        expectKeyword("LOOP");
        parseLoopEndLabel(loop.label);
        return loop;
    }

    private While parseWhile(String label) {
        While loop = new While(current().start);
        loop.label = label;
        expectKeyword("WHILE");
        loop.condition = captureExpression(new String[] { "LOOP" }, new String[0]);
        expectKeyword("LOOP");
        parseStatements(loop.statements);
        expectKeyword("END");
        expectKeyword("LOOP");
        parseLoopEndLabel(loop.label);
        return loop;
    }

    private For parseFor(String label) {
        For loop = new For(current().start);
        loop.label = label;
        expectKeyword("FOR");
        Token variableToken = expectIdentifier();
        loop.variable = variableToken.value;
        loop.variableQuoted = variableToken.quoted;
        expectKeyword("IN");
        if (isKeyword("REVERSE")) {
            loop.reverse = true;
            next();
        }
        if (isKeyword("SELECT") || isKeyword("WITH") || isKeyword("VALUES") || isKeyword("TABLE"))
            throw unsupported("FOR ... IN query");
        loop.startExpression = captureExpression(new String[0], new String[] { ".." });
        expectOp("..");
        loop.endExpression = captureExpression(new String[] { "LOOP", "BY" }, new String[0]);
        if (isKeyword("BY")) {
            next();
            loop.stepExpression = captureExpression(new String[] { "LOOP" }, new String[0]);
        }
        expectKeyword("LOOP");
        parseStatements(loop.statements);
        expectKeyword("END");
        expectKeyword("LOOP");
        parseLoopEndLabel(loop.label);
        return loop;
    }

    // END LOOP后面可以跟和开头相同的标签，不匹配时按PostgreSQL的做法报语法错误
    private void parseLoopEndLabel(String label) {
        if (current().type == TokenType.IDENTIFIER) {
            String endLabel = current().value;
            if (label == null || !label.equalsIgnoreCase(endLabel))
                throw syntaxError(current(), "end label " + endLabel + " does not match");
            next();
        }
        if (isOp(";"))
            next();
    }

    private Exit parseExit() {
        Exit node = new Exit(current().start);
        expectKeyword("EXIT");
        parseLabelAndCondition(node);
        return node;
    }

    private Continue parseContinue() {
        Continue node = new Continue(current().start);
        expectKeyword("CONTINUE");
        parseLabelAndCondition(node);
        return node;
    }

    private void parseLabelAndCondition(Object node) {
        String label = null;
        String condition = null;
        if (current().type == TokenType.IDENTIFIER && !isKeyword("WHEN") && !isOp(";")) {
            label = current().value;
            next();
        }
        if (isKeyword("WHEN")) {
            next();
            condition = captureExpression(new String[0], new String[] { ";" });
        }
        expectOp(";");
        if (node instanceof Exit) {
            Exit exit = (Exit) node;
            exit.label = label;
            exit.condition = condition;
        } else {
            Continue cont = (Continue) node;
            cont.label = label;
            cont.condition = condition;
        }
    }

    private Return parseReturn() {
        Return node = new Return(current().start);
        expectKeyword("RETURN");
        if (isKeyword("NEXT")) {
            node.next = true;
            next();
            if (isOp(";")) {
                next();
                return node;
            }
            node.expression = captureExpression(new String[0], new String[] { ";" });
            expectOp(";");
            return node;
        }
        if (isKeyword("QUERY")) {
            node.query = true;
            next();
            node.expression = captureExpression(new String[0], new String[] { ";" });
            expectOp(";");
            return node;
        }
        if (isOp(";")) {
            next();
            return node;
        }
        node.expression = captureExpression(new String[0], new String[] { ";" });
        expectOp(";");
        return node;
    }

    private GetDiagnostics parseGetDiagnostics() {
        GetDiagnostics node = new GetDiagnostics(current().start);
        expectKeyword("GET");
        expectKeyword("DIAGNOSTICS");
        Token target = expectIdentifier();
        node.target = target.value;
        node.targetQuoted = target.quoted;
        if (!isOp("=") && !isOp(":="))
            throw syntaxError(current(), "expected = in GET DIAGNOSTICS");
        next();
        Token item = expectIdentifier();
        node.item = item.value.toUpperCase();
        if (isOp(","))
            throw unsupported("GET DIAGNOSTICS with multiple items");
        expectOp(";");
        return node;
    }

    private Perform parsePerform() {
        Token startToken = current();
        expectKeyword("PERFORM");
        String query = captureExpression(new String[0], new String[] { ";" });
        expectOp(";");
        return new Perform(startToken.start, query);
    }

    private Raise parseRaise() {
        Raise node = new Raise(current().start);
        expectKeyword("RAISE");
        if (isOp(";")) {
            next();
            return node;
        }
        if (isKeyword("SQLSTATE")) {
            next();
            node.level = "EXCEPTION";
            node.sqlState = expectString().value;
            parseRaiseUsing(node);
            expectOp(";");
            return node;
        }
        if (isRaiseLevel(current().text)) {
            node.level = current().text.toUpperCase();
            next();
        } else {
            node.level = "EXCEPTION";
        }
        // 支持RAISE EXCEPTION SQLSTATE 'xxxxx'这种写法
        if (isKeyword("SQLSTATE")) {
            next();
            node.sqlState = expectString().value;
            parseRaiseUsing(node);
            expectOp(";");
            return node;
        }
        if (current().type == TokenType.STRING) {
            node.message = current().value;
            next();
            // 格式字符串后面可以有逗号分隔的参数表达式
            while (isOp(",")) {
                next();
                node.arguments.add(captureExpression(new String[] { "USING" },
                        new String[] { ",", ";" }));
            }
        }
        parseRaiseUsing(node);
        expectOp(";");
        return node;
    }

    private void parseRaiseUsing(Raise node) {
        if (!isKeyword("USING"))
            return;
        next();
        while (true) {
            Token option = expectIdentifier();
            String optionName = option.value;
            if (isOp("=") || isOp(":="))
                next();
            if ("ERRCODE".equalsIgnoreCase(optionName) || "SQLSTATE".equalsIgnoreCase(optionName)) {
                if (current().type != TokenType.STRING)
                    throw syntaxError(current(), "ERRCODE must be a string literal");
                node.sqlState = current().value;
                next();
            } else if ("MESSAGE".equalsIgnoreCase(optionName)
                    && current().type == TokenType.STRING) {
                node.message = current().value;
                next();
            } else {
                // 其它USING选项解析后忽略
                captureExpression(new String[0], new String[] { ",", ";" });
            }
            if (isOp(","))
                next();
            else
                break;
        }
    }

    private static boolean isRaiseLevel(String text) {
        return "NOTICE".equalsIgnoreCase(text) || "EXCEPTION".equalsIgnoreCase(text)
                || "WARNING".equalsIgnoreCase(text) || "INFO".equalsIgnoreCase(text)
                || "LOG".equalsIgnoreCase(text) || "DEBUG".equalsIgnoreCase(text);
    }

    private SqlExec parseSqlStatement() {
        Token startToken = current();
        int depth = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TokenType.END)
                break;
            if (t.type == TokenType.OPERATOR) {
                if ("(".equals(t.text)) {
                    depth++;
                } else if (")".equals(t.text)) {
                    depth--;
                } else if (";".equals(t.text) && depth == 0) {
                    String sql = slice(pos, i);
                    if (sql.isEmpty())
                        throw syntaxError(t, "empty SQL statement");
                    pos = i + 1;
                    return new SqlExec(startToken.start, sql);
                }
            }
        }
        throw syntaxError(current(), "missing ; at end of SQL statement");
    }

    private void parseHandlers(Block block) {
        while (isKeyword("WHEN")) {
            next();
            Handler handler = new Handler();
            while (true) {
                if (isKeyword("OTHERS")) {
                    handler.conditions.add("others");
                    next();
                } else if (isKeyword("SQLSTATE")) {
                    next();
                    handler.conditions.add(expectString().value);
                } else {
                    Token condition = expectIdentifier();
                    handler.conditions.add(PlpgSqlCondition.normalize(condition.value));
                }
                if (isKeyword("OR")) {
                    next();
                    continue;
                }
                break;
            }
            expectKeyword("THEN");
            parseStatements(handler.statements, "WHEN");
            block.handlers.add(handler);
        }
    }

    // 截取从startToken到endToken(不含)之间的源文本
    private String slice(int startToken, int endToken) {
        int start = tokens.get(startToken).start;
        int end = endToken >= tokens.size() ? source.length() : tokens.get(endToken).start;
        if (end < start)
            return "";
        return source.substring(start, end).trim();
    }

    // 切出表达式原文，遇到顶层关键字或操作符时返回，终止token留给调用方
    private String captureExpression(String[] stopKeywords, String[] stopOperators) {
        int startToken = pos;
        int depth = 0;
        int caseDepth = 0;
        int i = pos;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (t.type == TokenType.END)
                break;
            if (t.type == TokenType.OPERATOR) {
                String text = t.text;
                if ("(".equals(text) || "[".equals(text)) {
                    depth++;
                } else if (")".equals(text) || "]".equals(text)) {
                    depth--;
                } else if (depth == 0 && caseDepth == 0 && contains(stopOperators, text)) {
                    String expression = slice(startToken, i);
                    if (expression.isEmpty())
                        throw syntaxError(t, "missing expression");
                    pos = i;
                    return expression;
                }
            } else if (t.type == TokenType.IDENTIFIER && !t.quoted) {
                if ("CASE".equalsIgnoreCase(t.text)) {
                    caseDepth++;
                } else if ("END".equalsIgnoreCase(t.text) && caseDepth > 0) {
                    caseDepth--;
                } else if (depth == 0 && caseDepth == 0
                        && containsIgnoreCase(stopKeywords, t.text)) {
                    String expression = slice(startToken, i);
                    if (expression.isEmpty())
                        throw syntaxError(t, "missing expression");
                    pos = i;
                    return expression;
                }
            }
            i++;
        }
        throw syntaxError(current(), "missing expression terminator");
    }

    private static boolean contains(String[] values, String value) {
        for (String v : values) {
            if (v.equals(value))
                return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String[] values, String value) {
        for (String v : values) {
            if (v.equalsIgnoreCase(value))
                return true;
        }
        return false;
    }

    private String parseLabel() {
        expectOp("<<");
        Token label = expectIdentifier();
        expectOp(">>");
        return label.value;
    }

    private Token current() {
        return tokens.get(pos);
    }

    private Token tokenAt(int index) {
        if (index >= tokens.size())
            return tokens.get(tokens.size() - 1);
        return tokens.get(index);
    }

    private Token next() {
        Token t = current();
        if (pos < tokens.size() - 1)
            pos++;
        return t;
    }

    private boolean isKeyword(String keyword) {
        Token t = current();
        return t.type == TokenType.IDENTIFIER && !t.quoted && t.text.equalsIgnoreCase(keyword);
    }

    private boolean isAnyKeyword(String... keywords) {
        for (String keyword : keywords) {
            if (isKeyword(keyword))
                return true;
        }
        return false;
    }

    private boolean isOp(String operator) {
        Token t = current();
        return t.type == TokenType.OPERATOR && operator.equals(t.text);
    }

    private void expectKeyword(String keyword) {
        if (!isKeyword(keyword))
            throw syntaxError(current(), "expected " + keyword);
        next();
    }

    private void expectOp(String operator) {
        if (!isOp(operator))
            throw syntaxError(current(), "expected " + operator);
        next();
    }

    private Token expectIdentifier() {
        Token t = current();
        if (t.type != TokenType.IDENTIFIER)
            throw syntaxError(t, "expected identifier");
        next();
        return t;
    }

    private Token expectString() {
        Token t = current();
        if (t.type != TokenType.STRING)
            throw syntaxError(t, "expected string literal");
        next();
        return t;
    }

    private DbException syntaxError(Token token, String message) {
        return DbException.getSyntaxError(source, token.start, message);
    }

    private static DbException unsupported(String message) {
        return DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "PL/pgSQL: " + message);
    }
}
