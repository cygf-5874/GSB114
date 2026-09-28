import poolleak.Handle;
import poolleak.Pool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * poolleak 的固定验收程序（勿改）。
 *
 * <p>8 个场景覆盖 README「对外保证」的 8 条：
 * wake 2 / count 2 / idem 1 / timeout 2 / race 1。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。
 *
 * <p>并发场景一律用 {@link CountDownLatch} 栅栏把线程凑齐后再放行；判据只看可复核的
 * 计数与不变量（并发持有数上界、归还后的许可数、标志位），不看线程调度顺序、不看墙钟耗时。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗默认值（秒）。并发场景给足余量。 */
    private static final long WATCHDOG_SECONDS = 90L;

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final long watchdogSeconds;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            this.watchdogSeconds = WATCHDOG_SECONDS;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(s.watchdogSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + s.watchdogSeconds + " 秒内给出 PASS/FAIL 实际="
                    + s.watchdogSeconds + " 秒看门狗超时（子进程已强杀，多半是丢唤醒 / 死锁）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------------ 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario("wake/no-loss", Checker::wakeNoLoss));
        list.add(new Scenario("wake/close-wakes-all", Checker::wakeCloseWakesAll));
        list.add(new Scenario("count/available-inuse", Checker::countAvailableInUse));
        list.add(new Scenario("count/cycle-100k", Checker::countCycle100k));
        list.add(new Scenario("idem/repeated-close", Checker::idemRepeatedClose));
        list.add(new Scenario("timeout/returns-null", Checker::timeoutReturnsNull));
        list.add(new Scenario("timeout/interrupt", Checker::timeoutInterrupt));
        list.add(new Scenario("race/atomic-invariants", Checker::raceAtomicInvariants));
        return list;
    }

    // ------------------------------------------------------------------ wake

    private static String wakeNoLoss() throws Exception {
        Pool p = new Pool(1);
        Handle held = p.acquire();
        AtomicReference<Handle> got = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                got.set(p.acquire());
            } catch (Throwable e) {
                err.set(e);
            }
        }, "poolleak-waiter");
        worker.setDaemon(true);
        worker.start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            return "期望=等待线程及时启动 实际=未启动";
        }
        sleepQuietly(400L);
        held.close();
        worker.join(5000L);
        if (worker.isAlive()) {
            return "期望=一次归还唤醒等待者 实际=5 秒内等待者仍阻塞（丢唤醒）";
        }
        if (err.get() != null) {
            return "期望=等待者拿到 Handle 实际=抛 " + err.get();
        }
        if (got.get() == null) {
            return "期望=等待者拿到 Handle 实际=null";
        }
        String e = expect("唤醒后 available()", p.available(), 0);
        if (e != null) {
            return e;
        }
        got.get().close();
        e = expect("归还后 available()", p.available(), 1);
        p.close();
        return e;
    }

    private static String wakeCloseWakesAll() throws Exception {
        Pool p = new Pool(1);
        Handle held = p.acquire();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<String> bad = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                Handle h = p.acquire();
                h.close();
                bad.set("acquire 正常返回（未抛 IllegalStateException）");
            } catch (IllegalStateException expected) {
                // ok
            } catch (Throwable e) {
                err.set(e);
            }
        }, "poolleak-waiter");
        worker.setDaemon(true);
        worker.start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            return "期望=等待线程及时启动 实际=未启动";
        }
        sleepQuietly(400L);
        p.close();
        worker.join(5000L);
        if (worker.isAlive()) {
            return "期望=close() 唤醒等待中的 acquire 实际=5 秒内仍阻塞（等待者未被唤醒）";
        }
        if (err.get() != null) {
            return "期望=IllegalStateException 实际=抛 " + err.get();
        }
        if (bad.get() != null) {
            return "期望=IllegalStateException 实际=" + bad.get();
        }
        try {
            held.close();
        } catch (Throwable e) {
            return "期望=池关闭后已持有的 Handle.close() 不抛异常 实际=" + e;
        }
        return expect("池关闭后归还的 available()", p.available(), 1);
    }

    // ------------------------------------------------------------------ count

    private static String countAvailableInUse() throws Exception {
        final int capacity = 8;
        final int threads = 12;
        final int rounds = 20000;
        Pool p = new Pool(capacity);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        Handle h = p.acquire();
                        h.close();
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            }, "poolleak-storm-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        start.countDown();
        boolean alive = false;
        for (Thread t : workers) {
            t.join(30000L);
            if (t.isAlive()) {
                alive = true;
            }
        }
        if (alive) {
            return "期望=并发压测在 30 秒内结束 实际=仍有线程阻塞（丢唤醒 / 死锁）";
        }
        if (err.get() != null) {
            return "期望=压测无异常 实际=" + err.get();
        }
        String e = expect("压测后 available()", p.available(), capacity);
        if (e != null) {
            return e;
        }
        e = expect("压测后 inUse()", p.inUse(), 0);
        if (e != null) {
            return e;
        }
        e = expect("压测后 available()+inUse()", p.available() + p.inUse(), capacity);
        p.close();
        return e;
    }

    private static String countCycle100k() throws Exception {
        Pool p = new Pool(4);
        for (int i = 0; i < 100_000; i++) {
            Handle h = p.acquire();
            h.close();
            if (i % 10000 == 0) {
                String e = expect("第 " + i + " 次后 available()+inUse()", p.available() + p.inUse(), 4);
                if (e != null) {
                    return e;
                }
            }
        }
        String e = expect("10 万次后 available()", p.available(), 4);
        if (e != null) {
            return e;
        }
        e = expect("10 万次后 inUse()", p.inUse(), 0);
        p.close();
        return e;
    }

    // ------------------------------------------------------------------ idem

    private static String idemRepeatedClose() throws Exception {
        Pool p = new Pool(2);
        Handle h = p.acquire();
        h.close();
        h.close();
        h.close();
        String e = expect("重复 close 后 available()", p.available(), 2);
        if (e != null) {
            return e;
        }
        e = expect("重复 close 后 inUse()", p.inUse(), 0);
        if (e != null) {
            return e;
        }
        Handle h2 = p.acquire();
        e = expect("池仍可用（available()）", p.available(), 1);
        if (e != null) {
            return e;
        }
        h2.close();
        e = expect("再次归还后 available()", p.available(), 2);
        p.close();
        return e;
    }

    // ------------------------------------------------------------------ timeout

    private static String timeoutReturnsNull() throws Exception {
        Pool p = new Pool(1);
        Handle held = p.acquire();
        Handle n = p.acquire(200L, TimeUnit.MILLISECONDS);
        if (n != null) {
            n.close();
            return "期望=无许可时超时返回 null 实际=拿到 Handle";
        }
        held.close();
        String e = expect("超时后归还的 available()", p.available(), 1);
        if (e != null) {
            return e;
        }
        e = expect("超时后 inUse()", p.inUse(), 0);
        if (e != null) {
            return e;
        }

        // 等待期间若有许可归还，必须能拿到
        Pool q = new Pool(1);
        Handle h0 = q.acquire();
        CountDownLatch started = new CountDownLatch(1);
        Thread releaser = new Thread(() -> {
            started.countDown();
            sleepQuietly(200L);
            h0.close();
        }, "poolleak-releaser");
        releaser.setDaemon(true);
        releaser.start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            return "期望=归还线程及时启动 实际=未启动";
        }
        Handle got = q.acquire(5000L, TimeUnit.MILLISECONDS);
        if (got == null) {
            return "期望=等待期间归还后 acquire(timeout) 拿到 Handle 实际=null";
        }
        got.close();
        q.close();
        p.close();
        return null;
    }

    private static String timeoutInterrupt() throws Exception {
        String e = interruptCase(false);
        if (e != null) {
            return e;
        }
        return interruptCase(true);
    }

    private static String interruptCase(boolean timed) throws Exception {
        Pool p = new Pool(1);
        Handle held = p.acquire();
        AtomicReference<Boolean> flagRestored = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<String> bad = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                Handle h = timed ? p.acquire(10000L, TimeUnit.MILLISECONDS) : p.acquire();
                h.close();
                bad.set("正常返回（未抛 InterruptedException）");
            } catch (InterruptedException ie) {
                flagRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                err.set(t);
            }
        }, "poolleak-interrupted");
        worker.setDaemon(true);
        worker.start();
        if (!started.await(5, TimeUnit.SECONDS)) {
            return "期望=等待线程及时启动 实际=未启动";
        }
        sleepQuietly(400L);
        worker.interrupt();
        worker.join(5000L);
        if (worker.isAlive()) {
            return "期望=中断唤醒 acquire 实际=5 秒内仍阻塞";
        }
        if (err.get() != null) {
            return "期望=InterruptedException 实际=抛 " + err.get();
        }
        if (bad.get() != null) {
            return "期望=InterruptedException 实际=" + bad.get();
        }
        if (flagRestored.get() == null || !flagRestored.get()) {
            return "期望=抛出 InterruptedException 后中断标志被恢复 实际=" + flagRestored.get();
        }
        held.close();
        p.close();
        return null;
    }

    // ------------------------------------------------------------------ race

    private static String raceAtomicInvariants() throws Exception {
        final int capacity = 8;
        final int threads = 16;
        final int rounds = 3000;
        Pool p = new Pool(capacity);
        final AtomicInteger cur = new AtomicInteger();
        final AtomicInteger maxCur = new AtomicInteger();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        Handle h = p.acquire();
                        int c = cur.incrementAndGet();
                        maxCur.accumulateAndGet(c, Math::max);
                        // 先退出计数、再归还许可：保证「计数窗口」不会因 close 之后的间隙而虚高
                        cur.decrementAndGet();
                        h.close();
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            }, "poolleak-race-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        start.countDown();
        boolean alive = false;
        for (Thread t : workers) {
            t.join(40000L);
            if (t.isAlive()) {
                alive = true;
            }
        }
        if (alive) {
            return "期望=16 线程交错压测在 40 秒内结束 实际=仍有线程阻塞（丢唤醒 / 死锁）";
        }
        if (err.get() != null) {
            return "期望=压测无异常 实际=" + err.get();
        }
        if (maxCur.get() > capacity) {
            return "期望=并发持有数上界 " + capacity + " 实际峰值=" + maxCur.get() + "（一次归还放行了多个等待者）";
        }
        String e = expect("压测后 available()", p.available(), capacity);
        if (e != null) {
            return e;
        }
        e = expect("压测后 inUse()", p.inUse(), 0);
        if (e != null) {
            return e;
        }
        p.close();

        // 子检查：两个等待者、一次归还，恰好放行一个
        Pool q = new Pool(1);
        Handle h0 = q.acquire();
        final AtomicInteger released = new AtomicInteger();
        final List<Handle> kept = new ArrayList<>();
        final CountDownLatch two = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                two.countDown();
                try {
                    Handle h = q.acquire();
                    released.incrementAndGet();
                    // 一直持有，等测量结束；同时保持强引用，避免被 Cleaner 提前回收归还
                    synchronized (kept) {
                        kept.add(h);
                    }
                } catch (Throwable ignored) {
                    // 池关闭后等待者抛 IllegalStateException，正常
                }
            }, "poolleak-two-" + i);
            t.setDaemon(true);
            t.start();
        }
        if (!two.await(5, TimeUnit.SECONDS)) {
            return "期望=两个等待者及时启动 实际=未启动";
        }
        sleepQuietly(400L);
        h0.close();
        long deadline = System.currentTimeMillis() + 3000L;
        while (released.get() == 0 && System.currentTimeMillis() < deadline) {
            sleepQuietly(20L);
        }
        sleepQuietly(500L);
        int releasedCount = released.get();
        q.close();
        if (releasedCount != 1) {
            return "期望=一次归还恰好放行一个等待者 实际=放行 " + releasedCount + " 个";
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    private static String expect(String label, int actual, int wanted) {
        if (actual == wanted) {
            return null;
        }
        return label + " 期望=" + wanted + " 实际=" + actual;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Checker() {
    }
}
