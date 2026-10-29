# Lealone 缺陷台账（我方分支 vs 真实上游）

> 这份台账回答一个问题：**相对真实上游，我们到底修了什么、没修什么、凭什么这么说。**
> 深度分析（11 条上游未修项的现象/根因/探针细节）见 [`UPSTREAM_OPEN_ISSUES.md`](UPSTREAM_OPEN_ISSUES.md)。

| 项 | 值 |
|---|---|
| 真实上游 | `github.com/lealone/Lealone` master = **`34596f242`**（2026-10-29） |
| 我方分支 | `review/consolidated` = **`b3afc1cce`**（= fork `master`） |
| 我们的起点（merge-base） | **`b6c7cc654`**（2026-10-11） |
| 采集方式 | 逐条实证：同一份探针 / 同一份回归测试类，分别挂上游 classpath 与我方 classpath 跑 |

---

## 0. 口径：三个数不能混着用

| 数 | 含义 | 值 |
|---|---|---:|
| **raw 122** | `git rev-list --count b6c7cc654..b3afc1cce`（含 4 个 merge） | 122 |
| **去重 99** | 按 `git patch-id --stable` 去重后的唯一 patch | **99** |
| **语义唯一 98** | 第 20 对里 `d3fa1206d`(old) 比 `cb4f8c085`(new) 多 95 行（`StatementBase.copyParameterValues` + 对应测试），本次合并靠保留 old 侧才没丢 | 98 |

**为什么会有 19 组重复**：上一轮的工作被 rebase 过，历史里同时存在旧链（`c367985b6` 那套，已进 fork master）和新链（`46e3fdef0` 那套）。`git cherry` 在这里**无效**（两份副本相对上游起点都不在上游，会输出 118 行全 `+`），必须用 `patch-id`。

⚠️ **给后续合并的警告**：如果以 new 链为基重做合并，`StatementBase.copyParameterValues` 这段会**静默丢失**。

### 去重后 99 个提交的分类

| 类别 | 提交数 | 归并后 |
|---|---:|---:|
| **上游预存缺陷** | 64 | **30 个独立缺陷（F01–F30）** |
| 测试 / CI | 19 | — |
| 文档 | 5 | — |
| **自研过程缺陷**（只存在于我们新代码里） | 5 | 5（Blocker 级，见 §6） |
| **新功能** | 4 | 2 项（窗口函数、`= ANY/ALL` 数组） |
| **性能** | 2 | 2（P01/P02） |

### 证据分级（这一栏才有意义）

| 等级 | 条数 | 含义 |
|---|---:|---|
| **A · 双证据** | **21** | 上游复现红 + 我方复现绿（同一探针/测试类） |
| **B · 仅静态** | **10** | 只能在源码层证明"旧实现仍在"，其中 6 条的用例**在上游也通过**（未复现） |
| 性能 | 2 | 非正确性，两侧结果一致，只有耗时差异 |

---

## 1. 台账 A：**我们已修**的上游预存缺陷（F01–F30）

