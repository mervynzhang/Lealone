# 交接文档：Lealone 核心库本轮改动（等第三方模型评审）

> 写于本轮修复完成后、**尚未合并**到 `review/consolidated` 之前。
> 目的：让另一个模型在合并前独立评审核心库改动。请重点看第 5 节的"评审重点"。
> 所有数字均为实测，标注了【事实】/【推测】。

---

## 1. 背景

本轮起因是 PostgreSQL 插件兼容性/性能工作，过程中通过三路代码评审发现了 **5 个 Blocker** 和若干 High，
其中 4 个在 Lealone 核心库。这些问题的共同特征：**不报错、可能落盘、事后极难定位**，
而当时的测试（399~406 全绿）一条都没覆盖。

## 2. 分支与工作区地图【事实】

| worktree | 分支 | HEAD | 内容 | 状态 |
|---|---|---|---|---|
| `/tmp/wt-all` | `integration/all-fixes` | 见 `git log -1` | **本文件所在分支**，6 个分支/分支头的合并结果（`480235474` 为 fast-forward，其余 3 次为 merge commit，均零冲突） | **433 tests 绿** |
| `/tmp/wt-fix1` | `review/consolidated`→`fix/p0-perf` | `e9831e251` | P0：字符串协议编解码 + 发送缓冲自适应 | 已 push |
| `/tmp/wt-any` | `fix/any-array` | `abad9f55a` | `expr = ANY/ALL(array)` 表达式 | 已 push |
| `/tmp/wt-win` | `fix/window-row-number` | `73780ca23` | 窗口函数最小子集 + 2 Blocker 修复 | 已 push |
| `/tmp/wt-paren` | `fix/sql-paren-print` | `480235474` | 行值/列表括号打印（系统性缺陷） | 已 push |
| `/tmp/wt-net` | `fix/zero-length-packet` | `6628fa146` | 0 长度包丢失 + 负长度包 | 已 push |
| `/tmp/wt-any2` | `fix/any-csv-safety` | 见 `git log -1` | 第三方评审 **H1**：`= ANY('a,b,c')` 不再隐式拆分（改为 `'{a,b,c}'`） | 已 push（基于 `b51002b2a`） |
| `/tmp/pl-cat` | `fix/pg-jdbc-funcs` | `e7c29f3` | **PostgreSQL 插件**（不在核心评审范围，仅供参考） | 已 push |

集成分支的合并顺序：`73780ca23`(基) ← `480235474`(fast-forward) ← `6628fa146`(merge) ← `3290fc83a`(merge) ← `e9831e251`(merge)。

> ⚠️ **勘误（第三方评审指出）**：本文初版只合并到 `3290fc83a`，**漏了 `fix/p0-perf` 的最后 3 个提交**
> （`1c427d362` / `d8ab45ad4` / `e9831e251`），导致集成分支一度携带已被否决的 64KB 发送缓冲默认值。
> 已补 `git merge e9831e251`（零冲突），现在 `NetClientBase` 的默认值是 **8K + 自适应上调**。
> 补合并后全量回归：**433 tests, 0 failures, 0 errors, 10 skipped**。

> 主 checkout `/home/jovyan/data/work/lealone` **全程未改动**（在别人的分支上）。

## 3. 核心库改动逐条说明

### 3.1 窗口函数最小子集（分支 `fix/window-row-number`，基于 `abad9f55a`，分支头 `73780ca23`）

> **勘误**：`73780ca23` 自身的提交信息是「优化 ANY/ALL」，它是该分支的**头提交**而不是窗口功能的提交。
> 窗口相关实际提交：`c0f27b52`（最小子集）、`f3f30264d`（Blocker 1）、`4061b2d40`（Blocker 2）、`749894b0b`（High 3/4/5）。
- 新增 `lealone-sql/.../expression/WindowFunction.java`（695 行）、`lealone-sql/.../query/WindowResultTarget.java`
- 改 `SQLParserBase`（解析 `OVER (...)`）、`Select`、`QOperator`、`YieldableSelect`、`BuiltInAggregate.isDistinct()`、3 个 visitor
- **支持**：`ROW_NUMBER/RANK/DENSE_RANK` + `COUNT(*)/COUNT(x)/SUM/MIN/MAX/AVG`；`OVER ()`/`PARTITION BY`/`ORDER BY`
  （ASC/DESC/NULLS FIRST|LAST）；单表、多表 join（含 LEFT JOIN）、派生表/视图的顶层 select 列；多窗口共存；外层 ORDER BY/LIMIT/OFFSET
