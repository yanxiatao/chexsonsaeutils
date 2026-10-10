package git.chexson.chexsonsaeutils.client.gui.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FixedPlanningAlgorithmOrderTest {

    @Test
    void movesItemsInsideOrder() {
        var order = FixedPlanningAlgorithmOrder.defaults();

        assertTrue(order.moveDown(PlanningAlgorithm.GRAPH));
        assertEquals(List.of(
                PlanningAlgorithm.NATIVE_AE2,
                PlanningAlgorithm.GRAPH
        ), order.order());
    }

    @Test
    void refusesMovesBeyondBoundariesOrUnknownMember() {
        var order = FixedPlanningAlgorithmOrder.defaults();

        assertFalse(order.moveUp(PlanningAlgorithm.GRAPH));
        assertFalse(order.moveDown(PlanningAlgorithm.NATIVE_AE2));
        assertFalse(order.move(-1, 0));
        assertFalse(order.move(0, 2));
    }

    @Test
    void rejectsMissingAndDuplicateItems() {
        assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(
                List.of(PlanningAlgorithm.GRAPH)));
        assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(List.of(
                PlanningAlgorithm.GRAPH,
                PlanningAlgorithm.GRAPH
        )));
    }

    @Test
    void protocolMappingIsExplicitAndComplete() {
        assertEquals(
                git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId.values().length,
                PlanningAlgorithm.values().length
        );
        for (PlanningAlgorithm algorithm : PlanningAlgorithm.values()) {
            assertEquals(algorithm, PlanningAlgorithm.fromProtocolId(algorithm.protocolId()));
            assertEquals(algorithm.protocolId().name(), algorithm.id());
        }
    }

    @Test
    void snapshotRejectsNegativeRevision() {
        assertThrows(IllegalArgumentException.class, () -> new PlanningAlgorithmOrderSnapshot(
                -1L,
                false,
                FixedPlanningAlgorithmOrder.defaultOrder()
        ));
    }

    @Test
    void restoreDefaultsAndSnapshotKeepRevisionAndReadOnlyState() {
        var order = FixedPlanningAlgorithmOrder.defaults();
        order.moveDown(PlanningAlgorithm.GRAPH);
        assertFalse(order.isDefault());

        order.restoreDefaults();
        var snapshot = order.snapshot(42L, false);

        assertTrue(order.isDefault());
        assertEquals(42L, snapshot.revision());
        assertFalse(snapshot.editable());
        assertTrue(order.hasSameOrder(snapshot));
    }
}
