package git.chexson.chexsonsaeutils.config;

import git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AeaMigrationFeatureGateTest {

    @TempDir
    private Path tempDir;

    @Test
    void aeaMigrationGatesDefaultToEnabled() throws IOException {
        Path config = Files.createFile(tempDir.resolve("chexsonsaeutils-common.toml"));

        assertTrue(DyeablePatternsFeatureGate.isEnabledAtStartup(config));
        assertTrue(EnhancedCraftingStatusFeatureGate.isEnabledAtStartup(config));
        assertTrue(BuildingGadgets2IntegrationFeatureGate.isEnabledAtStartup(config));
        assertTrue(FtbUltimineMemoryCardFeatureGate.isEnabledAtStartup(config));
    }

    @Test
    void aeaMigrationGatesRespectExplicitFalse() throws IOException {
        Path config = tempDir.resolve("chexsonsaeutils-common.toml");
        Files.writeString(config, String.join(System.lineSeparator(),
                "dyeablePatternsEnabled = false",
                "enhancedCraftingStatusEnabled = false",
                "buildingGadgets2IntegrationEnabled = false",
                "ftbUltimineMemoryCardEnabled = false"
        ));

        assertFalse(DyeablePatternsFeatureGate.isEnabledAtStartup(config));
        assertFalse(EnhancedCraftingStatusFeatureGate.isEnabledAtStartup(config));
        assertFalse(BuildingGadgets2IntegrationFeatureGate.isEnabledAtStartup(config));
        assertFalse(FtbUltimineMemoryCardFeatureGate.isEnabledAtStartup(config));
    }

    @Test
    void optionalMigrationMixinsFailClosedWhenOptionalModsAreMissing() {
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.direwolf20.buildinggadgets2.common.containers.customhandler.TemplateManagerHandler",
                "git.chexson.chexsonsaeutils.mixin.buildinggadgets2.TemplateManagerHandlerMixin"
        ));
        assertFalse(plugin.shouldApplyMixin(
                "dev.ftb.mods.ftbultimine.rightclick.RightClickDispatcher",
                "git.chexson.chexsonsaeutils.mixin.ftbultimine.RightClickDispatcherMemoryCardMixin"
        ));
    }
}
