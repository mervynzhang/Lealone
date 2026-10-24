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
- **过程体语言**：`LANGUAGE SQL`（默认值，写不写都一样）和 `LANGUAGE plpgsql` 都支持；
  plpgsql 过程体里可以写 `DECLARE`/`BEGIN..END`/`IF`/`LOOP`/`EXCEPTION`/游标，还能按参数名引用参数。
- **DEFAULT参数值**：入参可以写 `DEFAULT <常量表达式>`（和函数一样只支持常量），CALL 时省略的实参
  用默认值补齐；有默认值的入参必须集中在末尾。默认值写进 DDL，SCRIPT/重启能回放。
- **命名实参**：`CALL p(b => 'Hello', a => 10)`，也接受 `:=`；位置实参必须写在命名实参前面。
  命名实参按参数名重排后再走原来的"按位置绑定"。`OUT` 参数既可以按位置写 `NULL` 占位，也可以在命名实参里写
  `a => null`（实测 `CALL p(a => null, b => 2)` 正常返回）。
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
  不参与比较；`VARIADIC` 参数已支持。

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
| `LANGUAGE plpgsql` 的过程体 | 支持：`CREATE PROCEDURE ... LANGUAGE plpgsql AS $$ BEGIN ... END; $$`，`CALL` 在异步链里执行函数体 |
| `SCRIPT` / 关库重开 | `CREATE PROCEDURE`（含 `OUT/INOUT`、`BEGIN ATOMIC`）会出现在 `SCRIPT` 里并能回放 |
| `DROP PROCEDURE` / `DROP PROCEDURE IF EXISTS` | 正常；删除后 `CALL` 抛 `PROCEDURE_NOT_FOUND_1` |
| `CREATE OR REPLACE PROCEDURE` | 支持，同签名覆盖、`SCRIPT` 里只保留一份 |
| `ALTER PROCEDURE p(签名) RENAME TO q` | 支持；`RENAME PROCEDURE p(签名) TO q` 这种语句形式**不支持**（见限制 5） |
| `VARIADIC` 参数 | 支持：`CREATE PROCEDURE p(VARIADIC x INT[])` + `CALL p(1,2,3)` |
| 命名实参里的 OUT 占位 | 支持：`CALL p(a => null, b => 2)` |
| 过程体里做 DDL | 支持（`BEGIN ATOMIC` 体里可以 `CREATE/ALTER/DROP TABLE`，按顺序执行） |
| 关闭重开库后 `CALL` 同一过程 | 仍可用 |
| 手动事务里 `CALL`，提交前从调用方查询 | **看不到**过程写入（0 行）；提交后可见（1 行） |

### 过程的 EXECUTE 权限（和 PG 的差异）

- `GRANT/REVOKE EXECUTE ON PROCEDURE ... TO/FROM PUBLIC|用户|角色` 都支持，授权落在 `RIGHT` 对象上，
  `SCRIPT` 里输出 `GRANT EXECUTE ON PROCEDURE 名字(签名) TO ...` / `REVOKE ... FROM ...`，回放和关库重开都生效。
- **兼容规则（重要差异）**：对象**从未被 GRANT/REVOKE 过**时，**任何人都可以调用**；
  一旦有任何授权记录（哪怕只是一条 `REVOKE ... FROM PUBLIC`），就严格按 PG 语义检查：
  超级用户/属主总是可以，`PUBLIC` 的 EXECUTE 位对所有人生效，具体用户/角色按角色继承算，否则报 `42501`。
  PostgreSQL 是"建对象时自动 GRANT EXECUTE TO PUBLIC"，Lealone **不做**这个自动授权，
  目的是不动既有库的 SCRIPT 和授权数据。
- `REVOKE EXECUTE ... FROM PUBLIC` 之后如果一条授权记录都不剩，会保留一条**0 权限的标记**（`Right`），
  这样"显式 REVOKE"和"从没授权过"才区分得开；这条标记在 `SCRIPT` 里也是 `REVOKE EXECUTE ... FROM PUBLIC`。
