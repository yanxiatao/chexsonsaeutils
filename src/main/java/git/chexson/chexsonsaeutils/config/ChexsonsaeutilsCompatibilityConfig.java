package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ChexsonsaeutilsCompatibilityConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FORMAL_MACHINE_CRAFTING_DISPATCH_ENABLED;
    public static final ModConfigSpec.BooleanValue FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED;
    public static final ModConfigSpec.BooleanValue PROCESSING_PATTERN_REPLACEMENT_ENABLED;
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
    public static final ModConfigSpec.BooleanValue AE_DIRECT_PROCESSING_MACHINE_REFLECTIVE_DISCOVERY_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CRAFTING_CONTINUATION_ENABLED = builder
                .comment("Disable the AE2 crafting continuation / ignore-missing feature bundle. Takes effect after restart.")
                .define("craftingContinuationEnabled", true);
        FORMAL_MACHINE_CRAFTING_DISPATCH_ENABLED = builder
                .comment("Disable the formal machine AE2 crafting dispatch fast path. Takes effect after restart.")
                .define("formalMachineCraftingDispatchEnabled", true);
        FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED = builder
                .comment("Disable the formal machine large-request planning aggregation fast path. Takes effect after restart.")
                .define("formalMachinePlanningAggregationEnabled", true);
        PROCESSING_PATTERN_REPLACEMENT_ENABLED = builder
                .comment("Disable the AE2 processing pattern replacement feature bundle. Takes effect after restart.")
                .define("processingPatternReplacementEnabled", true);
        builder.push("parallelCraftingCpuTool");
        PARALLEL_CRAFTING_CPU_ENABLED = builder
                .comment("Enable the official extreme-parallel AE2 CPU tool block. Takes effect after restart.")
                .define("parallelCraftingCpuEnabled", true);
        PARALLEL_CRAFTING_CPU_CO_PROCESSORS_PER_VIRTUAL_CPU = builder
                .comment("Advertised co-processors per virtual CPU. Clamped below Integer.MAX_VALUE to avoid overflow.")
                .define("parallelCraftingCpuCoProcessorsPerVirtualCpu", 2_147_483_646);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK = builder
                .comment("Maximum internal crafting lanes per tool block.")
                .define("parallelCraftingCpuMaxInternalLanesPerBlock", 65_536);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID = builder
                .comment("Maximum internal crafting lanes per AE grid.")
                .define("parallelCraftingCpuMaxInternalLanesPerGrid", 1_048_576);
        PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID = builder
                .comment("Maximum parallel CPU job submissions accepted per grid tick.")
                .define("parallelCraftingCpuMaxSubmissionsPerTickPerGrid", 4_096);
        PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID = builder
                .comment("Maximum pattern pushes budgeted per grid tick.")
                .define("parallelCraftingCpuMaxPatternPushesPerTickPerGrid", 1_048_576L);
        PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID = builder
                .comment("Maximum provider checks budgeted per grid tick.")
                .define("parallelCraftingCpuMaxProviderChecksPerTickPerGrid", 8_388_608L);
        PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID = builder
                .comment("Maximum wall-clock CPU scheduling budget per grid tick. Clamped to 45 ms.")
                .define("parallelCraftingCpuTickBudgetNanosPerGrid", 20_000_000L);
        PARALLEL_CRAFTING_CPU_STORAGE_BYTES = builder
                .comment("Advertised crafting storage bytes for the tool.")
                .define("parallelCraftingCpuStorageBytes", Long.MAX_VALUE / 4L);
        PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT = builder
                .comment("Shard count used by the extreme-lane scheduler.")
                .define("parallelCraftingCpuLaneShardCount", 4_096);
        builder.pop();
        builder.push("aeDirectProcessingMachine");
        AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS = builder
                .comment(
                        "Local direct processing machine recipe mappings. Format: machine_id=recipe_type_id=default_ticks.",
                        "Example: mymod:crusher=mymod:crushing=40.",
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
                        "Allowed values: normal, high, benchmark. Benchmark is intended for controlled performance tests."
                )
                .define("budgetProfile", "normal");
        AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED = builder
                .comment(
                        "Enable generic recipe type discovery for the AE direct processing machine.",
                        "When disabled, only explicit adapters and config/datapack mappings are used."
                )
                .define("genericDiscoveryEnabled", true);
        AE_DIRECT_PROCESSING_MACHINE_REFLECTIVE_DISCOVERY_ENABLED = builder
                .comment(
                        "Enable read-only reflective recipe shape discovery for the AE direct processing machine.",
                        "Reflection only runs during local discovery/index rebuilds, never in tick or pushPattern hot paths."
                )
                .define("reflectiveDiscoveryEnabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }
}
