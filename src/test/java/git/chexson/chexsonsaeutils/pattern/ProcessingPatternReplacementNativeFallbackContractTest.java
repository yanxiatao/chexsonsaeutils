package git.chexson.chexsonsaeutils.pattern;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.withStartupConfigDir;
import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.writeCommonConfig;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertDoesNotContain;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProcessingPatternReplacementNativeFallbackContractTest {

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

    private static final Path MENU_MIXIN_SOURCE = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java");
    private static final Path SCREEN_MIXIN_SOURCE = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java");
    private static final Path MAIN_CLASS = javaSource(
            "git/chexson/chexsonsaeutils/Chexsonsaeutils.java");
    private static final Path FEATURE_GATE_SOURCE = javaSource(
            "git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java");
    private static final Path MIXIN_PLUGIN_SOURCE = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java");

    @Test
    void disabledModeSkipsReplacementTerminalAndRuntimeMixins() throws Exception {
        Path configDir = writeCommonConfig(
                "replacement-native-fallback",
                "processingPatternReplacementEnabled = false"
        ).getParent();
        Object plugin = instantiatePlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.menu.implementations.PatternEncodingTermMenu",
                    REPLACEMENT_MENU_MIXIN
            ));
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.client.gui.me.pattern.PatternEncodingTermScreen",
                    REPLACEMENT_SCREEN_MIXIN
            ));
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.api.crafting.PatternDetailsHelper",
                    PATTERN_DETAILS_ACCESSOR
            ));
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.crafting.execution.CraftingCalculation",
                    CRAFTING_CALCULATION_ACCESSOR
            ));
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.crafting.execution.CraftingTreeProcess",
                    CRAFTING_TREE_PROCESS_MIXIN
            ));
            assertFalse(shouldApplyMixin(
                    plugin,
                    "appeng.crafting.execution.CraftingTreeNode",
                    CRAFTING_TREE_NODE_MIXIN
            ));
        });

        assertDisabledModeUsesStartupGateInsteadOfInlineFallback();
    }

    @Test
    void disabledModeKeepsDecoderAndMetadataWritebackOutOfNativePath() throws IOException {
        assertContains(MAIN_CLASS, "if (ProcessingPatternReplacementFeatureGate.isEnabledAtStartup()) {");
        assertContains(MAIN_CLASS, "event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);");
        assertDoesNotContain(MAIN_CLASS, "writeRules(");
        assertDoesNotContain(FEATURE_GATE_SOURCE, "writeRules(");
        assertDoesNotContain(MIXIN_PLUGIN_SOURCE, "writeRules(");
        assertContains(MENU_MIXIN_SOURCE, ".restoreRuleDraft(");
        assertContains(MENU_MIXIN_SOURCE, ".restoreRuleStatus(");
    }

    private static void assertDisabledModeUsesStartupGateInsteadOfInlineFallback() throws IOException {
        assertContains(MENU_MIXIN_SOURCE, ".restoreRuleDraft(");
        assertContains(SCREEN_MIXIN_SOURCE, "ProcessingSlotRuleVisualState.PARTIALLY_INVALID");

        assertDoesNotContain(MENU_MIXIN_SOURCE, "ProcessingPatternReplacementFeatureGate");
        assertDoesNotContain(MENU_MIXIN_SOURCE, "ContinuationFeatureGate");
        assertDoesNotContain(SCREEN_MIXIN_SOURCE, "ProcessingPatternReplacementFeatureGate");
        assertDoesNotContain(SCREEN_MIXIN_SOURCE, "ContinuationFeatureGate");
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
}
