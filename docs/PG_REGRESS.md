# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 当前基线：`feature/stored-routines`（`d1de03e3b`，复合类型 `CREATE TYPE`/`RETURNS comp`/`SETOF <表行类型>`、
  `%ROWTYPE` 数组、存储过程 OUT/INOUT 参数与按参数类型重载、`LANGUAGE plpgsql` 过程体、`RETURNS VOID` + 普通 DML、
  `BEGIN ATOMIC`/`CASE ... END` 多语句体、DML `RETURNING`、`WITH ORDINALITY`、标量函数进 FROM、多 OUT record）
- 上一轮基线：`5a25375b5`（96 用例 / 56 执行 / 40 skip / 207 语句，r3b 之后又解锁了一批，见第六节）
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
    skip-list.txt           skip/known-fail 清单（37 条，本套件最重要的交付物之一）
    create_function_sql.sql 由 PG create_function_sql.sql 转换（40 个用例）
    create_procedure.sql    由 PG create_procedure.sql 转换（24 个用例）
    plpgsql.sql             由 PG plpgsql.sql 抽样转换（23 个用例）
    rangefuncs.sql          由 PG rangefuncs.sql 抽样转换（10 个用例）
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
[pgregress]   create_function_sql.sql  用例 40(执行 19, skip 21)  脚本语句  75(通过  75, 失败 0)  被skip用例占  70 条
[pgregress]   create_procedure.sql     用例 24(执行 14, skip 10)  脚本语句  61(通过  61, 失败 0)  被skip用例占  37 条
[pgregress]   plpgsql.sql              用例 23(执行 18, skip  5)  脚本语句  56(通过  56, 失败 0)  被skip用例占  12 条
[pgregress]   rangefuncs.sql           用例 10(执行  9, skip  1)  脚本语句  35(通过  35, 失败 0)  被skip用例占   5 条
[pgregress] 合计: 用例 97 (执行 60, skip 37), 脚本语句 227 (通过 227, 失败 0), 耗时 521ms
[pgregress] skip 清单: 37 条用例, 点名跳过的 PG 语句 204 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中 129 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中  55 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 抽样转换(清单里点名跳过的代表性语句 15 条)
[pgregress]   rangefuncs.sql           PG 共  428 条语句, 抽样转换(清单里点名跳过的代表性语句 5 条)
```

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* `converted` 的文件（前两个）里，PG 语句要么执行、要么在清单里点名；`sampled` 的文件
  （plpgsql/rangefuncs 这种上千行的长篇）只抽样转换，清单里点名的只是被 skip 的代表性用例；
  这也是为什么这两个文件的 PG 语句总数远大于"执行 + skip"。

## 五、skip 清单摘要（按原因分组，共 37 条 / 204 条 PG 语句）

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
| Lealone 无 anyarray/anyelement 多态类型 | 3 | 15 | function_sql.sql_body_polymorphic, function_sql.anyarray_setof, procedure.polymorphic |
| Lealone 无 SQL 标准函数体, 也无 TEMP SEQUENCE/DISCARD | 1 | 3 | function_sql.sql_body_tempseq |
| Lealone 没有 information_schema.routines 等视图 | 1 | 19 | function_sql.information_schema |
| Lealone 不支持 WINDOW 属性与 FUNCTION/PROCEDURE 互转 | 1 | 3 | function_sql.or_replace_window |
| Lealone 无 BEGIN ATOMIC 函数体与 EXPLAIN | 1 | 10 | function_sql.setof_inline |
| Lealone 函数体只允许单条查询(不支持 DDL) | 1 | 8 | function_sql.ddl_in_body |
| Lealone 无 CREATE OPERATOR CLASS/分区操作符类 | 1 | 6 | function_sql.partition_ops |
| Lealone 不支持 $1/$2 位置参数 | 1 | 1 | function_sql.bad_body_positional |
| Lealone 无 anyelement/anyarray 多态类型 | 1 | 4 | function_sql.empty_body_call |
| Lealone 的 CALL 不区分函数与过程 | 2 | 3 | procedure.call_random, procedure.call_function |
| Lealone 客户端没有 psql 元命令, 也没有 pg_get_functiondef() | 1 | 1 | procedure.psql_meta |
| Lealone 过程体不能执行 DDL | 1 | 2 | procedure.utility_body |
| Lealone 不支持 DEFAULT 参数与命名实参 | 1 | 7 | procedure.named_default |
| Lealone 不支持 ALTER PROCEDURE/FUNCTION, 过程也不接受 WINDOW/STRICT/VARIADIC/DEFAULT | 1 | 8 | procedure.alter_and_bad_def |
| Lealone 无过程级 EXECUTE 权限与 SET ROLE 语义 | 1 | 10 | procedure.privileges |
| Lealone 不支持 ALTER ROUTINE/DROP ROUTINE | 1 | 5 | procedure.routine_syntax |
| Lealone 无 CREATE TRIGGER 与触发器函数(RETURNS trigger) | 2 | 4 | plpgsql.trigger_room, plpgsql.trigger_raise |
| Lealone 无 anyelement/anyarray 多态参数类型 | 1 | 1 | plpgsql.polymorphic |
| PL/pgSQL 游标(OPEN/FETCH/CLOSE)与 refcursor 类型不支持, DDL 阶段报 50100 | 1 | 6 | plpgsql.cursor |
| 没有 unnest()/generate_series()(WITH ORDINALITY 本身已支持) | 1 | 5 | rangefuncs.with_ordinality_unnest |
| 这段用 plpgsql 的 FOR ... IN 查询(50100 PL/pgSQL: FOR ... IN query)遍历表, 该语法还不支持; RETURNS SETOF <表行类型> 本身已可用 | 1 | 4 | plpgsql.setof_composite_record |
| 不支持命名实参(a => null)与过程的 VARIADIC 参数, 也不支持同名过程按 OUT/IN 区分重载(90150) | 1 | 8 | procedure.out_params_named |

## 六、这一轮刷新：解锁了什么

基线从 `5a25375b5` 到 `d1de03e3b`，复合类型、过程 OUT/INOUT、过程重载、`LANGUAGE plpgsql` 过程体、
`RETURNS VOID` + DML、`BEGIN ATOMIC`、DML `RETURNING`、`WITH ORDINALITY`、标量函数进 FROM 都落地了。
其中一部分已由 w-setof/w-comp 在清单上直接解锁（本轮开始时就是执行状态，例如 `function_sql.void_insert`、
`sql_body_atomic`、`sql_body_case`、`atomic_body`、`empty_body`、`procedure.plpgsql_body`、`rangefuncs.insert_returning`）；
本轮又逐条核对，删掉 4 行 skip、新增 1 个 skip 用例：

| 用例 | 之前 | 现在 | 靠什么能力 |
| --- | --- | --- | --- |
| `plpgsql.out_params_exception` | skip（`RETURNS RECORD` 不支持） | 执行 6 条语句 | 带 OUT 参数的函数返回复合值 + `(f(x)).error_code` 取字段 |
| `rangefuncs.setof_composite` | skip（`SETOF <表行类型>` 不支持） | 执行 4 条语句 | `RETURNS SETOF <表行类型>`（参数改成命名的） |
| `procedure.inout_params` | skip（CREATE PROCEDURE 只收 IN） | 执行 12 条语句 | 过程 `INOUT` 参数（含复合类型 INOUT） |
| `procedure.out_params` | skip（CREATE PROCEDURE 只收 IN） | 执行 8 条语句 | 过程 `OUT` 参数 + 过程体内 DML + 查询 |
| `procedure.out_params_named`（新增 skip） | — | — | 命名实参 `a => null` 与过程的 `VARIADIC` 仍不支持 |

统计变化：用例 96 → 97，执行 56 → 60，skip 40 → 37，脚本语句 207 → 227（失败 0）。

## 七、剩余缺口与缺陷（都已实测复现）

### N1. 过程的 OUT 实参会被求值

```sql
create procedure ptest9(out a int) as $$ INSERT INTO cp_test_out VALUES (1,'a'); SELECT 1; $$;
call ptest9(1/0);   -- Lealone: 22012 Division by zero; PG: 正常返回(OUT 实参不求值)
```

PG 文档明确写了 OUT 位置的表达式不会被求值。用例 `procedure.out_params`（这一句按 Lealone 实际行为断言 22012）。

### N2. 无名参数的报错很误导

```sql
create function functest_A_2(text[]) returns int as 'SELECT $1[1]::int';  -- 42102 Table ")" not found
```

PG 允许无名参数（用 `$1` 引用）。Lealone 报的不是"不支持位置参数"，而是 `42102 Table ")" not found`，
`CREATE PROCEDURE p(int)`、`create function f(text) returns setof text ...` 也会撞到同样的解析错误。
用例 `function_sql.a2_array_arg`、`function_sql.bad_body_positional`（原因已按实际报错更新）。

### N3. `EXPLAIN (COSTS OFF)` 不支持

```sql
explain (costs off) select * from f();   -- 42001 Syntax error
explain select * from f();               -- 可以（输出 Plan）
```

PG 的 setof 内联用例全部用 `EXPLAIN (COSTS OFF)`，所以 `function_sql.setof_inline` 仍被挡住。

### N4. `ALTER PROCEDURE` 解析不了（`ALTER FUNCTION` 可以）

```sql
alter function f() stable;        -- 可以
alter procedure p(int) strict;    -- 42001 Syntax error
```

用例 `procedure.alter_and_bad_def`。

### N5. `SET/RESET SESSION AUTHORIZATION` 不支持

```sql
set session authorization u1;     -- 42001 Syntax error
reset session authorization;      -- 42001 Syntax error
```

`GRANT ... ON PROCEDURE`/`REVOKE ... ON PROCEDURE` 也还是表级授权（`42102 Table "PROCEDURE" not found`）。
挡住 `function_sql.security_definer`、`function_sql.leakproof`、`procedure.privileges`。

### N6. 整段执行 `leakproof`/`privileges` 用例时 suite 会挂住

把 `function_sql.leakproof`（PG 88-119）或 `procedure.privileges`（PG 258-267）放进执行集跑，
整个测试 60 秒超时（逐条拆开单独执行都正常返回错误），疑似 `OWNER TO`/`GRANT`/`SET SESSION AUTHORIZATION`
组合下的会话状态问题；这两段继续在 skip 清单里，等定位后再开。

### 仍然不支持的（清单里都有用例）

* **多态类型**：`anyelement`/`anyarray` 参数与返回（6 条用例：`function_sql.sql_body_polymorphic`、
  `function_sql.anyarray_setof`、`function_sql.empty_body_call`、`procedure.polymorphic`、`plpgsql.polymorphic` 等），
  报 `42102 Table "ANYELEMENT" not found`。
* **函数体/过程体里做 DDL**：`function_sql.ddl_in_body`、`procedure.utility_body`（`50100 函数体只能是查询或带 RETURNING 的 DML`）。
* **SQL 标准函数体**：`RETURN 表达式` / `BEGIN ATOMIC` 之外的三条（`sql_body_return`、`sql_body_view`、`sql_body_tempseq`），
  `CREATE FUNCTION ... LANGUAGE sql RETURN expr` 仍报 42001。
* **参数默认值/命名实参/过程 VARIADIC**：`procedure.named_default`、`procedure.out_params_named`（DEFAULT 报 42001，
  过程 VARIADIC 报 `50100 VARIADIC parameter in CREATE PROCEDURE`）。
* **plpgsql 的 `FOR ... IN <query>`**：`plpgsql.setof_composite_record`（50100）。
  注意 `RETURNS SETOF <表行类型>` 本身已经可用（`rangefuncs.setof_composite`、`%ROWTYPE` 数组已验证）。
* **游标/触发器**：`plpgsql.cursor`、`plpgsql.trigger_*`。
* **`unnest()`/`generate_series()`/`ARRAY[...]` 字面量/`$1` 位置参数**：`rangefuncs.with_ordinality_unnest`、
  `function_sql.a2_array_arg`、`function_sql.bad_body_positional`。
* **目录与 DDL 属性**：`pg_proc`（2）、`pg_get_functiondef()`（2）、`information_schema.routines`（1）、
  `ALTER FUNCTION` 的属性组合 + `SET search_path`（`volatility_catalog`、`strict_catalog`、`or_replace_window`）、
  `CREATE OPERATOR CLASS`（1）。
* **CALL 语义与 psql 元命令**：`CALL` 不区分函数与过程（`call_random`、`call_function`）、
  `\df`/`psql` 元命令（`psql_meta`、`ambiguous_drop`）、`ALTER ROUTINE`/`DROP ROUTINE`（`routine_syntax`）。

## 八、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0.sql` / `create_function_1.sql` / `create_function_2.sql`
   在本地这棵 PG 源码树（20devel）里不存在**。`ls src/test/regress/sql | grep -iE 'func|proc'`
   能看到的函数相关文件是 `create_function_c.sql`（C 语言函数）、`create_function_sql.sql`、
   `rangefuncs.sql`、`regproc.sql`、`plpgsql.sql`、`create_procedure.sql` 等。
   所以函数部分的主体用 `create_function_sql.sql`，setof 用 `rangefuncs.sql`，过程用
   `create_procedure.sql`。这不是"我们挑着做"，是素材没有。
