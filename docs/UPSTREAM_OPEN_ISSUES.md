# upstream master 未修复问题清单（对照我们的分支）

- **上游**：`github.com/lealone/Lealone` master = `34596f242`（2026-10-29，"加密文件头只存放经过SHA256的密码"）
- **我们**：`review/consolidated` = `39ee21d6f`（含本轮 121 个提交）
- **采集方式**：逐条实证复现（不是读代码猜），所有数字均为本机实测
- **纪律**：本文只列**上游仍未修**的问题；每条都给可执行的复现方式。SLT 类问题给出**原始 `.test` 文件名 + 行号**。

---

## 0. 复现环境（先看这一节，否则会得到假失败）

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21   # 裸 java 是 Java 8
# 上游代码
git fetch https://github.com/lealone/Lealone.git master:refs/remotes/upstream/master
git worktree add --detach /tmp/wt-up refs/remotes/upstream/master
cd /tmp/wt-up && mvn -o -q -Dmaven.test.skip=true -pl '!lealone-test' package   # lealone-test 编译不过，见 U2
# 类路径：上游各模块 target/classes 在前，依赖 jar 在后
CP=$(ls -d */target/classes | tr '\n' ':')$(cat /tmp/cp.txt)
```

**SLT 复现的三条硬性前提**（我们踩过，否则结果全是假失败）：

1. 引擎读的是 **CWD 下的 `target/slt-db`**，`--base-dir` 只负责"清理"，**不改变引擎落库位置**。
   必须同时传 `-Dlealone.base.dir=<含 slt 的路径>`，并在每次运行前 `rm -rf` 掉 CWD 的 `target/slt-db`。
   否则第一条 `CREATE TABLE t1` 就报 `Table "T1" already exists`，后续连锁失败（多为 `Table ... already exists` 异常，
   以及因建表失败导致的 hash_mismatch）→ 会凭空多出**十几万条**假"失败"（实测：单文件两连跑，第二次 390/719 条失败）。
2. SLT 门禁（`SltRunner`/`SltBaseline`/`files.txt`/`baseline.txt`/`allowlist.txt`）**是我们加的**（提交 `0f6443739`），上游没有。
   用它跑上游引擎时，要单独编译这两个类：
   ```bash
   javac -d /tmp/sltout -cp "$CP:<junit4>" \
       <我们的分支>/lealone-test/src/test/java/com/lealone/test/slt/{SltRunner,SltBaseline}.java
   ```
3. `--base-dir` 的路径**必须含 `slt` 字样**（`SltRunner.resetBaseDir` 的安全网，防止误删），
   否则直接抛 `IOException: refusing to delete ...`；例如 `/tmp/slt-db` ✅、`/tmp/audit-db` ❌。
4. 套件本体不在仓库里（`/tmp/sqllogictest/test`，gregrahn/sqllogictest）。

**SLT 标准跑法**（本文所有 SLT 数字都出自这条命令，精选集 = `files.txt` 的 53 个文件）：

```bash
rm -rf /tmp/slt-db && java -Xmx4g -Dlealone.base.dir=/tmp/slt-db \
  -cp "/tmp/sltout:$CP:<junit4>" com.lealone.test.slt.SltRunner \
  --dir=/tmp/sqllogictest/test \
  --files=<我们的分支>/lealone-test/src/test/resources/slt/files.txt \
  --out=/tmp/sltreport --base-dir=/tmp/slt-db \
  --allowlist=<我们的分支>/lealone-test/src/test/resources/slt/allowlist.txt
