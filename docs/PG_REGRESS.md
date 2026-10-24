# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 当前基线：`feature/stored-routines`（`5a25375b5`，SQL 函数 6 个缺陷修复 + PL/pgSQL 第二轮语义补齐：
  FOUND/GET DIAGNOSTICS、裸 RETURN、多 OUT、RETURN NEXT/QUERY、RETURNS TABLE、record/%ROWTYPE、
  块标签限定名、WITH 函数体、CALL 结果集）
- 上一轮基线：`b388d91b3`（89 用例 / 45 执行 / 53 skip / 101 语句），对比见第六节
- 对照的 PG 源码：本地 `postgres` 20devel 的 `src/test/regress/sql/`
- 只加测试代码和资源，**没有改任何产品代码**；发现的缺陷都记在第七节，不在本工作线修

## 一、为什么做这件事

Lealone 的 PG 风格存储函数/过程是新增能力。PG 的回归测试是别人写好的、十几年积累的边界用例，
拿来当尺子能量出两类东西：

1. **回归**：已经能跑的语义（函数返回、setof 表函数、CALL 的写入可见、DROP 语义、PL/pgSQL 控制流…）
   一旦被改坏，这里会红。
2. **差距**：跑不了的用例按"缺什么能力"归类计数，谁在实现触发器、FOUND、游标、RECORD，
   直接看清单就知道自己那一格什么时候能清零。

## 二、目录与文件

```
lealone-test/src/test/java/com/lealone/test/pg/
    PgRegressTest.java      JUnit 入口：加载清单、执行、打印统计、有失败就 fail
    PgRegressRunner.java    执行引擎：H2 风格脚本解析 + 断言 + 每个文件一个库
    PgSkipList.java         skip 清单解析、清单与脚本对账、PG 语句计数
lealone-test/src/test/resources/pg/
    README.md               脚本格式、清单格式、怎么打开被 skip 的用例
    skip-list.txt           skip/known-fail 清单（60 条，本套件最重要的交付物之一）
    create_function_sql.sql 由 PG create_function_sql.sql 转换（40 个用例）
    create_procedure.sql    由 PG create_procedure.sql 转换（22 个用例）
    plpgsql.sql             由 PG plpgsql.sql 抽样转换（18 个用例）
    rangefuncs.sql          由 PG rangefuncs.sql 抽样转换（9 个用例）
docs/PG_REGRESS.md          本文档
```

脚本格式直接复用仓库已有的 H2 风格脚本测试
`com.lealone.test.script.SqlScriptTest`（语句 + `> ` 期望输出 + `> exception <code>`），
只加了 `-- @case`（用例 id，清单的键）和 `-- @pg`（PG 出处）两个标记。

## 三、怎么跑

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21

# 单独跑（约 0.5 秒，不需要 PG 源码）
rm -rf lealone-test/target/test-data
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-pgregress \
    -Dtest=PgRegressTest -DfailIfNoTests=false

# 同时校验 skip 清单里记录的 PG 行号/语句数（维护清单时用，CI 不需要）
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-pgregress \
    -Dtest=PgRegressTest -Dpg.srcdir=/home/jovyan/data/work/postgres/src/test/regress/sql