- **明确不支持**（显式报错，不静默给错结果）：窗口帧 `ROWS/RANGE/GROUPS`、命名窗口、`LAG/LEAD/NTILE` 等、
  聚合窗口带 ORDER BY（running 语义）、`DISTINCT`/`FILTER`、与 `GROUP BY/DISTINCT/FOR UPDATE` 混用、窗口出现在 WHERE/HAVING/ORDER BY/表达式内部
- 实现方式：解析阶段把聚合参数/分区键/排序键作为**隐藏列**追加到 select 末尾 → 物化后按 (分区键,排序键) 稳定排序 → 逐分区写回窗口列
- **Blocker 修复**（`f3f30264d`）：`readOver()` 缺 `currentSelect == null` 守卫，导致
  `UPDATE t SET v = RANK() OVER ()` **静默写 NULL**、`DELETE ... WHERE ROW_NUMBER() OVER () = 1` **静默删行**。
  现在所有 DML/DDL 形态当场抛语法错（`readAggregate:2313` 本来就有这个守卫）
- **High 修复**（`749894b0b`）：① 窗口计算改成可恢复步骤（每 N 行 `yieldIfNeeded`+`checkCanceled`，取消能中途退出）；
  ② 缓冲受 `MAX_MEMORY_ROWS` 约束（超限抛新错误码 90150）；③ 隐藏列按 SQL 去重、分布式 plan 不打印隐藏列

### 3.2 `expr = ANY/ALL(<array>)`（`abad9f55a`）
- 新增 `lealone-sql/.../expression/condition/ConditionAny.java`
- 支持 `= <> > >= < <=` × `ANY/SOME/ALL`；右侧支持数组值、PG 风格花括号数组字面量 `'{a,b,c}'`、子查询（复用 `ConditionInSelect`）
- 三值逻辑：命中即 TRUE；无命中且含 NULL → UNKNOWN；`ALL` 有决定 FALSE 即 FALSE；空数组 ANY=FALSE/ALL=TRUE
- `getSQL()`/`optimize()`（常量数组改写 `ConditionIn`，走索引）/`mapColumns`/`NOT` 改写 均已覆盖
- **Blocker 修复**（`4061b2d40`）：`getSQL()` 打印 rhs 时括号被 `setEnclosed(false)` 吞掉 → 视图/CTE 建成功但查询失败
- **H1 修复**（本分支 `fix/any-csv-safety`，基于 `b51002b2a`，第三方评审要求）：
  字符串右值**只有花括号包裹时**才按数组字面量解析（新增 `ArrayLiteralParser`，支持 `{a,b,c}`、
  `{"a,b","c"}` 双引号元素、`\"`/`\\` 转义、`NULL` 元素、`'{}'` 空数组）；普通字符串常量（哪怕含逗号）
  一律按**单值**处理；花括号字面量解析失败抛新错误码 `90151`，**不退化成单值**。
  行为变化与影响面见 8.1。

### 3.3 行值/列表括号打印（`480235474`，系统性缺陷）
- 根因：`StatementBuilder.enclosed` 是**沿调用链继承给整棵子树**的开关；`Select.getPlanSQL`/`UpDel.appendPlanSQL`
  为省掉整条 WHERE 最外层那对冗余括号调 `setEnclosed(false)`，把行值/列表、标量子查询、**嵌套算术**、
  NOT 里的 AND/OR 组合等**重解析必需**的括号一起吞了
