package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParallelCraftingCpuLogicContinuationTest {
    @Test
    void nativeWrapperUsedForContinuationDoesNotExposeMissingInitialItems() {
        AEItemKey used = AEItemKey.of(Items.OAK_PLANKS);
        AEItemKey missing = AEItemKey.of(Items.STICK);
        ICraftingPlan plan = plan(counter(used, 4L), counter(missing, 2L));

        ICraftingPlan nativePlan = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);

        assertTrue(nativePlan.usedItems().isEmpty());
        assertTrue(nativePlan.missingItems().isEmpty());
    }

    @Test
    void seedsMissingInitialItemsIntoParallelWaitingTracker() {
        AEItemKey missing = AEItemKey.of(Items.STICK);
        ICraftingPlan plan = plan(new KeyCounter(), counter(missing, 3L));
        ParallelExecutingCraftingJob job = new ParallelExecutingCraftingJob(
                CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan),
                ignored -> {
                },
                new CraftingLink(
                        CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                        new TestCraftingCpu()
                ),
                null
        );
        List<AEKey> changedKeys = new ArrayList<>();

        ParallelCraftingCpuLogic.seedInitialWaitingFor(job, plan.missingItems(), changedKeys::add);

        assertEquals(3L, job.waitingFor.extract(missing, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
        assertEquals(1, changedKeys.size());
        assertEquals(missing, changedKeys.getFirst());
        assertTrue(job.timeTracker.getStartItemCount() > 0L);
    }

    @Test
    void extractsAvailableUsedAndMissingInitialItemsForParallelContinuation() {
        AEItemKey used = AEItemKey.of(Items.OAK_PLANKS);
        AEItemKey missing = AEItemKey.of(Items.STICK);
        ICraftingPlan plan = plan(counter(used, 4L), counter(missing, 3L));
        TestStorage storage = new TestStorage();
        storage.insert(used, 4L, Actionable.MODULATE, null);
        storage.insert(missing, 1L, Actionable.MODULATE, null);
        ListCraftingInventory cpuInventory = new ListCraftingInventory(ignored -> {
        });

        KeyCounter missingInitialItems = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                plan,
                new TestGrid(new TestStorageService(storage)),
                cpuInventory,
                null
        );

        assertEquals(4L, cpuInventory.extract(used, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(1L, cpuInventory.extract(missing, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(2L, missingInitialItems.get(missing));
        assertEquals(0L, storage.get(used));
        assertEquals(0L, storage.get(missing));
    }

    @Test
    void recursiveFinalOutputIsNotCompleteWhileWaitingForMoreIntermediateOutputs() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        ICraftingPlan plan = recursivePlan(seed, 5L, 5L);
        ParallelExecutingCraftingJob job = new ParallelExecutingCraftingJob(
                plan,
                ignored -> {
                },
                new CraftingLink(
                        CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                        new TestCraftingCpu()
                ),
                null
        );

        job.waitingFor.insert(seed, 2L, Actionable.MODULATE);
        job.waitingFor.extract(seed, 1L, Actionable.MODULATE);

        assertEquals(5L, job.remainingAmount);
        assertEquals(1L, job.waitingFor.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(!ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));

        job.waitingFor.extract(seed, 1L, Actionable.MODULATE);

        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));
    }

    @Test
    void recursiveInternalSeedIsTrackedWhenFinalOutputDiffers() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey finalOutput = AEItemKey.of(Items.MAP);
        ICraftingPlan plan = new TestRecursiveCraftingPlan(
                new GenericStack(finalOutput, 1L),
                1024L,
                false,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of(),
                1L,
                seed
        );
        ParallelExecutingCraftingJob job = new ParallelExecutingCraftingJob(
                plan,
                ignored -> {
                },
                new CraftingLink(
                        CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                        new TestCraftingCpu()
                ),
                null
        );

        job.waitingFor.insert(seed, 1L, Actionable.MODULATE);

        assertEquals(1L, job.dyeableRecursiveInternalItems.get(seed));
        assertTrue(!ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));

        job.waitingFor.extract(seed, 1L, Actionable.MODULATE);

        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));
    }

    private static ICraftingPlan plan(KeyCounter usedItems, KeyCounter missingItems) {
        return new TestCraftingPlan(
                new GenericStack(AEItemKey.of(Items.CRAFTING_TABLE), 1L),
                1024L,
                true,
                true,
                usedItems,
                new KeyCounter(),
                missingItems,
                Map.of()
        );
    }

    private static ICraftingPlan recursivePlan(AEItemKey finalOutput, long amount, long finalOutputAmount) {
        return new TestRecursiveCraftingPlan(
                new GenericStack(finalOutput, amount),
                1024L,
                false,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of(),
                finalOutputAmount,
                finalOutput
        );
    }

    private static KeyCounter counter(AEItemKey key, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    private record TestCraftingPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes
    ) implements ICraftingPlan {
    }

    private record TestRecursiveCraftingPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes,
            long recursiveFinalOutputAmount,
            AEKey recursiveInternalItem
    ) implements ICraftingPlan, DyeablePatternRecursivePlan {
        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return true;
        }

        @Override
        public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
            return recursiveFinalOutputAmount;
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
            KeyCounter counter = new KeyCounter();
            if (recursiveInternalItem != null) {
                counter.add(recursiveInternalItem, 1L);
            }
            return counter;
        }
    }

    private static final class TestCraftingCpu implements appeng.api.networking.crafting.ICraftingCPU {
        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public appeng.api.networking.crafting.CraftingJobStatus getJobStatus() {
            return null;
        }

        @Override
        public void cancelJob() {
        }

        @Override
        public long getAvailableStorage() {
            return 1024L;
        }

        @Override
        public int getCoProcessors() {
            return 0;
        }

        @Override
        public net.minecraft.network.chat.Component getName() {
            return null;
        }

        @Override
        public appeng.api.config.CpuSelectionMode getSelectionMode() {
            return appeng.api.config.CpuSelectionMode.ANY;
        }
    }

    private record TestGrid(IStorageService storageService) implements IGrid {
        @Override
        public <C extends IGridService> C getService(Class<C> iface) {
            if (iface == IStorageService.class) {
                return iface.cast(storageService);
            }
            throw new IllegalArgumentException("Unsupported grid service: " + iface.getName());
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
            return List.of();
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public IGridNode getPivot() {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void export(com.google.gson.stream.JsonWriter jsonWriter) throws IOException {
            jsonWriter.nullValue();
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
        public long insert(AEKey what, long amount, Actionable mode, appeng.api.networking.security.IActionSource source) {
            if (mode == Actionable.MODULATE) {
                stacks.add(what, amount);
            }
            return amount;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, appeng.api.networking.security.IActionSource source) {
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

        long get(AEKey key) {
            return stacks.get(key);
        }
    }
}
