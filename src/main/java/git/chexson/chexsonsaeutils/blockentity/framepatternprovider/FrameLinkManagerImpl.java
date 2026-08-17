package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.energy.IEnergyOverlayGridConnection;
import appeng.me.helpers.BlockEntityNodeListener;
import git.chexson.chexsonsaeutils.frame.FrameDimensionImpl;
import git.chexson.chexsonsaeutils.frame.FrameStorageImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@link FrameLinkManager} 实现：跨维度虚拟连接的建立、销毁与隔离切换。
 * <p>
 * 拓扑：框架主节点（主世界网格）— 虚拟节点（非世界节点）— 机器节点（私有维度网格）。
 * 非隔离模式两条连接都建立，三节点合并入主网格；隔离模式只建立虚拟-机器连接，
 * 私有小网格经虚拟节点上的 overlay 能量桥与主网格共享能量池。
 * <p>
 * 机器节点在机器搬入私有维度后首次 tick 才就绪（AENetworkedBlockEntity.onReady），
 * 因此连接建立由 tick 驱动：就绪前跳过，就绪后补连，失效（搬回/卸载）后销毁。
 */
public class FrameLinkManagerImpl implements FrameLinkManager {

    private final FramePatternProviderBlockEntity blockEntity;
    /** 私有维度侧虚拟节点（非世界节点），null 表示未创建。 */
    @Nullable
    private IManagedGridNode virtualNode;
    /** 主节点 ↔ 虚拟节点连接（仅非隔离模式建立）。 */
    @Nullable
    private IGridConnection mainToVirtual;
    /** 虚拟节点 ↔ 机器节点连接（两种模式都建立）。 */
    @Nullable
    private IGridConnection virtualToMachine;
    /** 隔离模式 overlay 能量桥（随虚拟节点创建/销毁）。 */
    @Nullable
    private FrameEnergyOverlayBridge overlayBridge;
    /** 连接是否已建立（避免重复 createConnection 抛 IllegalStateException）。 */
    private boolean linkEstablished;

    public FrameLinkManagerImpl(FramePatternProviderBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public void ensureVirtualNode() {
        if (virtualNode != null) {
            return;
        }
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide()) {
            return;
        }
        IManagedGridNode node = GridHelper.createManagedNode(blockEntity, BlockEntityNodeListener.INSTANCE)
                .setTagName("frameLink")
                .setIdlePowerUsage(0)
                .setFlags(GridFlags.CANNOT_CARRY)
                .setInWorldNode(false);
        if (blockEntity.isIsolated()) {
            // 隔离模式：虚拟节点注册 overlay 能量桥，把主网格能量池暴露给私有网格
            overlayBridge = new FrameEnergyOverlayBridge(blockEntity);
            node.addService(IEnergyOverlayGridConnection.class, overlayBridge);
        }
        node.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        virtualNode = node;
    }

    @Override
    public void tick() {
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide()) {
            return;
        }
        if (!blockEntity.hasCapturedContent()) {
            if (linkEstablished) {
                teardownLink();
            }
            return;
        }
        IGridNode mainNode = blockEntity.getMainNode().getNode();
        IGridNode machineNode = getMachineNode();
        if (mainNode == null || machineNode == null) {
            // 机器节点未就绪（搬入后首 tick）或已失效（搬回/卸载）：销毁连接，等待下次 tick 补连
            if (linkEstablished) {
                teardownLink();
            }
            return;
        }
        if (!linkEstablished) {
            ensureVirtualNode();
            establishLink();
        }
    }

    @Override
    public void rebuild() {
        teardownLink();
        ensureVirtualNode();
        establishLink();
    }

    @Override
    public void teardownLink() {
        if (mainToVirtual != null) {
            mainToVirtual.destroy();
            mainToVirtual = null;
        }
        if (virtualToMachine != null) {
            virtualToMachine.destroy();
            virtualToMachine = null;
        }
        if (virtualNode != null) {
            virtualNode.destroy();
            virtualNode = null;
        }
        overlayBridge = null;
        linkEstablished = false;
    }

    /**
     * 按当前隔离配置建立连接（前置条件：主节点、机器节点、虚拟节点均已就绪）。
     */
    private void establishLink() {
        if (linkEstablished || virtualNode == null || virtualNode.getNode() == null) {
            return;
        }
        IGridNode mainNode = blockEntity.getMainNode().getNode();
        IGridNode machineNode = getMachineNode();
        if (mainNode == null || machineNode == null) {
            return;
        }
        if (blockEntity.isIsolated()) {
            // 隔离：虚拟节点与机器节点组成私有小网格，能量经 overlay 桥共享
            virtualToMachine = GridHelper.createConnection(virtualNode.getNode(), machineNode);
        } else {
            // 非隔离：主节点、虚拟节点、机器节点三节点合并入主网格
            mainToVirtual = GridHelper.createConnection(mainNode, virtualNode.getNode());
            virtualToMachine = GridHelper.createConnection(virtualNode.getNode(), machineNode);
        }
        linkEstablished = true;
    }

    /**
     * 通过 FrameStorage 映射查询私有维度机器节点。
     * <p>
     * 机器不是 AENetworkedBlockEntity（如熔炉）时没有网格节点，返回 null（无法建立虚拟连接）。
     */
    @Nullable
    private IGridNode getMachineNode() {
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return null;
        }
        UUID frameId = blockEntity.getFrameId();
        if (frameId == null) {
            return null;
        }
        ServerLevel frameLevel = FrameDimensionImpl.instance().getLevel(serverLevel.getServer());
        BlockPos framePos = FrameStorageImpl.instance().getFramePosition(frameLevel, frameId);
        if (framePos == null) {
            return null;
        }
        BlockEntity machine = frameLevel.getBlockEntity(framePos);
        if (machine instanceof AENetworkedBlockEntity networkedMachine) {
            return networkedMachine.getMainNode().getNode();
        }
        return null;
    }
}