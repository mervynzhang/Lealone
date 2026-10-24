# PostgreSQL 风格的存储函数（SQL 函数体）

本批改动给 Lealone 增加了 `CREATE FUNCTION` / `DROP FUNCTION`，函数体（body）只能是一条 SQL 查询语句，
参数在函数体里按名字引用。实现落在 `SqlFunction`（`FunctionAlias` 的子类）上，schema 注册/查找、
`JavaFunction` 表达式、错误码、`getCreateSQL` 持久化全部复用既有的 `FunctionAlias` 机制。
函数可以返回单个值，也可以用 `RETURNS SETOF` / `RETURNS TABLE` 返回多行结果，后者可以在 `FROM`
子句里当表函数用。

本文档已包含参数机制：`OUT`/`INOUT` 参数、`STRICT`、`VARIADIC`、调用期类型转换，
以及“按参数类型重载”的实现与消解规则（见第五节）。

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
    AS $$ body $$                       -- 经典写法: 函数体是一段SQL(可以有多条语句)

-- SQL标准的函数体有三种写法(不需要引号，直接跟在属性后面)
CREATE FUNCTION f(a INT) RETURNS INT RETURN a + 1;
CREATE FUNCTION f(a INT) RETURNS INT RETURN QUERY SELECT a;
CREATE FUNCTION f(a INT) RETURNS INT BEGIN RETURN a + 1; END;          -- 标准块体
CREATE FUNCTION f(a INT) RETURNS INT BEGIN ATOMIC RETURN a + 1; END;   -- 带ATOMIC的块体

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

- 函数体既能用美元引用 `AS $$ ... $$`，也能用单引号 `AS '...'`，还可以不写 `AS` 直接用
  SQL标准的 `RETURN <表达式>` / `BEGIN [ATOMIC] ... END`（见下面第二节第 1、1.1 条）。
- `LANGUAGE SQL` 可选，写其它语言（如 `plpgsql`）直接报 `FEATURE_NOT_SUPPORTED`。
- `LANGUAGE` 子句与易变性关键字（`IMMUTABLE`/`STABLE`/`VOLATILE`）都是可选的，且顺序任意，但都必须写在
  `AS` 之前。
- 参数模式：`IN`（默认）、`OUT`、`INOUT`、`VARIADIC`；数组类型可以写 `type[]` 或 `type ARRAY`。
- 参数可以只写类型不写名字（任何模式都可以，如 `CREATE FUNCTION f(INT, TEXT)`），
  函数体里用 `$1`/`$2` 按位置引用入参；`$n` 指的是第 n 个**入参**（IN/INOUT，OUT 不参与编号），
  `$1[1]::int` 这种写法也支持。引用不存在的位置参数（如只有 1 个入参却写 `$2`）在 CREATE 阶段报错。
- 入参可以带 `DEFAULT <常量表达式>`：省略的实参用默认值补齐；和 PostgreSQL 一样，有默认值的入参
  必须集中在末尾（否则报语法错），`OUT`/`VARIADIC` 参数不能有默认值。
- 调用时可以用命名实参：`SELECT f(b => 2, a => 1)`（`: =` 也接受），位置实参必须写在命名实参前面，
  同一个参数不能给两次；命名实参和默认值可以混用（`f(a => 1)` 里省略的 `b` 用默认值）。
- `DROP FUNCTION` 后面可选的 `(type, ...)` 用来定位具体是哪个重载（见第五节）；不写时要求这个名字只有一个重载。
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

