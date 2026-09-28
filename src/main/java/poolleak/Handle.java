package poolleak;

/**
 * 从 {@link Pool} 借出的一个句柄。`close()` 把许可还给池子。
 *
 * <p>句柄由 `Pool.acquire()` 产生，外部不要自己 new。
 */
public final class Handle implements AutoCloseable {

    private final Pool pool;

    Handle(Pool pool) {
        this.pool = pool;
    }

    /** 归还许可（见 README 对外保证第 2 条）。 */
    @Override
    public void close() {
        pool.returnPermit();
    }
}