2. **`plpgsql.sql`（4756 行 / 935 条语句）只抽样转换**：现在是 23 个用例（执行 18 个、skip 5 个），
   覆盖递归、`FOUND`、`PERFORM`、`RAISE`、嵌套 `EXCEPTION`、`SELECT INTO`、`OUT`/`INOUT`/多 `OUT`、
   `RETURN NEXT`/`RETURN QUERY`/`RETURNS TABLE`、`GET DIAGNOSTICS ROW_COUNT`、块标签限定名、函数内 DML；
   触发器、游标、多态参数、`RECORD` + DML `RETURNING`、`RETURNS RECORD`、`EXECUTE`、`CASE` 语句、
   `ASSERT` 的段落仍然 skip 或没抽样。**剩下几百条语句没有逐条登记**，这是本套件目前最大的透明性缺口。
3. **`rangefuncs.sql`（827 行 / 428 条语句）也只抽样**：转过来 10 个用例（执行 9 个），
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
9. **过程体里带 OUT/INOUT 参数**：过程体最后一条语句必须是查询（列数和 OUT 参数对得上），
   参数统一用 `?` 按位置引用（写参数名会报 42122）；带 OUT/INOUT 的过程体里不能再嵌套 `CALL`。
10. **共享表要隔离**：一个 `.sql` 文件共用一个库，PG 里多个用例共用的表（如 `cp_test`）如果被新用例插了行，
    会打乱后面用例的期望值，所以新用例改用独立表（如 `cp_test_out`）并在注释里说明。

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

