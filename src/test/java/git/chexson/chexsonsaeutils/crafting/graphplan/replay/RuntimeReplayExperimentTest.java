package git.chexson.chexsonsaeutils.crafting.graphplan.replay;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanCapture;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 离线复跑 runtime 回放实验的调度与统计口径：轮换、cold/warmup/sample、失败率与语义一致性。 */
class RuntimeReplayExperimentTest {

    @Test
    void experimentRotatesPlannersAndSeparatesFailures(@TempDir Path tempDir) throws Exception {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey mid = AEItemKey.of(Items.GUNPOWDER);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(leaf, 40L))
                .pattern(top, FakePattern.of(top, 1L, new FakeInput(mid, 2L)))
                .pattern(mid, FakePattern.of(mid, 1L, new FakeInput(leaf, 1L)))
                .build();
        GraphPlanCapture capture = GraphPlanCapture.forCapture(
                grid.crafting, grid.storage.getCachedInventory(), top, null);
        GraphCompiler.compile(capture.service(), grid.storage.getCachedInventory(), top, null);
        Path file = capture.write(3L, tempDir);
        assertNotNull(file);

        CapturedPlanReplay.Loaded fixture = CapturedPlanReplay.load(file,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        RuntimeReplayRunner.Experiment experiment = RuntimeReplayRunner.runExperiment(
                fixture,
                List.of(RuntimeReplayRunner.Planner.LINEAR, RuntimeReplayRunner.Planner.NEOECOAE),
                null, 1, 3).join();

        assertEquals(10, experiment.executionOrder().size());
        Map<String, List<String>> rounds = new HashMap<>();
        for (String entry : experiment.executionOrder()) {
            String[] parts = entry.split(":");
            rounds.computeIfAbsent(parts[0] + ":" + parts[1], ignored -> new ArrayList<>())
                    .add(parts[2]);
        }
        assertEquals(5, rounds.size(), "cold + 1 warmup + 3 sample rounds");
        for (List<String> round : rounds.values()) {
            assertEquals(2, round.size());
            assertTrue(round.contains("linear"));
            assertTrue(round.contains("neoecoae"));
        }

        RuntimeReplayRunner.PlannerStatistics linear = statistics(experiment,
                RuntimeReplayRunner.Planner.LINEAR);
        assertEquals(3, linear.samples());
        assertEquals(0, linear.failures());
        assertEquals(0.0, linear.failureRate());
        assertNull(linear.error());
        assertTrue(linear.coldNanos() >= 0L);
        assertTrue(linear.p50Nanos() >= 0L);
        assertTrue(linear.p95Nanos() >= 0L);
        assertTrue(linear.maxNanos() >= 0L);
        assertTrue(linear.calculationP50Nanos() >= 0L);
        assertTrue(linear.compileP50Nanos() >= 0L);
        assertTrue(linear.solveP50Nanos() >= 0L);
        assertTrue(linear.retentionP50Nanos() >= 0L);
        assertTrue(linear.convertP50Nanos() >= 0L);
        assertTrue(linear.summaryP50Nanos() >= 0L);
        assertTrue(linear.semanticConsistent(), "linear samples must be semantically stable");
        assertNotNull(linear.semanticSummary());

        RuntimeReplayRunner.PlannerStatistics neoEco = statistics(experiment,
                RuntimeReplayRunner.Planner.NEOECOAE);
        assertEquals(3, neoEco.samples());
        assertEquals(3, neoEco.failures());
        assertEquals(1.0, neoEco.failureRate());
        assertEquals(-1L, neoEco.coldNanos());
        assertEquals(-1L, neoEco.p50Nanos());
        assertNull(neoEco.semanticSummary());
        assertFalse(neoEco.semanticConsistent());
        assertNotNull(neoEco.error());
        assertTrue(neoEco.error().contains("NeoECOAE"), neoEco.error());

        for (RuntimeReplayRunner.Planner planner : RuntimeReplayRunner.Planner.values()) {
            switch (planner) {
                case AE2_NATIVE, GRAPH, FROZEN_ROUTE, NEOECOAE -> assertTrue(planner.productionEntry(),
                        planner + " must be classified as a production entry");
                case LINEAR, AGGREGATE, VM -> assertFalse(planner.productionEntry(),
                        planner + " must be classified as an independent solver");
            }
        }
    }

    private static RuntimeReplayRunner.PlannerStatistics statistics(
            RuntimeReplayRunner.Experiment experiment,
            RuntimeReplayRunner.Planner planner
    ) {
        return experiment.statistics().stream()
                .filter(statistics -> statistics.planner() == planner)
                .findFirst()
                .orElseThrow();
    }
}
