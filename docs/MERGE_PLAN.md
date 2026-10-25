# 合入计划与验证手册

> **结论先行**：请**整体合入 `review/fixes-1`**——一条命令、零冲突，且合并后实测全绿。
> 下面的 4 个批次是**审阅分组**（决定先看什么、哪些必须一起看），不是逐批 cherry-pick 的配方；
> 逐条 cherry-pick **不可行**，实测数据与原因见文末「演练结果」。

## 一、推荐合入方式（已实测）

```bash
git checkout master                # 本仓库 master 我们一个字都没改
git merge --no-ff review/fixes-1 -m "合入复审修复批次(48 个修复)"
mvn -B -o -q -DskipTests install
# 起测试服务(9210, 服务端 CWD 在仓库外, classpath 带 lealone-test/target/test-classes), 核对监听 PID 后:
mvn -B -o -q -pl lealone-test test                                                        # 381 项
mvn -B -o -q -pl lealone-test test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test  # 37.6 万条
```

演练结果（第 35 轮：在 `review/mervyn-consolidated` 上新分支合并整个 `review/fixes-1` 并实跑）：

| 项 | 结果 |
|---|---|
| 合并冲突 | **0 个** |
| 构建 | `mvn -DskipTests install` 成功 |
| 全量套件 | **381 项 / 0 失败 / 10 skipped** |
| SLT 门禁 | **376,388 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 → PASS** |

环境前提（测试代码写死的，不是本次改动引入的）：

* 9210 上要有测试服务；服务端 **CWD 必须在仓库外**（否则它生成的代码会落到仓库根）。
* 服务端 classpath 要带 `lealone-test/target/test-classes`，**分隔符不能漏**（我们踩过）。
* **验证前核对监听 9210 的 PID 就是刚用当前源码启动的进程** —— 残留旧进程会让你测到旧版本（我们踩过三次）。
* 反向验证：**去掉修复 → 对应用例变红**，这是每条修复的自证方式（命令见第五节）。


## 二、B1、P0 静默错数据 / 打崩 / 丢数据（19 项）

| 提交 | 解决什么问题 |
|---|---|
| `a82f740c1` | 修复关联子查询只在最内层与外层关联时返回上一行过期值的问题 |
| `07b3ec55b` | 修复异步提交回调在事务已结束或重复提交时会丢失或重复执行的问题 |
| `66e11a4c3` | 修复列存表整行读取全为null导致SCRIPT导出空指针的问题 |
| `dbfeb9886` | 修复ROUND按负精度舍入后输出科学计数法且标度为负的问题 |
| `eafbc5d96` | 修复checkpoint前置处理器失败被当成"没有待处理事务"导致redo log被提前截断的问题 |
| `f5ce2d457` | 修复行值IN的显式列表不走三值逻辑的问题 |
| `121aa2e39` | 修复索引异步构建期查询静默漏行的问题 |
| `7dc2fca53` | 修复Database.close被引擎内部session静默挡掉导致重启丢更新的问题 |
| `06e2bbd39` | 修复单个StorageMap保存失败会中止整轮检查点的问题 |
| `08a0f53d9` | 修复引擎内部session让数据库永远无法自动关闭的问题 |
| `70a7746ce` | 修复主表行已不存在时删除仍然空指针导致并发扫描偶发失败的问题 |
| `2d71694f8` | 修复只带主键的行删除时静默失败的问题 |
| `06893e57b` | 修复GROUP BY里的输入列被输出别名顶掉导致静默少分组的问题 |
| `dc779ffd5` | 修复SCRIPT导出超长LOB时因缺少data handler而失败的问题 |
| `7e965564c` | 修复最后一个用户连接断开时内部session挡住数据库自动关闭的问题 |
| `cd7d304ad` | 修复服务返回Map/Array时JSON输出错误的问题 |
| `ffd80e6fd` | 修复OCTET_LENGTH/BIT_LENGTH对二进制类型算错字节数并物化整个LOB的问题 |
| `26ade674d` | 修复事务里执行TRUNCATE会让会话进入不一致状态并静默丢数据的问题 |
| `135d93201` | 修复事务内查询之后ROLLBACK在本会话里看不到撤销结果的问题 |

