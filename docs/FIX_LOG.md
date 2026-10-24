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
| 21 | `SltGateStatementTest` 在进程内起嵌入式库跑 SltRunner，会改变同一 JVM 里嵌入式引擎的状态，把既有的持久化缺陷（第 26 项）触发成普通套件的偶发红 | 与 `SltTest` 一致：只在 `-Pslt`/`-Dslt.run=true` 下运行；同样补上"用完关闭并删除自己创建的库"、`@AfterClass` 跳过时不做清理 | 普通模式 `Skipped: 1`，`-Pslt` 模式通过；普通全量套件恢复确定性（见第 20 项后的验证） | 已修复（临时隔离，P0 修好后移回普通套件） |
| 20 | `CheckpointService.save` 的 `try/catch` 在**循环外**：任意一个 `StorageMap` 保存失败（例如它所属数据库的存储目录已被删除）就会中止**整轮**检查点，后面所有数据库的脏页都不落盘，而对应的 redo log 随后可能被截断 → 重启后丢更新 | 改成逐个 map 隔离（整个 map 处理体 try/catch + 日志），失败的 map 自己的 redo log 不会被截断，重启按 redo 重放 | 由 `SltGateStatementTest` + 全量套件驱动：修复前全量 2/2 红（`CheckpointDurabilityTest.testBackupThenUpdate` 期望 `2000,100` 实得 `2000,0`），修复后首轮绿；**仍有残留偶发**，见下条 | 已修复（缩小爆炸半径；残留另记） |
| 19 | SLT 回归门禁把 `kind=="statement"` 的失败**排除在外**：脚本里的 DDL/DML 坏掉时门禁依然 PASS（例如 `CREATE INDEX` 写错列名），只有 query 失败才计数 | `Failure.gated()` 改为对所有失败返回 true | 新增 `SltGateStatementTest`：临时脚本里放一条必然失败的 `statement ok`，断言它被记录、`gatedFail>0`、`delta>0`；回退 `gated()` 后该用例变红。精选集 27 个文件在更严格门禁下仍是 PASS（3 次中 2 次，见下条偶发） | 已修复 |
| 18 | 全量备份把每个文件读两遍（先算 crc 决定是否写，再读一遍写 zip），而全量备份每个文件都要写、根本不需要先算 crc | 全量备份改为**边写边算 crc 只读一遍**；增量备份保留两遍（必须先算 crc 才能和基准清单比对） | 插桩实测（6 万行 / 19MB 数据）：读取量 **39MB → 19MB**（正好省掉一整遍全库读）；墙钟 212ms→233ms（页缓存命中时无差别，冷缓存/大库才有收益）。`BackupTest`/`IncrementalBackupTest`/`CheckpointDurabilityTest`/`ScriptRoundTripTest`/`CompactTest` 共 11 项全绿；新增 `BackupManifestCrcTest` 断言清单里每个条目都仍有 size 与 crc32 | 已修复 |
| 17 | `AOTransaction.rollbackToSavepoint` 对"事务已结束"静默 return（与已回退的 `137de5658` 同型）：直接调用方（`Connection.rollback(savepoint)`）看不到这个编程错误 | 事务层恢复 `checkNotClosed()`；把容错移到真正的收尾点 `ServerSession.rollbackCurrentCommand`——捕获后打 warn 并**保证仍执行解锁**，语句不会挂死、锁也不会泄漏 | 新增 `SavepointRollbackAfterCloseTest` 3 例（提交后/回滚后回滚保存点要抛异常、引擎仍可用）；回退事务层改动后 3 例中 2 例红。另跑 9 个错误路径相关用例共 22 项全绿，无挂死 | 已修复 |
| 16 | 单表查询去重表超过 10 万条后**静默**停止去重，之后若出现重复行完全无从判断原因 | 保留上限（防内存无界增长），超过时打一次 warn 说明"本语句后续不再去重" | 新增 `FlatQueryDedupeLimitTest`（10.01 万行，批量插入 1.15s）；服务端日志实测恰好出现 1 次该 warn，且超限后行数仍准确（100100 行、`a=3` 10010 行） | 已修复（改为可见） |
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

### P2 热路径性能

