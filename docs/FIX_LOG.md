# 修复台账

本文件是**持续更新**的修复记录：复审 `review/mervyn-consolidated` 的 88 个提交后确认的问题，
以及后续每个修复提交"解决什么问题、怎么验证"。

审阅入口：[REVIEW_FIXES_SUMMARY.md](REVIEW_FIXES_SUMMARY.md)（按严重度分类的提交清单、判定不改的项、SLT 方言差异表、合入建议）。
本表按**时间顺序**记录（编号即记录顺序，不代表严重度）。

**当前状态**：48 个修复已完成，**未修复缺陷 0 个**；全量套件 381 项连续多轮全绿，
SLT 门禁 45 个文件 / 376,388 条查询 PASS。逐提交速查表见 [FIX_INDEX.md](FIX_INDEX.md)，
合入方式见 [MERGE_PLAN.md](MERGE_PLAN.md)。

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
| 27 | **干净关闭被静默跳过导致重启丢更新（P0，第 26 项的真正根因）**：`Database.close(boolean)` 在 `userSessions` 非空时直接 `return`（H2 语义）。而 `TableAnalyzer.analyzeIfRequired` 每次 DML 变更超过 `ANALYZE_AUTO`(2000) 就会异步跑表分析，并且用 `database.createSession(user)` 建了一个**登记进 `userSessions` 的内部 session**；`IndexOperator` 的系统 session 更是常驻。于是 `Database.close(false)` 经常正好撞上这些内部 session → **关闭变成空操作**（不 flushIndexOperations、不关表/存储、不 checkpoint、不刷 redo），调用方以为干净退出，复制/重启后读到的是没落盘的旧页 → 最后提交的更新丢失 | `Database` 新增 `getUserSessionCount()`：只有带 `ConnectionInfo` 的真连接才阻止关闭，`close()` 两处判据改用它（引擎内部 session 无 ConnectionInfo，不再阻止关闭） | `CheckpointDurabilityTest` 五条用例：修复前 5/5 轮里 **4 条**报 `Database.close(false)必须真的关闭数据库`（含**无备份**那条），修复后 5/5 × 3 轮全绿；新增 `testCloseWithInternalSession` 显式持有内部 session 覆盖该路径，并校验重启后 `2000/100` | 已修复 |
| 23 | `CheckpointDurabilityTest` 失败时（断言先于清理执行）会遗留"已关闭但仍注册在 `LealoneDatabase` 的库 + 复制出来的隔离目录 + 备份包"，同一 JVM 里后续用例再动这些目录时，后台检查点会对着已删除的目录反复报错 → 整个套件偶发红 | 新增 `@After cleanupLeftovers()`：无条件关闭残留库、删除隔离目录/库目录/备份包 | 正常路径 4/4 通过且残留 0 项；把期望值临时改成错误值制造失败后，残留同样是 **0 项**（修复前会留下 `_iso` 目录与 zip） | 已修复 |
| 22 | **索引异步构建期静默漏行（P0，本批修复自己引入的回归）**：`CREATE INDEX` 是异步的，构建期 `StandardSecondaryIndex.find()` 返回 `SsiBuildingCursor`（已建索引段 + 主表尾段）。`8135c4023` 为省一次主表读去掉了 `next()` 里的 `row = null`，前提是"每条索引项都由 `getIndexKey` 清缓存"，但**主表尾段不经过 `getIndexKey`** → 尾段每行都返回第一条尾段行的 `Row`；`17ccd63a3` 的 QFlat 又按 `getCurrentKey()` 去重 → 尾段除第一行外全被当重复行丢掉 → 查询**静默少行** | `StandardSecondaryIndexCursor`：缓存行带 `rowKey`，`get()` 发现 rowKey 变化就重读；新增 `setCachedRow()`，主表尾段直接复用该 Row（不额外读主表，保住覆盖索引优化）；`getIndexKey` 重置 rowKey | 新增 `IndexBuildingScanTest`（embedded、6 万行、建索引后连查 1500 次）：回退修复并 install 后 `expected:<60> but was:<1>`，修复后 3/3 通过。真实现场 `select4.test`（2832 条查询）：回退版 8 次跑出 1 次 `+1 FAIL`，修复后 **10/10 全绿** | 已修复 |
| 21 | `SltGateStatementTest` 在进程内起嵌入式库跑 SltRunner，会改变同一 JVM 里嵌入式引擎的状态，把持久化缺陷（第 26/27 项）触发成普通套件的偶发红 | 先与 `SltTest` 一致只在 `-Pslt` 下运行（临时隔离），并补上"用完关闭并删除自己创建的库"；**第 27 项修好后已移回普通套件**，不再有 skip | 移回后全量套件 361 用例 × 3 轮全绿 | 已修复 |
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

### 追查结论：没有 data handler 的 LOB（第 38/40/43/44 项同源）

