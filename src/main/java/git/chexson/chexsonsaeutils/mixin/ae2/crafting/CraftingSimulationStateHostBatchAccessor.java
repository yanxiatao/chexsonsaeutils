package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.inv.CraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * {@code crafts} 账本访问器（Phase 4）：宿主批次聚合在
 * {@code buildCraftingPlan} HEAD 注入处读写该账本。
 */
@Mixin(value = CraftingSimulationState.class, remap = false)
public interface CraftingSimulationStateHostBatchAccessor {

    @Accessor(value = "crafts", remap = false)
    Map<IPatternDetails, Long> chexsonsaeutils$crafts();
}
