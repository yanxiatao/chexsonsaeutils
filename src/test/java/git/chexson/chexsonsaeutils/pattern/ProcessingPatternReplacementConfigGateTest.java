package git.chexson.chexsonsaeutils.pattern;

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
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPatternReplacementConfigGateTest {

    private static final String FEATURE_GATE_CLASS =
            "git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate";
    private static final String MIXIN_PLUGIN_CLASS =
            "git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin";
    private static final String REPLACEMENT_MENU_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.PatternEncodingTermMenuRuleMixin";
    private static final String REPLACEMENT_SCREEN_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.PatternEncodingTermScreenRuleMixin";
    private static final String PATTERN_DETAILS_ACCESSOR =
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor";
    private static final String CRAFTING_CALCULATION_ACCESSOR =
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor";
    private static final String CRAFTING_TREE_PROCESS_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessReplacementMixin";
    private static final String CRAFTING_TREE_NODE_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeReplacementMixin";
    private static final String CONTINUATION_MENU_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftConfirmMenuContinuationMixin";
    private static final String ALWAYS_ON_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.SomeAlwaysOnMixin";
    private static final Path COMPATIBILITY_CONFIG = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java");
    private static final Path FEATURE_GATE_SOURCE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java");
    private static final Path MIXIN_PLUGIN_SOURCE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java");
    private static final Path MAIN_CLASS = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java");
    private static final Path MIXIN_CONFIG = Path.of("src/main/resources/chexsonsaeutils.mixins.json");
    private static final Path PATTERN_MENU_MIXIN_SOURCE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java");

    @Test
    void declaresStandaloneReplacementConfigKey() throws IOException {
        assertContains(COMPATIBILITY_CONFIG, "PROCESSING_PATTERN_REPLACEMENT_ENABLED");
        assertContains(COMPATIBILITY_CONFIG, ".define(\"processingPatternReplacementEnabled\", true)");
        assertContains(COMPATIBILITY_CONFIG, "CRAFTING_CONTINUATION_ENABLED");
        assertDoesNotContain(COMPATIBILITY_CONFIG, "FeatureGateRegistry");
        assertDoesNotContain(COMPATIBILITY_CONFIG, "GenericCompatibilityToggle");
        assertDoesNotContain(COMPATIBILITY_CONFIG, "compatibilityMode");
    }

    @Test
    void resolvesPersistedFalseBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig(
                "replacement-gate-config",
                "processingPatternReplacementEnabled = false"
        );
        assertEquals(Boolean.FALSE, invokeStartupConfigRead(configFile));
    }

    @Test
    void resolvesPersistedTrueBeforeSpecLoads() throws Exception {
        Path configFile = writeCommonConfig(
                "replacement-gate-config",
                "processingPatternReplacementEnabled = true"
        );
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(configFile));
    }

    @Test
    void fallsBackToDefaultWhenStartupConfigMissingOrUnreadable() throws Exception {
        Path missingConfig = Files.createTempDirectory("replacement-gate-missing")
                .resolve(commonConfigFileName());
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(missingConfig));

        Path unreadableConfig = Files.createTempDirectory("replacement-gate-unreadable");
        assertEquals(Boolean.TRUE, invokeStartupConfigRead(unreadableConfig));
    }

    @Test
    void featureGateUsesPersistedCommonConfigWithoutCleanupLogic() throws IOException {
        assertContains(FEATURE_GATE_SOURCE, "processingPatternReplacementEnabled");
        assertContains(FEATURE_GATE_SOURCE,
                "ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED.getDefault()");
        assertContains(FEATURE_GATE_SOURCE, "FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE)");
        assertDoesNotContain(FEATURE_GATE_SOURCE, "writeRules(");
    }

    @Test
    void startupPluginDisablesReplacementBundleWhenPersistedFalse() throws Exception {
        Path configDir = writeCommonConfig(
                "replacement-gate-config",
                "processingPatternReplacementEnabled = false"
        ).getParent();
        Object plugin = instantiatePlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(shouldApplyMixin(plugin, "appeng.menu.implementations.PatternEncodingTermMenu", REPLACEMENT_MENU_MIXIN));
            assertFalse(shouldApplyMixin(plugin, "appeng.client.gui.me.pattern.PatternEncodingTermScreen", REPLACEMENT_SCREEN_MIXIN));
            assertFalse(shouldApplyMixin(plugin, "appeng.api.crafting.PatternDetailsHelper", PATTERN_DETAILS_ACCESSOR));
            assertFalse(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingCalculation", CRAFTING_CALCULATION_ACCESSOR));
            assertFalse(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingTreeProcess", CRAFTING_TREE_PROCESS_MIXIN));
            assertFalse(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingTreeNode", CRAFTING_TREE_NODE_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void startupPluginKeepsReplacementBundleWhenPersistedTrue() throws Exception {
        Path configDir = writeCommonConfig(
                "replacement-gate-config",
                "processingPatternReplacementEnabled = true"
        ).getParent();
        Object plugin = instantiatePlugin();

        withStartupConfigDir(configDir, () -> {
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.implementations.PatternEncodingTermMenu", REPLACEMENT_MENU_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.client.gui.me.pattern.PatternEncodingTermScreen", REPLACEMENT_SCREEN_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.api.crafting.PatternDetailsHelper", PATTERN_DETAILS_ACCESSOR));
            assertTrue(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingCalculation", CRAFTING_CALCULATION_ACCESSOR));
            assertTrue(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingTreeProcess", CRAFTING_TREE_PROCESS_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.crafting.execution.CraftingTreeNode", CRAFTING_TREE_NODE_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.implementations.CraftConfirmMenu", CONTINUATION_MENU_MIXIN));
            assertTrue(shouldApplyMixin(plugin, "appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });
    }

    @Test
    void mixinPluginRoutesReplacementAndContinuationThroughSeparateStartupGates() throws IOException {
        assertContains(MIXIN_PLUGIN_SOURCE, "REPLACEMENT_TERMINAL_MIXINS");
        assertContains(MIXIN_PLUGIN_SOURCE, "REPLACEMENT_RUNTIME_MIXINS");
        assertContains(MIXIN_PLUGIN_SOURCE, "REPLACEMENT_ONLY_MIXINS");
        assertContains(MIXIN_PLUGIN_SOURCE, "ProcessingPatternReplacementFeatureGate.isEnabledAtStartup()");
        assertContains(MIXIN_PLUGIN_SOURCE, "ContinuationFeatureGate.isEnabledAtStartup()");
        assertDoesNotContain(MIXIN_PLUGIN_SOURCE, "writeRules(");
    }

    @Test
    void mixinConfigStillRegistersAllReplacementMembers() throws IOException {
        assertContains(MIXIN_CONFIG, "PatternEncodingTermMenuRuleMixin");
        assertContains(MIXIN_CONFIG, "PatternEncodingTermScreenRuleMixin");
        assertContains(MIXIN_CONFIG, "PatternDetailsHelperAccessor");
        assertContains(MIXIN_CONFIG, "CraftingCalculationAccessor");
        assertContains(MIXIN_CONFIG, "CraftingTreeProcessReplacementMixin");
        assertContains(MIXIN_CONFIG, "CraftingTreeNodeReplacementMixin");
    }

    @Test
    void commonSetupGuardsDecoderRegistrationWithReplacementGate() throws IOException {
        String mainSource = readUtf8(MAIN_CLASS).replace("\r\n", "\n");
        assertContains(MAIN_CLASS, "if (ProcessingPatternReplacementFeatureGate.isEnabledAtStartup()) {");
        assertContains(MAIN_CLASS, "event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);");
        assertContains(MAIN_CLASS, "registerProcessingPatternReplacementDecoder");
        assertFalse(mainSource.contains(
                "event.enqueueWork(Chexsonsaeutils::registerMultiLevelEmitterBootstrap);\n" +
                        "        event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);"
        ));
        assertDoesNotContain(MAIN_CLASS, "writeRules(");
    }

    @Test
    void boundarySourcesDoNotAddCleanupOrInlineGateLogic() throws IOException {
        assertDoesNotContain(PATTERN_MENU_MIXIN_SOURCE, "ProcessingPatternReplacementFeatureGate");
        assertDoesNotContain(PATTERN_MENU_MIXIN_SOURCE, "ContinuationFeatureGate");
        assertDoesNotContain(MAIN_CLASS, "writeRules(");
        assertDoesNotContain(FEATURE_GATE_SOURCE, "writeRules(");
        assertDoesNotContain(MIXIN_PLUGIN_SOURCE, "writeRules(");
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
