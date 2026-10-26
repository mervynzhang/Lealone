# 评审环境说明（本轮）

> 生成时间：本轮修复完成后。**当前没有任何 Lealone 进程在跑**，端口已全部释放。

## 现在是什么状态

| 项 | 状态 |
|---|---|
| Lealone 实例 | **0 个**（9210/9212/9215/9216/9217/5433~5438/9230~9302 全部已停，端口已释放） |
| PostgreSQL | **仍在运行**（5432 / 5434），是兼容矩阵对照用的，**请勿停止** |
| `~/.m2`（实际是 `/root/.m2`）核心 jar | 已用**集成分支 `integration/all-fixes`** 重新安装，与待评审代码一致（`lealone-sql` jar 时间 10-26 08:30） |
| 客户端 classpath 文件 | `/tmp/cp.txt` 指向上述 jar |
| 临时实例数据目录 | 已清理（`/tmp/srv*`、`/tmp/p0v`、`/tmp/vfy`、`/tmp/pv` 等） |

## 保留下来的东西（评审需要）

| 路径 | 用途 |
|---|---|
| `/tmp/wt-all` | **集成分支 worktree（评审对象）**，含 `docs/HANDOFF.md` 与本文 |
| `/tmp/wt-fix1` | `fix/p0-perf`（P0 性能）；`review/consolidated` 的规范 worktree |
| `/tmp/wt-any` | `fix/any-array`（`= ANY`） |
| `/tmp/wt-win` | `fix/window-row-number`（窗口函数 + Blocker 修复） |
| `/tmp/wt-paren` | `fix/sql-paren-print`（括号打印） |
| `/tmp/wt-net` | `fix/zero-length-packet`（0 长度包 + 负长度） |
| `/tmp/pl-cat` | PostgreSQL 插件 `fix/pg-jdbc-funcs`（不在核心评审范围） |
| `/tmp/pgcompat` | 兼容矩阵工具（`pgcompat.Matrix`，138 条用例） |
| `/tmp/leadcheck` | 压测工具（`Bench3`，带行数断言） |
| `/tmp/pgperf` | 性能报告与原始 CSV |
| `/tmp/pgplug` | 小工具（起插件/建库/执行 SQL） |

## 起一个干净实例（评审时的标准姿势）

```bash
export PATH=/home/jovyan/data/maven/bin:$PATH JAVA_HOME=/usr/lib/jvm/sapmachine-21   # 必须用 21，裸 java 是 8
cd /tmp/wt-all && mvn -o -q -DskipTests install            # 若改了代码，先重装
mkdir -p /tmp/rev && cd /tmp/rev
setsid $JAVA_HOME/bin/java -cp "$(cat /tmp/cp.txt)" com.lealone.main.Lealone \
    -baseDir /tmp/rev/srv -port 9411 > /tmp/rev/srv.log 2>&1 < /dev/null &
# 等 /tmp/rev/srv.log 出现 "Lealone started"
```

## 三个坑（本轮踩过）

1. **测试/调试实例必须在 jar 重新打包之后启动**，否则跑的是旧类（本轮因此出现过一次"假红"）。
2. `~/.m2` 里的核心 jar 会被任意 worktree 的 `mvn install` 覆盖 —— 切分支验证前先确认 jar 来源；
   当前已统一为集成分支。
3. 裸 `java` 是 Java 8，`com.lealone.main.Lealone` 会 `ClassNotFoundException`；必须用 `$JAVA_HOME/bin/java`。

## 冒烟结果（清理前用集成分支跑过，实例已停）

```
窗口函数   : SELECT id, row_number() over (order by id) FROM t  ->  1:1 2:2 3:3
ANY        : WHERE id = any((1,3))                             ->  1,3
行值 IN 视图: CREATE VIEW ... WHERE (id,v) IN ((1,'a'),(2,'b'))  ->  2 行
DML 窗口   : UPDATE t SET v = rank() over ()                   ->  正确报语法错
```
