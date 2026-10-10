package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 宿主批次聚合的规划侧 gate（Phase 4）：
 * {@code CraftingSimulationState.buildCraftingPlan} 的 HEAD 注入依据本接口
 * 决定是否把 {@code crafts} 里的宿主链样板替换为 {@link HostBatchPattern}。
 *
 * <p>arming 精确性契约：递归路径（L3）的 preliminary 快照与
 * {@code retainRecursiveCatalysts} 期间必须返回 {@code false}——补种与环记账
 * 依赖"看到原生样板"；仅最终构建前置 {@code true}。AE2 原生与快树路径恒
 * {@code true}。
 *
 * <p>{@code #dyeableSelectedPatternInputs} 供给递归路径的选定输入映射，
 * 供链分段的净输入口径使用；非递归路径返回 {@code null}。
 */
public interface HostBatchArmed {

    boolean chexsonsaeutils$hostBatchArmed();

    @Nullable
    IGrid chexsonsaeutils$planningGrid();

    /**
     * 递归计划的催化物保留账本快照；非递归路径返回 {@code null}。
     */
    @Nullable
    KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems();

    /**
     * 递归计划的选定输入映射（按键定义分组）；非递归路径返回 {@code null}。
     */
    @Nullable
    Map<AEItemKey, Map<AEKey, Long>> chexsonsaeutils$dyeableSelectedPatternInputs();

    /**
     * 宿主存在性探测结果的单槽缓存（calculation 生命周期）：null 表示未探测。
     * CRAFT_LESS 的每一轮 probe 共享一次 O(机器数) 扫描。
     */
    @Nullable
    Boolean chexsonsaeutils$hostPresenceCache();

    void chexsonsaeutils$setHostPresenceCache(@Nullable Boolean present);

    /**
     * 计算内余料仿真缓存（X5），挂 calculation 生命周期供 CRAFT_LESS
     * 各轮 probe 共享。
     */
    @Nullable
    HostBatchPlanningCache chexsonsaeutils$hostBatchPlanningCache();

    void chexsonsaeutils$setHostBatchPlanningCache(@Nullable HostBatchPlanningCache cache);
}
