package git.chexson.chexsonsaeutils.crafting.plannercompare.frozen;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.CapturedPlanFixture;
import git.chexson.chexsonsaeutils.crafting.speedplan.FrozenRoutePlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立进程运行；不加载或调用其他规划方案。 */
final class FrozenRoutePlannerBenchmarkTest {
    private static volatile long blackhole;

    @Test
    void benchmarkColdCompileAndWarmSolve() throws Exception {
        CapturedPlanFixture.Loaded fixture = loadFixture();
        var planner = new FrozenRoutePlanner();

        long started = System.nanoTime();
        var program = planner.compile(fixture.craftingService(), fixture.target(), null);
        long coldCompileNs = System.nanoTime() - started;

        FrozenRoutePlanner.SolveResult result = null;
        for (int i = 0; i < 2_000; i++) {
            result = planner.solve(program, fixture.stock(), fixture.amount());
            blackhole ^= result.plan().bytes();
        }

        int samples = 5_000;
        long[] timings = new long[samples];
        for (int i = 0; i < samples; i++) {
            started = System.nanoTime();
            result = planner.solve(program, fixture.stock(), fixture.amount());
            timings[i] = System.nanoTime() - started;
            blackhole ^= result.stats().operations();
        }
        Arrays.sort(timings);
        assertNotNull(result);
        var stats = result.stats();
        System.out.printf(
                "PLANNER_RESULT scheme=FROZEN coldCompileNs=%d warmP50Ns=%d warmP95Ns=%d "
                        + "warmP99Ns=%d operations=%d tasks=%d missingKinds=%d%n",
                coldCompileNs, percentile(timings, 50), percentile(timings, 95),
                percentile(timings, 99), stats.operations(), stats.tasks(), stats.missingKinds());
        assertTrue(stats.operations() > 0L);
    }

    private static CapturedPlanFixture.Loaded loadFixture() throws Exception {
        Path directory = Path.of("src", "test", "resources", "plan-captures");
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                Path capture = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
                if (capture != null) {
                    return CapturedPlanFixture.load(capture);
                }
            }
        }
        AEItemKey target = AEItemKey.of(Items.CLOCK);
        AEItemKey middle = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey leaf = AEItemKey.of(Items.REDSTONE);
        FakeGrid grid = FakeGrid.builder().storage(Map.of(leaf, 1_000_000L))
                .pattern(target, FakePattern.of(target, 1L, new FakeInput(middle, 4L)))
                .pattern(middle, FakePattern.of(middle, 2L, new FakeInput(leaf, 3L)))
                .build();
        return new CapturedPlanFixture.Loaded(grid.crafting, grid.storage.getCachedInventory(),
                target, 10_000L, -1, 2, 3, 0L, false, null);
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
