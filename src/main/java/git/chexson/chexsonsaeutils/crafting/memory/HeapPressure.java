package git.chexson.chexsonsaeutils.crafting.memory;

/** 堆余量读口；在分配或日志迁移前判断是否已侵入安全预留，可注入确定读数验收。 */
public interface HeapPressure {
    /** 禁用自动迁移的策略；显式分页模式不需要额外采样。 */
    HeapPressure NONE = () -> new Sample(Long.MAX_VALUE, 0L);
    /** 当前可用堆上限余量与必须保留的迁移空间，单位为字节。 */
    Sample sample();
    /** 按服务端设置创建每次请求的策略，避免热路径读取配置。 */
    static HeapPressure configured() {
        return git.chexson.chexsonsaeutils.config.PlanningServerConfig.autoSpill()
                ? new HeapPressureImpl(git.chexson.chexsonsaeutils.config.PlanningServerConfig.heapReserveMiB() * 1048576L)
                : NONE;
    }
    /** 剩余上限已包含 JVM 尚未提交的空间，不能把 freeMemory 当成可用堆总量。 */
    record Sample(long availableBytes, long reserveBytes) {
        /** 下一次分配侵入预留时提前迁移；用减法避免相加回绕。 */
        public boolean needsSpill(long allocationBytes) {
            if (allocationBytes < 0) throw new IllegalArgumentException("Heap allocation estimate must be nonnegative");
            return availableBytes <= reserveBytes || allocationBytes >= availableBytes - reserveBytes;
        }
    }
}
