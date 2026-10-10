package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningEngine;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanPostProcessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;
import java.math.BigInteger;

/** 单一来源的独立材料闭包；只返回可提交的真实计划或携带缺料的模拟计划。 */
public final class RemotePlanning {
    private RemotePlanning() {}
    public static ICraftingPlan plan(Level level, ICraftingService service, HolderLookup.Provider registries,
            AEKey output, BigInteger amount, IGrid grid) {
        if (grid == null) throw new IllegalArgumentException("远程规划必须绑定真实来源网络");
        var inventory = grid.getStorageService().getInventory();
        var stock = new KeyCounter();
        inventory.getAvailableStacks(stock);
        return plan(level, service, output, amount, grid, stock);
    }
    /** 多目标预览共享并递减同一库存快照，避免同源的多台设备重复计算现货。 */
    public static ICraftingPlan plan(Level level, ICraftingService service, AEKey output,
            BigInteger amount, IGrid grid, KeyCounter stock) {
        return plan(level, service, output, amount, grid, stock, null);
    }
    /** 显式共享真值覆盖默认实时采集，防止宽域盘在多目标预览中被重复使用。 */
    public static ICraftingPlan plan(Level level, ICraftingService service, AEKey output,
            BigInteger amount, IGrid grid, KeyCounter stock, git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth truth) {
        return plan(level, service, output, amount, grid, stock, truth, CalculationStrategy.REPORT_MISSING_ITEMS);
    }
    /** 执行按可完成数量拆批；预览保持完整需求及完整缺料，不改变两者的数量语义。 */
    public static ICraftingPlan plan(Level level, ICraftingService service, AEKey output,
            BigInteger amount, IGrid grid, KeyCounter stock,
            git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth truth, CalculationStrategy strategy) {
        var session = GraphPlanningEngine.createSession(new LocalCraftingViewImpl(service, grid), stock, output,
                strategy, 5000, level, true, null, grid);
        if (truth != null) session.graph().attachSupplyTruth(truth);
        var result = session.solve(PlanAmount.of(amount), GraphPlanningSession.BudgetPolicy.ENFORCED);
        var plan = result.handled() ? result.plan() : GraphPlanPostProcessor.shortagePlan(result, session.graph());
        if (plan == null) throw new IllegalStateException("来源规划拒绝：" + result.declineReason());
        if (plan.patternTimes().keySet().stream().anyMatch(RemotePattern.class::isInstance))
            throw new IllegalStateException("来源规划意外包含远程依赖");
        return plan;
    }
}