- `SET ROLE r` / `SET ROLE NONE` / `RESET ROLE`：切到角色后按**当前角色**算 EXECUTE 权限，
  普通用户只能切到自己所属的角色（或自己），否则 `42501`。
  **边界**：`SET ROLE` 只影响函数/过程的 EXECUTE 检查，**不影响表/服务/序列的权限**（那些仍按当前用户算）。

## 四、已知限制

1. **不是完整的 PL**：`LANGUAGE sql` 的过程体只能是扁平的 SQL 语句串（没有 `IF`/`LOOP`/变量）；
   要过程式逻辑请用 `LANGUAGE plpgsql` 的过程体（已支持 `DECLARE`/`BEGIN..END`/`IF`/`LOOP`/`EXCEPTION`/游标等，
   见 `docs/PG_REGRESS.md`）。`BEGIN ATOMIC` 体里只能写普通 SQL 语句，不能写 `IF`/`LOOP`/嵌套 `BEGIN ATOMIC`。
2. **参数引用**：`LANGUAGE sql` 的过程体里用 `?` 按位置引用参数（**不支持**按参数名引用，`select x` 里的 `x` 是列名不是参数），
   `BEGIN ATOMIC` 的过程体也一样；`LANGUAGE plpgsql` 的过程体可以用参数名。这一点和 `CREATE FUNCTION` 不同
   （函数的 SQL 体里参数名会被替换成 `?`）。
3. **手动事务中的可见性**：过程跑在嵌套 session 里，未提交前调用方 session 看不到过程的写入
   （实测：手动事务里 `CALL` 之后调用方查是 0 行，`commit` 之后是 1 行）。
4. **参数绑定按语句独立**：过程体里每条语句的第 i 个 `?` 对应第 i 个实参，多条语句不共享参数下标
   （嵌套 `CALL` 已支持，见第三节）。
5. **不支持**：`RENAME PROCEDURE name(签名) TO ...`（用 `ALTER PROCEDURE ... RENAME` 代替）、
   `COMMENT ON PROCEDURE`（注释按过程名存，和函数共用一套 key）。
6. **未验证**：集群 / 复制模式下过程的行为；`statement` 级复制的确定性校验。
7. 过程体按字面切分后逐条 `prepareStatement`，每条语句都会走一次会话级语句缓存。
8. **OUT/INOUT值的来源**：只认过程体最后一条查询的第一行；列数少于 `OUT/INOUT` 参数个数时报
   `DATA_CONVERSION_ERROR_1`，多出来的列被忽略；查询返回 0 行时 `OUT/INOUT` 值全是 `NULL`。
9. **重载签名**：只看入参的 `Value` 类型 id，不区分数组元素类型和 `varchar(10)`/`numeric(10,2)`
   这类 typmod；`DROP` 的完整参数列表匹配也不区分 `VARCHAR(10)` 和 `VARCHAR`，
   并且类型列表里**不能写参数名**（`DROP PROCEDURE p(IN a INT)` 会报语法错，写 `IN INT` 可以）。
10. **带 OUT/INOUT 的过程体**最后一条语句必须是查询，且过程体里不能再嵌套 `CALL`（建过程时就校验，
    报 `FEATURE_NOT_SUPPORTED`）。

## 五、与 PG 的已知差异

这些差异都是**有意为之**或**方言层面**的，不是 bug；`PgRegressTest` 里对应的 PG 用例按"为什么 skip"记在
`docs/PG_REGRESS.md` 第五节。

