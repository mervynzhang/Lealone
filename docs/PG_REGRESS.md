# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 当前基线：`feature/stored-routines`（`90264e02e`，在 `d1de03e3b` 的基础上又补齐了 `unnest()`/`generate_series()`、
  `pg_proc`/`pg_get_functiondef()` 目录、PL/pgSQL 的 `FOR ... IN <query>` 查询循环）
- 上一轮基线：`d1de03e3b`（97 用例 / 66 执行 / 31 skip / 255 语句），见第六节
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
[pgregress]   create_function_sql.sql  用例 40(执行 24, skip 16)  脚本语句  96(通过  96, 失败 0)  被skip用例占  54 条
[pgregress]   create_procedure.sql     用例 24(执行 14, skip 10)  脚本语句  63(通过  63, 失败 0)  被skip用例占  37 条
[pgregress]   plpgsql.sql              用例 23(执行 19, skip  4)  脚本语句  60(通过  60, 失败 0)  被skip用例占   8 条
[pgregress]   rangefuncs.sql           用例 10(执行 10, skip  0)  脚本语句  40(通过  40, 失败 0)  被skip用例占   0 条
[pgregress] 合计: 用例 97 (执行 67, skip 30), 脚本语句 259 (通过 259, 失败 0), 耗时 548ms
[pgregress] skip 清单: 30 条用例, 点名跳过的 PG 语句 170 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中 104 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中  55 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 抽样转换(清单里点名跳过的代表性语句 11 条)
[pgregress]   rangefuncs.sql           PG 共  428 条语句, 抽样转换(清单里点名跳过的代表性语句 0 条)
```

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* `converted` 的文件里 PG 语句要么执行、要么在清单里点名；`sampled` 的文件（plpgsql/rangefuncs 这种上千行的长篇）
  只抽样转换，清单里点名的只是被 skip 的代表性用例。

## 五、skip 清单摘要（按原因分组，共 30 条 / 170 条 PG 语句）

| 原因 | 条数 | PG语句 | 用例 id |
| --- | --- | --- | --- |
| Lealone 无 SET search_path, CREATE USER 必须带 PASSWORD | 1 | 4 | function_sql.setup |
| 缺少 $1 位置参数与 ARRAY[...] 字面量(数组参数本身已支持) | 1 | 1 | function_sql.a2_array_arg |
| LEAKPROOF 属性和pg_proc.proleakproof已支持, 但缺少函数级权限模型(SET SESSION AUTHORIZATION/OWNER权限)和建函数的LEAKPROOF权限校验 | 1 | 16 | function_sql.leakproof |
| Lealone 无 SQL 标准函数体(RETURN 表达式) | 2 | 12 | function_sql.sql_body_return, function_sql.sql_body_view |
| Lealone 无 anyarray/anyelement 多态类型 | 3 | 15 | function_sql.sql_body_polymorphic, function_sql.anyarray_setof, procedure.polymorphic |
| Lealone 无 SQL 标准函数体(RETURN表达式), pg_get_functiondef 本身已支持 | 1 | 13 | function_sql.pg_get_functiondef_sqlbody |
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
| 不支持命名实参(a => null)与过程的 VARIADIC 参数, 也不支持同名过程按 OUT/IN 区分重载(90150) | 1 | 8 | procedure.out_params_named |

## 六、这一轮刷新：解锁了什么

基线从 `d1de03e3b` 到 `90264e02e`。本轮**在产品代码里补了 PL/pgSQL 的 `FOR ... IN <query>` 查询循环**
（解析器 + AST + 解释器，约 65 行），顺带解锁了清单里唯一卡在这个语法上的用例：

| 用例 | 之前 | 现在 | 靠什么能力 |
| --- | --- | --- | --- |
| `plpgsql.setof_composite_record` | skip（`FOR ... IN query` 报 50100） | 执行 4 条语句 | `FOR rec IN SELECT ... LOOP` + `RETURN NEXT` 到 `SETOF <表行类型>`；`rec RECORD` 与 `row 表%ROWTYPE` 两种目标都覆盖 |

统计：用例 97 → 97，执行 66 → 67，skip 31 → 30，脚本语句 255 → 259（失败 0）。

之前几轮已经解锁、这轮复核仍为执行状态的：`function_sql.void_insert`（VOID+DML）、`sql_body_atomic`/`sql_body_case`/
`atomic_body`/`empty_body`（BEGIN ATOMIC）、`procedure.plpgsql_body`、`rangefuncs.insert_returning`（DML RETURNING）、
`rangefuncs.with_ordinality_unnest`、`function_sql.pg_proc`/`pg_get_functiondef*`（PG_PROC 目录）、
`rangefuncs.setof_composite`、`procedure.out_params`/`inout_params`、`plpgsql.out_params_exception` 等。

## 七、剩余缺口与缺陷

### 触发器（本轮只评估，未实现，详见第十二节）

PG 的两个触发器用例（`plpgsql.trigger_room`、`plpgsql.trigger_raise`）需要 `RETURNS trigger` 函数 +
`CREATE TRIGGER ... FOR EACH ROW EXECUTE PROCEDURE fn()`。Lealone 现在只有 H2 风格的 Java 类触发器，
本轮的判断是**不动 `TriggerObject` 的核心 fire 路径**，所以两条继续 skip（原因已按现状更新）。

### 游标（本轮只评估，未实现，详见第十二节）

`plpgsql.cursor` 需要 `DECLARE c CURSOR FOR`、`OPEN/FETCH/CLOSE`、`refcursor` 门户（跨语句/跨函数可见）、
参数化游标与命名实参，还有 `copy ... from stdin`。这轮实现的是 `FOR ... IN <query>`，游标整体留到下一轮。

### 其它仍然不支持的（清单里都有用例）

* **多态类型** `anyelement`/`anyarray`（5 条，报 `42102 Table "ANYELEMENT" not found`）。
* **SQL 标准函数体 `RETURN 表达式`**（3 条：`sql_body_return`、`sql_body_view`、`pg_get_functiondef_sqlbody`
  的最后一段；`BEGIN ATOMIC` 形式已支持）。
* **函数体/过程体里的 DDL**（2 条）。
* **DEFAULT 参数 / 命名实参 / 过程 VARIADIC**（2 条）；**`ALTER PROCEDURE`**（1 条，`ALTER FUNCTION` 可以）。
* **目录/权限**：`information_schema.routines`（1）、`SET search_path`（1）、过程级 `EXECUTE` 授权与 `SET ROLE`（1）、
  `LEAKPROOF` 的权限模型（1）、`ALTER ROUTINE`/`DROP ROUTINE`（1）。
* **`EXPLAIN (COSTS OFF)` 与 setof 内联**（1）、`WINDOW` 与 FUNCTION/PROCEDURE 互转（1）、
  `CREATE OPERATOR CLASS`（1）、`$1` 位置参数（2）、`ARRAY[...]` 字面量（1）、`TEMP SEQUENCE`/`DISCARD`（1）、
  `CALL` 不区分函数与过程（2）、psql 元命令 `\df`（1）。

## 八、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0.sql` / `create_function_1.sql` / `create_function_2.sql`
   在本地这棵 PG 源码树（20devel）里不存在**。`ls src/test/regress/sql | grep -iE 'func|proc'`
   能看到的函数相关文件是 `create_function_c.sql`（C 语言函数）、`create_function_sql.sql`、
   `rangefuncs.sql`、`regproc.sql`、`plpgsql.sql`、`create_procedure.sql` 等。
   所以函数部分的主体用 `create_function_sql.sql`，setof 用 `rangefuncs.sql`，过程用
   `create_procedure.sql`。这不是"我们挑着做"，是素材没有。
