package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provenance must resolve by plan identity: AE2's {@code CraftingPlan} is a
 * record with value equality, and the formal-machine aggregation replaces the
 * tagged plan with a rewritten instance, so both the identity semantics and the
 * explicit hand-off are load bearing for the confirm dialog title.
 */
class PlanningProvenanceTest {

    @Test
    void resolvesTaggedPlanAndIgnoresValueEqualTwin() {
        StubPlan tagged = new StubPlan();
        StubPlan twin = new StubPlan();
        assertEquals(tagged, twin);

        PlanningProvenance.tag(tagged, PlanningProvenance.GRAPH, System.nanoTime() - 5_000_000L);

        PlanningProvenance.Info info = PlanningProvenance.peek(tagged);
        assertEquals(PlanningProvenance.GRAPH, info.solver());
        assertTrue(info.millis() >= 4L);
        assertNull(PlanningProvenance.peek(twin));
    }

    @Test
    void peekIsNonDestructive() {
        StubPlan plan = new StubPlan();
        PlanningProvenance.tag(plan, PlanningProvenance.FAST, System.nanoTime());

        assertSame(PlanningProvenance.peek(plan), PlanningProvenance.peek(plan));
    }

    @Test
    void canExtendTimingToPacketDeliveryBoundary() {
        StubPlan plan = new StubPlan();
        long started = System.nanoTime() - 8_000_000L;
        PlanningProvenance.tag(plan, PlanningProvenance.GRAPH, started);

        PlanningProvenance.Info info = PlanningProvenance.peek(plan);
        assertTrue(info.calculationCompletedNanos() >= started);
        assertTrue(info.elapsedMillisAt(info.calculationCompletedNanos() + 4_000_000L) >= 12L);
    }

    @Test
    void recursiveWrapperResolvesThroughDelegate() {
        StubPlan delegate = new StubPlan();
        StubRecursivePlan wrapper = new StubRecursivePlan(delegate);

        PlanningProvenance.tag(wrapper, PlanningProvenance.GRAPH, System.nanoTime());

        assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(delegate).solver());
        assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(wrapper).solver());
    }

    /** Value-equal stub mimicking AE2's {@code CraftingPlan} record. */
    private static class StubPlan implements ICraftingPlan {

        private final KeyCounter used = new KeyCounter();

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
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter usedItems() {
            return this.used;
        }

        @Override
        public KeyCounter emittedItems() {
            return this.used;
        }

        @Override
        public KeyCounter missingItems() {
            return this.used;
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StubPlan;
        }

        @Override
        public int hashCode() {
            return StubPlan.class.hashCode();
        }
    }

    private static final class StubRecursivePlan extends StubPlan implements RecursivePlanContract {

        private final ICraftingPlan delegate;

        StubRecursivePlan(ICraftingPlan delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return true;
        }

        @Override
        public ICraftingPlan chexsonsaeutils$getDelegate() {
            return this.delegate;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StubRecursivePlan;
        }

        @Override
        public int hashCode() {
            return StubRecursivePlan.class.hashCode();
        }
    }
}