- 产生点确定：`DataBuffer.readValue` 反序列化"已存储 LOB"时 handler 传 null；写入路径（`onAddSucceeded`）会补，读页面路径曾经没补。
- 已做：崩溃点补齐（`GenScript`）+ 留 `info` 诊断（表名/rowKey/lobId/tableId）+ `unlink`/`link` 的空 handler 保护 + 裸 NPE 改成带上下文的错误。
- **现状**：自 `closeIfNeeded`/`removeSession`（第 39/42 项）修好之后，全量套件**连续 6 轮全绿**，诊断日志一次都没触发 —— 原先它能被复现出来，很可能正是那些"库没真正关闭"的状态造成的。保留诊断，若再现可直接定位到表。
- 已尝试但**未采纳**的改法：在 `RowType.read` 读页面时统一补 handler（无法构造出"不加这个改动就失败"的用例，按"未验证不改"放弃）；`ShutdownDatabase`/`LealoneDatabase.gc` 的 `getSessionCount()` 判据（SHUTDOWN 的执行会话本身就还在，改判据无法证明必要）。

## 1.5 新发现的 P0（本轮未修，已固定复现）

| # | 问题 | 复现 | 状态 |
|---|---|---|---|
| 51/52 | **事务里执行过一次查询之后，`ROLLBACK` 不再撤销之前的 DML（P0 静默错数据）——已修复**：`autocommit=false` → `DELETE FROM t WHERE id=1` → **事务内 `SELECT COUNT(*)`** → `conn.rollback()` → 本会话仍然读到删除后的结果（删除没有被撤销）。**根因不在 MVCC/undo**：逐语句 + undo 记录插桩显示 rollback 真的执行了、undo 记录真的回滚了，而且**另一个会话**回滚后立刻就能看到数据已恢复（2 行），只有**本会话**读到旧结果 —— 会话的语句缓存复用了回滚前的语句/游标状态（实测同一条 `SELECT COUNT(*)` 被 `[QC] reuse`）。**修复**：`ServerSession.rollback()` 在事务**有改动**（`transaction.getSavepointId() > 0`）时调 `clearQueryCache()`；没有改动时不清，避免影响自动提交下的性能。commit 路径实测无此问题（不改）。**测试**：`RollbackAfterQueryTest`（去掉修复即失败：`ROLLBACK 后必须恢复成 2 行, 实际 1`，带修复通过）；另用双连接探针确认"另一会话=2 / 本会话修复前=1、修复后=2"；`CommitStaleProbe` 确认 commit 路径正常 | 已修复 |

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
| 26 | ~~**重启后丢更新（潜伏，观测条件已消除）**~~ **已由第 27 项修复（真正根因是 `Database.close` 被内部 session 静默挡掉）**。以下为当时排除过程的记录：：全量套件里 `CheckpointDurabilityTest.testBackupThenUpdate` 期望 `2000,100` 实得 `2000,0`（行在、100 条更新没了）；日志抓到 `Failed to execute save: Directory does not exist: .../db_47/t_47_0`（某个已失效数据库在后台检查点里抛异常）。触发条件已定位为"同一 JVM 里前面有用例遗留了开着的库、而它的存储目录后来被删掉"；把那个用例（`SltGateStatementTest`）移出普通套件后，全量套件恢复确定性（360 项 × 2 轮全绿） | `CheckpointService.save`（已修"单 map 失败中止整轮"）、`Database.close`、后台检查点与关闭的竞争 | **已排除的假设**：① 多一个嵌入式库在场（8/8 正确，含显式 `close(false)`）；② CPU 高负载放大竞争窗口（单跑 6/6 绿）；③ `close(false)` 异步不刷盘（代码确认是同步 `table.close` + `Storage.close`）。**仍待查**：后台检查点与 `Database.close` 的竞争（需要"遗留库"这一前置条件） |
| 25 | ~~SLT 门禁偶发、原始代码同样复现~~ **更正：这是本批两个修复叠加引入的回归，不是既有缺陷**（原结论基于未 install 的对照，见第 23 条方法论警示）。已作为第 22 条修复 | — | — |
| 35 | JDBC 客户端的 trace 文件按默认相对路径 `trace.db/` 写到**模块目录**（`lealone-test/trace.db/TraceTestDB.*.trace.db`），在源码树里留下未跟踪残留 | 在 `lealone-test/pom.xml` 的 surefire 配置里把 `client.trace.directory` 指到 `${project.build.directory}/test-data/trace.db`（该目录已被测试启动时的自动清理覆盖） | 修复前跑 `TraceTest` 会在 `lealone-test/trace.db/` 留下 `TraceTestDB.*.trace.db`；修复后同一用例的 trace 落在 `target/test-data/` 下，模块目录干净、`git status` 无残留。补充：`TestBase` 的静态初始化本来也会设置 `client.trace.directory`，所以这条更像"双保险"，残留也可能来自手工探针进程（用仓库根当 CWD） | 已修复 |
| 50 | **事务里 `TRUNCATE` 会让会话进入不一致状态并静默丢数据（P0）**：`TRUNCATE` 直接 `map.clear()` 清空底层存储（不进 undo log），没法回滚；而它在未提交事务里执行后，实测 `TRUNCATE → INSERT → ROLLBACK` 之后的**后续 INSERT 会被静默丢掉**（1 行数据还在，再插的行不生效，重新打开库也一样）——用户的写入无声消失 | `TruncateTable.update()` 在 `!session.isAutoCommit()` 时抛错（信息里说明"不可回滚，请先提交/回滚，或用 DELETE FROM"）；自动提交下行为不变 | 新增 `TruncateNotRollbackableTest`：事务内 TRUNCATE 必须报错、报错后数据完好（2 行）、之后插入与回滚仍正常、自动提交下 TRUNCATE 正常生效；回退修复后第一个断言就失败（`事务里执行 TRUNCATE 必须报错`）。另附：试过"把 TRUNCATE 声明成 DDL（让其隐式提交）"，实测会让**同事务的 DML 也被提交**且后续回滚行为错乱，已回退 | 已修复 |
| 47 | SLT 门禁的**失败签名用截断到 500 字符的 SQL** 生成 —— 两条"前 500 字符相同、只有末尾不同"的失败会算出同一个签名，门禁把新增失败当成已登记而放行（假绿） | `Failure` 增加 `sqlFull`（不截断）专用于签名，`sql` 仍截断用于显示；门禁比较、baseline 渲染都不受影响 | 新增 `SltFailureKeyTest`：构造两条只有末尾不同的 600+ 字符 SQL，签名必须不同、同一 SQL 签名必须稳定、显示字段仍然截断；把签名改回截断版本后该用例失败（`只有末尾不同的两条失败不能算出同一个签名`），带修复通过；SLT 门禁仍 PASS（20.8 万条） | 已修复 |
| 54 | **关连接时等待调度器执行任务没有超时，会永久阻塞**（`ClientSession.execute` 里的 `latch.await()`）：实测偶发情况下客户端调度线程全停在 `NioEventLoop.select`、任务队列不推进，`JdbcPreparedStatementTest` 的 `tearDown` 关连接时因此挂满 300 秒 | 新增 `ClientSession.awaitOrThrow(latch, timeoutMs, operation)`：超时抛 `DbException`（信息里带操作名和超时值），`execute` 里改用 10 秒上限；关连接宁可失败也不永久挂住 | 新增 `ClientSessionAwaitTest`：已 countDown 的 latch 立即返回；永不 countDown 的 latch 在 200ms 内抛 `DbException` 且信息含操作名与超时值。**红对照**：把 `awaitOrThrow` 改回无超时的 `latch.await()` 后，该用例直接挂死（`mvn` 被外壳 90 秒超时杀掉，exit=124） | 已修复 |
| 53 | **客户端用例偶发挂死时只有"超时 300 秒"一句话，没有任何定位信息**（`JdbcConnectionTest` 在本项目里已偶发出现两次，每次都只能重跑碰运气；`Timeout` 规则不转储线程栈） | 新增 `DiagnosticTimeout` 规则（语义与 `Timeout` 相同，超时时把**全部线程栈**打到标准输出再抛原异常），`ClientTestBase` 改用它 | 新增 `DiagnosticTimeoutTest`：用 1 秒超时包住一个睡 5 秒的语句，断言(1)异常照常抛出且仍是 `TestTimedOutException`、(2)输出里有用例名标记、(3)有线程栈内容、(4)有结束标记；实测输出含 `==== 用例超时线程栈: ... ====` 与全部线程栈。全量套件见批次末尾 | 已修复（可诊断性） |
| 49 | 精选集扩到 39 个文件后，`random/expr`（约 1 万条/文件，专门覆盖常量折叠与聚合表达式）仍有大量文件没被门禁覆盖 | 又挑出 6 个**实测 0 失败**的 `random/expr/slt_good_{25,26,31,33,35,36}.test` 加入门禁，覆盖提升到 **45 个文件 / 37.6 万条查询 / 11 秒**；`baseline.txt` 与 README 同步更新 | 门禁 `TOTAL 376388 / 376374 通过 / 14 失败 / 2 允许 / 12→12 → +0 PASS`，单次 11 秒；全量套件见批次末尾。另：本轮把 `slt_good_{20,23,27,28}` 的 5 条 `exception` 逐条查过，**全部是 "Division by zero"**（SQLite 返回 NULL 的方言差异），没有新缺陷 | 已修复 |
| 48 | SLT 门禁的精选集只有 27 个文件（20.8 万条），`random/select/*` 这类"多表 + 复杂 WHERE/GROUP BY"的组合完全没被覆盖 —— 上一轮 `GROUP BY` 别名缺陷正是靠临时扩语料才发现的，说明覆盖面不够是实质风险 | 精选集扩到 **39 个文件**：加入 12 个 `random/select/*`（实测 109,187 条查询 0 失败），门禁覆盖从 20.8 万条提升到 **31.6 万条**，耗时 9s→11s；`baseline.txt` 重新生成（39 file 行 + 12 fail 行，失败签名与原来一致）；README 的规模数据同步更新 | 门禁 `TOTAL 316388 / 316374 通过 / 14 失败 / 2 允许 / 12→12 已登记` → `+0 PASS`；耗时 11 秒；全量套件见批次末尾 | 已修复 |
| 45 | **`GROUP BY` 里的输入列被输出别名顶掉，静默少分组、少返回行（P0 静默错数据）**：`Select.initGroup` 在找不到同名 select 表达式时会按**别名**匹配，而且没有区分"GROUP BY 里写的是输入列还是别名"。于是 `SELECT col1 AS col0, -col0 AS col1 FROM t GROUP BY col1, col0` 里 `GROUP BY col0` 被绑到别名 `col0`（其实是 `col1`）上，两个分组项都落到同一列 → 3 组只返回 2 组。同一段代码里还把 `sql = expr.getAlias()` 写在循环内，第一轮之后搜索键就被改掉了 | ① 输入列（`ExpressionColumn`）不再参与别名回退匹配（标准语义：输入列优先于别名）；② 别名只取一次，不再在循环内改写搜索键 | 新增 `GroupByAliasShadowTest`（输入列优先、非遮蔽、纯别名分组、无别名分组四种写法都必须 3 组）：回退修复后报 `别名遮蔽输入列时不能少分组 expected:<3> but was:<2>`，带修复通过；**用独立 oracle 复核**：把 SLT 语料（`random/groupby/slt_good_12.test` 等 21 个文件、16.2 万条查询）跑一遍，`row_count` 类失败从 1 降到 **0**。本条正是**用扩大后的 SLT 语料（SQLite 独立 oracle）新发现的**，不在原门禁的 27 个文件里 | 已修复 |
| 44 | `ValueLob.link(DataHandler, int)` 在 `dh == null` 时会空指针（小 LOB 分支要调 `dh.getMaxLengthInplaceLob()`）。和 `unlink` 同一类：反序列化出来的"已存储 LOB"就是没有 handler | `link` 在 `dh == null` 时原样返回（没有 LOB 存储可操作，不做改动） | 新增 `LobLinkWithoutHandlerTest`：`link(null, 9)` 必须原样返回、表 id 不变，小 LOB 路径也不能崩；回退修复后用例报错（NPE），带修复通过 | 已修复 |
| 43 | `ValueLob.unlink(DataHandler)` 在传入的 handler 为 null 时直接 `dh.getLobStorage()` 空指针。会话提交/回滚时会调 `unlink(v.getHandler())`，而"已存储 LOB 没有 handler"这个状态确实存在（`DataBuffer.readValue` 反序列化出来就是 null，前面已两次在套件里证实），等于给提交/回滚路径埋了一个 NPE | `unlink` 在 `dh == null` 时保持原状（LOB 仍挂在原表上）直接返回；新增 `LobUnlinkWithoutHandlerTest`（无 handler 的已存储 LOB 调 `unlink(null)` 不能崩、表 id 不变；小 LOB 路径不受影响） | 回退修复后用例报错（NPE），带修复通过 | 已修复 |
| 42 | `Database.removeSession()` 在**最后一个用户连接断开**时也用 `!userSessions.isEmpty()`（准确说是 `userSessions.isEmpty()`）判断，内部 session 会让库永远不自动关闭 —— 这是第 39 项在"断开连接"这条真正触发路径上的同一缺陷 | 判据改 `getUserSessionCount() == 0` | 在同一用例类里补 `testLastUserSessionDisconnectClosesDatabase`：`SET DB_CLOSE_DELAY 0` + 内部 session 存活时关掉最后一个用户连接，断言库自动关闭；回退修复后报 `最后一个用户连接断开后, 库应该自动关闭`，带修复 2/2 通过 | 已修复 |
| 41 | **客户端用例偶发永久挂死（测试基建，P0 级干扰）**：TCP 客户端在"事件循环注册失败"（连接已被关闭 / `SelectionKey` 已 cancel）后，这个 session 再也收不到响应，调用方一直等下去 —— 整个套件跟着挂死（实测卡在 `JdbcConnectionTest`/`JdbcPreparedStatementTest`，频率约 1/5，每次白跑 20 分钟）。根因一侧：`NioEventLoop.register` 只接 `ClosedChannelException`，`CancelledKeyException`/`IllegalStateException("key is cancelled")` 以裸运行时异常逃出，只在日志里留一条 `Failed to run async session task` | ① `NioEventLoop.register` 把这两类也转成带上下文的 `DbException`；② `ClientTestBase` 加 `@Rule Timeout(300s)`，把"挂死"变成"明确的失败" | 护栏有效性：临时把超时改成 1 秒 + 一个 sleep 3 秒的探针用例，用例在 **1.087 秒**时以超时错误失败（探针已删除）；全量套件见批次末尾 | 已修复（护栏+上下文） |
| 40 | `SCRIPT` 导出把超过 `IO_BUFFER_SIZE` 的 LOB 走 `writeLobStream(v)`（要读 LOB 内容）时，如果这个值是从页面反序列化出来的（`DataBuffer.readValue` 给的 handler 是 null）就会报 "LOB has no data handler"（以前是裸空指针）—— 全量套件里两次复现（`lobId=1 tableId=38`），失败点就是 `ColumnStorageFullRowTest.testScriptExport` | `GenScript` 在导出每列前补齐 handler（表级/库级按 `isUseTableLobStorage()` 选），并记一条 `info` 日志（表名+rowKey+lobId+tableId）；`ValueLob` 新增 `hasHandler()` | 修复后全量套件 **3 轮里 2 轮全绿、1 轮超时（客户端 NIO 挂死，与本改动无关，见下）**；补齐逻辑在这 3 轮里触发 0 次，说明**触发条件仍未复现**，因此这是一处"在已确认崩溃点上补齐"的兜底 + 诊断，不是根因修复 | 已修复（兜底+诊断） |
| 39 | `Database.closeIfNeeded()` 用 `!userSessions.isEmpty()` 判断"还有没有用户连接"，引擎自己的内部 session（表分析 `TableAnalyzer`、异步索引 `IndexOperator`）也算在内 —— 于是只要后台干过一次活，这个库就**再也不会自动关闭**，存储一直开着、文件一直被占着（和 `close()` 当年被内部 session 挡掉是同一类问题，只是那条已修） | 判据改 `getUserSessionCount() > 0`（只数真正带 `ConnectionInfo` 的连接） | 新增 `DatabaseCloseIfNeededTest`：`SET DB_CLOSE_DELAY 0` 后造一个内部 session、关掉真连接，断言 `closeIfNeeded()` 返回 true；回退修复后报 `只剩内部session时, 库应该可以自动关闭`，带修复通过 | 已修复 |
| 38 | 已存储的 LOB(不在内存、也不是临时文件)却没有 data handler 时，`ValueLob.getInputStream()` 直接抛裸 `NullPointerException: Cannot invoke "DataHandler.getLobStorage()" because "this.handler" is null` —— 全量套件里偶发的 `ColumnStorageFullRowTest.testScriptExport` 失败就是它，但异常信息完全看不出是哪张表哪个 LOB | 这种情况改成抛带 `lobId`/`tableId` 的内部错误；新增 `LobWithoutHandlerTest`（反射把 handler 置空后断言错误信息里带 id、且不再是裸 NPE） | 回退修复后该用例变红；带修复通过。**根因未定位**：该失败只在全量套件里出现（单跑 3/3 绿、与 LOB 用例同跑 2/2 绿），属于跨用例污染，下一步靠新的错误信息定位 | 已修复（诊断性） |
| 36 | **只带主键的行删索引时 NPE 的补齐**（接第 30 项）：上一版只在"能补读到整行"时用真实列值，如果**主表行已经不在了**（重复删除，或已被别的事务删掉），补读返回 null、列值仍是 null，异步索引任务照样 NPE（全量套件第 3 次复现：`concurrentScanMustNotReturnDuplicatedRows ... columnArray is null`） | ① `removeRow`：补读不到整行就直接返回（没有旧列值算不出索引 key；残留孤儿索引项会被扫描时的"行是否仍匹配"校验过滤掉）；② 异步索引操作只在 `oldColumns != null` 时创建；③ `StandardSecondaryIndex.remove` 在两种列值都没有时打 warn 并跳过，而不是空指针 | `ScanDuplicateTest` + 删除/更新相关 5 个用例连跑 3 轮全绿；全量套件连跑 **3 轮全绿**（369 项），`columnArray` 出现次数 **0**（修复前那一轮是 1 次失败） | 已修复 |
| 37 | 测试/调试产生的运行时目录（`trace.db/`、`lealone_data/`）落在仓库里变成未跟踪残留 | `.gitignore` 补上这两项 | `git status` 干净；另注：服务端"生成代码"是按**服务端进程的 CWD** 写相对路径的，用 `-port 9210` 起测试服务时要把 CWD 放到临时目录，否则生成文件会落到仓库根 | 已修复 |
| 34 | 测试数据目录 `lealone-test/target/test-data` 里残留着上一次运行的库（按名字建，带着上次的用户/密码和页状态），**不手工清理就再跑一轮会出现成片 `Wrong user name or password [28000-0]`**（实测第二轮 369 个用例里 **141 个错误**），以前只能靠每次 `rm -rf` 规避，忘一次就白跑一轮 | `TestBase` 静态初始化里在测试 JVM 启动时清掉 `target/test-data`（删不掉只打 warn，退回老行为） | 修复前：全量连跑两轮、中间不清理 → 第二轮 `141 errors`；修复后：**连跑三轮、中间都不清理，全部 369 项全绿**，每轮启动恰好自动清理 1 次 | 已修复 |
| 33 | 跑一遍套件就会**改写被跟踪的** `lealone-test/src/test/java/com/lealone/test/orm/generated/*.java`（9 个文件、整篇 737 行增删）：`ModelCodeGenerator` 用 `\r\n` 拼模板，而仓库里提交的是 LF；`Service.writeFile` 又是无条件覆盖。后果是工作区一跑就脏、只读检出直接失败 | `ModelCodeGenerator` 全部改用 `\n`；`Service.writeFile` 在内容没变时直接返回（不重写文件） | 修复前：跑 `com.lealone.test.orm.**` 后 `git status` 出现 9 个改写文件、`User.java` 含 53 个 `\r`；修复后同样跑一遍 **0 个改写**、`\r` 为 0。新增 `GeneratedCodeIdempotentTest`（断言生成文件无 `\r`、再生成一次内容与 mtime 都不变）：回退修复后报 `生成文件必须用LF换行 expected:<-1> but was:<39>` | 已修复 |
| 32 | `AOTransactionEngine.beforeClose(Storage)` 在关闭存储时发起的是**异步** checkpoint，而 `StorageBase.close()` 紧接着就同步 `save()` 并关闭 map：这个异步任务既多余，又会和下面"注销 map"竞争（真跑起来时 map 已摘除或存储已关闭，保存只能失败，日志里出现过 `Failed to execute save: Directory does not exist`） | 去掉 `beforeClose` 里的异步 checkpoint，落盘交给紧随其后的同步 `save()`（已确认唯一的 `Storage` 实现 `AOStorage` 走 `StorageBase.close()`） | 关闭/持久化相关 11 个用例（`CheckpointDurabilityTest` 5 例、`BackupTest`、`IncrementalBackupTest`、`CompactTest`、`ShutdownTest`）连跑 2 轮全绿；全量套件见批次末尾 | 已修复 |
| 31 | SLT 门禁的 baseline 只比"每文件失败**个数**"：同一个文件里"修好一条、又坏掉另一条"会因为个数相抵判成 OK（假绿）。报表 TOTAL 行同样按 `unexp-base` 聚合，会掩盖跨文件的相抵 | baseline 升级为 v2：`file <TAB> path <TAB> count` + `fail <TAB> path <TAB> key ...`，门禁按**失败签名集合**比较（`key` 只由 `subtype+SQL` 生成，不含 detail/行号）；新增/消失分别报 `NEW`/`STALE`；TOTAL 改用"每文件 delta 之和"；旧格式仍兼容 | 新增 `SltBaselineSignatureTest` 5 例（同签名 OK、**修好A坏掉B 报 NEW(+1)**、修复后报 STALE、旧计数格式兼容、render→load 往返）；端到端红对照：把 baseline 里一个 `fail` 的 key 换掉但**保持计数不变**，门禁 `TOTAL +1 FAIL`（修复前是 `+0 PASS`）；正常 baseline `TOTAL +0 PASS`；`baseline.txt` 用真实运行重新生成（27 个 file 行 + 12 个 fail 行，保留原有 AVG 类型说明） | 已修复 |
| 30 | **只带主键的行被交给 `removeRow`（P0）**：全量套件里 `ScanDuplicateTest` 偶发 `NullPointerException: Cannot load from object array because "columnArray" is null`（`StandardSecondaryIndex.remove(:154)` ← `IndexOperator$RIO.run(:531)`）。根因是有计划路径只带主键、行里列值为 null，而删除要按索引列算 key 删二级索引项：没有列值会空指针，异常又被异步回调吞掉 → **删除静默不生效**（红对照实测：行还在） | `StandardTable.removeRow`：`oldColumns == null` 时补读一次完整行（`primaryIndex.getRow(session, key, null)`）再维护索引 | 新增 `IndexRemoveKeyOnlyRowTest`（embedded，直接构造 `new Row(key, null)` 交给 `removeRow`，断言行被删、二级索引项也删掉、另一行不受影响）：回退修复并 install 后 `删除没有在3秒内完成 expected:<1> but was:<2>`（静默失败），带修复通过。另：全量套件 4 轮 + 单独 8 轮都没能直接触发原始 NPE，故用例按"不变量"覆盖 | 已修复 |

