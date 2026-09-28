package poolleak;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * poolleak 的既有用例（12 个），**全部跑在单线程顺序路径上**。
 *
 * <p>不依赖 JUnit：静态方法加 {@code main} 聚合自跑，全部通过时打印 {@code 12/12 passed}
 * 并以退出码 0 结束；有失败时打印失败清单并以退出码 1 结束。
 *
 * <p>这 12 条是「既有行为」的基线，**一条都不许删或改**。
 */
public final class PoolTest {

    private interface Body {
        /** 返回 null 表示通过，否则返回失败原因。 */
        String run();
    }

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("capacity-and-initial", PoolTest::testCapacityAndInitial);
        run("acquire-updates-counts", PoolTest::testAcquireUpdatesCounts);
        run("try-with-resources", PoolTest::testTryWithResources);
        run("multiple-acquires", PoolTest::testMultipleAcquires);
        run("available-plus-inuse-idle", PoolTest::testAvailablePlusInUseIdle);
        run("negative-capacity", PoolTest::testNegativeCapacity);
        run("capacity-zero", PoolTest::testCapacityZero);
        run("timeout-on-empty-returns-null", PoolTest::testTimeoutOnEmptyReturnsNull);
        run("close-pool-then-held-close-ok", PoolTest::testClosePoolThenHeldCloseOk);
        run("pool-close-idempotent", PoolTest::testPoolCloseIdempotent);
        run("reuse-after-release", PoolTest::testReuseAfterRelease);
        run("handle-close-then-counts-restored", PoolTest::testHandleCloseThenCountsRestored);

        System.out.println(passed + "/12 passed");
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("FAIL " + f);
            }
            System.exit(1);
        }
    }

    private static void run(String name, Body body) {
        try {
            String err = body.run();
            if (err == null) {
                passed++;
            } else {
                failures.add(name + "：" + err);
            }
        } catch (Throwable t) {
            failures.add(name + "：抛出 " + t);
        }
    }

    // ---------------------------------------------------------------- 基本计数

    private static String testCapacityAndInitial() {
        Pool p = new Pool(3);
        try {
            String e = expect("capacity", p.capacity(), 3);
            if (e != null) {
                return e;
            }
            e = expect("available 初值", p.available(), 3);
            if (e != null) {
                return e;
            }
            return expect("inUse 初值", p.inUse(), 0);
        } finally {
            p.close();
        }
    }

    private static String testAcquireUpdatesCounts() {
        Pool p = new Pool(3);
        try {
            Handle h = acquire(p);
            String e = expect("借出后 available", p.available(), 2);
            if (e != null) {
                return e;
            }
            e = expect("借出后 inUse", p.inUse(), 1);
            if (e != null) {
                return e;
            }
            h.close();
            e = expect("归还后 available", p.available(), 3);
            if (e != null) {
                return e;
            }
            return expect("归还后 inUse", p.inUse(), 0);
        } finally {
            p.close();
        }
    }

    private static String testTryWithResources() {
        Pool p = new Pool(2);
        try {
            try (Handle h = acquire(p)) {
                String e = expect("try 内 available", p.available(), 1);
                if (e != null) {
                    return e;
                }
            }
            String e = expect("try 退出后 available", p.available(), 2);
            if (e != null) {
                return e;
            }
            return expect("try 退出后 inUse", p.inUse(), 0);
        } finally {
            p.close();
        }
    }

    private static String testMultipleAcquires() {
        Pool p = new Pool(3);
        try {
            acquire(p);
            acquire(p);
            acquire(p);
            String e = expect("取满后 available", p.available(), 0);
            if (e != null) {
                return e;
            }
            return expect("取满后 inUse", p.inUse(), 3);
        } finally {
            p.close();
        }
    }

    private static String testAvailablePlusInUseIdle() {
        Pool p = new Pool(5);
        try {
            acquire(p);
            acquire(p);
            int sum = p.available() + p.inUse();
            return expect("空闲时 available+inUse", sum, 5);
        } finally {
            p.close();
        }
    }

    // ---------------------------------------------------------------- 边界

    private static String testNegativeCapacity() {
        try {
            Pool p = new Pool(-1);
            p.close();
            return "期望=new Pool(-1) 抛 IllegalArgumentException 实际=正常返回";
        } catch (IllegalArgumentException expected) {
            return null;
        } catch (Throwable t) {
            return "期望=IllegalArgumentException 实际=" + t;
        }
    }

    private static String testCapacityZero() {
        Pool p = new Pool(0);
        try {
            String e = expect("capacity", p.capacity(), 0);
            if (e != null) {
                return e;
            }
            e = expect("available", p.available(), 0);
            if (e != null) {
                return e;
            }
            return expect("inUse", p.inUse(), 0);
        } finally {
            p.close();
        }
    }

    private static String testTimeoutOnEmptyReturnsNull() {
        Pool p = new Pool(1);
        try {
            Handle held = acquire(p);
            Handle got = p.acquire(40L, TimeUnit.MILLISECONDS);
            if (got != null) {
                got.close();
                return "期望=无许可时超时返回 null 实际=拿到句柄";
            }
            held.close();
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "期望=超时返回 null 实际=抛 " + ie;
        } finally {
            p.close();
        }
    }

    private static String testClosePoolThenHeldCloseOk() {
        Pool p = new Pool(2);
        Handle h = acquire(p);
        p.close();
        try {
            h.close();
        } catch (Throwable t) {
            return "期望=池关闭后已持有的 Handle.close() 不抛异常 实际=" + t;
        }
        return expect("归还后 available", p.available(), 2);
    }

    private static String testPoolCloseIdempotent() {
        Pool p = new Pool(1);
        try {
            p.close();
            p.close();
            return null;
        } catch (Throwable t) {
            return "期望=重复 close() 池不抛异常 实际=" + t;
        }
    }

    private static String testReuseAfterRelease() {
        Pool p = new Pool(1);
        try {
            Handle h1 = acquire(p);
            h1.close();
            Handle h2 = acquire(p);
            String e = expect("再次借出后 available", p.available(), 0);
            if (e != null) {
                return e;
            }
            e = expect("再次借出后 inUse", p.inUse(), 1);
            if (e != null) {
                return e;
            }
            h2.close();
            return expect("归还后 available", p.available(), 1);
        } finally {
            p.close();
        }
    }

    private static String testHandleCloseThenCountsRestored() {
        Pool p = new Pool(2);
        try {
            Handle h1 = acquire(p);
            Handle h2 = acquire(p);
            h1.close();
            String e = expect("关一个后 available", p.available(), 1);
            if (e != null) {
                return e;
            }
            e = expect("关一个后 inUse", p.inUse(), 1);
            if (e != null) {
                return e;
            }
            h2.close();
            e = expect("全关后 available", p.available(), 2);
            if (e != null) {
                return e;
            }
            return expect("全关后 inUse", p.inUse(), 0);
        } finally {
            p.close();
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Handle acquire(Pool p) {
        try {
            return p.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("acquire 被中断", ie);
        }
    }

    private static String expect(String label, int actual, int wanted) {
        if (actual == wanted) {
            return null;
        }
        return label + " 期望=" + wanted + " 实际=" + actual;
    }

    private PoolTest() {
    }
}
