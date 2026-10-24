# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 基线：`wip/pgregress` 起点 `9261ba9bd`（`feature/stored-routines` 合入 PG 风格存储函数之后）
- 对照的 PG 源码：本地 `postgres` 20devel 的 `src/test/regress/sql/`
- 只加测试代码和资源，**没有改任何产品代码**；发现的缺陷都记在第六节，不在本工作线修

## 一、为什么做这件事

Lealone 的 PG 风格存储函数/过程是新增能力，之前的测试（`PgFunctionTest`、
`StoredProcedureTest`）是手写的，覆盖的是"我们想到的行为"。PG 的回归测试是别人写好的、
十几年积累的边界用例，拿来当尺子能量出两类东西：

1. **回归**：已经能跑的语义（函数返回第一行第一列、参数按名字引用、CALL 的写入可见、DROP 语义…）
   一旦被改坏，这里会红。
2. **差距**：跑不了的用例按"缺什么能力"归类计数，谁在实现 `RETURNS SETOF`、`OUT` 参数、
   PL/pgSQL，直接看清单就知道自己那一格什么时候能清零。

## 二、目录与文件

```
lealone-test/src/test/java/com/lealone/test/pg/
    PgRegressTest.java      JUnit 入口：加载清单、执行、打印统计、有失败就 fail
    PgRegressRunner.java    执行引擎：H2 风格脚本解析 + 断言 + 每个文件一个库
    PgSkipList.java         skip 清单解析、清单与脚本对账、PG 语句计数
lealone-test/src/test/resources/pg/
    README.md               脚本格式、清单格式、怎么打开被 skip 的用例
    skip-list.txt           skip/known-fail 清单（53 条，本套件最重要的交付物之一）
    create_function_sql.sql 由 PG create_function_sql.sql 转换（39 个用例）
    create_procedure.sql    由 PG create_procedure.sql 转换（22 个用例）
    plpgsql.sql             由 PG plpgsql.sql 抽取（6 个代表性用例，全部 skip）
docs/PG_REGRESS.md          本文档
```

脚本格式直接复用仓库已有的 H2 风格脚本测试
`com.lealone.test.script.SqlScriptTest`（语句 + `> ` 期望输出 + `> exception <code>`），
没有另造一套 DSL；只加了 `-- @case`（用例 id，清单的键）和 `-- @pg`（PG 出处）两个标记。

## 三、怎么跑

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21

# 单独跑（约 0.3 秒，不需要 PG 源码）
rm -rf lealone-test/target/test-data
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-pgregress \
    -Dtest=PgRegressTest -DfailIfNoTests=false

# 同时校验 skip 清单里记录的 PG 行号/语句数（维护清单时用，CI 不需要）
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-pgregress \
    -Dtest=PgRegressTest -Dpg.srcdir=/home/jovyan/data/work/postgres/src/test/regress/sql
