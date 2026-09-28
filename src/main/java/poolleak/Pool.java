package poolleak;

import java.lang.ref.Cleaner;
import java.util.concurrent.TimeUnit;

/**
 * 固定容量资源池：池里恰好有 `capacity` 个许可，`acquire()` 取一个、`Handle.close()` 还一个。
 *
 * <p>对外契约见 README「对外保证」8 条。公开的类型名、方法名与签名已经是最终形态，
 * 不要改动；内部表示与私有方法可以自由设计（也可以新增类）。
 */
public class Pool implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final int capacity;
    private final Object lock = new Object();

    private int available;
    private int inUse;
    private boolean closed;

    /**
     * @throws IllegalArgumentException {@code capacity < 0}
     */
    public Pool(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity 不能为负：" + capacity);
        }
        this.capacity = capacity;
        this.available = capacity;
        this.inUse = 0;
    }

    /** 池容量。 */
    public int capacity() {
        return capacity;
    }

    /** 当前可用许可数。 */
    public int available() {
        return available;
    }

    /** 当前已借出许可数。 */
    public int inUse() {
        return inUse;
    }

    /**
     * 取一个许可，池满时阻塞。
     *
     * @throws InterruptedException 等待期间线程被中断
     */
    public Handle acquire() throws InterruptedException {
        synchronized (lock) {
            if (available == 0) {
                if (closed) {
                    throw new IllegalStateException("池已关闭");
                }
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
            available--;
        }
        inUse++;
        return newHandle();
    }

    /**
     * 取一个许可，最多等 {@code timeout}。
     *
     * @return 拿到则返回句柄，超时返回 {@code null}
     * @throws InterruptedException 等待期间线程被中断
     */
    public Handle acquire(long timeout, TimeUnit unit) throws InterruptedException {
        long millis = Math.max(0L, unit.toMillis(timeout));
        synchronized (lock) {
            if (available == 0) {
                if (closed) {
                    throw new IllegalStateException("池已关闭");
                }
                try {
                    lock.wait(millis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (available == 0) {
                    available--;
                    return null;
                }
            }
            available--;
        }
        inUse++;
        return newHandle();
    }

    /** 归还一个许可（由 {@link Handle#close()} 调用）。 */
    void returnPermit() {
        inUse--;
        synchronized (lock) {
            available++;
            lock.notifyAll();
        }
    }

    /** 关闭池（见 README 对外保证第 5 条）。 */
    @Override
    public void close() {
        closed = true;
    }

    private Handle newHandle() {
        Handle h = new Handle(this);
        CLEANER.register(h, new ReturnPermit(this));
        return h;
    }

    private static final class ReturnPermit implements Runnable {
        private final Pool pool;

        ReturnPermit(Pool pool) {
            this.pool = pool;
        }

        @Override
        public void run() {
            pool.returnPermit();
        }
    }
}