| ID | 现象（一句话） | 上游 master 现状 | 我们的提交 | 回归测试 |
|---|---|---|---|---|
| **F01** | 0 长度 payload 包在包头跨 read 边界时被**永久丢弃**；负长度让读位置回退 → CPU 空转 | 仍失败 | `6628fa146` | `NioZeroLengthPacketTest`(7) |
| **F02** | plan SQL 吞掉行值/列表/嵌套算术括号 → 视图落盘后**不报错但结果错**（`-(id+1)=-3` 上游返回 0 行，应 1 行） | 仍失败 | `480235474` | `ExpressionParenRoundTripTest` |
| **F03** | 三值逻辑偏差：`NULL AND FALSE`→NULL、`(NULL,NULL) IS NULL`→false、行值比较含 NULL 返回 FALSE 而非 UNKNOWN | 仍失败 | `41da6e262` | `PredicateLogicTest`(15) |
| **F04** | 数值语义：`SUM(NULL)/AVG(NULL)` 报 90015；`AVG(int)` 截断（AVG(1,2)=1）；`ROUND(-2.5)=-2.0`；DECIMAL 除法 30 位标度 | 仍失败 | `db4278df8` | `ValueSemanticsTest`(15)、`CastMatrixTest`(12) |
| **F05** | `OCTET_LENGTH('abc')`→6；`CAST('abc' AS UUID)` 不报错 | 仍失败 | `c7db404dc` | `StringFunctionMatrixTest`(12)、`CastMatrixTest` |
| **F06** | GROUP BY 的输入列被同名输出别名顶掉 → **静默少分组** | 仍失败 | `b9aaea882` | `GroupByAliasShadowTest` |
| **F07** | 关联子查询返回过期值；`IN/NOT IN (SELECT)` 只看首行 | 仍失败 | `ccd5dc076` | `CorrelatedSubqueryTest`(4)；SLT `select1.test` 1000/1000 |
| **F08** | `ALL` 量词在聚合/表达式参数里被当列名（`COUNT(ALL 1)`、`MAX(ALL 1+49)`） | 仍失败 | `f0ab00101` | SLT `random/expr/slt_good_1.test` 10000/10000 |
| **F09** | 过早求值：`CASE WHEN TRUE THEN 1 ELSE 1/0 END`、`COALESCE(1,1/0)` 报 `Division by zero` | 仍失败 | `f05b5652b`、`5e0c64967` | `DataExceptionTest` |
| **F10** | 合法 `CREATE SEQUENCE … MAXVALUE 2` 被拒；序列失效时抛**裸 NPE** | 仍失败 | `635485b6f`、`e3689c1c4` | `SequenceTest`、`SequenceValueInvalidTest` |
| **F11** | DDL 后 prepared statement 不重编译；DDL 回滚丢对象 | 仍失败（`recompileIfRequired()` 上游**不存在**） | `d3fa1206d`、`cb4f8c085`、`646df2651` | 4 类 19 例 |
| **F12** | 事务里 `TRUNCATE` 被接受且不可回滚 → **静默永久丢数据** | 仍失败 | `b53fab324` | `TruncateNotRollbackableTest` |
| **F13** | 只带主键的 DELETE 在补读不到整行时抛 NPE / 卡住 | 仍失败 | `a2bd014ce` | `IndexRemoveKeyOnlyRowTest` |
| **F14** | `Database.close` 被内部 session 挡掉 → 重启丢更新；checkpoint/备份后写不落盘 | 仍失败 | `b0fb341d4`、`a60d7b3c7`、`80ec8bc9f` | `DatabaseCloseIfNeededTest`(2)、`CheckpointDurabilityTest`(5) |
| **F15** | 并发扫描重复行 / 全 NULL 幽灵行（`this.columns is null` NPE） | 仍失败 | `f1ed620a4`、`fbd0670b9`、`125f1bce9`、`0b9e9ea8f` | `ScanDuplicateTest`、`NullRowScanTest`、`ConcurrencyCheckpointTest` |
| **F16** | 二级索引读到陈旧值 / 丢更新 / 注册与重试归属 | 仍失败（**仅静态**） | `1fb4e4b5d`、`466404e42`、`f6a0f47aa`、`903115091` | `CoveredIndexScanTest`、`IndexBuildingScanTest` |
| **F17** | 列存整行读取/墓碑行列值错；列存缺 column page 时 NPE | 仍失败（**仅静态**） | `6b45344eb`、`ea2e72211` | `ColumnStorageFullRowTest`(3) |
| **F18** | 隔离级别：可重复读读到更旧版本 / RC 把墓碑行当可见 / `count(*)` 绕过快照 / `LOCK_TIMEOUT` 从未生效 | 仍失败 | `999714d17`、`d49ce10af`、`2c47585d6`、`23da6a2dd`、`cc712b843` | `IsolationMatrixTest`(17) |
| **F19** | 事务槽位/迟到回调/重试重复执行/异步提交 NPE/`YieldableCommand` 被误清 | 仍失败（部分静态） | `9d5def9c8`、`b5288f61d`、`4b28dd90d`、`3ed45642a`、`19714bdb0`、`c55e5c242`、`e4d03e84c` | `AsyncCommitCallbackTest`、`SavepointRollbackAfterCloseTest` |
| **F20** | 检查点/存储：多余异步检查点、单 map 失败中止整轮、LogSync 空转、redo 恢复 O(n²)+偏移越界、node page 无 children、索引操作 StackOverflow | 仍失败（**仅静态**） | `722c5aa38`、`607247802`、`32874bdbb`、`72ec5755f`、`b4648c082`、`16aa42065` | `IndexBuildingScanTest` |
| **F21** | 调度器链表跨线程竞争、关连接等调度器**永久阻塞**、同步 JDBC 回调与调度线程等待 | 仍失败（**仅静态**） | `5d6235e81`、`e540d6b6d`、`5d965a3c4` | `ClientSessionAwaitTest`(2) |
| **F22** | 异步索引操作队列并发 + 插入回滚时空指针 | 仍失败（**仅静态**） | `cd4ec8aab` | `IndexInsertDeleteSameTransactionTest`(2) |
| **F23** | 已存 LOB 缺 handler 报**裸 NPE**（不含 lobId）；读 LOB 到末尾客户端永久等待 | 仍失败 | `c79126cce`、`4db4caa07` | `LobWithoutHandlerTest`、`InMemoryLobTest` |
| **F24** | 加密 SCRIPT 错密码→块长度垃圾→EOF/OOM；SCRIPT 不真加密且导出 SystemSchema；备份读文件口径 | **部分上游已修**（见 §4） | `8a52a783d`、`ab8670a64`、`2921e8d90` | `FileStorageTest`(5)、`ScriptRoundTripTest`、`BackupTest` |
| **F25** | 协议健壮性：`len<<1` 溢出写坏包、到尾 `read()` 抛未受检 `BufferUnderflowException`、读写上限不对称、`putChars(null)` NPE | 仍失败（**仅静态**） | `e9831e251` | `StringCodecTest`、`TransferStreamBoundaryTest` |
| **F26** | JDBC 同步路径抛**非 SQLException**（未受检 `DbException`）→ `catch (SQLException)` 抓不到 | 仍失败 | `35e8c2ec7` | `JdbcStatementTest` |
| **F27** | `EXECUTE SERVICE` 四处缺陷（返回值/空模型/序列化/同步方法静默 NULL） | 静态（用例上游**也绿**） | `53eabe0df` | `ExecuteServiceTest` |
| **F28** | 配置解析：`engine.enabled` 与日志级别 | 静态 | `c4dde3483` | `SchedulerConfigTest` |
| **F29** | 改被外键引用的主键字段 NPE；并发更新同行丢更新 | 静态 | `dc1f4fd5f`、`e1b63282c` | `ConstraintReferentialTest` |
| **F30** | 同事务「删后按同一唯一索引键重写」→ 索引写错、重启丢记录 | 静态（上游 `a88f8dde5` 另一方案，**并带入 U1 脏读**） | `0ae4948fd` | `IndexInsertDeleteSameTransactionTest`(2) |