```

**默认门禁**：类名是 `PgRegressTest`，`surefire` 默认就会跑；整轮实测 0.33~0.40 秒（远低于 60 秒的
门槛），所以既没有 `@Ignore`，也没有在 `pom.xml` 里排除。它只在 `target/test-data/pgregress/`
下建临时库，跑完自己删掉，不会和别的测试抢数据。

可选系统属性：`-Dpg.dir=<脚本目录>`、`-Dpg.skip=<清单文件>`、`-Dpg.srcdir=<PG sql 目录>`。

## 四、一次完整运行的统计

```
[pgregress]   create_function_sql.sql  用例 39(执行  9, skip 30)  脚本语句  28(通过  28, 失败 0)  被skip用例占 103 条
[pgregress]   create_procedure.sql     用例 22(执行  5, skip 17)  脚本语句  19(通过  19, 失败 0)  被skip用例占  68 条
[pgregress]   plpgsql.sql              用例  6(执行  0, skip  6)  脚本语句   0(通过   0, 失败 0)  被skip用例占  20 条
[pgregress] 合计: 用例 67 (执行 14, skip 53), 脚本语句 47 (通过 47, 失败 0), 耗时 334ms
[pgregress] skip 清单: 53 条用例, 点名跳过的 PG 语句 277 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中 165 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中  92 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 整文件不适用(清单里点名 20 条代表性语句)
[pgregress]    4 条  Lealone 无 anyarray/anyelement 多态类型
[pgregress]    4 条  Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION)
[pgregress]    3 条  Lealone 无 RETURNS SETOF/RETURNS TABLE
...（按原因分组的完整列表见运行输出和第七节）
```

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* 两个已转换文件的短语数不是严格互补：少量 PG 行同时被"改编后执行"和"清单里跳过"引用
  （例如 `function_sql.overload` 里那两次 DROP，改编版在 `function_sql.drop_not_found` 里执行了），
  所以 165+92 只用来量级对比，别当 1:1 对账。

## 五、skip 清单摘要（按原因分组，共 53 条 / 277 条 PG 语句）

| 原因 | 条数 | PG 语句 | 用例 id |
| --- | --- | --- | --- |
| Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION) | 4 | 16 | plpgsql.recursion, plpgsql.for_loop_setof, plpgsql.exception_reraise, plpgsql.out_params_exception |
| Lealone 无 PL/pgSQL 与 CREATE TRIGGER 触发器 | 2 | 4 | plpgsql.trigger_room, plpgsql.trigger_raise |
| Lealone 无 anyarray/anyelement 多态类型 | 4 | 19 | function_sql.sql_body_polymorphic, function_sql.anyarray_setof, function_sql.empty_body_call, procedure.polymorphic |
| Lealone 无 RETURNS SETOF/RETURNS TABLE | 3 | 14 | function_sql.setof, function_sql.setof_inline, function_sql.setof_void |
| Lealone 无 SQL 标准函数体(RETURN 表达式) | 2 | 12 | function_sql.sql_body_return, function_sql.sql_body_view |
| Lealone 无 SQL 标准函数体, 也无 TEMP SEQUENCE/DISCARD | 1 | 3 | function_sql.sql_body_tempseq |
| Lealone 无 BEGIN ATOMIC 多语句函数体 | 2 | 11 | function_sql.sql_body_atomic, function_sql.sql_body_case |
| Lealone 无 BEGIN ATOMIC 过程体 | 1 | 5 | procedure.atomic_body |
| Lealone 过程体不能为空, 也无 BEGIN ATOMIC | 1 | 3 | procedure.empty_body |
| Lealone 函数体只允许单条查询(不支持 INSERT) | 1 | 6 | function_sql.void_insert |
| Lealone 函数体只允许单条查询(不支持 DDL) | 1 | 8 | function_sql.ddl_in_body |
| Lealone 过程体不能执行 DDL | 1 | 2 | procedure.utility_body |
| Lealone 没有 pg_proc/regproc 系统目录 | 1 | 4 | function_sql.pg_proc |
| Lealone 没有 pg_proc.provolatile 与 ALTER FUNCTION | 1 | 4 | function_sql.volatility_catalog |
| Lealone 没有 pg_get_functiondef() | 2 | 17 | function_sql.pg_get_functiondef, function_sql.pg_get_functiondef_sqlbody |
| Lealone 客户端没有 psql 元命令, 也没有 pg_get_functiondef() | 1 | 1 | procedure.psql_meta |
| Lealone 没有 information_schema.routines 等视图 | 1 | 19 | function_sql.information_schema |
| Lealone 不支持 ALTER PROCEDURE/ALTER FUNCTION 与 WINDOW/STRICT/VARIADIC/DEFAULT | 1 | 8 | procedure.alter_and_bad_def |
| Lealone 不支持 ALTER ROUTINE/DROP ROUTINE | 1 | 5 | procedure.routine_syntax |
| Lealone 不支持按参数类型重载 | 2 | 15 | function_sql.overload, procedure.ambiguous_drop |
| Lealone 不支持 SECURITY DEFINER/INVOKER | 1 | 8 | function_sql.security_definer |
| Lealone 不支持 LEAKPROOF 属性 | 1 | 16 | function_sql.leakproof |
| Lealone 不支持 STRICT/CALLED ON NULL INPUT | 1 | 9 | function_sql.strict |
| Lealone 不支持 WINDOW 属性与 FUNCTION/PROCEDURE 互转 | 1 | 3 | function_sql.or_replace_window |
| Lealone 不支持 $1/$2 位置参数 | 1 | 1 | function_sql.bad_body_positional |
| Lealone 不支持 DEFAULT 参数与命名实参 | 1 | 7 | procedure.named_default |
| Lealone 无 OUT/INOUT 参数 | 1 | 6 | procedure.inout_params |
| Lealone 无 OUT/INOUT/VARIADIC 参数 | 1 | 12 | procedure.out_params |
| Lealone 无 text[] 数组参数与下标 | 1 | 1 | function_sql.a2_array_arg |
| Lealone 无 CREATE OPERATOR CLASS/分区操作符类 | 1 | 6 | function_sql.partition_ops |
| Lealone 无 SET search_path, CREATE USER 必须带 PASSWORD | 1 | 4 | function_sql.setup |
| Lealone 无过程级 EXECUTE 权限与 SET ROLE 语义 | 1 | 10 | procedure.privileges |
| Lealone 的 CALL 不区分函数与过程 | 2 | 3 | procedure.call_random, procedure.call_function |
| 缺陷: RETURNS VOID 把 Java 序列化字节返回给客户端 | 1 | 5 | function_sql.void_return |
| 缺陷: 建函数时不校验函数体, 错误推迟到调用时 | 1 | 1 | function_sql.bad_body_return_type |
| 缺陷: 函数体返回多列时静默取第一列 | 1 | 1 | function_sql.bad_body_columns |
| 缺陷: CALL 返回结果集时 execute() 拿不到行 | 2 | 4 | procedure.body_select, procedure.collation |
| 缺陷: 过程体里嵌套 CALL 参数绑定冲突(90012) | 1 | 4 | procedure.nested_call |

## 六、暴露出的真实缺陷（都已实测复现）

这一节是这次引入 PG 回归测试最大的收获。**都没有在本工作线修**，只在 skip 清单里留了用例，
修好之后把对应清单行删掉，用例就会开始要求通过。

### D1. `RETURNS VOID` 把函数体的值按 Java 序列化字节返回给客户端

```sql
create function voidtest1(a int) returns void language sql as 'select a + 1';
select voidtest1(42);
```

实测：返回一行 `aced0005737200116a6176612e6c616e672e496e7465676572...`（十六进制字符串，
内容正是序列化后的 `java.lang.Integer 43`）。PG 返回一行空值（`voidtest1` 的 `expected` 是空串）。

影响：任何 `RETURNS VOID` 的 SQL 函数，客户端都会拿到一坨序列化字节。用例
`function_sql.void_return`（PG `create_function_sql.sql:381-390`）。

### D2. 内存库（`persistent=false`）下，返回 VARCHAR 的函数一调用就 NPE

```sql
-- 连接属性 PERSISTENT=false（SqlScriptTest 就是这么连的）
create table t(a int, b text);
insert into t values (1, 'x');
create function f_col(a int) returns varchar as 'select b from t where a = a';
select f_col(1);
```

实测：`General error: "java.lang.NullPointerException: Cannot invoke
"com.lealone.storage.lob.LobStorage.createClob(java.io.Reader, long)" because the return value of
"com.lealone.db.DataHandler.getLobStorage()" is null"`（错误码 50000）。同样的语句在
`persistent=true` 下正常返回 `x`。