1. **函数体是一条查询、带 `RETURNING` 的 DML，或者 DDL/utility 语句**：
   `SELECT` / `VALUES` / `TABLE` / `WITH ... SELECT` / `(SELECT ...)`，
   `INSERT` / `UPDATE` / `DELETE` / `MERGE ... RETURNING ...`（见 `RETURNING.md`），
   以及 `CREATE` / `ALTER` / `DROP` / `TRUNCATE` / `SET` / `CALL` 这类语句（函数体里做 DDL，见第 1.2 条）。
   建函数时就校验：单语句函数体的第一条 token 必须是上面这些关键字之一，函数体里出现 `;` 或 `?`
   会直接拒绝（防止一次执行多条语句、以及用户写的 `?` 和参数名替换出来的 `?` 混在一起）。
   不带 `RETURNING` 的 DML 函数体在建函数时报 `FEATURE_NOT_SUPPORTED`，除非是 `RETURNS VOID`。
   建函数时还会在一个嵌套 session 里 prepare 一次函数体（**不执行**）：语法错误、列/表不存在、
   常量返回值类型不匹配都在 `CREATE` 时报出；只有 setof/table/多个 OUT 参数的结果列形状留到调用时校验。
   函数体里有 DDL 时，函数体引用的表可能是它自己刚建出来的，这种"表不存在"(42102)会推迟到调用时校验
   （相当于 PG 的 `check_function_bodies=off`，Lealone 没有这个开关，是按函数体里有没有 DDL 自动判断的）。
   数据库启动回放 `sys(meta)` 时会跳过这个校验（此时函数体引用的表可能还没建出来）。

   1.1 **SQL标准的函数体（不带 `AS`）**：
   - `RETURN <表达式>`：整个函数体，等价于 `AS 'SELECT <表达式>'`。表达式里可以用参数名、子查询。
   - `RETURN QUERY <查询>`：查询本身就是函数体，适合 `RETURNS TABLE` / `RETURNS SETOF`。
   - `BEGIN [ATOMIC] ... END`：标准块体（`ATOMIC` 可省略），里面可以有多条语句，也允许空语句 `;;`；
     最后一条语句就是返回值，所以 `RETURN <表达式>` / `RETURN QUERY <查询>` 可以写在块体里，
     也可以直接写查询（等价于 `BEGIN ATOMIC SELECT ...; END`）。
   - SCRIPT 里 `RETURN <表达式>` 会被规范化成 `AS 'SELECT <表达式>'`，块体形式还是 `BEGIN ATOMIC ...; END`，
     回放结果完全等价。

   1.2 **函数体里做 DDL**：`AS $$ ... $$` 里可以写多条语句，按顺序执行，最后一条查询的结果就是返回值
   （`RETURNS VOID` 时最后一条可以是 DDL/DML，返回值是 `NULL`）。`BEGIN ATOMIC` 块体同理。
   DDL 在**嵌套连接**（`createNestedConnection`）上执行，所以函数体里的 DDL 和调用方的语句在同一个事务里，
   调用方回滚时 DDL 一起回滚。注意：
   - `CREATE TABLE`/`DROP TABLE` 这类语句本身没有问题，但**同一个函数被调用两次**时，
     第二次再执行 `CREATE TABLE` 会报"表已存在"，这是调用方的责任（PG 也一样）。
   - 函数体里的 DDL 没有 DDL 级别的校验（Lealone 的 DDL 在 prepare 阶段就能算出结果，不会留到执行时才炸），
     但**引用不到的表**推迟到调用时校验。
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
19. **`DEFAULT` 参数值**：只支持**常量表达式**（数字/字符串/布尔/NULL/常量运算），CREATE 时就按参数声明的
    类型转换好存下来，转换失败报 `DATA_CONVERSION_ERROR_1`；非常量（如 `DEFAULT RANDOM()`）报
    `FEATURE_NOT_SUPPORTED_1`。默认值写进 `getCreateSQL()`（`... b INT DEFAULT 10 ...`），
    SCRIPT/重启能原样回放。数组参数暂不支持默认值。
    **重载消解**：有默认值时实参个数是一个区间（`[必须提供的入参个数, 入参总数]`），
    匹配程度相同时优先选**不需要用默认值补齐**的那个重载（`f(int)` 与 `f(int,int DEFAULT 10)` 调用 `f(1)`
    选前者）；实参个数超出区间才算不匹配（`FUNCTION_NOT_FOUND_1`）。
20. **命名实参**：解析成 `(名字, 表达式)` 列表，消解重载时按每个候选函数的参数名重排成位置实参
    （按候选分别打分，名字对不上/重复/位置实参写在命名实参后面的候选直接不匹配），
    选中之后把实参按声明顺序重排，缺失的入参用默认值补成 `ValueExpression` 再执行。
    `getSQL()` 里保留 `B => 2` 这种写法，所以 `CREATE VIEW` 的视图定义回放时位置不会错。

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
| `DROP FUNCTION name(type, ...)` | 完整 | 参数类型用来在重载里定位函数：类型/个数对不上的重载不会被删，找不到时报 `FUNCTION_NOT_FOUND_1`（90022），`IF EXISTS` 时不报错。只比较类型，忽略 `varchar(10)` 这类 typmod；不写类型列表时，名字只有一个重载就删它，有多个重载报 `AMBIGUOUS_FUNCTION_NAME_1`（90152，对应 PG 的 42725） |
| `COMMENT ON FUNCTION name[(type)] IS '...'` | 完整 | 复用既有的 COMMENT 机制（`COMMENT ON ALIAS` 也能读写同一份注释），持久化成 `COMMENT ON ALIAS ...` |

