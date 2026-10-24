# 修复台账

本文件是**持续更新**的修复记录：复审 `review/mervyn-consolidated` 的 88 个提交后确认的问题，
以及后续每个修复提交"解决什么问题、怎么验证"。

约定（与提交规范一致）：

1. 一个提交只解决一个问题，**代码和它的测试在同一个提交里**；
2. 提交信息第一行说明解决了什么问题，正文写清 问题 / 根因 / 改动 / 测试；
3. 每个修复提交同时在本表追加一行，因此本表的顺序与提交顺序一致；
4. 回退某个修复时，在"状态"列标注，不要删行。

## 一、已修复

| # | 问题 | 修复要点 | 验证 | 状态 |
|---|---|---|---|---|
| 1 | 关联子查询只在最内层与外层关联时，中间层子查询结果被跨外层行复用，返回上一行的过期值（静默错数据） | `CorrelatedVisitor` 展开嵌套子查询，并把嵌套查询自己的 `TableFilter` 并入"自己的列"集合 | 新增 `CorrelatedSubqueryTest` 4 例；回退本改动后正好两条嵌套用例变红（`expected:<2> but was:<1>`），两条对照仍绿 | 已修复 |
| 2 | 异步提交回调在事务已结束时会丢失（语句挂死），重复提交时会重复执行（客户端收到两次响应） | `undoLog == null` 时补调 `asyncCommitComplete()`；`asyncCommitComplete()` 先取走 `asyncTask` 再置空，保证恰好一次 | 新增 `AsyncCommitCallbackTest` 2 例；去掉补调 → 30s 超时"没有回调"；去掉取走置空 → `expected:<1> but was:<2>` | 已修复 |
| 15 | 加密文件/损坏文件的块长度越界时**静默**当 EOF，LOB 读损坏数据会被悄悄截断且事后无法排查 | 保留"按 EOF 处理"的契约（`ScriptBase.verifyMagic` 已有明确的加密错误），但补一条 warn 日志带上块长度/可用长度/文件名 | 新增 `FileStorageTest.testChunkLengthExceedsFileSize`（长度为正但超出文件范围，此前只覆盖 `Integer.MAX_VALUE` 极端值）；`FileStorageTest` 5/5 通过 | 已修复（改为可见） |
| 14 | `SequenceLockedExceptionTest` 的 worker 抛异常后不 `countDown()`，主线程 `latch.await()` 永久挂死（surefire 也无超时），会把 CI 卡死 | worker 用 `finally { latch.countDown(); }` 并把异常存起来；主线程 `await(30s)` + 重新抛出；加 `@Test(timeout=60000)`；`lock_timeout` 从 1000000 降到 10000；补 conn/stmt 关闭 | 注入一个 worker 失败（改成查不存在的表）后：**0.2 秒内明确失败**（`worker 线程执行失败: Table "NOT_EXIST_TABLE" not found`）而不是挂死；正常路径连跑 2 次通过 | 已修复 |
| 13 | 列存表把 `columnIndexes == null` 当成"一列都不加载"：重启后 `SCRIPT` 导出直接 `NullPointerException: Value.getPrecision() because "v" is null`，整行读全为 null，`CREATE INDEX`/`ExternalService` 同路径受影响 | `BTreeCursor` 把 null 列集当整行取；`ColumnStorageLeafPage.getValue(index, int[])` 对 null 列集加载全部列页；新增 `beforeWrite` 覆盖，保存前确保列页已加载 | 两进程探针：回退修复后重启执行 `SCRIPT` 报上述 NPE，带修复则导出 5 行且整行读 `7,v7,70`；新增 `ColumnStorageFullRowTest` 3 例（整行读、SCRIPT 导出、CHECKPOINT SYNC 后 SUM 校验，连跑 3 轮稳定）。**注：该 NPE 需要重启才复现，池内用例只锁定行为** | 已修复 |
| 12 | 死代码：`IndexOperator.addIndexOperation(...)` 5 参重载零调用者；`BTreeStorage` 里 `thirdLastChunk != lastChunk` 恒真（`findThirdLastChunk` 取 `maxSeq-2`，与 `lastChunk` 序号必然不同） | 删除这两处；`Chunk.getOffset()` 的 >2G 溢出检查**保留**（零成本安全网，删掉才是退步） | 无新增测试（纯减法），全量套件验证 | 已修复 |
| 11 | `CreateTable` 失败路径用 `schema.remove(..., null)`，但注释写的理由（"remove 再用同一个 lock 会回滚两次，把上一条 DDL 创建的对象也回滚掉"）与事实不符：重复回滚已由 `TransactionalDbObjects` 的幂等守卫兜住 | 保留 `null`（改回 `lock` 会把 handler 注册到已 unlock 的锁上，变成 stale handler），把注释改成真实理由 | 新增 `CreateTableFailureTest` 2 例：建表失败不能回滚掉之前创建的表；显式事务内建表后回滚要消失、事务前的表要保留 | 已修复（注释+用例） |
| 10 | 常量折叠处的 `catch (DbException) {}` 空捕获会吞掉**所有**异常（含内部编程错误），把真缺陷降级成"不折叠"；与已回退的 `137de5658` 同型 | 新增 `DbException.isDataException`（SQLSTATE 22xxx），两处只容忍数据类错误，其余重新抛出 | 新增 `DataExceptionTest` 2 例；探针确认 `CASE WHEN TRUE THEN 1 ELSE 1/0`、`COALESCE(1,1/0)` 仍正常折叠，而 `1/0`、`CAST('x' AS INT)` 仍在运行期报 22012/22018 | 已修复 |
| 9 | 行值 IN 的显式列表不走三值逻辑：`(1,NULL) IN ((1,2),(3,4))` 得 FALSE，标准/PostgreSQL 要求 UNKNOWN（左侧单元素列表因为被改写成 `Comparison` 反而正确，路径不一致） | `ConditionIn` 对行值改用 `Comparison.compareRowValue` 的三值逻辑；`ConditionInConstantSet` 对行值不再走 `HashSet.contains`，改为逐项三值比较；把 `compareRowValue` 提成可按 `Database` 复用的静态方法 | 新增 `PredicateLogicTest.rowValueInUsesThreeValuedLogic`（8 条断言）；回退修复后该用例变红（`expected NULL ... but was:<false>`） | 已修复 |
| 8 | `OCTET_LENGTH`/`BIT_LENGTH` 对 BINARY/BLOB 按十六进制字符数计算（`X'41'` 得 2，应为 1），且把整串 LOB 物化一次 | 二进制类型直接取 `getPrecision()`（字节数），文本类型才按 UTF-8 计算 | 新增 `StringFunctionMatrixTest.octetAndBitLengthOnBinary`（BINARY/BLOB/中文各 2 条）；回退修复后该用例变红（`expected 1 but was 2`） | 已修复 |
| 7 | 服务返回 Map/Array 时按 JSON 输出错误：嵌套 Map/List 被压成带引号的字符串、控制字符原样输出（非法 JSON）；`java.sql.Array` 转换对基本类型数组抛 CCE、元素为 null 时 NPE、且所有元素被转成字符串 | `ValueMap.jsonValue` 递归处理 MAP/ARRAY/LIST/SET 并转义控制字符；`ValueArray.get(java.sql.Array)` 改用反射按下标取元素、按元素类型转换 | 新增 `ValueJsonTest` 4 例；回退修复后 3 红 1 错（基本类型数组为 ClassCastException） | 已修复 |
| 6 | 回滚本事务插入的索引项时直接解引用 `lockable.getPageListener()`，页操作还没执行完时它是 null（同目录 `UndoLogRecord.removeInsertedKey` 早已按同一不变量判空） | 与 `UndoLogRecord` 一致的判空 + 说明 | 新增 `IndexInsertDeleteSameTransactionTest` 2 例锁定"同事务插入又删除"的索引路径；**NPE 本身需要异步页操作窗口，单线程与并发压测均未复现**，本提交属一致性加固 | 已修复（加固） |
| 5 | checkpoint 前的前置处理器（把异步索引操作尽量执行完）失败时被当成"没有待处理事务"，checkpoint 会用当前 `lastTransactionId` 截断 redo log，把还没写进索引 map 的操作永久丢掉 | 失败改为抛 `DbException`；`CheckpointService.save` 捕获后**放弃本次 checkpoint**（不保存、不截断 redo） | 新增 `CheckpointHandlerFailureTest` 2 例：处理器失败必须抛异常、失败时脏页必须保持未保存；回退修复后两例同时变红 | 已修复 |
| 4 | `ROUND(x, 负精度)` 输出科学计数法且标度为负（`ROUND(123.456,-1)` 得 `1.2E+2`、`DECIMAL(2,-1)`），JDBC `getScale()` 非法 | DECIMAL 分支按负精度舍入后把标度归到 0 | 新增 `ValueSemanticsTest.testRoundNegativeScale`（4 组断言含平局远离 0）；回退修复后正好该用例变红 | 已修复 |
| 3 | 二级索引"复用游标缓存行"的优化被自己的 `row = null` 抵消，覆盖索引查询每返回一行读两次主表 | 删掉 `next()` 里的 `row = null`（每条索引项开始校验时 `getIndexKey` 会自己清 row，所以不会读到上一条的行） | 新增 `CoveredIndexScanTest` 2 例；插桩实测 500 行查询：修复后主表读 500 次（1 次/行），恢复该行后 1000 次（2 次/行） | 已修复 |

