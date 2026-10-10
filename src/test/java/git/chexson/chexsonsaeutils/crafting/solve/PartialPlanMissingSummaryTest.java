package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 残缺计划的缺料还原：AE2 把计划申报的缺口累进"可用数量"并把 missing 写成 0，
 * 这里只把那一份挪回缺料栏，且绝不重复计 AE2 自己已经算出的缺口。
 */
class PartialPlanMissingSummaryTest {

    private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEKey REDSTONE = AEItemKey.of(Items.REDSTONE);

    private static CraftingPlanSummaryEntry entry(AEKey key, long missing, long stored, long craft) {
        return new CraftingPlanSummaryEntry(key, missing, stored, craft);
    }

    private static CraftingPlanSummary summary(List<CraftingPlanSummaryEntry> entries) {
        return new CraftingPlanSummary(1L, true, entries);
    }

    private static StubPlan plan(boolean simulation, long declaredMissing) {
        StubPlan plan = new StubPlan();
        plan.simulation = simulation;
        if (declaredMissing > 0L) {
            plan.missing.add(GLASS, declaredMissing);
        }
        return plan;
    }

    @Test
    void completePlansAreLeftAlone() {
        CraftingPlanSummary summary = summary(List.of(entry(GLASS, 0L, 100L, 5L)));
        assertSame(summary, PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(false, 7L)),
                "非残缺计划一律不碰");
    }

    @Test
    void declaredShortfallMovesOutOfStoredIntoMissing() {
        CraftingPlanSummary summary = summary(List.of(
                entry(GLASS, 0L, 1_007L, 5L),
                entry(REDSTONE, 0L, 20L, 3L)));

        CraftingPlanSummary fixed = PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(true, 7L));

        CraftingPlanSummaryEntry glass = fixed.getEntries().stream()
                .filter(e -> e.getWhat().equals(GLASS)).findFirst().orElseThrow();
        assertEquals(7L, glass.getMissingAmount(), "计划申报的缺口要上屏");
        assertEquals(1_000L, glass.getStoredAmount(), "挪出缺口后剩下的才是可用数量");
        CraftingPlanSummaryEntry redstone = fixed.getEntries().stream()
                .filter(e -> e.getWhat().equals(REDSTONE)).findFirst().orElseThrow();
        assertEquals(0L, redstone.getMissingAmount(), "没申报缺口的键不该多出缺料行");
        assertEquals(20L, redstone.getStoredAmount());
        assertEquals(1L, fixed.getUsedBytes(), "字节与 simulation 标记照抄原概要");
        assertEquals(true, fixed.isSimulation());
    }

    @Test
    void missingAlreadyComputedByAe2IsNeverDoubleCounted() {
        CraftingPlanSummary summary = summary(List.of(entry(GLASS, 3L, 1_000L, 5L)));

        assertSame(summary, PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(true, 7L)),
                "AE2 已经算出缺口的条目不参与还原");
    }

    @Test
    void saturatedShortfallStillShowsAsMissing() {
        // 超 long 的缺口经 Ae2Amounts.toLong 饱和成 Long.MAX：那一格可用数量本就无从还原，
        // 真值由确认屏的宽域改写负责，这里只保证缺料行出现、不出现负数。
        CraftingPlanSummary summary = summary(List.of(entry(GLASS, 0L, Long.MAX_VALUE, 5L)));

        CraftingPlanSummaryEntry glass = PartialPlanMissingSummary
                .revealDeclaredMissing(summary, plan(true, Long.MAX_VALUE)).getEntries().get(0);

        assertEquals(Long.MAX_VALUE, glass.getMissingAmount());
        assertEquals(0L, glass.getStoredAmount());
    }

    /** AE2 的 CraftingPlan 桩：只给本用例要读的 simulation 与缺料账。 */
    private static final class StubPlan implements ICraftingPlan {

        private final KeyCounter missing = new KeyCounter();
        private boolean simulation;

        @Override
        public GenericStack finalOutput() {
            return null;
        }

        @Override
        public long bytes() {
            return 0L;
        }

        @Override
        public boolean simulation() {
            return simulation;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter usedItems() {
            return missing;
        }

        @Override
        public KeyCounter emittedItems() {
            return missing;
        }

        @Override
        public KeyCounter missingItems() {
            return missing;
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of();
        }
    }
}
