# 合入计划与验证手册

> **结论先行**：请**整体合入 `review/consolidated`**——它已 rebase 到 master 之上（**正好 52 个提交领先、无分叉**），
> 合入是干净的 fast-forward 或 `--no-ff` 合并，且合并后实测全绿（382 项 + 44.2 万条门禁）。
> 下面的 4 个批次（共 50 项，其中 1 项是"改错已回退"的对照）是**审阅分组**（决定先看什么、哪些必须一起看），不是逐批 cherry-pick 的配方；
> 逐条 cherry-pick **不可行**，实测数据与原因见文末「演练结果」。

## 一、推荐合入方式（已实测）

```bash
git checkout master                # 本仓库 master 我们一个字都没改
git merge --no-ff review/consolidated -m "合入复审修复批次(50 个修复)"
mvn -B -o -q -DskipTests install
# 起测试服务(9210, 服务端 CWD 在仓库外, classpath 带 lealone-test/target/test-classes), 核对监听 PID 后:
mvn -B -o -q -pl lealone-test test                                                        # 382 项
mvn -B -o -q -pl lealone-test test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test  # 44.2 万条
```

演练结果（在 `475f4b515` 之上合并整个 `review/consolidated` 并实跑）：

| 项 | 结果 |
|---|---|
| 合并冲突 | **0 个** |
| 构建 | `mvn -DskipTests install` 成功 |
| 全量套件 | **382 项 / 0 失败 / 10 skipped** |
| SLT 门禁 | **442,461 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 → PASS** |

环境前提（测试代码写死的，不是本次改动引入的）：

* 9210 上要有测试服务；服务端 **CWD 必须在仓库外**（否则它生成的代码会落到仓库根）。
* 服务端 classpath 要带 `lealone-test/target/test-classes`，**分隔符不能漏**（我们踩过）。
* **验证前核对监听 9210 的 PID 就是刚用当前源码启动的进程** —— 残留旧进程会让你测到旧版本（我们踩过三次）。
* 反向验证：**去掉修复 → 对应用例变红**，这是每条修复的自证方式（命令见第五节）。


## 二、B1、P0 静默错数据 / 打崩 / 丢数据（20 项）

| 提交 | 解决什么问题 |
|---|---|
| `d3d4bc982` | 修复关联子查询只在最内层与外层关联时返回上一行过期值的问题 |
| `8a9ec490e` | 修复异步提交回调在事务已结束或重复提交时会丢失或重复执行的问题 |
| `e0c3635d5` | 修复列存表整行读取全为null导致SCRIPT导出空指针的问题 |
| `78f36061f` | 修复ROUND按负精度舍入后输出科学计数法且标度为负的问题 |
| `b22001029` | 修复checkpoint前置处理器失败被当成"没有待处理事务"导致redo log被提前截断的问题 |
| `7b0ffa816` | 修复行值IN的显式列表不走三值逻辑的问题 |
| `3c5d31943` | 修复索引异步构建期查询静默漏行的问题 |
| `82dbd1d03` | 修复Database.close被引擎内部session静默挡掉导致重启丢更新的问题 |
| `bf482fe66` | 修复单个StorageMap保存失败会中止整轮检查点的问题 |
| `01c63f4fa` | 修复引擎内部session让数据库永远无法自动关闭的问题 |
| `34e71ea06` | 修复主表行已不存在时删除仍然空指针导致并发扫描偶发失败的问题 |
| `c0921e8b2` | 修复只带主键的行删除时静默失败的问题 |
| `e6800f6b3` | 修复GROUP BY里的输入列被输出别名顶掉导致静默少分组的问题 |
| `7775e2253` | 修复SCRIPT导出超长LOB时因缺少data handler而失败的问题 |
| `489e26331` | 修复最后一个用户连接断开时内部session挡住数据库自动关闭的问题 |
| `7ca5391de` | 修复服务返回Map/Array时JSON输出错误的问题 |
| `07adf0bbf` | 修复OCTET_LENGTH/BIT_LENGTH对二进制类型算错字节数并物化整个LOB的问题 |
| `cf892f039` | 修复事务里执行TRUNCATE会让会话进入不一致状态并静默丢数据的问题 |
| `09a704a92` | 修复事务内查询之后ROLLBACK在本会话里看不到撤销结果的问题 |
| `a5ac6d479` | 修复序列失效且找不回来时抛裸空指针而不是"序列不存在"的问题 |

## 三、B2、P1 过度防御 / 静默吞错 / 健壮性（11 项）

