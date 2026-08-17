package git.chexson.chexsonsaeutils.frame;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * 私有维度强制加载（TicketController）封装。
 * <p>
 * 动机：私有维度内真实运行的机器需要无玩家也完整 tick，因此必须对机器所在区块
 * 强制加载。NeoForge 的 TicketController 提供按 owner 归属的区块 ticket 机制，
 * 本类负责注册控制器、执行 forceChunk 调用，并在服务端重启恢复 ticket 时校验
 * 归属是否仍然有效（以 FrameStorageSavedData 为权威记录）。
 */
public final class FrameTicketController implements LoadingValidationCallback {

    private static final FrameTicketController INSTANCE = new FrameTicketController();

    private final TicketController controller = new TicketController(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frames"), this);

    private FrameTicketController() {
    }

    /**
     * 获取单例。
     *
     * @return 唯一实例
     */
    public static FrameTicketController instance() {
        return INSTANCE;
    }

    /**
     * 注册控制器（RegisterTicketControllersEvent 为 mod 总线事件）。
     *
     * @param event 注册事件
     */
    public void register(RegisterTicketControllersEvent event) {
        event.register(controller);
    }

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
    public boolean forceChunk(ServerLevel frameLevel, BlockPos owner, int chunkX, int chunkZ, boolean add) {
        return controller.forceChunk(frameLevel, owner, chunkX, chunkZ, add, true);
    }

    @Override
    public void validateTickets(ServerLevel level, TicketHelper ticketHelper) {
        FrameStorageSavedData savedData = FrameStorageSavedData.get(level);
        ticketHelper.getBlockTickets().forEach((owner, chunks) -> {
            if (!savedData.hasForceloadRecord(owner)) {
                ticketHelper.removeAllTickets(owner);
            }
        });
    }
}