package git.chexson.chexsonsaeutils.integration.appflux;

import org.jetbrains.annotations.Nullable;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.energy.IEnergyStorage;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.config.Actionable;
import appeng.api.upgrades.IUpgradeableObject;
import com.glodblock.github.appflux.common.AFSingletons;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import com.glodblock.github.appflux.config.AFConfig;
import com.glodblock.github.appflux.util.AFUtil;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.integration.CustomPatternEnergyInjector;

/**
 * appflux 感应卡灌电实现（需求 7）。
 * <p>
 * 动机：appflux 是 compileOnly 依赖——本类集中所有 appflux 引用，且只在
 * {@link #create} 确认 appflux 已加载后才实例化；未加载时返回 null，逻辑层
 * 不调用本类任何方法，避免 NoClassDefFoundError。
 * <p>
 * 灌电逻辑仿 appflux {@code EnergyHandler.DEFAULT}：先 simulate 查询机器可接收量，
 * 再从网格 FE 存储 extract，实际注入后把差额插回网格（不丢能量）。
 * 单次上限受 {@code AFConfig.getFluxAccessorIO()} 约束（默认 Long.MAX_VALUE，
 * 即"强行灌满"）。
 * <p>
 * 阶段 1 共享层泛化：感应卡检测与能量目标经宿主接口解析——升级库存
 * {@code host.getUpgrades()}、能量 handler {@code host.getMachineEnergyHandler()}
 * （无参版：遍历方向取第一个可用能量 handler，全不可用时返回 null 即跳过灌电）。
 */
public class AppFluxEnergyInjectorImpl implements CustomPatternEnergyInjector {

    private final CustomPatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;

    private AppFluxEnergyInjectorImpl(CustomPatternProviderLogicHost host, IManagedGridNode mainNode,
            IActionSource actionSource) {
        this.host = host;
        this.mainNode = mainNode;
        this.actionSource = actionSource;
    }

    /**
     * 工厂：appflux 未加载时返回 null（逻辑层据此跳过灌电）。
     *
     * @param host         逻辑宿主（取机器能量 handler 与升级库存）
     * @param mainNode     网格节点（取网格 FE 存储）
     * @param actionSource 注入动作来源（机器源）
     * @return 注入器实例；appflux 未加载时返回 null
     */
    @Nullable
    public static CustomPatternEnergyInjector create(CustomPatternProviderLogicHost host, IManagedGridNode mainNode,
            IActionSource actionSource) {
        if (!ModList.get().isLoaded("appflux")) {
            return null;
        }
        return new AppFluxEnergyInjectorImpl(host, mainNode, actionSource);
    }

    @Override
    public boolean isInstalled() {
        // 升级库存经 IUpgradeableObject 访问：CustomPatternProviderLogicHost 不声明
        // getUpgrades()（泛型菜单双边界 T extends Host & IUpgradeableObject 要求两接口
        // 无同签名方法冲突），所有宿主均实现 IUpgradeableObject，cast 运行时安全
        return ((IUpgradeableObject) host).getUpgrades().isInstalled(AFSingletons.INDUCTION_CARD);
    }

    @Override
    public int injectEnergy(int maxAmount) {
        IEnergyStorage accepter = host.getMachineEnergyHandler();
        if (accepter == null) {
            return 0;
        }
        var grid = mainNode.getGrid();
        if (grid == null) {
            return 0;
        }
        var storage = grid.getStorageService();
        // 单次上限：调用方上限与 appflux 配置取小
        int cap = Math.min(maxAmount, AFUtil.clampLong(AFConfig.getFluxAccessorIO()));
        var toAdd = accepter.receiveEnergy(cap, true);
        if (toAdd > 0) {
            var drained = storage.getInventory().extract(FluxKey.of(EnergyType.FE), toAdd, Actionable.MODULATE,
                    actionSource);
            if (drained > 0) {
                var actuallyDrained = accepter.receiveEnergy((int) drained, false);
                var differ = drained - actuallyDrained;
                if (differ > 0) {
                    storage.getInventory().insert(FluxKey.of(EnergyType.FE), differ, Actionable.MODULATE,
                            actionSource);
                }
                return actuallyDrained;
            }
        }
        return 0;
    }
}