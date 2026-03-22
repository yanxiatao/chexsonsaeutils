package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.mixin.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationConfigGateTest {

    private static final String FEATURE_GATE_CLASS =
            "git.chexson.chexsonsaeutils.config.ContinuationFeatureGate";
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
    private static final Path COMPATIBILITY_CONFIG = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java");
    private static final Path MOD_ENTRYPOINT = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java");
    private static final Path MIXINS_CONFIG = Path.of("src/main/resources/chexsonsaeutils.mixins.json");
    private static final Path MIXIN_PLUGIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ChexsonsaeutilsMixinPlugin.java");

    @Test
    void resolvesPersistedFalseBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig("craftingContinuationEnabled = false");
        assertEquals(Boolean.FALSE, invokeStartupConfigRead(configFile));
    }

    @Test
    void resolvesPersistedTrueBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig("craftingContinuationEnabled = true");
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(configFile));
    }

    @Test
    void fallsBackToDefaultWhenStartupConfigMissingOrUnreadable() throws Exception {
        Path missingConfig = Files.createTempDirectory("continuation-gate-missing")
                .resolve(COMMON_CONFIG_FILE);
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(missingConfig));

        Path unreadableConfig = Files.createTempDirectory("continuation-gate-unreadable");
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(unreadableConfig));
    }

    @Test
    void usesExplicitContinuationKey() throws IOException {
        assertContains(COMPATIBILITY_CONFIG, "craftingContinuation");
        assertContains(COMPATIBILITY_CONFIG, "ignore-missing");
        assertDoesNotContain(COMPATIBILITY_CONFIG, "safeMode");
        assertDoesNotContain(COMPATIBILITY_CONFIG, "compatibilityMode");
    }

    @Test
    void registersContinuationConfig() throws IOException {
        assertContains(MOD_ENTRYPOINT, "registerConfig");
        assertContains(MOD_ENTRYPOINT, "ChexsonsaeutilsCompatibilityConfig");
    }

    @Test
    void registersMixinPlugin() throws IOException {
        assertContains(MIXINS_CONFIG, "\"plugin\"");
        assertContains(MIXINS_CONFIG, MIXIN_PLUGIN_CLASS);
    }

    @Test
    void startupPluginDisablesContinuationMixinsWhenPersistedFalse() throws Exception {
        Path configDir = writeCommonConfig("craftingContinuationEnabled = false").getParent();
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(plugin.shouldApplyMixin("appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void startupPluginKeepsContinuationMixinsWhenPersistedTrue() throws Exception {
        Path configDir = writeCommonConfig("craftingContinuationEnabled = true").getParent();
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertTrue(plugin.shouldApplyMixin("appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void stillRegistersContinuationMixinPlugin() throws IOException {
        assertContains(MIXIN_PLUGIN, "IMixinConfigPlugin");
        assertContains(MIXIN_PLUGIN, "ContinuationFeatureGate.isEnabledAtStartup()");
    }

    @Test
    void gatesContinuationAccessors() throws IOException {
        assertContains(MIXIN_PLUGIN, "CraftingCpuLogicAccessor");
        assertContains(MIXIN_PLUGIN, "ExecutingCraftingJobAccessor");
        assertContains(MIXIN_PLUGIN, "CraftingCPUMenuAccessor");
        assertContains(MIXIN_PLUGIN, "AbstractTableRendererAccessor");
    }

    private static Boolean invokeStartupConfigRead(Path configFile) throws ReflectiveOperationException {
        Class<?> featureGateClass = Class.forName(FEATURE_GATE_CLASS);
        Method isEnabledAtStartup = featureGateClass.getMethod("isEnabledAtStartup", Path.class);
        return (Boolean) isEnabledAtStartup.invoke(null, configFile);
    }

    private static Path writeCommonConfig(String configLine) throws IOException {
        Path configDir = Files.createTempDirectory("continuation-gate-config");
        Path configFile = configDir.resolve(COMMON_CONFIG_FILE);
        Files.writeString(configFile, configLine + System.lineSeparator());
        return configFile;
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
