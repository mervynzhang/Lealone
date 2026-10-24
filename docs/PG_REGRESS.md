# PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

把 PostgreSQL 官方回归测试里和 `CREATE FUNCTION` / `CREATE PROCEDURE` 相关的部分，转成 Lealone
能跑的脚本测试，并**把跑不了的用例连同原因一起记成清单**。这份文档和
`lealone-test/src/test/resources/pg/skip-list.txt` 一起，既是测试套件的说明书，也是
"Lealone 离 PG 还有多远"的度量尺。

- 当前基线：`feature/stored-routines`（`2686a214b`）
- 实测统计：**98 用例（执行 93 / skip 5）、406 条脚本语句全部通过、773ms**
- 对照的 PG 源码：本地 `postgres` 20devel 的 `src/test/regress/sql/`
- 只加测试代码和资源；产品侧的已知差异记在第七、十一节

## 一、为什么做这件事

Lealone 的 PG 风格存储函数/过程是新增能力。PG 的回归测试是别人写好的、十几年积累的边界用例，
拿来当尺子能量出两类东西：

1. **回归**：已经能跑的语义（函数返回、setof 表函数、游标、触发器、CALL 的写入可见、DROP 语义…）
   一旦被改坏，这里会红。
2. **差距**：跑不了的用例按"缺什么能力"归类计数，谁在做哪一块，直接看清单就知道什么时候能清零。

## 二、目录与文件

```
lealone-test/src/test/java/com/lealone/test/pg/
    PgRegressTest.java      JUnit 入口：加载清单、执行、打印统计、有失败就 fail
    PgRegressRunner.java    执行引擎：H2 风格脚本解析 + 断言 + 每个文件一个库
    PgSkipList.java         skip 清单解析、清单与脚本对账、PG 语句计数
lealone-test/src/test/resources/pg/
    README.md               脚本格式、清单格式、怎么打开被 skip 的用例
    skip-list.txt           skip/known-fail 清单（5 条）
    create_function_sql.sql 由 PG create_function_sql.sql 转换（40 个用例）
    create_procedure.sql    由 PG create_procedure.sql 转换（25 个用例）
    plpgsql.sql             由 PG plpgsql.sql 抽样转换（23 个用例）
    rangefuncs.sql          由 PG rangefuncs.sql 抽样转换（10 个用例）
docs/PG_REGRESS.md          本文档
```

脚本格式复用仓库已有的 H2 风格脚本测试（语句 + `> ` 期望输出 + `> exception <code>`），
只加了 `-- @case`（用例 id，清单的键）和 `-- @pg`（PG 出处）两个标记。

## 三、怎么跑

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21

# 单独跑（约 0.8 秒，不需要 PG 源码）
rm -rf lealone-test/target/test-data
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-s4 \
    -Dtest=PgRegressTest -DfailIfNoTests=false

# 同时校验 skip 清单里记录的 PG 行号/语句数（维护清单时用，CI 不需要）
mvn -B -o -pl lealone-test test -Dmaven.repo.local=/tmp/m2-s4 \
    -Dtest=PgRegressTest -Dpg.srcdir=/home/jovyan/data/work/postgres/src/test/regress/sql
