package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterPlacementIntegrationTest {

    @Test
    void emitterItemFactoryUsesAe2PartItemContract() throws IOException {
        String migratedItemSource = readSource("src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterItem.java");

        assertTrue(migratedItemSource.contains("new PartItem<"),
                "emitter item factory must instantiate AE2 PartItem for in-world placement");
        assertTrue(migratedItemSource.contains("MultiLevelEmitterRuntimePart.class"),
                "emitter item factory must bind dedicated MultiLevelEmitter runtime part class");
        assertTrue(migratedItemSource.contains("MultiLevelEmitterRuntimePart::new"),
                "emitter item factory must provide dedicated runtime part constructor");
        assertFalse(migratedItemSource.contains("StorageLevelEmitterPart.class"),
                "emitter item factory must not regress to StorageLevelEmitterPart fallback binding");
        assertFalse(migratedItemSource.contains("StorageLevelEmitterPart::new"),
                "emitter item factory must not regress to StorageLevelEmitterPart fallback constructor");
    }

    @Test
    void registryIdentityStaysStableAfterPlacementContractMigration() {
        assertEquals("multi_level_emitter", MultiLevelEmitterItem.id());
        assertTrue(MultiLevelEmitterItem.isRegistryPath(MultiLevelEmitterItem.id()),
                "placement contract migration must not change stable emitter registry path");
    }

    @Test
    void placeOpenConfigureThresholdRuntimeFlowMutatesAuthoritativeState() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, 2);
        assertEquals(2, runtime.configuredItemCount(), "place/open flow must configure runtime slot count");

        boolean committed = MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 6L, 64L, true, false);
        assertTrue(committed, "configure flow should commit threshold on enter");
        MultiLevelEmitterScreen.commitThresholdFromInput(menu, 1, 3L, 64L, false, true);

        assertEquals(Map.of(0, 6L, 1, 3L), runtime.thresholds());
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 3L), true));

        MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 5L, 64L, true, false);
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
    }

    @Test
    void networkMenuBindingUsesPublishedRuntimeForUiMutations() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.registerRuntimeBindingResolver((inventory, networkData) -> runtime);
        try {
            MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.fromNetwork(null, null);
            assertTrue(menu.hasRuntimePartBinding(), "network-opened menu should bind runtime part");

            MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, 1);
            MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 7L, 64L, true, false);

            assertEquals(1, runtime.configuredItemCount());
            assertEquals(Map.of(0, 7L), runtime.thresholds());
        } finally {
            MultiLevelEmitterMenu.registerRuntimeBindingResolver((inventory, networkData) -> null);
        }
    }

    @Test
    void runtimeScreenOpenPathUsesMenuBackedControlsInsteadOfPlaceholderText() throws IOException {
        String screenSource = readSource(
                "src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        );

        assertTrue(screenSource.contains("ThresholdEditBox"),
                "runtime screen should expose threshold inputs for configured rows");
        assertTrue(screenSource.contains("MultiLevelEmitterScreen.applyConfiguredSlotCount"),
                "runtime screen should mutate configured slot count through the runtime menu");
        assertTrue(screenSource.contains("MultiLevelEmitterScreen.toggleComparisonMode"),
                "runtime screen should route comparison changes through runtime menu helpers");
        assertFalse(screenSource.contains("Runtime menu connected"),
                "runtime screen must not regress to the old placeholder-only status copy");
    }

    private static String readSource(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }

    private static MultiLevelEmitterRuntimePart newRuntimePart() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            MultiLevelEmitterRuntimePart runtime =
                    (MultiLevelEmitterRuntimePart) allocateInstance.invoke(unsafe, MultiLevelEmitterRuntimePart.class);
            runtime.applyConfiguration(1, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate runtime part test instance", exception);
        }
    }
}