| 29 | `CheckpointDurabilityTest` 的重启校验在复制 `db_0`（进程里一直在用的 lealone 元数据库）前不刷盘，复制活跃库有竞态，子进程偶尔 `ExceptionInInitializerError`（全量套件 3 轮里 1 次假红） | 复制前对元数据库做一次 `checkpoint(true)`（同步刷盘），保证复制到的是完整快照 | 全量套件 5 轮：4 轮全绿，剩下的 1 轮是另一个并发 NPE（见第 30 项），与本项无关；该用例单跑 3/3 绿 | 已修复 |
| 28 | `CheckpointDurabilityTest` 的重启校验会把**进程里一直在用的 `db_0`（lealone 元数据库）**复制到隔离目录，复制一个活跃库有竞态：子进程偶尔 `ExceptionInInitializerError`（实测全量套件 3 轮里 1 次），表现为该用例假红 | **未修**。试过的两条路都不行：① 子进程用自己的空 base dir 直接打开原库目录 → Lealone 把该目录当成新库（`Table "T" not found`），因为库名要靠 `db_0` 解析；② 只复制库目录不带 `db_0` → 同上。可行的后续方向：先让元数据库做一次 SYNC checkpooint 再复制，或让 `BackupRestoreCheck` 支持按存储路径构造库实例 | 已记录（不是引擎缺陷，属测试基建） |
| 24 | 测试不 hermetic：不清理 `target/test-data` 再跑会有 131 errors；跑套件会改写被跟踪的 `orm/generated/*.java` | `TestBase.java:51`、`SqlTestBase` 默认库 |

