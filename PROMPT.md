线程数在压测里单调上涨，回不到基线。poolleak 是 Java 17 的固定容量资源池，
只用 JDK 标准库（javac/java），构建走 `scripts/build.sh`，
自检走 `scripts/check.sh`（`check/` 是固定验收程序，别改），既有用例走 `bash scripts/test.sh`。

既有用例全在单线程顺序路径上跑，所以都是绿的 —— 问题的位置得你自己找。
`repro/Repro.java` 是压测同学的复现脚本。

任务：按 README「对外保证」的 8 条把缺陷修掉，让固定件全过。修完既有用例仍须全绿。

验收：
- bash scripts/test.sh 全绿；
- bash scripts/check.sh 退出码 0，8 个场景全过（wake 2 + count 2 + idem 1 + timeout 2 + race 1）。

约束：
1. 不改 `check/`；可以新增类。
2. 对外 API 名与签名不许改；`PoolTest.java` 里的既有用例一条都不许删或改。
3. 不许引入任何第三方依赖，不许加 `pom.xml` / `build.gradle`。
4. 并发场景要用栅栏把线程凑齐，别用 sleep 凑时间。
