# 存储过程（实验特性）

本分支（`feature/stored-procedure`）给 Lealone 加了脚本式存储过程。
核心功能可用并已通过回归，但有一批明确的限制（见第四节），**暂不建议合并到 master**。

## 一、语法

```sql
CREATE PROCEDURE [IF NOT EXISTS] [schema.]name([IN|OUT|INOUT] [p1 INT [DEFAULT 常量]], ...)
    [LANGUAGE SQL]
    AS 'SQL1; SQL2; ...';                -- 经典写法: 用分号分隔的语句串

CREATE PROCEDURE [IF NOT EXISTS] [schema.]name([IN|OUT|INOUT] [p1 INT [DEFAULT 常量]], ...)
    [LANGUAGE SQL]
    BEGIN ATOMIC SQL1; SQL2; END;        -- SQL标准的函数体, 可以有多条语句

CALL name(实参...);                      -- 实参可以省略带默认值的入参
CALL name(名字 => 实参, ...);            -- 命名实参
CALL name(10, b => 'Hello');
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
- **过程体语言**：只支持 `LANGUAGE SQL`（默认值，写不写都一样）。
- **DEFAULT参数值**：入参可以写 `DEFAULT <常量表达式>`（和函数一样只支持常量），CALL 时省略的实参
  用默认值补齐；有默认值的入参必须集中在末尾。默认值写进 DDL，SCRIPT/重启能回放。
- **命名实参**：`CALL p(b => 'Hello', a => 10)`，也接受 `:=`；位置实参必须写在命名实参前面。
  命名实参按参数名重排后再走原来的"按位置绑定"。**命名 OUT 参数占位不支持**（`CALL p(a => null)` 里的 `a`
  如果是 OUT 参数会报函数不存在），OUT 参数只能按位置写 `NULL` 占位。
- **不是函数**：过程不能出现在表达式里，`SELECT sp_ins(1, 2)` 报 `FUNCTION_NOT_FOUND_1`。
- **持久化**：过程体随 schema 元数据一起持久化，重开库后仍可用。

### 按参数类型重载

同名过程可以按**入参**类型重载，`OUT` 参数不参与签名；消解规则和函数重载完全一样
（复用 `SqlFunction.getTypeMatch` / `isImplicitConvertible` 那张矩阵）：完全匹配优先，
其次是可以隐式转换的类型，字符串字面量/NULL 按参数类型解析，并列时报 90152。

```sql
create procedure p(a int) as 'insert into t values(?, null)';
create procedure p(a varchar) as 'insert into t values(null, ?)';
call p(1);              -- int 版本
call p('x');            -- varchar 版本
call p(null);           -- 字符串版本(NULL优先按字符串类型解析)
drop procedure p(int);  -- 只删 int 版本
call p(1);              -- 90022(FUNCTION_NOT_FOUND_1): int 到 varchar 不是隐式转换
call p('x');            -- varchar 版本还在
```

- `CREATE PROCEDURE IF NOT EXISTS` 只跳过入参签名相同的；不同参数类型仍然创建。
  同签名重复创建报 `PROCEDURE_ALREADY_EXISTS_1`(90150)。
- `DROP PROCEDURE p(类型列表)` 按**完整参数列表**（含 `OUT` 参数位置）匹配，
  `IN`/`OUT`/`INOUT` 可以用来区分入参签名相同、只是 `OUT` 不同的重载；
  不写类型列表或者两个重载都能匹配时报 90152。
- 过程对象在 schema 里的 key 是 `名字(入参类型id...)`，`SCRIPT` 和重启回放走同一条
  `Schema.add`，所以所有重载都能保留；`getDropSQL()` 带完整签名和参数模式，脚本回放不会歧义。
- 限制：数组参数只按 `ARRAY` 类型 id 匹配，不区分元素类型；`varchar(10)` 这类 typmod
  不参与比较；不支持 `VARIADIC` 参数。

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
| 同签名重复创建 | 抛 `PROCEDURE_ALREADY_EXISTS_1`；不同参数类型可以共存 |
| 同名不同参数类型的过程 | `CALL p(1)`/`CALL p('x')` 分别走 int/varchar 版本；`CALL p(NULL)` 优先字符串版本 |
| `DROP PROCEDURE p(INT)` | 只删 int 版本，varchar 版本还在；没有匹配的签名时报 90151，`IF EXISTS` 时不报错 |
| 不写签名的 `DROP PROCEDURE p` | 只有一个重载时能删；多个重载时报 90152 |
| 用 `IN`/`OUT` 模式区分重载 | `DROP PROCEDURE p(OUT INT, INT)` 只删带 OUT 参数的那个重载 |
| `OUT`/`INOUT` 参数 | `CALL p(3, 'a')` 返回一行 `(6, 'a!')`，列名/类型来自参数声明 |
| 实参个数 | 只给入参或给全部参数（`OUT` 位写 `NULL`）都行，其它个数抛 `INVALID_PARAMETER_COUNT_2` |
| 有 `OUT` 参数但过程体最后一条不是查询 | 建过程时就抛 `FEATURE_NOT_SUPPORTED`，不会留到调用时才报错 |
| `BEGIN ATOMIC` 多条语句 | 顺序执行，最后一条查询作为结果（`CALL` 返回 `(30)`，前面的插入都生效） |
| 空的 `BEGIN ATOMIC` 块 | 建过程成功，`CALL` 什么都不做，更新数 0 |
| `LANGUAGE plpgsql` 的过程体 | 建过程时就抛 `FEATURE_NOT_SUPPORTED`（见限制 8） |
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
   命名 OUT 参数占位（`CALL p(a => null)`）、`CREATE OR REPLACE PROCEDURE`。
6. **未验证**：集群 / 复制模式下过程的行为；`statement` 级复制的确定性校验。
7. 过程体按字面切分后逐条 `prepareStatement`，每条语句都会走一次会话级语句缓存。
8. **PL/pgSQL过程体不支持**：`CREATE PROCEDURE ... LANGUAGE plpgsql` 在**建过程时**就报
   `FEATURE_NOT_SUPPORTED`。原因是过程走的是 `YieldableCallProcedure` 的异步链，
   而 PL/pgSQL解释器目前只能同步执行（`PlpgSqlSqlExecutor` 内部用 `statement.query()`），
   把它塞进 CALL 链会引入阻塞等待，所以这一轮明确拒绝而不是留下"建成功、调用才炸"的半成品。
   `CREATE FUNCTION ... LANGUAGE plpgsql` 不受影响（函数本来就走同步执行路径）。
9. **OUT/INOUT值的来源**：只认过程体最后一条查询的第一行；列数少于 `OUT/INOUT` 参数个数时报
   `DATA_CONVERSION_ERROR_1`，多出来的列被忽略；查询返回 0 行时 `OUT/INOUT` 值全是 `NULL`。
10. **`BEGIN ATOMIC` 里的语句**：只支持普通SQL语句（`SELECT/INSERT/UPDATE/DELETE/CALL/...`），
   里面的语句在 `CREATE` 时就会被解析（`SELECT * FROM 不存在的表` 建过程时就报错）；
    不支持 `IF`/`LOOP`/`CASE` 过程式语句，也不支持嵌套 `BEGIN ATOMIC`。
11. **重载签名**：只看入参的 `Value` 类型 id，不区分数组元素类型和 `varchar(10)`/`numeric(10,2)`
   这类 typmod；`DROP` 的完整参数列表匹配也不区分 `VARCHAR(10)` 和 `VARCHAR`，
   并且类型列表里**不能写参数名**（`DROP PROCEDURE p(IN a INT)` 会报语法错，写 `IN INT` 可以）。

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
- **过程体里做 DDL**：`BEGIN ATOMIC ... END` 过程体里可以执行 `CREATE TABLE`/`ALTER TABLE`/`DROP TABLE`
  这类 DDL，按顺序执行；建过程时逐条 prepare 校验语法，引用的表可能还没建出来（过程体自己建的），
  这种 `TABLE_OR_VIEW_NOT_FOUND` 推迟到 `CALL` 时校验。过程体改成**按原文切分**（`splitStatementsRaw`），
  这样切语句时不会因为表还不存在而失败；DDL 在嵌套会话的同一个事务里执行。
- **SCRIPT**：`GenScript` 之前没有遍历 `DbObjectType.PROCEDURE`，`SCRIPT`/备份会丢过程定义，已补上。
- **按参数类型重载**：`Schema.getDbObjectKey()` 对 `SqlProcedure` 返回 `名字(入参类型id...)`，
  `Schema.findProcedures()` 按名字取候选集合；`CallProcedure` 在 `prepare()` 里用
  `SqlProcedure.getMatchScore()`（内部调用 `SqlFunction.getTypeMatch`）消解重载；
  `DropProcedure`/`SQLParserBase.parseDropProcedure()` 支持 `name(类型列表)` 和 `IN/OUT/INOUT`
  模式，按完整参数列表匹配；`SqlProcedure.getDropSQL()` 输出带签名的 DROP，保证 `SCRIPT` 回放不歧义。

## 六、为什么用嵌套 session

另一条更“干净”的路线是：不动 session，直接在调用者 session 上跑内层 yieldable，
这样手动事务里的可见性问题（限制 3）会自然消失。但实测行不通：

- 子 yieldable 没有注册到 session 时，一次 `run()` 后状态停在 `STATEMENT_RUNNING`；
- 而 `getYieldableCommand()` 对 `STATEMENT_RUNNING` 直接返回 null，调度器不会再来驱动它；
- 异步写完成的回调只能把状态改成 `COMPLETED`，却没有人再去调 `run()` → `stop()`，链条静默停死。

嵌套 session 方案每级都注册成各自 session 的当前命令，被调度器公平对待，
任何一条语句等锁或让出都不会阻塞调度线程。

## 七、回归

`StoredProcedureTest`、`ProcedureOutParamTest`（OUT/INOUT）、`ProcedureOverloadTest`（按参数类型重载）、
`AtomicBodyTest`（BEGIN ATOMIC）、`ScriptRoundTripTest`、`PgRegressTest`、`PgFunction*Test`、
`Plpgsql*Test` 等回归全绿，具体用例数见交付说明。

跑测试前记得 `rm -rf lealone-test/target/test-data`。