`ALTER FUNCTION` 的对象是 `SqlFunction`（含 PL/pgSQL 函数）；对 `CREATE ALIAS` 建的 Java 函数报
`FEATURE_NOT_SUPPORTED`。`ALTER FUNCTION ... SET SCHEMA` 没有实现（要移动对象），也报不支持。

## 三、实现要点

- **函数体解析**（`SQLParserBase`）：
  - 语法入口 `parseCreateSqlFunction()`（约 5400 行），函数体分三种：`RETURN` 形式走 `parseReturnBody()`、
    块体走 `parseAtomicBody()`、`AS` 形式走 `readString()`。
  - `parseReturnBody()`：`RETURN <表达式>` 直接拼成 `SELECT <表达式>`；`RETURN QUERY <查询>` 只取查询原文。
  - `parseAtomicBody()`：**只按原文切分**（`;` 分隔，`BEGIN ... END`/`CASE ... END` 记嵌套层数），
    不做语义解析——这样函数体/过程体里的表可以是函数体自己刚建出来的；
    `RETURN <表达式>` / `RETURN QUERY <查询>` 在这里被改写成 `SELECT <表达式>` / 查询本身。
  - 多条语句的 `AS $$ ... $$` 函数体走 `LealoneSQLParser.splitStatementsRaw()` 切分，然后按 `BEGIN ATOMIC`
    同样的语句列表执行（`SqlFunction.setAtomicBody()`）。
- **建函数时校验**（`CreateSqlFunction.validateSqlBody()`）：语句列表逐条 `prepareStatement()`（不执行），
  函数体里有 DDL 时容忍 42102（表还没建出来）；最后一条是查询才有结果列校验。
- **建过程时校验**（`CreateProcedure.validateBody()`）：过程体语句逐条 prepare，容忍 42102，其它错误直接报。
- **执行**（`SqlFunction.executeFunction()`/`executeAtomicBody()`）：语句列表在嵌套 JDBC 连接上顺序执行，
  最后一条按需 `executeQuery()` 取返回值。

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
- `ROWS FROM(...)` 多函数合并成一个表函数（单个函数的 `WITH ORDINALITY` 已支持）
- 自定义复合类型（`CREATE TYPE ... AS (...)`）、`%ROWTYPE` 数组、整行 `record` 当标量
- `ALTER PROCEDURE`/`ALTER ROUTINE`（存储过程重载已支持，见 `docs/PROCEDURE.md`）
- `COMMENT ON FUNCTION name(type)` 仍按函数名存（多个重载共用一份注释）
- PG 的 preferred-type 平局再选；`CREATE FUNCTION f(int)`（参数只写类型不写名）
- `pg_catalog` 兼容 schema / 与 PG 同名的 `pg_proc` 对象（只提供 `INFORMATION_SCHEMA.PG_PROC`）
- `ALTER FUNCTION ... SET SCHEMA`（换 schema）、`SET configuration_parameter FROM CURRENT`
- `DROP FUNCTION ... CASCADE/RESTRICT`
- `SET`/`RESET` 只落 DDL，不真正影响函数体执行时的会话参数（见2.8节）
- 其它块语言；函数体里**不带 `RETURNING` 的 DML**（带 `RETURNING` 的 DML 已支持，见 `RETURNING.md`）
- `LATERAL`：函数表实参不能引用同一 FROM 里其它表的列
- 多维数组参数（`INT[][]`）、`OUT` 参数是数组类型时的返回列类型推导（只按 `Value.ARRAY` 处理）

## 五、按参数类型重载（已实现）