影响：内存库下所有"返回字符串类型的函数"不可用；本套件因此改用持久化库
（`target/test-data/pgregress/`），否则会误伤一大批用例。这是 `SqlScriptTest` 用内存库时
覆盖不到字符串函数的原因。

### D3. 函数体是 `WITH ... SELECT` 时参数绑定错乱

```sql
create function f_with(a int) returns int as 'WITH x AS (SELECT a * 2 AS v) SELECT v FROM x';
select f_with(5);
```

实测：`90012 Parameter "#1" is not set`，报错语句里能看到被改写成
`SELECT (?1 * 2) AS V FROM SYSTEM_RANGE(1, 1)` —— 参数占位符丢了绑定。PG 返回 10。

影响：`WITH` 作为函数体时不可用。这是这次写转换脚本时按 PG 语法探测出来的（PG 的
`create_function_sql.sql` / `create_procedure.sql` 里没有 `WITH` 函数体的用例），所以**套件里
目前还没有这一条**，建议单独补一个用例文件后登记进清单（见第九节第 3 条）。

### D4. 函数体返回多列时静默取第一列

```sql
create function f_multi(a int) returns int as 'select 1, 2, 3';
select f_multi(1);   -- Lealone: 1；PG: ERROR 42804 "return type mismatch"
```