- 最危险的不是"建视图后查不了"，而是**不报错但结果变了**：`(id+1)*2 = 4` 打印成 `ID + 2 = 4`
- 修法：`StatementBuilder.enBegin(boolean required)` 区分可选/必需括号；`Expression.appendChildSQL` 用独立 builder
  打印子表达式（"省略最外层冗余括号"不再继承进子树）；`ExpressionList`/`SubQuery`/嵌套 `Operation` 强制输出结构性括号；
  `ConditionNot`/`ConditionAndOr` 显式声明操作数括号需求；`Select`/`UpDel`/`SelectUnion` 各子句逐个用 `appendChildSQL`
- 新增 `ExpressionParenRoundTripTest`：20 组形态 × 3 条落盘路径（建视图查结果 / 从 `information_schema.views` 取定义重放 / 作为 CTE）
  + GROUP BY/ORDER BY 行值 + CHECK 约束 DDL 重放 + "不许多余括号"反向断言

### 3.4 0 长度包丢失 + 负长度包（`6628fa146`，**最值得评审的一条**）
- 根因：`packetLength` 同时承担"长度值"和"状态哨兵"。补包头路径读完包头后 `packetLength == 0` 被当成
  "还没读到 packetLength"，既不保存也不调 `conn.handle` → 包被永久丢弃
- 实测【事实】：5000 个 0 长度包、每个包头都跨 read 边界（1+3 切分）→ **0/5000 收到**（修复后 5000/5000）；
  真实随机分片下表现为 5000 条 PG `Sync` 丢 5 个响应
- 修复：`NioEventLoop.java:205-215` 补包头完成后立即 `checkPacketLength`；`packetLength == 0` 时立即 `conn.handle(...)`
- **负长度**：`getPacketLength` 返回 `len - 4`（PG 包头包含自身 4 字节），客户端发 `len < 4`（或 `0xFFFFFFFF` = -1）即得负值。
  旧代码 `checkPacketLength` 只检查"是否超过 maxPacketSize"，负值会走到 `nextPos = position + packetLength` → **position 往回退**
  → 同一批字节被反复当包头解析（可构造 CPU 空转/流错位）。现在 `packetLength < 0` 直接抛 `IOException` 并关连接
- 新增 `NioZeroLengthPacketTest`：不依赖 PG 插件，loopback socket + 自定义 4 字节长度协议直接驱动 `NioEventLoop.read()`，
  7 个测试覆盖包头各切分/5000 包 pipeline/单次 read 多包/0 与数据混排/maxPacketSize 边界/超长/负长度

### 3.5 P0 性能（`3290fc83a`）
- `DataBuffer.putChars`/`NetBuffer.appendChars`/`TransferOutputStream/InputStream`：字符串编解码从"逐字符"改"整串批量"
  （**线上字节与旧实现逐字节一致**，含 null/空/中文/emoji/不成对代理项/10 万字符边界，有 `StringCodecTest` 断言旧格式字节）
- `NetClientBase`：`SOCKET_SEND_BUFFER_SIZE` 默认保持 **8K**，大批量写时按连接**自适应上调**（显式配置时禁用自适应）
  - 实测【事实】100k 行 × 85 字符批量插入：默认配置中位数 **287ms**；显式固定 8K（关自适应）**≈9.1s**；显式 64K ≈310ms
  - 为什么不能直接把默认值改成 64K：`setSendBufferSize` 会关掉 Linux 自动调优（`tcp_wmem` 本可放大到 4MB），
    1000 连接 ≈160MB 不可换出内核内存（原 ≈48MB）
- `readString` 读到尾部返回 -1（与 `read(byte[],off,len)` 语义统一）；写端/读端统一 2^30-1 字符上限
- 测试配置 `scheduler_count` 不再写死（原写死 8；改后显式 4，避免高核 CI 起上百个调度线程）

## 4. 我已验证的内容【事实】

全部由我在**合并后的集成分支**上亲自跑过（不只看 subagent 报告）：