PostgreSQL 允许 `f(int)` 与 `f(varchar)` 并存并按实参类型选一个，Lealone 从这一轮开始也支持，
作用域限定在 `SqlFunction`（PostgreSQL 风格的 `CREATE FUNCTION`）；`CREATE ALIAS` 建的 Java 函数
仍然同名唯一、调用时按声明类型宽松转换，行为不变。

```sql
CREATE FUNCTION f(a INT) RETURNS INT AS $$ SELECT a + 1 $$;
CREATE FUNCTION f(a VARCHAR) RETURNS VARCHAR AS $$ SELECT a || '!' $$;
SELECT f(1);            -- 2
SELECT f('x');          -- x!
DROP FUNCTION f(INT);   -- 只删int版本
SELECT f(1);            -- 报错 90022: int不能隐式转成varchar，找不到 f(int)
SELECT f('x');          -- x!
```

存储过程（`SqlProcedure`）复用同一套存储方案和消解规则：schema key 也带 `名字(入参类型id...)`，
`Schema.findProcedures()` 返回候选集合，`SqlProcedure.getMatchScore()` 直接调用
`SqlFunction.getTypeMatch()`。区别是过程没有 variadic、允许按全部参数（含 OUT 占位）匹配，
`DROP PROCEDURE name(类型/模式)` 按完整参数列表匹配，详见 `docs/PROCEDURE.md`。

### 5.1 存储方案：FUNCTION_ALIAS 的 key 带上参数签名

`Schema` 里每类对象都放在 `TransactionalDbObjects` 里，key 原来是 `obj.getName()`。现在
`Schema.getDbObjectKey()` 对 `SqlFunction`/`SqlProcedure` 返回 `名字(参数类型id,...)`
（函数的数组参数还带上元素类型），其它对象类型原样返回名字，因此：

* `CREATE ALIAS`、`TABLE`、`INDEX` 等共用同一套容器的对象类型**完全不受影响**；
* `TransactionalDbObjects` 只是新增了“按key存/取”和 `findAll(session)`（同一套多版本可见性规则），
  原有按名字的方法都转调新方法，语义不变；
* 数据库启动回放 meta 记录时走的是同一条 `Schema.add` 路径，所以**重启后所有重载都在**；
* `SCRIPT` 遍历 `getAllSchemaObjects(FUNCTION_ALIAS)` 拿到的是每个重载各一条，`SqlFunction.getDropSQL()`
  会写成 `DROP FUNCTION IF EXISTS f(INT)`，回放时不会因为重载歧义而失败；
* `findFunction(name)` 保持“按名字返回一个”，新增 `findFunctions(name)` 返回按 id 排序的候选集合，
  `DROP/ALTER/CREATE OR REPLACE/IF NOT EXISTS/COMMENT` 用签名在候选集合里定位。

`INFORMATION_SCHEMA.FUNCTION_ALIASES` 里每个重载一行（`PARAMETER_TYPES`/`PARAMETER_TYPE_IDS` 不同）。

### 5.2 消解规则

顺序（数字越小越优先）：

1. **完全匹配**：实参类型 id 和形参类型 id 相同 → 0；
2. **隐式转换**：PostgreSQL `pg_cast.castcontext='implicit'` 的那部分 → 1；
3. **没有类型的实参**（`NULL`、字符串字面量，对应 PG 的 unknown 字面量）→ 字符串类型形参记 1、
   其它类型形参记 2（PG 规则 4.f：优先选字符串类别）；
4. 其余一律不匹配（`MATCH_NONE`）。

总权重最小的候选胜出；有多个候选并列最小且无法区分时，和 PG 一样报
`AMBIGUOUS_FUNCTION_NAME_1`（90152，SQLState 42725）。只有一个候选时也做同样的检查，
类型不兼容就报 `FUNCTION_NOT_FOUND_1`（90022，消息里带实际签名，如 `F(INTEGER)`）。

隐式转换矩阵（`SqlFunction.isImplicitConvertible`）：