## 二点六、仍未完全消除的偶发（记录以防被当成"已修"）

| 项 | 观察 |
|---|---|
| `JdbcConnectionTest` / `JdbcPreparedStatementTest` 偶发挂死（**根因已抓到并已修，见 #54**） | 已修过一次（`e215f042a`：客户端事件循环注册失败时挂死整个套件），并在 `ClientTestBase` 加了 `@Rule Timeout(300s)`，让它变成失败而不是永久挂住。本轮全量两连跑里**又出现 1 次**（第 1 次 378 项中 `run(JdbcConnectionTest)` 超时 300 秒，第 2 次全绿），说明**还有第二条挂死路径**没被覆盖。目前没有线程栈；下一步应在超时时打印所有线程栈（`Thread.getAllStackTraces()`）定位，而不是靠猜 |

**根因（第30轮用新的 `DiagnosticTimeout` 抓到线程栈）**：挂死不在用例主体，而在 `tearDown`：
`SqlTestBase.tearDownAfter` → `JdbcUtils.closeSilently` → `JdbcConnection.close()` →
`ClientSession.close()` → `ClientSession.execute(...)` 里 **`latch.await()`（无超时）** 等调度器执行关闭任务，
而线程栈显示当时所有 `CScheduleService-*` 客户端调度线程都停在 `NioEventLoop.select`（空闲），
任务队列没被处理，于是这个 latch 永远等不到 —— 测试的 `NETWORK_TIMEOUT` 又是 `Integer.MAX_VALUE`，
没有任何兜底。修复方向：关闭连接必须**有界等待**（超时后强制关闭 socket 并记录告警），或在
`execute` 的 latch 等待上给出超时 + 兜底执行，不能永久阻塞。→ **已按第二种修掉，见 #54**（`ClientSession.awaitOrThrow`，10 秒上限）。
注意：这只消除了"永久挂死"，任务队列不推进这个**诱因本身**还没解决；下次如果再挂，`DiagnosticTimeout` 会直接给出线程栈。