| 验证项 | 结果 |
|---|---|
| 全量回归 `mvn -o test -pl lealone-test` | **424 tests, 0 failures, 0 errors, 10 skipped, BUILD SUCCESS** |
| H1 分支（`fix/any-csv-safety`）全量回归 `mvn -o test -pl lealone-test` | **435 tests, 0 failures, 0 errors, 10 skipped, BUILD SUCCESS**（基线 433 + 2 个新增用例方法） |
| 4 种 DML 形态里的窗口函数 + 数据快照 | 全部抛 `Syntax error ... OVER[*] ()`，数据逐行不变 |
| 10 种表达式形态建视图 + 读 `information_schema.views` 定义 | 全通过（含修复前会失败的 `(a,b) IN (...)`、`(id+1)*2=4`） |
| `SET MAX_MEMORY_ROWS 3` + 5 行窗口查询 | 报 `90150` 明确资源不足错误 |
| `EXPLAIN` 单/多窗口 | 只打印可见列，无隐藏列 |
| `NioZeroLengthPacketTest` | 7/7 |
| P0 默认配置 vs 显式固定 8K（3/2 轮） | 254ms vs 8958ms |
| PostgreSQL 插件（参考）兼容矩阵 138 条 | 55.1%（本轮开始 50.0%，最初基线 30.7%） |

## 5. 评审重点（请逐条给结论，带 file:line）

### A. 异步模型
1. 窗口函数把结果**全量物化**后在 `stop()` 之外的可恢复步骤里排序（`QOperator`/`WindowResultTarget`/`WindowFunction`）：
   是否还有"看似异步其实同步跑完"的路径？`yieldIfNeeded` 的粒度（每 N 行）是否会在超大分区下让单个分区饿死同线程其它 session？
2. `checkCanceled` 与 `pendingException` 的交互：取消时是否真的不再做 O(n log n) 计算、缓冲是否释放（`abortWindow()`）。
3. `YieldableSelect.disableOlap()`（有窗口就禁用 OLAP 算子）是否会让大表扫描失去流式能力，与"必须物化"叠加后是否有内存风险放大。

### B. 内存与资源
4. `MAX_MEMORY_ROWS` 超限抛错（而非落盘）是否是可接受的设计？`LIMIT` 语义下（`SELECT ... OVER (...) LIMIT 1`）仍会物化到 maxMemoryRows 行是否会让用户意外。
5. 隐藏列去重后，`expressions` 数组下标与 `havingIndex`/`orderIndex` 等既有约定是否仍自洽（分布式 plan 跳过隐藏列的实现是否覆盖所有路径）。
6. `NetBuffer`/`DataBuffer` 的 `putChars/appendChars` 是否真的只在 per-scheduler 单线程上下文使用（评审者此前判断是，请复核）。

### C. 协议与网络（3.4）
7. `packetLength == 0` 时立即 `conn.handle(this.inputBuffer, false)` 是否与"同一 read 里后续还有包"的循环正确协作（会不会少处理/重复处理）。
8. 负长度拒绝的位置是否正确（是否覆盖 `getPacketLengthByteCount() <= 0` 的 HTTP 分支、BIO 路径 `NioWritableChannel.read`）。
9. `checkPacketLength` 现在只拒绝负值与超长；`packetLength == maxPacketSize` 的边界、以及"一次 read 里包头跨边界 + payload 也跨边界"的组合是否还有未覆盖场景。

### D. 语义正确性
10. `pattern`：窗口的 ORDER BY 默认 NULL 位置采用 PG 语义（ASC=NULLS LAST、DESC=NULLS FIRST），而 Lealone 普通 ORDER BY 默认相反——这个不一致是否是坑。
11. ~~`= ANY(<逗号字符串>)` 的隐式拆分会把 `'a, b'` 拆成两个元素（含 trim），是否与用户预期冲突；是否应只在 PG 方言下启用。~~
    **已修复（H1，分支 `fix/any-csv-safety`）**：改为显式花括号语法 `'{a,b,c}'`，普通字符串（含逗号）按单值，
    解析失败报 `90151`；行为变化声明见 8.1。
12. 括号打印改动后，`EXPLAIN`/plan SQL 是否对所有既有测试保持逐字一致（有"不许多余括号"反向断言，但覆盖面有限）。

### E. 代码风格与一致性
13. 是否复用了仓库既有工具（`Utils`/`DbException`/`StatementBuilder`/`StringUtils`），注释语言（仓库为中文）、命名、方法长度（是否 >80 行）。
14. 新增的 3 个 visitor 节点是否全覆盖，新增错误码/消息是否符合仓库约定（中英文双语）。
15. 测试是否覆盖错误路径（本轮教训），特别是：视图/CTE 落盘 round-trip、异步中途失败、字面量不被正则改写。

