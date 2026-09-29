package poolleak;

import java.lang.ref.Cleaner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 固定容量资源池：池里恰好有 `capacity` 个许可，`acquire()` 取一个、`Handle.close()` 还一个。
 *
 * <p>对外契约见 README「对外保证」8 条。公开的类型名、方法名与签名已经是最终形态，
 * 不要改动；内部表示与私有方法可以自由设计（也可以新增类）。
 */
public class Pool implements AutoCloseable {

    /**
     * 全池共享一个 Cleaner：只产生一条常驻守护线程，不会随池 / 句柄数量增长。
     */
    private static final Cleaner CLEANER = Cleaner.create();

    private final int capacity;
    private final Object lock = new Object();

    /* ---- 以下三个状态只允许在持有 lock 时读写 ---- */
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
        synchronized (lock) {
            return available;
        }
    }

    /** 当前已借出许可数。 */
    public int inUse() {
        synchronized (lock) {
            return inUse;
        }
    }

    /**
     * 取一个许可，池满时阻塞。
     *
     * @throws InterruptedException 等待期间线程被中断
     */
    public Handle acquire() throws InterruptedException {
        synchronized (lock) {
            // 必须在循环里复核条件：notify() 的漏唤醒、伪唤醒、池被关闭都要靠它处理。
            while (available == 0) {
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
            if (closed) {
                throw new IllegalStateException("池已关闭");
            }
            available--;
            inUse++;
            return newHandleLocked();
        }
    }

    /**
     * 取一个许可，最多等 {@code timeout}。
     *
     * @return 拿到则返回句柄，超时返回 {@code null}
     * @throws InterruptedException 等待期间线程被中断
     */
    public Handle acquire(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = Math.max(0L, unit.toNanos(timeout));
        synchronized (lock) {
            while (available == 0) {
                if (closed) {
                    throw new IllegalStateException("池已关闭");
                }
                if (remainingNanos <= 0L) {
                    // 超时：什么都没借走，许可总数不变。
                    return null;
                }
                long start = System.nanoTime();
                try {
                    // 纳秒级定时等待，剩余时间在循环外重算，保证总等待不超过 timeout。
                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                remainingNanos -= System.nanoTime() - start;
            }
            if (closed) {
                throw new IllegalStateException("池已关闭");
            }
            available--;
            inUse++;
            return newHandleLocked();
        }
    }

    /** 归还一个许可（由 {@link Handle#close()} 或 Cleaner 触发，恰好归还一次）。 */
    void returnPermit() {
        synchronized (lock) {
            inUse--;
            available++;
            // 一次归还恰好放行一个等待者；没有等待者时许可留在池里，唤醒不会丢。
            lock.notify();
        }
    }

    /** 关闭池：唤醒所有等待中的 acquire（见 README 对外保证第 5 条）。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            // 池关闭必须唤醒全部等待者，让它们复核 closed 后抛 IllegalStateException。
            lock.notifyAll();
        }
    }

    /** 调用方必须持有 {@link #lock}。 */
    private Handle newHandleLocked() {
        Permit permit = new Permit(this);
        Handle handle = new Handle(permit);
        // 清理动作只引用 permit（不引用 handle），句柄失联后才会被 PhantomReference 触发。
        CLEANER.register(handle, permit);
        return handle;
    }

    /**
     * 一个借出许可的「归还凭证」：显式 close 与 Cleaner 兜底归还共用同一条 CAS，
     * 保证许可在任意情况下恰好归还一次（幂等、不重复计数）。
     */
    static final class Permit implements Runnable {
        private final Pool pool;
        private final AtomicBoolean returned = new AtomicBoolean();

        Permit(Pool pool) {
            this.pool = pool;
        }

        @Override
        public void run() {
            returnOnce();
        }

        void returnOnce() {
            if (returned.compareAndSet(false, true)) {
                pool.returnPermit();
            }
        }
    }
}
