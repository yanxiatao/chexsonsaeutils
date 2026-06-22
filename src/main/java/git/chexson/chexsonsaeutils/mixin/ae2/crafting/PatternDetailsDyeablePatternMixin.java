package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.color.PatternDetailsColorAccessor;
import git.chexson.chexsonsaeutils.crafting.color.PatternColorHelper;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = IPatternDetails.class, remap = false)
public interface PatternDetailsDyeablePatternMixin extends PatternDetailsColorAccessor {

    @Shadow
    AEItemKey getDefinition();

    @Override
    @Unique
    default int chexsonsaeutils$getColor() {
        ItemStack definitionStack = getDefinition().toStack();
        return PatternColorHelper.getPatternColor(definitionStack);
    }
}