## 6. 如何复现/验证

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21   # 裸 java 是 Java 8
cd /tmp/wt-all && mvn -o -q -DskipTests install
mvn -o test -pl lealone-test                       # 424 tests
mvn -o -pl lealone-test -Dtest=WindowFunctionTest test
mvn -o -pl lealone-test -Dtest=ConditionAnyTest test
mvn -o -pl lealone-test -Dtest=ExpressionParenRoundTripTest test
mvn -o -pl lealone-test -Dtest=NioZeroLengthPacketTest test
mvn -o -pl lealone-test -Dtest=StringCodecTest test
# 若 mvn test 因离线缺 surefire-junit3 失败，用：
# javac + java -cp "$(cat /tmp/cp.txt)" org.junit.runner.JUnitCore <类名>
```

起一个调试实例：

```bash
mkdir -p /tmp/x && cd /tmp/x
setsid java -cp "$(cat /tmp/cp.txt)" com.lealone.main.Lealone -baseDir /tmp/x/srv -port 9401 > /tmp/x/srv.log 2>&1 < /dev/null &
# 等日志出现 "Lealone started"，然后
java -cp "/tmp/pgplug:$(cat /tmp/cp.txt)" Mk   # 或自己连 jdbc:lealone:tcp://localhost:9401/lealone 建库
```

## 6.1 跑全量测试的前置条件（第三方评审踩到过）

`lealone-test` 里的 `SqlTestBase` 系用例**要求 9210 上已经有一个测试服务**，否则约 99 个用例直接
`Connection refused: localhost/9210`。这不是缺陷，是既有的测试架构前提。

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21
mkdir -p /tmp/srv9210 /tmp/cwd9210          # CWD 必须在仓库外，否则生成的代码落到仓库根
cd /tmp/cwd9210 && setsid $JAVA_HOME/bin/java \
  -cp "<repo>/lealone-test/target/test-classes:$(cat /tmp/cp.txt)" \
  com.lealone.main.Lealone -baseDir /tmp/srv9210 \
  -config <repo>/lealone-test/src/test/resources/lealone-test.sql -port 9210 \
  > /tmp/srv9210.log 2>&1 < /dev/null &
# 等日志出现 "Lealone started"，并核对监听 9210 的 PID 就是刚起的进程（残留旧进程会让你测到旧版本）
```

classpath **必须**包含 `lealone-test/target/test-classes`（分隔符不能漏），否则服务端缺少测试资源配置。

## 7. 环境事实与坑

- 本机是 **cgroup 限制 32 核**（`cpu.max=3200000 100000`），不是 `nproc` 报的 192；`availableProcessors()=32`【事实】
- **测试实例必须在 jar 重新打包之后启动**，否则跑的是旧类（本轮因此出过一次"假红"：`format_type` 测试）【事实】
- `~/.m2` 里的核心 jar 会被任意 worktree 的 `mvn install` 覆盖 → 切分支验证前先确认 jar 来源【事实】
- 已知被占用的端口/实例（别人的，**不要杀**）：9210、9215、9216、9217、9296 等
- 压测注意：同 pod 多 agent 并行时机器噪声大，绝对值要用交叉重复取中位数【事实】

## 8. 已知限制与"不做"的决定（含理由）

