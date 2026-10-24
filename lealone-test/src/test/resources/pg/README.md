# pg/ — PostgreSQL 函数/存储过程回归测试（Lealone 转换版）

这个目录里是 PG 官方回归测试（`src/test/regress/sql/*.sql`）中与
`CREATE FUNCTION` / `CREATE PROCEDURE` 相关部分的 Lealone 转换版，由
`com.lealone.test.pg.PgRegressTest` 执行。整个套件的背景、统计、skip 清单摘要和已发现的
缺陷见仓库根目录的 [`docs/PG_REGRESS.md`](../../../../../docs/PG_REGRESS.md)。

## 脚本格式

和已有的 H2 风格脚本测试 `com.lealone.test.script.SqlScriptTest` **完全一样**，只是多了两个标记：

```sql
-- @case function_sql.a1_args          用例 id（skip 清单的键，全文件唯一）
-- @pg create_function_sql.sql:23-24   这个用例抄自 PG 的哪个文件的哪几行（必填）
create function functest_A_1(a text, b date) returns boolean language sql
    as 'select a = ''abcd'' and b > ''2001-01-01''';
> update count: 0

select functest_A_1('abcd', '2020-01-01') r;
> R
> ----
> TRUE
> rows: 1
```

* 语句以 `;` 结束，后面跟期望输出（`>` 开头）。
* 查询结果：列名 / 分隔线 / 数据行 / `> rows: N`（无序比较）或 `> rows (ordered): N`。
* 一行一列可以写成 `> >> 值`；更新语句写 `> update count: N`（DDL 是 0）或 `> ok`；
  异常写 `> exception <Lealone错误码>`。
* 一个 `.sql` 文件对应一个独立的库，文件内的用例按顺序执行（和 PG 一个测试文件跑一次一样，
  后面的用例可以用前面建的对象）；文件之间互不影响。库建在 `target/test-data/pgregress/`，
  整轮跑完删除。
* 每个用例都必须有 `-- @case` 和 `-- @pg`，缺了会直接报结构错误。

## skip 清单：`skip-list.txt`

制表符分隔，`#` 开头是注释：

```
<case-id> \t <PG源文件:起始行-结束行> \t <该范围内的PG语句数> \t <原因>
```

* 被 skip 的用例**仍然留在脚本里**（带 `-- PG 期望:` 注释），只是不执行。
* 对账规则：清单里的每个 id 必须在脚本里找得到 `-- @case`，否则测试失败。所以
  "把跑不过的用例删掉"这条路是堵死的；想让套件变绿只能真修好或明确写进清单。
* 原因分两类：以 `缺陷:` 开头的是 Lealone 的真实缺陷（见 `docs/PG_REGRESS.md` 第六节），
  其余是能力缺失（未实现的功能）。
* 头部还有 `# @pg-total <文件> <语句数> converted|skip-all` 三行，声明被转换的 PG 原文件有多少条
  语句，用来算"跳过了多少"；`skip-all` 表示整份文件都不适用（目前只有 `plpgsql.sql`）。

## 打开一个被 skip 的用例

1. 从 `skip-list.txt` 删掉那一行。
2. 把脚本里该用例的 `-- PG 期望:` 注释改写成 `>` 期望输出（对照 PG 的
   `src/test/regress/expected/<同名>.out`），必要时去掉为绕开缺失能力而写的注释。
3. `mvn -o -pl lealone-test test -Dtest=PgRegressTest`，失败就说明 Lealone 还没对齐。

## 运行与维护

```bash
# 单独跑（约 0.3 秒，不需要 PG 源码）
mvn -o -pl lealone-test test -Dtest=PgRegressTest -Dmaven.repo.local=/tmp/m2-pgregress

# 顺带校验 skip 清单里的 PG 行号和语句数（需要本地 PG 源码树）
mvn -o -pl lealone-test test -Dtest=PgRegressTest \
    -Dpg.srcdir=/path/to/postgres/src/test/regress/sql

# 只跑某个脚本目录 / 换一份清单
mvn -o -pl lealone-test test -Dtest=PgRegressTest -Dpg.dir=/tmp/pg-only -Dpg.skip=/tmp/skip.txt
```

写新用例时的技巧：

* PG 的函数名大小写不敏感（都转成大写），同一个文件里别和已有的函数重名，否则会
  `FUNCTION_ALIAS_ALREADY_EXISTS`。
* Lealone 的布尔值是 `TRUE/FALSE`（PG 是 `t/f`），适配时只改显示，不改语义。
* 需要结果集时用 `select f(...) as r` 起别名，列名短一点，期望输出的对齐好数。
* 不要为了通过而放宽期望值：Lealone 的行为和 PG 不一致就写进 skip 清单并说明原因。
