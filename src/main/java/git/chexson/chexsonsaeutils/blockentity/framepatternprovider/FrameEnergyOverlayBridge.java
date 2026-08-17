package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import appeng.api.networking.IGrid;
import appeng.me.energy.IEnergyOverlayGridConnection;
import appeng.me.service.EnergyService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 石英纤维 overlay 能量桥：把主网格 EnergyService 暴露给私有网格。
 * <p>
 * 动机：隔离模式下私有网格与主网格不合并，但需要共享能量池。
 * 本类实现 AE2 内部接口 IEnergyOverlayGridConnection（@ApiStatus.Internal），
 * 由虚拟节点注册为服务；EnergyService 构建 overlay 网格时遍历该服务，
 * 经 connectedEnergyServices 把主网格 EnergyService 并入能量池
 * （不合并网格、不占频道、不暴露 ME 存储，与石英纤维同款机制）。
 * <p>
 * 跨版本风险：IEnergyOverlayGridConnection 与 EnergyService 均为 AE2 内部 API，
 * 升级 AE2 时需重新验证（官方石英纤维 QuartzFiberPart 自身也在使用该机制）。
 */
public class FrameEnergyOverlayBridge implements IEnergyOverlayGridConnection {

    private final FramePatternProviderBlockEntity blockEntity;

    public FrameEnergyOverlayBridge(FramePatternProviderBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public Collection<EnergyService> connectedEnergyServices() {
        IGrid grid = blockEntity.getMainNode().getGrid();
        if (grid == null) {
            // 主节点尚未就绪：返回空；主节点加入网格时 EnergyService 会重建 overlay 并重新收集
            return List.of();
        }
        return Collections.singletonList((EnergyService) grid.getEnergyService());
    }
}