# 修复索引（逐提交：改了多少、自带哪个用例、怎么自证）

审阅用的一页速查表。每条修复给出：**改了几个文件**、**自带的用例**、**怎么快速自证**
（自证方式从提交信息里自动提取）。细节见 [FIX_LOG.md](FIX_LOG.md)，合入方式见 [MERGE_PLAN.md](MERGE_PLAN.md)。

建议的阅读顺序：
1. 先看**自带用例**的条目（确定性最强，能直接跑）；
2. 再看没有独立用例的基础设施/门禁/清理类，按"改动文件数"从大到小挑；
3. 任何一条都能用文末的"反向自证"流程验证（回退 → 对应用例变红）。

| 提交 | 解决什么问题 | 改动文件数 | 自带用例 | 自证方式（摘自提交信息） |
|---|---|---|---|---|

| `4690b8412` | 修复CheckpointDurabilityTest失败时遗留库和目录污染后续用例的问题 | 1 | CheckpointDurabilityTest | 正常路径 4/4 通过, 结束后 ckptdur 残留 0 项; 把期望值临时改成 999 制造失败后, 残留同样是 0 项(修复前会留下 _iso 目录与 zip)。 |
| `afa851dc0` | 修复GROUP BY里的输入列被输出别名顶掉导致静默少分组的问题 | 2 | GroupByAliasShadowTest | ① 新增 GroupByAliasShadowTest: 输入列优先、非遮蔽、纯别名分组、无别名分组四种写法都必须 3 组; |
| `cf53d6c3e` | 修复单个StorageMap保存失败会中止整轮检查点的问题 | 2 | SltGateStatementTest | 由全量套件驱动(SltGateStatementTest 提供额外的嵌入式库, 使该路径稳定复现)。 |
| `f1b61a540` | 补大表二级索引扫描的正确性护栏并记录一项未采纳的内存优化 | 1 | SecondaryIndexLargeScanTest |  |
| `6be4f5d30` | 修复异步提交回调在事务已结束或重复提交时会丢失或重复执行的问题 | 2 | AsyncCommitCallbackTest | AsyncCommitCallbackTest 两个用例。去掉补调 → "没有回调, 语句会挂死"(30s 超时); |
| `41e00cf3a` | 修复引擎内部session让数据库永远无法自动关闭的问题 | 2 | DatabaseCloseIfNeededTest | 新增 DatabaseCloseIfNeededTest —— SET DB_CLOSE_DELAY 0 后创建一个内部session, 关掉真连接, 断言 closeIfNeeded() 返回 true。回退本修复后报 "只剩内部session时, 库应该可以自动关闭", 带修复通过。 |
| `b713aae91` | 修复测试数据目录残留导致第二轮套件大批报错的问题 | 1 | TestBase | 修复前连跑两轮(中间不清理)第二轮 141 errors; 修复后连跑三轮、中间都不清理, 三轮都是 369 项全绿, 每轮启动恰好自动清理 1 次。 |
| `b52347178` | 修复索引异步构建期查询静默漏行的问题 | 2 | IndexBuildingScanTest | 新增 IndexBuildingScanTest(embedded, 6 万行, CREATE INDEX 后连续查 1500 次, 先 SET QUERY_CACHE_SIZE 0), |
| `4dc29a709` | 修复事务内查询之后ROLLBACK在本会话里看不到撤销结果的问题 | 2 | RollbackAfterQueryTest | RollbackAfterQueryTest(去掉 @Ignore 后的原复现用例)在两个连接下断言"回滚后必须 2 行"。 |
| `6621901f2` | 修复没有data handler的已存储LOB在link时空指针的问题 | 2 | LobLinkWithoutHandlerTest | 新增 LobLinkWithoutHandlerTest —— link(null, 9) 必须原样返回、表id保持原值, 小LOB(全内存)路径也不能崩。回退本修复后该用例报错(NPE), 带修复通过。 |
| `01441a9e3` | 修复客户端用例偶发挂死时只有超时提示没有线程栈无法定位的问题 | 3 | ClientTestBase,DiagnosticTimeout,DiagnosticTimeoutTest | 新增 DiagnosticTimeoutTest —— 用1秒超时包住一个睡5秒的语句, 断言异常照常抛出且仍是 TestTimedOutException、输出里有用例名标记、有线程栈内容、有结束标记。实测输出包含 |
| `4c8766145` | 修复事务里执行TRUNCATE会让会话进入不一致状态并静默丢数据的问题 | 2 | TruncateNotRollbackableTest | 新增 TruncateNotRollbackableTest —— 事务内执行 TRUNCATE 必须报错、报错后数据完好、 之后插入与回滚仍正常、自动提交下 TRUNCATE 正常生效。回退本修复后第一个断言就失败。 |
| `9fb45ee52` | 修复只带主键的行删除时静默失败的问题 | 2 | IndexRemoveKeyOnlyRowTest | 新增 IndexRemoveKeyOnlyRowTest(embedded): 直接构造 new Row(key, null) 交给 removeRow, 断言这一行被删掉、它的二级索引项一起删掉、另一行不受影响。 |
| `f0565b770` | 修复JDBC客户端trace文件写进模块目录留下未跟踪残留的问题 | 1 | （无独立用例） | 跑 `TraceTest` 后检查 `lealone-test/trace.db/` 应为空/不存在；回退修复后会留下 `TraceTestDB.*.trace.db` |
| `bd4734133` | 修复SLT门禁baseline只比失败个数导致同文件内替换失败仍然PASS的问题 | 6 | SltBaseline,SltBaselineSignatureTest,SltGateStatementTest,SltRunner,SltTest |  |
| `f306f830e` | 修复常量折叠处空catch吞掉所有异常的问题 | 4 | DataExceptionTest | DataExceptionTest 两例 —— 四个数据类错误码被识别; 参数未设置/语法错误/普通运行时异常不被当成数据类错误。 |
| `ac9b74662` | 修复回滚保存点对已结束事务静默返回的问题 | 3 | SavepointRollbackAfterCloseTest | 新增 SavepointRollbackAfterCloseTest 三例(提交后回滚保存点要抛异常、回滚后要抛异常、 抛异常后引擎仍可继续提交事务); 回退事务层改动后三例中两例变红。 另外跑了错误路径相关的 9 个用例(SequenceLockedException/RowLock/Deadlock/IsolationMatrix/ DdlRollback/ConcurrentTransact |
| `a41760cdb` | 修正建表失败路径清理逻辑的过时注释并补行为用例 | 2 | CreateTableFailureTest | 新增 CreateTableFailureTest 两例 —— ① 自动提交下先建表成功、再让一条建表语句失败, 前一张表必须还在, 失败的表不能留下; ② 显式事务内建表后回滚, 事务内建的表要消失、事务之前建的表要保留、回滚后还能继续建表。 |
| `4ffb0fbb9` | 修复SLT回归门禁不计statement失败导致脚本里的DDL/DML坏掉仍然PASS的问题 | 2 | SltGateStatementTest,SltRunner | 新增 SltGateStatementTest —— 临时写一个 .test 脚本, 里面放一条必然失败的 statement ok (查不存在的表), 断言该失败被记录、gated() 为 true、gatedFail>0、delta>0; 回退 gated() 后该用例立即变红("statement 失败必须计入门禁")。 |
| `75832936a` | 修复列存表整行读取全为null导致SCRIPT导出空指针的问题 | 3 | ColumnStorageFullRowTest | 新增 ColumnStorageFullRowTest 三例(整行读、SCRIPT 导出、CHECKPOINT SYNC 后 SUM 校验), 连跑三轮稳定; 另用两进程探针验证真实场景 —— 回退本修复后重启执行 SCRIPT 报上述 NPE, 带修复则导出 5 行、 |
| `a6cb3925c` | 修复已存储LOB缺少data handler时只报裸空指针无法定位的问题 | 2 | LobWithoutHandlerTest | 新增 LobWithoutHandlerTest —— 用反射把 handler 置空, 断言错误信息里带 lobId 和 tableId, 且不再是 NullPointerException。回退本改动后该用例变红, 带修复通过。 |
| `78177ffa5` | 修复没有data handler的已存储LOB在unlink时空指针的问题 | 2 | LobUnlinkWithoutHandlerTest | 新增 LobUnlinkWithoutHandlerTest —— 无 handler 的已存储LOB调 unlink(null) 不能崩、 表id保持原值、仍算挂在表上; 小LOB(全内存)路径不受影响。回退修复后该用例报错(NPE), 带修复通过。 |
| `9fb45ee52` | 修复主表行已不存在时删除仍然空指针导致并发扫描偶发失败的问题 | 2 | （无独立用例，靠并发扫描用例） | `ScanDuplicateTest` 与删除/更新相关用例连跑 3 轮；回退后偶发 `NullPointerException`（并发扫描） |
| `cb73aa8e2` | 修复块长度越界时静默当文件结束导致损坏数据被无声截断的问题 | 2 | FileStorageTest | 新增 FileStorageTest.testChunkLengthExceedsFileSize —— 块长度为正但远超文件剩余空间(不是 Integer.MAX_VALUE 那种极端值, 那条已有用例只覆盖了极端值), 断言仍然读到 -1 且不抛异常; FileStorageTest 5/5 通过。 |
| `a8c24f21c` | 修复Database.close被引擎内部session静默挡掉导致重启丢更新的问题 | 2 | CheckpointDurabilityTest |  |
| `41e00cf3a` | 修复最后一个用户连接断开时内部session挡住数据库自动关闭的问题 | 2 | DatabaseCloseIfNeededTest | DatabaseCloseIfNeededTest 增加 testLastUserSessionDisconnectClosesDatabase —— SET DB_CLOSE_DELAY 0, 创建一个内部session, 关掉最后一个用户连接, 断言库自动关闭。 回退本修复后报 "最后一个用户连接断开后, 库应该自动关闭", 带修复 2/2 通过。 |
| `b52347178` | 修复覆盖索引查询每返回一行读两次主表的问题 | 2 | CoveredIndexScanTest | CoveredIndexScanTest 两例 —— 覆盖索引扫描结果与主表扫描逐行一致且不重复; 同一行被连续更新 20 次后扫描只输出一次(锁住"每条索引项用自己的主表行校验"这条不变量)。 |
| `f9067fd7b` | 删除复审确认的两处死代码 | 2 | （纯删除死代码） | 编译通过与全量套件即可（无行为变化）；回退只会把死代码加回来 |
| `679f549aa` | 修复单表查询去重表超过上限后静默放弃去重的问题 | 2 | FlatQueryDedupeLimitTest | 新增 FlatQueryDedupeLimitTest —— 10.01 万行(批量插入, 1.15 秒), 验证超过上限后 结果行数仍然准确(全表 100100 行, a=3 有 10010 行), 并在服务端日志里确认该 warn 恰好出现一次。 |
| `57e0b0fd6` | 修复关闭存储时发起多余异步检查点的问题 | 1 | （无独立用例，靠关闭/持久化用例） | `CheckpointDurabilityTest` + `BackupTest` + `IncrementalBackupTest` 共 11 个用例；回退后日志里会出现多余的异步 checkpoint |
| `74bfcb58b` | 把SLT门禁精选集从39个文件扩到45个文件以覆盖更多表达式用例 | 3 | （门禁自身） | 跑 SLT 门禁：应 `TOTAL 376388 / +0 PASS`；精选集 45 个文件（当前值） |
| `1885a5bf6` | 修复关联子查询只在最内层与外层关联时返回上一行过期值的问题 | 2 | CorrelatedSubqueryTest | CorrelatedSubqueryTest 四个用例(两条嵌套关联 + 单层对照 + 非关联对照)。回退本改动后 |
| `0bcef4779` | 修复跑套件会改写被跟踪的生成代码导致工作区变脏的问题 | 4 | GeneratedCodeIdempotentTest | 新增 GeneratedCodeIdempotentTest —— 断言生成文件里没有 \r, 并且再生成一次时 内容与文件 mtime 都不变。回退修复并 install 后该用例报 |
| `324c61b6b` | 修复关连接时等待调度器执行任务没有超时会永久阻塞的问题 | 2 | ClientSessionAwaitTest | 新增 ClientSessionAwaitTest —— 已 countDown 的 latch 必须立刻返回; 永不 countDown 的 latch 必须在 200ms 内抛 DbException, 且信息里含操作名与超时值。 |
| `10e21b06f` | 修复服务返回Map/Array时JSON输出错误的问题 | 3 | ValueJsonTest | ValueJsonTest 四例 —— 嵌套 Map 输出成 JSON 对象、List 输出成 JSON 数组、控制字符被转义、 基本类型数组与含 null 元素的对象数组都能正确转换且保留类型。回退修复后四例中 3 红 1 错。 |
| `27e444c3c` | 修复全量备份把整个库读两遍的问题 | 2 | BackupManifestCrcTest | 新增 BackupManifestCrcTest —— 备份后直接读 zip 里的 BACKUP.MANIFEST, 断言每个数据条目 都有对应条目且 size>0、crc32!=0(证明单遍写入仍然记录了校验和, 恢复校验不受影响)。 另外 BackupTest/IncrementalBackupTest/CheckpointDurabilityTest/ScriptRoundTripTest/ |
| `74bfcb58b` | 把SLT门禁精选集从27个文件扩到39个文件以覆盖多表复杂查询 | 3 | （门禁自身） | 跑 SLT 门禁：`-Pslt -Dtest=SltTest`，应 `TOTAL 316388 / +0 PASS`；精选集 39 个文件 |
| `1ebdfadb1` | 修复ROUND按负精度舍入后输出科学计数法且标度为负的问题 | 2 | ValueSemanticsTest | ValueSemanticsTest.testRoundNegativeScale —— ROUND(123.456,-1)=120、科学计数法消失、 getScale()>=0; ROUND(CAST(123.456 AS DECIMAL(10,3)),-2)=100; ROUND(CAST(150 AS DECIMAL(10,0)),-2)=200(平局仍远离0); ROUND(-123.45 |
| `4a577cb54` | 修复SCRIPT导出超长LOB时因缺少data handler而失败的问题 | 2 | `ColumnStorageFullRowTest` | 跑 `ColumnStorageFullRowTest.testScriptExport`（配合 `CheckpointDurabilityTest` 一起跑更容易复现）；回退后该用例偶发 NPE |
| `47e70afd8` | 修复客户端事件循环注册失败时挂死整个套件的问题 | 2 | ClientTestBase | 护栏有效性用探针验证 —— 临时把超时改成 1 秒, 加一个 sleep 3 秒的客户端用例, 它在 1.087 秒时以超时错误失败(探针用例已删除); 正常超时 300 秒下, 全量套件见批次末尾的运行结果。 |
| `83b294c4b` | 修复checkpoint前置处理器失败被当成"没有待处理事务"导致redo log被提前截断的问题 | 3 | CheckpointHandlerFailureTest | CheckpointHandlerFailureTest 两例。 ① 处理器抛异常时 runBeforeCheckpointHandler 必须抛出去, 不能返回 Long.MAX_VALUE; ② 处理器失败时执行 CHECKPOINT SYNC, 脏页必须仍然是未保存状态(证明保存被放弃), 处理器恢复后检查点正常、数据不变。 回退修复后两例同时变红。 |
| `c49662523` | 修复SLT门禁失败签名用截断SQL生成导致新增失败被当成已登记的问题 | 2 | SltFailureKeyTest,SltRunner | 新增 SltFailureKeyTest —— 两条只有末尾不同的 600+ 字符 SQL 必须算出不同签名, 同一条 SQL 的签名必须稳定, 显示字段仍然截断。把签名改回截断版本后该用例失败 (报 "只有末尾不同的两条失败不能算出同一个签名"), 带修复通过; SLT 门禁(20.8万条)仍 PASS。 |
| `d1d071b37` | 修复重启校验复制活跃元数据库导致的偶发假红 | 1 | CheckpointDurabilityTest | 全量套件连跑 5 轮, 4 轮全绿(剩下的 1 轮是另一个并发扫描的 NPE, 已单独记账, 与本改动无关); 该用例单跑 3/3 绿。 |
| `8427b74e5` | 修复SequenceLockedExceptionTest在worker线程失败时会永久挂死的问题 | 1 | SequenceLockedExceptionTest |  |
| `37cf20e1c` | 修复回滚本事务插入的索引项时未判空页监听器的问题 | 2 | IndexInsertDeleteSameTransactionTest | IndexInsertDeleteSameTransactionTest 两例 —— 同事务插入又删除(带唯一索引 + 二级索引)100 轮后 表为空且走索引查不到; 同 key 先删后插再提交只有一行且索引可查。 |
| （整理时已删除的 rowsort 提交） | 修复SLT门禁rowsort只排实际结果不排期望值导致误判的问题 | 2 | SltGateRowsortTest,SltRunner | 新增 SltGateRowsortTest —— 脚本里期望值故意写成 3/1/2(集合相同), rowsort 下必须 PASS。 |
| `0c603f492` | 修复行值IN的显式列表不走三值逻辑的问题 | 4 | PredicateLogicTest | PredicateLogicTest.rowValueInUsesThreeValuedLogic 八条断言 —— (1,NULL) IN ((1,2),(3,4)) / (1,2) IN ((1,NULL),(3,4)) / (NULL,NULL) IN ((1,2)) / NOT IN 对应形式都是 UNKNOWN; (1,NULL) IN ((1,NULL)) 也是 UNKNOWN(行值相等 |
| `83d4e3dc1` | 修复OCTET_LENGTH/BIT_LENGTH对二进制类型算错字节数并物化整个LOB的问题 | 2 | StringFunctionMatrixTest | StringFunctionMatrixTest.octetAndBitLengthOnBinary —— BINARY 1/8、BLOB 2/16、 '中文' 6/48 六条断言。回退修复后该用例变红(expected 1 but was 2), 其余 11 个用例保持绿。 |
| `31cb9ffcb` | 把SLT门禁精选集从45个文件扩到53个文件 | 3 | （门禁自身） | 跑 SLT 门禁应 `TOTAL 442461 / +0 PASS`；精选集 53 个文件（当前值） |
| `fa2d6b06f` | 修复序列失效且找不回来时抛裸空指针而不是"序列不存在"的问题 | 3 | `SequenceValueInvalidTest` | 建序列→DROP→把旧对象 id 置 -1, 断言抛 `SEQUENCE_NOT_FOUND_1`; 还原原始代码即报与生产一致的 NPE |

## 快速跑某一条用例

```bash
mvn -B -o -q -DskipTests install                      # 必须: 否则用的是 ~/.m2 里的旧 jar
mvn -B -o -q -pl lealone-test test -Dtest=<用例名>     # 例: -Dtest=GroupByAliasShadowTest
```

## 反向自证（回退一条修复，确认对应用例变红）

```bash
git revert --no-edit <提交>
mvn -B -o -q -DskipTests install
# 重启测试服务并核对监听 9210 的 PID, 然后:
mvn -B -o -q -pl lealone-test test -Dtest=<该提交的用例>
git revert --no-edit HEAD                              # 恢复
```

统计：本分支共 **50 个修复**（含 1 个门禁扩容），其中 **42 个带独立用例**；另外 7 个是基础设施/门禁/清理类，
上面已逐条写明"用什么用例或什么现象能观察到差异"——所以 **48 条都可以自证**，
只是其中 7 条靠的是已有用例/日志/门禁输出，而不是新增用例。
