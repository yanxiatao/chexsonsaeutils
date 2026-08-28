package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastSimStateReset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * Adds a reset hook to {@link CraftingSimulationState} so the fast planning path can
 * recycle child simulation states from a pool instead of allocating fresh ones.
 *
 * <p>Only a new method is added; no existing behavior is altered. After
 * {@code chexsonsaeutils$resetForFastReuse()} the state exposes exactly the same
 * accumulators as a newly constructed instance, so reusing it is observationally
 * identical to allocating a new one.
 */
@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class CraftingSimulationStateFastResetMixin implements FastSimStateReset {

    @Shadow(remap = false)
    private KeyCounter unmodifiedCache;
    @Shadow(remap = false)
    private KeyCounter modifiableCache;
    @Shadow(remap = false)
    private KeyCounter emittedItems;
    @Shadow(remap = false)
    private double bytes;
    @Shadow(remap = false)
    private Map<IPatternDetails, Long> crafts;
    @Shadow(remap = false)
    private KeyCounter requiredExtract;

    @Override
    public void chexsonsaeutils$resetForFastReuse() {
        this.unmodifiedCache.clear();
        this.modifiableCache.clear();
        this.emittedItems.clear();
        this.requiredExtract.clear();
        this.crafts.clear();
        this.bytes = 0.0D;
    }
}
