package git.chexson.chexsonsaeutils.mixin.extendedae;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** ExtendedAE 原生和 KubeJS custom_infinity_cell 共用此库存，只有记录的键是无限供给。 */
@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.inventory.InfinityCellInventory", remap = false)
public abstract class InfinityCellSupplyMixin implements UnboundedStorageSupply {
    @Shadow @Final private AEKey record;
    @Override public Set<AEKey> unboundedSupplyKeys() { return Set.of(record); }
}