| # | 问题 | 位置 |
|---|---|---|
| 17 | 每条 UPDATE/DELETE 多一次 `map.put`（页锁 + 可能分裂） | `AOTransactionMap.java:453-457` |
| 19 | 每次事务性叶子页写多一次 `getCommittedObjects` + 每行 `copySelf`，最坏 4 次序列化，且重试 3 次后静默放弃 | `RowStorageLeafPage.java:87-127` |
| 21 | 每个游标一份 HashMap 随扫描行数无界增长 | `StandardSecondaryIndex.java:326,385-411` |

### 测试基建

| # | 问题 | 位置 |
|---|---|---|
| 22 | SLT 门禁**仍然可以被"同文件替换"绕过**：baseline 只比"每文件失败计数"，同一文件里"修好 A、坏掉 B"仍然 PASS | `SltBaseline.java:156-159` | 需把 baseline 从计数改为失败签名集合 |
| 26 | **重启后丢更新（P0，未完全定位）**：全量套件里 `CheckpointDurabilityTest.testBackupThenUpdate` 期望 `2000,100`、实得 `2000,0`（行在、100 条更新没了）。日志抓到 `Failed to execute save: Directory does not exist: .../db_47/t_47_0`，即某个已失效数据库在后台检查点里抛异常。修掉"单 map 失败中止整轮"后仍有约 1/2 概率复现 | `CheckpointService.save` + 关闭路径 | **待查**：怀疑关闭路径/异步 checkpt 与 `close(false)` 存在竞争，导致 `ckptdurA` 的更新在 redo 截断前没有被保存。已给 `SltGateStatementTest` 补上关闭并删除其遗留库（消除一个噪声源） |
| 25 | **SLT 门禁偶发，根因是引擎返回不确定的错误结果**：单跑 `select4.test`（2832 条查询）实测 6 次里 1 次失败、8 次里 1 次失败，每次失败的查询**不是同一条**（已观察到第 6947 行与第 6119 行两条不同的 UNION/EXCEPT 链），失败类型都是 `hash_mismatch` 且**行数不一致**（预期 19 实际 28 / 预期 28 实际 19），不是顺序差异。原始代码同样复现，与门禁改动无关 | 例：`select4.test:6119` 的 6 段 `UNION/EXCEPT` 链 + `WHERE NOT (x IN (...))`，表 t1/t3/t4/t5/t6/t8/t9 各 1000 行、每表 5 个索引 | **待查（已派专项调查）**：疑似索引扫描在 `NOT IN`/`EXCEPT` 路径上偶发漏行/多行，属于 P0 级（查询结果不确定）；门禁偶发会掩盖真回归 |
| 24 | 测试不 hermetic：不清理 `target/test-data` 再跑会有 131 errors；跑套件会改写被跟踪的 `orm/generated/*.java` | `TestBase.java:51`、`SqlTestBase` 默认库 |

## 三、判定为"问题不成立或收益未证实"（不动）

| 项 | 复审建议 | 实测结论 |
|---|---|---|
| `StatementBase.syncExecute` 等待循环"100 次全调度 pump/ms 白烧一个核" | 改成 1 次 pump + 1ms | **未证实，未采纳**。两个场景实测（4 线程争抢同一行的 1200 次更新；以及"持锁者闲置 2 秒"的纯等待场景）：等待期间进程 CPU 都是 ~150 ms / 2 秒（约占单核 7%），改与不改的 CPU 与墙钟差异都在噪声内（墙钟甚至偶有变差）。原因是每次 pump 在无活可干时开销很小，且当前等待主要走 `scheduler.await()` 而不是这个自旋循环。**结论：这不是热路径瓶颈，改它属于无收益改动。**

| 提交 | 结论 |
|---|---|
| `0f8cc70e6` IN 列表重复返回 | 两种数据形态下均未复现，tip 与回退该提交结果一致 |
| `3fec8493d` 列存新增列 NPE | `columnPages` 只有整体填充路径，判空不可达；删掉后跨重启用例仍绿 |
| `d0007915d` 可重复读读到更旧版本 | 语义分析成立（旧代码差一个版本），但现有 RR 用例去掉修复也全绿，无法复现 |
| `50b4e84a2` 同步 JDBC 偶发 InternalError | 调用路径真实（红栈里有 JDBC 线程进 `await`），但无法稳定复现 |
| `b2e217f7d` "备份文件被写坏" | 证据缺口维持原状：NPE 部分真实，备份坏包未实测到 |
