package git.chexson.chexsonsaeutils.mixin.buildinggadgets2;

import com.direwolf20.buildinggadgets2.common.network.packets.PacketUpdateTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PacketUpdateTemplateManager.class, remap = false)
public interface PacketUpdateTemplateManagerAccessor {
    @Accessor("mode")
    int getMode();

    @Accessor("templateName")
    String getTemplateName();
}
