# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 当前基线：`feature/stored-routines`（`b388d91b3`，已合入 SETOF/TABLE、OUT/INOUT/STRICT/VARIADIC、
  PL/pgSQL 子集、调用期类型转换）
- 上一轮基线：`9261ba9bd`（只有标量 SQL 函数/过程），对比见第六节
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
[pgregress]   create_function_sql.sql  用例 40(执行 11, skip 29)  脚本语句  41(通过  41, 失败 0)  被skip用例占  96 条
[pgregress]   create_procedure.sql     用例 22(执行  5, skip 17)  脚本语句  19(通过  19, 失败 0)  被skip用例占  68 条
[pgregress]   plpgsql.sql              用例 18(执行  7, skip 11)  脚本语句  20(通过  20, 失败 0)  被skip用例占  27 条
[pgregress]   rangefuncs.sql           用例  9(执行  6, skip  3)  脚本语句  21(通过  21, 失败 0)  被skip用例占   9 条
[pgregress] 合计: 用例 89 (执行 29, skip 60), 脚本语句 101 (通过 101, 失败 0), 耗时 482ms
[pgregress] skip 清单: 60 条用例, 点名跳过的 PG 语句 309 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中 156 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中  92 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 抽样转换(清单里点名跳过的代表性语句 40 条)
[pgregress]   rangefuncs.sql           PG 共  428 条语句, 抽样转换(清单里点名跳过的代表性语句 21 条)
```

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* `converted` 的文件（前两个）里，PG 语句要么执行、要么在清单里点名；`sampled` 的文件
  （plpgsql/rangefuncs 这种上千行的长篇）只抽样转换，清单里点名的只是被 skip 的代表性用例；
  这也是为什么这两个文件的 PG 语句总数远大于"执行 + skip"。

## 五、skip 清单摘要（按原因分组，共 60 条 / 309 条 PG 语句）

| 原因 | 条数 | PG语句 | 用例 id |
| --- | --- | --- | --- |
| Lealone 无 SET search_path, CREATE USER 必须带 PASSWORD | 1 | 4 | function_sql.setup |
| 缺少 $1 位置参数与 ARRAY[...] 字面量(数组参数本身已支持) | 1 | 1 | function_sql.a2_array_arg |
| Lealone 没有 pg_proc/regproc 系统目录 | 1 | 4 | function_sql.pg_proc |
| Lealone 没有 pg_proc.provolatile 与 ALTER FUNCTION | 1 | 4 | function_sql.volatility_catalog |
| Lealone 不支持 SECURITY DEFINER/INVOKER | 1 | 8 | function_sql.security_definer |
| Lealone 不支持 LEAKPROOF 属性 | 1 | 16 | function_sql.leakproof |
| Lealone 没有 pg_proc.proisstrict/ALTER FUNCTION, 也不认识 CALLED ON NULL INPUT 关键字 | 1 | 5 | function_sql.strict_catalog |
| Lealone 没有 pg_get_functiondef() | 2 | 17 | function_sql.pg_get_functiondef, function_sql.pg_get_functiondef_sqlbody |
| Lealone 无 SQL 标准函数体(RETURN 表达式) | 2 | 12 | function_sql.sql_body_return, function_sql.sql_body_view |
| Lealone 无 BEGIN ATOMIC 多语句函数体 | 2 | 11 | function_sql.sql_body_atomic, function_sql.sql_body_case |
| Lealone 无 anyarray/anyelement 多态类型 | 3 | 15 | function_sql.sql_body_polymorphic, function_sql.anyarray_setof, procedure.polymorphic |
| Lealone 无 SQL 标准函数体, 也无 TEMP SEQUENCE/DISCARD | 1 | 3 | function_sql.sql_body_tempseq |
| Lealone 没有 information_schema.routines 等视图 | 1 | 19 | function_sql.information_schema |
| Lealone 不支持按参数类型重载 | 2 | 15 | function_sql.overload, procedure.ambiguous_drop |
| Lealone 不支持 WINDOW 属性与 FUNCTION/PROCEDURE 互转 | 1 | 3 | function_sql.or_replace_window |
| Lealone 无 generate_series(), 且 select 列表里不展开 setof 函数 | 1 | 2 | function_sql.setof |
| Lealone 无 BEGIN ATOMIC 函数体与 EXPLAIN | 1 | 10 | function_sql.setof_inline |
| Lealone 函数体只允许单条查询(不支持 INSERT ... RETURNING) | 1 | 6 | function_sql.void_insert |
| 缺陷: RETURNS SETOF VOID 每行返回 Java 序列化字节, 且依赖 generate_series() | 1 | 2 | function_sql.setof_void |
| Lealone 函数体只允许单条查询(不支持 DDL) | 1 | 8 | function_sql.ddl_in_body |
| Lealone 无 CREATE OPERATOR CLASS/分区操作符类 | 1 | 6 | function_sql.partition_ops |
| 缺陷: 建函数时不校验函数体, 错误推迟到调用时 | 1 | 1 | function_sql.bad_body_return_type |
| 缺陷: 函数体返回多列时静默取第一列 | 1 | 1 | function_sql.bad_body_columns |
| Lealone 不支持 $1/$2 位置参数 | 1 | 1 | function_sql.bad_body_positional |
| Lealone 无 anyelement/anyarray 多态类型 | 1 | 4 | function_sql.empty_body_call |
| Lealone 的 CALL 不区分函数与过程 | 2 | 3 | procedure.call_random, procedure.call_function |
| Lealone 客户端没有 psql 元命令, 也没有 pg_get_functiondef() | 1 | 1 | procedure.psql_meta |
| Lealone 无 BEGIN ATOMIC 过程体 | 1 | 5 | procedure.atomic_body |
| Lealone 过程体不能执行 DDL | 1 | 2 | procedure.utility_body |
| 缺陷: CALL 返回结果集时 execute() 拿不到行 | 2 | 4 | procedure.body_select, procedure.collation |
| 缺陷: 过程体里嵌套 CALL 参数绑定冲突(90012) | 1 | 4 | procedure.nested_call |
| CREATE PROCEDURE 不接受 OUT/INOUT 参数(目前只有 CREATE FUNCTION 支持) | 1 | 6 | procedure.inout_params |
| Lealone 不支持 DEFAULT 参数与命名实参 | 1 | 7 | procedure.named_default |
| Lealone 过程体不能为空, 也无 BEGIN ATOMIC | 1 | 3 | procedure.empty_body |
| CREATE PROCEDURE 不接受 OUT/INOUT/VARIADIC 参数(目前只有 CREATE FUNCTION 支持) | 1 | 12 | procedure.out_params |
| Lealone 不支持 ALTER PROCEDURE/FUNCTION, 过程也不接受 WINDOW/STRICT/VARIADIC/DEFAULT | 1 | 8 | procedure.alter_and_bad_def |
| Lealone 无过程级 EXECUTE 权限与 SET ROLE 语义 | 1 | 10 | procedure.privileges |
| Lealone 不支持 ALTER ROUTINE/DROP ROUTINE | 1 | 5 | procedure.routine_syntax |
| Lealone 无 CREATE TRIGGER 与触发器函数(RETURNS trigger) | 2 | 4 | plpgsql.trigger_room, plpgsql.trigger_raise |
| Lealone 的 PL/pgSQL 没有 FOUND 魔法变量(42122 Column FOUND not found) | 1 | 4 | plpgsql.found |
| PL/pgSQL × SETOF/TABLE 在 DDL 阶段报 50100 | 1 | 6 | plpgsql.for_loop_setof |
| Lealone 无 anyelement/anyarray 多态参数类型 | 1 | 1 | plpgsql.polymorphic |
| 缺陷: 有 OUT/INOUT 参数时裸 RETURN; 报 50000 RETURN; cannot be used in a function returning a value | 1 | 3 | plpgsql.out_assign |
| 多个 OUT 参数的 PL/pgSQL 函数在 DDL 阶段报 50100 | 1 | 3 | plpgsql.multi_out |
| 这段依赖 FOUND 魔法变量(PERFORM 之后检查 FOUND) | 1 | 5 | plpgsql.perform |
| PL/pgSQL 游标(OPEN/FETCH/CLOSE)与 refcursor 类型不支持, DDL 阶段报 50100 | 1 | 6 | plpgsql.cursor |
| 缺陷: DECLARE x record / %ROWTYPE 能建函数, 调用时报 42001(内部 CAST(NULL AS RECORD) 语法错) | 1 | 2 | plpgsql.record |
| leaker_2 用 OUT 参数 + RETURNS RECORD(RECORD 不支持), leaker_1 还有复合字段访问 | 1 | 6 | plpgsql.out_params_exception |
| Lealone 无 SETOF <表行类型>, 也不支持 $1 位置参数 | 1 | 5 | rangefuncs.setof_composite |
| Lealone 不支持 WITH ORDINALITY, 也没有 unnest()/generate_series() | 1 | 8 | rangefuncs.with_ordinality |
| Lealone 函数体只允许单条查询, 不支持 INSERT ... RETURNING | 1 | 8 | rangefuncs.insert_returning |

## 六、这一轮刷新：解锁了什么

合并 `feature/stored-routines` 之后，清单里"不支持"的能力落地了一批，对应清单行删掉、用例开始真正执行：

| 用例 | 之前 | 现在 | 靠什么能力 |
| --- | --- | --- | --- |
| `function_sql.void_return` | skip（缺陷：VOID 返回序列化字节） | 执行 2 条语句 | `RETURNS VOID` 修好了 |
| `function_sql.strict`（原用例拆成 `strict` + `strict_catalog`） | skip | 执行 9 条语句 | `STRICT` / `RETURNS NULL ON NULL INPUT` |
| `plpgsql.recursion` | skip（无 PL/pgSQL） | 执行 2 条语句 | `LANGUAGE plpgsql` + `IF` + 递归调用 |
| `plpgsql.exception_reraise` | skip（无 PL/pgSQL） | 执行 2 条语句 | 嵌套 `EXCEPTION WHEN OTHERS` + `RAISE;` |

新加的用例（都是 PG 原文的段落，按新能力挑出来的）：

| 新用例 | PG 出处 | 覆盖的能力 |
| --- | --- | --- |
| `plpgsql.out_return` | plpgsql.sql:1683-1686 | 单个 `OUT` 参数 + `RETURN 表达式` |
| `plpgsql.inout_return` | plpgsql.sql:1697-1703 | `INOUT` 参数 |
| `plpgsql.select_into` | plpgsql.sql:1819-1832 | `SELECT INTO` + `IF` + `STABLE` |
| `plpgsql.select_into_insert` | plpgsql.sql:1834-1853 | 函数内 DML + 嵌套函数调用 |
| `plpgsql.raise_notice` | plpgsql.sql:2085-2106 | `RAISE NOTICE`（含占位符个数不对的报错） |
| `rangefuncs.setof_from` / `setof_from_call` | rangefuncs.sql:85-103 | `RETURNS SETOF int` + `FROM f(...)` 表函数 |
| `rangefuncs.setof_text_view` | rangefuncs.sql:113-118 | `RETURNS SETOF text` + 视图里用表函数 |
| `rangefuncs.table_function` | rangefuncs.sql:435-438 | `RETURNS TABLE(a int)` |
| `rangefuncs.table_function_typmod` | rangefuncs.sql:449-454 | `RETURNS TABLE(a varchar(5))` 列类型 |
| `rangefuncs.table_function_join` | rangefuncs.sql:66-80 | 表函数列别名列表 + JOIN |

按特性分组计数（新解锁的脚本语句）：

| 特性 | 新增脚本语句 |
| --- | --- |
| `RETURNS SETOF` / `RETURNS TABLE` + 表函数 | 11 |
| `OUT` / `INOUT` 参数 | 4 |
| `STRICT` / `RETURNS NULL ON NULL INPUT` | 9 |
| `RETURNS VOID` | 2 |
| `LANGUAGE plpgsql`（递归/RAISE/嵌套 EXCEPTION/SELECT INTO/DECLARE/IF） | 13 |
| 合计 | 39（脚本语句从 47 → 101，用例从 67 → 89，执行用例 14 → 29） |

## 七、剩余缺陷（都已实测复现）

**这一轮新发现的：**

### N1. `RETURNS SETOF VOID` 每一行都返回 Java 序列化字节

```sql
create function sv2() returns setof void as 'select 1';
select * from sv2();
```

实测每行是 `aced0005737200116a6176612e6c616e672e496e7465676572...`（序列化后的 Integer 1）。
PG 里 `setof void` 每行是空值。标量 `RETURNS VOID` 已经修好，这条没有跟着修。
用例 `function_sql.setof_void`。

### N2. 有 `OUT`/`INOUT` 参数的 PL/pgSQL 函数不能裸 `RETURN;`，也不能自然结束

```sql
create function functest_out2(i int, out j int) as
$$ begin j := i + 1; return; end $$ language plpgsql;   -- 50000 RETURN; cannot be used in a function returning a value
create function functest_inout(inout i int) as
$$ begin i := i+1; end $$ language plpgsql;             -- 50000 control reached end of function without RETURN
```

PG 里这两种写法都合法（有 OUT 参数时 `RETURN;` 和落到函数结尾都行，返回列就是 OUT 参数的值）。
Lealone 必须显式 `RETURN <值>`。用例 `plpgsql.out_assign`、`plpgsql.inout_return`
（后者已按 Lealone 的要求改成显式 RETURN 才跑通）。

### N3. 没有 `FOUND` 魔法变量

```sql
create function tf() returns boolean as
$$ begin insert into ftt values (1); if FOUND then return true; end if; return false; end $$ language plpgsql;
select tf();   -- 42122 Column "FOUND" not found
```

PG 里 `FOUND` 是 PL/pgSQL 的内建变量（`INSERT/UPDATE/DELETE`、`PERFORM`、`SELECT INTO` 之后都能查）。
用例 `plpgsql.found`、`plpgsql.perform`。

### N4. `DECLARE x record` / 表的 `%ROWTYPE` 能建函数，调用时报内部 SQL 语法错

```sql
create function rowtype1() returns varchar as
$$ DECLARE r t%ROWTYPE; BEGIN SELECT * INTO r FROM t WHERE id = 1; RETURN r.name; END; $$ language plpgsql;
select rowtype1();   -- 42001 Syntax error: SELECT CAST(NULL AS T%ROWTYPE) ...
```

DDL 阶段不报错，运行时解释器拿声明类型去拼 `CAST(NULL AS ...)`，`T%ROWTYPE`/`RECORD` 不是合法类型名。
用例 `plpgsql.record`、`plpgsql.out_params_exception`。

### N5. PL/pgSQL 的 `SETOF/TABLE` 与多个 `OUT` 参数在 DDL 阶段报 50100

```sql
create function f(in i int, out j int, out k text) as ... language plpgsql;
-- 50100 Feature not supported: "SETOF/TABLE, multiple OUT or array parameters in PL/pgSQL function"
```

SQL 语言的函数已经支持 SETOF/TABLE、多个 OUT，但 PL/pgSQL 还没有。
用例 `plpgsql.for_loop_setof`、`plpgsql.multi_out`。

### N6. 块标签的限定名取不到

```sql
create function pl_qual_names(param1 int) returns void as
$$ <<outerblock>> DECLARE param1 int := 1; BEGIN RAISE NOTICE '%', outerblock.param1; END; $$ language plpgsql;
-- 42122 Column "OUTERBLOCK.PARAM1" not found
```

标签本身能解析（带标签的 `CONTINUE outer` 可以工作），但 `标签.变量` 这种限定引用不行。
（这一条还没单独登记用例，见第十一节。）

**上一轮就存在、这一轮复测仍存在的：**

### N7. `CALL` 有结果集时 `Statement.execute()` 拿不到行

`create procedure ptest2() as 'select 5'; call ptest2();` → `execute()` 返回 false、
`updateCount=0`、`getResultSet()=null`（`executeQuery("CALL ptest2()")` 能拿到一行 5）。
用例 `procedure.body_select`、`procedure.collation`。

### N8. 过程体里嵌套 `CALL` 参数绑定冲突

`create procedure ptest3(y text) as 'call ptest1(?); call ptest1(?)'; call ptest3('b');`
→ `90012 Parameter "#1" is not set`。用例 `procedure.nested_call`。

### N9. 函数体返回多列时静默取第一列

`create function f_multi(a int) returns int as 'select 1, 2, 3'; select f_multi(1);` → 1；
PG 报 42804。用例 `function_sql.bad_body_columns`。

### N10. 建函数时不校验函数体

`create function f_bad(a int) returns int as 'select ''not an integer'''` 建函数成功，
第一次调用才报 22018；PG 建函数即报 42804。用例 `function_sql.bad_body_return_type`。

### N11. 函数体是 `WITH ... SELECT` 时参数绑定错乱

`create function f_with(a int) returns int as 'WITH x AS (SELECT a * 2 AS v) SELECT v FROM x'; select f_with(5);`
→ `90012 Parameter "#1" is not set`。PG 返回 10。（PG 这两个文件里没有 WITH 函数体的用例，
所以还没登记成用例。）

### N12. 内存库（`persistent=false`）下返回 VARCHAR 的函数一调用就 NPE

`LobStorage.createClob ... DataHandler.getLobStorage() is null`（50000），持久化库正常。
本套件因此改用持久化库（`target/test-data/pgregress`）。

**其它已知差异**（不算缺陷，但影响对齐）：参数名与列名同名时参数优先（PG 列优先）；
`CALL` 不区分函数与过程；`DROP FUNCTION`/`DROP PROCEDURE` 的错误码不统一（90077/90151 vs PG 42883）；
`RAISE <条件名>`（如 `RAISE syntax_error`）不支持，只能写 `RAISE EXCEPTION`/`RAISE SQLSTATE`。

## 八、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0.sql` / `create_function_1.sql` / `create_function_2.sql`
   在本地这棵 PG 源码树（20devel）里不存在**。`ls src/test/regress/sql | grep -iE 'func|proc'`
   能看到的函数相关文件是 `create_function_c.sql`（C 语言函数）、`create_function_sql.sql`、
   `rangefuncs.sql`、`regproc.sql`、`plpgsql.sql`、`create_procedure.sql` 等。
   所以函数部分的主体用 `create_function_sql.sql`，setof 用 `rangefuncs.sql`，过程用
   `create_procedure.sql`。这不是"我们挑着做"，是素材没有。
