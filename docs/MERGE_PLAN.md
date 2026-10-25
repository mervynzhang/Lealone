# 合入计划与验证手册

把 `review/fixes-1` 的 48 个修复按严重度分成 4 批，**每批可以独立合入、独立验证**。
建议顺序：B1 → B2 → B3 → B4（B2/B3 只改行为，不依赖 B1 的代码；但 B2 的门禁基线变化建议在 B1 之后）。

每批的验证方式统一是三步：

```bash
mvn -B -o -q -DskipTests install          # 必须: 否则 lealone-test 会用 ~/.m2 里的旧 jar(假结论)
mvn -B -o -q -pl lealone-test test        # 全量 381 项
mvn -B -o -q -pl lealone-test test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test   # 37.6 万条查询
```

环境前提（测试代码写死的，不是本次改动引入的）：

* 9210 端口上要有测试服务；启动时服务端 **CWD 必须在仓库外**（否则它生成的代码会落到仓库根）；
  服务端 classpath 要带 `lealone-test/target/test-classes`，且**分隔符不能漏**（我们踩过）。
* **验证前核对监听 9210 的 PID 就是刚用当前源码启动的进程** —— 残留旧进程会让你测到旧版本（我们踩过三次）。
* 反方向也要试：**去掉修复 → 对应用例变红**，这是每条修复自证的方式；命令见各批末尾。


## B1、P0 静默错数据 / 打崩 / 丢数据（19 个提交）

按顺序 cherry-pick（旧 → 新）：

```bash
git checkout -b merge-b1 review/mervyn-consolidated
git cherry-pick a82f740c1
git cherry-pick 07b3ec55b
git cherry-pick dbfeb9886
git cherry-pick eafbc5d96
git cherry-pick cd7d304ad
git cherry-pick ffd80e6fd
git cherry-pick f5ce2d457
git cherry-pick 66e11a4c3
git cherry-pick 06e2bbd39
git cherry-pick 121aa2e39
git cherry-pick 7dc2fca53
git cherry-pick 2d71694f8
git cherry-pick 70a7746ce
git cherry-pick 08a0f53d9
git cherry-pick dc779ffd5
git cherry-pick 7e965564c
git cherry-pick 06893e57b
git cherry-pick 26ade674d
git cherry-pick 135d93201
```

| 提交 | 解决什么问题 |
|---|---|
| `a82f740c1` | 修复关联子查询只在最内层与外层关联时返回上一行过期值的问题 |
| `07b3ec55b` | 修复异步提交回调在事务已结束或重复提交时会丢失或重复执行的问题 |
| `dbfeb9886` | 修复ROUND按负精度舍入后输出科学计数法且标度为负的问题 |
| `eafbc5d96` | 修复checkpoint前置处理器失败被当成"没有待处理事务"导致redo log被提前截断的问题 |
| `cd7d304ad` | 修复服务返回Map/Array时JSON输出错误的问题 |
| `ffd80e6fd` | 修复OCTET_LENGTH/BIT_LENGTH对二进制类型算错字节数并物化整个LOB的问题 |
| `f5ce2d457` | 修复行值IN的显式列表不走三值逻辑的问题 |
| `66e11a4c3` | 修复列存表整行读取全为null导致SCRIPT导出空指针的问题 |
| `06e2bbd39` | 修复单个StorageMap保存失败会中止整轮检查点的问题 |
| `121aa2e39` | 修复索引异步构建期查询静默漏行的问题 |
| `7dc2fca53` | 修复Database.close被引擎内部session静默挡掉导致重启丢更新的问题 |
| `2d71694f8` | 修复只带主键的行删除时静默失败的问题 |
| `70a7746ce` | 修复主表行已不存在时删除仍然空指针导致并发扫描偶发失败的问题 |
| `08a0f53d9` | 修复引擎内部session让数据库永远无法自动关闭的问题 |
| `dc779ffd5` | 修复SCRIPT导出超长LOB时因缺少data handler而失败的问题 |
| `7e965564c` | 修复最后一个用户连接断开时内部session挡住数据库自动关闭的问题 |
| `06893e57b` | 修复GROUP BY里的输入列被输出别名顶掉导致静默少分组的问题 |
| `26ade674d` | 修复事务里执行TRUNCATE会让会话进入不一致状态并静默丢数据的问题 |
| `135d93201` | 修复事务内查询之后ROLLBACK在本会话里看不到撤销结果的问题 |

