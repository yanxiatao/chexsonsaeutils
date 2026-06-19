package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
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
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import com.google.common.collect.ImmutableSet;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DyeablePatternRecursivePlanningTest {

    @Test
    void colorlessRecursivePatternDoesNotStartRingReplacement()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                -1,
                List.of(new GenericStack(seed, 1L)),
                List.of(new GenericStack(seed, 2L))
        );
        TestCalculation calculation = createCalculation(
                new GenericStack(seed, 5L),
                new TestProvider(recursivePattern),
                storageWith(seed, 1L)
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 5L);

        assertNull(plan);
    }

    @Test
    void colorlessDownstreamPatternCanUseDyedRecursiveSeed()
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
        TestCalculation calculation = createCalculation(
                new GenericStack(finalProduct, 1L),
                new TestProvider(recursivePattern, downstreamPattern),
                storageWith(seed, 1L)
        );

        ICraftingPlan plan = calculation.runCraftAttempt(false, 1L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        DyeablePatternRecursivePlan recursivePlan = (DyeablePatternRecursivePlan) plan;
        assertFalse(plan.simulation());
        assertEquals(1L, recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems().get(seed));
        assertEquals(1L, plan.patternTimes().get(recursivePattern));
        assertEquals(1L, plan.patternTimes().get(downstreamPattern));
    }

    @Test
    void failedLargeRingAttemptDoesNotPoisonLaterDyeableRingAttempt()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                0xFF336699,
                List.of(
                        new GenericStack(seed, 1L),
                        new GenericStack(dust, 1L)
                ),
                List.of(new GenericStack(seed, 2L))
        );
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        storage.insert(dust, 2L, Actionable.MODULATE, null);
        TestCalculation calculation = createCalculation(
                new GenericStack(seed, 3L),
                new TestProvider(recursivePattern),
                storage
        );

        assertNull(calculation.runCraftAttempt(false, 3L));
        ICraftingPlan plan = calculation.runCraftAttempt(false, 2L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        assertEquals(2L, plan.patternTimes().get(recursivePattern));
    }

    @Test
    void failedRealRingAttemptDoesNotPoisonSimulatedDyeableRingAttempt()
            throws InterruptedException {
        AEItemKey seed = AEItemKey.of(dyedItem(Items.PAPER, 0xFF336699));
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        TestPatternDetails recursivePattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                0xFF336699,
                List.of(
                        new GenericStack(seed, 1L),
                        new GenericStack(dust, 1L)
                ),
                List.of(new GenericStack(seed, 2L))
        );
        TestStorage storage = new TestStorage();
        storage.insert(seed, 1L, Actionable.MODULATE, null);
        storage.insert(dust, 2L, Actionable.MODULATE, null);
        TestCalculation calculation = createCalculation(
                new GenericStack(seed, 3L),
                new TestProvider(recursivePattern),
                storage
        );

        assertNull(calculation.runCraftAttempt(false, 3L));
        ICraftingPlan plan = calculation.runCraftAttempt(true, 3L);

        assertInstanceOf(DyeablePatternRecursivePlan.class, plan);
        assertTrue(plan.simulation());
        assertEquals(3L, plan.patternTimes().get(recursivePattern));
        assertEquals(1L, plan.missingItems().get(dust));
    }

    private static TestCalculation createCalculation(
            GenericStack output,
            TestProvider provider,
            TestStorage storage
    ) {
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(provider);
        TestCraftingService craftingService = new TestCraftingService(providers);
        TestGrid grid = new TestGrid(new TestStorageService(storage), craftingService);
        TestRequester requester = new TestRequester(new TestGridNode(grid));
        return new TestCalculation(grid, requester, output, providers);
    }

    private static TestStorage storageWith(AEKey key, long amount) {
        TestStorage storage = new TestStorage();
        storage.insert(key, amount, Actionable.MODULATE, null);
        return storage;
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
        public <T extends GridEvent> T postEvent(T event) {
            return event;
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
        public <T extends IGridNodeService> T getService(Class<T> serviceClass) {
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
        public boolean hasFlag(GridFlags flag) {
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
        public void addGlobalStorageProvider(IStorageProvider provider) {
        }

        @Override
        public void removeGlobalStorageProvider(IStorageProvider provider) {
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
        public void addGlobalCraftingProvider(ICraftingProvider provider) {
            providers.addProvider(provider);
        }

        @Override
        public void removeGlobalCraftingProvider(ICraftingProvider provider) {
            providers.removeProvider(provider);
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
                IActionSource source
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
            List<GenericStack> inputs,
            List<GenericStack> outputs
    ) implements IPatternDetails, IPatternDetailsColorAccessor {
        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs.stream()
                    .map(TestInput::new)
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

    private record TestInput(GenericStack stack) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] { stack };
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return stack.what().equals(input);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static ItemStack dyedItem(ItemLike item, int color) {
        ItemStack stack = item.asItem().getDefaultInstance();
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color & 0x00FFFFFF, true));
        return stack;
    }
}