### 台账 A 附：性能与测试基础设施

| ID | 内容 | 上游 | 我们的提交 |
|---|---|---|---|
| **P01** | 字符串协议编解码逐字符 + 大包批量插入被延迟 ACK 拖慢（100k 行×85 字符：默认 287ms vs 显式固定 8K ≈9.1s） | 未优化 | `9070d1bba` |
| **P02** | 发送缓冲默认值与按连接自适应；`scheduler_count` 不再写死 8 | 未优化 | `1c427d362`、`3290fc83a` |
| **U2** | 干净 checkout 下 **`lealone-test` 无法编译**（`HttpServerStart.java:117` 引用不存在的 `tmptest.model.User`） | 仍失败 | `4cf397f45` |

---

## 2. 台账 B：**上游仍未修**、我们也没做（或刻意不做）

| ID | 问题 | 复现 | 我们的状态 |
|---|---|---|---|
| **U7** | 加密文件头 `len` 无校验 → `NegativeArraySizeException` / `OutOfMemoryError` / `IllegalStateException`；`928c07017` 写出的旧文件用**正确密码**打开即崩 | 手工改 header 偏移 23 的 4 字节（详见 deep-dive 文档 §2.U7） | 未修。建议：加格式版本号 + `0 < len <= 1024` 校验 |
| **U8** | 密码校验挂在 `SysProperties.CHECK` 上 → `-Dlealone.check=false` 时**静默读到垃圾** | `-Dlealone.check=false` + 错密码 | 未修。建议：校验无条件执行 |
| **U9** | PBKDF2 迭代数只有 **10** → 文件是离线口令猜测预言机（1 次 SHA-256/猜测） | `FileEncrypt.java:59` | 未修。建议 ≥ 60 万或 Argon2id |
| **U10** | `IndexKey.copy()/copySelf()` 返回同一实例、忽略 `lock` 参数（`IndexKey.java:78-85`、`127-134`） | code review | **不适用**：我们用墓碑方案（`0ae4948fd`），不改 `Lock` 契约 |
| **U11** | `TransactionMap.Cursor.getKey()` 唯一索引返回 `value`，且 `next()` 前为 `null`（`AOTransactionMap.java:197-200`） | code review | **不适用**：未合入 `a88f8dde5` |

