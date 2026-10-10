package git.chexson.chexsonsaeutils.crafting.remote;
import java.util.concurrent.*;
import java.util.function.Supplier;
/** 跨网计算的有界线程池。AE2 原生计算池为 private，因此使用独立公开装配，不反射读取。 */
public final class RemoteCalculations {
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                var thread = new Thread(task, "chexsonsaeutils-remote-crafting");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { POOL.allowCoreThreadTimeOut(true); }
    private RemoteCalculations() {}
    /** 不阻塞服务器；容量耗尽直接拒绝，调用侧记录并显示错误。 */
    public static <T> Future<T> submit(Supplier<T> task) { return POOL.submit(task::get); }
}
