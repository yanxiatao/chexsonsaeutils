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

        assertTrue(order.moveDown(PlanningAlgorithm.FROZEN_ROUTE));
        assertEquals(List.of(
                PlanningAlgorithm.GRAPH,
                PlanningAlgorithm.FROZEN_ROUTE,
                PlanningAlgorithm.OPTIMIZED_TREE,
                PlanningAlgorithm.NATIVE_AE2
        ), order.order());
    }

    @Test
    void refusesMovesBeyondBoundariesOrUnknownMember() {
        var order = FixedPlanningAlgorithmOrder.defaults();

        assertFalse(order.moveUp(PlanningAlgorithm.FROZEN_ROUTE));
        assertFalse(order.moveDown(PlanningAlgorithm.NATIVE_AE2));
        assertFalse(order.move(-1, 0));
        assertFalse(order.move(0, 4));
    }

    @Test
    void dragMovePreservesAllFixedItems() {
        var order = FixedPlanningAlgorithmOrder.defaults();

        assertTrue(order.move(0, 3));
        assertEquals(List.of(
                PlanningAlgorithm.GRAPH,
                PlanningAlgorithm.OPTIMIZED_TREE,
                PlanningAlgorithm.NATIVE_AE2,
                PlanningAlgorithm.FROZEN_ROUTE
        ), order.order());
        FixedPlanningAlgorithmOrder.validate(order.order());
    }

    @Test
    void rejectsMissingAndDuplicateItems() {
        assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(
                List.of(PlanningAlgorithm.GRAPH)));
        assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(List.of(
                PlanningAlgorithm.FROZEN_ROUTE,
                PlanningAlgorithm.FROZEN_ROUTE,
                PlanningAlgorithm.OPTIMIZED_TREE,
                PlanningAlgorithm.NATIVE_AE2
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
        order.moveDown(PlanningAlgorithm.FROZEN_ROUTE);
        assertFalse(order.isDefault());

        order.restoreDefaults();
        var snapshot = order.snapshot(42L, false);

        assertTrue(order.isDefault());
        assertEquals(42L, snapshot.revision());
        assertFalse(snapshot.editable());
        assertTrue(order.hasSameOrder(snapshot));
    }
}