## 八、这一轮刷新：SQL标准函数体 + 函数体/过程体里做DDL

解锁 4 个用例（`sql_body_view`、`sql_body_return` 依赖 `RETURN <表达式>`/`BEGIN ... END`，
`ddl_in_body`、`procedure.utility_body` 依赖函数体/过程体里的 DDL），执行用例 60 → 64，
脚本语句 227 → 251，skip 清单 37 → 33 条：

| 用例 | 之前 | 现在 | 靠什么能力 |
| --- | --- | --- | --- |
| `function_sql.sql_body_return` | skip（无 SQL 标准函数体） | 执行 16 条语句 | `RETURN <表达式>`、`RETURN QUERY`、`BEGIN [ATOMIC] ... END`、块内空语句 `;;` |
| `function_sql.sql_body_view` | skip（无 SQL 标准函数体） | 执行 7 条语句 | `RETURN (SELECT ...)` 子查询表达式 + 函数体里引用视图 |
| `function_sql.ddl_in_body` | skip（函数体只允许单条查询） | 执行 15 条语句 | `AS $$ ... $$` 多语句函数体 + 函数体里的 `CREATE TABLE`/`ALTER TABLE` |
| `procedure.utility_body` | skip（过程体里不能执行 DDL） | 执行 3 条语句 | 过程体的 `BEGIN ATOMIC` 里执行 `CREATE TABLE`（PG 11 这里报 0A000，Lealone 支持，断言改成建成功） |

脚本切分器（`PgRegressRunner`）这一轮又补了两处：
`BEGIN ... END`（不带 `ATOMIC`）也算一个块的开始，但 `begin;`/`begin transaction` 不算；
字符串、`$$...$$`、块注释**没闭合**时整段还在语句里，不能按行尾分号切分
（`ddl_in_body` 的多行 `$$ ... $$` 函数体需要这个）。

仍然 skip 的相邻缺口：`function_sql.sql_body_tempseq`（`CREATE TEMP SEQUENCE` + `DISCARD TEMP`
以及"临时对象被删时连带删除依赖它的函数"）。
