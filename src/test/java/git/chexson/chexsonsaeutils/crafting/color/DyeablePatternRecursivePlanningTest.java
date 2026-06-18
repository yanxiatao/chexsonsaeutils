package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
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
import appeng.api.networking.crafting.ICraftingWatcherNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.crafting.inv.ListCraftingInventory;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import com.google.gson.stream.JsonWriter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DyeablePatternRecursivePlanningTest {

    @Test
    void rootSameColorRecursivePatternUsesSingleSeedInsteadOfReportingMissingLoopInputs()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(seed, 5L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 5L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(0L, plan.missingItems().get(seed));
        assertEquals(1L, plan.usedItems().get(seed));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(5L, recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount());
        assertEquals(5L, plan.patternTimes().get(recursivePattern));
        assertEquals(10L, plan.finalOutput().amount());
    }

    @Test
    void recursiveReplacementAwarePatternUsesNonPrimarySeedCandidate()
            throws InterruptedException {
        AEItemKey primary = AEItemKey.of(Items.PAPER);
        AEItemKey seed = AEItemKey.of(Items.MAP);
        AEItemKey firstInput = AEItemKey.of(Items.DIAMOND);
        AEItemKey secondInput = AEItemKey.of(Items.NETHER_BRICKS);
        ReplacementAwareProcessingPattern recursivePattern = replacementAwareRecursivePattern(
                primary,
                seed,
                List.of(
                        new GenericStack(primary, 1L),
                        new GenericStack(firstInput, 7L),
                        new GenericStack(secondInput, 1L)
                ),
                List.of(new GenericStack(seed, 2L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        storage.insert(firstInput, 35L, Actionable.MODULATE, null);
        storage.insert(secondInput, 5L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(seed, 5L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 5L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(0L, plan.missingItems().get(seed));
        assertEquals(1L, plan.usedItems().get(seed));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(35L, plan.usedItems().get(firstInput));
        assertEquals(5L, plan.usedItems().get(secondInput));
        assertEquals(5L, plan.patternTimes().get(recursivePattern));
        assertEquals(10L, plan.finalOutput().amount());
    }

    @Test
    void recursivePatternUsesPatternColorWhenRequestedOutputHasNoColor()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(seed, 5L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 5L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(0L, plan.missingItems().get(seed));
        assertEquals(1L, plan.usedItems().get(seed));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(5L, recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount());
        assertEquals(5L, plan.patternTimes().get(recursivePattern));
        assertEquals(10L, plan.finalOutput().amount());
    }

    @Test
    void recursivePatternRequestsScaledExternalInputsFromCompressedRing()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey firstInput = AEItemKey.of(Items.DIAMOND);
        AEItemKey secondInput = AEItemKey.of(Items.NETHER_BRICKS);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFF336699,
                List.of(
                        new GenericStack(seed, 1L),
                        new GenericStack(firstInput, 7L),
                        new GenericStack(secondInput, 1L)
                ),
                List.of(new GenericStack(seed, 2L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        storage.insert(firstInput, 35L, Actionable.MODULATE, null);
        storage.insert(secondInput, 5L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(seed, 5L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 5L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(0L, plan.missingItems().get(seed));
        assertEquals(1L, plan.usedItems().get(seed));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(5L, recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount());
        assertEquals(35L, plan.usedItems().get(firstInput));
        assertEquals(5L, plan.usedItems().get(secondInput));
        assertEquals(5L, plan.patternTimes().get(recursivePattern));
        assertEquals(10L, plan.finalOutput().amount());
    }

    @Test
    void chainedSameColorPlanKeepsRecursiveSeedAsInternalCatalyst()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        AEItemKey finalProduct = AEItemKey.of(dyedItem(Items.MAP, 0xFF336699));
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        TestPatternDetails downstreamPattern = new TestPatternDetails(
                AEItemKey.of(Items.REPEATER),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(finalProduct, 1L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern, downstreamPattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(finalProduct, 1L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 1L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems().get(seed));
        assertEquals(1L, plan.patternTimes().get(recursivePattern));
        assertEquals(1L, plan.patternTimes().get(downstreamPattern));
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount());
    }

    @Test
    void consumingOnlyAvailableRecursiveSeedSchedulesReserveCraft()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        AEItemKey finalProduct = AEItemKey.of(Items.DIAMOND);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        TestPatternDetails downstreamPattern = new TestPatternDetails(
                AEItemKey.of(Items.REPEATER),
                -1,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(finalProduct, 1L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(recursivePattern, downstreamPattern));
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        TestCalculation calculation = new TestCalculation(
                grid,
                requester,
                new GenericStack(finalProduct, 1L),
                providers
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 1L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems().get(seed));
        assertEquals(1L, plan.patternTimes().get(recursivePattern));
        assertEquals(1L, plan.patternTimes().get(downstreamPattern));
    }

    @Test
    void recursiveProducerCanUseReservedSeedButDownstreamConsumerCannot() {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        AEItemKey finalProduct = AEItemKey.of(dyedItem(Items.MAP, 0xFF336699));
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        TestPatternDetails downstreamPattern = new TestPatternDetails(
                AEItemKey.of(Items.REPEATER),
                0xFF336699,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(finalProduct, 1L))
        );
        KeyCounter internalItems = new KeyCounter();
        internalItems.add(seed, 1L);
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(seed, 1L, Actionable.MODULATE);

        assertFalse(DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                recursivePattern,
                internalItems,
                inventory
        ));
        assertEquals(1L, inventory.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(recursivePattern, DyeablePatternRecursiveTaskOrdering.orderedEntries(
                Map.of(recursivePattern, 1L, downstreamPattern, 1L),
                internalItems
        ).get(0).getKey());

        assertEquals(1L, inventory.extract(seed, 1L, Actionable.MODULATE));
        inventory.insert(seed, 2L, Actionable.MODULATE);
        assertEquals(2L, inventory.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertFalse(DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                downstreamPattern,
                internalItems,
                inventory
        ));

        assertEquals(1L, inventory.extract(seed, 1L, Actionable.MODULATE));
        assertEquals(1L, inventory.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                downstreamPattern,
                internalItems,
                inventory
        ));
    }

    private static final class TestCalculation extends DyeablePatternCraftingCalculation {
        private final DyeablePatternCraftingProviders providers;

        private TestCalculation(
                IGrid grid,
                ICraftingSimulationRequester requester,
                GenericStack output,
                DyeablePatternCraftingProviders providers
        ) {
            super(null, grid, requester, output, CalculationStrategy.REPORT_MISSING_ITEMS);
            this.providers = providers;
        }

        @Override
        DyeablePatternCraftingProviders getDyeableProviders(ICraftingService craftingService) {
            return providers;
        }

        @Override
        void handlePausing() {
        }
    }

    private record TestGrid(
            IStorageService storageService,
            ICraftingService craftingService
    ) implements IGrid {
        @Override
        public <C extends IGridService> C getService(Class<C> iface) {
            if (iface == IStorageService.class) {
                return iface.cast(storageService);
            }
            if (iface == ICraftingService.class) {
                return iface.cast(craftingService);
            }
            throw new IllegalArgumentException("Unsupported grid service: " + iface.getName());
        }

        @Override
        public <T extends GridEvent> T postEvent(T ev) {
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
            return List.of();
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public IGridNode getPivot() {
            return null;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public void export(JsonWriter jsonWriter) throws IOException {
            jsonWriter.nullValue();
        }
    }

    private record TestGridNode(IGrid grid) implements IGridNode {
        @Override
        public <T extends appeng.api.networking.IGridNodeService> T getService(Class<T> serviceClass) {
            return null;
        }

        @Override
        public Object getOwner() {
            return this;
        }

        @Override
        public void beginVisit(IGridVisitor visitor) {
        }

        @Override
        public IGrid getGrid() {
            return grid;
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
        public boolean hasFlag(appeng.api.networking.GridFlags flag) {
            return false;
        }

        @Override
        public int getOwningPlayerId() {
            return 0;
        }

        @Override
        public @Nullable UUID getOwningPlayerProfileId() {
            return null;
        }

        @Override
        public double getIdlePowerUsage() {
            return 0D;
        }

        @Override
        public @Nullable AEItemKey getVisualRepresentation() {
            return null;
        }

        @Override
        public appeng.api.util.AEColor getGridColor() {
            return appeng.api.util.AEColor.TRANSPARENT;
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

    private record TestRequester(IGridNode node) implements ICraftingSimulationRequester {
        @Override
        public @Nullable IActionSource getActionSource() {
            return IActionSource.empty();
        }

        @Override
        public @Nullable IGridNode getGridNode() {
            return node;
        }
    }

    private record TestStorageService(MEStorage storage) implements IStorageService {
        @Override
        public MEStorage getInventory() {
            return storage;
        }

        @Override
        public KeyCounter getCachedInventory() {
            return storage.getAvailableStacks();
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

    private static final class TestStorage implements MEStorage {
        private final KeyCounter stacks = new KeyCounter();

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.MODULATE) {
                stacks.add(what, amount);
            }
            return amount;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            long extracted = Math.min(stacks.get(what), amount);
            if (mode == Actionable.MODULATE) {
                stacks.remove(what, extracted);
            }
            return extracted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            out.addAll(stacks);
        }

        @Override
        public Component getDescription() {
            return Component.literal("test");
        }
    }

    private record TestCraftingService(
            DyeablePatternCraftingProviders providers
    ) implements ICraftingService {
        @Override
        public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return providers.getCraftingFor(whatToCraft);
        }

        @Override
        public void refreshNodeCraftingProvider(IGridNode node) {
        }

        @Override
        public void addGlobalCraftingProvider(ICraftingProvider cc) {
            providers.addProvider(cc);
        }

        @Override
        public void removeGlobalCraftingProvider(ICraftingProvider cc) {
            providers.removeProvider(cc);
        }

        @Override
        public void refreshGlobalCraftingProvider(ICraftingProvider provider) {
        }

        @Override
        public @Nullable AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            return null;
        }

        @Override
        public Future<ICraftingPlan> beginCraftingCalculation(
                net.minecraft.world.level.Level level,
                ICraftingSimulationRequester simRequester,
                AEKey craftWhat,
                long amount,
                CalculationStrategy strategy
        ) {
            throw new UnsupportedOperationException("Test crafting service does not schedule async calculations");
        }

        @Override
        public ICraftingSubmitResult submitJob(
                ICraftingPlan job,
                @Nullable ICraftingRequester requestingMachine,
                @Nullable ICraftingCPU target,
                boolean prioritizePower,
                IActionSource src
        ) {
            throw new UnsupportedOperationException("Test crafting service does not submit jobs");
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public boolean canEmitFor(AEKey what) {
            return false;
        }

        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            return providers.getCraftables(filter);
        }

        @Override
        public boolean isRequesting(AEKey what) {
            return false;
        }

        @Override
        public long getRequestedAmount(AEKey what) {
            return 0L;
        }

        @Override
        public boolean isRequestingAny() {
            return false;
        }
    }

    private record TestProvider(IPatternDetails... patterns) implements ICraftingProvider {
        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(patterns);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public Set<AEKey> getEmitableItems() {
            return Set.of();
        }

        @Override
        public int getPatternPriority() {
            return 0;
        }
    }

    private record TestPatternDetails(
            AEItemKey definition,
            int color,
            List<?> inputs,
            List<GenericStack> outputs
    ) implements IPatternDetails, IPatternDetailsColorAccessor {
        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs.stream()
                    .map(input -> input instanceof IInput testInput
                            ? testInput
                            : new TestInput((GenericStack) input))
                    .toArray(IInput[]::new);
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    private record TestInput(GenericStack... stacks) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return stacks;
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            for (GenericStack stack : stacks) {
                if (stack.what().equals(input)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static net.minecraft.world.item.ItemStack dyedItem(net.minecraft.world.level.ItemLike item, int color) {
        net.minecraft.world.item.ItemStack stack = item.asItem().getDefaultInstance();
        stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(color & 0x00FFFFFF, true));
        return stack;
    }

    private static ReplacementAwareProcessingPattern replacementAwareRecursivePattern(
            AEItemKey primary,
            AEItemKey seed,
            List<GenericStack> inputs,
            List<GenericStack> outputs
    ) {
        net.minecraft.world.item.ItemStack definitionStack =
                PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        definitionStack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(0x336699, true));
        AEItemKey definition = AEItemKey.of(definitionStack);
        ResourceLocation primaryId = primary.getId();
        ResourceLocation seedId = seed.getId();
        ProcessingSlotTagService tagService = new ProcessingSlotTagService(
                ignored -> List.of(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "recursive_test")),
                ignored -> List.of(primaryId, seedId),
                itemId -> primaryId.equals(itemId) || seedId.equals(itemId)
                        ? Set.of(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "recursive_test"))
                        : Set.of()
        );
        return new ReplacementAwareProcessingPattern(
                definition,
                List.of(new ProcessingPatternSlotReplacementRule(
                        0,
                        primaryId,
                        Set.of(),
                        Set.of(seedId)
                )),
                tagService,
                new ProcessingSlotRuleValidation(tagService)
        );
    }
}
