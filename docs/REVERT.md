# 回退指引

记录 2026-10-23 ~ 2026-10-24 这批改动的回退方式。下文所有结论都经过实际执行验证，不是推断。

## 备份点

| 名称 | 提交 / 分支 | 说明 |
| --- | --- | --- |
| 本批改动起点 | `cac7886a1` | 所有改动都在这个提交之后 |
| 历史清理前 | `pre-cleanup-1024`（本地）、`origin/backup`（远端） | 2026-10-24 合并重复提交之前的完整历史 |
| 清理后当前 | `master` / `origin/master` | 清理前后最终代码树完全一致（tree `b4ad0637e`） |

## 一、整体回退（推荐）

```bash
git switch -c revert-all master
git revert --no-commit cac7886a1..master
git commit -m "回退这批全部改动"
```

验证：`git diff cac7886a1 --stat` 输出为空。

实测结果：这批 41 个代码提交零冲突，回退后工作树与 `cac7886a1` 完全一致（涉及 64 个文件）。
本文件位于这批提交的末尾，整体回退时会被一并撤掉，不影响结果。

## 二、按主题回退

下面每组可独立回退，组内提交按**从新到旧**的顺序传给 `git revert`：

```bash
git revert --no-commit <组内提交，从新到旧>
git commit -m "回退XXX"
```

| 主题 | 提交（从新到旧） | 独立回退 |
| --- | --- | --- |
| 并发扫描/幽灵行修复 | `bdc43907d` 增加并发删除期间扫描不能返回NULL行的回归用例、`0ad59e9fb` 扫描时对当前行的列值做快照，修复并发删除时返回全NULL幽灵行、`cf0e18589` 启用ScanDuplicateTest并更正用例里的现状说明、`13a94ac84` 查询结果按行主键去重，修复并发写下同一行被同一条语句返回两次、`2f924158c` 事务槽位移除前校验事务id，避免重复移除导致计数变负或误删新事务、`7c7731b44` 提交回调校验事务身份，避免迟到的回调清掉session里已换上的下一个事务、`e72c7fa1f` 索引重试操作只交给行所在索引的IndexOperator执行，不再广播给表上其他索引 | 已实测可独立回退 |
| JDBC 异常契约 | `c9b740e26` 钉住异步接口的异常契约：cause必须是SQLException、`beb514d5b` 补充execute抛SQLException的用例、`574b386c4` Statement.execute同步路径抛SQLException而不是DbException、`502c6088b` 增加JDBC层异常断言，补充关闭语句与约束违反用例、`4cfda2791` Statement关闭后继续使用抛OBJECT_CLOSED，不再抛NPE | 已实测可独立回退 |
| SQL 功能（CTE / 派生列名 / JOIN USING / TABLE x） | `7f90385bd` CTE体支持VALUES和TABLE，语句支持TABLE x查询、`a09674921` JOIN支持USING子句、`366998e87` 派生表支持列别名列表、`d722ca57a` 支持非递归CTE | 已实测可独立回退 |
| 字符串与 CAST（OCTET_LENGTH、QUOTE_IDENT、UUID 校验） | `168413f98` CAST到UUID时校验必须32个十六进制位、`5038c7aa3` OCTET_LENGTH/BIT_LENGTH按UTF-8字节计算，新增QUOTE_IDENT函数 | 已实测可独立回退 |
| 三值逻辑（NULL AND FALSE、行值 IS NULL、行值比较） | `409c0d7d8` 修复行值比较含NULL时未按三值逻辑返回UNKNOWN的缺陷、`17ecc5ff5` 修复行值构造器全部为NULL时IS NULL返回false的缺陷、`89fed36c4` 修复NULL AND FALSE返回NULL的缺陷，AND/OR不再把NULL常量当FALSE折叠 | 已实测可独立回退 |
| 数值与聚合（AVG、DECIMAL、ROUND、TRUNC） | `6247ee879` 更正SelectTest里记录的旧AVG截断行为、`832c1e949` 更新脚本期望值与SLT基线以适配AVG浮点化，并在基线里写明原因、`e867f0f22` ROUND对DECIMAL入参保持DECIMAL类型并正确舍入、`f8aad10a0` ROUND平局时改为远离0取整，负数的-2.5得到-3、`bd0c64e6b` TRUNC只有一个参数时按标度0截断，不再抛NPE、`07e633217` 整数类型的AVG结果改用DOUBLE或DECIMAL避免截断、`7e8917cde` DECIMAL除法的结果标度按操作数精度推导，避免小数位过多、`85d51ba4c` DECIMAL类型的常量CAST折叠后保留声明的精度和标度 | 已实测可独立回退 |
| DDL 连锁与序列 | `90e9be202` 支持CURRENT VALUE FOR序列语法、`a00be9fb2` 修复序列取值范围检查过严导致只容纳两个值的序列无法创建的问题、`213bee06a` 修复序列到达边界值后不回绕以及非法预留值被写进元数据的问题、`89a3d74e2` 修复DDL之后缓存的prepared statement不重新编译导致读取旧表结构的问题、`43eb74d31` 修复视图引用的列被删除时错误码和消息错乱的问题 | 已实测可独立回退 |
| 索引构建期间的排序 | `3f4da112e` 索引构建期间不能用它满足排序，避免ORDER BY返回错误顺序、`31e7610b8` 钉住CREATE INDEX不回填已有行导致ORDER BY错序的缺陷 | 已实测可独立回退 |
| 二级索引吞吐量基准 | `6046cbb7e` 新增二级索引扫描吞吐量基准测试 | 已实测可独立回退 |
| 构建（maven-shade-plugin 升级） | `02f3e21e7` 升级maven-shade-plugin到3.6.0，修复全仓mvn install在lealone-test的配置解析错误 | 已实测可独立回退 |
| 测试注释更正 | `d9ea16cff` 更正剩余三个@Ignore的原因说明，区分H2差距与PostgreSQL差距 | 已实测可独立回退 |
| H2 风格脚本套件 + P0/P1 测试类 | `c96ad67e4` 补充P0/P1测试：值语义、谓词三值逻辑、执行计划、DDL连锁、字符串/CAST/聚合/序列/JOIN、`c1f31f470` 引入H2风格SQL脚本测试套件，首批7个脚本181条语句全绿 | **不能单独回退**，见第四节 |

