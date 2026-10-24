# DML `RETURNING`

`INSERT` / `UPDATE` / `DELETE` / `MERGE` 支持 PostgreSQL 风格的 `RETURNING` 子句，
返回语句**实际影响的行**。

## 一、支持的写法

```sql
INSERT INTO t(a, b) VALUES(1, 2) RETURNING a, b;
INSERT INTO t(a, b) VALUES(2, 3), (3, 4) RETURNING *;
INSERT INTO t(a, b) SELECT a, b FROM t2 RETURNING a, b;
UPDATE t SET b = 3 WHERE a = 1 RETURNING a, b;
UPDATE t AS x SET b = 3 WHERE x.a = 1 RETURNING x.a, x.b;
DELETE FROM t WHERE a = 1 RETURNING *;
MERGE INTO t(a, b) KEY(a) VALUES(1, 2) RETURNING a, b;
```

- 返回表达式里可以引用目标表的列、常量、函数和一般表达式，支持 `AS 别名`。
- `RETURNING *` 展开成目标表的全部列，列名就是表列名。
- `UPDATE` 返回更新后的行，`DELETE` 返回被删除的旧行，`INSERT` 返回插入后的行
  （包括 `DEFAULT`/序列产生的值）；`MERGE` 更新分支返回更新后的行，插入分支返回新行。
- 没有影响行时返回 0 行，但列元数据仍然存在。
- `RETURNING` 是语句的一部分：`UPDATE ... WHERE ... RETURNING ...`；
  `UPDATE`/`DELETE` 的 `LIMIT` 写在 `RETURNING` 之前（Lealone 扩展）。

作为独立语句执行时：

- `Statement.executeQuery(...)` / `PreparedStatement.executeQuery()` 返回结果集；
- `Statement.execute()` / `PreparedStatement.execute()` 返回 `true`，`getResultSet()` 能拿到行；
- `executeUpdate()` 仍按更新语句执行：返回影响行数，`RETURNING` 的行被丢弃；
- `Statement.getUpdateCount()` 在查询路径下是 `-1`，和普通查询一致。

没有 `RETURNING` 的 DML 行为完全不变（更新计数、事务、触发器、回滚语义都不变）。

## 二、实现结构

- `ManipulationStatement` 保存 `RETURNING` 表达式和 `RowColumnResolver`
  （一个只用一行数据做列解析的 `ColumnResolver`），`isQuery()` 在带 `RETURNING` 时返回 true。
- `INSERT`/`MERGE` 复用 `MerSert` 的写路径，`UPDATE`/`DELETE` 复用 `UpDel` 的写路径。
  `YieldableLoopUpdateBase.addReturningRow(...)` 在**存储层写成功之后**才计算并收集返回行，
  失败的事务不会产生返回行。
- 查询路径由 `ManipulationStatement.YieldableReturningQuery` 驱动：外层 yieldable 负责把结果集
  返回给客户端，内层还是原来的更新 yieldable，外层只是调用内层的 `run()` 并检查完成状态，
  **不阻塞调度线程**；`RETURNING` 表达式在写成功的回调里用当前行求值，没有额外 I/O。
- 返回行收集到 `LocalResult`：和 SELECT 一样受数据库 `MAX_MEMORY_ROWS` 限制，
  超过后自动写到临时文件，不会无限物化；`JDBC setMaxRows` 会通过 `LocalResult.setLimit` 截断。
- `MERGE` 的更新分支用内层 `UPDATE ... RETURNING` 查询拿返回行；这条同步路径和原来
  `MERGE` 调用 `update.update()` 的方式一致。
- `PL/pgSQL` 的同步查询入口沿用 `Query.query()` 同款的 `syncExecute`（PL/pgSQL 里的
  `SELECT` 本来就走这条路径），没有引入新的阻塞原语。

## 三、PL/pgSQL

```sql
-- RETURNING ... INTO 变量/record，支持 STRICT
INSERT INTO foo VALUES(5, 6) RETURNING * INTO rec;
INSERT INTO t(a) VALUES(1) RETURNING a INTO STRICT v;
UPDATE t SET b = 2 WHERE a = 1 RETURNING a, b INTO r;
DELETE FROM t WHERE a = 1 RETURNING * INTO r;

-- RETURN QUERY 里执行带 RETURNING 的 DML
RETURN QUERY INSERT INTO t(a) VALUES(1) RETURNING a;
RETURN QUERY UPDATE t SET b = 2 RETURNING a;
```

- `RETURNING ... INTO` 的 `INTO` 不属于 SQL，由 `PlpgSqlSqlExecutor.splitReturningInto()` 在执行前拆掉；
  0 行时 record 变成未赋值状态（字段读出来是 NULL），单值变量置 NULL，`FOUND = false`；
  `STRICT` 时 0 行报 `no_data_found`、多行报 `too_many_rows`。
- 不带 `INTO` 的 `INSERT ... RETURNING` 在 PL/pgSQL 里也允许，结果被丢弃，`ROW_COUNT`/`FOUND` 按返回行数设置。

## 四、SQL 函数体

`LANGUAGE sql` 的函数体除了查询，还允许**带 `RETURNING` 的 DML**：

```sql
CREATE FUNCTION ins(x INT) RETURNS INT AS
$$ INSERT INTO t(a) VALUES(x) RETURNING a $$;

CREATE FUNCTION ins_all(x INT) RETURNS TABLE(a INT, b INT) AS
$$ INSERT INTO t(a, b) VALUES(x, x) RETURNING * $$;
```

建函数时仍然只 `prepare` 一次（不执行）做校验，语法/列名错误在 `CREATE` 时报出；
不带 `RETURNING` 的 DML 函数体仍会报 `FEATURE_NOT_SUPPORTED`。
数据库启动回放 `sys(meta)` 时跳过校验（表还没建出来），和 PostgreSQL 的
`check_function_bodies=off` 一致。

## 五、不支持 / 已知限制

- **`OLD` / `NEW`**：不支持 `RETURNING old.x` / `RETURNING new.x`，只支持表列和普通表达式。
- **sharding 模式**：返回行由本地更新执行器收集，sharding 模式下的 `createYieldableUpdate`
  不是 `YieldableLoopUpdateBase`，会报 `FEATURE_NOT_SUPPORTED: RETURNING in this execution mode`。
- **INSTEAD OF 触发器**：触发器接管写入（`done = true`）时不产生返回行。
- **函数体里的不带 `RETURNING` 的 DML**：仍不支持（只有 `RETURNS VOID` 的 PG 写法需要，未做）。
- 函数体里的 DML 走嵌套连接执行，临时表的 `SERIAL` sequence 在嵌套 session 里找不到，
  这是既有引擎限制（PG 回归用例已改用普通表）。