| 差异 | Lealone 的行为 | PG 的行为 | 影响 |
| --- | --- | --- | --- |
| 过程体里的参数引用 | `?` 按位置（SQL 体）；plpgsql 体可用参数名 | 都可以用参数名 | 转换 PG 脚本时要把 SQL 体里的参数名改成 `?` |
| `DROP PROCEDURE` 的签名匹配 | 按**完整参数列表**（含 OUT/INOUT 位置、可写 `IN/OUT` 模式，不能写参数名） | 同样按完整参数列表，但可以写参数名 | 脚本里 `DROP PROCEDURE p(int, int)` 能匹配所有重载；`DROP PROCEDURE p` 在多重重载时报 90152 |
| `SECURITY DEFINER` | 以**属主身份**在嵌套会话里执行过程体（属主默认是创建者，DDL 里持久化 `SECURITY DEFINER` + `OWNER TO`，重启后仍生效）；`INVOKER`（默认）按调用者身份 | 同样以属主身份执行 | 和 PG 一致，可以用来做权限隔离（`INFORMATION_SCHEMA.ROUTINES.SECURITY_TYPE` 如实报 `DEFINER`/`INVOKER`） |
| `SET ROLE` 的作用范围 | 只影响函数/过程的 `EXECUTE` 权限检查 | 影响所有对象权限 | 表/序列/服务权限仍按当前用户算 |
| 授权默认值 | 对象从未被 GRANT/REVOKE 过时人人可调用；一旦有记录就严格检查 | 建对象即自动 `GRANT EXECUTE TO PUBLIC` | `REVOKE EXECUTE ... FROM PUBLIC` 之后的行为和 PG 一致 |
| `CALL` 的语义 | `CALL` 既能调过程也能调函数（JDBC `CallableStatement` 契约 `{call f(...)}`） | `CALL` 只调过程，调函数报 42809 | PG 的 `CALL version()`/`CALL sum(1)`/`CALL random()` 用例 skip（`procedure.call_function`、`procedure.call_random`） |
| psql 元命令 | 没有 `\df`（`pg_get_functiondef()` 已支持） | `\df` 列例程 | `procedure.psql_meta` 用例 skip |
| `BEGIN ATOMIC` 过程体 | 支持普通 SQL/DDL/`CALL` | PG 14+ 也支持 | 无 |
| 无 `CREATE OR REPLACE` 时的权限检查 | 同 PG：保留授权记录 | 保留授权记录 | 无 |

**三条和过程有关的 skip 用例**（和 `skip-list.txt` 逐字对应）：

* `procedure.call_function` — `CALL在Lealone里也用于调用函数(JDBC CallableStatement契约{call f(...)}), 与PG的"CALL只调过程"冲突`
* `procedure.call_random` — 同上；另外 `lealone-client` 的 `JdbcConnection` 用 `CALL READONLY()`/`CALL SCHEMA()`/
  `CALL TRANSACTION_ISOLATION_LEVEL()` 实现 `isReadOnly()`/`getSchema()`/`getTransactionIsolation()`，改语义要同步改这些内部调用
* `procedure.psql_meta` — `Lealone客户端没有psql元命令(\df); pg_get_functiondef()本身已支持`

解锁条件：前两条需要产品侧决定 `CALL` 是否区分函数与过程（或在 JDBC 层把 `{call f(...)}` 转成 `SELECT f(...)`）；
最后一条需要一个 psql 兼容客户端。

## 六、实现结构

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
- **DROP 依赖检查**：`DROP PROCEDURE`/`DROP FUNCTION` 如果还有其它例程在函数体/DEFAULT里调用它，
  按RESTRICT语义报90107（Lealone没有实现CASCADE）；依赖分析是文本级的，同名重载按名字匹配。
- **过程体里做 DDL**：`BEGIN ATOMIC ... END` 过程体里可以执行 `CREATE TABLE`/`ALTER TABLE`/`DROP TABLE`
  这类 DDL，按顺序执行；建过程时逐条 prepare 校验语法，引用的表可能还没建出来（过程体自己建的），
  这种 `TABLE_OR_VIEW_NOT_FOUND` 推迟到 `CALL` 时校验。过程体改成**按原文切分**（`splitStatementsRaw`），
  这样切语句时不会因为表还不存在而失败；DDL 在嵌套会话的同一个事务里执行。
