package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import net.minecraft.core.BlockPos;

/**
 * 形式化机器的规划提供接口，标识一个方块实体可作为 Formal Machine 规划聚合的参与方。
 * <p>
 * 实现方包括 {@code AbstractExtremeAssemblerHostBlockEntity} 和
 * {@code AEDirectProcessingMachineBlockEntity}，使规划聚合服务能够
 * 定位主机并查询其当前运行时信息。
 */
public interface HostBatchProvider extends ICraftingProvider {

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
     * 用于构建 {@code CraftingHostLocator} 唯一标识一个主机。
     *
     * @return 方块坐标，不应为 null
     */
    BlockPos getBlockPos();

    /**
     * 一次 {@code pushPattern} 能接受的发射份数上限。
     * <p>
     * 默认无上限（{@link Long.MAX_VALUE}）：份数在 CPU 侧本就是 long 账，宿主按 long 记
     * 份数时无需覆写。真按 int 记份数的宿主必须自己覆写这道上界并写明原因——
     * 回报过大时 CPU 会照那份数抽料申报，宿主 {@code pushPattern} 又整批拒收，
     * 任务于是每轮重批同一份料却永远推不动。
     */
    default long maxPushExecutions(IPatternDetails pattern) {
        return Long.MAX_VALUE;
    }
}
