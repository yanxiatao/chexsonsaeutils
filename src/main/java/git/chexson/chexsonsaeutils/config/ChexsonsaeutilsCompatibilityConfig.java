package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ChexsonsaeutilsCompatibilityConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FORMAL_MACHINE_CRAFTING_DISPATCH_ENABLED;
    public static final ModConfigSpec.BooleanValue FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED;
    public static final ModConfigSpec.BooleanValue PROCESSING_PATTERN_REPLACEMENT_ENABLED;
    public static final ModConfigSpec.BooleanValue DYEABLE_PATTERNS_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT;
    public static final ModConfigSpec.BooleanValue ENHANCED_CRAFTING_STATUS_ENABLED;
    public static final ModConfigSpec.BooleanValue BUILDING_GADGETS2_INTEGRATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FTB_ULTIMINE_MEMORY_CARD_ENABLED;
    public static final ModConfigSpec.BooleanValue PARALLEL_CRAFTING_CPU_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_CO_PROCESSORS_PER_VIRTUAL_CPU;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_STORAGE_BYTES;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>>
            AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS;
    public static final ModConfigSpec.ConfigValue<String> AE_DIRECT_PROCESSING_MACHINE_BUDGET_PROFILE;
    public static final ModConfigSpec.BooleanValue AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Core Feature Toggles").push("features");
        CRAFTING_CONTINUATION_ENABLED = builder
                .comment("Enable AE2 crafting continuation / ignore-missing feature bundle.",
                        "Allows crafting to continue when some patterns are temporarily unavailable.",
                        "Takes effect after restart.")
                .define("craftingContinuationEnabled", true);
        FORMAL_MACHINE_CRAFTING_DISPATCH_ENABLED = builder
                .comment("Enable formal machine AE2 crafting dispatch fast path.",
                        "Optimizes crafting dispatch for dedicated processing machines.",
                        "Takes effect after restart.")
                .define("formalMachineCraftingDispatchEnabled", false);
        FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED = builder
                .comment("Enable formal machine large-request planning aggregation fast path.",
                        "Optimizes planning for bulk crafting requests.",
                        "Takes effect after restart.")
                .define("formalMachinePlanningAggregationEnabled", true);
        PROCESSING_PATTERN_REPLACEMENT_ENABLED = builder
                .comment("Enable AE2 processing pattern replacement feature bundle.",
                        "Allows dynamic pattern substitution based on available materials.",
                        "Takes effect after restart.")
                .define("processingPatternReplacementEnabled", true);
        builder.pop();

        builder.comment("AEA Feature Migration").push("aeaMigration");
        DYEABLE_PATTERNS_ENABLED = builder
                .comment(
                        "Enable AEA dyeable pattern migration.",
                        "Extends AE2 pattern color handling while preserving existing replacement,",
                        "continuation, formal-machine, and parallel CPU planning semantics.",
                        "Takes effect after restart."
                )
                .define("dyeablePatternsEnabled", true);
        DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT = builder
                .comment(
                        "Minimum amount of each dyeable recursive catalyst to keep after a crafting plan.",
                        "When a plan would consume the last catalyst, dyeable recursive planning",
                        "crafts enough extra copies to keep this reserve available."
                )
                .defineInRange("dyeableRecursiveRetainedCatalystAmount",
                        DyeablePatternRecursiveConfig.DEFAULT_RETAINED_CATALYST_AMOUNT, 0, Integer.MAX_VALUE);
        ENHANCED_CRAFTING_STATUS_ENABLED = builder
                .comment(
                        "Enable AEA enhanced crafting status migration.",
                        "Adds Blocked and Pattern Times status data without replacing existing",
                        "continuation, formal-machine, or parallel CPU UI state.",
                        "Takes effect after restart."
                )
                .define("enhancedCraftingStatusEnabled", true);
        BUILDING_GADGETS2_INTEGRATION_ENABLED = builder
                .comment(
                        "Enable AEA Building Gadgets 2 template-to-processing-pattern integration.",
                        "Has no effect unless Building Gadgets 2 is installed.",
                        "Generates standard AE2 processing patterns without replacement metadata.",
                        "Takes effect after restart."
                )
                .define("buildingGadgets2IntegrationEnabled", true);
        FTB_ULTIMINE_MEMORY_CARD_ENABLED = builder
                .comment(
                        "Enable AEA FTB Ultimine memory-card compatibility.",
                        "Has no effect unless FTB Ultimine is installed.",
                        "Does not change AE2 single-target memory-card behavior.",
                        "Takes effect after restart."
                )
                .define("ftbUltimineMemoryCardEnabled", true);
        builder.pop();

        builder.comment("Parallel Crafting CPU Tool").push("parallelCraftingCpuTool");
        PARALLEL_CRAFTING_CPU_ENABLED = builder
                .comment("Enable the official extreme-parallel AE2 CPU tool block.",
                        "Takes effect after restart.")
                .define("parallelCraftingCpuEnabled", true);
        PARALLEL_CRAFTING_CPU_CO_PROCESSORS_PER_VIRTUAL_CPU = builder
                .comment("Advertised co-processors per virtual CPU.",
                        "Clamped below Integer.MAX_VALUE to avoid overflow.")
                .defineInRange("parallelCraftingCpuCoProcessorsPerVirtualCpu",
                        2_147_483_646, 0, Integer.MAX_VALUE - 1);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK = builder
                .comment("Maximum internal crafting lanes per tool block.")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerBlock",
                        65_536, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID = builder
                .comment("Maximum internal crafting lanes per AE grid.")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerGrid",
                        1_048_576, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID = builder
                .comment("Maximum parallel CPU job submissions accepted per grid tick.")
                .defineInRange("parallelCraftingCpuMaxSubmissionsPerTickPerGrid",
                        4_096, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID = builder
                .comment("Maximum pattern pushes budgeted per grid tick.")
                .defineInRange("parallelCraftingCpuMaxPatternPushesPerTickPerGrid",
                        1_048_576L, 1L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID = builder
                .comment("Maximum provider checks budgeted per grid tick.")
                .defineInRange("parallelCraftingCpuMaxProviderChecksPerTickPerGrid",
                        8_388_608L, 1L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID = builder
                .comment("Maximum wall-clock CPU scheduling budget per grid tick.",
                        "Clamped to 45 ms.")
                .defineInRange("parallelCraftingCpuTickBudgetNanosPerGrid",
                        20_000_000L, 1L, 45_000_000L);
        PARALLEL_CRAFTING_CPU_STORAGE_BYTES = builder
                .comment("Advertised crafting storage bytes for the tool.")
                .defineInRange("parallelCraftingCpuStorageBytes",
                        Long.MAX_VALUE / 4L, 1_024L, Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT = builder
                .comment("Shard count used by the extreme-lane scheduler.")
                .defineInRange("parallelCraftingCpuLaneShardCount",
                        4_096, 1, Integer.MAX_VALUE);
        builder.pop();

        builder.comment("AE Direct Processing Machine").push("aeDirectProcessingMachine");
        AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS = builder
                .comment(
                        "Local direct processing machine recipe mappings.",
                        "Format: machine_id=recipe_type_id=default_ticks",
                        "Example: mymod:crusher=mymod:crushing=40",
                        "Only affects the AE direct processing machine discovery layer."
                )
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
                .define("budgetProfile", "normal");
        AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED = builder
                .comment(
                        "Enable generic recipe type discovery for the AE direct processing machine.",
                        "When disabled, only explicit adapters and config/datapack mappings are used."
                )
                .define("genericDiscoveryEnabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }
}
