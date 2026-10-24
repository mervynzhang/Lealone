# PostgreSQL 风格的存储函数（SQL 函数体）

本批改动给 Lealone 增加了 `CREATE FUNCTION` / `DROP FUNCTION`，函数体（body）只能是一条 SQL 查询语句，
参数在函数体里按名字引用。实现落在 `SqlFunction`（`FunctionAlias` 的子类）上，schema 注册/查找、
`JavaFunction` 表达式、错误码、`getCreateSQL` 持久化全部复用既有的 `FunctionAlias` 机制。
函数可以返回单个值，也可以用 `RETURNS SETOF` / `RETURNS TABLE` 返回多行结果，后者可以在 `FROM`
子句里当表函数用。

## 一、支持的语法

```sql
CREATE [OR REPLACE] FUNCTION [IF NOT EXISTS] [schema.]name(p1 type, p2 type, ...)
    RETURNS type | RETURNS SETOF type | RETURNS TABLE(col1 type, col2 type, ...)
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
CREATE FUNCTION find_name(a INT) RETURNS VARCHAR AS
    $$ SELECT name FROM pgf_user WHERE id = a $$;
CREATE FUNCTION setof_int(a INT) RETURNS SETOF INT AS
    $$ SELECT id FROM pgf_user WHERE id >= a $$;
CREATE FUNCTION setof_table(a INT) RETURNS TABLE(id INT, name VARCHAR(50)) AS
    $$ SELECT id, name FROM pgf_user WHERE id >= a $$;
DROP FUNCTION IF EXISTS add_one;
```

## 二、语义

1. **函数体是单条查询**：`SELECT` / `VALUES` / `TABLE` / `WITH ... SELECT` / `(SELECT ...)`。
   创建时就校验第一条 token 是上面这些关键字，并且函数体里出现 `;` 或 `?` 会直接拒绝
   （防止一次执行多条语句、以及用户写的 `?` 和参数名替换出来的 `?` 混在一起）。
   函数体的 SQL 语法正确性不做创建时校验，调用时才由嵌套连接解析（和 `CREATE ALIAS` 一样是延迟校验）。
2. **标量函数**：取结果集**第一行第一列**作为返回值；结果集没有行时返回 `NULL`。
3. **`RETURNS SETOF type`**：函数返回多行，每行只有一列，值的类型是声明的 `type`。
   列名和 PostgreSQL 一样是**函数名**（未加引号的标识符在 Lealone 里默认转成大写）。
4. **`RETURNS TABLE(c1 t1, c2 t2, ...)`**：函数返回多行多列，结果列名/类型就是声明的那些。
   至少声明一列，列名不能重复（重名报 `SYNTAX_ERROR_1`）。
5. **表函数调用**：setof 函数可以出现在 `FROM` 里，调用形式和表一样，可以带参数、别名、
   列别名列表，并参与 `WHERE`/`JOIN`/聚合：

   ```sql
   SELECT * FROM setof_int(2);
   SELECT * FROM setof_table(2) t WHERE t.id > 1;
   SELECT u.name FROM pgf_user u JOIN setof_table(2) t ON u.id = t.id;
   SELECT * FROM setof_table(2) AS t(x, y);   -- 列别名列表
   SELECT count(*) FROM setof_int(99);        -- 没有行时是空集
   ```

6. **结果校验**：函数体结果集的列数必须和声明的一致，否则报
   `COLUMN_COUNT_DOES_NOT_MATCH`。每一列按位置转换成声明的类型。
   列数和类型的校验都发生在**调用时**，创建时不执行函数体（和标量函数一致）。
7. **select 列表里不展开**：PostgreSQL 里 `SELECT f(1)` 会把 setof 函数展开成多行，
   Lealone 不支持这种展开，遇到这种写法会明确报 `FEATURE_NOT_SUPPORTED`，
   提示改用 `SELECT * FROM f(...)`，不会静默返回第一行。
8. **`CALL f(1)`**：Lealone 既有的 `CALL` 对返回 ResultSet 的函数会把整张结果集返回，
   所以 setof 函数也可以用 `CALL` 调用（PostgreSQL 的 `CALL` 只用于存储过程，这是 Lealone 的扩展）。
