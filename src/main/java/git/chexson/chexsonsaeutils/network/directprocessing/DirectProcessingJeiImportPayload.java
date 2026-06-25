package git.chexson.chexsonsaeutils.network.directprocessing;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public record DirectProcessingJeiImportPayload(
        int containerId,
        MachineRecipeConfigImportRequest request
) {
    public static void encode(DirectProcessingJeiImportPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.containerId());
        MachineRecipeConfigImportRequest req = payload.request();
        writeNullableResourceLocation(buf, req.machineItemId());
        writeNullableResourceLocation(buf, req.machineBlockId());
        buf.writeVarInt(req.recipeTypeIds().size());
        for (ResourceLocation id : req.recipeTypeIds()) {
            buf.writeResourceLocation(id);
        }
        buf.writeVarInt(req.defaultTicks());
        buf.writeUtf(req.ioMode());
        buf.writeUtf(req.keyTypes());
        buf.writeBoolean(req.enabled());
        buf.writeUtf(req.signatureHintsJson());
    }

    public static DirectProcessingJeiImportPayload decode(FriendlyByteBuf buf) {
        int containerId = buf.readInt();
        ResourceLocation machineItemId = readNullableResourceLocation(buf);
        ResourceLocation machineBlockId = readNullableResourceLocation(buf);
        int recipeTypeCount = buf.readVarInt();
        List<ResourceLocation> recipeTypeIds = new ArrayList<>(recipeTypeCount);
        for (int i = 0; i < recipeTypeCount; i++) {
            recipeTypeIds.add(buf.readResourceLocation());
        }
        int defaultTicks = buf.readVarInt();
        String ioMode = buf.readUtf();
        String keyTypes = buf.readUtf();
        boolean enabled = buf.readBoolean();
        String signatureHintsJson = buf.readUtf();
        return new DirectProcessingJeiImportPayload(
                containerId,
                new MachineRecipeConfigImportRequest(
                        machineItemId,
                        machineBlockId,
                        recipeTypeIds,
                        defaultTicks,
                        ioMode,
                        keyTypes,
                        enabled,
                        signatureHintsJson
                )
        );
    }

    public static void handle(DirectProcessingJeiImportPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            net.minecraft.server.level.ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            AbstractContainerMenu currentMenu = player.containerMenu;
            if (currentMenu == null) {
                return;
            }
            tryApplyToMenu(currentMenu, payload);
        });
        ctx.setPacketHandled(true);
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

    private static void writeNullableResourceLocation(FriendlyByteBuf buf, ResourceLocation value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeResourceLocation(value);
        }
    }

    private static ResourceLocation readNullableResourceLocation(FriendlyByteBuf buf) {
        if (buf.readBoolean()) {
            return buf.readResourceLocation();
        }
        return null;
    }
}
