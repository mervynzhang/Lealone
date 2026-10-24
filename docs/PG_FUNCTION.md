# PostgreSQL 风格的存储函数（SQL 函数体）

本批改动给 Lealone 增加了 `CREATE FUNCTION` / `DROP FUNCTION`，函数体（body）只能是一条 SQL 查询语句，
参数在函数体里按名字引用。实现落在 `SqlFunction`（`FunctionAlias` 的子类）上，schema 注册/查找、
`JavaFunction` 表达式、错误码、`getCreateSQL` 持久化全部复用既有的 `FunctionAlias` 机制。

## 一、支持的语法

```sql
CREATE [OR REPLACE] FUNCTION [IF NOT EXISTS] [schema.]name(p1 type, p2 type, ...) RETURNS type
    [LANGUAGE SQL]
    [IMMUTABLE | STABLE | VOLATILE]
    AS $$ body $$;

DROP FUNCTION [IF EXISTS] [schema.]name[(type, ...)];
```

- 函数体既能用美元引用 `AS $$ ... $$`，也能用单引号 `AS '...'`。
- `LANGUAGE SQL` 可选，写其它语言（如 `plpgsql`）直接报 `FEATURE_NOT_SUPPORTED`。
- `LANGUAGE` 子句与易变性关键字（`IMMUTABLE`/`STABLE`/`VOLATILE`）都是可选的，且顺序任意，但都必须写在
  `AS` 之前。
- `DROP FUNCTION` 后面可选的 `(type, ...)` 会被解析后丢弃（不支持按参数类型重载）。
- 例：

```sql
CREATE FUNCTION add_one(a INT) RETURNS INT LANGUAGE SQL IMMUTABLE AS $$ SELECT a + 1 $$;
CREATE OR REPLACE FUNCTION find_name(a INT) RETURNS VARCHAR AS
    $$ SELECT name FROM pgf_user WHERE id = a $$;
DROP FUNCTION IF EXISTS add_one;
```

## 二、语义

1. **函数体是单条查询**：`SELECT` / `VALUES` / `TABLE` / `WITH ... SELECT` / `(SELECT ...)`。
   取结果集**第一行第一列**作为返回值；结果集没有行时返回 `NULL`。
   创建时就校验第一条 token 是上面这些关键字，并且函数体里出现 `;` 或 `?` 会直接拒绝
   （防止一次执行多条语句、以及用户写的 `?` 和参数名替换出来的 `?` 混在一起）。
   函数体的 SQL 语法正确性不做创建时校验，调用时才由嵌套连接解析（和 `CREATE ALIAS` 一样是延迟校验）。
2. **参数按名字引用**：创建时用 Lealone 自己的 tokenizer 对 body 切词，把与参数名匹配的**标识符**
   token 换成 `?`，并按出现顺序记录参数下标，运行时逐个绑定。同一个参数出现多次就绑多次（每次调用只求值一次实参）。
   - 字符串字面量、美元引用字符串、注释都被 tokenizer 当成单个 token，里面的内容不会被替换。
   - 没加双引号的参数名大小写不敏感；加了双引号按 token 原文精确匹配。
   - 形如 `t.a` 的限定名里的标识符不会被当成参数引用。
   - 参数名和表列名同名时，函数体里的该标识符会被当成参数（PostgreSQL 在这里是列优先，这是已知差异）。
3. **易变性**：`IMMUTABLE`/`STABLE` → `FunctionAlias.deterministic = true`；不写或写 `VOLATILE` → `false`。
   当前版本 Lealone 内核并不用 `deterministic` 影响执行计划，它只体现在持久化的 DDL 里（`IMMUTABLE`/`VOLATILE`），
   和既有的 `CREATE ALIAS ... DETERMINISTIC` 一样。
4. **OR REPLACE**：同名函数（含 `CREATE ALIAS` 建的同名对象）存在时先删再建，同一个事务同一个锁里完成。
   **IF NOT EXISTS**：存在时直接返回，不报错也不改已有定义。两个都写时以 `IF NOT EXISTS` 为准。
   都不写且已存在时报 `FUNCTION_ALIAS_ALREADY_EXISTS_1`（90076）。
5. **删除**：`DROP FUNCTION` 删除不存在函数时报 `FUNCTION_ALIAS_NOT_FOUND_1`（90077），
   加 `IF EXISTS` 不报错。
