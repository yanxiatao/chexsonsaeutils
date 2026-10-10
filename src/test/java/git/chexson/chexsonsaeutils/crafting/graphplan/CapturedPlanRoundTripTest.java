package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanCapture;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划夹具捕获 → 回放的往返对拍：同一请求，直接在原网格上求解与在回放夹具上
 * 求解必须得到逐字段相同的计划。这既证明捕获是忠实的（否则实机基准读数没有
 * 意义），也覆盖了模糊类还原——夹具用同一物品的不同组件重建同类变体。
 */
final class CapturedPlanRoundTripTest {

    private static AEItemKey variant(net.minecraft.world.item.Item item, int marker) {
        var stack = new ItemStack(item);
        var tag = new CompoundTag();
        tag.putInt("roundTripVariant", marker);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    @Test
    void capturedFixtureReplaysToTheSamePlan(@TempDir Path tempDir) throws Exception {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey mid = AEItemKey.of(Items.GUNPOWDER);
        // 同一物品的两个组件变体：还原后必须仍落在同一个模糊类里
        AEItemKey midVariant = variant(Items.GUNPOWDER, 3);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey leafDamaged = variant(Items.IRON_INGOT, 7);
        AEItemKey emitted = AEItemKey.of(Items.REDSTONE);

        var grid = FakeGrid.builder()
                .storage(Map.of(leaf, 40L, leafDamaged, 25L))
                .pattern(top, FakePattern.of(top, 1L, new FakeInput(mid, 2L), new FakeInput(emitted, 1L)))
                .pattern(mid, FakePattern.of(mid, 3L, FakeInput.anyVariant(leaf, 4L)))
                .pattern(midVariant, FakePattern.of(midVariant, 3L, FakeInput.anyVariant(leaf, 4L)))
                .emittable(emitted)
                .fuzzyCraftable(mid, mid, midVariant)
                .build();

        long amount = 5L;
        var capture = GraphPlanCapture.forCapture(grid.crafting, grid.storage.getCachedInventory(), top, null);
        var directGraph = GraphCompiler.compile(capture.service(), grid.storage.getCachedInventory(), top, null);
        assertFalse(directGraph.hasDeclined(), () -> "unexpected decline: " + directGraph.declineReason());
        var directResult = LinearPlanner.solve(directGraph, CalculationStrategy.REPORT_MISSING_ITEMS, amount);
        assertFalse(directResult.hasDeclined(), () -> "unexpected decline: " + directResult.declineReason());
        var directPlan = Ae2PlanConverter.toPlan(directResult, directGraph);

        Path file = capture.write(amount, tempDir);
        assertNotNull(file, "capture write failed");

        var loaded = CapturedPlanFixture.load(file);
        assertEquals(amount, loaded.amount());
        assertFalse(loaded.authoritativePerformance(), "level-less JUnit captures are semantic-only");
        assertNotNull(loaded.runtimeReplay());
        assertEquals(java.util.Set.of(top, mid, midVariant),
                loaded.craftingService().getCraftables(ignored -> true));
        assertEquals(java.util.Set.of(mid),
                loaded.craftingService().getCraftables(mid::equals));
        assertEquals(mid, loaded.craftingService().getFuzzyCraftable(mid, mid::equals));
        assertEquals(midVariant,
                loaded.craftingService().getFuzzyCraftable(mid, midVariant::equals));
        assertNull(loaded.craftingService().getFuzzyCraftable(leaf, leaf::equals),
                "非 craftable 键在 AE2 语义下没有模糊替代品");
        assertEquals(40L, loaded.runtimeReplay().storage().extract(
                leaf, 100L, Actionable.SIMULATE, IActionSource.empty()));
        assertEquals(40L, loaded.runtimeReplay().storage().extract(
                leaf, 100L, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(0L, loaded.runtimeReplay().storage().extract(
                leaf, 100L, Actionable.SIMULATE, IActionSource.empty()));
        var replayGraph = GraphCompiler.compile(loaded.craftingService(), loaded.stock(), loaded.target(), null);
        assertFalse(replayGraph.hasDeclined(), () -> "replay decline: " + replayGraph.declineReason());
        var replayResult = LinearPlanner.solve(replayGraph, CalculationStrategy.REPORT_MISSING_ITEMS,
                loaded.amount());
        assertFalse(replayResult.hasDeclined(), () -> "replay decline: " + replayResult.declineReason());
        var replayPlan = Ae2PlanConverter.toPlan(replayResult, replayGraph);

        assertEquals(directPlan.simulation(), replayPlan.simulation(), "simulation");
        assertEquals(directPlan.bytes(), replayPlan.bytes(), "bytes");
        assertEquals(directPlan.finalOutput(), replayPlan.finalOutput(), "finalOutput");
        assertEquals(FakePlanGrid.toPlain(directPlan.usedItems()),
                FakePlanGrid.toPlain(replayPlan.usedItems()), "usedItems");
        assertEquals(FakePlanGrid.toPlain(directPlan.missingItems()),
                FakePlanGrid.toPlain(replayPlan.missingItems()), "missingItems");
        assertEquals(FakePlanGrid.toPlain(directPlan.emittedItems()),
                FakePlanGrid.toPlain(replayPlan.emittedItems()), "emittedItems");
        assertEquals(patternFingerprints(directPlan.patternTimes()),
                patternFingerprints(replayPlan.patternTimes()), "patternTimes");
    }

    @Test
    void incompleteV2CapturesRemainNonAuthoritativeOrAreRejected(@TempDir Path tempDir) throws Exception {
        AEItemKey output = AEItemKey.of(Items.CLOCK);
        AEItemKey input = AEItemKey.of(Items.IRON_INGOT);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(input, 8L))
                .pattern(output, FakePattern.of(output, 1L, new FakeInput(input, 1L)))
                .build();
        GraphPlanCapture capture = GraphPlanCapture.forCapture(
                grid.crafting, grid.storage.getCachedInventory(), output, null);
        Path captured = capture.write(1L, tempDir);
        assertNotNull(captured);

        JsonObject droppedRoot = forceAuthoritative(captured);
        droppedRoot.addProperty("stockDropped", 1L);
        Path dropped = tempDir.resolve("stock-dropped.json");
        Files.writeString(dropped, droppedRoot.toString());
        assertFalse(loadRuntime(dropped).authoritativePerformance());

        JsonObject errorsRoot = forceAuthoritative(captured);
        JsonArray errors = new JsonArray();
        errors.add("capture-failure");
        errorsRoot.getAsJsonObject("integrity").add("errors", errors);
        Path errorsFile = tempDir.resolve("integrity-errors.json");
        Files.writeString(errorsFile, errorsRoot.toString());
        assertFalse(loadRuntime(errorsFile).authoritativePerformance());

        JsonObject missingRoot = forceAuthoritative(captured);
        missingRoot.remove("craftables");
        Path missingCraftables = tempDir.resolve("missing-craftables.json");
        Files.writeString(missingCraftables, missingRoot.toString());
        IOException failure = assertThrows(IOException.class, () -> loadRuntime(missingCraftables));
        assertTrue(failure.getMessage().contains("craftables"));

        Path legacy = Path.of("src", "test", "resources", "plan-captures",
                "chexsonsaeutils-plan-capture-recursive-ring.json");
        Assumptions.assumeTrue(Files.isRegularFile(legacy), "legacy capture resource missing");
        IOException legacyFailure = assertThrows(IOException.class, () -> loadRuntime(legacy));
        assertTrue(legacyFailure.getMessage().contains("legacy"));
    }

    private static JsonObject forceAuthoritative(Path captured) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(captured)).getAsJsonObject();
        root.addProperty("authoritativePerformance", true);
        root.getAsJsonObject("integrity").addProperty("complete", true);
        root.getAsJsonObject("integrity").addProperty("serviceClosureCaptured", true);
        root.getAsJsonObject("integrity").addProperty("stockComplete", true);
        root.getAsJsonObject("integrity").addProperty("validityCaptured", true);
        return root;
    }

