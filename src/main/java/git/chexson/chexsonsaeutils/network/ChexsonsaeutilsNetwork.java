package git.chexson.chexsonsaeutils.network;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ChexsonsaeutilsNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.parse(Chexsonsaeutils.MODID + ":main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId;

    private ChexsonsaeutilsNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
                nextId++,
                DirectProcessingJeiImportPayload.class,
                DirectProcessingJeiImportPayload::encode,
                DirectProcessingJeiImportPayload::decode,
                DirectProcessingJeiImportPayload::handle
        );
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }
}
