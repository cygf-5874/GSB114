package poolleak;

/**
 * 从 {@link Pool} 借出的一个句柄。`close()` 把许可还给池子。
 *
 * <p>句柄由 `Pool.acquire()` 产生，外部不要自己 new。
 */
public final class Handle implements AutoCloseable {

    private final Pool.Permit permit;

    Handle(Pool.Permit permit) {
        this.permit = permit;
    }

    /**
     * 归还许可（见 README 对外保证第 2 条）。
     *
     * <p>幂等：重复调用不会多还。句柄从未 close 而被 GC 时，Cleaner 会触发同一个
     * 一次性归还逻辑，因此「显式 close」与「GC 兜底」总共恰好归还一次。
     */
    @Override
    public void close() {
        permit.returnOnce();
    }
}
