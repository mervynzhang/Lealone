# PostgreSQL 风格的存储函数（SQL 函数体）

本批改动给 Lealone 增加了 `CREATE FUNCTION` / `DROP FUNCTION`，函数体（body）只能是一条 SQL 查询语句，
参数在函数体里按名字引用。实现落在 `SqlFunction`（`FunctionAlias` 的子类）上，schema 注册/查找、
`JavaFunction` 表达式、错误码、`getCreateSQL` 持久化全部复用既有的 `FunctionAlias` 机制。
函数可以返回单个值，也可以用 `RETURNS SETOF` / `RETURNS TABLE` 返回多行结果，后者可以在 `FROM`
子句里当表函数用。

本文档已包含参数机制：`OUT`/`INOUT` 参数、`STRICT`、`VARIADIC`、调用期类型转换，
以及“按参数类型重载”的评估结论（见第五节）。

第三轮（R3）补上了 PostgreSQL 的函数 DDL 属性：`ALTER FUNCTION`、`SECURITY DEFINER`/`INVOKER`、
`CALLED ON NULL INPUT`、`COST`/`ROWS`/`SET`/`LEAKPROOF`/`WINDOW`、`DROP FUNCTION` 的参数类型列表、
`COMMENT ON FUNCTION`。每条属性的支持程度见 2.8 节，DDL 都能通过 SCRIPT 原样回放。

## 一、支持的语法

```sql
CREATE [OR REPLACE] FUNCTION [IF NOT EXISTS] [schema.]name(
        [IN | OUT | INOUT | VARIADIC] [p1] type[[]], ...)
    [RETURNS type                      -- 有OUT/INOUT参数时可以省略
    | RETURNS SETOF type
    | RETURNS TABLE(col1 type, col2 type, ...)]
    [LANGUAGE SQL | plpgsql]
    [IMMUTABLE | STABLE | VOLATILE]
    [STRICT | CALLED ON NULL INPUT | RETURNS NULL ON NULL INPUT]
    [SECURITY DEFINER | SECURITY INVOKER]
    [LEAKPROOF | NOT LEAKPROOF]
    [WINDOW]
    [COST n] [ROWS n]
    [SET configuration_parameter { TO | = } value] ...
    [OWNER TO user]                    -- Lealone扩展, 见2.8节
    AS $$ body $$;

ALTER FUNCTION [IF EXISTS] [schema.]name[(type, ...)] 动作;
    -- 动作可以是(每次一句):
    --   IMMUTABLE | STABLE | VOLATILE
    --   STRICT | CALLED ON NULL INPUT | RETURNS NULL ON NULL INPUT
    --   SECURITY DEFINER | SECURITY INVOKER | LEAKPROOF | NOT LEAKPROOF | WINDOW
    --   COST n | ROWS n
    --   SET configuration_parameter { TO | = } value | RESET configuration_parameter | RESET ALL
    --   RENAME TO new_name | OWNER TO new_owner

COMMENT ON FUNCTION [schema.]name[(type, ...)] IS 'text';

DROP FUNCTION [IF EXISTS] [schema.]name[(type, ...)];
```

- 函数体既能用美元引用 `AS $$ ... $$`，也能用单引号 `AS '...'`。
- `LANGUAGE SQL` 可选，写其它语言（如 `plpgsql`）直接报 `FEATURE_NOT_SUPPORTED`。
- `LANGUAGE` 子句与易变性关键字（`IMMUTABLE`/`STABLE`/`VOLATILE`）都是可选的，且顺序任意，但都必须写在
  `AS` 之前。
- 参数模式：`IN`（默认）、`OUT`、`INOUT`、`VARIADIC`；数组类型可以写 `type[]` 或 `type ARRAY`。
- `OUT`/`INOUT`/`VARIADIC` 参数可以只写类型不写名字（内部用 `$1`、`$2`... 作为名字）。
- `DROP FUNCTION` 后面可选的 `(type, ...)` 会被解析后丢弃（不支持按参数类型重载，见第五节）。
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

