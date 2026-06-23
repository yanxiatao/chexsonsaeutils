package git.chexson.chexsonsaeutils.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;

public final class ParallelCraftingCpuConfig {

    public static final int DEFAULT_CO_PROCESSORS_PER_VIRTUAL_CPU = Integer.MAX_VALUE - 1;
    public static final int DEFAULT_MAX_INTERNAL_LANES_PER_BLOCK = 65_536;
    public static final int DEFAULT_MAX_INTERNAL_LANES_PER_GRID = 1_048_576;
    public static final int DEFAULT_MAX_SUBMISSIONS_PER_TICK_PER_GRID = 4_096;
    public static final long DEFAULT_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID = 1_048_576L;
    public static final long DEFAULT_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID = 8_388_608L;
    public static final long DEFAULT_TICK_BUDGET_NANOS_PER_GRID = 20_000_000L;
    public static final long DEFAULT_STORAGE_BYTES = Long.MAX_VALUE / 4L;
    public static final int DEFAULT_LANE_SHARD_COUNT = 4_096;

    public static final int MAX_CO_PROCESSORS_PER_VIRTUAL_CPU = Integer.MAX_VALUE - 1;
    public static final int MAX_INT_BUDGET = Integer.MAX_VALUE;
    public static final long MAX_LONG_BUDGET = Long.MAX_VALUE / 4L;
    public static final long MAX_TICK_BUDGET_NANOS_PER_GRID = 45_000_000L;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MIN_INT_BUDGET = 1;
    private static final int MIN_CO_PROCESSORS_PER_VIRTUAL_CPU = 0;
    private static final long MIN_LONG_BUDGET = 1L;
    private static final long MIN_STORAGE_BYTES = 1_024L;

    private ParallelCraftingCpuConfig() {
    }

    public static Settings current() {
        return read(false);
    }

    public static Settings loadWithStartupWarnings() {
        Settings settings = read(true);
        LOGGER.info(
                "AE2 parallel CPU tool config active: enabled={}, coProcessors={}, lanesPerBlock={}, lanesPerGrid={}, "
                        + "submissionsPerTick={}, pushesPerTick={}, providerChecksPerTick={}, tickBudgetNanos={}, "
                        + "storageBytes={}, laneShards={}",
                settings.enabled(),
                settings.coProcessorsPerVirtualCpu(),
                settings.maxInternalLanesPerBlock(),
                settings.maxInternalLanesPerGrid(),
                settings.maxSubmissionsPerTickPerGrid(),
                settings.maxPatternPushesPerTickPerGrid(),
                settings.maxProviderChecksPerTickPerGrid(),
                settings.tickBudgetNanosPerGrid(),
                settings.storageBytes(),
                settings.laneShardCount()
        );
        return settings;
    }

    private static Settings read(boolean warn) {
        return new Settings(
                booleanValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED),
                clampInt(
                        "parallelCraftingCpuCoProcessorsPerVirtualCpu",
                        intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_CO_PROCESSORS_PER_VIRTUAL_CPU),
                        MIN_CO_PROCESSORS_PER_VIRTUAL_CPU,
                        MAX_CO_PROCESSORS_PER_VIRTUAL_CPU,
                        warn
                ),
                clampInt(
                        "parallelCraftingCpuMaxInternalLanesPerBlock",
                        intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK),
                        MIN_INT_BUDGET,
                        MAX_INT_BUDGET,
                        warn
                ),
                clampInt(
                        "parallelCraftingCpuMaxInternalLanesPerGrid",
                        intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID),
                        MIN_INT_BUDGET,
                        MAX_INT_BUDGET,
                        warn
                ),
                clampInt(
                        "parallelCraftingCpuMaxSubmissionsPerTickPerGrid",
                        intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID),
                        MIN_INT_BUDGET,
                        MAX_INT_BUDGET,
                        warn
                ),
                clampLong(
                        "parallelCraftingCpuMaxPatternPushesPerTickPerGrid",
                        longValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID),
                        MIN_LONG_BUDGET,
                        MAX_LONG_BUDGET,
                        warn
                ),
                clampLong(
                        "parallelCraftingCpuMaxProviderChecksPerTickPerGrid",
                        longValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID),
                        MIN_LONG_BUDGET,
                        MAX_LONG_BUDGET,
                        warn
                ),
                clampLong(
                        "parallelCraftingCpuTickBudgetNanosPerGrid",
                        longValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID),
                        MIN_LONG_BUDGET,
                        MAX_TICK_BUDGET_NANOS_PER_GRID,
                        warn
                ),
                clampLong(
                        "parallelCraftingCpuStorageBytes",
                        longValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_STORAGE_BYTES),
                        MIN_STORAGE_BYTES,
                        MAX_LONG_BUDGET,
                        warn
                ),
                clampInt(
                        "parallelCraftingCpuLaneShardCount",
                        intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT),
                        MIN_INT_BUDGET,
                        MAX_INT_BUDGET,
                        warn
                )
        );
    }

    private static boolean booleanValue(ForgeConfigSpec.BooleanValue value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return value.getDefault();
    }

    private static int intValue(ForgeConfigSpec.ConfigValue<Integer> value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return value.getDefault();
    }

    private static long longValue(ForgeConfigSpec.ConfigValue<Long> value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return value.getDefault();
    }

    private static int clampInt(String key, int value, int minimum, int maximum, boolean warn) {
        int clamped = Math.max(minimum, Math.min(maximum, value));
        if (warn && clamped != value) {
            LOGGER.warn("Clamped AE2 parallel CPU config {} from {} to {}", key, value, clamped);
        }
        return clamped;
    }

    private static long clampLong(String key, long value, long minimum, long maximum, boolean warn) {
        long clamped = Math.max(minimum, Math.min(maximum, value));
        if (warn && clamped != value) {
            LOGGER.warn("Clamped AE2 parallel CPU config {} from {} to {}", key, value, clamped);
        }
        return clamped;
    }

    public record Settings(
            boolean enabled,
            int coProcessorsPerVirtualCpu,
            int maxInternalLanesPerBlock,
            int maxInternalLanesPerGrid,
            int maxSubmissionsPerTickPerGrid,
            long maxPatternPushesPerTickPerGrid,
            long maxProviderChecksPerTickPerGrid,
            long tickBudgetNanosPerGrid,
            long storageBytes,
            int laneShardCount
    ) {
    }
}
