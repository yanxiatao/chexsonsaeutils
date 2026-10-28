package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.items.contents.CellConfig;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.NetworkStorage;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOBigOrderRequest;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOExactInventory;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import cn.dancingsnow.neoecoae.crafting.execution.batch.ECOExactBatchPlanner;
import cn.dancingsnow.neoecoae.impl.storage.ECOCreativeCell;
import cn.dancingsnow.neoecoae.impl.storage.ECOInfiniteResourceCell;
import com.glodblock.github.extendedae.common.items.ItemInfinityCell;
import git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoInfinityCellInventory;
import java.math.BigInteger;
import java.util.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class NeoEcoSdkCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("NeoEcoSdkCategoryTest",
                NeoEcoBatchDispatchContractCases::verify,
                NeoEcoExactChainSdkCases::verify,
                NeoEcoUnboundedSupplySdkCases::verify
        );
    }

    private static final class NeoEcoBatchDispatchContractCases {
        /** SDK 验收使用真实模组与必需前置，必须实际加载成功后再执行行为断言。 */
        static void requiredModsAreLoaded() {
            assertTrue(ModList.get().isLoaded("neoecoae"), "NeoEco 必须在 FML 中真实加载");
            assertTrue(ModList.get().isLoaded("ldlib2"), "NeoEco 必需前置 ldlib2 必须真实加载");
        }

        private static final AEItemKey INPUT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey OUTPUT = AEItemKey.of(Items.IRON_BLOCK);
        private static final AEItemKey REMAINDER = AEItemKey.of(Items.BUCKET);
        private static final IPatternDetails PATTERN = new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return OUTPUT;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(OUTPUT, 3L));
            }
        };

        void genericDispatcherPreparesAndPushesTheRealFacadeBatchOnce() {
            var inventory = inventory();
            var host = new RecordingHost(4L, true);
            var energy = new RecordingEnergyService();
            PreparedBatchDispatcher dispatcher = host;
            PreparedBatch prepared = prepare(dispatcher, inventory, energy);

            assertNotNull(prepared);
            assertInstanceOf(PreparedBatchImpl.class, prepared);
            assertEquals(4L, prepared.craftCount());
            assertEquals(List.of(new GenericStack(INPUT, 8L)), prepared.inputTotal());
            assertEquals(List.of(new GenericStack(OUTPUT, 12L)), prepared.outputs());
            assertEquals(List.of(new GenericStack(REMAINDER, 4L)), prepared.remainders());
            assertEquals(CraftingCpuHelper.calculatePatternPower(new KeyCounter[]{counter(INPUT, 2L)}) * 4L,
                    prepared.power());
            assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
            assertTrue(prepared.push(inventory));
            assertEquals(12L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(1, host.dispatches);
            assertEquals(4L, host.dispatchedCount);
            // 宿主收到单发明细；整批乘法只发生一次。
            assertEquals(List.of(new GenericStack(INPUT, 2L)), host.inputs);
            assertEquals(List.of(new GenericStack(OUTPUT, 3L)), host.outputs);
            assertEquals(List.of(new GenericStack(REMAINDER, 1L)), host.remainders);
            assertEquals(0, host.singlePushes);
            assertEquals(0, energy.modulatedCalls);
            assertEquals(0.0D, energy.modulatedPower);
        }

        void rejectedRealSubmissionReturnsMaterialsAndDoesNotChargeEnergy() {
            var inventory = inventory();
            var host = new RecordingHost(4L, false);
            var energy = new RecordingEnergyService();
            PreparedBatch prepared = prepare(host, inventory, energy);

            assertNotNull(prepared);
            assertFalse(prepared.push(inventory));
            assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(1, host.dispatches);
            assertEquals(0, energy.modulatedCalls);
            assertEquals(0, host.singlePushes);
        }

        void zeroHostCapacityIsNotApplicableAndNeverDispatches() {
            var inventory = inventory();
            var host = new RecordingHost(0L, true);
            var energy = new RecordingEnergyService();

            assertNull(prepare(host, inventory, energy));
            assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(0, host.dispatches);
            assertEquals(0, energy.modulatedCalls);
        }

        private static PreparedBatch prepare(PreparedBatchDispatcher dispatcher,
                ListCraftingInventory inventory, IEnergyService energy) {
            return dispatcher.prepareBatch(PATTERN, new KeyCounter[]{counter(INPUT, 2L)},
                    counter(OUTPUT, 3L), counter(REMAINDER, 1L), inventory, 8L, energy, null, UUID.randomUUID());
        }

        private static ListCraftingInventory inventory() {
            var inventory = new ListCraftingInventory(ignored -> {});
            inventory.insert(INPUT, 20L, Actionable.MODULATE);
            return inventory;
        }

        private static KeyCounter counter(AEItemKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        /** 普通测试宿主调用真实公开 Facade；记录实际批量和单发明细。 */
        private static final class RecordingHost
                implements ICraftingProvider, PreparedBatchDispatcher, ECOFastPathDispatchProvider {
            private final long capacity;
            private final boolean accepted;
            private int dispatches;
            private int singlePushes;
            private long dispatchedCount;
            private List<GenericStack> inputs;
            private List<GenericStack> outputs;
            private List<GenericStack> remainders;

            private RecordingHost(long capacity, boolean accepted) {
                this.capacity = capacity;
                this.accepted = accepted;
            }

            @Override
            public Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
                if (capacity <= 0L) {
                    return null;
                }
                return new Preparation(capacity, null, false, batch -> {
                    dispatches++;
                    dispatchedCount = batch.craftCount();
                    inputs = context.inputItems();
                    outputs = context.outputs();
                    remainders = context.containerItems();
                    return accepted;
                });
            }

            @Override
            public PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] inputs,
                    KeyCounter outputs, KeyCounter remainders, ListCraftingInventory inventory,
                    long requested, IEnergyService energy, Level level, UUID jobId) {
                var prepared = ECOFastPathFacade.prepare(this, pattern, inputs, outputs, remainders,
                        inventory, requested, CraftingCpuHelper.calculatePatternPower(inputs), energy, level, jobId);
                return prepared == null ? null : new PreparedBatchImpl(prepared);
            }

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return List.of(PATTERN);
            }

            @Override
            public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder) {
                singlePushes++;
                return accepted;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
        }

        private static final class RecordingEnergyService implements IEnergyService {
            private double modulatedPower;
            private int modulatedCalls;

            @Override
            public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
                if (mode == Actionable.MODULATE) {
                    modulatedPower += amount;
                    modulatedCalls++;
                }
                return amount;
            }

            @Override
            public double getIdlePowerUsage() {
                return 0.0D;
            }

            @Override
            public double getChannelPowerUsage() {
                return 0.0D;
            }

            @Override
            public double getAvgPowerUsage() {
                return 0.0D;
            }

            @Override
            public double getAvgPowerInjection() {
                return 0.0D;
            }

            @Override
            public boolean isNetworkPowered() {
                return true;
            }

            @Override
            public double injectPower(double amount, Actionable mode) {
                return 0.0D;
            }

            @Override
            public double getStoredPower() {
                return Double.MAX_VALUE;
            }

            @Override
            public double getMaxStoredPower() {
                return Double.MAX_VALUE;
            }

            @Override
            public double getEnergyDemand(double maxRequired) {
                return 0.0D;
            }
        }

        static void verify() {
            requiredModsAreLoaded();
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchDispatchContractCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("genericDispatcherPreparesAndPushesTheRealFacadeBatchOnce", () -> new NeoEcoBatchDispatchContractCases().genericDispatcherPreparesAndPushesTheRealFacadeBatchOnce()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("rejectedRealSubmissionReturnsMaterialsAndDoesNotChargeEnergy", () -> new NeoEcoBatchDispatchContractCases().rejectedRealSubmissionReturnsMaterialsAndDoesNotChargeEnergy()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("zeroHostCapacityIsNotApplicableAndNeverDispatches", () -> new NeoEcoBatchDispatchContractCases().zeroHostCapacityIsNotApplicableAndNeverDispatches())
            );
        }
    }

    private static final class NeoEcoExactChainSdkCases {
        void mountedExactExtractionPreservesFiltersExclusionsAndActivity() {
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

        void realCpuReceiptRejectsOtherGridAndRestoresExactJobAfterSave() {
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

        void exactCpuReceiptPreservesExcessWaitsProgressAndSavedInventory() {
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

        void realComputationCpuUsesParentOrderCapacityAndOfflineBusKeepsInputs() {
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

        void exactBatchDebitsActualMaterialAndProducesOutputsAndContainersOnce() {
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

        void rejectedExactBatchRestoresAllMaterialAndRefundsReservation() {
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

        void driveMountedCellAcceptsEntireAssemblerBufferAndPreservesFilters() {
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

        void exactDriveInsertionUpdatesActivityAndHandlerVoidRules() {
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

        void realNetworkMountInsertsAndListsExactCellAmountsWithoutDuplication() {
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

        void bufferedIntermediateOutputStaysWithMachineUntilCpuStopsRequesting() {
            var buffer = new WideKeyCounter(); buffer.add(OUTPUT, HUGE);
            var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
            CraftingOutputRouter.storeUnrequestedBufferedOutputs(buffer, cell, IActionSource.empty(), key -> true);
            assertEquals(HUGE, buffer.getExact(OUTPUT));
            assertEquals(BigInteger.ZERO, cell.exactAmount(OUTPUT));
            CraftingOutputRouter.storeUnrequestedBufferedOutputs(buffer, cell, IActionSource.empty(), key -> false);
            assertTrue(buffer.isEmpty()); assertEquals(HUGE, cell.exactAmount(OUTPUT));
        }

        void bigParentSubmissionCarriesExactRootAndAlwaysClearsScopedMetadata() {
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

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoExactChainSdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("mountedExactExtractionPreservesFiltersExclusionsAndActivity", () -> new NeoEcoExactChainSdkCases().mountedExactExtractionPreservesFiltersExclusionsAndActivity()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("realCpuReceiptRejectsOtherGridAndRestoresExactJobAfterSave", () -> new NeoEcoExactChainSdkCases().realCpuReceiptRejectsOtherGridAndRestoresExactJobAfterSave()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("exactCpuReceiptPreservesExcessWaitsProgressAndSavedInventory", () -> new NeoEcoExactChainSdkCases().exactCpuReceiptPreservesExcessWaitsProgressAndSavedInventory()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("realComputationCpuUsesParentOrderCapacityAndOfflineBusKeepsInputs", () -> new NeoEcoExactChainSdkCases().realComputationCpuUsesParentOrderCapacityAndOfflineBusKeepsInputs()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("exactBatchDebitsActualMaterialAndProducesOutputsAndContainersOnce", () -> new NeoEcoExactChainSdkCases().exactBatchDebitsActualMaterialAndProducesOutputsAndContainersOnce()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("rejectedExactBatchRestoresAllMaterialAndRefundsReservation", () -> new NeoEcoExactChainSdkCases().rejectedExactBatchRestoresAllMaterialAndRefundsReservation()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("driveMountedCellAcceptsEntireAssemblerBufferAndPreservesFilters", () -> new NeoEcoExactChainSdkCases().driveMountedCellAcceptsEntireAssemblerBufferAndPreservesFilters()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("exactDriveInsertionUpdatesActivityAndHandlerVoidRules", () -> new NeoEcoExactChainSdkCases().exactDriveInsertionUpdatesActivityAndHandlerVoidRules()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("realNetworkMountInsertsAndListsExactCellAmountsWithoutDuplication", () -> new NeoEcoExactChainSdkCases().realNetworkMountInsertsAndListsExactCellAmountsWithoutDuplication()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("bufferedIntermediateOutputStaysWithMachineUntilCpuStopsRequesting", () -> new NeoEcoExactChainSdkCases().bufferedIntermediateOutputStaysWithMachineUntilCpuStopsRequesting()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("bigParentSubmissionCarriesExactRootAndAlwaysClearsScopedMetadata", () -> new NeoEcoExactChainSdkCases().bigParentSubmissionCarriesExactRootAndAlwaysClearsScopedMetadata())
            );
        }
    }

    private static final class NeoEcoUnboundedSupplySdkCases {
        private static final AEKey LAVA = AEFluidKey.of(Fluids.LAVA);

        void extendedAeCustomFluidCellRetainsUnboundedIdentityThroughDrive() {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("extendedae", "infinity_water_cell"));
            assertInstanceOf(ItemInfinityCell.class, item);
            var cell = new com.glodblock.github.extendedae.common.inventory.InfinityCellInventory(new ItemStack(item));
            var water = AEFluidKey.of(Fluids.WATER);
            assertEquals((long) Integer.MAX_VALUE * water.getAmountPerUnit(), cell.getAvailableStacks().get(water));
            checkMountedSupply(new DriveWatcher(cell, () -> {}), water);
        }

        void neoEcoCreativeCellRetainsSupplyIdentityThroughDrive() {
            var stack = AEItems.CREATIVE_CELL.stack();
            CellConfig.create(stack).setStack(0, new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_INGOT), 1));
            var cell = ECOCreativeCell.Handler.INSTANCE.getCellInventory(stack, null);
            assertNotNull(cell);
            checkMountedSupply(new DriveWatcher(cell, () -> {}), AEItemKey.of(Items.IRON_INGOT));
        }

        void neoEcoBaseResourceMatrixDeclaresUnboundedFluidSupply() {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("neoecoae", "eco_infinite_item_cell"));
            var cell = new ECOInfiniteResourceCell(new ItemStack(item), null);
            checkMountedSupply(new DriveWatcher(cell, () -> {}), LAVA);
        }

        void unlimitedCapacityWithFiniteContentsDoesNotDeclareUnboundedSupply() {
            var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
            BigInteger stored = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
            cell.insertExact(LAVA, stored, Actionable.MODULATE, IActionSource.empty());
            var network = new NetworkStorage(); network.mount(0, new DriveWatcher(cell, () -> {}));
            var snapshot = ((ExactNetworkSupply) network).readExactSupply();
            assertTrue(snapshot.unbounded().isEmpty());
            assertEquals(stored, snapshot.finite().get(LAVA));
            assertEquals(stored, cell.exactAmount(LAVA));
        }

        private static void checkMountedSupply(DriveWatcher drive, AEKey key) {
            var network = new NetworkStorage(); network.mount(0, drive);
            assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
            drive.setExtractFiltering(true, true);
            drive.setAllowExtraction(false);
            assertFalse(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
            drive.setAllowExtraction(true);
            assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
            network.unmount(drive);
            assertFalse(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoUnboundedSupplySdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("extendedAeCustomFluidCellRetainsUnboundedIdentityThroughDrive", () -> new NeoEcoUnboundedSupplySdkCases().extendedAeCustomFluidCellRetainsUnboundedIdentityThroughDrive()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("neoEcoCreativeCellRetainsSupplyIdentityThroughDrive", () -> new NeoEcoUnboundedSupplySdkCases().neoEcoCreativeCellRetainsSupplyIdentityThroughDrive()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("neoEcoBaseResourceMatrixDeclaresUnboundedFluidSupply", () -> new NeoEcoUnboundedSupplySdkCases().neoEcoBaseResourceMatrixDeclaresUnboundedFluidSupply()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("unlimitedCapacityWithFiniteContentsDoesNotDeclareUnboundedSupply", () -> new NeoEcoUnboundedSupplySdkCases().unlimitedCapacityWithFiniteContentsDoesNotDeclareUnboundedSupply())
            );
        }
    }
}
