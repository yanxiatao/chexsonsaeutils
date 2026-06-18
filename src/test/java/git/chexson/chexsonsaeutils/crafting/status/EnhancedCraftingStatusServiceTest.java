package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import appeng.menu.me.common.IncrementalUpdateHelper;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EnhancedCraftingStatusServiceTest {

    @Test
    void attachesBlockedAmountsToMatchingStatusEntries() {
        AEItemKey output = AEItemKey.of(Items.CRAFTING_TABLE);
        TestStatusEntry entry = new TestStatusEntry(1L, output, 0L, 0L, 4L);
        CraftingStatus status = new CraftingStatus(true, 0L, 0L, 0L, List.of(entry), false);
        TestBlockedTracker tracker = new TestBlockedTracker(Map.of(output, 3L));

        EnhancedCraftingStatusService.attachBlockedAmounts(status, tracker);

        assertEquals(3L, entry.chexsonsaeutils$blockedAmount());
    }

    @Test
    void attachesBlockedAmountsToIncrementalEntriesBySerial() {
        AEItemKey output = AEItemKey.of(Items.CRAFTING_TABLE);
        IncrementalUpdateHelper changes = new IncrementalUpdateHelper();
        long serial = changes.getOrAssignSerial(output);
        TestStatusEntry entry = new TestStatusEntry(serial, null, 0L, 0L, 4L);
        CraftingStatus status = new CraftingStatus(false, 0L, 0L, 0L, List.of(entry), false);
        TestBlockedTracker tracker = new TestBlockedTracker(Map.of(output, 3L));

        EnhancedCraftingStatusService.attachBlockedAmounts(status, tracker, changes);

        assertEquals(3L, entry.chexsonsaeutils$blockedAmount());
    }

    @Test
    void copiesBlockedAmountsAfterClientIncrementalMerge() {
        AEItemKey output = AEItemKey.of(Items.CRAFTING_TABLE);
        TestStatusEntry mergedEntry = new TestStatusEntry(1L, output, 0L, 0L, 4L);
        TestStatusEntry updateEntry = new TestStatusEntry(1L, null, 0L, 0L, 4L);
        updateEntry.chexsonsaeutils$setBlockedAmount(3L);
        CraftingStatus merged = new CraftingStatus(true, 0L, 0L, 0L, List.of(mergedEntry), false);
        CraftingStatus update = new CraftingStatus(false, 0L, 0L, 0L, List.of(updateEntry), false);

        EnhancedCraftingStatusService.copyBlockedAmountsBySerial(merged, update);

        assertEquals(3L, mergedEntry.chexsonsaeutils$blockedAmount());
    }

    @Test
    void attachesPatternTimesByPatternOutput() {
        AEItemKey output = AEItemKey.of(Items.CRAFTING_TABLE);
        TestPlanSummaryEntry entry = new TestPlanSummaryEntry(output, 0L, 0L, 7L);
        CraftingPlanSummary summary = new CraftingPlanSummary(128L, false, List.of(entry));
        TestPatternDetails firstPattern = new TestPatternDetails(AEItemKey.of(Items.STICK), output, 1L);
        TestPatternDetails secondPattern = new TestPatternDetails(AEItemKey.of(Items.OAK_PLANKS), output, 1L);
        ICraftingPlan plan = plan(Map.of(firstPattern, 2L, secondPattern, 5L));

        EnhancedCraftingStatusService.attachPatternTimes(summary, plan);

        assertEquals(List.of(5L, 2L), EnhancedCraftingStatusService.sortedPatternTimes(
                entry.chexsonsaeutils$patternTimes(),
                2
        ));
    }

    @Test
    void patternTimesAreSortedDescendingForDisplay() {
        assertEquals(List.of(9L, 5L), EnhancedCraftingStatusService.sortedPatternTimes(List.of(5L, 9L, 1L), 2));
    }

    @Test
    void slotAmountFormatterUsesCompactSuffix() {
        assertEquals("1.5K", EnhancedCraftingStatusFormatting.formatAmount(
                1_500L,
                appeng.api.stacks.AmountFormat.SLOT
        ));
    }

    private static ICraftingPlan plan(Map<IPatternDetails, Long> patternTimes) {
        return new TestCraftingPlan(
                new GenericStack(AEItemKey.of(Items.CRAFTING_TABLE), 1L),
                128L,
                false,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                patternTimes
        );
    }

    private static final class TestStatusEntry extends CraftingStatusEntry implements EnhancedCraftingStatusEntry {
        private long blockedAmount;

        private TestStatusEntry(long serial, @Nullable AEKey what, long storedAmount, long activeAmount,
                long pendingAmount) {
            super(serial, what, storedAmount, activeAmount, pendingAmount);
        }

        @Override
        public long chexsonsaeutils$blockedAmount() {
            return blockedAmount;
        }

        @Override
        public void chexsonsaeutils$setBlockedAmount(long blockedAmount) {
            this.blockedAmount = blockedAmount;
        }
    }

    private static final class TestPlanSummaryEntry extends CraftingPlanSummaryEntry
            implements EnhancedCraftingPlanSummaryEntry {
        private List<Long> patternTimes = List.of();

        private TestPlanSummaryEntry(AEKey what, long missingAmount, long storedAmount, long craftAmount) {
            super(what, missingAmount, storedAmount, craftAmount);
        }

        @Override
        public List<Long> chexsonsaeutils$patternTimes() {
            return patternTimes;
        }

        @Override
        public void chexsonsaeutils$setPatternTimes(List<Long> patternTimes) {
            this.patternTimes = List.copyOf(patternTimes);
        }
    }

    private record TestBlockedTracker(Map<AEKey, Long> blockedAmounts) implements EnhancedCraftingBlockedTracker {
        @Override
        public void chexsonsaeutils$clearBlockedTasks() {
        }

        @Override
        public long chexsonsaeutils$blockedAmount(AEKey what) {
            return blockedAmounts.getOrDefault(what, 0L);
        }

        @Override
        public KeyCounter chexsonsaeutils$blockedTasks() {
            return new KeyCounter();
        }
    }

    private record TestCraftingPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes
    ) implements ICraftingPlan {
    }

    private record TestPatternDetails(AEItemKey definition, AEKey output, long amount) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(output, amount));
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
