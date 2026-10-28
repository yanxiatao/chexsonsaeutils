package git.chexson.chexsonsaeutils.client.gui.config;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册与配置的行为场景；由类别测试统一调用。 */
public final class RegistrationScenarios {
    private RegistrationScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.client.gui.config",
                () -> FixedPlanningAlgorithmOrderCases.verify(temporaryRoot)
        );
    }

    /** FixedPlanningAlgorithmOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class FixedPlanningAlgorithmOrderCases {
        void movesItemsInsideOrder() {
            var order = FixedPlanningAlgorithmOrder.defaults();

            assertTrue(order.moveDown(PlanningAlgorithm.GRAPH));
            assertEquals(List.of(
                    PlanningAlgorithm.NATIVE_AE2,
                    PlanningAlgorithm.GRAPH
            ), order.order());
        }

        void refusesMovesBeyondBoundariesOrUnknownMember() {
            var order = FixedPlanningAlgorithmOrder.defaults();

            assertFalse(order.moveUp(PlanningAlgorithm.GRAPH));
            assertFalse(order.moveDown(PlanningAlgorithm.NATIVE_AE2));
            assertFalse(order.move(-1, 0));
            assertFalse(order.move(0, 2));
        }

        void rejectsMissingAndDuplicateItems() {
            assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(
                    List.of(PlanningAlgorithm.GRAPH)));
            assertThrows(IllegalArgumentException.class, () -> new FixedPlanningAlgorithmOrder(List.of(
                    PlanningAlgorithm.GRAPH,
                    PlanningAlgorithm.GRAPH
            )));
        }

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

        void snapshotRejectsNegativeRevision() {
            assertThrows(IllegalArgumentException.class, () -> new PlanningAlgorithmOrderSnapshot(
                    -1L,
                    false,
                    FixedPlanningAlgorithmOrder.defaultOrder()
            ));
        }

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.movesItemsInsideOrder", () -> new FixedPlanningAlgorithmOrderCases().movesItemsInsideOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.refusesMovesBeyondBoundariesOrUnknownMember", () -> new FixedPlanningAlgorithmOrderCases().refusesMovesBeyondBoundariesOrUnknownMember()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.rejectsMissingAndDuplicateItems", () -> new FixedPlanningAlgorithmOrderCases().rejectsMissingAndDuplicateItems()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.protocolMappingIsExplicitAndComplete", () -> new FixedPlanningAlgorithmOrderCases().protocolMappingIsExplicitAndComplete()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.snapshotRejectsNegativeRevision", () -> new FixedPlanningAlgorithmOrderCases().snapshotRejectsNegativeRevision()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FixedPlanningAlgorithmOrderCases.restoreDefaultsAndSnapshotKeepRevisionAndReadOnlyState", () -> new FixedPlanningAlgorithmOrderCases().restoreDefaultsAndSnapshotKeepRevisionAndReadOnlyState())
            );
        }
    }
}
