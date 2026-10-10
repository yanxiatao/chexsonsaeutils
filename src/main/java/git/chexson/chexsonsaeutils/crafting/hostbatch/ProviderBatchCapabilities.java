package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import org.jetbrains.annotations.Nullable;
import java.util.function.Predicate;

/** 每条 CPU 产线独立的宿主能力会话，隔离集成探测、适配身份缓存与诊断取数。 */
public interface ProviderBatchCapabilities {
    /** 先探测原生声明口；CPU 在 holder 上限读取前调用，保留原有宿主顺位。 */
    @Nullable DeclaredBatchHost nativeDeclaredHost(ICraftingProvider provider, IPatternDetails pattern,
            Predicate<DeclaredBatchHost> ready);

    /** holder 上限读取后探测适配声明口；原生 holder 已命中时不得进入折叠宿主通道。 */
    @Nullable DeclaredBatchHost adaptedDeclaredHost(ICraftingProvider provider, IPatternDetails pattern,
            Predicate<DeclaredBatchHost> ready);

    /** 取得提供者的 holder 能力；没有批量入口时返回 null。 */
    @Nullable HolderBatchCapability holderCapability(ICraftingProvider provider);

    /** 只读本会话已推送机器的队列深度与覆盖面；不重新扫描网格，不参与调度判据。 */
    String queuePendingReadout();
}
