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
| 3 | 二级索引"复用游标缓存行"的优化被自己的 `row = null` 抵消，覆盖索引查询每返回一行读两次主表 | 删掉 `next()` 里的 `row = null`（每条索引项开始校验时 `getIndexKey` 会自己清 row，所以不会读到上一条的行） | 新增 `CoveredIndexScanTest` 2 例；插桩实测 500 行查询：修复后主表读 500 次（1 次/行），恢复该行后 1000 次（2 次/行） | 已修复 |

## 二、待修复（按优先级）

### P0 静默错数据 / 打崩 / 丢数据

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| 3 | checkpoint 吞掉 `Throwable` 并返回 `Long.MAX_VALUE`，会据此截断 redo log，重启永久丢索引记录 | `AOTransactionEngine.java:279-291` | 应改为中止本次 checkpoint |
| 4 | 未提交插入回滚时取 `getPageListener()` 无判空 → NPE | `AOTransactionMap.java:660` | 与 `UndoLogRecord.java:86-91` 的加固统一 |
| 5 | 列存表 `columnIndexes == null` 被当成"不加载任何列"：`SCRIPT` 导出直接 NPE，重启后 checkpoint NPE，`CREATE INDEX`/`ExternalService` 读到 NULL | `CursorParameters` / `BTreeCursor:58` / `ColumnStorageLeafPage.getValue` | 需在存储层归一根治，`92080a980` 只堵了一个入口 |
| 6 | `ValueMap.getJson` 把嵌套 map/list 输出成带引号字符串、控制字符产生非法 JSON；`ValueArray.get(java.sql.Array)` 全转 String、primitive 数组 CCE、null 元素 NPE | `ValueMap.java:101-124`、`ValueArray.java:49-62` | 静默给错数据，建议改用现成 Json 编码器 |
| 7 | `ROUND(x, 负精度)` 输出科学计数法且 scale 为负（JDBC `getScale()` 非法） | `NumericFunction.java:293-298` | 本批新引入的回归 |
| 9 | `OCTET_LENGTH`/`BIT_LENGTH` 对 BINARY/BLOB 仍按十六进制字符数计算（`X'41'`→2，应 1），且改为每行整串物化 | `StringFunction.java:144-155` | 二进制走 `getPrecision()`，文本才按 UTF-8 |
| 10 | 行值 IN 的显式列表不走三值逻辑：`(1,NULL) IN ((1,2),(3,4))` 得 FALSE，应为 NULL | `ConditionIn.java:61-84`、`ConditionInConstantSet.java:67-79` | 单元素列表路径却正确，需统一 |

### P1 过度防御 / 静默吞错

| # | 问题 | 位置 |
|---|---|---|
| 11 | `Operation.optimize`/`SystemFunction.optimize` 空 `catch (DbException) {}` 吞掉所有异常（含内部编程错误） | `Operation.java:338-341`、`SystemFunction.java:838-841` |
| 12 | `rollbackToSavepoint` 的 `isClosed() → return` 与被回退的 `137de5658` 同类；正解是在 `YieldableBase.handleException`/`Session.commit/rollback` 加 try/finally | `AOTransaction.java:393-399` |
| 13 | 加密文件密码错误/损坏被静默当 EOF（LOB 流也走这里 → 数据被悄悄截断） | `FileStorageInputStream.java:142-146` |
| 14 | 去重表超过 10 万条后静默停止去重 | `QFlat.java:41` |
| 15 | 建表失败路径 `schema.remove(..., null)` 的注释与事实不符，且实测"既不必要也不充分" → 建议整体回退成 `lock` | `CreateTable.java:242-243` |
| 16 | 死代码：`IndexOperator.addIndexOperation(...)` 5 参重载零调用者；`Chunk.getOffset()` 的 >2G 抛异常不可达；`BTreeStorage:396` 的 `thirdLastChunk != lastChunk` 恒真 | `IndexOperator.java:408-412`、`Chunk.java:137-147`、`BTreeStorage.java:396` |

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
| 23 | `SequenceLockedExceptionTest` worker 抛异常后不 `countDown()`，主线程永久挂死 | `SequenceLockedExceptionTest.java:29,36,45,62-64` |
| 24 | 测试不 hermetic：不清理 `target/test-data` 再跑会有 131 errors；跑套件会改写被跟踪的 `orm/generated/*.java` | `TestBase.java:51`、`SqlTestBase` 默认库 |

## 三、判定为"问题不成立或未能复现"（不动）

| 提交 | 结论 |
|---|---|
| `0f8cc70e6` IN 列表重复返回 | 两种数据形态下均未复现，tip 与回退该提交结果一致 |
| `3fec8493d` 列存新增列 NPE | `columnPages` 只有整体填充路径，判空不可达；删掉后跨重启用例仍绿 |
| `d0007915d` 可重复读读到更旧版本 | 语义分析成立（旧代码差一个版本），但现有 RR 用例去掉修复也全绿，无法复现 |
| `50b4e84a2` 同步 JDBC 偶发 InternalError | 调用路径真实（红栈里有 JDBC 线程进 `await`），但无法稳定复现 |
| `b2e217f7d` "备份文件被写坏" | 证据缺口维持原状：NPE 部分真实，备份坏包未实测到 |
