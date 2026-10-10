package git.chexson.chexsonsaeutils.crafting.progress;

import java.util.concurrent.*;

/** Future 协议原样委托；只额外携带请求进度，取消仍传播到原任务。 */
public final class PlanningProgressFuture<T> implements Future<T>, PlanningProgressSource {
    private final Future<T> delegate;
    private final PlanningProgress progress;
    public PlanningProgressFuture(Future<T> delegate, PlanningProgress progress) {
        this.delegate = delegate; this.progress = progress;
    }
    /** 在后台线程中建立进度上下文，覆盖编译和全部求解尝试。 */
    public static <T> PlanningProgressFuture<T> submit(ExecutorService executor, Callable<T> work) {
        var progress = new PlanningProgressImpl();
        Future<T> future = executor.submit(() -> {
            try (var scope = PlanningProgressContext.open(progress)) { return work.call(); }
        });
        return new PlanningProgressFuture<>(future, progress);
    }
    @Override public PlanningProgress planningProgress() { return progress; }
    @Override public boolean cancel(boolean interrupt) { return delegate.cancel(interrupt); }
    @Override public boolean isCancelled() { return delegate.isCancelled(); }
    @Override public boolean isDone() { return delegate.isDone(); }
    @Override public T get() throws InterruptedException, ExecutionException { return delegate.get(); }
    @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.get(timeout, unit);
    }
}
