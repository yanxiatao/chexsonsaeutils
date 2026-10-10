package git.chexson.chexsonsaeutils.crafting.solve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OrderedPlanningSelectorTest {

    @Test
    void configuredOrderControlsFirstSuccessfulAttempt() {
        List<PlanningAlgorithmId> visited = new java.util.ArrayList<>();

        var selected = OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.FAST, PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> true,
                algorithm -> {
                    visited.add(algorithm);
                    return algorithm == PlanningAlgorithmId.GRAPH ? "graph-plan" : null;
                });

        assertEquals(List.of(PlanningAlgorithmId.FAST, PlanningAlgorithmId.GRAPH), visited);
        assertEquals(PlanningAlgorithmId.GRAPH, selected.algorithm());
        assertEquals("graph-plan", selected.value());
    }

    @Test
    void ineligibleAndRecoverableFailuresContinue() {
        List<PlanningAlgorithmId> visited = new java.util.ArrayList<>();

        var selected = OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.FROZEN_ROUTE, PlanningAlgorithmId.GRAPH,
                        PlanningAlgorithmId.FAST, PlanningAlgorithmId.NATIVE),
                algorithm -> algorithm != PlanningAlgorithmId.FROZEN_ROUTE,
                algorithm -> {
                    visited.add(algorithm);
                    if (algorithm == PlanningAlgorithmId.GRAPH) {
                        throw new IllegalStateException("decline");
                    }
                    return algorithm == PlanningAlgorithmId.NATIVE ? "native-plan" : null;
                });

        assertEquals(List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.FAST,
                PlanningAlgorithmId.NATIVE), visited);
        assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());
    }

    @Test
    void successfulTerminalStopsLaterEntries() {
        AtomicInteger attempts = new AtomicInteger();

        var selected = OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.NATIVE, PlanningAlgorithmId.GRAPH),
                algorithm -> true,
                algorithm -> {
                    attempts.incrementAndGet();
                    return "plan";
                });

        assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());
        assertEquals(1, attempts.get());
    }

    @Test
    void terminalFailureDoesNotFallThrough() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.NATIVE, PlanningAlgorithmId.GRAPH),
                algorithm -> true,
                algorithm -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("native failed");
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void cancellationAlwaysPropagates() {
        assertThrows(CancellationException.class, () -> OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> true,
                algorithm -> {
                    throw new CancellationException("cancelled");
                }));
    }

    @Test
    void emptyEligibleSetReturnsNull() {
        assertNull(OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.FAST),
                algorithm -> false,
                algorithm -> "unreachable"));
    }
}
