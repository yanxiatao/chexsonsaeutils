package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationSubmitBridge;
import git.chexson.chexsonsaeutils.mixin.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationConfirmFlowTest {
    private static final String MIXIN_PLUGIN_CLASS =
            "git.chexson.chexsonsaeutils.mixin.ChexsonsaeutilsMixinPlugin";
    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";
    private static final String CONTINUATION_MENU_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftConfirmMenuContinuationMixin";
    private static final String CONTINUATION_SCREEN_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftConfirmScreenContinuationMixin";
    private static final String ALWAYS_ON_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.SomeAlwaysOnMixin";
    private static final Path SCREEN_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CraftConfirmScreenContinuationMixin.java");
    private static final Path MENU_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java");
    private static final Path MIXINS = Path.of("src/main/resources/chexsonsaeutils.mixins.json");
    private static final Path MIXIN_PLUGIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ChexsonsaeutilsMixinPlugin.java");
    private static final Path EN_US = Path.of("src/main/resources/assets/chexsonsaeutils/lang/en_us.json");
    private static final Path ZH_CN = Path.of("src/main/resources/assets/chexsonsaeutils/lang/zh_cn.json");

    @Test
    void bridgeDefaultsToNormalMode() {
        assertEquals(CraftingContinuationMode.DEFAULT, CraftingContinuationMode.defaultMode());
        assertEquals(CraftingContinuationMode.IGNORE_MISSING,
                CraftingContinuationSubmitBridge.withContinuationMode(
                        CraftingContinuationMode.IGNORE_MISSING,
                        CraftingContinuationSubmitBridge::currentMode));
        assertEquals(CraftingContinuationMode.DEFAULT, CraftingContinuationSubmitBridge.currentMode());
    }

    @Test
    void defaultsToNormalMode() throws IOException {
        assertContains(SCREEN_MIXIN, "chexsonContinuationMode");
        assertContains(SCREEN_MIXIN, "CraftingContinuationMode.defaultMode()");
        assertContains(EN_US, "gui.chexsonsaeutils.crafting_mode.default");
    }

    @Test
    void resetsOnReopen() throws IOException {
        assertContains(MENU_MIXIN,
                "private CraftingContinuationMode chexsonsaeutils$continuationMode = CraftingContinuationMode.defaultMode();");
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.getConfirmMode(menu)");
    }

    @Test
    void sharesModeWithinCurrentConfirm() throws IOException {
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.setConfirmMode(menu");
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.getConfirmMode(menu)");
    }

    @Test
    void enablesSimulationStartOnlyWhenIgnoreMissing() throws IOException {
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.allowsSimulationStart(menu, plan)");
        assertContains(EN_US, "gui.chexsonsaeutils.crafting_mode.ignore_missing");
        assertContains(ZH_CN, "gui.chexsonsaeutils.crafting_mode.ignore_missing");
    }

    @Test
    void hidesContinuationEntryWhenDisabledAfterRestart() throws Exception {
        Path configDir = writeCommonConfigDir(false);
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(plugin.shouldApplyMixin("appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });

        assertContains(MIXINS, MIXIN_PLUGIN_CLASS);
        assertContains(MIXIN_PLUGIN, "ContinuationFeatureGate.isEnabledAtStartup()");
    }

    @Test
    void keepsContinuationEntryWhenEnabledAfterRestart() throws Exception {
        Path configDir = writeCommonConfigDir(true);
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertTrue(plugin.shouldApplyMixin("appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });

        assertContains(SCREEN_MIXIN, "Button.builder");
        assertContains(SCREEN_MIXIN, "chexsonsaeutils$cycleContinuationMode()");
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.getConfirmMode(menu)");
        assertContains(SCREEN_MIXIN, "CraftingContinuationSubmitBridge.allowsSimulationStart(menu, plan)");
        assertContains(MENU_MIXIN, "registerClientAction");
    }

    private static void assertContains(Path path, String expectedSnippet) throws IOException {
        assertTrue(Files.exists(path), () -> "Expected file to exist: " + path);
        String content = Files.readString(path);
        assertTrue(content.contains(expectedSnippet),
                () -> "Expected " + path + " to contain: " + expectedSnippet);
    }

    private static void assertDoesNotContain(Path path, String unexpectedSnippet) throws IOException {
        assertTrue(Files.exists(path), () -> "Expected file to exist: " + path);
        String content = Files.readString(path);
        assertTrue(!content.contains(unexpectedSnippet),
                () -> "Expected " + path + " to not contain: " + unexpectedSnippet);
    }

    private static Path writeCommonConfigDir(boolean enabled) throws IOException {
        Path configDir = Files.createTempDirectory("continuation-confirm-config");
        Path configFile = configDir.resolve(COMMON_CONFIG_FILE);
        Files.writeString(configFile, "craftingContinuationEnabled = " + enabled + System.lineSeparator());
        return configDir;
    }

    private static void withStartupConfigDir(Path configDir, ThrowingRunnable action) throws Exception {
        String previousValue = System.getProperty(CONFIG_DIR_PROPERTY);
        try {
            System.setProperty(CONFIG_DIR_PROPERTY, configDir.toString());
            action.run();
        } finally {
            if (previousValue == null) {
                System.clearProperty(CONFIG_DIR_PROPERTY);
            } else {
                System.setProperty(CONFIG_DIR_PROPERTY, previousValue);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
