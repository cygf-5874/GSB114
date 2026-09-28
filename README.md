# poolleak — 固定容量资源池

`poolleak` 是一个 Java 17 的固定容量资源池，只用 JDK 标准库（`javac` / `java`），
不引入 Maven / Gradle，也不下载任何第三方包。池里恰好有 `capacity` 个许可，
`acquire()` 取一个、`Handle.close()` 还一个。

```java
try (Pool pool = new Pool(4)) {
    Handle h = pool.acquire();          // 取一个许可（池满时阻塞）
    h.close();                          // 还回去（幂等）
    Handle h2 = pool.acquire(200, TimeUnit.MILLISECONDS);   // 超时返回 null
    pool.available();                   // 可用许可
    pool.inUse();                       // 已借出许可
}
```

## 目录

```
src/main/java/poolleak/Pool.java   资源池（含缺陷）
src/main/java/poolleak/Handle.java 借出的句柄（close 归还）
src/test/java/poolleak/PoolTest.java 既有用例（12 个，只覆盖单线程顺序路径，起点全绿）
repro/Repro.java                   压测复现脚本
scripts/build.sh                   编译 main / test / repro
scripts/test.sh                    跑既有用例（构建后执行 poolleak.PoolTest）
scripts/check.sh                   固定验收入口（勿改）
check/Checker.java                 固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`、`out-test/`、`out-check/`、`out-repro/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh            # 编译 src/main/java、src/test/java、repro/
bash scripts/test.sh             # 既有 12 个用例，全绿时打印 12/12 passed
bash scripts/check.sh            # 固定验收，支持 -list 与 --only <组名>
bash scripts/check.sh -list      # 列出全部 8 个验收场景
bash scripts/check.sh --only race
java -cp out:out-repro poolleak.Repro     # 压测复现（Windows 用 out;out-repro）
```

起点状态：既有 12 个用例全绿（都跑在单线程顺序路径上）；`bash scripts/check.sh`
以退出码 1 结束（并发 / 边界场景不通过）。

> `check/` 下的是**固定验收程序，勿改**。公开的**类型名、方法名与签名**已经是最终形态
> （可以新增内部成员、私有方法或新的类），不要改动下面 `API` 一节列出的这些。

## 对外保证

下面 8 条是 `poolleak` 的**对外保证**，它们是本题验收点的唯一出处。
**它们是保证，不是「当前行为」的转述**；实现方式不限，但必须让这 8 条同时成立。

1. `Pool(int capacity)` 与 `Handle acquire()`：`capacity` 为 0 时 `acquire()` **阻塞**，
   直到有许可被归还；`capacity` 为负抛 `IllegalArgumentException`。
2. `Handle.close()` 归还许可，**幂等**（重复 `close()` 不得多还）；未 `close()` 的 `Handle`
   被 GC 时必须**自动归还**（`Cleaner` / `PhantomReference` 任一实现）。
3. **不丢失唤醒**：`release` 必须恰好唤醒一个等待者 —— 既不得漏唤（等待者永久阻塞），
   也不得在未复核条件的情况下让多个等待者同时通过（一次归还只放行一个借出）。
4. `Pool.available() + Pool.inUse() == capacity` 在**任意时刻**成立（并发下也成立）。
5. `close()` 关闭池：**等待中的** `acquire` 抛 `IllegalStateException`；
   已持有的 `Handle.close()` 仍可归还且**不抛异常**。
6. `acquire(long timeout, TimeUnit unit)` 超时返回 `null`，且**不得吞掉许可**
   （超时前后池的许可总数不变）；等待期间若有许可归还应能拿到。
7. 线程中断时 `acquire` / `acquire(timeout, unit)` 抛 `InterruptedException`
   且**恢复中断标志**。
8. 连续 10 万次 acquire/release 后 `available()` 回到 `capacity`、`inUse()` 回到 0
   （无泄漏、无重复计数）。

## API

```
poolleak.Pool implements AutoCloseable
  Pool(int capacity)                        （capacity < 0 抛 IllegalArgumentException）
  #capacity()                                -> int
  #available()                               -> int    （当前可用许可）
  #inUse()                                   -> int    （当前已借出许可）
  #acquire()                 throws InterruptedException -> Handle
  #acquire(long, TimeUnit)   throws InterruptedException -> Handle  （超时返回 null）
  #close()                                   -> void

poolleak.Handle implements AutoCloseable
  #close()                                   -> void   （归还许可，幂等）
```

## 验收

`check/Checker.java` 是固定验收程序，**不要修改**。它按 5 组共 8 个场景检查上面的保证。
**每个场景都在独立 JVM 子进程里跑，带看门狗**，超时判不通过；
一个场景失败不会遮蔽其余场景（失败不早退）。

| 组 | 场景 | 对应保证 |
| --- | --- | --- |
| `wake` | `no-loss` | 3 |
| `wake` | `close-wakes-all` | 3、5 |
| `count` | `available-inuse` | 4、8 |
| `count` | `cycle-100k` | 8 |
| `idem` | `repeated-close` | 2 |
| `timeout` | `returns-null` | 6 |
| `timeout` | `interrupt` | 7 |
| `race` | `atomic-invariants` | 3、4、8 |

共 8 个场景。并发场景用**栅栏**（`CountDownLatch`）把线程凑齐后再放行，
判据只看**可复核的计数与不变量**（并发持有数上界、归还后的许可数），
不依赖线程调度顺序、不看墙钟耗时（`timeout` 组只用「超时返回 null / 未吞许可」这类可判定事实）。