    @Test
    void replayValidityUsesAcceptsListWhenKnown(@TempDir Path tempDir) throws Exception {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey leafDamaged = variant(Items.IRON_INGOT, 7);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(leaf, 8L))
                .pattern(top, FakePattern.of(top, 1L, FakeInput.anyVariant(leaf, 1L)))
                .build();
        GraphPlanCapture capture = GraphPlanCapture.forCapture(
                grid.crafting, grid.storage.getCachedInventory(), top, null);
        GraphCompiler.compile(capture.service(), grid.storage.getCachedInventory(), top, null);
        Path file = capture.write(1L, tempDir);
        assertNotNull(file);

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject input = root.getAsJsonArray("patterns").get(0).getAsJsonObject()
                .getAsJsonArray("inputs").get(0).getAsJsonObject();
        int leafIndex = input.getAsJsonArray("candidates").get(0).getAsJsonArray().get(0).getAsInt();
        input.addProperty("acceptsKnown", true);
        JsonArray accepts = new JsonArray();
        accepts.add(leafIndex);
        input.add("accepts", accepts);

        Path known = tempDir.resolve("accepts-known.json");
        Files.writeString(known, root.toString());
        IPatternDetails.IInput replayed = firstInput(loadRuntime(known));
        assertTrue(replayed.isValid(leaf, null), "recorded accept must stay valid");
        assertFalse(replayed.isValid(leafDamaged, null), "unlisted variant must be rejected");