| 提交 | 解决什么问题 |
|---|---|
| `4db9d7f72` | 修复常量折叠处空catch吞掉所有异常的问题 |
| `4f6eaca95` | 删除复审确认的两处死代码 |
| `544c73e0f` | 修正建表失败路径清理逻辑的过时注释并补行为用例 |
| `113a84502` | 修复块长度越界时静默当文件结束导致损坏数据被无声截断的问题 |
| `b303c961e` | 修复单表查询去重表超过上限后静默放弃去重的问题 |
| `8112175af` | 修复回滚保存点对已结束事务静默返回的问题 |
| `a7faa6342` | 修复关闭存储时发起多余异步检查点的问题 |
| `e85ee9cf4` | 修复已存储LOB缺少data handler时只报裸空指针无法定位的问题 |
| `354df843a` | 修复没有data handler的已存储LOB在unlink时空指针的问题 |
| `c10d4ef86` | 修复没有data handler的已存储LOB在link时空指针的问题 |
| `8eae9b856` | 修复回滚本事务插入的索引项时未判空页监听器的问题 |

## 三点五、B3、P2 热路径性能（2 项）

| 提交 | 解决什么问题 |
|---|---|
| `fda2323a6` | 修复全量备份把整个库读两遍的问题 |
| `7dff9853a` | 修复覆盖索引查询每返回一行读两次主表的问题 |

## 四、B4、测试基建 / SLT 门禁（16 项修复 + 1 组对照）

| 提交 | 解决什么问题 |
|---|---|
| `608c8ba8d` | 修复SequenceLockedExceptionTest在worker线程失败时会永久挂死的问题 |
| `a8319c539` | 修复CheckpointDurabilityTest失败时遗留库和目录污染后续用例的问题 |
| `54229f16e` | 修复重启校验复制活跃元数据库导致的偶发假红 |
| `644bd10c5` |  |
| `9341915f6` | 修复SLT回归门禁不计statement失败导致脚本里的DDL/DML坏掉仍然PASS的问题 |
| `c716fcf0b` | 修复SLT门禁baseline只比失败个数导致同文件内替换失败仍然PASS的问题 |
| `326b8a7d3` | 修复SLT门禁失败签名用截断SQL生成导致新增失败被当成已登记的问题 |
| `43171d108` | 修复跑套件会改写被跟踪的生成代码导致工作区变脏的问题 |
| `f1990a467` | 修复测试数据目录残留导致第二轮套件大批报错的问题 |
| `ef705636b` | 修复JDBC客户端trace文件写进模块目录留下未跟踪残留的问题 |
| `27caed30d` | 修复客户端事件循环注册失败时挂死整个套件的问题 |
| `561a3982b` | 把SLT门禁精选集从27个文件扩到39个文件以覆盖多表复杂查询 |
| `561a3982b` | 把SLT门禁精选集从39个文件扩到45个文件以覆盖更多表达式用例 |
| `8a8c979b6` | 修复客户端用例偶发挂死时只有超时提示没有线程栈无法定位的问题 |
| `9e95b1676` | 修复关连接时等待调度器执行任务没有超时会永久阻塞的问题 |
| `e41efa3f1` | 补大表二级索引扫描的正确性护栏并记录一项未采纳的内存优化 |
| `04e3c7792` | 把SLT门禁精选集从45个文件扩到53个文件 |
| （整理时已删除：rowsort 改错与回退） | （`rowsort` 改错与回退，净效果为零，**不要合**） |
## 五、各组的重点验证用例（对照跑，快）

本分支新增 29 个用例，都是「带修复绿、去掉修复红」：

| 组 | 用例 |
|---|---|
| B1 | `GroupByAliasShadowTest`、`TruncateNotRollbackableTest`、`RollbackAfterQueryTest`、`IndexBuildingScanTest`、`IndexInsertDeleteSameTransactionTest`、`IndexRemoveKeyOnlyRowTest`、`ColumnStorageFullRowTest`、`ValueJsonTest`、`CorrelatedSubqueryTest`、`FlatQueryDedupeLimitTest`、`CreateTableFailureTest`、`AsyncCommitCallbackTest`、`CheckpointHandlerFailureTest`、`SavepointRollbackAfterCloseTest`、`DatabaseCloseIfNeededTest`、`DataExceptionTest` |
| B2 | `LobWithoutHandlerTest`、`LobLinkWithoutHandlerTest`、`LobUnlinkWithoutHandlerTest` |
| B3 | `BackupManifestCrcTest`、`CoveredIndexScanTest`、`SecondaryIndexLargeScanTest` |
| B4 | `SltGateStatementTest`、`SltFailureKeyTest`、`SltBaselineSignatureTest`、`DiagnosticTimeoutTest`、`ClientSessionAwaitTest`、`GeneratedCodeIdempotentTest` |

跑法：`mvn -B -o -q -pl lealone-test test -Dtest=A,B,C`（多个用例逗号分隔、不要加引号）。

## 六、回退验证（每条修复自证的方式）

以 B1 的 `09a704a92`（事务内查询后 ROLLBACK 在本会话看不到撤销结果）为例：

