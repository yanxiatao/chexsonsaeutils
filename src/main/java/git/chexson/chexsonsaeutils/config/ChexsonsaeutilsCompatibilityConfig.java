package git.chexson.chexsonsaeutils.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChexsonsaeutilsCompatibilityConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CRAFTING_CONTINUATION_ENABLED;
    public static final ModConfigSpec.BooleanValue PROCESSING_PATTERN_REPLACEMENT_ENABLED;
    public static final ModConfigSpec.IntValue MAX_CUSTOM_PATTERN_PAGES;
    public static final ModConfigSpec.BooleanValue DYEABLE_PATTERNS_ENABLED;
    public static final ModConfigSpec.IntValue RECURSIVE_RING_MAX_MEMBERS;
    public static final ModConfigSpec.BooleanValue ENHANCED_CRAFTING_STATUS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> CRAFTING_AMOUNT_NUMBER_STYLE;
    public static final ModConfigSpec.BooleanValue BUILDING_GADGETS2_INTEGRATION_ENABLED;
    public static final ModConfigSpec.BooleanValue FTB_ULTIMINE_MEMORY_CARD_ENABLED;
    public static final ModConfigSpec.BooleanValue PARALLEL_CRAFTING_CPU_ENABLED;
    public static final ModConfigSpec.BooleanValue GRAPH_PLANNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> GRAPH_PLANNING_BUDGET_MILLIS;
    public static final ModConfigSpec.BooleanValue GRAPH_PLANNING_LEXICOGRAPHIC;
    public static final ModConfigSpec.BooleanValue GRAPH_PLANNING_TRACE;
    public static final ModConfigSpec.BooleanValue PARALLEL_CPU_DIAGNOSTICS;
    public static final ModConfigSpec.BooleanValue GRAPH_PLAN_CAPTURE;
    public static final ModConfigSpec.BooleanValue GRAPH_PLANNING_CLOSURE_GATE;
    public static final ModConfigSpec.ConfigValue<Integer> GRAPH_PLANNING_MAX_DEPTH;
    public static final ModConfigSpec.BooleanValue WIDE_AMOUNT_SOLVER_ENABLED;
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
    public static final ModConfigSpec.BooleanValue EXTREME_ASSEMBLER_ENABLED;
    public static final ModConfigSpec.BooleanValue AE_DIRECT_PROCESSING_MACHINE_ENABLED;
    public static final ModConfigSpec.BooleanValue CUSTOM_PATTERN_PROVIDER_ENABLED;
    public static final ModConfigSpec.BooleanValue CUSTOM_PATTERN_PROVIDER_OVERSTACK_RETURN_ENABLED;
    public static final ModConfigSpec.BooleanValue MATTER_MASS_PATTERN_PROVIDER_ENABLED;
    public static final ModConfigSpec.BooleanValue MULTI_LEVEL_EMITTER_ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_ITEM_GEN_ENABLED;
    public static final ModConfigSpec.BooleanValue INFINITY_CELL_ENABLED;
    public static final ModConfigSpec.BooleanValue SLOT_NUMBER_OVERLAY_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_TOTAL_PATTERN_SLOTS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_QUEUE_TASKS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_BATCHES;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_MAX_PENDING_OUTPUT_RETRY_DELAY_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> AE_DIRECT_PROCESSING_MACHINE_DIRTY_PATTERN_REFRESH_BUDGET_PER_TICK;
    public static final ModConfigSpec.ConfigValue<Integer> EXTREME_ASSEMBLER_TOTAL_PATTERN_SLOTS;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_BASE_TICKS;
    public static final ModConfigSpec.ConfigValue<Integer> PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_MAX_TICKS;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK_PER_GRID;
    public static final ModConfigSpec.ConfigValue<Long> PARALLEL_CRAFTING_CPU_MAX_REINJECT_PATTERN_INPUTS_PER_TICK_PER_GRID;

    // ---- 声明键与启动期回落读取共用的键名常量：键名字符串只在声明处出现一次 ----
    // 治"改 accessor 名不报错、只静默关能力"：.define 的声明键与 featureEnabled/readInt 回落读取
    // 引同一常量，任一处漂移编译即红，不会再让 TOML 静默读不到键。
    // 覆盖"声明与回落读取同在本文件内"的 6 把键（private 足够）。
    private static final String WIDE_AMOUNT_SOLVER_ENABLED_KEY = "wideAmountSolverEnabled";
    private static final String GRAPH_PLANNING_CLOSURE_GATE_KEY = "graphPlanningClosureGateEnabled";
    private static final String GRAPH_PLANNING_TRACE_KEY = "graphPlanningTrace";
    private static final String PARALLEL_CPU_DIAGNOSTICS_KEY = "parallelCraftingCpuDiagnostics";
    private static final String GRAPH_PLAN_CAPTURE_KEY = "graphPlanCapture";
    private static final String GRAPH_PLANNING_MAX_DEPTH_KEY = "graphPlanningMaxDepth";
    private static final String RECURSIVE_RING_MAX_MEMBERS_KEY = "recursiveRingMaxMembers";

    public static final String PROCESSING_PATTERN_REPLACEMENT_ENABLED_KEY = "processingPatternReplacementEnabled";
    public static final String MATTER_MASS_PATTERN_PROVIDER_ENABLED_KEY = "matterMassPatternProviderEnabled";

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
        PROCESSING_PATTERN_REPLACEMENT_ENABLED = builder
                .comment("Enable AE2 processing pattern replacement feature bundle.",
                        "Allows dynamic pattern substitution based on available materials.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.processingPatternReplacementEnabled")
                .define(PROCESSING_PATTERN_REPLACEMENT_ENABLED_KEY, true);
        MAX_CUSTOM_PATTERN_PAGES = builder
                .comment("Maximum pattern pages of the custom pattern provider.",
                        "Each page holds 36 pattern slots; the pattern inventory capacity is pages * 36.",
                        "Extending pages is unlocked by consuming items (phase 5b), and expanded pages are",
                        "kept when the provider is dismantled. Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.maxCustomPatternPages")
                .defineInRange("maxCustomPatternPages", 8, 1, 8);
        EXTREME_ASSEMBLER_ENABLED = builder
                .comment("Enable the Extreme Assembler behavior.",
                        "Registered blocks/items stay available for world compatibility;",
                        "when disabled, placed machines stop processing. Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.extremeAssemblerEnabled")
                .define("extremeAssemblerEnabled", true);
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
        CUSTOM_PATTERN_PROVIDER_OVERSTACK_RETURN_ENABLED = builder
                .comment("Allow one return-bar slot of the custom pattern provider to exceed the item",
                        "stack size limit, so a full machine output slot can be pulled in one pass.",
                        "When disabled, new writes are clamped to the original per-slot limit; content",
                        "already stored above the limit is still returned to the network in full.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.customPatternProviderOverstackReturnEnabled")
                .define("customPatternProviderOverstackReturnEnabled", true);
        MATTER_MASS_PATTERN_PROVIDER_ENABLED = builder
                .comment("Enable the matter mass pattern provider feature bundle.",
                        "Registered blocks/items stay available for world compatibility;",
                        "when disabled, machines stop converting patterns and delivering matter masses,",
                        "and the pattern decoder / cell handler / mixins are not loaded.",
                        "Takes effect after restart.")
                .translation("configuration.chexsonsaeutils.matterMassPatternProviderEnabled")
                .define(MATTER_MASS_PATTERN_PROVIDER_ENABLED_KEY, true);
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
        RECURSIVE_RING_MAX_MEMBERS = builder
                .comment(
                        "Maximum number of patterns in a recursive ring after directed",
                        "closure. Larger closures are treated as non-calculable and fall back to",
                        "per-item planning. Takes effect immediately."
                )
                .translation("configuration.chexsonsaeutils.recursiveRingMaxMembers")
                .defineInRange(RECURSIVE_RING_MAX_MEMBERS_KEY, 64, 1, Integer.MAX_VALUE);
        ENHANCED_CRAFTING_STATUS_ENABLED = builder
                .comment(
                        "Enable AEA enhanced crafting status migration.",
                        "Adds Blocked and Pattern Times status data without replacing existing",
                        "continuation, formal-machine, or parallel CPU UI state.",
                        "Takes effect after restart."
                )
                .translation("configuration.chexsonsaeutils.enhancedCraftingStatusEnabled")
                .define("enhancedCraftingStatusEnabled", true);
        CRAFTING_AMOUNT_NUMBER_STYLE = builder
                .comment(
                        "Number style for the short amounts shown in the crafting confirm table.",
                        "Allowed values: abbreviated (1.5K, 16Qi, 2De), scientific (1.50e3, 1.62e19).",
                        "Only affects the in-cell column; tooltips always keep the full unabbreviated amount.",
                        "Takes effect immediately."
                )
                .translation("configuration.chexsonsaeutils.craftingAmountNumberStyle")
                .define("craftingAmountNumberStyle", "abbreviated");
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
        GRAPH_PLANNING_ENABLED = builder
                .comment("Enable the level-1 graph crafting-planning engine.",
                        "The planning neighbourhood is compiled once into an immutable graph and solved",
                        "linearly, reusing the graph across every probe. Anything the graph engine",
                        "cannot express yet (multiple routes, fuzzy variants, recursive patterns)",
                        "falls back to the native AE2 path.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanningEnabled")
                .define("graphPlanningEnabled", true);
        GRAPH_PLANNING_BUDGET_MILLIS = builder
                .comment("Wall-clock budget in milliseconds for one graph planning session, shared by",
                        "graph compilation and every solve probe. On exhaustion the calculation falls",
                        "back to the tree path. 0 disables the budget (run to completion).")
                .translation("configuration.chexsonsaeutils.graphPlanningBudgetMillis")
                .defineInRange("graphPlanningBudgetMillis", 10_000, 0, Integer.MAX_VALUE);
        GRAPH_PLANNING_LEXICOGRAPHIC = builder
                .comment("Experimental: at multi-route competition points the graph planner fires",
                        "every candidate route on a ledger copy and keeps the lexicographically",
                        "best plan (fewest leftovers, then fewest network items, then fewest",
                        "crafts, then fewest bytes) instead of first-come-first-served. Changes",
                        "route selection results and costs one extra ledger pass per candidate.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanningLexicographic")
                .define("graphPlanningLexicographic", false);
        GRAPH_PLANNING_CLOSURE_GATE = builder
                .comment("Post-solve material conservation gate for the level-1 graph planner:",
                        "before a solve result is accepted, every key is checked for",
                        "supply (network extractions, emissions, ring seeds and pattern outputs)",
                        ">= demand (pattern inputs, final output and missing items). A violation",
                        "declines the graph plan and falls back to the tree path, so a symbol",
                        "error in a closed-form shortcut can never ship an impossible plan.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanningClosureGateEnabled")
                .define(GRAPH_PLANNING_CLOSURE_GATE_KEY, true);
        GRAPH_PLANNING_TRACE = builder
                .comment("Add per-site / per-key lines to the graph planning trace.",
                        "These scale with the site tree, so a large",
                        "modpack request produces tens of megabytes of text and the formatting",
                        "alone costs about a second — only turn this on to debug one request.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanningTrace")
                .define(GRAPH_PLANNING_TRACE_KEY, false);
        GRAPH_PLAN_CAPTURE = builder
                .comment("Write a replayable planning fixture to logs/chexsonsaeutils-plan-capture-*.json",
                        "for every request that takes the graph path. The file records the crafting",
                        "service view (patterns, emittable keys, fuzzy craftables) plus the stock",
                        "snapshot, so the mod's own benchmark can replay the exact request offline.",
                        "Debug aid only — leave it off during normal play.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanCapture")
                .define(GRAPH_PLAN_CAPTURE_KEY, false);
        PARALLEL_CPU_DIAGNOSTICS = builder
                .comment("输出并行 CPU 的批处理、停滞、提交、恢复与正常 BigInteger 回执诊断。",
                        "默认关闭，立即生效；真实提交失败和异常回执仍记录警告。")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuDiagnostics")
                .define(PARALLEL_CPU_DIAGNOSTICS_KEY, false);
        GRAPH_PLANNING_MAX_DEPTH = builder
                .comment("Maximum planning recursion depth for the level-1 graph solver. Very deep",
                        "dependency chains beyond this depth decline and fall back to the tree path.",
                        "Ring compression keeps real-world graphs far below the default.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.graphPlanningMaxDepth")
                .defineInRange(GRAPH_PLANNING_MAX_DEPTH_KEY, 2048, 16, 1_048_576);
        WIDE_AMOUNT_SOLVER_ENABLED = builder
                .comment("Use exact long/BigInteger arithmetic in the graph planning solver instead of",
                        "the double-based ring scaling chain and the 2^52 request cap. Amounts beyond",
                        "long are tracked with BigInteger ledgers and clamped at the AE2 boundary.",
                        "Disabling restores the legacy cap and double chain as a safety fuse.",
                        "Takes effect immediately.")
                .translation("configuration.chexsonsaeutils.wideAmountSolverEnabled")
                .define(WIDE_AMOUNT_SOLVER_ENABLED_KEY, true);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK = builder
                .comment("Maximum internal crafting lanes per tool block.",
                        "0 = unlimited (each lane holds one executing job's inventory;",
                        "unbounded submission may grow memory under automation).")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxInternalLanesPerBlock")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerBlock",
                        65_536, 0, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID = builder
                .comment("Maximum internal crafting lanes per AE grid.",
                        "0 = unlimited.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuMaxInternalLanesPerGrid")
                .defineInRange("parallelCraftingCpuMaxInternalLanesPerGrid",
                        1_048_576, 0, Integer.MAX_VALUE);
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
                        "Clamped to 45 ms. This is the MSPT guard: crafting scheduling runs on",
                        "the server thread, and 20 ms is 40% of a 50 ms tick - raising it toward",
                        "the 45 ms clamp directly eats the tick headroom other server work needs.",
                        "Throughput problems are almost always provider accept rate or backoff,",
                        "not this budget.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuTickBudgetNanosPerGrid")
                .defineInRange("parallelCraftingCpuTickBudgetNanosPerGrid",
                        20_000_000L, 1L, 45_000_000L);
        PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT = builder
                .comment("Shard count used by the extreme-lane scheduler.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuLaneShardCount")
                .defineInRange("parallelCraftingCpuLaneShardCount",
                        4_096, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_BASE_TICKS = builder
                .comment("Base exponential backoff delay in ticks for busy pattern providers.",
                        "Lower values retry sooner; the polling cost is one isBusy() check,",
                        "which does not touch the tick time budget.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuProviderBackoffBaseTicks")
                .defineInRange("parallelCraftingCpuProviderBackoffBaseTicks",
                        1, 1, Integer.MAX_VALUE);
        PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_MAX_TICKS = builder
                .comment("Maximum backoff delay in ticks for busy pattern providers.",
                        "The old default of 40 ticks let a single rejection stall retries for",
                        "two whole seconds; with few providers that starves execution.",
                        "10 ticks caps the stall at half a second while still avoiding",
                        "busy-polling hot loops.")
                .translation("configuration.chexsonsaeutils.parallelCraftingCpuProviderBackoffMaxTicks")
                .defineInRange("parallelCraftingCpuProviderBackoffMaxTicks",
                        10, 1, Integer.MAX_VALUE);
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

        builder.comment("Extreme Assembler")
                .translation("configuration.chexsonsaeutils.section.extremeAssembler")
                .push("extremeAssembler");
        EXTREME_ASSEMBLER_TOTAL_PATTERN_SLOTS = builder
                .comment("Total pattern slot capacity of Extreme Assembler hosts.",
                        "Shrinking this below a host's used slots may affect existing saves.")
                .translation("configuration.chexsonsaeutils.totalPatternSlots")
                .defineInRange("totalPatternSlots", 16_384, 1, 65_536);
        builder.pop();
        SPEC = builder.build();
    }

    private ChexsonsaeutilsCompatibilityConfig() {
    }

    /**
     * @return 配置允许的最大样板页数（1-8，默认 8）。
     */
    public static int maxCustomPatternPages() {
        return MAX_CUSTOM_PATTERN_PAGES.get();
    }

    /**
     * @return 定制样板供应器产物返回栏是否允许单格超过物品堆叠上限。
     *         经 {@link #boolValue} 读取，配置未加载时回落到默认值（开启）。
     */
    public static boolean customPatternProviderOverstackReturnEnabled() {
        return boolValue(CUSTOM_PATTERN_PROVIDER_OVERSTACK_RETURN_ENABLED);
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

    public static String stringValue(ModConfigSpec.ConfigValue<String> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    // 确认表紧凑列计数方式的原始配置串（abbreviated 默认 / scientific）：config 只交出这个串，
    // 把它解析成计数方式（含"认不出回缩写"的极性）由能力侧负责，config 不再引能力包类型。
    public static String craftingAmountNumberStyleValue() {
        return stringValue(CRAFTING_AMOUNT_NUMBER_STYLE);
    }

    /**
     * 功能开关的双轨读取：SPEC 已加载读 SPEC；否则（Mixin 插件等启动早期阶段）
     * 直接解析启动期 TOML 回落。
     */
    public static boolean featureEnabled(ModConfigSpec.BooleanValue spec, String key) {
        if (SPEC.isLoaded()) {
            return spec.get();
        }
        return StartupConfigReader.readBoolean(key, spec.getDefault());
    }

    /** 宽域（long/BigInteger）求解开关，默认开；关掉恢复 2^52 帽与 double 环 scale 链。 */
    public static boolean wideAmountSolverEnabled() {
        return featureEnabled(WIDE_AMOUNT_SOLVER_ENABLED, WIDE_AMOUNT_SOLVER_ENABLED_KEY);
    }

    /** 图求解后置守恒闸门开关（Phase 2d B4），默认开；关掉即回退无闸门行为。 */
    public static boolean graphPlanningClosureGateEnabled() {
        return featureEnabled(GRAPH_PLANNING_CLOSURE_GATE, GRAPH_PLANNING_CLOSURE_GATE_KEY);
    }

    /** 图求解递归深度上限（Phase 2d K6 退路），默认 2048。 */
    public static boolean graphPlanningTraceEnabled() {
        return featureEnabled(GRAPH_PLANNING_TRACE, GRAPH_PLANNING_TRACE_KEY);
    }

    public static boolean graphPlanCaptureEnabled() {
        return featureEnabled(GRAPH_PLAN_CAPTURE, GRAPH_PLAN_CAPTURE_KEY);
    }

    /** 执行期诊断统一开关；关闭时跳过周期快照与正常回执日志，保留真实错误。 */
    public static boolean parallelCpuDiagnosticsEnabled() {
        return featureEnabled(PARALLEL_CPU_DIAGNOSTICS, PARALLEL_CPU_DIAGNOSTICS_KEY);
    }

    public static int graphPlanningMaxDepth() {
        if (SPEC.isLoaded()) {
            return GRAPH_PLANNING_MAX_DEPTH.get();
        }
        return StartupConfigReader.readInt(
                GRAPH_PLANNING_MAX_DEPTH_KEY, GRAPH_PLANNING_MAX_DEPTH.getDefault());
    }

    /** 递归环成员规模上限，超限环按不可算处理并回落逐件规划。 */
    public static int recursiveRingMaxMembers() {
        if (SPEC.isLoaded()) {
            return RECURSIVE_RING_MAX_MEMBERS.get();
        }
        return StartupConfigReader.readInt(RECURSIVE_RING_MAX_MEMBERS_KEY, RECURSIVE_RING_MAX_MEMBERS.getDefault());
    }

    public static ParallelCpuSettings parallelCpuSettings() {
        return readParallelCpuSettings(false);
    }

    public static ParallelCpuSettings loadParallelCpuSettingsWithStartupWarnings() {
        ParallelCpuSettings settings = readParallelCpuSettings(true);
        LOGGER.info(
                "AE2 parallel CPU tool config active: enabled={}, lanesPerBlock={}, lanesPerGrid={}, "
                        + "submissionsPerTick={}, pushesPerTick={}, providerChecksPerTick={}, tickBudgetNanos={}, "
                        + "laneShards={}, extractInputsPerTick={}, reinjectInputsPerTick={}",
                settings.enabled(),
                settings.maxInternalLanesPerBlock(),
                settings.maxInternalLanesPerGrid(),
                settings.maxSubmissionsPerTickPerGrid(),
                settings.maxPatternPushesPerTickPerGrid(),
                settings.maxProviderChecksPerTickPerGrid(),
                settings.tickBudgetNanosPerGrid(),
                settings.laneShardCount(),
                settings.maxExtractPatternInputsPerTickPerGrid(),
                settings.maxReinjectPatternInputsPerTickPerGrid()
        );
        return settings;
    }

    private static ParallelCpuSettings readParallelCpuSettings(boolean warn) {
        return new ParallelCpuSettings(
                boolValue(PARALLEL_CRAFTING_CPU_ENABLED),
                clampInt("parallelCraftingCpuMaxInternalLanesPerBlock",
                        intValue(PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_BLOCK),
                        0, Integer.MAX_VALUE, warn),
                clampInt("parallelCraftingCpuMaxInternalLanesPerGrid",
                        intValue(PARALLEL_CRAFTING_CPU_MAX_INTERNAL_LANES_PER_GRID),
                        0, Integer.MAX_VALUE, warn),
                clampInt("parallelCraftingCpuMaxSubmissionsPerTickPerGrid",
                        intValue(PARALLEL_CRAFTING_CPU_MAX_SUBMISSIONS_PER_TICK_PER_GRID),
                        1, Integer.MAX_VALUE, warn),
                clampLong("parallelCraftingCpuMaxPatternPushesPerTickPerGrid",
                        longValue(PARALLEL_CRAFTING_CPU_MAX_PATTERN_PUSHES_PER_TICK_PER_GRID),
                        1L, Long.MAX_VALUE / 4L, warn),
                clampLong("parallelCraftingCpuMaxProviderChecksPerTickPerGrid",
                        longValue(PARALLEL_CRAFTING_CPU_MAX_PROVIDER_CHECKS_PER_TICK_PER_GRID),
                        1L, Long.MAX_VALUE / 4L, warn),
                clampLong("parallelCraftingCpuTickBudgetNanosPerGrid",
                        longValue(PARALLEL_CRAFTING_CPU_TICK_BUDGET_NANOS_PER_GRID),
                        1L, 45_000_000L, warn),
                clampInt("parallelCraftingCpuLaneShardCount",
                        intValue(PARALLEL_CRAFTING_CPU_LANE_SHARD_COUNT),
                        1, Integer.MAX_VALUE, warn),
                clampLong("parallelCraftingCpuMaxExtractPatternInputsPerTickPerGrid",
                        longValue(PARALLEL_CRAFTING_CPU_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK_PER_GRID),
                        1L, Long.MAX_VALUE / 4L, warn),
                clampLong("parallelCraftingCpuMaxReinjectPatternInputsPerTickPerGrid",
                        longValue(PARALLEL_CRAFTING_CPU_MAX_REINJECT_PATTERN_INPUTS_PER_TICK_PER_GRID),
                        1L, Long.MAX_VALUE / 4L, warn)
        );
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

    public record ParallelCpuSettings(
            boolean enabled,
            int maxInternalLanesPerBlock,
            int maxInternalLanesPerGrid,
            int maxSubmissionsPerTickPerGrid,
            long maxPatternPushesPerTickPerGrid,
            long maxProviderChecksPerTickPerGrid,
            long tickBudgetNanosPerGrid,
            int laneShardCount,
            long maxExtractPatternInputsPerTickPerGrid,
            long maxReinjectPatternInputsPerTickPerGrid
    ) {
    }

    /**
     * 启动期直接解析 TOML 的底层读取器：SPEC 尚未加载（Mixin 插件阶段等）
     * 时的双轨回落实现，仅本类内部使用。
     */
    private static final class StartupConfigReader {

        private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
        private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";

        private StartupConfigReader() {
        }

        static boolean readBoolean(String key, boolean defaultValue) {
            return read(resolveCommonConfigPath(), key, "true|false", defaultValue, Boolean::parseBoolean);
        }

        static int readInt(String key, int defaultValue) {
            return read(resolveCommonConfigPath(), key, "-?\\d+", defaultValue, Integer::parseInt);
        }

        private static <T> T read(Path configFile, String key, String valuePattern, T defaultValue,
                Function<String, T> parser) {
            T persistedValue = readPersistedValue(configFile, key, valuePattern, parser);
            return persistedValue != null ? persistedValue : defaultValue;
        }

        private static Path resolveCommonConfigPath() {
            String overriddenConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
            if (overriddenConfigDir != null && !overriddenConfigDir.isBlank()) {
                return Path.of(overriddenConfigDir).resolve(COMMON_CONFIG_FILE);
            }
            return FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE);
        }

        private static <T> T readPersistedValue(Path configFile, String key, String valuePattern,
                Function<String, T> parser) {
            if (configFile == null || !Files.isRegularFile(configFile)) {
                return null;
            }

            Pattern pattern = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(" + valuePattern + ")\\s*(?:#.*)?$");
            try {
                String configContent = Files.readString(configFile);
                Matcher matcher = pattern.matcher(configContent);
                if (!matcher.find()) {
                    return null;
                }
                return parser.apply(matcher.group(1));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read startup config key {} from {}", key, configFile, exception);
                return null;
            }
        }
    }
}
