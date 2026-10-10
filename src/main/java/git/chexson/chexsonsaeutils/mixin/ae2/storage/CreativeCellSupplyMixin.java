package git.chexson.chexsonsaeutils.mixin.ae2.storage;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** AE2 创造元件的配置键均由元件生成，身份判定不依赖数量投影。 */
@Mixin(targets = "appeng.me.cells.CreativeCellInventory", remap = false)
public abstract class CreativeCellSupplyMixin implements UnboundedStorageSupply {
    @Shadow @Final private Set<AEKey> configured;
    @Override public Set<AEKey> unboundedSupplyKeys() { return Set.copyOf(configured); }
}
