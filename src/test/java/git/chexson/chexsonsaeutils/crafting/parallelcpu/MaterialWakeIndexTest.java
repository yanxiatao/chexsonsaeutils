package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MaterialWakeIndexTest {
    @Test
    void fourThousandStrictTiersOnlyReturnTheirExactConsumerAndCleanUp() {
        MaterialWakeIndex index = new MaterialWakeIndexImpl(pattern -> true);
        appeng.api.crafting.IPatternDetails selected = null;
        var arriving = FakePlanGrid.dyedKey(Items.PAPER, 137);
        for (int tier = 0; tier < 4000; tier++) {
            var key = FakePlanGrid.dyedKey(Items.PAPER, tier);
            var pattern = FakePattern.of(key, 1);
            index.register(pattern, Set.of(key));
            if (tier == 137) selected = pattern;
        }
        assertEquals(Set.of(selected), index.candidates(arriving));
        var replacement = AEItemKey.of(Items.GOLD_INGOT);
        index.register(selected, Set.of(replacement));
        assertTrue(index.candidates(arriving).isEmpty());
        assertEquals(Set.of(selected), index.candidates(replacement));
        index.remove(selected);
        assertTrue(index.candidates(replacement).isEmpty());
    }

    @Test
    void unknownPatternsKeepFamilyCandidatesWithoutScanningUnrelatedItems() {
        MaterialWakeIndex index = new MaterialWakeIndexImpl();
        var paper = FakePattern.of(AEItemKey.of(Items.PAPER), 1);
        var iron = FakePattern.of(AEItemKey.of(Items.IRON_INGOT), 1);
        index.register(paper, Set.of(FakePlanGrid.dyedKey(Items.PAPER, 1), FakePlanGrid.dyedKey(Items.PAPER, 2)));
        index.register(iron, Set.of(AEItemKey.of(Items.IRON_INGOT)));
        assertEquals(Set.of(paper), index.candidates(FakePlanGrid.dyedKey(Items.PAPER, 3)));
        index.remove(paper);
        assertTrue(index.candidates(FakePlanGrid.dyedKey(Items.PAPER, 3)).isEmpty());
    }

    @Test
    void diagnosticCellDoesNotFormatWideAmountsUntilItIsDisplayed() {
        var conversions = new AtomicInteger();
        var amount = new BigInteger(BigInteger.TEN.pow(4000).toByteArray()) {
            @Override public String toString() { conversions.incrementAndGet(); return super.toString(); }
        };
        var probe = new ParallelBatchExtraction.Probe();
        probe.noteMinCell(ParallelBatchExtraction.Probe.MinCellScope.HOLDER, 0, AEItemKey.of(Items.PAPER),
                BigInteger.ONE, amount, null, Long.MAX_VALUE, BigInteger.ONE, BigInteger.ONE, 5, 5);
        assertEquals(0, conversions.get());
        var described = probe.describeMinCell();
        assertTrue(conversions.get() > 0);
        assertTrue(described.contains(amount.toString()));
    }
}
