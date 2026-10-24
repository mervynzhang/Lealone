# 存储过程（实验特性）

本分支（`feature/stored-procedure`）给 Lealone 加了脚本式存储过程。
核心功能可用并已通过回归，但有一批明确的限制（见第四节），**暂不建议合并到 master**。

## 一、语法

```sql
CREATE PROCEDURE [IF NOT EXISTS] [schema.]name([IN|OUT|INOUT] p1 INT, ...)
    [LANGUAGE SQL]
    AS 'SQL1; SQL2; ...';                -- 经典写法: 用分号分隔的语句串

CREATE PROCEDURE [IF NOT EXISTS] [schema.]name([IN|OUT|INOUT] p1 INT, ...)
    [LANGUAGE SQL]
    BEGIN ATOMIC SQL1; SQL2; END;        -- SQL标准的函数体, 可以有多条语句

CREATE PROCEDURE [IF NOT EXISTS] [schema.]name([IN|OUT|INOUT] p1 INT, ...)
    LANGUAGE plpgsql
    AS $$ BEGIN ... END $$;              -- PL/pgSQL过程体, OUT/INOUT就是里面的变量

CALL name(实参...);
DROP PROCEDURE [IF EXISTS] [schema.]name;
```

过程体是用分号分隔的 SQL 语句串，用 `?` 按出现顺序引用参数：

```sql
CREATE TABLE t(id int primary key, v int);
CREATE PROCEDURE sp_ins(pk int, val int) AS 'INSERT INTO t VALUES(?, ?)';
CALL sp_ins(1, 10);
```

参数支持 PostgreSQL的 `IN`（默认）、`OUT`、`INOUT` 三种模式，`OUT`/`INOUT` 可以省略参数名。
有 `OUT`/`INOUT` 参数时 `CALL` 返回**一行**，值来自过程体最后一条查询的第一行：

```sql
CREATE PROCEDURE p(IN x INT, OUT y INT, INOUT z VARCHAR) AS 'SELECT ? * 2, ? || ''!''';
CALL p(3, 'a');       -- 返回一行 (6, 'a!')
CALL p(3, NULL, 'a'); -- PostgreSQL写法: 给OUT参数写NULL占位，结果同上
```

`BEGIN ATOMIC ... END` 是 SQL 标准的函数体写法，里面可以有多条语句，最后一条查询的结果
就是过程的 `OUT`/`INOUT` 值（没有 `OUT` 参数时就是 `CALL` 的结果集）：

```sql
CREATE PROCEDURE p2() BEGIN ATOMIC
    INSERT INTO t VALUES(1, 10);
    INSERT INTO t VALUES(2, 20);
    SELECT 30;
END;
CALL p2();  -- 返回一行 (30)
```

## 二、语义

- **原子性**：整个过程是一条语句粒度的原子单元。过程体里任何一条语句失败，整个过程回滚。
- **事务**：过程体里禁止 `COMMIT` / `ROLLBACK` / `SAVEPOINT` 等事务语句，违反时抛 `FEATURE_NOT_SUPPORTED`。
- **返回**：过程体最后一条是查询时返回该结果集；否则返回各更新语句的更新数之和。
- **OUT/INOUT**：有 `OUT`/`INOUT` 参数时，`CALL` 总是返回一行，列名就是参数名，
  列类型是参数的声明类型；值来自过程体**最后一条查询**的第一行（按列位置对应 `OUT`/`INOUT` 参数）。
  实参可以只给入参（`IN`/`INOUT`），也可以像 PostgreSQL 那样给 `OUT` 参数写 `NULL` 占位。
- **过程体语言**：支持 `LANGUAGE SQL`（默认值，写不写都一样）和 `LANGUAGE plpgsql`。
- **PL/pgSQL过程体**：`OUT`/`INOUT` 参数就是 PL/pgSQL 里的变量，过程体自己给它们赋值，
  `CALL` 照样返回一行（列名/列类型来自参数声明）；过程体里可以写变量声明、`IF`、`LOOP`、
  `RAISE` 等 PL/pgSQL语句，和 `CREATE FUNCTION ... LANGUAGE plpgsql` 是同一套解释器。
  `RETURN;` 提前结束时也会把当前的 `OUT`/`INOUT` 值返回。
