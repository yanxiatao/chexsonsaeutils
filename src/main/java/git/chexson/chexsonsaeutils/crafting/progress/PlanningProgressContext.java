package git.chexson.chexsonsaeutils.crafting.progress;

/** 为嵌套求解传播当前请求；Scope 必须关闭以避免线程池请求串号。 */
public final class PlanningProgressContext {
    private static final ThreadLocal<PlanningProgressImpl> CURRENT = new ThreadLocal<>();
    private PlanningProgressContext() {}
    public static PlanningProgressImpl current() { return CURRENT.get(); }
    /** 进入请求上下文，关闭时恢复调用方上下文。 */
    public static Scope open(PlanningProgressImpl progress) {
        var previous = CURRENT.get(); CURRENT.set(progress); return new Scope(previous);
    }
    public static final class Scope implements AutoCloseable {
        private final PlanningProgressImpl previous;
        private Scope(PlanningProgressImpl previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