## 二、待修复（按优先级）

### P0 静默错数据 / 打崩 / 丢数据

| # | 问题 | 位置 | 说明 |
|---|---|---|---|

### P1 过度防御 / 静默吞错

| # | 问题 | 位置 |
|---|---|---|
| 12 | `rollbackToSavepoint` 的 `isClosed() → return` 与被回退的 `137de5658` 同类；正解是在 `YieldableBase.handleException`/`Session.commit/rollback` 加 try/finally | `AOTransaction.java:393-399` |
| 14 | 去重表超过 10 万条后静默停止去重 | `QFlat.java:41` |

### P2 热路径性能

| # | 问题 | 位置 |
|---|---|---|
| 17 | 每条 UPDATE/DELETE 多一次 `map.put`（页锁 + 可能分裂） | `AOTransactionMap.java:453-457` |
| 18 | 等待期间约 100 次全调度 pump/ms，白烧一个核 | `StatementBase.java:553-565` |
| 19 | 每次事务性叶子页写多一次 `getCommittedObjects` + 每行 `copySelf`，最坏 4 次序列化，且重试 3 次后静默放弃 | `RowStorageLeafPage.java:87-127` |
| 20 | 全量备份也把整个库读两遍（crc 预读 + 写 zip） | `StorageBase.java:275-300` |
| 21 | 每个游标一份 HashMap 随扫描行数无界增长 | `StandardSecondaryIndex.java:326,385-411` |