9. **参数按名字引用**：创建时用 Lealone 自己的 tokenizer 对 body 切词，把与参数名匹配的**标识符**
   token 换成 `?`，并按出现顺序记录参数下标，运行时逐个绑定。同一个参数出现多次就绑多次（每次调用只求值一次实参）。
   - 字符串字面量、美元引用字符串、注释都被 tokenizer 当成单个 token，里面的内容不会被替换。
   - 没加双引号的参数名大小写不敏感；加了双引号按 token 原文精确匹配。
   - 形如 `t.a` 的限定名里的标识符不会被当成参数引用。
   - 参数名和表列名同名时，函数体里的该标识符会被当成参数（PostgreSQL 在这里是列优先，这是已知差异）。
10. **易变性**：`IMMUTABLE`/`STABLE` → `FunctionAlias.deterministic = true`；不写或写 `VOLATILE` → `false`。
    当前版本 Lealone 内核并不用 `deterministic` 影响执行计划，它只体现在持久化的 DDL 里（`IMMUTABLE`/`VOLATILE`），
    和既有的 `CREATE ALIAS ... DETERMINISTIC` 一样。
11. **OR REPLACE**：同名函数（含 `CREATE ALIAS` 建的同名对象）存在时先删再建，同一个事务同一个锁里完成。
    **IF NOT EXISTS**：存在时直接返回，不报错也不改已有定义。两个都写时以 `IF NOT EXISTS` 为准。
    都不写且已存在时报 `FUNCTION_ALIAS_ALREADY_EXISTS_1`（90076）。
12. **删除**：`DROP FUNCTION` 删除不存在函数时报 `FUNCTION_ALIAS_NOT_FOUND_1`（90077），
    加 `IF EXISTS` 不报错。
13. **持久化**：`getCreateSQL()` 生成
    `CREATE FUNCTION <name>(<"参数名" 类型, ...>) RETURNS <类型>|<SETOF 类型>|<TABLE("列名" 类型, ...)>
    <IMMUTABLE|VOLATILE> AS <函数体>`，参数名/列名用 `database.quoteIdentifier` 输出，
    函数体用 `StringUtils.quoteStringSQL` 输出（含换行/非 ASCII 字符时会变成 `STRINGDECODE(...)`）。
    这条语句能被自己的 parser 原样回放，重启后从 sys(meta) 表回放即可继续使用（已实测）。
14. **函数体里的表名按调用方当前的 schema 解析**（嵌套连接在 schema 不是 `PUBLIC` 时会先 `USE` 一下调用方的 schema）。
    函数名本身仍按 Lealone 既有规则解析：未限定名只查 session 当前 schema。

## 三、实现要点

- `SqlFunction`（`lealone-db/.../db/schema/SqlFunction.java`）继承 `FunctionAlias`，
  覆盖 `findJavaMethod`/`getJavaMethods` 返回自定义的 `JavaMethod` 子类，`getValue` 里通过
  `session.createNestedConnection(false)` 拿嵌套连接，`prepareStatement(参数化后的函数体)`
  → 绑定参数 → `executeQuery()`。标量函数取第一行第一列；setof 函数把整个结果集读出来，
  转换后放进 `SimpleResultSet`，再包成 `ValueResultSet` 返回。
- setof 函数的 `JavaMethod.getDataType()` 返回 `Value.RESULT_SET`，于是既有的
  `JavaFunction` → `FunctionTable` → `FunctionIndex` 这条表函数链路可以直接复用；
  `FunctionTable` 构造时会用 `columnList=true` 调一次函数拿结果列元数据，
  这时只根据声明生成空的结果集，不执行函数体。
- `FunctionAlias.isSetReturning()`（默认 `false`）是新增的钩子，`SqlFunction` 在声明了
  `SETOF`/`TABLE` 时返回 `true`，`JavaFunction` 转发它；`Select.prepare()` 用它把
  select 列表里的 setof 函数调用识别出来报 `FEATURE_NOT_SUPPORTED`。
