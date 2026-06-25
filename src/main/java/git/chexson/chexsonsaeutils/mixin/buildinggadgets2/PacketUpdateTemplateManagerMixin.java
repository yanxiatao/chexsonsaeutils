package git.chexson.chexsonsaeutils.mixin.buildinggadgets2;

import com.direwolf20.buildinggadgets2.common.containers.TemplateManagerContainer;
import com.direwolf20.buildinggadgets2.common.network.packets.PacketUpdateTemplateManager;
import git.chexson.chexsonsaeutils.integration.buildinggadgets2.BuildingGadgets2TemplatePatternBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(value = PacketUpdateTemplateManager.class, remap = false)
public abstract class PacketUpdateTemplateManagerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void chexsonsaeutils$encodeAe2Pattern(
            PacketUpdateTemplateManager message,
            Supplier<NetworkEvent.Context> context,
            CallbackInfo ci
    ) {
        PacketUpdateTemplateManagerAccessor accessor = (PacketUpdateTemplateManagerAccessor) message;
        if (accessor.getMode() != 0) {
            return;
        }

        NetworkEvent.Context ctx = context.get();
        ServerPlayer player = ctx.getSender();
        if (player == null) {
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
        ctx.enqueueWork(() -> {
            BuildingGadgets2TemplatePatternBridge.tryEncodeIntoTemplateSlot(
                    player,
                    menu,
                    accessor.getTemplateName()
            );
        });
    }
}
