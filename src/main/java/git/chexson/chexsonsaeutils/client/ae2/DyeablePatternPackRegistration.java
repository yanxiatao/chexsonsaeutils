package git.chexson.chexsonsaeutils.client.ae2;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * 染色样板资源包注册。
 *
 * 只把本 mod 内置的样板覆盖资源挂到客户端资源包列表。
 */
public final class DyeablePatternPackRegistration {

    private static final ResourceLocation PACK_LOCATION =
            ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "resourcepacks/dyeable_pattern");
    private static final Component PACK_TITLE = Component.literal("Dyeable Pattern");

    private DyeablePatternPackRegistration() {
    }

    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        event.addPackFinders(PACK_LOCATION, PackType.CLIENT_RESOURCES, PACK_TITLE, PackSource.BUILT_IN, false,
                net.minecraft.server.packs.repository.Pack.Position.TOP);
    }
}
