package git.chexson.chexsonsaeutils.mixin.cell4;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Cell4 1.0.4fix3 的物品、标签、模组过滤盘共用生成库存；配置和黑名单由原实现应用。 */
@Pseudo
@Mixin(targets = "com.cell4.common.storage.AbstractInfinityStorage", remap = false)
public abstract class InfinityStorageSupplyMixin implements UnboundedStorageSupply {
    @Override public Set<AEKey> unboundedSupplyKeys() {
        return Set.copyOf(((MEStorage) (Object) this).getAvailableStacks().keySet());
    }
}