-- OUT参数组成返回行，RETURNS可以省略
CREATE FUNCTION f(IN a INT, OUT b INT) AS $$ SELECT a + 1 $$;
-- INOUT既是入参也是返回列
CREATE FUNCTION g(INOUT x INT) AS $$ SELECT x * 2 $$;
-- 任一入参为NULL时不执行函数体
CREATE FUNCTION safe_div(IN a INT) RETURNS INT STRICT AS $$ SELECT 1 / a $$;
-- 最后一个参数是数组，调用时展开
CREATE FUNCTION var_count(VARIADIC nums INT[]) RETURNS INT AS $$ SELECT ARRAY_LENGTH(nums) $$;
SELECT var_count(1, 2, 3);  -- 3
```

## 二、语义

1. **函数体是单条查询**：`SELECT` / `VALUES` / `TABLE` / `WITH ... SELECT` / `(SELECT ...)`。
   创建时就校验第一条 token 是上面这些关键字，并且函数体里出现 `;` 或 `?` 会直接拒绝
   （防止一次执行多条语句、以及用户写的 `?` 和参数名替换出来的 `?` 混在一起）。
   建函数时还会在一个嵌套 session 里 prepare 一次函数体（**不执行**）：语法错误、列/表不存在、
   常量返回值类型不匹配都在 `CREATE` 时报出；只有 setof/table/多个 OUT 参数的结果列形状留到调用时校验。
   数据库启动回放 `sys(meta)` 时会跳过这个校验（此时函数体引用的表可能还没建出来），
   等价于 PG 的 `check_function_bodies=off`。
2. **标量函数**：取结果集**第一行第一列**作为返回值；结果集没有行时返回 `NULL`。
3. **`RETURNS SETOF type`**：函数返回多行，每行只有一列，值的类型是声明的 `type`。
   列名和 PostgreSQL 一样是**函数名**（未加引号的标识符在 Lealone 里默认转成大写）。
   当 `type` 是 `VOID` 时结果列类型是 `NULL`，和 PostgreSQL 一样**不返回任何行**，
   函数体返回什么、返回多少列都不影响（PG 官方回归 `voidtest5` 的期望输出就是 `(0 rows)`）。
4. **`RETURNS TABLE(c1 t1, c2 t2, ...)`**：函数返回多行多列，结果列名/类型就是声明的那些。
   至少声明一列，列名不能重复（重名报 `SYNTAX_ERROR_1`）。
5. **表函数调用**：函数都可以出现在 `FROM` 里，调用形式和表一样，可以带参数、别名、
   列别名列表，并参与 `WHERE`/`JOIN`/聚合：

   ```sql
   SELECT * FROM setof_int(2);
   SELECT * FROM setof_table(2) t WHERE t.id > 1;
   SELECT u.name FROM pgf_user u JOIN setof_table(2) t ON u.id = t.id;
   SELECT * FROM setof_table(2) AS t(x, y);   -- 列别名列表
   SELECT count(*) FROM setof_int(99);        -- 没有行时是空集
   -- 标量函数也能当表函数: 只有一行一列, 列名用函数名(和 PostgreSQL 一致)
   SELECT * FROM add_one(42);                 -- 一行 43
   -- 多个 OUT 参数的函数: 展开成一行多列
   SELECT * FROM f(42);                       -- 一行 (j, k)
   -- WITH ORDINALITY: 最后追加一列从 1 开始的 bigint 行号
   SELECT * FROM setof_int(2) WITH ORDINALITY AS z(a, ord);
   ```

   `WITH ORDINALITY` 的行号列默认列名是 `ORDINALITY`（Lealone 默认把标识符大写，
   写 `ordinality` 也能按大小写不敏感解析），类型是 `BIGINT`；可以用列别名列表改名，
   行号在 `WHERE` 之前算好（所以 `WHERE ord > 1` 过滤后序号不重排）。
   只有函数后面能跟 `WITH ORDINALITY`，普通表后面跟会报语法错。
   函数表的 `WITH ORDINALITY` 和列别名列表会出现在生成的视图定义里，
   所以 `CREATE VIEW v AS SELECT * FROM f() WITH ORDINALITY AS z(a, ord)` 之后重新编译视图不会丢列
   （`FunctionTable.getSQL()` 输出 `WITH ORDINALITY`，`TableFilter.getPlanSQL()` 输出列别名列表）。

6. **结果校验**：函数体结果集的列数必须和声明的一致，否则报
   `COLUMN_COUNT_DOES_NOT_MATCH`。每一列按位置转换成声明的类型。
   setof/table 的结果列数和类型转换仍发生在**调用时**，创建时不执行函数体；
   标量返回值的列数和常量类型在创建时就校验（见第 1 条）。
7. **select 列表里不展开**：PostgreSQL 里 `SELECT f(1)` 会把 setof 函数展开成多行，
   Lealone 不支持这种展开，遇到这种写法会明确报 `FEATURE_NOT_SUPPORTED`，
   提示改用 `SELECT * FROM f(...)`，不会静默返回第一行。
8. **`CALL f(1)`**：Lealone 既有的 `CALL` 对返回 ResultSet 的函数会把整张结果集返回，
   所以 setof 函数也可以用 `CALL` 调用（PostgreSQL 的 `CALL` 只用于存储过程，这是 Lealone 的扩展）。
9. **参数按名字引用**：创建时用 Lealone 自己的 tokenizer 对 body 切词，把与参数名匹配的**标识符**
   token 换成 `?`，并按出现顺序记录参数下标，运行时逐个绑定。同一个参数出现多次就绑多次（每次调用只求值一次实参）。
   函数体是 `WITH ... SELECT` 时，CTE 查询里的参数也能正确绑定（多个 CTE 共用参数时按全局编号传值）。
   - 字符串字面量、美元引用字符串、注释都被 tokenizer 当成单个 token，里面的内容不会被替换。
   - 没加双引号的参数名大小写不敏感；加了双引号按 token 原文精确匹配。
   - 形如 `t.a` 的限定名里的标识符不会被当成参数引用。
   - 参数名和表列名同名时，函数体里的该标识符会被当成参数（PostgreSQL 在这里是列优先，这是已知差异）。
10. **`OUT` / `INOUT` 参数**：`OUT` 参数不出现在实参列表里，`INOUT` 参数既算入参也算返回列；
    返回列按参数声明顺序排列。
    - 恰好一个 `OUT`/`INOUT` 参数时，函数返回一个标量，类型就是该参数的类型，
      调用形式是 `SELECT f(1)`；此时 `RETURNS` 可写可不写，写了必须和该参数类型一致。
    - 有多个 `OUT`/`INOUT` 参数时，函数返回一行 **record**：
      - 值上下文（`SELECT f(1)`）里 record 用 `ValueArray` 表示，`getString()` 是 `(43, foo)`，
        和 PostgreSQL 的 record 文本形式一致（Lealone 的数组元素之间带一个空格，PG 的 record 不带）；
      - 表上下文（`SELECT * FROM f(1)`）里仍然展开成一列一列，列名用参数名；
      - `STRICT` 函数入参为 NULL 时 `SELECT f(NULL)` 得到 NULL record，`SELECT * FROM f(NULL)`
        得到一行全 NULL（PostgreSQL 在这里都是 NULL 记录）。
      此时不能再写 `RETURNS`。表达式里还不能对 record 取字段（`(f(1)).j` 不支持）。
    - 只有 `OUT` 参数、没有入参时可以直接 `SELECT f()`。
    - `OUT` 参数在函数体里被引用时绑定为 NULL（PostgreSQL 里在 SQL 函数体中引用 OUT 参数是非法的），
      因此不要依赖这种写法。
    - `RETURNS SETOF`/`RETURNS TABLE` 和 `OUT`/`INOUT` 参数不能同时出现（PostgreSQL 也不允许），
      同时写会报 `FEATURE_NOT_SUPPORTED`，不会静默忽略其中一侧。
11. **STRICT**：任一入参为 NULL 时不执行函数体，直接返回 NULL（`INOUT` 算入参，`OUT` 不算）。
   等价写法 `RETURNS NULL ON NULL INPUT` 也支持。函数体不会被求值，所以像
   `SELECT 1 / a` 这种“NULL 会报错但 0 才真正报错”的写法可以安全用 STRICT 保护。
12. **VARIADIC**：必须是参数列表最后一个参数，且类型必须是数组（`type[]`/`type ARRAY`）。
   调用时最后一个参数位置可以传 0 到多个实参，运行期打包成 `ValueArray` 传给函数体：
   - 不传时是空数组（不是 NULL），固定参数仍然不能少；
   - 展开出来的每个实参都按**数组元素类型**转换；
   - 只传一个数组值时直接当数组用（不再多包一层）。Lealone 目前没有 `ARRAY[...]` 字面量，
     所以这条只有函数返回数组时才用得上。
13. **调用期类型转换**：实参按参数声明的类型转换（`Value.convertTo`）后再绑定给函数体，
   和 PostgreSQL “实参按声明类型做隐式转换”对齐；不能转换时抛 `DATA_CONVERSION_ERROR_1`（22018，
   PostgreSQL 这里是 42883/22P02 一类），错误信息里带上参数名、实参值和目标类型。
   转换发生在执行期，所以常量实参在 prepare/optimize 阶段就会报错，列这种非常量实参在运行时才报错。
   注意 Lealone 的类型转换比 PostgreSQL 宽松（例如 `INT` 声明可以接受 `'5'` 这种字符串、
   `INT` 声明接受 `VARCHAR` 列、甚至标量会被 `toArray()` 包成单元素数组），
   这里只保证“不能转换时一定报错”，不保证和 PostgreSQL 的可转换集合完全一致。
14. **易变性**：`IMMUTABLE`/`STABLE` → `FunctionAlias.deterministic = true`；不写或写 `VOLATILE` → `false`。
    当前版本 Lealone 内核并不用 `deterministic` 影响执行计划，它只体现在持久化的 DDL 里（`IMMUTABLE`/`VOLATILE`），
    和既有的 `CREATE ALIAS ... DETERMINISTIC` 一样。
15. **OR REPLACE**：同名函数（含 `CREATE ALIAS` 建的同名对象）存在时先删再建，同一个事务同一个锁里完成。
    **IF NOT EXISTS**：存在时直接返回，不报错也不改已有定义。两个都写时以 `IF NOT EXISTS` 为准。
    都不写且已存在时报 `FUNCTION_ALIAS_ALREADY_EXISTS_1`（90076）。
16. **删除**：`DROP FUNCTION` 删除不存在函数时报 `FUNCTION_ALIAS_NOT_FOUND_1`（90077），
    加 `IF EXISTS` 不报错。
17. **持久化**：`getCreateSQL()` 生成
    `CREATE FUNCTION <name>([OUT|INOUT] [VARIADIC] <"参数名" 类型[[]], ...)
    [RETURNS <类型>|<SETOF 类型>|<TABLE("列名" 类型, ...)>] [STRICT]
    <IMMUTABLE|VOLATILE> AS <函数体>`，有 `OUT`/`INOUT` 参数时省略 `RETURNS`（和 PostgreSQL 一样，
    返回类型由参数推导）。参数名/列名用 `database.quoteIdentifier` 输出，
    函数体用 `StringUtils.quoteStringSQL` 输出（含换行/非 ASCII 字符时会变成 `STRINGDECODE(...)`）。
    这条语句能被自己的 parser 原样回放，重启后从 sys(meta) 表回放即可继续使用（已实测）。
18. **函数体里的表名按调用方当前的 schema 解析**（嵌套连接在 schema 不是 `PUBLIC` 时会先 `USE` 一下调用方的 schema）。
    函数名本身仍按 Lealone 既有规则解析：未限定名只查 session 当前 schema。

2.8 **函数属性**（`CREATE FUNCTION` 与 `ALTER FUNCTION` 共用一套解析和持久化）:

| 属性 | 支持程度 | 说明 |
| --- | --- | --- |
| `IMMUTABLE` / `STABLE` / `VOLATILE` | 完整 | `IMMUTABLE`/`STABLE` 都是 `deterministic=true`，默认 `VOLATILE`；持久化时**原样保留**用户写的关键字（上一版把 `STABLE` 归一化成 `IMMUTABLE`） |
| `STRICT` / `RETURNS NULL ON NULL INPUT` / `CALLED ON NULL INPUT` | 完整 | 两种写法都等价于 `STRICT`（任一入参为 NULL 时不执行函数体），`CALLED ON NULL INPUT` 是显式写出来的默认行为；DDL 里一定写出 `STRICT` 或 `CALLED ON NULL INPUT` 之一，所以重启后语义不变 |
| `SECURITY DEFINER` / `SECURITY INVOKER` | 完整 | `DEFINER` 时函数体用一个**属主身份的嵌套会话**执行（SQL 函数体和 PL/pgSQL 函数体都支持），`INVOKER`（默认）用调用者身份；属主默认是创建者，DDL 里同时持久化 `SECURITY DEFINER` 和 `OWNER TO`，重启后仍然生效 |
| `OWNER TO user` | 只影响 DEFINER 的执行身份 | Lealone 没有函数级的 ownership/EXECUTE 权限，属主唯一的作用是 `SECURITY DEFINER` 时以谁的身份执行；用户必须存在，否则报 `USER_NOT_FOUND_1`。为了能回放，`OWNER TO` 写成 `AS` 之后的 Lealone 扩展子句 |
| `LEAKPROOF` / `NOT LEAKPROOF` | 只解析 + 持久化 | Lealone 没有泄露证明（leakproof）机制，不生效；`NOT LEAKPROOF` 只是把标记清掉（PG 默认值） |
| `WINDOW` | 只解析 + 持久化 | Lealone 没有窗口函数框架，标记不生效；保留是为了 PG 的 DDL 能过 |
| `COST n` / `ROWS n` | 只解析 + 持久化 | 优化器不使用这两个提示（`JavaFunction.getCost()` 仍按固定值算）；`ROWS` 在 PostgreSQL 里只对 SETOF 有意义，这里不做校验 |
| `SET configuration_parameter = value` | 只解析 + 持久化 | **不会**真的在嵌套会话里执行这个 SET（避免 `work_mem` 这类 Lealone 不认识的参数直接报错），`SET search_path` 也不改变 schema 解析；`RESET name` / `RESET ALL` 只是把条目从列表里删掉。没加引号的标识符值会按 Lealone 的规则大写（如 `s1` → `S1`） |
| `DROP FUNCTION name(type, ...)` | 完整（无重载） | 参数类型参与签名校验：类型/个数对不上就报 `FUNCTION_NOT_FOUND_1`（90022，消息里带实际签名），`IF EXISTS` 时不报错。只比较类型，忽略 `varchar(10)` 这类 typmod；没有类型列表时按名字删除 |
| `COMMENT ON FUNCTION name[(type)] IS '...'` | 完整 | 复用既有的 COMMENT 机制（`COMMENT ON ALIAS` 也能读写同一份注释），持久化成 `COMMENT ON ALIAS ...` |

`ALTER FUNCTION` 的对象是 `SqlFunction`（含 PL/pgSQL 函数）；对 `CREATE ALIAS` 建的 Java 函数报
`FEATURE_NOT_SUPPORTED`。`ALTER FUNCTION ... SET SCHEMA` 没有实现（要移动对象），也报不支持。

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
- `FunctionTable` 现在支持三种函数：`Value.RESULT_SET`（setof/table）、record
  （`Function.isRecordReturning()`，多 OUT 参数）、以及标量（一行一列，列名用函数名）。
  标量/record 的行是现场组装的（不经过 `ValueResultSet`）。`WITH ORDINALITY` 只是给
  `FunctionTable` 追加一列 `BIGINT`，读结果集时逐行补行号，不再单独包一层表。
- `Function.isRecordReturning()`/`FunctionAlias.isRecordReturning()`（默认 `false`）是新钩子，
  `SqlFunction` 在 OUT 参数个数大于 1 时返回 `true`，`JavaFunction` 转发它；
  多 OUT 函数的 `returnType` 是 `Value.ARRAY`，值上下文里返回 `ValueArray`（record），
  `columnList=true` 时仍然返回列定义用的 `ValueResultSet`。
- `FunctionAlias.isSetReturning()`（默认 `false`）是新增的钩子，`SqlFunction` 在声明了
  `SETOF`/`TABLE` 时返回 `true`，`JavaFunction` 转发它；`Select.prepare()` 用它把
  select 列表里的 setof 函数调用识别出来报 `FEATURE_NOT_SUPPORTED`。
- `SqlFunctionBodyParser`（`lealone-sql/.../sql/SqlFunctionBodyParser.java`）继承 `LealoneSQLParser`，
  借用它的 tokenizer 做“参数名换 `?`”。
- `SQLParserBase.parseCreateSqlFunction` 解析参数模式/数组类型/STRICT，以及 `RETURNS SETOF` /
  `RETURNS TABLE(...)`；参数模式/数组元素类型放在 `parameterModes`/`parameterTypeIds`/
  `parameterComponentTypes` 数组里交给 `SqlFunction`，入参、返回列的下标在 `setParameters` 里一次算好，
  运行时不再按模式分支去猜。
- `CreateSqlFunction`（`lealone-sql/.../sql/ddl/CreateSqlFunction.java`）负责加排它锁、处理
  `OR REPLACE`/`IF NOT EXISTS`、`schema.add`；语句类型复用 `SQLStatement.CREATE_ALIAS`，
  `DROP FUNCTION` 复用 `DropFunctionAlias`（同样是 `DbObjectType.FUNCTION_ALIAS`）。
- `FunctionAttributes`（`lealone-sql/.../sql/ddl/FunctionAttributes.java`）是属性值的载体，
  `SQLParserBase.readFunctionAttribute()` 被 `CREATE FUNCTION`（`AS` 前后的两个属性循环）和
  `ALTER FUNCTION` 共用；`SqlFunction` 上是这些属性字段 + 原样保留的易变性关键字，
  属性全部体现在 `SqlFunction.getCreateSQL()` 里，所以落 sys(meta) 表后重启能回放。
- `AlterSqlFunction`（`lealone-sql/.../sql/ddl/AlterSqlFunction.java`）负责 `ALTER FUNCTION`：
  加 `FUNCTION_ALIAS` 排它锁、按（可选的）参数类型列表校验签名、改完字段后 `database.updateMeta()`
  重写 sys 表里的 DDL；`RENAME TO` 走 `Schema.rename()`（`Database.renameDatabaseObject()` 只支持
  非 schema 对象，直接用它会在 schema 对象上 NPE）。
- `SECURITY DEFINER`：`ServerSession` 新增了 `createNestedConnection(User, boolean)` /
  `createNestedSession(User)` 两个以指定用户身份建嵌套会话的重载，`SqlFunction` 里的
  `createNestedConnection(session)`/`createNestedSession(session)` 按 `securityDefiner` 选择身份，
  `PlpgSqlFunction` 也改了 1 行用同一个入口。
- 函数表在 `readTableFilter()` 里也支持列别名列表（`FROM f(1) t(a, b)`），复用了既有的
  `FunctionTable.setColumnAliases`。
- 每个函数调用都会新建一个嵌套 session/连接并重新 prepare 函数体，没有做按 session 缓存，
  和 `CREATE ALIAS` 的 Java 方法调用方式一致。

## 四、明确不做（都不在本次范围内）

- select 列表里展开 setof 函数（`SELECT f(1)` 展开成多行）、以及 `f(1) + 1` 这类嵌套使用
- `ROWS n` 只当属性存着，不作为优化器的行数提示
- `RETURNS RECORD`/复合类型/`SETOF <表名>` 的返回类型；多 `OUT` 参数函数的 record 值在表达式里不能取字段
- `ROWS FROM(...)` 多函数合并成一个表函数（单个函数的 `WITH ORDINALITY` 已支持）
- 按参数类型重载（同名函数不能并存，见第五节）、`ALTER PROCEDURE`/`ALTER ROUTINE`
- `ALTER FUNCTION ... SET SCHEMA`（换 schema）、`SET configuration_parameter FROM CURRENT`
- `DROP FUNCTION ... CASCADE/RESTRICT`
- `SET`/`RESET` 只落 DDL，不真正影响函数体执行时的会话参数（见2.8节）
- `LANGUAGE plpgsql` 等块语言与 `BEGIN ... END` 函数体、函数体里的 DML
- `LATERAL`：函数表实参不能引用同一 FROM 里其它表的列
- 多维数组参数（`INT[][]`）、`OUT` 参数是数组类型时的返回列类型推导（只按 `Value.ARRAY` 处理）

## 五、按参数类型重载：评估结论（未实现）

PostgreSQL 允许 `f(int)` 与 `f(varchar)` 并存，Lealone 目前做不到。评估改动面后**没有实现**，
卡点在“schema 对象以名字为唯一键”这一层，不在 DDL 或调用表达式的表面代码：

1. **对象存储层**：`Schema` 把每类 `SchemaObject` 放在
   `TransactionalDbObjects` 里，`add/copyOnAdd/remove/find` 全部用 `dbObject.getName()` 做 `CaseInsensitiveMap` 的 key
   （`TransactionalDbObjects.add`）。同名函数第二个 `put` 会**直接覆盖**第一个；
   更关键的是数据库启动回放 meta 记录时走的是同一条 `dbObjects.add(obj)` 路径，
   即便 DDL 阶段骗过去了，重启后也只剩一个重载。要干净支持重载，必须让
   FUNCTION_ALIAS 的 key 变成“名字+参数类型签名”，于是所有按名字的查找
   （`findFunction`/`getFunction`/`DROP FUNCTION`/`OR REPLACE`/`IF NOT EXISTS`/`SET COMMENT`/
   `GenScript`/`InfoMetaTable`/`Schema.removeSchemaObjects`）都要跟着改成按签名或返回候选集合。
2. **通用性影响**：`TransactionalDbObjects` 是所有 DbObjectType 共用的多版本容器，
   改 key 语义会波及 `CREATE ALIAS`、`DROP ALIAS`、`COMMENT ON ALIAS` 的既有行为；
   只在 FUNCTION_ALIAS 上做特例则需要新的按类型分支，代码会变得不干净。
3. **解析层**：`SQLParserBase.readJavaFunction` 是**先按名字取到 `FunctionAlias` 再解析实参**的，
   `CREATE ALIAS` 也走这条路。要按类型选重载，必须改成先解析实参、再做候选函数的重载消解，
   而 PostgreSQL 的消解规则（精确匹配 → 首选类型 → 隐式转换 → 歧义报错）还要给 `Expression`
   在 prepare 阶段计算出足够精确的静态类型，Lealone 这一层目前有不少 `Value.UNKNOWN`。
4. **DDL 层**：`CREATE FUNCTION` 的重复检测、`OR REPLACE` 的替换目标、`DROP FUNCTION name(types)`
   的定位都要按签名做，且类型文本要归一化（`INT`/`INTEGER`/`INT4` 视为同一类型）后才能比较。

粗估：即便只做一个“简化版消解规则”，也需要改上述四层、约 300~400 行并且动到通用对象存储，
风险会外溢到 `CREATE ALIAS` 与重启回放，不符合“300 行内干净实现”的前提，因此本次不做。
`PgFunctionParamTest.testOverloadByParameterType` 用 `@Ignore` 保留了 PostgreSQL 期望的行为，
`PgFunctionParamTest.testOverloadNotSupported` 断言了当前“同名函数不能并存”的现状。

## 六、已知限制 / 额外发现但没修的问题

1. 函数体的 SQL 语法/列名/常量返回类型现在建函数时就校验（数据库启动回放时跳过），
   但 setof/table 的结果列形状仍是调用时校验；函数体里也不能写 `;` 和 `?`。
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
10. 标量函数当表函数用（`SELECT * FROM f(1)`）已经支持，得到一行一列，列名用函数名
    （`CREATE ALIAS` 建的标量 Java 函数同样适用）。返回 NULL 时也有一行（值为 NULL），不是空集。
    只有 `RETURNS SETOF`/`RETURNS TABLE` 的函数返回类型才是 `Value.RESULT_SET`。
11. 单个 OUT/INOUT 参数等价于标量返回，函数体必须只返回一列：函数体返回多列时建函数就报 22018
    （调用路径也不会再静默取第一列）。
12. 多个 OUT 参数时 `SELECT f(1)` 得到 record（`(43, foo)`），`SELECT * FROM f(1)` 得到一行多列；
    但 record 值不能再参与运算或取字段，只能整体打印/比较。函数体返回 0 行时 `SELECT f(1)` 是 NULL，
    `SELECT * FROM f(1)` 是一行全 NULL（PostgreSQL 也返回 NULL 记录）。
13. `VARIADIC` 调用时传单个 NULL 会被当成“一个 NULL 元素”，PostgreSQL 会把它当成 NULL 数组。
14. `CREATE OR REPLACE FUNCTION` 的报错优先级和 PG 相反：Lealone 先校验函数体再看重名，
    所以“同名函数已存在 + 新函数体非法”时报的是函数体错误，PG 会先报重名。不影响正确性。
14. DDL 回滚后，同一个 session 里之前 prepare 过的 `SELECT f(...)` 可能命中缓存的执行计划，
    仍然返回旧结果（函数对象其实已经回滚掉了，新 session 调用会报找不到）。这是 DDL 与
    prepared statement 缓存之间的老问题，不在本次改动范围内。
15. `ALTER FUNCTION ... COST/ROWS/SET/LEAKPROOF/WINDOW` 全部只改 DDL，调用行为和优化器行为都不变；
    其中 `SET` 如果不生效，`SET search_path` 也就不会影响函数体里未限定表名的解析（仍按调用方 schema）。
16. `SECURITY DEFINER` 依赖 DDL 里的 `OWNER TO`：如果属主用户被删掉了，调用会报 `USER_NOT_FOUND_1`；
    属主的权限就是嵌套会话的权限，Lealone 没有函数级的 EXECUTE 权限，所以“谁能调用这个函数”
    不受属主控制。
17. `DROP/ALTER FUNCTION` 的签名比较只看类型（`Value` 类型 id），`varchar(10)` 与 `varchar`、
    `numeric(10,2)` 与 `numeric` 视为同一类型；比较的是**入参**个数与类型，参数模式关键字
    （`IN/OUT/INOUT/VARIADIC`）会被读掉但写出来的每个类型仍占一个位置，所以和 PostgreSQL 一样只写
    IN/INOUT 参数即可（`DROP FUNCTION f(OUT int)` 在只有 OUT 参数的函数上会报函数不存在）。
18. `ALTER FUNCTION` 一次只能带一个动作（和 PostgreSQL 一致），例如改易变性、改 `COST` 要分两条语句。
19. `COMMENT ON FUNCTION` 的注释按**函数名**存（和 `COMMENT ON ALIAS` 共用一份 key），
    不支持重载所以没有歧义；但函数被 `OR REPLACE` 重建后注释会保留（PostgreSQL 也是保留）。
20. `ALTER FUNCTION` 只支持 `SqlFunction`/`PlpgSqlFunction`；`CREATE ALIAS` 建的 Java 函数、
    `CREATE AGGREGATE` 建的对象都报 `FEATURE_NOT_SUPPORTED`。