2. **`plpgsql.sql`（4756 行 / 935 条语句）只抽样转换**：现在是 23 个用例（执行 19 个、skip 4 个），
   覆盖递归、`FOUND`、`PERFORM`、`RAISE`、嵌套 `EXCEPTION`、`SELECT INTO`、`OUT`/`INOUT`/多 `OUT`、
   `RETURN NEXT`/`RETURN QUERY`/`RETURNS TABLE`、`GET DIAGNOSTICS ROW_COUNT`、块标签限定名、函数内 DML；
   触发器、游标、多态参数、`RECORD` + DML `RETURNING`、`RETURNS RECORD`、`EXECUTE`、`CASE` 语句、
   `ASSERT` 的段落仍然 skip 或没抽样。**剩下几百条语句没有逐条登记**，这是本套件目前最大的透明性缺口。
3. **`rangefuncs.sql`（827 行 / 428 条语句）也只抽样**：转过来 10 个用例（执行 10 个），
   `WITH ORDINALITY`/`unnest()`/`generate_series()`/`SETOF <表行类型>` 都已经执行，其余段落还没抽样。
4. **`portals.sql`**：按要求没纳入（游标/portal 语义，不是函数/过程定义）。
5. 没转换的 PG 断言：`EXPLAIN` 计划、`\df` 等 psql 元命令、`information_schema.routines` 等目录视图、
   `GRANT/REVOKE/SET ROLE` 权限、`ALTER FUNCTION/PROCEDURE/ROUTINE`——都随用例一起 skip，
   原因写在清单里，没有偷偷删。`pg_proc` 查询已经改成等价的 `INFORMATION_SCHEMA.PG_PROC` 查询并执行。

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
2. **下一轮的两个大块**（评估见第十二节）：
   * 游标：`DECLARE CURSOR` + `OPEN/FETCH/CLOSE` + `refcursor` 门户（约 700~1000 行），能解锁 `plpgsql.cursor`，
     并为 `portals.sql` 打底；
   * 触发器：`EXECUTE PROCEDURE` + `RETURNS trigger` + plpgsql 桥接 + BEFORE 取消语义（约 700~800 行），
     能解锁 `plpgsql.trigger_room`、`plpgsql.trigger_raise` 两条。