影响：写错的函数体在 Lealone 上"看起来能用"，迁移时会静默丢列。用例
`function_sql.bad_body_columns`（PG `:471-472`）。

### D5. 建函数时不校验函数体，错误推迟到调用时

```sql
create function f_bad(a int) returns int as 'select ''not an integer''';  -- PG: ERROR 42804
select f_bad(1);                                                        -- Lealone: 22018
```

影响：`CREATE FUNCTION` 成功、上线后第一次调用才炸。PG 在 `check_function_bodies=on`（默认）
时建函数就报错。用例 `function_sql.bad_body_return_type`（PG `:465-466`）。

### D6. `CALL` 有结果集时，`Statement.execute()` 拿不到行

```sql
create procedure ptest2() as 'select 5';
call ptest2();        -- Statement.execute(): false, updateCount=0, getResultSet()=null
                      -- PreparedStatement.executeQuery("CALL ptest2()"): 能拿到一行 5
```

影响：过程体是查询时，用 `execute()` 的客户端（含本套件的引擎、`SqlScriptTest`）拿不到结果集；
PG 的 `CALL` 返回结果行。用例 `procedure.body_select`、`procedure.collation`。

### D7. 过程体里嵌套 CALL 时参数绑定冲突

```sql
create procedure ptest1(x text) as 'insert into cp_test values (1, ?)';
create procedure ptest3(y text) as 'call ptest1(?); call ptest1(?)';
call ptest3('b');
```

实测：`90012 Parameter "#1" is not set` —— 过程体的 `?` 和外层参数的 `?` 用的是同一套占位符。
PG 支持 `CALL ptest1(y)` / `CALL ptest1($1)`。用例 `procedure.nested_call`（PG `:63-74`）。

### 已知差异（PG_FUNCTION.md 已记录，这里量一下影响面）

* **参数名与列名同名时参数优先**（PG 是列优先）：`where a = a` 被绑定成 `? = ?`，
  `select f(99)` 返回表里第一行而不是 `NULL`。见 `docs/PG_FUNCTION.md` 第二节第 2 条第 3 点。
* **`CALL` 不区分函数和过程**：`CALL random()`、`CALL sum(1)` 会真的执行函数（PG 报 42809
  "is not a procedure"）。清单里 `procedure.call_random` / `procedure.call_function`。
* **DROP 的错误码不统一**：`DROP FUNCTION` 不存在报 90077，`DROP PROCEDURE` 不存在报 90151，
  而"用 DROP PROCEDURE 删函数"也报 90151（PG 都是 42883，"is not a procedure"）。
  用例 `procedure.drop_wrong_kind` 目前按 Lealone 的错误码断言。

## 七、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0.sql` / `create_function_1.sql` / `create_function_2.sql`
   在本地这棵 PG 源码树（20devel）里不存在**。`ls src/test/regress/sql | grep -iE 'func|proc'`
   能看到的函数相关文件是 `create_function_c.sql`（C 语言函数）、`create_function_sql.sql`、
   `functional_deps.sql`、`hash_func.sql`、`misc_functions.sql`、`rangefuncs.sql`、`regproc.sql`、
   `plpgsql.sql`、`create_procedure.sql` 等，没有 `function.sql` 和 `create_function_0/1/2.sql`。
   所以函数部分的主体用的是 `create_function_sql.sql`，过程部分用 `create_procedure.sql`。
   这不是"我们挑着做"，是素材没有；`create_function_c.sql` / `rangefuncs.sql` 分别需要 C 语言
   函数和 `RETURNS SETOF`，等对应能力有了再纳入。
2. **`plpgsql.sql`（4756 行 / 935 条语句）整体不适用**：里面 100% 是 `LANGUAGE plpgsql` 的块语言
   与触发器，Lealone 直接报 `FEATURE_NOT_SUPPORTED: LANGUAGE PLPGSQL`。为了不让"整份文件跳过"变成
   一句空话，脚本 `pg/plpgsql.sql` 里按 PG 原文保留了 6 个代表性用例（触发器函数、`RAISE`、
   `IF` 递归、`FOR..LOOP` + `RETURN NEXT`、嵌套 `EXCEPTION`、`OUT` 参数 + `RECORD`），
   每个都带 PG 行号，清单里记为"无 PL/pgSQL"。**剩下 900 多条语句没有逐条登记**，这是
   本套件目前最大的透明性缺口，等 PL/pgSQL 有着落时再按段补。