```

**默认门禁**：类名是 `PgRegressTest`，`surefire` 默认就会跑；整轮实测 0.5 秒左右（远低于 60 秒的
门槛），所以既没有 `@Ignore`，也没有在 `pom.xml` 里排除。它只在 `target/test-data/pgregress/`
下建库（每轮开始时先清空该目录），不和别的测试抢数据；跑完把库留在 `target` 下
（`target` 不入库，门禁前的 `rm -rf lealone-test/target/test-data` 会一并清掉）。

可选系统属性：`-Dpg.dir=<脚本目录>`、`-Dpg.skip=<清单文件>`、`-Dpg.srcdir=<PG sql 目录>`。

## 四、一次完整运行的统计

```
[pgregress]   create_function_sql.sql  用例 40(执行 16, skip 24)  脚本语句  58(通过  58, 失败 0)  被skip用例占  86 条
[pgregress]   create_procedure.sql     用例 22(执行  9, skip 13)  脚本语句  38(通过  38, 失败 0)  被skip用例占  54 条
[pgregress]   plpgsql.sql              用例 23(执行 17, skip  6)  脚本语句  52(通过  52, 失败 0)  被skip用例占  16 条
[pgregress]   rangefuncs.sql           用例 10(执行  8, skip  2)  脚本语句  31(通过  31, 失败 0)  被skip用例占   8 条
[pgregress] 合计: 用例 95 (执行 50, skip 45), 脚本语句 179 (通过 179, 失败 0), 耗时 468ms
[pgregress] skip 清单: 45 条用例, 点名跳过的 PG 语句 250 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中 146 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中  73 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 抽样转换(清单里点名跳过的代表性语句 21 条)
[pgregress]   rangefuncs.sql           PG 共  428 条语句, 抽样转换(清单里点名跳过的代表性语句 10 条)
```

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* `converted` 的文件（前两个）里，PG 语句要么执行、要么在清单里点名；`sampled` 的文件
  （plpgsql/rangefuncs 这种上千行的长篇）只抽样转换，清单里点名的只是被 skip 的代表性用例；
  这也是为什么这两个文件的 PG 语句总数远大于"执行 + skip"。

## 五、skip 清单摘要（按原因分组，共 45 条 / 250 条 PG 语句）

| 原因 | 条数 | PG语句 | 用例 id |
| --- | --- | --- | --- |
| Lealone 无 SET search_path, CREATE USER 必须带 PASSWORD | 1 | 4 | function_sql.setup |
| 缺少 $1 位置参数与 ARRAY[...] 字面量(数组参数本身已支持) | 1 | 1 | function_sql.a2_array_arg |
| Lealone 没有 pg_proc/regproc 系统目录 | 1 | 4 | function_sql.pg_proc |
| Lealone 没有 pg_proc.provolatile 与 ALTER FUNCTION | 1 | 4 | function_sql.volatility_catalog |
| Lealone 不支持 SECURITY DEFINER/INVOKER | 1 | 8 | function_sql.security_definer |
| Lealone 不支持 LEAKPROOF 属性 | 1 | 16 | function_sql.leakproof |
| Lealone 没有 pg_proc.proisstrict 与 ALTER FUNCTION(CALLED ON NULL INPUT 已支持) | 1 | 5 | function_sql.strict_catalog |
| Lealone 没有 pg_get_functiondef() | 2 | 17 | function_sql.pg_get_functiondef, function_sql.pg_get_functiondef_sqlbody |
| Lealone 无 SQL 标准函数体(RETURN 表达式) | 2 | 12 | function_sql.sql_body_return, function_sql.sql_body_view |
| Lealone 无 BEGIN ATOMIC 多语句函数体 | 2 | 11 | function_sql.sql_body_atomic, function_sql.sql_body_case |
| Lealone 无 anyarray/anyelement 多态类型 | 3 | 15 | function_sql.sql_body_polymorphic, function_sql.anyarray_setof, procedure.polymorphic |
| Lealone 无 SQL 标准函数体, 也无 TEMP SEQUENCE/DISCARD | 1 | 3 | function_sql.sql_body_tempseq |
| Lealone 没有 information_schema.routines 等视图 | 1 | 19 | function_sql.information_schema |
| Lealone 不支持 WINDOW 属性与 FUNCTION/PROCEDURE 互转 | 1 | 3 | function_sql.or_replace_window |
| Lealone 无 BEGIN ATOMIC 函数体与 EXPLAIN | 1 | 10 | function_sql.setof_inline |
| 函数体只支持带RETURNING的DML(voidtest3 是不带RETURNING的INSERT) | 1 | 6 | function_sql.void_insert |
| Lealone 函数体只允许单条查询(不支持 DDL) | 1 | 8 | function_sql.ddl_in_body |
| Lealone 无 CREATE OPERATOR CLASS/分区操作符类 | 1 | 6 | function_sql.partition_ops |
| Lealone 不支持 $1/$2 位置参数 | 1 | 1 | function_sql.bad_body_positional |
| Lealone 无 anyelement/anyarray 多态类型 | 1 | 4 | function_sql.empty_body_call |
| Lealone 的 CALL 不区分函数与过程 | 2 | 3 | procedure.call_random, procedure.call_function |
| Lealone 客户端没有 psql 元命令, 也没有 pg_get_functiondef() | 1 | 1 | procedure.psql_meta |
| CREATE 能过但 CALL 时报 42000: 过程体不支持 BEGIN ATOMIC 块 | 1 | 5 | procedure.atomic_body |
| Lealone 过程体不能执行 DDL | 1 | 2 | procedure.utility_body |
| CREATE PROCEDURE 只接受 IN 参数(42102), OUT/INOUT 在 CREATE FUNCTION 里已支持 | 1 | 6 | procedure.inout_params |
| Lealone 不支持 DEFAULT 参数与命名实参 | 1 | 7 | procedure.named_default |
| CREATE 能过但 CALL 时报 42000: 过程体不支持 BEGIN ATOMIC 空块 | 1 | 3 | procedure.empty_body |
| CREATE PROCEDURE 只接受 IN 参数(42102); OUT/INOUT/VARIADIC 在 CREATE FUNCTION 里已支持 | 1 | 12 | procedure.out_params |
| Lealone 不支持 ALTER PROCEDURE/FUNCTION, 过程也不接受 WINDOW/STRICT/VARIADIC/DEFAULT | 1 | 8 | procedure.alter_and_bad_def |
| Lealone 无过程级 EXECUTE 权限与 SET ROLE 语义 | 1 | 10 | procedure.privileges |
| Lealone 不支持 ALTER ROUTINE/DROP ROUTINE | 1 | 5 | procedure.routine_syntax |
| Lealone 无 CREATE TRIGGER 与触发器函数(RETURNS trigger) | 2 | 4 | plpgsql.trigger_room, plpgsql.trigger_raise |
| Lealone 无 anyelement/anyarray 多态参数类型 | 1 | 1 | plpgsql.polymorphic |
| PL/pgSQL 游标(OPEN/FETCH/CLOSE)与 refcursor 类型不支持, DDL 阶段报 50100 | 1 | 6 | plpgsql.cursor |
| RETURNS RECORD 复合返回类型不支持, 且 leaker_1 用 (f(x)).field 取复合返回值的字段 | 1 | 6 | plpgsql.out_params_exception |
| Lealone 无 SETOF <表行类型>, 也不支持 $1 位置参数 | 1 | 5 | rangefuncs.setof_composite |
| 没有 unnest()/generate_series()(WITH ORDINALITY 本身已支持) | 1 | 5 | rangefuncs.with_ordinality_unnest |
| RETURNS SETOF <表行类型> 不支持(只支持标量 setof 与 RETURNS TABLE) | 1 | 4 | plpgsql.setof_composite_record |


## 六、这一轮刷新：解锁了什么

基线从 `b388d91b3` 到 `5a25375b5`，SQL 函数修了 6 个缺陷、PL/pgSQL 补齐了 FOUND/OUT/SETOF 等语义。
逐条核对后删掉 7 行 skip（`function_sql.setof`、`procedure.collation`、`plpgsql.found`、`plpgsql.perform`、
`plpgsql.out_assign`、`plpgsql.multi_out`、`plpgsql.for_loop_setof`），新增 5 个用例（4 个执行 + 1 个 skip）：
执行用例 34 → 45，脚本语句 112 → 142（+30），skip 清单 55 → 49 条。
之后又解锁 `procedure.ambiguous_drop`：执行用例 49 → 50，脚本语句 167 → 179（+12），skip 清单 46 → 45 条。

| 用例 | 之前 | 现在 | 靠什么能力 |
| --- | --- | --- | --- |
| `plpgsql.found` | skip（无 FOUND） | 执行 4 条语句 | `FOUND` 魔法变量 |
| `plpgsql.perform` | skip（依赖 FOUND） | 执行 4 条语句 | `PERFORM` + `FOUND` |
| `plpgsql.out_assign` | skip（缺陷：裸 RETURN） | 执行 2 条语句 | 有 OUT 参数时裸 `RETURN;` / 自然结束 |
| `plpgsql.inout_return` | 已执行（改成显式 RETURN 才通过） | 执行 2 条语句 | 改回 PG 原样的自然结束写法 |
| `plpgsql.multi_out` | skip（多 OUT 50100） | 执行 2 条语句 | PL/pgSQL 多个 OUT 返回一行 |
| `plpgsql.for_loop_setof` | skip（SETOF 50100） | 执行 2 条语句 | 标量 `RETURNS SETOF` + `RETURN NEXT` |
| `plpgsql.select_into` | 已执行（用 `x is not null` 代替 FOUND） | 执行 4 条语句 | 改回 PG 原样的 `IF FOUND` |
| `function_sql.setof` | skip（generate_series + select 列表展开） | 执行 2 条语句 | body 换成 `SYSTEM_RANGE` + `SELECT * FROM f()` |
| `function_sql.strict` | 已执行（缺 F_2） | 执行 13 条语句 | 补上 `CALLED ON NULL INPUT` |
| `procedure.collation` | skip（CALL 结果集拿不到） | 执行 2 条语句 | `CALL` 结果集 + 过程体 `SELECT ? = ?` |
| `procedure.ambiguous_drop` | skip（过程同名唯一，不能重载） | 执行 12 条语句 | 过程按参数类型重载 + `DROP PROCEDURE name(类型/模式)`（见 docs/PROCEDURE.md） |
| `function_sql.overload` | skip（不支持按参数类型重载） | 执行 8 条语句 | `CREATE/DROP FUNCTION` 按参数类型重载（见 docs/PG_FUNCTION.md 第五节） |

新加的用例（都是 PG 原文段落）：

| 新用例 | PG 出处 | 覆盖的能力 |
| --- | --- | --- |
| `plpgsql.qualified_names` | plpgsql.sql:2841-2858 | 块标签 + `outerblock.param1` 限定名 |
| `plpgsql.table_function` | plpgsql.sql:3540-3546 | `RETURNS TABLE` + `RETURN QUERY` |
| `plpgsql.table_function_next` | plpgsql.sql:3548-3557 | `RETURNS TABLE` 的列当变量 + `RETURN NEXT` |
| `plpgsql.return_query_row_count` | plpgsql.sql:3561-3570 | `RETURN QUERY VALUES` + `GET DIAGNOSTICS ROW_COUNT` + `FOUND` |
| `plpgsql.setof_composite_record`（skip） | plpgsql.sql:1487-1512 | `RETURNS SETOF <表行类型>` 仍不支持 |

按特性分组计数（新增执行用例 11 个）：

| 特性 | 新执行用例 | 说明 |
| --- | --- | --- |
| PL/pgSQL `FOUND` / `PERFORM` / `GET DIAGNOSTICS ROW_COUNT` | 3 | `plpgsql.found`、`plpgsql.perform`（解锁）、`plpgsql.return_query_row_count`（新增） |
| PL/pgSQL 裸 `RETURN;` / `INOUT` 自然结束 / 多 `OUT` | 2 | `plpgsql.out_assign`、`plpgsql.multi_out`（解锁）；`plpgsql.inout_return` 改回 PG 原样写法 |
| PL/pgSQL `SETOF` / `RETURN NEXT` / `RETURN QUERY` / `RETURNS TABLE` | 3 | `plpgsql.for_loop_setof`（解锁）、`plpgsql.table_function`、`plpgsql.table_function_next`（新增） |
| PL/pgSQL 块标签限定名 | 1 | `plpgsql.qualified_names`（新增） |
| SQL 函数 `RETURNS SETOF` | 1 | `function_sql.setof`（解锁，body 换成 SYSTEM_RANGE） |
| `CALLED ON NULL INPUT` | 0（补进既有用例） | `function_sql.strict` 补上 PG 的 F_2 |
| 过程的 `CALL` 结果集 | 1 | `procedure.collation`（解锁） |
| 合计 | 11 | |

## 七、剩余缺口与缺陷（都已实测复现）

### N1. 标量函数不能出现在 `FROM` 子句里

```sql
create function f1(i int, out j int) returns int as $$ begin return i + 1; end $$ language plpgsql;
select * from f1(42);   -- 90000 Function "F1" must return a result set
```

PG 对任何函数都允许 `FROM f(...)`（单行结果集），Lealone 只允许 setof/TABLE/多 OUT 的函数。
`plpgsql.out_assign`、`plpgsql.inout_return` 因此只断言 `SELECT f(42)`（PG 原文里还有 `SELECT * FROM f1(42)`）。

### N2. 多 OUT 函数的标量调用拿不到 record

```sql
create function f1(i int, out j int, out k text) as ... language plpgsql;
select f1(42);   -- 50000
```

PG 返回 `(43,foo)`。Lealone 必须写 `SELECT * FROM f1(42)`。用例 `plpgsql.multi_out`。

### N3. 没有 `least()` / `greatest()`

```sql
select least('a','b');   -- 42122 Column not found
```

PG 的 `create_procedure.sql:164-170` 用 `least('a','b')` 调过程，这里只能直接传它的结果 `'a'`。
用例 `procedure.collation`。

### N4. `WITH ORDINALITY` 不支持

```sql
select * from rngfunct(1) with ordinality as z(a,b,ord);   -- 42000 Syntax error
```

用例 `rangefuncs.with_ordinality`（这一段同时还依赖 `unnest()`/`generate_series()`）。

### N5. 过程体的 `BEGIN ATOMIC` 到 `CALL` 时才报错

```sql
create procedure pe() as 'BEGIN ATOMIC END';   -- CREATE 成功
call pe();                                     -- 42000 Syntax error "BEGIN ATOMIC"
```

和之前"函数体创建时不校验"是一类问题：DDL 阶段不校验过程体。用例 `procedure.atomic_body`、
`procedure.empty_body`。

### 仍然不支持的（清单里都有用例）

* **DML 相关**：SQL 函数体只能是查询（`INSERT ... RETURNING`/DDL 都不行）；`INSERT ... RETURNING ... INTO record`
  也不行（引擎没有 DML RETURNING）；过程体不能执行 DDL。用例 `function_sql.void_insert`、
  `function_sql.ddl_in_body`、`plpgsql.record`、`rangefuncs.insert_returning`、`procedure.utility_body`。
* **返回类型**：`RETURNS SETOF <表行类型>`、`RETURNS RECORD` 与 `(f(x)).field` 复合字段访问。
  用例 `plpgsql.setof_composite_record`、`plpgsql.out_params_exception`、`rangefuncs.setof_composite`。
* **CREATE PROCEDURE 只收 IN 参数**：写 `OUT/INOUT` 报 42102（同名能力在 CREATE FUNCTION 里有）。
  用例 `procedure.inout_params`、`procedure.out_params`。
* **游标 / refcursor**、**触发器**、**anyelement/anyarray 多态**、**RETURN QUERY EXECUTE/EXECUTE 动态 SQL**、
  **GET DIAGNOSTICS 之外的诊断项**、**CASE 语句**、**ASSERT**：用例 `plpgsql.cursor`、`plpgsql.trigger_*`、
  `plpgsql.polymorphic`、`function_sql.anyarray_setof` 等。
* **DDL 属性/目录**：`pg_proc`、`pg_get_functiondef()`、`information_schema.routines`、`ALTER FUNCTION/PROCEDURE/ROUTINE`、
  `SECURITY DEFINER`、`LEAKPROOF`、`WINDOW`、按参数类型重载、`DEFAULT`/命名实参。原因都在清单里。
* **其它 SQL 能力**：`ARRAY[...]` 字面量、`$1/$2` 位置参数、`generate_series()`、`unnest()`、
  `SELECT f()` 的 setof 展开（会明确报 50100，要求写 `SELECT * FROM f()`）、SQL 标准函数体
  （`RETURN expr` / `BEGIN ATOMIC`）、`EXPLAIN`、`CREATE OPERATOR CLASS`。

## 八、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0.sql` / `create_function_1.sql` / `create_function_2.sql`
   在本地这棵 PG 源码树（20devel）里不存在**。`ls src/test/regress/sql | grep -iE 'func|proc'`
   能看到的函数相关文件是 `create_function_c.sql`（C 语言函数）、`create_function_sql.sql`、
   `rangefuncs.sql`、`regproc.sql`、`plpgsql.sql`、`create_procedure.sql` 等。
   所以函数部分的主体用 `create_function_sql.sql`，setof 用 `rangefuncs.sql`，过程用
   `create_procedure.sql`。这不是"我们挑着做"，是素材没有。