```

* **默认门禁**：类名是 `PgRegressTest`，`surefire` 默认就会跑；整轮实测 0.8 秒左右（远低于 60 秒门槛），
  所以既没有 `@Ignore`，也没有在 `pom.xml` 里排除。
* 它只在 `target/test-data/pgregress/` 下建库（每轮开始时先清空该目录），不和别的测试抢数据。
* `com.lealone.test.sql.ddl.*Test` 里有几个类会连 TCP 服务端（如 `AlterUserTest`、`DatabaseStatementTest`、
  `TruncateTableTest`），一起跑这个包时先按 `-Dlealone.test.port=<端口>` 起服务端；`PgRegressTest` 用嵌入式库，不需要。
* 可选系统属性：`-Dpg.dir=<脚本目录>`、`-Dpg.skip=<清单文件>`、`-Dpg.srcdir=<PG sql 目录>`。

## 四、一次完整运行的统计（实测，`-Dpg.srcdir` 校验通过）

```
[pgregress]   create_function_sql.sql  用例 40(执行 38, skip  2)  脚本语句 158(通过 158, 失败 0)  被skip用例占   5 条
[pgregress]   create_procedure.sql     用例 25(执行 22, skip  3)  脚本语句 112(通过 112, 失败 0)  被skip用例占   4 条
[pgregress]   plpgsql.sql              用例 23(执行 23, skip  0)  脚本语句  96(通过  96, 失败 0)  被skip用例占   0 条
[pgregress]   rangefuncs.sql           用例 10(执行 10, skip  0)  脚本语句  40(通过  40, 失败 0)  被skip用例占   0 条
[pgregress] 合计: 用例 98 (执行 93, skip 5), 脚本语句 406 (通过 406, 失败 0), 耗时 773ms
[pgregress] skip 清单: 5 条用例, 点名跳过的 PG 语句 20 条
[pgregress]   create_function_sql.sql  PG 共  179 条语句, 其中  16 条在 skip 清单里
[pgregress]   create_procedure.sql     PG 共  109 条语句, 其中   4 条在 skip 清单里
[pgregress]   plpgsql.sql              PG 共  935 条语句, 抽样转换(清单里点名跳过的代表性语句 0 条)
[pgregress]   rangefuncs.sql           PG 共  428 条语句, 抽样转换(清单里点名跳过的代表性语句 0 条)
```

按维度汇总：

| 维度 | 数值 |
| --- | --- |
| 用例总数 | 98（执行 93 = **94.9%**，skip 5 = 5.1%） |
| 转换后的脚本语句 | 406（全部通过） |
| 被 skip 用例覆盖的 PG 原语句 | 20 条 |
| 四个 PG 文件的 PG 语句总数 | 179 + 109 + 935 + 428 = 1651 |
| skip 原因 | 5 条用例、4 个原因（`setof_inline`、`partition_ops`、`CALL` 语义 ×2、`psql` 元命令） |

口径说明：

* **用例**：一个 `-- @case` 块，是 skip 和统计的单位；**脚本语句**：转换后脚本里真正执行的 SQL 语句。
* skip 清单的第三列是 **PG 原文件里的语句数**（不是脚本语句），用来衡量"缺口有多大"。
* `converted` 的文件（前两个）里 PG 语句要么执行、要么在清单里点名；`sampled` 的文件
  （plpgsql/rangefuncs 这种上千行的长篇）只抽样转换，清单里点名的只是被 skip 的代表性用例，
  所以"执行 + skip"远小于文件总语句数——**未转换段落没有逐条登记，这是本套件最大的透明性缺口**（见第八节）。

## 五、skip 清单（5 条，逐条给"为什么"和"什么条件下能解锁"）

清单原文（`skip-list.txt`，制表符分隔：`用例 id` / `PG 出处:行号` / `PG 语句数` / 原因）与下表**逐字一致**：

| 用例 id | PG 出处 | PG 语句 | 为什么 skip | 什么条件下能解锁 |
| --- | --- | --- | --- | --- |
| `function_sql.setof_inline` | `create_function_sql.sql:353-376` | 10 | `select列表里的setof展开已支持, 但SQL函数的SETOF体不会内联进执行计划(EXPLAIN只有函数表), 且脚本无法断言多行计划文本` | ① 实现 SQL 函数 SETOF 体内联进执行计划（`EXPLAIN` 里能看到内联后的计划）；② 同时给 runner 加"多行 EXPLAIN 文本"的断言能力（或把这 10 条语句改写成按行匹配） |
| `function_sql.partition_ops` | `create_function_sql.sql:449-461` | 6 | `Lealone 无 CREATE OPERATOR CLASS/分区操作符类` | 实现 `CREATE OPERATOR CLASS`（含 `CREATE OPERATOR`、btree/hash 操作符类注册），并让分区表能按操作符类选索引 |
| `procedure.call_function` | `create_procedure.sql:236-237` | 2 | `CALL在Lealone里也用于调用函数(JDBC CallableStatement契约{call f(...)}), 与PG的"CALL只调过程"冲突` | 需要产品侧决定语义：要么给 `CALL` 加"只调过程"的模式（例如 `CALL` 优先过程、找不到再回退函数，或在 JDBC 层把 `{call f(...)}` 转成 `SELECT f(...)`），要么接受这条永远 skip |
| `procedure.call_random` | `create_procedure.sql:2-2` | 1 | `CALL在Lealone里也用于调用函数(JDBC CallableStatement契约{call f(...)}), 与PG的"CALL只调过程"冲突` | 同上；另外 `lealone-client` 的 `JdbcConnection` 正是用 `CALL READONLY()`/`CALL SCHEMA()`/`CALL TRANSACTION_ISOLATION_LEVEL()` 实现 `isReadOnly()`/`getSchema()`/`getTransactionIsolation()`，改 `CALL` 语义要同步改这些内部调用 |
| `procedure.psql_meta` | `create_procedure.sql:14-21` | 1 | `Lealone客户端没有psql元命令(\df); pg_get_functiondef()本身已支持` | 有 psql 兼容客户端（或给 lealone-shell 加 `\df`）；用例本身只差这一条元命令，`pg_get_functiondef` 返回的 DDL 已经能断言 |

**为什么这 5 条"不值得硬编"**：

* `setof_inline` 的 10 条语句本质是**执行计划断言**（PG 用它验证 setof 函数体被内联），
  Lealone 没有同样形态的优化器，硬断言"计划文本"会把实现细节写死；等真做内联再开。
* `partition_ops` 依赖一整套操作符类基础设施，属于"分区表 + 索引"另一条线，不在函数/过程范围内。
* `call_function`/`call_random`/`psql_meta` 是**方言/客户端**差异，不是函数语义缺失；
  前两条改了会影响 JDBC 的 `CallableStatement` 契约，后一条需要 psql 兼容客户端。

## 六、已经解锁的能力（按主题）

* **函数语法**：SCALAR/SETOF/TABLE/VOID 返回、`OUT`/`INOUT`/`VARIADIC`/`STRICT`/`CALLED ON NULL INPUT`、
  无名参数与 `$1`、`DEFAULT` 参数值、命名实参、`LANGUAGE sql`（含 SQL 标准 `RETURN 表达式`、
  `BEGIN ATOMIC` 多语句体、`TABLE`/`VALUES`/`WITH` 体）、`LANGUAGE plpgsql`、`SECURITY DEFINER`、
  `LEAKPROOF`、易变性、`COST/ROWS`（只存储）、`ALTER FUNCTION` 的属性/改名。
* **函数体能力**：`SELECT`/`VALUES`/`TABLE`/`WITH`、带 `RETURNING` 的 DML、DDL（`BEGIN ATOMIC`）、
  复合类型返回（`CREATE TYPE`/`RETURNS comp`/`SETOF comp`/`(f()).col`）、`%ROWTYPE` 与数组、
  多态类型（`anyelement`/`anyarray`/`anynonarray`）、`generate_series`/`unnest`/`SYSTEM_RANGE`、
  `WITH ORDINALITY`、标量函数进 `FROM`。
* **PL/pgSQL**：`DECLARE`/`BEGIN..END`/`IF`/`ELSIF`/`LOOP`/`WHILE`/`FOR`（整数范围、
  `FOR ... IN <query>`、`FOR ... IN <cursor>`）/`RETURN`/`RETURN NEXT`/`RETURN QUERY`/`RAISE`（含
  `SQLSTATE` 与 `;` 重抛）/`EXCEPTION`（含嵌套，不含 `GET STACKED DIAGNOSTICS`）/`PERFORM`/`SELECT INTO`
  （含 `STRICT`）/`GET DIAGNOSTICS ROW_COUNT`/`FOUND`/块标签限定名/`record` 与 `%ROWTYPE`/`%ROWTYPE` 数组/
  **游标**（`DECLARE CURSOR`、参数化游标、`OPEN`/`FETCH`/`CLOSE`、`refcursor` 门户跨函数可见、
  `constant refcursor`）。**尚未支持**：`CASE` 语句、`FOREACH`、`ASSERT`、`EXECUTE` 动态 SQL、
  `RETURN QUERY EXECUTE`、`GET STACKED DIAGNOSTICS`（PG 里这些段落的用例还没抽样或直接跳过了）。
* **过程**：`IN`/`OUT`/`INOUT`/`VARIADIC`、按参数类型重载、`LANGUAGE plpgsql` 过程体、`BEGIN ATOMIC`
  过程体（含 DDL）、`CREATE OR REPLACE`、`ALTER PROCEDURE ... RENAME`、`GRANT/REVOKE EXECUTE ON PROCEDURE`
  + `SET ROLE`、`DROP PROCEDURE name(签名)`（含 `IN/OUT` 模式消歧义）。
* **触发器**：PG 语法 `CREATE TRIGGER ... FOR EACH ROW EXECUTE PROCEDURE fn()` + `RETURNS trigger` +
  `NEW`/`OLD`（`plpgsql.trigger_*` 2 个用例已执行）。
* **目录**：`INFORMATION_SCHEMA.PG_PROC`（兼容 `pg_proc` 的查询）、`PARAMETERS`、`ROUTINES`、
  `pg_get_functiondef()`、`SCRIPT` 往返（函数/过程/触发器/复合类型/授权）。

## 七、已知差异（语义层，不影响用例通过）

1. **过程体参数**：只能用 `?` 按位置引用（写参数名会当成列名）；`CREATE FUNCTION` 的函数体可以用参数名。
2. **`DROP` 的签名匹配**：按完整参数列表（只比类型，不比 `varchar(10)` 这类 typmod），
   类型列表里不能写参数名（写 `IN INT` 可以）。
3. **`SECURITY DEFINER`**：只记录属性并让函数体用属主身份执行，没有函数级 `EXECUTE` 权限模型；
   属主被删后调用报 `USER_NOT_FOUND`。
4. **`SET ROLE`** 只影响函数/过程的 `EXECUTE` 检查，不影响表/序列/服务权限。
5. **授权兼容规则**：对象从未被 GRANT/REVOKE 过时人人可调用（PG 是建对象即自动 `GRANT EXECUTE TO PUBLIC`）；
   一旦有授权记录就严格按 PG 语义检查。
6. **`CALL` 语义**：`CALL` 既能调过程也能调函数（JDBC `CallableStatement` 契约），
   所以 PG 的两条"CALL 函数应报错"用例在 skip 清单里。
7. **`refcursor`** 是 `VARCHAR` 的别名，`SCRIPT`/元数据里会显示成 `VARCHAR`；SQL 层
   `FETCH ... FROM <name>`/`CLOSE <name>` 还没有（门户在 PL/pgSQL 内跨函数可用）。
8. **`EXPLAIN`** 支持 `(COSTS OFF)`，但不做 setof 函数体内联（计划里只有函数表）。
9. **布尔显示**：`TRUE`/`FALSE`（PG 的 psql 显示 `t`/`f`）；错误码按 Lealone 的编号断言，
   脚本注释里同时写明 PG 的错误码。
10. **触发器**是 PG 语法 + PL/pgSQL 桥接，`TG_*` 等内建变量只实现了用例覆盖到的部分。

## 八、PG 原文件里到底跳过了什么（如实交代）

1. **`function.sql` / `create_function_0/1/2.sql` 在本地这棵 PG 源码树（20devel）里不存在**。
   函数部分的主体用 `create_function_sql.sql`，setof 用 `rangefuncs.sql`，过程用 `create_procedure.sql`。
   这不是"挑着做"，是素材没有。
2. **`plpgsql.sql`（4756 行 / 935 条语句）**：现在 23 个用例**全部执行**（skip 0），覆盖递归、`FOUND`、
   `PERFORM`、`RAISE`、嵌套 `EXCEPTION`、`SELECT INTO`、`OUT`/`INOUT`/多 `OUT`、`RETURN NEXT`/
   `RETURN QUERY`/`RETURNS TABLE`、`GET DIAGNOSTICS`、块标签、函数内 DML、游标、触发器；
   **没转换的段落**：`CASE` 语句的边界组合、`FOREACH`、`RETURN QUERY EXECUTE`、`GET STACKED DIAGNOSTICS`
   的一部分、以及大量 patchfield 场景的触发器/游标组合。**剩下几百条语句没有逐条登记**。
3. **`rangefuncs.sql`（827 行 / 428 条语句）**：10 个用例全部执行（skip 0），
   `WITH ORDINALITY`、`unnest()`、`generate_series()`、`SETOF <表行类型>` 都覆盖到了；
   其余大段（`ROWS FROM`、多函数合并、LATERAL 相关）没抽样。
4. **`portals.sql`**：没纳入（游标/portal 的 SQL 层语义；PL/pgSQL 游标已在 `plpgsql.cursor` 覆盖）。
5. 没转换的 PG 断言：`\df` 等 psql 元命令、`information_schema.routine_sequence_usage/
   routine_column_usage/routine_table_usage` 的依赖行、`EXPLAIN` 计划文本比对——都写明在清单或本节，
   没有偷偷删。

## 九、转换/适配规则

改的是语法不是语义，脚本里逐条注明：

1. **参数命名**：PG 允许无名参数和 `$1/$2`，Lealone 现在也支持；转换版保留命名参数只为可读。
2. **PL/pgSQL 函数体必须写在一行**：脚本解析器按"行末是 `;`"切语句，多行块体会被切断。
   写成 `AS $$ DECLARE ...; BEGIN ...; END; $$;` 一行即可（见 `pg/README.md`）。
3. **布尔/空值显示**：PG `t/f`，Lealone `TRUE/FALSE`；PG 的一行空值在这里是 `null`。
4. **过程体参数**用 `?` 按位置引用；`BEGIN ATOMIC` 过程体也一样。
5. **`FOUND`** 已支持，`plpgsql.found`/`perform`/`select_into` 都用 PG 原样的 `IF FOUND`。
6. **`RAISE` 条件名**：`RAISE syntax_error` 改成 `RAISE EXCEPTION '...'`，其余语义不变。
7. **`generate_series` 的用例**里用 `SYSTEM_RANGE` 替代（列名 `X`）并注明；
   `unnest`/`WITH ORDINALITY` 用 PG 原样写法。
8. **错误码不同但都是拒绝**的用例，断言 Lealone 的错误码，并在注释里写明 PG 的错误码
   （42601/42804/42809/42883/42P13 ↔ 50100/42000/50000/90022/90077/90151）。
9. **过程体里带 OUT/INOUT 参数**：过程体最后一条语句必须是查询（列数和 OUT 参数对得上），
   参数统一用 `?` 引用；带 OUT/INOUT 的过程体里不能再嵌套 `CALL`。
10. **共享表要隔离**：一个 `.sql` 文件共用一个库，PG 里多个用例共用的表（如 `cp_test`）如果被新用例插了行，
    会打乱后面用例的期望值，所以新用例改用独立表（如 `cp_test_out`）并在注释里说明。

## 十、口径与限制

* 一个 `.sql` 文件共用一个库，用例按文件顺序执行（和 PG 一致），所以用例之间可以有依赖；
  改脚本时注意函数名不要撞（Lealone 函数名大小写不敏感）。
* 执行引擎用 `Statement.execute()`：过程体是查询时 `getResultSet()` 能拿到行，因此"CALL 返回结果集"可以断言。
* `skip-list.txt` 的第三列是 PG 语句数，由 `PgSkipList.countStatements` 统计（能处理 `--` 注释、
  `'...'`、`"..."`、`$$...$$`、`$tag$...$tag$`，psql 元命令不算语句）。加 `-Dpg.srcdir=...` 时
  会把清单里的行号范围和语句数跟 PG 源码逐条核对，写错就报错；清单里的 id 在脚本里找不到用例也会报错，
  所以**不能靠删用例来变绿**。
* 统计数字（用例数、执行/skip、脚本语句、耗时）随脚本改动实时变化，以 `[pgregress]` 输出为准；
  本文档第四节是 `2686a214b` 上的实测值。

## 十一、当前完成度与剩余缺口

### 11.1 一张表看全景

| 能力 | 状态 | 还差什么 |
| --- | --- | --- |
| SQL 函数：标量/SETOF/TABLE/VOID、OUT/INOUT/VARIADIC、STRICT、状态属性、易变性 | ✅ 完成 | — |
| SQL 函数体：查询/带 RETURNING 的 DML/DDL/复合类型/多态/`WITH`/`BEGIN ATOMIC`/`RETURN expr` | ✅ 完成 | 不带 `RETURNING` 的裸 DML 仍报 50100（PG 也只在 `RETURNS VOID` 时允许，用例已适配） |
| SQL 函数：setof 体内联进执行计划 | ❌ 未做 | `function_sql.setof_inline`（10 条 PG 语句）；需要优化器支持 + runner 的多行 EXPLAIN 断言 |
| 运算符/操作符类 | ❌ 未做 | `function_sql.partition_ops`（6 条）；需要 `CREATE OPERATOR CLASS` 一整块 |
| PL/pgSQL 语句（控制流、异常、游标、诊断） | ✅ 完成 | 用例覆盖到的都在跑 |
| PL/pgSQL 的 `CASE` 语句 / `FOREACH` / `ASSERT` / `EXECUTE` 动态 SQL / `RETURN QUERY EXECUTE` / `GET STACKED DIAGNOSTICS` | ❌ 未做 | 都报 `50100 PL/pgSQL statement: ...`；PG 对应段落（`plpgsql.sql` 的 CASE/FOREACH/EXECUTE 段）还没抽样，补齐前不建议动 |
| PL/pgSQL 游标 + `refcursor` | ✅ 完成（PL 内） | SQL 层 `FETCH ... FROM <name>`/`CLOSE <name>`、`MOVE`、`WHERE CURRENT OF`、`WITH HOLD` |
| PL/pgSQL 触发器函数 | ✅ 完成 | `TG_*` 内建变量只实现用例覆盖到的部分 |
| 存储过程（IN/OUT/INOUT/VARIADIC、重载、plpgsql 体、BEGIN ATOMIC、DDL 体、OR REPLACE、RENAME） | ✅ 完成 | `RENAME PROCEDURE name(签名)` 这种语句形式未支持（用 `ALTER PROCEDURE ... RENAME`） |
| `CALL` 语义 | ⚠️ 方言差异 | 与 JDBC `CallableStatement` 契约冲突，`call_random`/`call_function` 2 条 skip |
| psql 元命令 | ❌ 不在范围 | `\df`（`procedure.psql_meta` 1 条 skip） |
| 目录：`pg_proc` 兼容、`PARAMETERS`、`ROUTINES`、`pg_get_functiondef` | ✅ 完成 | 依赖关系视图（`ROUTINE_*_USAGE`）返回空表 |
| 触发器（PG 语法） | ✅ 完成 | 只在 `plpgsql.trigger_*` 覆盖的场景验证 |
| PG 回归用例覆盖率 | 98 个用例 / 1651 条 PG 语句中的 20 条被 skip | `plpgsql.sql`/`rangefuncs.sql` 仍有大段没抽样（第八节） |

### 11.2 怎么继续往上加

1. **打开用例**：从 `skip-list.txt` 删掉 id → 按 `pg/README.md` 把 `-- PG 期望` 改成 `>` 断言 →
   跑 `-Dtest=PgRegressTest`，红了就说明还没对齐。
2. **能立刻做的低风险项**：
   * SQL 层 `FETCH ... FROM <name>`/`CLOSE <name>`（复用 `ServerSession.getCursorPortals()`，
     新增 `FetchPortal`/`ClosePortal` 语句，约 200 行）；
   * `RENAME PROCEDURE name(签名) TO ...` 语句形式（已有 `ALTER PROCEDURE ... RENAME` 的实现可复用）；
   * `ROUTINE_*_USAGE` 依赖行（`ROUTINE_ROUTINE_USAGE` 已有视图，依赖分析还没填）；
   * 把 `plpgsql.sql` 里还没抽样的段落（`FOREACH`、`RETURN QUERY EXECUTE`、`CASE` 组合）补成用例。
3. **需要产品决策的**：`CALL` 是否区分函数与过程（涉及 JDBC `CallableStatement` 和
   `JdbcConnection` 内部的 `CALL READONLY()`/`CALL SCHEMA()`/`CALL TRANSACTION_ISOLATION_LEVEL()`）。
4. **补齐 PG 覆盖面**：`portals.sql` 在 SQL 层门户语句落地后纳入；`rangefuncs.sql` 的 `ROWS FROM`/
   LATERAL 段落等支持后再补。