- **执行模型（重要）**：PL/pgSQL过程体是**同步解释执行**的——`YieldableCallProcedure` 在
  `startInternal()` 里直接跑 `PlpgSqlInterpreter`，解释器内部的SQL走既有的
  `PlpgSqlSqlExecutor` → `statement.query()` → `StatementBase.syncExecute()`，
  即"协作式等待"（等锁时用 `Thread.sleep(1)` 自旋、并驱动调度器执行别的语句）。
  它**不是** `CALL` 过程体那种 yieldable 链：SQL过程体是每条语句一个子 yieldable 串起来的。
  这和 `CREATE FUNCTION ... LANGUAGE plpgsql` 的执行方式完全一致（函数体也走这条同步路径）。
- **不是函数**：过程不能出现在表达式里，`SELECT sp_ins(1, 2)` 报 `FUNCTION_NOT_FOUND_1`。
- **持久化**：过程体随 schema 元数据一起持久化，重开库后仍可用。

## 三、实测行为

以下都是在本分支上实际执行得到的结论：

| 场景 | 结果 |
| --- | --- |
| 多语句过程体 | `CALL` 返回更新数 2，两行都写入 |
| 带参过程 | 参数按位置绑定，写入对调用方可见（自动提交模式下） |
| 过程体是查询 | `CALL` 返回结果集；`Statement.execute()` 返回 true，`getResultSet()` 能拿到行 |
| 只有更新语句的过程 | `Statement.execute()` 返回 false，`getUpdateCount()` 是累计更新数 |
| 过程体里嵌套 `CALL` | 支持，内外层语句各自独立绑定 `?` 参数 |
| 过程体第二条语句违反主键 | 抛 `DUPLICATE_KEY_1`，且**第一条语句的插入已回滚** |
| 过程体里写 `COMMIT` | 抛 `FEATURE_NOT_SUPPORTED: transaction statement in procedure` |
| `SELECT` 里调用过程 | 抛 `FUNCTION_NOT_FOUND_1` |
| 重名创建 | 抛 `PROCEDURE_ALREADY_EXISTS_1` |
| `OUT`/`INOUT` 参数 | `CALL p(3, 'a')` 返回一行 `(6, 'a!')`，列名/类型来自参数声明 |
| 实参个数 | 只给入参或给全部参数（`OUT` 位写 `NULL`）都行，其它个数抛 `INVALID_PARAMETER_COUNT_2` |
| 有 `OUT` 参数但过程体最后一条不是查询 | 建过程时就抛 `FEATURE_NOT_SUPPORTED`，不会留到调用时才报错 |
| `BEGIN ATOMIC` 多条语句 | 顺序执行，最后一条查询作为结果（`CALL` 返回 `(30)`，前面的插入都生效） |
| 空的 `BEGIN ATOMIC` 块 | 建过程成功，`CALL` 什么都不做，更新数 0 |
| `LANGUAGE plpgsql` 的过程体 | 可建可 `CALL`，`OUT`/`INOUT` 由过程体赋值，`CALL` 返回一行（同步解释执行，见限制 8） |
| `LANGUAGE plpgsql` + `BEGIN ATOMIC` | 建过程时抛 `FEATURE_NOT_SUPPORTED`（PL/pgSQL只有 `BEGIN ... END` 块） |
| `SCRIPT` / 关库重开 | `CREATE PROCEDURE`（含 `OUT/INOUT`、`BEGIN ATOMIC`）会出现在 `SCRIPT` 里并能回放 |
| `DROP PROCEDURE` / `DROP PROCEDURE IF EXISTS` | 正常；删除后 `CALL` 抛 `PROCEDURE_NOT_FOUND_1` |
| 关闭重开库后 `CALL` 同一过程 | 仍可用 |
| 手动事务里 `CALL`，提交前从调用方查询 | **看不到**过程写入（0 行）；提交后可见（1 行） |

