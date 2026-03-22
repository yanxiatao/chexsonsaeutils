package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import net.minecraft.nbt.CompoundTag;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterExpressionRuntimeIntegrationTest {
    @Test
    void autoExpressionMigratesLegacySlotCountToFlatOrChain() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                3,
                Map.of(0, 5L, 1, 2L, 2, 7L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.AND
                )
        );

        assertEquals("#1 OR #2 OR #3", runtime.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.AUTO, runtime.expressionOwnership());
        assertFalse(runtime.expressionIsInvalid());
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 1L, 0L), true));
    }

    @Test
    void customExpressionPersistsAcrossSnapshotRoundTrip() {
        MultiLevelEmitterRuntimePart beforeReload = newRuntimePart();
        beforeReload.applyConfiguration(3, null, null, null);
        beforeReload.applyExpressionFromUi("#1 AND (#2 OR #3)");

        CompoundTag snapshot = new CompoundTag();
        writeRuntimeSnapshot(beforeReload, snapshot);

        MultiLevelEmitterRuntimePart afterReload = newRuntimePart();
        readRuntimeSnapshot(afterReload, snapshot);

        assertEquals("#1 AND (#2 OR #3)", afterReload.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.CUSTOM, afterReload.expressionOwnership());
        assertFalse(afterReload.expressionIsInvalid());
        assertTrue(afterReload.evaluateConfiguredOutput(List.of(1L, 0L, 1L), true));
    }

    @Test
    void slotShrinkDropsRemovedTailDataImmediately() throws IOException {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                3,
                Map.of(0, 4L, 1, 7L, 2, 9L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN,
                        MultiLevelEmitterPart.ComparisonMode.EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.OR
                )
        );

        runtime.updateConfiguredItemCountFromUi(2);

        String runtimeSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java"
        ));

        assertEquals(2, runtime.configuredItemCount());
        assertNull(runtime.getConfig().getStack(2));
        assertEquals(Map.of(0, 4L, 1, 7L), runtime.thresholds());
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN
                ),
                runtime.comparisonModes()
        );
        assertEquals(List.of(MultiLevelEmitterPart.LogicRelation.AND), runtime.relations());
        assertTrue(runtimeSource.contains("trimConfigInventoryToConfiguredSlots("),
                "slot shrink must keep trimming removed tail-slot config inventory immediately");
        assertTrue(runtimeSource.contains("updateConfiguredItemCountFromUi("),
                "slot shrink must stay on the runtime authority entry point");
    }

    @Test
    void customExpressionRemainsInvalidAfterShrinkUntilUserFixesIt() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(3, null, null, null);
        runtime.applyExpressionFromUi("#1 AND #3");

        assertTrue(runtime.evaluateConfiguredOutput(List.of(1L, 0L, 1L), true));

        runtime.updateConfiguredItemCountFromUi(2);

        assertEquals("#1 AND #3", runtime.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.CUSTOM, runtime.expressionOwnership());
        assertTrue(runtime.expressionIsInvalid());
        assertFalse(runtime.evaluateConfiguredOutput(List.of(1L, 1L), true));

        runtime.applyExpressionFromUi("#1 AND #2");

        assertEquals("#1 AND #2", runtime.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.CUSTOM, runtime.expressionOwnership());
        assertFalse(runtime.expressionIsInvalid());
        assertTrue(runtime.evaluateConfiguredOutput(List.of(1L, 1L), true));
    }

    @Test
    void menuApplyExpressionRejectsInvalidPayload() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(2, null, null, null);
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        menu.applyExpression("#1 AND #2");
        menu.applyExpression("#1 AND #3");

        assertEquals("#1 AND #2", runtime.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.CUSTOM, runtime.expressionOwnership());
        assertFalse(runtime.expressionIsInvalid());
    }

    private static void writeRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod("writeRuntimeSnapshot", CompoundTag.class);
            method.setAccessible(true);
            method.invoke(runtime, snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to write runtime snapshot", exception);
        }
    }

    private static void readRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod(
                    "readRuntimeSnapshot",
                    CompoundTag.class,
                    boolean.class
            );
            method.setAccessible(true);
            method.invoke(runtime, snapshot, false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read runtime snapshot", exception);
        }
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