| 从 | 到 | 说明 |
| --- | --- | --- |
| 同类型 id | 同类型 id | 完全匹配 |
| TINYINT → SMALLINT/INT/BIGINT/DECIMAL/REAL/DOUBLE | 加宽 | 整数只往更宽的方向转 |
| SMALLINT → INT/BIGINT/DECIMAL/REAL/DOUBLE | 加宽 | |
| INT → BIGINT/DECIMAL/REAL/DOUBLE | 加宽 | |
| BIGINT → DECIMAL/REAL/DOUBLE | 加宽 | |
| REAL → DOUBLE | 加宽 | |
| DECIMAL → FLOAT/DOUBLE/整数 | 特殊 | DECIMAL 在 Lealone 里兼作“数值表达式类型还没推导出来”的默认类型（函数体里的参数占位符参与运算时就是 DECIMAL），所以放开 DECIMAL 往其它数值类型的转换，否则嵌套调用会被误判成函数不存在 |
| CHAR/VARCHAR/TEXT 之间 | 互相 | 字符串类型之间视为隐式 |
| DATE → TIMESTAMP | 单向 | 反过来不行 |
| `NULL` / 字符串字面量 → 任意类型 | 无类型实参 | 运行期按形参类型转换，转换失败报 22018 |
| `int → varchar`、`varchar → int`、`boolean → int`、`double → decimal`、`timestamp → date` 等 | 不允许 | 必须显式 `CAST`，否则报 90022 |

已知与 PostgreSQL 的差异：

* 数值族“加宽”的判断是**类型 id** 级别的，不区分 `INT` 和 `INTEGER`（同一个 id），
  也不考虑 `NUMERIC(10,2)` 和 `NUMERIC(20,4)` 的差异（只看 `Value.DECIMAL`）；
* PG 在“多个候选都是隐式转换”时还会用 preferred type（`float8`/`text`/`timestamptz`）再选一次，
  Lealone 没有这一层，直接报歧义（例如 `f(bigint)` 与 `f(double)` 被 `SMALLINT` 实参调用）；
* 无类型实参只认 `NULL` 和**字符串字面量**；`CAST('5' AS VARCHAR)` 这种显式转过的常量在解析期
  已经是 `ValueExpression`，也会被当成无类型（PG 里它是 text，需要隐式/显式转换才行）；
* `CREATE FUNCTION f(int)` 这种“参数只写类型不写名字”的写法还不支持（参数名必填），
  和重载无关，是词法层的老限制；