- `SqlFunctionBodyParser`（`lealone-sql/.../sql/SqlFunctionBodyParser.java`）继承 `LealoneSQLParser`，
  借用它的 tokenizer 做“参数名换 `?`”。
- `SQLParserBase.parseCreateSqlFunction` 解析 `RETURNS SETOF` / `RETURNS TABLE(...)`，
  `CreateSqlFunction` 负责加排它锁、处理 `OR REPLACE`/`IF NOT EXISTS`、`schema.add`；
  语句类型复用 `SQLStatement.CREATE_ALIAS`，`DROP FUNCTION` 复用 `DropFunctionAlias`。
- 函数表在 `readTableFilter()` 里也支持列别名列表（`FROM f(1) t(a, b)`），复用了既有的
  `FunctionTable.setColumnAliases`。
- 每个函数调用都会新建一个嵌套 session/连接并重新 prepare 函数体，没有做按 session 缓存，
  和 `CREATE ALIAS` 的 Java 方法调用方式一致。

## 四、明确不做（都不在本次范围内）

- select 列表里展开 setof 函数（`SELECT f(1)` 展开成多行）、以及 `f(1) + 1` 这类嵌套使用
- `ROWS n` 提示、`WITH ORDINALITY`
- 复合类型/`record`/`SETOF <表名>` 的返回类型、`OUT`/`INOUT` 参数、`VARIADIC`、`STRICT`
- 按参数类型重载、`ALTER FUNCTION`、`COMMENT ON FUNCTION`
- `DROP FUNCTION ... CASCADE/RESTRICT`
- `LANGUAGE plpgsql` 等块语言与 `BEGIN ... END` 函数体、函数体里的 DML
- `LATERAL`：函数表实参不能引用同一 FROM 里其它表的列

## 五、已知限制 / 额外发现但没修的问题

1. 函数体的 SQL 语法在创建时不校验，`AS $$ SELECT FROM $$` 也能建成功，调用时才报错。
2. 参数名与列名同名时参数优先（见上文）。
3. `TABLE x` 作为函数体只支持裸表名：Lealone 的 `TABLE x` 语句本身不支持 `WHERE`（`TABLE t1 WHERE id = 1`
   会报语法错），所以 `TABLE` 函数体只能取整表的数据。
4. 函数名解析仍只查 session 当前 schema，`USE s3` 之后调用 `PUBLIC` 里的函数必须写成 `PUBLIC.f()`。
5. setof 函数只有顶层出现在 select 列表时才会被拦下并报 `FEATURE_NOT_SUPPORTED`；
   `f(1) + 1`、`WHERE f(1) = 1`、`ORDER BY f(1)` 这类嵌套/其它位置没有专门校验，
   会走普通表达式的类型转换路径报错或得到不可用的值，不会静默返回第一行。
6. setof 函数体的列数/列类型只在调用时校验和转换；创建时不执行函数体，也不做类型兼容性预检查。
7. 声明列的精度（如 `VARCHAR(5)`）只体现在结果元数据里，不强制截断函数体返回的值，
   这一点和既有的 `CREATE ALIAS` 表函数一致。
8. 表函数实参不能引用同一 FROM 里其它表的列（没有 LATERAL），会报
   `Column "L.ID" not found`；只有常量/参数/变量可以作为实参。
9. `FROM f(1) AS t` 只给表起别名，不会像 PostgreSQL 那样把单列结果的列名改成 `t`；
   要改列名得用列别名列表 `FROM f(1) AS t(x)`。列别名列表允许少于结果列数（PostgreSQL 要求个数相同）。
10. 标量函数当表函数用（`SELECT * FROM f(1)`）现在会报 `FUNCTION_MUST_RETURN_RESULT_SET_1`；
    以前会走 `JavaFunction.getValueForColumnList` 的类型转换失败，错误码不明确。
11. DDL 回滚后，同一个 session 里之前 prepare 过的 `SELECT f(...)` 可能命中缓存的执行计划，
    仍然返回旧结果（函数对象其实已经回滚掉了，新 session 调用会报找不到）。这是 DDL 与
    prepared statement 缓存之间的老问题，不在本次改动范围内。
