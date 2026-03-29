package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.withStartupConfigDir;
import static git.chexson.chexsonsaeutils.support.ContinuationConfigTestSupport.writeCommonConfigDir;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationConfirmFlowTest {
    private static final String MIXIN_PLUGIN_CLASS =
            "git.chexson.chexsonsaeutils.mixin.ae2.ChexsonsaeutilsMixinPlugin";
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
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java");
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
    void rebuildsIgnoreMissingButtonAfterScreenInit() throws IOException {
        assertContains(SCREEN_MIXIN, "@Inject(method = \"init\", at = @At(\"HEAD\"))");
        assertContains(SCREEN_MIXIN, "chexsonsaeutils$continuationModeButton = null;");
        assertContains(SCREEN_MIXIN, "chexsonsaeutils$continuationModeButton == null");
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
        Path configDir = writeCommonConfigDir("continuation-confirm-config", false);
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
        Path configDir = writeCommonConfigDir("continuation-confirm-config", true);
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

}