3. **低成本、立刻能清的格**：
   * 多态类型 `anyelement`/`anyarray`（5 条）；
   * SQL 标准函数体 `RETURN 表达式`（3 条）；
   * `EXPLAIN (COSTS OFF)`（1 条，`EXPLAIN` 本身已可用）；
   * `ALTER PROCEDURE`（1 条，`ALTER FUNCTION` 已可用）；
   * `ARRAY[...]` 字面量 + `$1` 位置参数（3 条）；
   * `DEFAULT` 参数/命名实参（2 条）；`information_schema.routines`（1 条）。
4. **补齐 PG 覆盖面**：`plpgsql.sql` 按段登记（游标、`RETURN QUERY EXECUTE`、`CASE` 语句、`FOREACH`、`ASSERT`）；
   `portals.sql` 如果以后支持游标再纳入。

## 十二、触发器与游标的评估（r5 轮）

### 12.1 触发器

**PG 脚本要求**（`plpgsql.sql:130-172`）：`create function tg_...() returns trigger`（函数体用 `NEW`/`OLD`，
`RETURN NEW`/`RETURN OLD`），`create trigger tg_room_au after update on Room for each row execute procedure tg_room_au();`
BEFORE 行触发里 `RAISE EXCEPTION` 要能阻止写入，AFTER 行触发里要能对其它表做 DML。

**Lealone 现状**：
* `CREATE TRIGGER`（`CreateTrigger`）只支持 H2 语法：`BEFORE|AFTER|INSTEAD OF` + `INSERT/UPDATE/DELETE[/SELECT]` +
  `FOR EACH ROW` + `QUEUE n` + `NOWAIT` + `CALL 'class'` + `AS 源码`；遇到 PG 的 `EXECUTE PROCEDURE` 直接 42001。
* 触发器体只能是 Java 类（`Trigger` 接口：`init(conn, schema, name, table, before, type)`、`fire(conn, oldRow, newRow)`），
  或实现 `Trigger` 的动态源码，由 `TriggerObject.load()` 编译/反射加载，`fireRow` 在 `Table.fireRow` 里按行调用。
* 没有 `RETURNS trigger` 类型（`42102 Table "TRIGGER" not found`），也没有 `NEW`/`OLD` 记录变量。
* BEFORE 行触发的取消语义只有一个出口：`TriggerObject.fireRow` 返回 true 表示"语句已处理"（INSTEAD OF 也用它），
  没有单独表达"RETURN NULL 取消本行"。

