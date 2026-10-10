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
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> true,
                algorithm -> {
                    visited.add(algorithm);
                    return algorithm == PlanningAlgorithmId.GRAPH ? "graph-plan" : null;
                });

        assertEquals(List.of(PlanningAlgorithmId.GRAPH), visited);
        assertEquals(PlanningAlgorithmId.GRAPH, selected.algorithm());
        assertEquals("graph-plan", selected.value());
    }

    /**
     * 判据（删 FAST 档）：默认序就是「图 + 原生树」，图那一档 DECLINE（交回 null）之后
     * 直接落到原生树——序里没有任何中间算法档，期望写死。
     */
    @Test
    void graphDeclineFallsStraightThroughToNativeTreeInDefaultOrder() {
        List<PlanningAlgorithmId> tried = new java.util.ArrayList<>();

        var selected = OrderedPlanningSelector.select(
                // 配置侧交回的是串名册；顺位由这里原样 parse 成枚举，一名对一枚、不借 Set 也不重排。
                git.chexson.chexsonsaeutils.config.PlanningServerConfig.DEFAULT_ORDER.stream()
                        .map(PlanningAlgorithmId::parse)
                        .flatMap(java.util.Optional::stream)
                        .toList(),
                algorithm -> true,
                algorithm -> {
                    tried.add(algorithm);
                    return algorithm == PlanningAlgorithmId.NATIVE ? "native-plan" : null;
                });

        assertEquals(List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE), tried);
        assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());
        assertEquals("native-plan", selected.value());
    }

    @Test
    void ineligibleAndRecoverableFailuresContinue() {
        // 可恢复失败那一腿：图这一档抛异常（非终点档）⇒ 继续往下试，由终点档交回计划。
        List<PlanningAlgorithmId> visited = new java.util.ArrayList<>();

        var selected = OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> true,
                algorithm -> {
                    visited.add(algorithm);
                    if (algorithm == PlanningAlgorithmId.GRAPH) {
                        throw new IllegalStateException("decline");
                    }
                    return algorithm == PlanningAlgorithmId.NATIVE ? "native-plan" : null;
                });

        assertEquals(List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE), visited);
        assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());

        // 不许入围那一腿：图被 eligibility 挡在门外 ⇒ 一次都不试，直接由终点档交回。
        List<PlanningAlgorithmId> tried = new java.util.ArrayList<>();
        var fellThrough = OrderedPlanningSelector.select(
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> algorithm != PlanningAlgorithmId.GRAPH,
                algorithm -> {
                    tried.add(algorithm);
                    return "native-plan";
                });

        assertEquals(List.of(PlanningAlgorithmId.NATIVE), tried);
        assertEquals(PlanningAlgorithmId.NATIVE, fellThrough.algorithm());
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
                List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                algorithm -> false,
                algorithm -> "unreachable"));
    }
}
