package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 框架对私有维度机器的统一访问 API。
 * <p>
 * 动机：机器被捕获后位于私有维度 chexsonsaeutils:frames 真实运行，框架 BE 在主世界。
 * 样板推送（B4b 阶段）与周围管道输入需要跨维度访问机器库存/能量，
 * 本接口通过 frameId 映射（FrameStorage）解析机器位置并提供 capability 查询。
 * <p>
 * 成员作用：
 * <ul>
 *   <li>getMachineLevel：私有维度 ServerLevel（客户端或未捕获时返回 null）。</li>
 *   <li>getMachinePos：机器在私有维度的位置（映射缺失时返回 null）。</li>
 *   <li>getMachineBlockEntity：机器 BE（chunk 未加载守卫，返回 null）。</li>
 *   <li>getMachineItemHandler：机器 ITEM capability（机器无 handler 时返回 null）。</li>
 *   <li>getMachineEnergyHandler：机器 ENERGY capability（appflux 灌电路径基础，阶段 6 用）。</li>
 * </ul>
 */
public interface FrameMachineAccess {

    /**
     * @return 私有维度 ServerLevel，客户端或未捕获时返回 null
     */
    @Nullable
    ServerLevel getMachineLevel();

    /**
     * @return 机器在私有维度的位置，映射缺失时返回 null
     */
    @Nullable
    BlockPos getMachinePos();

    /**
     * @return 机器 BE，chunk 未加载或映射缺失时返回 null
     */
    @Nullable
    BlockEntity getMachineBlockEntity();

    /**
     * @return 机器 ITEM capability，机器无 handler 时返回 null
     */
    @Nullable
    IItemHandler getMachineItemHandler();

    /**
     * @return 机器 ENERGY capability，机器无 handler 时返回 null
     */
    @Nullable
    IEnergyStorage getMachineEnergyHandler();
}