2. **`plpgsql.sql`（4756 行 / 935 条语句）只抽样转换**：这一轮从 6 个代表性用例扩到 18 个用例
   （执行 7 个、skip 11 个），覆盖递归、`RAISE`、嵌套 `EXCEPTION`、`SELECT INTO`、`OUT`/`INOUT`、
   函数内 DML；触发器、FOUND、游标、多态参数、`RECORD`、`RETURN QUERY`、`EXECUTE`、`GET DIAGNOSTICS`、
   `CASE` 语句、`ASSERT` 的段落仍然 skip 或没抽样。**剩下几百条语句没有逐条登记**，
   这是本套件目前最大的透明性缺口。
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
5. **没有 FOUND**：依赖 FOUND 的用例 skip；能改编的（如 `SELECT INTO` 后判断变量）在脚本里注明改编。
6. **RAISE 条件名**：`RAISE syntax_error` 改成 `RAISE EXCEPTION '...'`，其余语义不变。
7. **generate_series 缺失**：`RETURNS TABLE` 的用例里用 `SYSTEM_RANGE` 替代，并注明。
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
   * N6 块标签限定名 → `plpgsql.qualified_names`（PG `plpgsql.sql:2841-2858`）；
   * N11 `WITH` 函数体 → 单独一个用例文件（PG 这两个文件里没有对应段落）；
   * N10 建函数时校验函数体 → 需要 PG `check_function_bodies` 的对照段落。
3. **正在被实现的几格**（谁做完谁就能清空对应清单行）：
   * 触发器 → `plpgsql.trigger_*` 2 条；
   * FOUND → `plpgsql.found`、`plpgsql.perform` 2 条；
   * PL/pgSQL 的 SETOF/TABLE 与多 OUT → `plpgsql.for_loop_setof`、`plpgsql.multi_out` 2 条；
   * RECORD/%ROWTYPE → `plpgsql.record`、`plpgsql.out_params_exception` 2 条；
   * 过程级 OUT/INOUT → `procedure.inout_params`、`procedure.out_params` 2 条；
   * 游标 → `plpgsql.cursor` 1 条。
4. **补齐 PG 覆盖面**：`plpgsql.sql` 按段登记（尤其是游标、FOUND、RAISE 变体）；
   `rangefuncs.sql` 里 `WITH ORDINALITY` 落地后再补；`portals.sql` 如果以后支持游标再纳入。
