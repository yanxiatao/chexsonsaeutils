package git.chexson.chexsonsaeutils.mixin.buildinggadgets2;

import com.direwolf20.buildinggadgets2.common.containers.TemplateManagerContainer;
import com.direwolf20.buildinggadgets2.common.network.data.UpdateTemplateManagerPayload;
import com.direwolf20.buildinggadgets2.common.network.handler.PacketUpdateTemplateManager;
import git.chexson.chexsonsaeutils.integration.buildinggadgets2.BuildingGadgets2TemplatePatternBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketUpdateTemplateManager.class, remap = false)
public abstract class PacketUpdateTemplateManagerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$encodeAe2Pattern(
            UpdateTemplateManagerPayload payload,
            IPayloadContext context,
            CallbackInfo ci
    ) {
        if (payload.mode() != 0) {
            return;
        }

        Player contextPlayer = context.player();
        if (!(contextPlayer instanceof ServerPlayer player)) {
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof TemplateManagerContainer)) {
            return;
        }

        if (!BuildingGadgets2TemplatePatternBridge.isAe2PatternTarget(menu.getSlot(1).getItem())) {
            return;
        }

        ci.cancel();
        context.enqueueWork(() -> BuildingGadgets2TemplatePatternBridge.tryEncodeIntoTemplateSlot(
                player,
                menu,
                payload.templateName()
        ));
    }
}