- **SCRIPT**：`GenScript` 之前没有遍历 `DbObjectType.PROCEDURE`，`SCRIPT`/备份会丢过程定义，已补上。
- **PL/pgSQL 过程体**：`CallProcedure` 识别 `LANGUAGE plpgsql` 时直接复用 `PlpgSqlFunction` 的解释器
  （`PlpgSqlInterpreter`）在嵌套 session 里同步执行，过程体写成一行（脚本按行末 `;` 切语句的限制见
  `docs/PG_REGRESS.md` 第九节）；OUT/INOUT 的值仍按"最后一条查询的第一行"取，plpgsql 体里赋值 OUT 参数也支持。
- **CREATE OR REPLACE / ALTER ... RENAME**：`CreateProcedure` 支持 `OR REPLACE`（同签名覆盖、保留授权），
  `AlterProcedure` 支持 `RENAME TO`。
- **按参数类型重载**：`Schema.getDbObjectKey()` 对 `SqlProcedure` 返回 `名字(入参类型id...)`，
  `Schema.findProcedures()` 按名字取候选集合；`CallProcedure` 在 `prepare()` 里用
  `SqlProcedure.getMatchScore()`（内部调用 `SqlFunction.getTypeMatch`）消解重载；
  `DropProcedure`/`SQLParserBase.parseDropProcedure()` 支持 `name(类型列表)` 和 `IN/OUT/INOUT`
  模式，按完整参数列表匹配；`SqlProcedure.getDropSQL()` 输出带签名的 DROP，保证 `SCRIPT` 回放不歧义。

## 七、为什么用嵌套 session

另一条更“干净”的路线是：不动 session，直接在调用者 session 上跑内层 yieldable，
这样手动事务里的可见性问题（限制 3）会自然消失。但实测行不通：

- 子 yieldable 没有注册到 session 时，一次 `run()` 后状态停在 `STATEMENT_RUNNING`；
- 而 `getYieldableCommand()` 对 `STATEMENT_RUNNING` 直接返回 null，调度器不会再来驱动它；
- 异步写完成的回调只能把状态改成 `COMPLETED`，却没有人再去调 `run()` → `stop()`，链条静默停死。

嵌套 session 方案每级都注册成各自 session 的当前命令，被调度器公平对待，
任何一条语句等锁或让出都不会阻塞调度线程。

## 八、回归

`StoredProcedureTest`、`ProcedureOutParamTest`（OUT/INOUT）、`ProcedureOverloadTest`（按参数类型重载）、
`ProcedureNestedCallOutTest`、`ProcedureOutArgTest`、`AtomicBodyTest`（BEGIN ATOMIC）、
`ProcedurePlpgsqlTest`（plpgsql 过程体）、`GrantProcedureTest`、`AlterProcedureTest`、
`ScriptRoundTripTest`、`PgRegressTest`、`Plpgsql*Test` 等回归全绿；
`PgRegressTest` 当前是 **98 用例（执行 93 / skip 5）、406 条语句全部通过**（见 `docs/PG_REGRESS.md` 第四节）。

跑测试前记得 `rm -rf lealone-test/target/test-data`。

## 九、当前完成度与剩余缺口

过程线的整体完成度、PG 回归用例的覆盖情况、以及和函数/触发器/游标相关的能力全景，
统一维护在 `docs/PG_REGRESS.md` 第十一节"当前完成度与剩余缺口"（一张表：能力 / 状态 / 还差什么）。
和过程直接相关的剩余项只有三条 skip 用例：`procedure.call_function`、`procedure.call_random`（`CALL` 语义
与 JDBC `CallableStatement` 契约冲突）和 `procedure.psql_meta`（没有 psql 元命令 `\df`），
原因和解锁条件见本文第五节以及 `docs/PG_REGRESS.md` 第五节。