## 3. 台账 C：**上游自己的回归**（我们不引入）

| ID | 问题 | 复现 | 我们的状态 |
|---|---|---|---|
| **U1** | 可重复读/串行化**脏读**：`TransactionalValue.java:179` 缺 `return`，穿透到 `read_uncommitted` | 上游自带 `IsolationLevelTest` → `expected null, but was:<a>`；我方 `OK (1 test)` | **没有这个回归**：`a88f8dde5` 不是我方祖先；我们另有 `999714d17`/`d49ce10af`/`2c47585d6`/`23da6a2dd`/`cc712b843` 五笔相关修复 |

---

## 4. 上游近期提交与我们重复的部分（合并前必看）

| 上游提交 | 内容 | 与我们 |
|---|---|---|
| `a88f8dde5` | #280 同事务删后按同一唯一键重写（`IndexKey`/`Lock` 方案） | **主题与我们 `0ae4948fd` 重复**，但**引入 U1 脏读** → 不可直接合 |
| `126165952` | `FileStorage` 用 `DbSetting.ENCRYPTION_KEY` | 与我们 `ab8670a64` 的 `FileStorage` 部分重复（还在修同一处） |
| `928c07017` + `34596f242` | 加密文件头存密码校验值、错密码报 90049 | 与我们 `8a52a783d` 主题重复但**方式不同**（我们存密文内 magic，不落派生值） |
| `1c45274a8` | SCRIPT 不生成 SystemSchema 表 | 与我们 `ab8670a64` 主题重复 |
| `dfbf8b16e` | 第二次 `BACKUP TO` 读到空数据（`getInputStream()` 加 `resetFilePointer()`） | 与我们 `2921e8d90` **主题重复**（方式不同） |

> 上游修完这两轮之后，`FileStorageTest` **仍然是 2 红**（`testCorruptedChunkLength`、`testChunkLengthExceedsFileSize`），所以 F24 的"块长度校验"部分上游仍未覆盖。

---

## 5. 仅静态证据的 10 条（不要当作已复现）

`F16`、`F17`、`F20`、`F21`、`F22`、`F25`、`F27`、`F28`、`F29`、`F30`

其中 **6 条对应的用例在上游也通过**（`CoveredIndexScanTest`、`ColumnStorageFullRowTest`、`SavepointRollbackAfterCloseTest`、`IndexInsertDeleteSameTransactionTest`、`IndexBuildingScanTest`、`ExecuteServiceTest`）——
说明"旧实现仍在"不等于"现在就能复现"，这些条目**只能算静态风险**，不能当作已证实的缺陷。

另有 2 条的修复我们**探针未复现**：`125f1bce9`（IN 列表重复返回）、`0b9e9ea8f`（索引构建期 ORDER BY 错序）——需要特定计划/并发条件。

---

## 6. 我们自己的 5 个 Blocker（**不是**上游问题，属自研过程）

