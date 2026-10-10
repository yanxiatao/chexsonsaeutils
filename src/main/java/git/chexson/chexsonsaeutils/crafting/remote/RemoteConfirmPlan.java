package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.networking.crafting.ICraftingPlan;
/** 确认菜单实际计划的只读契约，预览读取真实样板次数，不根据摘要猜测中间材料。 */
public interface RemoteConfirmPlan {
    /** 当前已经计算完成的实际计划；计算中为空，调用者不得修改任何账本。 */
    ICraftingPlan remotePlan();
}
