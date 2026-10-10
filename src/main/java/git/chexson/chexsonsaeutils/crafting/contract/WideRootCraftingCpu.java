package git.chexson.chexsonsaeutils.crafting.contract;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;

/** 能持久化并交付精确根订单的 CPU；提交行为由各执行器保持自己的任务语义。 */
public interface WideRootCraftingCpu {
    /** 当前执行器是否有足够资源接收该精确计划；父订单执行器可采用自身容量规则。 */
    default boolean canSubmitWideJob(ICraftingPlan plan) {
        return git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier.resolveBytes(plan)
                .compareTo(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                        ((ICraftingCPU) this).getAvailableStorage())) <= 0;
    }

    /** 保持完整根数量提交；默认直接执行自有计划，可选执行器使用官方精确接单 API。 */
    default ICraftingSubmitResult submitWideJob(IGrid grid, ICraftingPlan plan, IActionSource source) {
        return grid.getCraftingService().submitJob(plan, null, (ICraftingCPU) this, true, source);
    }
}