## 四、已知限制

1. **不是完整的 PL**：没有 `BEGIN ... END` 块、变量声明、`IF`/`WHILE`、游标、异常处理。过程体只能是扁平的 SQL 语句串。
2. **参数引用**：过程体里用 `?` 按位置引用参数（**不支持**按参数名引用，`select x` 里的 `x` 是列名不是参数），
   `BEGIN ATOMIC` 的过程体也一样；这一点和 `CREATE FUNCTION` 不同（函数体里参数名会被替换成 `?`）。
3. **手动事务中的可见性**：过程跑在嵌套 session 里，未提交前调用方 session 看不到过程的写入。
4. **参数绑定按语句独立**：过程体里每条语句的第 i 个 `?` 对应第 i 个实参，多条语句不共享参数下标
   （嵌套 `CALL` 已支持，见第三节）。
5. **不支持**：`RENAME PROCEDURE`、`INFORMATION_SCHEMA` 里的过程元数据视图、`VARIADIC` 参数、
   `DEFAULT` 参数值、命名实参（`p(b => 1)`）、`CREATE OR REPLACE PROCEDURE`。
6. **未验证**：集群 / 复制模式下过程的行为；`statement` 级复制的确定性校验。
7. 过程体按字面切分后逐条 `prepareStatement`，每条语句都会走一次会话级语句缓存。
8. **PL/pgSQL过程体是同步执行**：`CALL` 一个 plpgsql 过程时，`YieldableCallProcedure`
   会在线程里同步跑完整个解释器（`PlpgSqlInterpreter`），解释器内部的SQL用
   `StatementBase.syncExecute()` 协作式等待。这跟 SQL 过程体"每条语句一个子 yieldable"的模型不同，
   而是和 `CREATE FUNCTION ... LANGUAGE plpgsql` 保持一致：plpgsql 函数体本来就这么执行。
   副作用：长时间运行或大量等锁的 plpgsql 过程体会占住当前调度线程，和 plpgsql 函数一样。
   plpgsql 过程体不支持 `BEGIN ATOMIC`（建过程时报 `FEATURE_NOT_SUPPORTED`）。
9. **OUT/INOUT值的来源**：只认过程体最后一条查询的第一行；列数少于 `OUT/INOUT` 参数个数时报
   `DATA_CONVERSION_ERROR_1`，多出来的列被忽略；查询返回 0 行时 `OUT/INOUT` 值全是 `NULL`。
10. **`BEGIN ATOMIC` 里的语句**：只支持普通SQL语句（`SELECT/INSERT/UPDATE/DELETE/CALL/...`），
   里面的语句在 `CREATE` 时就会被解析（`SELECT * FROM 不存在的表` 建过程时就报错）；
    不支持 `IF`/`LOOP`/`CASE` 过程式语句，也不支持嵌套 `BEGIN ATOMIC`。

## 五、实现结构

新增 5 个文件、修改 8 个已有文件（共 4 个模块）：

| 文件 | 职责 |
| --- | --- |
| `lealone-db/.../db/schema/SqlProcedure.java` | schema 对象：过程体文本 + 参数列 + `getCreateSQL/getDropSQL` |
| `lealone-sql/.../sql/ddl/CreateProcedure.java` | `CREATE PROCEDURE`（加锁、重名校验、`schema.add`） |
| `lealone-sql/.../sql/ddl/DropProcedure.java` | `DROP PROCEDURE` |
| `lealone-sql/.../sql/dml/CallProcedure.java` | `CALL p(...)` 语句 |
| `lealone-sql/.../sql/executor/YieldableCallProcedure.java` | 执行器：逐条驱动过程体语句 |

`CALL` 是按查询语句还是更新语句执行，由 `CallProcedure.isQuery()` 判断：
用 tokenizer 扫一遍过程体（只切词、不解析语句，避免 WITH 在 prepare 阶段建临时视图），
遇到 `SELECT/VALUES/TABLE/WITH` 开头的语句就按查询执行，否则按更新执行。

