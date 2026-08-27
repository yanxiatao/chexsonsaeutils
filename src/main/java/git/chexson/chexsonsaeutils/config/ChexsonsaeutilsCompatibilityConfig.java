package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ChexsonsaeutilsCompatibilityConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED;
    public static final ModConfigSpec.BooleanValue PROCESSING_PATTERN_REPLACEMENT_ENABLED;
    public static final ModConfigSpec.IntValue MAX_CUSTOM_PATTERN_PAGES;
    public static final ModConfigSpec.BooleanValue DYEABLE_PATTERNS_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT;
    public static final ModConfigSpec.BooleanValue ENHANCED_CRAFTING_STATUS_ENABLED;
    public static final ModConfigSpec.BooleanValue BUILDING_GADGETS2_INTEGRATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FTB_ULTIMINE_MEMORY_CARD_ENABLED;
    public static final ModConfigSpec.BooleanValue PARALLEL_CRAFTING_CPU_ENABLED;
    public static final ModConfigSpec.BooleanValue PARALLEL_CPU_FAST_PLANNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CPU_FAST_PLANNING_BUDGET_MILLIS;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>>
            AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS;
    public static final ModConfigSpec.ConfigValue<String> AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE;
    public static final ModConfigSpec.BooleanValue AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED;
    public static final ModConfigSpec.BooleanValue HIGH_CAPACITY_CRAFTING_MACHINE_ENABLED;
    public static final ModConfigSpec.BooleanValue AE_DIRECT_PROCESSING_MACHINE_ENABLED;
    public static final ModConfigSpec.BooleanValue CUSTOM_PATTERN_PROVIDER_ENABLED;
    public static final ModConfigSpec.BooleanValue MULTI_LEVEL_EMITTER_ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_ITEM_GEN_ENABLED;
    public static final ModConfigSpec.BooleanValue INFINITY_CELL_ENABLED;
    public static final ModConfigSpec.BooleanValue SLOT_NUMBER_OVERLAY_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_TOTAL_PATTERN_SLOTS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_DEFAULT_OPERATION_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_QUEUE_TASKS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_BATCHES;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK;
    public static final ModConfigSpec.ConfigValue<Integer> HIGH_CAPACITY_TOTAL_PATTERN_SLOTS;
    public static final ModConfigSpec.ConfigValue<Integer> HIGH_CAPACITY_DEFAULT_BASE_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> HIGH_CAPACITY_LOCAL_EXECUTION_QUEUE_CAPACITY;
    public static final ModConfigSpec.ConfigValue<Long> HIGH_CAPACITY_TICK_SOFT_BUDGET_NANOS;
    public static final ModConfigSpec.ConfigValue<Long> HIGH_CAPACITY_TICK_HARD_BUDGET_NANOS;
    public static final ModConfigSpec.ConfigValue<Long> HIGH_CAPACITY_TICK_ABSOLUTE_BUDGET_NANOS;
    public static final ModConfigSpec.ConfigValue<Integer> HIGH_CAPACITY_COMPLETION_PROGRESS_SAVE_INTERVAL;
    public static final ModConfigSpec.ConfigValue<Integer> HIGH_CAPACITY_QUEUE_PROGRESS_SAVE_INTERVAL;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_BASE_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_MAX_TICKS;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_REINJECT_PATTERN_INPUTS_PER_TICK_PER_GRID;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Core Feature Toggles")
                .translation("configuration.chexsonsaeutils.section.features")
                .push("features");
        CRAFTING_CONTINUATION_ENABLED = builder
                .comment("Enable AE2 crafting continuation / ignore-missing feature bundle.",
                        "Allows crafting to continue when some patterns are temporarily unavailable.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.craftingContinuationEnabled")
                .define("craftingContinuationEnabled", true);
        FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED = builder
                .comment("Enable formal machine large-request planning aggregation fast path.",
                        "Optimizes planning for bulk crafting requests.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.formalMachinePlanningAggregationEnabled")
                .define("formalMachinePlanningAggregationEnabled", true);
        PROCESSING_PATTERN_REPLACEMENT_ENABLED = builder
                .comment("Enable AE2 processing pattern replacement feature bundle.",
                        "Allows dynamic pattern substitution based on available materials.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.processingPatternReplacementEnabled")
                .define("processingPatternReplacementEnabled", true);
        MAX_CUSTOM_PATTERN_PAGES = builder
                .comment("Maximum pattern pages of the frame pattern provider.",
                        "Each page holds 36 pattern slots; the pattern inventory capacity is pages * 36.",
                        "Extending pages is unlocked by consuming items (phase 5b), and expanded pages are",
                        "kept when the frame is dismantled. Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.maxFramePatternPages")
                .defineInRange("maxFramePatternPages", 8, 1, 8);
        HIGH_CAPACITY_CRAFTING_MACHINE_ENABLED = builder
                .comment("Enable the high-capacity crafting machine behavior.",
                        "Registered blocks/items stay available for world compatibility;",
                        "when disabled, placed machines stop processing. Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.highCapacityCraftingMachineEnabled")
                .define("highCapacityCraftingMachineEnabled", true);
        AE_DIRECT_PROCESSING_MACHINE_ENABLED = builder
                .comment("Enable the AE direct processing machine behavior.",
                        "Registered blocks/items stay available for world compatibility;",
                        "when disabled, placed machines stop processing. Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.aeDirectProcessingMachineEnabled")
                .define("aeDirectProcessingMachineEnabled", true);
        CUSTOM_PATTERN_PROVIDER_ENABLED = builder
                .comment("Enable the custom pattern provider active behavior.",
                        "Registered blocks/items stay available for world compatibility;",
                        "when disabled, providers stop active extraction and pushing.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.customPatternProviderEnabled")
                .define("customPatternProviderEnabled", true);
        MULTI_LEVEL_EMITTER_ENABLED = builder
                .comment("Enable the multi-level emitter output logic.",
                        "Registered parts stay available for world compatibility;",
                        "when disabled, emitters always read as off. Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.multiLevelEmitterEnabled")
                .define("multiLevelEmitterEnabled", true);
        AUTO_ITEM_GEN_ENABLED = builder
                .comment("Enable the auto item generator (debug) block behavior.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.autoItemGenEnabled")
                .define("autoItemGenEnabled", true);
        INFINITY_CELL_ENABLED = builder
                .comment("Enable the infinity storage cell registration.",
                        "Has no effect after restart.")
                .translation("configuration.chexsonsaeutils.infinityCellEnabled")
                .define("infinityCellEnabled", true);
        SLOT_NUMBER_OVERLAY_ENABLED = builder
                .comment("Enable the client-side slot number overlay.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.slotNumberOverlayEnabled")
                .define("slotNumberOverlayEnabled", true);
        builder.pop();

        builder.comment("AEA Feature Migration")
                .translation("configuration.chexsonsaeutils.section.aeaMigration")
                .push("aeaMigration");
        DYEABLE_PATTERNS_ENABLED = builder
                .comment(
                        "Enable AEA dyeable pattern migration.",
                        "Extends AE2 pattern color handling while preserving existing replacement,",
                        "continuation, formal-machine, and parallel CPU planning semantics.",
                        "Takes effect after restart."
                )
                .translation("configuration.chexsonsaeutils.dyeablePatternsEnabled")
                .define("dyeablePatternsEnabled", true);
        DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT = builder
                .comment(
                        "Minimum amount of each dyeable recursive catalyst to keep after a crafting plan.",
                        "When a plan would consume the last catalyst, dyeable recursive planning",
                        "crafts enough extra copies to keep this reserve available."
                )
                .translation("configuration.chexsonsaeutils.dyeableRecursiveRetainedCatalystAmount")
                .defineInRange("dyeableRecursiveRetainedCatalystAmount",
                        1, 0, Integer.MAX_VALUE);
        ENHANCED_CRAFTING_STATUS_ENABLED = builder
                .comment(
                        "Enable AEA enhanced crafting status migration.",
                        "Adds Blocked and Pattern Times status data without replacing existing",
                        "continuation, formal-machine, or parallel CPU UI state.",
                        "Takes effect after restart."
                )
                .translation("configuration.chexsonsaeutils.enhancedCraftingStatusEnabled")
                .define("enhancedCraftingStatusEnabled", true);
        BUILDING_GADGETS2_INTEGRATION_ENABLED = builder
                .comment(
                        "Enable AEA Building Gadgets 2 template-to-processing-pattern integration.",
                        "Has no effect unless Building Gadgets 2 is installed.",
                        "Generates standard AE2 processing patterns without replacement metadata.",
                        "Takes effect after restart."
                )
                .translation("configuration.chexsonsaeutils.buildingGadgets2IntegrationEnabled")
                .define("buildingGadgets2IntegrationEnabled", true);
        FTB_ULTIMINE_MEMORY_CARD_ENABLED = builder
                .comment(
                        "Enable AEA FTB Ultimine memory-card compatibility.",
                        "Has no effect unless FTB Ultimine is installed.",
                        "Does not change AE2 single-target memory-card behavior.",
                        "Takes effect after restart."
                )
                .translation("configuration.chexsonsaeutils.ftbUltimineMemoryCardEnabled")
                .define("ftbUltimineMemoryCardEnabled", true);
        builder.pop();

        builder.comment("Parallel Crafting CPU Tool")
                .translation("configuration.chexsonsaeutils.section.parallelCraftingCpuTool")
                .push("parallelCraftingCpuTool");
        PARALLEL_CRAFTING_CPU_ENABLED = builder
                .comment("Enable the official extreme-parallel AE2 CPU tool block.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuEnabled")
                .define("parallelCraftingCpuEnabled", true);
        PARALLEL_CPU_FAST_PLANNING_ENABLED = builder
                .comment("Enable the parallel CPU fast crafting-plan calculation path.",
                        "When the AE network hosts this mod's parallel CPU, crafting plans are",
                        "computed on a dedicated fast path without AE2's per-tick time slicing,",
                        "which is much faster for large jobs. On any failure the calculation",
                        "automatically falls back to the native AE2 path. Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.parallelCpuFastPlanningEnabled")
                .define("parallelCpuFastPlanningEnabled", true);
        PARALLEL_CPU_FAST_PLANNING_BUDGET_MILLIS = builder
                .comment("Wall-clock budget in milliseconds for a single fast planning run.",
                        "If the fast path exceeds it, the calculation falls back to the throttled",
                        "native AE2 path. 0 disables the budget (run to completion).")
                .translation("configuration.chexsonsaeutils.parallelCpuFastPlanningBudgetMillis")
                .defineInRange("parallelCpuFastPlanningBudgetMillis", 10_000, 0, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK = builder
                .comment("Maximum internal crafting lanes per tool block.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxInternalLanesPerBlock")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerBlock",
                        65_536, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID = builder
                .comment("Maximum internal crafting lanes per AE grid.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxInternalLanesPerGrid")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerGrid",
                        1_048_576, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID = builder
                .comment("Maximum parallel CPU job submissions accepted per grid tick.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxSubmissionsPerTickPerGrid")
                .defineInRange("parallelCraftingCpuMaxSubmissionsPerTickPerGrid",
                        4_096, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID = builder
                .comment("Maximum pattern pushes budgeted per grid tick.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxPatternPushesPerTickPerGrid")
                .defineInRange("parallelCraftingCpuMaxPatternPushesPerTickPerGrid",
                        1_048_576L, 1L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID = builder
                .comment("Maximum provider checks budgeted per grid tick.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxProviderChecksPerTickPerGrid")
                .defineInRange("parallelCraftingCpuMaxProviderChecksPerTickPerGrid",
                        8_388_608L, 1L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID = builder
                .comment("Maximum wall-clock CPU scheduling budget per grid tick.",
                        "Clamped to 45 ms.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuTickBudgetNanosPerGrid")
                .defineInRange("parallelCraftingCpuTickBudgetNanosPerGrid",
                        20_000_000L, 1L, 45_000_000L);
        PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT = builder
                .comment("Shard count used by the extreme-lane scheduler.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuLaneShardCount")
                .defineInRange("parallelCraftingCpuLaneShardCount",
                        4_096, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_BASE_TICKS = builder
                .comment("Base exponential backoff delay in ticks for busy pattern providers.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuProviderBackoffBaseTicks")
                .defineInRange("parallelCraftingCpuProviderBackoffBaseTicks",
                        2, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_MAX_TICKS = builder
                .comment("Maximum backoff delay in ticks for busy pattern providers.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuProviderBackoffMaxTicks")
                .defineInRange("parallelCraftingCpuProviderBackoffMaxTicks",
                        40, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK_PER_GRID = builder
                .comment("Maximum pattern input extractions budgeted per grid tick.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxExtractPatternInputsPerTickPerGrid")
                .defineInRange("parallelCraftingCpuMaxExtractPatternInputsPerTickPerGrid",
                        1_048_576L, 1L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_MAX_REINJECT_PATTERN_INPUTS_PER_TICK_PER_GRID = builder
                .comment("Maximum pattern input reinjections budgeted per grid tick.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxReinjectPatternInputsPerTickPerGrid")
                .defineInRange("parallelCraftingCpuMaxReinjectPatternInputsPerTickPerGrid",
                        1_048_576L, 1L, Long.MAX_VALUE / 4L);
        builder.pop();

        builder.comment("AE Direct Processing Machine")
                .translation("configuration.chexsonsaeutils.section.aeDirectProcessingMachine")
                .push("aeDirectProcessingMachine");
        AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS = builder
                .comment(
                        "Local direct processing machine recipe mappings.",
                        "Format: machine_id=recipe_type_id=default_ticks",
                        "Example: mymod:crusher=mymod:crushing=40",
                        "Only affects the AE direct processing machine discovery layer."
                )
                .translation("configuration.chexsonsaeutils.recipeMappings")
                .defineListAllowEmpty(
                        "recipeMappings",
                        List.of(),
                        () -> "",
                        value -> value instanceof String stringValue && !stringValue.isBlank()
                );
        AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE = builder
                .comment(
                        "Execution budget profile for the AE direct processing machine.",
                        "Allowed values: normal, high, benchmark.",
                        "Benchmark is intended for controlled performance tests."
                )
                .translation("configuration.chexsonsaeutils.budgetProfile")
                .define("budgetProfile", "normal");
        AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED = builder
                .comment(
                        "Enable generic recipe type discovery for the AE direct processing machine.",
                        "When disabled, only explicit adapters and config/datapack mappings are used."
                )
                .translation("configuration.chexsonsaeutils.genericDiscoveryEnabled")
                .define("genericDiscoveryEnabled", true);
        AE_DIRECT_PROCESSING_MACHINE_TOTAL_PATTERN_SLOTS = builder
                .comment("Total pattern slot capacity of the AE direct processing machine.",
                        "Shrinking this below a machine's used slots may affect existing saves.")
                .translation("configuration.chexsonsaeutils.totalPatternSlots")
                .defineInRange("totalPatternSlots", 16_384, 1, 65_536);
        AE_DIRECT_PROCESSING_MACHINE_DEFAULT_OPERATION_TICKS = builder
                .comment("Base processing duration in ticks before speed card reduction.")
                .translation("configuration.chexsonsaeutils.defaultOperationTicks")
                .defineInRange("defaultOperationTicks", 20, 1, Integer.MAX_VALUE);
        AE_DIRECT_PROCESSING_MACHINE_MAX_QUEUE_TASKS = builder
                .comment("Maximum tasks kept in the execution queue per machine.")
                .translation("configuration.chexsonsaeutils.maxQueueTasks")
                .defineInRange("maxQueueTasks", 256, 1, Integer.MAX_VALUE);
        AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_BATCHES = builder
                .comment("Maximum output batches buffered before backpressure rejects new work.")
                .translation("configuration.chexsonsaeutils.maxPendingOutputBatches")
                .defineInRange("maxPendingOutputBatches", 1_024, 1, Integer.MAX_VALUE);
        AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS = builder
                .comment("Cap for the exponential retry delay when output return to the network fails.")
                .translation("configuration.chexsonsaeutils.maxPendingOutputRetryDelayTicks")
                .defineInRange("maxPendingOutputRetryDelayTicks", 20, 1, Integer.MAX_VALUE);
        AE_DIRECT_PROCESSING_MACHINE_DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK = builder
                .comment("Dirty pattern slot refreshes budgeted per tick per machine.")
                .translation("configuration.chexsonsaeutils.dirtyPatternRefreshBudgetPerTick")
                .defineInRange("dirtyPatternRefreshBudgetPerTick", 64, 1, Integer.MAX_VALUE);
        builder.pop();

        builder.comment("High Capacity Crafting Machine")
                .translation("configuration.chexsonsaeutils.section.highCapacityCraftingMachine")
                .push("highCapacityCraftingMachine");
        HIGH_CAPACITY_TOTAL_PATTERN_SLOTS = builder
                .comment("Total pattern slot capacity of high-capacity crafting hosts.",
                        "Shrinking this below a host's used slots may affect existing saves.")
                .translation("configuration.chexsonsaeutils.totalPatternSlots")
                .defineInRange("totalPatternSlots", 16_384, 1, 65_536);
        HIGH_CAPACITY_DEFAULT_BASE_TICKS = builder
                .comment("Base crafting duration in ticks before speed card reduction.")
                .translation("configuration.chexsonsaeutils.defaultBaseTicks")
                .defineInRange("defaultBaseTicks", 20, 1, Integer.MAX_VALUE);
        HIGH_CAPACITY_LOCAL_EXECUTION_QUEUE_CAPACITY = builder
                .comment("Maximum tasks kept in each host's local execution queue.")
                .translation("configuration.chexsonsaeutils.localExecutionQueueCapacity")
                .defineInRange("localExecutionQueueCapacity", 1_024, 1, Integer.MAX_VALUE);
        HIGH_CAPACITY_TICK_SOFT_BUDGET_NANOS = builder
                .comment("Soft wall-clock budget per host tick in nanos.")
                .translation("configuration.chexsonsaeutils.tickSoftBudgetNanos")
                .defineInRange("tickSoftBudgetNanos", 4_000_000L, 1L, 45_000_000L);
        HIGH_CAPACITY_TICK_HARD_BUDGET_NANOS = builder
                .comment("Hard wall-clock budget per host tick in nanos.")
                .translation("configuration.chexsonsaeutils.tickHardBudgetNanos")
                .defineInRange("tickHardBudgetNanos", 5_000_000L, 1L, 45_000_000L);
        HIGH_CAPACITY_TICK_ABSOLUTE_BUDGET_NANOS = builder
                .comment("Absolute wall-clock budget per host tick in nanos.",
                        "Must stay above the hard budget to remain effective.")
                .translation("configuration.chexsonsaeutils.tickAbsoluteBudgetNanos")
                .defineInRange("tickAbsoluteBudgetNanos", 6_000_000L, 1L, 45_000_000L);
        HIGH_CAPACITY_COMPLETION_PROGRESS_SAVE_INTERVAL = builder
                .comment("Completion slices processed before forcing a progress save.")
                .translation("configuration.chexsonsaeutils.completionProgressSaveInterval")
                .defineInRange("completionProgressSaveInterval", 32, 1, Integer.MAX_VALUE);
        HIGH_CAPACITY_QUEUE_PROGRESS_SAVE_INTERVAL = builder
                .comment("Queue mutations processed before forcing a progress save.")
                .translation("configuration.chexsonsaeutils.queueProgressSaveInterval")
                .defineInRange("queueProgressSaveInterval", 128, 1, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }

    /**
     * @return 配置允许的最大样板页数（1-8，默认 8）。
     *         阶段 1 共享层泛化：原 FramePatternProviderBlockEntity.maxPages() 静态
     *         方法移至此处，框架样板供应器与定制样板供应器共用
     */
    public static int maxFramePatternPages() {
        return MAX_CUSTOM_PATTERN_PAGES.get();
    }

    public static int intValue(ModConfigSpec.ConfigValue<Integer> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static long longValue(ModConfigSpec.ConfigValue<Long> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static boolean boolValue(ModConfigSpec.BooleanValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