```

---

## 1. 总览：按我们的提交组织

| # | 我们的提交 | 修掉的上游问题 | 上游复现 | 上游状态 |
|---|---|---|---|---|
| U1 | （无对应提交；我们**没有**合入引入它的 `a88f8dde5`） | 可重复读/串行化**脏读** | `IsolationLevelTest` | ❌ 失败 |
| U2 | `4cf397f45` 清理死代码 | `lealone-test` 在干净 checkout 下**无法编译** | `mvn test-compile -pl lealone-test` | ❌ 失败 |
| U3 | `f0ab00101` 支持聚合函数的 ALL 量词 | 表达式/聚合参数里的 `ALL` 被当成列名或函数名（`SELECT ALL` 修饰符本身可用） | SLT **13,751** 条；`random/expr/slt_good_1.test:1682` 等 34 个文件 | ❌ 失败 |
| U4 | `ccd5dc076` 修复关联子查询返回过期值 | **关联子查询**结果错误 | SLT 1,186 条；`select1.test:174` 等 | ❌ 失败 |
| U5 | `db4278df8` 修正数值语义 | 表达式**整数溢出**报 `Numeric value out of range` | SLT；`random/expr/slt_good_60.test:74168` 等 | ❌ 失败 |
| U6 | `db4278df8`（`ADefault` 把 NULL/UNKNOWN 按 DECIMAL） | `SUM(NULL)` / `AVG(NULL)` 报错 | SLT 54 条；`random/expr/slt_good_2.test:27570`、`random/expr/slt_good_4.test:29748` | ❌ 失败 |
| U7 | **未修（我们也没做）** | 加密文件头 `len` 无校验 → 裸 JVM 异常 | 手工构造 header（§2.U7） | ❌ 失败 |
| U8 | **未修** | 密码校验受 `SysProperties.CHECK` 控制 | `-Dlealone.check=false` + 错密码 | ❌ 失败 |
| U9 | **未修** | PBKDF2 迭代数只有 10 | 代码 `FileEncrypt.java:59` | ❌ 设计问题 |
| U10 | **不适用**（我们没合 `a88f8dde5`） | `IndexKey.copy/copySelf` 返回同一实例、忽略 `lock` 参数 | 代码 review | ❌ 未修 |
| U11 | **不适用** | `TransactionMap.Cursor.getKey()` 唯一索引返回 value、`next()` 前为 null | 代码 review | ❌ 未修 |

**SLT 总量对照**（同一 runner、同一 53 文件精选集、同样带 allowlist）：

| 引擎 | 语句数 | 通过 | 失败 | allowlist 吸收 | **净失败** | 失败文件数 |
|---|---:|---:|---:|---:|---:|---:|
| **upstream master `34596f242`** | 442,461 | 422,590 | 19,871 | 4,792 | **15,079** | 38 |
| **我们 `39ee21d6f`** | 442,461 | 442,447 | 14 | 2 | **12** | 7 |

---

## 2. 逐条详情

### U1 🔴 可重复读 / 串行化事务读到未提交数据（脏读）

- **引入提交**：`a88f8dde5`（2026-10-25，issue #280 的唯一索引修复）——重构时丢了 `return`。
- **根因**：`lealone-aote/src/main/java/com/lealone/transaction/aote/TransactionalValue.java:179`

  ```java
  if (t != null) {
      getValue(lockable, lock, lockOwner);   // ← 没有 return，返回值被丢弃
  } else {
      return getValue(lockable);
  }
  ```
  `case IL_REPEATABLE_READ/IL_SERIALIZABLE` 因缺 `return` **穿透**到下一个 `case IL_READ_UNCOMMITTED`，
  于是可重复读/串行化事务直接返回其它未提交事务的当前值。改造前这里两个分支都是显式 `return`。
- **复现（上游自带测试，AOTE 内嵌，不需要 9210）**：

  ```bash
  javac -d /tmp/tout -cp "$CP:<junit4>" \
    lealone-test/src/test/java/com/lealone/test/TestBase.java \
    lealone-test/src/test/java/com/lealone/test/aote/AoteTestBase.java \
    lealone-test/src/test/java/com/lealone/test/aote/IsolationLevelTest.java
  java -cp "/tmp/tout:$CP:<junit4>:<hamcrest>" org.junit.runner.JUnitCore com.lealone.test.aote.IsolationLevelTest
  ```
  实测输出：`java.lang.AssertionError: expected null, but was:<a>`（`IsolationLevelTest.java:39`，
  `t4 = IL_REPEATABLE_READ; assertNull(map.get("1"))`）。
  同一份测试在我们分支上 `OK (1 test)`。
- **影响**：隔离级别被击穿，读到未提交数据。属**数据正确性**问题，最高优先级。
- **我们的状态**：`review/consolidated` 的基线 `b6c7cc654` 早于 `a88f8dde5`，**没有这个回归**；
  我们另有 `999714d17`（可重复读读到更旧版本）、`2c47585d6`（RC 下把无锁墓碑行当可见记录）两笔相关修复。
  ⚠️ **结论：这个提交不能直接合入我们的分支**，否则会带入脏读。

### U2 🟠 `lealone-test` 模块在干净 checkout 下无法编译

- **根因**：`lealone-test/src/test/java/com/lealone/test/http/HttpServerStart.java:117` 引用了 `tmptest.model.User`，
  而仓库里**没有** `tmptest/model/User.java`（该仓库无此文件，`git ls-tree` 为空），也没有 codegen 步骤生成它。
- **复现**：`mvn -o -q test-compile -pl lealone-test`
  实测：`[ERROR] ...HttpServerStart.java:[117,35] package tmptest.model.User does not exist`
- **影响**：任何"克隆→跑测试"的流程都会一开始就失败；也解释了 U1 这种回归为什么没被 CI 拦住。
- **我们的状态**：`4cf397f45`「清理导致 lealone-test 无法编译的死代码」（删掉 `HttpServerStart` 里那 18 行）。

### U3 🟠 `ALL` 量词 / `SELECT ALL` 被当成列名

- **现象**：`ALL` 出现在**表达式/聚合参数**位置时被当成标识符，例如 `MAX(ALL + 49)`、`COUNT(ALL + -18)`；报
  `Column "ALL" not found [42122]`，另一部分报 `Function "ALL" not found`。
- **复现（原始 SLT）**：`random/expr/slt_good_1.test:1682`、`:2003`、`:2172`（以及 `slt_good_*` 共 20+ 个文件）
  实测在 master 上精选集内（同一份报告 `/tmp/audit-report-up/failures.tsv`）：
  **13,447 条** `Column "ALL" not found` + **304 条** `Function "ALL" not found` = **13,751 条**，涉及 34 个文件
  （其中 distinct SQL 275 条）。
- **我们的状态**：`f0ab00101`「支持聚合函数的 ALL 量词」。实测我们分支：

  | SQL | master | 我们 |
  |---|---|---|
  | `SELECT COUNT(ALL 1)` | 报错 | `1` |
  | `SELECT ALL 1` | **正常返回 `1`**（`SELECT ALL` 修饰符本身没问题） | `1` |
  | `SELECT MAX(ALL 1 + 49)` | 报错 | `50` |
  | `SELECT ALL MAX(ALL + 49)` | 报错 | `49` |
  | `SELECT ALL COUNT(ALL + -18)` | 报错 | `1` |

### U4 🟠 关联子查询返回过期值

- **现象**：`(SELECT count(*) FROM t1 AS x WHERE x.b<t1.b)` 这类**引用外层列的标量子查询**结果错，
  常见表现是恒为 0 或取到旧值。
- **复现（原始 SLT）**：`select1.test:174`、`:232`、`:281`、`:322`、`:381`、`:406`（精选集内 1,186 条 hash_mismatch，
  其中 1,186/1,190 都含 `(SELECT ...)` + 外层 `t1.` 引用）。
  样例：`select1.test:174  SELECT (SELECT count(*) FROM t1 AS x WHERE x.b<t1.b) FROM t1 WHERE ... ORDER BY 1`
  实测 master：`exp=20 values md5=1d6b8ed... / act=20 values md5=bcacc28... first: 0 | 0`。
- **我们的状态**：`ccd5dc076`「修复关联子查询返回过期值与 IN/NOT IN 只看首行的问题」。
  实测我们分支 `select1.test` 0 失败。

### U5 🟡 表达式整数溢出直接报错（SQLite/PG 会提升类型）

- **现象**：`Numeric value out of range: "45682308000"`。典型 SQL 都是常量乘法/COALESCE 组合，
  期望值明显超出 INT 但 SQLite（本套件的 oracle）按更大类型算。
- **复现（原始 SLT）**：`random/expr/slt_good_60.test:74168`、`slt_good_91.test:31229`、`slt_good_109.test:66138`、
  `slt_good_25.test:19593`、`slt_good_31.test:30937`、`slt_good_40.test:31363`（均为 `random/expr/` 下）。
  ⚠️ 这 6 例里 **`random/expr/slt_good_91.test:31229` 在我们分支上仍以同一异常失败**（见 §3），其余已修。
  样例：`random/expr/slt_good_60.test:74168  SELECT ALL COALESCE ( - 3, - - 29 * 95 * - 84 * + 94 * - 25 * + 84 )`
- **我们的状态**：`db4278df8`「修正数值语义: DECIMAL 精度标度、AVG、ROUND 与 TRUNC」。
  实测我们分支：`SELECT ALL COALESCE(-3, -29*95*-84*+94*-25*+84)` → `-3`。

### U6 🟡 `SUM(NULL)` / `AVG(NULL)` 报"类型错误"

- **现象**：`SUM or AVG on wrong data type for "SUM(NULL)" [90015]`。
- **复现（原始 SLT）**：`random/expr/slt_good_2.test:27570`、`slt_good_4.test:29748`、`:33568`、`:64060`（精选集内 54 条）。
- **我们的状态**：`db4278df8`；`ADefault.optimize()` 里 `dataType == Value.NULL || UNKNOWN` 时按 `DECIMAL` 处理。
  实测我们分支：`SUM(NULL)`→`null`、`AVG(NULL)`→`null`、`SUM(DISTINCT NULL)`→`null`、`COUNT(NULL)`→`0`。

### U7 🟠 加密文件头 `len` 无校验 → 裸 JVM 异常（`34596f242` 引入）

- **根因**：`lealone-common/src/main/java/com/lealone/storage/fs/impl/encrypt/FileEncrypt.java:129-130`

  ```java
  int len = lenBuffer.getInt();
  byte[] passwordHash = new byte[len];     // ← 没有范围校验
  DataUtils.readFully(base, SALT_POS + SALT_LENGTH + 4, ByteBuffer.wrap(passwordHash));
  ```
- **复现（无上游测试，手工构造 header）**：用一个正常的新格式文件，改写 header 偏移 23 处的 4 字节长度：
  实测（`SysProperties.CHECK=true`）：

  | 伪造 len | 实测异常 |
  |---|---|
  | `-1` | `java.lang.NegativeArraySizeException: -1` |
  | `2147483647` | `java.lang.OutOfMemoryError: Requested array size exceeds VM limit` |
  | `100000` | `java.lang.IllegalStateException: ... file length 8256 read length 91771` |
- **真实触发场景**（不需要恶意攻击）：**上一个提交 `928c07017` 写出的文件**，其 header `[23..39)` 是 16 字节随机密钥，
  升级后被当成 `len` 读 → 大概率负数或巨大 → 用户看到 `NegativeArraySizeException` 而不是"密码错误/版本不支持"。
  根因是 **header 格式改了两版却没有版本号**。
- **我们的状态**：未修（我们没合入这两个提交）。若要修：加格式版本号 + `0 < len <= 1024` 校验，越界报 90049。

### U8 🟡 密码校验挂在调试开关上 → 关掉后**静默读到垃圾**

- **根因**：`FileEncrypt.java:114` 与 `:126` 两处 `if (SysProperties.CHECK)`；校验只在 `CHECK=true` 时执行。
- **复现**：`-Dlealone.check=false`，用**错误密码**打开加密文件 →
  **不报错**，`read()` 直接返回 64 字节垃圾（实测 `明文正确=false`）。
- **对比**：`CHECK=true` 时错密码正确报 `90049`，正确密码正常读。
- **影响**：数据库文件读出错数据比报错更危险；校验成本只有一次 SHA-256，没有理由受调试开关控制。
- **我们的状态**：未修。建议：校验**无条件执行**，`CHECK` 只保留给"magic 头"这类额外断言。

### U9 🟡 PBKDF2 迭代数只有 10

- **位置**：`FileEncrypt.java:59  private static final int HASH_ITERATIONS = 10;`
- **影响**：`34596f242` 之后 header 里存了 `SHA256(pk‖salt)` 校验值，文件因此成为**离线口令猜测预言机**：
  试一个密码 ≈ **1 次 SHA-256**（`getHashWithSalt`；`FilePathEncrypt.getPasswordBytes` 只是把 char[] 按 UTF-16 拷成 byte[]，
  不做哈希），单核每秒可试数百万次——比预想的更弱。
  弱口令基本等同明文。建议 ≥ 60 万次（OWASP 2023）或换 Argon2id，并把参数写进 header 以便升级。
- **我们的状态**：未修（我们用 `ScriptBase` 的密文内 magic 做校验，同样受这个强度上限约束）。

### U10 🟡 `IndexKey.copy()/copySelf()` 返回同一实例并忽略 `lock` 参数

- **位置**：`lealone-db/src/main/java/com/lealone/db/index/standard/IndexKey.java:78-90`、`:127-140`

  ```java
  public Object copy(Object oldLockedValue, Lock lock) {
      if (oldLockedValue instanceof SingleIndexKey) return oldLockedValue;   // 忽略 lock
      ...
  }
  ```
- **影响**：共享可变 `Lockable`，`lock` 未绑定；改动 `TransactionalValue` 语义后容易出现别名问题。
- **复现**：代码 review（无测试覆盖）。可用"同一唯一索引键在两事务间并发读写 + 观察 Lock 绑定"的用例暴露。
- **我们的状态**：不适用——我们的 `0ae4948fd` 用**墓碑标记**方案（`IndexKey.deleted`），不改 `Lock`/`Cursor` 契约。

### U11 🟡 `TransactionMap.Cursor.getKey()` 契约变化

- **位置**：`lealone-aote/src/main/java/com/lealone/transaction/aote/AOTransactionMap.java:199`

  ```java
  public K getKey() {
      if (getKeyType().isUniqueKey()) return (K) value;   // 返回"值"而不是游标键
      return cursor.getKey();
  }
  ```
- **影响**：① 首次 `next()` 之前 `value` 为 `null`，`getKey()` 返回 null；② 依赖"key 就是 map 里的键对象"的调用方
  （按 key 回查/删除）可能拿不到相等的对象。
- **复现**：代码 review。可写用例：唯一索引游标在 `next()` 前调用 `getKey()`；以及对返回的 key 做 `map.get(key)`。
- **我们的状态**：不适用（未合入 `a88f8dde5`）。

---

## 3. SLT 失败聚类（上游 master 侧，净 15,079 条 / 38 文件）

| 根因 | 条数 | 典型 detail | 原始 SLT |
|---|---:|---|---|
| `ALL` 被当成标识符 | **13,751** | `Column "ALL" not found`(13,447) / `Function "ALL" not found`(304) | `random/expr/slt_good_1.test:1682` 等 34 个文件（U3） |
| 关联子查询 | 1,186 | `hash_mismatch`，均含 `(SELECT ... t1.col ...)` | `select1.test:174` 起（U4） |
| `SUM/AVG(NULL)` | 54 | `SUM or AVG on wrong data type` | `random/expr/slt_good_2.test:27570`、`slt_good_4.test:29748`（U6） |
| 整数溢出 | ≈6+ | `Numeric value out of range` | `random/expr/slt_good_60.test:74168` 等（U5） |
| 其它 hash/value/row_count | **82** | 值或行数不一致（多数仍与关联子查询相关：78 条中有 67 条含子查询） | `select1.test:911`、`:3649`、`:8810` 等 |

失败最多的文件（master）：`select3.test`(806)、`random/expr/slt_good_96.test`(703)、`slt_good_106.test`(702)、
`slt_good_5.test`(697)、`slt_good_21.test`(697)、`slt_good_60.test`(695)、`slt_good_91.test`(690)…

**我们分支的 12 条 baseline 偏差（已登记，非回归）**：14 条失败分布在 8 个文件
（`random/expr/slt_good_{4,19,21,60,91,96,106,109}.test`），其中 2 条被 allowlist 吸收、净 12 条落 7 个文件
（`slt_good_106.test` 的失败全部被 allowlist 覆盖）。

需要澄清一处**先前写错的定性**：这 12 条**不是"双方都未修"**。交叉比对 master 侧结果后：
- **11 条**是我们的 AVG 按 H2 规则返回 DOUBLE/DECIMAL 造成的**类型传播偏差**（master 侧 8 条通过、
  3 条因 U3 的 `ALL` 报错而无法比较）。例：`-81/14+16` 两边都是 11，但 `14/(CASE…AVG…)+23`
  我们是 DOUBLE 22.517、oracle（SQLite）是 23。
- **仅 1 条**（`random/expr/slt_good_91.test:31229`）是**双方同因失败**（U5 的数值溢出）。
详见 `lealone-test/src/test/resources/slt/baseline.txt` 顶部说明。

---

## 4. 待确认 / 低优先

| 项 | 说明 | 状态 |
|---|---|---|
| `FileStorage.getInputStream()` 现在强制 `resetFilePointer()`（`dfbf8b16e`） | 修好了"第二次 `BACKUP TO` 读到空数据"，但把"每次取流都从头读"变成了硬约定；若有调用方依赖"接着上次位置读"会静默读到重复数据 | 未验证到实际回归，仅记录 |
| `IndexKeyType.java:201-203` | 留了被注释掉的 `if (ax == null) { getOldValue... }` 死代码 | 风格问题 |
| 上游与我们重复的工作 | `1c45274a8`（SCRIPT 不生成 SystemSchema 表）、`126165952`（`DbSetting.ENCRYPTION_KEY`）与我们 `ab8670a64` **主题相同**（该提交同时改了 `GenScript`/`RunScript`/`ScriptBase`/`FileStorage`）；未逐行证明等价 | 主题已覆盖，合并前建议逐行比对 |

---

## 5. 结论与建议

1. **U1 是不合入 upstream `a88f8dde5` 的硬理由**：它把可重复读/串行化变成脏读，且有上游自带测试可以证明。
2. **U3/U4/U5/U6 说明我们的分支在 SQL 语义上领先 master 一个身位**（精选集 15,079 → 12），
   这四类问题都已有对应提交，建议在合并说明里引用本文的数字。
3. **U7/U8 是上游最新两个加密提交自身的缺口**：一个格式无版本号（畸形 header 直接抛 JVM 异常），
   一个把校验挂在调试开关上（关掉后静默读垃圾）。如果我们要补"加密数据文件的错密码检测"，应按 §2.U7 的建议一次做对。
4. **U2 建议上游修**（我们已用死代码清理规避）：干净 checkout 编译不过，会掩盖所有测试回归。

---

## 6. 复查记录

本文提交前由**独立 agent（干净 context，未参与原始测写）**逐条复核，方式是"自己重跑"而不是"读文档认可"：

- **复核通过**（与其独立实测一致）：上游/我们的 HEAD 与祖先关系、SLT 总量 442,461 与两侧净失败 15,079/12、
  SLT 门禁确由 `0f6443739` 引入且上游无此目录、U1（含 `a88f8dde5` 前后代码对照）、U2、U4、U6、U7
  （并额外用 `928c07017` 的二进制造出旧格式文件，实测用**正确密码**打开即 `NegativeArraySizeException: -1246632479`，
  印证 U7 的真实触发场景）、U8（4 个独立 JVM 的对照矩阵）、U9、U10/U11、§4 的 `dfbf8b16e`。
- **复核纠正了本文 5 处实质错误 + 5 处小瑕疵**（已全部改正）：
  1. U3 的 `Function "ALL" not found` 条数 89 → **304**（合计 13,751）；
  2. U3 对照表里 `SELECT ALL 1` 被误写成"master 报错"，实际 master **正常返回 1**（坏的是聚合参数里的 `ALL`）；
  3. §3 把 12 条 baseline 偏差定性为"双方都未修"**错误** —— 其中 11 条是我们 AVG→DOUBLE/DECIMAL 的类型传播偏差
     （master 侧 8 条通过、3 条因 U3 无法比较），仅 `random/expr/slt_good_91.test:31229` 双方同因失败；
  4. §3 的"其它 ≈330" → **82**，"ALL ≈13,500" → **13,751**；
  5. U5 的 6 个样例里 `random/expr/slt_good_91.test:31229` **在我们分支仍失败**，原文并读会误以为全部已修。
  小瑕疵：`random/expr/` 路径前缀、`IndexKeyType` 死代码行号 198-206 → 201-203、U9 的猜测成本
  （本路径不经 `getKeyPasswordHash`，**1 次** SHA-256 即可判别）、假失败的表现描述、上游重复工作的措辞（改为"主题相同"）。
- 复核方保留的原始证据：`/tmp/audit-report-up`（master 侧报告）、`/tmp/audit-report-cons{,2}`（我们侧报告）。
