package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计算内规划缓存（Phase 4 X5）：同一 calculation 的 CRAFT_LESS 二分会对
 * 相同的样板集合重跑 ~30 轮 probe——共现黑名单扫描与"最贵操作"
 * （{@code getRemainingItems} 余料仿真）挂 calculation 生命周期共享，
 * 跨 calculation 自然失效，无跨算污染风险。
 */
public final class HostBatchPlanningCache {

    private final ConcurrentHashMap<IPatternDetails, Map<AEItemKey, Long>> remainderCache =
            new ConcurrentHashMap<>();

    /** 单批余料仿真缓存：键为样板身份（同 definition 的样板余料一致）。 */
    public Map<AEItemKey, Long> remainders(
            IPatternDetails pattern,
            java.util.function.Function<IPatternDetails, Map<AEItemKey, Long>> loader) {
        return remainderCache.computeIfAbsent(pattern, loader);
    }
}
