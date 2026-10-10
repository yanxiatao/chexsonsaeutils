package git.chexson.chexsonsaeutils.network.directprocessing;

import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Function;

public record DirectProcessingJeiImportPayload(
        int containerId,
        MachineRecipeConfigImportRequest request
) implements CustomPacketPayload {

    public static final Type<DirectProcessingJeiImportPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "direct_processing_jei_import")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DirectProcessingJeiImportPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    DirectProcessingJeiImportPayload::containerId,
                    MachineRecipeConfigImportRequest.STREAM_CODEC,
                    DirectProcessingJeiImportPayload::request,
                    DirectProcessingJeiImportPayload::new
            );

    public static void handle(DirectProcessingJeiImportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!tryApplyToMenu(context.player().containerMenu, payload)) {
                com.mojang.logging.LogUtils.getLogger().warn("直连处理机拒绝导入机器 {} 的配方", payload.request().machineItemId());
                context.player().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "gui.chexsonsaeutils.ae_direct_processing_machine.import_failed"), false);
            }
        });
    }

    static boolean tryApplyToMenu(AbstractContainerMenu currentMenu, DirectProcessingJeiImportPayload payload) {
        if (payload == null || payload.request() == null) {
            return false;
        }
        if (!(currentMenu instanceof AEDirectProcessingMachineMenu menu)) {
            return false;
        }
        return tryApplyToTarget(menu.getContainerIdForPayload(), payload, menu::applyJeiImportRequestFromClient);
    }

    static boolean tryApplyToTarget(
            int currentContainerId,
            DirectProcessingJeiImportPayload payload,
            Function<MachineRecipeConfigImportRequest, Boolean> applier
    ) {
        if (payload == null || payload.request() == null || applier == null) {
            return false;
        }
        if (currentContainerId != payload.containerId()) {
            return false;
        }
        return Boolean.TRUE.equals(applier.apply(payload.request()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
