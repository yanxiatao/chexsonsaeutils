package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.me.storage.NetworkStorage;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOBigOrderRequest;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOExactInventory;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import cn.dancingsnow.neoecoae.crafting.execution.batch.ECOExactBatchPlanner;
import git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoInfinityCellInventory;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 完整原始 SDK 环境下调用实际事务、网络挂载和父订单作用域。 */
final class NeoEcoExactChainSdkTest {
    @Test void mountedExactExtractionPreservesFiltersExclusionsAndActivity() {
        var source = IActionSource.empty();
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        int[] activity = {0};
        var drive = new appeng.me.storage.DriveWatcher(cell, () -> activity[0]++);
        var network = new NetworkStorage(); network.mount(0, drive);
        assertInstanceOf(git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.class, network);
        assertEquals(HUGE, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, source));
        assertEquals(HUGE, git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                network, INPUT, HUGE, Actionable.SIMULATE, source));
        assertEquals(HUGE, cell.exactAmount(INPUT)); assertEquals(1, activity[0]);
        drive.setExtractFiltering(true, true); drive.setAllowExtraction(false);
        assertEquals(BigInteger.ZERO, git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                network, INPUT, HUGE, Actionable.MODULATE, source));
        drive.setAllowExtraction(true);
        var partition = new KeyCounter(); partition.add(OUTPUT, 1);
        drive.setPartitionList(new appeng.util.prioritylist.PrecisePriorityList(partition));
        assertEquals(BigInteger.ZERO, git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                network, INPUT, HUGE, Actionable.MODULATE, source));
        drive.setWhitelist(appeng.api.config.IncludeExclude.BLACKLIST);
        try (var excluded = cn.dancingsnow.neoecoae.impl.storage.transfer.StorageExtractionExclusions.open(List.of(drive))) {
            assertEquals(BigInteger.ZERO, git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                    network, INPUT, HUGE, Actionable.MODULATE, source));
        }
        assertEquals(HUGE, cell.exactAmount(INPUT));
        assertEquals(HUGE, git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                network, INPUT, HUGE, Actionable.MODULATE, source));
        assertEquals(BigInteger.ZERO, cell.exactAmount(INPUT)); assertEquals(2, activity[0]);
    }
    private static final AEItemKey INPUT = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey REMAINDER = AEItemKey.of(Items.BUCKET);
    private static final BigInteger HUGE = BigInteger.TEN.pow(50).add(BigInteger.valueOf(7));
    private static final IPatternDetails PATTERN = new IPatternDetails() {
        public AEItemKey getDefinition() { return AEItemKey.of(Items.FURNACE); }
        public IInput[] getInputs() { return new IInput[0]; }
        public List<GenericStack> getOutputs() { return List.of(new GenericStack(OUTPUT, 3)); }
        public boolean supportsPushInputsToExternalInventory() { return false; }
    };

    @Test void realCpuReceiptRejectsOtherGridAndRestoresExactJobAfterSave() {
        var liveGrid = new java.util.concurrent.atomic.AtomicReference<appeng.api.networking.IGrid>();
        var cluster = new cn.dancingsnow.neoecoae.multiblock.cluster.NEComputationCluster(
                net.minecraft.core.BlockPos.ZERO, new net.minecraft.core.BlockPos(1, 1, 1));
        var cpu = new cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPU(
                cluster, cn.dancingsnow.neoecoae.api.ECOTier.L4) {
            @Override public appeng.api.networking.IGrid getGrid() { return liveGrid.get(); }
            @Override public net.minecraft.world.level.Level getLevel() { return null; }
        };
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var waiting = new ECOExactInventory(ignored -> {}); waiting.setEnabled(true);
        waiting.restore(Map.of(OUTPUT, HUGE));
        var data = new net.minecraft.nbt.CompoundTag(); data.putBoolean("ecoExactInventory", true);
        var job = new net.minecraft.nbt.CompoundTag();
        job.putBoolean("exactOrder", true);
        job.put("link", appeng.crafting.execution.CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false));
        job.put("finalOutput", GenericStack.writeTag(registries, new GenericStack(INPUT, 1)));
        job.putLong("remainingAmount", 1);
        job.put("waitingFor", waiting.writeToNBT(registries));
        var trackerData = new net.minecraft.nbt.CompoundTag(); trackerData.putBoolean("exactWork", true);
        var started = new net.minecraft.nbt.CompoundTag(); var completed = new net.minecraft.nbt.CompoundTag();
        for (var type : appeng.api.stacks.AEKeyTypes.getAll()) {
            started.putString(type.getId().toString(), "0"); completed.putString(type.getId().toString(), "0");
        }
        trackerData.put("exactStarted", started); trackerData.put("exactCompleted", completed);
        job.put("timeTracker", trackerData); data.put("job", job);
        cpu.getLogic().readFromNBT(data, registries);
        var grid = new EmptyGrid(); liveGrid.set(grid);
        var receiver = (git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver) cpu;
        assertEquals(BigInteger.ZERO, receiver.receive(new EmptyGrid(), OUTPUT, HUGE));
        assertEquals(HUGE, cpu.getLogic().getExactActivePreview().get(OUTPUT));
        assertEquals(HUGE, receiver.receive(grid, OUTPUT, HUGE.add(BigInteger.TEN)));
        assertEquals(HUGE, cpu.getLogic().getExactStoredPreview().get(OUTPUT));
        assertTrue(cpu.getLogic().getExactActivePreview().isEmpty());
        var saved = new net.minecraft.nbt.CompoundTag(); cpu.getLogic().writeToNBT(saved, registries);
        liveGrid.set(null); cpu.getLogic().readFromNBT(saved, registries);
        assertEquals(HUGE, cpu.getLogic().getExactStoredPreview().get(OUTPUT));
        assertEquals(HUGE.toString(), saved.getCompound("job").getCompound("timeTracker")
                .getCompound("exactCompleted").getString(OUTPUT.getType().getId().toString()));
        assertTrue(cpu.getLogic().getExactActivePreview().isEmpty());
    }

    @Test void exactCpuReceiptPreservesExcessWaitsProgressAndSavedInventory() {
        var waiting = new ECOExactInventory(ignored -> {}); waiting.setEnabled(true);
        var inventory = new ECOExactInventory(ignored -> {}); inventory.setEnabled(true);
        waiting.restore(Map.of(OUTPUT, HUGE, REMAINDER, HUGE));
        var progressData = new net.minecraft.nbt.CompoundTag();
        progressData.putBoolean("exactWork", true);
        var started = new net.minecraft.nbt.CompoundTag(); var done = new net.minecraft.nbt.CompoundTag();
        for (var type : appeng.api.stacks.AEKeyTypes.getAll()) {
            started.putString(type.getId().toString(), "0"); done.putString(type.getId().toString(), "0");
        }
        progressData.put("exactStarted", started); progressData.put("exactCompleted", done);
        var tracker = new cn.dancingsnow.neoecoae.crafting.execution.ElapsedTimeTracker(progressData);
        var progress = (NeoEcoExactCompletedWork) tracker;
        var accepted = NeoEcoExactOutputTransfer.receive(waiting, inventory, OUTPUT,
                HUGE.add(BigInteger.valueOf(31)), window -> {
                    long received = waiting.extract(OUTPUT, window, Actionable.MODULATE);
                    inventory.insert(OUTPUT, received, Actionable.MODULATE);
                    progress.chexsonsaeutils$recordCompleted(BigInteger.valueOf(received), OUTPUT.getType());
                    return received;
                }, rest -> progress.chexsonsaeutils$recordCompleted(rest, OUTPUT.getType()));
        assertEquals(HUGE, accepted); assertEquals(HUGE, inventory.amount(OUTPUT));
        assertEquals(BigInteger.ZERO, waiting.amount(OUTPUT)); assertEquals(HUGE, waiting.amount(REMAINDER));
        assertEquals(HUGE, tracker.getExactCompletedWork(OUTPUT.getType()));
        assertEquals(Long.MAX_VALUE, tracker.getCompletedWork(OUTPUT.getType()));
        var restoredProgress = new cn.dancingsnow.neoecoae.crafting.execution.ElapsedTimeTracker(tracker.writeToNBT());
        assertEquals(HUGE, restoredProgress.getExactCompletedWork(OUTPUT.getType()));
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var restored = new ECOExactInventory(ignored -> {});
        restored.readFromNBT(inventory.writeToNBT(registries), registries);
        assertEquals(HUGE, restored.amount(OUTPUT));
        assertEquals(BigInteger.ZERO, NeoEcoExactOutputTransfer.receive(waiting, inventory, REMAINDER,
                HUGE, window -> 0, rest -> fail("拒收不得计完成量")));
        assertEquals(HUGE, waiting.amount(REMAINDER));
        var network = new NetworkStorage();
        NeoEcoExactOutputTransfer.returnInventory(restored, network, IActionSource.empty());
        assertEquals(HUGE, restored.amount(OUTPUT));
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null); network.mount(0, cell);
        NeoEcoExactOutputTransfer.returnInventory(restored, network, IActionSource.empty());
        assertTrue(restored.snapshot().isEmpty()); assertEquals(HUGE, cell.exactAmount(OUTPUT));
        var stock = network.getAvailableStacks();
        var truth = git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.capture(null, stock, network);
        assertEquals(PlanAmount.of(HUGE), truth.supplyOf(OUTPUT, stock.get(OUTPUT)));
    }

    @Test void realComputationCpuUsesParentOrderCapacityAndOfflineBusKeepsInputs() {
        var cluster = new cn.dancingsnow.neoecoae.multiblock.cluster.NEComputationCluster(
                net.minecraft.core.BlockPos.ZERO, new net.minecraft.core.BlockPos(1, 1, 1));
        var cpu = new cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPU(
                cluster, cn.dancingsnow.neoecoae.api.ECOTier.L4) {
            @Override public long getAvailableStorage() { return 1; }
        };
        var wide = assertInstanceOf(git.chexson.chexsonsaeutils.crafting.contract.WideRootCraftingCpu.class, cpu);
        var plan = new CraftingPlan(new GenericStack(OUTPUT, Long.MAX_VALUE), Long.MAX_VALUE,
                false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        assertTrue(wide.canSubmitWideJob(plan));
        var bus = new cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                        net.minecraft.resources.ResourceLocation.parse("neoecoae:crafting_pattern_bus")),
                net.minecraft.core.BlockPos.ZERO, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        net.minecraft.resources.ResourceLocation.parse("neoecoae:crafting_pattern_bus")).defaultBlockState());
        var dispatcher = assertInstanceOf(git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher.class, bus);
        var pool = new appeng.crafting.inv.ListCraftingInventory(ignored -> {});
        pool.insert(INPUT, 20, Actionable.MODULATE);
        var input = new KeyCounter(); input.add(INPUT, 2);
        var output = new KeyCounter(); output.add(OUTPUT, 3);
        assertNull(dispatcher.prepareBatch(PATTERN, new KeyCounter[]{input}, output, new KeyCounter(),
                pool, 8, null, null, UUID.randomUUID()));
        assertEquals(20, pool.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test void exactBatchDebitsActualMaterialAndProducesOutputsAndContainersOnce() {
        var host = new ExactHost(true);
        var inventory = new ECOExactInventory(ignored -> {});
        inventory.setEnabled(true);
        inventory.restore(Map.of(INPUT, HUGE.multiply(BigInteger.TWO)));
        var batch = ECOExactBatchPlanner.prepare(host, context(), inventory, HUGE, Map.of());
        assertNotNull(batch);
        assertEquals(HUGE, batch.craftCount());
        assertTrue(host.buffer.isEmpty());
        assertEquals(HUGE.multiply(BigInteger.TWO), inventory.amount(INPUT));
        int[] commits = {0};
        assertTrue(batch.submit(new ECOFastPathFacade.Reservation() {
            public void commit() { commits[0]++; }
            public void refund() { fail("成功派发不应退款"); }
        }));
        assertEquals(BigInteger.ZERO, inventory.amount(INPUT));
        assertEquals(HUGE.multiply(BigInteger.valueOf(3)), host.buffer.getExact(OUTPUT));
        assertEquals(HUGE, host.buffer.getExact(REMAINDER));
        assertEquals(1, commits[0]);
        assertThrows(IllegalStateException.class, () -> batch.submit(null));
    }

    @Test void rejectedExactBatchRestoresAllMaterialAndRefundsReservation() {
        var host = new ExactHost(false);
        var inventory = new ECOExactInventory(ignored -> {});
        inventory.setEnabled(true);
        inventory.restore(Map.of(INPUT, HUGE.multiply(BigInteger.TWO)));
        var batch = ECOExactBatchPlanner.prepare(host, context(), inventory, HUGE.add(BigInteger.ONE), Map.of());
        assertNotNull(batch);
        assertEquals(HUGE, batch.craftCount());
        int[] refunds = {0};
        assertFalse(batch.submit(new ECOFastPathFacade.Reservation() {
            public void commit() { fail("拒收不应扣能"); }
            public void refund() { refunds[0]++; }
        }));
        assertEquals(HUGE.multiply(BigInteger.TWO), inventory.amount(INPUT));
        assertTrue(host.buffer.isEmpty());
        assertEquals(1, refunds[0]);
    }

    @Test void driveMountedCellAcceptsEntireAssemblerBufferAndPreservesFilters() {
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        int[] activity = {0};
        var drive = new appeng.me.storage.DriveWatcher(cell, () -> activity[0]++);
        var network = new NetworkStorage();
        network.mount(0, drive);
        var source = IActionSource.empty();
        assertEquals(HUGE, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.SIMULATE, source));
        assertEquals(BigInteger.ZERO, cell.exactAmount(INPUT));
        assertEquals(0, activity[0]);
        drive.setAllowInsertion(false);
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, source));
        drive.setAllowInsertion(true);
        var partition = new KeyCounter(); partition.add(OUTPUT, 1);
        drive.setPartitionList(new appeng.util.prioritylist.PrecisePriorityList(partition));
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, source));
        drive.setWhitelist(appeng.api.config.IncludeExclude.BLACKLIST);
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(network, OUTPUT, HUGE, Actionable.MODULATE, source));
        var buffer = new WideKeyCounter(); buffer.add(INPUT, HUGE);
        CraftingOutputRouter.storeUnrequestedBufferedOutputs(buffer, network, source, key -> false);
        assertTrue(buffer.isEmpty());
        assertEquals(HUGE, cell.exactAmount(INPUT));
        assertEquals(HUGE, ((ExactNetworkSupply) network).readExactSupply().finite().get(INPUT));
        assertEquals(1, activity[0]);
        drive.setExtractFiltering(true, true);
        partition.clear(); partition.add(INPUT, 1);
        assertTrue(((ExactNetworkSupply) network).readExactSupply().finite().isEmpty());
        drive.setWhitelist(appeng.api.config.IncludeExclude.WHITELIST);
        assertEquals(HUGE, ((ExactNetworkSupply) network).readExactSupply().finite().get(INPUT));
        drive.setAllowExtraction(false);
        assertTrue(((ExactNetworkSupply) network).readExactSupply().finite().isEmpty());
        assertEquals(HUGE, cell.exactAmount(INPUT));
        network.unmount(drive);
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, source));
    }

    @Test void exactDriveInsertionUpdatesActivityAndHandlerVoidRules() {
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        int[] activity = {0};
        var drive = new appeng.me.storage.DriveWatcher(cell, () -> activity[0]++);
        var source = IActionSource.empty();
        assertEquals(HUGE, ExactStorageInsertion.insert(drive, INPUT, HUGE, Actionable.MODULATE, source));
        assertEquals(HUGE, cell.exactAmount(INPUT));
        assertEquals(1, activity[0]);
        var blocked = new appeng.me.storage.MEInventoryHandler(new appeng.me.storage.NullInventory());
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(blocked, INPUT, HUGE, Actionable.MODULATE, source));
        blocked.setVoidOverflow(true);
        assertEquals(HUGE, ExactStorageInsertion.insert(blocked, INPUT, HUGE, Actionable.MODULATE, source));
        blocked.setAllowInsertion(false);
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(blocked, INPUT, HUGE, Actionable.MODULATE, source));
    }

    @Test void realNetworkMountInsertsAndListsExactCellAmountsWithoutDuplication() {
        var network = new NetworkStorage();
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        network.mount(0, cell);
        assertInstanceOf(ExactStorageInsertion.class, network);
        assertEquals(HUGE, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.SIMULATE, IActionSource.empty()));
        assertEquals(BigInteger.ZERO, cell.exactAmount(INPUT));
        assertEquals(HUGE, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(HUGE, cell.exactAmount(INPUT));
        assertEquals(HUGE, ((ExactNetworkSupply) network).readExactSupply().finite().get(INPUT));
        assertEquals(19L, network.extract(INPUT, 19L, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(HUGE.subtract(BigInteger.valueOf(19)), ((ExactNetworkSupply) network).readExactSupply().finite().get(INPUT));
        network.unmount(cell);
        assertTrue(((ExactNetworkSupply) network).readExactSupply().finite().isEmpty());
        assertEquals(BigInteger.ZERO, ExactStorageInsertion.insert(network, INPUT, HUGE, Actionable.MODULATE, IActionSource.empty()));
    }

    @Test void bufferedIntermediateOutputStaysWithMachineUntilCpuStopsRequesting() {
        var buffer = new WideKeyCounter(); buffer.add(OUTPUT, HUGE);
        var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
        CraftingOutputRouter.storeUnrequestedBufferedOutputs(buffer, cell, IActionSource.empty(), key -> true);
        assertEquals(HUGE, buffer.getExact(OUTPUT));
        assertEquals(BigInteger.ZERO, cell.exactAmount(OUTPUT));
        CraftingOutputRouter.storeUnrequestedBufferedOutputs(buffer, cell, IActionSource.empty(), key -> false);
        assertTrue(buffer.isEmpty()); assertEquals(HUGE, cell.exactAmount(OUTPUT));
    }

    @Test void bigParentSubmissionCarriesExactRootAndAlwaysClearsScopedMetadata() {
        ICraftingPlan projection = new CraftingPlan(new GenericStack(OUTPUT, Long.MAX_VALUE), 0,
                false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new WideAmountCraftingPlan((CraftingPlan) projection, WideLedgerSnapshot.empty(),
                WideCraftSnapshot.empty(), Set.of(), PlanAmount.of(HUGE));
        ICraftingPlan[] carrier = {null};
        assertEquals(HUGE, NeoEcoWideCraftingSubmission.submit(plan, null, submitted -> {
            carrier[0] = submitted;
            var admission = ECOBigOrderRequest.forSubmission(submitted);
            assertNotNull(admission); assertFalse(admission.forced());
            assertNull(ECOBigOrderRequest.forSubmission(plan));
            return admission.requested();
        }));
        assertNull(ECOBigOrderRequest.forSubmission(carrier[0]));
        assertThrows(IllegalStateException.class, () -> NeoEcoWideCraftingSubmission.submit(plan, null, submitted -> {
            carrier[0] = submitted; throw new IllegalStateException("接单异常");
        }));
        assertNull(ECOBigOrderRequest.forSubmission(carrier[0]));
    }

    private static final class EmptyGrid implements appeng.api.networking.IGrid {
        public <C extends appeng.api.networking.IGridService> C getService(Class<C> type) { throw new UnsupportedOperationException(type.getName()); }
        public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { return event; }
        public Iterable<Class<?>> getMachineClasses() { return List.of(); }
        public Iterable<appeng.api.networking.IGridNode> getMachineNodes(Class<?> type) { return List.of(); }
        public <T> Set<T> getMachines(Class<T> type) { return Set.of(); }
        public <T> Set<T> getActiveMachines(Class<T> type) { return Set.of(); }
        public Iterable<appeng.api.networking.IGridNode> getNodes() { return List.of(); }
        public boolean isEmpty() { return true; }
        public appeng.api.networking.IGridNode getPivot() { return null; }
        public int size() { return 0; }
        public void export(com.google.gson.stream.JsonWriter writer) { throw new UnsupportedOperationException(); }
    }

    private static ECOBatchDispatchContext context() {
        return new ECOBatchDispatchContext(PATTERN, List.of(List.of(new GenericStack(INPUT, 2))),
                List.of(new GenericStack(OUTPUT, 3)), List.of(new GenericStack(REMAINDER, 1)), null, UUID.randomUUID());
    }
    private static final class ExactHost implements NeoEcoExactBatchHost, ECOFastPathDispatchProvider {
        final WideKeyCounter buffer = new WideKeyCounter();
        final boolean accepted;
        ExactHost(boolean accepted) { this.accepted = accepted; }
        public long chexsonsaeutils$getNeoEcoBatchCapacity(IPatternDetails pattern) { return Long.MAX_VALUE; }
        public boolean chexsonsaeutils$pushNeoEcoBatch(IPatternDetails pattern, List<GenericStack> inputs,
                List<GenericStack> outputs, List<GenericStack> remainders, UUID jobId, long count) {
            return chexsonsaeutils$pushNeoEcoExactBatch(pattern, inputs, outputs, remainders, jobId, BigInteger.valueOf(count));
        }
        public boolean chexsonsaeutils$pushNeoEcoExactBatch(IPatternDetails pattern, List<GenericStack> inputs,
                List<GenericStack> outputs, List<GenericStack> remainders, UUID jobId, BigInteger count) {
            return accepted && NeoEcoBatchOutputBuffer.append(buffer, outputs, remainders, count);
        }
        public Preparation eco$prepareFastPath(ECOBatchDispatchContext context) { return null; }
        public ExactPreparation eco$prepareExactFastPath(ECOBatchDispatchContext context, BigInteger requested) {
            return NeoEcoExactBatchPreparation.prepare(this, context, requested);
        }
    }
}