2. **`plpgsql.sql`（4756 行 / 935 条语句）只抽样转换**：这一轮扩到 23 个用例（执行 16 个、skip 7 个），
   覆盖递归、`FOUND`、`PERFORM`、`RAISE`、嵌套 `EXCEPTION`、`SELECT INTO`、`OUT`/`INOUT`/多 `OUT`、
   `RETURN NEXT`/`RETURN QUERY`/`RETURNS TABLE`、`GET DIAGNOSTICS ROW_COUNT`、块标签限定名、函数内 DML；
   触发器、游标、多态参数、`RECORD` + DML `RETURNING`、`RETURNS RECORD`、`EXECUTE`、`CASE` 语句、
   `ASSERT` 的段落仍然 skip 或没抽样。**剩下几百条语句没有逐条登记**，这是本套件目前最大的透明性缺口。
3. **`rangefuncs.sql`（827 行 / 428 条语句）也只抽样**：转过来 9 个用例（执行 6 个），
   依赖 `WITH ORDINALITY`、`unnest()`、`generate_series()`、`SETOF <表行类型>` 的段落 skip 或没抽。
4. **`portals.sql`**：按要求没纳入（游标/portal 语义，不是函数/过程定义）。
5. 没转换的 PG 断言：`EXPLAIN` 计划、`\df` 等 psql 元命令、`pg_proc` / `information_schema` 目录查询、
   `GRANT/REVOKE/SET ROLE` 权限、`ALTER FUNCTION/PROCEDURE/ROUTINE`——都随用例一起 skip，
   原因写在清单里，没有偷偷删。

