package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterIntegrationTest {

    @Test
    void runtimePathIsAnchoredAndRejectsPassThroughRegression() throws IOException {
        String itemSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterItem.java"
        ));
        String runtimeSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterRuntimePart.java"
        ));

        assertTrue(itemSource.contains("MultiLevelEmitterRuntimePart.class"),
                "runtime feature path must stay bound to MultiLevelEmitterRuntimePart");
        assertFalse(itemSource.contains("StorageLevelEmitterPart.class"),
                "runtime feature path must not regress to StorageLevelEmitterPart fallback");
        assertTrue(runtimeSource.contains("evaluateConfiguredOutput("),
                "runtime part must expose a concrete evaluation entry point");
        assertTrue(runtimeSource.contains("MultiLevelEmitterPart.evaluateConfiguredOutput"),
                "runtime part must execute MultiLevelEmitter evaluation logic directly");
    }

    @Test
    void runtimeEvaluationUsesPerSlotThresholdsAndNetworkPrecedence() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 2L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );

        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 1L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 2L), false));
    }

    @Test
    void thresholdCommitTransitionChangesRuntimeOutputDeterministically() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 6L, 1, 3L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );

        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 3L), true));

        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 3L),
                runtime.comparisonModes(),
                runtime.relations()
        );

        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 3L), true));
    }

    @Test
    void runtimeReloadConfigRoundTripKeepsOutputDeterministic() {
        MultiLevelEmitterRuntimePart beforeReload = newRuntimePart();
        beforeReload.applyConfiguration(
                2,
                Map.of(0, 4L, 1, 7L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );

        CompoundTag persisted = new CompoundTag();
        persisted.putInt(MultiLevelEmitterRuntimePart.NBT_CONFIGURED_ITEM_COUNT, 2);
        MultiLevelEmitterPart.writeThresholdsToNbt(
                beforeReload.thresholds(),
                persisted,
                MultiLevelEmitterRuntimePart.NBT_REPORTING_VALUES
        );
        MultiLevelEmitterUtils.writeComparisonModesToNBT(
                beforeReload.comparisonModes(),
                persisted,
                MultiLevelEmitterRuntimePart.NBT_COMPARISON_MODES
        );
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(
                beforeReload.relations(),
                persisted,
                MultiLevelEmitterRuntimePart.NBT_LOGIC_RELATIONS
        );

        MultiLevelEmitterRuntimePart afterReload = newRuntimePart();
        afterReload.applyConfiguration(
                persisted.getInt(MultiLevelEmitterRuntimePart.NBT_CONFIGURED_ITEM_COUNT),
                MultiLevelEmitterPart.readThresholdsFromNbt(
                        persisted,
                        MultiLevelEmitterRuntimePart.NBT_REPORTING_VALUES
                ),
                MultiLevelEmitterUtils.readComparisonModesFromNBT(
                        persisted,
                        MultiLevelEmitterRuntimePart.NBT_COMPARISON_MODES
                ),
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(
                        persisted,
                        MultiLevelEmitterRuntimePart.NBT_LOGIC_RELATIONS
                )
        );

        List<Long> observedValues = List.of(4L, 9L);
        boolean before = beforeReload.evaluateConfiguredOutput(observedValues, true);
        boolean after = afterReload.evaluateConfiguredOutput(observedValues, true);

        assertEquals(beforeReload.thresholds(), afterReload.thresholds());
        assertEquals(beforeReload.comparisonModes(), afterReload.comparisonModes());
        assertEquals(beforeReload.relations(), afterReload.relations());
        assertTrue(before);
        assertEquals(before, after);
    }

    @Test
    void runtimeMenuReopenRetainsTwoConfiguredSlotsWithDistinctState() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 4L, 1, 7L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );

        MultiLevelEmitterMenu.RuntimeMenu firstMenu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        MultiLevelEmitterScreen.RuntimeScreenState firstState = MultiLevelEmitterScreen.snapshotState(firstMenu);
        MultiLevelEmitterMenu.RuntimeMenu reopenedMenu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        MultiLevelEmitterScreen.RuntimeScreenState reopenedState = MultiLevelEmitterScreen.snapshotState(reopenedMenu);

        assertEquals(2, firstState.configuredSlots());
        assertEquals(2, reopenedState.configuredSlots());
        assertEquals(6, reopenedState.visibleSlots());
        assertEquals(firstState.slots().get(0).threshold(), reopenedState.slots().get(0).threshold());
        assertEquals(firstState.slots().get(1).threshold(), reopenedState.slots().get(1).threshold());
        assertEquals(firstState.slots().get(1).comparisonMode(), reopenedState.slots().get(1).comparisonMode());
    }

    @Test
    void changingOneSlotLeavesTheOtherSlotStateIntact() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 6L, 1, 3L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 5L, 64L, true, false);
        MultiLevelEmitterScreen.toggleComparisonMode(menu, 0);

        assertEquals(5L, runtime.thresholds().get(0));
        assertEquals(3L, runtime.thresholds().get(1));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.LESS_THAN, runtime.comparisonModes().get(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL, runtime.comparisonModes().get(1));
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 3L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(4L, 2L), true));
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
            runtime.applyConfiguration(0, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate runtime part test instance", exception);
        }
    }
}
