package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.networking.crafting.ICraftingProvider;
import net.minecraft.core.BlockPos;

/**
 * 形式化机器的规划提供接口，标识一个方块实体可作为 Formal Machine 规划聚合的参与方。
 * <p>
 * 实现方包括 {@code AbstractHighCapacityCraftingHostBlockEntity} 和
 * {@code AEDirectProcessingMachineBlockEntity}，使规划聚合服务能够
 * 定位主机并查询其当前运行时信息。
 */
public interface IFormalMachinePlanningProvider extends ICraftingProvider {

    /**
     * 返回当前正在执行的操作已消耗的 tick 数。
     * <p>
     * 用于规划聚合时估算模式执行进度。
     *
     * @return 已消耗的 tick 数，0 表示空闲
     */
    int getCurrentOperationTicks();

    /**
     * 返回此提供者的方块位置。
     * <p>
     * 用于构建 {@code FormalMachineHostLocator} 唯一标识一个主机。
     *
     * @return 方块坐标，不应为 null
     */
    BlockPos getBlockPos();
}