3. **`portals.sql`**：按要求跳过（游标/portal 语义，不是函数/过程定义），没有纳入统计。
4. **没转换的 PG 断言**：`EXPLAIN` 计划、`\df` 等 psql 元命令、`pg_proc` / `information_schema`
   目录查询、`GRANT/REVOKE/SET ROLE` 权限、`ALTER FUNCTION/PROCEDURE/ROUTINE`——都随用例一起
   skip 了，原因写在清单里，没有偷偷删。
5. **转换时做过的适配**（改的是语法不是语义，都在脚本注释里逐条标注）：
   * PG 允许无名参数 `create function f(text, date)`，Lealone 要求参数带名字，函数体里用参数名
     代替 `$1/$2`；
   * PG 布尔输出 `t/f`，Lealone 是 `TRUE/FALSE`；
   * PG 过程体 `LANGUAGE SQL AS $$ INSERT ... VALUES (1, x) $$`，Lealone 过程体用 `?` 按位置
     引用参数：`AS 'INSERT ... VALUES (1, ?)'`；
   * `TRUNCATE cp_test` → `TRUNCATE TABLE cp_test`；
   * PG 用 `pg_proc.provolatile` 验证 IMMUTABLE/STABLE/VOLATILE，Lealone 没有目录表，改成
     "四种写法都能建函数且调用结果正确"；
   * 错误码不同但都是拒绝的用例，断言 Lealone 的错误码，并在注释里写明 PG 的错误码
     （42601/42804/42809/42883/42P13 ↔ 50100/42000/90022/90077/90151）。

## 八、口径与限制

* 一个 `.sql` 文件共用一个库，用例按文件顺序执行（和 PG 一致），所以用例之间可以有依赖；
  改脚本时注意函数名不要撞（Lealone 函数名大小写不敏感，`functest_B_1` 和 `functest_b_1` 是同一个）。
* 执行引擎用 `Statement.execute()`，因此 D6 那类"CALL 返回结果集"的用例没法在脚本里断言。
* `skip-list.txt` 的第三列是 PG 语句数，由 `PgSkipList.countStatements` 统计（能处理
  `--` 注释、`'...'`、`"..."`、`$$...$$`、`$tag$...$tag$`，psql 元命令不算语句）。
  加 `-Dpg.srcdir=...` 时会把清单里的行号范围和语句数跟 PG 源码逐条核对，写错就报错。

## 九、怎么继续往上加（给后续实现者的清单）

1. **打开用例**：从 `skip-list.txt` 删掉 id → 按 `pg/README.md` 把 `-- PG 期望` 改成 `>` 断言 →
   跑 `-Dtest=PgRegressTest`，红了就说明还没对齐。
2. **正在被实现的几格**（谁做完谁就能清空对应清单行）：
   * `RETURNS SETOF` / `RETURNS TABLE` → `function_sql.setof*` 3 条（14 条 PG 语句）
   * `OUT/INOUT/VARIADIC/STRICT/DEFAULT` 参数 → `procedure.inout_params`、`procedure.out_params`、
     `procedure.named_default`、`function_sql.strict` 4 条（34 条 PG 语句）
   * `LANGUAGE plpgsql` → `plpgsql.*` 6 条，之后可以按段把 `plpgsql.sql` 补全
3. **建议新增的用例**（这次发现的缺陷还没有对应脚本）：
   * D3 的 `WITH` 函数体 → 建议加 `function_sql.with_body`，期望 10；
   * D2 的内存库字符串返回 → 建议在 `docs`/JUnit 层单独加一个内存库场景，不要混进本套件。
4. **补齐 PG 覆盖面**：等 `create_function_c.sql`（C 语言函数，用不到）以外的文件补齐；
   `plpgsql.sql` 按段登记；`portals.sql` 如果以后支持游标再纳入。