## 九、转换/适配规则

改的是语法不是语义，脚本里逐条注明：

1. **参数必须命名**：PG 允许无名参数 `f(text, date)` 和 `$1/$2` 位置引用，Lealone 要求命名，
   函数体里用参数名。
2. **PL/pgSQL 函数体必须写在一行**：脚本解析器按"行末是 `;`"切语句，多行块体会被切断。
   写成 `AS $$ DECLARE ...; BEGIN ...; END; $$;` 一行即可（见 `pg/README.md`）。
3. **布尔/空值显示**：PG `t/f`，Lealone `TRUE/FALSE`；PG 的一行空值在这里是 `null`。
4. **过程体参数**：PG 过程体按名字/位置引用，Lealone 过程体用 `?` 按位置引用。
5. **FOUND**：现在已经支持，`plpgsql.found`/`perform`/`select_into` 都用 PG 原样的 `IF FOUND`。
6. **RAISE 条件名**：`RAISE syntax_error` 改成 `RAISE EXCEPTION '...'`，其余语义不变。
7. **generate_series 缺失**：`RETURNS TABLE`/setof 的用例里用 `SYSTEM_RANGE` 替代（列名 `X`），并注明。
8. **错误码不同但都是拒绝**的用例，断言 Lealone 的错误码，并在注释里写明 PG 的错误码
   （42601/42804/42809/42883/42P13 ↔ 50100/42000/50000/90022/90077/90151）。