## 二半、方法论警示（验证时踩过的坑，必须记住）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **兄弟模块从 `/root/.m2` 解析，不用工作树里的 `target/classes`**（第 23 条） | `mvn -o -pl lealone-test test` 只重编 lealone-test，`lealone-db`/`lealone-aote` 等**加载的是已 install 的 jar**。于是"回退源码但不 install"会让用例**假通过**（实测：回退 `StandardSecondaryIndex` 后不 install，`IndexBuildingScanTest` 依然绿；install 之后才 `expected:<60> but was:<1>`） | 做红/绿对照时**必须**先 `mvn -o -q -DskipTests install`（或加 `-am`），否则得出的"原始代码也复现/也通过"结论不可信 |
| `SqlTestBase` 的 `executeQuery(String)` 已经调过一次 `rs.next()`（第 24 条） | 再调 `rs.next()` 会跳过第一行，表现为 `expected:<1> but was:<2>` 之类的诡异断言 | 直接用当前 `rs`，或 `closeResultSet()` 后自己重开 |
| `mvn clean` 在离线环境不可用 | 残留 `target/classes` 造成假红/假绿 | `rm -rf lealone-*/target` 代替 |
| **多个 agent 共享 `/root/.m2`** | 别人 `install` 后，你的 `mvn -pl lealone-test test` 用的是**别人那份引擎 jar**。实测：子代理把 `.m2` 还原成未修复版后，我的全量套件立刻出现 4 个"close 没真的关闭"的失败，看着像回归，其实是被换了 jar | 跑套件前先校验 jar 状态（例如 `javap -p -cp <jar> com.lealone.db.Database | grep getUserSessionCount`），或在自己的工作树里先 `mvn -o -q -DskipTests install` |
| 共享 `target/test-data` 不清理 | 跨用例库污染，出现 `Wrong user name or password [28000-0]` 等 131 个错误 | 每次跑套件前 `rm -rf lealone-test/target/test-data` |
| 循环重跑同一条 SQL 会命中会话级语句/结果缓存 | 探针测不到真正执行（以为"稳定正确"） | 先 `SET QUERY_CACHE_SIZE 0` |