## 三、单个提交回退

这批 41 个代码提交里有 34 个可以单独 `git revert` 干净应用，另外 7 个需要连同改过同一处的伙伴提交一起回退：

| 提交 | 冲突文件 | 需一起回退 |
| --- | --- | --- |
| `502c6088b` 增加JDBC层异常断言 | `JdbcStatementTest` | `c9b740e26` |
| `beb514d5b` 补充execute抛SQLException的用例 | `JdbcStatementTest` | `c9b740e26` |
| `89fed36c4` 修复NULL AND FALSE | `PredicateLogicTest` | `409c0d7d8` |
| `d722ca57a` 支持非递归CTE | `SQLParserBase` | `90e9be202` |
| `31e7610b8` 钉住CREATE INDEX错序 | `DdlCascadeTest` | `3f4da112e` |
| `c1f31f470` H2脚本套件 | 新增文件已被后续改写 | 各自的修复提交 |
| `c96ad67e4` P0/P1测试 | 新增文件已被后续改写 | 各自的修复提交 |

冲突原因都是“后一个提交在相邻行加了代码”造成的上下文重叠，带上伙伴提交即可。

## 四、为什么测试套件不能单独回退

脚本套件和 P0/P1 测试类是先落地的，当时用 `@Ignore` 记录尚未修复的缺陷；
之后的每个修复提交都会回填期望值、摘掉对应的 `@Ignore`。两者是共生的：

- 想撤掉测试套件，必须连同所有修改过这些测试文件的修复提交一起撤；
- 想保留测试套件，则只能按第二节的主题逐组回退。

## 五、注意事项

1. 回退后请 `rm -rf lealone-test/target/test-data` 再跑测试，否则残留库会互相干扰。
2. 本仓库测试默认使用 `9210` 端口的服务端，回退前后都要确认它是否在运行。
3. 整批回退后 `git diff <起点>` 必须为空，这是判断回退是否完整的唯一标准。
