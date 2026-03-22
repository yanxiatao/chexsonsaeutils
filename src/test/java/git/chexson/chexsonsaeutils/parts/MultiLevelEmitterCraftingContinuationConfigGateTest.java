package git.chexson.chexsonsaeutils.parts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.withStartupConfigDir;
import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.commonConfigFileName;
import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.writeCommonConfig;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertDoesNotContain;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationConfigGateTest {

    private static final String FEATURE_GATE_CLASS =
            "git.chexson.chexsonsaeutils.config.ContinuationFeatureGate";
    private static final String MIXIN_PLUGIN_CLASS =
            "git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin";
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

    @Test
    void resolvesPersistedFalseBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig("continuation-gate-config", "craftingContinuationEnabled = false");
        assertEquals(Boolean.FALSE, invokeStartupConfigRead(configFile));
    }

    @Test
    void resolvesPersistedTrueBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig("continuation-gate-config", "craftingContinuationEnabled = true");
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(configFile));
    }

    @Test
    void fallsBackToDefaultWhenStartupConfigMissingOrUnreadable() throws Exception {
        Path missingConfig = Files.createTempDirectory("continuation-gate-missing")
                .resolve(commonConfigFileName());
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
        assertContains(resourcePath("chexsonsaeutils.mixins.json"), "\"plugin\"");
        assertContains(resourcePath("chexsonsaeutils.mixins.json"), MIXIN_PLUGIN_CLASS);
    }

    @Test
    void startupPluginDisablesContinuationMixinsWhenPersistedFalse() throws Exception {
        Path configDir = writeCommonConfig("continuation-gate-config", "craftingContinuationEnabled = false").getParent();
        Object plugin = instantiatePlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(shouldApplyMixin(plugin, "appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertFalse(shouldApplyMixin(plugin, "appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void startupPluginKeepsContinuationMixinsWhenPersistedTrue() throws Exception {
        Path configDir = writeCommonConfig("continuation-gate-config", "craftingContinuationEnabled = true").getParent();
        Object plugin = instantiatePlugin();

        withStartupConfigDir(configDir, () -> {
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.client.gui.me.crafting.CraftConfirmScreen", CONTINUATION_SCREEN_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void stillRegistersContinuationMixinPlugin() throws IOException {
        Path mixinPlugin = javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        );
        assertContains(mixinPlugin, "IMixinConfigPlugin");
        assertContains(mixinPlugin, "ContinuationFeatureGate.isEnabledAtStartup()");
    }

    @Test
    void gatesContinuationAccessors() throws IOException {
        Path mixinPlugin = javaSource(
                "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java"
        );
        assertContains(mixinPlugin, "CraftingCpuLogicAccessor");
        assertContains(mixinPlugin, "ExecutingCraftingJobAccessor");
        assertContains(mixinPlugin, "CraftingCPUMenuAccessor");
        assertContains(mixinPlugin, "AbstractTableRendererAccessor");
    }

    private static Object instantiatePlugin() throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName(MIXIN_PLUGIN_CLASS);
        Constructor<?> constructor = pluginClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static boolean shouldApplyMixin(Object plugin, String targetClassName, String mixinClassName)
            throws ReflectiveOperationException {
        Method shouldApplyMixin = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);
        return (Boolean) shouldApplyMixin.invoke(plugin, targetClassName, mixinClassName);
    }

    private static Boolean invokeStartupConfigRead(Path configFile) throws ReflectiveOperationException {
        Class<?> featureGateClass = Class.forName(FEATURE_GATE_CLASS);
        Method isEnabledAtStartup = featureGateClass.getMethod("isEnabledAtStartup", Path.class);
        return (Boolean) isEnabledAtStartup.invoke(null, configFile);
    }

}
