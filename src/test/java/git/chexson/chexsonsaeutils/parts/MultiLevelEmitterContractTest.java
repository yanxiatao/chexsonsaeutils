package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterContractTest {

    @Test
    void networkDownOverridesEvaluationResult() {
        assertFalse(MultiLevelEmitterPart.resolveEmitterState(false, 2, true, RedstoneMode.HIGH_SIGNAL));
    }

    @Test
    void emptyConfigIsOnWhenNetworkActive() {
        assertTrue(MultiLevelEmitterPart.resolveEmitterState(true, 0, false, RedstoneMode.HIGH_SIGNAL));
    }

    @Test
    void fuzzyFallsBackToStrictWhenCapabilityMissing() {
        MultiLevelEmitterPart.MatchingMode requested = MultiLevelEmitterPart.MatchingMode.IGNORE_ALL;
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(requested, false);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
    }

    @Test
    void matchingModesNormalizePerSlot() {
        List<MultiLevelEmitterPart.MatchingMode> normalized =
                MultiLevelEmitterPart.normalizeMatchingModesForSlotCount(
                        List.of(
                                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.PERCENT_75
                        ),
                        3,
                        false
                );
        assertEquals(3, normalized.size());
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(1));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(2));
    }

    @Test
    void matchingModeChangeTriggersRecompute() {
        assertTrue(MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL));
        assertFalse(MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.STRICT));
    }

    @Test
    void craftingFallsBackToNoneWhenCapabilityMissing() {
        MultiLevelEmitterPart.CraftingMode requested = MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT;
        MultiLevelEmitterPart.CraftingMode effective =
                MultiLevelEmitterPart.resolveCraftingMode(requested, false);

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, effective);
    }

    @Test
    void craftingModesNormalizePerSlotWhenCardIsMissing() {
        List<MultiLevelEmitterPart.CraftingMode> normalized =
                MultiLevelEmitterPart.normalizeCraftingModesForSlotCount(
                        List.of(
                                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                        ),
                        3,
                        false
                );

        assertEquals(3, normalized.size());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(2));
    }

    @Test
    void craftingModeCyclesThroughExplicitNoneState() {
        assertEquals(
                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                MultiLevelEmitterPart.nextCraftingMode(MultiLevelEmitterPart.CraftingMode.NONE)
        );
        assertEquals(
                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                MultiLevelEmitterPart.nextCraftingMode(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING)
        );
        assertEquals(
                MultiLevelEmitterPart.CraftingMode.NONE,
                MultiLevelEmitterPart.nextCraftingMode(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT)
        );
    }

    @Test
    void markedSlotsDefaultToEmitWhileCraftingWhenCraftingCardBecomesAvailable() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, false);
        runtime.applyConfiguration(2, null, null, null);
        setConfiguredKey(runtime, 0, new DummyKey("marked", "slot", 0, 0));

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, runtime.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, runtime.craftingModeForSlot(1));

        runtime.setInstalledCards(false, true);
        reconcileCardModes(runtime);

        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, runtime.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, runtime.craftingModeForSlot(1));
    }

    @Test
    void expressionAndTreatsInactiveSlotAsNeutral() {
        MultiLevelEmitterExpressionCompileResult compileResult =
                MultiLevelEmitterExpressionCompiler.compile("#1 AND #2", 2, slot -> true);

        MultiLevelEmitterPart.AggregationResult result = compileResult.plan().evaluateParticipating(List.of(
                MultiLevelEmitterPart.SlotEvaluation.participating(true),
                MultiLevelEmitterPart.SlotEvaluation.inactive()
        ));

        assertEquals(1, result.participatingCount());
        assertTrue(result.result());
    }

    @Test
    void expressionOrTreatsInactiveSlotAsNeutral() {
        MultiLevelEmitterExpressionCompileResult compileResult =
                MultiLevelEmitterExpressionCompiler.compile("#1 OR #2", 2, slot -> true);

        MultiLevelEmitterPart.AggregationResult result = compileResult.plan().evaluateParticipating(List.of(
                MultiLevelEmitterPart.SlotEvaluation.participating(false),
                MultiLevelEmitterPart.SlotEvaluation.inactive()
        ));

        assertEquals(1, result.participatingCount());
        assertFalse(result.result());
    }

    @Test
    void relationFallbackReturnsZeroParticipatingWhenAllSlotsIgnored() {
        MultiLevelEmitterPart.AggregationResult result = MultiLevelEmitterPart.evaluateFinalResultWithParticipation(
                List.of(
                        MultiLevelEmitterPart.SlotEvaluation.inactive(),
                        MultiLevelEmitterPart.SlotEvaluation.inactive()
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );

        assertEquals(0, result.participatingCount());
        assertFalse(result.result());
    }

    @Test
    void emitWhileCraftingUsesBooleanParticipationInsteadOfThresholdComparison() {
        MultiLevelEmitterPart.AggregationResult result = MultiLevelEmitterPart.evaluateFinalResultWithParticipation(
                List.of(
                        MultiLevelEmitterPart.SlotEvaluation.participating(true),
                        MultiLevelEmitterPart.SlotEvaluation.participating(false)
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );

        assertEquals(2, result.participatingCount());
        assertFalse(result.result());
    }

    private static CapabilityAwareRuntimePart newCapabilityRuntimePart(boolean fuzzyInstalled, boolean craftingInstalled) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            CapabilityAwareRuntimePart runtime =
                    (CapabilityAwareRuntimePart) allocateInstance.invoke(unsafe, CapabilityAwareRuntimePart.class);
            runtime.setInstalledCards(fuzzyInstalled, craftingInstalled);
            runtime.applyConfiguration(1, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate capability-aware runtime part test instance", exception);
        }
    }

    private static void setConfiguredKey(MultiLevelEmitterRuntimePart runtime, int slot, AEKey key) {
        runtime.getConfig().setStack(slot, key == null ? null : new GenericStack(key, 1));
    }

    private static void reconcileCardModes(MultiLevelEmitterRuntimePart runtime) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod("reconcileCardModes");
            method.setAccessible(true);
            method.invoke(runtime);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reconcile card modes", exception);
        }
    }

    private static final class CapabilityAwareRuntimePart extends MultiLevelEmitterRuntimePart {
        private boolean fuzzyInstalled;
        private boolean craftingInstalled;

        private CapabilityAwareRuntimePart() {
            super(null);
        }

        void setInstalledCards(boolean fuzzyInstalled, boolean craftingInstalled) {
            this.fuzzyInstalled = fuzzyInstalled;
            this.craftingInstalled = craftingInstalled;
        }

        @Override
        public boolean hasFuzzyCardInstalled() {
            return fuzzyInstalled;
        }

        @Override
        public boolean hasCraftingCardInstalled() {
            return craftingInstalled;
        }
    }

    private static final class DummyKeyType extends AEKeyType {
        private static final DummyKeyType INSTANCE = new DummyKeyType();

        private DummyKeyType() {
            super(Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:test")),
                    DummyKey.class,
                    Component.literal("Test"));
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return null;
        }
    }

    private static final class DummyKey extends AEKey {
        private final String primaryKey;
        private final String variantId;
        private final int fuzzyValue;
        private final int fuzzyMaxValue;

        private DummyKey(String primaryKey, String variantId, int fuzzyValue, int fuzzyMaxValue) {
            this.primaryKey = primaryKey;
            this.variantId = variantId;
            this.fuzzyValue = fuzzyValue;
            this.fuzzyMaxValue = fuzzyMaxValue;
        }

        @Override
        public AEKeyType getType() {
            return DummyKeyType.INSTANCE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("primary", primaryKey);
            tag.putString("variant", variantId);
            tag.putInt("fuzzyValue", fuzzyValue);
            tag.putInt("fuzzyMaxValue", fuzzyMaxValue);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primaryKey;
        }

        @Override
        public int getFuzzySearchValue() {
            return fuzzyValue;
        }

        @Override
        public int getFuzzySearchMaxValue() {
            return fuzzyMaxValue;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse(
                    "chexsonsaeutils:" + primaryKey + "_" + variantId
            ));
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(primaryKey + ":" + variantId);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, net.minecraft.core.BlockPos pos) {
        }
    }
}