        input.add("accepts", new JsonArray());
        Path strict = tempDir.resolve("accepts-strict.json");
        Files.writeString(strict, root.toString());
        IPatternDetails.IInput strictInput = firstInput(loadRuntime(strict));
        assertFalse(strictInput.isValid(leaf, null), "known-but-empty accepts reject everything");
    }

    @Test
    void replayPreservesPerCandidateRemainders(@TempDir Path tempDir) throws Exception {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey mid = AEItemKey.of(Items.GUNPOWDER);
        AEItemKey bucket = AEItemKey.of(Items.BUCKET);
        AEItemKey bowl = AEItemKey.of(Items.BOWL);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(leaf, 4L))
                .pattern(top, FakePattern.of(top, 1L, FakeInput.multiWithRemainders(
                        1L, Map.of(leaf, bucket, mid, bowl), leaf, mid)))
                .build();
        GraphPlanCapture capture = GraphPlanCapture.forCapture(
                grid.crafting, grid.storage.getCachedInventory(), top, null);
        GraphCompiler.compile(capture.service(), grid.storage.getCachedInventory(), top, null);
        Path file = capture.write(1L, tempDir);
        assertNotNull(file);

        IPatternDetails.IInput replayed = firstInput(loadRuntime(file));
        assertArrayEquals(new GenericStack[] { new GenericStack(leaf, 1), new GenericStack(mid, 1) },
                replayed.getPossibleInputs());
        assertEquals(bucket, replayed.getRemainingKey(leaf));
        assertEquals(bowl, replayed.getRemainingKey(mid));
        assertNull(replayed.getRemainingKey(top));
        assertTrue(replayed.isValid(leaf, null));
        assertTrue(replayed.isValid(mid, null));
        assertFalse(replayed.isValid(top, null));
    }

    @Test
    void fluidKeysRoundTripThroughV2Capture(@TempDir Path tempDir) throws Exception {
        AEFluidKey water = AEFluidKey.of(new FluidStack(Fluids.WATER, 1000));
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(leaf, 8L))
                .pattern(water, FakePattern.of(water, 1000L, new FakeInput(leaf, 1L)))
                .build();
        long amount = 2000L;
        var capture = GraphPlanCapture.forCapture(
                grid.crafting, grid.storage.getCachedInventory(), water, null);
        var directGraph = GraphCompiler.compile(
                capture.service(), grid.storage.getCachedInventory(), water, null);
        assertFalse(directGraph.hasDeclined(), () -> "unexpected decline: " + directGraph.declineReason());
        var directResult = LinearPlanner.solve(directGraph, CalculationStrategy.REPORT_MISSING_ITEMS, amount);
        assertFalse(directResult.hasDeclined(), () -> "unexpected decline: " + directResult.declineReason());
        var directPlan = Ae2PlanConverter.toPlan(directResult, directGraph);

        Path file = capture.write(amount, tempDir);
        assertNotNull(file);
        var loaded = loadRuntime(file);
        assertEquals(water, loaded.target());
        assertFalse(loaded.target() instanceof AEItemKey);

        var replayGraph = GraphCompiler.compile(
                loaded.craftingService(), loaded.stock(), loaded.target(), null);
        assertFalse(replayGraph.hasDeclined(), () -> "replay decline: " + replayGraph.declineReason());
        var replayResult = LinearPlanner.solve(replayGraph, CalculationStrategy.REPORT_MISSING_ITEMS,
                loaded.amount());
        assertFalse(replayResult.hasDeclined(), () -> "replay decline: " + replayResult.declineReason());
        var replayPlan = Ae2PlanConverter.toPlan(replayResult, replayGraph);

        assertEquals(directPlan.finalOutput(), replayPlan.finalOutput(), "finalOutput");
        assertEquals(FakePlanGrid.toPlain(directPlan.usedItems()),
                FakePlanGrid.toPlain(replayPlan.usedItems()), "usedItems");
        assertEquals(FakePlanGrid.toPlain(directPlan.missingItems()),
                FakePlanGrid.toPlain(replayPlan.missingItems()), "missingItems");
        assertEquals(patternFingerprints(directPlan.patternTimes()),
                patternFingerprints(replayPlan.patternTimes()), "patternTimes");
    }

    private static IPatternDetails.IInput firstInput(
            git.chexson.chexsonsaeutils.crafting.graphplan.replay.CapturedPlanReplay.Loaded loaded) {
        return loaded.craftingService().getCraftingFor(loaded.target())
                .iterator().next().getInputs()[0];
    }

    /** 样板以 definition/输出/输入指纹比较：直连与回放的样板对象身份不同，KeyCounter 也无 equals。 */
    private static Map<String, Long> patternFingerprints(Map<IPatternDetails, Long> patterns) {
        Map<String, Long> fingerprints = new java.util.HashMap<>();
        for (var entry : patterns.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            StringBuilder fingerprint = new StringBuilder();
            fingerprint.append(pattern.getDefinition()).append("|out=");
            for (GenericStack output : pattern.getOutputs()) {
                fingerprint.append(output.what()).append(':').append(output.amount()).append(',');
            }
            fingerprint.append("|in=");
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                fingerprint.append(input.getMultiplier()).append('[');
                for (GenericStack candidate : input.getPossibleInputs()) {
                    fingerprint.append(candidate.what()).append(':').append(candidate.amount()).append(',');
                }
                fingerprint.append("];");
            }
            fingerprints.merge(fingerprint.toString(), entry.getValue(), Math::addExact);
        }
        return fingerprints;
    }

    private static git.chexson.chexsonsaeutils.crafting.graphplan.replay.CapturedPlanReplay.Loaded loadRuntime(
            Path file) throws IOException {
        return git.chexson.chexsonsaeutils.crafting.graphplan.replay.CapturedPlanReplay.load(
                file, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