| 项 | 决定 | 理由 |
|---|---|---|
| 窗口帧 `ROWS/RANGE/GROUPS`、running aggregate、命名窗口、`LAG/LEAD` | 不做 | 显式报错即可保证不产生错误结果；实现成本远高于收益 |
| 窗口超 `MAX_MEMORY_ROWS` 落盘 | 不做（报错） | 窗口计算需要随机访问行，落盘流不满足 |
| `ARRAY[...]` 字面量 | 不做 | Lealone 的 `[` 是下标语法，与既有语法冲突；用 `(1,2,3)` 或字符串字面量 `'{1,2,3}'` |
| `ConditionAny` 的裸逗号值列表 `ANY(1,2)` | 不做 | 标准要求数组；`(1,2)` 已是既有数组/行值字面量 |
| `= ANY('a,b,c')` 的裸逗号字符串 | **不再支持**（按单值处理） | 隐式拆分让 `'Doe, John'` 静默错值（H1）；数组改用 `'{a,b,c}'` |
| 以 `{` 开头、但无法解析成数组字面量的字符串（如 JSON `'{"a":1}'`） | 当数组解析并报 `90151` | 宁可显式报错也不静默退化成单值；确需单值请改用 `IN ('{"a":1}')`（`ConditionIn` 不经过数组解析） |
| 窗口函数（`ROW_NUMBER()` 等） | **仅支持单机** | 分布式 plan 会打印窗口函数本体，跨节点场景没有验证（详见 8.1） |
| `~*`/`!~*`（大小写不敏感正则） | 不做 | 核心无对应实现，退化为 `REGEXP`（与旧行为一致） |
| 分布式 plan SQL 真集群验证 | 未做 | 无集群环境；只验证了 `EXPLAIN`/本地路径 |
| `(a,b) IN (SELECT c1,c2 ...)` | 不支持 | 引擎拒绝多列子查询（既有），非打印问题 |

### 8.1 行为变化声明（第三方评审要求）【事实】

1. **`= ANY('a,b,c')` / `= ALL('a,b,c')` 不再按逗号拆分**：普通字符串常量一律按**单值**处理，
   要表达数组必须写花括号字面量 `'{a,b,c}'`（PG 风格；支持 `'{"a,b","c"}'`、`\"`/`\\` 转义、
   `NULL` 元素、`'{}'` 空数组）。原因：旧行为会把 `'Doe, John'` 静默拆成 `'Doe'` 和 `'John'`，
   真正的单值反而匹配不到，也不报错。具体影响面：
   - `'a,b,c'`、`'1,2,3'` 这类写法以前是 3 个元素，现在是 1 个元素（`'b' = ANY('a,b,c')` 由 TRUE 变 FALSE）；
   - `'a, b, c'`（逗号后带空格）同理，以前会 trim 成 3 个元素；
   - `= ANY('')` 以前是空数组（ANY=FALSE），现在是单个空字符串元素；空数组要写 `'{}'`；
   - JDBC `setString` 参数、表列值也遵循同一规则：含逗号的普通字符串是单值，只有花括号才当数组；
   - 以 `{` 开头但写错（括号不配对、空元素、多维数组等）→ 错误码 `90151`，不会退化成单值。
2. **DML 里的窗口函数以前静默求值为 NULL，现在是语法错**（有意收紧）：
   `UPDATE t SET v = RANK() OVER ()` 旧行为是**写入 NULL**、`DELETE ... WHERE ROW_NUMBER() OVER () = 1`
   旧行为是**静默删行**；现在解析阶段直接抛 `Syntax error ... OVER[*] ()`。这是窗口函数 Blocker 修复
   （`f3f30264d`）的一部分，第三方评审要求显式声明这条行为变化。
3. **窗口函数仅支持单机**：分布式 plan 会打印窗口函数本体（隐藏列已不打印），跨节点执行没有验证；
   对外说明与文档都必须标注"**窗口函数仅支持单机**"。

## 9. 流程教训（本轮最大收获）

> **"测试全绿"不等于可合。** 本轮 5 个 Blocker 全部在"399~406 tests 全绿"状态下被评审发现，
> 且都具备"不报错、可能落盘、事后难查"的特征。

后续三类改动必须专门覆盖错误路径：
1. **解析器/语法扩展**（尤其正则文本改写）→ 要有"字面量不被改写"的逐字节断言；
2. **异步回调链** → 要有"中途失败后连接/会话仍可用"的用例；
3. **表达式打印**（视图/CTE/CHECK 落盘）→ 要有"建了就能查"的 round-trip 回归。

另外两条具体教训：
- `null`/`0` 这类"合法值"不能同时当状态哨兵（`packetLength == 0` 丢包就是这么来的）；
- 默认值优化要先算**每连接/每实例的资源成本**，再谈吞吐收益（64KB 发送缓冲那条）。