```bash
git revert --no-edit 09a704a92
mvn -B -o -q -DskipTests install
# 重启测试服务并核对 PID，然后:
mvn -B -o -q -pl lealone-test test -Dtest=RollbackAfterQueryTest
# 期望: FAILURE - "ROLLBACK 后必须恢复成 2 行, 实际 1"
git revert --no-edit HEAD     # 恢复
```

其余提交同理：**提交信息里写了「回退后哪个用例会红、红成什么样子」**，照着做即可。

## 七、合入时必须一起看的三件事

1. **门禁基线**：B4 会把 SLT 精选集从 27 个文件扩到 45 个（20.8 万 → 44.2 万条），并重新生成
   `lealone-test/src/test/resources/slt/baseline.txt`；只合其中一部分会让基线与 `files.txt` 不一致。
2. **生成代码幂等**（`43171d108`）：跳过它，合入后第一次跑套件工作区就会变脏（9 个被跟踪的
   `orm/generated/*.java` 被重写）。
3. **测试数据自动清理**（`f1990a467`）：跳过它，**第二轮**套件会大批报 `Wrong user name or password`。

## 八、不在这 4 组里的东西

| 内容 | 说明 |
|---|---|
| 6 项「实测后判定不改」 | 数据在 `REVIEW_FIXES_SUMMARY.md` 第六节，合了反而是退步 |
| 台账/汇总/手册这三份文档的提交 | 属于审阅材料，按需合 |
| rowsort 改错与回退（整理时已删除）（`rowsort` 改错与回退） | 净效果为零，两者都**不要合** |

## 八点四、最终验证基线（第 40 轮，交付时口径）

| 项 | 数值 |
|---|---|
| 分支 / HEAD | `review/consolidated` @ `faefe035b` |
| 提交数 | **52 个**（50 个问题各一个提交 + 门禁扩容 + 说明文档，整理时已把同问题提交与全部文档提交压合） |
| 全量套件 | **382 项 / 0 失败 / 10 skipped**（连续多轮一致） |
| SLT 门禁 | **53 个文件 / 442,461 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 PASS**（14 秒） |
| 未修复缺陷 | **0**（判定不改的 6 项有实测数据，见汇总第六节） |
| 台账 | 50 行、编号 1..50 连续、每行带提交号 |
| 新增用例 | **30 个测试类**（其中 42 条修复带独立用例，其余 8 条靠已有用例/日志/门禁自证） |

## 八点五、端到端验证（干净 clone，第 40 轮最终版）

上面的推荐做法在**干净 clone** 上完整跑通了一遍（不是在工作区里"看起来能跑"）：

```bash
git clone <repo> /tmp/e2e && cd /tmp/e2e
git checkout -b e2e-merge <集成分支>          # 演练时用 475f4b515
git merge --no-ff origin/review/consolidated       # 或 merge review/consolidated
mvn -B -o -q -DskipTests install
# 用这个 clone 的产物起测试服务(核对监听 9210 的 PID 指向该 clone), 然后:
mvn -B -o -q -pl lealone-test test                                   # 382 项
mvn -B -o -q -pl lealone-test test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test
```

| 步骤 | 结果 |
|---|---|
| merge | **0 冲突** |
| 构建 | 成功 |
| 全量套件(连跑 2 轮) | **382 / 382 通过，0 失败，10 skipped**（两轮一致） |
| SLT 门禁 | **442,461 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 → PASS**（14 秒） |

## 九、演练结果：为什么不做逐条 cherry-pick

实测（在 `475f4b515` 上按严重度分组逐条 cherry-pick）：

| 做法 | 结果 |
|---|---|
| 只把 B1（19 个 P0 提交）cherry-pick 到基线上 | **2 成功 / 17 冲突** |
| 先补台账骨架再 cherry-pick B4（16 个） | **0 成功 / 16 冲突** |
| `git cherry-pick -n` 后丢弃文档改动 | 仍 **17 个冲突**（`-n` 在冲突时就不会继续） |
| **整体 `git merge --no-ff`** | **0 冲突，构建通过，382 项全绿，门禁 PASS** |

冲突有两个结构性原因：

1. **每个修复提交都改了 `docs/FIX_LOG.md`**（这是本批的规矩：代码与台账同步更新），
   逐条 cherry-pick 时台账的增量补丁互相冲突；
2. **B1 依赖 B4**：B1 的若干提交修改了 `SltGateStatementTest`、`DatabaseCloseIfNeededTest`、
   `RollbackAfterQueryTest` 等**测试基建提交才创建**的文件，所以按严重度顺序 cherry-pick 必然失败。

因此：**分组只用于审阅**（先读 B1 的 19 项），**合入走整体 merge**。
如果确实需要拆小批次，建议按「文件范围」而不是「严重度」来拆（例如先合所有 `lealone-aote` 的改动），
并且要把台账文件排除在外——这一条我们**没有实测**，不写进推荐做法。
