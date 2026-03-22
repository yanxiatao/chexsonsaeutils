package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.mixin.ChexsonsaeutilsMixinPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterCraftingContinuationLifecycleIntegrationTest {

    private static final String MIXIN_PLUGIN_CLASS =
            "git.chexson.chexsonsaeutils.mixin.ChexsonsaeutilsMixinPlugin";
    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";
    private static final String CRAFTING_SERVICE_CONTINUATION_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceContinuationMixin";
    private static final String CPU_MENU_CONTINUATION_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuContinuationMixin";
    private static final String CPU_SCREEN_CONTINUATION_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingCPUScreenContinuationMixin";
    private static final String STATUS_TABLE_CONTINUATION_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.CraftingStatusTableRendererContinuationMixin";
    private static final String CPU_MENU_ACCESSOR_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuAccessor";
    private static final String TABLE_RENDERER_ACCESSOR_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.client.gui.AbstractTableRendererAccessor";
    private static final String ALWAYS_ON_MIXIN =
            "git.chexson.chexsonsaeutils.mixin.ae2.menu.SomeAlwaysOnMixin";
    private static final Path PARTIAL_SUBMIT = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationPartialSubmit.java");
    private static final Path SUBMIT_BRIDGE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationSubmitBridge.java");
    private static final Path MIXINS = Path.of("src/main/resources/chexsonsaeutils.mixins.json");
    private static final Path MIXIN_PLUGIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ChexsonsaeutilsMixinPlugin.java");
    private static final Path CONFIRM_MENU_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java");
    private static final Path CRAFTING_SERVICE_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java");
    private static final Path CPU_LOGIC_ACCESSOR = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingCpuLogicAccessor.java");
    private static final Path EXECUTING_JOB_ACCESSOR = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/ExecutingCraftingJobAccessor.java");
    private static final Path SAVED_DATA = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationSavedData.java");
    private static final Path STATUS_SERVICE = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationStatusService.java");
    private static final Path CPU_MENU_ACCESSOR = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftingCPUMenuAccessor.java");
    private static final Path CPU_MENU_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftingCPUMenuContinuationMixin.java");
    private static final Path CPU_SCREEN_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CraftingCPUScreenContinuationMixin.java");
    private static final Path TABLE_RENDERER_MIXIN = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CraftingStatusTableRendererContinuationMixin.java");
    private static final Path TABLE_RENDERER_ACCESSOR = Path.of(
            "src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/AbstractTableRendererAccessor.java");

    @Test
    void continuesRunnableBranches() throws IOException {
        assertContains(SUBMIT_BRIDGE, "withContinuationMode");
        assertContains(CONFIRM_MENU_MIXIN, "withContinuationMode(");
        assertContains(CRAFTING_SERVICE_MIXIN, "submitJob");
        assertContains(CRAFTING_SERVICE_MIXIN, "CraftingContinuationPartialSubmit.submitPartialJob");
        assertContains(PARTIAL_SUBMIT, "submitPartialJob(");
    }

    @Test
    void waitingOnlyTracksMissingInitialInputs() throws IOException {
        assertContains(PARTIAL_SUBMIT, "extractAvailableInitialItems(");
        assertContains(PARTIAL_SUBMIT, "plan.missingItems()");
        assertContains(PARTIAL_SUBMIT, "seedInitialWaitingFor(");
        assertContains(PARTIAL_SUBMIT, "recordWaitingDetail(");
        assertContains(PARTIAL_SUBMIT, "timeTracker");
        assertContains(CPU_LOGIC_ACCESSOR, "getInventory");
        assertContains(EXECUTING_JOB_ACCESSOR, "getWaitingFor");
    }

    @Test
    void keepsNonMissingErrorsTerminal() throws IOException {
        assertContains(CRAFTING_SERVICE_MIXIN, "CraftingSubmitResult.INCOMPLETE_PLAN");
        assertContains(MIXINS, "\"ae2.crafting.CraftingServiceContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.crafting.CraftingCpuLogicAccessor\"");
        assertContains(MIXINS, "\"ae2.crafting.ExecutingCraftingJobAccessor\"");
    }

    @Test
    void resumesWhenInputsAppear() throws IOException {
        assertContains(STATUS_SERVICE, "trackJob(");
        assertContains(STATUS_SERVICE, "clearCompletedJob(");
        assertContains(STATUS_SERVICE, "syncSelectedCpuDetail(");
        assertContains(CPU_MENU_MIXIN, "partialWaiting");
        assertContains(STATUS_SERVICE, "resolveRefillActionSource(");
        assertContains(STATUS_SERVICE, "encodeKeyForSync(");
        assertContains(STATUS_SERVICE, "storage.extract(liveKey, stillWaiting, Actionable.MODULATE, refillActionSource)");
        assertContains(STATUS_SERVICE, "reconcileWaitingInputs(");
        assertContains(STATUS_SERVICE, "reconcileWaitingInputsOnServerEndTick(");
        assertContains(CRAFTING_SERVICE_MIXIN, "insertIntoCpus");
        assertContains(CRAFTING_SERVICE_MIXIN, "updateCPUClusters");
        assertContains(CRAFTING_SERVICE_MIXIN, "onServerEndTick");
        assertContains(CRAFTING_SERVICE_MIXIN, "reconcileWaitingInputsOnServerEndTick");
    }

    @Test
    void manualSubmitResumeDoesNotDependOnRequesterLinkage() throws IOException {
        assertContains(CRAFTING_SERVICE_MIXIN, "requestingMachine");
        assertContains(STATUS_SERVICE, "getCachedInventory()");
        assertContains(STATUS_SERVICE, "hasAvailabilityIncrease(");
        assertContains(STATUS_SERVICE, "snapshotAvailableWaitingStacks(");
    }

    @Test
    void restoresAfterRestart() throws IOException {
        assertContains(STATUS_SERVICE, "rebuildAfterLoad(");
        assertContains(STATUS_SERVICE, "buildSnapshot(");
        assertContains(SAVED_DATA, "finalOutputKey");
        assertContains(SAVED_DATA, "runningBranchLabels");
    }

    @Test
    void statusProjectionUsesInlineWaitingState() throws IOException {
        assertContains(CPU_MENU_ACCESSOR, "chexsonsaeutils$getCpu");
        assertContains(CPU_MENU_MIXIN, "method = \"broadcastChanges\"");
        assertContains(CPU_MENU_MIXIN, "At(\"HEAD\")");
        assertContains(CPU_MENU_MIXIN, "method = \"setCPU\"");
        assertContains(CPU_MENU_MIXIN, "waitingStackLines");
        assertContains(CPU_SCREEN_MIXIN, "WaitingStackProjectionHost");
        assertContains(CPU_SCREEN_MIXIN, "parseWaitingStackLines");
        assertContains(TABLE_RENDERER_MIXIN, "gui.chexsonsaeutils.crafting_status.waiting");
        assertContains(TABLE_RENDERER_MIXIN, "encodeKeyForSync");
        assertContains(TABLE_RENDERER_MIXIN, "GuiText.FromStorage");
        assertContains(TABLE_RENDERER_ACCESSOR, "chexsonsaeutils$getScreen");
        assertContains(MIXINS, "\"ae2.menu.CraftingCPUMenuAccessor\"");
        assertContains(MIXINS, "\"ae2.menu.CraftingCPUMenuContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.client.gui.CraftingCPUScreenContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.client.gui.AbstractTableRendererAccessor\"");
        assertContains(MIXINS, "\"ae2.client.gui.CraftingStatusTableRendererContinuationMixin\"");
        assertDoesNotContain(CPU_SCREEN_MIXIN, "drawFG");
    }

    @Test
    void removesLifecycleHooksWhenDisabledAfterRestart() throws Exception {
        Path configDir = writeCommonConfigDir(false);
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertFalse(plugin.shouldApplyMixin("appeng.me.service.CraftingService", CRAFTING_SERVICE_CONTINUATION_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.menu.me.crafting.CraftingCPUMenu", CPU_MENU_CONTINUATION_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftingCPUScreen", CPU_SCREEN_CONTINUATION_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.client.gui.me.common.CraftingStatusTableRenderer", STATUS_TABLE_CONTINUATION_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.menu.me.crafting.CraftingCPUMenu", CPU_MENU_ACCESSOR_MIXIN));
            assertFalse(plugin.shouldApplyMixin("appeng.client.gui.me.common.AbstractTableRenderer", TABLE_RENDERER_ACCESSOR_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });

        assertContains(MIXINS, MIXIN_PLUGIN_CLASS);
        assertContains(MIXIN_PLUGIN, "ContinuationFeatureGate.isEnabledAtStartup()");
    }

    @Test
    void preservesLifecycleHooksWhenEnabledAfterRestart() throws Exception {
        Path configDir = writeCommonConfigDir(true);
        ChexsonsaeutilsMixinPlugin plugin = new ChexsonsaeutilsMixinPlugin();

        withStartupConfigDir(configDir, () -> {
            assertTrue(plugin.shouldApplyMixin("appeng.me.service.CraftingService", CRAFTING_SERVICE_CONTINUATION_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.me.crafting.CraftingCPUMenu", CPU_MENU_CONTINUATION_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.client.gui.me.crafting.CraftingCPUScreen", CPU_SCREEN_CONTINUATION_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.client.gui.me.common.CraftingStatusTableRenderer", STATUS_TABLE_CONTINUATION_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.me.crafting.CraftingCPUMenu", CPU_MENU_ACCESSOR_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.client.gui.me.common.AbstractTableRenderer", TABLE_RENDERER_ACCESSOR_MIXIN));
            assertTrue(plugin.shouldApplyMixin("appeng.menu.SomeAlwaysOnMenu", ALWAYS_ON_MIXIN));
        });

        assertContains(MIXINS, "\"ae2.crafting.CraftingServiceContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.crafting.CraftingCpuLogicAccessor\"");
        assertContains(MIXINS, "\"ae2.crafting.ExecutingCraftingJobAccessor\"");
        assertContains(MIXINS, "\"ae2.menu.CraftingCPUMenuAccessor\"");
        assertContains(MIXINS, "\"ae2.menu.CraftingCPUMenuContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.client.gui.AbstractTableRendererAccessor\"");
        assertContains(MIXINS, "\"ae2.client.gui.CraftingCPUScreenContinuationMixin\"");
        assertContains(MIXINS, "\"ae2.client.gui.CraftingStatusTableRendererContinuationMixin\"");
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
        Path configDir = Files.createTempDirectory("continuation-lifecycle-config");
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