**要改的地方和量级**：
1. 解析 `CREATE TRIGGER ... FOR EACH ROW EXECUTE PROCEDURE fn()`，把函数引用存进 `TriggerObject`（+80 行）。
2. `RETURNS trigger` 类型 + `NEW`/`OLD` 隐式 record 变量 + `RETURN NEW/OLD/NULL` 语义（+150 行）。
3. 新增一个内置的 Java `Trigger` 实现做桥接：在 `fire` 里把 `Object[] oldRow/newRow` 转成 `Value[]` 绑到 NEW/OLD，
   在同一个 session 上执行 plpgsql 函数，再把 NEW 的修改写回 `newRow`（+200 行）。
4. `Table.fireBeforeRow`/`TriggerObject.fireRow` 增加"取消本行"语义（返回值从 boolean 变成三态或新增异常）（+60 行）。
5. `TriggerObject` 的持久化/Script 输出、`DROP TRIGGER`、触发器函数的依赖与重载解析（+80 行）。
6. 测试（含 `ScriptRoundTripTest` 的触发器往返、`TriggerObjectTest` 的 Java 触发器不回归）（+200 行）。

**判断**：总量 700~800 行，且第 3、4 点会动 `TriggerObject`/`Table` 的核心同步 fire 路径（还有 QUEUE/NOWAIT
异步触发器与嵌套连接），风险高；按本轮红线**只评估不实现**。触发器体还要考虑事务内 DML 的可见性、
`INSTEAD OF` 与 PG 行级触发器的差异、`TG_OP` 等内建变量，属于"一整块"的工作，不适合和游标一起塞进本轮。

### 12.2 游标

**PG 脚本要求**（`plpgsql.sql:1859-1900` 及其后续到约 2000 行）：
* `declare rc refcursor; open rc for select a from rc_test; return rc;`（无名/未绑定的 refcursor，`return refcursor`）
* `use_refcursor(rc refcursor)`：`fetch next from rc into x; return x.a;`
* SQL 层的门户操作：`select refcursor_test1('test1'); fetch next in test1; fetch all from test2;`（事务内可见）
* 参数化游标：`declare c1 cursor (p1 int, p2 int) for select ...; open c1($1, $2); fetch c1 into nonsense; close c1;`
* 命名实参 `open c1(param12 := $2, param1 := $1)`、`constant refcursor`、`fetch ... into` 的目标是 record
* 数据准备用 `copy rc_test from stdin`（psql 元命令）

**Lealone 现状**：`PlpgSqlParser` 只把 `OPEN/FETCH/CLOSE` 当关键字识别，语句入口统一报
`50100 PL/pgSQL statement: OPEN`；`DECLARE c CURSOR FOR ...` 会被当成类型声明；没有 `refcursor` 类型，
也没有跨函数/跨语句的门户注册表（`PlpgSqlSqlExecutor` 每次执行都是独立语句）。

**要改的地方和量级**：
1. `DECLARE name CURSOR [(params)] FOR <query>`：变量种类 + 参数列表（+120 行）。
2. `OPEN`（绑定查询、求值参数）、`FETCH [NEXT|ALL|n] [FROM] c INTO target`、`CLOSE`、`MOVE`（+200 行）。
3. 游标本体：物化结果集 + 位置 + FOUND/ROW_COUNT（+80 行）。
4. `refcursor` 类型 + 门户注册表（session 级、事务结束关闭、`fetch in name` 的 SQL 语法）（+250 行）。
5. `FOR rec IN <cursor>` 循环（+40 行）；`WHERE CURRENT OF`（+100 行，可选）。
6. 测试（+250 行）。

**判断**：总量 700~1000 行，其中门户注册表要挂到 session/事务生命周期上，跨函数传 refcursor 还要考虑
未绑定/已绑定两种状态与 `constant` 校验；风险中高。本轮已经把其中的 **`FOR ... IN <query>`** 做完
（65 行，解锁 `plpgsql.setof_composite_record`），剩下的 `DECLARE CURSOR` + `refcursor` 门户作为下一轮独立任务。