* PostgreSQL 的 `text` 在 Lealone 里映射成 `CLOB`，匹配时把 `CLOB` 和字符串类型
  (`VARCHAR/CHAR/...)` 当成同一类（可以互相隐式转换），所以 `text` 参数能接受字符串字面量/表达式。

### 5.3 相关文件

| 文件 | 作用 |
| --- | --- |
| `Schema.getDbObjectKey/findFunctions/findFunction` | 按签名存放、按名字取候选集合 |
| `SqlFunction.getOverloadKey/getMatchScore/isImplicitConvertible` | 签名、匹配权重、隐式转换矩阵 |
| `JavaFunction.resolveOverload` | 按实参静态类型选重载（解析期定不下来就等到 `optimize`） |
| `CreateSqlFunction/DropFunctionAlias/AlterSqlFunction` | 按签名创建/替换/删除/修改 |
| `FunctionOverloadTest` | 重载、DROP、OR REPLACE、SCRIPT 往返、重启、NULL/无类型实参、隐式转换矩阵 |
| `SqlProcedure.getMatchScore/matchesDropSignature` | 存储过程复用同一套匹配规则；DROP 按完整参数列表+IN/OUT模式匹配 |
| `ProcedureOverloadTest` | 过程重载、DROP 签名/模式、IF NOT EXISTS、SCRIPT 往返、重启 |

## 六、函数目录（PostgreSQL 兼容）

Lealone 提供只读的内置元数据表 `INFORMATION_SCHEMA.PG_PROC`（不是 PG 的 `pg_catalog.pg_proc`，
查询时要带 `INFORMATION_SCHEMA.` 前缀），每个 `SqlFunction` 一行，`CREATE ALIAS`/`CREATE AGGREGATE`
建的对象不在里面：

| 列 | 含义 |
| --- | --- |
| `PRONAME` | 函数名（按数据库的大小写设置转成大写/小写） |
| `PRORETTYPE` | 返回值类型文本，和 `CREATE FUNCTION` 里写的一致 |
| `PROARGTYPES` | 入参类型文本，格式为 `{INT,VARCHAR}`，数组参数带 `[]`，无参数是 `{}` |
| `PROVOLATILE` | `i`/`s`/`v`，对应 IMMUTABLE/STABLE/VOLATILE |
| `PROISSTRICT` | 是否 STRICT（RETURNS NULL ON NULL INPUT） |
| `PROSECDEF` | 是否 SECURITY DEFINER |
| `PROLEAKPROOF` | 是否 LEAKPROOF |
| `PROSRC` | 函数体原文 |

这些列直接读当前对象状态，`ALTER FUNCTION` 之后马上变化；表本身是 `InfoMetaTable` 生成的
只读伪表，数据库启动时自动创建，不参与 `SCRIPT`/备份（和 `INFORMATION_SCHEMA` 其它表一样），
所以不需要额外的持久化处理，重启后行由元数据重建。

另外还提供三张 SQL 标准的只读视图（同一套 `InfoMetaTable` 机制，自动创建、不进 SCRIPT/备份）：

| 视图 | 内容 |
| --- | --- |
| `INFORMATION_SCHEMA.PARAMETERS` | 每个函数/过程的每个参数一行，包含 `SPECIFIC_NAME`、`ORDINAL_POSITION`（1开始，含OUT）、`PARAMETER_MODE`(IN/OUT/INOUT)、`PARAMETER_NAME`、`DATA_TYPE`、`PARAMETER_DEFAULT` 等列 |
| `INFORMATION_SCHEMA.ROUTINES` | 每个函数/过程一行，包含 `ROUTINE_NAME`、`ROUTINE_TYPE`(FUNCTION/PROCEDURE)、`SPECIFIC_NAME`(名字_id，用来区分重载)、`DATA_TYPE`、`IS_DETERMINISTIC`(YES/NO)、`SECURITY_TYPE`(DEFINER/INVOKER)、`EXTERNAL_LANGUAGE` 等列 |
| `INFORMATION_SCHEMA.ROUTINE_ROUTINE_USAGE` | 列齐全（`SPECIFIC_NAME`/`ROUTINE_NAME` 等），但**依赖关系还没有填充，查询返回空表** |

这三张视图只列 `SqlFunction`/`SqlProcedure`（`CREATE ALIAS`/`CREATE AGGREGATE` 不在里面）；
`SPECIFIC_NAME` 的格式是 `名字_id`，同名重载靠它区分，PARAMETERS 和 ROUTINES 可以按
`SPECIFIC_SCHEMA + SPECIFIC_NAME` 关联。

已知缺口：DEFAULT 参数由另一条线实现，现在 `PARAMETER_DEFAULT` 一律是 NULL；
`ROUTINE_ROUTINE_USAGE` 需要分析方法体/默认值里的引用，暂时没有填充；
`ROUTINE_SEQUENCE_USAGE`/`ROUTINE_COLUMN_USAGE`/`ROUTINE_TABLE_USAGE` 没有做。
`information_schema` 的 PG 用例已经用这些视图部分适配执行，见 `docs/PG_REGRESS.md` 第六节。

内置函数 `pg_get_functiondef(函数名)` 返回该函数的 `getCreateSQL()` 文本（`CREATE FUNCTION ...`，
不是 PG 的 `CREATE OR REPLACE FUNCTION`，也没有 schema 前缀）。它只接受函数名，没有 regproc/oid
参数；同名有多个重载时报 90152，找不到报 90077。该函数标记为非确定性函数，避免在 prepare 阶段
把结果当成常量缓存起来。

PG 回归里原来查 `pg_proc`/`pg_get_functiondef` 的 5 个用例已经改成等价的 Lealone 目录查询并执行，
见 `docs/PG_REGRESS.md`。

## 七、已知限制 / 额外发现但没修的问题

- **`CREATE TEMP SEQUENCE` + `DISCARD TEMP` 不支持**：PG 回归用例 `function_sql.sql_body_tempseq`
  依赖这两个语句（以及"DISCARD TEMP 连带删除依赖临时对象建立起来的函数"），本轮只支持
  `RETURN nextval('...')` 这种函数体写法本身，临时序列/临时对象依赖还是缺口，用例继续 skip。
- **函数体里执行多条语句必须是 DDL/DML 混合或最后一条是查询**：`RETURNS INT` 的函数体以 DDL 结尾
  （没有查询、也没有 `RETURN`）仍然报 `FEATURE_NOT_SUPPORTED`。

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
