package git.chexson.chexsonsaeutils.crafting.progress;

/** 单求解线程写入、菜单线程读取；只在阶段切换及抽样点发布轻量快照。 */
public final class PlanningProgressImpl implements PlanningProgress {
    private final long started = System.nanoTime();
    private volatile Snapshot latest = new Snapshot("QUEUED", 0L, 0L);
    private long steps;
    /** 开始编译、求解、留种、转换或原生计算阶段，累计工作不重置。 */
    public void phase(String phase) { latest = new Snapshot(phase, steps, 0L); }
    /** 报告实际执行的求解步骤，跨二分试探累计。 */
    public void advance(long count) {
        if (count < 0) throw new IllegalArgumentException("Progress count must be nonnegative");
        steps = Math.addExact(steps, count);
        latest = new Snapshot(latest.phase(), steps, 0L);
    }
    @Override public Snapshot snapshot() {
        Snapshot value = latest;
        return new Snapshot(value.phase(), value.steps(), Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    }
}