### 测试基建

| # | 问题 | 位置 |
|---|---|---|
| 22 | SLT 门禁假绿：statement 失败永不计入门禁；baseline 只比"每文件失败计数" | `SltRunner.java:108-110`、`SltBaseline.java:156-159` |
| 24 | 测试不 hermetic：不清理 `target/test-data` 再跑会有 131 errors；跑套件会改写被跟踪的 `orm/generated/*.java` | `TestBase.java:51`、`SqlTestBase` 默认库 |

## 三、判定为"问题不成立或未能复现"（不动）

| 提交 | 结论 |
|---|---|
| `0f8cc70e6` IN 列表重复返回 | 两种数据形态下均未复现，tip 与回退该提交结果一致 |
| `3fec8493d` 列存新增列 NPE | `columnPages` 只有整体填充路径，判空不可达；删掉后跨重启用例仍绿 |
| `d0007915d` 可重复读读到更旧版本 | 语义分析成立（旧代码差一个版本），但现有 RR 用例去掉修复也全绿，无法复现 |
| `50b4e84a2` 同步 JDBC 偶发 InternalError | 调用路径真实（红栈里有 JDBC 线程进 `await`），但无法稳定复现 |
| `b2e217f7d` "备份文件被写坏" | 证据缺口维持原状：NPE 部分真实，备份坏包未实测到 |
