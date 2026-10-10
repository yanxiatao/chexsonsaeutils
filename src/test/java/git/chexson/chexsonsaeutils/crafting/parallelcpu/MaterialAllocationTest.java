package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 材料账真实行为：身份、宽域、快照隔离、到货收窄及实时配额。 */
final class MaterialAllocationTest {
    private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey DEFINITION = AEItemKey.of(Items.PAPER);

    @Test
    void adoptionCopiesBothBooksAndPreservesEqualPatternIdentities() {
        var first = new Pattern(DEFINITION);
        var twin = new Pattern(DEFINITION);
        assertEquals(first, twin);
        assertNotSame(first, twin);
        var a = amount(GLASS, 10L);
        var b = amount(GLASS, 20L);
        var envelope = amount(GLASS, 3L);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        assigned.put(first, a);
        assigned.put(twin, b);
        MaterialAllocation allocation = new MaterialAllocationImpl();
        assertTrue(allocation.adopt(assigned, Map.of(first, envelope)));
        a.add(GLASS, 50L);
        envelope.add(GLASS, 50L);
        allocation.recordConsumption(first, GLASS, 4L);
        assertEquals(PlanAmount.of(10L), allocation.assignedAmount(first, GLASS));
        assertEquals(PlanAmount.of(20L), allocation.assignedAmount(twin, GLASS));
        assertEquals(PlanAmount.of(3L), allocation.envelopeAmount(first, GLASS));
        assertEquals(PlanAmount.of(6L), allocation.remainingFamilyQuota(first, GLASS));
        assertEquals(PlanAmount.of(20L), allocation.remainingFamilyQuota(twin, GLASS));
        assertEquals(2, allocation.allocatedPatternCount());
        assertEquals(MaterialAllocation.Source.PLAN, allocation.source());
    }

    @Test
    void zeroBasisDiffersFromMissingPatternOrFamily() {
        var pattern = new Pattern(DEFINITION);
        var absent = new Pattern(AEItemKey.of(Items.COMPASS));
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.registerSubmitPattern(pattern);
        assertEquals(1, allocation.allocatedPatternCount());
        assertNull(allocation.remainingFamilyQuota(pattern, GLASS));
        allocation.mintShare(pattern, GLASS, BigInteger.ZERO);
        assertTrue(allocation.hasQuotaBasis(pattern, GLASS));
        assertFalse(allocation.hasQuotaBasis(pattern, IRON));
        assertEquals(PlanAmount.ZERO, allocation.remainingFamilyQuota(pattern, GLASS));
        assertEquals(PlanAmount.ZERO, allocation.remainingFamilyQuota(pattern, IRON));
        assertNull(allocation.remainingFamilyQuota(absent, GLASS));
    }

    @Test
    void sameReservationReadsConsumptionImmediatelyAndOldRowsStayImmutable() {
        var pattern = new Pattern(DEFINITION);
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.mintShare(pattern, GLASS, BigInteger.TEN);
        var rows = allocation.rows(List.of(pattern));
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(1L));
        var tasks = Map.of((IPatternDetails) pattern, progress);
        var reservation = ParallelPlanReservation.build(tasks.entrySet(), Map.of(), allocation);
        assertEquals(10L, reservation.remainingQuota(pattern, GLASS));
        allocation.recordConsumption(pattern, GLASS, 4L);
        assertEquals(6L, reservation.remainingQuota(pattern, GLASS));
        assertTrue(rows.getFirst().eaten().isEmpty());
        assertEquals(PlanAmount.of(10L), rows.getFirst().assigned().getFirst().amount());
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
        assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().assigned().clear());
        allocation.recordConsumption(pattern, GLASS, 8L);
        assertEquals(0L, reservation.remainingQuota(pattern, GLASS));
        assertEquals(PlanAmount.of(-2L), allocation.rawFamilyBalance(pattern, GLASS));
    }

    @Test
    void wideAmountsRemainExactAcrossMintConsumptionAndSnapshots() {
        var pattern = new Pattern(DEFINITION);
        var wide = new BigInteger("18000000000000000000");
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.mintShare(pattern, GLASS, wide);
        allocation.recordConsumption(pattern, GLASS, wide.subtract(BigInteger.ONE));
        assertEquals(PlanAmount.of(wide), allocation.assignedAmount(pattern, GLASS));
        assertEquals(PlanAmount.of(1L), allocation.remainingFamilyQuota(pattern, GLASS));
        assertEquals(PlanAmount.of(wide), allocation.rows(List.of(pattern)).getFirst()
                .assigned().getFirst().amount());
    }

    @Test
    void arrivalNarrowsInExecutionOrderWithoutChangingEnvelopeLicense() {
        var first = new Pattern(DEFINITION);
        var second = new Pattern(AEItemKey.of(Items.COMPASS));
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.adopt(Map.of(first, amount(GLASS, 10L), second, amount(GLASS, 10L)),
                Map.of(first, amount(GLASS, 3L)));
        allocation.narrowArrival(List.of(second, first), GLASS,
                PlanAmount.of(12L), PlanAmount.ZERO, PlanAmount.ZERO);
        assertEquals(PlanAmount.of(10L), allocation.assignedAmount(second, GLASS));
        assertEquals(PlanAmount.of(2L), allocation.assignedAmount(first, GLASS));
        assertEquals(PlanAmount.of(3L), allocation.envelopeAmount(first, GLASS));
    }

    @Test
    void consumedOnlyPatternsAndOrphanEnvelopesRemainInDiagnosticRows() {
        var assigned = new Pattern(DEFINITION);
        var consumed = new Pattern(AEItemKey.of(Items.COMPASS));
        var orphan = new Pattern(AEItemKey.of(Items.CHEST));
        MaterialAllocation allocation = new MaterialAllocationImpl();
        allocation.adopt(Map.of(assigned, amount(GLASS, 10L)), Map.of(orphan, amount(IRON, 3L)));
        allocation.recordConsumption(consumed, GLASS, 2L);
        allocation.recordConsumption(consumed, GLASS, 1L);
        var rows = allocation.rows(List.of());
        assertEquals(3, rows.size());
        assertSame(assigned, rows.get(0).pattern());
        assertSame(orphan, rows.get(1).pattern());
        assertSame(consumed, rows.get(2).pattern());
        assertEquals(PlanAmount.of(3L), rows.get(2).eaten().getFirst().amount());
    }

    private static WideKeyCounter amount(AEKey key, long amount) {
        var counter = new WideKeyCounter();
        counter.set(key, amount);
        return counter;
    }

    private record Pattern(AEItemKey definition) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() { return definition; }
        @Override
        public IInput[] getInputs() { return new IInput[0]; }
        @Override
        public List<GenericStack> getOutputs() { return List.of(); }
    }
}