## B2、P1 过度防御 / 静默吞错 / 健壮性（11 个提交）

按顺序 cherry-pick（旧 → 新）：

```bash
git checkout -b merge-b2 review/mervyn-consolidated
git cherry-pick eed40f4cd
git cherry-pick 3f8363ba9
git cherry-pick 549aa6998
git cherry-pick 89b5abc7e
git cherry-pick 7dbf3c1e0
git cherry-pick 9241f581d
git cherry-pick 504db65a0
git cherry-pick 9a0e5953f
git cherry-pick 6e28f1993
git cherry-pick 700b236cf
git cherry-pick 161c87676
```

| 提交 | 解决什么问题 |
|---|---|
| `eed40f4cd` | 修复回滚本事务插入的索引项时未判空页监听器的问题 |
| `3f8363ba9` | 修复常量折叠处空catch吞掉所有异常的问题 |
| `549aa6998` | 修正建表失败路径清理逻辑的过时注释并补行为用例 |
| `89b5abc7e` | 删除复审确认的两处死代码 |
| `7dbf3c1e0` | 修复块长度越界时静默当文件结束导致损坏数据被无声截断的问题 |
| `9241f581d` | 修复单表查询去重表超过上限后静默放弃去重的问题 |
| `504db65a0` | 修复回滚保存点对已结束事务静默返回的问题 |
| `9a0e5953f` | 修复关闭存储时发起多余异步检查点的问题 |
| `6e28f1993` | 修复已存储LOB缺少data handler时只报裸空指针无法定位的问题 |
| `700b236cf` | 修复没有data handler的已存储LOB在unlink时空指针的问题 |
| `161c87676` | 修复没有data handler的已存储LOB在link时空指针的问题 |

## B3、P2 热路径性能（2 个提交）

按顺序 cherry-pick（旧 → 新）：

```bash
git checkout -b merge-b3 review/mervyn-consolidated
git cherry-pick 8135c4023
git cherry-pick d18c73dc3
```

| 提交 | 解决什么问题 |
|---|---|
| `8135c4023` | 修复覆盖索引查询每返回一行读两次主表的问题 |
| `d18c73dc3` | 修复全量备份把整个库读两遍的问题 |

## B4、测试基建 / SLT 门禁（16 个提交）

按顺序 cherry-pick（旧 → 新）：

```bash
git checkout -b merge-b4 review/mervyn-consolidated
git cherry-pick eeb79a7e6
git cherry-pick 5577a117d
git cherry-pick 0459f3347
git cherry-pick 71a92ffbe
git cherry-pick ee68d2079
git cherry-pick 3e7dc2f3a
git cherry-pick 071c1de76
git cherry-pick a9b0716cb
git cherry-pick 0d3a77c9c
git cherry-pick 39dac48f6
git cherry-pick e215f042a
git cherry-pick ec89521d9
git cherry-pick d86a347f6
git cherry-pick a1f4d1e5c
git cherry-pick 1da3d49db
git cherry-pick c2ab81d45
```

| 提交 | 解决什么问题 |
|---|---|
| `eeb79a7e6` | 修复SequenceLockedExceptionTest在worker线程失败时会永久挂死的问题 |
| `5577a117d` | 修复SLT回归门禁不计statement失败导致脚本里的DDL/DML坏掉仍然PASS的问题 |
| `0459f3347` | 修复CheckpointDurabilityTest失败时遗留库和目录污染后续用例的问题 |
| `71a92ffbe` |  |
| `ee68d2079` | 修复重启校验复制活跃元数据库导致的偶发假红 |
| `3e7dc2f3a` | 修复SLT门禁baseline只比失败个数导致同文件内替换失败仍然PASS的问题 |
| `071c1de76` | 补大表二级索引扫描的正确性护栏并记录一项未采纳的内存优化 |
| `a9b0716cb` | 修复跑套件会改写被跟踪的生成代码导致工作区变脏的问题 |
| `0d3a77c9c` | 修复测试数据目录残留导致第二轮套件大批报错的问题 |
| `39dac48f6` | 修复JDBC客户端trace文件写进模块目录留下未跟踪残留的问题 |
| `e215f042a` | 修复客户端事件循环注册失败时挂死整个套件的问题 |
| `ec89521d9` | 修复SLT门禁失败签名用截断SQL生成导致新增失败被当成已登记的问题 |
| `d86a347f6` | 把SLT门禁精选集从27个文件扩到39个文件以覆盖多表复杂查询 |
| `a1f4d1e5c` | 把SLT门禁精选集从39个文件扩到45个文件以覆盖更多表达式用例 |
| `1da3d49db` | 修复客户端用例偶发挂死时只有超时提示没有线程栈无法定位的问题 |
| `c2ab81d45` | 修复关连接时等待调度器执行任务没有超时会永久阻塞的问题 |
## 各批的重点验证用例（对照跑，快）

