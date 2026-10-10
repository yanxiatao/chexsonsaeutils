package git.chexson.chexsonsaeutils.crafting.memory;

/** JVM 堆余量采样：预留取配置下限与最大堆 10% 的较大值，不主动触发 GC。 */
final class HeapPressureImpl implements HeapPressure {
    private final long reserveBytes;
    HeapPressureImpl(long reserveBytes) {
        if (reserveBytes < 0) throw new IllegalArgumentException("Heap reserve must be nonnegative");
        this.reserveBytes = reserveBytes;
    }
    @Override public Sample sample() {
        Runtime runtime = Runtime.getRuntime();
        return fromUsage(runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory(), reserveBytes);
    }
    /** 使用与实机相同的公式验收未提交堆空间及分配预估。 */
    static Sample fromUsage(long maximum, long committed, long free, long reserveBytes) {
        long used = Math.max(0L, committed - free);
        return new Sample(Math.max(0L, maximum - used), Math.max(reserveBytes, maximum / 10L));
    }
}