## 三、B2、P1 过度防御 / 静默吞错 / 健壮性（11 项）

| 提交 | 解决什么问题 |
|---|---|
| `3f8363ba9` | 修复常量折叠处空catch吞掉所有异常的问题 |
| `89b5abc7e` | 删除复审确认的两处死代码 |
| `549aa6998` | 修正建表失败路径清理逻辑的过时注释并补行为用例 |
| `7dbf3c1e0` | 修复块长度越界时静默当文件结束导致损坏数据被无声截断的问题 |
| `9241f581d` | 修复单表查询去重表超过上限后静默放弃去重的问题 |
| `504db65a0` | 修复回滚保存点对已结束事务静默返回的问题 |
| `9a0e5953f` | 修复关闭存储时发起多余异步检查点的问题 |
| `6e28f1993` | 修复已存储LOB缺少data handler时只报裸空指针无法定位的问题 |
| `700b236cf` | 修复没有data handler的已存储LOB在unlink时空指针的问题 |
| `161c87676` | 修复没有data handler的已存储LOB在link时空指针的问题 |
| `eed40f4cd` | 修复回滚本事务插入的索引项时未判空页监听器的问题 |

## 三点五、B3、P2 热路径性能（2 项）

| 提交 | 解决什么问题 |
|---|---|
| `d18c73dc3` | 修复全量备份把整个库读两遍的问题 |
| `8135c4023` | 修复覆盖索引查询每返回一行读两次主表的问题 |

## 四、B4、测试基建 / SLT 门禁（16 项）

| 提交 | 解决什么问题 |
|---|---|
| `eeb79a7e6` | 修复SequenceLockedExceptionTest在worker线程失败时会永久挂死的问题 |
| `0459f3347` | 修复CheckpointDurabilityTest失败时遗留库和目录污染后续用例的问题 |
| `ee68d2079` | 修复重启校验复制活跃元数据库导致的偶发假红 |
| `71a92ffbe` |  |
| `5577a117d` | 修复SLT回归门禁不计statement失败导致脚本里的DDL/DML坏掉仍然PASS的问题 |
| `3e7dc2f3a` | 修复SLT门禁baseline只比失败个数导致同文件内替换失败仍然PASS的问题 |
| `ec89521d9` | 修复SLT门禁失败签名用截断SQL生成导致新增失败被当成已登记的问题 |
| `a9b0716cb` | 修复跑套件会改写被跟踪的生成代码导致工作区变脏的问题 |
| `0d3a77c9c` | 修复测试数据目录残留导致第二轮套件大批报错的问题 |
| `39dac48f6` | 修复JDBC客户端trace文件写进模块目录留下未跟踪残留的问题 |
| `e215f042a` | 修复客户端事件循环注册失败时挂死整个套件的问题 |
| `d86a347f6` | 把SLT门禁精选集从27个文件扩到39个文件以覆盖多表复杂查询 |
| `a1f4d1e5c` | 把SLT门禁精选集从39个文件扩到45个文件以覆盖更多表达式用例 |
| `1da3d49db` | 修复客户端用例偶发挂死时只有超时提示没有线程栈无法定位的问题 |
| `c2ab81d45` | 修复关连接时等待调度器执行任务没有超时会永久阻塞的问题 |
| `071c1de76` | 补大表二级索引扫描的正确性护栏并记录一项未采纳的内存优化 |
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

以 B1 的 `135d93201`（事务内查询后 ROLLBACK 在本会话看不到撤销结果）为例：

```bash
git revert --no-edit 135d93201
mvn -B -o -q -DskipTests install
# 重启测试服务并核对 PID，然后:
mvn -B -o -q -pl lealone-test test -Dtest=RollbackAfterQueryTest
# 期望: FAILURE - "ROLLBACK 后必须恢复成 2 行, 实际 1"
git revert --no-edit HEAD     # 恢复
```

其余提交同理：**提交信息里写了「回退后哪个用例会红、红成什么样子」**，照着做即可。

## 七、合入时必须一起看的三件事