## 十、口径与限制

* 一个 `.sql` 文件共用一个库，用例按文件顺序执行（和 PG 一致），所以用例之间可以有依赖；
  改脚本时注意函数名不要撞（Lealone 函数名大小写不敏感）。
* 执行引擎用 `Statement.execute()`，因此 N7 那类"CALL 返回结果集"的用例没法在脚本里断言。
* `skip-list.txt` 的第三列是 PG 语句数，由 `PgSkipList.countStatements` 统计（能处理
  `--` 注释、`'...'`、`"..."`、`$$...$$`、`$tag$...$tag$`，psql 元命令不算语句）。
  加 `-Dpg.srcdir=...` 时会把清单里的行号范围和语句数跟 PG 源码逐条核对，写错就报错。

## 十一、怎么继续往上加

1. **打开用例**：从 `skip-list.txt` 删掉 id → 按 `pg/README.md` 把 `-- PG 期望` 改成 `>` 断言 →
   跑 `-Dtest=PgRegressTest`，红了就说明还没对齐。
2. **建议新增的用例**（这轮发现、还没登记的缺陷）：
   * N1 标量函数出现在 `FROM` 里 → 等支持后把 `SELECT * FROM f1(42)` 加回 `plpgsql.out_assign`/`inout_return`；
   * N2 多 OUT 标量调用 → 等支持后把 `SELECT f1(42)` 加回 `plpgsql.multi_out`；
   * N3 `least()/greatest()` → 等支持后把 `procedure.collation` 改回 PG 原样的 `least('a','b')`。
3. **还能解锁的几格**（谁做完谁就能清空对应清单行）：
   * 触发器 → `plpgsql.trigger_*` 2 条；
   * DML `RETURNING` → `plpgsql.record` 1 条（record 本身已可用）；
   * `RETURNS RECORD`/复合字段 → `plpgsql.out_params_exception` 1 条；
   * `RETURNS SETOF <表行类型>` → `plpgsql.setof_composite_record`、`rangefuncs.setof_composite` 2 条；
   * 过程级 OUT/INOUT → `procedure.inout_params`、`procedure.out_params` 2 条；
   * 游标 → `plpgsql.cursor` 1 条；`WITH ORDINALITY` → `rangefuncs.with_ordinality` 1 条。
4. **补齐 PG 覆盖面**：`plpgsql.sql` 按段登记（尤其是游标、`RETURN QUERY EXECUTE`、`CASE` 语句）；
   `rangefuncs.sql` 里 `WITH ORDINALITY` 落地后再补；`portals.sql` 如果以后支持游标再纳入。
