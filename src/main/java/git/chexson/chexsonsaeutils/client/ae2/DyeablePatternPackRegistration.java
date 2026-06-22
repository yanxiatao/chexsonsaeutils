package git.chexson.chexsonsaeutils.client.ae2;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
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
    private static final Component PACK_TITLE =
            Component.translatable("resourcePack.chexsonsaeutils.dyeable_pattern");

    private DyeablePatternPackRegistration() {
    }

    public static String packTitleTranslationKey() {
        return "resourcePack.chexsonsaeutils.dyeable_pattern";
    }

    public static boolean packAlwaysActive() {
        return true;
    }

    public static boolean shouldRegister() {
        return FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled");
    }

    public static void register(AddPackFindersEvent event) {
        if (!shouldRegister()) {
            return;
        }
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        event.addPackFinders(PACK_LOCATION, PackType.CLIENT_RESOURCES, PACK_TITLE, PackSource.BUILT_IN, packAlwaysActive(),
                net.minecraft.server.packs.repository.Pack.Position.TOP);
    }
}