1. **门禁基线**：B4 会把 SLT 精选集从 27 个文件扩到 45 个（20.8 万 → 37.6 万条），并重新生成
   `lealone-test/src/test/resources/slt/baseline.txt`；只合其中一部分会让基线与 `files.txt` 不一致。
2. **生成代码幂等**（`a9b0716cb`）：跳过它，合入后第一次跑套件工作区就会变脏（9 个被跟踪的
   `orm/generated/*.java` 被重写）。
3. **测试数据自动清理**（`0d3a77c9c`）：跳过它，**第二轮**套件会大批报 `Wrong user name or password`。

## 八、不在这 4 组里的东西

| 内容 | 说明 |
|---|---|
| 6 项「实测后判定不改」 | 数据在 `REVIEW_FIXES_SUMMARY.md` 第六节，合了反而是退步 |
| 台账/汇总/手册这三份文档的提交 | 属于审阅材料，按需合 |
| `f41a65626` + `a7a85a6af`（`rowsort` 改错与回退） | 净效果为零，两者都**不要合** |

## 八点四、最终验证基线（第 38 轮）

| 项 | 数值 |
|---|---|
| 分支 / HEAD | `review/fixes-1` @ `7d9d5d322`（本轮文档提交前） |
| 提交数 | 76 个（48 修复 + 28 记录/文档） |
| 全量套件 | **381 项 / 0 失败 / 10 skipped**（连续多轮一致） |
| SLT 门禁 | **45 个文件 / 376,388 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 PASS** |
| 未修复缺陷 | **0**（判定不改的 6 项有实测数据，见汇总第六节） |
| 台账 | 48 行、编号 1..48 连续、每行带提交号（本轮重建） |

## 八点五、端到端验证（干净 clone，第 37 轮）

上面的推荐做法在**干净 clone** 上完整跑通了一遍（不是在工作区里"看起来能跑"）：

```bash
git clone <repo> /tmp/e2e && cd /tmp/e2e
git checkout -b e2e-merge <集成分支>          # 演练时用 review/mervyn-consolidated
git merge --no-ff origin/review/fixes-1       # 或 merge review/fixes-1
mvn -B -o -q -DskipTests install
# 用这个 clone 的产物起测试服务(核对监听 9210 的 PID 指向该 clone), 然后:
mvn -B -o -q -pl lealone-test test                                   # 381 项
mvn -B -o -q -pl lealone-test test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test
```

| 步骤 | 结果 |
|---|---|
| merge | **0 冲突** |
| 构建 | 成功 |
| 全量套件(连跑 2 轮) | **381 / 381 通过，0 失败，10 skipped**（两轮一致） |
| SLT 门禁 | **376,388 条查询 / 14 失败(2 条 allowlist) / 12 已登记 / +0 → PASS** |

## 九、演练结果：为什么不做逐条 cherry-pick

实测（在 `review/mervyn-consolidated` 上按严重度分组逐条 cherry-pick）：

| 做法 | 结果 |
|---|---|
| 只把 B1（19 个 P0 提交）cherry-pick 到基线上 | **2 成功 / 17 冲突** |
| 先补台账骨架再 cherry-pick B4（16 个） | **0 成功 / 16 冲突** |
| `git cherry-pick -n` 后丢弃文档改动 | 仍 **17 个冲突**（`-n` 在冲突时就不会继续） |
| **整体 `git merge --no-ff`** | **0 冲突，构建通过，381 项全绿，门禁 PASS** |

冲突有两个结构性原因：

1. **每个修复提交都改了 `docs/FIX_LOG.md`**（这是本批的规矩：代码与台账同步更新），
   逐条 cherry-pick 时台账的增量补丁互相冲突；
2. **B1 依赖 B4**：B1 的若干提交修改了 `SltGateStatementTest`、`DatabaseCloseIfNeededTest`、
   `RollbackAfterQueryTest` 等**测试基建提交才创建**的文件，所以按严重度顺序 cherry-pick 必然失败。

因此：**分组只用于审阅**（先读 B1 的 19 项），**合入走整体 merge**。
如果确实需要拆小批次，建议按「文件范围」而不是「严重度」来拆（例如先合所有 `lealone-aote` 的改动），
并且要把台账文件排除在外——这一条我们**没有实测**，不写进推荐做法。
