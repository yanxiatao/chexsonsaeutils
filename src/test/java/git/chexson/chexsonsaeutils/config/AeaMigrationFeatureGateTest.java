package git.chexson.chexsonsaeutils.config;

import git.chexson.chexsonsaeutils.client.ae2.DyeablePatternPackRegistration;
import git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(DyeablePatternRecursiveConfig.DEFAULT_CROSS_COLOR_CHAIN_PLANNING_ENABLED);
    }

    @Test
    void dyeableRecursiveCrossColorChainPlanningDefaultsOff() {
        assertFalse(ChexsonsaeutilsCompatibilityConfig
                .DYEABLE_RECURSIVE_CROSS_COLOR_CHAIN_PLANNING_ENABLED
                .getDefault());
        assertFalse(DyeablePatternRecursiveConfig.crossColorChainPlanningEnabled());
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
                "com.direwolf20.buildinggadgets2.common.network.handler.PacketUpdateTemplateManager",
                "git.chexson.chexsonsaeutils.mixin.buildinggadgets2.PacketUpdateTemplateManagerMixin"
        ));
        assertFalse(plugin.shouldApplyMixin(
                "dev.ftb.mods.ftbultimine.rightclick.RightClickDispatcher",
                "git.chexson.chexsonsaeutils.mixin.ftbultimine.RightClickDispatcherMemoryCardMixin"
        ));
    }

    @Test
    void buildingGadgetsDependencyUsesMinecraft1211File() throws IOException {
        Path gradleProperties = Path.of("gradle.properties");

        String value = Files.readAllLines(gradleProperties).stream()
                .filter(line -> line.startsWith("building_gadgets2_file_id="))
                .map(line -> line.substring("building_gadgets2_file_id=".length()))
                .findFirst()
                .orElseThrow();

        assertEquals("6850515", value);
    }

    @Test
    void aeaMigrationConfigKeysStayDocumentedInSampleConfig() throws IOException {
        Path config = tempDir.resolve("chexsonsaeutils-common.toml");
        Files.writeString(config, String.join(System.lineSeparator(),
                "[aeaMigration]",
                "dyeablePatternsEnabled = false",
                "dyeableRecursiveCrossColorChainPlanningEnabled = true",
                "enhancedCraftingStatusEnabled = true",
                "buildingGadgets2IntegrationEnabled = false",
                "ftbUltimineMemoryCardEnabled = true"
        ));

        String content = Files.readString(config);

        assertTrue(content.contains("[aeaMigration]"));
        assertTrue(content.contains("dyeablePatternsEnabled = false"));
        assertTrue(content.contains("dyeableRecursiveCrossColorChainPlanningEnabled = true"));
        assertTrue(content.contains("enhancedCraftingStatusEnabled = true"));
        assertTrue(content.contains("buildingGadgets2IntegrationEnabled = false"));
        assertTrue(content.contains("ftbUltimineMemoryCardEnabled = true"));
    }

    @Test
    void dyeablePatternPackTitleUsesTranslationKey() {
        assertEquals(
                "resourcePack.chexsonsaeutils.dyeable_pattern",
                DyeablePatternPackRegistration.packTitleTranslationKey()
        );
    }

    @Test
    void dyeablePatternPackIsAlwaysActive() {
        assertTrue(DyeablePatternPackRegistration.packAlwaysActive());
    }

    @Test
    void mixinConfigStillListsAllOptionalMigrationMixins() throws IOException {
        String mixinConfig = Files.readString(Path.of("src/main/resources/chexsonsaeutils.mixins.json"));

        List<String> requiredMixins = List.of(
                "ae2.crafting.CraftingServiceDyeablePatternMixin",
                "ae2.crafting.CraftingTreeNodeDyeablePatternMixin",
                "ae2.menu.CraftingStatusEntryEnhancedStatusMixin",
                "ae2.client.gui.CraftingCPUScreenEnhancedStatusMixin",
                "buildinggadgets2.TemplateManagerHandlerMixin",
                "ftbultimine.RightClickDispatcherMemoryCardMixin"
        );

        for (String mixin : requiredMixins) {
            assertTrue(mixinConfig.contains(mixin));
        }
    }
}
