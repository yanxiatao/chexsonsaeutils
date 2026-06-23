package git.chexson.chexsonsaeutils.client.ae2;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;

/**
 * 染色样板资源包注册。
 *
 * 只把本 mod 内置的样板覆盖资源挂到客户端资源包列表。
 */
public final class DyeablePatternPackRegistration {

    private static final Component PACK_TITLE =
            Component.translatable("resourcePack.chexsonsaeutils.dyeable_pattern");

    private DyeablePatternPackRegistration() {
    }

    public static String packTitleTranslationKey() {
        return "resourcePack.chexsonsaeutils.dyeable_pattern";
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
        Path packPath = ModList.get().getModFileById("chexsonsaeutils").getFile()
                .findResource("resourcepacks/dyeable_pattern");
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate(
                    "builtin/chexsonsaeutils/dyeable_pattern",
                    PACK_TITLE,
                    true,
                    path -> new PathPackResources(path, packPath, false),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    PackSource.BUILT_IN
            );
            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }
}