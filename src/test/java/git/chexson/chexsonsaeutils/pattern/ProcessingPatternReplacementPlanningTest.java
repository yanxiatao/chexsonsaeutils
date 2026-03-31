package git.chexson.chexsonsaeutils.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.pattern.replacement.PlanningReplacementSelector;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementGroupTemplateSelector;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private static final Path PLANNING_SELECTOR = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/PlanningReplacementSelector.java");
    private static final Path GROUP_SELECTOR = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ReplacementGroupTemplateSelector.java");
    private static final Path WRAPPER = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java");
    private static final Path MIXIN_CONFIG = resourcePath("chexsonsaeutils.mixins.json");

    @Test
    void planningMixinsAreRegistered() throws IOException {
        assertContains(MIXIN_CONFIG, "CraftingTreeProcessReplacementMixin");
        assertContains(MIXIN_CONFIG, "CraftingTreeNodeReplacementMixin");
        assertContains(PROCESS_MIXIN, "@Redirect");
        assertContains(PROCESS_MIXIN, "replacement != null ? replacement : what");
        assertContains(PLANNING_SELECTOR, "selectPlanningStack");
        assertContains(NODE_MIXIN, "ReplacementGroupTemplateSelector.selectTemplates");
        assertContains(GROUP_SELECTOR, "selectTemplates");
    }

    @Test
    void representativeTemplatePreferenceScalesWithActualRequestedAmount() throws IOException {
        assertContains(NODE_MIXIN, "@Inject(method = \"request\", at = @At(\"HEAD\")");
        assertContains(NODE_MIXIN, "chexsonsaeutils$currentRequestedAmount = requestedAmount;");
        assertContains(NODE_MIXIN, "ReplacementGroupTemplateSelector.selectTemplates");
        assertContains(NODE_MIXIN, "this.chexsonsaeutils$currentRequestedAmount");
    }

    @Test
    void replacementAwareProcessesForceSinglePatternExecution() throws IOException {
        assertContains(PROCESS_MIXIN, "@Inject(method = \"<init>\", at = @At(\"TAIL\")");
        assertContains(PROCESS_MIXIN, "this.limitQty = true;");
        assertContains(PROCESS_MIXIN, "this.details instanceof ReplacementAwareProcessingPattern");
    }

    @Test
    void substituteCandidateAppearsInPlanningWhenSubstituteIsAlreadyAvailable() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                1L,
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
                1L,
                null,
                (key, requiredAmount) -> key.equals(primary) && requiredAmount == 1L,
                key -> false,
                key -> false,
                (whatToCraft, filter) -> substitute
        );

        assertEquals(primary, selected);
    }

    @Test
    void planningSelectorRejectsReplacementWhenItCannotCoverRequestedGroups() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                2L,
                null,
                (key, requiredAmount) -> key.equals(substitute) && requiredAmount == 1L,
                key -> false,
                key -> false,
                (whatToCraft, filter) -> null
        );

        assertNull(selected);
    }

    @Test
    void inventoryBackedSubstituteBeatsCraftablePrimaryForSingleGroup() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        TestInput input = new TestInput(primary, substitute);

        AEKey selected = PlanningReplacementSelector.selectPlanningStack(
                input,
                1L,
                null,
                (key, requiredAmount) -> key.equals(substitute) && requiredAmount == 1L,
                key -> false,
                key -> key.equals(primary),
                (whatToCraft, filter) -> null
        );

        assertEquals(substitute, selected);
    }

    @Test
    void groupedTemplateSelectorPrefersPrimaryForCurrentWholeGroup() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");

        List<InputTemplate> templates = ReplacementGroupTemplateSelector.selectTemplates(
                new SlotInput(64L, primary, substitute),
                64L,
                (key, requiredAmount) -> key.equals(primary) && requiredAmount == 64L
        );

        assertEquals(1, templates.size());
        assertEquals(primary, templates.get(0).key());
        assertEquals(1L, templates.get(0).amount());
    }

    @Test
    void groupedTemplateSelectorFallsBackToSubstituteForCurrentWholeGroup() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");

        List<InputTemplate> templates = ReplacementGroupTemplateSelector.selectTemplates(
                new SlotInput(64L, primary, substitute),
                64L,
                (key, requiredAmount) -> key.equals(substitute) && requiredAmount == 64L
        );

        assertEquals(1, templates.size());
        assertEquals(substitute, templates.get(0).key());
        assertEquals(1L, templates.get(0).amount());
    }

    @Test
    void groupedTemplateSelectorReturnsEmptyWhenNoWholeGroupCandidateExists() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");

        List<InputTemplate> templates = ReplacementGroupTemplateSelector.selectTemplates(
                new SlotInput(64L, primary, substitute),
                64L,
                (key, requiredAmount) -> false
        );

        assertEquals(List.of(), templates);
    }

    @Test
    void multiRequestExtractionPrefersWholeGroupsBeforeFallbackMixing() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(primary, 96L, appeng.api.config.Actionable.MODULATE);
        inventory.insert(substitute, 96L, appeng.api.config.Actionable.MODULATE);

        SlotInput input = new SlotInput(64L, primary, substitute);
        List<InputTemplate> templates = materializeTemplates(inventory, input);
        long remaining = 128L;
        KeyCounter extracted = new KeyCounter();
        while (remaining > 0L) {
            long request = Math.min(64L, remaining);
            List<InputTemplate> wholeGroup = ReplacementGroupTemplateSelector.selectTemplates(
                    input,
                    request,
                    (key, requiredAmount) ->
                            inventory.extract(key, requiredAmount, appeng.api.config.Actionable.SIMULATE) >= requiredAmount
            );
            if (!wholeGroup.isEmpty()) {
                InputTemplate selected = wholeGroup.get(0);
                long extractedTemplates = CraftingCpuHelper.extractTemplates(inventory, selected, request);
                extracted.add(selected.key(), extractedTemplates * selected.amount());
            } else {
                for (InputTemplate template : templates) {
                    long extractedTemplates = CraftingCpuHelper.extractTemplates(inventory, template, request);
                    if (extractedTemplates > 0) {
                        extracted.add(template.key(), extractedTemplates * template.amount());
                        request -= extractedTemplates;
                        if (request == 0L) {
                            break;
                        }
                    }
                }
            }
            remaining -= 64L;
        }

        assertEquals(64L, extracted.get(primary));
        assertEquals(64L, extracted.get(substitute));
    }

    @Test
    void extractionStillFallsBackToMixingWhenNoWholeGroupCandidateExists() {
        DummyKey primary = new DummyKey("primary");
        DummyKey substitute = new DummyKey("substitute");
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(primary, 16L, appeng.api.config.Actionable.MODULATE);
        inventory.insert(substitute, 48L, appeng.api.config.Actionable.MODULATE);

        List<InputTemplate> wholeGroup = ReplacementGroupTemplateSelector.selectTemplates(
                new SlotInput(64L, primary, substitute),
                64L,
                (key, requiredAmount) ->
                        inventory.extract(key, requiredAmount, appeng.api.config.Actionable.SIMULATE) >= requiredAmount
        );

        assertEquals(List.of(), wholeGroup);

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
        assertContains(GROUP_SELECTOR, "return List.of(new InputTemplate");
    }

    private static List<InputTemplate> materializeTemplates(ListCraftingInventory inventory, IPatternDetails.IInput input) {
        List<InputTemplate> templates = new ArrayList<>();
        for (InputTemplate template : CraftingCpuHelper.getValidItemTemplates(inventory, input, null)) {
            templates.add(template);
        }
        return templates;
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
        public appeng.api.stacks.AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[]{input};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of();
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

}
