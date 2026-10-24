# 存储过程（实验特性）

本分支（`feature/stored-procedure`）给 Lealone 加了脚本式存储过程。
核心功能可用并已通过回归，但有一批明确的限制（见第四节），**暂不建议合并到 master**。

## 一、语法

```sql
CREATE PROCEDURE [IF NOT EXISTS] [schema.]name(p1 INT, p2 VARCHAR, ...) AS 'SQL1; SQL2; ...';
CALL name(实参...);
DROP PROCEDURE [IF EXISTS] [schema.]name;
```

过程体是用分号分隔的 SQL 语句串，用 `?` 按出现顺序引用参数：

```sql
CREATE TABLE t(id int primary key, v int);
CREATE PROCEDURE sp_ins(pk int, val int) AS 'INSERT INTO t VALUES(?, ?)';
CALL sp_ins(1, 10);
```

## 二、语义

- **原子性**：整个过程是一条语句粒度的原子单元。过程体里任何一条语句失败，整个过程回滚。
- **事务**：过程体里禁止 `COMMIT` / `ROLLBACK` / `SAVEPOINT` 等事务语句，违反时抛 `FEATURE_NOT_SUPPORTED`。
- **返回**：过程体最后一条是查询时返回该结果集；否则返回各更新语句的更新数之和。
- **不是函数**：过程不能出现在表达式里，`SELECT sp_ins(1, 2)` 报 `FUNCTION_NOT_FOUND_1`。
- **持久化**：过程体随 schema 元数据一起持久化，重开库后仍可用。

## 三、实测行为

以下都是在本分支上实际执行得到的结论：

| 场景 | 结果 |
| --- | --- |
| 多语句过程体 | `CALL` 返回更新数 2，两行都写入 |
| 带参过程 | 参数按位置绑定，写入对调用方可见（自动提交模式下） |
| 过程体是查询 | `CALL` 返回结果集；`Statement.execute()` 返回 true，`getResultSet()` 能拿到行 |
| 只有更新语句的过程 | `Statement.execute()` 返回 false，`getUpdateCount()` 是累计更新数 |
| 过程体里嵌套 `CALL` | 支持，内外层语句各自独立绑定 `?` 参数 |
| 过程体第二条语句违反主键 | 抛 `DUPLICATE_KEY_1`，且**第一条语句的插入已回滚** |
| 过程体里写 `COMMIT` | 抛 `FEATURE_NOT_SUPPORTED: transaction statement in procedure` |
| `SELECT` 里调用过程 | 抛 `FUNCTION_NOT_FOUND_1` |
| 重名创建 | 抛 `PROCEDURE_ALREADY_EXISTS_1` |
| `DROP PROCEDURE` / `DROP PROCEDURE IF EXISTS` | 正常；删除后 `CALL` 抛 `PROCEDURE_NOT_FOUND_1` |
| 关闭重开库后 `CALL` 同一过程 | 仍可用 |
| 手动事务里 `CALL`，提交前从调用方查询 | **看不到**过程写入（0 行）；提交后可见（1 行） |

## 四、已知限制

1. **不是完整的 PL**：没有 `BEGIN ... END` 块、变量声明、`IF`/`WHILE`、游标、异常处理。过程体只能是扁平的 SQL 语句串。
2. **参数**：只有位置参数（`?`），没有 `OUT` / `INOUT`；**参数个数不匹配只在执行期报错**（`Parameter "#1" is not set`），建过程时不校验。
3. **手动事务中的可见性**：过程跑在嵌套 session 里，未提交前调用方 session 看不到过程的写入。
4. **参数绑定按语句独立**：过程体里每条语句的第 i 个 `?` 对应第 i 个实参，多条语句不共享参数下标
   （嵌套 `CALL` 已支持，见第三节）。
5. **不支持**：`RENAME PROCEDURE`、`INFORMATION_SCHEMA` 里的过程元数据视图、`OUT` 参数。
6. **未验证**：集群 / 复制模式下过程的行为；`statement` 级复制的确定性校验。
7. 过程体按字面切分后逐条 `prepareStatement`，每条语句都会走一次会话级语句缓存。

## 五、实现结构

新增 5 个文件、修改 8 个已有文件（共 4 个模块）：

| 文件 | 职责 |
| --- | --- |
| `lealone-db/.../db/schema/SqlProcedure.java` | schema 对象：过程体文本 + 参数列 + `getCreateSQL/getDropSQL` |
| `lealone-sql/.../sql/ddl/CreateProcedure.java` | `CREATE PROCEDURE`（加锁、重名校验、`schema.add`） |
| `lealone-sql/.../sql/ddl/DropProcedure.java` | `DROP PROCEDURE` |
| `lealone-sql/.../sql/dml/CallProcedure.java` | `CALL p(...)` 语句 |
| `lealone-sql/.../sql/executor/YieldableCallProcedure.java` | 执行器：逐条驱动过程体语句 |

`CALL` 是按查询语句还是更新语句执行，由 `CallProcedure.isQuery()` 判断：
用 tokenizer 扫一遍过程体（只切词、不解析语句，避免 WITH 在 prepare 阶段建临时视图），
遇到 `SELECT/VALUES/TABLE/WITH` 开头的语句就按查询执行，否则按更新执行。

改动点：`DbObjectType` 新增 `PROCEDURE` 枚举值（追加在末尾，值写进 SYS 表）、`Schema` 新增查找、
`ErrorCode` 与消息文件各加 2 条、`SQLStatement` 新增语句类型常量、`SQLParserBase` 新增两个解析方法、
`LealoneSQLParser` 覆盖 `parseCall()`。

**解析器主干零改动**：`SQLParserBase.parseCall()`（H2 血统）没有被修改，`CALL` 的过程名探测放在
子类的 `parseCall()` 覆盖里，过程名不存在时回退到原来的 `CALL <表达式>`。

## 六、为什么用嵌套 session

另一条更“干净”的路线是：不动 session，直接在调用者 session 上跑内层 yieldable，
这样手动事务里的可见性问题（限制 3）会自然消失。但实测行不通：

- 子 yieldable 没有注册到 session 时，一次 `run()` 后状态停在 `STATEMENT_RUNNING`；
- 而 `getYieldableCommand()` 对 `STATEMENT_RUNNING` 直接返回 null，调度器不会再来驱动它；
- 异步写完成的回调只能把状态改成 `COMPLETED`，却没有人再去调 `run()` → `stop()`，链条静默停死。

嵌套 session 方案每级都注册成各自 session 的当前命令，被调度器公平对待，
任何一条语句等锁或让出都不会阻塞调度线程。

## 七、回归

在本分支上实测：`ScriptRoundTripTest`、`ExplainTest`、`SelectTest`、`ViewTest`、
`DdlCascadeTest`、`SequenceTest`、`CteTest`、`SqlScriptTest`、`JoinUsingTest`、
`DerivedColumnAliasTest`、`InsertTest`、`UpdateTest`、`DeleteTest`、`TruncateTableTest`
共 38 个用例全绿；新增的 `StoredProcedureTest` 全绿。

跑测试前记得 `rm -rf lealone-test/target/test-data`。
