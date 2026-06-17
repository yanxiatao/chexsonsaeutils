package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Virtual scaled crafting pattern used to collapse repeated AE2 crafting executions into larger logical runs.
 */
public interface IFormalMachineScaledPattern extends IMolecularAssemblerSupportedPattern, IFormalMachineDelegatingPattern {

    int multiplier();

    ItemStack[] getScaledCraftingGridCopies();

    @Nullable
    GenericStack templatePrimary();

    Map<AEItemKey, Long> templateRemainders();
}
