package poolleak;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测复现脚本：多线程反复 acquire/release，压完之后把池子的计数打出来跟基线比。
 *
 * <p>这只是「现象重现」的入口，用来观察压测后计数的实际值。
 */
public final class Repro {

    public static void main(String[] args) throws Exception {
        int capacity = 8;
        int threads = 32;
        int rounds = 20000;

        Pool pool = new Pool(capacity);
        System.out.println("capacity=" + capacity + " baseline: available=" + pool.available()
                + " inUse=" + pool.inUse());

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        Handle h = pool.acquire();
                        h.close();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            }, "repro-" + t);
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : workers) {
            th.join(60_000L);
        }

        System.out.println("after " + threads + " threads x " + rounds + " acquire/release:");
        System.out.println("  available=" + pool.available() + "  (baseline " + capacity + ")");
        System.out.println("  inUse=" + pool.inUse() + "  (baseline 0)");
        System.out.println("  errors=" + errors.get());
        pool.close();
    }

    private Repro() {
    }
}
