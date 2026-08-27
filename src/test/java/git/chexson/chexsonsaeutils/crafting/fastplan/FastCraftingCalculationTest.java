package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.IGridService;
import appeng.api.networking.IGridVisitor;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IStorageProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AEColor;
import com.google.common.collect.ImmutableSet;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the parallel CPU fast planning path produces correct plans.
 *
 * <p>The fast path reuses AE2's planning tree/simulation state (so results match
 * the native algorithm) but re-drives them without per-tick time slicing or a
 * {@code TickHandler}/{@code Level} dependency. These tests build a synthetic grid
 * and assert the resulting plan, covering single-pattern, multi-branch, missing-item
 * (simulation) and large multi-branch scenarios.
 */
final class FastCraftingCalculationTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);

    private static ICraftingPlan runFast(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(output, amount), strategy, 0L);
        return calc.run();
    }

    @Test
    void singlePatternCraftProducesExpectedPlan() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(new GenericStack(IRON, 10), plan.finalOutput());
        assertEquals(10L, plan.patternTimes().get(ironFromGold));
        assertEquals(20L, plan.usedItems().get(GOLD));
        assertEquals(0L, plan.usedItems().get(IRON));
    }

    @Test
    void multiBranchUsesPatternsInOrderUntilExhausted() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, DIAMOND, 30L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertTrue(plan.multiplePaths());
        assertEquals(5L, plan.patternTimes().get(ironFromGold));
        assertEquals(10L, plan.patternTimes().get(ironFromDiamond));
        assertEquals(10L, plan.usedItems().get(GOLD));
        assertEquals(30L, plan.usedItems().get(DIAMOND));
    }

    @Test
    void insufficientIngredientsYieldSimulationPlan() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertTrue(plan.simulation());
        // 10 iron needs 20 gold; only 5 in storage, so 15 gold are reported missing.
        assertEquals(15L, plan.missingItems().get(GOLD));
    }

    @Test
    void craftLessBinarySearchFindsLargestCraftableAmount() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        // 10 gold -> 5 iron is the largest craftable amount below 32.
        assertEquals(5L, plan.finalOutput().amount());
        assertEquals(5L, plan.patternTimes().get(ironFromGold));
    }

    @Test
    void nestedCraftingChainsThroughIntermediate() {
        // IRON <- 1 GOLD ; GOLD <- 2 REDSTONE. Storage only has REDSTONE.
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .pattern(GOLD, goldFromRedstone)
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(ironFromGold));
        assertEquals(10L, plan.patternTimes().get(goldFromRedstone));
        assertEquals(20L, plan.usedItems().get(REDSTONE));
    }

    @Test
    void largeMultiBranchPlanCompletesWithinBudget() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1_000L, DIAMOND, 100_000L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        long start = System.nanoTime();
        // Generous budget so the fast path itself runs to completion.
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(IRON, 20_000), CalculationStrategy.REPORT_MISSING_ITEMS, 30_000L);
        ICraftingPlan plan = calc.run();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(20_000L, plan.finalOutput().amount());
        // 500 iron from gold (1000 gold / 2), the rest from diamond.
        assertEquals(500L, plan.patternTimes().get(ironFromGold));
        assertEquals(19_500L, plan.patternTimes().get(ironFromDiamond));
        assertTrue(elapsedMillis < 30_000L, "fast path exceeded budget: " + elapsedMillis + "ms");
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Minimal crafting pattern: outputs {@code outputCount} of {@code output} per run. */
    private static final class FakePattern implements IPatternDetails {
        private final AEItemKey definition;
        private final GenericStack output;
        private final IInput[] inputs;

        private FakePattern(AEKey output, long outputCount, Map<? extends AEKey, Long> inputs) {
            this.definition = AEItemKey.of(Items.PAPER);
            this.output = new GenericStack(output, outputCount);
            this.inputs = inputs.entrySet().stream()
                    .map(e -> (IInput) new FakeInput(e.getKey(), e.getValue()))
                    .toArray(IInput[]::new);
        }

        static FakePattern crafting(AEKey output, long outputCount, Map<? extends AEKey, Long> inputs) {
            return new FakePattern(output, outputCount, inputs);
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(output);
        }
    }

    private static final class FakeInput implements IPatternDetails.IInput {
        private final GenericStack possible;
        private final long multiplier;

        private FakeInput(AEKey key, long multiplier) {
            this.possible = new GenericStack(key, 1);
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{possible};
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return input.equals(possible.what());
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static final class FakeRequester implements ICraftingSimulationRequester {
        private final IGridNode node;

        private FakeRequester(IGridNode node) {
            this.node = node;
        }

        @Override
        public IActionSource getActionSource() {
            return IActionSource.empty();
        }

        @Override
        public IGridNode getGridNode() {
            return node;
        }
    }

    /** Minimal grid wiring a fake storage service and crafting service together. */
    private static final class FakeGrid implements IGrid {
        final FakeStorage storage;
        final FakeCraftingService crafting;
        final FakeNode node;
        final FakeRequester requester;

        private FakeGrid(Map<AEKey, Long> inventory, Map<AEKey, List<IPatternDetails>> patterns) {
            this.storage = new FakeStorage(inventory);
            this.crafting = new FakeCraftingService(patterns);
            this.node = new FakeNode(this);
            this.requester = new FakeRequester(node);
        }

        static Builder builder() {
            return new Builder();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <C extends IGridService> C getService(Class<C> iface) {
            if (iface == IStorageService.class) {
                return (C) storage;
            }
            if (iface == ICraftingService.class) {
                return (C) crafting;
            }
            throw new UnsupportedOperationException("service not provided in test: " + iface);
        }

        @Override
        public <T extends appeng.api.networking.events.GridEvent> T postEvent(T ev) {
            return ev;
        }

        @Override
        public Iterable<Class<?>> getMachineClasses() {
            return List.of();
        }

        @Override
        public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) {
            return List.of();
        }

        @Override
        public <T> Set<T> getMachines(Class<T> machineClass) {
            return Set.of();
        }

        @Override
        public <T> Set<T> getActiveMachines(Class<T> machineClass) {
            return Set.of();
        }

        @Override
        public Iterable<IGridNode> getNodes() {
            return List.of(node);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public IGridNode getPivot() {
            return node;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public void export(com.google.gson.stream.JsonWriter jsonWriter) {
        }

        static final class Builder {
            private final Map<AEKey, Long> inventory = new HashMap<>();
            private final Map<AEKey, List<IPatternDetails>> patterns = new HashMap<>();

            Builder storage(Map<AEKey, Long> inv) {
                inventory.putAll(inv);
                return this;
            }

            Builder pattern(AEKey output, IPatternDetails... pats) {
                patterns.put(output, List.of(pats));
                return this;
            }

            FakeGrid build() {
                return new FakeGrid(inventory, patterns);
            }
        }
    }

    private static final class FakeStorage implements IStorageService {
        private final KeyCounter cached = new KeyCounter();

        private FakeStorage(Map<AEKey, Long> inventory) {
            inventory.forEach(cached::add);
        }

        @Override
        public MEStorage getInventory() {
            return null;
        }

        @Override
        public KeyCounter getCachedInventory() {
            return cached;
        }

        @Override
        public void addGlobalStorageProvider(IStorageProvider cc) {
        }

        @Override
        public void removeGlobalStorageProvider(IStorageProvider cc) {
        }

        @Override
        public void refreshNodeStorageProvider(IGridNode node) {
        }

        @Override
        public void refreshGlobalStorageProvider(IStorageProvider provider) {
        }

        @Override
        public void invalidateCache() {
        }
    }

    private static final class FakeCraftingService implements ICraftingService {
        private final Map<AEKey, List<IPatternDetails>> patterns;

        private FakeCraftingService(Map<AEKey, List<IPatternDetails>> patterns) {
            this.patterns = patterns;
        }

        @Override
        public java.util.Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return patterns.getOrDefault(whatToCraft, List.of());
        }

        @Override
        public void refreshNodeCraftingProvider(IGridNode node) {
        }

        @Override
        public void addGlobalCraftingProvider(ICraftingProvider cc) {
        }

        @Override
        public void removeGlobalCraftingProvider(ICraftingProvider cc) {
        }

        @Override
        public void refreshGlobalCraftingProvider(ICraftingProvider provider) {
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            return null;
        }

        @Override
        public Future<ICraftingPlan> beginCraftingCalculation(Level level,
                ICraftingSimulationRequester simRequester, AEKey what, long amount,
                CalculationStrategy strategy) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public ICraftingSubmitResult submitJob(ICraftingPlan job, ICraftingRequester requestingMachine,
                ICraftingCPU target, boolean prioritizePower, IActionSource src) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public boolean canEmitFor(AEKey someItem) {
            return false;
        }

        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            return Set.of();
        }

        @Override
        public boolean isRequesting(AEKey what) {
            return false;
        }

        @Override
        public long getRequestedAmount(AEKey what) {
            return 0;
        }

        @Override
        public boolean isRequestingAny() {
            return false;
        }
    }

    private static final class FakeNode implements IGridNode {
        private final IGrid grid;

        private FakeNode(IGrid grid) {
            this.grid = grid;
        }

        @Override
        public IGrid getGrid() {
            return grid;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends IGridNodeService> T getService(Class<T> serviceClass) {
            return (T) grid.getService((Class) serviceClass);
        }

        @Override
        public Object getOwner() {
            return null;
        }

        @Override
        public void beginVisit(IGridVisitor visitor) {
        }

        @Override
        public ServerLevel getLevel() {
            return null;
        }

        @Override
        public Set<Direction> getConnectedSides() {
            return Set.of();
        }

        @Override
        public Map<Direction, IGridConnection> getInWorldConnections() {
            return Map.of();
        }

        @Override
        public List<IGridConnection> getConnections() {
            return List.of();
        }

        @Override
        public boolean hasGridBooted() {
            return true;
        }

        @Override
        public boolean isPowered() {
            return true;
        }

        @Override
        public boolean meetsChannelRequirements() {
            return true;
        }

        @Override
        public boolean hasFlag(GridFlags flag) {
            return false;
        }

        @Override
        public int getOwningPlayerId() {
            return -1;
        }

        @Override
        public UUID getOwningPlayerProfileId() {
            return null;
        }

        @Override
        public double getIdlePowerUsage() {
            return 0;
        }

        @Override
        public AEItemKey getVisualRepresentation() {
            return null;
        }

        @Override
        public AEColor getGridColor() {
            return AEColor.TRANSPARENT;
        }

        @Override
        public void fillCrashReportCategory(CrashReportCategory category) {
        }

        @Override
        public int getMaxChannels() {
            return 0;
        }

        @Override
        public int getUsedChannels() {
            return 0;
        }
    }
}
