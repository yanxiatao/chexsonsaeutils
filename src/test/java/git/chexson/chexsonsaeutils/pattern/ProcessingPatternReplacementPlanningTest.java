package git.chexson.chexsonsaeutils.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.pattern.replacement.PlanningReplacementSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProcessingPatternReplacementPlanningTest {
    private static final Path PROCESS_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingTreeProcessReplacementMixin.java");
    private static final Path NODE_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingTreeNodeReplacementMixin.java");
    private static final Path HELPER = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/PlanningReplacementSelector.java");
    private static final Path WRAPPER = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java");
    private static final Path MIXIN_CONFIG = resourcePath("chexsonsaeutils.mixins.json");

    @Test
    void planningMixinsAreRegistered() throws IOException {
        assertContains(MIXIN_CONFIG, "CraftingTreeProcessReplacementMixin");
        assertContains(MIXIN_CONFIG, "CraftingTreeNodeReplacementMixin");
        assertContains(PROCESS_MIXIN, "@Redirect");
        assertContains(PROCESS_MIXIN, "value = \"NEW\"");
        assertContains(PROCESS_MIXIN, "replacement != null ? replacement : what");
        assertContains(HELPER, "selectPlanningStack");
        assertContains(NODE_MIXIN, "@Inject(method = \"notRecursive\"");
        assertContains(HELPER, "getFuzzyCraftable");
    }

    @Test
    void representativeTemplatePreferenceScalesWithActualRequestedAmount() throws IOException {
        assertContains(NODE_MIXIN, "@Inject(method = \"request\", at = @At(\"HEAD\")");
        assertContains(NODE_MIXIN, "chexsonsaeutils$currentRequestedAmount = requestedAmount;");
        assertContains(NODE_MIXIN, "possibleInput.amount() * this.chexsonsaeutils$currentRequestedAmount");
    }

    @Test
    void substituteCandidateAppearsInPlanningWhenSubstituteIsAlreadyAvailable() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> false,
                key -> key.equals(substitute),
                key -> false,
                (whatToCraft, filter) -> null
        );

        assertEquals(substitute, selected);
    }

    @Test
    void originalInputStillRemainsPrimaryWhenAvailable() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> false,
                key -> key.equals(primary),
                key -> false,
                (whatToCraft, filter) -> substitute
        );

        assertEquals(primary, selected);
    }

    @Test
    void fuzzyCraftableCandidateAppearsWhenPrimaryHasNoDirectPath() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> false,
                key -> false,
                key -> false,
                (whatToCraft, filter) -> filter.matches(substitute) ? substitute : null
        );

        assertEquals(substitute, selected);
    }

    @Test
    void planningSelectorNeverReturnsIrrelevantCandidate() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        DummyKey irrelevant = new DummyKey("irrelevant");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> false,
                key -> false,
                key -> false,
                (whatToCraft, filter) -> irrelevant
        );

        assertNull(selected);
    }

    @Test
    void primaryCraftablePatternStillRemainsPrimaryWhenAvailable() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> false,
                key -> false,
                key -> key.equals(primary),
                (whatToCraft, filter) -> substitute
        );

        assertEquals(primary, selected);
    }

    @Test
    void inventoryBackedSubstituteBeatsCraftablePrimary() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (key, requiredAmount) -> key.equals(substitute) && requiredAmount == 1L,
                key -> false,
                key -> key.equals(primary),
                (whatToCraft, filter) -> null
        );

        assertEquals(substitute, selected);
    }

    @Test
    void ae2InputExtractionCanUsePureSubstituteInventory() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(substitute, 64L, appeng.api.config.Actionable.MODULATE);

        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedContainerItems = new KeyCounter();
        KeyCounter[] extracted = CraftingCpuHelper.extractPatternInputs(
                new SingleInputPattern(new SlotInput(64L, primary, substitute)),
                inventory,
                null,
                expectedOutputs,
                expectedContainerItems
        );

        assertNotNull(extracted);
        assertEquals(64L, extracted[0].get(substitute));
    }

    @Test
    void ae2InputExtractionCanCombinePrimaryAndSubstituteCounts() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(primary, 16L, appeng.api.config.Actionable.MODULATE);
        inventory.insert(substitute, 48L, appeng.api.config.Actionable.MODULATE);

        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedContainerItems = new KeyCounter();
        KeyCounter[] extracted = CraftingCpuHelper.extractPatternInputs(
                new SingleInputPattern(new SlotInput(64L, primary, substitute)),
                inventory,
                null,
                expectedOutputs,
                expectedContainerItems
        );

        assertNotNull(extracted);
        assertEquals(16L, extracted[0].get(primary));
        assertEquals(48L, extracted[0].get(substitute));
    }

    @Test
    void planningMixinsRespectReplacementAwarePossibleInputs() throws IOException {
        assertContains(WRAPPER, "matchesAnyPossibleInput");
        assertContains(NODE_MIXIN,
                "if (!ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, requestedWhat)) {");
        assertContains(NODE_MIXIN,
                "if (!ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, this.what)) {");
        assertContains(HELPER, "ReplacementAwareProcessingPattern.matchesAnyPossibleInput");
    }

    private record TestInput(GenericStack[] possibleInputs) implements IPatternDetails.IInput {
        private TestInput(AEKey... possibleKeys) {
            this(java.util.Arrays.stream(possibleKeys)
                    .map(key -> new GenericStack(key, 1L))
                    .toArray(GenericStack[]::new));
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack possibleInput : possibleInputs) {
                if (input.matches(possibleInput)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private record SlotInput(long multiplier, GenericStack[] possibleInputs) implements IPatternDetails.IInput {
        private SlotInput(long multiplier, AEKey... possibleKeys) {
            this(multiplier, java.util.Arrays.stream(possibleKeys)
                    .map(key -> new GenericStack(key, 1L))
                    .toArray(GenericStack[]::new));
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack possibleInput : possibleInputs) {
                if (input.matches(possibleInput)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private record SingleInputPattern(IPatternDetails.IInput input) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[]{input};
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[0];
        }

        @Override
        public GenericStack getPrimaryOutput() {
            return null;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }

        @Override
        public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink sink) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DummyKeyType extends AEKeyType {
        private static final DummyKeyType INSTANCE = new DummyKeyType();

        private DummyKeyType() {
            super(Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:planning-test")),
                    DummyKey.class,
                    Component.literal("PlanningTest"));
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
        private final String id;

        private DummyKey(String id) {
            this.id = id;
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
            tag.putString("id", id);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:" + id));
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DummyKey dummyKey)) {
                return false;
            }
            return Objects.equals(id, dummyKey.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