改动点：`DbObjectType` 新增 `PROCEDURE` 枚举值（追加在末尾，值写进 SYS 表）、`Schema` 新增查找、
`ErrorCode` 与消息文件各加 2 条、`SQLStatement` 新增语句类型常量、`SQLParserBase` 新增两个解析方法、
`LealoneSQLParser` 覆盖 `parseCall()`。

**解析器主干零改动**：`SQLParserBase.parseCall()`（H2 血统）没有被修改，`CALL` 的过程名探测放在
子类的 `parseCall()` 覆盖里，过程名不存在时回退到原来的 `CALL <表达式>`。

后续补的两批能力（都不改动上面的主干）：

- **OUT/INOUT**：`SqlProcedure` 增加 `parameterModes/inputIndexes/outputIndexes`，
  `SQLParserBase.parseProcedureParameter()` 解析参数模式，`CreateProcedure` 建过程时校验
  "有 OUT/INOUT 时最后一条语句必须是查询"（`LealoneSQLParser.hasTrailingQueryStatement()` 只切词不解析语句），
  `CallProcedure.getMetaData()/isQuery()` 按 OUT/INOUT 参数给出结果列，`YieldableCallProcedure`
  在过程体执行完后把最后一条查询的第一行按参数类型转换成一结果行。
- **BEGIN ATOMIC**：`SQLParserBase.parseAtomicBody()` 用 `parseStatement()` 逐条解析 `BEGIN ATOMIC ... END`
  里的语句并拼成过程体/函数体；函数体按语句分别做参数名替换（`SqlFunctionBodyParser.parseAtomicBody`），
  执行时 `SqlFunction.executeAtomicBody()` 顺序执行、最后一条查询的结果作为返回值；
  过程体直接复用原来的语句串执行路径。`LealoneSQLParser.splitStatements()` 是两者共用的拆分工具。
- **SCRIPT**：`GenScript` 之前没有遍历 `DbObjectType.PROCEDURE`，`SCRIPT`/备份会丢过程定义，已补上。
- **PL/pgSQL过程体**：`SqlProcedure` 增加了 `language`（`isPlpgSql()`），`getCreateSQL()` 在非SQL语言时
  输出 `LANGUAGE plpgsql`；`YieldableCallProcedure` 在 `startInternal()` 里判断是 plpgsql 就直接跑
  `PlpgSqlInterpreter`（`executePlpgSqlBody()`），`PlpgSqlResultInfo` 增加了一个 `procedure` 标志，
  让"有OUT/INOUT参数时就返回一行record(ValueArray)"对过程也成立（函数是"多个OUT参数才返回record"）。
  过程体的解释结果在 `buildPlpgSqlOutputResult()` 里按参数类型转换后组成 `CALL` 的结果行。

## 六、为什么用嵌套 session

另一条更“干净”的路线是：不动 session，直接在调用者 session 上跑内层 yieldable，
这样手动事务里的可见性问题（限制 3）会自然消失。但实测行不通：

- 子 yieldable 没有注册到 session 时，一次 `run()` 后状态停在 `STATEMENT_RUNNING`；
- 而 `getYieldableCommand()` 对 `STATEMENT_RUNNING` 直接返回 null，调度器不会再来驱动它；
- 异步写完成的回调只能把状态改成 `COMPLETED`，却没有人再去调 `run()` → `stop()`，链条静默停死。

嵌套 session 方案每级都注册成各自 session 的当前命令，被调度器公平对待，
任何一条语句等锁或让出都不会阻塞调度线程。

## 七、回归

`StoredProcedureTest`、`ProcedureOutParamTest`（OUT/INOUT）、`AtomicBodyTest`（BEGIN ATOMIC）、
`ScriptRoundTripTest`、`PgRegressTest`、`PgFunction*Test`、`Plpgsql*Test` 等回归全绿，
具体用例数见交付说明。

跑测试前记得 `rm -rf lealone-test/target/test-data`。
