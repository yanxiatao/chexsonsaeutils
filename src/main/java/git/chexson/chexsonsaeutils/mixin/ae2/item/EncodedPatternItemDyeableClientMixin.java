package git.chexson.chexsonsaeutils.mixin.ae2.item;

import appeng.crafting.pattern.EncodedPatternItem;
import git.chexson.chexsonsaeutils.crafting.color.PatternColorHelper;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EncodedPatternItem.class, remap = false)
public abstract class EncodedPatternItemDyeableClientMixin {

    @Inject(method = "appendHoverText", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$hideOnlyColorTooltip(
            ItemStack stack,
            Level level,
            List<Component> lines,
            TooltipFlag flags,
            CallbackInfo ci
    ) {
        if (PatternColorHelper.hasOnlyColorData(stack)) {
            ci.cancel();
        }
    }
}