## 三、判定为"问题不成立或收益未证实"（不动）

| 项 | 复审建议 | 实测结论 |
|---|---|---|
| SLT 门禁的 `rowsort` 只排实际结果、不排期望值（怀疑会误判） | 比较前把期望值也排序 | **未采纳（改错已回退，`a7a85a6af`）**：语料里的期望值本来就是按 SLT 约定排好序的；我把期望值当成"扁平值列表"再排序，多列结果会被打散、和实际结果的行对齐破坏 —— 门禁失败数从 14 暴涨到 13656。回退后门禁恢复 PASS。原始行为符合 SLT 规范，不是缺陷。 |
| `RowStorageLeafPage` 页面写时的"提交重试"：并发提交时整页重新序列化（复审怀疑是每条更新多一次整页行拷贝） | 去掉重试，或改成更轻的脏页跟踪 | **未采纳**：插桩实测重试率 **0.35%（单线程）/ 0.18%（4 线程）**（2 万行表 + 4 线程更新 + 同时 `CHECKPOINT SYNC` 强制刷页，页写 571/2218 次，重试 2/4 次）。它是一次廉价的正确性措施（避免写出半新半旧的页），开销可以忽略，改它没有意义。 |
| 二级索引游标的 `rowSnapshots`（每个扫描到的 rowKey 一条，无上界） | 加个 10 万条上限（对齐 QFlat 的去重上限），超限打 warn | **未证实，未采纳**。理由是实测无法隔离出收益：400k 行扫描在 `-Xmx200m` 下正常（476ms/146MB），降到 `-Xmx120m` 时**插入阶段就 OOM**；改成"预建 100 万行库 + 纯扫描"后，`-Xmx96m` 下扫描也跑不完——瓶颈是页缓存而不是快照表。同时加限会**牺牲去重正确性**（超过上限的行可能重复返回），拿正确性换一个测不出来的收益不划算。已改为补一个 12 万行索引扫描的护栏用例（不丢行、不重复）。 |
| `AOTransactionMap` 每条 UPDATE/DELETE 多一次 `map.put`（建议改成按 key 标脏叶子页） | 用 `StorageMap.markDirty` 替代 `map.put` | **未证实，未采纳**：2 万行 × 3 轮（4 个二级索引）更新基准，基线 `202/102/112 ms`、变体 `181/103/101 ms`、`186/109/92 ms` —— 稳态差异在噪声内。原因：`put` 命中已存在的 key 与 `gotoLeafPage` 标脏做的是同一次 B 树搜索，省不掉；反而 `markDirty` 少了 `PageListener` 重绑，动了脏页跟踪的正确性风险。 |
| `StatementBase.syncExecute` 等待循环"100 次全调度 pump/ms 白烧一个核" | 改成 1 次 pump + 1ms | **未证实，未采纳**。两个场景实测（4 线程争抢同一行的 1200 次更新；以及"持锁者闲置 2 秒"的纯等待场景）：等待期间进程 CPU 都是 ~150 ms / 2 秒（约占单核 7%），改与不改的 CPU 与墙钟差异都在噪声内（墙钟甚至偶有变差）。原因是每次 pump 在无活可干时开销很小，且当前等待主要走 `scheduler.await()` 而不是这个自旋循环。**结论：这不是热路径瓶颈，改它属于无收益改动。**

| 提交 | 结论 |
|---|---|
| `0f8cc70e6` IN 列表重复返回 | 两种数据形态下均未复现，tip 与回退该提交结果一致 |
| `3fec8493d` 列存新增列 NPE | `columnPages` 只有整体填充路径，判空不可达；删掉后跨重启用例仍绿 |
| `d0007915d` 可重复读读到更旧版本 | 语义分析成立（旧代码差一个版本），但现有 RR 用例去掉修复也全绿，无法复现 |
| `50b4e84a2` 同步 JDBC 偶发 InternalError | 调用路径真实（红栈里有 JDBC 线程进 `await`），但无法稳定复现 |
| `b2e217f7d` "备份文件被写坏" | 证据缺口维持原状：NPE 部分真实，备份坏包未实测到 |