6. **持久化**：`getCreateSQL()` 生成
   `CREATE FUNCTION <name>(<"参数名" 类型, ...>) RETURNS <类型> <IMMUTABLE|VOLATILE> AS <函数体>`，
   参数名用 `database.quoteIdentifier` 输出，函数体用 `StringUtils.quoteStringSQL` 输出
   （含换行/非 ASCII 字符时会变成 `STRINGDECODE(...)`）。这条语句能被自己的 parser 原样回放，
   重启后从 sys(meta) 表回放即可继续使用（已用“新 JVM 进程打开旧库”的方式实测）。
7. **函数体里的表名按调用方当前的 schema 解析**（嵌套连接在 schema 不是 `PUBLIC` 时会先 `USE` 一下调用方的 schema）。
   函数名本身仍按 Lealone 既有规则解析：未限定名只查 session 当前 schema。

## 三、实现要点

- `SqlFunction`（`lealone-db/.../db/schema/SqlFunction.java`）继承 `FunctionAlias`，
  覆盖 `findJavaMethod`/`getJavaMethods` 返回自定义的 `JavaMethod` 子类，`getValue` 里通过
  `session.createNestedConnection(false)` 拿嵌套连接，`prepareStatement(参数化后的函数体)`
  → 绑定参数 → `executeQuery()` → 取第一行第一列 → `DataType.convertToValue` 转成声明的返回类型。
  嵌套连接和 `FunctionAlias` 调用 Java 方法时用的是同一套机制，事务挂到父事务上。
- `SqlFunctionBodyParser`（`lealone-sql/.../sql/SqlFunctionBodyParser.java`）继承 `LealoneSQLParser`，
  借用它的 tokenizer 做“参数名换 `?`”。
- `CreateSqlFunction`（`lealone-sql/.../sql/ddl/CreateSqlFunction.java`）负责加排它锁、处理
  `OR REPLACE`/`IF NOT EXISTS`、`schema.add`；语句类型复用 `SQLStatement.CREATE_ALIAS`，
- `DROP FUNCTION` 复用 `DropFunctionAlias`（同样是 `DbObjectType.FUNCTION_ALIAS`）。
- 每个函数调用都会新建一个嵌套 session/连接并重新 prepare 函数体，没有做按 session 缓存，
  和 `CREATE ALIAS` 的 Java 方法调用方式一致。

## 四、明确不做（都不在本次范围内）

- `LANGUAGE plpgsql` 等块语言与 `BEGIN ... END` 函数体
- `RETURNS SETOF` / `RETURNS TABLE` / 复合类型或行返回
- `OUT` / `INOUT` 参数、`VARIADIC`、`STRICT`
- `SECURITY DEFINER`/`SECURITY INVOKER`、`COST`/`ROWS`/`SET` 子句
- 按参数类型重载（同名函数只能有一个）、`ALTER FUNCTION`、`COMMENT ON FUNCTION`
- `DROP FUNCTION ... CASCADE/RESTRICT`

## 五、已知限制 / 额外发现但没修的问题

1. 函数体的 SQL 语法在创建时不校验，`AS $$ SELECT FROM $$` 也能建成功，调用时才报错。
2. 参数名与列名同名时参数优先（见上文）。
3. `TABLE x` 作为函数体只支持裸表名：Lealone 的 `TABLE x` 语句本身不支持 `WHERE`（`TABLE t1 WHERE id = 1`
   会报语法错），所以 `TABLE` 函数体只能取整表的第一行第一列。
4. 函数名解析仍只查 session 当前 schema，`USE s3` 之后调用 `PUBLIC` 里的函数必须写成 `PUBLIC.f()`。
5. 把标量函数当表函数用（`SELECT * FROM f(1)`）会走 `JavaFunction.getValueForColumnList`，
   那里要求返回值是 `ValueResultSet`，标量函数会类型转换失败；`CREATE ALIAS` 的标量函数本来也是这样。
6. DDL 回滚后，同一个 session 里之前 prepare 过的 `SELECT f(...)` 可能命中缓存的执行计划，
   仍然返回旧结果（函数对象其实已经回滚掉了，新 session 调用会报找不到）。这是 DDL 与
   prepared statement 缓存之间的老问题，不在本次改动范围内。
