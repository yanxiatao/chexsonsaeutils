package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 为 CPU 提供已准备整批物理派发的宿主接口；核心契约不依赖任何可选模组。
 * <p>准备只探测容量、材料与能量，不扣材料；CPU 检查 inputTotal 后才允许 push。
 */
public interface PreparedBatchDispatcher {

    /**
     * 依据单发原型和当前 CPU 库存准备一批；CPU 缩小份数后可再次调用。
     * @param pattern 当前任务样板
     * @param perCraftInputs 已提取并归位的单发输入原型
     * @param perCraftOutputs 单发预期产出
     * @param perCraftRemainders 单发预期回流
     * @param inventory 整批物理提料来源，也是后续 push 使用的 CPU 库存
     * @param requested CPU 经配额与环种留底检查后的请求上限
     * @param energyService 准备阶段的能量模拟来源，实际扣能由 CPU 负责
     * @param level 样板执行所需的世界上下文
     * @param craftingJobId 当前合成任务标识
     * @return 已准备批次；宿主不适用或当前无容量时为 null
     */
    @Nullable
    PreparedBatch prepareBatch(
            IPatternDetails pattern,
            KeyCounter[] perCraftInputs,
            KeyCounter perCraftOutputs,
            KeyCounter perCraftRemainders,
            ListCraftingInventory inventory,
            long requested,
            IEnergyService energyService,
            Level level,
            UUID craftingJobId
    );
}
