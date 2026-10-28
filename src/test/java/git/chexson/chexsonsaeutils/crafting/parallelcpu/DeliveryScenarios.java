package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.*;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import java.math.BigInteger;
import java.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精确交付与持久化的行为场景；由类别测试统一调用。 */
public final class DeliveryScenarios {
    private DeliveryScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.parallelcpu",
                () -> ReliabilityScenarios.verify(temporaryRoot),
                () -> ParallelCpuPersistenceCases.verify(temporaryRoot),
                () -> ParallelExpectedOutputLedgerCases.verify(temporaryRoot),
                () -> ParallelRecursiveBeyondLongOrderCases.verify(temporaryRoot),
                () -> ParallelRecursiveFinalOutputDeliveryCases.verify(temporaryRoot),
                () -> ParallelReturnedOutputProgressCases.verify(temporaryRoot),
                () -> ParallelRunningAccountsWideLedgerCases.verify(temporaryRoot),
                () -> ParallelTaskProgressWideAmountCases.verify(temporaryRoot),
                () -> ParallelWaitingForWideInventoryCases.verify(temporaryRoot),
                () -> ParallelWidePoolRoundStoreCases.verify(temporaryRoot),
                () -> ParallelWideProductionCreditCases.verify(temporaryRoot),
                () -> ParallelWideRootDeliveryCases.verify(temporaryRoot),
                () -> ParallelWideTruthReadoutCases.verify(temporaryRoot)
        );
    }

    /** ParallelCpuPersistenceCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCpuPersistenceCases {
        private static final HolderLookup.Provider REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.CHEST);
        private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey ZERO_QUOTA = AEItemKey.of(Items.GOLD_INGOT);
        private static final BigInteger WIDE = BigInteger.TEN.pow(25).add(BigInteger.valueOf(123));
        private static final Pattern FIRST = new Pattern(AEItemKey.of(Items.STRUCTURE_VOID));
        private static final Pattern SECOND = new Pattern(AEItemKey.of(Items.JIGSAW));

        static {
            PatternDetailsHelper.registerDecoder(new IPatternDetailsDecoder() {
                @Override public boolean isEncodedPattern(ItemStack stack) {
                    return stack.is(Items.STRUCTURE_VOID) || stack.is(Items.JIGSAW);
                }
                @Override public IPatternDetails decodePattern(AEItemKey key, Level level) {
                    if (FIRST.definition().equals(key) || SECOND.definition().equals(key)) return new Pattern(key);
                    return null;
                }
            });
        }

        void activeJobRestoresWideProgressQuotasDeliveriesAndContinuesAccounting() {
            var host = new HostImpl();
            var cluster = cluster(host);
            var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
            var job = job(lane);
            job.tasks.get(FIRST).addFirings(PlanAmount.of(WIDE));
            job.tasks.get(FIRST).consumeFirings(17L);
            var expectedFirings = job.tasks.get(FIRST).exactRemaining();
            var assigned = new WideKeyCounter();
            assigned.setExact(MATERIAL, WIDE);
            assigned.set(ZERO_QUOTA, 0L);
            var envelope = new WideKeyCounter();
            envelope.set(MATERIAL, 7L);
            job.materialAllocation.adopt(Map.of(FIRST, assigned), Map.of(FIRST, envelope));
            job.materialAllocation.recordConsumption(FIRST, MATERIAL, BigInteger.valueOf(29));
            job.batchObligations.incur(FIRST, BigInteger.valueOf(3));
            job.addFinalOutputDelivered(PRODUCT, 40L);
            job.remainingAmount = 60L;
            job.waitingFor.insert(PRODUCT, 60L, Actionable.MODULATE);
            job.initialItemsRemaining.insertExact(MATERIAL, WIDE, Actionable.MODULATE);
            job.initialItemsCursor = 1;
            job.noteUnboundedSupply(ZERO_QUOTA);
            job.suspended = true;

            var restored = new ParallelExecutingCraftingJob(job.writeToNBT(REGISTRIES), REGISTRIES, ignored -> {}, lane);
            assertEquals(List.of(FIRST.definition(), SECOND.definition()),
                    restored.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList());
            var pattern = restored.tasks.keySet().iterator().next();
            assertNotSame(FIRST, pattern, "材料账必须绑定本次解码的身份");
            assertEquals(expectedFirings, restored.tasks.get(pattern).exactRemaining());
            assertEquals(WIDE.subtract(BigInteger.valueOf(29)),
                    restored.materialAllocation.remainingFamilyQuota(pattern, MATERIAL).exactValue());
            assertTrue(restored.materialAllocation.hasQuotaBasis(pattern, ZERO_QUOTA));
            assertEquals(PlanAmount.ZERO, restored.materialAllocation.remainingFamilyQuota(pattern, ZERO_QUOTA));
            assertEquals(PlanAmount.of(7), restored.materialAllocation.envelopeAmount(pattern, MATERIAL));
            assertEquals(MaterialAllocation.Source.PLAN, restored.materialAllocation.source());
            assertEquals(BigInteger.valueOf(3), restored.batchObligations.pendingFirings(pattern));
            assertEquals(PlanAmount.of(40), restored.finalOutputDeliveredAmount());
            assertEquals(PlanAmount.of(100), restored.finalOutputOrderAmount());
            assertEquals(60L, restored.remainingAmount);
            assertEquals(PlanAmount.of(60), restored.waitingForExact(PRODUCT));
            assertEquals(WIDE, WideCraftingInventory.amount(restored.initialItemsRemaining, MATERIAL));
            assertEquals(1, restored.initialItemsCursor);
            assertTrue(restored.isUnboundedSupply(ZERO_QUOTA));
            assertTrue(restored.suspended);
            restored.tasks.get(pattern).consumeFirings(11L);
            restored.materialAllocation.recordConsumption(pattern, MATERIAL, 11L);
            restored.addFinalOutputDelivered(PRODUCT, 60L);
            assertEquals(expectedFirings.subtract(PlanAmount.of(11)), restored.tasks.get(pattern).exactRemaining());
            assertEquals(WIDE.subtract(BigInteger.valueOf(40)),
                    restored.materialAllocation.remainingFamilyQuota(pattern, MATERIAL).exactValue());
            assertEquals(PlanAmount.of(100), restored.finalOutputDeliveredAmount());
        }

        void originalBeyondLongProductionIsNotRecalculatedFromRemainingFirings() {
            var lane = new ParallelCraftingLaneState(cluster(new HostImpl()), UUID.randomUUID(), 0L);
            var plan = new CraftingPlan(new GenericStack(PRODUCT, Long.MAX_VALUE), 0L, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(FIRST, Long.MAX_VALUE, SECOND, 1L));
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), true, false), lane.linkCpu());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
            job.tasks.get(FIRST).consumeFirings(42L);
            job.addFinalOutputDelivered(PRODUCT, 42L);
            var restored = new ParallelExecutingCraftingJob(job.writeToNBT(REGISTRIES), REGISTRIES, ignored -> {}, lane);
            assertEquals(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)), restored.finalOutputOrderAmount());
            assertEquals(PlanAmount.of(42), restored.finalOutputDeliveredAmount());
        }

        void loadBeforeWorldBindingPreservesRawTasksEvenWhenSavedAgain() {
            var cluster = cluster(new HostImpl());
            var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
            var lanes = new ListTag();
            var laneTag = new CompoundTag();
            laneTag.putUUID("laneId", lane.laneId());
            var logic = new CompoundTag();
            logic.put("job", job(lane).writeToNBT(REGISTRIES));
            laneTag.put("logic", logic);
            lanes.add(laneTag);
            var saved = new CompoundTag();
            saved.put("parallelCpuLanes", lanes);
            cluster.readFromNBT(saved, REGISTRIES);
            saved.remove("parallelCpuLanes");
            cluster.restorePendingJobs();
            assertEquals(0, cluster.activeLaneCount(), "没有世界时不能提前解码任务");
            var rewritten = new CompoundTag();
            cluster.writeToNBT(rewritten, REGISTRIES);
            assertEquals(lanes, rewritten.getList("parallelCpuLanes", Tag.TAG_COMPOUND));
        }

        void undecodableTaskIsPausedAndPreservedInsteadOfSilentlyDropped() {
            var lane = new ParallelCraftingLaneState(cluster(new HostImpl()), UUID.randomUUID(), 0L);
            var saved = job(lane).writeToNBT(REGISTRIES);
            var tasks = saved.getList("tasks", Tag.TAG_COMPOUND);
            var invalid = AEItemKey.of(Items.DIRT).toTag(REGISTRIES);
            ParallelExecutingCraftingJob.writeTaskProgress(invalid, new ParallelExecutingCraftingJob.TaskProgress());
            tasks.add(invalid);
            var restored = new ParallelExecutingCraftingJob(saved, REGISTRIES, ignored -> {}, lane);
            assertTrue(restored.suspended);
            assertEquals(2, restored.tasks.size());
            var rewritten = restored.writeToNBT(REGISTRIES).getList("tasks", Tag.TAG_COMPOUND);
            assertEquals(3, rewritten.size());
            assertEquals(invalid, rewritten.getCompound(2));
        }

        void submissionPauseAndCancellationMarkHostForSaving() {
            var host = new HostImpl();
            var grid = new DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.TestGrid(() -> net.minecraft.network.chat.Component.empty());
            host.grid = grid;
            var cluster = cluster(host);
            var result = cluster.submitJob(grid, plan(), null, IActionSource.empty(), 0L);
            assertTrue(result.successful());
            assertTrue(host.saves > 0);
            var active = new java.util.ArrayList<ParallelCraftingLane>();
            cluster.appendActiveLanes(active);
            var lane = active.getFirst();
            int before = host.saves;
            ((ParallelCraftingLaneState) lane).logic().setJobSuspended(true);
            assertTrue(host.saves > before);
            before = host.saves;
            cluster.cancelLane((ParallelCraftingLaneState) lane);
            assertTrue(host.saves > before);
            assertEquals(0, cluster.activeLaneCount());
        }

        private static ParallelCraftingCpuCluster cluster(HostImpl host) {
            return new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
        }
        private static CraftingPlan plan() {
            var patterns = new LinkedHashMap<IPatternDetails, Long>();
            patterns.put(FIRST, 50L);
            patterns.put(SECOND, 50L);
            return new CraftingPlan(new GenericStack(PRODUCT, 100L), 0L, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), patterns);
        }
        private static ParallelExecutingCraftingJob job(ParallelCraftingLaneState lane) {
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), true, false), lane.linkCpu());
            return new ParallelExecutingCraftingJob(plan(), ignored -> {}, link, null);
        }
        private record Pattern(AEItemKey definition) implements IPatternDetails {
            @Override public AEItemKey getDefinition() { return definition; }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 1L)); }
        }
        private static final class HostImpl implements ParallelCpuHost {
            private int saves;
            private IGrid grid;
            @Override public void saveChanges() { saves++; }
            @Override public IGrid getGrid() { return grid; }
            @Override public Level getLevel() { return null; }
            @Override public IGridNode getActionableNode() { return null; }
            @Override public CpuSelectionMode getSelectionMode() { return CpuSelectionMode.ANY; }
            @Override public boolean isParallelCpuProviderActive() { return true; }
            @Override public boolean canProcessParallelCpuJobs() { return true; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases.activeJobRestoresWideProgressQuotasDeliveriesAndContinuesAccounting", () -> new ParallelCpuPersistenceCases().activeJobRestoresWideProgressQuotasDeliveriesAndContinuesAccounting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases.originalBeyondLongProductionIsNotRecalculatedFromRemainingFirings", () -> new ParallelCpuPersistenceCases().originalBeyondLongProductionIsNotRecalculatedFromRemainingFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases.loadBeforeWorldBindingPreservesRawTasksEvenWhenSavedAgain", () -> new ParallelCpuPersistenceCases().loadBeforeWorldBindingPreservesRawTasksEvenWhenSavedAgain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases.undecodableTaskIsPausedAndPreservedInsteadOfSilentlyDropped", () -> new ParallelCpuPersistenceCases().undecodableTaskIsPausedAndPreservedInsteadOfSilentlyDropped()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuPersistenceCases.submissionPauseAndCancellationMarkHostForSaving", () -> new ParallelCpuPersistenceCases().submissionPauseAndCancellationMarkHostForSaving())
            );
        }
    }

    /** ParallelExpectedOutputLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelExpectedOutputLedgerCases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GEAR = AEItemKey.of(Items.CLOCK);
        /** 回流容器（模具/桶那一类投下去原样退回的键）：只有实物一条路。 */
        private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

        private static final long PER_BATCH_EXPECTED = 4_000_000_000_000_000_000L;
        private static final long PER_BATCH_PLANNED = 3_500_000_000_000_000_000L;

        /** 三批全额按自报计划入网：合计 3×Long.MAX_VALUE，逐位等于申报产出，且 {@code waitingFor} 一格都不挂。 */
        void plannedNetworkOutputsAddUpAcrossBatchesWithoutLongTruncation() {
            var job = newJob();
            var declaredTotal = BigInteger.ZERO;

            for (int batch = 1; batch <= 3; batch++) {
                var expected = new KeyCounter();
                expected.add(PRODUCT, Long.MAX_VALUE);
                var delivered = new KeyCounter();
                delivered.add(PRODUCT, Long.MAX_VALUE);
                ParallelCraftingCpuLogic.reserveExpectedWaiting(
                        job, expected, new KeyCounter(), delivered);
                declaredTotal = declaredTotal.add(BigInteger.valueOf(Long.MAX_VALUE));
            }

            var exactTotal = BigInteger.valueOf(3L).multiply(BigInteger.valueOf(Long.MAX_VALUE));
            assertEquals(declaredTotal, exactTotal, "申报总量本身就该是 3×Long.MAX_VALUE");
            assertEquals(exactTotal, job.expectedToNetwork(PRODUCT).exactValue(),
                    "预期入网账跨批复加逐位精确，绝不被 long 截断");
            assertTrue(exactTotal.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                    "本用例量的确实越出了 long：这正是老口径记不住的那一档");
            assertEquals(0L, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "全额入网的产物不再挂 waitingFor 预期：那笔实物永远不会回 CPU");
        }

        /** 一批申报里两条去向并存：实物差额 + 预期入网合计恒等于申报量，宿主报多了也不重复计。 */
        void physicalAndNetworkRoutesSumExactlyWithoutDoubleCounting() {
            var job = newJob();
            var declaredTotal = BigInteger.ZERO;

            for (int batch = 1; batch <= 3; batch++) {
                var expected = new KeyCounter();
                expected.add(PRODUCT, PER_BATCH_EXPECTED);
                var containers = new KeyCounter();
                containers.add(BUCKET, 1L);
                var delivered = new KeyCounter();
                delivered.add(PRODUCT, PER_BATCH_PLANNED);
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, containers, delivered);
                declaredTotal = declaredTotal.add(BigInteger.valueOf(PER_BATCH_EXPECTED));
            }

            var netPart = job.expectedToNetwork(PRODUCT).exactValue();
            long containerPart = job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE);
            assertEquals(BigInteger.valueOf(PER_BATCH_PLANNED).multiply(BigInteger.valueOf(3L)),
                    netPart, "预期入网那一截跨批复加精确（1.05e19 枚）");
            assertEquals(3L * (PER_BATCH_EXPECTED - PER_BATCH_PLANNED), containerPart,
                    "waitingFor 只挂没入网的那一截差额");
            assertEquals(declaredTotal, netPart.add(BigInteger.valueOf(containerPart)),
                    "实物 + 预期入网 = 申报总量：合计精确，不多也不少");

            assertEquals(3L, job.waitingFor.extract(BUCKET, Long.MAX_VALUE, Actionable.SIMULATE),
                    "回流容器整笔挂预期：它必须实物回 CPU 才再投得出去");
            assertTrue(job.expectedToNetwork(BUCKET).signum() == 0,
                    "分流只作用于产出，容器件一条都不进预期入网账");

            // 宿主报超出申报：多出的那一枚不认（与"申报 ∩ 实收"同一条口径）
            var overExpected = new KeyCounter();
            overExpected.add(PRODUCT, 10L);
            var overDelivered = new KeyCounter();
            overDelivered.add(PRODUCT, 11L);
            var netBefore = job.expectedToNetwork(PRODUCT).exactValue();
            long containerBefore = job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(
                    job, overExpected, new KeyCounter(), overDelivered);
            assertEquals(netBefore.add(BigInteger.TEN), job.expectedToNetwork(PRODUCT).exactValue(),
                    "超出申报的 1 枚不重复入账");
            assertEquals(containerBefore,
                    job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "申报全额按自报计划入网：不再挂任何实物预期");

            // 申报表之外的键：一笔账都不许添（改前那一支把它们"全额按已入网结算"并顺手复加报称账，
            // 而生产侧的申报表与宿主自报计划同出 details.getOutputs()，那一支在真宿主上永不命中）
            var outsidePlanned = new KeyCounter();
            outsidePlanned.add(GEAR, 7L);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(
                    job, new KeyCounter(), new KeyCounter(), outsidePlanned);
            assertEquals(0L, job.expectedToNetwork(GEAR).signum(),
                    "申报表之外的键不进预期入网账");
            assertEquals(0L, job.accountAnnounced.getLong(GEAR),
                    "也不进报称产出流水账：这张样板从没申报过它");
            assertEquals(0L, job.waitingFor.extract(GEAR, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /** 预期入网账进出 NBT 逐位相等；能塞 {@code long} 的条目不多挂宽域键，缺宽域键即按老 {@code long} 读。 */
        void plannedLedgerRoundTripsThroughJobNbtBitForBit() {
            var wideExact = new BigInteger("100000000000000000001234567890");
            var delivered = new WideKeyCounter();
            delivered.add(PRODUCT, Long.MAX_VALUE);
            delivered.add(GEAR, wideExact);

            var data = new CompoundTag();
            ParallelExecutingCraftingJob.writeExpectedNetworkOutputs(data, REGISTRIES, delivered);

            var restored = new WideKeyCounter();
            ParallelExecutingCraftingJob.readExpectedNetworkOutputs(data, REGISTRIES, restored);
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), restored.getExact(PRODUCT));
            assertEquals(wideExact, restored.getExact(GEAR), "越界条目含低位特征数字，逐位还原");

            ListTag entries = data.getList(
                    ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK, Tag.TAG_COMPOUND);
            assertEquals(2, entries.size());
            int wideEntries = 0;
            for (int index = 0; index < entries.size(); index++) {
                if (entries.getCompound(index).contains(
                        ParallelExecutingCraftingJob.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                    wideEntries++;
                }
            }
            assertEquals(1, wideEntries, "只有真越界的条目多挂宽域键");

            // 常态零预期：一个键都不挂，不是迁移壳
            var bare = new CompoundTag();
            ParallelExecutingCraftingJob.writeExpectedNetworkOutputs(bare, REGISTRIES, new WideKeyCounter());
            assertFalse(bare.contains(ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK,
                    Tag.TAG_LIST));

            // 缺宽域键就是"这笔装得进 long"：按老 long 读数原样吃回来
            var legacyEntry = GEAR.toTagGeneric(REGISTRIES);
            legacyEntry.putLong("#", 42L);
            var legacyList = new ListTag();
            legacyList.add(legacyEntry);
            var legacy = new CompoundTag();
            legacy.put(ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK, legacyList);
            var fromLegacy = new WideKeyCounter();
            ParallelExecutingCraftingJob.readExpectedNetworkOutputs(legacy, REGISTRIES, fromLegacy);
            assertEquals(BigInteger.valueOf(42L), fromLegacy.getExact(GEAR));
        }

        private static ParallelExecutingCraftingJob newJob() {
            return new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
        }

        private static ICraftingPlan emptyPlan() {
            return new ICraftingPlan() {
                private final KeyCounter empty = new KeyCounter();

                @Override
                public GenericStack finalOutput() {
                    return null;
                }

                @Override
                public long bytes() {
                    return 0L;
                }

                @Override
                public boolean simulation() {
                    return false;
                }

                @Override
                public boolean multiplePaths() {
                    return false;
                }

                @Override
                public KeyCounter usedItems() {
                    return empty;
                }

                @Override
                public KeyCounter emittedItems() {
                    return empty;
                }

                @Override
                public KeyCounter missingItems() {
                    return empty;
                }

                @Override
                public Map<IPatternDetails, Long> patternTimes() {
                    return Map.of();
                }
            };
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelExpectedOutputLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelExpectedOutputLedgerCases.plannedNetworkOutputsAddUpAcrossBatchesWithoutLongTruncation", () -> new ParallelExpectedOutputLedgerCases().plannedNetworkOutputsAddUpAcrossBatchesWithoutLongTruncation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelExpectedOutputLedgerCases.physicalAndNetworkRoutesSumExactlyWithoutDoubleCounting", () -> new ParallelExpectedOutputLedgerCases().physicalAndNetworkRoutesSumExactlyWithoutDoubleCounting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelExpectedOutputLedgerCases.plannedLedgerRoundTripsThroughJobNbtBitForBit", () -> new ParallelExpectedOutputLedgerCases().plannedLedgerRoundTripsThroughJobNbtBitForBit())
            );
        }
    }

    /** ParallelRecursiveBeyondLongOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelRecursiveBeyondLongOrderCases {
        private static final AEKey PRODUCT = AEItemKey.of(new net.minecraft.world.item.ItemStack(Items.CHEST));
        private static final long LONG_MAX = Long.MAX_VALUE;
        /** 订单量真值：1e19 枚，比 {@code long} 天花板多出 7.77e17 枚。 */
        private static final BigInteger ORDER_WIDE = new BigInteger("10000000000000000000");
        private static final BigInteger TAIL_CHUNK = ORDER_WIDE.subtract(BigInteger.valueOf(LONG_MAX));
        /** FIX-G：终点键里<b>不经任务表</b>、随计划直接出门的那一截（1e12 枚，单笔塞得进 long）。 */
        private static final BigInteger EMITTED_CHUNK = new BigInteger("1000000000000");

        /**
         * 主线那一档：{@code >long} 订单、实物<b>交齐</b> ⇒ 收工判据必须成立。
         *
         * <p>交付分两轮进账（AE2 交付通道单笔 ≤ {@code long}），交付账按宽域累加到 1e19。
         * 未修树上这一条红：订单量交回 {@code null} ⇒ 判据恒假。
         */
        void beyondLongOrderClosesOncePhysicallyDeliveredInFull() {
            var producer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), new GenericStack(PRODUCT, 1L));
            var job = newJob(producer, ORDER_WIDE, Map.of(), -1L);

            // 差一口气：先交一轮 long 天花板，还欠 7.77e17 枚
            job.addFinalOutputDelivered(PRODUCT, LONG_MAX);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "只交了 " + LONG_MAX + " 枚、订单是 1e19 枚：差 " + TAIL_CHUNK + " 枚就不许收工");

            // 第二轮把剩下的交齐
            job.addFinalOutputDelivered(PRODUCT, TAIL_CHUNK.longValueExact());
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "终点订单量 > Long.MAX_VALUE、实物交齐 ⇒ 收工判据必须成立（判据读不出订单量"
                            + "就是把 long 出口那道哨兵当成『没有量』，这一档于是永远走不到 finishJob）："
                            + "交付账=" + job.finalOutputDeliveredAmount().exactValue());

            // 同一把尺的两侧读数：订单量取的是宽域真值，不是饱和哨兵
            assertNotNull(job.finalOutputOrderAmount(), "哨兵这一档必须由宽域份数账反推出订单量");
            assertEquals(ORDER_WIDE, job.finalOutputOrderAmount().exactValue(),
                    "订单量必须等于 1e19 枚真值——交回 Long.MAX_VALUE 就是把哨兵当量");
            assertEquals(ORDER_WIDE, job.finalOutputDeliveredAmount().exactValue(),
                    "两轮实物交付在宽域交付账上凑齐 1e19 枚");
        }

        /**
         * 两型分开：读数是哨兵时，<b>反推不出真值</b>那一型明确不判；<b>真值恰好是
         * {@code Long.MAX_VALUE}</b> 那一型照判。两型在 long 出口上长得一模一样（都是
         * {@code 9223372036854775807}），分开它们的只有宽域份数账。
         */
        void sentinelWithoutDerivableTruthJudgesNothingWhileTrueLongMaxJudges() {
            // 型一：任务表里没有产出终点键的样板 ⇒ 反推不出订单量 ⇒ 不判（交满也不收工）。
            //
            // FIX-G 顺手将这一臂的夹具改成<b>空任务表</b>：上一版把 unrelated×1 发留在表上，
            // {@code isDyeableRecursiveJobComplete} 那条老臂（任务表空 且 waitingFor 空）当场恒假，
            // 这一句 assertFalse 于是被夹具顺带满足——它压根没打在交付臂上。现在表是空的，拦住老臂的
            // 只剩 {@code waitingFor} 上那一截不经任务表直接出门的残量，断言问的才是交付臂本身：
            // 订单量算不出来 ⇒ 交付量凑得再满也不许放行。
            var unrelated = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L));
            var noTruth = newJob(unrelated, null, Map.of(), -1L, emittedIron(5L));
            assertTrue(noTruth.tasks.isEmpty(), "夹具：任务表是空的，这一句判据只能由交付臂给出");
            assertFalse(noTruth.waitingFor.list.isEmpty(),
                    "夹具：不经任务表直接出门的那一截挂在 waitingFor 上，老臂（两格全空）恒假");
            assertNull(noTruth.finalOutputOrderAmount(),
                    "反推不出真值就明说不判：不许拿饱和哨兵顶一个'看起来到了'的订单量；"
                            + "出门那一截不是终点键，也不许被算成终点产量");
            noTruth.addFinalOutputDelivered(PRODUCT, LONG_MAX);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(noTruth),
                    "订单量算不出来的那一型只能等任务表自己清空，不许被交付量凑齐（空表下这一句打的正是交付臂）");

            // 型二：终点份数＝Long.MAX_VALUE、每发 1 枚 ⇒ 真值就是 9223372036854775807，不是哨兵
            var producer = new FakePattern(AEItemKey.of(Items.REDSTONE_BLOCK), new GenericStack(PRODUCT, 1L));
            var trueMax = newJob(producer, null, Map.of(producer, LONG_MAX), -1L);
            assertNotNull(trueMax.finalOutputOrderAmount(),
                    "真值恰好是 Long.MAX_VALUE 的那一型是一个数得出来的数，必须与'反推不出'分开");
            assertEquals(BigInteger.valueOf(LONG_MAX), trueMax.finalOutputOrderAmount().exactValue(),
                    "订单量＝终点份数 Long.MAX × 每发 1 枚");
            trueMax.addFinalOutputDelivered(PRODUCT, LONG_MAX);
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(trueMax),
                    "真 Long.MAX 订单交齐就该收工：把这一型也一并弃权，等于把真订单当成哨兵扔掉");
        }

        /**
         * FIX-G：终点键有一截<b>不经任务表</b>、随计划直接出门（构造那趟把它插进 {@code waitingFor}）时，
         * 反推出来的订单量必须含那一截。
         *
         * <p>漏掉它的后果是<b>偏小＝提前收工</b>：交付账刚交满"任务表那一截的毛量"就判成收工，
         * 而订单实际还差直接出门的那一截。这一档只在哨兵那一型上被读到（订单越出 {@code long}，
         * {@code long} 出口交回的是哨兵，唯一还完整的读数就是这份反推产量）。
         *
         * <p>未修树红在 {@code finalOutputOrderAmount} 少 {@code 1e12} 枚、且交满毛量就被判成完成。
         */
        void terminalChunkEmittedOutsideTheTaskTableStillCountsIntoTheOrder() {
            var producer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), new GenericStack(PRODUCT, 1L));
            // 订单真值 1e19 枚＝任务表那一截（1e19-1e12 发 × 每发 1 枚）＋直接出门那一截（1e12 枚）
            var job = newJob(producer, ORDER_WIDE.subtract(EMITTED_CHUNK), Map.of(), -1L,
                    emittedTerminal(EMITTED_CHUNK.longValueExact()));

            assertNotNull(job.finalOutputOrderAmount(), "哨兵这一档必须由宽域终点量反推订单量");
            assertEquals(ORDER_WIDE, job.finalOutputOrderAmount().exactValue(),
                    "订单量必须含直接出门的那一截 1e12 枚——少算它就是偏小，偏小＝提前收工");

            // 交满"不含那一截的毛量"：任务表那一截整笔交齐（单笔 ≤ long，分两轮进账）
            job.addFinalOutputDelivered(PRODUCT, LONG_MAX);
            job.addFinalOutputDelivered(PRODUCT,
                    ORDER_WIDE.subtract(EMITTED_CHUNK).subtract(BigInteger.valueOf(LONG_MAX)).longValueExact());
            assertEquals(ORDER_WIDE.subtract(EMITTED_CHUNK), job.finalOutputDeliveredAmount().exactValue(),
                    "交付账此刻正好是任务表那一截");
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "还差直接出门的那一截 1e12 枚就判收工＝提前收工：订单量偏小是这一条的根因");

            job.addFinalOutputDelivered(PRODUCT, EMITTED_CHUNK.longValueExact());
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "补上那一截才算交齐（两侧都不许在比较前窄化）");
        }

        /**
         * 建一份带递归契约的计划：终点件 {@code PRODUCT × Long.MAX_VALUE}（{@code >long} 订单在
         * {@code long} 出口上就长成这一格），契约定版量交回 {@code -1}＝没有定版量，退回计划终点量。
         *
         * @param wideFirings 走宽域份数账的发数（{@code >long} 那一档）；给 {@code null} 即不进这份账
         * @param longTimes   AE2 那份代表值字典（塞得进 {@code long} 的发数从这一格来）
         */
        private static ParallelExecutingCraftingJob newJob(IPatternDetails producer,
                BigInteger wideFirings, Map<IPatternDetails, Long> longTimes, long contractAmount) {
            return newJob(producer, wideFirings, longTimes, contractAmount, new KeyCounter());
        }

        /**
         * 同一份夹具，多给一格<b>不经任务表直接出门</b>的量（{@code plan.emittedItems()}：构造那一趟
         * 把它插进 {@code waitingFor}，{@code patternTimes()} 与宽域份数账里都没有它）。
         */
        private static ParallelExecutingCraftingJob newJob(IPatternDetails producer,
                BigInteger wideFirings, Map<IPatternDetails, Long> longTimes, long contractAmount,
                KeyCounter emittedItems) {
            var counter = new WideCraftCounter();
            if (wideFirings != null) {
                counter.add(producer, PlanAmount.of(wideFirings));
            }
            var base = new CraftingPlan(new GenericStack(PRODUCT, LONG_MAX), 0L, true, false,
                    new KeyCounter(), emittedItems, new KeyCounter(), longTimes);
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    contractAmount, Map.of(), Map.of(), WideLedgerSnapshot.empty(),
                    WideCraftSnapshot.capture(counter));
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, null, null);
        }

        /** 出门那一截就是终点键本身：订单量必须把它算进去。 */
        private static KeyCounter emittedTerminal(long amount) {
            var emitted = new KeyCounter();
            emitted.add(PRODUCT, amount);
            return emitted;
        }

        /** 出门那一截是<b>别的</b>键：一枚也不许算进终点产量。 */
        private static KeyCounter emittedIron(long amount) {
            var emitted = new KeyCounter();
            emitted.add(AEItemKey.of(Items.IRON_INGOT), amount);
            return emitted;
        }

        /** 只产一格的样板替身：终点键的产量按 {@code 每发量 × 发数} 算。 */
        private record FakePattern(AEItemKey definition, GenericStack output) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(output);
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveBeyondLongOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveBeyondLongOrderCases.beyondLongOrderClosesOncePhysicallyDeliveredInFull", () -> new ParallelRecursiveBeyondLongOrderCases().beyondLongOrderClosesOncePhysicallyDeliveredInFull()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveBeyondLongOrderCases.sentinelWithoutDerivableTruthJudgesNothingWhileTrueLongMaxJudges", () -> new ParallelRecursiveBeyondLongOrderCases().sentinelWithoutDerivableTruthJudgesNothingWhileTrueLongMaxJudges()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveBeyondLongOrderCases.terminalChunkEmittedOutsideTheTaskTableStillCountsIntoTheOrder", () -> new ParallelRecursiveBeyondLongOrderCases().terminalChunkEmittedOutsideTheTaskTableStillCountsIntoTheOrder())
            );
        }
    }

    /** ParallelRecursiveFinalOutputDeliveryCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelRecursiveFinalOutputDeliveryCases {
        private static final HolderLookup.Provider REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final long ORDER = 100L;
        /** 订单要的终点产物。 */
        private static final AEKey PRODUCT = AEItemKey.of(new ItemStack(Items.CHEST));
        /** 排不下去的那条尾巴还挂在产出缓冲上的实物。 */
        private static final AEKey TAIL_MATERIAL = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
        /** 环起步借的那颗种：它不是可花库存，也不算交付量。 */
        private static final AEKey SEED = AEItemKey.of(new ItemStack(Items.BUCKET));

        /** 最终产物先到时，已接单的中间批次仍必须付清超过 long 的材料，才能退回真正余料。 */
        void finalDeliveryCannotReturnUnpaidIntermediateBatchMaterials() {
            BigInteger firings = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3)).add(BigInteger.valueOf(17));
            var intermediate = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(TAIL_MATERIAL, 1), new GenericStack(SEED, 4));
            var job = newRecursiveJob(intermediate, 1, null);
            job.tasks.get(intermediate).addFirings(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings.subtract(BigInteger.ONE)));
            var pool = new git.chexson.chexsonsaeutils.bigint.WideCraftingInventory(ignored -> {});
            BigInteger realSurplus = BigInteger.valueOf(17);
            pool.insertExact(SEED, firings.multiply(BigInteger.valueOf(4)).add(realSurplus), Actionable.MODULATE);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
            var host = new git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost() {
                @Override public boolean supportsDeclaredBatch(IPatternDetails pattern) { return pattern == intermediate; }
                @Override public boolean declaredBatchFiringsUnbounded() { return true; }
                @Override public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] prototype, BigInteger count) {
                    assertEquals(4L, prototype[0].get(SEED));
                    return count;
                }
                @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(intermediate); }
                @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
                    throw new AssertionError("本测试必须经过整批声明");
                }
                @Override public boolean isBusy() { return false; }
            };
            var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, intermediate, job, job.tasks.entrySet(),
                    pool, null, reservation, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                    Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 1);
            assertTrue(push != null && push.accepted() > 0);
            assertEquals(firings.subtract(BigInteger.ONE), job.batchObligations.pendingFirings(intermediate));
            deliver(job, PRODUCT, ORDER);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "中间批次已经生产但材料尚未扣完，不能将待付款的库存作为余料退网");
            for (int batch = 0; batch < 20 && job.batchObligations.hasAnyPending(); batch++) {
                long payable = ParallelBatchExtraction.obligationBatchFirings(intermediate,
                        job.batchObligations.pendingFirings(intermediate));
                assertTrue(ParallelCraftingCpuLogic.payObligationBatch(job, intermediate, payable, pool, null,
                        reservation, new ParallelPoolLedger(), null, null) > 0);
            }
            assertFalse(job.batchObligations.hasAnyPending());
            assertEquals(realSurplus, pool.amount(SEED), "整批发数乘四必须全部实扣，只留下真实余量");
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));
        }

        /** 真实提交与 tick 入口验证：同一个原生声明+fold 提供者先读原生声明，再读 holder 上限。
         * 没有材料使执行在探测后停止；适配壳若被提前探测，网格供应口计数就会从 0 增长。 */
        void nativeDeclaredFoldProviderKeepsCpuProbeOrderAndPriority() {
            var owner = new TestOwner();
            var network = new RecordingNetwork();
            var grid = new TestGrid(network);
            ((TestManagedNode) owner.getMainNode()).grid = grid;
            var gridReads = new java.util.concurrent.atomic.AtomicInteger();
            var cluster = new ParallelCraftingCpuCluster(owner,
                    new git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilitiesFactory() {
                        @Override
                        public git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities create(
                                java.util.function.Supplier<IGrid> supplier) {
                            return new git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesImpl(() -> {
                                gridReads.incrementAndGet();
                                return supplier.get();
                            });
                        }
                        @Override
                        public git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalDrops terminalDrops() {
                            return new ProviderBatchCapabilitiesFactoryImpl().terminalDrops();
                        }
                    });
            var lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
            var pattern = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(PRODUCT, 1L), new GenericStack(SEED, 1L));
            var host = new NativeDeclaredFoldHost(pattern);
            var energy = new ProbeEnergy();
            var service = new ProbeCraftingService(grid, energy, host);
            var plan = new CraftingPlan(new GenericStack(PRODUCT, 8L), 0L, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 8L));
            assertTrue(lane.logic().trySubmitJob(grid, plan, IActionSource.empty(), null).successful());
            lane.logic().tickCraftingLogic(energy, service, 1L, null, null);
            assertEquals(List.of("native", "foldMax"), host.probes);
            assertEquals(0, gridReads.get(), "原生声明优先时不得建立 adapted 宿主壳");
            assertEquals(1, ((TestStorage) grid.storage).providers.size());
            assertTrue(lane.isLaneActive(), "没有材料的任务不能伪报完成");
        }

        private static final class NativeDeclaredFoldHost
                implements git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost,
                git.chexson.chexsonsaeutils.integration.uselessmod.UselessFurnaceBatchHost {
            private final IPatternDetails pattern;
            private final java.util.ArrayList<String> probes = new java.util.ArrayList<>();
            NativeDeclaredFoldHost(IPatternDetails pattern) { this.pattern = pattern; }
            @Override public boolean supportsDeclaredBatch(IPatternDetails details) {
                assertTrue(details == pattern);
                probes.add("native");
                return true;
            }
            @Override public long chexsonsaeutils$maxFoldedPushExecutions(IPatternDetails details) {
                assertTrue(details == pattern);
                probes.add("foldMax");
                return 8L;
            }
            @Override public IPatternDetails chexsonsaeutils$foldPushPattern(IPatternDetails details, long executions) {
                throw unused();
            }
            @Override public BigInteger pushDeclaredBatch(IPatternDetails details, KeyCounter[] inputs,
                    BigInteger executions) { throw unused(); }
            @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(pattern); }
            @Override public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) { throw unused(); }
            @Override public boolean isBusy() { return false; }
        }

        /** AE2 的正常构造路径注册存储提供者，探测用例只定向提供这一台宿主。 */
        private static final class ProbeCraftingService extends appeng.me.service.CraftingService {
            private final NativeDeclaredFoldHost host;
            ProbeCraftingService(TestGrid grid, ProbeEnergy energy, NativeDeclaredFoldHost host) {
                super(grid, grid.storage, energy);
                this.host = host;
            }
            @Override public Iterable<appeng.api.networking.crafting.ICraftingProvider> getProviders(IPatternDetails key) {
                assertTrue(key == host.pattern);
                return List.of(host);
            }
        }

        /** 电力为零，仍允许执行前的宿主能力探测；其余电力观察口不在此场景内。 */
        private static final class ProbeEnergy implements appeng.api.networking.energy.IEnergyService {
            @Override public double extractAEPower(double amount, Actionable mode,
                    appeng.api.config.PowerMultiplier multiplier) { return 0.0; }
            @Override public double getIdlePowerUsage() { throw unused(); }
            @Override public double getChannelPowerUsage() { throw unused(); }
            @Override public double getAvgPowerUsage() { throw unused(); }
            @Override public double getAvgPowerInjection() { throw unused(); }
            @Override public boolean isNetworkPowered() { throw unused(); }
            @Override public double injectPower(double amount, Actionable mode) { throw unused(); }
            @Override public double getStoredPower() { throw unused(); }
            @Override public double getMaxStoredPower() { throw unused(); }
            @Override public double getEnergyDemand(double maxRequired) { throw unused(); }
        }

        void deliveredFinalOutputClosesTheJobAndHandsTheProductOver() {
            // ---------------------------------------------------------------------------------
            // 判据层：真尾巴挂在任务表上，交付齐了就要收工
            // ---------------------------------------------------------------------------------
            var tail = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(TAIL_MATERIAL, 1L),
                    new GenericStack(SEED, 1L));
            var job = newRecursiveJob(tail, 5L, null);
            // 尾巴：5 发排不下去，它申报出去的那份实物也永不回池 —— 改前那条判据正是被这一格钉住的
            job.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "交付一笔都没记、尾巴还挂着：不许收工（也不许砍需求，本刀不动任务表）");

            // 那颗种回池：它是环内部件，一枚都不算交付量（#43/#45 口径）
            job.dyeableRecursiveInternalItems.add(SEED, 1L);
            deliver(job, PRODUCT, 60L);
            deliver(job, SEED, 5L);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "交了 60/100，差 40 枚就是没收工");
            assertEquals(60L, job.finalOutputDeliveredAmount().exactValue().longValueExact(),
                    "种那一笔不许推进交付量");
            assertEquals(0L, job.finalOutputDelivered.getAmount(SEED).signum(),
                    "非终点产物的键一枚都不许混进交付账：混进去就是把还没产出的量报成交齐");

            // 差一口气：99/100
            deliver(job, PRODUCT, 39L);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "99/100 不收工：交付判据不许四舍五入");

            // 中间批次已经接单，也必须交割材料后才算完成。
            job.batchObligations.incur(tail, BigInteger.ONE);
            deliver(job, PRODUCT, 1L);
            assertEquals(ORDER, job.finalOutputDeliveredAmount().exactValue().longValueExact(),
                    "交付账按宽域真值累计到订单量");
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "终点产物已齐，但已接单的中间批次仍欠材料，不能提前退料");
            job.batchObligations.settle(tail, 1L);
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "义务交割干净 + 交付量到订单量 ⇒ 收工，且尾巴仍挂在任务表上："
                            + "尾条目剩余发数=" + job.tasks.get(tail).exactRemaining().exactValue());

            // ---------------------------------------------------------------------------------
            // 真收尾层：AE2 交货到账口那一句 insert 起，走完 finishJob(true)
            // ---------------------------------------------------------------------------------
            var resumeJob = newRecursiveJob(tail, 0L, newRequesterCpuLink());
            resumeJob.waitingFor.insert(PRODUCT, ORDER, Actionable.MODULATE);
            resumeJob.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);
            var lane = new ParallelCraftingLaneState(diagnosticCluster(), UUID.randomUUID(), 0L);
            var logic = lane.logic();
            var logicTag = new CompoundTag();
            logicTag.put("job", resumeJob.writeToNBT(REGISTRIES));
            logic.readFromNBT(logicTag, REGISTRIES);
            var cpuLink = (CraftingLink) lane.getLastLink();
            var requester = new RecordingRequester();
            tieLinkToRequester(cpuLink, requester);

            assertEquals(ORDER, logic.insert(PRODUCT, ORDER, Actionable.MODULATE),
                    "终点产物交到入账口必须按申报量整笔收下");
            assertTrue(cpuLink.isDone(), "markDone 没被走到：作业没有真的进入完成态");
            assertFalse(lane.isLaneActive(), "交付齐了作业还在表上：尾巴把收尾夹住了");
            assertEquals(List.of(PRODUCT + "x" + ORDER), requesteeDeliveries(requester),
                    "requester 收到的必须正好是订单要的终点产物");
            assertEquals(0L, lane.getRequestedAmount(TAIL_MATERIAL),
                    "尾巴那份挂账随收尾结清（finishJob 的 waitingFor.clear），不留幽灵需求");

            var dumped = new CompoundTag();
            logic.writeToNBT(dumped, REGISTRIES);
            assertTrue(dumped.getList("inventory", Tag.TAG_COMPOUND).isEmpty(),
                    "收尾后 CPU 库存必须是空池：残池投网是 storeItems() 那一步的既有出口，"
                            + "本刀不许在作业还挂着的时候把料留在池里");
        }

        /** 退出前已到账 40 枚，重进后再到 60 枚：累计到齐即收尾，并真实交付全部 100 枚。 */
        void partialDeliveryBeforeReloadCountsTowardActualFinalDeliveryAfterReload() {
            var job = newRecursiveJob(Map.of(), newRequesterCpuLink());
            job.addFinalOutputDelivered(PRODUCT, 40L);
            job.waitingFor.insert(PRODUCT, 60L, Actionable.MODULATE);
            job.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);
            var pool = new appeng.crafting.inv.ListCraftingInventory(ignored -> {});
            pool.insert(PRODUCT, 40L, Actionable.MODULATE);
            var saved = new CompoundTag();
            saved.put("job", job.writeToNBT(REGISTRIES));
            saved.put("inventory", pool.writeToNBT(REGISTRIES));
            var lane = new ParallelCraftingLaneState(diagnosticCluster(), UUID.randomUUID(), 0L);
            lane.logic().readFromNBT(saved, REGISTRIES);
            var link = (CraftingLink) lane.getLastLink();
            var requester = new RecordingRequester();
            tieLinkToRequester(link, requester);
            assertTrue(lane.isLaneActive());
            assertEquals(60L, lane.logic().insert(PRODUCT, 60L, Actionable.MODULATE));
            assertTrue(link.isDone());
            assertFalse(lane.isLaneActive());
            assertEquals(List.of(PRODUCT + "x" + ORDER), requesteeDeliveries(requester));
            assertEquals(0L, lane.getRequestedAmount(TAIL_MATERIAL));
        }

        /**
         * FIX-F 的反向半边（不许把收窄做成新的提前收工）：欠账挂在<b>产出终点键的那张样板</b>上时，
         * 终点量凑齐也不许收工——那一发的材料还欠着宿主，收工等于让宿主白多产（#92 那一型）。
         *
         * <p>夹具两条前提照旧钉住：任务表非空、{@code waitingFor} 上挂着排不下去的尾巴 ⇒
         * {@code isDyeableRecursiveJobComplete} 那条老臂恒假，这一条断言打的只有交付臂。
         */
        void pendingObligationOnATerminalProducerStillBlocksTheDeliveryArm() {
            var tail = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(TAIL_MATERIAL, 1L),
                    new GenericStack(SEED, 1L));
            // 这张样板的产出就是终点键：它的欠账仍算这一单的欠账
            var terminalProducer = new FakePattern(AEItemKey.of(Items.REDSTONE),
                    new GenericStack(PRODUCT, 1L),
                    new GenericStack(SEED, 1L));
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(tail, 5L);
            times.put(terminalProducer, 3L);
            var job = newRecursiveJob(times, null);
            job.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);

            deliver(job, PRODUCT, ORDER);
            assertEquals(ORDER, job.finalOutputDeliveredAmount().exactValue().longValueExact(),
                    "交付账已凑齐订单量");
            job.batchObligations.incur(terminalProducer, BigInteger.ONE);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "产出终点键的那张样板还欠一发材料没交割就收工 = 宿主白多产一发终点件（#92 那一型）："
                            + "FIX-F 收窄的是欠账的『谁』，不是把交付臂放开成提前收工");

            job.batchObligations.incur(tail, BigInteger.ONE);
            job.batchObligations.settle(terminalProducer, 1L);
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "中间批次欠账未清仍不能收工");
            job.batchObligations.settle(tail, 1L);
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job));
        }

        /**
         * 交付账<b>唯一</b>的实物写口：终点产物交到 CPU 入账口那一刻记的那一笔——
         * {@code ParallelCraftingCpuLogic#insertInternal} 认成终点产物那一支走的正是这个方法。
         *
         * <p>宿主自报"不经池子直接交出去"的那一截（{@code reserveOne} 的分流口）不再进这本账：
         * 那是按配方算出的计划量，通道里没有一枚实物回执（FIX-C，见
         * {@code DeclaredBatchPlannedOutputs} 类注释）。它只摘 {@code waitingFor}、只记预期账，
         * 这一条由 {@link #hostOverReportedPlannedAmountNeverCountsAsDelivery()} 钉住。
         */
        private static void deliver(ParallelExecutingCraftingJob job, AEKey what, long amount) {
            job.addFinalOutputDelivered(what, amount);
        }

        /**
         * FIX-C：交付账只认<b>实物</b>。宿主虚报一个大于订单量的 planned、实物一件没回 ⇒ 作业不许收工。
         *
         * <p>未修树（{@code 22bbc22}）上这一条红：那一版在 {@code reserveOne} 的分流口上把 planned 直接
         * 记进了交付账，虚报 1000 枚（申报只有 100 枚）就凑够订单量、把作业判成完成，而网络里一件实物没有。
         */
        void hostOverReportedPlannedAmountNeverCountsAsDelivery() {
            var tail = new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack(TAIL_MATERIAL, 1L),
                    new GenericStack(SEED, 1L));
            var job = newRecursiveJob(tail, 5L, null);
            job.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);

            // 申报 100 枚产物，宿主自报计划入网 1000 枚（远大于订单量），实物一件没回
            var announced = new KeyCounter();
            announced.add(PRODUCT, ORDER);
            var lyingPlannedToNetwork = new KeyCounter();
            lyingPlannedToNetwork.add(PRODUCT, ORDER * 10L);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, announced, new KeyCounter(),
                    lyingPlannedToNetwork);

            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "宿主虚报 planned 就把作业判成收工：实物一件没进网络，交付账只认实物");

            // 分流本身照旧成立：这一截不再等实物，但"不再等"不等于"已交付"
            assertEquals(0L, job.finalOutputDeliveredAmount().exactValue().signum(),
                    "planned 那一截一枚都不许进交付账");
            assertEquals(ORDER, job.expectedToNetwork(PRODUCT).exactValue().longValueExact(),
                    "预期入网账仍按 min(自报, 申报) 记满 100 枚");
            assertEquals(0L, job.waitingForExact(PRODUCT).exactValue().signum(),
                    "申报的 100 枚已被自报计划摘走，不再挂实物预期");
        }

        /**
         * 实机 lane {@code 00587962}（{@code ae2:creative_storage_cell x 10000}、{@code RecursiveCraftingPlan}）
         * 那一型：终点键<b>整批</b>走"申报批→宿主直投网络"（{@code plannedToNetwork}），CPU 入账口
         * <b>一枚实物都没见过</b>，任务表此时已经排空，池里只剩环起步借的那颗种。
         *
         * <p><b>改前红在"没人问"</b>：那条收工判据（{@link ParallelCraftingCpuLogic#isDyeableRecursiveJobComplete}）
         * 的三个求值点全在 {@code insertInternal} 里（实物到账那一刻），而这一腿压根不进
         * {@code insertInternal} ⇒ 判据成立也没有谁去读它 ⇒ 不进 {@code finishJob(true)} ⇒ 那颗种跟着
         * 卡在 CPU 里（实机日志在 {@code 13:19:12.644 … 清单={ae2:creative_storage_cell=10000}} 之后
         * 这一条 lane 一行不剩；{@code per100r} 与 {@code stall} 两条读数一起哑火，是因为
         * {@code executeCrafting} 在 {@code job.tasks.isEmpty()} 处直接 return）。
         *
         * <p>本条把<b>什么时候问</b>扩成"每 tick 也问"，交付账与三本流水一字未动：交付账仍是
         * {@code 0}（{@code planned} 不是实物回执，#116 的口径），收尾仍走既有 {@code finishJob(true)}
         * → {@code storeItems()}。
         *
         * <p><b>#227 K2 之后这一条要两步才讲得清，两步都被钉住</b>：
         * <ol>
         *   <li><b>本场内</b>：第二臂读 {@code planNet − 已交割}，所以这一腿要真收工，得有该笔的终局凭据
         *       把划走的那一截转成已交割——夹具因此补一步 {@code noteAdmitted + deliverTerminal(SUCCESS)}
         *       （{@link #creditTheDirectInjectionBatchWithASuccessTerminal}，走的就是
         *       {@code UselessBigIntegerApiBridge#onBatchFinished} 回调体的那一句）。<b>没</b>这一步的那一档
         *       照样拦住收工，由 {@code ParallelDeclaredBatchDeliveryCredentialTest} 的
         *       {@code planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen} 钉着；</li>
         *   <li><b>续传</b>：这条夹具的 lane 是靠 {@code writeToNBT}/{@code readFromNBT} 那趟往返种出来的，
         *       也就是续传形状。交回这一截的凭据（台账与它的 {@code batchId}）按既有口径<b>不落 NBT</b> ⇒
         *       装载时把落盘回来的直投预期整本退回同一个退账出口
         *       （{@code ParallelExecutingCraftingJob#dropRestoredExpectedToNetwork}）——它既转不成已交割、
         *       也不许拦收工，留着就是 {@code #209} 那一型（一笔永远等不到凭据的预期把作业永久钉在"未收工"）。
         *       本条的 {@code cpuLink.isDone()} 那一格就是从这一档取证的：退了账，tick 才收得了工。</li>
         * </ol>
         */
        void tickClosingAsksTheCriterionWhenNoPhysicalDeliveryEverArrives() throws Exception {
            var job = newRecursiveJob(Map.of(), newCpuLink());
            var announced = new KeyCounter();
            announced.add(PRODUCT, ORDER);
            var plannedToNetwork = new KeyCounter();
            plannedToNetwork.add(PRODUCT, ORDER);
            var recordedPlanned = ParallelCraftingCpuLogic.reserveExpectedWaiting(
                    job, announced, new KeyCounter(), plannedToNetwork);
            creditTheDirectInjectionBatchWithASuccessTerminal(job, recordedPlanned);

            assertEquals(0L, job.finalOutputDeliveredAmount().exactValue().signum(),
                    "夹具前提：整批走宿主直投网络，交付账恒 0 —— planned 不是实物回执（#116 口径）");
            assertEquals(ORDER, job.expectedToNetwork(PRODUCT).exactValue().longValueExact(),
                    "预期入网账照旧按自报量记满：SUCCESS 不动这本，只把同一份枚数并进交割账");
            assertEquals(0L, job.declaredNetworkUndelivered(PRODUCT).exactValue().signum(),
                    "夹具前提：该笔终局 SUCCESS 已到，直投差值清零（本场内第二臂读的就是这一格）");
            assertTrue(job.tasks.isEmpty() && job.waitingFor.list.isEmpty(),
                    "夹具前提：任务表已排空、产出缓冲已被自报计划摘清");
            assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "判据本身成立（第二臂：任务表空 ∧ 产出缓冲空 ∧ 直投差值已按该笔 SUCCESS 清掉）"
                            + "——缺的从来不是判据，是有人来问它");

            var network = new RecordingNetwork();
            var lane = offlineLaneSeededWith(job, network);
            var logic = lane.logic();
            var cpuLink = (CraftingLink) lane.getLastLink();

            assertTrue(logic.tickCraftingLogic(null, null, 0L, null, null),
                    "任务表已空：这一 tick 推不出任何一发，返回 idle 才对得上产线那条调度");

            assertFalse(logic.hasJob(), "手动订单必须真正释放作业；原生 standalone link 没有收货 nexus");
            assertFalse(lane.isLaneActive(),
                    "判据成立却没人问：作业仍挂在 lane 上（实机那一跑只能手动取消，取消后那颗种不退网、下一条 lane 的环饿死）");
            assertEquals(1L, network.stored(SEED), "环起步借的那颗种必须经 storeItems() 退回网络");
            assertEquals(0L, network.stored(PRODUCT), "网络侧不许多出终点产物：收工不伪造实物");
            assertTrue(logic.isInventoryEmpty(), "收尾后 CPU 池必须是空池");
            assertTrue(cpuLink.isStandalone(), "直投网络的手动订单不绑定 requester");
        }

        /**
         * 反向半边（不许把"每 tick 也问"做成"还没交割就收工"）：终点键在 {@code waitingFor} 上仍挂着
         * 一截<b>实物</b>欠账时，同一趟 tick 既不收工、也不许把那颗种投回网络。
         */
        void tickKeepsTheJobOpenWhilePhysicalProductIsStillOwed() throws Exception {
            var job = newRecursiveJob(Map.of(), newCpuLink());
            var announced = new KeyCounter();
            announced.add(PRODUCT, ORDER);
            var plannedToNetwork = new KeyCounter();
            plannedToNetwork.add(PRODUCT, ORDER - 10L);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, announced, new KeyCounter(), plannedToNetwork);

            assertEquals(10L, job.waitingForExact(PRODUCT).exactValue().longValueExact(),
                    "夹具前提：终点键还挂着一截实物欠账（宿主只报了 90/100）");
            assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                    "交付臂没凑齐、老臂被 waitingFor 那一格拦住：判据本身就不成立");

            var network = new RecordingNetwork();
            var lane = offlineLaneSeededWith(job, network);
            var logic = lane.logic();
            var cpuLink = (CraftingLink) lane.getLastLink();

            logic.tickCraftingLogic(null, null, 0L, null, null);

            assertFalse(cpuLink.isDone(), "还欠 10 枚实物就 markDone = 把「还没交割」判成收工");
            assertTrue(lane.isLaneActive(), "waitingFor 仍挂着实物欠账那一格非空 ⇒ 这一趟 tick 不许收工");
            assertEquals(0L, network.stored(SEED), "那颗种仍留在 CPU 池里，不许随一次 tick 退网");
            assertFalse(logic.isInventoryEmpty(), "作业还在跑：池里那颗种还是 CPU 的");
        }

        /** 声明批中间产物的接收账，经真实 CPU 插入口完整落入宽域库存。 */
        void declaredIntermediateOutputsEnterTheActualCpuPoolBeyondLong() throws Exception {
            var job = newRecursiveJob(Map.of(), newCpuLink());
            job.dyeableRecursiveInternalItems.add(SEED, 1L);
            job.waitingFor.insert(PRODUCT, ORDER, Actionable.MODULATE);
            var outputs = new KeyCounter();
            outputs.add(SEED, Long.MAX_VALUE);
            for (int batch = 0; batch < 3; batch++) {
                assertTrue(ParallelCraftingCpuLogic.reserveExpectedWaiting(
                        job, outputs, new KeyCounter(), outputs).isEmpty());
            }
            var network = new RecordingNetwork();
            var lane = offlineLaneSeededWith(job, network);
            for (int batch = 0; batch < 3; batch++) {
                assertEquals(Long.MAX_VALUE, lane.logic().insert(SEED, Long.MAX_VALUE, Actionable.MODULATE));
            }
            var saved = new CompoundTag();
            lane.logic().writeToNBT(saved, REGISTRIES);
            var actualPool = new git.chexson.chexsonsaeutils.bigint.WideCraftingInventory(ignored -> {});
            actualPool.readFromNBT(saved.getList("inventory", Tag.TAG_COMPOUND), REGISTRIES);
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3L)).add(BigInteger.ONE),
                    actualPool.amount(SEED), "回交的全部中间产物与原有种子均保留在 CPU 实物库存");
            assertEquals(0L, network.stored(SEED), "材料没有绕到公共盘里等待再提取");
            assertTrue(lane.isLaneActive(), "终点实物仍待交付，接收中间材料不提前收工");
        }

        /**
         * 把上面那份作业挂进一条<b>能跑产线 tick</b> 的离线 lane：池子里只有环起步借的那颗种，
         * 网络侧一开始全空。作业经 {@code writeToNBT}/{@code readFromNBT} 那条真链进来，
         * 与既有真收尾用例同一条夹具口径（空任务表不触发样板 decode，离线可跑）。
         */
        private static ParallelCraftingLaneState offlineLaneSeededWith(
                ParallelExecutingCraftingJob job, MEStorage network) throws Exception {
            var lane = new ParallelCraftingLaneState(offlineCluster(network), UUID.randomUUID(), 0L);
            var logicTag = new CompoundTag();
            logicTag.put("job", job.writeToNBT(REGISTRIES));
            var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(new ParallelPoolLedger(), ignored -> {
            });
            pool.insert(SEED, 1L, Actionable.MODULATE);
            logicTag.put("inventory", pool.writeToNBT(REGISTRIES));
            lane.logic().readFromNBT(logicTag, REGISTRIES);
            return lane;
        }

        /** 通过 AE2 的受保护节点创建入口构造合法宿主，公开主节点在构造结束后绑定测试网格。 */
        /** 仅供真实诊断入口使用；合法节点仍绑定记录型存储网格。 */
        static ParallelCraftingCpuCluster diagnosticCluster() {
            return offlineCluster(new RecordingNetwork());
        }

        /** 注入可观察装配口验证诊断不初始化会话，其他构造路径与生产宿主相同。 */
        static ParallelCraftingCpuCluster diagnosticCluster(
                git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilitiesFactory factory) {
            var owner = new TestOwner();
            ((TestManagedNode) owner.getMainNode()).grid = new TestGrid(new RecordingNetwork());
            return new ParallelCraftingCpuCluster(owner, factory);
        }

        static ParallelCraftingCpuCluster offlineCluster(MEStorage network) {
            var owner = new TestOwner();
            ((TestManagedNode) owner.getMainNode()).grid = new TestGrid(network);
            return new ParallelCraftingCpuCluster(owner, new ProviderBatchCapabilitiesFactoryImpl());
        }

        private static final class TestOwner extends AE2ParallelCpuToolBlockEntity {
            TestOwner() {
                super(net.minecraft.core.BlockPos.ZERO,
                        ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_BLOCK.get().defaultBlockState());
            }
            @Override protected IManagedGridNode createMainNode() { return new TestManagedNode(); }
        }

        /** 仅提供真实存储服务的合法网格桩，可供同包网络诊断用例复用。 */
        static final class TestGrid implements IGrid {
            private final IStorageService storage;
            TestGrid(MEStorage inventory) { storage = new TestStorage(inventory); }
            public <C extends appeng.api.networking.IGridService> C getService(Class<C> iface) {
                if (iface == IStorageService.class) { return iface.cast(storage); }
                throw new UnsupportedOperationException("测试未配置网格服务：" + iface);
            }
            public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { return event; }
            public Iterable<Class<?>> getMachineClasses() { throw unused(); }
            public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) { throw unused(); }
            public <T> java.util.Set<T> getMachines(Class<T> machineClass) { throw unused(); }
            public <T> java.util.Set<T> getActiveMachines(Class<T> machineClass) { throw unused(); }
            public Iterable<IGridNode> getNodes() { return List.of(); }
            public boolean isEmpty() { throw unused(); }
            public IGridNode getPivot() { throw unused(); }
            public int size() { throw unused(); }
            public void export(com.google.gson.stream.JsonWriter writer) { throw unused(); }
        }

        private static final class TestStorage implements IStorageService {
            private final MEStorage inventory;
            private final java.util.ArrayList<appeng.api.storage.IStorageProvider> providers = new java.util.ArrayList<>();
            TestStorage(MEStorage inventory) { this.inventory = inventory; }
            public MEStorage getInventory() { return inventory; }
            public KeyCounter getCachedInventory() { return inventory.getAvailableStacks(); }
            public void addGlobalStorageProvider(appeng.api.storage.IStorageProvider provider) { providers.add(provider); }
            public void removeGlobalStorageProvider(appeng.api.storage.IStorageProvider provider) {
                if (!providers.remove(provider)) { throw unused(); }
            }
            public void refreshNodeStorageProvider(IGridNode node) { throw unused(); }
            public void refreshGlobalStorageProvider(appeng.api.storage.IStorageProvider provider) { throw unused(); }
            public void invalidateCache() { throw unused(); }
        }

        /** 构造期配置口接收设置，执行期只暴露在线状态及绑定的真实网格桩。 */
        private static final class TestManagedNode implements IManagedGridNode {
            private IGrid grid;
            private final IGridNode node = new TestNode(this);
            public void destroy() { throw unused(); }
            public void create(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) { throw unused(); }
            public void loadFromNBT(CompoundTag tag) { throw unused(); }
            public void saveToNBT(CompoundTag tag) { throw unused(); }
            public IManagedGridNode setFlags(appeng.api.networking.GridFlags... flags) { return this; }
            public IManagedGridNode setExposedOnSides(java.util.Set<net.minecraft.core.Direction> sides) { return this; }
            public IManagedGridNode setIdlePowerUsage(double usage) { return this; }
            public IManagedGridNode setVisualRepresentation(AEItemKey icon) { return this; }
            public IManagedGridNode setInWorldNode(boolean accessible) { return this; }
            public IManagedGridNode setTagName(String tagName) { return this; }
            public IManagedGridNode setGridColor(appeng.api.util.AEColor color) { return this; }
            public <T extends appeng.api.networking.IGridNodeService> IManagedGridNode addService(Class<T> type, T service) {
                throw unused();
            }
            public boolean isReady() { return grid != null; }
            public boolean isActive() { return grid != null; }
            public boolean isOnline() { return grid != null; }
            public boolean isPowered() { return grid != null; }
            public boolean hasGridBooted() { return grid != null; }
            public void setOwningPlayerId(int id) { throw unused(); }
            public void setOwningPlayer(net.minecraft.world.entity.player.Player player) { throw unused(); }
            public IGridNode getNode() { return node; }
        }

        private record TestNode(TestManagedNode managed) implements IGridNode {
            public <T extends appeng.api.networking.IGridNodeService> T getService(Class<T> type) { throw unused(); }
            public Object getOwner() { throw unused(); }
            public void beginVisit(appeng.api.networking.IGridVisitor visitor) { throw unused(); }
            public IGrid getGrid() { return managed.grid; }
            public net.minecraft.server.level.ServerLevel getLevel() { throw unused(); }
            public java.util.Set<net.minecraft.core.Direction> getConnectedSides() { throw unused(); }
            public Map<net.minecraft.core.Direction, appeng.api.networking.IGridConnection> getInWorldConnections() {
                throw unused();
            }
            public List<appeng.api.networking.IGridConnection> getConnections() { throw unused(); }
            public boolean hasGridBooted() { return true; }
            public boolean isPowered() { return true; }
            public boolean meetsChannelRequirements() { return true; }
            public boolean hasFlag(appeng.api.networking.GridFlags flag) { throw unused(); }
            public int getOwningPlayerId() { throw unused(); }
            public UUID getOwningPlayerProfileId() { throw unused(); }
            public double getIdlePowerUsage() { throw unused(); }
            public AEItemKey getVisualRepresentation() { throw unused(); }
            public appeng.api.util.AEColor getGridColor() { throw unused(); }
            public void fillCrashReportCategory(net.minecraft.CrashReportCategory category) { throw unused(); }
            public int getMaxChannels() { throw unused(); }
            public int getUsedChannels() { throw unused(); }
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("此夹具不支持该测试入口");
        }

        /** 网络侧存储桩：只把 {@code insert} 落进一张表，供"种退回网络"那一格读。 */
        private static final class RecordingNetwork implements MEStorage {
            private final KeyCounter stored = new KeyCounter();

            private long stored(AEKey what) {
                return stored.get(what);
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
                if (mode == Actionable.MODULATE) {
                    stored.add(what, amount);
                }
                return amount;
            }

            @Override
            public Component getDescription() {
                return Component.literal("recording-network");
            }
        }

        private static List<String> requesteeDeliveries(RecordingRequester requester) {
            return requester.delivered.keySet().stream()
                    .map(key -> key + "x" + requester.delivered.get(key))
                    .toList();
        }

        /** CPU 侧那根 link：只为了让 {@code writeToNBT} 能把它序列化出去（读回来的是产线自己造的那根）。 */
        private static CraftingLink newCpuLink() {
            return new CraftingLink(
                    CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                    (appeng.api.networking.crafting.ICraftingCPU) null);
        }

        private static CraftingLink newRequesterCpuLink() {
            return new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), false, false),
                    (appeng.api.networking.crafting.ICraftingCPU) null);
        }

        /** 递归计划 + 递归契约：终点产物 {@code PRODUCT x ORDER}，契约量同为 ORDER。 */
        private static ParallelExecutingCraftingJob newRecursiveJob(
                IPatternDetails tail, long tailFirings, @Nullable CraftingLink link) {
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(tail, tailFirings);
            return newRecursiveJob(times, link);
        }

        /** 同一份夹具，任务表按传入的 {@code 样板 → 发数} 建（FIX-F 要两张样板各占一格）。 */
        private static ParallelExecutingCraftingJob newRecursiveJob(
                Map<IPatternDetails, Long> times, @Nullable CraftingLink link) {
            var base = new CraftingPlan(new GenericStack(PRODUCT, ORDER), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    ORDER, Map.of(), Map.of(), null, null);
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, link, null);
        }

        /**
         * 把这一批"直投网络"的产出按产线那条凭据转成<b>已交割</b>：{@code noteAdmitted} 挂上在飞台账，
         * 再沿 {@code UselessBigIntegerReceipt#deliverTerminal} 打回 {@code SUCCESS}——那一句就是
         * {@code UselessBigIntegerApiBridge#onBatchFinished} 回调体的<b>全部</b>实质动作。
         *
         * <p>交过去的枚数是 {@code reserveExpectedWaiting} 刚划走的那一份（与 {@code planNet} 同一处算式），
         * 所以本场内的直投差值就此清零。#227 K2 之后第二臂读的是那道差，本夹具因此需要这一步。
         */
        private static void creditTheDirectInjectionBatchWithASuccessTerminal(
                ParallelExecutingCraftingJob job, Map<AEKey, Long> recordedPlanned) {
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            var batchId = UUID.randomUUID();
            receipt.noteBatchId(batchId);
            var pendingWide = new LinkedHashMap<AEKey, BigInteger>();
            recordedPlanned.forEach((key, amount) -> pendingWide.put(key, BigInteger.valueOf(amount)));
            var producer = new FakePattern(AEItemKey.of(Items.BARREL),
                    new GenericStack(PRODUCT, 1L), new GenericStack(AEItemKey.of(Items.SAND), 1L));
            job.declaredTerminals.noteAdmitted(receipt, producer, BigInteger.ONE, 0L,
                    job::rollBackDeclaredBatchFirings, pendingWide, false,
                    job::creditDeclaredBatchDelivered);
            receipt.deliverTerminal(batchId, DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        }

        /** 把 CPU 侧那根 link 接到 requester 侧：产线上这两根 link 靠同一颗 nexus 成对。 */
        private static void tieLinkToRequester(CraftingLink cpuLink, ICraftingRequester requester) {
            var nexus = new CraftingLinkNexus(cpuLink.getCraftingID());
            cpuLink.setNexus(nexus);
            var requesterLink = new CraftingLink(
                    CraftingCpuHelper.generateLinkData(cpuLink.getCraftingID(), false, true), requester);
            requesterLink.setNexus(nexus);
        }

        private record FakePattern(AEItemKey definition, GenericStack output,
                GenericStack seedInput) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new FakeInput(seedInput)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(output);
            }
        }

        private record FakeInput(GenericStack candidate) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {candidate};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return candidate != null && candidate.what().equals(input);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        /** 收货的 requester：记录每一笔交付与每一次作业状态变化。 */
        private static final class RecordingRequester implements ICraftingRequester {
            private final KeyCounter delivered = new KeyCounter();

            @Override
            public long insertCraftedItems(ICraftingLink link, AEKey what, long amount,
                    Actionable mode) {
                if (mode == Actionable.MODULATE) {
                    delivered.add(what, amount);
                }
                return amount;
            }

            @Override
            public ImmutableSet<ICraftingLink> getRequestedJobs() {
                return ImmutableSet.of();
            }

            @Override
            public void jobStateChange(ICraftingLink link) {
            }

            @Override
            public IGridNode getActionableNode() {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.finalDeliveryCannotReturnUnpaidIntermediateBatchMaterials", () -> new ParallelRecursiveFinalOutputDeliveryCases().finalDeliveryCannotReturnUnpaidIntermediateBatchMaterials()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.nativeDeclaredFoldProviderKeepsCpuProbeOrderAndPriority", () -> new ParallelRecursiveFinalOutputDeliveryCases().nativeDeclaredFoldProviderKeepsCpuProbeOrderAndPriority()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.deliveredFinalOutputClosesTheJobAndHandsTheProductOver", () -> new ParallelRecursiveFinalOutputDeliveryCases().deliveredFinalOutputClosesTheJobAndHandsTheProductOver()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.partialDeliveryBeforeReloadCountsTowardActualFinalDeliveryAfterReload", () -> new ParallelRecursiveFinalOutputDeliveryCases().partialDeliveryBeforeReloadCountsTowardActualFinalDeliveryAfterReload()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.pendingObligationOnATerminalProducerStillBlocksTheDeliveryArm", () -> new ParallelRecursiveFinalOutputDeliveryCases().pendingObligationOnATerminalProducerStillBlocksTheDeliveryArm()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.hostOverReportedPlannedAmountNeverCountsAsDelivery", () -> new ParallelRecursiveFinalOutputDeliveryCases().hostOverReportedPlannedAmountNeverCountsAsDelivery()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.tickClosingAsksTheCriterionWhenNoPhysicalDeliveryEverArrives", () -> new ParallelRecursiveFinalOutputDeliveryCases().tickClosingAsksTheCriterionWhenNoPhysicalDeliveryEverArrives()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.tickKeepsTheJobOpenWhilePhysicalProductIsStillOwed", () -> new ParallelRecursiveFinalOutputDeliveryCases().tickKeepsTheJobOpenWhilePhysicalProductIsStillOwed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRecursiveFinalOutputDeliveryCases.declaredIntermediateOutputsEnterTheActualCpuPoolBeyondLong", () -> new ParallelRecursiveFinalOutputDeliveryCases().declaredIntermediateOutputsEnterTheActualCpuPoolBeyondLong())
            );
        }
    }

    /** ParallelReturnedOutputProgressCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelReturnedOutputProgressCases {
        private static final AEKey ROOT = AEItemKey.of(Items.CHEST);
        private static final AEKey ITEM = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey FLUID = AEFluidKey.of(Fluids.WATER);

        void actualLongReturnUpdatesBothTrackerAndMenuStatus() {
            var emitted = new KeyCounter();
            emitted.add(ITEM, 4);
            var plan = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                    new KeyCounter(), emitted, new KeyCounter(), Map.of());
            var job = job(plan);
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var saved = new CompoundTag();
            saved.put("job", job.writeToNBT(registries));
            var lane = new ParallelCraftingLaneState(DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.diagnosticCluster(), UUID.randomUUID(), 0);
            lane.logic().readFromNBT(saved, registries);
            assertEquals(1, lane.insertIntoWaiting(ITEM, 1, Actionable.SIMULATE));
            assertEquals(0.0f, lane.logic().getElapsedTimeTracker().getProgress());
            assertEquals(1, lane.insertIntoWaiting(ITEM, 1, Actionable.MODULATE));
            assertEquals(0.25f, lane.logic().getElapsedTimeTracker().getProgress());
            var status = lane.getJobStatus();
            assertEquals(0.25, status.progress() / (double) status.totalItems(), 0.000001);
            assertEquals(3, lane.insertIntoWaiting(ITEM, 10, Actionable.MODULATE));
            assertEquals(1.0f, lane.logic().getElapsedTimeTracker().getProgress());
            assertEquals(0, lane.insertIntoWaiting(ITEM, 10, Actionable.MODULATE));
            assertEquals(1.0f, lane.logic().getElapsedTimeTracker().getProgress());
        }

        void patternFluidReturnUsesTheSameRawUnitsAsItsPlannedWork() {
            long unit = FLUID.getAmountPerUnit();
            var pattern = new Pattern(List.of(new GenericStack(ITEM, 4), new GenericStack(FLUID, 4 * unit)));
            var plan = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 1L));
            var job = job(plan);
            var waiting = (WideCraftingInventory) job.waitingFor;
            waiting.insert(ITEM, 4, Actionable.MODULATE);
            waiting.insert(FLUID, 4 * unit, Actionable.MODULATE);
            var pool = new WideCraftingInventory(ignored -> {});
            assertEquals(BigInteger.ONE, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, ITEM, BigInteger.ONE, ignored -> {}));
            assertEquals(0.125f, job.timeTracker.getProgress(), 0.000001f);
            assertEquals(BigInteger.valueOf(unit), ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, BigInteger.valueOf(unit), ignored -> {}));
            assertEquals(0.25f, job.timeTracker.getProgress(), 0.000001f);
            ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, ITEM, BigInteger.valueOf(3), ignored -> {});
            ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, BigInteger.valueOf(3 * unit), ignored -> {});
            assertEquals(1.0f, job.timeTracker.getProgress());
        }

        void wideFluidReturnAdvancesExactlyAndSurvivesSaveReload() {
            long unit = FLUID.getAmountPerUnit();
            var pattern = new Pattern(List.of(new GenericStack(FLUID, 4 * unit)));
            var firings = BigInteger.TEN.pow(40);
            var total = firings.multiply(BigInteger.valueOf(4 * unit));
            var crafts = new git.chexson.chexsonsaeutils.bigint.WideCraftCounter();
            crafts.add(pattern, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(firings));
            var base = new CraftingPlan(new GenericStack(ROOT, 100), 0, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, Long.MAX_VALUE));
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(base,
                    git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot.empty(),
                    git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot.capture(crafts), Set.of());
            var job = job(plan);
            ((WideCraftingInventory) job.waitingFor).insertExact(FLUID, total, Actionable.MODULATE);
            var pool = new WideCraftingInventory(ignored -> {});
            var first = total.divide(BigInteger.valueOf(4));
            assertEquals(first, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, first, ignored -> {}));
            assertEquals(0.25f, job.timeTracker.getProgress());
            var restored = new ParallelElapsedTimeTracker(job.timeTracker.writeToNBT());
            assertEquals(0.25f, restored.getProgress());
            assertEquals(total.subtract(first), ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, total, ignored -> {}));
            assertEquals(1.0f, job.timeTracker.getProgress());
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, FLUID, total, ignored -> {}));
            assertEquals(1.0f, job.timeTracker.getProgress());
        }

        private static ParallelExecutingCraftingJob job(appeng.api.networking.crafting.ICraftingPlan plan) {
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                    (appeng.api.networking.crafting.ICraftingCPU) null);
            return new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        }

        private record Pattern(List<GenericStack> outputs) implements IPatternDetails {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return outputs; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelReturnedOutputProgressCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelReturnedOutputProgressCases.actualLongReturnUpdatesBothTrackerAndMenuStatus", () -> new ParallelReturnedOutputProgressCases().actualLongReturnUpdatesBothTrackerAndMenuStatus()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelReturnedOutputProgressCases.patternFluidReturnUsesTheSameRawUnitsAsItsPlannedWork", () -> new ParallelReturnedOutputProgressCases().patternFluidReturnUsesTheSameRawUnitsAsItsPlannedWork()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelReturnedOutputProgressCases.wideFluidReturnAdvancesExactlyAndSurvivesSaveReload", () -> new ParallelReturnedOutputProgressCases().wideFluidReturnAdvancesExactlyAndSurvivesSaveReload())
            );
        }
    }

    /** ParallelRunningAccountsWideLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelRunningAccountsWideLedgerCases {
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

        /** 三批各挂 {@code Long.MAX_VALUE} 枚：报称账精确到 3×，对外 long 读数钉在天花板。 */
        void announcedAccountRemembersTheExactTotalAcrossBatchesBeyondLong() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);

            for (int batch = 1; batch <= 3; batch++) {
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            }

            var exactTotal = LONG_MAX.multiply(BigInteger.valueOf(3L));
            assertEquals(exactTotal, job.accountAnnounced.getExact(PRODUCT),
                    "报称产出流水账跨批复加逐位精确：这才是账本本身装得下 >long 的意思");
            assertTrue(job.accountAnnounced.hasOverflow(), "越界那一截确实进了溢出侧表");
            assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT),
                    "对外 long 读数（停滞日志的 ann=）仍是饱和天花板：口径与本刀之前一字不变");
            assertNotEquals(exactTotal.longValue(), job.accountAnnounced.getLong(PRODUCT),
                    "精确值不在对外 long 读口上——那条口只报饱和");

            var legacy = new KeyCounter();
            for (int batch = 1; batch <= 3; batch++) {
                legacy.add(PRODUCT, Long.MAX_VALUE);
            }
            assertEquals(Long.MAX_VALUE - 2L, legacy.get(PRODUCT),
                    "老口径：3×Long.MAX 裸加回绕成 MAX-2，既不是真值也不是天花板");
            assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT),
                    "新口径至少被钉在 long 天花板下：判「够不够」只会低估、不会假通过");
        }

        /** 同一组数（3 × 4e18 枚）的差分：老 {@link KeyCounter} 回绕成负数，宽域账给精确值。 */
        void oldLongLedgerWrappedNegativeOnTheSameStreamWhileWideLedgerStaysExact() {
            long perBatch = 4_000_000_000_000_000_000L;
            var exactTotal = BigInteger.valueOf(perBatch).multiply(BigInteger.valueOf(3L));
            assertTrue(exactTotal.compareTo(LONG_MAX) > 0,
                    "本用例确实越界：1.2e19 枚 > 9.22e18，正是老口径记不住的那一档");

            var legacy = new KeyCounter();
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, perBatch);
            for (int batch = 1; batch <= 3; batch++) {
                legacy.add(PRODUCT, perBatch);
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            }

            assertTrue(legacy.get(PRODUCT) < 0L,
                    "旧口径会算出负数的那组数：回绕后的读数把「报称产出」读成了倒欠");
            assertEquals(-6_446_744_073_709_551_616L, legacy.get(PRODUCT),
                    "回绕值可复算：1.2e19 - 2^64");
            assertEquals(exactTotal, job.accountAnnounced.getExact(PRODUCT),
                    "新口径同一组数给精确值，不回绕、不变负");
            assertTrue(job.accountAnnounced.getLong(PRODUCT) > 0L, "对外读数也不再是负数");
            assertEquals(Long.MAX_VALUE, job.accountAnnounced.getLong(PRODUCT), "对外读数按饱和收口");
        }

        /** 实收回池账跟着实物回池一笔笔加：越界后仍精确，"报称 − 回池"那一截读得出来。 */
        void returnedAccountTracksPhysicalBatchReturnsWithoutWrapping() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);
            for (int batch = 1; batch <= 3; batch++) {
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            }
            assertEquals(LONG_MAX.multiply(BigInteger.valueOf(3L)),
                    WideCraftingInventory.amount(job.waitingFor, PRODUCT));

            // 两批实物回池：与 insertInternal 同一条"先 SIMULATE 夹住、再 MODULATE 抽走并记账"
            returnOnePhysicalBatch(job, Long.MAX_VALUE);
            returnOnePhysicalBatch(job, Long.MAX_VALUE);

            assertEquals(LONG_MAX.multiply(BigInteger.TWO), job.accountReturned.getExact(PRODUCT),
                    "实收回池账逐位精确：ret= 的读数不该是回绕出来的数");
            assertEquals(Long.MAX_VALUE, job.accountReturned.getLong(PRODUCT),
                    "对外 long 读数按饱和收口，日志里 ret 与 ann 同为天花板");
            assertTrue(job.accountReturned.getLong(PRODUCT) >= 0L, "绝不回绕成负");
            assertEquals(LONG_MAX, job.accountAnnounced.getExact(PRODUCT)
                            .subtract(job.accountReturned.getExact(PRODUCT)),
                    "判据「报称多、回池少」在宽域账上仍算得出：还差 1×Long.MAX 枚没回来");
            assertEquals(LONG_MAX, WideCraftingInventory.amount(job.waitingFor, PRODUCT),
                    "账与缓冲同步下降：销一笔少一笔，不双计");
        }

        /** 申报流一次划分：报称账 = 等实物 + 预期入网，越界量级下合计仍不多也不少。 */
        void announcedAccountStillEqualsPhysicalPlusNetworkAcrossWideBatches() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);
            var plannedToNetwork = new KeyCounter();
            plannedToNetwork.add(PRODUCT, 4_000_000_000_000_000_000L);
            var declaredTotal = BigInteger.ZERO;

            for (int batch = 1; batch <= 3; batch++) {
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), plannedToNetwork);
                declaredTotal = declaredTotal.add(LONG_MAX);
            }

            assertEquals(declaredTotal, job.accountAnnounced.getExact(PRODUCT),
                    "报称账按全额记，与产物走哪条路无关");
            assertEquals(declaredTotal,
                    WideCraftingInventory.amount(job.waitingFor, PRODUCT)
                            .add(job.expectedToNetwork(PRODUCT).exactValue()),
                    "等实物 + 预期入网 = 申报总量：三本账在越界量级下仍对得齐"
                            + "（预期入网那一截读的是宿主自报的计划量，不是入网回执）");
        }

        /**
         * 回池一笔：镜像 {@code ParallelCraftingCpuLogic#insertInternal} 的动作形状——
         * 先用 SIMULATE 读出产出的预期上限夹住本批，再 MODULATE 抽走并记 {@code accountReturned}。
         */
        private static void returnOnePhysicalBatch(ParallelExecutingCraftingJob job, long requested) {
            AEKey what = PRODUCT;
            long clamped = Math.min(requested, job.waitingFor.extract(what, requested,
                    Actionable.SIMULATE));
            if (clamped <= 0L) {
                return;
            }
            job.waitingFor.extract(what, clamped, Actionable.MODULATE);
            job.accountReturned.add(what, clamped);
        }

        private static ParallelExecutingCraftingJob newJob() {
            return new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
        }

        private static ICraftingPlan emptyPlan() {
            return new ICraftingPlan() {
                private final KeyCounter empty = new KeyCounter();

                @Override
                public GenericStack finalOutput() {
                    return null;
                }

                @Override
                public long bytes() {
                    return 0L;
                }

                @Override
                public boolean simulation() {
                    return false;
                }

                @Override
                public boolean multiplePaths() {
                    return false;
                }

                @Override
                public KeyCounter usedItems() {
                    return empty;
                }

                @Override
                public KeyCounter emittedItems() {
                    return empty;
                }

                @Override
                public KeyCounter missingItems() {
                    return empty;
                }

                @Override
                public Map<IPatternDetails, Long> patternTimes() {
                    return Map.of();
                }
            };
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelRunningAccountsWideLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRunningAccountsWideLedgerCases.announcedAccountRemembersTheExactTotalAcrossBatchesBeyondLong", () -> new ParallelRunningAccountsWideLedgerCases().announcedAccountRemembersTheExactTotalAcrossBatchesBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRunningAccountsWideLedgerCases.oldLongLedgerWrappedNegativeOnTheSameStreamWhileWideLedgerStaysExact", () -> new ParallelRunningAccountsWideLedgerCases().oldLongLedgerWrappedNegativeOnTheSameStreamWhileWideLedgerStaysExact()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRunningAccountsWideLedgerCases.returnedAccountTracksPhysicalBatchReturnsWithoutWrapping", () -> new ParallelRunningAccountsWideLedgerCases().returnedAccountTracksPhysicalBatchReturnsWithoutWrapping()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRunningAccountsWideLedgerCases.announcedAccountStillEqualsPhysicalPlusNetworkAcrossWideBatches", () -> new ParallelRunningAccountsWideLedgerCases().announcedAccountStillEqualsPhysicalPlusNetworkAcrossWideBatches())
            );
        }
    }

    /** ParallelTaskProgressWideAmountCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelTaskProgressWideAmountCases {
        /** 1e19 发：越出 {@code long}，正是会被截成 {@code Long.MAX_VALUE} 的那一档。 */
        private static final BigInteger OVER_LONG = new BigInteger("10000000000000000000");

        /** 低位有特征的越界值：编码丢精度（例如走 double）就抓得出来。 */
        private static final BigInteger OVER_LONG_ODD = OVER_LONG.add(BigInteger.valueOf(1234567890123L));

        private static final AEItemKey PRODUCT = AEItemKey.of(new ItemStack(Items.IRON_INGOT));

        private static final FakePattern PATTERN = new FakePattern(
                AEItemKey.of(Items.CRAFTING_TABLE),
                new GenericStack(PRODUCT, 1L));

        void overLongFiringsAreRecordedExactlyInTheTaskTable() {
            var job = new ParallelExecutingCraftingJob(
                    widePlan(PATTERN, OVER_LONG), ignored -> {
                    }, (CraftingLink) null, null);

            assertEquals(1, job.tasks.size());
            var recorded = job.tasks.get(PATTERN);
            assertEquals(OVER_LONG, recorded.exactRemaining().exactValue(),
                    "任务表要存规划侧精确发数，不是 patternTimes() 那份饱和代表值");
            assertTrue(OVER_LONG.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
            assertEquals(Long.MAX_VALUE, recorded.value, "饱和镜像照旧留着，喂只能吃 long 的旧调用点");
            assertFalse(recorded.isFinished(), "越界任务不能被读成已跑完");
        }

        /**
         * 建表的键集＝「自有宽域份数账 ∪ AE2 代表值字典」：两份各缺一截时都不许丢任务。
         *
         * <p>反向守门：{@link WideCraftSnapshot#capture} 只记发数越出 {@code long} 的样板，
         * 谁把键集来源直接换成它，塞得进 long 的窄样板就会整批从任务表消失。
         */
        void taskTableKeysAreTheUnionOfWideAccountAndRepresentativeDict() {
            FakePattern narrowOnly = new FakePattern(
                    AEItemKey.of(Items.PAPER), new GenericStack(PRODUCT, 1L));
            FakePattern wideOnly = new FakePattern(
                    AEItemKey.of(Items.BOOK), new GenericStack(PRODUCT, 1L));

            var crafts = new WideCraftCounter();
            crafts.add(wideOnly, PlanAmount.of(OVER_LONG));
            // AE2 那份代表值字典只记得下窄任务：越界那一发在字典里缺位。
            var representative = new HashMap<IPatternDetails, Long>();
            representative.put(narrowOnly, 5L);
            var plan = new FakePlan(Map.copyOf(representative), WideCraftSnapshot.capture(crafts));

            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            assertEquals(2, job.tasks.size(), "两份键集取并集：窄样板与宽域样板都要在表里");
            assertTrue(job.tasks.containsKey(narrowOnly), "只塞得进 long 的样板不能被宽域快照挤掉");
            assertEquals(5L, job.tasks.get(narrowOnly).exactRemaining().fastValue());
            assertTrue(job.tasks.containsKey(wideOnly), "AE2 字典缺位的越界样板必须由宽域账补进表");
            assertEquals(OVER_LONG, job.tasks.get(wideOnly).exactRemaining().exactValue());
        }

        void stallReadoutPrintsExactFiringsNotTheSaturatingSentinel() {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(OVER_LONG));
            assertEquals(Long.MAX_VALUE, progress.value, "先确认镜像确实处于饱和哨兵态");

            var readout = ParallelCraftingCpuLogic.describeStallTaskFirings(progress);
            assertEquals(OVER_LONG.toString(), readout, "份数读数要给精确真值");
            assertFalse(readout.contains("9223372036854775807"),
                    "越界任务不许把饱和哨兵当份数摆进排查日志");

            // 域内任务观感零变；空任务沿用 -1 形态
            var narrow = new ParallelExecutingCraftingJob.TaskProgress();
            narrow.addFirings(PlanAmount.of(42L));
            assertEquals("42", ParallelCraftingCpuLogic.describeStallTaskFirings(narrow));
            assertEquals("-1", ParallelCraftingCpuLogic.describeStallTaskFirings(null));
        }

        void wideFiringsRoundTripThroughTaskEntryNbtBitForBit() {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(OVER_LONG_ODD));

            var item = new CompoundTag();
            ParallelExecutingCraftingJob.writeTaskProgress(item, progress);
            var restored = ParallelExecutingCraftingJob.readTaskProgress(item);

            assertEquals(OVER_LONG_ODD, restored.exactRemaining().exactValue(),
                    "存→读逐位相等（含低位特征数字）");
            assertEquals(progress.value, restored.value, "饱和镜像同样往返一致");

            // 常态（装得进 long）不该多挂宽域键：老键位就是全部
            var narrow = new ParallelExecutingCraftingJob.TaskProgress();
            narrow.addFirings(PlanAmount.of(42L));
            var narrowItem = new CompoundTag();
            ParallelExecutingCraftingJob.writeTaskProgress(narrowItem, narrow);
            assertFalse(narrowItem.contains(
                    ParallelExecutingCraftingJob.NBT_CRAFTING_PROGRESS_WIDE, Tag.TAG_BYTE_ARRAY),
                    "能塞 long 的份数只写老 long 键");
            assertEquals(42L, ParallelExecutingCraftingJob.readTaskProgress(narrowItem)
                    .exactRemaining().fastValue());
        }

        void batchedDecrementStaysExactAndNeverLooksFinished() {
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.addFirings(PlanAmount.of(OVER_LONG));

            long batch = 1_000_000_000L;
            for (int round = 1; round <= 4; round++) {
                ParallelCraftingCpuLogic.applyPreparedBatchSuccess(
                        job, task, preparedBatch(batch), new NoopEnergyService());
                assertEquals(OVER_LONG.subtract(BigInteger.valueOf(batch * round)),
                        task.exactRemaining().exactValue(), "第 " + round + " 批后剩余必须逐位精确");
                assertEquals(Long.MAX_VALUE, task.value, "越界期间镜像仍是饱和代表值");
                assertFalse(task.isFinished(), "还剩约 1e19 发：绝不允许被判成跑完");
            }

            // 扣回 long 域：撤掉侧值，镜像立刻恢复成精确值，任务仍未跑完
            task.consumeFirings(Long.MAX_VALUE - 1_000L);
            var demoted = OVER_LONG
                    .subtract(BigInteger.valueOf(batch).multiply(BigInteger.valueOf(4L)))
                    .subtract(BigInteger.valueOf(Long.MAX_VALUE - 1_000L));
            assertEquals(demoted, task.exactRemaining().exactValue());
            assertEquals(demoted.longValueExact(), task.value, "回到 long 域后镜像即精确值");
            assertFalse(task.isFinished());

            // 跑完最后一段：只有精确归零才算完成
            task.consumeFirings(task.exactRemaining().fastValue());
            assertTrue(task.isFinished());
            assertEquals(0L, task.value);
        }

        /** 规划侧宽账 + AE2 那份饱和字典同源：一份 {@link WideCraftCounter} 同时交出两样。 */
        private static ICraftingPlan widePlan(IPatternDetails pattern, BigInteger firings) {
            var crafts = new WideCraftCounter();
            crafts.add(pattern, PlanAmount.of(firings));
            return new FakePlan(crafts.longView(), WideCraftSnapshot.capture(crafts));
        }

        private static ICraftingPlan emptyPlan() {
            return new FakePlan(Map.of(), WideCraftSnapshot.empty());
        }

        private static PreparedBatch preparedBatch(long count) {
            return new PreparedBatch() {
                @Override
                public boolean push(ListCraftingInventory inventory) {
                    return true;
                }

                @Override
                public long craftCount() {
                    return count;
                }

                @Override
                public List<GenericStack> inputTotal() {
                    return List.of();
                }

                @Override
                public List<GenericStack> outputs() {
                    return List.of();
                }

                @Override
                public List<GenericStack> remainders() {
                    return List.of();
                }

                @Override
                public double power() {
                    return 0.0D;
                }
            };
        }

        private static final class NoopEnergyService implements IEnergyService {
            @Override
            public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
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

        private record FakePlan(Map<IPatternDetails, Long> patternTimes,
                WideCraftSnapshot wideCrafts) implements ICraftingPlan, WideAmountPlanCarrier {

            private static final KeyCounter EMPTY = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return EMPTY;
            }

            @Override
            public KeyCounter emittedItems() {
                return EMPTY;
            }

            @Override
            public KeyCounter missingItems() {
                return EMPTY;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return patternTimes;
            }

            @Override
            public WideLedgerSnapshot chexsonsaeutils$wideLedgers() {
                return WideLedgerSnapshot.empty();
            }

            @Override
            public WideCraftSnapshot chexsonsaeutils$wideCrafts() {
                return wideCrafts;
            }
        }

        private record FakePattern(AEItemKey definition,
                GenericStack output) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(output);
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases.overLongFiringsAreRecordedExactlyInTheTaskTable", () -> new ParallelTaskProgressWideAmountCases().overLongFiringsAreRecordedExactlyInTheTaskTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases.taskTableKeysAreTheUnionOfWideAccountAndRepresentativeDict", () -> new ParallelTaskProgressWideAmountCases().taskTableKeysAreTheUnionOfWideAccountAndRepresentativeDict()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases.stallReadoutPrintsExactFiringsNotTheSaturatingSentinel", () -> new ParallelTaskProgressWideAmountCases().stallReadoutPrintsExactFiringsNotTheSaturatingSentinel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases.wideFiringsRoundTripThroughTaskEntryNbtBitForBit", () -> new ParallelTaskProgressWideAmountCases().wideFiringsRoundTripThroughTaskEntryNbtBitForBit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTaskProgressWideAmountCases.batchedDecrementStaysExactAndNeverLooksFinished", () -> new ParallelTaskProgressWideAmountCases().batchedDecrementStaysExactAndNeverLooksFinished())
            );
        }
    }

    /** ParallelWaitingForWideInventoryCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelWaitingForWideInventoryCases {
        private static final HolderLookup.Provider REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

        /** 作业使用宽域容器记录等待产出的精确数量。 */
        void waitingForUsesTheWideImplementation() {
            assertInstanceOf(WideCraftingInventory.class, newJob().waitingFor);
        }

        /** 同一产物跨三批各挂 {@code Long.MAX_VALUE} 枚：精确账是 3×，对 AE2 的读数钉在天花板。 */
        void waitingForRemembersTheExactAmountAcrossBatchesBeyondLong() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);

            for (int batch = 1; batch <= 3; batch++) {
                ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            }

            assertEquals(LONG_MAX.multiply(BigInteger.valueOf(3L)), job.waitingForExact(PRODUCT).exactValue(),
                    "跨批挂账逐位精确：这才是「库存本身能记 >long」的意思");
            assertEquals(Long.MAX_VALUE, getWaitingForReadout(job, PRODUCT),
                    "getWaitingFor 那条基类身份读口：溢出键报饱和 long，与无限盘对网络报数同一口径");
            assertEquals(Long.MAX_VALUE, job.waitingFor.list.get(PRODUCT));
            assertTrue(job.waitingFor.list.get(PRODUCT) > 0L,
                    "读数不绕回成负数，回池夹取不会被判成「没料」");
            assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(), "本用例一笔都没入网，两本账不串");
        }

        /** 越界那截被物理回池逐步抽走：AE2 读数按批下降，精确账同步下降，绝不双计也绝不少扣。 */
        void returningPhysicalOutputShavesTheWideLedgerBatchByBatch() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            assertEquals(LONG_MAX.multiply(BigInteger.TWO), job.waitingForExact(PRODUCT).exactValue());

            // 一批回池：insertInternal 先 SIMULATE 夹取、再 MODULATE 抽走
            long accepted = job.waitingFor.extract(PRODUCT, getWaitingForReadout(job, PRODUCT),
                    Actionable.MODULATE);
            assertEquals(Long.MAX_VALUE, accepted);
            assertEquals(LONG_MAX, job.waitingForExact(PRODUCT).exactValue());
            assertEquals(Long.MAX_VALUE, getWaitingForReadout(job, PRODUCT));

            assertEquals(1L, job.waitingFor.extract(PRODUCT, 1L, Actionable.MODULATE));
            assertEquals(LONG_MAX.subtract(BigInteger.ONE), job.waitingForExact(PRODUCT).exactValue());
        }

        /** 常态挂账（不越界）：写出的 NBT 与老实现逐字相同，一个覆盖键都不多挂。 */
        void inRangeReservationsWriteTheSameNbtShapeAsBefore() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, 1_000_000_000_000L);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);

            var plain = new ListCraftingInventory(ignored -> {
            });
            plain.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);

            ListTag written = job.waitingFor.writeToNBT(REGISTRIES);
            assertEquals(plain.writeToNBT(REGISTRIES), written,
                    "作业落盘的 waitingFor 列表形状不变：老存档读得动，别的 mod 也读得动");
            assertEquals(0, countWideEntries(written));
        }

        /** 越界挂账进出 NBT 逐位相等，且读回后对 AE2 仍报饱和读数（作业级：走的就是那两条读写口）。 */
        void wideReservationRoundTripsThroughTheJobReadAndWritePorts() {
            var job = newJob();
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            var exact = LONG_MAX.multiply(BigInteger.TWO);

            ListTag written = job.waitingFor.writeToNBT(REGISTRIES);
            assertEquals(1, countWideEntries(written), "只有真越界那条挂覆盖键");
            assertFalse(job.waitingFor.list.isEmpty());

            var restored = new WideCraftingInventory(ignored -> {
            });
            restored.readFromNBT(written, REGISTRIES);
            assertEquals(exact, WideCraftingInventory.amount(restored, PRODUCT));
            assertEquals(Long.MAX_VALUE, restored.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        // ---- 夹具 ----

        /** 与 {@code ParallelCraftingCpuLogic.getWaitingFor} 逐字同一条调用：以基类身份问还剩多少。 */
        private static long getWaitingForReadout(ParallelExecutingCraftingJob job, AEItemKey what) {
            return job.waitingFor.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
        }

        private static int countWideEntries(ListTag tag) {
            int count = 0;
            for (int index = 0; index < tag.size(); index++) {
                if (tag.getCompound(index).contains(
                        WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                    count++;
                }
            }
            return count;
        }

        private static ParallelExecutingCraftingJob newJob() {
            return new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
        }

        private static ICraftingPlan emptyPlan() {
            return new ICraftingPlan() {
                private final KeyCounter empty = new KeyCounter();

                @Override
                public GenericStack finalOutput() {
                    return null;
                }

                @Override
                public long bytes() {
                    return 0L;
                }

                @Override
                public boolean simulation() {
                    return false;
                }

                @Override
                public boolean multiplePaths() {
                    return false;
                }

                @Override
                public KeyCounter usedItems() {
                    return empty;
                }

                @Override
                public KeyCounter emittedItems() {
                    return empty;
                }

                @Override
                public KeyCounter missingItems() {
                    return empty;
                }

                @Override
                public Map<IPatternDetails, Long> patternTimes() {
                    return Map.of();
                }
            };
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases.waitingForUsesTheWideImplementation", () -> new ParallelWaitingForWideInventoryCases().waitingForUsesTheWideImplementation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases.waitingForRemembersTheExactAmountAcrossBatchesBeyondLong", () -> new ParallelWaitingForWideInventoryCases().waitingForRemembersTheExactAmountAcrossBatchesBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases.returningPhysicalOutputShavesTheWideLedgerBatchByBatch", () -> new ParallelWaitingForWideInventoryCases().returningPhysicalOutputShavesTheWideLedgerBatchByBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases.inRangeReservationsWriteTheSameNbtShapeAsBefore", () -> new ParallelWaitingForWideInventoryCases().inRangeReservationsWriteTheSameNbtShapeAsBefore()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWaitingForWideInventoryCases.wideReservationRoundTripsThroughTheJobReadAndWritePorts", () -> new ParallelWaitingForWideInventoryCases().wideReservationRoundTripsThroughTheJobReadAndWritePorts())
            );
        }
    }

    /** ParallelWidePoolRoundStoreCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelWidePoolRoundStoreCases {
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

        // ---- 分轮回池交付 ----

        /** 同一键精确值 3×MAX+7：按轮各交不超过 long，四轮交完、条目整条撤走，合计逐枚一致。 */
        void overflowedPoolKeyConvergesAcrossRoundsOfAtMostLongMax() {
            WideCraftingInventory pool = newPool();
            for (int batch = 0; batch < 3; batch++) {
                pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            }
            pool.insert(GLASS, 7L, Actionable.MODULATE);
            BigInteger exact = LONG_MAX.multiply(BigInteger.valueOf(3L)).add(BigInteger.valueOf(7L));
            assertEquals(exact, pool.amount(GLASS));

            var offers = new ArrayList<Long>();
            BigInteger deliveredTotal = BigInteger.ZERO;
            for (int round = 0; round < 4; round++) {
                BigInteger before = totalExact(pool);
                ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> {
                    offers.add(offer);
                    return offer; // 网络全收
                });
                BigInteger delivered = before.subtract(totalExact(pool));
                assertTrue(delivered.signum() > 0, "每一轮都必须真实推进，绝不原地打转");
                assertTrue(delivered.compareTo(LONG_MAX) <= 0, "一轮每键最多交一个 long");
                deliveredTotal = deliveredTotal.add(delivered);
            }
            assertEquals(exact, deliveredTotal, "四轮合计恰等于精确值：一枚不多一枚不少");
            assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 7L), offers);
            assertTrue(pool.list.isEmpty(), "清零即整条撤走，不留 0 值条目");
            assertFalse(pool.hasOverflow(), "交付完成后侧表不残留");
        }

        /** 网络整轮拒收：台账一分不扣，主表哨兵行与侧表原样，下一轮继续。 */
        void refusedRoundDebtsNothingAndKeepsTheWideLedgerIntact() {
            WideCraftingInventory pool = newPool();
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            BigInteger exact = LONG_MAX.multiply(BigInteger.TWO);

            ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> 0L);

            assertEquals(exact, pool.amount(GLASS), "拒收一轮，台账一分不扣");
            assertEquals(Long.MAX_VALUE, pool.list.get(GLASS), "溢出键对基类身份读数仍是饱和哨兵");
            assertTrue(pool.hasOverflow());
        }

        /** 申报交出 MAX、网络只认 9 枚：扣账只按实收的 9，绝不跟着申报走。 */
        void partialAcceptanceDebitsByReceiptNotByOffer() {
            WideCraftingInventory pool = newPool();
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            pool.insert(GLASS, 9L, Actionable.MODULATE);
            BigInteger before = pool.amount(GLASS);
            assertEquals(LONG_MAX.multiply(BigInteger.TWO).add(BigInteger.valueOf(9L)), before);

            ParallelCraftingCpuLogic.storeWideInventory(pool, (what, offer) -> {
                assertEquals(Long.MAX_VALUE, offer, "溢出键本轮的可交量应夹在 long 天花板");
                return 9L;
            });

            assertEquals(LONG_MAX.multiply(BigInteger.TWO), pool.amount(GLASS),
                    "2×MAX+9 只按实收扣 9 枚：扣账恒等于实收，不是申报数");
        }

        /** {@code long} 域内的键：分轮交付与旧的"主表逐键 setValue"退法逐位一致。 */
        void inRangeQuantitiesMatchTheLegacyMainTableLoopRoundByRound() {
            WideCraftingInventory wide = newPool();
            ListCraftingInventory legacy = new ListCraftingInventory(ignored -> {
            });
            wide.insert(GLASS, 250L, Actionable.MODULATE);
            wide.insert(IRON, 100L, Actionable.MODULATE);
            legacy.list.add(GLASS, 250L);
            legacy.list.add(IRON, 100L);

            for (int round = 0; round < 6; round++) {
                ParallelCraftingCpuLogic.storeWideInventory(wide, acceptAtMost(100L));
                for (var entry : legacy.list) {
                    long inserted = Math.min(entry.getLongValue(), 100L);
                    entry.setValue(entry.getLongValue() - inserted);
                }
                legacy.list.removeZeros();
                assertSameMainTable(wide.list, legacy.list, "第 " + round + " 轮后主表必须逐键一致");
            }
            assertTrue(wide.list.isEmpty() && legacy.list.isEmpty(), "两种退法最终都收敛到空池");
        }

        // ---- 精确读数接线 ----

        /**
         * 台账 #87：宽域直写／直取口与基类那两条 {@code long} 口<b>记进同一本</b>进出账。
         *
         * <p>一次批量、四个出口，手算表（全部打在 GLASS 这一格上，期望值写死、不由被测产出复算）：
         * <ol>
         *   <li>{@code insertExact(100, MODULATE)}：100 塞得进 {@code long} ⇒ 实现内部退回
         *       {@code insert(long)} ⇒ 由那一条覆写记 <b>一次</b> ⇒ {@code in=100}。
         *       宽域覆写若也跟着记，这里就是 200（双计）。</li>
         *   <li>{@code extractExact(60, MODULATE)}：这条口恒走侧表那张嘴、<b>不回调</b>
         *       {@code extract(long)} ⇒ 只能由宽域覆写记下 ⇒ {@code out=60}。
         *       改前这一格恒 {@code 0}（退网交付整段隐身）。</li>
         *   <li>{@code insert(25, MODULATE)} 基类 {@code long} 口 ⇒ {@code in=125}。</li>
         *   <li>{@code extract(10, MODULATE)} 基类 {@code long} 口 ⇒ {@code out=70}。</li>
         *   <li>两次 {@code SIMULATE}（{@code insertExact(7)}／{@code extractExact(9)}）
         *       一律不进账 ⇒ {@code in}/{@code out} 停在 125／70。</li>
         * </ol>
         * ⇒ {@code in − out = 125 − 70 = 55 = 100 − 60 + 25 − 10} ＝池子这一格的<b>净变化</b>。
         */
        void everyWideAndLongMouthBooksIntoTheSameInOutLedger() {
            var ledger = new ParallelPoolLedger();
            var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(ledger, ignored -> {
            });

            ledger.path("submit");
            pool.insertExact(GLASS, BigInteger.valueOf(100L), Actionable.MODULATE);
            assertEquals(100L, ledger.in(GLASS),
                    "≤long 那一支只许记一次：宽域覆写跟着记就成 200（双计）");

            ledger.path("ecoPush");
            assertEquals(BigInteger.valueOf(60L),
                    pool.extractExact(GLASS, BigInteger.valueOf(60L), Actionable.MODULATE));
            assertEquals(60L, ledger.out(GLASS),
                    "退网走 extractExact：改前这张嘴不记账，out{} 里恒 0");

            ledger.path("submit");
            pool.insert(GLASS, 25L, Actionable.MODULATE);
            pool.extract(GLASS, 10L, Actionable.MODULATE);
            assertEquals(125L, ledger.in(GLASS), "基类 long 口与宽域口同一本");
            assertEquals(70L, ledger.out(GLASS), "基类 long 口与宽域口同一本");

            pool.insertExact(GLASS, BigInteger.valueOf(7L), Actionable.SIMULATE);
            pool.extractExact(GLASS, BigInteger.valueOf(9L), Actionable.SIMULATE);
            assertEquals(125L, ledger.in(GLASS), "SIMULATE 一律不进流水");
            assertEquals(70L, ledger.out(GLASS), "SIMULATE 一律不进流水");

            assertEquals(BigInteger.valueOf(55L), pool.amount(GLASS), "池子现量手算：100−60+25−10");
            assertEquals(55L, ledger.in(GLASS) - ledger.out(GLASS),
                    "一次批量的对账：in − out ＝ 池子净变化");
        }

        /**
         * 台账 #87 的越界那一档：同一笔进出，{@code long} 域内的格对得齐，越界的两格<b>不再隐身</b>。
         *
         * <p>手算表：{@code insertExact(IRON, 2×Long.MAX)}（续传读回的形状）→ 越界那一支不回调
         * {@code insert(long)}，只能由宽域覆写记下 → {@code in=Long.MAX_VALUE}（这本账是 {@code long}
         * 读数账 ⇒ 报小不许回绕）。随后分轮退网 {@code extractExact(IRON, Long.MAX, MODULATE)} 交出
         * 一个天花板 → {@code out=Long.MAX_VALUE}。改前这两格都是 {@code 0}，池子里躺着 2×MAX 枚而
         * {@code in} 的 {@code resume} 那一格与 {@code out} 那一格一个字都不印——照 {@code debug.log}
         * 定性就会把"池子被人抽走一批"读成"没人伸手"。
         */
        void wideMouthsBeyondLongNoLongerVanishFromThePoolReadout() {
            var ledger = new ParallelPoolLedger();
            var pool = ParallelCraftingCpuLogic.newLedgerBackedPool(ledger, ignored -> {
            });

            ledger.path("resume");
            pool.insertExact(IRON, LONG_MAX.multiply(BigInteger.TWO), Actionable.MODULATE);
            assertEquals(LONG_MAX.multiply(BigInteger.TWO), pool.amount(IRON), "池子记住的是精确值");
            assertEquals(Long.MAX_VALUE, ledger.in(IRON),
                    "越界那一截过去在 in{} 里隐身（改前恒 0）：现在报一个天花板，报小不许回绕");
            assertTrue(ledger.describeKey(IRON).contains("resume=" + ParallelPoolLedger.SATURATED_READOUT),
                    "带路径点名的读口也要看见这一笔（达顶那一格按标记印、不把饱和哨兵当量印）："
                            + ledger.describeKey(IRON));

            ledger.restore(ledger.path("store"));
            pool.extractExact(IRON, LONG_MAX, Actionable.MODULATE);
            assertEquals(Long.MAX_VALUE, ledger.out(IRON),
                    "分轮退网那一笔过去整段不进 out{}（改前恒 0）");
            assertEquals(Long.MAX_VALUE, ledger.in(IRON), "入账侧读数不因此改动");
        }

        /**
         * 台账 #226 的那条饱和合并换共享档后的读数钉子：同一条路径上两个键各记 {@code Long.MAX_VALUE}，
         * {@code describePaths()} 的合计必须停在顶上（印 9223372036854775807），不许回绕成 {@code -2}。
         * 换的是只夹上界那一档——负方向如实相加，本读口 {@code noteOut} 按非正早退走不到负向，
         * 那一半的算术契约由 {@code bigint/ClampedMathTest} 钉。
         */
        void pathTotalReadoutPinsTheCeilingInsteadOfWrappingAcrossTwoKeys() {
            var ledger = new ParallelPoolLedger();
            ledger.path("batch");
            ledger.noteOut(GLASS, Long.MAX_VALUE);
            ledger.noteOut(IRON, Long.MAX_VALUE);

            String described = ledger.describePaths();

            assertTrue(described.contains("batch=" + Long.MAX_VALUE + "("),
                    "两格各一个天花板，合计只许夹在顶上：" + described);
            assertFalse(described.contains("=-2"),
                    "回绕会把越顶的合计读成负数：" + described);
        }

        /** 停滞日志 inv= 段打精确真值；不再是主表那个饱和哨兵。 */
        void stallSnapshotRendersTheTrueAmountWhereTheSentinelUsedToPrintMax() {
            WideCraftingInventory pool = newPool();
            for (int batch = 0; batch < 3; batch++) {
                pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            }
            BigInteger exact = LONG_MAX.multiply(BigInteger.valueOf(3L));

            String exactView = ParallelCraftingCpuLogic.describeInventoryExact(
                    ParallelCraftingCpuLogic.snapshotPoolExact(pool));
            assertTrue(exactView.contains(exact.toString()),
                    "inv= 段必须打出 3×Long.MAX 的精确十进制，而不是饱和哨兵：" + exactView);
            assertTrue(exactView.startsWith("kinds=1"), "形状与老 describeInventory 同一条：" + exactView);
        }

        /** 状态栏聚合：三源叠到越界时按饱和交出，绝不回绕；域内合计与旧写法逐位一致。 */
        void menuAggregationSaturatesAcrossSourcesInsteadOfWrapping() {
            WideCraftingInventory pool = newPool();
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            pool.insert(IRON, 5L, Actionable.MODULATE);

            var job = newJob();
            var expected = new KeyCounter();
            expected.add(GLASS, Long.MAX_VALUE);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
            // 未发任务还挂着一整发 MAX：MAX(池) + 2×MAX(缓冲) + MAX(任务) 若裸加必回绕
            var pattern = new OutputPattern(GLASS, Long.MAX_VALUE);
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = 1L;
            job.tasks.put(pattern, progress);
            job.waitingFor.insert(IRON, 7L, Actionable.MODULATE);

            var out = new KeyCounter();
            ParallelCraftingCpuLogic.getAllItems(pool, job, out);

            assertEquals(Long.MAX_VALUE, out.get(GLASS), "越界合计钉在天花板：报小不报大，永不回绕");
            assertTrue(out.get(GLASS) > 0L);
            assertEquals(12L, out.get(IRON), "域内合计与旧 addAll 写法逐位一致");
        }

        /** 无作业（收尾后残池）：聚合就是池子的饱和读数。 */
        void menuAggregationWithoutJobReportsThePoolSaturated() {
            WideCraftingInventory pool = newPool();
            pool.insert(GLASS, Long.MAX_VALUE, Actionable.MODULATE);
            pool.insert(GLASS, 1L, Actionable.MODULATE);

            var out = new KeyCounter();
            ParallelCraftingCpuLogic.getAllItems(pool, null, out);
            assertEquals(Long.MAX_VALUE, out.get(GLASS));
        }

        // ---- 夹具 ----

        private static WideCraftingInventory newPool() {
            return new WideCraftingInventory(ignored -> {
            });
        }

        private static ParallelExecutingCraftingJob newJob() {
            return new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
        }

        private static ParallelCraftingCpuLogic.WidePoolInserter acceptAtMost(long perKeyPerRound) {
            return (what, offer) -> Math.min(offer, perKeyPerRound);
        }

        private static BigInteger totalExact(WideCraftingInventory pool) {
            BigInteger total = BigInteger.ZERO;
            for (var entry : pool.list) {
                total = total.add(pool.amount(entry.getKey()));
            }
            return total;
        }

        private static void assertSameMainTable(KeyCounter left, KeyCounter right, String message) {
            var keys = new HashSet<AEKey>();
            for (var entry : left) {
                keys.add(entry.getKey());
            }
            for (var entry : right) {
                keys.add(entry.getKey());
            }
            for (AEKey key : keys) {
                assertEquals(left.get(key), right.get(key), message + " 键 " + key + " 主表读数不一致");
            }
            assertEquals(left.size(), right.size(), message);
        }

        private static ICraftingPlan emptyPlan() {
            return new ICraftingPlan() {
                private final KeyCounter empty = new KeyCounter();

                @Override
                public GenericStack finalOutput() {
                    return null;
                }

                @Override
                public long bytes() {
                    return 0L;
                }

                @Override
                public boolean simulation() {
                    return false;
                }

                @Override
                public boolean multiplePaths() {
                    return false;
                }

                @Override
                public KeyCounter usedItems() {
                    return empty;
                }

                @Override
                public KeyCounter emittedItems() {
                    return empty;
                }

                @Override
                public KeyCounter missingItems() {
                    return empty;
                }

                @Override
                public Map<IPatternDetails, Long> patternTimes() {
                    return Map.of();
                }
            };
        }

        /** 只挂一个产出键的样板：状态栏聚合按 outputs × 份数取值。 */
        private record OutputPattern(AEKey product, long perFiring) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.FURNACE);
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(product, perFiring));
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.overflowedPoolKeyConvergesAcrossRoundsOfAtMostLongMax", () -> new ParallelWidePoolRoundStoreCases().overflowedPoolKeyConvergesAcrossRoundsOfAtMostLongMax()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.refusedRoundDebtsNothingAndKeepsTheWideLedgerIntact", () -> new ParallelWidePoolRoundStoreCases().refusedRoundDebtsNothingAndKeepsTheWideLedgerIntact()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.partialAcceptanceDebitsByReceiptNotByOffer", () -> new ParallelWidePoolRoundStoreCases().partialAcceptanceDebitsByReceiptNotByOffer()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.inRangeQuantitiesMatchTheLegacyMainTableLoopRoundByRound", () -> new ParallelWidePoolRoundStoreCases().inRangeQuantitiesMatchTheLegacyMainTableLoopRoundByRound()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.everyWideAndLongMouthBooksIntoTheSameInOutLedger", () -> new ParallelWidePoolRoundStoreCases().everyWideAndLongMouthBooksIntoTheSameInOutLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.wideMouthsBeyondLongNoLongerVanishFromThePoolReadout", () -> new ParallelWidePoolRoundStoreCases().wideMouthsBeyondLongNoLongerVanishFromThePoolReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.pathTotalReadoutPinsTheCeilingInsteadOfWrappingAcrossTwoKeys", () -> new ParallelWidePoolRoundStoreCases().pathTotalReadoutPinsTheCeilingInsteadOfWrappingAcrossTwoKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.stallSnapshotRendersTheTrueAmountWhereTheSentinelUsedToPrintMax", () -> new ParallelWidePoolRoundStoreCases().stallSnapshotRendersTheTrueAmountWhereTheSentinelUsedToPrintMax()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.menuAggregationSaturatesAcrossSourcesInsteadOfWrapping", () -> new ParallelWidePoolRoundStoreCases().menuAggregationSaturatesAcrossSourcesInsteadOfWrapping()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWidePoolRoundStoreCases.menuAggregationWithoutJobReportsThePoolSaturated", () -> new ParallelWidePoolRoundStoreCases().menuAggregationWithoutJobReportsThePoolSaturated())
            );
        }
    }

    /** ParallelWideProductionCreditCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelWideProductionCreditCases {
        /** 主臂那一格的剩余发数真值：1e19 枚，比 {@code long} 天花板还多出 7.77e17 枚。 */
        private static final BigInteger FIRINGS_WIDE = new BigInteger("10000000000000000000");
        /** 复加顶格那一型的每笔量：两笔之和正好比天花板多一枚。 */
        private static final long HALF_MAX_PLUS_ONE = Long.MAX_VALUE / 2L + 1L;
        /** 贷方账里那一枚中间键（实机形态：{@code mekanism:redstone} 那一格）。 */
        private static final AEKey MIDDLE = AEItemKey.of(new ItemStack(Items.NETHERITE_SCRAP));
        /** 被占料锁盯着的那一枚。 */
        private static final AEKey ORE = AEItemKey.of(new ItemStack(Items.RAW_IRON));
        /** 退路上那一枚：它的量决定伸手那一格改不改得了道。 */
        private static final AEKey COPPER = AEItemKey.of(new ItemStack(Items.RAW_COPPER));
        /** 各家样板自己的产物（不参与这一族读数）。 */
        private static final AEKey PRODUCT = AEItemKey.of(new ItemStack(Items.IRON_BLOCK));
        /** 谁也吃不上的那一枚，只用来给样板填一条不相关的输入。 */
        private static final AEKey FILLER = AEItemKey.of(new ItemStack(Items.DIRT));

        private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
        private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

        /**
         * <b>存在性臂（主臂，HEAD 必红）</b>：某样板剩余发数 {@code > long}（1e19 发）、每发产 1 枚
         * {@code MIDDLE} ⇒ 这一笔产出必须在贷方账上，而且交回的是<b>宽域真值十位数</b>。
         *
         * <p>HEAD 红在这里：那一趟拿 {@code representableFirings}（越界交回 0）当这一行<b>存不存在</b>
         * 的判据，整条 {@code continue} ⇒ 贷方量读成 0、该键根本不在账上（红件逐字
         * {@code expected: <10000000000000000000> but was: <0>}）——正是实机同一行里
         * {@code toMake=0} 与 {@code left=2.27e20} 同时成立的那一型。
         *
         * <p>只红这一条的变异：量那一趟的发数出口退回 {@code representableFirings}（越界即整条跳过），
         * 或<b>单笔</b>顶格那一支挨着名单补一句 {@code remove}。分家臂走的是<b>复加</b>顶格那一支
         * 且发数装得进 long，正向臂与窄值臂压根不顶格——两把变异都碰不到别的臂。
         */
        void beyondLongFiringsPatternStillBooksItsPlannedOutputInWideTruth() {
            var producer = pattern(Items.BLAST_FURNACE, FILLER, 1L, MIDDLE, 1L);
            var job = newJob();
            job.tasks.put(producer, wideProgress(FIRINGS_WIDE));

            var reservation = MaterialAllocationFixtures.build(
                    job.tasks.entrySet(), Map.of(ORE, 10L), NO_PLAN, NO_TAKEN);

            PlanAmount credit = reservation.pendingProductionExact(MIDDLE);
            assertEquals("10000000000000000000", credit.exactValue().toString(),
                    "剩余发数 > long 的那张样板，计划产出必须按宽域真值进贷方账——"
                            + "拿“能不能塞进 long”当存在性判据就是把这笔产出整个撤掉");
            assertEquals(FIRINGS_WIDE, credit.exactValue(),
                    "贷方账读数＝发数真值 × 每发 1 枚，一个数都不许多也不许少");
            assertFalse(credit.isFast(),
                    "这一笔本来就越出 long：账上摆的是真值，不是 0，也不是那道天花板");
        }

        /**
         * <b>锁与量分家臂</b>：撤锁那一型（贷方量顶到 {@code long} 天花板 ⇒
         * {@code productionHasNoTrustworthyReading} 为真）仍<b>不许</b>让量消失。
         *
         * <p>夹具取的是 HEAD 里"每笔都装得进 {@code long}、合计装不进"那一型：两张样板各产
         * {@code Long.MAX/2 + 1} 枚 {@code MIDDLE}——单笔都表得出来，合计 {@code
         * 9223372036854775808} 正好比天花板多一枚。HEAD 在这一支上写的是 {@code dropAsUntrustworthy}
         * ＝{@code map.remove} 挨着 {@code untrusted.add}：名单那一半是对的（少一道悲观锁），
         * {@code remove} 那一半就是把"锁算式跑不出可信数"读成"这一键没有产出"，于是
         * {@code releaseable} 记成 0、整份占料锁扣到底——实机 {@code toMake=0} 那一格就是这么来的。
         *
         * <p>只红这一条的变异：复加顶格那一支把"撤锁"写成"撤量"（挨着名单补一句 {@code remove}）。
         * 主臂那一键走的是<b>单笔</b>顶格那一支（一笔就 {@code 1e19} 枚），两型各在一处，这把变异
         * 碰不到它；本用例第一句（锁）与最后一句（锁放开后池子原样交回）在变异下照旧绿，
         * 只有量那一句当场红。
         */
        void revokingThePessimisticLockKeepsEveryUnitOfTheCredit() {
            var producerA = patternWithFirstCandidate(Items.GRINDSTONE, FILLER, 1L,
                    MIDDLE, HALF_MAX_PLUS_ONE);
            var producerB = patternWithFirstCandidate(Items.SMOKER, FILLER, 1L,
                    MIDDLE, HALF_MAX_PLUS_ONE);
            var locker = patternWithFirstCandidate(Items.FURNACE, MIDDLE, 100L, PRODUCT, 1L);
            var job = newJob();
            job.tasks.put(producerA, progress(1L));
            job.tasks.put(producerB, progress(1L));
            job.tasks.put(locker, progress(1L));

            var reservation = MaterialAllocationFixtures.build(
                    job.tasks.entrySet(), Map.of(MIDDLE, 500L), NO_PLAN, NO_TAKEN);

            assertTrue(reservation.productionHasNoTrustworthyReading(MIDDLE),
                    "前提（锁那一腿）：合计顶穿 long ⇒ 这一键在 long 域锁算式里跑不出可信读数 ⇒ 不下悲观锁");
            assertEquals("9223372036854775808", reservation.pendingProductionExact(MIDDLE)
                            .exactValue().toString(),
                    "撤锁不许顺带撤量：两笔各 (MAX+1)/2 复加出来的那一枚越界量照旧在账上，"
                            + "一笔都不因为进了名单而消失");
            assertFalse(reservation.pendingProductionExact(MIDDLE).isFast(),
                    "合计越出 long 的就是真值本身，不是天花板读数，也不是被撤掉的 0");

            var asker = patternWithFirstCandidate(Items.CARTOGRAPHY_TABLE, MIDDLE, 1L, PRODUCT, 1L);
            assertEquals(500L, reservation.usableFor(MIDDLE, 500L, asker, 1L,
                            asker.getInputs()[0], true),
                    "锁确实放开了：池子里那 500 枚原样交回，不是被一整份兑不了的债权扣光");
        }

        /**
         * <b>正向臂</b>：正常（每笔都装得进 {@code long}）订单的一个读数都不动。
         *
         * <p>这一格钉的是"分家不是放开"：贷方 {@code 60} 枚而别人按第一候选认领 {@code 100} 枚 ⇒
         * 能兑现的只有 {@code 0} 枚，剩下那 {@code 100} 枚占料锁照旧扣到底——{@code usableFor}
         * 交回 {@code 400} 而不是池子现量。这一型在 HEAD 与改后必须是同一个数。
         *
         * <p>只红这一条的变异：把顶格判据放宽成"任何正量都算不可信"（{@code >= 0} 之类的写法）——
         * 于是这一键也进了名单、整道锁被放开，第一句（名单为空）与最后一句（照扣 100 枚）同时红。
         * 把宽域账当成"放开夹子"的理由，本用例拦的就是这一步；别的臂没有靠这道锁取数的断言。
         */
        void normalOrdersKeepTheirReservationAndCreditReadingsByteForByte() {
            var locker = patternWithFirstCandidate(Items.FURNACE, ORE, 100L, PRODUCT, 1L);
            var oreProducer = patternWithFirstCandidate(Items.SMOKER, FILLER, 1L, ORE, 60L);
            var copperProducer = patternWithFirstCandidate(Items.GRINDSTONE, FILLER, 1L, COPPER, 5L);
            var job = newJob();
            job.tasks.put(locker, progress(1L));
            job.tasks.put(oreProducer, progress(1L));
            job.tasks.put(copperProducer, progress(1L));

            var reservation = MaterialAllocationFixtures.build(
                    job.tasks.entrySet(), Map.of(ORE, 500L), NO_PLAN, NO_TAKEN);

            assertEquals(0, reservation.untrustedProductionKeyCount(),
                    "正常订单里一个键都不该进“锁不可信”的名单：越界判据不许顺手放宽");
            assertFalse(reservation.productionHasNoTrustworthyReading(ORE),
                    "贷方 60 枚装得进 long，就是可信口径：这一键的占料锁照下");
            assertEquals("60", reservation.pendingProductionExact(ORE).exactValue().toString(),
                    "窄值那一档的贷方读数与 HEAD 逐字相同");
            assertEquals("5", reservation.pendingProductionExact(COPPER).exactValue().toString(),
                    "退路上那一枚的贷方读数同样一字未动");

            var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                    new GenericStack(COPPER, 1L)}, 1L, null);
            var consumer = new FakePattern(AEItemKey.of(new ItemStack(Items.CARTOGRAPHY_TABLE)),
                    new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                    new IPatternDetails.IInput[]{reroutable});
            assertEquals(400L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, false),
                    "占料锁逐字不变：能兑现的产出只有 60 枚，抵不上别人认领的 100 枚 ⇒ 那 100 枚照扣");
        }

        /**
         * <b>窄值退回臂</b>：{@code representableFirings} 走窄支时与 HEAD 同一条路、同一个读数，
         * 一次宽域升档都不许多；而锁那一腿撤下一格的锁，也不许带走这张样板的量。
         *
         * <p>夹具是 {@code 1e13} 发（正好装得进 {@code long} ⇒ 发数走的正是 HEAD 那条窄支）× 每发
         * {@code 1e6} 枚（占料那一格装不下，合计 {@code 1e19} 枚）⇒ 老口径那一格 {@code units ==
         * Long.MAX_VALUE} 即 {@code continue} 的豁免一字未动：这一格既不进占料锁也不进认领。
         * 同一张样板每发产 1 枚 × {@code 1e13} 发＝{@code 1e13} 枚照旧进贷方账，而且落在
         * {@code PlanAmount} 的 {@code long} 快路径上——规范化不变式是 {@code wide == null 当且仅当
         * 值落在 long 域}，所以 {@code isFast()} 恒真就是"这一趟一条 BigInteger 都没多"的字节水位自证。
         *
         * <p>只红这一条的变异：把"撤锁"写成"撤量"挪到锁那一腿——{@code units == Long.MAX_VALUE}
         * 那一格不再跳过，而是把那道饱和读数当占料记进锁账。第一句当场红（池子里 500 枚被一堵兑不了
         * 的墙扣光、{@code usable=0}），就是实机 {@code locked=[minecraft:lava
         * stored=9223372036854775807 usable=0] noCand=3154} 那一跑。其余三臂的夹具里没有会饱和的格子
         * （正向臂每格 1～100 枚、分家臂 1 发、主臂整张被窄尺跳过），这一把碰不到它们。
         */
        void narrowFiringsTakeTheNarrowPathAndUnscalableSlotKeepsItsCredit() {
            var saturatingSlot = patternWithFirstCandidate(Items.FURNACE, ORE, 1_000_000L, MIDDLE, 1L);
            var job = newJob();
            job.tasks.put(saturatingSlot, progress(10_000_000_000_000L));

            var reservation = MaterialAllocationFixtures.build(
                    job.tasks.entrySet(), Map.of(ORE, 500L), NO_PLAN, NO_TAKEN);

            var asker = patternWithFirstCandidate(Items.CARTOGRAPHY_TABLE, ORE, 1L, PRODUCT, 1L);
            assertEquals(500L, reservation.usableFor(ORE, 500L, asker, 1L, asker.getInputs()[0], true),
                    "窄支那一格的豁免一字未动：锁主自己要分几万轮才吃下的那一截，不配把 500 枚判成"
                            + "别人取不动——把饱和读数记进占料账就是把锁乘成一堵墙");

            PlanAmount credit = reservation.pendingProductionExact(MIDDLE);
            assertEquals("10000000000000", credit.exactValue().toString(),
                    "每发 1 枚 × 1e13 发＝1e13 枚：与换域前同一条乘算、同一个数");
            assertTrue(credit.isFast(),
                    "字节水位：这一趟一次宽域升档都不许多——分家换的是出口，"
                            + "不是把 long 快路径换成 BigInteger");
        }

        private static ParallelExecutingCraftingJob newJob() {
            ICraftingPlan plan = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 越出 {@code long} 的任务进度：走真实写入口（{@code addFirings}），饱和镜像自动成哨兵。 */
        private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(firings));
            return progress;
        }

        /** 一张"吃 {@code input} 一枚、产 {@code output} 若干枚"的单槽样板。 */
        private static FakePattern pattern(Item definition, AEKey input, long inputUnits,
                AEKey output, long outputUnits
        ) {
            return patternWithFirstCandidate(definition, input, inputUnits, output, outputUnits);
        }

        /** 一张只有一个候选的单槽样板（退料键为 null ⇒ 不是模具槽）。 */
        private static FakePattern patternWithFirstCandidate(Item definition, AEKey input, long inputUnits,
                AEKey output, long outputUnits
        ) {
            return new FakePattern(AEItemKey.of(new ItemStack(definition)),
                    new GenericStack[]{new GenericStack(output, outputUnits)},
                    new IPatternDetails.IInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(input, inputUnits)}, 1L, null)});
        }

        private record FakePattern(AEItemKey definition, GenericStack[] outputs,
                IPatternDetails.IInput[] slots) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return slots;
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(outputs);
            }
        }

        private record FakeInput(GenericStack[] candidates, long multiplier,
                AEKey remainder) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates.clone();
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                for (GenericStack candidate : candidates) {
                    if (candidate != null && candidate.what().equals(input)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainder;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelWideProductionCreditCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideProductionCreditCases.beyondLongFiringsPatternStillBooksItsPlannedOutputInWideTruth", () -> new ParallelWideProductionCreditCases().beyondLongFiringsPatternStillBooksItsPlannedOutputInWideTruth()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideProductionCreditCases.revokingThePessimisticLockKeepsEveryUnitOfTheCredit", () -> new ParallelWideProductionCreditCases().revokingThePessimisticLockKeepsEveryUnitOfTheCredit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideProductionCreditCases.normalOrdersKeepTheirReservationAndCreditReadingsByteForByte", () -> new ParallelWideProductionCreditCases().normalOrdersKeepTheirReservationAndCreditReadingsByteForByte()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideProductionCreditCases.narrowFiringsTakeTheNarrowPathAndUnscalableSlotKeepsItsCredit", () -> new ParallelWideProductionCreditCases().narrowFiringsTakeTheNarrowPathAndUnscalableSlotKeepsItsCredit())
            );
        }
    }

    /** ParallelWideRootDeliveryCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelWideRootDeliveryCases {
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final BigInteger ORDER = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.TEN);
        private ParallelExecutingCraftingJob job() {
            return job(ORDER);
        }
        private ParallelExecutingCraftingJob job(BigInteger amount) {
            var base = new CraftingPlan(new GenericStack(PRODUCT, Long.MAX_VALUE), 0, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            var plan = new WideAmountCraftingPlan(base, WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(),
                    PlanAmount.of(amount));
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
            return new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        }

        void assemblerStandaloneFinalOutputEntersOwnDiskExactlyAndWaitsForRealAcceptance() {
            var huge = BigInteger.TEN.pow(30);
            var limit = new java.util.concurrent.atomic.AtomicReference<>(BigInteger.ZERO);
            var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(
                    new net.minecraft.world.item.ItemStack(Items.STICK), null) {
                @Override public BigInteger insertExact(AEKey key, BigInteger amount, appeng.api.config.Actionable mode,
                        appeng.api.networking.security.IActionSource source) {
                    return super.insertExact(key, amount.min(limit.get()), mode, source);
                }
            };
            var network = new appeng.me.storage.NetworkStorage();
            network.mount(0, new appeng.me.storage.DriveWatcher(cell, () -> { }));
            var lane = new ParallelCraftingLaneState(DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.offlineCluster(network), UUID.randomUUID(), 0);
            var job = job(huge);
            ((WideCraftingInventory) job.waitingFor).insertExact(PRODUCT, huge, appeng.api.config.Actionable.MODULATE);
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("job", job.writeToNBT(registries));
            lane.logic().readFromNBT(tag, registries);
            assertEquals(huge, lane.logic().claimDeclaredOutput(PRODUCT, huge.add(BigInteger.TEN)));
            assertEquals(BigInteger.ZERO, lane.logic().claimDeclaredOutput(PRODUCT, huge));
            assertTrue(lane.logic().hasJob());
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(BigInteger.ZERO, cell.exactAmount(PRODUCT));
            assertTrue(lane.logic().hasJob(), "磁盘拒收时不得提前完成");
            limit.set(BigInteger.valueOf(37));
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(BigInteger.valueOf(37), cell.exactAmount(PRODUCT));
            assertTrue(lane.logic().hasJob());
            var saved = new net.minecraft.nbt.CompoundTag();
            lane.logic().writeToNBT(saved, registries);
            lane.logic().readFromNBT(saved, registries);
            limit.set(huge);
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(huge, cell.exactAmount(PRODUCT));
            assertFalse(lane.logic().hasJob(), "整笔实物入盘后才能完成");
        }
        void exactNetworkRootDeliveryAndRejectedRollbackDeductOnlyActualReceipt() {
            var network = new ExactNetwork();
            var lane = new ParallelCraftingLaneState(DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.offlineCluster(network), UUID.randomUUID(), 0);
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(PRODUCT, ORDER, appeng.api.config.Actionable.MODULATE);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("job", job().writeToNBT(registries)); tag.put("inventory", pool.writeToNBT(registries));
            lane.logic().readFromNBT(tag, registries);
            network.limit = BigInteger.valueOf(4);
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(BigInteger.valueOf(4), network.stored); assertTrue(lane.logic().hasJob());
            network.limit = ORDER;
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(ORDER, network.stored); assertFalse(lane.logic().hasJob());
            assertEquals(2, network.calls);

            var huge = BigInteger.TEN.pow(100);
            pool.clear(); pool.insertExact(PRODUCT, huge, appeng.api.config.Actionable.MODULATE);
            network.limit = BigInteger.ZERO;
            ParallelCraftingCpuLogic.returnMaterialsToNetwork(network, pool, appeng.api.networking.security.IActionSource.empty());
            assertEquals(huge, pool.amount(PRODUCT));
            network.limit = BigInteger.valueOf(7);
            ParallelCraftingCpuLogic.returnMaterialsToNetwork(network, pool, appeng.api.networking.security.IActionSource.empty());
            assertEquals(huge.subtract(BigInteger.valueOf(7)), pool.amount(PRODUCT));
            assertEquals(ORDER.add(BigInteger.valueOf(7)), network.stored);
            network.limit = huge;
            ParallelCraftingCpuLogic.returnMaterialsToNetwork(network, pool, appeng.api.networking.security.IActionSource.empty());
            assertTrue(pool.list.isEmpty()); assertEquals(ORDER.add(huge), network.stored);
        }

        private static final class ExactNetwork implements appeng.api.storage.MEStorage,
                git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion {
            BigInteger limit = BigInteger.ZERO;
            BigInteger stored = BigInteger.ZERO;
            int calls;
            public net.minecraft.network.chat.Component getDescription() { return net.minecraft.network.chat.Component.literal("精确接收网络"); }
            public BigInteger insertExact(AEKey key, BigInteger amount, appeng.api.config.Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                calls++;
                var accepted = amount.min(limit);
                if (mode == appeng.api.config.Actionable.MODULATE) stored = stored.add(accepted);
                return accepted;
            }
        }

        void representativeLongDeliveryDoesNotCompleteExactOrder() {
            var job = job();
            job.recordFinalOutputAccepted(Long.MAX_VALUE);
            assertEquals(ORDER, job.finalOutputOrderAmount().exactValue());
            assertEquals(ORDER.subtract(BigInteger.valueOf(Long.MAX_VALUE)), job.exactRemainingAmount.exactValue());
            assertEquals(Long.MAX_VALUE, job.remainingAmount);
            job.recordFinalOutputAccepted(Long.MAX_VALUE);
            assertEquals(10, job.remainingAmount);
            job.recordFinalOutputAccepted(0);
            assertEquals(10, job.remainingAmount);
            job.recordFinalOutputAccepted(10);
            assertEquals(PlanAmount.ZERO, job.exactRemainingAmount);
            assertEquals(0, job.remainingAmount);
        }

        void standaloneWideOrderReallyEntersNetworkAndFinishesWithoutRequester() {
            var stored = new java.util.concurrent.atomic.AtomicReference<>(BigInteger.ZERO);
            var limit = new java.util.concurrent.atomic.AtomicLong(0);
            var ref = new java.util.concurrent.atomic.AtomicReference<ParallelCraftingLaneState>();
            var physical = new appeng.api.storage.MEStorage() {
                @Override public net.minecraft.network.chat.Component getDescription() {
                    return net.minecraft.network.chat.Component.literal("测试网络");
                }
                @Override public long insert(AEKey key, long amount, appeng.api.config.Actionable mode,
                        appeng.api.networking.security.IActionSource source) {
                    // 模拟网络先调用合成拦截器；同一 CPU 必须避开自己的再次拦截。
                    assertEquals(0, ref.get().insertIntoWaiting(key, amount, mode));
                    long accepted = Math.min(amount, limit.get());
                    if (mode == appeng.api.config.Actionable.MODULATE)
                        stored.updateAndGet(value -> value.add(BigInteger.valueOf(accepted)));
                    return accepted;
                }
            };
            var network = new appeng.me.storage.NetworkStorage();
            network.mount(0, physical);
            network.mount(Integer.MAX_VALUE, new appeng.api.storage.MEStorage() {
                @Override public net.minecraft.network.chat.Component getDescription() { return physical.getDescription(); }
                @Override public long insert(AEKey key, long amount, appeng.api.config.Actionable mode,
                        appeng.api.networking.security.IActionSource source) {
                    var result = ref.get().insertIntoWaitingAndGetResult(key, amount, mode);
                    assertEquals(result.physicalInserted(), result.accounted());
                    return result.physicalInserted();
                }
            });
            var lane = new ParallelCraftingLaneState(DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.offlineCluster(network), UUID.randomUUID(), 0);
            ref.set(lane);
            var job = job();
            ((WideCraftingInventory) job.waitingFor).insertExact(PRODUCT, ORDER, appeng.api.config.Actionable.MODULATE);
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("job", job.writeToNBT(registries));
            lane.logic().readFromNBT(tag, registries);
            assertEquals(Long.MAX_VALUE, lane.insertIntoWaiting(PRODUCT, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
            assertEquals(BigInteger.ZERO, stored.get());
            for (int i = 0; i < 2; i++) {
                assertEquals(Long.MAX_VALUE, network.insert(PRODUCT, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE,
                        appeng.api.networking.security.IActionSource.empty()));
                assertTrue(lane.logic().hasJob());
            }
            assertEquals(10, network.insert(PRODUCT, 10, appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty()));
            lane.logic().drainStandaloneFinalOutput();
            assertEquals(BigInteger.ZERO, stored.get());
            assertTrue(lane.logic().hasJob());
            limit.set(4);
            lane.logic().drainStandaloneFinalOutput();
            assertTrue(stored.get().signum() > 0);
            assertTrue(lane.logic().hasJob());
            limit.set(Long.MAX_VALUE);
            lane.logic().drainStandaloneFinalOutput();
            assertFalse(lane.logic().hasJob());
            assertEquals(ORDER, stored.get());
        }
        void persistsWideRemainingAndActualDeliveredQuantityAcrossReload() {
            var job = job();
            job.recordFinalOutputAccepted(Long.MAX_VALUE);
            job.addFinalOutputDelivered(PRODUCT, Long.MAX_VALUE);
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var saved = job.writeToNBT(registries);
            var lane = new ParallelCraftingLaneState(DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.diagnosticCluster(), UUID.randomUUID(), 0);
            var restored = new ParallelExecutingCraftingJob(saved, registries, ignored -> {}, lane);
            assertEquals(ORDER, restored.finalOutputOrderAmount().exactValue());
            assertEquals(job.exactRemainingAmount, restored.exactRemainingAmount);
            assertEquals(job.finalOutputDeliveredAmount(), restored.finalOutputDeliveredAmount());
            restored.recordFinalOutputAccepted(Long.MAX_VALUE);
            assertEquals(10, restored.remainingAmount);
            saved.putByteArray("exactRemainingAmount", BigInteger.valueOf(-1).toByteArray());
            assertThrows(IllegalArgumentException.class, () -> new ParallelExecutingCraftingJob(saved, registries, ignored -> {}, lane));
        }

        void networkDeclaredOrderRequiresRealTerminalDeliveryBeforeCompletion() {
            var job = job();
            job.addExpectedToNetwork(PRODUCT, ORDER);
            assertFalse(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
            job.creditDeclaredBatchDelivered(Map.of(PRODUCT, ORDER.subtract(BigInteger.ONE)), false);
            assertEquals(1, job.remainingAmount);
            assertFalse(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
            job.creditDeclaredBatchDelivered(Map.of(PRODUCT, BigInteger.ONE), false);
            assertEquals(0, job.remainingAmount);
            assertTrue(ParallelCraftingCpuLogic.isDeclaredNetworkJobComplete(job));
        }

        void cpuSnapshotRetainsWideStoredAndActiveWithoutUsingLongProjection() {
            var job = job();
            var pattern = new appeng.api.crafting.IPatternDetails() {
                @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.STICK); }
                @Override public IInput[] getInputs() { return new IInput[0]; }
                @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 2)); }
            };
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(ORDER)); job.tasks.put(pattern, progress);
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(PRODUCT, ORDER, appeng.api.config.Actionable.MODULATE);
            ((WideCraftingInventory) job.waitingFor).insertExact(PRODUCT, ORDER.add(BigInteger.ONE),
                    appeng.api.config.Actionable.MODULATE);
            var snapshot = ParallelCraftingCpuLogic.exactMenuAmounts(pool, job);
            pool.extractExact(PRODUCT, BigInteger.ONE, appeng.api.config.Actionable.MODULATE);
            assertEquals(ORDER, snapshot.stored().get(PRODUCT));
            assertEquals(ORDER.add(BigInteger.ONE), snapshot.active().get(PRODUCT));
            assertEquals(ORDER.multiply(BigInteger.TWO), snapshot.pending().get(PRODUCT));
            assertEquals(ORDER.subtract(BigInteger.ONE), ParallelCraftingCpuLogic.exactMenuAmounts(pool, job).stored().get(PRODUCT));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.assemblerStandaloneFinalOutputEntersOwnDiskExactlyAndWaitsForRealAcceptance", () -> new ParallelWideRootDeliveryCases().assemblerStandaloneFinalOutputEntersOwnDiskExactlyAndWaitsForRealAcceptance()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.exactNetworkRootDeliveryAndRejectedRollbackDeductOnlyActualReceipt", () -> new ParallelWideRootDeliveryCases().exactNetworkRootDeliveryAndRejectedRollbackDeductOnlyActualReceipt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.representativeLongDeliveryDoesNotCompleteExactOrder", () -> new ParallelWideRootDeliveryCases().representativeLongDeliveryDoesNotCompleteExactOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.standaloneWideOrderReallyEntersNetworkAndFinishesWithoutRequester", () -> new ParallelWideRootDeliveryCases().standaloneWideOrderReallyEntersNetworkAndFinishesWithoutRequester()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.persistsWideRemainingAndActualDeliveredQuantityAcrossReload", () -> new ParallelWideRootDeliveryCases().persistsWideRemainingAndActualDeliveredQuantityAcrossReload()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.networkDeclaredOrderRequiresRealTerminalDeliveryBeforeCompletion", () -> new ParallelWideRootDeliveryCases().networkDeclaredOrderRequiresRealTerminalDeliveryBeforeCompletion()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideRootDeliveryCases.cpuSnapshotRetainsWideStoredAndActiveWithoutUsingLongProjection", () -> new ParallelWideRootDeliveryCases().cpuSnapshotRetainsWideStoredAndActiveWithoutUsingLongProjection())
            );
        }
    }

    /** ParallelWideTruthReadoutCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelWideTruthReadoutCases {
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

        // ---------- alloc=：宽域真值排序与精确读数 ----------

        /**
         * 六个顶格键里，真值最大的那一格必须排到头名，六列交回精确十进制。
         *
         * <p>旧形状：&gt;long 的缺口全读成同一道天花板，前四格由登记序决定——登记在前的 1.2e19
         * 压过真值 2.4e19 的那格；{@code left=/taken=/ann=/toMake=} 全印 {@code 9223372036854775807}。
         * 这一条喂真值 2.4e19 的缺口与 1.2e19 的噪声：改前红在<b>行序与逐字真值</b>，改后必须逐字精确。
         */
        void allocRowsSortByWideTruthAndPrintExactDecimalsBeyondLong() {
            var stuck = AEItemKey.of(Items.NETHERITE_SCRAP);
            var noiseA = AEItemKey.of(Items.NETHERITE_BLOCK);
            var noiseB = AEItemKey.of(Items.DIAMOND_BLOCK);
            var noiseC = AEItemKey.of(Items.EMERALD_BLOCK);
            var droppedOut = AEItemKey.of(Items.COPPER_BLOCK);
            var job = newJob();
            // 登记序把噪声 A（真值 1.2e19）排在卡死格（真值 2.4e19）前面：饱和排序看登记序，宽域排序看真值。
            job.tasks.put(producer(noiseA), wideProgress(new BigInteger("12000000000000000000")));
            job.tasks.put(producer(stuck), wideProgress(new BigInteger("24000000000000000000")));
            job.tasks.put(producer(noiseB), progress(8_000_000_000_000_000_000L));
            job.tasks.put(producer(noiseC), progress(5_000_000_000_000_000_000L));
            job.tasks.put(producer(droppedOut), progress(100_000_000_000_000L));
            MaterialAllocationFixtures.assign(job, selfLoop(stuck), wideShare(stuck, new BigInteger("24000000000000000000")));
            MaterialAllocationFixtures.assign(job, selfLoop(noiseA), wideShare(noiseA, new BigInteger("12000000000000000000")));
            job.accountTaken.add(stuck, new BigInteger("12000000000000000000"));
            job.accountAnnounced.add(stuck, new BigInteger("11000000000000000000"));
            job.accountReturned.add(stuck, new BigInteger("1000000000000000"));
            job.accountPlannedEmit.add(stuck, 4_000_000_000_000_000_000L);

            var line = stallLine(job, widePool());
            var segment = allocSegment(line);

            assertTrue(segment.indexOf("minecraft:netherite_scrap") >= 0
                            && segment.indexOf("minecraft:netherite_scrap")
                                    < segment.indexOf("minecraft:netherite_block"),
                    "真值 2.4e19 的那格必须排在 1.2e19 之前（饱和值同格顶格、由登记序抢先就是撒谎）：" + segment);
            assertTrue(segment.contains("minecraft:netherite_scrap left=24000000000000000000"),
                    "left= 交回精确十进制，不是饱和哨兵：" + segment);
            assertTrue(segment.contains("taken=12000000000000000000"), "taken= 走宽域 getExact：" + segment);
            assertTrue(segment.contains("ann=11000000000000000000"), "ann= 走宽域 getExact：" + segment);
            assertTrue(segment.contains("ret=1000000000000000"), "ret= 域内值逐字不变：" + segment);
            assertTrue(segment.contains("toMake=48000000000000000000"),
                    "toMake= 一发产 2 枚 × 剩余 2.4e19 = 4.8e19（独立手算，不抄实现）：" + segment);
            assertFalse(segment.contains("9223372036854775807"),
                    "alloc= 段里不许再摆饱和哨兵当量：" + segment);
            assertFalse(segment.contains("minecraft:copper_block"),
                    "缺口前四之外的键不在头几名里（封顶口径不变）：" + segment);
            assertEquals(4, segment.split(" left=", -1).length - 1,
                    "缺口行恰四行（必含格另计，本用例没有 blocked= 点名）：" + segment);
        }

        // ---------- alloc=：必含本窗 blocked= 点名的键 ----------

        /**
         * {@code blocked=} 真失败格点名的键，即使缺口挤不进前四，也必须单独成行进 {@code alloc=}。
         *
         * <p>实机 19:46 那一行 4 格被四个顶格噪声占满、真卡死的 flux 格一个字都没有——旧形状永远看不见
         * 链条上一环。这一条把 flux 格<b>真走</b>一次单发提取失败（每发 9 枚、池内 7 枚），键从现成的
         * {@code Probe#failingCell} 账取，不新开第二本账。改前红在这一格压根不在 {@code alloc=} 段里。
         */
        void allocMustCarryTheKeyTheStuckCellActuallyNamed() {
            var flux = AEItemKey.of(Items.ENDER_EYE);
            var pFlux = new FakePattern(AEItemKey.of(Items.COMPARATOR),
                    new FakeInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(flux, 1L)}, 9L, null, Set.of(flux))},
                    List.of(new GenericStack(AEItemKey.of(Items.CALIBRATED_SCULK_SENSOR), 1)));
            var pool = widePool();
            pool.insertExact(flux, BigInteger.valueOf(7L), Actionable.MODULATE); // 池内 7 枚、每发要 9 枚
            var probe = new ParallelBatchExtraction.Probe();
            assertNull(ParallelBatchExtraction.prepareUnitPrototype(pFlux, pool, null,
                            new KeyCounter(), RESERVATION_FREE, probe),
                    "夹具前提：这一格每发 9 枚、池内只有 7 枚，单发真算不出");
            var named = probe.failingCell(pFlux);
            assertNotNull(named, "夹具前提：真失败格必须点名到这一张");
            assertEquals(flux, named.key(), "夹具前提：点名的是 flux 那一枚");

            var job = newJob();
            for (var noise : List.of(AEItemKey.of(Items.AMETHYST_SHARD), AEItemKey.of(Items.ANCIENT_DEBRIS),
                    AEItemKey.of(Items.BEACON), AEItemKey.of(Items.BLAZE_POWDER),
                    AEItemKey.of(Items.BOOK))) {
                job.tasks.put(producer(noise), progress(100L)); // 噪声缺口 100，全都大于 flux 的 20
            }
            job.tasks.put(pFlux, progress(3L)); // 缺口 = 9×3 − 7 = 20：挤不进前四
            job.batchObligations.incur(pFlux, BigInteger.valueOf(11L));
            MaterialAllocationFixtures.assign(job, pFlux, wideShare(flux, BigInteger.valueOf(45L)));

            var stallProbe = newStallProbe();
            stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                    probe, new java.util.HashMap<>(), pool, job.declaredTerminals, 0L);
            var line = stallProbe.describeStallLine(UUID.randomUUID(), 1, job, pool, null,
                    "…", "", 0L, -2L);
            var segment = allocSegment(line);

            assertTrue(segment.contains("minecraft:ender_eye left=45"),
                    "blocked= 点名的 flux 键必须单独成行进 alloc=（缺口 20 挤不进前四也要在）：" + segment);
            assertEquals(5, segment.split(" left=", -1).length - 1,
                    "四个顶格噪声行 + 一个必含行：" + segment);
        }

        /**
         * {@code 回补受阻} 点名的键同样必须进 {@code alloc=}：这一格的键只在现成的回补账串里
         * （{@code slot#N_<键>_got=…}），键从那一本现成读数回查声明候选表取。
         * 改前红在：这张样板本轮没记真失败格、键也挤不进缺口前四 ⇒ {@code alloc=} 整格看不见它。
         */
        void allocMustCarryTheKeyTheRefillBlockedCellNamed() {
            var chest = AEItemKey.of(Items.CHEST);
            var pRefill = new FakePattern(AEItemKey.of(Items.HOPPER_MINECART),
                    new FakeInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(chest, 1L)}, 5L, null, Set.of(chest))},
                    List.of(new GenericStack(AEItemKey.of(Items.LANTERN), 1)));
            var pool = widePool();
            pool.insertExact(chest, BigInteger.ONE, Actionable.MODULATE); // 每发 5 枚、池内只有 1 枚
            var probe = new ParallelBatchExtraction.Probe();
            ParallelBatchExtraction.refillObligationBatch(pRefill, pool, null, 1L, RESERVATION_FREE,
                    (what, wanted) -> 0L, probe);
            var refillCells = probe.refillBlockedCellsFor(pRefill);
            assertTrue(refillCells.contains("slot#0_" + chest + "_got="),
                    "夹具前提：回补受阻那一格真点名了 chest（现成账的形状）：" + refillCells);

            var job = newJob();
            for (var noise : List.of(AEItemKey.of(Items.AMETHYST_SHARD), AEItemKey.of(Items.ANCIENT_DEBRIS),
                    AEItemKey.of(Items.BEACON), AEItemKey.of(Items.BLAZE_POWDER),
                    AEItemKey.of(Items.BOOK))) {
                job.tasks.put(producer(noise), progress(100L));
            }
            job.tasks.put(pRefill, progress(2L)); // 缺口 = 5×2 − 1 = 9：挤不进前四
            job.batchObligations.incur(pRefill, BigInteger.valueOf(4L));
            MaterialAllocationFixtures.assign(job, pRefill, wideShare(chest, BigInteger.valueOf(40L)));

            var stallProbe = newStallProbe();
            stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                    probe, new java.util.HashMap<>(), pool, job.declaredTerminals, 0L);
            var line = stallProbe.describeStallLine(UUID.randomUUID(), 1, job, pool, null,
                    "…", "", 0L, -2L);
            var segment = allocSegment(line);

            assertTrue(segment.contains("minecraft:chest left=40"),
                    "回补受阻点名的键必须单独成行进 alloc=：" + segment);
        }

        // ---------- poolKeys=：这一行点名的键，逐枚把池账本的进／出接出来 ----------

        /**
         * 台账 #226 笔 3b：{@code ParallelPoolLedger#describeKey} 那本逐路径分账在生产代码里
         * <b>零调用点</b>（全仓只有测试读它）——"这一枚键是被哪条路送进池子、又被哪条路送出池子"
         * 在停滞行上从来没印过。这一格把它接到<b>这一行已经点名的那几枚键</b>上
         * （{@code blockedNamedKeys} 那一份现成名单，不新开第二本账）。
         *
         * <p>账记在产线那<b>同一本</b>上（{@code logic.poolLedger}，走 {@code noteIn}/{@code noteOut}
         * 那唯一的两个累账口），期望数出自夹具自己写死的 12 进 / 3 出，不抄实现。
         */
        void stallLinePoolKeysCellPrintsLedgerInAndOutPerNamedKey() {
            var chest = AEItemKey.of(Items.CHEST);
            var pRefill = new FakePattern(AEItemKey.of(Items.HOPPER_MINECART),
                    new FakeInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(chest, 1L)}, 5L, null, Set.of(chest))},
                    List.of(new GenericStack(AEItemKey.of(Items.LANTERN), 1)));
            var pool = widePool();
            pool.insertExact(chest, BigInteger.ONE, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();
            ParallelBatchExtraction.refillObligationBatch(pRefill, pool, null, 1L, RESERVATION_FREE,
                    (what, wanted) -> 0L, probe);

            var job = newJob();
            job.tasks.put(pRefill, progress(2L));
            job.batchObligations.incur(pRefill, BigInteger.valueOf(4L));
            MaterialAllocationFixtures.assign(job, pRefill, wideShare(chest, BigInteger.valueOf(40L)));

            var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L).logic();
            // 两笔账各挂一条路径：submit 那一头真进 12 枚、store 那一头真出 3 枚
            var backToNone = logic.poolLedger.path("submit");
            logic.poolLedger.noteIn(chest, 12L);
            logic.poolLedger.restore(backToNone);
            backToNone = logic.poolLedger.path("store");
            logic.poolLedger.noteOut(chest, 3L);
            logic.poolLedger.restore(backToNone);

            var stallProbe = logic.new StallProbe();
            stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                    probe, new java.util.HashMap<>(), pool, job.declaredTerminals, 0L);
            var line = stallProbe.describeStallLine(UUID.randomUUID(), 1, job, pool, null,
                    "…", "", 0L, -2L);

            var cell = firstTokenStartingWith(line, "poolKeys=");
            assertEquals("poolKeys=view=narrow,since=reset,[" + chest + "进{submit=12}/出{store=3}]",
                    cell, "点名的这一枚必须带它自己的进／出逐路径量，并自报窄域与窗：" + line);
            assertEquals("poolKeysCov=shown=1/total=1",
                    firstTokenStartingWith(line, "poolKeysCov="),
                    "名单里一枚、格内点出一枚：" + line);
            assertFalse(cell.chars().anyMatch(Character::isWhitespace),
                    "整格仍是零空白的一个 token（段数判据的前提）：" + cell);
        }

        /**
         * 同一格的两档"不许当量印"：①那本账饱和在 {@code Long.MAX_VALUE}（{@code book} 宁可报小），
         * 达顶那一格只能印 {@code <sat>} 标记；②被点名的另一枚键这本账<b>一笔没记到</b>，
         * 只能印空花括号 {@code 进{}/出{}}（那是"零笔"，不许读成"零枚"以外的东西）。
         */
        void stallLinePoolKeysCellMarksSaturatedAndUnbookedInsteadOfPrintingThemAsAmounts() {
            var chest = AEItemKey.of(Items.CHEST);
            var bucket = AEItemKey.of(Items.BUCKET);
            var pTwoSlots = new FakePattern(AEItemKey.of(Items.HOPPER_MINECART),
                    new FakeInput[]{
                            new FakeInput(new GenericStack[]{new GenericStack(chest, 1L)},
                                    5L, null, Set.of(chest)),
                            new FakeInput(new GenericStack[]{new GenericStack(bucket, 1L)},
                                    5L, null, Set.of(bucket))},
                    List.of(new GenericStack(AEItemKey.of(Items.LANTERN), 1)));
            var pool = widePool();
            pool.insertExact(chest, BigInteger.ONE, Actionable.MODULATE);
            pool.insertExact(bucket, BigInteger.ONE, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();
            ParallelBatchExtraction.refillObligationBatch(pTwoSlots, pool, null, 1L, RESERVATION_FREE,
                    (what, wanted) -> 0L, probe);
            assertEquals(2, probe.describeRefillBlockedCells().split("slot#", -1).length - 1,
                    "夹具前提：两格都真被点名（这一格的名单就是从这里来的）："
                            + probe.describeRefillBlockedCells());

            var job = newJob();
            job.tasks.put(pTwoSlots, progress(2L));
            job.batchObligations.incur(pTwoSlots, BigInteger.valueOf(4L));

            var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L).logic();
            var backToNone = logic.poolLedger.path("resume");
            logic.poolLedger.noteIn(chest, Long.MAX_VALUE); // 夹在顶上：这一格是哨兵，不是量
            logic.poolLedger.restore(backToNone);

            var stallProbe = logic.new StallProbe();
            stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                    probe, new java.util.HashMap<>(), pool, job.declaredTerminals, 0L);
            var line = stallProbe.describeStallLine(UUID.randomUUID(), 1, job, pool, null,
                    "…", "", 0L, -2L);

            var cell = firstTokenStartingWith(line, "poolKeys=");
            assertEquals("poolKeys=view=narrow,since=reset,[" + chest + "进{resume=<sat>},"
                            + bucket + "进{}/出{}]",
                    cell, "达顶那一格按标记印、没记到的那一枚按空花括号印：" + line);
            assertFalse(cell.contains("9223372036854775807"),
                    "饱和哨兵不许冒充量：" + cell);
            assertEquals("poolKeysCov=shown=2/total=2",
                    firstTokenStartingWith(line, "poolKeysCov="), "两枚都点了：" + line);
        }

        // ---------- blocked=：最多三张，按 owed 降序 ----------

        /**
         * 四张欠账样板：{@code blocked=} 必须按 {@code owed} 降序点名前三张（7→5→3），欠得最少的第四张
         * 不点名；每张的段形状与旧的单张段逐字同形，整格仍是零空白的一个 token。
         * 旧形状只点第一张：实机连续 7 行同一张人工太阳、链条上一环永远被顶名挤掉。
         */
        void blockedNamesUpToThreeStuckTemplatesInOwedOrder() {
            var low = patternWithOutput(AEItemKey.of(Items.DIORITE), AEItemKey.of(Items.BOOKSHELF));
            var high = patternWithOutput(AEItemKey.of(Items.ANDESITE), AEItemKey.of(Items.BARREL));
            var middle = patternWithOutput(AEItemKey.of(Items.GRANITE), AEItemKey.of(Items.HOPPER));
            var least = patternWithOutput(AEItemKey.of(Items.TUFF), AEItemKey.of(Items.LANTERN));
            var job = newJob();
            // 登记序故意打乱 owed：点名序必须由 owed 降序决定，不随登记序翻。
            job.tasks.put(low, progress(1L));
            job.tasks.put(high, progress(1L));
            job.tasks.put(least, progress(1L));
            job.tasks.put(middle, progress(1L));
            job.batchObligations.incur(low, BigInteger.valueOf(3L));
            job.batchObligations.incur(high, BigInteger.valueOf(7L));
            job.batchObligations.incur(least, BigInteger.valueOf(1L));
            job.batchObligations.incur(middle, BigInteger.valueOf(5L));

            var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                    job.tasks.entrySet(), widePool(), RESERVATION_FREE,
                    new ParallelBatchExtraction.Probe());

            assertFalse(blocked.chars().anyMatch(Character::isWhitespace),
                    "整格仍是零空白的一个 token（段数判据的前提）：" + blocked);
            var iHigh = blocked.indexOf("minecraft:barrel");
            var iMiddle = blocked.indexOf("minecraft:hopper");
            var iLow = blocked.indexOf("minecraft:bookshelf");
            assertTrue(iHigh >= 0 && iMiddle >= 0 && iLow >= 0,
                    "owed=7/5/3 的三张都要被点名（旧形状只点登记序第一张）：" + blocked);
            assertTrue(iHigh < iMiddle && iMiddle < iLow,
                    "点名序按 owed 降序（7→5→3），与登记序无关：" + blocked);
            // 段界＝前段收尾的 ">" 紧接后段的 "<"；四张的头名物品都是 minecraft: ⇒ 两处界即三段。
            assertEquals(2, countOccurrences(blocked, "><minecraft:"),
                    "三段两界：每段的 <…> 形状一字不变，只是多几段（段界折空白后仍可寻址）：" + blocked);
            assertFalse(blocked.contains("minecraft:lantern"),
                    "owed 最小的第四张不点名（封顶三张）：" + blocked);
            assertTrue(blocked.contains("owed=7") && blocked.contains("owed=5") && blocked.contains("owed=3"),
                    "每张段里自己的 owed 读数照旧：" + blocked);
        }

        // ---------- noCandWhy=：自报身份 + 每发件数 ----------

        // ---------- ncSplit=：格名自报窗口，子标签说真判据 ----------

        // ---------- pushed=：宽域真值合计 ----------

        void per100rKeepsExactPushesBeyondLongInEachRound() {
            var exact = BigInteger.TEN.pow(30).add(BigInteger.valueOf(17));
            var line = renderPer100rLine(new ParallelBatchExtraction.Probe(),
                    new ListCraftingInventory(ignored -> {}), exact);
            assertEquals("pushed=" + exact.multiply(BigInteger.valueOf(100)),
                    firstTokenStartingWith(line, "pushed="));
        }

        /**
         * 每轮实推 {@code Long.MAX_VALUE} 发（bigint lane 的形状）：旧 {@code saturatingAdd} 第一轮就把
         * 100 轮钉死在 {@code 9223372036854775807}——"有没有推进"的读数一直撒谎。宽域合计必须交出
         * 100 × {@code Long.MAX_VALUE} 的精确十进制，读数每窗都在动才证明确实在推进。
         */
        void pushedSumsRoundsInWideTruthNotPinnedToTheSentinel() {
            var line = renderPer100rLine(new ParallelBatchExtraction.Probe(), widePool(), Long.MAX_VALUE);
            var token = firstTokenStartingWith(line, "pushed=");
            assertEquals("pushed=" + LONG_MAX.multiply(BigInteger.valueOf(100L)), token,
                    "100 轮 × Long.MAX 的逐字真值（独立手算：922337203685477580700），不是天花板：" + line);
        }

        // ---------- 夹具 ----------

        private static FakePattern producer(AEKey eats) {
            return new FakePattern(AEItemKey.of(pickItem(eats)),
                    new FakeInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(eats, 1L)}, 1L, null, Set.of(eats))},
                    List.of(new GenericStack(eats, eats instanceof AEItemKey key
                            && key.getItem() == Items.NETHERITE_SCRAP ? 2L : 1L)));
        }

        /** 配给账按<b>样板实例</b>记账：配给写进与任务表同一张实例（record 的 equals 也按槽数组认，够用）。 */
        private static FakePattern selfLoop(AEKey eats) {
            return producer(eats);
        }

        private static FakePattern patternWithOutput(AEKey input, AEKey output) {
            return new FakePattern(AEItemKey.of(Items.FURNACE),
                    new FakeInput[]{new FakeInput(
                            new GenericStack[]{new GenericStack(input, 1L)}, 1L, null, Set.of(input))},
                    List.of(new GenericStack(output, 1)));
        }

        /** 噪声键的样板头要各不相同：这里只取"每个键配一个固定物品"的映射，尾部键共用兜底。 */
        private static net.minecraft.world.item.Item pickItem(AEKey key) {
            if (key.equals(AEItemKey.of(Items.NETHERITE_BLOCK))) {
                return Items.BLAST_FURNACE;
            }
            if (key.equals(AEItemKey.of(Items.DIAMOND_BLOCK))) {
                return Items.FLETCHING_TABLE;
            }
            if (key.equals(AEItemKey.of(Items.EMERALD_BLOCK))) {
                return Items.SMITHING_TABLE;
            }
            if (key.equals(AEItemKey.of(Items.COPPER_BLOCK))) {
                return Items.GRINDSTONE;
            }
            if (key.equals(AEItemKey.of(Items.ANCIENT_DEBRIS))) {
                return Items.LOOM;
            }
            if (key.equals(AEItemKey.of(Items.BEACON))) {
                return Items.CARTOGRAPHY_TABLE;
            }
            if (key.equals(AEItemKey.of(Items.BLAZE_POWDER))) {
                return Items.FISHING_ROD;
            }
            if (key.equals(AEItemKey.of(Items.BOOK))) {
                return Items.BOW;
            }
            return Items.SHEARS;
        }

        private static String stallLine(ParallelExecutingCraftingJob job, ListCraftingInventory pool) {
            return stallLine(job, pool, "", 0L, -2L);
        }

        private static String stallLine(ParallelExecutingCraftingJob job, ListCraftingInventory pool,
                String noCandKey, long noCandStored, long noCandQuota) {
            return newStallProbe().describeStallLine(UUID.randomUUID(), 1, job, pool, null,
                    "…", noCandKey, noCandStored, noCandQuota);
        }

        private static ParallelCraftingCpuLogic.StallProbe newStallProbe() {
            return new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L).logic().new StallProbe();
        }

        /** {@code alloc=} 段：从 {@code alloc=[} 起，到后一格的边界（{@code quotaBlind=}／{@code ecoOut=}）止。 */
        private static String allocSegment(String line) {
            var start = line.indexOf(" alloc=[");
            assertTrue(start >= 0, "整行里没有 alloc= 段：" + line);
            var blind = line.indexOf(" quotaBlind=", start);
            var eco = line.indexOf(" ecoOut=", start);
            var end = blind >= 0 ? blind : (eco >= 0 ? eco : line.length());
            return line.substring(start, end);
        }

        private static String firstTokenStartingWith(String line, String marker) {
            for (var token : line.split(" ")) {
                if (token.startsWith(marker)) {
                    return token;
                }
            }
            throw new AssertionError("整行里没有以 " + marker + " 开头的格：" + line);
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
                count++;
            }
            return count;
        }

        /** 走真渲染口：把这份 Probe 喂满 100 轮（每轮 pushed 增量＝第三参数），取回 per100r 整行原文。 */
        private static String renderPer100rLine(ParallelBatchExtraction.Probe probe,
                ListCraftingInventory pool, long pushedPerRound) {
            return renderPer100rLine(probe, pool, BigInteger.valueOf(pushedPerRound));
        }

        private static String renderPer100rLine(ParallelBatchExtraction.Probe probe,
                ListCraftingInventory pool, BigInteger pushedPerRound) {
            var appender = new CapturingAppender();
            var context = (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            var configuration = context.getConfiguration();
            try {
                appender.start();
                configuration.addAppender(appender);
                configuration.getRootLogger()
                        .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
                context.updateLoggers();

                var stallProbe = new ParallelCraftingLaneState(
                        DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.diagnosticCluster(), UUID.randomUUID(), 10L)
                        .logic().new StallProbe();
                // 这一条判据问的是 pushed=/obWhy= 那几格，在飞那两格恒空即"没接这条通道"那一档的真读数
                var idleTerminals = newJob().declaredTerminals;
                for (int round = 0; round < 100; round++) {
                    stallProbe.noteBatch(pushedPerRound, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                            probe, new java.util.HashMap<>(), pool, idleTerminals, 0L);
                }
                return appender.lastLine("[chexsonsaeutils-batch] per100r");
            } finally {
                configuration.getRootLogger().removeAppender(appender.getName());
                context.updateLoggers();
                appender.stop();
            }
        }

        /** 只收 INFO 及以上的原文行：渲染点的产物除了日志没有第二个出口。 */
        private static final class CapturingAppender
                extends org.apache.logging.log4j.core.appender.AbstractAppender {

            private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

            private CapturingAppender() {
                super("wide-truth-per100r-capture", null,
                        org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
            }

            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                var message = event.getMessage();
                if (message != null) {
                    lines.add(message.getFormattedMessage());
                }
            }

            private String lastLine(String prefix) {
                String found = "<no log line captured with prefix " + prefix + ">";
                for (var line : lines) {
                    if (line.startsWith(prefix)) {
                        found = line;
                    }
                }
                return found;
            }
        }

        private static WideCraftingInventory widePool() {
            return new WideCraftingInventory(ignored -> {
            });
        }

        private static ParallelExecutingCraftingJob newJob() {
            var base = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            return new ParallelExecutingCraftingJob(base, ignored -> {
            }, (CraftingLink) null, null);
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 越出 long 的任务进度：走真实写入口（addFirings），镜像自动变哨兵。 */
        private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(firings));
            return progress;
        }

        private static WideKeyCounter wideShare(AEKey key, BigInteger amount) {
            var counter = new WideKeyCounter();
            counter.setExact(key, amount);
            return counter;
        }

        private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
        private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

        /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
        private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), NO_PLAN, NO_TAKEN);

        private record FakeInput(GenericStack[] candidates, long multiplier,
                @Nullable AEKey remainder, Set<AEKey> accepts) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates.clone();
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return accepts.contains(input);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainder;
            }
        }

        private record FakePattern(AEItemKey definition, IPatternDetails.IInput[] slots,
                List<GenericStack> outputs) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return slots.clone();
            }

            @Override
            public List<GenericStack> getOutputs() {
                return outputs;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.allocRowsSortByWideTruthAndPrintExactDecimalsBeyondLong", () -> new ParallelWideTruthReadoutCases().allocRowsSortByWideTruthAndPrintExactDecimalsBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.allocMustCarryTheKeyTheStuckCellActuallyNamed", () -> new ParallelWideTruthReadoutCases().allocMustCarryTheKeyTheStuckCellActuallyNamed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.allocMustCarryTheKeyTheRefillBlockedCellNamed", () -> new ParallelWideTruthReadoutCases().allocMustCarryTheKeyTheRefillBlockedCellNamed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.stallLinePoolKeysCellPrintsLedgerInAndOutPerNamedKey", () -> new ParallelWideTruthReadoutCases().stallLinePoolKeysCellPrintsLedgerInAndOutPerNamedKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.stallLinePoolKeysCellMarksSaturatedAndUnbookedInsteadOfPrintingThemAsAmounts", () -> new ParallelWideTruthReadoutCases().stallLinePoolKeysCellMarksSaturatedAndUnbookedInsteadOfPrintingThemAsAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.blockedNamesUpToThreeStuckTemplatesInOwedOrder", () -> new ParallelWideTruthReadoutCases().blockedNamesUpToThreeStuckTemplatesInOwedOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.per100rKeepsExactPushesBeyondLongInEachRound", () -> new ParallelWideTruthReadoutCases().per100rKeepsExactPushesBeyondLongInEachRound()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelWideTruthReadoutCases.pushedSumsRoundsInWideTruthNotPinnedToTheSentinel", () -> new ParallelWideTruthReadoutCases().pushedSumsRoundsInWideTruthNotPinnedToTheSentinel())
            );
        }
    }
}
