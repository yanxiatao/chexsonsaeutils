package git.chexson.chexsonsaeutils.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 私有维度存储与强制加载的统一入口。
 * <p>
 * 动机：搬移阶段（B2）需要把被包裹机器搬到私有维度并强制加载其区块，
 * 本接口把这些操作收敛为单一 API，屏蔽 TicketController 与 SavedData 细节。
 * 所有方法都需要显式传入私有维度实例（通过 FrameDimension 获取），保持无状态。
 */
public interface FrameStorage {

    /**
     * 强制/解除强制加载私有维度区块（ticking=true，无玩家也完整 tick）。
     *
     * @param frameLevel 私有维度
     * @param owner      归属者（主世界框架 BE 的位置）
     * @param chunkX     区块 X
     * @param chunkZ     区块 Z
     * @param add        true 强制加载，false 解除
     * @return ticket 状态是否发生变化
     */
    boolean forceload(ServerLevel frameLevel, BlockPos owner, int chunkX, int chunkZ, boolean add);

    /**
     * 登记 frameId 对应的私有维度位置（搬移阶段调用）。
     *
     * @param frameLevel 私有维度
     * @param frameId    框架唯一 ID
     * @param position   私有维度位置
     */
    void putFramePosition(ServerLevel frameLevel, UUID frameId, BlockPos position);

    /**
     * 查询 frameId 对应的私有维度位置。
     *
     * @param frameLevel 私有维度
     * @param frameId    框架唯一 ID
     * @return 私有维度位置；无记录时返回 null
     */
    @Nullable BlockPos getFramePosition(ServerLevel frameLevel, UUID frameId);

    /**
     * 移除 frameId 的映射（机器被取出时调用）。
     *
     * @param frameLevel 私有维度
     * @param frameId    框架唯一 ID
     */
    void removeFramePosition(ServerLevel frameLevel, UUID frameId);

    /**
     * 分配下一个可用坐标（线性分配，间距 ≥ 3 格）。
     *
     * @param frameLevel 私有维度
     * @return 私有维度可用坐标
     */
    BlockPos allocateNextPosition(ServerLevel frameLevel);

    /**
     * 服务端启动时重挂全部强制加载（读 SavedData → forceChunk add）。
     *
     * @param frameLevel 私有维度
     */
    void reinstateForceloads(ServerLevel frameLevel);
}