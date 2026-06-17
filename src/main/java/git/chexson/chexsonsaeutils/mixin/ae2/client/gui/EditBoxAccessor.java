package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EditBox.class, remap = false)
public interface EditBoxAccessor {
    @Accessor(value = "highlightPos", remap = false)
    int chexsonsaeutils$getHighlightPos();
}
