package git.chexson.chexsonsaeutils.pattern.replacement;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 验证替换包装的供应器身份、完整投料及图规划数量。 */
public class ReplacementAwarePatternContractTest {
    public static final AEItemKey RAW = AEItemKey.of(Items.RAW_IRON);
    public static final AEItemKey ALT = AEItemKey.of(Items.RAW_GOLD);
    public static final AEItemKey OUT = AEItemKey.of(Items.IRON_INGOT);
    public static ReplacementAwarePattern wrap(IPatternDetails pattern) {
        var tag = ResourceLocation.parse("c:raw_materials");
        var tags = new ProcessingSlotTagExpander() {
            @Override public List<ResourceLocation> extractSourceTagIds(ItemStack stack) { return List.of(tag); }
            @Override public boolean sharesAnySourceTag(Set<ResourceLocation> source, ResourceLocation item) {
                return source.contains(tag) && ALT.getId().equals(item);
            }
        };
        var rule = new ProcessingPatternSlotReplacementRule(0, RAW.getId(), Set.of(), Set.of(ALT.getId()));
        return new ReplacementAwarePattern(pattern, List.of(rule), tags, new ProcessingSlotRuleValidation(tags));
    }
    @Test void providerMembershipAndHashLookupRecognizeSeparateDecodes() {
        var stack = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(stack, List.of(new GenericStack(RAW, 2)), List.of(new GenericStack(OUT, 2)));
        var first = wrap(new AEProcessingPattern(AEItemKey.of(stack)));
        var second = wrap(new AEProcessingPattern(AEItemKey.of(stack)));
        assertEquals(first, first); assertEquals(first, second); assertEquals(second, first);
        assertTrue(List.of(first).contains(second));
        assertEquals(5L, Map.of(first, 5L).get(second));
        assertNotEquals(first, first.basePattern()); assertNotEquals(first.basePattern(), first);
    }
    @Test void nativeProviderRegistryFindsAndPushesReplacementPattern() {
        var pattern = wrap(FakePlanGrid.FakePattern.crafting(OUT, 2, Map.of(RAW, 4L)));
        var delivered = new KeyCounter();
        var provider = new appeng.api.networking.crafting.ICraftingProvider() {
            @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(pattern); }
            @Override public boolean isBusy() { return false; }
            @Override public boolean pushPattern(IPatternDetails details, KeyCounter[] holders) {
                if (!getAvailablePatterns().contains(details)) return false;
                details.pushInputsToExternalInventory(holders, delivered::add);
                return true;
            }
        };
        var providers = new appeng.me.service.helpers.NetworkCraftingProviders();
        providers.addProvider(provider);
        var requested = wrap(FakePlanGrid.FakePattern.crafting(OUT, 2, Map.of(RAW, 4L)));
        var found = providers.getMediums(requested).iterator();
        assertTrue(found.hasNext());
        var holder = new KeyCounter(); holder.add(ALT, 4);
        assertTrue(found.next().pushPattern(requested, new KeyCounter[]{holder}));
        assertEquals(4, delivered.get(ALT));
        assertFalse(found.hasNext());
    }

    @Test void externalPushTransfersEntireReplacementBatch() {
        var pattern = wrap(FakePlanGrid.FakePattern.crafting(OUT, 2, Map.of(RAW, 4L)));
        var holder = new KeyCounter(); holder.add(ALT, 40);
        var delivered = new KeyCounter();
        pattern.pushInputsToExternalInventory(new KeyCounter[]{holder}, delivered::add);
        assertEquals(40, delivered.get(ALT)); assertEquals(0, delivered.get(RAW));
        assertEquals(1, pattern.getInputs()[0].getMultiplier());
        assertEquals(4, pattern.getInputs()[0].getPossibleInputs()[0].amount());
        var single = new KeyCounter(); single.add(ALT, 4);
        var sparse = pattern.resolveSparseInputs(new KeyCounter[]{single});
        assertNotNull(sparse); assertEquals(new GenericStack(ALT, 4), sparse.getFirst());
    }
    @Test void substitutesAreAllocatedInWholePatternPortions() {
        var pattern = wrap(FakePlanGrid.FakePattern.crafting(OUT, 2, Map.of(RAW, 2L)));
        var grid = FakePlanGrid.FakeGrid.builder().storage(Map.of(RAW, 7L, ALT, 4L)).pattern(OUT, pattern).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), OUT, null);
        assertFalse(graph.hasDeclined(), graph.declineReason());
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10);
        assertFalse(result.hasDeclined(), result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertFalse(plan.simulation());
        assertEquals(6, plan.usedItems().get(RAW));
        assertEquals(4, plan.usedItems().get(ALT));
        assertEquals(5L, plan.patternTimes().get(pattern));
        assertEquals(1, pattern.getInputs()[0].getMultiplier());
        assertEquals(2, pattern.getInputs()[0].getPossibleInputs()[0].amount());
    }

    private static ReplacementAwarePattern seedsPattern() {
        var source = AEItemKey.of(Items.BEETROOT_SEEDS);
        var alternative = AEItemKey.of(Items.WHEAT_SEEDS);
        var tag = ResourceLocation.parse("c:seeds");
        var tags = new ProcessingSlotTagExpander() {
            @Override public List<ResourceLocation> extractSourceTagIds(ItemStack stack) { return List.of(tag); }
            @Override public List<ProcessingSlotCandidateGroup> buildCandidateGroups(Collection<ResourceLocation> selected) {
                return List.of(new ProcessingSlotCandidateGroup(tag, List.of(source.getId(), alternative.getId())));
            }
        };
        var stack = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(stack, List.of(new GenericStack(source, 2)), List.of(new GenericStack(OUT, 2)));
        var rule = new ProcessingPatternSlotReplacementRule(0, source.getId(), Set.of(tag), Set.of());
        return new ReplacementAwarePattern(new AEProcessingPattern(AEItemKey.of(stack)), List.of(rule),
                tags, new ProcessingSlotRuleValidation(tags));
    }

    @Test void nativeExtractionLeavesOddSeedRemainderAndTakesWholeAlternativePortions() {
        var pattern = seedsPattern();
        var beetroot = AEItemKey.of(Items.BEETROOT_SEEDS);
        var wheat = AEItemKey.of(Items.WHEAT_SEEDS);
        var inventory = new git.chexson.chexsonsaeutils.bigint.WideCraftingInventory(key -> { });
        inventory.insert(beetroot, 7, appeng.api.config.Actionable.MODULATE);
        inventory.insert(wheat, 4, appeng.api.config.Actionable.MODULATE);
        long remaining = 5;
        var delivered = new KeyCounter();
        for (var candidate : pattern.getInputs()[0].getPossibleInputs()) {
            var template = new appeng.crafting.execution.InputTemplate(candidate.what(), candidate.amount());
            long extracted = appeng.crafting.execution.CraftingCpuHelper.extractTemplates(inventory, template, remaining);
            delivered.add(candidate.what(), extracted * candidate.amount());
            remaining -= extracted;
        }
        assertEquals(0, remaining);
        assertEquals(6, delivered.get(beetroot)); assertEquals(4, delivered.get(wheat));
        assertEquals(1, inventory.extract(beetroot, 100, appeng.api.config.Actionable.SIMULATE));
        assertEquals(0, inventory.extract(wheat, 100, appeng.api.config.Actionable.SIMULATE));
        var grid = FakePlanGrid.FakeGrid.builder().storage(Map.of(beetroot, 7L, wheat, 4L)).pattern(OUT, pattern).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), OUT, null);
        assertFalse(graph.hasDeclined(), graph.declineReason());
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10);
        assertFalse(result.hasDeclined(), result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertFalse(plan.simulation());
        assertEquals(6, plan.usedItems().get(beetroot)); assertEquals(4, plan.usedItems().get(wheat));
        assertEquals(5L, plan.patternTimes().get(pattern));
    }

    @Test void tooltipAddsTagOnlyToConfiguredInputAndPreservesOutputAndStyle() {
        var pattern = seedsPattern();
        var details = pattern.getTooltip(null, net.minecraft.world.item.TooltipFlag.NORMAL);
        var seed = AEItemKey.of(Items.BEETROOT_SEEDS);
        var input = net.minecraft.network.chat.Component.literal(appeng.core.localization.GuiText.With.text().getString()
                + ": 2 x " + seed.getDisplayName().getString()).withStyle(net.minecraft.ChatFormatting.BLUE);
        var output = net.minecraft.network.chat.Component.literal("2 x " + seed.getDisplayName().getString());
        var lines = new ArrayList<net.minecraft.network.chat.Component>(List.of(output, input));
        var hints = pattern.inputReplacementHints();
        assertEquals("（#c:seeds）", hints.get(seed).getString());
        git.chexson.chexsonsaeutils.client.gui.tooltip.ProcessingReplacementTooltips.appendInputHints(lines, details, hints);
        assertEquals(input.getString() + "（#c:seeds）", lines.get(1).getString());
        assertEquals(input.getStyle(), lines.get(1).getStyle());
        assertEquals(output, lines.getFirst());
        assertEquals(1, hints.size());
    }

    @Test void graphPlansFullMultiplierForReplacementPattern() {
        var pattern = wrap(FakePlanGrid.FakePattern.crafting(OUT, 2, Map.of(RAW, 4L)));
        var grid = FakePlanGrid.FakeGrid.builder().storage(Map.of(ALT, 20L)).pattern(OUT, pattern).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), OUT, null);
        assertFalse(graph.hasDeclined(), graph.declineReason());
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10);
        assertFalse(result.hasDeclined(), result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertFalse(plan.simulation()); assertEquals(20, plan.usedItems().get(ALT));
        assertEquals(5L, plan.patternTimes().get(pattern)); assertTrue(plan.missingItems().isEmpty());
    }
}
