package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostChainSegmenter.PatternDefinitionKey;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * crafts 层替换的规划上下文（Phase 4）：一次 {@code buildCraftingPlan} 内
 * 的记忆化载体——definition 解包、独占宿主、净输入描述、共现黑名单全部
 * 惰性缓存。键即样板对象（AE2 样板的 equals/hashCode 基于 definition，
 * 与原重写链一致）。
 *
 * <p>CRAFT_LESS 的每轮 probe 新建子沙盒（crafts 重建）但共用同一
 * calculation，因此递归路径的契约账本（催化物保留、选定输入）从
 * calculation 的 duck 接口注入，而非从 plan 提取——plan 在此时尚未构建。
 */
final class HostBatchPlanningContext {

    private final CraftingService craftingService;
    @Nullable
    private final GenericStack finalOutput;
    /** 递归路径的催化物保留账本（duck 注入）；非递归为 null。 */
    @Nullable
    private final KeyCounter recursiveInitialItems;
    /** 递归路径的选定输入映射（duck 注入）；非递归为 null。 */
    @Nullable
    private final Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs;
    /**
     * 规划时库存快照（图路径传入，原生路径 null）：现料先跑拆批用——
     * 按网络料可跑步数把段拆成"现料批 + 等料批"，不等上游供足。
     */
    @Nullable
    private final Map<AEKey, Long> networkStock;
    /** 全图产出键集（图路径传入）：边界键在其中即依赖上游产出，需等待。 */
    @Nullable
    private final java.util.Set<AEKey> upstreamProducedKeys;

    private final Map<IPatternDetails, @Nullable PatternDefinitionKey> definitionKeys = new HashMap<>();
    private final Map<IPatternDetails, @Nullable IPatternDetails> unwrappedPatterns = new HashMap<>();
    private final Map<IPatternDetails, @Nullable AECraftingPattern> basePatterns = new HashMap<>();
    private final Map<IPatternDetails, @Nullable HostBatchProvider> exclusiveHosts = new HashMap<>();
    private final Map<IPatternDetails, @Nullable Map<AEKey, Long>> aggregationInputs = new HashMap<>();

    @Nullable
    private final HostBatchPlanningCache planningCache;

    private HostBatchPlanningContext(
            CraftingService craftingService,
            @Nullable GenericStack finalOutput,
            @Nullable KeyCounter recursiveInitialItems,
            @Nullable Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            @Nullable HostBatchPlanningCache planningCache,
            @Nullable Map<AEKey, Long> networkStock,
            @Nullable java.util.Set<AEKey> upstreamProducedKeys
    ) {
        this.craftingService = craftingService;
        this.finalOutput = finalOutput;
        this.recursiveInitialItems = recursiveInitialItems;
        this.selectedPatternInputs = selectedPatternInputs;
        this.planningCache = planningCache;
        this.networkStock = networkStock;
        this.upstreamProducedKeys = upstreamProducedKeys;
    }

    /**
     * crafts 替换入口的上下文工厂：递归契约账本从 calculation 的 duck
     * 接口提取（{@code RecursiveCraftingPlan} 在 buildCraftingPlan 时尚未存在）。
     */
    static HostBatchPlanningContext create(
            CraftingService craftingService,
            @Nullable HostBatchArmed armed,
            @Nullable GenericStack finalOutput
    ) {
        return new HostBatchPlanningContext(
                craftingService,
                finalOutput,
                armed == null ? null : armed.chexsonsaeutils$dyeableRecursiveInitialItems(),
                armed == null ? null : armed.chexsonsaeutils$dyeableSelectedPatternInputs(),
                armed == null ? null : armed.chexsonsaeutils$hostBatchPlanningCache(),
                null,
                null
        );
    }

    /** 图路径工厂：附带库存快照与上游产出键集，支持现料先跑拆批。 */
    static HostBatchPlanningContext createWithStock(
            CraftingService craftingService,
            @Nullable HostBatchArmed armed,
            @Nullable GenericStack finalOutput,
            @Nullable Map<AEKey, Long> networkStock,
            @Nullable java.util.Set<AEKey> upstreamProducedKeys
    ) {
        return new HostBatchPlanningContext(
                craftingService,
                finalOutput,
                armed == null ? null : armed.chexsonsaeutils$dyeableRecursiveInitialItems(),
                armed == null ? null : armed.chexsonsaeutils$dyeableSelectedPatternInputs(),
                armed == null ? null : armed.chexsonsaeutils$hostBatchPlanningCache(),
                networkStock,
                upstreamProducedKeys
        );
    }

    @Nullable
    Map<AEKey, Long> networkStock() {
        return networkStock;
    }

    @Nullable
    java.util.Set<AEKey> upstreamProducedKeys() {
        return upstreamProducedKeys;
    }

    CraftingService craftingService() {
        return craftingService;
    }

    @Nullable
    GenericStack finalOutput() {
        return finalOutput;
    }

    @Nullable
    KeyCounter recursiveInitialItems() {
        return recursiveInitialItems;
    }

    @Nullable
    Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs() {
        return selectedPatternInputs;
    }

    @Nullable
    PatternDefinitionKey patternDefinitionKey(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        if (definitionKeys.containsKey(pattern)) {
            return definitionKeys.get(pattern);
        }
        PatternDefinitionKey definitionKey = PatternDefinitionKey.of(pattern);
        definitionKeys.put(pattern, definitionKey);
        return definitionKey;
    }

    @Nullable
    IPatternDetails unwrapBasePattern(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        if (unwrappedPatterns.containsKey(pattern)) {
            return unwrappedPatterns.get(pattern);
        }
        IPatternDetails unwrapped = HostChainSegmenter.unwrapBasePattern(pattern);
        unwrappedPatterns.put(pattern, unwrapped);
        return unwrapped;
    }

    @Nullable
    AECraftingPattern unwrapBaseCraftingPattern(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        if (basePatterns.containsKey(pattern)) {
            return basePatterns.get(pattern);
        }
        AECraftingPattern basePattern = HostChainSegmenter.unwrapBaseCraftingPattern(pattern);
        basePatterns.put(pattern, basePattern);
        return basePattern;
    }

    @Nullable
    HostBatchProvider exclusiveHostBatchProvider(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        if (exclusiveHosts.containsKey(pattern)) {
            return exclusiveHosts.get(pattern);
        }
        HostBatchProvider host = HostBatchPlanner.exclusiveHostBatchProvider(craftingService, pattern);
        exclusiveHosts.put(pattern, host);
        return host;
    }

    @Nullable
    Map<AEKey, Long> describeAggregationInputs(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        if (aggregationInputs.containsKey(pattern)) {
            return aggregationInputs.get(pattern);
        }
        Map<AEKey, Long> inputs = HostChainSegmenter.describeAggregationInputs(
                selectedPatternInputs, pattern);
        aggregationInputs.put(pattern, inputs);
        return inputs;
    }

    @Nullable
    HostBatchPlanningCache planningCache() {
        return planningCache;
    }
}
