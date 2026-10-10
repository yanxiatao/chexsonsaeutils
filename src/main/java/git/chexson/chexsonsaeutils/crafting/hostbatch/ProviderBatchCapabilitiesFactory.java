package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.networking.IGrid;
import java.util.function.Supplier;

/** 在装配入口注入能力会话创建策略，使 CPU 无需引用集成实现。 */
public interface ProviderBatchCapabilitiesFactory {
    /** 为一条产线创建独立会话；网格供应口在首次创建适配宿主时读取，避免冻结未入网状态。 */
    ProviderBatchCapabilities create(Supplier<IGrid> grid);
    /** 读取真实全场终局丢弃累计快照，不创建产线能力会话。 */
    DeclaredBatchTerminalDrops terminalDrops();
}