本分支新增的 29 个用例（都是「带修复绿、去掉修复红」的）：

| 批次 | 用例 |
|---|---|
| B1 | `GroupByAliasShadowTest`、`TruncateNotRollbackableTest`、`RollbackAfterQueryTest`、`IndexBuildingScanTest`、`IndexInsertDeleteSameTransactionTest`、`IndexRemoveKeyOnlyRowTest`、`ColumnStorageFullRowTest`、`ValueJsonTest`、`CorrelatedSubqueryTest`、`FlatQueryDedupeLimitTest`、`CreateTableFailureTest`、`AsyncCommitCallbackTest`、`CheckpointHandlerFailureTest`、`SavepointRollbackAfterCloseTest`、`DatabaseCloseIfNeededTest`、`IndexRemoveKeyOnlyRowTest`、`DataExceptionTest` |
| B2 | `LobWithoutHandlerTest`、`LobLinkWithoutHandlerTest`、`LobUnlinkWithoutHandlerTest`、`TruncateNotRollbackableTest` |
| B3 | `BackupManifestCrcTest`、`CoveredIndexScanTest`、`SecondaryIndexLargeScanTest` |
| B4 | `SltGateStatementTest`、`SltFailureKeyTest`、`SltBaselineSignatureTest`、`DiagnosticTimeoutTest`、`ClientSessionAwaitTest`、`GeneratedCodeIdempotentTest` |

跑法：`mvn -B -o -q -pl lealone-test test -Dtest=A,B,C`（多个用例用逗号分隔，不用引号）。

## 回退验证（每条修复自证的方式）

以 B1 的 `135d93201`（事务内查询后 ROLLBACK 看不到撤销结果）为例：

```bash
git checkout review/fixes-1
git revert --no-edit 135d93201        # 或者只把源码那一行改回去
mvn -B -o -q -DskipTests install
# 重启测试服务(核对 PID), 然后:
mvn -B -o -q -pl lealone-test test -Dtest=RollbackAfterQueryTest
# 期望: FAILURE - ROLLBACK 后必须恢复成 2 行, 实际 1
git revert --no-edit HEAD             # 恢复
```

其余提交同理：**提交信息里写了"回退后哪个用例会红、红成什么样子"**，照着做即可。

## 合入时必须一起看的三件事

1. **门禁基线**：B4 会扩大 SLT 精选集（27 → 45 个文件、20.8 万 → 37.6 万条），并重生成
   `lealone-test/src/test/resources/slt/baseline.txt`。如果只合其中一部分，请**整批合**，
   否则基线里的 `file` 行会和实际 files.txt 不一致。
2. **生成代码幂等**（`a9b0716cb`）：它让跑套件不再改写被跟踪的 `orm/generated/*.java`，
   如果跳过它，合入后第一次跑套件工作区就会变脏。
3. **测试数据自动清理**（`0d3a77c9c`）：跳过它会让**第二轮**套件大批报
   `Wrong user name or password`（同一目录残留上一轮的库）。

## 不在这 4 批里的东西

| 内容 | 为什么不在一起 |
|---|---|
| 6 项「实测后判定不改」 | 数据都在 `REVIEW_FIXES_SUMMARY.md` 第六节，合了反而是退步 |
| 台账/汇总/计划这三份文档提交 | 属于审阅材料，按需合 |
| `a7a85a6af`（`rowsort` 那次改错的回退） | 与 `f41a65626` 成对，两者都**不要合**（净效果为零） |
