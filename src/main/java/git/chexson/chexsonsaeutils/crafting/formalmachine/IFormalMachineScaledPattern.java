package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.pattern.AECraftingPattern;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface IFormalMachineScaledPattern extends IMolecularAssemblerSupportedPattern {

    AECraftingPattern basePattern();

    int multiplier();

    ItemStack[] getScaledCraftingGridCopies();

    @Nullable
    GenericStack templatePrimary();

    Map<AEItemKey, Long> templateRemainders();
}
