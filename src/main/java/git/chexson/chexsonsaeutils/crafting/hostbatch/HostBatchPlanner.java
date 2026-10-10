package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.me.service.CraftingService;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingSimulationStateHostBatchAccessor;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿主批次聚合（Phase 4 crafts 层替换）：在
 * {@code CraftingSimulationState.buildCraftingPlan} 的 HEAD 把 {@code crafts}
 * 里的宿主链样板替换为 {@code HostBatchPattern}（full + remainder 双实例
 * 批次化），随后由 AE2 原生逻辑构建计划——bytes/usedItems/missing 全部取
 * AE2 原值，不再"事后重写整份 plan"重算账目（574 字节失真类问题根除）。
 *
 * <p>链的净外部输入与原树完全相同（链内抵消），因此 AE2 的
 * {@code requiredExtract} 天然正确，无需手术。
 */
public final class HostBatchPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private HostBatchPlanner() {
    }

    /**
     * HEAD 注入入口：全部守门（配置/armed/grid/幂等/宿主早退）通过后执行
     * 链分段 + 批次化 + 写回。任何异常只记日志并放弃替换——原生计划不受影响。
     */
    /**
     * 图路径入口：直接对聚合求解器产出的 crafts map 做宿主批重写。
     *
     * <p>与原生路径的 {@code rewriteCrafts(state, calculation, amount)} 相比，
     * 不需要 {@code CraftingSimulationState}（图路径用 {@code GraphPlanningState}
     * 存 crafts）也不需要 {@code CraftingCalculation}（图路径的
     * {@code GraphPlanningSession} 直接持有 output 与 level）。
     * armed 参数供 {@code HostBatchPlanningContext.create} 使用，可传 null
     * （非递归路径的 dyeable 字段返回 null 即可）。
     *
     * @return 归因摘要（守门/分段/写回结果），调用方并入计时行；异常返回原因。
     */
    public static String rewriteCraftsForGraph(
            Map<IPatternDetails, Long> crafts,
            @Nullable IGrid grid,
            @Nullable CraftingService craftingService,
            @Nullable Level level,
            AEKey output,
            long amount,
            @Nullable HostBatchArmed armed,
            @Nullable Map<AEKey, Long> networkStock
    ) {
        try {
            return rewriteCraftsMap(crafts, grid, craftingService, level, output, amount, armed, networkStock);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to rewrite graph crafts to host batches for output {}", output, exception);
            return "error:" + exception.getClass().getSimpleName();
        }
    }

    private static String rewriteCraftsMap(
            Map<IPatternDetails, Long> crafts,
            @Nullable IGrid grid,
            @Nullable CraftingService craftingService,
            @Nullable Level level,
            AEKey output,
            long amount,
            @Nullable HostBatchArmed armed,
            @Nullable Map<AEKey, Long> networkStock
    ) {
        // 1) 配置总开关
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED,
                "formalMachinePlanningAggregationEnabled")) {
            return "gate:configOff";
        }
        if (grid == null || craftingService == null || level == null || crafts.isEmpty()) {
            return "gate:nulls grid=%s service=%s level=%s crafts=%d".formatted(
                    grid != null, craftingService != null, level != null, crafts.size());
        }
        for (IPatternDetails pattern : crafts.keySet()) {
            if (pattern instanceof HostBatchPattern) {
                return "gate:alreadyBatched";
            }
        }
        // 2) 宿主存在性
        if (!hasActiveHosts(grid)) {
            return "gate:noHosts";
        }
        // 3) Context：armed 可为 null（非递归路径的 dyeable 字段返回 null 即可）；
        //    stock 快照与上游产出键集驱动「现料批 + 等料批」拆批
        java.util.Set<AEKey> upstreamProducedKeys = new java.util.LinkedHashSet<>();
        for (Map.Entry<IPatternDetails, Long> entry : crafts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            for (GenericStack outputStack : entry.getKey().getOutputs()) {
                if (outputStack != null && outputStack.what() != null) {
                    upstreamProducedKeys.add(outputStack.what());
                }
            }
        }
        HostBatchPlanningContext rewriteContext = HostBatchPlanningContext.createWithStock(
                craftingService, armed, new GenericStack(output, Math.max(1L, amount)),
                networkStock, upstreamProducedKeys);

        // 4) 链分段 + 批次化
        HostChainSegmenter.SelectedPlanGraph selectedGraph = HostChainSegmenter.buildSelectedPlanGraph(
                rewriteContext, output, crafts);
        if (selectedGraph == null || selectedGraph.nodes().isEmpty()) {
            String summary = "noGraph nodes=%s crafts=%d".formatted(
                    selectedGraph == null ? "null" : selectedGraph.nodes().size(), crafts.size());
            diag("graph.hostBatch " + summary);
            return summary;
        }
        List<HostChainSegmenter.BatchedCandidate> candidates =
                HostChainSegmenter.buildHostAggregationCandidates(
                        level, rewriteContext, selectedGraph);
        int hostNullNodes = 0;
        for (var node : selectedGraph.nodes().values()) {
            if (node.host() == null) {
                hostNullNodes++;
            }
        }
        if (candidates.isEmpty()) {
            String summary = "noCandidates nodes=%d hostNull=%d crafts=%d".formatted(
                    selectedGraph.nodes().size(), hostNullNodes, crafts.size());
            diag("graph.hostBatch " + summary);
            return summary;
        }

        // 5) 批次化写回
        Map<IPatternDetails, Long> rewritten = new LinkedHashMap<>(crafts);
        boolean changed = false;
        int failedBuild = 0;
        // 现料拆批的兄弟批共享 originalPatterns：第一个批移除后第二个
        // 必然 miss——样板仍在则移除；已被前面的兄弟移除则本批照常写入
        // （兄弟批语义）；从未被任何批移除才是真 miss。此前兄弟批误走 miss
        // 分支被丢弃，等料批份量从计划中凭空消失，上游产出永远差一截，
        // 任务表剩孤儿样板永缺料死锁（stall 形态：remaining=1 + 原生样板
        // xN + extractNull 大 + waitingFor=0）。
        java.util.Set<IPatternDetails> removedOriginals = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        int removedMiss = 0;
        int siblingBatches = 0;
        for (HostChainSegmenter.BatchedCandidate batched : candidates) {
            HostChainSegmenter.HostAggregationCandidate candidate = batched.candidate();
            HostBatchPatternImpl aggregatedPattern = HostBatchPatternImpl.create(
                    level.registryAccess(),
                    candidate.basePattern(),
                    CraftingHostLocator.fromHost(candidate.host()),
                    candidate.boundaryInputs(),
                    candidate.aggregatedOutputs(),
                    candidate.aggregatedRemainders(),
                    candidate.steps(),
                    candidate.totalTicks()
            );
            if (aggregatedPattern == null) {
                failedBuild++;
                continue;
            }
            boolean removedAny = false;
            boolean siblingShare = false;
            for (IPatternDetails originalPattern : candidate.originalPatterns()) {
                if (rewritten.containsKey(originalPattern)) {
                    removedAny |= rewritten.remove(originalPattern) != null;
                } else if (removedOriginals.contains(originalPattern)) {
                    siblingShare = true;
                }
            }
            if (!removedAny && !siblingShare) {
                removedMiss++;
                continue;
            }
            if (siblingShare && !removedAny) {
                siblingBatches++;
            }
            removedOriginals.addAll(candidate.originalPatterns());
            rewritten.merge(aggregatedPattern, batched.pushCount(), Long::sum);
            changed = true;
        }
        String writeback = "writeback candidates=%d changed=%s failedBuild=%d removedMiss=%d sibling=%d"
                .formatted(candidates.size(), changed, failedBuild, removedMiss, siblingBatches)
                + " craftsBefore=%d craftsAfter=%d nodes=%d".formatted(crafts.size(), rewritten.size(),
                        selectedGraph.nodes().size());
        diag("graph.hostBatch " + writeback);
        var segFailures = HostChainSegmenter.drainFailureStats();
        diag("graph.hostBatch segFail topo=%d defEmpty=%d remainder=%d primary=%d boundaryIn=%d boundaryOut=%d"
                        + " splitOut=%d baseNull=%d unwrapNull=%d hostNull=%d ringMember=%d unsupported=%d"
                        + " blockedSeg=%d blockedNodes=%d stockSplit=%d stockSplitFallback=%d"
                        + " stockCut=%d stockCutNodes=%d stockCutShort=%d stockCutShortKey=%s"
                        + " topoEx=%s remainderEx=%s boundaryEx=%s hostNullEx=%s hostNullKey=%s",
                segFailures.topoNull, segFailures.defEmpty, segFailures.remainderNull,
                segFailures.primaryNonPositive, segFailures.boundaryInputsNull, segFailures.boundaryOutputsEmpty,
                segFailures.splitOutputsEmpty, segFailures.basePatternNull,
                segFailures.groupedUnwrapNull, segFailures.groupedHostNull, segFailures.groupedRingMember,
                segFailures.groupedUnsupported,
                segFailures.blockedSegments, segFailures.blockedSegmentNodes,
                segFailures.stockSplits, segFailures.stockSplitFallback,
                segFailures.stockCutRings, segFailures.stockCutRingNodes,
                segFailures.stockCutShort, segFailures.stockCutShortKey,
                segFailures.topoExample, segFailures.remainderExample, segFailures.boundaryExample,
                segFailures.hostNullExample, segFailures.hostNullKeyExample);
        if (!changed) {
            return writeback;
        }
        crafts.clear();
        crafts.putAll(rewritten);
        return writeback;
    }

    /**
     * 归因行：诊断会话活跃时进 trace buffer（随 dump 一次输出），
     * 否则直出主日志——绝不两处都打。格式为 printf 风格（%d/%s），
     * 直出前先 String.format（SLF4J 只认 {} 占位，此前 %d 原样漏出）。
     */
    static void diag(String format, Object... args) {
        if (git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics.active()) {
            git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics.log(format, args);
            return;
        }
        LOGGER.info("[hostBatch] " + (args.length == 0 ? format : String.format(format, args)));
    }

    public static void rewriteCrafts(CraftingSimulationState state, CraftingCalculation calculation, long calculatedAmount) {
        try {
            rewriteCraftsInternal(state, calculation, calculatedAmount);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to rewrite crafts to host batches for output {}",
                    calculation.getOutput(), exception);
        }
    }

    private static void rewriteCraftsInternal(
            CraftingSimulationState state,
            CraftingCalculation calculation,
            long calculatedAmount
    ) {
        // 1) 配置总开关（持久键沿用 formalMachinePlanningAggregationEnabled）。
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED,
                "formalMachinePlanningAggregationEnabled")) {
            return;
        }
        // 2) armed gate：递归路径 preliminary 阶段恒 false（补种/环记账
        //    必须看到原生样板），final build 与原生/快树路径为 true。
        if (!(calculation instanceof HostBatchArmed armed) || !armed.chexsonsaeutils$hostBatchArmed()) {
            return;
        }
        // 3) grid：宿主探测与 provider 查询的来源。
        IGrid grid = armed.chexsonsaeutils$planningGrid();
        if (grid == null) {
            return;
        }
        // 4) 幂等守门：crafts 已含批次样板（理论不可达——子沙盒每轮重建），
        //    防御未来构建点变化导致的双计。
        Map<IPatternDetails, Long> crafts = ((CraftingSimulationStateHostBatchAccessor) state)
                .chexsonsaeutils$crafts();
        if (crafts.isEmpty()) {
            return;
        }
        for (IPatternDetails pattern : crafts.keySet()) {
            if (pattern instanceof HostBatchPattern) {
                return;
            }
        }
        // 5) 宿主存在性早退：无宿主网格跳过整个分段——探测结果挂
        //    calculation 生命周期，CRAFT_LESS 每轮 probe 共享一次扫描。
        Boolean cachedPresence = armed.chexsonsaeutils$hostPresenceCache();
        boolean hasHosts = cachedPresence != null ? cachedPresence : hasActiveHosts(grid);
        if (cachedPresence == null) {
            armed.chexsonsaeutils$setHostPresenceCache(hasHosts);
        }
        if (!hasHosts) {
            return;
        }

        CraftingService craftingService = (CraftingService) grid.getCraftingService();
        Level level = ((CraftingCalculationAccessor) calculation).chexsonsaeutils$getLevel();
        if (level == null) {
            return;
        }
        AEKey output = calculation.getOutput();
        HostBatchPlanningContext rewriteContext = HostBatchPlanningContext.create(
                craftingService,
                armed,
                new GenericStack(output, Math.max(1L, calculatedAmount))
        );

        // 6) 链分段 + 批次化：输入从 nativePlan.patternTimes 改为 state.crafts。
        HostChainSegmenter.SelectedPlanGraph selectedGraph = HostChainSegmenter.buildSelectedPlanGraph(
                rewriteContext,
                output,
                crafts
        );
        if (selectedGraph == null || selectedGraph.nodes().isEmpty()) {
            return;
        }
        List<HostChainSegmenter.BatchedCandidate> candidates = HostChainSegmenter.buildHostAggregationCandidates(
                level,
                rewriteContext,
                selectedGraph
        );
        if (candidates.isEmpty()) {
            return;
        }

        // 7) 批次化写回：full 实例按 pushCount 写入，remainder 实例 1 次。
        Map<IPatternDetails, Long> rewritten = new LinkedHashMap<>(crafts);
        boolean changed = false;
        for (HostChainSegmenter.BatchedCandidate batched : candidates) {
            HostChainSegmenter.HostAggregationCandidate candidate = batched.candidate();
            HostBatchPatternImpl aggregatedPattern = HostBatchPatternImpl.create(
                    level.registryAccess(),
                    candidate.basePattern(),
                    CraftingHostLocator.fromHost(candidate.host()),
                    candidate.boundaryInputs(),
                    candidate.aggregatedOutputs(),
                    candidate.aggregatedRemainders(),
                    candidate.steps(),
                    candidate.totalTicks()
            );
            if (aggregatedPattern == null) {
                LOGGER.warn("Failed to build aggregated host-batch pattern for host {} and output {}",
                        candidate.host().getBlockPos(), output);
                continue;
            }
            boolean removedAny = false;
            for (IPatternDetails originalPattern : candidate.originalPatterns()) {
                removedAny |= rewritten.remove(originalPattern) != null;
            }
            if (!removedAny) {
                continue;
            }
            rewritten.merge(aggregatedPattern, batched.pushCount(), Long::sum);
            changed = true;
        }

        if (!changed) {
            return;
        }

        // 8) 写回 crafts：AE2 原生逻辑继续构建计划（bytes/used/missing 原值）。
        crafts.clear();
        crafts.putAll(rewritten);
    }

    /**
     * 宿主存在性探测（O(机器数) 只读扫描，绝不触发
     * {@code synchronizeIndexedProviders}）。结果由调用方挂 calculation
     * 生命周期缓存。
     */
    public static boolean hasActiveHosts(@Nullable IGrid grid) {
        if (grid == null) {
            return false;
        }
        return scanHostProviders(grid);
    }

    private static boolean scanHostProviders(IGrid grid) {
        try {
            // AE2 网格按节点 owner 的实际类注册机器（Grid.addNode 用
            // owner.getClass() 作键），按抽象类/接口 getMachines 永远查不到。
            // 改走节点遍历比 owner 接口——具体实现类是什么都不影响。
            for (var node : grid.getNodes()) {
                if (node != null && node.getOwner() instanceof HostBatchProvider) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 探测绝不破坏规划；失败按无宿主处理。
        }
        return false;
    }

    static @Nullable HostBatchProvider exclusiveHostBatchProvider(
            CraftingService craftingService,
            IPatternDetails pattern
    ) {
        IPatternDetails providerPattern = HostChainSegmenter.unwrapBasePattern(pattern);
        if (providerPattern == null) {
            return null;
        }

        HostBatchProvider matchedHost = null;
        boolean sawFormalProvider = false;
        for (ICraftingProvider provider : craftingService.getProviders(providerPattern)) {
            if (!(provider instanceof HostBatchProvider host)) {
                continue;
            }
            sawFormalProvider = true;
            if (matchedHost != null && matchedHost != host) {
                return null;
            }
            matchedHost = host;
        }
        return sawFormalProvider ? matchedHost : null;
    }
}