| Blocker | 问题 | 修复 |
|---|---|---|
| B1 | 窗口函数在 DML/DDL 里被**静默求值为 NULL**（`UPDATE t SET v = RANK() OVER ()` 写 NULL；`DELETE … WHERE ROW_NUMBER() OVER () = 1` 删除行） | `f3f30264d` |
| B2 | `ConditionAny.getSQL()` 产出不可重解析 SQL → `= ANY((1,3))` 的视图建了查不了 | `4061b2d40` |
| B3 | 窗口执行无让出/无界缓冲/隐藏列污染计划 | `749894b0b` |
| B4 | `ANY('a,b')` 隐式按逗号拆分 → **静默错值** | `a0973f26b` |
| B5 | （插件仓库）`packetFinished` 递归派发致连接卡死、`normalize()` 改坏字面量 | `cdc69f8`、`e7c29f3`（lealone-plugins） |

这些**不应**计入"上游缺陷"：上游没有这些代码路径。

---

## 7. 双方都未修的 SLT 偏差

我方 12 条 baseline 偏差（14 条失败分布 8 个文件，2 条被 allowlist 吸收，净 12 条落 7 个文件）。
交叉比对后：**11 条**是我方 `AVG` 返回 `DOUBLE/DECIMAL` 造成的类型传播偏差（上游侧 8 条通过、3 条因 F08 无法比较），
**仅 1 条**（`random/expr/slt_good_91.test:31229`）双方同因失败（数值溢出）。
详见 `lealone-test/src/test/resources/slt/baseline.txt`。

精选集（53 文件 / 442,461 条语句）净失败对照：

| 引擎 | 净失败 | 文件数 |
|---|---:|---:|
| 上游 master `34596f242` | **15,079** | 38 |
| 我们 `b3afc1cce` | **12** | 7 |

---

## 8. 复查记录

本文与 deep-dive 文档由**两个独立 agent（干净 context，未参与原始测写）**先后审计：

1. **第一次**（针对 deep-dive 的 11 条）：纠正 5 处实质错误 + 5 处瑕疵（`Function "ALL"` 89→304、`SELECT ALL 1` 定性、§3"双方都未修"定性、"其它 ≈330"→82、U5 样例归属等），并为 U7 补了"用旧格式文件实测崩溃"的证据。
2. **第二次**（针对本台账）：用 `patch-id` 完成去重（122→99，并发现第 20 对漂移 95 行）；用绝对 classpath 双向跑探针与 30 个回归测试类（**23 个在上游红、30 个在我们全绿**）；补出 15 组此前未入文档的上游预存缺陷；并把 10 条只有静态证据的条目单独隔离，避免夸大。

**已知未复核项**（诚实标注）：
- U3/F08、F07 的 SLT **总量**数字未重跑全量（只抽样 `slt_good_1.test` 284 红、`select1.test` 217 红，方向一致）。
- U7/U8 的伪造 header 实验第二次审计未重做（第一次已做，结论保留）。
- `F15` 的两笔、`F27` 一笔无行为级证据（见 §5）。

---

## 9. 复现环境（关键前提）

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21   # 裸 java 是 Java 8

# 上游（lealone-test 编译不过 → 必须跳过该模块）
git worktree add --detach /tmp/led-up refs/remotes/upstream/master
cd /tmp/led-up && mvn -o -q -Dmaven.test.skip=true -pl '!lealone-test' package
CP_UP=$(ls -d /tmp/led-up/*/target/classes | tr '\n' ':')$(cat /tmp/cp.txt)

# 我们
cd <our-worktree> && mvn -o -q -DskipTests install
CP_OURS=$(ls -d <our-worktree>/*/target/classes | tr '\n' ':')$(cat /tmp/cp.txt)
```

⚠️ **classpath 必须用绝对路径**：相对路径在某些 CWD 下会退化成 `~/.m2` 里的 jar（= 我们自己的类），
导致"上游也全绿"的假结论——审计方第一次就踩到了这一点。

SLT 复现另有三条硬性前提（引擎读 CWD 的 `target/slt-db`、`--base-dir` 必须含 `slt`、精选集与 allowlist 位置），
详见 `UPSTREAM_OPEN_ISSUES.md` §0。
