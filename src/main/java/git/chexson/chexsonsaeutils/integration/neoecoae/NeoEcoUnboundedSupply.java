package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.dancingsnow.neoecoae.impl.storage.ECOCreativeCell;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import java.util.HashSet;
import java.util.Set;

/** 从真实挂载读取无限供给；容量无限或库存饱和不构成无限供给证据。 */
public final class NeoEcoUnboundedSupply {
    private NeoEcoUnboundedSupply() {}

    /** 只模拟可见挂载的提取，包装层过滤仍生效，绝不消耗有限库存。 */
    public static Set<AEKey> read(MEStorage visible) {
        var result = new HashSet<AEKey>();
        result.addAll(PlanningSupplyTruth.unboundedKeysOf(visible));
        // ECO 创造元件本身只报饱和数；公开 configuredKeys 明确提供无限供给身份。
        if (visible instanceof ECOCreativeCell creative) {
            for (AEKey key : creative.configuredKeys()) {
                if (visible.extract(key, 1, Actionable.SIMULATE, IActionSource.empty()) == 1)
                    result.add(key);
            }
        }
        return Set.copyOf(result);
    }
}
