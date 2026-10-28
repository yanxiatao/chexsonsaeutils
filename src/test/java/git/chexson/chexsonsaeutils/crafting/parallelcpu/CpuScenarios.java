package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ImmediateBatchOutputHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并行 CPU 调度的行为场景；由类别测试统一调用。 */
public final class CpuScenarios {
    private CpuScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.parallelcpu",
                () -> CompressionSchedulingRegressionCases.verify(temporaryRoot),
                () -> DeepCompressionOrderCases.verify(temporaryRoot),
                () -> ImmediateCompressionExecutionCases.verify(temporaryRoot),
                () -> MaterialAllocationCases.verify(temporaryRoot),
                () -> MaterialWakeIndexCases.verify(temporaryRoot),
                () -> ParallelBatchExtractionCases.verify(temporaryRoot),
                () -> ParallelCpuHostContractCases.verify(temporaryRoot),
                () -> ParallelCpuProviderBackoffCases.verify(temporaryRoot),
                () -> ParallelCraftingCpuLogicCases.verify(temporaryRoot),
                () -> ParallelCraftPlanBaseOrderCases.verify(temporaryRoot),
                () -> ParallelCustomKeyPoolRulerCases.verify(temporaryRoot),
                () -> ParallelElapsedTimeTrackerCases.verify(temporaryRoot),
                () -> ParallelEnvelopeDemandHoleCases.verify(temporaryRoot),
                () -> ParallelEnvelopeHoleReadoutCases.verify(temporaryRoot),
                () -> ParallelFiringsPlanOwnsTotalCases.verify(temporaryRoot),
                () -> ParallelOrderingRulerIdentityCases.verify(temporaryRoot),
                () -> ParallelPartialSubmitContractShellCases.verify(temporaryRoot),
                () -> ParallelPatternIdentityAndBackoffBlindCases.verify(temporaryRoot),
                () -> ParallelPlanBalanceOrderingCases.verify(temporaryRoot),
                () -> ParallelRationTableOrderCases.verify(temporaryRoot),
                () -> ParallelRingSeedReserveAtExtractionCases.verify(temporaryRoot),
                () -> ParallelSubmitArrivalQuotaCases.verify(temporaryRoot),
                () -> ParallelTiedHoleKeyOrderCases.verify(temporaryRoot),
                () -> ParallelUnboundedSourceExtractionCases.verify(temporaryRoot),
                () -> ParallelUntrustedProductionClaimCases.verify(temporaryRoot),
                () -> ParallelVariantAttributionOrderCases.verify(temporaryRoot),
                () -> PatternStableIdentityCases.verify(temporaryRoot)
        );
    }

    /** CompressionSchedulingRegressionCases 的独立场景夹具，按类别统一执行。 */
    public static final class CompressionSchedulingRegressionCases {
        void arrivalOfOneCompressionTierOnlyWakesItsActualConsumer() {
            var job = job();
            var patterns = new ArrayList<IPatternDetails>();
            var keys = new ArrayList<AEItemKey>();
            for (int index = 0; index < 4000; index++) {
                var key = FakePlanGrid.dyedKey(Items.PAPER, index);
                keys.add(key);
                var pattern = FakePattern.of(AEItemKey.of(Items.DIAMOND), 1, new FakeInput(key, 9));
                patterns.add(pattern);
                ParallelCraftingCpuLogic.recordMaterialBackoff(job, pattern, 10);
            }
            ParallelCraftingCpuLogic.wakeMaterialBackoff(job, keys.get(137), null);
            assertFalse(job.materialBackoffUntil.containsKey(patterns.get(137)));
            assertEquals(3999, job.materialBackoffUntil.size());
            for (int index = 0; index < 4000; index++) {
                if (index != 137) assertTrue(job.materialBackoffUntil.containsKey(patterns.get(index)));
            }
        }

        void fuzzyVariantStillWakesAConsumerThatActuallyAcceptsIt() {
            var job = job();
            var template = FakePlanGrid.dyedKey(Items.PAPER, 1);
            var variant = FakePlanGrid.dyedKey(Items.PAPER, 2);
            var pattern = FakePattern.of(AEItemKey.of(Items.BOOK), 1, FakeInput.anyVariant(template, 1));
            ParallelCraftingCpuLogic.recordMaterialBackoff(job, pattern, 10);
            ParallelCraftingCpuLogic.wakeMaterialBackoff(job, variant, null);
            assertTrue(job.materialBackoffUntil.isEmpty());
            assertTrue(job.materialBackoffMissing.isEmpty());
        }

        void deletingCompletedPrefixDoesNotSkipTheNewlyReadyNextStage() {
            var tasks = new LinkedHashMap<IPatternDetails, Integer>();
            for (int index = 0; index < 4000; index++) {
                var key = FakePlanGrid.dyedKey(Items.PAPER, index);
                tasks.put(FakePattern.of(key, 1), index);
            }
            var snapshot = new ArrayList<>(tasks.entrySet());
            var next = snapshot.get(137).getKey();
            for (int index = 0; index < 137; index++) tasks.remove(snapshot.get(index).getKey());
            var remaining = new ArrayList<>(tasks.entrySet());
            assertEquals(0, ParallelCraftingCpuLogic.resumeTaskIndex(remaining, next, 137));
            assertSame(next, remaining.get(ParallelCraftingCpuLogic.resumeTaskIndex(remaining, next, 137)).getKey());
            tasks.remove(next);
            assertEquals(0, ParallelCraftingCpuLogic.resumeTaskIndex(new ArrayList<>(tasks.entrySet()), next, 137));
        }

        private static ParallelExecutingCraftingJob job() {
            var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.DIAMOND), 1), 1, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            return new ParallelExecutingCraftingJob(plan, ignored -> {}, null, null);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CompressionSchedulingRegressionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionSchedulingRegressionCases.arrivalOfOneCompressionTierOnlyWakesItsActualConsumer", () -> new CompressionSchedulingRegressionCases().arrivalOfOneCompressionTierOnlyWakesItsActualConsumer()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionSchedulingRegressionCases.fuzzyVariantStillWakesAConsumerThatActuallyAcceptsIt", () -> new CompressionSchedulingRegressionCases().fuzzyVariantStillWakesAConsumerThatActuallyAcceptsIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionSchedulingRegressionCases.deletingCompletedPrefixDoesNotSkipTheNewlyReadyNextStage", () -> new CompressionSchedulingRegressionCases().deletingCompletedPrefixDoesNotSkipTheNewlyReadyNextStage())
            );
        }
    }

    /** DeepCompressionOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class DeepCompressionOrderCases {
        void componentCompressionChainCanBePlannedAndOrderedWithoutJvmRecursion() {
            verify(1450, false);
        }

        void manyInvalidClassMembersDoNotForceExactInputsIntoNativePlanning() {
            verify(96, false);
        }

        void deepShortageRemainsExactAndCannotBecomeAnExecutableOrder() {
            verify(1450, true);
        }

        void deepOrdersAndShortageDoNotDependOnLargeThreadStacks() throws Exception {
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                    runnable -> Thread.ofPlatform().stackSize(256 * 1024).unstarted(runnable))) {
                executor.submit(() -> {
                    verify(1450, false);
                    verify(1450, true);
                }).get();
            }
        }

        void level4000PlanningPreviewAndOrderDoNotUseTheRecursiveDepthCeiling() throws Exception {
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                    runnable -> Thread.ofPlatform().stackSize(256 * 1024).unstarted(runnable))) {
                executor.submit(() -> {
                    verify(4000, false);
                    verify(4000, true);
                }).get();
            }
        }

        void oneE1000Level4000PreviewPacketAndOrderRemainExact() {
            verify(4000, false, BigInteger.TEN.pow(1000));
        }

        private void verify(int depth, boolean shortage) {
            verify(depth, shortage, BigInteger.ONE);
        }

        private void verify(int depth, boolean shortage, BigInteger rootAmount) {
            var keys = new ArrayList<AEItemKey>();
            for (int index = 0; index <= depth; index++) {
                keys.add(FakePlanGrid.dyedKey(Items.PAPER, index));
            }
            var base = keys.getLast();
            var demand = BigInteger.valueOf(9).pow(depth).multiply(rootAmount);
            var stock = shortage ? demand.subtract(BigInteger.ONE) : demand;
            var builder = FakeGrid.builder().storage(Map.of(base, Long.MAX_VALUE));
            var patterns = new ArrayList<FakePattern>();
            for (int index = 0; index < depth; index++) {
                var pattern = FakePattern.of(keys.get(index), 1, new FakeInput(keys.get(index + 1), 9));
                patterns.add(pattern);
                builder.pattern(keys.get(index), pattern);
            }
            var grid = builder.build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), keys.getFirst(), null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(base, PlanAmount.of(stock))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(rootAmount), false, null);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertEquals(shortage, result.simulation());
            assertEquals(stock, result.state().used().getExact(base));
            assertEquals(shortage ? BigInteger.ONE : BigInteger.ZERO, result.state().missing().getExact(base));
            assertNull(MaterialClosureGate.validate(result.state(), graph, PlanAmount.of(rootAmount), null));
            var plan = Ae2PlanConverter.toPlan(result, graph);
            assertNotNull(plan);
            assertTrue(plan.bytes() > 0);
            assertTrue(WideAmountPlanCarrier.resolveBytes(plan).exactValue().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
            var counts = git.chexson.chexsonsaeutils.crafting.status.PlanSummaryAmounts.fromPlan(plan);
            var summary = new appeng.menu.me.crafting.CraftingPlanSummary(plan.bytes(), plan.simulation(), counts.entries());
            git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer.attachPatternTimes(summary, plan);
            git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.attach(summary, plan);
            git.chexson.chexsonsaeutils.crafting.status.PlanGraph.attach(summary, plan);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                    net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
            try {
                appeng.core.network.clientbound.CraftConfirmPlanPacket.STREAM_CODEC.encode(buffer,
                        new appeng.core.network.clientbound.CraftConfirmPlanPacket(summary));
                System.out.printf("压缩链预览 depth=%d shortage=%s bytes=%d%n", depth, shortage, buffer.readableBytes());
                var decoded = appeng.core.network.clientbound.CraftConfirmPlanPacket.STREAM_CODEC.decode(buffer).plan();
                assertEquals(WideAmountPlanCarrier.resolveBytes(plan).exactValue(),
                        git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.resolve(decoded, ""));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
            if (rootAmount.bitLength() > 63) {
                // 摘要之外还会发送精确物料账本：同时验证真实分片 codec，避免修完图后再撞菜单字段限制。
                var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
                var encoded = git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec.encode(counts.displaySnapshot(), registries);
                var chunks = git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload.split(7, 11, encoded);
                git.chexson.chexsonsaeutils.network.crafting.WidePlanFrame frame =
                        new git.chexson.chexsonsaeutils.network.crafting.WidePlanFrameImpl();
                for (var chunk : chunks.reversed()) {
                    var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries);
                    try {
                        git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload.STREAM_CODEC.encode(wire, chunk);
                        assertTrue(wire.readableBytes() < 65536);
                        var received = git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload.STREAM_CODEC.decode(wire);
                        frame.accept(received.revision(), received.index(), received.count(), received.data());
                        assertEquals(0, wire.readableBytes());
                    } finally { wire.release(); }
                }
                var decoded = git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec.decode(frame.completed(11), registries);
                for (var kind : git.chexson.chexsonsaeutils.bigint.WideAmountKind.values())
                    assertEquals(counts.displaySnapshot().entries(kind), decoded.entries(kind));
                System.out.printf("wide preview ledger chars=%d chunks=%d%n", encoded.length(), chunks.size());
            }
            if (shortage) {
                assertTrue(plan.simulation());
                return;
            }
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
            assertEquals(depth, job.tasks.size());
            var firings = rootAmount;
            for (var pattern : patterns) {
                assertEquals(firings, job.tasks.get(pattern).exactRemaining().exactValue());
                firings = firings.multiply(BigInteger.valueOf(9));
            }
            assertTrue(result.state().journal() < depth * 30, "日志大小应随级数增长，不能随压缩数量分轮");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.componentCompressionChainCanBePlannedAndOrderedWithoutJvmRecursion", () -> new DeepCompressionOrderCases().componentCompressionChainCanBePlannedAndOrderedWithoutJvmRecursion()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.manyInvalidClassMembersDoNotForceExactInputsIntoNativePlanning", () -> new DeepCompressionOrderCases().manyInvalidClassMembersDoNotForceExactInputsIntoNativePlanning()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.deepShortageRemainsExactAndCannotBecomeAnExecutableOrder", () -> new DeepCompressionOrderCases().deepShortageRemainsExactAndCannotBecomeAnExecutableOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.deepOrdersAndShortageDoNotDependOnLargeThreadStacks", () -> new DeepCompressionOrderCases().deepOrdersAndShortageDoNotDependOnLargeThreadStacks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.level4000PlanningPreviewAndOrderDoNotUseTheRecursiveDepthCeiling", () -> new DeepCompressionOrderCases().level4000PlanningPreviewAndOrderDoNotUseTheRecursiveDepthCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepCompressionOrderCases.oneE1000Level4000PreviewPacketAndOrderRemainExact", () -> new DeepCompressionOrderCases().oneE1000Level4000PreviewPacketAndOrderRemainExact())
            );
        }
    }

    /** ImmediateCompressionExecutionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ImmediateCompressionExecutionCases {
        void executes4000CompressionStagesWithExactPaymentAndImmediateRealOutputs() {
            int depth = 4000;
            var keys = new ArrayList<AEItemKey>();
            for (int index = 0; index <= depth; index++) keys.add(FakePlanGrid.dyedKey(Items.PAPER, index));
            var builder = FakeGrid.builder().storage(Map.of(keys.getLast(), Long.MAX_VALUE));
            var patterns = new ArrayList<FakePattern>();
            for (int index = 0; index < depth; index++) {
                var pattern = FakePattern.of(keys.get(index), 1, new FakeInput(keys.get(index + 1), 9));
                patterns.add(pattern);
                builder.pattern(keys.get(index), pattern);
            }
            var grid = builder.build();
            var required = BigInteger.valueOf(9).pow(depth).multiply(BigInteger.TWO);
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), keys.getFirst(), null);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(keys.getLast(), PlanAmount.of(required))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
            assertFalse(result.hasDeclined(), result::declineReason);
            var plan = Ae2PlanConverter.toPlan(result, graph);
            var link = new CraftingLink(CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false), (ICraftingCPU) null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
            assertEquals(patterns.reversed(), new ArrayList<>(job.tasks.keySet()), "实际任务表必须先原料后上级");
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(keys.getLast(), required, Actionable.MODULATE);
            var assigned = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var firings = BigInteger.TWO;
            for (int index = 0; index < depth; index++) {
                var quota = new WideKeyCounter();
                quota.add(keys.get(index + 1), firings.multiply(BigInteger.valueOf(9)));
                assigned.put(patterns.get(index), quota);
                firings = firings.multiply(BigInteger.valueOf(9));
            }
            job.materialAllocation.adopt(assigned, Map.of());
            assertTrue(ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job).isEmpty());
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                    job.unboundedSupplyKeys, job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
            var ledger = new ParallelPoolLedger();
            var host = new ImmediateHost(grid);
            long start = System.nanoTime();
            for (int index = depth - 1; index >= 0; index--) {
                if (index % 128 == 0) {
                    assertTrue(ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job).isEmpty());
                    reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation,
                            job.unboundedSupplyKeys, job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
                }
                var pattern = patterns.get(index);
                var pushed = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, pattern, job,
                        job.tasks.entrySet(), pool, null, reservation, ledger, new KeyCounter(), new KeyCounter(),
                        Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0);
                assertNotNull(pushed, "本级应使用整批申报");
                assertTrue(pushed.accepted() > 0);
                assertTrue(pool.amount(keys.get(index)).signum() == 0, "登记等待前不能提前交货");
                ParallelCraftingCpuLogic.settleImmediateBatch(host, job, pattern, pool, null, reservation,
                        ledger, null, (source, what, available) -> {
                            assertSame(grid, source);
                            return what.equals(keys.getFirst())
                                    ? ParallelCraftingCpuLogic.claimDeclaredStandaloneOutput(job, pool, what, available, ignored -> {})
                                    : ParallelCraftingCpuLogic.claimDeclaredIntermediateOutput(job, pool, what, available, ignored -> {});
                        });
                assertFalse(job.batchObligations.hasPending(pattern));
                assertTrue(job.tasks.get(pattern).isFinished());
                assertTrue(host.buffer.isEmpty());
                assertEquals(BigInteger.ZERO, pool.amount(keys.get(index + 1)), "上级材料必须全额扣除");
                assertEquals(BigInteger.ZERO, job.materialAllocation.remainingFamilyQuota(pattern,
                        keys.get(index + 1)).exactValue(), "本级真实配额必须全部销账");
            }
            assertEquals(BigInteger.TWO, pool.amount(keys.getFirst()));
            assertTrue(job.waitingFor.list.isEmpty());
            System.out.printf("%d 级即时执行耗时 ms=%.3f%n", depth, (System.nanoTime() - start) / 1_000_000.0);
        }

        void bufferedOutputKeepsRejectedAndPartiallyAcceptedAmounts() {
            var grid = FakeGrid.builder().build();
            var host = new ImmediateHost(grid);
            var key = AEItemKey.of(Items.DIAMOND);
            var amount = BigInteger.TEN.pow(500);
            host.buffer.add(key, amount);
            host.deliverBufferedBatchOutputs((source, what, available) -> BigInteger.ZERO);
            assertEquals(amount, host.buffer.getExact(key));
            host.deliverBufferedBatchOutputs((source, what, available) -> available.subtract(BigInteger.ONE));
            assertEquals(BigInteger.ONE, host.buffer.getExact(key));
        }

        void missingPaymentKeepsDebtAndDoesNotDeliverTheBufferedBatchEarly() {
            var raw = AEItemKey.of(Items.IRON_INGOT);
            var output = AEItemKey.of(Items.IRON_BLOCK);
            var pattern = FakePattern.of(output, 1, new FakeInput(raw, 9));
            var base = new appeng.crafting.CraftingPlan(new appeng.api.stacks.GenericStack(output, 2), 1,
                    false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 2L));
            var job = new ParallelExecutingCraftingJob(base, ignored -> {}, null, null);
            job.batchObligations.incur(pattern, BigInteger.ONE);
            var pool = new WideCraftingInventory(ignored -> {});
            var reservation = MaterialAllocationFixtures.build(
                    Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(), Map.of(), Map.of(), Map.of());
            var host = new ImmediateHost(FakeGrid.builder().build());
            host.buffer.add(output, BigInteger.TWO);
            var deliveries = new java.util.concurrent.atomic.AtomicInteger();
            ParallelCraftingCpuLogic.settleImmediateBatch(host, job, pattern, pool, null, reservation,
                    new ParallelPoolLedger(), null, (source, what, available) -> {
                        deliveries.incrementAndGet();
                        return available;
                    });
            assertEquals(0, deliveries.get());
            assertEquals(BigInteger.ONE, job.batchObligations.pendingFirings(pattern));
            assertEquals(BigInteger.TWO, host.buffer.getExact(output));
        }

        private static final class ImmediateHost implements DeclaredBatchHost, ImmediateBatchOutputHost {
            private final IGrid grid;
            private final WideKeyCounter buffer = new WideKeyCounter();
            private ImmediateHost(IGrid grid) { this.grid = grid; }
            @Override public boolean supportsDeclaredBatch(IPatternDetails pattern) { return true; }
            @Override public boolean declaredBatchFiringsUnbounded() { return true; }
            @Override public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] prototype, BigInteger count) {
                for (var output : pattern.getOutputs()) buffer.add(output.what(), count.multiply(BigInteger.valueOf(output.amount())));
                return count;
            }
            @Override public void deliverBufferedBatchOutputs(DeclaredBatchOutputReceiver receiver) {
                CraftingOutputRouter.claimBufferedOutputs(buffer, grid, receiver);
            }
            @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
            @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] holder) { return false; }
            @Override public boolean isBusy() { return false; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ImmediateCompressionExecutionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ImmediateCompressionExecutionCases.executes4000CompressionStagesWithExactPaymentAndImmediateRealOutputs", () -> new ImmediateCompressionExecutionCases().executes4000CompressionStagesWithExactPaymentAndImmediateRealOutputs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ImmediateCompressionExecutionCases.bufferedOutputKeepsRejectedAndPartiallyAcceptedAmounts", () -> new ImmediateCompressionExecutionCases().bufferedOutputKeepsRejectedAndPartiallyAcceptedAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ImmediateCompressionExecutionCases.missingPaymentKeepsDebtAndDoesNotDeliverTheBufferedBatchEarly", () -> new ImmediateCompressionExecutionCases().missingPaymentKeepsDebtAndDoesNotDeliverTheBufferedBatchEarly())
            );
        }
    }

    /** MaterialAllocationCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialAllocationCases {
        private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey DEFINITION = AEItemKey.of(Items.PAPER);

        void adoptionCopiesBothBooksAndPreservesEqualPatternIdentities() {
            var first = new Pattern(DEFINITION);
            var twin = new Pattern(DEFINITION);
            assertEquals(first, twin);
            assertNotSame(first, twin);
            var a = amount(GLASS, 10L);
            var b = amount(GLASS, 20L);
            var envelope = amount(GLASS, 3L);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            assigned.put(first, a);
            assigned.put(twin, b);
            MaterialAllocation allocation = new MaterialAllocationImpl();
            assertTrue(allocation.adopt(assigned, Map.of(first, envelope)));
            a.add(GLASS, 50L);
            envelope.add(GLASS, 50L);
            allocation.recordConsumption(first, GLASS, 4L);
            assertEquals(PlanAmount.of(10L), allocation.assignedAmount(first, GLASS));
            assertEquals(PlanAmount.of(20L), allocation.assignedAmount(twin, GLASS));
            assertEquals(PlanAmount.of(3L), allocation.envelopeAmount(first, GLASS));
            assertEquals(PlanAmount.of(6L), allocation.remainingFamilyQuota(first, GLASS));
            assertEquals(PlanAmount.of(20L), allocation.remainingFamilyQuota(twin, GLASS));
            assertEquals(2, allocation.allocatedPatternCount());
            assertEquals(MaterialAllocation.Source.PLAN, allocation.source());
        }

        void zeroBasisDiffersFromMissingPatternOrFamily() {
            var pattern = new Pattern(DEFINITION);
            var absent = new Pattern(AEItemKey.of(Items.COMPASS));
            MaterialAllocation allocation = new MaterialAllocationImpl();
            allocation.registerSubmitPattern(pattern);
            assertEquals(1, allocation.allocatedPatternCount());
            assertNull(allocation.remainingFamilyQuota(pattern, GLASS));
            allocation.mintShare(pattern, GLASS, BigInteger.ZERO);
            assertTrue(allocation.hasQuotaBasis(pattern, GLASS));
            assertFalse(allocation.hasQuotaBasis(pattern, IRON));
            assertEquals(PlanAmount.ZERO, allocation.remainingFamilyQuota(pattern, GLASS));
            assertEquals(PlanAmount.ZERO, allocation.remainingFamilyQuota(pattern, IRON));
            assertNull(allocation.remainingFamilyQuota(absent, GLASS));
        }

        void sameReservationReadsConsumptionImmediatelyAndOldRowsStayImmutable() {
            var pattern = new Pattern(DEFINITION);
            MaterialAllocation allocation = new MaterialAllocationImpl();
            allocation.mintShare(pattern, GLASS, BigInteger.TEN);
            var rows = allocation.rows(List.of(pattern));
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(1L));
            var tasks = Map.of((IPatternDetails) pattern, progress);
            var reservation = ParallelPlanReservation.build(tasks.entrySet(), Map.of(), allocation);
            assertEquals(10L, reservation.remainingQuota(pattern, GLASS));
            allocation.recordConsumption(pattern, GLASS, 4L);
            assertEquals(6L, reservation.remainingQuota(pattern, GLASS));
            assertTrue(rows.getFirst().eaten().isEmpty());
            assertEquals(PlanAmount.of(10L), rows.getFirst().assigned().getFirst().amount());
            assertThrows(UnsupportedOperationException.class, () -> rows.clear());
            assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().assigned().clear());
            allocation.recordConsumption(pattern, GLASS, 8L);
            assertEquals(0L, reservation.remainingQuota(pattern, GLASS));
            assertEquals(PlanAmount.of(-2L), allocation.rawFamilyBalance(pattern, GLASS));
        }

        void wideAmountsRemainExactAcrossMintConsumptionAndSnapshots() {
            var pattern = new Pattern(DEFINITION);
            var wide = new BigInteger("18000000000000000000");
            MaterialAllocation allocation = new MaterialAllocationImpl();
            allocation.mintShare(pattern, GLASS, wide);
            allocation.recordConsumption(pattern, GLASS, wide.subtract(BigInteger.ONE));
            assertEquals(PlanAmount.of(wide), allocation.assignedAmount(pattern, GLASS));
            assertEquals(PlanAmount.of(1L), allocation.remainingFamilyQuota(pattern, GLASS));
            assertEquals(PlanAmount.of(wide), allocation.rows(List.of(pattern)).getFirst()
                    .assigned().getFirst().amount());
        }

        void arrivalNarrowsInExecutionOrderWithoutChangingEnvelopeLicense() {
            var first = new Pattern(DEFINITION);
            var second = new Pattern(AEItemKey.of(Items.COMPASS));
            MaterialAllocation allocation = new MaterialAllocationImpl();
            allocation.adopt(Map.of(first, amount(GLASS, 10L), second, amount(GLASS, 10L)),
                    Map.of(first, amount(GLASS, 3L)));
            allocation.narrowArrival(List.of(second, first), GLASS,
                    PlanAmount.of(12L), PlanAmount.ZERO, PlanAmount.ZERO);
            assertEquals(PlanAmount.of(10L), allocation.assignedAmount(second, GLASS));
            assertEquals(PlanAmount.of(2L), allocation.assignedAmount(first, GLASS));
            assertEquals(PlanAmount.of(3L), allocation.envelopeAmount(first, GLASS));
        }

        void consumedOnlyPatternsAndOrphanEnvelopesRemainInDiagnosticRows() {
            var assigned = new Pattern(DEFINITION);
            var consumed = new Pattern(AEItemKey.of(Items.COMPASS));
            var orphan = new Pattern(AEItemKey.of(Items.CHEST));
            MaterialAllocation allocation = new MaterialAllocationImpl();
            allocation.adopt(Map.of(assigned, amount(GLASS, 10L)), Map.of(orphan, amount(IRON, 3L)));
            allocation.recordConsumption(consumed, GLASS, 2L);
            allocation.recordConsumption(consumed, GLASS, 1L);
            var rows = allocation.rows(List.of());
            assertEquals(3, rows.size());
            assertSame(assigned, rows.get(0).pattern());
            assertSame(orphan, rows.get(1).pattern());
            assertSame(consumed, rows.get(2).pattern());
            assertEquals(PlanAmount.of(3L), rows.get(2).eaten().getFirst().amount());
        }

        private static WideKeyCounter amount(AEKey key, long amount) {
            var counter = new WideKeyCounter();
            counter.set(key, amount);
            return counter;
        }

        private record Pattern(AEItemKey definition) implements IPatternDetails {
            @Override
            public AEItemKey getDefinition() { return definition; }
            @Override
            public IInput[] getInputs() { return new IInput[0]; }
            @Override
            public List<GenericStack> getOutputs() { return List.of(); }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.adoptionCopiesBothBooksAndPreservesEqualPatternIdentities", () -> new MaterialAllocationCases().adoptionCopiesBothBooksAndPreservesEqualPatternIdentities()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.zeroBasisDiffersFromMissingPatternOrFamily", () -> new MaterialAllocationCases().zeroBasisDiffersFromMissingPatternOrFamily()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.sameReservationReadsConsumptionImmediatelyAndOldRowsStayImmutable", () -> new MaterialAllocationCases().sameReservationReadsConsumptionImmediatelyAndOldRowsStayImmutable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.wideAmountsRemainExactAcrossMintConsumptionAndSnapshots", () -> new MaterialAllocationCases().wideAmountsRemainExactAcrossMintConsumptionAndSnapshots()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.arrivalNarrowsInExecutionOrderWithoutChangingEnvelopeLicense", () -> new MaterialAllocationCases().arrivalNarrowsInExecutionOrderWithoutChangingEnvelopeLicense()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialAllocationCases.consumedOnlyPatternsAndOrphanEnvelopesRemainInDiagnosticRows", () -> new MaterialAllocationCases().consumedOnlyPatternsAndOrphanEnvelopesRemainInDiagnosticRows())
            );
        }
    }

    /** MaterialWakeIndexCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialWakeIndexCases {
        void fourThousandStrictTiersOnlyReturnTheirExactConsumerAndCleanUp() {
            MaterialWakeIndex index = new MaterialWakeIndexImpl(pattern -> true);
            appeng.api.crafting.IPatternDetails selected = null;
            var arriving = FakePlanGrid.dyedKey(Items.PAPER, 137);
            for (int tier = 0; tier < 4000; tier++) {
                var key = FakePlanGrid.dyedKey(Items.PAPER, tier);
                var pattern = FakePattern.of(key, 1);
                index.register(pattern, Set.of(key));
                if (tier == 137) selected = pattern;
            }
            assertEquals(Set.of(selected), index.candidates(arriving));
            var replacement = AEItemKey.of(Items.GOLD_INGOT);
            index.register(selected, Set.of(replacement));
            assertTrue(index.candidates(arriving).isEmpty());
            assertEquals(Set.of(selected), index.candidates(replacement));
            index.remove(selected);
            assertTrue(index.candidates(replacement).isEmpty());
        }

        void unknownPatternsKeepFamilyCandidatesWithoutScanningUnrelatedItems() {
            MaterialWakeIndex index = new MaterialWakeIndexImpl();
            var paper = FakePattern.of(AEItemKey.of(Items.PAPER), 1);
            var iron = FakePattern.of(AEItemKey.of(Items.IRON_INGOT), 1);
            index.register(paper, Set.of(FakePlanGrid.dyedKey(Items.PAPER, 1), FakePlanGrid.dyedKey(Items.PAPER, 2)));
            index.register(iron, Set.of(AEItemKey.of(Items.IRON_INGOT)));
            assertEquals(Set.of(paper), index.candidates(FakePlanGrid.dyedKey(Items.PAPER, 3)));
            index.remove(paper);
            assertTrue(index.candidates(FakePlanGrid.dyedKey(Items.PAPER, 3)).isEmpty());
        }

        void diagnosticCellDoesNotFormatWideAmountsUntilItIsDisplayed() {
            var conversions = new AtomicInteger();
            var amount = new BigInteger(BigInteger.TEN.pow(4000).toByteArray()) {
                @Override public String toString() { conversions.incrementAndGet(); return super.toString(); }
            };
            var probe = new ParallelBatchExtraction.Probe();
            probe.noteMinCell(ParallelBatchExtraction.Probe.MinCellScope.HOLDER, 0, AEItemKey.of(Items.PAPER),
                    BigInteger.ONE, amount, null, Long.MAX_VALUE, BigInteger.ONE, BigInteger.ONE, 5, 5);
            assertEquals(0, conversions.get());
            var described = probe.describeMinCell();
            assertTrue(conversions.get() > 0);
            assertTrue(described.contains(amount.toString()));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialWakeIndexCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialWakeIndexCases.fourThousandStrictTiersOnlyReturnTheirExactConsumerAndCleanUp", () -> new MaterialWakeIndexCases().fourThousandStrictTiersOnlyReturnTheirExactConsumerAndCleanUp()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialWakeIndexCases.unknownPatternsKeepFamilyCandidatesWithoutScanningUnrelatedItems", () -> new MaterialWakeIndexCases().unknownPatternsKeepFamilyCandidatesWithoutScanningUnrelatedItems()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialWakeIndexCases.diagnosticCellDoesNotFormatWideAmountsUntilItIsDisplayed", () -> new MaterialWakeIndexCases().diagnosticCellDoesNotFormatWideAmountsUntilItIsDisplayed())
            );
        }
    }

    /** ParallelBatchExtractionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelBatchExtractionCases {
        private static final AEItemKey ORE = AEItemKey.of(Items.IRON_ORE);
        private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

        /** 富料消费者：一发两矿，带容器回流。 */
        private static final FakePattern RICH = new FakePattern(
                AEItemKey.of(Items.PAPER),
                new FakeInput[]{new FakeInput(new GenericStack(ORE, 2), 1L, BUCKET, Set.of(ORE))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 两个槽都吃同一种矿：批量按该键存量算，不能各算各的。 */
        private static final FakePattern TWIN_SLOTS = new FakePattern(
                AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                new FakeInput[]{
                        new FakeInput(new GenericStack(ORE, 1), 1L, null, Set.of(ORE)),
                        new FakeInput(new GenericStack(ORE, 1), 1L, null, Set.of(ORE))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        private static final AEItemKey COPPER = AEItemKey.of(Items.COPPER_INGOT);

        /** 单槽每发 10 枚：池子里不足 10 枚时整次提取都必须作废。 */
        private static final FakePattern TRICKLE = new FakePattern(
                AEItemKey.of(Items.STONE),
                new FakeInput[]{new FakeInput(new GenericStack(ORE, 1), 10L, null, Set.of(ORE))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 一发产 64 枚：份数一大，64×k 就溢出 long，产出账变成饱和假数。 */
        private static final FakePattern BULKY_OUTPUT = new FakePattern(
                AEItemKey.of(Items.CAULDRON),
                new FakeInput[]{new FakeInput(new GenericStack(ORE, 1), 1L, null, Set.of(ORE))},
                List.of(new GenericStack(PRODUCT, 64)));

        /** 槽0 带回流容器、槽1 吃另一种矿：后槽零碎时两槽的料都要退。 */
        private static final FakePattern ORE_AND_COPPER = new FakePattern(
                AEItemKey.of(Items.GRINDSTONE),
                new FakeInput[]{
                        new FakeInput(new GenericStack(ORE, 1), 2L, BUCKET, Set.of(ORE)),
                        new FakeInput(new GenericStack(COPPER, 1), 5L, null, Set.of(COPPER))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 单槽两位候选：铁矿石在前、铜矿石在后，规划期按顺位榨干靠前的再退下一个。 */
        private static final FakePattern FUZZY_ORDER = new FakePattern(
                AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(ORE, 1L), new GenericStack(COPPER, 1L)},
                        1L, null, Set.of(ORE, COPPER))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 与 {@link #FUZZY_ORDER} 同一条候选顺位、另一个样板：两个都能退料的槽抢同一份首位料。 */
        private static final FakePattern FUZZY_ORDER2 = new FakePattern(
                AEItemKey.of(Items.CHEST),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(ORE, 1L), new GenericStack(COPPER, 1L)},
                        1L, null, Set.of(ORE, COPPER))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 只吃一枚矿、没有第二候选：它的存量必须留在池子里，模糊槽不能把它改道吃掉。 */
        private static final FakePattern ORE_ONLY = new FakePattern(
                AEItemKey.of(Items.FURNACE),
                new FakeInput[]{new FakeInput(new GenericStack(ORE, 1), 1L, null, Set.of(ORE))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 烧铜产铁矿：这份铁矿是<b>还没派发</b>的产出，要按顺位先抵给占它的槽。 */
        private static final FakePattern COPPER_TO_ORE = new FakePattern(
                AEItemKey.of(Items.BREWING_STAND),
                new FakeInput[]{new FakeInput(new GenericStack(COPPER, 1), 1L, null, Set.of(COPPER))},
                List.of(new GenericStack(ORE, 1)));

        /** 烧桶产铜：铜是<b>后面才落地</b>的产出，现场还没有它也算这一格的退路。 */
        private static final FakePattern BUCKET_TO_COPPER = new FakePattern(
                AEItemKey.of(Items.COMPOSTER),
                new FakeInput[]{new FakeInput(new GenericStack(BUCKET, 1), 1L, null, Set.of(BUCKET))},
                List.of(new GenericStack(COPPER, 1)));

        /** 同一物品本体、不同耐久：AE2 按物品本体归为同一模糊族，已受损的排在全新之前。 */
        private static AEItemKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        private static final AEItemKey V1 = wornKey(50);
        private static final AEItemKey V2 = wornKey(0);

        /** 两个槽各吃同一物品本体族里的一个变体：规划给这一族定 5 枚（V1 三枚、V2 两枚）。 */
        private static final FakePattern TWIN_VARIANTS = new FakePattern(
                AEItemKey.of(Items.CRAFTING_TABLE),
                new FakeInput[]{
                        new FakeInput(new GenericStack(V1, 1), 1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack(V2, 1), 2L, null, Set.of(V2))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 单槽每发 3 枚、同族两个变体都落得下：混取时这一格要吃满 3 枚。 */
        private static final FakePattern MIX_FAMILY = new FakePattern(
                AEItemKey.of(Items.LOOM),
                new FakeInput[]{new FakeInput(
                        new GenericStack(V1, 1), 3L, null, Set.of(V1, V2))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 只吃得下 V1 这一份精确键的消费者：V1 的存量整批是它的。 */
        private static final FakePattern NEEDS_V1 = new FakePattern(
                AEItemKey.of(Items.BLAST_FURNACE),
                new FakeInput[]{new FakeInput(new GenericStack(V1, 1), 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 候选表只有一项，但按物品本体放行任意组件变体：第三方"万能样板"的槽形。 */
        private static final FakePattern ANY_VARIANT_SLOT = new FakePattern(
                AEItemKey.of(Items.SMOKER),
                new FakeInput[]{new FakeInput(new GenericStack(V1, 1), 1L, null, Set.of(V1, V2))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 没有提交期配给：作业按老口径（结构判据）取料。 */
        private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
        private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

        /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
        private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
                orderedTasks().entrySet(), Map.of(), NO_PLAN, NO_TAKEN);

        /** 任务表按给定顺序进账本：配给是顺序敏感的，Map.of 的序跨 JVM 不稳定。 */
        private static java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress> orderedTasks(Object... pairs) {
            var map = new java.util.LinkedHashMap<IPatternDetails,
                    ParallelExecutingCraftingJob.TaskProgress>();
            for (int i = 0; i < pairs.length; i += 2) {
                map.put((IPatternDetails) pairs[i],
                        (ParallelExecutingCraftingJob.TaskProgress) pairs[i + 1]);
            }
            return map;
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 提交期定版的配给：按样板实例索引，与 {@code planMaterialPlan} 同构。 */
        private static Map<IPatternDetails, WideKeyCounter> planMaterial(Object... pairs) {
            var map = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
            for (int i = 0; i < pairs.length; i += 2) {
                map.put((IPatternDetails) pairs[i], castShare(pairs[i + 1]));
            }
            return map;
        }

        /** 配给账的形状换了宽域账本（{@code planMaterialPlan} 换型），数值口径一字未动。 */
        private static WideKeyCounter share(Object... pairs) {
            var counter = new WideKeyCounter();
            for (int i = 0; i < pairs.length; i += 2) {
                counter.set((AEKey) pairs[i], (Long) pairs[i + 1]);
            }
            return counter;
        }

        @SuppressWarnings("unchecked")
        private static WideKeyCounter castShare(Object share) {
            return (WideKeyCounter) share;
        }

        private static ListCraftingInventory pool(long ore) {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, ore, Actionable.MODULATE);
            return inventory;
        }

        void batchFollowsThePoolNotTheRequestedFiringCount() {
            ListCraftingInventory inventory = pool(10L);
            KeyCounter expectedOutputs = new KeyCounter();
            KeyCounter expectedContainers = new KeyCounter();

            var batch = ParallelBatchExtraction.prepare(RICH, inventory, null, 6L,
                    expectedOutputs, expectedContainers, RESERVATION_FREE);

            assertNotNull(batch);
            assertEquals(5L, batch.executions(), "five firings' worth of ore was in the pool");
            assertEquals(0L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(5L, expectedOutputs.get(PRODUCT));
            assertEquals(5L, expectedContainers.get(BUCKET), "回流容器按每发一只计");
        }

        /** 同键多槽：按"每发合计吃几枚"算可行份数，5 枚撑两槽各 1 枚就是 2 发。 */
        void sharedKeyAcrossSlotsIsNotCountedTwice() {
            ListCraftingInventory inventory = pool(5L);

            var batch = ParallelBatchExtraction.prepare(TWIN_SLOTS, inventory, null, 9L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE);

            assertNotNull(batch, "两槽每发合计 2 枚，5 枚够开工两发");
            assertEquals(2L, batch.executions());
            assertEquals(2L, batch.holder()[0].get(ORE));
            assertEquals(2L, batch.holder()[1].get(ORE));
            assertEquals(1L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 料够就一次给完：亿级剩余发数不能靠"每轮一发"爬。
         *
         * <p>实机形态是合金炉计划合成 7.1G，一次交互只推进 1 发，吞吐被自己的记账卡住。
         */
        void hugePoolTwinSlotsGivesAllFiringsInOneBatch() {
            long firings = 1_780_000_000L;
            ListCraftingInventory inventory = pool(Math.multiplyExact(firings, 2L));

            var batch = ParallelBatchExtraction.prepare(TWIN_SLOTS, inventory, null, firings,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE);

            assertNotNull(batch);
            assertEquals(firings, batch.executions(), "原料够就不能退到单发");
            assertEquals(0L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 请求份数先夹到"每发量 × 份数仍装得进 long"，再谈提取。
         *
         * <p>此前是乘法饱和成 {@code Long.MAX_VALUE} 照样推给机器：份数写着 k、产出账却是
         * {@code Long.MAX_VALUE}，两者对不上，产出侧要么永远等齐不了、要么被当成已完成。
         */
        void requestIsClampedSoTheOutputLedgerStaysExact() {
            long fits = Long.MAX_VALUE / 64L;
            KeyCounter expectedOutputs = new KeyCounter();

            var batch = ParallelBatchExtraction.prepare(BULKY_OUTPUT, pool(fits), null, Long.MAX_VALUE,
                    expectedOutputs, new KeyCounter(), RESERVATION_FREE);

            assertNotNull(batch);
            assertEquals(fits, batch.executions(), "64 枚一发，份数最多只能到 long/64");
            assertEquals(Math.multiplyExact(fits, 64L), expectedOutputs.get(PRODUCT));
            assertTrue(expectedOutputs.get(PRODUCT) != Long.MAX_VALUE, "产出账不许是饱和出来的假数");
        }

        void perFiringMultiplierBoundsTheRequestToo() {
            // TRICKLE 每发吃 10 枚：回流/取料乘法同样不能溢出。
            assertEquals(Long.MAX_VALUE / 10L,
                    ParallelBatchExtraction.clampToRepresentablePerFiringAmounts(TRICKLE, Long.MAX_VALUE));
            assertEquals(6L, ParallelBatchExtraction.clampToRepresentablePerFiringAmounts(TRICKLE, 6L),
                    "够得着的请求不该被动到");
            assertEquals(1L, ParallelBatchExtraction.clampToRepresentablePerFiringAmounts(TRICKLE, 0L),
                    "至少留一发，装不下的样板交给提取逻辑判失败");
        }

        /**
         * 份数被谁钉住：探针必须给出"请求 vs 实得"同一次提取的配对与该槽的除法式子。
         *
         * <p>实机判读只认这一行——{@code stored ÷ demand} 是现场料就把因落在池子，
         * 落在别处（电、宿主上界）由同一记录里的 pwr/cap 指认。
         */
        void probePairsRequestedWithTheBindingSlotArithmetic() {
            ListCraftingInventory inventory = pool(5L);
            var probe = new ParallelBatchExtraction.Probe();
            probe.noteRequestContext(6L, Long.MAX_VALUE, 9L, RICH);

            var batch = ParallelBatchExtraction.prepare(RICH, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE, probe);

            assertNotNull(batch);
            assertEquals(2L, batch.executions());
            assertEquals(1L, probe.lostCount);
            assertEquals(6L, probe.lostReq);
            assertEquals(2L, probe.lostGot);
            assertEquals(-1L, probe.lostPwr, "不设限的电记成 -1，不与真实上界混读");
            assertEquals(ORE.toString(), probe.lostBindKey);
            assertEquals(5L, probe.lostBindStored);
            assertEquals(2L, probe.lostBindDemand);
        }

        /** 零碎料凑不满一发：整次尝试必须原数归位，否则每轮重试都在把这个键吃到 0。 */
        void starvedTrickleGoesBackToThePool() {
            ListCraftingInventory inventory = pool(4L);
            KeyCounter expectedOutputs = new KeyCounter();
            KeyCounter expectedContainers = new KeyCounter();

            var batch = ParallelBatchExtraction.prepare(TRICKLE, inventory, null, 6L,
                    expectedOutputs, expectedContainers, RESERVATION_FREE);

            assertNull(batch);
            assertEquals(4L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "已扣的半截料不能丢在池外");
            assertTrue(expectedOutputs.isEmpty());
            assertTrue(expectedContainers.isEmpty());
        }

        /** 前槽填满、后槽只够零碎：料全数归位，失败尝试的回流容器账不得残留给调用方。 */
        void laterStarvedSlotKeepsNeitherMaterialsNorRemainderClaims() {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, 2L, Actionable.MODULATE);
            inventory.insert(COPPER, 3L, Actionable.MODULATE);
            KeyCounter expectedOutputs = new KeyCounter();
            KeyCounter expectedContainers = new KeyCounter();

            var batch = ParallelBatchExtraction.prepare(ORE_AND_COPPER, inventory, null, 9L,
                    expectedOutputs, expectedContainers, RESERVATION_FREE);

            assertNull(batch);
            assertEquals(2L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(3L, inventory.extract(COPPER, Long.MAX_VALUE, Actionable.SIMULATE));
            assertTrue(expectedContainers.isEmpty(), "没开工就不该挂任何回流容器");
        }

        /**
         * 模糊槽按样板候选顺位取料：靠前的候选只够 2 发时也轮不到靠后的富料候选。
         *
         * <p>改取"谁能撑最多发"会让执行侧把规划期许诺给后续份数的同族富余量提前吃掉，
         * 尾段就出现无产者的缺口。
         */
        void fuzzySlotKeepsThePatternCandidateOrder() {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, 2L, Actionable.MODULATE);
            inventory.insert(COPPER, 100L, Actionable.MODULATE);

            var first = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE);

            assertNotNull(first);
            assertEquals(2L, first.executions(), "首批只能吃到靠前候选的存量");
            assertEquals(2L, first.holder()[0].get(ORE));
            assertEquals(100L, inventory.extract(COPPER, Long.MAX_VALUE, Actionable.SIMULATE),
                    "富料候选要留给后续份数，不能被这一批改道吃掉");

            var second = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE);

            assertNotNull(second);
            assertEquals(6L, second.executions(), "靠前的候选吃干后照旧打满批量");
            assertEquals(6L, second.holder()[0].get(COPPER));
        }

        /**
         * 计划自己产出的中间料会给模糊槽多出一个规划期没有的候选：这块料是别人唯一能用的，
         * 顺位再靠前也不能改道吃。实机形态是织布机槽位吃光计划给尾段预留的玻璃，
         * 尾段任务从此 stored=0 且无生产者。
         */
        void fuzzySlotSkipsMaterialReservedByOtherTasks() {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, 100L, Actionable.MODULATE);
            inventory.insert(COPPER, 100L, Actionable.MODULATE);
            var remainingTasks =
                    orderedTasks(
                            ORE_ONLY, progress(100L),
                            FUZZY_ORDER, progress(6L));

            var batch = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(),
                    MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                            Map.of(ORE, 100L, COPPER, 100L), NO_PLAN, NO_TAKEN));

            assertNotNull(batch);
            assertEquals(6L, batch.executions());
            assertEquals(0L, batch.holder()[0].get(ORE), "铁矿整批都被只吃得下它的任务占着");
            assertEquals(6L, batch.holder()[0].get(COPPER), "同一格退到第二个候选照样开工");
            assertEquals(100L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 退路还没落到池子，也不等于可以改吃别人唯一能用的那份。
         *
         * <p>家族槽排在首位的键常是别人唯一的口粮，而退路要等后面的发数才产出。只看
         * "现场落得下两个键"就把首位键放行，实机形态是 {@code refinedstorage:1k_storage_part}
         * 的玻璃槽：首位 {@code ae2:quartz_glass}、备选含 {@code minecraft:glass}，尾段
         * {@code ae2:quartz_glass x 1199245818 stored=0}，池子里躺着同数量的普通玻璃没人要。
         */
        void lateFallbackStillYieldsTheExclusiveConsumersKey() {
            ListCraftingInventory inventory = pool(100L);
            var remainingTasks = orderedTasks(
                    ORE_ONLY, progress(100L),
                    BUCKET_TO_COPPER, progress(100L),
                    FUZZY_ORDER, progress(6L));

            var batch = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(),
                    MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                            Map.of(ORE, 100L, BUCKET, 100L), NO_PLAN, NO_TAKEN));

            assertNull(batch, "铜还没产出来，这一格既不能吃铁矿也不能空转：整批作废");
            assertEquals(100L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "单发回退也不能从池子里抠走别人的口粮");
        }

        /**
         * 两个都能改道的槽抢同一份首位料：占料不能把计划的首位料缺口锁成死料。
         *
         * <p>锁按"人人占料"记，就把缺口那部分永远锁在池子里，取不到它的恰恰是能改道的槽，
         * 整张任务表一起起步不了（{@code blocked=[soloReady=0/108]}）。
         */
        void spillableSlotsDoNotStrandTheFirstCandidate() {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, 6L, Actionable.MODULATE);
            inventory.insert(COPPER, 6L, Actionable.MODULATE);
            var remainingTasks = orderedTasks(
                    FUZZY_ORDER, progress(4L),
                    FUZZY_ORDER2, progress(4L));
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(ORE, 6L, COPPER, 6L), NO_PLAN, NO_TAKEN);

            var first = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 4L,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(first);
            assertEquals(4L, first.holder()[0].get(ORE), "能改道的槽没有理由不吃顺位第一");

            var second = ParallelBatchExtraction.prepare(FUZZY_ORDER2, inventory, null, 4L,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(second);
            assertEquals(2L, second.executions());
            assertEquals(2L, second.holder()[0].get(ORE));
            assertEquals(0L, inventory.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "剩下的铁矿要能被吃掉，不能被锁成死料");
        }

        /**
         * 规划把这一格付在铜上，执行侧就得吃铜：铁矿整池摆在面前也不能改吃顺位第一。
         *
         * <p>占料锁在这张表上看不见缺口——烧铜产铁的 300 枚足够抵住 100 枚铁矿的独占，
         * 按现场存量重算归属就会把这 6 枚铁照吃，尾段只吃得下铁的任务就此缺料。
         * 实机形态是 {@code ae2:charged_certus_quartz_crystal}：规划把 526 份拆给未充能水晶，
         * 执行侧一路吃充能到底，尾段 {@code stored=2} 缺 430。
         */
        void plannedAllocationPutsTheFuzzySlotOnTheKeyThePlanChose() {
            var remainingTasks = orderedTasks(
                    ORE_ONLY, progress(100L),
                    COPPER_TO_ORE, progress(300L),
                    FUZZY_ORDER, progress(6L));
            var material = planMaterial(
                    ORE_ONLY, share(ORE, 100L),
                    COPPER_TO_ORE, share(COPPER, 300L),
                    FUZZY_ORDER, share(COPPER, 6L));
            var taken = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(ORE, 100L, COPPER, 400L), material, taken);
            var slot = FUZZY_ORDER.getInputs()[0];

            // 这一格规划里没分到铁：整池 100 枚全是别人的口粮。
            assertEquals(0L, reservation.usableFor(ORE, 100L, FUZZY_ORDER, 6L, slot, false));
            // 铜的现场存量比配额多 394 枚也没用：配额是硬上限，只放规划写下的那 6 枚。
            assertEquals(6L, reservation.usableFor(COPPER, 400L, FUZZY_ORDER, 6L, slot, false));

            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(ORE, 100L, Actionable.MODULATE);
            inventory.insert(COPPER, 400L, Actionable.MODULATE);
            var batch = ParallelBatchExtraction.prepare(FUZZY_ORDER, inventory, null, 6L,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch);
            assertEquals(6L, batch.executions());
            assertEquals(0L, batch.holder()[0].get(ORE), "规划没配给它铁，就不能按顺位改吃铁");
            assertEquals(6L, batch.holder()[0].get(COPPER));

            // 销账只看真提走了多少：份额取完就是取完了，多出来的存量不是它那份料。
            taken.computeIfAbsent(FUZZY_ORDER, ignored -> new WideKeyCounter())
                    .set(COPPER, 6L);
            var afterTake = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(ORE, 100L, COPPER, 394L), material, taken);
            assertEquals(0L, afterTake.usableFor(COPPER, 394L, FUZZY_ORDER, 6L, slot, false));
            // 同一张表上，只吃得下铁的锁主不被夹：整池铁矿都是它配给内的。
            assertEquals(100L, afterTake.usableFor(ORE, 100L, ORE_ONLY, 100L,
                    ORE_ONLY.getInputs()[0], false));
        }

        /**
         * 规划侧认下的<b>无限源</b>键不挨任何一道 {@code long} 域夹子：配给封顶与占料锁两条路径
         * 都退回现场量，仅豁免明确登记的精确键（V1 登记不代表 V2 也能生成）。
         *
         * <p>实机形态是 1e8 份订单卡死时的 {@code locked=[minecraft:lava stored=9223372036854775807
         * usable=0]}：池子里躺着 9.2E 枚岩浆，是<b>我们自己</b>那份配额/占料账把它判成取不动，
         * 而"无限"的语义是没有数值上界——在它身上夹出来的任何数都是假数。
         *
         * <p>对照组是同一份任务表、同一对象料、同一份配给与已吃账，只把无限键集换成空：
         * 三个读数必须逐字不变（把这条判据打回去，就是 {@link #plannedAllocationPutsTheFuzzySlotOnTheKeyThePlanChose}
         * 修过的模糊槽顺位）。
         */
        void unboundedSupplyKeysEscapeEveryLongDomainClamp() {
            var remainingTasks = orderedTasks(
                    ORE_ONLY, progress(100L),
                    FUZZY_ORDER, progress(6L),
                    FUZZY_ORDER2, progress(6L),
                    NEEDS_V1, progress(100L),
                    TWIN_VARIANTS, progress(4L));
            // FUZZY_ORDER 这一格规划定在铁上 6 枚、TWIN_VARIANTS 一族定 5 枚（V1 三枚、V2 两枚）
            var material = planMaterial(
                    FUZZY_ORDER, share(ORE, 6L),
                    TWIN_VARIANTS, share(V1, 3L, V2, 2L));
            // 两笔都已销完：份额 = 配给 − 已吃 = 0
            var taken = planMaterial(
                    FUZZY_ORDER, share(ORE, 6L),
                    TWIN_VARIANTS, share(V1, 3L, V2, 2L));
            Map<AEKey, Long> pool = Map.of(ORE, 100L, COPPER, 100L, V1, 100L, V2, 100L);
            var control = MaterialAllocationFixtures.build(
                    remainingTasks.entrySet(), pool, material, taken);
            var unbounded = MaterialAllocationFixtures.build(
                    remainingTasks.entrySet(), pool, material, taken, Set.of(ORE, V1));

            // 一、份额已销完：control 夹到 0，无限键压根没有配额口径（-1）
            assertEquals(0L, control.remainingQuota(FUZZY_ORDER, ORE));
            assertEquals(-1L, unbounded.remainingQuota(FUZZY_ORDER, ORE),
                    "无限键不许被夹成 0：那等于把还能推的批次永远判死");
            assertEquals(0L, control.usableFor(ORE, 100L, FUZZY_ORDER, 6L,
                    FUZZY_ORDER.getInputs()[0], false));
            assertEquals(100L, unbounded.usableFor(ORE, 100L, FUZZY_ORDER, 6L,
                    FUZZY_ORDER.getInputs()[0], false));

            // 二、占料锁：ORE_ONLY 是唯一吃得住这 100 枚的任务，control 一格也不放给改得道的槽
            assertEquals(0L, control.usableFor(ORE, 100L, FUZZY_ORDER2, 6L,
                    FUZZY_ORDER2.getInputs()[0], true), "对照组：整批铁矿都锁给独占槽");
            assertEquals(-1L, control.quotaFor(ORE, FUZZY_ORDER2, FUZZY_ORDER2.getInputs()[0]),
                    "它压根不在配给表里：0 读数只可能来自占料锁");
            assertEquals(100L, unbounded.usableFor(ORE, 100L, FUZZY_ORDER2, 6L,
                    FUZZY_ORDER2.getInputs()[0], true), "无限键不许被预留任何量");

            // 三、精确来源豁免：V1 放行，未登记的 V2 仍受有限库存约束
            assertEquals(0L, control.usableFor(V1, 100L, TWIN_VARIANTS, 4L,
                    TWIN_VARIANTS.getInputs()[0], false));
            assertEquals(100L, unbounded.usableFor(V1, 100L, TWIN_VARIANTS, 4L,
                    TWIN_VARIANTS.getInputs()[0], false));
            assertEquals(0L, control.usableFor(V2, 100L, TWIN_VARIANTS, 4L,
                    TWIN_VARIANTS.getInputs()[1], false), "族配额 5 枚已销完");
            assertEquals(0L, unbounded.usableFor(V2, 100L, TWIN_VARIANTS, 4L,
                    TWIN_VARIANTS.getInputs()[1], false), "未配置的同族变体不能凭空生成");

            // 四、非无限键逐字不变：规划没把这一格配在铜上，铜就照样是 0 份额
            assertEquals(0L, control.usableFor(COPPER, 100L, FUZZY_ORDER, 6L,
                    FUZZY_ORDER.getInputs()[0], false));
            assertEquals(0L, unbounded.usableFor(COPPER, 100L, FUZZY_ORDER, 6L,
                    FUZZY_ORDER.getInputs()[0], false));
            assertEquals(0L, control.remainingQuota(TWIN_VARIANTS, V1));
            assertEquals(0L, unbounded.remainingQuota(TWIN_VARIANTS, COPPER));
            assertFalse(unbounded.isUnboundedSupply(COPPER), "没登记过的键不许被顺带豁免");
        }

        /**
         * 候选表只有一项、按物品本体放行多个变体的槽：被精确任务占着的变体不能吃。
         *
         * <p>模糊不是候选表撑出来的：AE2 取料按物品本体把同类变体一并递回，再由该槽的
         * isValid 过滤。只看候选表长度会漏掉这一整类槽。
         */
        void singleCandidateVariantSlotKeepsReservedVariant() {
            var remainingTasks = orderedTasks(
                    NEEDS_V1, progress(100L),
                    ANY_VARIANT_SLOT, progress(6L));

            var freePool = variantPool(100L, 6L);
            var unclamped = ParallelBatchExtraction.prepare(ANY_VARIANT_SLOT, freePool, null, 6L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE);
            assertNotNull(unclamped);
            assertEquals(6L, unclamped.holder()[0].get(V1), "没人占料时本槽顺位第一吃的就是 v1");

            ListCraftingInventory pool = variantPool(100L, 6L);
            var batch = ParallelBatchExtraction.prepare(ANY_VARIANT_SLOT, pool, null, 6L,
                    new KeyCounter(), new KeyCounter(),
                    MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                            Map.of(V1, 100L, V2, 6L), NO_PLAN, NO_TAKEN));

            assertNotNull(batch);
            assertEquals(6L, batch.executions());
            assertEquals(0L, batch.holder()[0].get(V1), "v1 整批是精确任务的唯一用料");
            assertEquals(6L, batch.holder()[0].get(V2), "换个变体照样开工");
            assertEquals(100L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 同一批提取里，一个物品本体族的配额只能花一遍。
         *
         * <p>封顶按<b>族</b>算剩余份额（{@code ParallelPlanReservation.shareOf}），批内已经承诺的量
         * 却按<b>精确键</b>累加（{@code demandPerFiring}）：两个槽各吃同族一个变体时，每一槽都按
         * "整份族配额"被放行，合起来就超出规划定死的份数。销账按 holder 实提量全额记
         * （{@code ParallelCraftingCpuLogic.recordTakenInputs}），于是配额账比规划定数多出这一截
         * ——实机形态 {@code site=batch minecraft:glass ate=11992458236 share=11992458235}：
         * 多吃的那 1 枚把这一族的份额顶成 0，尾段留在池里的那枚玻璃从此谁也拿不到
         * （{@code stored=1 toMake=0} 的永久 backoff）。
         */
        void familyQuotaIsNotSpentTwiceWithinOneBatch() {
            var remainingTasks = orderedTasks(TWIN_VARIANTS, progress(4L));
            var material = planMaterial(TWIN_VARIANTS, share(V1, 3L, V2, 2L));
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L), material, NO_TAKEN);
            ListCraftingInventory inventory = variantPool(100L, 100L);

            var batch = ParallelBatchExtraction.prepare(TWIN_VARIANTS, inventory, null, 4L,
                    new KeyCounter(), new KeyCounter(), reservation);

            assertNotNull(batch, "族配额 5 枚、每发合计 3 枚：至少能开工一发");
            assertEquals(3L, familyTotal(batch.holder()),
                    "这一族每发合计 3 枚、配额只剩 5 枚 => 一批最多提 3 枚，不能各槽按整份配额花两遍");
        }

        /**
         * 单发混取同族两个候选：定数之外一枚都不能多吃。
         *
         * <p>{@code prepareSingleFiring} 在一格里逐个候选累加，每个候选都拿"整份族剩余配额"
         * 当自己的上限 ⇒ 同族两个候选各吃一遍。规划说这一族给 2 枚，第 3 枚就不该被接受；
         * 凑不满这一格就整批作废、料原数归位（既不能超，也不能把零碎吃干）。
         */
        void mixedSingleFiringStaysInsideTheFamilyQuota() {
            var remainingTasks = orderedTasks(MIX_FAMILY, progress(1L));
            var material = planMaterial(MIX_FAMILY, share(V1, 2L));
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 10L, V2, 10L), material, NO_TAKEN);
            ListCraftingInventory inventory = variantPool(10L, 10L);

            var batch = ParallelBatchExtraction.prepare(MIX_FAMILY, inventory, null, 1L,
                    new KeyCounter(), new KeyCounter(), reservation);

            assertNull(batch, "这一格要 3 枚而族配额只剩 2 枚：混取不能吃第 3 枚");
            assertEquals(10L, inventory.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE),
                    "作废的尝试不能把同族零碎吃在池外");
            assertEquals(10L, inventory.extract(V2, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 同族三个变体各占一槽、每槽一发 {@code 4e18} 枚：这一族的每发合计 {@code 1.2e19} 枚与
         * 族配额 {@code 2.4e19} 枚<b>都越出 {@code long}</b>。
         *
         * <p>真答案是"还能开 2 发"。改前这道商在 {@code long} 域做：分子是"宁高估"钉到
         * {@link Long#MAX_VALUE} 的配额<b>读数</b>，分母是 {@code addOrMax} 同样钉到天花板的每发合计
         * <b>读数</b>——{@code Long.MAX_VALUE / Long.MAX_VALUE = 1}，两份哨兵相除读出"只开得起一发"，
         * 剩下那一发（{@code 1.2e19} 枚配额 + 池子里躺着的料）永远推不出去。
         *
         * <p>现在分子走 {@code quotaExact} 的宽域真值、分母按各槽每发件数的<b>精确</b>合计累加，
         * 商做完才窄化一次（{@code cap} 本来就是 {@code long}）。
         */
        void familyQuotaAboveLongDividesInTheWideDomain() {
            var pattern = TRIPLE_FAMILY_VARIANTS;
            var remainingTasks = orderedTasks(pattern, progress(2L));
            var material = planMaterial(pattern, wideShare(V1, QUOTA_24E19));
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(), material, NO_TAKEN);
            var slot0 = pattern.getInputs()[0];
            // 本用例必须走真配额口径：读口报得出份额（>=0）才算数，全走 RESERVATION_FREE 是假闭环。
            long quotaReadout = reservation.quotaFor(V1, pattern, slot0);
            assertTrue(quotaReadout >= 0L, "quotaFor 必须报得出这一族的份额，否则夹子压根没生效");
            assertEquals(Long.MAX_VALUE, quotaReadout,
                    "2.4e19 枚的族配额在 long 读数上顶到天花板：它是哨兵，不是量");
            assertEquals("24000000000000000000",
                    String.valueOf(reservation.quotaExact(V1, pattern, slot0)),
                    "宽域读口交出的是真值：除法只能拿它做分子");

            var pool = triplePool(8_000_000_000_000_000_000L);
            var batch = ParallelBatchExtraction.payDeclaredObligation(pattern, pool, null, 2L,
                    reservation, null);

            assertNotNull(batch, "池子里三键各摆着两发的料、配额也够两发：一发不许被夹成零");
            assertEquals(2L, batch.executions(),
                    "族配额 2.4e19 枚 ÷ 每发合计 1.2e19 枚 = 2 发：两个天花板读数相除得 1 就是把哨兵当了量");
            assertEquals(8_000_000_000_000_000_000L, batch.holder()[0].get(V1));
            assertEquals(8_000_000_000_000_000_000L, batch.holder()[1].get(V2));
            assertEquals(8_000_000_000_000_000_000L, batch.holder()[2].get(V3));
            assertEquals(0L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE),
                    "两发正好把这一族的配额花满：三个键都该被提空");
            assertEquals(0L, pool.extract(V3, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 混取那一格吃同族三个候选、一发三刀：族配额 {@code 1.2e19} 枚刚好够<b>一整发</b>。
         *
         * <p>改前死在第三刀上：份额读的是顶到天花板的 {@code long} 读数、已吃账也是
         * （{@code 4e18 + 4e18 = 8e18} 之后仍与天花板做差），
         * {@code (Long.MAX_VALUE - 8e18) / 4e18 = 0} ⇒ 第三枚候选被判"配额用尽" ⇒ 这一格凑不满 ⇒
         * <b>整发作废</b>，池里明明还有料、配额明明还剩 4e18 枚。
         *
         * <p>这一条是 {@code templatesInsideFamilyQuota} 那一道"先减后除"的口子：减法与除法现在
         * 全在宽域上做，做完才窄化一次。
         */
        void mixedSingleFiringPaysItsLastTemplateOnTheTrueFamilyQuota() {
            var pattern = MIX_TRIPLE_FAMILY;
            var remainingTasks = orderedTasks(pattern, progress(1L));
            var material = planMaterial(pattern, wideShare(V1, TRIPLE_FAMILY_UNITS));
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(), material, NO_TAKEN);
            var slot = pattern.getInputs()[0];
            assertTrue(reservation.quotaFor(V1, pattern, slot) >= 0L,
                    "本用例走真配额口径：没有配额口径就等于没测这道夹子");
            assertEquals("12000000000000000000",
                    String.valueOf(reservation.quotaExact(V1, pattern, slot)),
                    "一发的真值：三个候选各 4e18 枚，配额刚好够这一发");

            // 三个变体各摆一发要吃的 4e18 枚：配额刚好够这一发，池子也刚好够这一发。
            var pool = triplePool(4_000_000_000_000_000_000L);

            var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 1L,
                    new KeyCounter(), new KeyCounter(), reservation);

            assertNotNull(batch,
                    "配额刚好够一整发：把第三刀判成配额用尽就是拿两份哨兵相减当量用");
            assertEquals(1L, batch.executions());
            assertEquals(TRIPLE_FAMILY_UNITS, familyTotalExact(batch.holder()),
                    "三刀各 4e18 枚，整发合计 1.2e19 枚（越出 long 也要按真值交出来）");
            assertEquals(0L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(0L, pool.extract(V2, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(0L, pool.extract(V3, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * {@code usableFor} 那道 {@code min(现场量, 配额读数)} <b>不会</b>把"还能补的那一截"判成 0：
         * 饱和的配额读数只会让它<b>不夹</b>（分子是天花板时 min 交出的是现场量），要夹出 0 只能是
         * 份额真的销完了——那正是配额这条硬上限该干的事。
         *
         * <p>三条读数各钉一个方向：族配额越界（顶天花板的哨兵）⇒ 交回现场量；配额剩 4e18 枚 ⇒
         * 夹到 4e18（宁少不多，一枚不许多放）；配额销尽 ⇒ 夹到 0。
         * 与 {@code quotaExact} 同一条判据（同一格豁免、同一个族合计），两侧不会再分叉。
         */
        void saturatingQuotaReadoutNeverZeroesWhatIsStillRefundable() {
            var pattern = TRIPLE_FAMILY_VARIANTS;
            var remainingTasks = orderedTasks(pattern, progress(2L));
            var slot0 = pattern.getInputs()[0];

            // 一、族配额 2.4e19 枚（long 读数=天花板）：现场 5e18 枚一枚不少地放行。
            var wideQuota = MaterialAllocationFixtures.build(remainingTasks.entrySet(), Map.of(),
                    planMaterial(pattern, wideShare(V1, QUOTA_24E19)), NO_TAKEN);
            assertEquals(Long.MAX_VALUE, wideQuota.quotaFor(V1, pattern, slot0), "先确认读数顶在天花板");
            assertEquals(5_000_000_000_000_000_000L,
                    wideQuota.usableFor(V1, 5_000_000_000_000_000_000L, pattern, 2L, slot0, false),
                    "饱和的配额读数不是那道夹子：把还能付的那一截判成 0 就是上一刀的死锁同族");

            // 二、族配额只剩 3e18 枚（装得进 long，读数是精确值）：现场 5e18 枚被夹到 3e18，宁少不多。
            var tightQuota = MaterialAllocationFixtures.build(remainingTasks.entrySet(), Map.of(),
                    planMaterial(pattern, wideShare(V1, TIGHT_QUOTA_UNITS)), NO_TAKEN);
            assertEquals(3_000_000_000_000_000_000L,
                    tightQuota.usableFor(V1, 5_000_000_000_000_000_000L, pattern, 2L, slot0, false),
                    "配额是硬上限：这一族定死 3e18 枚，现场多出来的不许放");

            // 三、份额销尽：夹到 0（封顶仍然生效，换宽域没放宽它）。
            var taken = new WideKeyCounter();
            taken.add(V1, TIGHT_QUOTA_UNITS);
            var spent = MaterialAllocationFixtures.build(remainingTasks.entrySet(), Map.of(),
                    planMaterial(pattern, wideShare(V1, TIGHT_QUOTA_UNITS)),
                    planMaterial(pattern, taken));
            assertEquals(0L, spent.usableFor(V1, 5_000_000_000_000_000_000L, pattern, 2L, slot0, false),
                    "销完就是 0：这一格一枚也不许再从这一族付");
        }

        /**  holder 各槽里落在 V1 那一族上的<b>真值</b>合计（{@code long} 累加会回绕，这里走宽域）。 */
        private static java.math.BigInteger familyTotalExact(KeyCounter[] holder) {
            var family = V1.getPrimaryKey();
            java.math.BigInteger total = java.math.BigInteger.ZERO;
            for (KeyCounter slot : holder) {
                if (slot == null) {
                    continue;
                }
                for (var entry : slot) {
                    if (entry.getKey() != null && entry.getKey().getPrimaryKey().equals(family)) {
                        total = total.add(java.math.BigInteger.valueOf(entry.getLongValue()));
                    }
                }
            }
            return total;
        }

        /** 同一物品本体的第三个组件变体：三槽各吃一个变体才凑得出越出 {@code long} 的族合计。 */
        private static final AEItemKey V3 = wornKey(75);
        /** 一族三个变体各 {@code 4e18} 枚：每发合计 {@code 1.2e19} 枚，越出 {@code long}。 */
        private static final java.math.BigInteger TRIPLE_FAMILY_UNITS =
                java.math.BigInteger.valueOf(4_000_000_000_000_000_000L).multiply(
                        java.math.BigInteger.valueOf(3L));
        /** 族配额 {@code 2.4e19} 枚：够开两发，long 读数顶在天花板上。 */
        private static final java.math.BigInteger QUOTA_24E19 =
                TRIPLE_FAMILY_UNITS.multiply(java.math.BigInteger.valueOf(2L));
        /** 装得进 {@code long} 的族配额：这道夹子的读数在这一个用例里是精确值。 */
        private static final java.math.BigInteger TIGHT_QUOTA_UNITS =
                java.math.BigInteger.valueOf(3_000_000_000_000_000_000L);

        /** 三槽各吃同族一个变体、每槽每发 4e18 枚：族每发合计 1.2e19 枚（越出 {@code long}）。 */
        private static final FakePattern TRIPLE_FAMILY_VARIANTS = new FakePattern(
                AEItemKey.of(Items.SMITHING_TABLE),
                new FakeInput[]{
                        new FakeInput(new GenericStack(V1, 4_000_000_000_000_000_000L), 1L, null,
                                Set.of(V1)),
                        new FakeInput(new GenericStack(V2, 4_000_000_000_000_000_000L), 1L, null,
                                Set.of(V2)),
                        new FakeInput(new GenericStack(V3, 4_000_000_000_000_000_000L), 1L, null,
                                Set.of(V3))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 一槽三个候选、每刀 4e18 枚、倍率 3：一发要吃三刀，走的正是 {@code prepareSingleFiring}。 */
        private static final FakePattern MIX_TRIPLE_FAMILY = new FakePattern(
                AEItemKey.of(Items.FLETCHING_TABLE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{
                                new GenericStack(V1, 4_000_000_000_000_000_000L),
                                new GenericStack(V2, 4_000_000_000_000_000_000L),
                                new GenericStack(V3, 4_000_000_000_000_000_000L)},
                        3L, null, Set.of(V1, V2, V3))},
                List.of(new GenericStack(PRODUCT, 1)));

        /** 三个变体各放 {@code each} 枚的窄域池子（每键都装得进 {@code long}）。 */
        private static ListCraftingInventory triplePool(long each) {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(V1, each, Actionable.MODULATE);
            inventory.insert(V2, each, Actionable.MODULATE);
            inventory.insert(V3, each, Actionable.MODULATE);
            return inventory;
        }

        /** 越出 {@code long} 的配给：<b>真配额</b>口径，配给表本身是宽域账。 */
        private static WideKeyCounter wideShare(AEKey key, java.math.BigInteger amount) {
            var counter = new WideKeyCounter();
            counter.setExact(key, amount);
            return counter;
        }

        /**
         * 因果链坐实（第四刀）：两本配给账的<b>饱和合并</b>把"还能推几发"算成 0，尾段批次被误判配额用尽。
         *
         * <p>输入串刻意让<b>两本账各自的真值都越出 {@code long}、而它们的差仍装得进 {@code long}</b>：
         * <ul>
         *   <li>配给：同族两个变体各被两个槽复加一次（{@code 6e18 + 6e18}），族合计 {@code 2.4e19} 枚；</li>
         *   <li>已吃：同族两个变体各跨两批销账（{@code 5e18 + 5e18}），族合计 {@code 2e19} 枚；</li>
         *   <li>真剩余 {@code 4e18} 枚 ⇒ 这一族还剩一百多万亿发可开。</li>
         * </ul>
         *
         * <p>老口径下每一笔合并都停在 {@link Long#MAX_VALUE}，于是"族合计 − 族合计"读成 {@code 0}
         * ⇒ {@code ParallelBatchExtraction.executionsInsideFamilyQuota} 夹出 0 发 ⇒ {@code pickCandidate}
         * 连"至少一发"的闸口都过不去 ⇒ 返回 {@code null}，任务被判配额用尽而拒推。
         * 对照段就把<b>老口径实际会存下的那批饱和读数</b>原样喂进同一道判据，量出这个 0。
         */
        void saturatedPlanLedgersFalselyZeroOutAFamilyQuotaThatStillHasShare() {
            var remainingTasks = orderedTasks(TWIN_VARIANTS, progress(4L));

            // 宽域账本：逐槽/逐批复加，合计越出 long 也保住真值。
            var wideMaterial = new WideKeyCounter();
            var wideTaken = new WideKeyCounter();
            for (AEKey variant : new AEKey[]{V1, V2}) {
                wideMaterial.add(variant, 6_000_000_000_000_000_000L);
                wideMaterial.add(variant, 6_000_000_000_000_000_000L);
                wideTaken.add(variant, 5_000_000_000_000_000_000L);
                wideTaken.add(variant, 5_000_000_000_000_000_000L);
            }
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L),
                    planMaterial(TWIN_VARIANTS, wideMaterial),
                    planMaterial(TWIN_VARIANTS, wideTaken));
            assertEquals(4_000_000_000_000_000_000L, reservation.remainingQuota(TWIN_VARIANTS, V1),
                    "族配给 2.4e19 枚减去已吃 2e19 枚：还剩 4e18 枚，一枚都不能少算");

            var batch = ParallelBatchExtraction.prepare(TWIN_VARIANTS, variantPool(100L, 100L),
                    null, 4L, new KeyCounter(), new KeyCounter(), reservation);

            assertNotNull(batch, "还剩 4e18 枚族配额、池里也有料：这一批必须推得动");
            assertEquals(4L, batch.executions());
            assertEquals(12L, familyTotal(batch.holder()), "4 发 × 每发合计 3 枚");

            // 对照：老口径在这串输入上实际会存下的读数（每笔合并顶在 Long.MAX_VALUE）。
            var legacyMaterial = new WideKeyCounter();
            var legacyTaken = new WideKeyCounter();
            for (AEKey variant : new AEKey[]{V1, V2}) {
                legacyMaterial.set(variant, Long.MAX_VALUE);
                legacyTaken.set(variant, Long.MAX_VALUE);
            }
            var legacyReservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L),
                    planMaterial(TWIN_VARIANTS, legacyMaterial),
                    planMaterial(TWIN_VARIANTS, legacyTaken));
            assertEquals(0L, legacyReservation.remainingQuota(TWIN_VARIANTS, V1),
                    "两本账都顶到天花板 ⇒ 相减读成 0：这就是尾段 stall 的那一步");
            assertNull(ParallelBatchExtraction.prepare(TWIN_VARIANTS, variantPool(100L, 100L),
                            null, 4L, new KeyCounter(), new KeyCounter(), legacyReservation),
                    "老口径下这一批被判配额用尽而整批作废");
        }

        /**
         * 销账"不超不少"的判据行为<b>换宽域后一字不变</b>：配额吃满就是 0 发，剩一发量就恰好放行。
         *
         * <p>本刀只把两本账的算术换宽，没放宽任何封顶：已吃账与配给账相等时 {@code quota=0}
         * ⇒ 提取作废；差额只剩一发用料时 {@code quota=1} ⇒ 只放行一发。
         */
        void writeoffStillClampsExactlyAtTheWideFamilyQuota() {
            var remainingTasks = orderedTasks(NEEDS_V1, progress(4L));
            var material = new WideKeyCounter();
            material.add(V1, 7_000_000_000_000_000_000L);
            material.add(V1, 7_000_000_000_000_000_000L);

            // 不超：已吃 1.4e19 枚 = 配给 1.4e19 枚 ⇒ 份额归零，池里有料也不许再提一枚。
            var fullyWrittenOff = new WideKeyCounter();
            fullyWrittenOff.add(V1, 7_000_000_000_000_000_000L);
            fullyWrittenOff.add(V1, 7_000_000_000_000_000_000L);
            var spentReservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L), planMaterial(NEEDS_V1, material),
                    planMaterial(NEEDS_V1, fullyWrittenOff));
            assertEquals(0L, spentReservation.remainingQuota(NEEDS_V1, V1), "销完即 0 份额");
            assertNull(ParallelBatchExtraction.prepare(NEEDS_V1, variantPool(100L, 100L),
                    null, 4L, new KeyCounter(), new KeyCounter(), spentReservation));

            // 不少：已吃 1.3e19 枚 ⇒ 还剩 1e18 枚，一发的量给得出来就不该把批次夹成 0。
            var partlyWrittenOff = new WideKeyCounter();
            partlyWrittenOff.add(V1, 7_000_000_000_000_000_000L);
            partlyWrittenOff.add(V1, 6_000_000_000_000_000_000L);
            var partlyReservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L), planMaterial(NEEDS_V1, material),
                    planMaterial(NEEDS_V1, partlyWrittenOff));
            assertEquals(1_000_000_000_000_000_000L, partlyReservation.remainingQuota(NEEDS_V1, V1));
            var batch = ParallelBatchExtraction.prepare(NEEDS_V1, variantPool(100L, 100L),
                    null, 4L, new KeyCounter(), new KeyCounter(), partlyReservation);
            assertNotNull(batch);
            assertEquals(4L, batch.executions());
            assertEquals(4L, familyTotal(batch.holder()));
        }

        /**
         * 对外出口仍只饱和一次：真剩余也越出 {@code long} 时，判据读数钉在 {@link Long#MAX_VALUE}，
         * 既不回绕成负、也不把封顶整道撤掉。
         */
        void quotaExitReadingStillSaturatesAtLongMax() {
            var remainingTasks = orderedTasks(NEEDS_V1, progress(4L));
            var material = new WideKeyCounter();
            material.add(V1, 7_000_000_000_000_000_000L);
            material.add(V1, 7_000_000_000_000_000_000L);
            var reservation = MaterialAllocationFixtures.build(remainingTasks.entrySet(),
                    Map.of(V1, 100L, V2, 100L), planMaterial(NEEDS_V1, material), NO_TAKEN);

            assertEquals(Long.MAX_VALUE, reservation.remainingQuota(NEEDS_V1, V1),
                    "账本内部精确 1.4e19 枚，判据的 long 出口仍按宁高估口径饱和一次");
        }

        /** holder 里属于该物品本体族（V1/V2 同一 primary key）的合计枚数。 */
        private static long familyTotal(KeyCounter[] holder) {
            long total = 0L;
            for (KeyCounter slot : holder) {
                if (slot == null) {
                    continue;
                }
                for (var entry : slot) {
                    if (entry.getKey() != null && entry.getKey().getPrimaryKey()
                            .equals(V1.getPrimaryKey())) {
                        total += entry.getLongValue();
                    }
                }
            }
            return total;
        }

        private static ListCraftingInventory variantPool(long v1, long v2) {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(V1, v1, Actionable.MODULATE);
            inventory.insert(V2, v2, Actionable.MODULATE);
            return inventory;
        }

        /**
         * 三本 long 域账里<b>多笔复加撞顶</b>要撤账，不是钉成 {@code Long.MAX_VALUE}。
         *
         * <p>两个各只有一条路的槽，在同一键上各记 {@code Long.MAX/2 + 1}：单项都装得进 long
         * （过得了"单项越界不记账"那道豁免），合计越界。老口径 {@code saturatingAdd} 把
         * {@code firstCandidateNeed}/{@code firstCandidateDemand} 钉成哨兵 ⇒
         * {@code usableFor} 拿池子读数去减它，另一位改得了道的消费者恒 {@code usable=0}
         * （实机 {@code locked=[minecraft:lava stored=9223372036854775807 usable=0]}、
         * {@code noCand} 常驻、欠账永冻，且只在续传作业上发作）。新口径撤账 ⇒ 交回池子现量：
         * 宁可少一道悲观预留，也不把算不出可信口径的那一截当当场可兑现的债权。
         */
        void multiSlotSumBeyondLongCancelsTheKeyInsteadOfLockingItAtSentinel() {
            long halfPlusOne = Long.MAX_VALUE / 2L + 1L;
            var lockerA = new FakePattern(AEItemKey.of(Items.LEVER),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, halfPlusOne),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var lockerB = new FakePattern(AEItemKey.of(Items.BARREL),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, halfPlusOne),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var reservation = MaterialAllocationFixtures.build(
                    orderedTasks(lockerA, progress(1L), lockerB, progress(1L)).entrySet(),
                    Map.of(), NO_PLAN, NO_TAKEN);

            var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                    new GenericStack(PRODUCT, 1L)}, 1L, null, Set.of(ORE, PRODUCT));
            var consumer = new FakePattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                    new FakeInput[]{reroutable},
                    List.of(new GenericStack(PRODUCT, 1)));

            assertEquals(500L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, true),
                    "多笔合计越界不是可信口径：撤账后 usableFor 交回池子现量，"
                            + "不是拿哨兵当量把该键对所有人钉成 0");
        }

        /**
         * 产出这本账是<b>贷方</b>：多笔合计越界时不许用"少一笔贷方"把占料锁<b>收紧</b>。
         *
         * <p>两张样板各产 {@code Long.MAX/2 + 1} 枚铁矿 ⇒ {@code pendingProduction[ORE]} 合计越界。
         * 撤条目那条路（与两本负债账同形状）在这里方向是反的：{@code releaseable=0} ⇒
         * {@code reserved} 等于整份锁（100 枚）⇒ 这一键对改得了道的消费者照扣到底，正是类注释
         * 41-42 行那条"永远兑不了的锁、谁也取不到料"。新口径把"这一键的产出账放不下可信口径"
         * 做成显式事实 ⇒ <b>这一键不下悲观锁</b> ⇒ 交回池子现量。
         */
        void untrustworthyProductionCreditUnlocksTheKeyInsteadOfLockingItTighter() {
            long halfPlusOne = Long.MAX_VALUE / 2L + 1L;
            var locker = new FakePattern(AEItemKey.of(Items.FURNACE),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, 100L),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var producerA = new FakePattern(AEItemKey.of(Items.BLAST_FURNACE),
                    new FakeInput[]{new FakeInput(new GenericStack(BUCKET, 1L),
                            1L, null, Set.of(BUCKET))},
                    List.of(new GenericStack(ORE, halfPlusOne)));
            var producerB = new FakePattern(AEItemKey.of(Items.SMOKER),
                    new FakeInput[]{new FakeInput(new GenericStack(BUCKET, 1L),
                            1L, null, Set.of(BUCKET))},
                    List.of(new GenericStack(ORE, halfPlusOne)));
            var reservation = MaterialAllocationFixtures.build(
                    orderedTasks(producerA, progress(1L), producerB, progress(1L),
                            locker, progress(1L)).entrySet(),
                    Map.of(ORE, 500L, COPPER, 40L), NO_PLAN, NO_TAKEN);

            var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                    new GenericStack(COPPER, 1L)}, 1L, null, Set.of(ORE, COPPER));
            var consumer = new FakePattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                    new FakeInput[]{reroutable}, List.of(new GenericStack(PRODUCT, 1)));

            assertEquals(500L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, false),
                    "产出账放不下可信口径只能不下锁：交回池子现量，不许用少一笔贷方扣掉那 100 枚");
        }

        /**
         * 同一本贷方账里<b>一笔</b>饱和哨兵与<b>多笔</b>合计撞顶是同一个事实 ⇒ 同一个宽松读数。
         *
         * <p>老口径 {@code merge} 在 {@code stored == null} 时直接 {@code put}，一笔
         * {@code saturatingMultiply} 的 {@code Long.MAX_VALUE} 照样进得了产出账当贷方：这里它躺在
         * <b>备选键</b>铜上，被 {@code canAvoidThisKey} 读成"这条退路上计划有量"⇒ 给伸手的槽
         * <b>加上</b>一道锁（铁矿那 100 枚照扣）。同一本账一笔算可信、两笔算不可信，自相矛盾。
         * 新口径两种形状合流成"该键产出账没有可信口径"：不可信的那一截不算供给 ⇒ 这一格判成
         * 改不了道 ⇒ 不夹，池子现量原样交回。
         */
        void singleSaturatedProductionCreditReadsTheSameAsAMultiSumOverflow() {
            long halfPlusOne = Long.MAX_VALUE / 2L + 1L;
            var locker = new FakePattern(AEItemKey.of(Items.FURNACE),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, 100L),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var bulkyProducer = new FakePattern(AEItemKey.of(Items.GRINDSTONE),
                    new FakeInput[]{new FakeInput(new GenericStack(BUCKET, 1L),
                            1L, null, Set.of(BUCKET))},
                    List.of(new GenericStack(COPPER, halfPlusOne)));
            var reservation = MaterialAllocationFixtures.build(
                    orderedTasks(bulkyProducer, progress(3L), locker, progress(1L)).entrySet(),
                    Map.of(), NO_PLAN, NO_TAKEN);

            var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                    new GenericStack(COPPER, 1L)}, 1L, null, Set.of(ORE, COPPER));
            var consumer = new FakePattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                    new FakeInput[]{reroutable}, List.of(new GenericStack(PRODUCT, 1)));

            assertEquals(500L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, false),
                    "一笔饱和哨兵与多笔撞顶同读数：不可信的产出既不放开锁也不加锁，这一键不夹");
        }

        /**
         * 撤过账的键不许被晚来的第四笔记回——半截账照样是哨兵冒充量的那条来路。
         *
         * <p>前三笔各 {@code Long.MAX/2 + 1} 枚把铁矿的占料账顶越界（第二笔即撤账），第四笔晚来的
         * 任务只要 100 枚。少了"撤过不再记回"那一句，这一笔会把<b>半截账</b>记回去
         * （只剩第四笔那 100 枚，前三笔的账已经没了）：锁按 4.6e18 枚记，改得了道的消费者这一键
         * 恒 {@code usable=0}——正是撤账要修的那条死锁在这里原地复发。
         */
        void revokedDebtLedgerStaysCancelledForLateTasks() {
            long halfPlusOne = Long.MAX_VALUE / 2L + 1L;
            var lockerA = new FakePattern(AEItemKey.of(Items.LEVER),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, halfPlusOne),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var lockerB = new FakePattern(AEItemKey.of(Items.BARREL),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, halfPlusOne),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var lockerC = new FakePattern(AEItemKey.of(Items.CHEST),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, halfPlusOne),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var lateLocker = new FakePattern(AEItemKey.of(Items.COMPOSTER),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, 100L),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var reservation = MaterialAllocationFixtures.build(
                    orderedTasks(lockerA, progress(1L), lockerB, progress(1L),
                            lockerC, progress(1L), lateLocker, progress(1L)).entrySet(),
                    Map.of(ORE, 500L, COPPER, 40L), NO_PLAN, NO_TAKEN);

            var reroutable = new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                    new GenericStack(COPPER, 1L)}, 1L, null, Set.of(ORE, COPPER));
            var consumer = new FakePattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                    new FakeInput[]{reroutable}, List.of(new GenericStack(PRODUCT, 1)));

            assertEquals(500L, reservation.usableFor(ORE, 500L, consumer, 1L, reroutable, false),
                    "撤过账的键第四笔也不许记回：半截账会把该键对改得了道的消费者扣成 0");
        }

        /**
         * {@code self} 那一格 {@code chargedUnits} 饱和 ⇒ 归还账里<b>不含</b>这一格。
         *
         * <p>归还账（{@code reservedForExclusiveConsumers} 最后那一句减法）还的是"锁主自己这份配额"。
         * 把 {@code Long.MAX_VALUE} 那道<b>哨兵</b>当归还量累进去，别人的锁就被整笔放开：这里锁主要
         * 100 枚铁矿、池子 500 枚，正确读数只放 400 枚给改得了道的槽；哨兵一进账就交回 500 枚，
         * 等于从只剩 2 枚口粮的尾段消费者嘴里把那 100 枚多放行出去（实机 {@code exclusive=456 stored=2}
         * 那一族）。
         */
        void saturatedSelfSlotContributesNothingToTheRefundLedger() {
            long halfPlusOne = Long.MAX_VALUE / 2L + 1L;
            var locker = new FakePattern(AEItemKey.of(Items.FURNACE),
                    new FakeInput[]{new FakeInput(new GenericStack(ORE, 100L),
                            1L, null, Set.of(ORE))},
                    List.of(new GenericStack(PRODUCT, 1)));
            var self = new FakePattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                    new FakeInput[]{
                            new FakeInput(new GenericStack(ORE, halfPlusOne), 1L, null, Set.of(ORE)),
                            new FakeInput(new GenericStack[]{new GenericStack(ORE, 1L),
                                    new GenericStack(COPPER, 1L)}, 1L, null, Set.of(ORE, COPPER))
                    },
                    List.of(new GenericStack(PRODUCT, 1)));
            var reservation = MaterialAllocationFixtures.build(
                    orderedTasks(locker, progress(1L)).entrySet(),
                    Map.of(), NO_PLAN, NO_TAKEN);

            var asker = self.getInputs()[1];
            assertEquals(400L, reservation.usableFor(ORE, 500L, self, 3L, asker, true),
                    "饱和的那一格不进归还账：只还得起可信的那 0 枚，别人的 100 枚锁照留");
        }

        private record FakeInput(GenericStack[] candidates, long multiplier,
                @Nullable AEKey remainder, Set<AEKey> accepts) implements IPatternDetails.IInput {

            private FakeInput(GenericStack candidate, long multiplier,
                    @Nullable AEKey remainder, Set<AEKey> accepts) {
                this(new GenericStack[]{candidate}, multiplier, remainder, accepts);
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates.clone();
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                return accepts.contains(input);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainder;
            }
        }

        private record FakePattern(AEItemKey definition, FakeInput[] slots,
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
            org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.batchFollowsThePoolNotTheRequestedFiringCount", () -> new ParallelBatchExtractionCases().batchFollowsThePoolNotTheRequestedFiringCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.sharedKeyAcrossSlotsIsNotCountedTwice", () -> new ParallelBatchExtractionCases().sharedKeyAcrossSlotsIsNotCountedTwice()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.hugePoolTwinSlotsGivesAllFiringsInOneBatch", () -> new ParallelBatchExtractionCases().hugePoolTwinSlotsGivesAllFiringsInOneBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.requestIsClampedSoTheOutputLedgerStaysExact", () -> new ParallelBatchExtractionCases().requestIsClampedSoTheOutputLedgerStaysExact()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.perFiringMultiplierBoundsTheRequestToo", () -> new ParallelBatchExtractionCases().perFiringMultiplierBoundsTheRequestToo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.probePairsRequestedWithTheBindingSlotArithmetic", () -> new ParallelBatchExtractionCases().probePairsRequestedWithTheBindingSlotArithmetic()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.starvedTrickleGoesBackToThePool", () -> new ParallelBatchExtractionCases().starvedTrickleGoesBackToThePool()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.laterStarvedSlotKeepsNeitherMaterialsNorRemainderClaims", () -> new ParallelBatchExtractionCases().laterStarvedSlotKeepsNeitherMaterialsNorRemainderClaims()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.fuzzySlotKeepsThePatternCandidateOrder", () -> new ParallelBatchExtractionCases().fuzzySlotKeepsThePatternCandidateOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.fuzzySlotSkipsMaterialReservedByOtherTasks", () -> new ParallelBatchExtractionCases().fuzzySlotSkipsMaterialReservedByOtherTasks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.lateFallbackStillYieldsTheExclusiveConsumersKey", () -> new ParallelBatchExtractionCases().lateFallbackStillYieldsTheExclusiveConsumersKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.spillableSlotsDoNotStrandTheFirstCandidate", () -> new ParallelBatchExtractionCases().spillableSlotsDoNotStrandTheFirstCandidate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.plannedAllocationPutsTheFuzzySlotOnTheKeyThePlanChose", () -> new ParallelBatchExtractionCases().plannedAllocationPutsTheFuzzySlotOnTheKeyThePlanChose()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.unboundedSupplyKeysEscapeEveryLongDomainClamp", () -> new ParallelBatchExtractionCases().unboundedSupplyKeysEscapeEveryLongDomainClamp()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.singleCandidateVariantSlotKeepsReservedVariant", () -> new ParallelBatchExtractionCases().singleCandidateVariantSlotKeepsReservedVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.familyQuotaIsNotSpentTwiceWithinOneBatch", () -> new ParallelBatchExtractionCases().familyQuotaIsNotSpentTwiceWithinOneBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.mixedSingleFiringStaysInsideTheFamilyQuota", () -> new ParallelBatchExtractionCases().mixedSingleFiringStaysInsideTheFamilyQuota()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.familyQuotaAboveLongDividesInTheWideDomain", () -> new ParallelBatchExtractionCases().familyQuotaAboveLongDividesInTheWideDomain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.mixedSingleFiringPaysItsLastTemplateOnTheTrueFamilyQuota", () -> new ParallelBatchExtractionCases().mixedSingleFiringPaysItsLastTemplateOnTheTrueFamilyQuota()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.saturatingQuotaReadoutNeverZeroesWhatIsStillRefundable", () -> new ParallelBatchExtractionCases().saturatingQuotaReadoutNeverZeroesWhatIsStillRefundable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.saturatedPlanLedgersFalselyZeroOutAFamilyQuotaThatStillHasShare", () -> new ParallelBatchExtractionCases().saturatedPlanLedgersFalselyZeroOutAFamilyQuotaThatStillHasShare()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.writeoffStillClampsExactlyAtTheWideFamilyQuota", () -> new ParallelBatchExtractionCases().writeoffStillClampsExactlyAtTheWideFamilyQuota()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.quotaExitReadingStillSaturatesAtLongMax", () -> new ParallelBatchExtractionCases().quotaExitReadingStillSaturatesAtLongMax()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.multiSlotSumBeyondLongCancelsTheKeyInsteadOfLockingItAtSentinel", () -> new ParallelBatchExtractionCases().multiSlotSumBeyondLongCancelsTheKeyInsteadOfLockingItAtSentinel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.untrustworthyProductionCreditUnlocksTheKeyInsteadOfLockingItTighter", () -> new ParallelBatchExtractionCases().untrustworthyProductionCreditUnlocksTheKeyInsteadOfLockingItTighter()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.singleSaturatedProductionCreditReadsTheSameAsAMultiSumOverflow", () -> new ParallelBatchExtractionCases().singleSaturatedProductionCreditReadsTheSameAsAMultiSumOverflow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.revokedDebtLedgerStaysCancelledForLateTasks", () -> new ParallelBatchExtractionCases().revokedDebtLedgerStaysCancelledForLateTasks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelBatchExtractionCases.saturatedSelfSlotContributesNothingToTheRefundLedger", () -> new ParallelBatchExtractionCases().saturatedSelfSlotContributesNothingToTheRefundLedger())
            );
        }
    }

    /** ParallelCpuHostContractCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCpuHostContractCases {
        void widePlanReachesMaterialCheckForExplicitAndAutomaticParallelCpuSelection() {
            var output = AEItemKey.of(Items.IRON_INGOT);
            for (var required : List.of(BigInteger.valueOf(100_000_000_000L), BigInteger.TEN.pow(30))) {
                for (boolean autoSelect : new boolean[] { false, true }) {
                    MEStorage emptyStorage = () -> Component.literal("空库存");
                    var grid = new DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.TestGrid(emptyStorage);
                    var host = new HostImpl();
                    host.grid = grid;
                    host.processing = true;
                    var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
                    var cpuGrid = new ParallelCraftingCpuGrid(grid);
                    cpuGrid.setClusters(List.of(cluster));

                    long clamped = required.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
                    var used = new KeyCounter();
                    used.add(output, clamped);
                    var base = new CraftingPlan(new GenericStack(output, clamped), 0L, false, false,
                            used, new KeyCounter(), new KeyCounter(), Map.of());
                    var wideUsed = new WideKeyCounter();
                    wideUsed.setExact(output, required);
                    var plan = new WideAmountCraftingPlan(base,
                            WideLedgerSnapshot.capture(null, wideUsed, null), null, Set.of());

                    var result = cpuGrid.submitJob(plan, null,
                            autoSelect ? null : cluster.remainingCapacityCpu(), true, null);
                    assertFalse(result.successful());
                    assertEquals(CraftingSubmitErrorCode.MISSING_INGREDIENT, result.errorCode());
                    assertEquals(output, assertInstanceOf(GenericStack.class, result.errorDetail()).what());
                    assertEquals(0, cluster.activeLaneCount());
                }
            }
        }

        void registeredInactiveCpuRejectsNormalAndPartialJobsWithoutCreatingLanes() {
            var grid = FakePlanGrid.FakeGrid.builder().build();
            var host = new HostImpl();
            host.grid = grid;
            host.node = grid.node;
            var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
            var cpuGrid = new ParallelCraftingCpuGrid(grid);
            cpuGrid.setClusters(List.of(cluster));
            assertTrue(cpuGrid.hasCpu(cluster.remainingCapacityCpu()));

            var normal = offlineState(cpuGrid.submitJob(
                    plan(false), null, cluster.remainingCapacityCpu(), true, null));
            assertEquals("host-inactive", normal.reason());
            assertTrue(normal.providerReady());
            assertFalse(normal.canProcess());
            assertTrue(normal.nodePresent());
            assertEquals(Boolean.TRUE, normal.powered());
            assertEquals(Boolean.TRUE, normal.booted());
            assertEquals(Boolean.TRUE, normal.channelsSatisfied());
            assertTrue(normal.sameGrid());
            assertTrue(normal.attachedToSubmittingGrid());

            var partial = offlineState(cpuGrid.submitPartialJob(
                    plan(true), null, cluster.remainingCapacityCpu(), true, null));
            assertEquals("partial-host-inactive", partial.reason());
            assertEquals(0, cluster.activeLaneCount());
        }

        void removedCpuReportsRegistrationFailureInsteadOfInventingPowerFailure() {
            var grid = FakePlanGrid.FakeGrid.builder().build();
            var host = new HostImpl();
            var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
            var cpuGrid = new ParallelCraftingCpuGrid(grid);
            cpuGrid.setClusters(List.of(cluster));
            cpuGrid.setClusters(List.of());

            var state = offlineState(cpuGrid.submitJob(
                    plan(false), null, cluster.remainingCapacityCpu(), true, null));
            assertEquals("cluster-not-registered", state.reason());
            assertFalse(state.nodePresent());
            assertNull(state.powered());
            assertNull(state.booted());
            assertNull(state.channelsSatisfied());
            assertFalse(state.attachedToSubmittingGrid());
            assertEquals(0, cluster.activeLaneCount());
        }

        private static ParallelCraftingCpuCluster.OfflineSubmissionState offlineState(ICraftingSubmitResult result) {
            assertFalse(result.successful());
            assertEquals(CraftingSubmitErrorCode.CPU_OFFLINE, result.errorCode());
            return assertInstanceOf(ParallelCraftingCpuCluster.OfflineSubmissionState.class, result.errorDetail());
        }

        private static CraftingPlan plan(boolean simulation) {
            return new CraftingPlan(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L), 0L, simulation, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        }

        void clusterUsesHostIdentityAndCurrentSelectionAndRunningState() {
            var host = new HostImpl();
            var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
            assertSame(host, cluster.source().machine().orElseThrow());
            assertTrue(cluster.canBeAutoSelectedFor(null));
            assertFalse(cluster.isPreferredFor(null));
            assertTrue(cluster.isActive());
            assertFalse(cluster.canProcessJobs());

            host.mode = CpuSelectionMode.MACHINE_ONLY;
            host.processing = true;
            assertTrue(cluster.canBeAutoSelectedFor(null));
            assertTrue(cluster.isPreferredFor(null));
            assertTrue(cluster.canProcessJobs());

            host.mode = CpuSelectionMode.PLAYER_ONLY;
            host.active = false;
            host.processing = false;
            assertFalse(cluster.canBeAutoSelectedFor(null));
            assertFalse(cluster.isPreferredFor(null));
            assertFalse(cluster.isActive());
            assertFalse(cluster.canProcessJobs());
        }

        /** 尚未入世界/接网的测试宿主；运行状态由场景独立驱动，不依赖方块构造副作用。 */
        private static final class HostImpl implements ParallelCpuHost {
            private int saves;
            @Override public void saveChanges() { saves++; }
            private CpuSelectionMode mode = CpuSelectionMode.ANY;
            private boolean active = true;
            private boolean processing;
            private IGrid grid;
            private IGridNode node;
            @Override
            public @Nullable IGrid getGrid() { return grid; }
            @Override
            public @Nullable Level getLevel() { return null; }
            @Override
            public @Nullable IGridNode getActionableNode() { return node; }
            @Override
            public CpuSelectionMode getSelectionMode() { return mode; }
            @Override
            public boolean isParallelCpuProviderActive() { return active; }
            @Override
            public boolean canProcessParallelCpuJobs() { return processing; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelCpuHostContractCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuHostContractCases.widePlanReachesMaterialCheckForExplicitAndAutomaticParallelCpuSelection", () -> new ParallelCpuHostContractCases().widePlanReachesMaterialCheckForExplicitAndAutomaticParallelCpuSelection()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuHostContractCases.registeredInactiveCpuRejectsNormalAndPartialJobsWithoutCreatingLanes", () -> new ParallelCpuHostContractCases().registeredInactiveCpuRejectsNormalAndPartialJobsWithoutCreatingLanes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuHostContractCases.removedCpuReportsRegistrationFailureInsteadOfInventingPowerFailure", () -> new ParallelCpuHostContractCases().removedCpuReportsRegistrationFailureInsteadOfInventingPowerFailure()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuHostContractCases.clusterUsesHostIdentityAndCurrentSelectionAndRunningState", () -> new ParallelCpuHostContractCases().clusterUsesHostIdentityAndCurrentSelectionAndRunningState())
            );
        }
    }

    /** ParallelCpuProviderBackoffCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCpuProviderBackoffCases {
        void rejectedPatternDoesNotBackOffAnotherPatternOnSameReadyProvider() {
            var rejected = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
            var other = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.GOLD_INGOT), 1L, java.util.Map.of());
            var provider = new AlwaysReadyProvider(List.of(rejected, other));
            var backoff = new ParallelCpuProviderBackoff(2, 40);

            backoff.recordPushRejected(provider, rejected, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                    backoff.checkProvider(provider, rejected, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.READY,
                    backoff.checkProvider(provider, other, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
        }

        void busyProviderStillBacksOffEveryPattern() {
            var first = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
            var second = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.GOLD_INGOT), 1L, java.util.Map.of());
            var provider = new BusyProvider(List.of(first, second));
            var backoff = new ParallelCpuProviderBackoff(2, 40);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BUSY,
                    backoff.checkProvider(provider, first, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L));
            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                    backoff.checkProvider(provider, second, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
        }

        /**
         * F1-②：申报腿自己被拒 ⇒ <b>下一窗申报腿确实退避</b>。
         *
         * <p>防的是把"按腿分桶"做成"申报腿永不退避"：分掉的是 holder 腿那一桶，不是申报腿这本账。
         */
        void declaredLegRejectionStillBacksOffTheDeclaredLegNextWindow() {
            var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
            var provider = new AlwaysReadyProvider(List.of(pattern));
            var backoff = new ParallelCpuProviderBackoff(2, 40);

            backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 10L);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                    backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                    "申报腿拒收后下一窗照旧退避：分桶不许把这一腿的账一并抹掉");
        }

        /**
         * F1-③：holder 腿自己推被拒 ⇒ 退避<b>只作用于 holder 腿</b>，申报腿不受影响。
         */
        void holderLegRejectionDoesNotBackOffTheDeclaredLeg() {
            var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
            var provider = new AlwaysReadyProvider(List.of(pattern));
            var backoff = new ParallelCpuProviderBackoff(2, 40);

            backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                    backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                    "holder 腿那一桶照旧得退避（旧语义不许漂）");
            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.READY,
                    backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                    "两条腿分开记：holder 腿被拒不许堵申报批那条路");
        }

        /**
         * F1-④：{@code recordPushAccepted} 的清账<b>只清同一条腿</b>那一桶。
         */
        void acceptedPushClearsOnlyTheSameLeg() {
            var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
            var provider = new AlwaysReadyProvider(List.of(pattern));
            var backoff = new ParallelCpuProviderBackoff(2, 40);

            backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);
            backoff.recordPushAccepted(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                    backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                    "申报腿接受一分没解 holder 腿的账：反向也一样");

            backoff.recordPushAccepted(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER);

            assertEquals(
                    ParallelCpuProviderBackoff.ProviderAvailability.READY,
                    backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                    "同一条腿的接受即清账（与改前同语义）");
        }

        private record AlwaysReadyProvider(List<IPatternDetails> patterns) implements ICraftingProvider {
            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return patterns;
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return true;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
        }

        private record BusyProvider(List<IPatternDetails> patterns) implements ICraftingProvider {
            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return patterns;
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return false;
            }

            @Override
            public boolean isBusy() {
                return true;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases.rejectedPatternDoesNotBackOffAnotherPatternOnSameReadyProvider", () -> new ParallelCpuProviderBackoffCases().rejectedPatternDoesNotBackOffAnotherPatternOnSameReadyProvider()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases.busyProviderStillBacksOffEveryPattern", () -> new ParallelCpuProviderBackoffCases().busyProviderStillBacksOffEveryPattern()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases.declaredLegRejectionStillBacksOffTheDeclaredLegNextWindow", () -> new ParallelCpuProviderBackoffCases().declaredLegRejectionStillBacksOffTheDeclaredLegNextWindow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases.holderLegRejectionDoesNotBackOffTheDeclaredLeg", () -> new ParallelCpuProviderBackoffCases().holderLegRejectionDoesNotBackOffTheDeclaredLeg()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCpuProviderBackoffCases.acceptedPushClearsOnlyTheSameLeg", () -> new ParallelCpuProviderBackoffCases().acceptedPushClearsOnlyTheSameLeg())
            );
        }
    }

    /** ParallelCraftingCpuLogicCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCraftingCpuLogicCases {
        private static final AEItemKey OUTPUT = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
        private static final AEItemKey REMAINDER = AEItemKey.of(new ItemStack(Items.BUCKET));

        void exactCellInitialExtractionTakesEntireWideUsedAndNeverRefillsConsumedPool() {
            var demand = BigInteger.TEN.pow(100).add(BigInteger.valueOf(17));
            var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
            cell.insertExact(OUTPUT, demand.multiply(BigInteger.TWO), Actionable.MODULATE, IActionSource.empty());
            var pool = new WideCraftingInventory(ignored -> {});
            var plan = planWithWideUsed(OUTPUT, demand);
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, cell, pool, IActionSource.empty(), null));
            assertEquals(demand, pool.amount(OUTPUT));
            assertEquals(demand, cell.exactAmount(OUTPUT));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
            pool.extractExact(OUTPUT, demand, Actionable.MODULATE);
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, cell, IActionSource.empty(), () -> true));
            assertEquals(demand, cell.exactAmount(OUTPUT));
        }

        void exactCellContinuationSettlesOnlyPersistedPlannedRemainderInOneOperation() {
            var demand = BigInteger.TEN.pow(80);
            var plan = planWithWideUsed(OUTPUT, demand);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(OUTPUT, BigInteger.valueOf(19), Actionable.MODULATE);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
            cell.insertExact(OUTPUT, demand.multiply(BigInteger.TWO), Actionable.MODULATE, IActionSource.empty());
            var budget = new java.util.concurrent.atomic.AtomicInteger();
            assertEquals(demand.subtract(BigInteger.valueOf(19)), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, cell, IActionSource.empty(), () -> budget.getAndIncrement() == 0));
            assertEquals(demand, pool.amount(OUTPUT));
            assertEquals(demand.add(BigInteger.valueOf(19)), cell.exactAmount(OUTPUT));
            assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
        }

        void exactCellPartialReceiptDoesNotInventMissingMaterialOrRepeatCollectedAmount() {
            var max = BigInteger.valueOf(Long.MAX_VALUE);
            var demand = max.multiply(BigInteger.TEN);
            var plan = planWithWideUsed(OUTPUT, demand);
            var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
            var have = max.multiply(BigInteger.TWO).add(BigInteger.valueOf(7));
            cell.insertExact(OUTPUT, have, Actionable.MODULATE, IActionSource.empty());
            var pool = new WideCraftingInventory(ignored -> {});
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, cell, pool, IActionSource.empty(), null));
            assertEquals(have, pool.amount(OUTPUT));
            assertEquals(BigInteger.ZERO, cell.exactAmount(OUTPUT));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            assertEquals(demand.subtract(have), job.initialItemsRemaining.amount(OUTPUT));
            cell.insertExact(OUTPUT, demand, Actionable.MODULATE, IActionSource.empty());
            assertEquals(demand.subtract(have), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, cell, IActionSource.empty(), () -> true));
            assertEquals(demand, pool.amount(OUTPUT));
            assertEquals(have, cell.exactAmount(OUTPUT));
        }

        void resumedJobRecognizesExplicitSupplyWithoutDiscardingInventoryOrWaitingOutputs() {
            BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
            var plan = planWithWideUsed(OUTPUT, amount);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            job.initialItemsRemaining.insertExact(OUTPUT, amount, Actionable.MODULATE);
            ((WideCraftingInventory) job.waitingFor).insertExact(OUTPUT, amount, Actionable.MODULATE);
            var supply = new LyingInfiniteStorage(OUTPUT);
            ParallelCraftingCpuLogic.refreshJobUnboundedSupply(job, supply, IActionSource.empty());
            assertTrue(job.isUnboundedSupply(OUTPUT));
            assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
            assertEquals(amount, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
            var keys = new java.util.HashSet<AEKey>();
            var pool = new WideCraftingInventory(ignored -> {});
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, supply, pool, IActionSource.empty(), keys));
            assertTrue(keys.contains(OUTPUT));
            assertEquals(BigInteger.ZERO, pool.amount(OUTPUT));
        }

        void infiniteSupplyDoesNotExemptUnconfiguredComponentVariant() {
            var named = new ItemStack(Items.IRON_INGOT);
            named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("有限变体"));
            var variant = AEItemKey.of(named);
            assertEquals(OUTPUT.getPrimaryKey(), variant.getPrimaryKey());
            var plan = planWithWideUsed(variant, BigInteger.TEN);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            job.noteUnboundedSupply(OUTPUT);
            assertTrue(job.isUnboundedSupply(OUTPUT));
            assertFalse(job.isUnboundedSupply(variant));
            var reservation = ParallelPlanReservation.build(List.of(), Map.of(), null, java.util.Set.of(OUTPUT));
            assertTrue(reservation.isUnboundedSupply(OUTPUT));
            assertFalse(reservation.isUnboundedSupply(variant));
            var pool = new WideCraftingInventory(ignored -> {});
            assertNotNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, new LyingInfiniteStorage(OUTPUT),
                    pool, IActionSource.empty(), new java.util.HashSet<>()));
            assertEquals(BigInteger.ZERO, pool.amount(variant));
        }

        void resumedFiniteStorageRevokesOldInfiniteIdentityWithoutChangingMaterialOrWaiting() {
            BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
            var job = new ParallelExecutingCraftingJob(planWithWideUsed(OUTPUT, amount), ignored -> {},
                    (CraftingLink) null, null);
            job.noteUnboundedSupply(OUTPUT);
            job.initialItemsRemaining.insertExact(OUTPUT, amount, Actionable.MODULATE);
            ((WideCraftingInventory) job.waitingFor).insertExact(OUTPUT, amount, Actionable.MODULATE);
            var finite = new WideNetworkStorage(OUTPUT, amount);
            class ExactFiniteView implements appeng.api.storage.MEStorage,
                    git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply {
                @Override public Snapshot readExactSupply() {
                    return new Snapshot(Map.of(OUTPUT, finite.remainingTotal()), java.util.Set.of());
                }
                @Override public long extract(AEKey key, long count, Actionable mode, IActionSource source) {
                    return finite.extract(key, count, mode, source);
                }
                @Override public Component getDescription() { return Component.literal("有限大数库存"); }
            }
            ParallelCraftingCpuLogic.refreshJobUnboundedSupply(job, new ExactFiniteView(), IActionSource.empty());
            assertFalse(job.isUnboundedSupply(OUTPUT));
            assertEquals(amount, job.initialItemsRemaining.amount(OUTPUT));
            assertEquals(amount, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
            assertEquals(amount, finite.remainingTotal());
        }

        void undeclaredSimulationSuccessAndRealRefusalIsMissingMaterial() {
            var plan = planWithWideUsed(OUTPUT, BigInteger.TEN);
            var keys = new java.util.HashSet<AEKey>();
            var pool = new WideCraftingInventory(ignored -> {});
            var storage = new appeng.api.storage.MEStorage() {
                @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                    return mode == Actionable.SIMULATE ? amount : 0;
                }
                @Override public Component getDescription() { return Component.literal("拒收的普通盘"); }
            };
            assertNotNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, storage, pool, IActionSource.empty(), keys));
            assertTrue(keys.isEmpty());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            var missing = new KeyCounter(); missing.add(OUTPUT, 10);
            ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null, storage, IActionSource.empty(), pool);
            assertFalse(job.isUnboundedSupply(OUTPUT));
            assertEquals(BigInteger.TEN, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
        }


        void plannedInitialExtractionNeverExceedsWideUsedEvenAfterPoolIsConsumed() {
            BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
            BigInteger needed = max.multiply(BigInteger.TWO).add(BigInteger.valueOf(17));
            var plan = planWithWideUsed(OUTPUT, needed);
            var storage = new WideNetworkStorage(OUTPUT, needed.multiply(BigInteger.TEN));
            var pool = new WideCraftingInventory(ignored -> {});
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, storage, pool, IActionSource.empty(), new java.util.HashSet<>()));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            assertEquals(max.add(BigInteger.valueOf(17)), job.initialItemsRemaining.amount(OUTPUT));
            var asks = new java.util.concurrent.atomic.AtomicInteger();
            assertEquals(max, ParallelCraftingCpuLogic.extractPlannedInitialItems(job, pool, storage,
                    IActionSource.empty(), () -> asks.getAndIncrement() < 1));
            assertEquals(BigInteger.valueOf(17), job.initialItemsRemaining.amount(OUTPUT));
            assertEquals(BigInteger.valueOf(17), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, storage, IActionSource.empty(), () -> true));
            assertEquals(needed, pool.amount(OUTPUT));
            pool.extractExact(OUTPUT, needed, Actionable.MODULATE);
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, storage, IActionSource.empty(), () -> true));
            assertEquals(needed.multiply(BigInteger.valueOf(9)), storage.remainingTotal());
            assertEquals(3, storage.extractAsks, "初抽和两次续取后封账，空池也不重新向网络要料");
        }

        void plannedInitialExtractionDoesNotInventMaterialDemandFromTasks() {
            var pattern = new TestPattern(OUTPUT, new IPatternDetails.IInput[0]);
            var plan = new CraftingPlan(new GenericStack(OUTPUT, 1), 0, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, Long.MAX_VALUE));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            var pool = new WideCraftingInventory(ignored -> {});
            var storage = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(Long.MAX_VALUE).pow(2));
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, storage, IActionSource.empty(), () -> true));
            assertEquals(0, storage.extractAsks);
            assertEquals(BigInteger.ZERO, pool.amount(OUTPUT));
        }

        void shortPlannedExtractionKeepsOnlyUndeliveredAmountForNextTick() {
            var plan = planWithWideUsed(OUTPUT, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insert(OUTPUT, Long.MAX_VALUE, Actionable.MODULATE);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            var first = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(3));
            assertEquals(BigInteger.valueOf(3), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, first, IActionSource.empty(), () -> true));
            assertEquals(BigInteger.valueOf(7), job.initialItemsRemaining.amount(OUTPUT));
            var second = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(50));
            assertEquals(BigInteger.valueOf(7), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                    job, pool, second, IActionSource.empty(), () -> true));
            assertEquals(BigInteger.valueOf(43), second.remainingTotal());
            assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
        }

        void plannedInitialRemainderSurvivesJobSaveWithoutResettingTheBudget() {
            BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
            var plan = planWithWideUsed(OUTPUT, max.multiply(BigInteger.valueOf(3)));
            var link = new CraftingLink(appeng.crafting.execution.CraftingCpuHelper.generateLinkData(
                    UUID.randomUUID(), true, false), (appeng.api.networking.crafting.ICraftingCPU) null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insert(OUTPUT, Long.MAX_VALUE, Actionable.MODULATE);
            ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
            var storage = new WideNetworkStorage(OUTPUT, max.multiply(BigInteger.valueOf(10)));
            var asks = new java.util.concurrent.atomic.AtomicInteger();
            ParallelCraftingCpuLogic.extractPlannedInitialItems(job, pool, storage,
                    IActionSource.empty(), () -> asks.getAndIncrement() < 1);
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var restored = new ParallelExecutingCraftingJob(job.writeToNBT(registries), registries,
                    ignored -> {}, new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L));
            assertEquals(max, restored.initialItemsRemaining.amount(OUTPUT));
            assertEquals(max, ParallelCraftingCpuLogic.extractPlannedInitialItems(restored, pool,
                    storage, IActionSource.empty(), () -> true));
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(restored,
                    pool, storage, IActionSource.empty(), () -> true));
            assertEquals(max.multiply(BigInteger.valueOf(8)), storage.remainingTotal());
        }

        void wallClockBudgetRemainsTheOnlyExhaustionGuard() {
            var ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(1L));
            ledger.resetForTick(1L, 1L);

            assertFalse(ledger.hasTimeBudget(2L));
            assertTrue(ledger.isExhausted());
            assertTrue(ledger.exhaustedTypes().contains(ParallelCpuGridBudgetLedger.BudgetType.TIME));
        }

        void unavailableBatchFallsBackWithoutBeingClassifiedAsPushRejection() {
            assertEquals(
                    ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                    ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                            null, new ListCraftingInventory(ignored -> {
                            })));
            assertEquals(
                    ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                    ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                            preparedBatch(1L, List.of(), List.of(), 0.0D),
                            new ListCraftingInventory(ignored -> {
                            })));
        }

        void rejectedPreparedBatchIsClassifiedAsPushRejection() {
            PreparedBatch rejected = new PreparedBatch() {
                @Override
                public boolean push(ListCraftingInventory inventory) {
                    return false;
                }

                @Override
                public long craftCount() {
                    return 2L;
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

            assertEquals(
                    ParallelCraftingCpuLogic.PreparedBatchPushResult.REJECTED,
                    ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                            rejected, new ListCraftingInventory(ignored -> {
                            })));
        }

        void officialBatchSuccessAccountsCompleteCraftCountInOfficialOrder() {
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 17L;
            var energy = new RecordingEnergyService();
            PreparedBatch prepared = preparedBatch(
                    12L,
                    List.of(new GenericStack(OUTPUT, 36L)),
                    List.of(new GenericStack(REMAINDER, 12L)),
                    48.0D);

            ParallelCraftingCpuLogic.applyPreparedBatchSuccess(job, task, prepared, energy);

            assertEquals(5L, task.value);
            assertEquals(36L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(12L, job.waitingFor.extract(REMAINDER, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(12.0D, job.timeTracker.getStartedUnits());
            assertEquals(48.0D, energy.modulatedPower);
            assertEquals(1, energy.modulatedCalls);
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

        private static PreparedBatch preparedBatch(
                long count,
                List<GenericStack> outputs,
                List<GenericStack> remainders,
                double power
        ) {
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
                    return outputs;
                }

                @Override
                public List<GenericStack> remainders() {
                    return remainders;
                }

                @Override
                public double power() {
                    return power;
                }
            };
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

        /**
         * 销账的豁免必须与封顶同一条判据：<b>只看这一格</b>是不是把该件原样退回。
         *
         * <p>原先按样板级判（该样板任一格自偿还就整笔不销账），于是同一张样板里
         * 模具槽吃过的键，普通消耗格的提走量也不进配额账 ⇒ 份额永远扣不完 ⇒
         * 实机 {@code site=batch minecraft:glass ate=11992458237 share=11992458236}。
         */
        void billingExemptionIsPerSlotNotPerPattern() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = new TestInput(new GenericStack(glass, 1L), glass);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 6L));

            ParallelCraftingCpuLogic.recordTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 4L), holds(glass, 3L)});

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(3L, eaten == null ? 0L : eaten.getLong(glass),
                    "只有模具槽那一格豁免，普通格的 3 枚必须销到配额上");
            assertEquals(7L, job.accountTaken.getLong(glass), "流水账两格都得记");
        }

        /**
         * NeoEco 批量快路径的销账<b>并回同一个出口</b>：豁免只剩槽级那一条判据，不再有第二本账。
         *
         * <p>改前 {@code recordNeoEcoTakenInputs} 自己写两本账、并且用<b>样板级</b> {@code isRepaidKey}
         * 判豁免：这张样板里有一格把玻璃原样退回，于是快路径提走的 16 枚<b>整笔</b>不进配额账
         * ⇒ 份额永远扣不完 ⇒ 下一次还能按满额取。现在它只把按键汇总的明细按一发样本摊回槽号
         * （每格 1 枚 × 8 发），再交回唯一出口 {@code recordTakenInputs}。
         */
        void neoEcoFastPathWriteoffRidesTheSingleSlotLevelExit() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = new TestInput(new GenericStack(glass, 1L), glass);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 1L), holds(glass, 1L)}, 8L,
                    List.of(new GenericStack(glass, 16L)), outflow(glass, 16L), null);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(8L, eaten == null ? 0L : eaten.getLong(glass),
                    "摊回槽号后仍按槽级判据销账：只有模具槽那一格豁免，普通格的 8 枚必须进配额账");
            assertEquals(16L, job.accountTaken.getLong(glass),
                    "流水账两格都得记：与常规推送同一个出口、同一口径");

            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(92L, reservation.remainingQuota(pattern, glass),
                    "吃掉 8 枚份额后还剩 92 枚读得回来：整笔豁免就是份额永远扣不完");
        }

        /**
         * 同一个出口的<b>摊不平</b>形状（独立复核定性的零用例分支）：快路径给的汇总比
         * {@code 样本 × 份数} 多出 4 枚（工具耐久、组件变化这类对不上）。
         *
         * <p>模具槽那 8 枚只进流水账、不进配额账。改前是把整笔 20 枚都搬到普通格上 ⇒ 配额被多销 8 枚
         * ⇒ 份额提前归零 ⇒ 尾段那一格永远开不了工，正是实机 {@code over=[ate=N share=N−1]} 那一型。
         */
        void unbalancedFastPathTotalNeverBillsTheMouldShareOnTheConsumingSlot() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = new TestInput(new GenericStack(glass, 1L), glass);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 1L), holds(glass, 1L)}, 8L,
                    List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), null);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(12L, eaten == null ? 0L : eaten.getLong(glass),
                    "算得清的模具槽 8 枚不许搬到普通格：配额只销 20−8");
            assertEquals(20L, job.accountTaken.getLong(glass), "流水账仍要记满：提走了就是提走了");

            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(88L, reservation.remainingQuota(pattern, glass),
                    "配额读回 88：多销那 8 枚就是把尾段那一格夹死");
        }

        /**
         * 出账量<b>由池子的宽域现量差派生</b>（F1 的派生侧）：一发真需越出 {@code long} 时，
         * 现量差照样是精确值——而对方那份按键汇总的 {@code long} 读数在这组数上早已钉在天花板。
         *
         * <p>量级照实机 2026-09-26 那一跑：池里 {@code 1.82e19} 枚（{@code >long}），这一批真出账
         * {@code 9e18} 枚，对方 {@code KeyCounter} 交给我们的 {@code inputTotal()} 只能是
         * {@link Long#MAX_VALUE} 那个哨兵。
         */
        void neoEcoOutflowIsMeasuredFromThePoolWideReadingNotFromTheReportedTotal() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var pool = new WideCraftingInventory(ignored -> {
            });
            var inPool = BigInteger.valueOf(Long.MAX_VALUE)
                    .add(BigInteger.valueOf(9_000_000_000_000_000_000L));
            pool.insertExact(glass, inPool, Actionable.MODULATE);
            var reported = List.of(new GenericStack(glass, Long.MAX_VALUE));
            var prototype = new KeyCounter[]{holds(glass, 1L)};

            var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(pattern, prototype, reported);
            var before = ParallelCraftingCpuLogic.readPoolWide(pool, keys);
            pool.extractExact(glass, BigInteger.valueOf(9_000_000_000_000_000_000L), Actionable.MODULATE);
            var outflow = ParallelCraftingCpuLogic.measurePoolOutflow(pool, keys, before);

            assertEquals(BigInteger.valueOf(9_000_000_000_000_000_000L), outflow.getExact(glass),
                    "出账＝前后两次宽域现量之差：越界那一截是精确值，不钉天花板");
            assertEquals(inPool.subtract(BigInteger.valueOf(9_000_000_000_000_000_000L)),
                    WideCraftingInventory.amount(pool, glass),
                    "池子剩下的也是宽域真值：派生不靠任何一份饱和读数");
        }

        /**
         * 销账量<b>不许从对方报的饱和读数里取</b>（F1 的后果侧，实机 {@code ate=9223372036854775807
         * share=1056640106232500} 那一型），而且这条结论<b>必须由生产本体给出</b>：驱动
         * {@code ParallelCraftingCpuLogic#pushPreparedBatch} 本体，宿主 {@code push} 真从我们这块池子里
         * 扣料，{@code inputTotal()} 交回 {@link Long#MAX_VALUE} 那份哨兵读数。
         *
         * <p>形状：一发样本这一格记 500 枚，宿主按它自己那份配方每发真扣 2300 枚 ⇒ 池子净差值大于
         * {@code 样本 × 份数} ⇒ 走"摊不平"分岔 ⇒ 残量被 {@code takingSlot} 归到<b>有槽</b>那一号。
         * 上一刀把销账量换成池子差值对本症状是<b>数值空操作</b>（{@code extractExact} 全有全无 ⇒
         * 两个读数同值），真正的洞是那一号压根没吃过 {@code capByRemainingShare} ⇒ 配额账顶到天花板
         * ⇒ {@code quotaExact} 恒 0 ⇒ {@code usableFor=0} ⇒ {@code locked502}。
         *
         * <p>验红：把 {@code #attach} 里的封顶摘回"只夹 {@code target == shape} 那一支"，这条红在
         * "只许销 1000 枚"那一句；把本体的 {@code planReservation} 漏接成 {@code null} 同样红。
         */
        void neoEcoWriteoffNeverBillsTheSaturatedReadingTheOtherSideReports() {
            var water = AEItemKey.of(Items.WATER_BUCKET);
            var consumed = new TestInput(new GenericStack(water, 500L), null);
            var pattern = new TestPattern(water, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            // 实机形状：这一族只分到 1000 枚，池子里却躺着 9.22e18 枚
            MaterialAllocationFixtures.assign(job, pattern, share(water, 1_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insertExact(water, BigInteger.valueOf(Long.MAX_VALUE), Actionable.MODULATE);
            // 宿主每发真扣 2300 枚（样本只记 500），交回的按键读数钉在天花板上
            var host = new FakeNeoEcoHost(pattern, water, 2_300L, -1L, Long.MAX_VALUE);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(2L, pushed, "发数仍按样本那一维夹：1000/500 = 2 发");
            // F5：这里原有 `assertEquals(1, host.prepares, ...)` 已恒真——本 fixture 的 count 恒等于
            // line913 夹过的 requested，affordable 与 craftCount 出自同一算式同一份份额 ⇒ 两次读数逐字相等，
            // "第二次申报没有发生"在这个形状上没有观察点。折回那一支真正的牙在
            // #reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped（贪心宿主 prepares=2）与
            // #requestSideFoldIsObservableAsEcoFoldTimesAndFirings（ecoFold=1/4000）上，这里不再钉恒等数字。
            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten, "普通消耗格那一截必须进配额账");
            assertEquals(1_000L, eaten.getLong(water),
                    "物理被扣 4600 枚也只许按份额销 1000 枚：有槽那一号不再是绕过封顶的漏口");
            assertTrue(eaten.getExact(water).compareTo(BigInteger.valueOf(1_000L)) <= 0L,
                    "已吃 ≤ 份额：拿饱和读数入账就是 ate=Long.MAX 而 share=1000");
            assertEquals(4_600L, job.accountTaken.getLong(water),
                    "流水账收未夹原值：超销那一截必须在账上看得见，否则下一跑对这一型又是盲的");
            assertEquals(0L, reservation.remainingQuota(pattern, water),
                    "份额照实销尽：夹住不等于把这一族的口粮抹到别人头上");
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(4_600L)),
                    WideCraftingInventory.amount(pool, water),
                    "池子真扣 4600 枚：封顶只夹账，不动物理交割");
        }

        /**
         * 同一个 {@code (槽号,键)} 被 {@code attach} 两次时必须<b>累加</b>，不许第二次抹掉第一次（F2）。
         *
         * <p>形状取自"摊不平"这一支：样本 1 枚/发 × 8 发＝8 枚先归回<b>自偿还格</b>，残量 12 枚在
         * "没有非自偿还格可退"时被 {@code takingSlot} 退到<b>同一格</b>。老写法是
         * {@code WideKeyCounter#setExact}（覆盖）：{@code holder} 那本 {@code add} 了两笔（20），
         * 宽域那本却只剩第二笔（12）⇒ 账比物理出账少一截。
         */
        void repeatedAttachOnTheSameSlotAndKeyAccumulatesInsteadOfOverwriting() {
            var glass = AEItemKey.of(Items.GLASS);
            // 唯一一格就是自偿还格 ⇒ 残量只能退到它自己：两次 attach 落在同一个 (index,key)
            var mold = new TestInput(new GenericStack(glass, 1L), glass);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 1L)}, 8L,
                    List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), null);

            assertEquals(20L, job.accountTaken.getLong(glass),
                    "同一 (槽号,键) 两次 attach 必须累加：覆盖就抹掉了第一次那 8 枚");
            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(0L, eaten == null ? 0L : eaten.getLong(glass),
                    "自偿还格整格豁免：累加不改豁免判据，配额账仍一枚不进");
        }

        /**
         * "归不到任何一格"那一号（{@code slot == null}）不再是绕过封顶的销账口（F3）：它吃的是
         * 正常格<b>同一份</b> {@code quotaExact} 算式，残量超份额就<b>照实少销</b>到份额上限——
         * 既不是原样多销，也不是静默归零。
         *
         * <p>取料侧 {@code clampByRemainingQuota} 只夹样本槽看得见的键，这一截在它视野之外：
         * 不补这一夹，归不到格的残量就能把整族份额一口吃空。还原那一行（去掉封顶）时这条用例
         * 红在"销账被夹到 30"与"份额读回 0"上。
         */
        void unattributableResidualIsCappedByTheSameQuotaFormula() {
            var glass = AEItemKey.of(Items.GLASS);
            var bucket = AEItemKey.of(Items.BUCKET);
            // 样本槽里只有 bucket ⇒ glass 这一截归不到任何一格，只能挂最后一号（slot==null）
            var consumed = new TestInput(new GenericStack(bucket, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 30L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(bucket, 1L)}, 1L,
                    List.of(new GenericStack(glass, 100L)), outflow(glass, 100L), reservation);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten, "夹住不等于不销账：这一截照样要走配额账，只是不许超份额");
            assertEquals(30L, eaten.getLong(glass),
                    "残量 100 超份额 30：销账被同一份 quotaExact 夹到 30（照实少销，不是归零）");
            assertEquals(100L, job.accountTaken.getLong(glass),
                    "流水账收未夹原值：只有配额账吃夹后的数，超销那一截必须在诊断账上留着");
            assertEquals(0L, reservation.remainingQuota(pattern, glass),
                    "夹完就是份额用尽——多销那 70 枚从来不是这张样板的口粮");
        }

        /**
         * 请求侧那一刀（本刀的正题）：<b>让"物理被扣的量"结构性不可能越份额</b>。
         *
         * <p>宿主按机器容量自己定发数，第一次申报就是请求那一维的数倍（实机 {@code units=500} 对
         * {@code 每发=1000} 那一型：它每发真扣 2 枚，一发样本只记 1 枚）。越界就<b>少请求</b>——
         * 照夹后的发数向同一位宿主另要一批，既不夹销账、不归零，更不是在这里拒收。
         *
         * <p>摘掉 {@code #pushPreparedBatch} 里那道 push 前复核（{@code dispatchFiringsInsideQuota}）：
         * 那一批 {@code craftCount=4000} 大于请求的 1200 发，会被现成的 dispatch 上限整批判成拒收
         * （{@code pushed=0}），这条红在"实收 600 发"那一句——正是用户红线点名的"把拒规划当修复"。
         */
        void neoEcoFastPathReasksFewerFiringsInsteadOfBillingOverShare() {
            var water = AEItemKey.of(Items.WATER_BUCKET);
            var consumed = new TestInput(new GenericStack(water, 1L), null);
            var pattern = new TestPattern(water, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(water, 1_200L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(water, 20_000L, Actionable.MODULATE);
            // 老实交总量的宿主：每发 2 枚，第一次按机器容量申报 4000 发（远超份额折得出的发数）
            var host = new FakeNeoEcoHost(pattern, water, 2L, 4_000L, 0L);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(600L, pushed,
                    "1200 枚份额 ÷ 每发 2 枚 = 600 发：越界就少请求，照实夹到份额另要一批");
            assertEquals(2, host.prepares, "同一位宿主被另要了一批更小的：少请求，不是拒收");
            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten);
            assertEquals(1_200L, eaten.getLong(water), "配额账正好销满 1200 枚：一枚都不许多");
            assertEquals(1_200L, job.accountTaken.getLong(water),
                    "物理出账 == 请求量：这一型不必靠销账侧兜底，流水账也不虚增");
            assertEquals(BigInteger.valueOf(18_800L), WideCraftingInventory.amount(pool, water),
                    "池子只少了这一批真交出去的那 1200 枚");
        }

        /**
         * 回归对照（R1(b)）：份额充足 ⇒ 请求量与 {@code 69872f5} 之前逐字相同（防封顶做成普遍缩批）。
         *
         * <p>样本 1 枚/发、宿主每发也扣 1 枚、份额 5000 枚 → 请求 100 发 → 应实收 100 发、销账 100 枚。
         * 把 {@code dispatchFiringsInsideQuota} 做成无条件缩批就会把这一条红在 pushed=100 上。
         */
        void quotaCapLeavesSufficientShareUntouched() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 500L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 5_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 10_000L, Actionable.MODULATE);
            // 每发 1 枚、无 greedy 覆写、无读数覆写 → 与样本逐字同向
            var host = new FakeNeoEcoHost(pattern, glass, 1L, 0L, 0L);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(500L, pushed, "500 发任务、份额 5000/1=5000 不夹——与 69872f5 前逐字相同");
            assertEquals(500L, job.accountTaken.getLong(glass), "流水账 == 物理出账 == 请求量");
            assertEquals(500L, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getLong(glass),
                    "配额账 == 流水账：份额充足时封顶不缩批");
        }

        /**
         * N1（P1，崩 lane）：宿主 {@code prepare} 交回 null（mixin {@code eco$prepareFastPath} 在
         * 机器 capacity≤0 时明写返回 null）时，{@code #pushPreparedBatch} 必须走"这一批不适用"的出口，
         * 安静地把这一条样板留在快路径之外——不抛异常、不崩 tick。
         *
         * <p>旧实现在拿到 {@code prepared} 之后直接 {@code prepared.craftCount()} /
         * {@code prepared.inputTotal()} ⇒ CPU tick 内 NPE，整条 lane 崩（本条红在异常上）。
         */
        void nullPrepareFromHostQuietsTheSampleInsteadOfCrashingTheLane() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 20_000L, Actionable.MODULATE);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(new NullPrepareHost(pattern)),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(0L, pushed, "宿主交回 null ⇒ 这一条样板安静地不走快路径，返回 0 而非崩 tick");
            assertEquals(BigInteger.valueOf(20_000L), WideCraftingInventory.amount(pool, glass),
                    "没有 prepare 成功就没有物理出账：池子一枚不少");
        }

        /**
         * N2（P1，静默永久关掉快路径）①：申报里含一个"配给表无此族"的键 ⇒ 该键没有配额口径，
         * 不参与折发数 ⇒ 快路径照走（旧实现把它当"用完"夹成 0 ⇒ {@code return 0} ⇒ 红）。
         */
        void reportedKeyWithoutAnyQuotaBasisDoesNotFoldTheFastPathAway() {
            var glass = AEItemKey.of(Items.GLASS);
            var snow = AEItemKey.of(Items.SNOW_BLOCK);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 100L;
            job.tasks.put(pattern, task);
            // 配给表里有 glass，但没有 snow 这一族。
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertFalse(reservation.hasQuotaBasis(pattern, snow),
                    "配给表里没有这一族 ⇒ 没有配额口径");

            long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                    reservation, pattern, new KeyCounter[]{holds(glass, 1L)},
                    List.of(new GenericStack(snow, 10L)), 10L);

            assertEquals(10L, affordable,
                    "样本没看见、配给表也没这一族的键 ⇒ 不折发数（旧实现 return 0 就是关掉快路径）");
        }

        /**
         * N2（P1）②（控制臂，防把两型一起放开）：申报里的键确有份额且已用完 ⇒ 仍照实 {@code return 0}。
         */
        void reportedKeyWithSpentQuotaStillFoldsTheFastPathToZero() {
            var glass = AEItemKey.of(Items.GLASS);
            var bucket = AEItemKey.of(Items.BUCKET);
            var consumed = new TestInput(new GenericStack(bucket, 1L), null);
            var pattern = new TestPattern(bucket, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 100L;
            job.tasks.put(pattern, task);
            var planned = new WideKeyCounter();
            planned.set(bucket, 5_000L);
            planned.set(glass, 100L);
            MaterialAllocationFixtures.assign(job, pattern, planned);
            job.addPlanMaterialTaken(pattern, glass, 100L);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertTrue(reservation.hasQuotaBasis(pattern, glass),
                    "这一族确有配给（原配 100 枚）⇒ 有配额口径");
            assertEquals(0L, reservation.remainingQuota(pattern, glass), "但份额已用完 ⇒ 读出 0");

            long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                    reservation, pattern, new KeyCounter[]{holds(bucket, 1L)},
                    List.of(new GenericStack(glass, 10L)), 10L);

            assertEquals(0L, affordable, "确有份额且已用完 ⇒ 仍照实 return 0，不与'没有口径'一并放开");
        }

        /**
         * F1（P1）①：配给表里<b>确有这一族、但条目是 0 值</b>（{@code #mergePlannedShare} 保住"0 值条目
         * 也在册"的形状）⇒ 这一族<b>有配额口径</b> ⇒ 请求侧照旧折（不许放过），份额仍只由
         * {@code ParallelPlanReservation#quotaExact} 交（这里交 0）。
         *
         * <p>摘掉那一号判据（回到 {@code shareOf(...).signum() > 0}）时这条红两处：
         * {@code hasQuotaBasis} 读出 {@code false}、{@code affordable} 读出 {@code 10}。
         */
        void reportedKeyWithZeroValuedPlanEntryStillFoldsTheRequestSide() {
            var glass = AEItemKey.of(Items.GLASS);
            var bucket = AEItemKey.of(Items.BUCKET);
            var consumed = new TestInput(new GenericStack(bucket, 1L), null);
            var pattern = new TestPattern(bucket, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 100L;
            job.tasks.put(pattern, task);
            var planned = new WideKeyCounter();
            planned.set(bucket, 5_000L);
            planned.set(glass, 0L);
            MaterialAllocationFixtures.assign(job, pattern, planned);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            assertTrue(reservation.hasQuotaBasis(pattern, glass),
                    "0 值条目也在册 ⇒ 这一族确有配额口径（份额 0 不是没口径）");
            assertEquals(0L, reservation.remainingQuota(pattern, glass), "份额真值仍只由 quotaExact 交：这里是 0");

            long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                    reservation, pattern, new KeyCounter[]{holds(bucket, 1L)},
                    List.of(new GenericStack(glass, 10L)), 10L);

            assertEquals(0L, affordable,
                    "份额 0 的族照样折请求量：放过它就是让物理多拿的那一截无主（over= 也不亮）");
        }

        /**
         * F1（P1）②（控制臂，防把"没口径"那一型顺手放开成"有口径"）：表里<b>压根没有这一族</b>，
         * 以及<b>这张样板压根不在表里</b> ⇒ 请求侧不折（保住 0492755 的"没口径不静默关路"），
         * 样板不在表时销账侧也不夹（{@code quotaExact} 交 {@code null} = 没有口径，不是 0）。
         *
         * <p>把新判据写成"表里有这张样板就算有口径"（丢掉族级检查）时，臂 A 红在
         * {@code hasQuotaBasis=false} 与 {@code affordable=10}；写成"assigned 非 null 就算口径"
         * （丢掉 {@code isEmpty()} 那一条）时，臂 B 红在 {@code capByRemainingShare} 交出 0。
         */
        void familyAbsentFromPlanTableNeitherFoldsTheRequestNorClosesTheFastPath() {
            var glass = AEItemKey.of(Items.GLASS);
            var snow = AEItemKey.of(Items.SNOW_BLOCK);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 100L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 5_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            // 臂 A：样板在表里、这一族不在 ⇒ 没口径 ⇒ 不折
            assertFalse(reservation.hasQuotaBasis(pattern, snow), "表里没有这一族 ⇒ 没有配额口径");
            assertEquals(10L, ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                            reservation, pattern, new KeyCounter[]{holds(glass, 1L)},
                            List.of(new GenericStack(snow, 10L)), 10L),
                    "没口径的申报键不许把发数折成 0：那是把快路径静默永久关掉");

            // 臂 B：这张样板不在表里 ⇒ 没口径 ⇒ 销账侧也不夹（null 不是 0）
            var unplanned = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            assertFalse(reservation.hasQuotaBasis(unplanned, snow), "样板不在表里 ⇒ 没有配额口径");
            assertEquals(BigInteger.TEN, ParallelCraftingCpuLogic.capByRemainingShare(
                            BigInteger.TEN, reservation, unplanned, snow, null, new java.util.HashMap<>()),
                    "没有配额口径就不夹：把 null 读成 0 就是永久关掉这一张样板");
        }

        /**
         * F2（P1）：一批发端跨<b>同族两枚不同精确键</b>（变体）——{@code #attach} 每一号都拿同一份
         * <b>批前</b> {@code quotaExact} 读数来夹（配额账要到批末 {@code #recordTakenInputs} 才前进），
         * 于是两枚变体各吃整份份额（最多 {@code 2 × share}）。这里用批内族级余量钉成"只许花一遍"。
         *
         * <p>摘掉批内余量（把那张局部表交回 {@code null}）时红在"第二枚变体键不许再吃整份"
         * 与"两笔合计 ≤ 份额"上：旧实现读出 {@code 20 + 20 = 40}。
         */
        void twoVariantKeysOfOneFamilySpendTheFamilyShareOnlyOncePerBatch() {
            var variantA = wornKey(50);
            var variantB = wornKey(0);
            var slotA = new TestInput(new GenericStack(variantA, 20L), null);
            var slotB = new TestInput(new GenericStack(variantB, 20L), null);
            var pattern = new TestPattern(variantA, new IPatternDetails.IInput[]{slotA, slotB});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 20L;
            job.tasks.put(pattern, task);
            // 这一族总共只配给 20 枚（登记在 variantA 的精确键上，判据按族合计读）
            MaterialAllocationFixtures.assign(job, pattern, share(variantA, 20L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var poolOutflow = new WideKeyCounter();
            poolOutflow.add(variantA, BigInteger.valueOf(20L));
            poolOutflow.add(variantB, BigInteger.valueOf(20L));

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(variantA, 20L), holds(variantB, 20L)}, 1L,
                    List.of(new GenericStack(variantA, 20L), new GenericStack(variantB, 20L)),
                    poolOutflow, reservation);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten, "这一批确实花了族份额");
            assertEquals(20L, eaten.getLong(variantA), "第一枚变体键销满整份份额");
            assertEquals(0L, eaten.getLong(variantB),
                    "同族第二枚变体键不许再吃整份：批内族级余量已扣完（旧实现这里销 20 ⇒ 合计 2×share）");
            assertEquals(40L, job.accountTaken.getLong(variantA) + job.accountTaken.getLong(variantB),
                    "流水账仍收未夹原值：封顶不许把超销那一截从诊断账上一起抹掉");
        }

        /**
         * F4（P2）：{@code #capByRemainingShare} 不许恒交 {@code slot=null}——那一道槽级
         * {@code SlotAccount#selfRepaying} 豁免一丢，模具槽那一号就<b>替</b>普通消耗格把批内族余量扣了，
         * 而它自己那一号在 {@code #recordTakenInputs} 又整格豁免 ⇒ 发作方向是<b>少销</b>。
         *
         * <p>摘掉 slot 透传（交回 {@code null}）时红在"族份额留给真消耗它的那一格"：份额 10 枚
         * 被模具槽那 10 枚先扣光 ⇒ 配额账一枚不进。
         */
        void selfRepayingMoldSlotKeepsTheFamilyShareForTheConsumingSlot() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = new TestInput(new GenericStack(glass, 10L), glass);
            var consumed = new TestInput(new GenericStack(glass, 10L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 20L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 10L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 1L,
                    List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), reservation);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten, "普通消耗格那一截必须进配额账");
            assertEquals(10L, eaten.getLong(glass),
                    "模具槽整格豁免 ⇒ 它也不许占批内族余量：份额 10 枚仍归真消耗它的那一格");
            assertEquals(20L, job.accountTaken.getLong(glass),
                    "流水账照收未夹原值：两格物理都动了料");
            assertEquals(0L, reservation.remainingQuota(pattern, glass), "份额照实销尽，不是被豁免那一格吃光的");
        }

        /**
         * F3（P2）：第二处 {@code prepareBatch} 的结果同样可能是 {@code null}
         * （宿主 capacity 在两次申报之间掉到 0）。{@code #pushPreparedBatchCandidate} 自己就护 null ⇒
         * 这里复用同一个"这一批不适用"的 {@code continue} 出口，不新增日志、不新增拒绝原因。
         *
         * <p>摘掉那道护栏时这条红在"返回 0 而非崩 tick"上：旧写法直接 {@code prepared.inputTotal()} 抛 NPE。
         */
        void secondPrepareReturningNullTakesTheSameNotApplicableExit() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 20_000L, Actionable.MODULATE);
            var host = new FoldThenNullHost(pattern, glass);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(0L, pushed, "第二次 prepare 交回 null ⇒ 走既有出口：返回 0，不崩 tick");
            assertEquals(2, host.prepares, "第一处成功、请求侧折过之后第二处才交回 null");
            assertEquals(BigInteger.valueOf(20_000L), WideCraftingInventory.amount(pool, glass),
                    "没有 push 就没有物理出账：池子一枚不少");
        }

        /**
         * F6（P2）：请求侧折回是<b>可分型</b>的读数——本 tick 折了几次、折掉几发（{@code ecoFold=次数/发数}）。
         * 它<b>不参与任何判据</b>：同一跑里 {@code pushed} 仍由份额折出 1000 发，摘掉读数那条腿也只掉读数。
         *
         * <p>摘掉 {@code #noteEcoFold} 那一号调用时红在 {@code ecoFold} 仍是空串上。
         */
        void requestSideFoldIsObservableAsEcoFoldTimesAndFirings() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 20_000L, Actionable.MODULATE);
            var ledger = new ParallelPoolLedger();
            // 贪心宿主第一次申报就顶到 5000 发（份额只折得出 1000 发），且继续全有全无地真扣料
            var host = new FakeNeoEcoHost(pattern, glass, 1L, 5_000L, -1L);

            assertEquals("", ledger.ecoFold(), "还没跑过就是空读数（与'折掉 0 发'分得开）");
            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, ledger, null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(1_000L, pushed, "判据不认这个读数：发数仍只由份额折出");
            assertEquals("1/4000", ledger.ecoFold(),
                    "折回 1 次、折掉 4000 发：5000 → 1000 这一步在 ecoOut= 那一串旁边分得出来");
            ledger.reset();
            assertEquals("", ledger.ecoFold(), "随 tick 现算：reset 就清，不跨 tick");
        }

        /** 同一物品本体、不同耐久：AE2 按物品本体归为同一模糊族，精确键却各是一枚。 */
        private static AEItemKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        /**
         * F3 用：第一次 {@code prepare} 交回一批贪心大 batch（逼出请求侧折回），
         * 第二次起交回 {@code null}（对应宿主 capacity 掉到 0 那一档）。
         */
        private static final class FoldThenNullHost implements ICraftingProvider, PreparedBatchDispatcher {
            private final IPatternDetails pattern;
            private final AEKey take;
            int prepares;

            FoldThenNullHost(IPatternDetails pattern, AEKey take) {
                this.pattern = pattern;
                this.take = take;
            }

            @Override
            public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                    IPatternDetails pattern, KeyCounter[] perCraftInputs, KeyCounter perCraftOutputs,
                    KeyCounter perCraftRemainders, ListCraftingInventory inventory, long requested,
                    IEnergyService energyService, Level level, UUID craftingJobId
            ) {
                prepares++;
                if (prepares > 1) {
                    return null;
                }
                return new PreparedBatch() {
                    @Override
                    public boolean push(ListCraftingInventory craftingInventory) {
                        return false;
                    }

                    @Override
                    public long craftCount() {
                        return 5_000L;
                    }

                    @Override
                    public List<GenericStack> inputTotal() {
                        // 顶到天花板的哨兵读数：那一维不进取请求侧，折回只由样本那一维给出
                        return List.of(new GenericStack(take, Long.MAX_VALUE));
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

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return List.of(pattern);
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return false;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
        }

        /**
         * N3（P2，本刀真正的症状路径）：申报某键顶到 {@code Long.MAX_VALUE}、池子里该键有巨量、
         * 份额只有 N ⇒ push 之前请求量就被样本那一维折到"份额内的那一发数"，池子差值 ≤ N。
         *
         * <p>旧实现整键跳过（{@code total==Long.MAX_VALUE → continue}）且无盲发即 {@code return craftCount}
         * ⇒ 贪心宿主 {@code craftCount=5000} 大于请求的 1000 发被现成 dispatch 上限拒收 ⇒ {@code pushed=0}（红）。
         */
        void reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 5_000L;
            job.tasks.put(pattern, task);
            // 份额只有 1000 枚，池子里躺着巨量，宿主每发真扣 1 枚（与样本同向）却顶到天花板申报。
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 20_000L, Actionable.MODULATE);
            // greedy=5000 让第一次申报就顶到 5000 发；reportedOverride=Long.MAX_VALUE 逼出哨兵读数。
            var host = new FakeNeoEcoHost(pattern, glass, 1L, 5_000L, Long.MAX_VALUE);

            long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                    job, pool, new ParallelPoolLedger(), null, null,
                    pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                    new RecordingEnergyService(), 1L, null, null, reservation);

            assertEquals(2, host.prepares, "顶到天花板的键不再整键跳过 ⇒ 样本那一维折出 1000 发 < craftCount ⇒ 另要一批");
            assertEquals(1_000L, pushed, "请求量折到份额内那一发数：1000 枚 ÷ 每发 1 枚 = 1000 发");
            assertEquals(BigInteger.valueOf(19_000L), WideCraftingInventory.amount(pool, glass),
                    "池子差值 1000 ≤ 份额：物理多拿的那一截不再无主");
            assertEquals(1_000L, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getLong(glass),
                    "销账 == 份额 == 物理出账：这一型不再靠事后 attach 兜底");
        }

        /**
         * N4（P2）：同键两格各按<b>同一份批内 remaining</b> 夹——族合计除法保证"两格合计不超吃"，
         * 且销账侧不把第二格按已花完的 remaining 再夹一次。
         */
        void twoSlotsOfOneFamilyAreCappedByTheFamilyTotalNotEachByTheWholeShare() {
            var glass = AEItemKey.of(Items.GLASS);
            var slotA = new TestInput(new GenericStack(glass, 10L), null);
            var slotB = new TestInput(new GenericStack(glass, 10L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{slotA, slotB});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 20L;
            job.tasks.put(pattern, task);
            // 这一族总共只配给 20 枚（两格每发合计 20 枚 ⇒ 只够 1 发）。
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 20L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            // 请求侧：族合计除法 ⇒ 20 枚份额 ÷ 每发 20 枚 = 1 发；per-slot（各按整份 20/10=2）会放到 2 发超吃。
            assertEquals(1L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    reservation, pattern, new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 20L),
                    "两格合计按族份额折 ⇒ 只够 1 发；per-slot 会各自吃整份而超吃");

            // 销账侧：两格都按同一份批内 remaining(20) 夹，第二格不因第一格先销而读到已花完的份额。
            ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 1L,
                    List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), reservation);
            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertNotNull(eaten, "两格合计销账必须进配额账");
            assertEquals(20L, eaten.getLong(glass), "合计销满份额 20，不被'第二格再夹一次'削成 10");
            assertTrue(eaten.getLong(glass) <= 20L, "两格合计销账不超过份额");
        }

        /**
         * N5（P2）①：{@code describeEcoOut} 渲染的 {@code 键:池子差值/reported=对方读数} 整串，
         * 含"两值不等"这一形状；{@code noteEcoOut} 收下这条读数并原样读出（空读数不覆盖旧值）。
         */
        void ecoOutPairRendersPoolDeltaAgainstReportedReading() {
            var glass = AEItemKey.of(Items.GLASS);
            var snow = AEItemKey.of(Items.SNOW_BLOCK);
            var poolOutflow = new WideKeyCounter();
            poolOutflow.add(glass, BigInteger.valueOf(500L));
            poolOutflow.add(snow, BigInteger.valueOf(3L));
            var reported = List.of(
                    new GenericStack(glass, 900L),   // 两值不等：差值 500 ≠ 对方报 900
                    new GenericStack(snow, 3L));      // 两值相等
            String rendered = ParallelCraftingCpuLogic.describeEcoOut(poolOutflow, reported);
            assertTrue(rendered.contains(glass + ":500/reported=900"),
                    "玻璃那一档必须把差值与对方读数并排点名：" + rendered);
            assertTrue(rendered.contains(snow + ":3/reported=3"),
                    "雪那一档两值相等也照样成对：" + rendered);
            assertEquals("", ParallelCraftingCpuLogic.describeEcoOut(new WideKeyCounter(), reported),
                    "没有出账就是空串");

            var ledger = new ParallelPoolLedger();
            assertEquals("", ledger.ecoOut(), "没推过 NeoEco 批次就是空读数");
            ledger.noteEcoOut(rendered);
            assertEquals(rendered, ledger.ecoOut(), "noteEcoOut 收下整串、原样读出");
            ledger.noteEcoOut("");
            assertEquals(rendered, ledger.ecoOut(), "空读数不覆盖上一条：诊断行不闪回空");
        }

        /**
         * N5（P2）②：{@code locked=[<定义>#slotN,<键>,stored=..,units=..,usable=..]} 渲染后的整串
         * （上一轮只断了字段填充，没断渲染）。
         *
         * <p>子字段边界是 {@code ,}（不带空白）：这一格进 per100r 后按空白切必须恰占一段，而
         * {@code stored=/units=/usable=} 仍寻得到址。
         */
        void lockedReadoutRendersDefinitionSlotAndThreeNumbers() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 10L;
            job.tasks.put(pattern, task);
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 0L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 100L, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();
            probe.noteNoCandidate(3, consumed, pool, null, pattern, reservation);

            String rendered = ParallelCraftingCpuLogic.describeLockedReadout(
                    probe.ncLockedOwner, probe.ncLockedSlot, probe.ncLockedKey,
                    probe.ncLockedStored, probe.ncLockedUnits, probe.ncLockedUsable);
            assertTrue(rendered.contains("#slot3,"), "整串必须带上槽号：" + rendered);
            assertTrue(rendered.contains(",stored="), "整串必须带上现场量：" + rendered);
            assertTrue(rendered.contains(",units=") && rendered.contains(",usable="),
                    "整串必须带上每发件数与账面可用量：" + rendered);
            assertEquals((probe.ncLockedOwner + "#slot3," + probe.ncLockedKey
                            + ",stored=" + probe.ncLockedStored + ",units=" + probe.ncLockedUnits
                            + ",usable=" + probe.ncLockedUsable).replace(' ', '_'),
                    rendered, "渲染口径逐字＝owner#slotN,key,stored,units,usable（第三方串的空白只在出口折）");
            // 一格一 token：整串不许残留任何空白（键带组件时 "(with patches)" 必须已被折掉）
            assertFalse(rendered.chars().anyMatch(Character::isWhitespace),
                    "locked= 那一格不许含任何空白：" + rendered);
        }

        /** N1 用：宿主 {@code prepare} 恒交回 null（对应 mixin 在 capacity≤0 时的出口）。 */
        private static final class NullPrepareHost implements ICraftingProvider, PreparedBatchDispatcher {
            private final IPatternDetails pattern;

            NullPrepareHost(IPatternDetails pattern) {
                this.pattern = pattern;
            }

            @Override
            public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                    IPatternDetails pattern, KeyCounter[] perCraftInputs, KeyCounter perCraftOutputs,
                    KeyCounter perCraftRemainders, ListCraftingInventory inventory, long requested,
                    IEnergyService energyService, Level level, UUID craftingJobId
            ) {
                return null;
            }

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return List.of(pattern);
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return false;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
        }

        /** 池子宽域现量差那本账的测试替身：一笔出账真值（键 → 枚数）。 */
        private static WideKeyCounter outflow(AEKey key, long exact) {
            var counter = new WideKeyCounter();
            counter.add(key, BigInteger.valueOf(exact));
            return counter;
        }

        /**
         * 流水账换成宽域账后，<b>销账判据的行为必须一字不变</b>：豁免仍只看这一格、配额账仍走它自己
         * 既有的饱和合并，越界量级下判据读到的仍是精确值。
         *
         * <p>量级取三批 ×（模具槽 2e18 + 普通槽 2e18）＝提走账合计 1.2e19 枚（越出 {@code long}，
         * 老 {@link KeyCounter} 在这组数上回绕成负），而配额账每键只累计 6e18 枚（仍装得进
         * {@code long}）⇒ 两本账各自独立，账变宽不会把判据拖下水。
         */
        void slotLevelWriteoffCriteriaSurviveWideMagnitudes() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = new TestInput(new GenericStack(glass, 1L), glass);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);

            long perSlot = 2_000_000_000_000_000_000L;
            var legacy = new KeyCounter();
            for (int batch = 1; batch <= 3; batch++) {
                ParallelCraftingCpuLogic.recordTakenInputs(job, pattern,
                        new KeyCounter[]{holds(glass, perSlot), holds(glass, perSlot)});
                legacy.add(glass, perSlot);
                legacy.add(glass, perSlot);
            }

            var exactTaken = java.math.BigInteger.valueOf(perSlot)
                    .multiply(java.math.BigInteger.valueOf(6L));
            assertEquals(exactTaken, job.accountTaken.getExact(glass),
                    "提走流水账跨批复加逐位精确：两格都得记，模具槽也不漏");
            assertTrue(legacy.get(glass) < 0L, "同一组数在老 long 账上回绕成负");
            assertEquals(Long.MAX_VALUE, job.accountTaken.getLong(glass),
                    "对外 long 读数（停滞日志的 taken=）按饱和收口：口径不变");

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(3L * perSlot, eaten == null ? 0L : eaten.getLong(glass),
                    "配额销账仍只记普通格那三笔：模具槽整格豁免，判据与账宽无关");
            assertEquals(java.math.BigInteger.valueOf(3L * perSlot),
                    exactTaken.subtract(java.math.BigInteger.valueOf(eaten.getLong(glass))),
                    "流水账与配额账的差额恒等于模具槽豁免那一截：销账出口仍只有一个");
        }

        /**
         * 因果链决定性用例（第四刀）：<b>同一个键累计越出 {@code long}</b> 之后，剩余份额不能被判成 0。
         *
         * <p>写入口取真路：配给由 {@code describePlanBalance} 的逐槽复加产生（这里两笔 6e18），
         * 已吃由推送成功时的 {@code addPlanMaterialTaken} 复加产生（这里两笔 5e18）。
         * 两本账真值分别是 1.2e19 与 1e19 枚 ⇒ 还剩 2e18 枚配额。
         *
         * <p>老口径两笔合并都停在 {@link Long#MAX_VALUE}（{@code ParallelExecutingCraftingJob}
         * 的 {@code merge} 三元式与 {@code describePlanBalance} 的 {@code saturatedAdd} 各顶一次），
         * 判据拿两个天花板读数相减读出 {@code 0} ⇒ 还能推 2e18 枚的任务被 {@code quotaFor} 夹成
         * 0 发而拒推（尾段 {@code pushed=0} 的来路）。对照段用同一串输入把这条链量出来。
         */
        void cumulativeWriteoffAboveLongKeepsTheRemainingShareReadable() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);

            long mintEach = 6_000_000_000_000_000_000L;
            long takeEach = 5_000_000_000_000_000_000L;
            var planned = new WideKeyCounter();
            planned.add(glass, mintEach);
            planned.add(glass, mintEach);
            MaterialAllocationFixtures.assign(job, pattern, planned);
            job.addPlanMaterialTaken(pattern, glass, takeEach);
            job.addPlanMaterialTaken(pattern, glass, takeEach);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
            assertEquals(new BigInteger("10000000000000000000"), eaten.getExact(glass),
                    "已吃账跨批复加逐位精确，绝不停在 Long.MAX_VALUE");
            assertEquals(new BigInteger("12000000000000000000"), planned.getExact(glass),
                    "配给账逐槽复加同样精确：顶到天花板就是把配到自己的那一截抹掉");

            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(2_000_000_000_000_000_000L, reservation.remainingQuota(pattern, glass),
                    "还剩的 2e18 枚必须读得出来——判据要拿它除以每发用料");

            assertEquals(0L, legacySaturatedQuota(
                            new long[]{mintEach, mintEach}, new long[]{takeEach, takeEach}),
                    "老口径在同一串输入下把剩余份额算成 0：这就是被判配额用尽的那一步");
        }

        /**
         * 换宽域<b>没有</b>放宽销账判据：账仍只按实提量复加，配额之外的份额一律读不出来。
         *
         * <p>已吃恰好等于配给 ⇒ 份额恒为 0；一笔都没吃 ⇒ 份额等于配给的饱和读数。
         */
        void writeoffCriteriaAreUnchangedByTheWideLedgers() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var planned = new WideKeyCounter();
            planned.add(glass, 6_000_000_000_000_000_000L);
            planned.add(glass, 6_000_000_000_000_000_000L);
            MaterialAllocationFixtures.assign(job, pattern, planned);

            var settled = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(Long.MAX_VALUE, settled.remainingQuota(pattern, glass),
                    "一笔没吃：份额即配给 1.2e19 枚，对外出口仍饱和一次 Long.MAX_VALUE");

            for (int batch = 0; batch < 3; batch++) {
                job.addPlanMaterialTaken(pattern, glass, 4_000_000_000_000_000_000L);
            }
            var fullySpent = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(0L, fullySpent.remainingQuota(pattern, glass),
                    "已吃 1.2e19 枚 = 配给：销完就是 0 份额，宽域账不许它多吃一枚");
        }

        /**
         * 因果链决定性用例（第五刀）：<b>规划出口</b>那张配给表本身越出 {@code long} 时，执行侧
         * 照单采用之后剩余份额必须按真值读出来、任务才推得动。
         *
         * <p>写入口取真路：配给由 {@code MaterialClosureGate.buildMaterialPlan} 那趟账定版、随
         * {@link RecursiveCraftingPlan} 出门，再由 {@code adoptPlannedMaterialPlan} 整本搬进作业。
         * 同一键两笔各 9e18 枚 ⇒ 真值 1.8e19 枚；实吃一笔 9e18 枚 ⇒ 还剩 9e18 枚配额。
         *
         * <p>老口径这条链的上游是 {@code Map<AEKey, Long>}：规划侧那一次饱和加把配给写成
         * {@link Long#MAX_VALUE}，再与逐批精确的已吃账（第四刀换的宽域账）做差就只剩 2.2e17 枚
         * ⇒ 还能推 9e18 枚的批次被 {@code quotaFor} 夹成零头。对照段用同一串输入把这条差量出来。
         */
        void plannedTableAboveLongSurvivesAdoptionIntoTheQuotaJudge() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            long slotUnits = 9_000_000_000_000_000_000L;
            var planned = new WideKeyCounter();
            planned.add(glass, slotUnits);
            planned.add(glass, slotUnits);
            var plan = planWithMaterialPlan(pattern, planned);
            var job = new ParallelExecutingCraftingJob(
                    plan, ignored -> {
                    }, (CraftingLink) null, null);

            assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                    "计划已带配给表：本趟排水不该再 mint 第二份归属");
            assertEquals(EXACT_18E18, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(glass),
                    "照单采用不许降级：计划里那 1.8e19 枚必须原样进配给账");

            job.addPlanMaterialTaken(pattern, glass, slotUnits);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(9_000_000_000_000_000_000L, reservation.remainingQuota(pattern, glass),
                    "还剩的 9e18 枚必须读得出来——判据要拿它除以每发用料");
            assertEquals(223372036854775807L, legacySaturatedQuota(
                            new long[]{slotUnits, slotUnits}, new long[]{slotUnits}),
                    "老口径在同一串输入下只剩 2.2e17 枚：这一截就是被上游饱和吃掉的批次");
        }

        /**
         * 换宽域<b>没有</b>放宽这条链上的任何一道出口：照单采用来的超界配额，对外读数照旧饱和
         * {@code Long.MAX_VALUE}；销账判据仍"不超不少"——已吃恰好等于配给就是 0 份额。
         *
         * <p>顺带钉住隔离性：老口径靠 {@code Map.copyOf} 让计划里那张表不可变，账本换型后由
         * {@code copy()} 顶上来——执行侧在自已那本账上复加，绝不许回写计划的配给上界。
         */
        void adoptedWideQuotaKeepsTheSaturatedExitAndTheExactWriteoff() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            long slotUnits = 9_000_000_000_000_000_000L;
            var planned = new WideKeyCounter();
            planned.add(glass, slotUnits);
            planned.add(glass, slotUnits);
            var plan = planWithMaterialPlan(pattern, planned);
            var job = new ParallelExecutingCraftingJob(
                    plan, ignored -> {
                    }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            var untouched = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(Long.MAX_VALUE, untouched.remainingQuota(pattern, glass),
                    "一笔没吃：份额即配给 1.8e19 枚，对外出口仍饱和一次 Long.MAX_VALUE");

            job.addPlanMaterialTaken(pattern, glass, slotUnits);
            job.addPlanMaterialTaken(pattern, glass, slotUnits);
            var fullySpent = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(0L, fullySpent.remainingQuota(pattern, glass),
                    "已吃 1.8e19 枚 = 配给：销完就是 0 份额，宽域表不许它多吃一枚");

            job.materialAllocation.mintShare(pattern, glass, BigInteger.valueOf(5L));
            assertEquals(EXACT_18E18,
                    plan.chexsonsaeutils$planMaterialPlan().get(pattern).getExact(glass),
                    "执行侧在那本账上复加不得回写计划里的配给上界");
        }

        /** 1.8e19 枚：两笔 9e18 复加的精确合计（越出 {@code long}，老载体写不下）。 */
        private static final BigInteger EXACT_18E18 = new BigInteger("18000000000000000000");

        /**
         * 「在册且值为 0」与「表里没这张样板」是两回事，这条判据到这才算有用例钉住。
         *
         * <p>那本账只要非空，这张样板就<b>有配额口径</b>：份额读出 {@code 0} ⇒ 硬封顶夹死
         * （规划期定料的另一半——这一格一枚都不许从别的键付）。反过来，样板整本不在表里
         * 读出 {@code -1} ⇒ 不夹，夹了就是把没有归属的样板整批饿死。
         *
         * <p>0 值条目由 {@code mergePlannedShare} 在非正增量时按 {@code HashMap.put} 语义写下
         * （{@link WideKeyCounter#set} 保住键位），并原样活过 {@code adoptPlannedMaterialPlan}
         * 那一次 {@code copy()}。规划侧的 {@code bill} 对非正数直接 return，所以「只剩 0 标记」
         * 的形状在这条链上的唯一职责就是把 {@code isEmpty()} 顶成真：它一旦被当成空表跳过，
         * 这张样板就掉出配额口径，封顶形同虚设。
         */
        void zeroShareInRegisterKeepsTheClampWhileAbsenceLeavesItOpen() {
            var glass = AEItemKey.of(Items.GLASS);
            var snow = AEItemKey.of(Items.SNOW_BLOCK);
            var bucket = AEItemKey.of(Items.BUCKET);
            var zeroed = new TestPattern(glass,
                    new IPatternDetails.IInput[]{new TestInput(new GenericStack(glass, 1L), null)});
            var unassigned = new TestPattern(snow,
                    new IPatternDetails.IInput[]{new TestInput(new GenericStack(snow, 1L), null)});

            var withZero = new WideKeyCounter();
            withZero.set(glass, 0L);      // 在册、值为 0：规划定了这一格一枚都不从这键付
            withZero.set(bucket, 5L);     // 同一样板的另一笔正常份额，用来排除"整本被跳过"
            var empty = new WideKeyCounter();
            var material = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(zeroed, withZero);
            material.put(unassigned, empty);
            var plan = planWithMaterialPlan(material);
            var job = new ParallelExecutingCraftingJob(
                    plan, ignored -> {
                    }, (CraftingLink) null, null);

            assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                    "表非空：本趟排水不该再 mint 第二份归属");
            var adopted = MaterialAllocationFixtures.assignedSnapshot(job).get(zeroed);
            assertTrue(adopted.containsKey(glass),
                    "0 值条目必须活过 copy()：它撑住的就是 isEmpty() 这一票");
            assertEquals(0L, adopted.getLong(glass), "在册不等于有钱");
            assertFalse(MaterialAllocationFixtures.assignedSnapshot(job).containsKey(unassigned),
                    "空账整本跳过：等价于这张样板没被配给");

            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(0L, reservation.remainingQuota(zeroed, glass),
                    "在册且为 0 ⇒ 夹到 0（份额 0 是规划定下的数，不是'没有口径'）");
            assertEquals(0L, reservation.remainingQuota(zeroed, snow),
                    "同一张样板的键外候选照样夹：定料之后不许退到没配给的键上去");
            assertEquals(5L, reservation.remainingQuota(zeroed, bucket), "正数那一笔照常就是份额");
            assertEquals(-1L, reservation.remainingQuota(unassigned, snow),
                    "不在表里＝没有配额口径 ⇒ 负数不夹");
        }

        /**
         * 规划出口那份只带配给表的递归计划：其余契约字段留空，两个<b>显示旁路</b>账交 {@code null}
         * 由 compact 构造器兜成空快照。{@code patternTimes} 逐张挂一发，只为让作业建出那些任务。
         */
        private static RecursiveCraftingPlan planWithMaterialPlan(
                java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> material) {
            var times = new java.util.LinkedHashMap<IPatternDetails, Long>();
            for (IPatternDetails pattern : material.keySet()) {
                times.put(pattern, 1L);
            }
            var base = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
        }

        /**
         * 规划出口那份只带配给表的递归计划：其余契约字段留空，两个<b>显示旁路</b>账交 {@code null}
         * 由 compact 构造器兜成空快照。{@code patternTimes} 挂一发，只为让作业建出那条任务。
         */
        private static RecursiveCraftingPlan planWithMaterialPlan(
                IPatternDetails pattern, WideKeyCounter share) {
            var material = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(pattern, share);
            return planWithMaterialPlan(material);
        }

        /** 配给/销账账本换宽域账本后的建表helper：数值口径与老 {@code Map.of(key, amount)} 一字不变。 */
        /**
         * R5：F5 那两处新读数（locked 档的样板身份与槽号）要有牙齿——不能停在默认 {@code "?"}/{@code -1}。
         *
         * <p>驱动 {@code Probe#noteNoCandidate} 走到 locked 分支：现场有料、够一发，却被份额夹到 {@code usable<units}。
         * 断 owner 不是 {@code "?"}、slot 不是 {@code -1}，且 -2（没递出候选）与 -1（无配额口径）话术可辨。
         */
        void lockedReadoutNamesThePatternAndSlotAndSeparatesMinusOneFromMinusTwo() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 1L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = 10L;
            job.tasks.put(pattern, task);
            // 份额 0 ⇒ 现场有料却付不出 ⇒ 走 locked 分支
            MaterialAllocationFixtures.assign(job, pattern, share(glass, 0L));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(glass, 100L, Actionable.MODULATE);

            var probe = new ParallelBatchExtraction.Probe();
            probe.noteNoCandidate(3, consumed, pool, null, pattern, reservation);

            assertEquals("3", String.valueOf(probe.ncLockedSlot));
            assertFalse(probe.ncLockedSlot < 0, "locked 那一档必须点到具体槽号，不许留默认 -1");
            assertFalse("?".equals(probe.ncLockedOwner), "locked 那一档必须点名样板身份，不许留默认 ?");
            assertTrue(probe.ncLockedOwner.contains("glass"),
                    "owner 印出样板定义身份：" + probe.ncLockedOwner);
            // -2 与 -1 话术可辨
            assertFalse(ParallelCraftingCpuLogic.describeNoCandQuota(-1L)
                    .equals(ParallelCraftingCpuLogic.describeNoCandQuota(-2L)),
                    "无配额口径(-1) 与没递出可计数候选(-2) 必须话术不同");
            assertEquals("无配额口径(-1)", ParallelCraftingCpuLogic.describeNoCandQuota(-1L));
            assertEquals("没递出可计数候选(-2)", ParallelCraftingCpuLogic.describeNoCandQuota(-2L));
        }

        private static WideKeyCounter share(AEKey key, long amount) {
            var counter = new WideKeyCounter();
            counter.set(key, amount);
            return counter;
        }

        /**
         * 老口径的完整判据链（两本账各自饱和合并 ⇒ 族合计 ⇒ 饱和相减），逐行照 {@code HEAD} 之前
         * 的 {@code merge} 三元式、{@code ParallelPlanReservation.shareOf} 与 {@code saturatingSubtract}
         * 重写一遍，只用于对照断言。
         */
        private static long legacySaturatedQuota(long[] mint, long[] takes) {
            long assigned = 0L;
            for (long posting : mint) {
                assigned = assigned > Long.MAX_VALUE - posting ? Long.MAX_VALUE : assigned + posting;
            }
            long eaten = 0L;
            for (long take : takes) {
                eaten = eaten > Long.MAX_VALUE - take ? Long.MAX_VALUE : eaten + take;
            }
            return eaten <= 0L ? Math.max(0L, assigned) : Math.max(0L, assigned - eaten);
        }

        private static KeyCounter holds(AEKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        /**
         * 一次交互向电网要的电封顶在 int 那一档（2026-09-22 定稿口径）：<b>份数照常聚合</b>，
         * 只是不再按"每发功耗 × k 发"整笔去要电。上一条口径被我实现反了一次（拿电去夹份数，
         * 直接把超大批次掐回 2.1e6 发），这条用例就是钉住那个反处的。
         */
        void powerBillingIsCappedAtIntWhileTheBatchStillAggregatesNormally() {
            assertEquals((double) Integer.MAX_VALUE, ParallelCraftingCpuLogic.billedPower(1.0e18D),
                    "整批功耗超过那一档：只按封顶要电");
            assertEquals(1234.0D, ParallelCraftingCpuLogic.billedPower(1234.0D), "没超过就一个字不改");
            assertEquals(0.0D, ParallelCraftingCpuLogic.billedPower(0.0D));

            var perFiringThousand = new TestPattern(OUTPUT, new IPatternDetails.IInput[]{
                    new TestInput(new GenericStack(OUTPUT, 1_000L), null)});
            assertEquals(Long.MAX_VALUE, ParallelCraftingCpuLogic.powerAffordableExecutions(
                    perFiringThousand, 1.0e12D),
                    "电网够付这一档封顶：电这一闸不许再夹份数");
            assertEquals(5L, ParallelCraftingCpuLogic.powerAffordableExecutions(
                    perFiringThousand, 5_500.0D), "电网连封顶都付不起：照旧按现量折算几发");
        }

        /**
         * 无限盘的初始缺口<b>不许</b>挂进"正在合成"：提交时只向网络抽那一次（AE2 的
         * {@code tryExtractInitialItems} 每键一次 ≤long），此后没有任何一条路会再去抽它——
         * 实机形态就是界面上 {@code 正在合成 9.2E} 永远等不到，而 {@code waitingFor} 不清空
         * 作业也永远不判完成。需要量由取料侧就地注入。
         */
        void infiniteSupplyShortfallIsNotRegisteredAsCraftingInProgress() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of(lava));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            var missing = new KeyCounter();
            missing.add(lava, Long.MAX_VALUE);
            missing.add(OUTPUT, 500L);

            ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null, null,
                    IActionSource.empty(), null);

            assertEquals(0L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                    "无限键不许挂在正在合成：那一笔料永远不会从网络来");
            assertEquals(500L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "普通键照旧挂账等实物，一个字不许少");
            assertTrue(job.isUnboundedSupply(lava));
        }

        /**
         * 提交时的初始提料对无限源键<b>一次都不向网络伸手</b>：那一步每键只抽一次、一次最多
         * {@code long}，无限盘要供的量可以 {@code >long}，抽不到的那一截从此没人再来抽。
         * 普通键仍照 AE2 原语义走：抽不满就整单退回、原路投回网络，并报出缺的那一口。
         */
        void initialExtractionNeverAsksTheNetworkForInfiniteSupplies() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var used = new KeyCounter();
            used.add(lava, Long.MAX_VALUE);
            used.add(OUTPUT, 10L);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            used, new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of(lava));

            var storage = new RecordingNetworkStorage();
            var cpuInventory = new ListCraftingInventory(ignored -> {
            });
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, storage, cpuInventory, IActionSource.empty(), new java.util.HashSet<>()),
                    "无限键不许被算进「网络欠我多少料」，普通键 10 枚网络给得起");
            assertEquals(0L, storage.extractCalls.getOrDefault(lava, 0L),
                    "岩浆一次都不该被向网络要");
            assertEquals(1L, storage.extractCalls.getOrDefault(OUTPUT, 0L), "普通键照旧要一次");
            assertEquals(10L, cpuInventory.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE));

            // 普通键抽不满：整单原路投回并报出缺的那一口（AE2 原语义一字不动）
            var greedy = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                    counter(OUTPUT, 500L), new KeyCounter(), new KeyCounter(), Map.of());
            var shortStorage = new RecordingNetworkStorage();
            var cpu2 = new ListCraftingInventory(ignored -> {
            });
            var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                    greedy, shortStorage, cpu2, IActionSource.empty(), new java.util.HashSet<>());
            assertNotNull(missing);
            assertEquals(OUTPUT, missing.what());
            assertEquals(490L, missing.amount());
            assertEquals(0L, cpu2.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "抽不满就不能把半份料留在 CPU 里");
            assertEquals(10L, shortStorage.inserted.getOrDefault(OUTPUT, 0L), "已抽的那 10 枚要原路投回");
        }

        /** 旧计划未携带身份，提交时仍能从明确的精确网络来源补录无限供给。 */
        void explicitlyDeclaredNetworkSourceIsRecognizedAtSubmitAndHandedBackAsUnbounded() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            counter(lava, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of());

            var forgiven = new java.util.HashSet<AEKey>();
            var cpu = new ListCraftingInventory(ignored -> {
            });
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, new LyingInfiniteStorage(lava), cpu, IActionSource.empty(), forgiven),
                    "报得出取不出不许拒掉整单提交");
            assertEquals(java.util.Set.of(lava), forgiven, "这一键要被认成无限源");
            assertTrue(cpu.list.isEmpty(), "放过不等于凭空把满 long 摆进 CPU：需要量在取料那一批才注");
        }

        /**
         * 同一判据的反面：网络两样读数都给 0 的是<b>真缺料</b>，照旧整单投回并报出缺的那一口，
         * 一个键都不许被"认成无限源"。
         */
        void genuinelyAbsentInputStillRefusesTheSubmission() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            counter(OUTPUT, 5L), new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of());

            var forgiven = new java.util.HashSet<AEKey>();
            var cpu = new ListCraftingInventory(ignored -> {
            });
            var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, new LyingInfiniteStorage(lava), cpu, IActionSource.empty(), forgiven);

            assertNotNull(missing, "网络报 0 的键就是真缺料，不许放过");
            assertEquals(OUTPUT, missing.what());
            assertFalse(forgiven.contains(OUTPUT), "真缺料的键不能被认成无限源");
        }

        /**
         * 计划真需要某键 {@code >long} 枚时，提交腿<b>只喂一刀</b>：需求 1.4e19 枚、网络真有 1.4e19，
         * 提交这一步只搬一个 {@code long} 进池子，剩下的那一截留在网络里，由执行侧按批次常态续取
         * （{@code refillTaskInputsFromNetwork} / {@code payDeclaredBatchObligations}）。
         *
         * <p>为什么不再"分批吸到满足"：实机 1e10 的单真需 {@code 1e26} 枚 ⇒ 一万一千刀网络提取全压在
         * 点下开始键那一刻（服务端当场僵住）；而拒单那条形同虚设的代价是先把有限盘整仓吸进 CPU 池再
         * 原路投回（实机 2026-09-25 11:20 的"乱提取"本体）。抽不满的判据本身没动：见下面两条反向用例。
         */
        void aboveLongDemandAbsorbsASingleChunkAndLeavesTheRestInTheNetwork() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var demand = new BigInteger("14000000000000000000"); // 1.4e19 > Long.MAX_VALUE
            var plan = planWithWideUsed(lava, demand);

            var storage = new WideNetworkStorage(lava, demand);
            var pool = new WideCraftingInventory(ignored -> {
            });
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, storage, pool, IActionSource.empty(), new java.util.HashSet<>()),
                    "网络给得出满口：一刀即止也要放行提交，不许判死这一单");
            assertEquals(1, storage.extractAsks,
                    "点开始键那一刻只伸手一次：把全量吸进池子就是实机僵住的那一刀");
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), WideCraftingInventory.amount(pool, lava),
                    "池子里只落一刀");
            assertEquals(demand.subtract(BigInteger.valueOf(Long.MAX_VALUE)), storage.remainingTotal(),
                    "剩下的那一截留在网络里，由执行侧按批次取");
        }

        /**
         * 不许假通过的反向用例：网络只有 5e18 枚（真不够 1.4e19）⇒ 一口没喂满就是真缺料，
         * 仍如实拒提交，缺口是真值 9e18 那一截（1.4e19 − 5e18），既没被吞成"够"、也没被误认成谎报无限源。
         */
        void aboveLongDemandStillReportsTrueShortfallWhenNetworkIsGenuinelyShort() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var demand = new BigInteger("14000000000000000000"); // 1.4e19
            var have = BigInteger.valueOf(5_000_000_000_000_000_000L); // 5e18：网络真给不出剩下的
            var plan = planWithWideUsed(lava, demand);

            var storage = new WideNetworkStorage(lava, have);
            var pool = new WideCraftingInventory(ignored -> {
            });
            var forgiven = new java.util.HashSet<AEKey>();
            var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, storage, pool, IActionSource.empty(), forgiven);

            assertNotNull(missing, "一口给不满就是真缺料，不许放过");
            assertEquals(lava, missing.what());
            assertEquals(9_000_000_000_000_000_000L, missing.amount(),
                    "缺口是真值 9e18（1.4e19 − 5e18），装得进 long 就原样报、不许饱和成天花板");
            assertTrue(forgiven.isEmpty(), "确实在给料、只是不够的普通网络不能被认成无限源");
            assertEquals(0L, WideCraftingInventory.amount(pool, lava).longValue(),
                    "抽不满的整单要原路投回、池子清空");
        }

        /**
         * 一张<b>存储量本来就 {@code >long} 的有限盘</b>（neoecoae／extendedae_plus 那一型）：它一次恰好
         * 交出一个 {@code long}，是真在给料的普通键，<b>绝不许</b>被认成无限源（凭空造料是用户纠过两次的
         * 那一坑）。未声明生成能力的有限盘不得被豁免，{@code forgiven} 必须恒空。
         *
         * <p>提交腿对它也只喂一刀：计划要 3e19、网络真有 2×long，旧形状会把这 2×long 整仓吸进池子再
         * 因填不满而拒单（实机 11:20 的"乱提取"），现在一刀即止、剩下的照旧留在网络里由执行侧续取。
         */
        void hugeFiniteDiskThatReportsTheCeilingIsNeitherDrainedNorLatchedAsUnbounded() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var demand = new BigInteger("30000000000000000000"); // 3e19：两整刀仍填不满
            var have = BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1); // 1.844e19：网络真给到这里
            var plan = planWithWideUsed(lava, demand);

            var storage = new WideNetworkStorage(lava, have);
            var pool = new WideCraftingInventory(ignored -> {
            });
            var forgiven = new java.util.HashSet<AEKey>();
            assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                    plan, storage, pool, IActionSource.empty(), forgiven),
                    "满口给得出就是给得起这一刀，不许拿剩下的还差得远拒掉整单");
            assertTrue(forgiven.isEmpty(), "真交得出料的有限盘不是谎报元件：一次都不许被认成无限源");
            assertEquals(1, storage.extractAsks, "整仓吸进池子那一刀才是乱提取本体");
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), WideCraftingInventory.amount(pool, lava));
            assertEquals(have.subtract(BigInteger.valueOf(Long.MAX_VALUE)), storage.remainingTotal(),
                    "网络剩下的那一截不许在提交这一步被搬走");
        }

        /**
         * 投回那一截必须按<b>宽域真值</b>分 chunk 还。池子里同一键完全可以累计到 k×{@code Long.MAX}
         * （执行侧每 tick 回补一刀，跨批累加越界），而主表只报得出一个天花板，越界那一截藏在溢出
         * 侧表里。旧写法读主表还一次再 {@code clear()}：网络只收回 {@code Long.MAX} 枚，池子却整条清空，
         * 剩下的 {@code (k−1)×Long.MAX} 枚不是没投回去，是凭空蒸发了。
         */
        void aboveLongPoolIsReturnedToTheNetworkInChunksWithoutEvaporating() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var have = BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1); // 1.844e19
            var storage = new WideNetworkStorage(lava, BigInteger.ZERO);

            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insertExact(lava, have, Actionable.MODULATE);
            assertEquals(have, WideCraftingInventory.amount(pool, lava), "现场：池子里累计到 2×long");

            ParallelCraftingCpuLogic.returnMaterialsToNetwork(storage, pool, IActionSource.empty());

            assertEquals(have, storage.remainingTotal(),
                    "分批投出去的 2×long 枚要逐 chunk 原路还回网络，一枚都不许蒸发");
            assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(pool, lava), "还完的池子清空");
        }

        /**
         * 只带一张 {@code used} 宽域溢出账的图计划：AE2 的 {@code usedItems} 把该键饱和成
         * {@link Long#MAX_VALUE}，真值挂在 {@link WideLedgerSnapshot} 的 USED 类上——正是
         * {@code tryExtractInitialItems} 读需求真值的来路。
         */
        private static git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan
                planWithWideUsed(AEKey key, BigInteger exactUsed) {
            var wideUsed = new WideKeyCounter();
            wideUsed.setExact(key, exactUsed);
            var ledger = WideLedgerSnapshot.capture(null, wideUsed, null);
            var base = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                    counter(key, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(), Map.of());
            return new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    base, ledger, null, java.util.Set.of());
        }

        /**
         * 大数库存：某键持有 {@code total} 枚（BigInteger），{@code extract} 每次最多给一个 {@code long}，
         * 抽干后如实给 0（SIMULATE 也诚实报剩余）。正是"网络其实有、只是单次装不下"的实机形状。
         */
        private static final class WideNetworkStorage implements appeng.api.storage.MEStorage {
            private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
            private final AEKey what;
            private BigInteger remaining;
            /** 被伸手的次数：一刀即止这条判据唯一的可证读数。 */
            private int extractAsks;

            private WideNetworkStorage(AEKey what, BigInteger total) {
                this.what = what;
                this.remaining = total;
            }

            private BigInteger remainingTotal() {
                return remaining;
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
                if (!what.equals(key)) {
                    return 0L;
                }
                extractAsks++;
                BigInteger granted = remaining.min(BigInteger.valueOf(amount)).min(LONG_MAX);
                if (mode == Actionable.MODULATE) {
                    remaining = remaining.subtract(granted);
                }
                return granted.longValue();
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource src) {
                if (mode == Actionable.MODULATE && what.equals(key)) {
                    remaining = remaining.add(BigInteger.valueOf(amount));
                }
                return amount;
            }

            @Override
            public Component getDescription() {
                return Component.literal("wide-network");
            }
        }

        /**
         * 部分提交那条腿（{@code seedInitialWaitingFor}）：网络仍然"报得出"而真取没给的那一口
         * 不许挂进"正在合成"——挂上就是界面那个 9.2E，而且 {@code waitingFor} 不清空作业连完成
         * 都判不了。同一次调用里网络报 0 的普通键照旧挂账等实物。
         */
        void partialSubmitNeverAwaitsAClaimTheNetworkCannotHonour() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            var missing = new KeyCounter();
            missing.add(lava, Long.MAX_VALUE);
            missing.add(OUTPUT, 7L);

            ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null,
                    new LyingInfiniteStorage(lava), IActionSource.empty(), new ListCraftingInventory(ignored -> {
            }));

            assertEquals(0L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                    "报得出取不出的那一口不挂账");
            assertTrue(job.isUnboundedSupply(lava), "当场认下，后面每一批都按无限源放行");
            assertEquals(7L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "网络报 0 的普通键照旧挂账等实物");
        }

        /**
         * 同一把尺子的另一半：网络<b>每次给不满但确实在给</b>（SIMULATE 报满、MODULATE 给 1 枚）
         * 不算谎报元件——给到的那一枚要落进池子，剩下的照旧挂账等实物。
         *
         * <p>这一条钉的是"只看 SIMULATE 就当无限"那条误判：那样一来普通存储的剩余缺口从此不再等，
         * 作业直接少一截料。
         */
        void partialDeliveryIsStillAwaitedAndNotLatchedAsUnbounded() {
            var lava = AEItemKey.of(Items.LAVA_BUCKET);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                    new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                            new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                    null, null, java.util.Set.of());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            var missing = new KeyCounter();
            missing.add(lava, 5L);
            var pool = new ListCraftingInventory(ignored -> {
            });

            ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null,
                    new TrickleStorage(lava), IActionSource.empty(), pool);

            assertEquals(1L, pool.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                    "真给到的那一枚不许被吞掉");
            assertEquals(4L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                    "剩下的 4 枚照旧挂账等实物");
            assertFalse(job.isUnboundedSupply(lava), "确实在给料的网络不算谎报元件");
        }

        /** SIMULATE 报满、MODULATE 每次只给 1 枚：普通"给不满但仍在给"的存储形状。 */
        private static final class TrickleStorage implements appeng.api.storage.MEStorage {
            private final AEKey what;

            private TrickleStorage(AEKey what) {
                this.what = what;
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
                if (!what.equals(key)) {
                    return 0L;
                }
                return mode == Actionable.SIMULATE ? amount : Math.min(amount, 1L);
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource src) {
                return amount;
            }

            @Override
            public Component getDescription() {
                return Component.literal("trickle");
            }
        }

        /** 明确声明无限供给的网络夹具；模拟有求必应，实际不搬料，用于验证来源旁路。 */
        private static final class LyingInfiniteStorage
                implements appeng.api.storage.MEStorage, git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply {
            private final AEKey lying;

            private LyingInfiniteStorage(AEKey lying) {
                this.lying = lying;
            }

            @Override public Snapshot readExactSupply() {
                return new Snapshot(Map.of(), java.util.Set.of(lying));
            }

            @Override
            public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
                if (mode == Actionable.SIMULATE && lying.equals(what)) {
                    return amount;
                }
                return 0L;
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
                return amount;
            }

            @Override
            public Component getDescription() {
                return Component.literal("lying");
            }
        }

        /** 每次 extract 只给 10 枚，并记下每个键被要过几次、又收回了几枚。 */
        private static final class RecordingNetworkStorage implements appeng.api.storage.MEStorage {
            private final java.util.Map<AEKey, Long> extractCalls = new java.util.HashMap<>();
            private final java.util.Map<AEKey, Long> inserted = new java.util.HashMap<>();

            @Override
            public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
                extractCalls.merge(what, 1L, Long::sum);
                return Math.min(amount, 10L);
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
                if (mode == Actionable.MODULATE) {
                    inserted.merge(what, amount, Long::sum);
                }
                return amount;
            }

            @Override
            public Component getDescription() {
                return Component.literal("recording");
            }
        }

        private static KeyCounter counter(AEKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        /** 同物品的组件变体：一族三个变体各占一槽，才凑得出越出 {@code long} 的每发合计。 */
        private static AEItemKey worn(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        private static final AEItemKey PICK_A = worn(50);
        private static final AEItemKey PICK_B = worn(0);
        private static final AEItemKey PICK_C = worn(75);
        /** 族配额 {@code 2.4e19} 枚：真值够开两发，{@code long} 读数顶在天花板。 */
        private static final BigInteger FAST_PATH_QUOTA = new BigInteger("24000000000000000000");

        /**
         * NeoEco 快路径那道配额夹（{@code clampByRemainingQuota}）<b>两侧都不许是饱和读数</b>。
         *
         * <p>改前：分子是 {@code quotaFor} 那份"宁高估"钉到 {@link Long#MAX_VALUE} 的<b>哨兵</b>，
         * 分母是 {@code saturatedAdd} 同样钉在天花板上的每发合计——{@code Long.MAX_VALUE /
         * Long.MAX_VALUE = 1}，明明还付得起 2 发的一批被这道 long 除法夹成 1 发。
         * 现在分子走 {@code quotaExact} 的宽域真值、分母按 {@link PlanAmount} 累加，商做完才窄化一次。
         *
         * <p>对照段钉住"普通 {@code long} 域吞吐不变"：所有读数都装得进 {@code long} 时，
         * 宽域与 {@code long} 两条算法逐位同值（夹子既没放宽也没夹得更狠）。
         */
        void fastPathQuotaClampDividesOnTheWideDomain() {
            var slots = new IPatternDetails.IInput[]{
                    new TestInput(new GenericStack(PICK_A, 4_000_000_000_000_000_000L), null),
                    new TestInput(new GenericStack(PICK_B, 4_000_000_000_000_000_000L), null),
                    new TestInput(new GenericStack(PICK_C, 4_000_000_000_000_000_000L), null)
            };
            var pattern = new TestPattern(OUTPUT, slots);
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var planned = new WideKeyCounter();
            planned.setExact(PICK_A, FAST_PATH_QUOTA);
            MaterialAllocationFixtures.assign(job, pattern, planned);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            // 真配额口径：这道夹子必须真的生效（负数＝没有口径＝不夹，测了个空）。
            long readout = reservation.quotaFor(PICK_A, pattern, slots[0]);
            assertTrue(readout >= 0L, "本用例走真配额：quotaFor 必须报得出这一族的份额");
            assertEquals(Long.MAX_VALUE, readout, "2.4e19 枚的族配额在 long 读数上顶到天花板：它是哨兵");
            assertEquals(FAST_PATH_QUOTA.toString(),
                    String.valueOf(reservation.quotaExact(PICK_A, pattern, slots[0])),
                    "宽域读口交出真值——除法只准拿它当分子");

            var sample = new KeyCounter[]{holds(PICK_A, 4_000_000_000_000_000_000L),
                    holds(PICK_B, 4_000_000_000_000_000_000L),
                    holds(PICK_C, 4_000_000_000_000_000_000L)};
            assertEquals(2L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    reservation, pattern, sample, 2L),
                    "族配额 2.4e19 枚 ÷ 每发合计 1.2e19 枚 = 2 发：两个天花板相除读出 1 发就是夹不住份数");
            // 夹子仍只往下夹：请求 9 发时按这一族的真份额折成 2 发，一枚配额都不许多花。
            assertEquals(2L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    reservation, pattern, sample, 9L), "宁少不多：份额只够两发就停在两发");

            // 对照：全部读数装得进 long 的普通形状，宽域算法与 long 算法逐位同值。
            var smallSlots = new IPatternDetails.IInput[]{
                    new TestInput(new GenericStack(PICK_A, 1L), null),
                    new TestInput(new GenericStack(PICK_B, 1L), null),
                    new TestInput(new GenericStack(PICK_C, 1L), null)
            };
            var smallPattern = new TestPattern(OUTPUT, smallSlots);
            var smallPlanned = new WideKeyCounter();
            smallPlanned.set(PICK_A, 24L);
            var smallJob = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            MaterialAllocationFixtures.assign(smallJob, smallPattern, smallPlanned);
            var smallReservation = ParallelPlanReservation.build(smallJob.tasks.entrySet(), Map.of(),
                    smallJob.materialAllocation);
            var smallSample = new KeyCounter[]{holds(PICK_A, 1L), holds(PICK_B, 1L), holds(PICK_C, 1L)};
            // 24 枚份额 ÷ 该族每发合计（1→2→3 递增）＝ 24/12/8：请求 9 发被夹到 8 发。
            assertEquals(8L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    smallReservation, smallPattern, smallSample, 9L),
                    "普通 long 域的发数：换宽域后一个数都不许动");
            assertEquals(5L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    smallReservation, smallPattern, smallSample, 5L), "请求本身就比上界小时，夹子不放大");
        }

        /**
         * 一发样本吃了哪些键、几份已经定下来时，<b>没有配额口径</b>（续传作业／原样退回的模具槽）
         * 那一侧仍然一概不夹：夹了就是把没进表的样板整批饿死。判据换读口（{@code quotaFor<0} →
         * {@code quotaExact==null}）后仍与取料侧同源。
         */
        void fastPathQuotaClampStaysOpenWithoutAQuotaChannel() {
            var slots = new IPatternDetails.IInput[]{
                    new TestInput(new GenericStack(PICK_A, 1L), PICK_A),
                    new TestInput(new GenericStack(PICK_B, 1L), null)
            };
            var pattern = new TestPattern(OUTPUT, slots);
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var planned = new WideKeyCounter();
            planned.set(PICK_B, 3L);
            MaterialAllocationFixtures.assign(job, pattern, planned);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            // 槽0 是原样退回的模具槽：它不在配给里 ⇒ 那一格不夹；槽1 有配额 ⇒ 按 3 枚份额夹。
            assertEquals(-1L, reservation.quotaFor(PICK_A, pattern, slots[0]), "模具槽没有配额口径");
            var sample = new KeyCounter[]{holds(PICK_A, 1L), holds(PICK_B, 1L)};
            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                    reservation, pattern, sample, 7L),
                    "只夹有口径的那一格：模具槽那一格一枚都不该进配额账");
        }

        /**
         * 自算配给趟（生产主路：计划没带配给表时 {@code describePlanBalance} 的
         * {@code mintAllocation == true} 那一支，交白卷出口见 {@code adoptPlannedMaterialPlan}）
         * <b>不许拿 {@code TaskProgress#value} 那份饱和镜像当乘数</b>。
         *
         * <p>任务剩余发数 {@code > long} 时 {@code value} 读出 {@code Long.MAX_VALUE} 哨兵：旧实现拿它
         * 乘每发件数，mint 出的族配给顶在天花板上、与真需差一整截，下游"配额=0 / 可花却付 0"
         * 的实机症状就出自这里。修法：发数读 {@code exactRemaining()} 宽域真值，排水走
         * {@code drainSlotWide}。
         */
        void submitMintAllocationMultipliesWithWideFiringsNotTheLongMirror() {
            var glass = AEItemKey.of(Items.GLASS);
            var consumed = new TestInput(new GenericStack(glass, 3L), null);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            // 3 × 发数 越出 long：发数本身 Long.MAX+1001，真需 3×(Long.MAX+1001)
            var firings = java.math.BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(1001L));
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(firings));
            job.tasks.put(pattern, progress);

            ParallelCraftingCpuLogic.mintSubmitAllocation(job, job.tasks.entrySet(),
                    new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>());

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
            assertNotNull(assigned, "自算趟必须把这张样板的配给 mint 出来");
            assertEquals(firings.multiply(BigInteger.valueOf(3L)), assigned.getExact(glass),
                    "mint 出的配给 = 宽域真需 3×发数：旧实现这里交回的是 Long.MAX_VALUE 那份假数");

            // 取料点/回补侧共用那把尺：拿 quotaExact 的宽域真值除每发合计，可付发数要真算得出来
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var quota = reservation.quotaExact(glass, pattern, consumed);
            assertNotNull(quota, "mint 出的那一格必须有配额口径");
            var payable = ParallelBatchExtraction.cellPayableFirings(
                    null, null, quota.exactValue(), BigInteger.valueOf(3L));
            assertEquals(firings, payable,
                    "按这份配给判'这一格几发'读出的是宽域真发数：拿饱和镜像当乘数时这里少算一整截");
        }

        /**
         * 对照臂（防把宽化做成第二套算法）：与主臂同一批生产成员（{@code mintSubmitAllocation} →
         * {@code SlotAccount#drainSlotWide} → {@code SlotAccount#chargedUnitsWide}），但发数装得进
         * {@code long} ⇒ 交回的配给/缺口读数与旧窄域口径<b>逐字相同</b>。
         *
         * <p>这条臂刻意带两处<b>只在这条臂上分叉</b>的结构，主臂（无自偿还槽、供给为空）看不见：
         * <ul>
         *   <li><b>部分可付</b>的 {@code glass} 池：供给 6000 枚只盖得住 2000 发，{@code drain} 的
         *       {@code fundable = onHand.divide(unit)} 除法被真正走到——漂它只红这条臂。</li>
         *   <li><b>自偿还模具槽</b>（{@code mold}，{@code getRemainingKey==候选键}）：池中无料，
         *       整段 5000 发全没盖住，缺口走 {@code chargedUnitsWide} 的"自偿还只算一发"那一支 ⇒
         *       {@code hole=7}（每发 7 枚 × 1 发）而非 {@code 7×5000}；{@code uncoveredNeed=35000} 仍是整段需求。
         *       把那一支漂成"按整批发数算"只红这条臂（主臂无自偿还槽）。</li>
         * </ul>
         *
         * <p>反向（宽域 vs 窄域那把尺）：把 {@code chargedUnitsWide} 的乘积漂回 {@code long} 饱和，
         * 这条臂的每个数都还在 {@code long} 内 ⇒ 逐字不变（仍绿），只有主臂那条 {@code 3×(Long.MAX+1001)}
         * 会被夹回 {@code Long.MAX} 而红。两向合起来才说明这条臂<em>构成对照</em>，不是空转。
         */
        void submitMintAllocationStaysByteIdenticalWithinLongRange() {
            var glass = AEItemKey.of(Items.GLASS);
            var mold = AEItemKey.of(Items.BUCKET);
            var glassSlot = new TestInput(new GenericStack(glass, 3L), null);
            // 模具槽：候选键与回流键同一个 ⇒ SlotAccount#selfRepaying 命中
            var moldSlot = new TestInput(new GenericStack(mold, 7L), mold);
            var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{glassSlot, moldSlot});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(BigInteger.valueOf(5000L)));
            job.tasks.put(pattern, progress);
            var holes = new java.util.HashMap<AEKey, BigInteger[]>();
            var consumers = new java.util.HashMap<AEKey, java.util.Map<String, BigInteger>>();
            // 部分可付：池里只有 6000 枚 glass ⇒ 只盖得住 2000 发，另 3000 发进 unfunded
            var supply = new java.util.HashMap<AEKey, BigInteger>();
            supply.put(glass, BigInteger.valueOf(6000L));

            ParallelCraftingCpuLogic.mintSubmitAllocation(job, job.tasks.entrySet(),
                    supply, holes, consumers);

            // glass：配给 = 整段需求 3×5000=15000（部分可付只改 hole/funded 的切分，不改配给总量）
            assertEquals(BigInteger.valueOf(15000L), MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(glass),
                    "每发 3 枚 × 5000 发：窄域口径的同一个数，宽化没有换算法");
            // 部分可付把 unfunded 从 5000 发到 3000 发：hole=3×3000=9000，need 仍是整段 3×5000=15000
            assertEquals(BigInteger.valueOf(9000L), holes.get(glass)[0],
                    "fundable 除法把 6000/3=2000 发判成已付，没盖住的 3000 发×3 枚=9000");
            assertEquals(BigInteger.valueOf(15000L), holes.get(glass)[1], "未盖住槽整段需求=3×发数=15000");
            // mold：自偿还只算一发 ⇒ 缺口 hole=7×1=7；need 仍印整段 7×5000=35000
            assertEquals(BigInteger.valueOf(7L), holes.get(mold)[0],
                    "自偿还模具槽整批只欠一发起步量：chargedUnitsWide 走『只算一发』那一支");
            assertFalse(MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).containsKey(mold),
                    "模具槽那份起步量不进配给：它每发都从池子里过一遍");
            assertEquals(BigInteger.valueOf(35000L), holes.get(mold)[1],
                    "未盖槽整段需求仍是 7×5000：全额盖住的槽不计，没盖住的按整段印");
        }

        /**
         * 台账 #115 剩下的那一半：mint 那一臂的<b>端到端</b>用例（不是只问 mint 交回的那个数）。
         *
         * <p>真链一整条：{@code adoptPlannedMaterialPlan} 交 {@code true}（计划没带配给表）→
         * {@code describePlanBalance} 里那一趟 {@code mintSubmitAllocation} 落表 →
         * {@code ParallelPlanReservation#quotaExact} 的<b>两臂</b> → 取料点 {@code prepare} 的封顶 →
         * 唯一销账出口 {@code recordTakenInputs} → 回补腿 {@code refillObligationBatch}。
         *
         * <h3>独立手算表（期望值全部从这张表来，一个字都不由被测产出复算）</h3>
         * <pre>
         * 每发件数：槽0 = 4 枚/发，槽1 = 3 枚/发                      （夹具写死）
         * 任务发数：20 发                                             （夹具写死）
         * 提交供给：plan.usedItems() = 镐 80 枚、桶 60 枚              （＝整段真需，全额盖住）
         *   ⇒ mint 配给：镐 4×20 = 80，桶 3×20 = 60，holes 空
         * 池子起步：镐 100 枚、桶 100 枚
         * 取料点请求 25 发 ⇒ 配额维 min(80/4, 60/3) = 20；池维 min(100/4, 100/3) = 25
         *   ⇒ 夹到 20 发，吃掉 镐 80、桶 60 ⇒ 池 镐 20、桶 40
         * 销账 ⇒ 已吃 镐 80、桶 60 ⇒ 剩配额 0/0
         * 回补一批 20 发：镐 need = 4×20 = 80、池内 20 ⇒ 缺口 60
         *   配额臂 ⇒ 可付发数 0/4 = 0 ⇒ 这一格一枚都不补、向网络伸手 0 次
         *   （配额<b>不</b>生效那一支：room = 60 ⇒ 补 60 枚、伸手 ≥1 次 —— 这一格的牙）
         * </pre>
         *
         * <p>{@code quotaExact} 两臂各钉一个读数：在册且销光＝{@code 0}（"许过且花完"），
         * 表非空而没这一族且<b>带槽</b>＝{@code null}（"从没许过"⇒ 不夹），同一键换成
         * {@code slot == null} 的读数＝{@code 0}（那一道放开只对着带槽的伸手口径）。
         */
        void submitMintFeedsQuotaArmsThenCapsExtractionAndRefillEndToEnd() {
            var pick = AEItemKey.of(new ItemStack(Items.IRON_PICKAXE));
            var sand = AEItemKey.of(Items.SAND);
            var pickSlot = new TestInput(new GenericStack(pick, 4L), null);
            var bucketSlot = new TestInput(new GenericStack(REMAINDER, 3L), null);
            var pattern = new TestPattern(OUTPUT, new IPatternDetails.IInput[]{pickSlot, bucketSlot});
            var job = new ParallelExecutingCraftingJob(
                    emptyPlan(), ignored -> {
                    }, (CraftingLink) null, null);
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(BigInteger.valueOf(20L)));
            job.tasks.put(pattern, progress);
            var plan = planUsing(pick, 80L, REMAINDER, 60L);

            assertTrue(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                    "计划没带配给表 ⇒ 份额只能由提交期 mint 这一臂交（false 那一臂走照单采用）");

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var balance = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertTrue(balance.contains(" src=submit"), "这一趟必须走 mint 臂：" + balance);
            assertTrue(balance.contains(" ok keys="), "供给恰好全额盖住 ⇒ 不该报出缺口：" + balance);
            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
            assertNotNull(assigned, "mint 必须把这张样板的配给落进表里");
            assertEquals(BigInteger.valueOf(80L), assigned.getExact(pick), "槽0 配给＝4 枚/发 × 20 发");
            assertEquals(BigInteger.valueOf(60L), assigned.getExact(REMAINDER), "槽1 配给＝3 枚/发 × 20 发");

            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var pickQuota = reservation.quotaExact(pick, pattern, pickSlot);
            assertNotNull(pickQuota, "mint 落表后这一格必须有配额口径");
            assertEquals(BigInteger.valueOf(80L), pickQuota.exactValue(), "销账前的份额＝手算的整段真需");

            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(pick, 100L, Actionable.MODULATE);
            pool.insert(REMAINDER, 100L, Actionable.MODULATE);

            var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 25L,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "两格都有料，批量必须开得工");
            assertEquals(20L, batch.executions(),
                    "25 发的请求被配额夹到 20 发：池维给得出 25，配额只许 80/4 与 60/3");
            assertEquals(80L, batch.holder()[0].get(pick), "这一批真从池子里拿走 4 枚/发 × 20 发");
            assertEquals(60L, batch.holder()[1].get(REMAINDER), "槽1 按 3 枚/发 × 20 发");
            assertEquals(20L, pool.extract(pick, Long.MAX_VALUE, Actionable.SIMULATE),
                    "池子按手算表掉到 20 枚");

            ParallelCraftingCpuLogic.recordTakenInputs(job, pattern, batch);
            assertEquals(BigInteger.ZERO, reservation.quotaExact(pick, pattern, pickSlot).exactValue(),
                    "在册且销光＝0（『许过且花完』的形状），不是 null");
            assertNull(reservation.quotaExact(sand, pattern, pickSlot),
                    "表非空、没这一族、带槽＝没有配额口径（『从没许过』），不夹");
            assertEquals(BigInteger.ZERO, reservation.quotaExact(sand, pattern, null).exactValue(),
                    "同一键换成 slot==null 的那一臂：照旧交销账份额 0，不是 null");

            var pulls = new java.util.ArrayList<String>();
            ParallelBatchExtraction.ObligationRefill refill = (what, wanted) -> {
                pulls.add(what + "/" + wanted);
                return wanted;
            };
            long moved = ParallelBatchExtraction.refillObligationBatch(
                    pattern, pool, null, 20L, reservation, refill);
            assertEquals(0L, moved,
                    "配额已尽 ⇒ 回补一枚都不搬；配额不生效那一支会按缺口补满 60 枚");
            assertEquals(List.of(), pulls, "配额已尽 ⇒ 这一条腿压根不向网络伸手");
        }

        /**
         * 只带 {@code usedItems()} 那份供给的计划：{@code describePlanBalance} 的提交供给
         * 就是从这儿进 mint 那趟排水的（计划产出走 {@code getOutputs}，本用例那两张夹具样板都交空表）。
         */
        private static ICraftingPlan planUsing(AEKey first, long firstAmount, AEKey second, long secondAmount) {
            var used = new KeyCounter();
            used.add(first, firstAmount);
            used.add(second, secondAmount);
            var empty = new KeyCounter();
            return new ICraftingPlan() {
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
                    return used;
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

        /**
         * {@code [chexsonsaeutils-batch]} 一行必须带着"这一轮被哪个闸口挡住"那两格（读数修正）。
         *
         * <p>{@code materialBackoff=} 与 {@code extractNull=} 过去<b>只在停滞行里有</b>，per100r 一个字都没
         * ⇒ 判不出"20t 的材料退避吃掉了多少轮"（实机那一跑 {@code evaluated=30} 只看得见评估了 30 条
         * 任务，看不见其中多少条是被缺料退避挡掉的，于是把退避当成"没伸手"）。口径取停滞行<b>同一格</b>
         * 的那一轮读数（{@code StallProbe#noteDetail} 已经记下的那两个数），不新记账、不跨窗累加。
         *
         * <p>{@code describeBatchGateReadouts} 是这两格进 per100r 那一行的<b>唯一</b>出口：摘掉这两格，
         * 这里两条断言逐字变红。
         */
        void batchLineCarriesTheBackoffAndExtractNullGatesSoTwentyTickBackoffIsCountable() {
            assertEquals(" materialBackoff=0 extractNull=0",
                    ParallelCraftingCpuLogic.describeBatchGateReadouts(0L, 0L),
                    "两格都要出现且随场景取值：这一窗没人被退避挡住、也没人提取交回 null");
            assertEquals(" materialBackoff=12 extractNull=3",
                    ParallelCraftingCpuLogic.describeBatchGateReadouts(12L, 3L),
                    "退避挡掉 12 条任务、提取交回 null 3 条的场景，必须与停滞行同一把尺");
        }

        /**
         * 驱动本体的假 NeoEco 宿主：{@code prepareBatch} 按"机器容量"自己定发数，
         * {@code push} 照 {@code ECOBatchCraftingHelper#extractExact} 的<b>全有全无</b>真从池子里扣料。
         */
        private static final class FakeNeoEcoHost implements ICraftingProvider, PreparedBatchDispatcher {
            private final IPatternDetails pattern;
            private final AEKey take;
            /** 这一批<b>每发真扣</b>几枚：可以与一发样本记的不一样。 */
            private final long takePerFiring;
            /** {@code >0}：第一次申报就按机器容量报这个发数。 */
            private final long greedyFirstCount;
            /** {@code inputTotal()} 读数覆写：{@code >0} 即照它报。 */
            private final long reportedOverride;
            int prepares;

            FakeNeoEcoHost(IPatternDetails pattern, AEKey take, long takePerFiring,
                    long greedyFirstCount, long reportedOverride
            ) {
                this.pattern = pattern;
                this.take = take;
                this.takePerFiring = takePerFiring;
                this.greedyFirstCount = greedyFirstCount;
                this.reportedOverride = reportedOverride;
            }

            @Override
            public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                    IPatternDetails pattern,
                    KeyCounter[] perCraftInputs,
                    KeyCounter perCraftOutputs,
                    KeyCounter perCraftRemainders,
                    ListCraftingInventory inventory,
                    long requested,
                    IEnergyService energyService,
                    Level level,
                    UUID craftingJobId
            ) {
                prepares++;
                long count = greedyFirstCount > 0L && prepares == 1 ? greedyFirstCount : requested;
                long taken = takePerFiring * count;
                var inputTotal = List.of(new GenericStack(take,
                        reportedOverride > 0L ? reportedOverride : taken));
                return new PreparedBatch() {
                    @Override
                    public boolean push(ListCraftingInventory craftingInventory) {
                        long got = craftingInventory.extract(take, taken, Actionable.MODULATE);
                        if (got != taken) {
                            if (got > 0L) {
                                craftingInventory.insert(take, got, Actionable.MODULATE);
                            }
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public long craftCount() {
                        return count;
                    }

                    @Override
                    public List<GenericStack> inputTotal() {
                        return inputTotal;
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

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return List.of(pattern);
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return true;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
        }

        private record TestInput(GenericStack candidate,
                @org.jetbrains.annotations.Nullable AEKey remainder) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[]{candidate};
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
                return remainder;
            }
        }

        private record TestPattern(AEItemKey definition,
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
            public java.util.List<GenericStack> getOutputs() {
                return java.util.List.of();
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.exactCellInitialExtractionTakesEntireWideUsedAndNeverRefillsConsumedPool", () -> new ParallelCraftingCpuLogicCases().exactCellInitialExtractionTakesEntireWideUsedAndNeverRefillsConsumedPool()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.exactCellContinuationSettlesOnlyPersistedPlannedRemainderInOneOperation", () -> new ParallelCraftingCpuLogicCases().exactCellContinuationSettlesOnlyPersistedPlannedRemainderInOneOperation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.exactCellPartialReceiptDoesNotInventMissingMaterialOrRepeatCollectedAmount", () -> new ParallelCraftingCpuLogicCases().exactCellPartialReceiptDoesNotInventMissingMaterialOrRepeatCollectedAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.resumedJobRecognizesExplicitSupplyWithoutDiscardingInventoryOrWaitingOutputs", () -> new ParallelCraftingCpuLogicCases().resumedJobRecognizesExplicitSupplyWithoutDiscardingInventoryOrWaitingOutputs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.infiniteSupplyDoesNotExemptUnconfiguredComponentVariant", () -> new ParallelCraftingCpuLogicCases().infiniteSupplyDoesNotExemptUnconfiguredComponentVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.resumedFiniteStorageRevokesOldInfiniteIdentityWithoutChangingMaterialOrWaiting", () -> new ParallelCraftingCpuLogicCases().resumedFiniteStorageRevokesOldInfiniteIdentityWithoutChangingMaterialOrWaiting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.undeclaredSimulationSuccessAndRealRefusalIsMissingMaterial", () -> new ParallelCraftingCpuLogicCases().undeclaredSimulationSuccessAndRealRefusalIsMissingMaterial()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.plannedInitialExtractionNeverExceedsWideUsedEvenAfterPoolIsConsumed", () -> new ParallelCraftingCpuLogicCases().plannedInitialExtractionNeverExceedsWideUsedEvenAfterPoolIsConsumed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.plannedInitialExtractionDoesNotInventMaterialDemandFromTasks", () -> new ParallelCraftingCpuLogicCases().plannedInitialExtractionDoesNotInventMaterialDemandFromTasks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.shortPlannedExtractionKeepsOnlyUndeliveredAmountForNextTick", () -> new ParallelCraftingCpuLogicCases().shortPlannedExtractionKeepsOnlyUndeliveredAmountForNextTick()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.plannedInitialRemainderSurvivesJobSaveWithoutResettingTheBudget", () -> new ParallelCraftingCpuLogicCases().plannedInitialRemainderSurvivesJobSaveWithoutResettingTheBudget()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.wallClockBudgetRemainsTheOnlyExhaustionGuard", () -> new ParallelCraftingCpuLogicCases().wallClockBudgetRemainsTheOnlyExhaustionGuard()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.unavailableBatchFallsBackWithoutBeingClassifiedAsPushRejection", () -> new ParallelCraftingCpuLogicCases().unavailableBatchFallsBackWithoutBeingClassifiedAsPushRejection()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.rejectedPreparedBatchIsClassifiedAsPushRejection", () -> new ParallelCraftingCpuLogicCases().rejectedPreparedBatchIsClassifiedAsPushRejection()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.officialBatchSuccessAccountsCompleteCraftCountInOfficialOrder", () -> new ParallelCraftingCpuLogicCases().officialBatchSuccessAccountsCompleteCraftCountInOfficialOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.billingExemptionIsPerSlotNotPerPattern", () -> new ParallelCraftingCpuLogicCases().billingExemptionIsPerSlotNotPerPattern()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.neoEcoFastPathWriteoffRidesTheSingleSlotLevelExit", () -> new ParallelCraftingCpuLogicCases().neoEcoFastPathWriteoffRidesTheSingleSlotLevelExit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.unbalancedFastPathTotalNeverBillsTheMouldShareOnTheConsumingSlot", () -> new ParallelCraftingCpuLogicCases().unbalancedFastPathTotalNeverBillsTheMouldShareOnTheConsumingSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.neoEcoOutflowIsMeasuredFromThePoolWideReadingNotFromTheReportedTotal", () -> new ParallelCraftingCpuLogicCases().neoEcoOutflowIsMeasuredFromThePoolWideReadingNotFromTheReportedTotal()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.neoEcoWriteoffNeverBillsTheSaturatedReadingTheOtherSideReports", () -> new ParallelCraftingCpuLogicCases().neoEcoWriteoffNeverBillsTheSaturatedReadingTheOtherSideReports()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.repeatedAttachOnTheSameSlotAndKeyAccumulatesInsteadOfOverwriting", () -> new ParallelCraftingCpuLogicCases().repeatedAttachOnTheSameSlotAndKeyAccumulatesInsteadOfOverwriting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.unattributableResidualIsCappedByTheSameQuotaFormula", () -> new ParallelCraftingCpuLogicCases().unattributableResidualIsCappedByTheSameQuotaFormula()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.neoEcoFastPathReasksFewerFiringsInsteadOfBillingOverShare", () -> new ParallelCraftingCpuLogicCases().neoEcoFastPathReasksFewerFiringsInsteadOfBillingOverShare()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.quotaCapLeavesSufficientShareUntouched", () -> new ParallelCraftingCpuLogicCases().quotaCapLeavesSufficientShareUntouched()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.nullPrepareFromHostQuietsTheSampleInsteadOfCrashingTheLane", () -> new ParallelCraftingCpuLogicCases().nullPrepareFromHostQuietsTheSampleInsteadOfCrashingTheLane()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.reportedKeyWithoutAnyQuotaBasisDoesNotFoldTheFastPathAway", () -> new ParallelCraftingCpuLogicCases().reportedKeyWithoutAnyQuotaBasisDoesNotFoldTheFastPathAway()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.reportedKeyWithSpentQuotaStillFoldsTheFastPathToZero", () -> new ParallelCraftingCpuLogicCases().reportedKeyWithSpentQuotaStillFoldsTheFastPathToZero()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.reportedKeyWithZeroValuedPlanEntryStillFoldsTheRequestSide", () -> new ParallelCraftingCpuLogicCases().reportedKeyWithZeroValuedPlanEntryStillFoldsTheRequestSide()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.familyAbsentFromPlanTableNeitherFoldsTheRequestNorClosesTheFastPath", () -> new ParallelCraftingCpuLogicCases().familyAbsentFromPlanTableNeitherFoldsTheRequestNorClosesTheFastPath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.twoVariantKeysOfOneFamilySpendTheFamilyShareOnlyOncePerBatch", () -> new ParallelCraftingCpuLogicCases().twoVariantKeysOfOneFamilySpendTheFamilyShareOnlyOncePerBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.selfRepayingMoldSlotKeepsTheFamilyShareForTheConsumingSlot", () -> new ParallelCraftingCpuLogicCases().selfRepayingMoldSlotKeepsTheFamilyShareForTheConsumingSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.secondPrepareReturningNullTakesTheSameNotApplicableExit", () -> new ParallelCraftingCpuLogicCases().secondPrepareReturningNullTakesTheSameNotApplicableExit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.requestSideFoldIsObservableAsEcoFoldTimesAndFirings", () -> new ParallelCraftingCpuLogicCases().requestSideFoldIsObservableAsEcoFoldTimesAndFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped", () -> new ParallelCraftingCpuLogicCases().reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.twoSlotsOfOneFamilyAreCappedByTheFamilyTotalNotEachByTheWholeShare", () -> new ParallelCraftingCpuLogicCases().twoSlotsOfOneFamilyAreCappedByTheFamilyTotalNotEachByTheWholeShare()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.ecoOutPairRendersPoolDeltaAgainstReportedReading", () -> new ParallelCraftingCpuLogicCases().ecoOutPairRendersPoolDeltaAgainstReportedReading()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.lockedReadoutRendersDefinitionSlotAndThreeNumbers", () -> new ParallelCraftingCpuLogicCases().lockedReadoutRendersDefinitionSlotAndThreeNumbers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.slotLevelWriteoffCriteriaSurviveWideMagnitudes", () -> new ParallelCraftingCpuLogicCases().slotLevelWriteoffCriteriaSurviveWideMagnitudes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.cumulativeWriteoffAboveLongKeepsTheRemainingShareReadable", () -> new ParallelCraftingCpuLogicCases().cumulativeWriteoffAboveLongKeepsTheRemainingShareReadable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.writeoffCriteriaAreUnchangedByTheWideLedgers", () -> new ParallelCraftingCpuLogicCases().writeoffCriteriaAreUnchangedByTheWideLedgers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.plannedTableAboveLongSurvivesAdoptionIntoTheQuotaJudge", () -> new ParallelCraftingCpuLogicCases().plannedTableAboveLongSurvivesAdoptionIntoTheQuotaJudge()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.adoptedWideQuotaKeepsTheSaturatedExitAndTheExactWriteoff", () -> new ParallelCraftingCpuLogicCases().adoptedWideQuotaKeepsTheSaturatedExitAndTheExactWriteoff()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.zeroShareInRegisterKeepsTheClampWhileAbsenceLeavesItOpen", () -> new ParallelCraftingCpuLogicCases().zeroShareInRegisterKeepsTheClampWhileAbsenceLeavesItOpen()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.lockedReadoutNamesThePatternAndSlotAndSeparatesMinusOneFromMinusTwo", () -> new ParallelCraftingCpuLogicCases().lockedReadoutNamesThePatternAndSlotAndSeparatesMinusOneFromMinusTwo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.powerBillingIsCappedAtIntWhileTheBatchStillAggregatesNormally", () -> new ParallelCraftingCpuLogicCases().powerBillingIsCappedAtIntWhileTheBatchStillAggregatesNormally()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.infiniteSupplyShortfallIsNotRegisteredAsCraftingInProgress", () -> new ParallelCraftingCpuLogicCases().infiniteSupplyShortfallIsNotRegisteredAsCraftingInProgress()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.initialExtractionNeverAsksTheNetworkForInfiniteSupplies", () -> new ParallelCraftingCpuLogicCases().initialExtractionNeverAsksTheNetworkForInfiniteSupplies()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.explicitlyDeclaredNetworkSourceIsRecognizedAtSubmitAndHandedBackAsUnbounded", () -> new ParallelCraftingCpuLogicCases().explicitlyDeclaredNetworkSourceIsRecognizedAtSubmitAndHandedBackAsUnbounded()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.genuinelyAbsentInputStillRefusesTheSubmission", () -> new ParallelCraftingCpuLogicCases().genuinelyAbsentInputStillRefusesTheSubmission()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.aboveLongDemandAbsorbsASingleChunkAndLeavesTheRestInTheNetwork", () -> new ParallelCraftingCpuLogicCases().aboveLongDemandAbsorbsASingleChunkAndLeavesTheRestInTheNetwork()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.aboveLongDemandStillReportsTrueShortfallWhenNetworkIsGenuinelyShort", () -> new ParallelCraftingCpuLogicCases().aboveLongDemandStillReportsTrueShortfallWhenNetworkIsGenuinelyShort()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.hugeFiniteDiskThatReportsTheCeilingIsNeitherDrainedNorLatchedAsUnbounded", () -> new ParallelCraftingCpuLogicCases().hugeFiniteDiskThatReportsTheCeilingIsNeitherDrainedNorLatchedAsUnbounded()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.aboveLongPoolIsReturnedToTheNetworkInChunksWithoutEvaporating", () -> new ParallelCraftingCpuLogicCases().aboveLongPoolIsReturnedToTheNetworkInChunksWithoutEvaporating()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.partialSubmitNeverAwaitsAClaimTheNetworkCannotHonour", () -> new ParallelCraftingCpuLogicCases().partialSubmitNeverAwaitsAClaimTheNetworkCannotHonour()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.partialDeliveryIsStillAwaitedAndNotLatchedAsUnbounded", () -> new ParallelCraftingCpuLogicCases().partialDeliveryIsStillAwaitedAndNotLatchedAsUnbounded()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.fastPathQuotaClampDividesOnTheWideDomain", () -> new ParallelCraftingCpuLogicCases().fastPathQuotaClampDividesOnTheWideDomain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.fastPathQuotaClampStaysOpenWithoutAQuotaChannel", () -> new ParallelCraftingCpuLogicCases().fastPathQuotaClampStaysOpenWithoutAQuotaChannel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.submitMintAllocationMultipliesWithWideFiringsNotTheLongMirror", () -> new ParallelCraftingCpuLogicCases().submitMintAllocationMultipliesWithWideFiringsNotTheLongMirror()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.submitMintAllocationStaysByteIdenticalWithinLongRange", () -> new ParallelCraftingCpuLogicCases().submitMintAllocationStaysByteIdenticalWithinLongRange()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.submitMintFeedsQuotaArmsThenCapsExtractionAndRefillEndToEnd", () -> new ParallelCraftingCpuLogicCases().submitMintFeedsQuotaArmsThenCapsExtractionAndRefillEndToEnd()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftingCpuLogicCases.batchLineCarriesTheBackoffAndExtractNullGatesSoTwentyTickBackoffIsCountable", () -> new ParallelCraftingCpuLogicCases().batchLineCarriesTheBackoffAndExtractNullGatesSoTwentyTickBackoffIsCountable())
            );
        }
    }

    /** ParallelCraftPlanBaseOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCraftPlanBaseOrderCases {
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
        /** 内容序（＝注册序）：石、铁砧、雪、书、制图台。definition 的字符串序与它无关。 */
        private static final AEItemKey DEF_FIRST = AEItemKey.of(Items.STONE);
        private static final AEItemKey DEF_SECOND = AEItemKey.of(Items.ANVIL);
        private static final AEItemKey DEF_THIRD = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey DEF_VARIANT_FIRST = AEItemKey.of(Items.WRITABLE_BOOK);
        private static final AEItemKey DEF_VARIANT_SECOND = AEItemKey.of(Items.CARTOGRAPHY_TABLE);
        /** 同一族（一把破损铁镐）的两个变体键，各由一张样板付。 */
        private static final AEKey VARIANT_ONE = wornKey(10);
        private static final AEKey VARIANT_TWO = wornKey(20);

        private static final List<AEItemKey> CONTENT_ORDER =
                List.of(DEF_FIRST, DEF_SECOND, DEF_THIRD, DEF_VARIANT_FIRST, DEF_VARIANT_SECOND);

        private static final BigInteger ONE = BigInteger.ONE;
        private static final BigInteger ZERO = BigInteger.ZERO;

        /**
         * 独立手算表（纸面算出，不经被测代码）。每一格是"这一张样板在这一键上的配给份额"，
         * 排水与族摊派都按<b>注册序</b>逐位扣：
         *
         * <table border="1">
         * <caption>#136：五张样板、玻璃池 2、变体族池 1</caption>
         * <tr><th>样板（注册序）</th><th>吃</th><th>发数</th><th>mint 份额</th>
         *     <th>族摊派后</th><th>planBalance 点名</th></tr>
         * <tr><td>石</td><td>玻璃 1</td><td>1</td><td>1（池 2→1）</td><td>1</td><td>—</td></tr>
         * <tr><td>铁砧</td><td>玻璃 1</td><td>1</td><td>1（池 1→0）</td><td>1</td><td>—</td></tr>
         * <tr><td>雪</td><td>玻璃 1</td><td>1</td><td>1（池空，记 shortfall）</td><td>0（摊不到钱）</td>
         *     <td>want= 点名它，hole=1</td></tr>
         * <tr><td>书</td><td>变体一 1</td><td>1</td><td>1</td><td>1（族池 1 被登记首位摊走）</td><td>—</td></tr>
         * <tr><td>制图台</td><td>变体二 1</td><td>1</td><td>1</td><td>0（族池已空）</td><td>—</td></tr>
         * </table>
         *
         * <p>底序错了这一张表就整个错：玻璃池那两枚落在<b>哪两张</b>头上、{@code want=} 点名谁、
         * 变体族那 1 枚归给<b>哪个变体</b>，在哈希序下都由键的哈希决定。
         */
        private static Expected handTable() {
            return new Expected(CONTENT_ORDER,
                    Map.of(DEF_FIRST, ONE, DEF_SECOND, ONE, DEF_THIRD, ZERO,
                            DEF_VARIANT_FIRST, ONE, DEF_VARIANT_SECOND, ZERO),
                    DEF_THIRD);
        }

        /** 手算表形状：顺位、逐张份额、被点名没盖住的那一张。 */
        private record Expected(List<AEItemKey> order, Map<AEItemKey, BigInteger> sharesAfterNarrowing,
                AEItemKey namedUncovered) {
        }

        void taskTableOrderAndTheNumbersItFeedsAreIndependentOfPatternIdentity() {
            var hand = handTable();
            // 两次构造＝同一份计划内容的两组实例：实例不同⇒身份哈希不同（第二组还按相反顺序 new
            // 出来交给图）。改前的顺位正是由那一位哈希决定，两次可以给出相反的答案。
            var first = readBuild(false);
            var second = readBuild(true);

            for (var build : List.of(first, second)) {
                assertEquals(hand.order(), build.planTimesOrder(),
                        "交出去的份数字典顺位＝规划侧注册序，不是主表哈希序");
                assertEquals(hand.order(), build.taskOrder(),
                        "作业任务表的底序＝那份注册序（三条顺位共用它）");
                assertEquals(hand.sharesAfterNarrowing(), build.sharesAfterNarrowing,
                        "族池摊派与变体归属：钱落在哪几张、族里哪个变体上，按底序逐位扣");
                assertTrue(build.planBalance().contains(" want=[" + label(hand.namedUncovered) + "=1]"),
                        () -> "planBalance 该点名注册序第三位（雪）——池子只有两枚玻璃。整行="
                                + build.planBalance());
                assertTrue(build.planBalance().contains(" minecraft:glass hole=1 uncoveredNeed=1 "),
                        () -> "少配的就是那 1 枚（每发 1 × 1 发）：" + build.planBalance());
                assertFalse(build.planBalance().contains(label(DEF_FIRST)),
                        () -> "注册序首位拿到了那两枚里的第一枚，不许被报成缺口。整行=" + build.planBalance());
                assertFalse(build.planBalance().contains(label(DEF_SECOND)),
                        () -> "注册序次位同理。整行=" + build.planBalance());
            }
            // 顺位立住了才会有这一条：两组实例的整行读数逐字相同
            assertEquals(first.planBalance(), second.planBalance(),
                    "同一份计划的两组实例构造读出不同的行——底序还跟着哈希走");
        }

        /** 一次构造：真实出口（转换器）+ 真实作业 + 真实排水 mint + 真实摊派。 */
        private static Build readBuild(boolean reversePatternCreationOrder) {
            var patterns = newPatterns(reversePatternCreationOrder);
            var state = new GraphPlanningState(Map.of(GLASS, 0L), Map.of());
            // 登记一律按内容序（＝求解先给谁记账那一序）；两组实例的差别只在实例本身与图的登记列表序
            for (var definition : CONTENT_ORDER) {
                state.addCrafting(patterns.byDefinition.get(definition), 1L);
            }
            state.addUsedForReconciliation(GLASS, 2L);
            state.addUsedForReconciliation(VARIANT_ONE, 1L);
            state.addUsedForReconciliation(VARIANT_TWO, 1L);

            var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1L, false), graph(patterns));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            var planTimesOrder = plan.patternTimes().keySet().stream()
                    .map(IPatternDetails::getDefinition).toList();
            var taskOrder = job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList();
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            // 唯一的收窄口：玻璃族到账 2（与 mint 那份供给同量，只检顺位）、变体族到账 1
            job.narrowPlanMaterialShareByArrival(GLASS, PlanAmount.of(2L), PlanAmount.ZERO);
            job.narrowPlanMaterialShareByArrival(VARIANT_ONE, PlanAmount.of(1L), PlanAmount.ZERO);

            var shares = new LinkedHashMap<AEItemKey, BigInteger>();
            for (var task : job.tasks.entrySet()) {
                var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(task.getKey());
                var total = BigInteger.ZERO;
                for (AEKey key : assigned.keys()) {
                    total = total.add(assigned.getExact(key));
                }
                shares.put(task.getKey().getDefinition(), total);
            }
            return new Build(planTimesOrder, taskOrder, line, shares);
        }

        /** 一次构造的全部读数。 */
        private record Build(List<AEItemKey> planTimesOrder, List<AEItemKey> taskOrder, String planBalance,
                Map<AEItemKey, BigInteger> sharesAfterNarrowing) {
        }

        /** {@code describeConsumers} 那一格的标签形状（样板身份只用 definition，不参与任何排序）。 */
        private static String label(AEItemKey definition) {
            return definition + "#" + BaseOrderPattern.class.getSimpleName() + " unit=1x1";
        }

        /** 五张样板；第二组按相反顺序 new 实例（身份哈希与第一组无关），内容序登记不变。 */
        private static Patterns newPatterns(boolean reverseCreationOrder) {
            var byDefinition = new LinkedHashMap<AEItemKey, BaseOrderPattern>();
            for (var definition : CONTENT_ORDER) {
                byDefinition.put(definition, new BaseOrderPattern(definition, inputFor(definition)));
            }
            var created = new ArrayList<>(byDefinition.values());
            if (reverseCreationOrder) {
                Collections.reverse(created);
            }
            return new Patterns(byDefinition, created);
        }

        private static AEKey inputFor(AEItemKey definition) {
            if (DEF_VARIANT_FIRST.equals(definition)) {
                return VARIANT_ONE;
            }
            if (DEF_VARIANT_SECOND.equals(definition)) {
                return VARIANT_TWO;
            }
            return GLASS;
        }

        /** 内容序登记表 + 实例创建序（后者交给图，正是不许影响读数的东西）。 */
        private record Patterns(LinkedHashMap<AEItemKey, BaseOrderPattern> byDefinition,
                List<BaseOrderPattern> createdInOrder) {
        }

        private static CraftGraph graph(Patterns patterns) {
            return new CraftGraph(CELL,
                    Map.of(),
                    new IdentityHashMap<IPatternDetails, git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern>(),
                    List.copyOf(patterns.createdInOrder()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(),
                    Set.of(),
                    Map.of(), false, null, 1, 1);
        }

        private static AEKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        /**
         * 单格单候选的样板。<b>刻意不覆写 {@code equals/hashCode}</b>：换一组实例就换一次哈希，
         * 而生产路上的样板换一次 JVM 会话就换一次哈希（同一维自由度）。
         */
        private static final class BaseOrderPattern implements IPatternDetails {

            private final AEItemKey definition;
            private final AEKey input;

            private BaseOrderPattern(AEItemKey definition, AEKey input) {
                this.definition = definition;
                this.input = input;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new SingleInput(input)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(CELL, 1L));
            }
        }

        /** 一发吃一枚候选、不退料：不是模具槽，排水按整批发数计费。 */
        private record SingleInput(AEKey candidate) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(candidate, 1L)};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey key, @Nullable net.minecraft.world.level.Level level) {
                return candidate.equals(key);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelCraftPlanBaseOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCraftPlanBaseOrderCases.taskTableOrderAndTheNumbersItFeedsAreIndependentOfPatternIdentity", () -> new ParallelCraftPlanBaseOrderCases().taskTableOrderAndTheNumbersItFeedsAreIndependentOfPatternIdentity())
            );
        }
    }

    /** ParallelCustomKeyPoolRulerCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelCustomKeyPoolRulerCases {
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

        /**
         * 自定义键类型：{@code codec}/{@code readFromPacket} 这些注册表口径本文件用不到，
         * 也不碰 {@code AEKeyType#getRawId()}（那要 AE2 的 keytypes 注册表）。
         */
        private static final class TestChemicalType extends AEKeyType {
            private TestChemicalType() {
                super(ResourceLocation.fromNamespaceAndPath("testmek", "chemical"),
                        TestChemicalKey.class, Component.literal("testmek:chemical"));
            }

            @Override
            public MapCodec<? extends AEKey> codec() {
                return null;
            }

            @Override
            public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
                return new TestChemicalKey();
            }
        }

        private static final AEKeyType CHEMICAL_TYPE = new TestChemicalType();

        /**
         * 形状照 {@code me.ramidzkh.mekae2.ae2.MekanismKey}（{@code reference-sources} 里没有它的源码，
         * 口径取自 {@code applied-mekanistics-574300-5978711.jar} 的反编译）：{@code getType} 交回自己的
         * 键类型、{@code equals} 只比资源本体（不比数量）、{@code hashCode = Objects.hash(本体)}、
         * {@code getFuzzySearchMaxValue} 沿用 {@code AEKey} 默认的 0（不可做耐久模糊区间）。
         * 唯一被本用例放大的差别：主键交回<b>值对象</b>而不是注册表单例——那正是"池账看得见、
         * AE2 的模糊索引看不见"这一缝的用力点。
         */
        private static final class TestChemicalKey extends AEKey {
            /** 值对象主键：每个键实例一个新对象，{@code equals} 相等、引用不等。 */
            private final ResourceLocation primary =
                    ResourceLocation.fromNamespaceAndPath("mekanism", "redstone");

            @Override
            public AEKeyType getType() {
                return CHEMICAL_TYPE;
            }

            @Override
            public AEKey dropSecondary() {
                return this;
            }

            @Override
            public CompoundTag toTag(HolderLookup.Provider registries) {
                var tag = new CompoundTag();
                tag.putString("id", primary.toString());
                return tag;
            }

            @Override
            public Object getPrimaryKey() {
                return primary;
            }

            @Override
            public ResourceLocation getId() {
                return primary;
            }

            @Override
            public void writeToPacket(RegistryFriendlyByteBuf data) {
            }

            @Override
            protected Component computeDisplayName() {
                return Component.literal("mekanism:redstone");
            }

            @Override
            public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            }

            @Override
            public boolean hasComponents() {
                return false;
            }

            @Override
            public boolean equals(@Nullable Object other) {
                if (this == other) {
                    return true;
                }
                if (other == null || getClass() != other.getClass()) {
                    return false;
                }
                return Objects.equals(primary, ((TestChemicalKey) other).primary);
            }

            @Override
            public int hashCode() {
                return Objects.hash(primary);
            }

            @Override
            public String toString() {
                return "TestChemicalKey{stack=" + primary + "}";
            }
        }

        /** 样板声明的那一枚（万象样板候选表里的键）。 */
        private static final AEKey DECLARED = new TestChemicalKey();
        /** 池子里躺着的那一枚：与声明键 {@code equals} 相等，但主键是另一个引用。 */
        private static final AEKey IN_POOL = new TestChemicalKey();
        private static final AEItemKey ORE = AEItemKey.of(Items.IRON_ORE);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey MISSING = AEItemKey.of(Items.NETHER_STAR);

        /** 每发每格各吃 1 枚。 */
        private static final long UNITS_PER_FIRING = 1L;

        /** 没有占料、没有提交期配给：这一档不夹份数，把"候选宇宙"这一条腿单独摆出来。 */
        private static final ParallelPlanReservation RESERVATION = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), Map.of(), Map.of());

        /** 万象样板那一型：slot0 是自定义键，slot1 是同批的普通物品格。 */
        private static final OmniPattern OMNIVERSAL = new OmniPattern(
                AEItemKey.of(Items.GRINDSTONE),
                new OmniInput[]{
                        new OmniInput(new GenericStack[]{new GenericStack(DECLARED, UNITS_PER_FIRING)},
                                UNITS_PER_FIRING),
                        new OmniInput(new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)},
                                UNITS_PER_FIRING)
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 只吃 {@code IRON_ORE} 那一格的一张：反向守门用，免得把自定义键那格卷进同一条断言。 */
        private static final OmniPattern ORE_ONLY = new OmniPattern(
                AEItemKey.of(Items.FURNACE),
                new OmniInput[]{new OmniInput(
                        new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)}, UNITS_PER_FIRING)},
                List.of(new GenericStack(PRODUCT, 1)));

        /**
         * 取料点：这一格算得出份数、推得动。
         *
         * <p>改前红在 {@code prepare} 直接交 {@code null}（slot0 递不出候选 ⇒ 整批判死），
         * 并且 {@code ncNoListing} 涨 1 —— 实机那一跑把这一档报了 1285 次。
         */
        void theCustomKeySlotPaysItsFiringsInsteadOfReportingNoListing() {
            var pool = poolWithOverflow();
            pool.insert(ORE, 8L, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();

            var batch = ParallelBatchExtraction.prepare(OMNIVERSAL, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION, probe);

            assertNotNull(batch, "池账里躺着这格要的那一枚，就不是缺料：不许把能推的一步判死");
            assertEquals(4L, batch.executions(), "份数由这一格与别的格的现场量一起定，"
                    + "不受「模糊索引看不见这枚自定义键」影响");
            assertEquals(4L, batch.holder()[0].get(IN_POOL), "自定义键那一格交出 4 枚");
            assertEquals(0L, probe.ncNoListing, "「样板写了候选、池里一枚没有」这一档不许再涨");
            assertEquals(0L, probe.ncEmpty, "也不是真缺料");
            assertEquals(4L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "另一格真扣走 4 枚（8 枚起点剩下 4 枚）");
            assertEquals(LONG_MAX.add(BigInteger.valueOf(4L)),
                    WideCraftingInventory.amount(pool, IN_POOL),
                    "这一格付掉 4 枚后池账读出 LONG_MAX+4：伸手的是池子真装着的那一枚，"
                            + "同一份料不许被拆进两个分格");
        }

        /**
         * FIX-E：池料<b>塞得进 {@code long}</b>（实机最常见那一档，这里 8 枚）时同一格照样算得出份数、推得动。
         *
         * <p>上一刀只修了溢出那一档：那一档的 {@code pool=8} 是天花板之上的余数，读的是<b>溢出侧表</b>
         * （{@code HashMap}，按 {@code equals}）；而 8 枚这一档没有侧表条目，唯一那一腿是主表
         * {@code list.get()}，它按 {@code getPrimaryKey()} 的<b>引用</b>分格 ⇒ 声明键落在另一个桶里，
         * 读数仍是 0 ⇒ 第三支候选不触发 ⇒ 这一格照样被报成"池里一枚没有"。
         *
         * <p>改前红在 {@code prepare} 直接交 {@code null}（slot0 递不出候选 ⇒ 整批判死）＋
         * {@code ncNoListing} 涨 1，与溢出那一档改前的红字同一形状。
         */
        void theCustomKeySlotStillReadsItsEightWhenNothingOverflowed() {
            var pool = poolWithPlainEight();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            // 夹具前提：这一档只有主表那一腿，侧表帮不上忙
            assertEquals(8L, pool.list.get(IN_POOL),
                    "夹具前提：8 枚躺在池子那个实例的分格里（没越界 ⇒ 主表是真值、侧表无条目）");
            assertEquals(0L, pool.list.get(DECLARED),
                    "夹具前提：主表那一腿按 getPrimaryKey() 的引用分格，声明键落在另一个桶里 ⇒ 读 0");
            assertTrue(!iteratorHasAny(pool.findFuzzyTemplates(DECLARED)),
                    "夹具前提：AE2 的模糊索引同样看不见这一格");

            var probe = new ParallelBatchExtraction.Probe();
            var batch = ParallelBatchExtraction.prepare(OMNIVERSAL, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION, probe);

            assertNotNull(batch, "池子里躺着 8 枚这一格要的那一枚（塞得进 long）：不是缺料，不许判死整批");
            assertEquals(4L, batch.executions(), "份数由这一格（8 枚）与另一格（4 枚）的现场量一起定");
            assertEquals(4L, batch.holder()[0].get(IN_POOL), "自定义键那一格交出 4 枚");
            assertEquals(0L, probe.ncNoListing, "「样板写了候选、池里一枚没有」这一档不许再涨");
            assertEquals(0L, probe.ncEmpty, "也不是真缺料");
            assertEquals(0L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "另一格那 4 枚真扣走（起点 4 枚 ⇒ 清零）");
            assertEquals(BigInteger.valueOf(4L), WideCraftingInventory.amount(pool, IN_POOL),
                    "这一格付掉 4 枚后池账读出 4 枚：伸手的是池子真装着的那一枚，不留 8 枚的幽灵");
        }

        /**
         * 申报腿同尺：{@code declaredPayableCeiling} 不许因为候选宇宙看不见这一格而把份数算成 0。
         *
         * <p>改前红在 {@code declared=0}（实机停滞行的 {@code declared=0}＋{@code declaredReject} 累加那一型）。
         */
        void theDeclaredCeilingReadsTheSamePoolLedger() {
            var pool = poolWithOverflow();
            pool.insert(ORE, 4L, Actionable.MODULATE);

            var declared = ParallelBatchExtraction.declaredPayableCeiling(OMNIVERSAL, pool, null,
                    RESERVATION, new BigInteger("1000000000000000000"));

            assertEquals(BigInteger.valueOf(4L), declared,
                    "上界由两格的现场量取最小：自定义键那一格池账有量就不是 0");
        }

        /** 义务回补那条腿也认这枚自定义键：先补料、再照单付得出去。 */
        void obligationPaymentReachesTheCustomKeySlotThroughTheSameUniverse() {
            var pool = poolWithOverflow();
            pool.insert(ORE, 3L, Actionable.MODULATE);

            var paid = ParallelBatchExtraction.payDeclaredObligation(OMNIVERSAL, pool, null, 3L,
                    RESERVATION, null);

            assertNotNull(paid, "欠账交割伸手的那套候选与取料点同一本池账");
            assertEquals(3L, paid.executions());
            assertEquals(0L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE), "另一格 3 枚真付出去");
            assertEquals(LONG_MAX.add(BigInteger.valueOf(5L)),
                    WideCraftingInventory.amount(pool, IN_POOL), "自定义键那一格付掉 3 枚：余 5 枚在侧表");
        }

        /**
         * 反向半边（不许把缺料报成有料）：池账压根没有的这一格仍按"递不出候选"记，申报仍是 0。
         *
         * <p>放开候选宇宙不等于放行：这里判的是"池账有没有这一枚"，没有就一枚也不凭空给。
         */
        void aSlotThePoolLedgerReallyDoesNotHoldStillShorts() {
            var pool = poolWithOverflow();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            var pattern = new OmniPattern(AEItemKey.of(Items.CRAFTING_TABLE),
                    new OmniInput[]{
                            new OmniInput(new GenericStack[]{new GenericStack(MISSING,
                                    UNITS_PER_FIRING)}, UNITS_PER_FIRING),
                            new OmniInput(new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)},
                                    UNITS_PER_FIRING)
                    },
                    List.of(new GenericStack(PRODUCT, 1)));
            var probe = new ParallelBatchExtraction.Probe();

            assertNull(ParallelBatchExtraction.prepare(pattern, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION, probe),
                    "池账里没有的就是缺料：放开候选宇宙不许变成凭空给料");
            assertEquals(1L, probe.ncNoListing, "这一格仍然记「递不出候选」，不是被跳过");
            assertEquals(BigInteger.ZERO, ParallelBatchExtraction.declaredPayableCeiling(pattern, pool,
                    null, RESERVATION, BigInteger.TEN), "申报上界照旧归零");
            assertEquals(4L, WideCraftingInventory.amount(pool, ORE).longValueExact(),
                    "没付出去就一枚都不许动池子");
        }

        // ---------- 销账与占料那三条腿：读池子没过池账口径 ----------

        /**
         * 销账快照（{@code ParallelCraftingCpuLogic#readPoolWide}）：push <b>前</b>那一次现量读数按声明键
         * 裸读。要点名的键出自宿主自报清单与样板候选表（{@code neoEcoOutflowKeys}），正是"声明那一份实例"
         * ⇒ 落在另一个桶里恒 0。
         *
         * <p>这一条只问快照那一腿：期望 8 枚（池里真躺着的量），改前红在 0。
         */
        void theOutflowSnapshotReadsThePoolLedgerInstanceOfAThirdPartyKey() {
            var pool = poolWithPlainEight();
            var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(OMNIVERSAL, null, null);

            assertEquals(BigInteger.valueOf(8L),
                    ParallelCraftingCpuLogic.readPoolWide(pool, keys).get(DECLARED),
                    "push 前的现量快照读出池里那 8 枚：按声明键裸读落在另一个桶里，读回来的是 0");
        }

        /**
         * 销账差值（{@code ParallelCraftingCpuLogic#measurePoolOutflow}）：push <b>后</b>那一次现量读数同样
         * 按声明键裸读 ⇒ 池子被读成空 ⇒ 差值把<b>还留在池里的那一截</b>也算成出账（真扣 5 枚却报 8 枚），
         * 两腿一起裸读时更是恒 0（{@code ate=0} ⇒ 配额账不扣、旁人可反复申报同一份存量）。
         *
         * <p>push 前的快照在这里<b>手填</b>成 8 枚——那是快照那一腿改后本就交回的读数：为的是这一条只红在
         * 差值那一行，不被同用例里靠前的快照断言替它挡刀。
         */
        void theOutflowDeltaSubtractsThePoolLedgerReadingTakenByThePush() {
            var pool = poolWithPlainEight();
            var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(OMNIVERSAL, null, null);
            var before = new java.util.HashMap<AEKey, BigInteger>();
            before.put(DECLARED, BigInteger.valueOf(8L));

            assertEquals(5L, pool.extract(IN_POOL, 5L, Actionable.MODULATE),
                    "夹具动作：宿主 push 真从池子里提走 5 枚，池里还剩 3 枚");

            assertEquals(BigInteger.valueOf(5L),
                    ParallelCraftingCpuLogic.measurePoolOutflow(pool, keys, before).getExact(DECLARED),
                    "出账＝8 枚现量减池里剩下的 3 枚：改后读的是池子真装着的那一枚；按声明键裸读把池子读成空"
                            + " ⇒ 剩下那 3 枚也被当成出账销掉");
        }

        /**
         * 占料（{@code ParallelCraftingCpuLogic#pendingPoolClaims} 递给
         * {@code BatchObligationLedger#seedRemaining} 的那一个池子现量读口）：点名的键出自欠账样板的
         * <b>候选表</b> ⇒ 裸读恒 0 ⇒ 顺位排水"排出去 0 枚"⇒ 欠着的料一枚也不遮，同一份存量被旁人反复申报。
         */
        void thePoolClaimForPendingObligationsReadsThePoolLedgerInstance() {
            var pool = poolWithPlainEight();
            var obligations = new BatchObligationLedger();
            obligations.incur(OMNIVERSAL, BigInteger.valueOf(3L));
            var tasks = List.of(Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>entry(
                    OMNIVERSAL, taskWith(3L)));

            var claims = ParallelCraftingCpuLogic.pendingPoolClaims(obligations, tasks, pool, RESERVATION);

            assertEquals(BigInteger.valueOf(3L), claims.getExact(DECLARED),
                    "欠 3 发就把池里那 3 枚口粮遮住：排水先按池账找到池子真装着的那一枚才排得出量，"
                            + "裸读恒 0 时这份占料是空的");
        }

        /**
         * 反向守门（普通 {@code AEItemKey}）：声明键与池子里那一份本来就在<b>同一个分格</b>（主键是注册表
         * 单例）⇒ 换读法不许把本来看得见的那一格读没。三条腿各给一个数，逐字与改前同口径。
         */
        void anOrdinaryItemKeyReadsTheSameThreeNumbersThroughTheNewRuler() {
            var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(ORE_ONLY, null, null);

            var snapshotPool = poolWithPlainOre();
            assertEquals(BigInteger.valueOf(8L),
                    ParallelCraftingCpuLogic.readPoolWide(snapshotPool, keys).get(ORE),
                    "快照：物品键走哪把尺都是同一格，8 枚不许读成 0");

            var outflowPool = poolWithPlainOre();
            var before = new java.util.HashMap<AEKey, BigInteger>();
            before.put(ORE, BigInteger.valueOf(8L));
            assertEquals(5L, outflowPool.extract(ORE, 5L, Actionable.MODULATE), "夹具动作：真扣走 5 枚");
            assertEquals(BigInteger.valueOf(5L),
                    ParallelCraftingCpuLogic.measurePoolOutflow(outflowPool, keys, before).getExact(ORE),
                    "差值：池里剩 3 枚就只销 5 枚，与改前同一个数");

            var obligations = new BatchObligationLedger();
            obligations.incur(ORE_ONLY, BigInteger.valueOf(4L));
            assertEquals(BigInteger.valueOf(4L), ParallelCraftingCpuLogic.pendingPoolClaims(
                            obligations,
                            List.of(Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>entry(
                                    ORE_ONLY, taskWith(4L))),
                            poolWithPlainOre(), RESERVATION).getExact(ORE),
                    "占料：欠 4 发×每发 1 枚 ⇒ 遮 4 枚，与改前同一个数");
        }

        // ---------- 两处诊断读数的池内=：同一个桶里的 0 会骗下一轮排障 ----------

        // ---------- 倒终点产物与实物回补那两脚：读与扣都伸容器真装着的那一枚 ----------

        /**
         * 脚一（{@code ParallelCraftingCpuLogic#flushDyeableRecursiveFinalOutput} 的
         * {@code inventory.extract(finalKey, …, SIMULATE)} 现量 {@code <= 0} 即早退）：
         * 终点产物<b>已经在池里</b>（交进来的是交付那一刻的那一枚实例），收尾要倒出去时伸的却是
         * 计划申报的那一枚 ⇒ 落在另一个桶里 ⇒ 现量 0 ⇒ 整脚跳过 ⇒ 用户报的
         * "补种的东西卡在 cpu 库存里"就是这一型。
         *
         * <p>这一条<b>只</b>红在交付那一格：靠前的三条断言（8 枚真在池里、按申报键向 AE2 那条
         * {@code getStored} 读是 0、requester 一笔没收）改前改后同绿，替不了这一刀的刀口。
         */
        void theFinalOutputFlushPoursOutTheInstanceThePoolActuallyHolds() throws Exception {
            var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L).logic();
            assertEquals(8L, logic.insertBuffered(IN_POOL, 8L, Actionable.MODULATE),
                    "夹具动作：终点产物按交付那一刻的那一枚进池子");
            assertEquals(8L, logic.getStored(IN_POOL), "夹具前提：池子里真躺着 8 枚");
            assertEquals(0L, logic.getStored(DECLARED),
                    "夹具前提：按申报键向 AE2 那条池子读口伸手落在另一个桶里（那是 AE2 契约面，本刀未动）");

            var requester = new RecordingRequester();
            var job = newDyeableRecursiveJob(new GenericStack(DECLARED, 8L), newCpuLinkTiedTo(requester));
            logic.flushDyeableRecursiveFinalOutput(job);

            assertEquals(8L, requester.delivered(IN_POOL),
                    "终点产物必须按池里那一枚整笔倒给收货方：改前那一句按申报键裸读现量读出 0 ⇒ 早退 ⇒ 一枚都不交");
            assertEquals(0L, logic.getStored(IN_POOL),
                    "交出去的 8 枚必须真从池子那一格里抽走：只放开读数而扣落在另一个桶，收尾的 storeItems() 会把同一份料再投一次网");
        }

        /**
         * 脚二（{@code ParallelCraftingCpuLogic#insertInternal} 问产出缓冲的
         * {@code job.waitingFor.extract(what, …, SIMULATE)} 现量 {@code <= 0} 即 {@code EMPTY}）：
         * 挂账那一格是 {@code reserveOne} 按<b>样板申报</b>的键实例记的，实物回来时 AE2 交的是
         * 交货那一刻的另一枚 ⇒ 落在另一个桶里 ⇒ 这一笔回补被判成"没在等它"，一枚不收。
         *
         * <p>{@code lanesByKey}（{@code HashMap}，按 {@code equals}）本来就认得出这一枚——
         * "找得到 lane 却认不出账"正是这一族的形状。摘账那一脚也钉住：改后缓冲必须清零，
         * 否则 {@code waitingFor.list.isEmpty()} 那一条收工判据永远不成立。
         */
        void theReturnedPhysicalProductIsAccountedAgainstTheBufferThatHoldsTheOwedEntry() throws Exception {
            var logic = logicAwaiting(DECLARED);

            assertEquals(8L, logic.getWaitingFor(DECLARED),
                    "夹具前提：挂账真挂在样板申报的那一枚上");
            assertEquals(0L, logic.getWaitingFor(IN_POOL),
                    "夹具前提：交回来的那一枚落在另一个桶里（这一条改前改后同绿）");

            assertEquals(8L, logic.insert(IN_POOL, 8L, Actionable.SIMULATE),
                    "实物回到账口必须认这笔回补：改前问账按交来的那一枚裸读 ⇒ EMPTY ⇒ 一枚都不收");
            assertEquals(8L, logic.insert(IN_POOL, 8L, Actionable.MODULATE),
                    "实收那一笔同样要认：改前这里也是 EMPTY，回补整段作废");
            assertEquals(0L, logic.getWaitingFor(DECLARED),
                    "摘账必须落在挂账那一格上：只放开问账而摘走另一格，这一格永远清不掉、作业永远收不了工");
        }

        /**
         * 反向守门（普通 {@code AEItemKey} 的那两脚）：物品键的主键是注册表单例 ⇒ 申报的那一枚与容器里
         * 那一份本来就在同一个分格 ⇒ 换读法之后两脚的数与改前逐字相同（各 8 枚、各清零）。
         */
        void anOrdinaryItemKeyPoursOutAndAccountsTheSameTwoAmounts() throws Exception {
            var declaredItem = AEItemKey.of(new ItemStack(Items.CHEST));
            var deliveredItem = AEItemKey.of(new ItemStack(Items.CHEST));
            assertTrue(declaredItem != deliveredItem, "夹具前提：两枚是两个实例（主键仍是同一个 Item 注册表单例）");
            assertEquals(declaredItem.getPrimaryKey(), deliveredItem.getPrimaryKey(),
                    "夹具前提：物品键的主键是注册表单例 ⇒ 两把尺子本来就同值");

            var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L).logic();
            logic.insertBuffered(deliveredItem, 8L, Actionable.MODULATE);
            var requester = new RecordingRequester();
            logic.flushDyeableRecursiveFinalOutput(
                    newDyeableRecursiveJob(new GenericStack(declaredItem, 8L), newCpuLinkTiedTo(requester)));
            assertEquals(8L, requester.delivered(declaredItem), "倒出去的还是 8 枚，换读法不许把看得见的那格读没");
            assertEquals(0L, logic.getStored(deliveredItem), "扣走的还是同一格那 8 枚");

            var bufferLogic = logicAwaiting(declaredItem);
            assertEquals(8L, bufferLogic.insert(deliveredItem, 8L, Actionable.MODULATE),
                    "回补认的还是 8 枚：与改前同一个数");
            assertEquals(0L, bufferLogic.getWaitingFor(declaredItem),
                    "摘账摘的还是同一格");
        }

        // ---------- 那两脚的夹具 ----------

        /** 通过真实提交入口把已申报产出挂入等待账，使用同一作业完成模拟和实际到账验证。 */
        private static ParallelCraftingCpuLogic logicAwaiting(AEKey awaited) {
            var network = new appeng.me.storage.NetworkStorage();
            var cluster = DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.offlineCluster(network);
            var logic = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L).logic();
            var emitted = new KeyCounter();
            emitted.add(awaited, 8L);
            var plan = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, false, false,
                    new KeyCounter(), emitted, new KeyCounter(), Map.of());
            assertTrue(logic.trySubmitJob(cluster.grid(), plan,
                    appeng.api.networking.security.IActionSource.empty(), null).successful());
            return logic;
        }

        /** 递归计划作业：终点产物与契约量都按交进来的那份 {@code finalOutput} 定版。 */
        private static ParallelExecutingCraftingJob newDyeableRecursiveJob(
                GenericStack finalOutput, @Nullable CraftingLink link) {
            var base = new CraftingPlan(finalOutput, 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    finalOutput.amount(), Map.of(), Map.of(), null, null);
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, link, null);
        }

        /** CPU 侧那根 link，并按产线的成对口径接到收货方那根上（同一颗 nexus）。 */
        private static CraftingLink newCpuLinkTiedTo(ICraftingRequester requester) {
            var cpuLink = new CraftingLink(
                    CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                    (appeng.api.networking.crafting.ICraftingCPU) null);
            var nexus = new CraftingLinkNexus(cpuLink.getCraftingID());
            cpuLink.setNexus(nexus);
            var requesterLink = new CraftingLink(
                    CraftingCpuHelper.generateLinkData(cpuLink.getCraftingID(), false, true), requester);
            requesterLink.setNexus(nexus);
            return cpuLink;
        }

        /**
         * 收货方：按 {@code equals} 记账（{@code HashMap}）——{@code KeyCounter} 的主表按主键引用分格，
         * 用它记会把"交进来的是哪一枚"这个夹具事实混进断言里。
         */
        private static final class RecordingRequester implements ICraftingRequester {
            private final java.util.Map<AEKey, Long> received = new java.util.HashMap<>();

            long delivered(AEKey what) {
                return received.getOrDefault(what, 0L);
            }

            @Override
            public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
                if (mode == Actionable.MODULATE) {
                    received.merge(what, amount, Long::sum);
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

        /** 欠账排水只读任务表上的顺位：这一档给一张挂在表上的任务条目（发数读数本身不参与排水）。 */
        private static ParallelExecutingCraftingJob.TaskProgress taskWith(long firings) {
            var task = new ParallelExecutingCraftingJob.TaskProgress();
            task.value = firings;
            return task;
        }

        /** 池子里 8 枚普通物品键：主键是注册表单例 ⇒ 声明键与池内实例本来就是同一格。 */
        private static WideCraftingInventory poolWithPlainOre() {
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insertExact(ORE, BigInteger.valueOf(8L), Actionable.MODULATE);
            return pool;
        }

        /** 池子里 {@code LONG_MAX+8} 枚自定义键：主表钉在天花板、侧表躺着多出来的 8 枚。 */
        private static WideCraftingInventory poolWithOverflow() {
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insertExact(IN_POOL, LONG_MAX.add(BigInteger.valueOf(8L)), Actionable.MODULATE);
            return pool;
        }

        /**
         * 池子里 8 枚自定义键（塞得进 {@code long}，实机最常见那一档）：不挂侧表条目，
         * 这一格的读数<b>只有</b>主表那一腿。
         */
        private static WideCraftingInventory poolWithPlainEight() {
            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insertExact(IN_POOL, BigInteger.valueOf(8L), Actionable.MODULATE);
            return pool;
        }

        private static boolean iteratorHasAny(Iterable<AEKey> keys) {
            for (AEKey ignored : keys) {
                return true;
            }
            return false;
        }

        /** 模糊／万象样板那一型：候选表只写一枚精确键，{@code isValid} 按 {@code equals} 认。 */
        private record OmniInput(GenericStack[] candidates, long multiplier)
                implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates.clone();
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                for (var candidate : candidates) {
                    if (candidate.what().equals(input)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        private record OmniPattern(AEItemKey definition, OmniInput[] slots,
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
            org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theCustomKeySlotPaysItsFiringsInsteadOfReportingNoListing", () -> new ParallelCustomKeyPoolRulerCases().theCustomKeySlotPaysItsFiringsInsteadOfReportingNoListing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theCustomKeySlotStillReadsItsEightWhenNothingOverflowed", () -> new ParallelCustomKeyPoolRulerCases().theCustomKeySlotStillReadsItsEightWhenNothingOverflowed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theDeclaredCeilingReadsTheSamePoolLedger", () -> new ParallelCustomKeyPoolRulerCases().theDeclaredCeilingReadsTheSamePoolLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.obligationPaymentReachesTheCustomKeySlotThroughTheSameUniverse", () -> new ParallelCustomKeyPoolRulerCases().obligationPaymentReachesTheCustomKeySlotThroughTheSameUniverse()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.aSlotThePoolLedgerReallyDoesNotHoldStillShorts", () -> new ParallelCustomKeyPoolRulerCases().aSlotThePoolLedgerReallyDoesNotHoldStillShorts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theOutflowSnapshotReadsThePoolLedgerInstanceOfAThirdPartyKey", () -> new ParallelCustomKeyPoolRulerCases().theOutflowSnapshotReadsThePoolLedgerInstanceOfAThirdPartyKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theOutflowDeltaSubtractsThePoolLedgerReadingTakenByThePush", () -> new ParallelCustomKeyPoolRulerCases().theOutflowDeltaSubtractsThePoolLedgerReadingTakenByThePush()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.thePoolClaimForPendingObligationsReadsThePoolLedgerInstance", () -> new ParallelCustomKeyPoolRulerCases().thePoolClaimForPendingObligationsReadsThePoolLedgerInstance()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.anOrdinaryItemKeyReadsTheSameThreeNumbersThroughTheNewRuler", () -> new ParallelCustomKeyPoolRulerCases().anOrdinaryItemKeyReadsTheSameThreeNumbersThroughTheNewRuler()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theFinalOutputFlushPoursOutTheInstanceThePoolActuallyHolds", () -> new ParallelCustomKeyPoolRulerCases().theFinalOutputFlushPoursOutTheInstanceThePoolActuallyHolds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.theReturnedPhysicalProductIsAccountedAgainstTheBufferThatHoldsTheOwedEntry", () -> new ParallelCustomKeyPoolRulerCases().theReturnedPhysicalProductIsAccountedAgainstTheBufferThatHoldsTheOwedEntry()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelCustomKeyPoolRulerCases.anOrdinaryItemKeyPoursOutAndAccountsTheSameTwoAmounts", () -> new ParallelCustomKeyPoolRulerCases().anOrdinaryItemKeyPoursOutAndAccountsTheSameTwoAmounts())
            );
        }
    }

    /** ParallelElapsedTimeTrackerCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelElapsedTimeTrackerCases {
        void wideWorkDoesNotFinishAtTheFirstLongSizedReturn() {
            var tracker = new ParallelElapsedTimeTracker();
            for (int batch = 0; batch < 100; batch++) tracker.addMaxItems(Long.MAX_VALUE, AEKeyType.items());
            for (int batch = 0; batch < 10; batch++) tracker.decrementItems(Long.MAX_VALUE, AEKeyType.items());
            assertEquals(0.1f, tracker.getProgress(), 0.000001f);
            assertEquals(0.1d, (tracker.getStartItemCount() - tracker.getRemainingItemCount())
                    / (double) tracker.getStartItemCount(), 0.000001d);
            var resumed = new ParallelElapsedTimeTracker(tracker.writeToNBT());
            assertEquals(0.1f, resumed.getProgress(), 0.000001f);
            for (int batch = 0; batch < 90; batch++) resumed.decrementItems(Long.MAX_VALUE, AEKeyType.items());
            assertEquals(1.0f, resumed.getProgress());
            assertEquals(0L, resumed.getRemainingItemCount());
        }
        void enormousBatchSurvivesPersistenceWithoutFloatingPointOverflow() {
            var tracker = new ParallelElapsedTimeTracker();
            var total = java.math.BigInteger.TEN.pow(400);
            tracker.addMaxItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total), AEKeyType.items());
            tracker.decrementItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total.divide(java.math.BigInteger.valueOf(4))), AEKeyType.items());
            var resumed = new ParallelElapsedTimeTracker(tracker.writeToNBT());
            assertEquals(0.25f, resumed.getProgress());
            assertTrue(resumed.getRemainingItemCount() > 0);
        }

        void itemAndFluidWorkUsesTheirOwnUnits() {
            var tracker = new ParallelElapsedTimeTracker();
            tracker.addMaxItems(100, AEKeyType.items());
            tracker.addMaxItems(100L * AEKeyType.fluids().getAmountPerUnit(), AEKeyType.fluids());
            tracker.decrementItems(100, AEKeyType.items());
            assertEquals(0.5f, tracker.getProgress());
        }
        void lastUnitCannotRoundAnUnfinishedOrderToComplete() {
            var tracker = new ParallelElapsedTimeTracker();
            var total = java.math.BigInteger.TEN.pow(400);
            tracker.addMaxItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total), AEKeyType.items());
            tracker.decrementItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total.subtract(java.math.BigInteger.ONE)), AEKeyType.items());
            assertTrue(tracker.getProgress() < 1.0f);
            assertEquals(1L, tracker.getRemainingItemCount());
            tracker.decrementItems(1L, AEKeyType.items());
            assertEquals(1.0f, tracker.getProgress());
            assertEquals(0L, tracker.getRemainingItemCount());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelElapsedTimeTrackerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelElapsedTimeTrackerCases.wideWorkDoesNotFinishAtTheFirstLongSizedReturn", () -> new ParallelElapsedTimeTrackerCases().wideWorkDoesNotFinishAtTheFirstLongSizedReturn()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelElapsedTimeTrackerCases.enormousBatchSurvivesPersistenceWithoutFloatingPointOverflow", () -> new ParallelElapsedTimeTrackerCases().enormousBatchSurvivesPersistenceWithoutFloatingPointOverflow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelElapsedTimeTrackerCases.itemAndFluidWorkUsesTheirOwnUnits", () -> new ParallelElapsedTimeTrackerCases().itemAndFluidWorkUsesTheirOwnUnits()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelElapsedTimeTrackerCases.lastUnitCannotRoundAnUnfinishedOrderToComplete", () -> new ParallelElapsedTimeTrackerCases().lastUnitCannotRoundAnUnfinishedOrderToComplete())
            );
        }
    }

    /** ParallelEnvelopeDemandHoleCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelEnvelopeDemandHoleCases {
        private static final AEItemKey GHOST = AEItemKey.of(Items.STRUCTURE_VOID);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

        private static final long SLOT_MULTIPLIER = 3L;
        private static final long FIRINGS = 4L;
        /** 首位候选<b>声明</b>的每模板量：3e18 枚，配上倍率 3 与 4 发就把这一格的需求顶出 {@code long}。 */
        private static final long GHOST_UNITS_PER_TEMPLATE = 3_000_000_000_000_000_000L;
        /** 同一枚候选在<b>规划宇宙</b>里的每模板量：1e18 枚——封套按这一枚记（#199 第一刀的落点）。 */
        private static final long GHOST_UNIVERSE_UNITS = 1_000_000_000_000_000_000L;
        /** 手算：封套＝倍率 3 × 宇宙内每发 1e18 × 4 发 = 1.2e19 枚（许可上界）。 */
        private static final BigInteger ENVELOPE_UNITS = new BigInteger("12000000000000000000");
        /** 手算：取料点每发件数 3×3e18=9e18 × 4 发 = 3.6e19 枚（每发真要吃，与封套分得开）。 */
        private static final BigInteger GHOST_SLOT_DEMAND =
                new BigInteger("36000000000000000000");
        /** 手算：排水实付 SNOW = 12 模板 × 每模板 1 枚。 */
        private static final long SNOW_BILLED_UNITS = 12L;
        /** SNOW 一族的计划供给（也是执行侧 usedItems 那一腿）。 */
        private static final long SNOW_PLAN_SUPPLY = 40L;

        /**
         * 反向臂：这一格只被记了封套、该族在任务表里没有生产者、网络存量为 0、也不在无限源清单里。
         *
         * <p>未修主代码上 {@code describePlanBalance} 交出 {@code ok keys=1}（{@code holes} 空的出口），
         * 3.6e19 枚的每发需求一个字都不进缺口判据 ⇒ 下面三条断言一起红。
         */
        void envelopeOnlyFamilyBeyondLongEntersTheHoleInsteadOfOkKeys() {
            var source = new EnvelopeDemandDetails();
            var bill = shippedBill(source);
            var plan = planOf(source, bill, snowUsedOnly(), Set.of());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" src=plan"), () -> "读的是照单采用那一臂：" + line);
            assertTrue(line.contains(GHOST + " hole=" + GHOST_SLOT_DEMAND),
                    () -> "这一格每发真要吃的 9e18 枚 × 4 发 = 3.6e19 枚必须进缺口，且不许被 long 天花板截短。"
                            + " 整行=" + line);
            assertTrue(line.contains(" uncoveredNeed=" + GHOST_SLOT_DEMAND),
                    () -> "want 侧的整段需求也必须是宽域真值：" + line);
            assertFalse(line.contains(" ok keys="),
                    () -> "封套那一族隐身时打出的 ok keys= 是分母不是判决，必然卡死的单不许再报成配平：" + line);
            assertTrue(line.contains(" unbalanced=1/"), () -> "缺口只这一格：" + line);
            // 排水实记那一腿不许被这一刀带出第二个洞：SNOW 实付 12 枚 ≤ 现场到账 40 枚
            assertFalse(line.contains(SNOW + " hole="), () -> "雪块实记已被供给盖住：" + line);
            // 配额夹照旧读整本（封套那一笔照样在下发表里，摘掉它就把现场有量那一型夹回 0）
            assertEquals(ENVELOPE_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getExact(GHOST),
                    () -> "下发的封套那一笔逐字不变（1.2e19 枚＝宇宙内那枚的每发件数 × 4 发）：" + line);
        }

        /**
         * 正向臂①：同族有现场存量 ⇒ 照旧放行、照旧闭得上。
         *
         * <p>与反向臂同一份表、同一串乘式，只把 {@code usedItems} 多给 5 枚 GHOST——顺位退路的判据只问
         * "这一族在现场拿不拿得到"，不问拿到的够不够整格（够不够由上面那一趟排水实记的 need 核）。
         * 期望逐字：整行 {@code ok keys=}，且封套那一笔 1.2e19 枚仍在下发的表里。
         */
        void envelopeFamilyWithNetworkStockStillClosesTheBalanceLine() {
            var source = new EnvelopeDemandDetails();
            var bill = shippedBill(source);
            var used = new KeyCounter();
            used.add(SNOW, SNOW_PLAN_SUPPLY);
            used.add(GHOST, 5L);
            var plan = planOf(source, bill, used, Set.of());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" ok keys="),
                    () -> "同族有存量＝退路成立，这一格不许被报成缺料：" + line);
            assertFalse(line.contains("hole="), () -> "一个洞都不该有：" + line);
            assertEquals(ENVELOPE_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getExact(GHOST),
                    () -> "放行是靠退路成立，不是靠把封套从配额账上摘掉：" + line);
        }

        /**
         * 正向臂②：同族在无限源清单里 ⇒ 照旧放行。
         *
         * <p>同一份表、同一串乘式，只把 GHOST 认成无限源（计划随契约带下来的那份键集）。期望逐字：
         * {@code ok keys=}，且 1.2e19 枚的封套那一笔一字不动。
         */
        void envelopeFamilyFromUnboundedSourceStaysOutOfTheHole() {
            var source = new EnvelopeDemandDetails();
            var bill = shippedBill(source);
            var plan = planOf(source, bill, snowUsedOnly(), Set.of(GHOST));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" ok keys="),
                    () -> "这一族本就不该向网络抽它：" + line);
            assertFalse(line.contains("hole="), () -> "无限源不许被立成欠账：" + line);
            assertEquals(1, job.unboundedSupplyKeys.size(), () -> "夹具前提：这一枚确实在清单里：" + line);
        }

        /**
         * 正向臂③：同族已经有<b>排水实记</b> ⇒ 同一笔欠账只许报一遍。
         *
         * <p>手算（30 枚那一档，装得进 {@code long}，好把"报两遍"这一档单独钉死）：
         * <pre>
         *   assigned{GHOST} = 60   envelopes{GHOST} = 30   ⇒ 排水实记 = 60 − 30 = 30 枚
         *   执行侧供给 = 空 ⇒ 实记那一腿报 GHOST hole=30（uncoveredNeed=30）
         *   封套那一腿（每发件数 2×3=6 × 5 发 = 30 枚）必须被"同族有实记"挡住 ⇒ 总洞仍是 30，不是 60
         * </pre>
         * 少了这一道挡，同一格就被算成两笔欠账（{@code hole=60}），正是顺位悲观报毛账的形状。
         */
        void familyAlreadyBilledByDrainIsNotBilledTwice() {
            var source = new SmallEnvelopeDetails();
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var assignedRow = new WideKeyCounter();
            assignedRow.add(GHOST, new BigInteger("60"));
            assigned.put(source, assignedRow);
            var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var envelopeRow = new WideKeyCounter();
            envelopeRow.add(GHOST, 30L);
            envelopes.put(source, envelopeRow);

            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(source, 5L);
            var base = new CraftingPlan(new GenericStack(IRON, 5L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 5L,
                    Map.of(), assigned, null, null, Set.of(), envelopes);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(GHOST + " hole=30 "),
                    () -> "排水实记那一腿报 30 枚，封套那一腿不许再加 30 枚。整行=" + line);
            assertFalse(line.contains(GHOST + " hole=60"), () -> "同一格报两遍＝毛账：" + line);
            assertTrue(line.contains(" unbalanced=1/"), () -> "缺口只这一格：" + line);
        }

        /** 执行侧供给的存量那一腿：雪块按计划到账 40 枚。 */
        private static KeyCounter snowUsedOnly() {
            var used = new KeyCounter();
            used.add(SNOW, SNOW_PLAN_SUPPLY);
            return used;
        }

        /** 真闸门那一趟账：下发的整本 {GHOST 1.2e19（封套）, SNOW 12（排水实记）} + 封套本 {GHOST 1.2e19}。 */
        private static MaterialClosureGate.MaterialPlanBill shippedBill(EnvelopeDemandDetails source) {
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GHOST, GHOST_UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, 1L),
                                    new GraphInput.Candidate(GHOST, GHOST_UNIVERSE_UNITS)),
                            Set.of(GHOST, SNOW), false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GHOST, 0L, SNOW, SNOW_PLAN_SUPPLY),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(Map.of(GHOST, 0L, SNOW, SNOW_PLAN_SUPPLY), Map.of());
            state.addCrafting(source, FIRINGS);
            // 首族计划供给 0：不提取 ⇒ 该族在本格的排水实吃恒 0，只剩封套那一笔
            state.extract(SNOW, SNOW_PLAN_SUPPLY, false);

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "夹具前提：闸门必须把这张表发出去（白卷时执行侧走自算臂，"
                    + "下面所有断言都不再经过这一刀）");
            assertEquals(ENVELOPE_UNITS, shipped.getExact(GHOST),
                    () -> "夹具前提：封套那一笔＝宇宙内那枚的每发件数 3e18 × 4 发 = 1.2e19 枚, 表=" + shipped);
            assertEquals(SNOW_BILLED_UNITS, shipped.getLong(SNOW),
                    () -> "夹具前提：整格由备选那一枚付满 12 枚, 表=" + shipped);
            assertEquals(ENVELOPE_UNITS, bill.envelopes().get(source).getExact(GHOST),
                    "同一笔值另记进封套本");
            return bill;
        }

        /** 把闸门那两本账装进真契约；{@code used} 与 {@code unbounded} 由三条用例各自给。 */
        private static RecursiveCraftingPlan planOf(EnvelopeDemandDetails source,
                MaterialClosureGate.MaterialPlanBill bill, KeyCounter used, Set<AEKey> unbounded) {
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(source, FIRINGS);
            var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                    used, new KeyCounter(), new KeyCounter(), times);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            assigned.putAll(bill.assigned());
            var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            envelopes.putAll(bill.envelopes());
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, unbounded, envelopes);
        }

        /**
         * 反向臂/正向臂①②的样板：原始候选顺位 [GHOST@3e18 首位, SNOW@1 备选]、槽倍率 3、无退还键。
         * {@code getInputs()} 必须交回<b>真的</b>候选表，否则 {@code billReachKeySlot} 对早退。
         */
        private static final class EnvelopeDemandDetails implements IPatternDetails {

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new EnvelopeInput(
                        new GenericStack(GHOST, GHOST_UNITS_PER_TEMPLATE),
                        new GenericStack(SNOW, 1L)) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.REPEATER);
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 正向臂③的样板：候选每模板 2 枚、倍率 3、5 发 ⇒ 这一格每发真要吃的量＝ 6 × 5 = 30 枚。 */
        private static final class SmallEnvelopeDetails implements IPatternDetails {

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new EnvelopeInput(
                        new GenericStack(GHOST, 2L), new GenericStack(SNOW, 2L)) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.REPEATER);
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 模糊原始槽：倍率 3、两个候选、无退还键（非模具槽）。 */
        private static final class EnvelopeInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;

            private EnvelopeInput(GenericStack... candidates) {
                this.candidates = candidates;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return SLOT_MULTIPLIER;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeDemandHoleCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeDemandHoleCases.envelopeOnlyFamilyBeyondLongEntersTheHoleInsteadOfOkKeys", () -> new ParallelEnvelopeDemandHoleCases().envelopeOnlyFamilyBeyondLongEntersTheHoleInsteadOfOkKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeDemandHoleCases.envelopeFamilyWithNetworkStockStillClosesTheBalanceLine", () -> new ParallelEnvelopeDemandHoleCases().envelopeFamilyWithNetworkStockStillClosesTheBalanceLine()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeDemandHoleCases.envelopeFamilyFromUnboundedSourceStaysOutOfTheHole", () -> new ParallelEnvelopeDemandHoleCases().envelopeFamilyFromUnboundedSourceStaysOutOfTheHole()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeDemandHoleCases.familyAlreadyBilledByDrainIsNotBilledTwice", () -> new ParallelEnvelopeDemandHoleCases().familyAlreadyBilledByDrainIsNotBilledTwice())
            );
        }
    }

    /** ParallelEnvelopeHoleReadoutCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelEnvelopeHoleReadoutCases {
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        /** "雪块一族已有实记、别张样板又给它记封套"那一型的第二族。 */
        private static final AEItemKey COPPER = AEItemKey.of(Items.COPPER_INGOT);

        /** 手算表里的三个乘数。 */
        private static final long UNITS_PER_TEMPLATE = 2L;
        private static final long SLOT_MULTIPLIER = 3L;
        private static final long FIRINGS = 5L;
        /**
         * 玻璃在 <b>AE2 原始候选表</b>上声明的每发件数：宇宙内那一枚每发 2 枚，声明首位这一枚 6 枚。
         * 封套（许可上界）按宇宙内那枚记，取料点每发真要吃的按声明那枚记 ⇒ 两个数分得开（#199）。
         */
        private static final long DECLARED_GLASS_UNITS = 6L;
        /** 整槽份额＝排水实记＝封套＝30 枚（倍率 3 × 宇宙内每发 2 × 5 发）。 */
        private static final long WHOLE_SLOT_UNITS = 30L;
        /** 取料点那一侧每发真要吃的：倍率 3 × 声明每发 6 × 5 发 = 90 枚。 */
        private static final long REACH_DEMAND_UNITS = 90L;
        /** 规划侧 cover 看到的雪块供给（A 例执行侧也用它 ⇒ 无洞）。 */
        private static final long SNOW_PLAN_SUPPLY = 40L;
        /** B 例：现场只到账 10 枚 ⇒ 实记真洞 30 − 10 = 20 枚。 */
        private static final long SNOW_ARRIVED = 10L;
        private static final long REAL_HOLE_UNITS = 20L;

        /**
         * A 例：计划供给盖得住排水实记 ⇒ <b>排水实记那一腿</b>一个洞都不许有；封套那一族不许顶破这一腿
         * （拿整本当 need 就是把许可上界当欠账）。
         *
         * <p>台账 #196 改口后这一例报的是<b>另一条腿</b>的洞：玻璃那一族没有排水实记、现场没有存量、
         * 图上没有生产者、也不在无限源清单里 ⇒ 取料点每发真要吃的 18 枚 × 5 发 = 90 枚按
         * {@code billReachSlotDemand} 如实进同一只 {@code holes} 口袋（旧口径把这一格整条减光，
         * 提交边界因此打 {@code ok keys=}，实机就是这一格尾段卡死）。
         * 两条腿各钉各的数：雪块 {@code hole=} 一个字不许有（封套不许进 need 分子），玻璃 {@code hole=90}
         * 一个字不许少（那一族的每发需求不许隐身）、且不许是封套那 30 枚（许可上界不许当欠账）。
         */
        void envelopeNeverEntersTheDrainClaimLegWhileItsOwnUnsourcedDemandEntersTheHole() {
            var source = new EnvelopeDetails();
            var bill = shippedBill(source);
            var plan = planOf(source, bill, SNOW_PLAN_SUPPLY);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" src=plan"),
                    () -> "读的是照单采用那一臂（自算臂压根没有封套可减）：" + line);
            assertFalse(line.contains(SNOW + " hole="),
                    () -> "雪块排水实记 30 枚 ≤ 执行侧供给 40 枚：这一腿一个洞都不该有，"
                            + "出现雪块的 hole= 就是又拿整本当 need 了。整行=" + line);
            assertTrue(line.contains(GLASS + " hole=" + REACH_DEMAND_UNITS),
                    () -> "玻璃那一族三样都没有（无实记/无存量/非无限源）⇒ 取料点每发 18 枚 × 5 发 = 90 枚"
                            + "必须进缺口，不再被整条减光。整行=" + line);
            assertFalse(line.contains(GLASS + " hole=" + WHOLE_SLOT_UNITS),
                    () -> "封套那一笔 30 枚（倍率 3 × 宇宙内每发 2 × 5 发）是许可上界：把它报成 hole 就是"
                            + "又拿整本当 need——这一档的数与每发需求 90 枚分得开（#199 补的牙）。整行=" + line);
            assertFalse(line.contains(" ok keys="),
                    () -> "缺口非空就不许走 ok 出口（ok keys= 是分母不是判决）：" + line);
            // 穿壳路：两本都进了作业，且下发表那一笔逐字不变
            assertEquals(WHOLE_SLOT_UNITS, MaterialAllocationFixtures.assignedSnapshot(job).get(source).getLong(GLASS),
                    () -> "封套照样在下发的表里（不许摘掉）：" + line);
            assertEquals(WHOLE_SLOT_UNITS, MaterialAllocationFixtures.envelopeSnapshot(job).get(source).getLong(GLASS),
                    () -> "封套那一本必须穿过契约进作业，hole 才有东西可减：" + line);
            assertEquals(0L, MaterialAllocationFixtures.envelopeSnapshot(job).get(source).getLong(SNOW),
                    () -> "排水实记不进封套本（同一趟账的两本账各记各的）：" + line);
        }

        /**
         * B 例：排水<b>实记</b>真缺 20 枚时照旧报（{@code 121/123/126} 那一腿一字未动），
         * 封套那一族的<b>每发需求</b>另报 30 枚（{@code 196} 新增那一腿）——两个洞各有各的来路，
         * 谁也不许把谁抹掉。
         *
         * <p>期望逐字来自类注释那张手算表：雪块实记 30 枚 > 到账 10 枚 ⇒ {@code hole=20}；
         * 玻璃每发 18 枚 × 5 发 = 90 枚、供给 0 ⇒ {@code hole=90}；{@code unbalanced=2/1}
         * （两个洞 / 一个产出键）。按洞大小排序 ⇒ 玻璃(90) 在雪块(20) 前面。
         */
        void drainClaimHoleAndUnsourcedEnvelopeDemandBothPrintWithTheirOwnNumbers() {
            var source = new EnvelopeDetails();
            var plan = planOf(source, shippedBill(source), SNOW_ARRIVED);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(SNOW + " hole=" + REAL_HOLE_UNITS),
                    () -> "真洞不许被这一刀顺带抹掉：雪块实记 30 枚 > 到账 10 枚 ⇒ hole=20。整行=" + line);
            assertTrue(line.contains(GLASS + " hole=" + REACH_DEMAND_UNITS),
                    () -> "玻璃那一族的每发需求（18 枚 × 5 发）必须另立一笔，不许从缺口里隐身：" + line);
            assertTrue(line.contains(" unbalanced=2/"),
                    () -> "两条腿各报一格：" + line);
            // 排序位：按洞大小排，玻璃 90 > 雪块 20 ⇒ 紧跟 unbalanced= 的是玻璃
            assertTrue(line.contains(" unbalanced=2/1 " + GLASS + " hole=90"),
                    () -> "洞大的排前面（这一档读数口径与 #126 逐字相同，只是洞的来路多了一条）：" + line);
        }

        /**
         * 配额夹<b>照旧读整本</b>（含封套）：首位族在现场有量时可以吃满那一格的 30 枚。
         *
         * <p>这一条钉的是"不许顺手把封套从配额上摘掉"——{@code hole=} 不算它，不等于它不存在：
         * {@code 0fbec2b} 定版的用途就是"运行期现场有量而规划宇宙为 0 时允许它吃满这一格"。
         * 期望同样出自手算表：{@code GLASS} 份额 30、{@code SNOW} 份额 30（减法不许漏到配额上）。
         */
        void quotaClampStillReadsTheWholeBookIncludingTheEnvelope() {
            var source = new EnvelopeDetails();
            var plan = planOf(source, shippedBill(source), SNOW_PLAN_SUPPLY);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var slot = source.getInputs()[0];

            var envelopeQuota = reservation.quotaExact(GLASS, source, slot);
            assertNotNull(envelopeQuota,
                    () -> "封套那一族在配给表里就是配额口径，hole 不算它也不能把它夹成没口径");
            assertEquals(BigInteger.valueOf(WHOLE_SLOT_UNITS), envelopeQuota.exactValue(),
                    "现场有量时这一族可花到整槽那一份＝30 枚（封套存在的全部理由）");
            assertEquals(WHOLE_SLOT_UNITS, reservation.remainingQuota(source, GLASS),
                    "不带槽的销账口径读同一份：两臂不许分叉");
            assertEquals(WHOLE_SLOT_UNITS, reservation.usableFor(GLASS, 100L, source, FIRINGS, slot, false),
                    "池子里躺着 100 枚也只放封套那 30 枚：上界就是上界，不是没有上界");
            assertEquals(WHOLE_SLOT_UNITS, reservation.remainingQuota(source, SNOW),
                    () -> "排水实记那一族的配额原样：hole 侧的减法不许漏进配额账");
        }

        /**
         * 封套落在"这一族在别张样板已经有排水实记"上时，缺口判据必须一个洞都不立。
         * 这一臂是<b>"拿封套整本当 need"那一型回归的专属牙</b>（台账 #199 回炉第二刀）：A/B 两例里
         * 封套额与每发需求已经被拆成两个数（30 / 90），而雪块这一族的洞<b>只可能</b>由"整本当 need"
         * 造出来——抹掉每发需求那一腿碰不到它（雪块的取料点需求本就被"同族有实记"挡住），
         * 摘掉必含格那一档也碰不到它（这里压根没有洞可点名）。
         *
         * <p>手算表（两张样板各 5 发、槽倍率 3、每模板 2 枚 ⇒ 每格整槽 30 枚）：
         * <pre>
         *   样板甲：声明 [雪块@2]，宇宙 [雪块@2]，计划供给雪块 30 ⇒ 排水实记 雪块 30 枚、族已在册不补封套
         *   样板乙：声明 [雪块@2 首位, 铜@2]，宇宙 [铜@2, 雪块@2] ⇒ 排水付满铜 30 枚，
         *          取料点按声明顺位真伸手的是雪块 ⇒ 乙张记封套 雪块 30 枚
         *   执行侧供给（usedItems）= 雪块 30 + 铜 30
         *   实记 = 整本 − 封套 = {雪块 30, 铜 30} ⇒ 两笔都被盖住 ⇒ 一个洞都不许有
         *   —— 拿整本当 need：雪块 need = 30 + 30 = 60 &gt; 30 ⇒ 凭空给雪块立一笔洞
         * </pre>
         */
        void envelopeOnAFamilyThatDrainAlreadyPaidNeverEntersTheHole() {
            var paid = new PaidFamilyDetails(Items.REPEATER,
                    new GenericStack(SNOW, UNITS_PER_TEMPLATE));
            var sibling = new PaidFamilyDetails(Items.COMPARATOR,
                    new GenericStack(SNOW, UNITS_PER_TEMPLATE), new GenericStack(COPPER, UNITS_PER_TEMPLATE));
            var bill = paidFamilyBill(paid, sibling);

            // 夹具前提（不是被判据）：雪块一族在甲张有实记、在乙张只有封套——少了这一对，下面那条
            // assertFalse 就是天然绿，"整本当 need"这一型根本没被量到
            var paidRow = bill.assigned().get(paid);
            assertNotNull(paidRow, () -> "样板甲的表必须下发，否则这一臂不经过减式, 整本=" + bill.assigned());
            assertEquals(WHOLE_SLOT_UNITS, paidRow.getLong(SNOW), "甲张排水实记：雪块 30 枚");
            var paidEnvelope = bill.envelopes().get(paid);
            assertTrue(paidEnvelope == null || paidEnvelope.getLong(SNOW) == 0L,
                    () -> "族已在册就不补封套（甲张这一本不许同时有实记与封套）, 封套=" + paidEnvelope);
            var siblingRow = bill.assigned().get(sibling);
            assertNotNull(siblingRow, () -> "样板乙的表必须下发, 整本=" + bill.assigned());
            assertEquals(WHOLE_SLOT_UNITS, siblingRow.getLong(COPPER), "乙张排水实记：铜块 30 枚");
            assertEquals(WHOLE_SLOT_UNITS, siblingRow.getLong(SNOW), "乙张封套：雪块 30 枚（许可上界）");
            assertEquals(WHOLE_SLOT_UNITS, bill.envelopes().get(sibling).getLong(SNOW),
                    () -> "雪块那一族的封套确实记在乙张这一本上：被减数不是空的，这一臂才有东西可减");

            var used = new KeyCounter();
            used.add(SNOW, WHOLE_SLOT_UNITS);
            used.add(COPPER, WHOLE_SLOT_UNITS);
            var plan = planOf(List.of(paid, sibling), bill, used);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" src=plan"), () -> "读的是照单采用那一臂：" + line);
            assertFalse(line.contains(SNOW + " hole="),
                    () -> "雪块实记 30 枚被现场 30 枚盖住，乙张那一笔只是许可上界：出现雪块的 hole= 就是"
                            + "又拿整本当 need（#199 这一型在旧夹具里与真需求洞同数，谁也分不出来）。整行=" + line);
            assertFalse(line.contains(COPPER + " hole="),
                    () -> "铜块实记同样被现场盖住：" + line);
        }

        /**
         * {@code holes} 的<b>必含格</b>：本窗 {@code blocked=}／{@code 回补受阻} 点名的键，即使按大小
         * 排不进前 8 也必须成行（台账 #199 回炉第二刀，与 {@code alloc=} 同一口径、同一只渲染器）。
         *
         * <p>夹具：一张样板的配给表上九个键各欠一笔（90、80、…、10 枚），封套本为空 ⇒ 九个洞都出自
         * 排水实记那一腿。可见区封顶前 8 ⇒ 最小的那一枚（青金石 {@code hole=10}）自然被挤出去。
         * 两臂一起钉："没点名就不出现"证明下面那一行真是必含格给的、不是窗口给的；点名之后必须逐字成行，
         * 且用的还是同一串格名（{@code hole}/{@code uncoveredNeed}/{@code want}）。
         */
        void keyNamedByBlockedCellGetsARowEvenWhenItLosesTheOrdering() {
            var source = new EnvelopeDetails();
            var namedKey = AEItemKey.of(Items.LAPIS_LAZULI);
            AEItemKey[] holes = {GLASS, SNOW, AEItemKey.of(Items.GOLD_INGOT), COPPER,
                    AEItemKey.of(Items.DIAMOND), AEItemKey.of(Items.EMERALD),
                    AEItemKey.of(Items.REDSTONE), AEItemKey.of(Items.COAL), namedKey};
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var row = new WideKeyCounter();
            for (int i = 0; i < holes.length; i++) {
                row.add(holes[i], 90L - 10L * i);   // 90、80、…、10：点名的那一枚排最后
            }
            assigned.put(source, row);
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(source, FIRINGS);
            var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, Set.of(),
                    new IdentityHashMap<IPatternDetails, WideKeyCounter>());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var unnamed = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertTrue(unnamed.contains(" unbalanced=9/"),
                    () -> "九个洞都在账上（可见区封顶前 8 是分母那一档，不裁账）：" + unnamed);
            assertFalse(unnamed.contains(namedKey + " hole="),
                    () -> "前提：这一枚按大小确实排不进前 8（否则下面那条判据没有牙）：" + unnamed);

            var named = ParallelCraftingCpuLogic.describePlanBalance(job, plan, List.of(namedKey));
            assertTrue(named.contains(namedKey + " hole=10 "),
                    () -> "被 blocked=／回补受阻 点名的键，排序落后面也必须成行（同一只渲染器、同一串格名）："
                            + named);
            assertTrue(named.contains(" unbalanced=9/"),
                    () -> "必含格只补行，不改判决也不改缺口计数：" + named);
        }

        /** 真闸门那一趟账：下发的整本 {GLASS 30, SNOW 30} + 封套本 {GLASS 30}。 */
        private static MaterialClosureGate.MaterialPlanBill shippedBill(EnvelopeDetails source) {
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE),
                                    new GraphInput.Candidate(GLASS, UNITS_PER_TEMPLATE)),
                            Set.of(GLASS, SNOW), false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(
                    Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY), Map.of());
            state.addCrafting(source, FIRINGS);
            // 玻璃计划供给 0：不提取 ⇒ 该族在排水里实吃恒 0，只剩封套那一笔
            state.extract(SNOW, SNOW_PLAN_SUPPLY, false);

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
            // 先钉夹具本身：白卷（表空）时执行侧走自算臂，下面所有断言都会失去落点
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "夹具必须真下发这张表，否则 hole 那条臂根本不经过这一刀");
            assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(GLASS), "下发表的封套那一笔");
            assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(SNOW), "下发表的排水实记那一笔");
            return bill;
        }

        /**
         * 把闸门那两本账装进真契约：{@code usedItems} 是执行侧供给里"从网络取到"那一腿，
         * 由调用方按 A/B 两例给（40 枚 ⇒ 无洞；10 枚 ⇒ 雪块真洞 20 枚）。
         */
        private static RecursiveCraftingPlan planOf(
                EnvelopeDetails source, MaterialClosureGate.MaterialPlanBill bill, long snowUsed) {
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(source, FIRINGS);
            var used = new KeyCounter();
            used.add(SNOW, snowUsed);
            var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                    used, new KeyCounter(), new KeyCounter(), times);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            assigned.putAll(bill.assigned());
            var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            envelopes.putAll(bill.envelopes());
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, Set.of(), envelopes);
        }

        /**
         * "同一族在甲张有排水实记、在乙张只有封套"那一型真闸门那一趟账。
         *
         * <p>甲张：声明与宇宙都是 [雪块@2]、计划供给 30 ⇒ 排水付满雪块 30、族已在册 ⇒ 不补封套。
         * 乙张：声明 [雪块@2 首位, 铜@2]、宇宙 [铜@2, 雪块@2] ⇒ 排水按宇宙顺位付满铜 30，
         * 取料点按声明顺位真伸手的是雪块 ⇒ 补封套雪块 30。两张各 5 发、槽倍率 3。
         */
        private static MaterialClosureGate.MaterialPlanBill paidFamilyBill(
                IPatternDetails paid, IPatternDetails sibling) {
            var paidPattern = new GraphPattern(paid, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(SNOW, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE)),
                            Set.of(SNOW), false)));
            var siblingPattern = new GraphPattern(sibling, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(SNOW, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(COPPER, UNITS_PER_TEMPLATE),
                                    new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE)),
                            Set.of(SNOW, COPPER), false)));
            var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
            bySource.put(paid, paidPattern);
            bySource.put(sibling, siblingPattern);
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(paidPattern, siblingPattern)),
                    bySource,
                    List.of(paid, sibling),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(SNOW, WHOLE_SLOT_UNITS, COPPER, WHOLE_SLOT_UNITS),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(
                    Map.of(SNOW, WHOLE_SLOT_UNITS, COPPER, WHOLE_SLOT_UNITS), Map.of());
            state.addCrafting(paid, FIRINGS);
            state.addCrafting(sibling, FIRINGS);
            state.extract(SNOW, WHOLE_SLOT_UNITS, false);
            state.extract(COPPER, WHOLE_SLOT_UNITS, false);
            return MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
        }

        /** 两张样板装进同一份契约：任务表各 {@value #FIRINGS} 发，执行侧供给的存量那一腿由调用方给。 */
        private static RecursiveCraftingPlan planOf(List<IPatternDetails> sources,
                MaterialClosureGate.MaterialPlanBill bill, KeyCounter used) {
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (var source : sources) {
                times.put(source, FIRINGS);
            }
            var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                    used, new KeyCounter(), new KeyCounter(), times);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            assigned.putAll(bill.assigned());
            var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            envelopes.putAll(bill.envelopes());
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, Set.of(), envelopes);
        }

        /**
         * 两型夹具的样板替身：definition 由入参定（两张样板必须彼此可分），原始候选顺位由入参给。
         * 槽倍率 3、无退还键（非模具槽），与 {@link EnvelopeDetails} 同形。
         */
        private static final class PaidFamilyDetails implements IPatternDetails {
            private final AEItemKey definition;
            private final GenericStack[] rawCandidates;

            private PaidFamilyDetails(net.minecraft.world.item.Item definition,
                    GenericStack... rawCandidates) {
                this.definition = AEItemKey.of(definition);
                this.rawCandidates = rawCandidates;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new EnvelopeInput(rawCandidates) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return this.definition;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /**
         * 真候选表的样板替身：原始顺位 [玻璃@6 首位, 雪块@2 备选]、槽倍率 3、无退还键（非模具槽）。
         * {@code getInputs()} 必须交回<b>真的</b>候选表，否则 {@code billReachKeySlot} 对早退。
         * 玻璃声明那枚每发 6 枚而规划宇宙那枚每发 2 枚，是"封套上界 ≠ 每发真要吃量"这一档的夹具本体。
         */
        private static final class EnvelopeDetails implements IPatternDetails {

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new EnvelopeInput(
                        new GenericStack(GLASS, DECLARED_GLASS_UNITS),
                        new GenericStack(SNOW, UNITS_PER_TEMPLATE)) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.REPEATER);
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 模糊原始槽：倍率 3、两个候选、无退还键。 */
        private static final class EnvelopeInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;

            private EnvelopeInput(GenericStack... candidates) {
                this.candidates = candidates;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return SLOT_MULTIPLIER;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases.envelopeNeverEntersTheDrainClaimLegWhileItsOwnUnsourcedDemandEntersTheHole", () -> new ParallelEnvelopeHoleReadoutCases().envelopeNeverEntersTheDrainClaimLegWhileItsOwnUnsourcedDemandEntersTheHole()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases.drainClaimHoleAndUnsourcedEnvelopeDemandBothPrintWithTheirOwnNumbers", () -> new ParallelEnvelopeHoleReadoutCases().drainClaimHoleAndUnsourcedEnvelopeDemandBothPrintWithTheirOwnNumbers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases.quotaClampStillReadsTheWholeBookIncludingTheEnvelope", () -> new ParallelEnvelopeHoleReadoutCases().quotaClampStillReadsTheWholeBookIncludingTheEnvelope()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases.envelopeOnAFamilyThatDrainAlreadyPaidNeverEntersTheHole", () -> new ParallelEnvelopeHoleReadoutCases().envelopeOnAFamilyThatDrainAlreadyPaidNeverEntersTheHole()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelEnvelopeHoleReadoutCases.keyNamedByBlockedCellGetsARowEvenWhenItLosesTheOrdering", () -> new ParallelEnvelopeHoleReadoutCases().keyNamedByBlockedCellGetsARowEvenWhenItLosesTheOrdering())
            );
        }
    }

    /** ParallelFiringsPlanOwnsTotalCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelFiringsPlanOwnsTotalCases {
        /** 一发吃 1000 枚 V1。 */
        private static final long UNITS_PER_FIRING = 1000L;
        private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

        private static final AEItemKey V1 = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.BARREL);

        /** 单槽、每发 1000 枚 V1、产 1 枚、无回流容器。 */
        private static final FakePattern ONE_A_FIRING = new FakePattern(
                AEItemKey.of(Items.CHEST),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)},
                        UNITS_PER_FIRING, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));

        private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
        private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

        /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
        private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), NO_PLAN, NO_TAKEN);

        private static ListCraftingInventory pool(long v1) {
            ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
            });
            if (v1 > 0L) {
                inventory.insert(V1, v1, Actionable.MODULATE);
            }
            return inventory;
        }

        private static ParallelExecutingCraftingJob newJob() {
            return new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /**
         * 判据 1：池里只 1 发的料、规划剩 5 发 ⇒ 本批推 1 发、剩余仍是 4。
         *
         * <p>现场量（池子这一轮只凑得出 1 发）只许切"本批推几发"这一层——凑齐 1 发就推 1 发。
         * 它<b>不许</b>把这张样板的总份数夹成 0 或 1：推完这一发后任务表剩余必须还是 5-1=4。
         */
        void singleFiringInPoolPushesOneFiringAndLeavesPlanRemaining() {
            var job = newJob();
            var task = progress(5L);
            job.tasks.put(ONE_A_FIRING, task);
            var oneFiringPool = pool(UNITS_PER_FIRING); // 恰好 1 发的料

            var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new AcceptingHost(),
                    ONE_A_FIRING, job, job.tasks.entrySet(), oneFiringPool, null, RESERVATION_FREE,
                    new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(), Long.MAX_VALUE,
                    Long.MAX_VALUE, null, null, null, null, 0L);

            assertNotNull(push, "凑齐 1 发就得推 1 发——现场量只切本批，不许让这张样板整批交白卷");
            assertEquals(1L, push.accepted(), "本批推 1 发（现场这一轮只凑得出 1 发）");
            assertEquals(4L, task.exactRemaining().exactValue().longValueExact(),
                    "总份数只被实收扣掉推出去的那 1 发，5-1=4，不许夹成 0 或 1");
        }


        // ==================================================================================
        // 夹具
        // ==================================================================================


        /** 照收申报份数、回执给满实收的宿主。 */
        private static final class AcceptingHost implements DeclaredBatchHost {
            @Override
            public boolean supportsDeclaredBatch(IPatternDetails pattern) {
                return true;
            }

            @Override
            public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
                return BIG_LONG;
            }

            @Override
            public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                    BigInteger declaredExecutions) {
                return declaredExecutions;
            }

            @Override
            public List<IPatternDetails> getAvailablePatterns() {
                return List.of();
            }

            @Override
            public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
                return true;
            }

            @Override
            public boolean isBusy() {
                return false;
            }
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
            public boolean isValid(AEKey input, Level level) {
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
            org.junit.jupiter.api.Assertions.assertAll("ParallelFiringsPlanOwnsTotalCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelFiringsPlanOwnsTotalCases.singleFiringInPoolPushesOneFiringAndLeavesPlanRemaining", () -> new ParallelFiringsPlanOwnsTotalCases().singleFiringInPoolPushesOneFiringAndLeavesPlanRemaining())
            );
        }
    }

    /** ParallelOrderingRulerIdentityCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelOrderingRulerIdentityCases {
        private static final AEKey SEED = AEItemKey.of(Items.NETHERITE_SCRAP);
        private static final AEKey SNOW = AEItemKey.of(Items.SNOWBALL);
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

        private static final long RING_FIRINGS = 7L;
        private static final long RIVAL_FIRINGS = 3L;
        /** 环每发的起步量：吃 2 枚还 2 枚 ⇒ {@code RingSeedLedger} 记的就是这一份。 */
        private static final long DERIVED_SEED = 2L;
        /** 契约把同一个种记成 1 枚：比派生量小 ⇒ 取并集必须留 2 枚。 */
        private static final long CONTRACT_SEED = 1L;
        /** 契约独有的一族（任务表里没有任何样板产它）：派生给不出，只能靠并集留在册。 */
        private static final long CONTRACT_SNOW = 9L;

        /** 环样板：吃 2 枚种、还 2 枚种，外加一枚铁锭。 */
        private static FakePattern ringPattern() {
            return FakePattern.colored(-1, AEItemKey.of(Items.FLOWER_POT),
                    List.of(new GenericStack(SEED, DERIVED_SEED), new GenericStack(PRODUCT, 1L)),
                    FakeInput.stack(SEED, DERIVED_SEED));
        }

        /** 旁人样板：只吃雪块、不成环，排序上落在环之后。 */
        private static FakePattern rivalPattern() {
            return FakePattern.colored(-1, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(PRODUCT, 1L)),
                    FakeInput.stack(SNOW, 1L));
        }

        /**
         * 建作业：任务表两行按手算表给发数。
         *
         * @param contractInternals {@code null} 交回 AE2 裸计划（没有契约那一半），
         *                          非空（含空表）走带内部件的递归契约
         */
        private static ParallelExecutingCraftingJob jobOf(
                FakePattern ring, FakePattern rival, @Nullable KeyCounter contractInternals) {
            Map<IPatternDetails, Long> times = new LinkedHashMap<>();
            times.put(ring, RING_FIRINGS);
            times.put(rival, RIVAL_FIRINGS);
            var base = new CraftingPlan(new GenericStack(PRODUCT, RING_FIRINGS + RIVAL_FIRINGS),
                    0L, true, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            ICraftingPlan plan = contractInternals == null
                    ? base
                    : new RecursiveCraftingPlan(base, new KeyCounter(), contractInternals, 0L,
                            Map.of(), Map.of(), null, null);
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
        }

        /** 契约那一半的内部件：一种比派生量小、一种派生给不出。 */
        private static KeyCounter contractInternalItems() {
            var internal = new KeyCounter();
            internal.add(SEED, CONTRACT_SEED);
            internal.add(SNOW, CONTRACT_SNOW);
            return internal;
        }

        /** {@code KeyCounter} 的迭代序是哈希序（按设计不保证），所以读数一律按键取名、取值再比。 */
        private static Map<String, Long> readout(KeyCounter counter) {
            var snapshot = new TreeMap<String, Long>();
            for (var entry : counter) {
                snapshot.put(String.valueOf(entry.getKey()), entry.getLongValue());
            }
            return snapshot;
        }

        private static Map<String, Long> expectedMerged() {
            var expected = new TreeMap<String, Long>();
            expected.put(String.valueOf(SEED), DERIVED_SEED);
            expected.put(String.valueOf(SNOW), CONTRACT_SNOW);
            return expected;
        }

        private static Map<String, Long> expectedDerivedOnly() {
            var expected = new TreeMap<String, Long>();
            expected.put(String.valueOf(SEED), DERIVED_SEED);
            return expected;
        }

        /** 同键两份读数取更大的那一档：契约记小了也不能把派生量盖掉。 */
        void dyeableContractAndDerivedSeedsMergeToTheSameBookThroughBothEntries() {
            var ring = ringPattern();
            var rival = rivalPattern();
            var job = jobOf(ring, rival, contractInternalItems());

            var logicSide = ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job);
            var jobSide = job.orderingInternalItems();

            assertEquals(expectedMerged(), readout(logicSide),
                    () -> "执行侧那一入口交出的表不对上手算表：" + readout(logicSide));
            assertEquals(expectedMerged(), readout(jobSide),
                    () -> "摊派那一入口交出的表不对上手算表：" + readout(jobSide));
            // 这里原来还有一条 assertEquals(readout(jobSide), readout(logicSide))：6ea1411 把 logicSide
            // 那一入口改成委托 job.orderingInternalItems() 之后，它是同一次调用的两份返回值互比，恒真。
            assertSameOrderingSequence(job, logicSide, jobSide, ring, rival);
        }

        /** 裸计划（AE2 {@code CraftingPlan}）：只剩任务表现算的那一份。 */
        void barePlanFallsBackToDerivedSeedsThroughBothEntries() {
            var ring = ringPattern();
            var rival = rivalPattern();
            var job = jobOf(ring, rival, null);

            var logicSide = ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job);
            var jobSide = job.orderingInternalItems();

            assertEquals(expectedDerivedOnly(), readout(logicSide),
                    () -> "裸计划这一臂只剩派生账本：" + readout(logicSide));
            assertEquals(expectedDerivedOnly(), readout(jobSide),
                    () -> "裸计划这一臂只剩派生账本：" + readout(jobSide));
            // 同一形状的"两入口逐元素相同"臂在此删掉（委托之后恒真，理由见类注释）。
            assertSameOrderingSequence(job, logicSide, jobSide, ring, rival);
        }

        /** 这把尺的用途：同一张任务表经两个入口喂进排序，交出的序列逐元素相同，且环在旁人之前。 */
        private static void assertSameOrderingSequence(ParallelExecutingCraftingJob job,
                KeyCounter logicSide, KeyCounter jobSide,
                FakePattern ring, FakePattern rival) {
            var fromLogic = keysOf(RecursiveTaskOrdering.orderedEntries(job.tasks, logicSide));
            var fromJob = keysOf(RecursiveTaskOrdering.orderedEntries(job.tasks, jobSide));
            assertEquals(fromLogic, fromJob,
                    "两个入口喂出来的推送顺位不是同一个序：" + fromLogic + " vs " + fromJob);
            assertEquals(List.of(ring, rival), fromLogic,
                    () -> "顺位本身也要钉住（环生产者排最前）：" + fromLogic);
        }

        private static List<IPatternDetails> keysOf(
                List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> ordered) {
            var keys = new ArrayList<IPatternDetails>(ordered.size());
            for (var entry : ordered) {
                keys.add(entry.getKey());
            }
            return keys;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelOrderingRulerIdentityCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelOrderingRulerIdentityCases.dyeableContractAndDerivedSeedsMergeToTheSameBookThroughBothEntries", () -> new ParallelOrderingRulerIdentityCases().dyeableContractAndDerivedSeedsMergeToTheSameBookThroughBothEntries()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelOrderingRulerIdentityCases.barePlanFallsBackToDerivedSeedsThroughBothEntries", () -> new ParallelOrderingRulerIdentityCases().barePlanFallsBackToDerivedSeedsThroughBothEntries())
            );
        }
    }

    /** ParallelPartialSubmitContractShellCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelPartialSubmitContractShellCases {
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

        private static final long UNITS_PER_TEMPLATE = 2L;
        private static final long SLOT_MULTIPLIER = 3L;
        private static final long FIRINGS = 5L;
        /** 整槽份额＝排水实记＝封套＝30 枚。 */
        private static final long WHOLE_SLOT_UNITS = 30L;
        /** 规划侧 cover 看到的雪块供给（闸门那趟宇宙的供给读数）。 */
        private static final long SNOW_PLAN_SUPPLY = 40L;

        /** 穿壳之后两本账仍在册、份额逐字不变，且提交边界读的是照单采用那一臂。 */
        void submissionShellCarriesBothMaterialBooks() {
            var source = new ShellDetails();
            var bill = shippedBill(source);
            var plan = planOf(source, bill);
            var shell = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);

            var job = new ParallelExecutingCraftingJob(shell, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, shell);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, shell);

            assertTrue(line.contains(" src=plan"),
                    () -> "两本账在壳后一起消失时，提交期会自算第二份归属（src=submit）——"
                            + "那正是 #44 定版要绕开的形状。整行=" + line);
            var shippedAssigned = MaterialAllocationFixtures.assignedSnapshot(job).get(source);
            assertNotNull(shippedAssigned,
                    () -> "配给表穿不过这一层壳（作业里没这张样板的账）：" + line);
            assertEquals(WHOLE_SLOT_UNITS, shippedAssigned.getLong(GLASS),
                    () -> "封套那一笔在下发的表里逐字不许变（摘掉它就把首位族现场有量那一型夹回 0）：" + line);
            assertEquals(WHOLE_SLOT_UNITS, shippedAssigned.getLong(SNOW),
                    () -> "排水实记那一笔逐字不许变：" + line);
            var shippedEnvelope = MaterialAllocationFixtures.envelopeSnapshot(job).get(source);
            assertNotNull(shippedEnvelope,
                    () -> "封套那一本没随契约穿过这一层壳 ⇒ hole 那格没有东西可减（回到 #123 的假洞形状）："
                            + line);
            assertEquals(WHOLE_SLOT_UNITS, shippedEnvelope.getLong(GLASS),
                    () -> "封套本逐字不许变：" + line);
            assertEquals(0L, shippedEnvelope.getLong(SNOW),
                    () -> "排水实记不进封套本（同一趟账的两本账各记各的）：" + line);
        }

        /**
         * 穿壳之后 {@code hole=} 那一格仍是"实记腿只算排水实记"的口径：雪块真洞照报，封套那一笔
         * 仍旧不是它的减数。台账 #196 之后玻璃那一族另外立一笔——这一族在穿壳后的供给里既没有实记、
         * 也没有存量、还不在无限源里，取料点每发真要吃的 6 枚 × 5 发 = 30 枚不许再从缺口里隐身。
         */
        void submissionShellKeepsBothHoleLegsReportingWhatTheyReallyOwe() {
            var source = new ShellDetails();
            var plan = planOf(source, shippedBill(source));
            var shell = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);
            var job = new ParallelExecutingCraftingJob(shell, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, shell);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, shell);

            assertTrue(line.contains(SNOW + " hole=" + WHOLE_SLOT_UNITS),
                    () -> "雪块排水实记 30 枚、壳后供给为 0 ⇒ 真洞 30 枚照报。整行=" + line);
            assertTrue(line.contains(GLASS + " hole=" + WHOLE_SLOT_UNITS),
                    () -> "玻璃那一族的每发需求（6 枚 × 5 发 = 30 枚）必须进缺口——旧口径把这一格整条"
                            + "减光，提交边界因此打 ok keys=，实机正是这一格尾段 ranDry。整行=" + line);
            assertTrue(line.contains(" unbalanced=2/"),
                    () -> "两条腿各报一格（实记腿雪块 30 枚 + 伸手腿玻璃 30 枚）：" + line);
            assertFalse(line.contains(" ok keys="),
                    () -> "缺口非空就不许走 ok 出口：" + line);
        }

        /** 真闸门那一趟账：下发的整本 {GLASS 30, SNOW 30} + 封套本 {GLASS 30}。 */
        private static MaterialClosureGate.MaterialPlanBill shippedBill(ShellDetails source) {
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, UNITS_PER_TEMPLATE, SLOT_MULTIPLIER,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, UNITS_PER_TEMPLATE),
                                    new GraphInput.Candidate(GLASS, UNITS_PER_TEMPLATE)),
                            Set.of(GLASS, SNOW), false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(
                    Map.of(GLASS, 0L, SNOW, SNOW_PLAN_SUPPLY), Map.of());
            state.addCrafting(source, FIRINGS);
            // 玻璃计划供给 0：不提取 ⇒ 该族在排水里实吃恒 0，只剩封套那一笔
            state.extract(SNOW, SNOW_PLAN_SUPPLY, false);

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph, FIRINGS);
            // 先钉夹具本身：白卷（表空）时执行侧走自算臂，下面所有断言都会失去落点
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "夹具必须真下发这张表，否则这一腿压根不经过这一刀");
            assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(GLASS), "下发表的封套那一笔");
            assertEquals(WHOLE_SLOT_UNITS, shipped.getLong(SNOW), "下发表的排水实记那一笔");
            return bill;
        }

        /** 把闸门那两本账装进真契约（{@code usedItems} 空：部分提交腿不在提交这一步预抽整段料）。 */
        private static ICraftingPlan planOf(
                ShellDetails source, MaterialClosureGate.MaterialPlanBill bill) {
            Map<IPatternDetails, Long> times = new LinkedHashMap<>();
            times.put(source, FIRINGS);
            var base = new CraftingPlan(new GenericStack(IRON, FIRINGS), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            assigned.putAll(bill.assigned());
            var envelopes = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            envelopes.putAll(bill.envelopes());
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, Set.of(), envelopes);
        }

        /** 真候选表的样板替身：原始顺位 [玻璃@2 首位, 雪块@2 备选]、槽倍率 3、无退还键（非模具槽）。 */
        private static final class ShellDetails implements IPatternDetails {

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new ShellInput(
                        new GenericStack(GLASS, UNITS_PER_TEMPLATE),
                        new GenericStack(SNOW, UNITS_PER_TEMPLATE)) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.REPEATER);
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 模糊原始槽：倍率 3、两个候选、无退还键。 */
        private static final class ShellInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;

            private ShellInput(GenericStack... candidates) {
                this.candidates = candidates;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return SLOT_MULTIPLIER;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public @org.jetbrains.annotations.Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelPartialSubmitContractShellCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPartialSubmitContractShellCases.submissionShellCarriesBothMaterialBooks", () -> new ParallelPartialSubmitContractShellCases().submissionShellCarriesBothMaterialBooks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPartialSubmitContractShellCases.submissionShellKeepsBothHoleLegsReportingWhatTheyReallyOwe", () -> new ParallelPartialSubmitContractShellCases().submissionShellKeepsBothHoleLegsReportingWhatTheyReallyOwe())
            );
        }
    }

    /** ParallelPatternIdentityAndBackoffBlindCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelPatternIdentityAndBackoffBlindCases {
        /** 带组件的键：{@code toString()} 是 "{@code <id> (with patches)}"，两个外来空格就是形状判据的牙。 */
        private static final AEItemKey WORN = worn(50);
        private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
        private static final AEKey CELL = AEItemKey.of(Items.CHEST);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final long SESSION_QUOTA = 42_662_400_000L;

        /** 样板身份标 {@code #i<hex>} 的样（八位以内十六进制，全小写）。 */
        private static final java.util.regex.Pattern IDENTITY_TAG =
                java.util.regex.Pattern.compile("#i([0-9a-f]{1,8})");

        /**
         * 判据①：{@code noCandWhy=} 与 {@code quotaBlind=} 的 {@code src=} 带<b>同一个</b>样板身份标，
         * 且与 {@code blocked=} 的样板头一致；两张同名不同实例的样板各自的身份标<b>必须不同</b>。
         *
         * <p>改前红在"两格里压根没有身份标"：{@code #i} 一个都找不出来 ⇒ 取不出 tag ⇒ 断言红。
         */
        void theSamePatternCarriesOneIdentityTagAcrossNoCandWhyAndQuotaBlindSrc() {
            // 生产者：产 SHINING，自己那一格吃 WORN（池里一枚没有 ⇒ 真走 noCand 那一档）
            var slots = new OmniSlot[]{new OmniSlot(new GenericStack[]{new GenericStack(WORN, 4L)}, 1L)};
            var producer = new FakePattern(AEItemKey.of(Items.GRINDSTONE), slots,
                    List.of(new GenericStack(SHINING, 1L)));
            // 另一张<b>同名不同实例</b>的万象样板：与 producer 按 equals 相等（AE2 就按 definition 覆写 equals）
            var twin = new FakePattern(producer.getDefinition(), slots, producer.getOutputs());
            assertEquals(producer, twin, "夹具前提：两张样板按 equals 必须是同一张（AE2 的样板就长这样）");
            assertTrue(producer != twin, "夹具前提：它们是<b>两个实例</b>——只看名字配不上号的那一型");

            // 消费者：吃 SHINING，配给表给它记一份没销的配额 ⇒ quotaBlind= 才会点名它
            var consumerSlots = new OmniSlot[]{
                    new OmniSlot(new GenericStack[]{new GenericStack(SHINING, 1L)}, 1L)};
            var consumer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), consumerSlots,
                    List.of(new GenericStack(PRODUCT, 1L)));

            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(consumer, 1L);
            times.put(producer, 1L);
            var job = jobWithRation(times, consumer);
            var pool = emptyPool();
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);

            // noCandWhy= 那一格的原文由<b>真取料腿</b>交出（noteNoCandidate 就是生产侧唯一的写入口）
            var probe = new ParallelBatchExtraction.Probe();
            probe.noteNoCandidate(0, producer.getInputs()[0], pool, null, producer, reservation);
            var otherProbe = new ParallelBatchExtraction.Probe();
            otherProbe.noteNoCandidate(0, twin.getInputs()[0], pool, null, twin, reservation);

            var line = renderStallLine(job, pool, null, probe.noCandKey, probe.noCandStored,
                    probe.noCandQuota);
            var noCandTag = firstTag(line.substring(line.indexOf("noCandWhy="),
                    line.indexOf(" stored=")), "noCandWhy=");
            var srcTag = firstTag(line.substring(line.indexOf(" quotaBlind=")), "quotaBlind= 的 src=");
            assertEquals(noCandTag, srcTag,
                    "同一张样板实例的身份标必须在两格里是同一个数，否则归因还是断在这一跳：" + line);

            // 另一张同名实例必须报出<b>不同</b>的身份标（旧日志里两格字面完全相同，正因此配不上号）
            var twinLine = renderStallLine(job, pool, null, otherProbe.noCandKey,
                    otherProbe.noCandStored, otherProbe.noCandQuota);
            var twinTag = firstTag(twinLine.substring(twinLine.indexOf("noCandWhy="),
                    twinLine.indexOf(" stored=")), "noCandWhy=（另一张）");
            assertNotEquals(noCandTag, twinTag,
                    "同名两张的身份标不许相同（相同就是身份标没长在实例上）：" + twinLine);

            // blocked= 的样板头：同一张 producer 必须交回同一个身份标
            job.batchObligations.incur(producer, BigInteger.ONE);
            var ordered = new java.util.ArrayList<Map.Entry<IPatternDetails,
                    ParallelExecutingCraftingJob.TaskProgress>>();
            ordered.add(Map.entry((IPatternDetails) producer, progress(1L)));
            var obligationLine = ParallelCraftingCpuLogic.describeObligationStallLine(
                    java.util.UUID.randomUUID(), BigInteger.valueOf(7L), job, ordered,
                    pool, reservation, probe, 0L);
            var tokens = List.of(obligationLine.split(" "));
            var blockedCell = tokens.get(tokens.size() - 1);
            assertTrue(blockedCell.startsWith("blocked=<"), "最后一格必须是 blocked=：" + obligationLine);
            assertEquals(noCandTag, firstTag(blockedCell, "blocked= 的样板头"),
                    "欠账样板的头与 noCandWhy=/src= 必须是同一张实例：" + obligationLine);
        }

        /**
         * 判据②：样板被退避跳过那一档，{@code backoffBlind=} 印得出 {@code 每发}/{@code 池内}/{@code 网络sim}
         * 三个子字段，整格<b>零空白</b>（带组件键的两个外来空格折成 {@code "_"}）；没样板在退避时不印空壳。
         *
         * <p>改前红在这一格压根不存在：整行里找不出 {@code backoffBlind=} ⇒ 取 token 直接红。
         */
        void backoffBlindPrintsTheSkippedPatternsOwnFailingCell() {
            var slots = new OmniSlot[]{new OmniSlot(new GenericStack[]{new GenericStack(WORN, 4L)}, 1L)};
            var skipped = new FakePattern(AEItemKey.of(Items.BARREL), slots,
                    List.of(new GenericStack(PRODUCT, 1L)));
            var job = newJob();
            job.tasks.put(skipped, progress(5L));
            // 与 recordMaterialBackoff 同一写法：退避在册 + 当初欠的键（全部候选键）
            job.materialBackoffUntil.put(skipped, 1_000L);
            job.materialBackoffMissing.put(skipped, new java.util.HashSet<>(Set.of(WORN)));
            var pool = emptyPool(); // 池内 0 枚：这一格真付不出每发 4 枚

            var line = renderStallLine(job, pool, gridHolding(WORN, 7L), "", 0L, -2L);
            var cell = firstTokenOf(line, "backoffBlind=");
            assertTrue(cell.contains(",每发=4"), "每发要几枚：与 blocked= 同一道口（SlotAccount.unitsPerFiringExact）："
                    + cell);
            assertTrue(cell.contains(",池内=0"), "池里还剩几枚：WideCraftingInventory.amount 那一道口：" + cell);
            assertTrue(cell.contains(",网络sim=7"), "网络自报能交出几枚（至多一个 long，下界读）：" + cell);
            assertTrue(cell.contains("#slot0," + oneToken(WORN) + ","),
                    "欠的那一枚键要点了名，且第三方空格在格出口折成 '_';：" + cell);
            assertTrue(cell.contains("#i"), "这一格要能对上 noCandWhy=/src= 点的那一张：" + cell);
            assertFalse(cell.chars().anyMatch(Character::isWhitespace),
                    "一格一 token：整格不许含任何空白：" + cell);

            // 反向那一档：没有样板在退避 ⇒ 不许印空壳
            var clean = newJob();
            clean.tasks.put(skipped, progress(5L));
            var cleanCell = firstTokenOf(renderStallLine(clean, pool, null, "", 0L, -2L), "backoffBlind=");
            assertEquals("backoffBlind=<none>", cleanCell,
                    "没被跳过就是 <none>，不许摆一个 [] 的空壳；网格不在场那一档照常点名：" + cleanCell);
        }

        /** 走真渲染口交整行（生产侧 {@code AELog.info("%s", …)} 打的就是这一个串）。 */
        private static String renderStallLine(ParallelExecutingCraftingJob job,
                ListCraftingInventory pool, @Nullable IGrid grid, String noCandKey,
                long noCandStored, long noCandQuota) {
            var stallProbe = new ParallelCraftingLaneState(null, java.util.UUID.randomUUID(), 10L)
                    .logic().new StallProbe();
            return stallProbe.describeStallLine(java.util.UUID.randomUUID(), 1, job, pool, grid,
                    "…", noCandKey, noCandStored, noCandQuota);
        }

        /** 取整行里第一个 {@code #i<hex>}：取不出即红（这一格压根没有身份标）。 */
        private static String firstTag(String segment, String whichCell) {
            var matcher = IDENTITY_TAG.matcher(segment);
            assertTrue(matcher.find(), whichCell + " 里必须带样板身份标 #i<hex>：" + segment);
            return matcher.group(1);
        }

        /** 取整行里以 {@code marker} 开头的那一格（整格零空白才切得出来）。 */
        private static String firstTokenOf(String line, String marker) {
            for (var token : line.split(" ")) {
                if (token.startsWith(marker)) {
                    return token;
                }
            }
            throw new AssertionError("整行里没有以 " + marker + " 开头的格：" + line);
        }

        /** 日志出口那把尺：toString 里的空白折成 {@code "_"}（与格出口同一把）。 */
        private static String oneToken(Object key) {
            return String.valueOf(key).replace(' ', '_');
        }

        private static AEItemKey worn(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        private static WideCraftingInventory emptyPool() {
            return new WideCraftingInventory(ignored -> {
            });
        }

        private static ParallelExecutingCraftingJob newJob() {
            var base = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            return new ParallelExecutingCraftingJob(base, ignored -> {
            }, (CraftingLink) null, null);
        }

        /** 提交期配给表（真路：{@code adoptPlannedMaterialPlan}）：消费者在 SHINING 上留一份没销的配额。 */
        private static ParallelExecutingCraftingJob jobWithRation(Map<IPatternDetails, Long> times,
                IPatternDetails consumer) {
            var share = new WideKeyCounter();
            share.add(SHINING, SESSION_QUOTA);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            return job;
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 只问 {@code getStorageService().getInventory().extract(…, SIMULATE, …)} 那一张嘴的网络桩。 */
        private static IGrid gridHolding(AEKey what, long total) {
            var held = new HoldingNetwork(what, total);
            return git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder()
                    .liveStorage(held).build();
        }


        /** 持固定枚数的网络桩：SIMULATE 恒交回同一份现量（不把料抽走）。 */
        private static final class HoldingNetwork implements MEStorage {
            private final Map<AEKey, Long> held = new java.util.HashMap<>();

            private HoldingNetwork(AEKey what, long total) {
                held.put(what, total);
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
                var remaining = held.get(key);
                return remaining == null ? 0L : Math.min(remaining, amount);
            }

            @Override
            public Component getDescription() {
                return Component.literal("holding-network");
            }
        }

        /** 万象／模糊样板那一型：候选表写死，{@code isValid} 按 {@code equals} 认。 */
        private record OmniSlot(GenericStack[] candidates, long multiplier)
                implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, Level level) {
                for (var candidate : candidates) {
                    if (candidate.what().equals(input)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        private record FakePattern(AEItemKey definition, OmniSlot[] slots,
                List<GenericStack> outputs) implements IPatternDetails {

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
                return outputs;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelPatternIdentityAndBackoffBlindCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPatternIdentityAndBackoffBlindCases.theSamePatternCarriesOneIdentityTagAcrossNoCandWhyAndQuotaBlindSrc", () -> new ParallelPatternIdentityAndBackoffBlindCases().theSamePatternCarriesOneIdentityTagAcrossNoCandWhyAndQuotaBlindSrc()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPatternIdentityAndBackoffBlindCases.backoffBlindPrintsTheSkippedPatternsOwnFailingCell", () -> new ParallelPatternIdentityAndBackoffBlindCases().backoffBlindPrintsTheSkippedPatternsOwnFailingCell())
            );
        }
    }

    /** ParallelPlanBalanceOrderingCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelPlanBalanceOrderingCases {
        /** 玻璃只够一份：谁排在前面谁就拿走它，后面那一号进 {@code hole=}。 */
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
        /** 插入序第一位，definition 字符串序<b>第二</b>位（{@code item.minecraft.stone} > {@code item.minecraft.anvil}）。 */
        private static final AEItemKey DEF_FIRST_INSERTED = AEItemKey.of(Items.STONE);
        /** 插入序第二位，definition 字符串序<b>第一</b>位。 */
        private static final AEItemKey DEF_FIRST_BY_STRING = AEItemKey.of(Items.ANVIL);

        /**
         * 报告顺位 == 推送顺位：两份料只够一份时，被点名没盖住的那一个必须是<b>推送顺位次位</b>。
         *
         * <p>独立手算表（不经被测排序）：
         * <table border="1">
         * <caption>#134：插入序与 definition 序相反的一对样板</caption>
         * <tr><th>样板</th><th>插入序</th><th>definition 字符串序</th><th>每发吃玻璃</th><th>发数</th>
         *     <th>排水（池子只有 1 枚）</th></tr>
         * <tr><td>石（{@code item.minecraft.stone}）</td><td>第 1</td><td>第 2</td><td>1</td><td>1</td>
         *     <td>盖住：配给 1，不进 hole</td></tr>
         * <tr><td>铁砧（{@code item.minecraft.anvil}）</td><td>第 2</td><td>第 1</td><td>1</td><td>1</td>
         *     <td>没盖住：hole=1，want= 点名它</td></tr>
         * </table>
         * 改前的 definition 预排把两行的次序整个翻过来 ⇒ {@code want=} 点名的是石——正是这一条要咬住的形状。
         */
        void planBalanceNamesTheConsumerThatThePushOrderPutsSecond() {
            var firstInserted = new OrderingPattern(DEF_FIRST_INSERTED);
            var firstByString = new OrderingPattern(DEF_FIRST_BY_STRING);
            // 夹具前提一：两个序真的反向摆着（不反向就没有并列项可分辨，本用例就空转）
            assertTrue(String.valueOf(DEF_FIRST_INSERTED).compareTo(String.valueOf(DEF_FIRST_BY_STRING)) > 0,
                    "夹具必须让插入序与 definition 字符串序相反，否则这条判据无从落地");
            var plan = planWith(firstInserted, firstByString);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            // 夹具前提二：任务表的插入序就是 [石, 铁砧]，且谁都不是环成员 ⇒ 推送顺位＝插入顺位
            assertEquals(List.of(DEF_FIRST_INSERTED, DEF_FIRST_BY_STRING),
                    job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList(),
                    "任务表插入序没立住：本用例读的就是这一份序");
            var pushOrder = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
            assertEquals(List.of(DEF_FIRST_INSERTED, DEF_FIRST_BY_STRING),
                    pushOrder.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                    "推送顺位＝插入顺位（两份都不是环成员，那把尺不动并列项）");

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            assertTrue(line.contains(" src=submit"),
                    () -> "读的是自算那一臂（排水按 orderedTasks 走）：" + line);
            assertTrue(line.contains(" want=[" + consumerLabel(firstByString) + "="),
                    () -> "报告点名的该是推送顺位次位（铁砧）——池子只有一份料，排在后面的那个没被盖住。"
                            + "整行=" + line);
            assertFalse(line.contains(consumerLabel(firstInserted)),
                    () -> "推送顺位首位（石）拿到了那一份料，不许被报成缺口；报出它＝报告顺位与推送顺位反了。"
                            + "整行=" + line);
            assertTrue(line.contains(" hole=1 "),
                    () -> "没盖住的就是那 1 枚（每发 1 × 1 发）：" + line);
        }

        /** 重复日志不复加自产、配给或已吃账；同一个 reservation 始终读当前消费量。 */
        void repeatedSubmitReadoutKeepsEveryMaterialBookUnchanged() {
            var pattern = new OrderingPattern(DEF_FIRST_INSERTED);
            var plan = planWith(pattern, new OrderingPattern(DEF_FIRST_BY_STRING));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            job.addPlanMaterialTaken(pattern, GLASS, 1L);
            var emit = job.accountPlannedEmit.getExact(CELL);
            var ration = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS);
            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getExact(GLASS);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            var first = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertEquals(first, ParallelCraftingCpuLogic.describePlanBalance(job, plan));
            assertEquals(emit, job.accountPlannedEmit.getExact(CELL));
            assertEquals(ration, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS));
            assertEquals(eaten, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getExact(GLASS));
            assertEquals(0L, reservation.remainingQuota(pattern, GLASS));
        }

        /** 未初始化的作业读日志也不得建账；提交建账不依赖任何日志调用。 */
        void diagnosticsDoNotInitializeMaterials() {
            var plan = planWith(new OrderingPattern(DEF_FIRST_INSERTED),
                    new OrderingPattern(DEF_FIRST_BY_STRING));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertTrue(job.accountPlannedEmit.isEmpty());
            assertTrue(MaterialAllocationFixtures.assignedSnapshot(job).isEmpty());
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            assertEquals(java.math.BigInteger.valueOf(2L), job.accountPlannedEmit.getExact(CELL));
            assertEquals(2, MaterialAllocationFixtures.assignedSnapshot(job).size());
        }

        /** 计划配给短交后收窄到零；再次打印不得重新采用计划并抹掉收窄结果。 */
        void adoptedRationIsNotRestoredByDiagnosticsAfterShortArrival() {
            var pattern = new OrderingPattern(DEF_FIRST_INSERTED);
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(pattern, 1L);
            var assigned = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            assigned.add(GLASS, 10L);
            var envelope = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            envelope.add(GLASS, 3L);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                    base, new KeyCounter(), new KeyCounter(), 1L, Map.of(), Map.of(pattern, assigned),
                    null, null, java.util.Set.of(), Map.of(pattern, envelope));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            job.narrowPlanMaterialShareByArrival(GLASS,
                    git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO,
                    git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO);
            var emit = job.accountPlannedEmit.getExact(CELL);
            var first = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertEquals(first, ParallelCraftingCpuLogic.describePlanBalance(job, plan));
            assertEquals(java.math.BigInteger.ZERO, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS));
            assertEquals(java.math.BigInteger.valueOf(3L),
                    MaterialAllocationFixtures.envelopeSnapshot(job).get(pattern).getExact(GLASS));
            assertEquals(emit, job.accountPlannedEmit.getExact(CELL));
            assertEquals(java.math.BigInteger.TEN, assigned.getExact(GLASS));
        }

        /** 自产两倍 long 上限可完整支付下游两发；显示饱和不得参与排水。 */
        void submitAllocationUsesExactSelfProductionBeyondLong() {
            var producer = new ProductionPattern(DEF_FIRST_INSERTED, GLASS, 1L, CELL, Long.MAX_VALUE);
            var consumer = new ProductionPattern(DEF_FIRST_BY_STRING, CELL, Long.MAX_VALUE,
                    AEItemKey.of(Items.COMPASS), 1L);
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(producer, 2L);
            times.put(consumer, 2L);
            var used = new KeyCounter();
            used.add(GLASS, 2L);
            var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.COMPASS), 2L),
                    0L, true, false, used, new KeyCounter(), new KeyCounter(), times);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var exact = java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO);
            assertEquals(exact, job.accountPlannedEmit.getExact(CELL));
            assertEquals(exact, MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getExact(CELL));
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertFalse(line.contains(" hole="), () -> line);
            assertEquals(exact, job.accountPlannedEmit.getExact(CELL));
        }

        private record ProductionPattern(AEItemKey definition, AEKey input, long units,
                AEKey output, long amount) implements IPatternDetails {
            @Override
            public AEItemKey getDefinition() { return definition; }
            @Override
            public IInput[] getInputs() {
                return new IInput[] {new IInput() {
                    @Override
                    public GenericStack[] getPossibleInputs() {
                        return new GenericStack[] {new GenericStack(input, units)};
                    }
                    @Override
                    public long getMultiplier() { return 1L; }
                    @Override
                    public boolean isValid(AEKey key, net.minecraft.world.level.Level level) {
                        return input.equals(key);
                    }
                    @Override
                    public @Nullable AEKey getRemainingKey(AEKey key) { return null; }
                }};
            }
            @Override
            public List<GenericStack> getOutputs() { return List.of(new GenericStack(output, amount)); }
        }

        /** 消费者标签与 {@code describeConsumer} 同一形状：样板身份取 definition，不参与任何排序算式。 */
        private static String consumerLabel(IPatternDetails pattern) {
            return pattern.getDefinition() + "#" + pattern.getClass().getSimpleName() + " unit=1x1";
        }

        /** 两份样板各 1 发、各吃 1 枚玻璃；{@code usedItems} 只给得出 1 枚 ⇒ 顺位次位必欠。 */
        private static CraftingPlan planWith(IPatternDetails first, IPatternDetails second) {
            var times = new LinkedHashMap<IPatternDetails, Long>();
            times.put(first, 1L);
            times.put(second, 1L);
            var used = new KeyCounter();
            used.add(GLASS, 1L);
            return new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    used, new KeyCounter(), new KeyCounter(), times);
        }

        /** 单格单候选（玻璃 1 枚）、产出一枚箱子的样板；不在任何环里 ⇒ 环优先那把尺不动它的序。 */
        private record OrderingPattern(AEItemKey definition) implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new OrderingInput(GLASS)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(CELL, 1L));
            }
        }

        private record OrderingInput(AEKey candidate) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(candidate, 1L)};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return candidate.equals(input);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases.planBalanceNamesTheConsumerThatThePushOrderPutsSecond", () -> new ParallelPlanBalanceOrderingCases().planBalanceNamesTheConsumerThatThePushOrderPutsSecond()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases.repeatedSubmitReadoutKeepsEveryMaterialBookUnchanged", () -> new ParallelPlanBalanceOrderingCases().repeatedSubmitReadoutKeepsEveryMaterialBookUnchanged()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases.diagnosticsDoNotInitializeMaterials", () -> new ParallelPlanBalanceOrderingCases().diagnosticsDoNotInitializeMaterials()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases.adoptedRationIsNotRestoredByDiagnosticsAfterShortArrival", () -> new ParallelPlanBalanceOrderingCases().adoptedRationIsNotRestoredByDiagnosticsAfterShortArrival()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelPlanBalanceOrderingCases.submitAllocationUsesExactSelfProductionBeyondLong", () -> new ParallelPlanBalanceOrderingCases().submitAllocationUsesExactSelfProductionBeyondLong())
            );
        }
    }

    /** ParallelRationTableOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelRationTableOrderCases {
        /** 摊派这一族的键（24 张样板共用同一族，份额各 10 枚）。 */
        private static final AEKey FAMILY_KEY = AEItemKey.of(Items.IRON_INGOT);
        /** 与配给键无关的产物键：让 {@code describePlanBalance} 的供给腿盖不住那 24 个洞。 */
        private static final AEKey OUTPUT = AEItemKey.of(Items.NETHER_STAR);
        /** 交回任务表的份数。 */
        private static final long FIRINGS = 1L;
        /** 每张样板在这一族上的配给份额（枚）。 */
        private static final long SHARE_PER_PATTERN = 10L;
        /** 24 张各要 10 枚，本轮到账只够 12 张——夹哪 12 张成 0，由顺位说。 */
        private static final long ARRIVED_UNITS = 120L;
        /** 摊满的那几张（前 12 号）。 */
        private static final int FULLY_FUNDED = 12;

        /**
         * 24 个互不相等的样板本体（{@code equals} 取 definition ⇒ 同一 item 的两张实例相等）。
         * 取 24 只而不取三五只：{@code IdentityHashMap} 只有撞上同一个桶才让插入顺序影响遍历序，
         * 24 条目摊 64 槽的期望碰撞数是 4 次以上，反序插入必然给出另一份遍历序（判据才有牙）。
         */
        private static final List<AEItemKey> DEFINITIONS = List.of(
                AEItemKey.of(Items.DIRT), AEItemKey.of(Items.SAND), AEItemKey.of(Items.GRAVEL),
                AEItemKey.of(Items.CLAY), AEItemKey.of(Items.CLAY_BALL), AEItemKey.of(Items.STONE),
                AEItemKey.of(Items.COBBLESTONE), AEItemKey.of(Items.MOSSY_COBBLESTONE),
                AEItemKey.of(Items.OBSIDIAN), AEItemKey.of(Items.NETHERRACK),
                AEItemKey.of(Items.SOUL_SAND), AEItemKey.of(Items.SOUL_SOIL),
                AEItemKey.of(Items.END_STONE), AEItemKey.of(Items.PRISMARINE_SHARD),
                AEItemKey.of(Items.PRISMARINE_CRYSTALS), AEItemKey.of(Items.RED_SAND),
                AEItemKey.of(Items.TERRACOTTA), AEItemKey.of(Items.BRICK),
                AEItemKey.of(Items.PAPER), AEItemKey.of(Items.FEATHER),
                AEItemKey.of(Items.FLINT), AEItemKey.of(Items.STRING),
                AEItemKey.of(Items.SLIME_BALL), AEItemKey.of(Items.GUNPOWDER));

        /**
         * 第 1 条（进数字）：族到账摊派的<b>尾循环</b>顺位＝它那一号任务表条目的顺位，
         * 不再是 {@code IdentityHashMap} 的遍历序。
         *
         * <p>配给表 24 张全部按<b>另一实例</b>登记（闸门那两把键那一型），任务表上那张身份都认不到 ⇒
         * 名单整个落在尾循环里。本轮到账只够 12 张 ⇒ "摊满的那 12 张是谁"就是这一刀的读数。
         */
        void rationTailKeepsItsTaskRankAcrossInsertionOrders() {
            var first = rationBuild(false);
            var second = rationBuild(true);

            assertEquals(DEFINITIONS, first.taskOrder(),
                    "夹具前提：任务表底序＝规划侧注册序，那一把尺不动并列项");
            assertEquals(DEFINITIONS.subList(0, FULLY_FUNDED), first.fundedDefinitions(),
                    () -> "摊派顺位＝任务表那把尺：前 12 张摊满、后 12 张夹成 0。实际摊满的那批="
                            + first.fundedDefinitions());
            assertEquals(first.fundedDefinitions(), second.fundedDefinitions(),
                    "同一份台账只换身份表的插入顺序 ⇒ 摊到份额的那批与先后次序都不许变");
            assertEquals(first.shareVector(), second.shareVector(),
                    "逐张份额读数随身份表插入序变＝摊派还在吃 System.identityHashCode 的序");
        }

        /**
         * 第 2 条（进点名）：{@code planBalance=} 里并列 {@code hole} 的点名次序＝推送底序，
         * 而不是 {@code sumAssignedByKey} 那本身份表的遍历序。
         *
         * <p>走 {@code src=plan}（照单采用）那一臂：24 张各配 10 枚、执行侧供给盖不住 ⇒ 24 个并列洞，
         * {@code ranked} 只按洞数值排一把尺（稳定排序），并列项的先后就是 {@code holes} 的插入序，
         * 而 {@code holes} 的插入序由 {@code sumAssignedByKey → need} 的回读序交回。
         */
        void tiedHolesAreNamedInTheRationTableTaskRankAcrossInsertionOrders() {
            var first = balanceBuild(false);
            var second = balanceBuild(true);

            assertEquals(DEFINITIONS.stream().limit(8).map(String::valueOf).toList(),
                    first.printedHoleKeys(),
                    () -> "前 8 点名＝推送底序的头 8 个键（一把尺都不加）。实际点名="
                            + first.printedHoleKeys() + " 整行=" + first.line());
            assertEquals(first.line(), second.line(),
                    "换一种身份表插入顺序就换一份 planBalance 读数＝还在吃哈希序：" + second.line());
        }

        // ---- 一次构造：摊派臂 ----

        /** 一次摊派构造的读数：任务表序、逐张份额（按任务表顺位摆）、摊满的那批。 */
        private record RationBuild(List<AEItemKey> taskOrder, List<Long> shareVector,
                                   List<AEItemKey> fundedDefinitions) {
        }

        /**
         * @param reversedRationInsert 假＝按任务表顺位把 24 张插进身份表，真＝反着插。
         *                             两种构造用的是<b>同一批实例</b>，只有插入序不同。
         */
        private static RationBuild rationBuild(boolean reversedRationInsert) {
            var tasks = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
            var twins = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
            for (var definition : DEFINITIONS) {
                tasks.add(new EqualByDefinitionPattern(definition, FAMILY_KEY));
                twins.add(new EqualByDefinitionPattern(definition, FAMILY_KEY));
            }
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (var task : tasks) {
                times.put(task, FIRINGS);
            }
            var plan = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            // 配给表按<b>另一实例</>登记：任务表上的那张在身份表里认不到，名单整段落进尾循环
            for (int index : insertionIndexes(reversedRationInsert)) {
                var assigned = new WideKeyCounter();
                assigned.add(FAMILY_KEY, SHARE_PER_PATTERN);
                MaterialAllocationFixtures.assign(job, twins.get(index), assigned);
            }

            job.narrowPlanMaterialShareByArrival(
                    FAMILY_KEY, PlanAmount.of(ARRIVED_UNITS), PlanAmount.ZERO);

            var shares = new ArrayList<Long>(DEFINITIONS.size());
            var funded = new ArrayList<AEItemKey>();
            for (int index = 0; index < DEFINITIONS.size(); index++) {
                long left = MaterialAllocationFixtures.assignedSnapshot(job).get(twins.get(index)).getLong(FAMILY_KEY);
                shares.add(left);
                if (left >= SHARE_PER_PATTERN) {
                    funded.add(DEFINITIONS.get(index));
                }
            }
            var taskOrder = job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList();
            return new RationBuild(List.copyOf(taskOrder), List.copyOf(shares), List.copyOf(funded));
        }

        // ---- 一次构造：提交边界读数臂 ----

        /** 一次 {@code describePlanBalance} 构造的读数：整行与按出现次序点名的洞键。 */
        private record BalanceBuild(String line, List<String> printedHoleKeys) {
        }

        private static BalanceBuild balanceBuild(boolean reversedRationInsert) {
            var tasks = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
            var twins = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
            for (var definition : DEFINITIONS) {
                tasks.add(new EqualByDefinitionPattern(definition, definition));
                twins.add(new EqualByDefinitionPattern(definition, definition));
            }
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (var task : tasks) {
                times.put(task, FIRINGS);
            }
            var base = new CraftingPlan(new GenericStack(OUTPUT, FIRINGS), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            for (int index : insertionIndexes(reversedRationInsert)) {
                var ration = new WideKeyCounter();
                // 每张样板配给自己那号键 10 枚，而执行侧供给里没有这一号键 ⇒ 24 个并列洞
                ration.add(DEFINITIONS.get(index), SHARE_PER_PATTERN);
                assigned.put(twins.get(index), ration);
            }
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                    Map.of(), assigned, null, null, Set.of(), new IdentityHashMap<>());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertTrue(line.contains(" src=plan"),
                    () -> "读的是照单采用那一臂（自算臂压根不吃这本身份表）：" + line);
            var printed = new ArrayList<String>();
            var matcher = java.util.regex.Pattern.compile(" (minecraft:[a-z_]+) hole=").matcher(line);
            while (matcher.find()) {
                printed.add(matcher.group(1));
            }
            return new BalanceBuild(line, List.copyOf(printed));
        }

        /** 假＝按任务表顺位插入身份表；真＝反着插（同一批实例，只换插入序）。 */
        private static List<Integer> insertionIndexes(boolean reversed) {
            var indexes = new ArrayList<Integer>(DEFINITIONS.size());
            for (int index = 0; index < DEFINITIONS.size(); index++) {
                indexes.add(index);
            }
            if (reversed) {
                Collections.reverse(indexes);
            }
            return indexes;
        }

        /** 按 definition 定身位的样板替身（与 AE2 四条样板实现同形）。 */
        private static final class EqualByDefinitionPattern implements IPatternDetails {

            private final AEItemKey definition;
            private final AEKey input;

            private EqualByDefinitionPattern(AEItemKey definition, AEKey input) {
                this.definition = definition;
                this.input = input;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new SingleCandidateSlot(input)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(OUTPUT, 1L));
            }

            @Override
            public boolean equals(Object obj) {
                return obj != null && obj.getClass() == getClass()
                        && ((EqualByDefinitionPattern) obj).definition.equals(definition);
            }

            @Override
            public int hashCode() {
                return definition.hashCode();
            }
        }

        /** 单候选槽：候选就是那一号键，一发一枚。 */
        private record SingleCandidateSlot(AEKey candidate) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(candidate, 1L)};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
                return candidate.equals(input);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelRationTableOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRationTableOrderCases.rationTailKeepsItsTaskRankAcrossInsertionOrders", () -> new ParallelRationTableOrderCases().rationTailKeepsItsTaskRankAcrossInsertionOrders()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRationTableOrderCases.tiedHolesAreNamedInTheRationTableTaskRankAcrossInsertionOrders", () -> new ParallelRationTableOrderCases().tiedHolesAreNamedInTheRationTableTaskRankAcrossInsertionOrders())
            );
        }
    }

    /** ParallelRingSeedReserveAtExtractionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelRingSeedReserveAtExtractionCases {
        void wideBatchSubtractsRingReserveFromExactStockBeforeDividingMaterialDemand() {
            for (long units : new long[]{4L, 100L}) {
                for (long quotaDivisor : new long[]{1L, 2L}) {
                    BigInteger stock = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(100));
                    var consumer = new FakePattern(AEItemKey.of(Items.FURNACE),
                            new FakeInput[]{new FakeInput(new GenericStack[]{new GenericStack(SEED, units)},
                                    1, null, Set.of(SEED))}, List.of(new GenericStack(OUTSIDE_PRODUCT, 1)));
                    var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {}, (CraftingLink) null, null);
                    job.tasks.put(RING_DOUBLER, progress(5));
                    var remaining = new ParallelExecutingCraftingJob.TaskProgress();
                    remaining.addFirings(PlanAmount.of(stock));
                    job.tasks.put(consumer, remaining);
                    BigInteger allocated = stock.divide(BigInteger.valueOf(quotaDivisor));
                    var quota = new WideKeyCounter(); quota.add(SEED, allocated);
                    MaterialAllocationFixtures.assign(job, consumer, quota);
                    var pool = new WideCraftingInventory(ignored -> {});
                    pool.insertExact(SEED, stock, Actionable.MODULATE);
                    var reservation = reservationOf(job, Map.of(SEED, Long.MAX_VALUE));
                    BigInteger expected = stock.subtract(BigInteger.valueOf(5)).min(allocated)
                            .divide(BigInteger.valueOf(units));
                    assertEquals(expected, ParallelBatchExtraction.declaredPayableCeiling(
                            consumer, pool, null, reservation, stock));
                    var batch = ParallelBatchExtraction.payDeclaredObligation(consumer, pool, null,
                            Long.MAX_VALUE, reservation, null);
                    assertNotNull(batch);
                    long paidFirings = expected.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
                    assertEquals(paidFirings, batch.executions());
                    assertEquals(stock.subtract(BigInteger.valueOf(paidFirings).multiply(BigInteger.valueOf(units))),
                            pool.amount(SEED));
                }
            }
        }

        /** 环种：增殖样板每发吃 1 枚、产 2 枚（净 +1），旁人吃掉就不回来。 */
        private static final AEItemKey SEED = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        /** 旁人的产物：不回种、也不喂给环上的任何待办 producer。 */
        private static final AEItemKey OUTSIDE_PRODUCT = AEItemKey.of(Items.BARREL);

        /** 环成员：一发吃 1 枚种、产 2 枚种。 */
        private static final FakePattern RING_DOUBLER = new FakePattern(
                AEItemKey.of(Items.SMITHING_TABLE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(SEED, 2L)));

        /** 外面吃的：一发吃 1 枚种、产别的键，种键上有配给（本 bug 的形状：配给走早退那条）。 */
        private static final FakePattern OUTSIDE_CONSUMER = new FakePattern(
                AEItemKey.of(Items.ANVIL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

        /** 旁人配给里的份额：远大于池里那一枚，为的是把"夹到 0"这件事钉在留底那一格、不是配额用尽。 */
        private static final long OUTSIDE_QUOTA = 479994L;
        /** 环成员自己的配给份额（实机两腿都在配给表里）。 */
        private static final long RING_QUOTA = 959986L;
        /** 增殖腿还没跑的发数：留底按"剩余发数 × 每发吃掉几枚"记。 */
        private static final long RING_REMAINING_FIRINGS = 5L;

        // ---------- 链式环那一档：A 吃 K 出 L、B 吃 L 出 K，没有任何样板把物料还给自己 ----------

        /** 链上的中转那枚 L：只有 A 产它、只有 B 吃它。 */
        private static final AEItemKey LINK = AEItemKey.of(Items.REDSTONE);

        /** 链式环 A：每发吃 1 枚种 K、产 1 枚中转 L。它自己不还 K——旧口径 owed(K)=0 就死在这一格。 */
        private static final FakePattern CHAIN_EMITTER = new FakePattern(
                AEItemKey.of(Items.REPEATER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(LINK, 1L)));

        /** 链式环 B：每发吃 1 枚中转 L、产回 1 枚种 K（{@code inputAmount(B, K) = 0} 的那一张）。 */
        private static final FakePattern CHAIN_REFUNDER = new FakePattern(
                AEItemKey.of(Items.COMPARATOR),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(LINK, 1L)}, 1L, null, Set.of(LINK))},
                List.of(new GenericStack(SEED, 1L)));

        /** 链两侧各欠 2 发 ⇒ 分量对 K 的剩余需求 = 2 × 1 = 2 枚，比池里那 1 枚多。 */
        private static final long CHAIN_EMITTER_FIRINGS = 2L;
        private static final long CHAIN_REFUNDER_FIRINGS = 2L;
        private static final long CHAIN_OUTSIDE_FIRINGS = 3L;

        // ---------- 宽域那一档：> long 的订单也要守种，净吃种那一型照样在册 ----------

        /** 越出 {@code long} 的剩余发数（2^64）：用户要的那一档"真正的无上限"落在这一量级上。 */
        private static final BigInteger OVER_LONG_FIRINGS = new BigInteger("18446744073709551616");

        /** 净吃种那一腿：一发吃 2 枚种、只还 1 枚。它欠的发数越大，这一环净吃掉的那一枚越多。 */
        private static final FakePattern NET_CONSUMER = new FakePattern(
                AEItemKey.of(Items.BREWING_STAND),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 2L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(SEED, 1L)));

        /** 补种那一腿：一发吃 1 枚种、还 3 枚。发数悬殊时它补不上 {@link #NET_CONSUMER} 那份净消耗。 */
        private static final FakePattern OVER_REFUNDER = new FakePattern(
                AEItemKey.of(Items.CAULDRON),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(SEED, 3L)));

        /** {@code long} 域顶点那一档用的腿：一发吃 2 枚还 2 枚（净 0），留底靠发数被推到 {@code 2×long} 域顶点。 */
        private static final FakePattern SEED_KEEPER = new FakePattern(
                AEItemKey.of(Items.DROPPER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 2L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(SEED, 2L)));

        // ---------- 义务那一档：申报把剩余发数扣干之后，还没交割的那 k-1 发仍是这一张要吃掉的量 ----------

        /** 欠账那一张的产物：谁也不吃它，免得把旁人 {@link #OUTSIDE_CONSUMER} 也拽进同一个分量。 */
        private static final AEItemKey DEBT_PRODUCT = AEItemKey.of(Items.HOPPER);

        /**
         * 欠账那一张：一发吃 1 枚种、还 1 枚种（自己就是种键那一环的一环）。
         * 申报那一刻 {@code consumeFirings} 把它的剩余发数扣成 0，义务账上还挂着 {@code k-1} 发的材料要从池里取。
         */
        private static final FakePattern DECLARED_DEBTOR = new FakePattern(
                AEItemKey.of(Items.SMOKER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(SEED, 1L), new GenericStack(DEBT_PRODUCT, 1L)));

        /** 申报了 4 发、原型只结清 1 发 ⇒ 义务账上挂着 3 发没交割。 */
        private static final long DECLARED_OBLIGATION_FIRINGS = 3L;

        // ---------- 同族两个精确键那一档：族是查表口径、记账与豁免按精确键各自的分量交 ----------

        /**
         * 同一件物品的两个<b>精确</b>变体（带组件）：两枚是 {@code AEKey} 上两个不同的键，
         * {@code getPrimaryKey()} 上是同一个物品本体族——正是"族格上只留下 max、另一枚不记账"那一档的形状。
         */
        private static final AEItemKey TWIN_VARIANT_A = namedVariant(Items.IRON_INGOT, "twin-seed-a");
        private static final AEItemKey TWIN_VARIANT_B = namedVariant(Items.IRON_INGOT, "twin-seed-b");

        /**
         * 一发同时吃同族<b>两枚</b>精确键各 1 枚、各还 1 枚：两枚各自都被这张还在图里的样板吃掉，
         * 各自在同一个分量里。族留底必须是两枚之和（{@code 2 × 发数}），取最大就少算另一枚那一份。
         */
        private static final FakePattern TWIN_SEED_DOUPLER = new FakePattern(
                AEItemKey.of(Items.LOOM),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)},
                                1L, null, Set.of(TWIN_VARIANT_A)),
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)},
                                1L, null, Set.of(TWIN_VARIANT_B))},
                List.of(new GenericStack(TWIN_VARIANT_A, 1L), new GenericStack(TWIN_VARIANT_B, 1L)));

        /** 这一档的旁人：一发吃 1 枚 {@link #TWIN_VARIANT_A}，种键上有充足配给（走配给早退那一档的形状）。 */
        private static final FakePattern TWIN_OUTSIDE_CONSUMER = new FakePattern(
                AEItemKey.of(Items.GRINDSTONE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)}, 1L, null,
                        Set.of(TWIN_VARIANT_A))},
                List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

        /** 同族两枚精确键各欠的发数：族留底合计 {@code 2 × (1 + 1) = 4} 枚，旧口径只留下 {@code 2} 枚。 */
        private static final long TWIN_DOUPLER_FIRINGS = 2L;
        private static final long TWIN_OUTSIDE_FIRINGS = 3L;
        /** 这一档的池量：3 枚 &lt; 4 枚族留底 ⇒ 旁人对种键的可花量是 0；旧口径交出 3−2=1。 */
        private static final long TWIN_POOL_AMOUNT = 3L;

        // ---------- 同族两个分量那一档：豁免名单按分量交，A 的成员不免扣 B 那份口粮 ----------

        /**
         * 分量 A 那一张：一发吃 1 枚 {@link #TWIN_VARIANT_A}（还 2 枚，净正在 A 上）<b>又吃 1 枚 B</b>。
         * 它吃 B 的那一格落在 B 那个分量之外 ⇒ 对 B 的账不计，可它照样是把 B 往外抽的那一张。
         */
        private static final FakePattern TWIN_COMPONENT_A = new FakePattern(
                AEItemKey.of(Items.STONECUTTER),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)},
                                1L, null, Set.of(TWIN_VARIANT_A)),
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)},
                                1L, null, Set.of(TWIN_VARIANT_B))},
                List.of(new GenericStack(TWIN_VARIANT_A, 2L)));

        /** 分量 B 那一张：一发吃 1 枚 B、还 1 枚 B（自己就是 B 那一环的一环，与 A 那一张不成环）。 */
        private static final FakePattern TWIN_COMPONENT_B = new FakePattern(
                AEItemKey.of(Items.CARTOGRAPHY_TABLE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)}, 1L, null,
                        Set.of(TWIN_VARIANT_B))},
                List.of(new GenericStack(TWIN_VARIANT_B, 1L)));

        /** A 分量欠 2 发（族留底 2 枚）、B 分量欠 1 发（族留底 1 枚）：两侧都不是零，互相都该扣到对方那一份。 */
        private static final long COMPONENT_A_FIRINGS = 2L;
        private static final long COMPONENT_B_FIRINGS = 1L;

        // ---------- 折发数那条腿上的三处：族合计贴亏空、读池没过池账、无配额那一档漏夹 ----------

        /**
         * 一发各吃同族<b>两枚</b>键一枚的旁人：A 那一枚才是环欠着的种，B 那一枚是同族的富余变体。
         * 族合计那道除法拿 B 的可花量去补 A 的亏空，就是这一档的用力点。
         */
        private static final FakePattern TWIN_OUTSIDE_BOTH_KEYS = new FakePattern(
                AEItemKey.of(Items.FLETCHING_TABLE),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_B, 1L)}, 1L, null,
                                Set.of(TWIN_VARIANT_B)),
                        new FakeInput(new GenericStack[]{new GenericStack(TWIN_VARIANT_A, 1L)}, 1L, null,
                                Set.of(TWIN_VARIANT_A))},
                List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

        /** 环欠着的那一枚（A）池量＝族留底：50 枚全在环的口粮里，可花 0。 */
        private static final long RESERVED_SEED_POOL = 50L;
        /** 同族另一枚（B）池量：100 枚，旧口径拿它替 A 贴账折出 {@code (0+50)/2 = 25} 发。 */
        private static final long RICH_VARIANT_POOL = 100L;
        /** 环欠 50 发×每发 1 枚 A（没有任何样板还 B ⇒ owed 只记在 A 那一格上）。 */
        private static final long TWIN_RESERVE_FIRINGS = 50L;
        /** 宿主自己报上来的发数：远大于两枚键各自折得出的发数。 */
        private static final long TWIN_FOLD_REQUESTED = 40L;

        /** 不在配给表里的旁人：一发吃 1 枚种、产别的键（{@code quotaExact} 交 {@code null} 那一型）。 */
        private static final FakePattern UNRATIONED_OUTSIDE_CONSUMER = new FakePattern(
                AEItemKey.of(Items.BLAST_FURNACE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(SEED, 1L)}, 1L, null, Set.of(SEED))},
                List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

        /**
         * 第三方自定义键那一档的种：主键是值对象 ⇒ 声明键与池内实例 {@code equals} 相等而分格不同。
         * 形状照 {@code ParallelCustomKeyPoolRulerTest}（Applied Mekanistics 那一型），本文件不起第二套口径。
         */
        private static final AEKey CHEM_SEED_REPORTED = new SeedChemicalKey();
        /** 池子里真躺着的那一份实例。 */
        private static final AEKey CHEM_SEED_IN_POOL = new SeedChemicalKey();

        /** 自定义键那一档的环成员：一发吃 1 枚声明键、产 2 枚（自己就是那一环的一环）。 */
        private static final FakePattern CHEM_RING = new FakePattern(
                AEItemKey.of(Items.BEACON),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(CHEM_SEED_REPORTED, 1L)}, 1L, null,
                        Set.of(CHEM_SEED_REPORTED))},
                List.of(new GenericStack(CHEM_SEED_REPORTED, 2L)));

        /** 自定义键那一档的旁人：一发吃 1 枚<b>声明键那一份实例</b>，种键上有充足配给。 */
        private static final FakePattern CHEM_OUTSIDE_CONSUMER = new FakePattern(
                AEItemKey.of(Items.CAMPFIRE),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(CHEM_SEED_REPORTED, 1L)}, 1L, null,
                        Set.of(CHEM_SEED_REPORTED))},
                List.of(new GenericStack(OUTSIDE_PRODUCT, 1L)));

        /** 环欠 5 发×每发 1 枚：薄池读成 0 就是恒夹，读到 100 枚才有那 95 枚可花。 */
        private static final long CHEM_RESERVE_FIRINGS = 5L;

        /**
         * 判据 1：种键上配额充足而环还欠着发数 ⇒ 旁人可花量 0、一发推不出去，环自己照常推得出。
         */
        void ringSeedReserveClampsTheOtherConsumerOnTheQuotaFastPath() {
            var job = newJob(RING_REMAINING_FIRINGS, 3L);
            var reservation = reservationOf(job);

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER, seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            // 旧口径这一格是 min(池内=1, 配给=479994)=1 ⇒ 一发被推出去、那颗种就此回不来
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                    seedSlot(OUTSIDE_CONSUMER), false),
                    "环自己还欠 5 发×每发 1 枚：池里那枚种在环转起来之前不许被旁人抽走");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                            new KeyCounter(), new KeyCounter(), reservation),
                    "可花量 0 ⇒ 这一轮旁人一发也推不出去（holder 段与单发回落段都不许绕过留底）");

            assertEquals(1L, reservation.usableFor(SEED, 1L, RING_DOUBLER, 1L,
                    seedSlot(RING_DOUBLER), false),
                    "还回这一枚种的环成员自己不受留底这一格扣，否则环永远起步不了");
            var ringBatch = ParallelBatchExtraction.prepare(RING_DOUBLER, pool(), null,
                    RING_REMAINING_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(ringBatch, "池里那颗种得留给环自己起步：增殖腿照常能推");
            assertEquals(1L, ringBatch.executions(), "池里只一枚种、每发吃一枚 ⇒ 本轮推 1 发");
        }

        /**
         * 判据 2：环跑完 ⇒ 留底那一格自动没有 ⇒ 同一份薄池、同一张旁人样板立刻拿到那颗种。
         */
        void reserveDisappearsOnceTheRingHasNoFiringsLeft() {
            var job = newJob(0L, 3L);
            var reservation = reservationOf(job);

            assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "增殖腿已无剩余发数：没有环要留的那一截了，旁人照旧按份额取");
            var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "环跑完了还把种锁着，就是把夹子写成了恒夹");
            assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
        }

        /**
         * 单发让路那一刀在自有并行 CPU 这一条路上<b>没有独有的覆盖面</b>：它挡的那一格由留底原样挡着。
         *
         * <p>两头都要有数，才算"吞掉"而不是"没人在场"：
         * <ol>
         *   <li>旧那一刀真会跳的那一档（池里只剩那一枚种、契约留底 1 枚）——不用它，
         *       {@code usableFor} 把这一键夹到 0，{@code prepare} 整批发不出去；</li>
         *   <li>旧那一刀<b>放行</b>的那一档（池里两枚、它只算一发）——留底照样夹到 0。
         *       这一档就是实机 {@code taken=3} 那一次的形状：旁人一批吃两枚，单发口径看不见第二枚，
         *       旧那一刀当时点头放行，种还是被一次抽干。</li>
         * </ol>
         *
         * <p>反向那一半也钉住：环成员在两把尺上同判放行（它自己还回这一枚种），
         * 删掉让路那一刀不会把环自己挡在料外。
         */
        void singleFiringYieldAddsNothingOnTopOfTheSeedReserve() {
            var job = newJob(RING_REMAINING_FIRINGS, 3L);
            var reservation = reservationOf(job);
            var contractReserve = contractReserve(1L);

            // 档①：旧那一刀真跳的那一档
            var thinPool = pool();
            assertTrue(RecursiveTaskOrdering.hasPendingProducer(job.tasks, contractReserve),
                    "前提读数：增殖腿还欠 5 发，让路判据的总闸在这一档是开着的");
            assertTrue(RecursiveTaskOrdering.shouldDeferConsumer(
                            OUTSIDE_CONSUMER, contractReserve, thinPool, job.tasks),
                    "前提读数：单发让路在池里只剩一枚种这一档确实要跳这一张旁人样板（它挡的就是这一格）");
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "同一档留底的读数：环欠 5 发×每发 1 枚，比池里那 1 枚多 ⇒ 旁人对种键的可花量是 0");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, thinPool, null, 3L,
                            new KeyCounter(), new KeyCounter(), reservation),
                    "让路那一刀挡的东西，取料点上的留底原样挡着：这一路上它不是唯一那把尺");

            // 档②：旧那一刀放行、而实机真被抽干的那一档（一批吃两枚）
            var twoSeedPool = poolWith(SEED, 2L);
            assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                            OUTSIDE_CONSUMER, contractReserve, twoSeedPool, job.tasks),
                    "单发口径只算一发：2-1=1 还在留底之上 ⇒ 旧那一刀在这里放行，看不见这一批要吃的第二枚");
            assertEquals(0L, reservation.usableFor(SEED, 2L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "同一档留底的读数：它按整批发数夹，两枚全在环的口粮里");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, twoSeedPool, null, 3L,
                            new KeyCounter(), new KeyCounter(), reservation),
                    "旧那一刀漏掉的正是这一次：留底把整批挡下，池里那两枚一枚也不许旁人动");

            // 反向：环成员在两把尺上同判放行——删让路那一刀不会把环自己挡在料外
            assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                            RING_DOUBLER, contractReserve, thinPool, job.tasks),
                    "增殖样板自己还回这一枚种：让路判据一路放行");
            var ringBatch = ParallelBatchExtraction.prepare(RING_DOUBLER, thinPool, null,
                    RING_REMAINING_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(ringBatch, "留底的豁免名单就是环分量成员：它照常拿得到那颗种");
            assertEquals(1L, ringBatch.executions(), "池里只一枚种、每发吃一枚 ⇒ 本轮推 1 发");
        }

        /**
         * 链式判据 1（这一刀的来路）：A 吃 K 出 L、B 吃 L 出 K，两侧都还欠着发数 ⇒
         * 分量对 K 的剩余需求 2 枚 &gt; 池里那 1 枚 ⇒ 旁人 C 对 K 的可花量是 <b>0</b>、一发推不出去。
         */
        void chainedRingReserveClampsTheOutsiderAlthoughNobodyRefundsTheSeedToItself() {
            var job = chainJob(CHAIN_EMITTER_FIRINGS, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job);

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                            seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：C 在种键上份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "A 还欠 2 发×每发 1 枚 K：链上没有任何样板把 K 还给自己，这一截照样是环自己的口粮");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                            CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation),
                    "可花量 0 ⇒ 这一轮 C 一发也推不出去（holder 段与单发回落段都不许绕过留底）");
        }

        /**
         * 链式判据 2（反向钉，防过夹）：把 A 的剩余发数设 0 ⇒ 分量对 K 的需求为 0
         * ⇒ 同一份薄池里 C 立刻拿到那颗 K。
         */
        void chainedRingReserveDisappearsOnceTheEmitterHasNoFiringsLeft() {
            var job = chainJob(0L, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job);

            assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "A 已无剩余发数：分量再没有要留给自己的那一截 K，C 照旧按份额取");
            var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                    CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "环已经转不动了还把 K 锁着，就是把夹子写成了恒夹");
            assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
        }

        /**
         * 链式判据 3：分量成员自己照常拿得到料——A 拿 K 起步、B 拿 L 转回 K，
         * 不许出现"A 被 B 的需求夹住、B 等 A 的 L"互等死锁。
         */
        void chainedRingMembersStillReachTheirOwnSeed() {
            var job = chainJob(CHAIN_EMITTER_FIRINGS, CHAIN_REFUNDER_FIRINGS, CHAIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job, Map.of(SEED, 1L, LINK, 1L));

            assertEquals(1L, reservation.usableFor(SEED, 1L, CHAIN_EMITTER, 1L,
                            seedSlot(CHAIN_EMITTER), false),
                    "A 是 K 所在分量的成员：留底不许扣它，否则环永远起步不了");
            var emitterBatch = ParallelBatchExtraction.prepare(CHAIN_EMITTER, poolWith(SEED, 1L), null,
                    CHAIN_EMITTER_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(emitterBatch, "A 拿不到那颗种，就没人产 L，B 那份 K 永远回不来");
            assertEquals(1L, emitterBatch.executions(), "池里只一枚 K、每发吃一枚 ⇒ 本轮推 1 发");

            var refunderBatch = ParallelBatchExtraction.prepare(CHAIN_REFUNDER, poolWith(LINK, 1L), null,
                    CHAIN_REFUNDER_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(refunderBatch, "B 也在同一个分量里：分量自己那份 L 留底不许把它夹成 A 等 B 的 K、B 等 A 的 L");
            assertEquals(1L, refunderBatch.executions(), "池里只一枚 L、每发吃一枚 ⇒ 本轮推 1 发");
        }

        /**
         * 链式判据 4（这一档此前没人守）：链式环<b>本来就净吃那一枚种</b>——A 欠 5 发吃 K、
         * B 欠 4 发还 K ⇒ {@code owed=5 > handedBack=4}，缺口由规划期起步提取补。
         *
         * <p>旧资格判据 {@code ret >= need} 把这一档<b>整键撤账</b> ⇒ 这一族不产留底 ⇒ 池里那枚 K
         * 在环转起来之前被旁人一发送走，而这条环本来还能再转 4 轮跑完（永久饿死）。
         *
         * <p>新口径两格都要有读数：账本那侧不再有数值资格那一格（还它的 B 还在图里 ⇒ K 与吃它的 A
         * 同分量 ⇒ 在册）；留底<b>取真实缺口</b> {@code owed − handedBack = 5 − 4 = 1}，
         * 不是整份剩余需求 5——所以池里 3 枚时旁人还能拿走 {@code 3 - 1 = 2} 枚，而池里那唯一的一枚
         * 一发也拿不走。环成员自己不受这一格扣（那一枚精确键的分量名单里写着 A 与 B）。
         */
        void netConsumingChainedRingReservesTheRealGapAndNotTheWholeDemand() {
            var job = chainJob(5L, 4L, CHAIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job);

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                            seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：C 在种键上份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "A 欠 5 发、B 只欠 4 发：这一环本来就净吃那一枚种，池里那 1 枚旁人一发也拿不走");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                            CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation),
                    "可花量 0 ⇒ 这一轮 C 一发也推不出去（环优先跑，跑完环再放行）");

            assertTrue(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                    "还种的 B 还欠 4 发、还在图里 ⇒ K 与吃它的 A 同分量 ⇒ 这一族必须在册"
                            + "（旧口径按 owed>handedBack 在这里整键撤账）");
            assertEquals(2L, reservationOf(job, Map.of(SEED, 3L)).usableFor(SEED, 3L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "留底取的是真实缺口 need−ret = 5−4 = 1，不是整份剩余需求 5：池里 3 枚时 C 还能拿走 2 枚");

            assertEquals(1L, reservation.usableFor(SEED, 1L, CHAIN_EMITTER, 1L,
                            seedSlot(CHAIN_EMITTER), false),
                    "环成员自己不受这一格扣：那一枚 K 就在 A→L→B→K 里循环");
        }

        /**
         * 链式判据 5（守门，防"还种腿一发不欠也照锁"）：还种那一腿 B 一发都不欠 ⇒ 它被 {@code rebuild}
         * 的筛图整张出图 ⇒ 没有任何还在图里的样板把 K 还回来 ⇒ K 与吃它的 A <b>不成分量</b> ⇒
         * 这一族不进账 ⇒ 同一份薄池旁人照常取走。
         *
         * <p><b>这一条钉的是筛图那一档，不是一道数值对账</b>：账本里"分量一枚都交不出来（{@code ret = 0}）
         * 就撤账"那一支按 SCC 定义<b>不可达</b>（键与吃它的样板同分量 ⇒ 分量内必有一张还在图里、
         * 发数 > 0 的样板还它），已随恒 0 的读数 {@code keysNotRepaying} 一起删掉。
         */
        void chainedRingHandingBackNothingKeepsTheSeedOutOfTheLedger() {
            var job = chainJob(CHAIN_EMITTER_FIRINGS, 0L, CHAIN_OUTSIDE_FIRINGS);

            assertFalse(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                    "还种的 B 一发不欠 ⇒ 整张被筛出图外 ⇒ K 与吃它的 A 不再同分量，这一档不许当环种");
            var reservation = reservationOf(job);
            assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "还不回来一粒的族不产留底：薄池里那枚 K 照常放给旁人");
            var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                    CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "分量一枚都交不出来还把种锁着，就是把夹子写成了恒夹");
            assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
        }

        /**
         * 链式判据 6（"环跑完自动放行"打在净吃种这一档上，反向守门防恒夹）：吃种的 A 跑到 0 发
         * ⇒ 它整张被筛出图外 ⇒ K 出分量、{@code owed} 那笔复加也归零 ⇒ 留底没有 ⇒
         * 同一份薄池里旁人立刻拿到那枚 K。
         *
         * <p>本条在旧口径（{@code ret >= need} 撤账、留底取整份 {@code owed}）下同样为绿：
         * 它是<b>防恒夹</b>的守门判据，不是这一刀的回归网。
         */
        void netConsumingChainedRingReleasesTheSeedOnceTheEmitterIsDone() {
            var job = chainJob(0L, 4L, CHAIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job);

            assertEquals(1L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "A 的 5 发跑到 0：它出图、分量对 K 的剩余需求复加成 0 ⇒ 缺口那一截也没有 ⇒ 放行旁人");
            var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                    CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "环已经跑完了还把 K 锁着，就是把夹子写成了恒夹");
            assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
        }

        /**
         * 链式判据 7（在册名单与留底<b>随发数每轮现算</b>）：任务<b>条数</b>一动不动（B 始终留在表里，
         * 只是剩余发数从 0 抬到 4）⇒ "这一枚精确键在不在册"必须跟着发数变。
         *
         * <p>旧那一格按<b>任务表条数</b>缓存重建（{@code RingSeedLedger} 里那一格本刀已删），册子滞后到
         * "有人出表"那一趟，
         * 而取料点上的留底复加读的是本 tick 的发数 ⇒ {@code 5/0} 跑到 {@code 5/4} 该入册的那一档没人补。
         */
        void seedEligibilityFollowsTheFiringsWithinOneTaskCount() {
            var job = chainJob(5L, 0L, CHAIN_OUTSIDE_FIRINGS);
            var before = reservationOf(job);
            assertEquals(1L, before.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "前提读数：B 一发都不欠 ⇒ 它被筛出图外 ⇒ K 与 A 不成分量、这一族不在册，旁人照常取走");

            // 条数不变（三张样板都还在表里），只把 B 的剩余发数经任务表现成的写口抬到 4
            job.tasks.get(CHAIN_REFUNDER).addFirings(PlanAmount.of(4L));

            var after = reservationOf(job);
            assertEquals(0L, after.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "5/4 这一档该入册就入册：按条数缓存的话这里还得等有人出表才夹得住");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null,
                            CHAIN_OUTSIDE_FIRINGS, new KeyCounter(), new KeyCounter(), after),
                    "册子跟着发数走 ⇒ 这一轮 C 一发也推不出去");
        }

        /**
         * 宽域判据 1（这一刀的来路）：环成员的剩余发数越出 {@code long}（{@code 2^64}）⇒
         * 留底照旧把池里那颗种扣光，旁人对种键的可花量是 <b>0</b>、{@code prepare} 一发推不出去。
         *
         * <p>旧口径是"任一格越界就 {@code trustworthy=false} ⇒ 整族不进账"：量级一大那颗种的留底直接消失，
         * {@code 9b76b76} 修掉的病原样回来。这一条在 {@code a559fd7} 上必须先红。
         */
        void overLongRingFiringsStillReserveTheirSeed() {
            var job = ringJobWithWideFirings(RING_DOUBLER, OVER_LONG_FIRINGS);
            var reservation = reservationOf(job);

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                            seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "增殖腿还欠 2^64 发×每发 1 枚：份数越出 long 不是免掉留底的条件");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                            new KeyCounter(), new KeyCounter(), reservation),
                    "可花量 0 ⇒ >long 的订单上这一轮旁人也一发推不出去");
        }

        /**
         * 宽域判据 2（口径改过的那一条）：环在宽域里 {@code handedBack < owed}（净吃种）⇒ 这一族
         * <b>照样在册</b>（账本那侧没有数值资格那一格，还它的腿还在图里就入账），
         * 留底取真实缺口 {@code owed - handedBack} 那一截。
         *
         * <p>旧口径在这里把键整键撤账（"还不回来就不算种"）⇒ 这一族不产留底 ⇒ 薄池里那枚种被旁人
         * 一发抽干。{@code 0 < handedBack < owed} 那一档正是 {@code cf74bd4} 删掉"单发让路"之后没人守的形状。
         *
         * <p>两侧复加必须在宽域上做：本例 {@code owed = 2×2^64 + 1×1 = 2^65+1}、
         * {@code handedBack = 1×2^64 + 3×1 = 2^64+3} ⇒ 缺口 {@code 2^64 - 2} 这一截比任何塞得进
         * {@code long} 的池量都大，所以旁人对种键的可花量是 0；分量成员自己在豁免名单里，
         * 照常拿得到起步那一枚。
         */
        void wideDomainNetConsumingRingReservesTheRealGap() {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(NET_CONSUMER, wideProgress(OVER_LONG_FIRINGS));
            job.tasks.put(OVER_REFUNDER, progress(1L));
            job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
            MaterialAllocationFixtures.assign(job, NET_CONSUMER, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OVER_REFUNDER, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));

            // 宽域真值：owed = 2×2^64 + 1×1 = 2^65+1，handedBack = 1×2^64 + 3×1 = 2^64+3 ⇒ 环净吃种
            assertTrue(job.ringSeeds.seedDemandsByFamily().containsKey(SEED.getPrimaryKey()),
                    "还种的腿还在图里 ⇒ 这一枚键与吃它的样板同分量 ⇒ 必须在册"
                            + "（旧口径按 owed>handedBack 在这里整键撤账）");
            var reservation = reservationOf(job);
            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "留底取的是缺口 owed−handedBack = 2^64−2 那一截：比任何 long 池量都大，旁人一枚也拿不走");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                    new KeyCounter(), new KeyCounter(), reservation), "这一轮旁人一发也推不出去");
            assertEquals(1L, reservation.usableFor(SEED, 1L, NET_CONSUMER, 1L,
                            seedSlot(NET_CONSUMER), false),
                    "净吃种那一腿自己是分量成员：这一格不许把它夹在自己的口粮上");
        }

        /**
         * 宽域判据 3（{@code long} 域顶点那一档的真话）：一发吃 2 枚、欠 {@code Long.MAX_VALUE} 发
         * ⇒ 留底是 {@code 2×Long.MAX_VALUE} 那一档，<b>比任何塞得进 long 的池量都大</b> ⇒
         * 这一键照样产留底（不是"越界就不产"），旁人可花量 0、分量成员自己照常拿得到。
         *
         * <p>饱和镜像与宽域两种口径在这一条上对任何池量都交出同一个 0（差不出），能吃出差别的是
         * 上一条用例那一截<b>缺口</b>读数（镜像把 {@code owed} 与 {@code handedBack} 同时顶到
         * {@code Long.MAX_VALUE}，缺口就读不出来了）；这一条钉的是"整族不进账"那一档与"恒夹"那一档。
         */
        void longMaxFiringsTierYieldsAReserveBiggerThanAnyPool() {
            var job = ringJobWithWideFirings(SEED_KEEPER, BigInteger.valueOf(Long.MAX_VALUE));
            var reservation = reservationOf(job);

            assertEquals(0L, reservation.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "每发 2 枚 × 欠 long 域顶点那么多发：这一档产的是大于任何池量的留底，不是不产留底");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                    new KeyCounter(), new KeyCounter(), reservation));
            assertEquals(1L, reservation.usableFor(SEED, 1L, SEED_KEEPER, 1L, seedSlot(SEED_KEEPER), false),
                    "分量成员自己在豁免名单里：这一格不是恒夹");
        }

        /**
         * 义务判据 1（这一刀的来路，证伪输入）：增殖腿 C 欠 5 发、欠账那一张 A 剩余发数 0 而义务账上挂着
         * 3 发 ⇒ 留底基准是"剩余 + 未交割义务"那一档（{@code RingSeedLedger#outstandingFirings}）。
         *
         * <p>旧口径把 A 整张筛出 SCC 图外（只看 {@code exactRemaining()}），两头一起漏：
         * A 不在豁免名单 ⇒ 池里那枚种对它夹成 0 ⇒ 它已收产物的欠账永远交割不掉（停滞）；
         * A 那 3 发的种量也不进 {@code owed} ⇒ 旁人只被 C 那 5 枚夹住，池里 6 枚时还能再拿走 1 枚。
         */
        void declaredObligationFiringsCountIntoTheSeedReserveAndKeepTheDebtorExempt() {
            var job = debtorJob(RING_REMAINING_FIRINGS, 0L, DECLARED_OBLIGATION_FIRINGS);
            var thinPool = reservationOf(job, Map.of(SEED, 1L));

            assertEquals(1L, thinPool.usableFor(SEED, 1L, DECLARED_DEBTOR, 1L,
                            seedSlot(DECLARED_DEBTOR), false),
                    "它自己就是种键那一环的成员：挂着 3 发欠账要付，留底不许把它夹成 0");
            var paying = ParallelBatchExtraction.prepare(DECLARED_DEBTOR, pool(), null,
                    DECLARED_OBLIGATION_FIRINGS, new KeyCounter(), new KeyCounter(), thinPool);
            assertNotNull(paying, "欠账交割那一腿拿不到料，已收产物的欠账就永远交割不掉（停滞）");
            assertEquals(1L, paying.executions(), "池里只一枚种、每发吃一枚 ⇒ 这一轮交割 1 发");

            // owed 要连 A 那 3 发一起算：5+3=8 枚 > 池里 6 枚；旧口径只夹 5 枚 ⇒ 旁人仍拿走 1 枚
            var sixPool = reservationOf(job, Map.of(SEED, 6L));
            assertEquals(0L, sixPool.usableFor(SEED, 6L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "分量对种键的剩余需求是 C 的 5 发加 A 挂账那 3 发：旁人一枚也不许动");
            assertNull(ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, poolWith(SEED, 6L), null,
                            3L, new KeyCounter(), new KeyCounter(), sixPool),
                    "可花量 0 ⇒ 这一轮旁人一发也推不出去（欠账那一份口粮留得住）");
        }

        /**
         * 义务判据 2（反向守门，防"把义务账写成永挂"）：欠账全部交割完（挂账 0、剩余 0）⇒
         * 这一张既不在 {@code owed} 也不在成员名单里。
         *
         * <p>第一档把增殖腿留着（C 欠 5 发）：池里 6 枚时旁人还能拿走 6−5=1 枚，交割掉的那 3 发不许再计一次。
         * 第二档整条环都跑完且欠账清光 ⇒ 这一族没有留底 ⇒ 同一份薄池旁人照常取走。
         */
        void settledObligationsLeaveTheDebtorOutOfBothTheReserveAndTheMemberList() {
            var job = debtorJob(RING_REMAINING_FIRINGS, 0L, DECLARED_OBLIGATION_FIRINGS);
            job.batchObligations.settle(DECLARED_DEBTOR, DECLARED_OBLIGATION_FIRINGS);
            var sixPool = reservationOf(job, Map.of(SEED, 6L));

            assertFalse(job.ringSeeds.seedDemandsByFamily().get(SEED.getPrimaryKey())
                            .members().contains(DECLARED_DEBTOR),
                    "挂账已清的这一张不在分量成员名单里：义务账不许写成永挂");
            assertEquals(1L, sixPool.usableFor(SEED, 6L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "owed 只剩增殖腿那 5 发：交割干净的 3 发不再计一次，旁人还能拿走 6−5=1 枚");

            var finished = reservationOf(debtorJob(0L, 0L, 0L), Map.of(SEED, 1L));
            assertEquals(1L, finished.usableFor(SEED, 1L, OUTSIDE_CONSUMER, 1L,
                            seedSlot(OUTSIDE_CONSUMER), false),
                    "整条环跑完且欠账清光 ⇒ 这一族没有留底 ⇒ 同一份薄池旁人照常取走");
            var batch = ParallelBatchExtraction.prepare(OUTSIDE_CONSUMER, pool(), null, 3L,
                    new KeyCounter(), new KeyCounter(), finished);
            assertNotNull(batch, "环已经没有了还把种锁着，就是把夹子写成了恒夹");
            assertEquals(1L, batch.executions(), "池里只一枚种 ⇒ 本批推 1 发");
        }

        /**
         * 同族两键判据 1（这一刀的来路）：一张样板一发同时吃同族<b>两枚精确键</b>各 1 枚、各还 1 枚，
         * 两枚各自过资格 ⇒ 族留底是 {@code (1+1) × 2 发 = 4} 枚，池里那 3 枚全在环的口粮里 ⇒
         * 旁人对种键的可花量是 <b>0</b>、一发推不出去。
         *
         * <p>旧口径把分量口径按族建档、同槽/同族的每发消耗 {@code Math::max} 并账 ⇒ 族格上只留下
         * {@code 1 × 2 发 = 2} 枚 ⇒ 同一档交出 {@code 3 − 2 = 1} 枚，那第二枚正是旁人从池里多抽走的。
         */
        void twinSeedKeysOfOneFamilySumIntoTheFamilyReserve() {
            var job = twinJob(TWIN_DOUPLER_FIRINGS, TWIN_OUTSIDE_FIRINGS);
            var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT));

            assertEquals(2, job.ringSeeds.seedDemandsByFamily()
                            .get(Items.IRON_INGOT).seedKeys().size(),
                    "前提读数：同族两枚精确键各自过资格、各记一份分量口径——并成一族就只剩一份");
            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(TWIN_VARIANT_A, TWIN_OUTSIDE_CONSUMER,
                            slot(TWIN_OUTSIDE_CONSUMER, 0)),
                    "前提读数：旁人这一族的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            // 旧口径（族格 Math::max 并账）这一格交回 3−2=1：第二枚的那一份没人留
            assertEquals(0L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                            TWIN_OUTSIDE_CONSUMER, 1L, slot(TWIN_OUTSIDE_CONSUMER, 0), false),
                    "同族两枚键各欠 2 发×每发 1 枚：族留底是两枚之和 4，不是取最大的 2；池里那 3 枚一枚也不许旁人动");
            assertNull(ParallelBatchExtraction.prepare(TWIN_OUTSIDE_CONSUMER,
                            poolWith(TWIN_VARIANT_A, TWIN_POOL_AMOUNT), null, TWIN_OUTSIDE_FIRINGS,
                            new KeyCounter(), new KeyCounter(), reservation),
                    "可花量 0 ⇒ 这一轮旁人一发也推不出去（holder 段与单发回落段都不许绕过留底）");

            // 环成员自己在两枚键上都免扣：族条目下两份分量名单都写着它
            assertEquals(TWIN_POOL_AMOUNT, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                            TWIN_SEED_DOUPLER, 1L, slot(TWIN_SEED_DOUPLER, 0), false),
                    "它就在自己那一枚的分量成员名单里：A 那一份口粮不许扣它");
            assertEquals(TWIN_POOL_AMOUNT, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                            TWIN_SEED_DOUPLER, 1L, slot(TWIN_SEED_DOUPLER, 1), false),
                    "同族第二枚那一格也是它自己要吃的：并账把豁免写成族一份时这里照样放行");
        }

        /**
         * 同族两键判据 2（守门，防把"族求和"写成恒夹）：同族两枚键里<b>只有 A 在册</b>——
         * {@link #TWIN_COMPONENT_A} 吃 A 也吃 B，可这个作业里没有任何样板把 B 还回来 ⇒ 图上没有指向 B
         * 那一个节点的边 ⇒ B 与吃它的样板<b>不成分量</b> ⇒ B 进不了账。
         * 族留底因此只计 A 那一枚的 {@code 1 × 2 发 = 2} 枚，B 那一格的消耗不进账 ⇒
         * 池里 3 枚时旁人照常拿走 {@code 3 − 2 = 1} 枚。
         *
         * <p>这一条挡的是反过来的那一刀：把族下"这一张样板吃掉的同族全部消耗"整个求和（连不进账的
         * 那一枚也算进去）⇒ 留底变成 4 枚 ⇒ 同一档夹成 0，把还能推的那一发也一起锁死。
         */
        void onlyTheQualifiedSeedKeyOfTheFamilyCountsIntoItsReserve() {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(TWIN_COMPONENT_A, progress(TWIN_DOUPLER_FIRINGS));
            job.tasks.put(TWIN_OUTSIDE_CONSUMER, progress(TWIN_OUTSIDE_FIRINGS));
            MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A, rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
            MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));

            var demands = job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT);
            assertEquals(1, demands.seedKeys().size(),
                    "没过资格那一枚（B 在这一趟没有还它的样板）不进账：族下只留 A 那一份分量口径");
            assertEquals(TWIN_VARIANT_A, demands.seedKeys().get(0).seed(),
                    "在册的就是过资格那一枚精确键，不是它的族、也不是同族另一枚");

            var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT));
            assertEquals(1L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                            TWIN_OUTSIDE_CONSUMER, 1L, slot(TWIN_OUTSIDE_CONSUMER, 0), false),
                    "owed 只剩过资格那一枚的 2 枚：没资格那一格不许跟着复加，旁人还能拿走 3−2=1 枚");
            var batch = ParallelBatchExtraction.prepare(TWIN_OUTSIDE_CONSUMER,
                    poolWith(TWIN_VARIANT_A, TWIN_POOL_AMOUNT), null, TWIN_OUTSIDE_FIRINGS,
                    new KeyCounter(), new KeyCounter(), reservation);
            assertNotNull(batch, "环只欠 2 枚而池里有 3 枚：这一发还能推，写成恒夹就是把它锁死");
            assertEquals(1L, batch.executions(), "可花量 1 枚、每发吃一枚 ⇒ 本批推 1 发");
        }

        /**
         * 同族两分量判据（豁免按分量交）：同族两枚键各在自己的分量里（A 那一张还 B 那一张），
         * 两侧都还欠着发数 ⇒ 每一侧伸手拿<b>对面那一份口粮</b>都要先扣对面的留底。
         *
         * <p>旧口径把两个分量的成员并成<b>一份</b>族免扣名单 ⇒ A 的成员在 B 的那一枚上也被整体放行，
         * B 那份口粮能被 A 抽干（薄池那一档旧码交出整池 1 枚，留底读数应是 0）。
         *
         * <p>反向那一半同样钉住并写明族口径的代价：族是<b>查表</b>口径（与 {@code shareOf} 同族），
         * B 的成员在自己那一枚上免扣 B 那一份，可它伸手拿同族任一变体照样要先扣 A 分量那 2 枚。
         */
        void twinComponentsExemptOnlyTheirOwnMemberList() {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(TWIN_COMPONENT_A, progress(COMPONENT_A_FIRINGS));
            job.tasks.put(TWIN_COMPONENT_B, progress(COMPONENT_B_FIRINGS));
            MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A, rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
            MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_B, rationOf(RING_QUOTA, TWIN_VARIANT_B));
            var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                    TWIN_VARIANT_B, TWIN_POOL_AMOUNT));

            assertEquals(2, job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT).seedKeys().size(),
                    "前提读数：同族两个分量各记一份留底（A 欠 2 枚、B 欠 1 枚）");
            assertEquals(2L, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                            TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 1), false),
                    "A 分量的成员伸手拿 B 那一份口粮：旧码并成一份免扣名单时交回整池 3 枚，这里先扣 B 分量那 1 枚");
            assertEquals(0L, reservation.usableFor(TWIN_VARIANT_B, 1L,
                            TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 1), false),
                    "薄池那一档：池里就是 B 分量欠着的那一枚时，A 的成员一枚也不许动（旧码放行整池）");
            assertEquals(2L, reservation.usableFor(TWIN_VARIANT_A, TWIN_POOL_AMOUNT,
                            TWIN_COMPONENT_A, 1L, slot(TWIN_COMPONENT_A, 0), false),
                    "它在自己那一枚上免扣 A 那一份，只剩对面 B 分量那 1 枚要扣");
            assertEquals(1L, reservation.usableFor(TWIN_VARIANT_B, TWIN_POOL_AMOUNT,
                            TWIN_COMPONENT_B, 1L, slot(TWIN_COMPONENT_B, 0), false),
                    "族是查表口径：B 的成员免扣 B 那一份，可它拿同族任一变体照样先扣 A 分量那 2 枚");
        }

        /**
         * 折发数那条腿（NeoEco 快路径的请求侧与宿主申报那条）也读同一份留底：份额足得很、
         * 环还欠着 5 发 ⇒ 旁人对种键的可花量是 <b>0</b> ⇒ 宿主自己报上来的 3 发一发也折不出。
         *
         * <p>取料点那把尺（{@code usableFor}）折的是<b>池子现量</b>，这一条腿折的是<b>发数</b>——
         * 它压根不经过取料点，所以"每发吃一枚种"这一维过去只受配额夹：实机 {@code 1e11} 那一跑里
         * {@code 每发=1,池内=0,网络sim=0} 那一行说的正是这一条腿把种申报走了。
         */
        void theFiringFoldOnTheFastPathAlsoReadsTheSeedReserve() {
            var job = newJob(RING_REMAINING_FIRINGS, 3L);
            var reservation = reservationOf(job);
            var pool = pool();
            var sample = new KeyCounter[]{eats(SEED, 1L)};

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                            seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                            reservation, OUTSIDE_CONSUMER, sample, 3L, pool),
                    "环欠 5 发×每发 1 枚 &gt; 池里那 1 枚 ⇒ 可花量 0 ⇒ 这一趟对种键一发也申报不出去");
            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                            reservation, OUTSIDE_CONSUMER, sample, 3L),
                    "交不出池子读数那一档照旧只按配额折：这一刀只长在看得见池子的那条腿上");
        }

        /**
         * 反向守门（同一条腿）：环分量的成员自己走折发数这条路时照常拿得到种。
         *
         * <p>豁免那一档必须写成"这一条腿对它不成立"，不是"扣 0 枚"——按后者这里会被池里那一枚种折成
         * 一发（3 → 1），增殖腿从此每 tick 只推进一发，链式环里 A 等 B 的 K、B 等 A 的 L 还会互等死锁。
         */
        void theFiringFoldLeavesTheRingMemberOwnSeedAlone() {
            var job = newJob(RING_REMAINING_FIRINGS, 3L);
            var reservation = reservationOf(job);
            var pool = pool();

            assertEquals(RING_QUOTA, reservation.quotaFor(SEED, RING_DOUBLER, seedSlot(RING_DOUBLER)),
                    "前提读数：增殖腿在种键上也有份额，这一档不是没有配额口径");
            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation, RING_DOUBLER,
                            new KeyCounter[]{eats(SEED, 1L)}, 3L, pool),
                    "它是 K 那一环的成员：这一条腿不许拿池里那一枚去夹它自己的发数（豁免写成夹就是新闸）");
        }

        /**
         * 环跑完自动放行（同一条腿）：种键的 {@code owed} 归零 ⇒ 这一族整个不出账 ⇒
         * 同一份薄池、同一张旁人样板的 3 发照旧折得出来。这一条防的是把这一刀写成恒夹。
         */
        void theFiringFoldReleasesTheSeedOnceTheRingHasNoDemandLeft() {
            var job = newJob(0L, 3L);
            var reservation = reservationOf(job);

            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation, OUTSIDE_CONSUMER,
                            new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                    "增殖腿一发不欠 ⇒ 没有要留给环的那一截 ⇒ 旁人的 3 发照常折得出");
        }

        /**
         * A（同一截留底被扣 N 次）：这一族只有 A 那一枚在册（没有任何样板还 {@link #TWIN_VARIANT_B}），
         * 环欠的 {@code 1 × 50} 枚全记在 A 那一格上。读口对<b>每一枚</b>键交回的都是"这一枚扣掉<b>整族</b>
         * {@code owed}"之后的读数，旧口径把两枚的可花量<b>相加</b>再除族每发合计 ⇒ 同一截留底扣两遍，
         * 而那道除法顺手让 B 那 100 枚去补 A 那 50 枚的亏空：一发各吃 A、B 一枚的旁人折出
         * {@code (0+50)/2 = 25} 发，可它伸手那一格 A 在取料点上本来就是 <b>0</b> 枚。
         *
         * <p>改后按精确键各除各的每发量、商取最小 ⇒ 0 发。这一条红在改前那一句的 25。
         */
        void theFiringFoldDoesNotLetTheRicherKeyOfAFamilyPayTheReservedOnesDeficit() {
            var job = twinBothKeysJob(TWIN_RESERVE_FIRINGS);
            var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, RESERVED_SEED_POOL,
                    TWIN_VARIANT_B, RICH_VARIANT_POOL));
            var pool = twinPool(RESERVED_SEED_POOL, RICH_VARIANT_POOL);
            // 槽序正是这一档的用力点：富余那一枚在前、被扣空那一枚在后，族合计才补得上
            var sample = new KeyCounter[]{eats(TWIN_VARIANT_B, 1L), eats(TWIN_VARIANT_A, 1L)};

            assertEquals(1, job.ringSeeds.seedDemandsByFamily().get(Items.IRON_INGOT).seedKeys().size(),
                    "前提读数：这一族只有 A 那一枚在册（没有样板还 B）⇒ owed 记在 A 那一格上");
            assertEquals(0L, reservation.usableFor(TWIN_VARIANT_A, RESERVED_SEED_POOL,
                            TWIN_OUTSIDE_BOTH_KEYS, 1L, slot(TWIN_OUTSIDE_BOTH_KEYS, 1), false),
                    "前提读数：取料点那把尺上 A 那一枚可花 0——50 枚全在环的口粮里");
            assertEquals(50L, reservation.usableFor(TWIN_VARIANT_B, RICH_VARIANT_POOL,
                            TWIN_OUTSIDE_BOTH_KEYS, 1L, slot(TWIN_OUTSIDE_BOTH_KEYS, 0), false),
                    "前提读数：同族富余那一枚可花 50，那是替别人扣过一遍 owed 的读数，不是多出来的发数");
            assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                            reservation, TWIN_OUTSIDE_BOTH_KEYS, sample, TWIN_FOLD_REQUESTED, pool),
                    "族里富余那一枚不许替被扣空那一枚贴亏空：按精确键各除各的每发量，A 那一格折不出第 1 发");
        }

        /**
         * 同一刀的反向守门（改前改后同一个数，钉"不许把还能推的那几发折成 0"）：族两枚键各 10 枚而
         * {@code owed} 只有 5 枚时，取料点对<b>每一枚</b>键都交出可花 5 枚 ⇒ 这一张旁人物理上就是只能跑 5 发。
         *
         * <p>复核给的"过夹那一型"（族真可花 15 ⇒ 应折 7 发）取的是<b>族可互换</b>口径，而留底按精确键记账
         * （{@code f8679c6}）：跑 7 发要的是 7 枚 A 加 7 枚 B，A 那一枚被环扣着 5 枚就只剩 5 枚可走
         * ⇒ 第 6 发起就付不出 A。这一档不是这一刀的回归网，钉的是这一刀不越位。
         */
        void theFiringFoldOnABalancedTwinFamilyStaysOnThePoolLegsOwnReading() {
            var job = twinBothKeysJob(5L);
            var reservation = reservationOf(job, Map.of(TWIN_VARIANT_A, 10L, TWIN_VARIANT_B, 10L));
            var pool = twinPool(10L, 10L);

            assertEquals(5L, reservation.usableFor(TWIN_VARIANT_A, 10L, TWIN_OUTSIDE_BOTH_KEYS, 1L,
                            slot(TWIN_OUTSIDE_BOTH_KEYS, 1), false),
                    "前提读数：取料点给 A 那一枚可花 10−5=5 枚");
            assertEquals(5L, reservation.usableFor(TWIN_VARIANT_B, 10L, TWIN_OUTSIDE_BOTH_KEYS, 1L,
                            slot(TWIN_OUTSIDE_BOTH_KEYS, 0), false),
                    "前提读数：B 那一枚同样 5 枚 ⇒ 两把尺在这一档给同一个数");
            assertEquals(5L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                            reservation, TWIN_OUTSIDE_BOTH_KEYS,
                            new KeyCounter[]{eats(TWIN_VARIANT_B, 1L), eats(TWIN_VARIANT_A, 1L)},
                            TWIN_FOLD_REQUESTED, pool),
                    "各除各的每发量之后还是 5 发：这一刀只把 25 那一型夹住，不把还能推的那几发一起锁死");
        }

        /**
         * B（读池没过池账口径）：宿主自报清单与样本槽里那一枚键，跟池子里那一份<b>不是同一个实例</b>。
         * 第三方自定义键把主键存成值对象（从 NBT／网络包重建出来的那一型）时，
         * {@code KeyCounter} 的主表按 {@code getPrimaryKey()} 的<b>引用</b>分格 ⇒ 拿声明键读池恒读 0，
         * 留底那一刀于是把<b>自己的池子现量</b>读成空 ⇒ NeoEco 这一条腿<b>恒夹</b>（池里明明躺着 100 枚）。
         *
         * <p>红在改前那一句：折出 0 发。改后读池走 {@code ParallelBatchExtraction#exactStored}
         * （先把声明键换成池账真装着的那一枚，复用现成解析、不写第二份）⇒ 与取料点读到同一格。
         */
        void theFiringFoldReadsThePoolLedgerInstanceOfAThirdPartyKey() {
            var job = chemicalSeedJob(CHEM_RESERVE_FIRINGS);
            var reservation = reservationOf(job, Map.of(CHEM_SEED_REPORTED, 100L));
            var pool = new ListCraftingInventory(ignored -> {
            });
            pool.insert(CHEM_SEED_IN_POOL, 100L, Actionable.MODULATE);

            assertEquals(CHEM_SEED_REPORTED, CHEM_SEED_IN_POOL, "夹具前提：按 equals 就是同一枚键");
            assertTrue(CHEM_SEED_REPORTED.getPrimaryKey() != CHEM_SEED_IN_POOL.getPrimaryKey(),
                    "夹具前提：主键是两个引用（值对象主键的自定义键就长这一型）");
            assertEquals(0L, pool.list.get(CHEM_SEED_REPORTED),
                    "夹具前提：主表按引用分格，声明键落在另一个桶里 ⇒ 旧口径读到的现量恒 0");
            assertEquals(100L, pool.list.get(CHEM_SEED_IN_POOL),
                    "夹具前提：100 枚躺在池子那一份实例的分格里");
            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(CHEM_SEED_REPORTED, CHEM_OUTSIDE_CONSUMER,
                            seedSlot(CHEM_OUTSIDE_CONSUMER)),
                    "前提读数：这一张在种键上有配额口径——旧口径确实走到了留底那一刀");
            assertEquals(95L, reservation.usableFor(CHEM_SEED_REPORTED, 100L, CHEM_OUTSIDE_CONSUMER, 1L,
                            seedSlot(CHEM_OUTSIDE_CONSUMER), false),
                    "前提读数：取料点那把尺读同一格给的是 100−5=95，不是 0");
            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation,
                            CHEM_OUTSIDE_CONSUMER, new KeyCounter[]{eats(CHEM_SEED_REPORTED, 1L)}, 3L, pool),
                    "读池先过池账口径 ⇒ 池里那 100 枚看得见，扣掉环欠的 5 枚照常折得出 3 发（改前恒夹成 0）");
        }

        /**
         * C（无配额那一档漏夹，与取料点不对称）：这张旁人<b>压根不在配给表里</b> ⇒ {@code quotaExact}
         * 交 {@code null}（"没有口径"，不是"份额用完"那个 0）。旧口径把留底那一刀排在配额那一刀之后、
         * {@code null} 就 {@code continue} ⇒ 这一型<b>不读留底</b>，3 发原样折出去；而取料点
         * {@link ParallelPlanReservation#usableFor} 是没有配额口径照样夹——两把尺对同一件事两个答案。
         *
         * <p>红在改前那一句的 3。改后留底独立施夹（与网络回补那条腿同一形状）⇒ 0 发；
         * 同档反向那一半钉的是"环一发不欠 ⇒ 放行"，这一刀不是恒夹。
         */
        void theFiringFoldReadsTheSeedReserveEvenWithoutAnyQuotaBasis() {
            var job = jobWithUnrationedOutsider(RING_REMAINING_FIRINGS);
            var reservation = reservationOf(job);

            assertNull(reservation.quotaExact(SEED, UNRATIONED_OUTSIDE_CONSUMER,
                            seedSlot(UNRATIONED_OUTSIDE_CONSUMER)),
                    "前提读数：这一张没有配额口径（quotaExact 交 null，不是份额用完那个 0）");
            assertEquals(0L, reservation.usableFor(SEED, 1L, UNRATIONED_OUTSIDE_CONSUMER, 1L,
                            seedSlot(UNRATIONED_OUTSIDE_CONSUMER), false),
                    "同一档取料点那把尺：没有配额口径照样夹，可花量 0");
            assertEquals(0L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservation,
                            UNRATIONED_OUTSIDE_CONSUMER, new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                    "留底这一刀从配额那一刀里拆出来独立施夹：这一型也读得到 owed=5 ⇒ 一发也申报不出去");

            var finished = jobWithUnrationedOutsider(0L);
            assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(reservationOf(finished),
                            UNRATIONED_OUTSIDE_CONSUMER, new KeyCounter[]{eats(SEED, 1L)}, 3L, pool()),
                    "环跑完 ⇒ 这一族不出账 ⇒ 同一张没配额的旁人照常折得 3 发（这一刀不是恒夹）");
        }

        /**
         * 网络回补那条腿也读同一份留底：环欠着 5 发而池里空空 ⇒ 旁人这一批要吃的 3 枚种
         * <b>净增可花量是 0</b> ⇒ 一枚也不许经网络搬进本 lane 池子。
         *
         * <p>这一条腿过去只减"池内已存"与"族剩配额"，压根不读留底——旁人<b>申报批</b>那
         * {@code k-1} 发的料就是从这条腿绕开池子那把尺搬进来的（实机 {@code 1e11} 那一跑）。
         */
        void theNetworkRefillLegAlsoReadsTheSeedReserve() {
            var job = newJob(RING_REMAINING_FIRINGS, 3L);
            var reservation = reservationOf(job);
            var pool = new ListCraftingInventory(ignored -> {
            });
            var network = new SeedNetwork(SEED, 5L);

            assertEquals(OUTSIDE_QUOTA, reservation.quotaFor(SEED, OUTSIDE_CONSUMER,
                            seedSlot(OUTSIDE_CONSUMER)),
                    "前提读数：旁人这一键的份额足得很——下面那个 0 不是配额用尽，是留底扣的");
            assertEquals(0L, ParallelBatchExtraction.refillObligationBatch(OUTSIDE_CONSUMER, pool, null,
                            3L, reservation, network),
                    "环欠 5 发×每发 1 枚 ⇒ 这一趟搬进来的净增可花量是 0 ⇒ 一颗种也不许经网络绕开留底");
            assertEquals(BigInteger.valueOf(5L), network.remaining(),
                    "没伸手就是没伸手：网络那五枚一枚不少（搬进本 lane 池子就等于搬走了）");
            assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(pool, SEED),
                    "本张的池子也没多出那一截");
        }

        /**
         * 同一条腿的两档反向守门（防恒夹）：臂 A 环分量的成员自己走这条腿回补照常搬得进
         * （豁免写成"按池子现量夹"就是把增殖腿扣在自己的起步量上）；
         * 臂 B 环跑完 ⇒ {@code owed} 归零、这一族整个不出账 ⇒ 同一笔回补照旧放行旁人。
         */
        void theNetworkRefillLegNeitherClampsTheRingNorStaysClampedForever() {
            var memberPool = new ListCraftingInventory(ignored -> {
            });
            var memberNetwork = new SeedNetwork(SEED, 5L);
            assertEquals(3L, ParallelBatchExtraction.refillObligationBatch(RING_DOUBLER, memberPool, null,
                            3L, reservationOf(newJob(RING_REMAINING_FIRINGS, 3L)), memberNetwork),
                    "臂 A：增殖腿给自己回补那 3 发的料——这一条腿对它不成立，不许夹成 0");
            assertEquals(BigInteger.valueOf(2L), memberNetwork.remaining(),
                    "臂 A：只走出本张那三枚，环还要留给旁人的那一截本来就不存在");

            var outsiderPool = new ListCraftingInventory(ignored -> {
            });
            var outsiderNetwork = new SeedNetwork(SEED, 5L);
            assertEquals(3L, ParallelBatchExtraction.refillObligationBatch(OUTSIDE_CONSUMER, outsiderPool,
                            null, 3L, reservationOf(newJob(0L, 3L)), outsiderNetwork),
                    "臂 B：环一发不欠 ⇒ 这一族不留底 ⇒ 同一笔回补照常放给旁人");
        }

        // ==================================================================================
        // 夹具
        // ==================================================================================

        /** 每 tick 现算的那本账：任务表 + 池子读数 + 两本配给账 + 作业自己的环种账。 */
        private static ParallelPlanReservation reservationOf(ParallelExecutingCraftingJob job) {
            return reservationOf(job, Map.of(SEED, 1L));
        }

        /** 池子快照按档位给：链式判据 3 那一档里 L 也在池内，占料那条分支要看得见同一份读数。 */
        private static ParallelPlanReservation reservationOf(
                ParallelExecutingCraftingJob job, Map<AEKey, Long> poolSnapshot
        ) {
            return ParallelPlanReservation.build(job.tasks.entrySet(), poolSnapshot,
                    job.materialAllocation, Set.of(),
                    job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
        }

        /** 链式环的账：A 与 B 都在任务表里，C 在种键上有充足配给（走配给早退那一档的形状）。 */
        private static ParallelExecutingCraftingJob chainJob(
                long emitterFirings, long refunderFirings, long outsideFirings
        ) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(CHAIN_EMITTER, progress(emitterFirings));
            job.tasks.put(CHAIN_REFUNDER, progress(refunderFirings));
            job.tasks.put(OUTSIDE_CONSUMER, progress(outsideFirings));
            MaterialAllocationFixtures.assign(job, CHAIN_EMITTER, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
            return job;
        }

        /** 环成员与旁人都在配给表里在册（实机 {@code alloc=[…left=959985…]} 两腿合计的那一份）。 */
        private static ParallelExecutingCraftingJob newJob(long ringFirings, long outsideFirings) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(RING_DOUBLER, progress(ringFirings));
            job.tasks.put(OUTSIDE_CONSUMER, progress(outsideFirings));
            MaterialAllocationFixtures.assign(job, RING_DOUBLER, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
            return job;
        }

        /**
         * 申报挂账那一档的账：增殖腿 C、欠账那一张 A、一张有充足配给的旁人 C'，三张都在配给表里在册。
         * A 的剩余发数与义务账上的挂账发数各由入参给（后者走义务账现成的 {@code incur}，不写第二本账）。
         */
        private static ParallelExecutingCraftingJob debtorJob(
                long doublerFirings, long debtorFirings, long debtorPendingFirings
        ) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(RING_DOUBLER, progress(doublerFirings));
            job.tasks.put(DECLARED_DEBTOR, progress(debtorFirings));
            job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
            MaterialAllocationFixtures.assign(job, RING_DOUBLER, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, DECLARED_DEBTOR, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
            job.batchObligations.incur(DECLARED_DEBTOR, BigInteger.valueOf(debtorPendingFirings));
            return job;
        }

        /**
         * 自循环环 + 一张有充足配给的旁人，环成员的剩余发数走任务表现成的宽域写口
         * （{@code TaskProgress#addFirings}）：越界自动升侧值，不绕开任务表自己塞字段。
         */
        private static ParallelExecutingCraftingJob ringJobWithWideFirings(
                FakePattern ringMember, BigInteger firings
        ) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(ringMember, wideProgress(firings));
            job.tasks.put(OUTSIDE_CONSUMER, progress(3L));
            MaterialAllocationFixtures.assign(job, ringMember, ration(RING_QUOTA));
            MaterialAllocationFixtures.assign(job, OUTSIDE_CONSUMER, ration(OUTSIDE_QUOTA));
            return job;
        }

        /** 宽域剩余发数：只经 {@code addFirings} 这一道写口进任务表。 */
        private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.addFirings(PlanAmount.of(firings));
            return progress;
        }

        private static WideKeyCounter ration(long quota) {
            var assigned = new WideKeyCounter();
            assigned.add(SEED, quota);
            return assigned;
        }

        /** 同族那一档的配给：按传进来的每一枚精确键各记一份份额（族商是把它们加起来判的）。 */
        private static WideKeyCounter rationOf(long quota, AEKey... keys) {
            var assigned = new WideKeyCounter();
            for (AEKey key : keys) {
                assigned.add(key, quota);
            }
            return assigned;
        }

        /** 同族两个精确键那一档的账：吃两枚键的环成员 + 一枚有充足配给的旁人（只吃 A 那一枚）。 */
        private static ParallelExecutingCraftingJob twinJob(long doublerFirings, long outsideFirings) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(TWIN_SEED_DOUPLER, progress(doublerFirings));
            job.tasks.put(TWIN_OUTSIDE_CONSUMER, progress(outsideFirings));
            MaterialAllocationFixtures.assign(job, TWIN_SEED_DOUPLER,
                    rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
            MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));
            return job;
        }

        /**
         * 同一件物品的一个<b>带组件变体</b>：与同物品的另一个变体是两个不同的精确键、同一个物品本体族——
         * "族格上只留下 max"那一档就长在这一对键上。
         */
        private static AEItemKey namedVariant(net.minecraft.world.item.Item item, String name) {
            var stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            return AEItemKey.of(stack);
        }

        /**
         * 折发数那条腿"A 那一档"的账：环成员 {@link #TWIN_COMPONENT_A} 欠 A 那一枚（B 没有样板还它、
         * 不进账），旁人 {@link #TWIN_OUTSIDE_BOTH_KEYS} 一发各吃 A、B 一枚，只在 A 那一枚上有配给
         * （族是查表口径 ⇒ B 那一格同样有配额口径，配额那一刀夹不住它）。
         */
        private static ParallelExecutingCraftingJob twinBothKeysJob(long ringFirings) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(TWIN_COMPONENT_A, progress(ringFirings));
            job.tasks.put(TWIN_OUTSIDE_BOTH_KEYS, progress(TWIN_FOLD_REQUESTED));
            MaterialAllocationFixtures.assign(job, TWIN_COMPONENT_A,
                    rationOf(RING_QUOTA, TWIN_VARIANT_A, TWIN_VARIANT_B));
            MaterialAllocationFixtures.assign(job, TWIN_OUTSIDE_BOTH_KEYS, rationOf(OUTSIDE_QUOTA, TWIN_VARIANT_A));
            return job;
        }

        /** 增殖腿与一张有配额的旁人，再加一张<b>不进配给表</b>的旁人（{@code quotaExact} 交 null 那一型）。 */
        private static ParallelExecutingCraftingJob jobWithUnrationedOutsider(long ringFirings) {
            var job = newJob(ringFirings, 3L);
            job.tasks.put(UNRATIONED_OUTSIDE_CONSUMER, progress(3L));
            return job;
        }

        /** 自定义键那一档的账：环成员欠着声明键那枚种，旁人在同一枚键上有充足配给。 */
        private static ParallelExecutingCraftingJob chemicalSeedJob(long ringFirings) {
            var job = new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
            }, (CraftingLink) null, null);
            job.tasks.put(CHEM_RING, progress(ringFirings));
            job.tasks.put(CHEM_OUTSIDE_CONSUMER, progress(3L));
            MaterialAllocationFixtures.assign(job, CHEM_RING, rationOf(RING_QUOTA, CHEM_SEED_REPORTED));
            MaterialAllocationFixtures.assign(job, CHEM_OUTSIDE_CONSUMER, rationOf(OUTSIDE_QUOTA, CHEM_SEED_REPORTED));
            return job;
        }

        /** 同族两枚键各放 {@code amountA}／{@code amountB} 枚的池子（A 是环欠着的那一枚）。 */
        private static ListCraftingInventory twinPool(long amountA, long amountB) {
            var inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(TWIN_VARIANT_A, amountA, Actionable.MODULATE);
            inventory.insert(TWIN_VARIANT_B, amountB, Actionable.MODULATE);
            return inventory;
        }

        /**
         * 契约／NBT 那份内部物表（{@code job.dyeableRecursiveInternalItems}）：单发让路那一刀喂的就是它，
         * 记的是规划期为这一颗种起步预留的枚数——与留底那把尺读的"环分量自己还没跑完的那一截"是两份口径。
         */
        private static KeyCounter contractReserve(long seedAmount) {
            var internalItems = new KeyCounter();
            internalItems.add(SEED, seedAmount);
            return internalItems;
        }

        /** 一发样本这一格吃 {@code perFiring} 枚：折发数那条腿（{@code perCraftInputs}）读的就是这一份。 */
        private static KeyCounter eats(AEKey key, long perFiring) {
            var sample = new KeyCounter();
            sample.add(key, perFiring);
            return sample;
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 池子里只躺着那一枚种：正是要留底的那一格。 */
        private static ListCraftingInventory pool() {
            var inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(SEED, 1L, Actionable.MODULATE);
            return inventory;
        }

        /** 薄池通用形状：只放 {@code amount} 枚这一个键，留底一夹就见底。 */
        private static ListCraftingInventory poolWith(AEKey key, long amount) {
            var inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(key, amount, Actionable.MODULATE);
            return inventory;
        }

        /**
         * 网络回补通道：网络上只躺着 {@code key} 那一枚键，要多少给多少。
         *
         * <p>这一档钉的是"这一刀伸不伸手"，不是网络交不交得出——剩下的枚数由 {@link #remaining()}
         * 读数自证（没伸手就一枚不少）。
         */
        private static final class SeedNetwork implements ParallelBatchExtraction.ObligationRefill {

            private final AEKey key;
            private long remaining;

            private SeedNetwork(AEKey key, long available) {
                this.key = key;
                this.remaining = available;
            }

            @Override
            public long pull(@Nullable AEKey what, long wanted) {
                if (what == null || !what.equals(key) || remaining <= 0L) {
                    return 0L;
                }
                long got = Math.min(wanted, remaining);
                remaining -= got;
                return got;
            }

            /** 网络上还剩几枚（纯读数）：这一刀没伸手就要在这里自证"一枚不少"。 */
            private BigInteger remaining() {
                return BigInteger.valueOf(remaining);
            }
        }

        private static IPatternDetails.IInput seedSlot(IPatternDetails pattern) {
            return pattern.getInputs()[0];
        }

        /**
         * 自定义键类型：与 {@code ParallelCustomKeyPoolRulerTest} 同一形状（codec 那一格本文件用不到，
         * 也不碰 {@code AEKeyType#getRawId()}），为的是让主键成为<b>值对象</b>。
         */
        private static final class SeedChemicalType extends AEKeyType {
            private SeedChemicalType() {
                super(ResourceLocation.fromNamespaceAndPath("testmek", "chemical"),
                        SeedChemicalKey.class, Component.literal("testmek:chemical"));
            }

            @Override
            public MapCodec<? extends AEKey> codec() {
                return null;
            }

            @Override
            public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
                return new SeedChemicalKey();
            }
        }

        private static final AEKeyType SEED_CHEM_TYPE = new SeedChemicalType();

        /**
         * 第三方自定义键（Mekanism 那一型）：{@code equals} 只比资源本体 ⇒ 两份实例认量同一枚，
         * 而 {@code getPrimaryKey()} 交回<b>各自新建的值对象</b> ⇒ AE2 那本按引用分格的主表把它们
         * 记进两个不同的桶。折发数那条腿伸手的键正是"声明那一份"，池子里躺着的是"另一份"。
         */
        private static final class SeedChemicalKey extends AEKey {
            private final ResourceLocation primary =
                    ResourceLocation.fromNamespaceAndPath("mekanism", "redstone");

            @Override
            public AEKeyType getType() {
                return SEED_CHEM_TYPE;
            }

            @Override
            public AEKey dropSecondary() {
                return this;
            }

            @Override
            public CompoundTag toTag(HolderLookup.Provider registries) {
                var tag = new CompoundTag();
                tag.putString("id", primary.toString());
                return tag;
            }

            @Override
            public Object getPrimaryKey() {
                return primary;
            }

            @Override
            public ResourceLocation getId() {
                return primary;
            }

            @Override
            public void writeToPacket(RegistryFriendlyByteBuf data) {
            }

            @Override
            protected Component computeDisplayName() {
                return Component.literal("mekanism:redstone");
            }

            @Override
            public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            }

            @Override
            public boolean hasComponents() {
                return false;
            }

            @Override
            public boolean equals(@Nullable Object other) {
                if (this == other) {
                    return true;
                }
                if (other == null || getClass() != other.getClass()) {
                    return false;
                }
                return Objects.equals(primary, ((SeedChemicalKey) other).primary);
            }

            @Override
            public int hashCode() {
                return Objects.hash(primary);
            }

            @Override
            public String toString() {
                return "SeedChemicalKey{stack=" + primary + "}";
            }
        }

        /** 同族那一档一张样板有两格（两枚精确键各一格）：点名要伸的是哪一格。 */
        private static IPatternDetails.IInput slot(IPatternDetails pattern, int index) {
            return pattern.getInputs()[index];
        }

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
            public boolean isValid(AEKey input, Level level) {
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
            org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.wideBatchSubtractsRingReserveFromExactStockBeforeDividingMaterialDemand", () -> new ParallelRingSeedReserveAtExtractionCases().wideBatchSubtractsRingReserveFromExactStockBeforeDividingMaterialDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.ringSeedReserveClampsTheOtherConsumerOnTheQuotaFastPath", () -> new ParallelRingSeedReserveAtExtractionCases().ringSeedReserveClampsTheOtherConsumerOnTheQuotaFastPath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.reserveDisappearsOnceTheRingHasNoFiringsLeft", () -> new ParallelRingSeedReserveAtExtractionCases().reserveDisappearsOnceTheRingHasNoFiringsLeft()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.singleFiringYieldAddsNothingOnTopOfTheSeedReserve", () -> new ParallelRingSeedReserveAtExtractionCases().singleFiringYieldAddsNothingOnTopOfTheSeedReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.chainedRingReserveClampsTheOutsiderAlthoughNobodyRefundsTheSeedToItself", () -> new ParallelRingSeedReserveAtExtractionCases().chainedRingReserveClampsTheOutsiderAlthoughNobodyRefundsTheSeedToItself()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.chainedRingReserveDisappearsOnceTheEmitterHasNoFiringsLeft", () -> new ParallelRingSeedReserveAtExtractionCases().chainedRingReserveDisappearsOnceTheEmitterHasNoFiringsLeft()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.chainedRingMembersStillReachTheirOwnSeed", () -> new ParallelRingSeedReserveAtExtractionCases().chainedRingMembersStillReachTheirOwnSeed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.netConsumingChainedRingReservesTheRealGapAndNotTheWholeDemand", () -> new ParallelRingSeedReserveAtExtractionCases().netConsumingChainedRingReservesTheRealGapAndNotTheWholeDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.chainedRingHandingBackNothingKeepsTheSeedOutOfTheLedger", () -> new ParallelRingSeedReserveAtExtractionCases().chainedRingHandingBackNothingKeepsTheSeedOutOfTheLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.netConsumingChainedRingReleasesTheSeedOnceTheEmitterIsDone", () -> new ParallelRingSeedReserveAtExtractionCases().netConsumingChainedRingReleasesTheSeedOnceTheEmitterIsDone()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.seedEligibilityFollowsTheFiringsWithinOneTaskCount", () -> new ParallelRingSeedReserveAtExtractionCases().seedEligibilityFollowsTheFiringsWithinOneTaskCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.overLongRingFiringsStillReserveTheirSeed", () -> new ParallelRingSeedReserveAtExtractionCases().overLongRingFiringsStillReserveTheirSeed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.wideDomainNetConsumingRingReservesTheRealGap", () -> new ParallelRingSeedReserveAtExtractionCases().wideDomainNetConsumingRingReservesTheRealGap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.longMaxFiringsTierYieldsAReserveBiggerThanAnyPool", () -> new ParallelRingSeedReserveAtExtractionCases().longMaxFiringsTierYieldsAReserveBiggerThanAnyPool()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.declaredObligationFiringsCountIntoTheSeedReserveAndKeepTheDebtorExempt", () -> new ParallelRingSeedReserveAtExtractionCases().declaredObligationFiringsCountIntoTheSeedReserveAndKeepTheDebtorExempt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.settledObligationsLeaveTheDebtorOutOfBothTheReserveAndTheMemberList", () -> new ParallelRingSeedReserveAtExtractionCases().settledObligationsLeaveTheDebtorOutOfBothTheReserveAndTheMemberList()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.twinSeedKeysOfOneFamilySumIntoTheFamilyReserve", () -> new ParallelRingSeedReserveAtExtractionCases().twinSeedKeysOfOneFamilySumIntoTheFamilyReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.onlyTheQualifiedSeedKeyOfTheFamilyCountsIntoItsReserve", () -> new ParallelRingSeedReserveAtExtractionCases().onlyTheQualifiedSeedKeyOfTheFamilyCountsIntoItsReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.twinComponentsExemptOnlyTheirOwnMemberList", () -> new ParallelRingSeedReserveAtExtractionCases().twinComponentsExemptOnlyTheirOwnMemberList()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldOnTheFastPathAlsoReadsTheSeedReserve", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldOnTheFastPathAlsoReadsTheSeedReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldLeavesTheRingMemberOwnSeedAlone", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldLeavesTheRingMemberOwnSeedAlone()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldReleasesTheSeedOnceTheRingHasNoDemandLeft", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldReleasesTheSeedOnceTheRingHasNoDemandLeft()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldDoesNotLetTheRicherKeyOfAFamilyPayTheReservedOnesDeficit", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldDoesNotLetTheRicherKeyOfAFamilyPayTheReservedOnesDeficit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldOnABalancedTwinFamilyStaysOnThePoolLegsOwnReading", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldOnABalancedTwinFamilyStaysOnThePoolLegsOwnReading()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldReadsThePoolLedgerInstanceOfAThirdPartyKey", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldReadsThePoolLedgerInstanceOfAThirdPartyKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theFiringFoldReadsTheSeedReserveEvenWithoutAnyQuotaBasis", () -> new ParallelRingSeedReserveAtExtractionCases().theFiringFoldReadsTheSeedReserveEvenWithoutAnyQuotaBasis()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theNetworkRefillLegAlsoReadsTheSeedReserve", () -> new ParallelRingSeedReserveAtExtractionCases().theNetworkRefillLegAlsoReadsTheSeedReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelRingSeedReserveAtExtractionCases.theNetworkRefillLegNeitherClampsTheRingNorStaysClampedForever", () -> new ParallelRingSeedReserveAtExtractionCases().theNetworkRefillLegNeitherClampsTheRingNorStaysClampedForever())
            );
        }
    }

    /** ParallelSubmitArrivalQuotaCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelSubmitArrivalQuotaCases {
        /** 实机那格的形状：一单里唯一候选是 enriched_shining 一类"申报了却一份料都没到"的键。 */
        private static final long SESSION_QUOTA = 42_662_400_000L;
        private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
        private static final AEKey CELL = AEItemKey.of(Items.CHEST);
        /** 环种：只有一发把它原样回炉的那张样板算环成员，摊派顺位靠它排在旁人之前。 */
        private static final AEKey SEED = AEItemKey.of(Items.BUCKET);

        // ----------------------------------------------------------------------------------------------------
        // 主用例：配额 4.27e10、提交腿到账 0、计划自产 0 ⇒ 份额收到 0，取料点不再空撞。
        // ----------------------------------------------------------------------------------------------------

        void declaredShortfallNarrowsRationToRealArrival() {
            var consumer = consumingPattern(SHINING, CELL);
            // 实机形状：这一键在计划的缺口账（missingItems）上——提交腿抽 0、缺口不挂在途账，
            // 配给却按仿真量给它记了整份配额。
            var plan = planWithRation(consumer, SHINING, SESSION_QUOTA,
                    new KeyCounter(), counter(SHINING, SESSION_QUOTA));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                    "计划带配给表：照单采用，排水不得再 mint 第二份");
            assertEquals(SESSION_QUOTA, MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getLong(SHINING),
                    "收窄前份额齐、池子空——这正是实机 708 次 nolist 空撞的账面形状");

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var pool = new WideCraftingInventory(ignored -> {
            });
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.ZERO)), pool,
                    IActionSource.empty(), arrivals);
            assertEquals(BigInteger.ZERO, familyArrivalOf(arrivals, SHINING),
                    "前提读数：这一族真到账 0（网络一枚都没交）");
            narrowEveryFamily(job, arrivals);

            assertEquals(BigInteger.ZERO, MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getExact(SHINING),
                    "到账 0 且自产 0 ⇒ 假份额必须收到 0——取料点不再顶着配额空撞");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(0L, reservation.remainingQuota(consumer, SHINING),
                    "剩余配额读回 0：既有路径（同族别的候选／照实停在缺口）接手，不新增拒绝分支");
            assertTrue(shortfall.isEmpty(),
                    "declared shortfall 照旧不挂在途账——这刀只收账面，不动 waitingFor 语义");
        }

        // ----------------------------------------------------------------------------------------
        // 对照用例：全额到账 ⇒ 表与改前逐字相同（防把收窄做成普遍缩表）。
        // ----------------------------------------------------------------------------------------

        void fullArrivalKeepsTheRationByteIdentical() {
            // (a) ≤long 一口喂满：抽到的就是计划要搬的全部
            var consumer = consumingPattern(SHINING, CELL);
            var share = new WideKeyCounter();
            share.add(SHINING, 500L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    counter(SHINING, 500L), new KeyCounter(), new KeyCounter(), times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            var before = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).copy();

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.valueOf(500L))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);
            assertEquals(before.getExact(SHINING), MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getExact(SHINING),
                    "全额到账一个都不许收");

            // (b) >long 需求、网络喂满一整刀：提交这一步不搬的那一截是"按批续取的合法量"，
            // 拿它当假份额收窄就是把合法的大数 lane 掐死。
            var demand = new BigInteger("14000000000000000000");
            var wideShare = new WideKeyCounter();
            wideShare.add(SHINING, demand);
            var wideMaterial = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            wideMaterial.put(consumer, wideShare);
            var wideUsed = new WideKeyCounter();
            wideUsed.setExact(SHINING, demand);
            var wideBase = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    counter(SHINING, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(),
                    times(consumer, 1L));
            var widePlan = new RecursiveCraftingPlan(wideBase, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), wideMaterial, WideLedgerSnapshot.capture(null, wideUsed, null), null);
            var wideJob = new ParallelExecutingCraftingJob(widePlan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(wideJob, widePlan);
            var wideArrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    widePlan, gridOf(new HoldingNetwork(SHINING, demand)),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), wideArrivals);
            // 手算：到账＝一整刀 9223372036854775807，本步不搬那一截＝1.4e19 − 9223372036854775807
            //       ＝4776627963145224193；两腿合计正好是这一族申报的 1.4e19 ⇒ allowance＝demand
            //       ⇒ 一个字不收。少了后一腿，族摊派就会把 1.4e19 夹成一刀的量（>long 的 lane 永久掐死）。
            assertEquals(new BigInteger("4776627963145224193"),
                    familyOutsideArrivalOf(wideArrivals, SHINING),
                    "喂满一整刀的那一族必须把\"本步不搬\"的那一截照量记进兜底池");
            assertEquals(demand, familyArrivalOf(wideArrivals, SHINING)
                            .add(familyOutsideArrivalOf(wideArrivals, SHINING)),
                    "两腿合计＝这一族申报的 1.4e19：正好摊得满，等于不收窄");
            narrowEveryFamily(wideJob, wideArrivals);
            assertEquals(demand, MaterialAllocationFixtures.assignedSnapshot(wideJob).get(consumer).getExact(SHINING),
                    "喂满一整刀：1.4e19 份额一枚都不许收");
        }

        // ----------------------------------------------------------------------------------------
        // 自产用例：到账 0 而计划自产 >0 ⇒ 份额按"到账+自产"留下（错杀它就是留种/环那套的账）。
        // ----------------------------------------------------------------------------------------

        void plannedSelfEmitSurvivesZeroArrival() {
            var consumer = consumingPattern(SHINING, CELL);
            var plan = planWithRation(consumer, SHINING, SESSION_QUOTA,
                    new KeyCounter(), counter(SHINING, SESSION_QUOTA));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            // 提交瞬间的计划自产快照（生产路由由 describePlanBalance 按任务表产出写下）：
            // 这一族任务表会产 300 枚——这份额是后面能兑现的，不许错杀。
            job.accountPlannedEmit.add(SHINING, 300L);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.ZERO)),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);

            assertEquals(BigInteger.valueOf(300L), MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getExact(SHINING),
                    "份额 ← min(42662400000, 到账0 + 自产300)：自产那一截必须原样留下");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(300L, reservation.remainingQuota(consumer, SHINING),
                    "取料点还能按自产兑现的那 300 枚推批次——这刀不砍需求");
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #219 条目⑥：计划自产那一腿不许拿顶格哨兵当量——pool 少算就是把还能推的那一截夹小。
        // ----------------------------------------------------------------------------------------

        /**
         * (a) 主臂：任务表自产 {@code 1.4e19} 枚（发数越界那一型：每发 1 枚 × {@code 1.4e19} 发）、
         * 同族两个消费者各申报 {@code 7e18}，网络一枚都不给（到账 0、无兜底）⇒ 摊派必须把
         * {@code 1.4e19} 全额摊开，两张都保满份。
         *
         * <p>改前报的假数（HEAD 实跑）：{@code accountPlannedEmit} 是 AE2 {@code KeyCounter}，写口
         * {@code describePlanBalance} 在 {@code saturatingMultiply(1, Long.MAX_VALUE)} 上收口 ⇒
         * 这一格恒读天花板，{@code pool＝0＋0＋9223372036854775807}——首位摊满 {@code 7e18}，
         * 次位只剩 {@code 2223372036854775807}，被夹小的那一格带着开不了工的份额停在尾段
         * （与实机 {@code left>0 ∧ ret=0} 那一族同形）。饱和是<b>向上夹</b>：真值越大这一腿反而被
         * 记得越小，所以它是判据不是读数。
         *
         * <p>发数走的是计划对象自带的宽域读口（{@code WideCraftSnapshot} → 任务表
         * {@code TaskProgress#exactRemaining}），不是新建的旁路账。
         */
        void beyondLongPlanSelfEmitIsSpreadAcrossTwoFamilyConsumers() {
            var firings = new BigInteger("14000000000000000000");
            var eachShare = new BigInteger("7000000000000000000");
            var producer = emittingPattern(Items.GLOWSTONE_DUST, SHINING, 1L);
            var first = plainConsumerPattern(AEItemKey.of(Items.PAPER));
            var second = plainConsumerPattern(AEItemKey.of(Items.COMPASS));
            var plan = planWithSelfEmit(producer, firings, List.of(first, second),
                    List.of(eachShare, eachShare));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                    "计划带配给表：照单采用");
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            ParallelCraftingCpuLogic.describePlanBalance(job, plan);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.ZERO)),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            assertEquals(BigInteger.ZERO, familyArrivalOf(arrivals, SHINING),
                    "前提读数：这一族真到账 0");
            assertEquals(BigInteger.ZERO, familyOutsideArrivalOf(arrivals, SHINING),
                    "前提读数：短掉那一截没有兜底（declared shortfall 不记量）");
            narrowEveryFamily(job, arrivals);

            assertEquals(eachShare, MaterialAllocationFixtures.assignedSnapshot(job).get(first).getExact(SHINING),
                    "首位消费者：7e18 一枚都不许收");
            assertEquals(eachShare, MaterialAllocationFixtures.assignedSnapshot(job).get(second).getExact(SHINING),
                    "次位消费者也摊得满——改前这里读回 2223372036854775807（被天花板夹掉 4.78e18）");
            assertEquals(firings, familyShareAcrossTable(job, SHINING),
                    "族合计＝计划自产真值：两张各自满份，池子给得起");
        }

        /**
         * (b) 正向控制：{@code ≤long} 的常态计划，摊派结果逐字不变——这一臂防的是把"宽域"写成
         * "更宽＝少夹"的假修复。
         *
         * <p>两个子档都在纸上独立算好（自产读宽账与读窄账在这一档是同一个数）：
         * <ul>
         * <li>自产 {@code 1200} 盖得住两张各 {@code 500} ⇒ 一个字都不收；</li>
         * <li>自产 {@code 300} 盖不住两张各 {@code 500} ⇒ 族合计照旧被夹到 {@code 300}（收窄那道
         * 判据仍然咬得住，只是先摊给谁由既有推送顺位决定，本刀不动顺位）。</li>
         * </ul>
         */
        void withinLongPlanSelfEmitKeepsTheAllocationVerbatim() {
            var first = plainConsumerPattern(AEItemKey.of(Items.PAPER));
            var second = plainConsumerPattern(AEItemKey.of(Items.COMPASS));

            // 子档一：自产 1200（30 枚 × 40 发）盖得住 500＋500 ⇒ 不收窄
            var covered = planWithSelfEmit(emittingPattern(Items.GLOWSTONE_DUST, SHINING, 30L),
                    BigInteger.valueOf(40L), List.of(first, second),
                    List.of(BigInteger.valueOf(500L), BigInteger.valueOf(500L)));
            var coveredJob = narrowWithZeroArrival(covered);
            assertEquals(BigInteger.valueOf(500L), MaterialAllocationFixtures.assignedSnapshot(coveredJob).get(first).getExact(SHINING),
                    "常态计划首位逐字不变");
            assertEquals(BigInteger.valueOf(500L), MaterialAllocationFixtures.assignedSnapshot(coveredJob).get(second).getExact(SHINING),
                    "常态计划次位逐字不变");
            assertEquals(BigInteger.valueOf(1000L), familyShareAcrossTable(coveredJob, SHINING),
                    "盖得住就是全额下发：宽域账在这一档不放过任何一截配额");

            // 子档二：自产 300（30 枚 × 10 发）盖不住 500＋500 ⇒ 族合计照旧只有 300
            var starved = planWithSelfEmit(emittingPattern(Items.GLOWSTONE_DUST, SHINING, 30L),
                    BigInteger.valueOf(10L), List.of(first, second),
                    List.of(BigInteger.valueOf(500L), BigInteger.valueOf(500L)));
            var starvedJob = narrowWithZeroArrival(starved);
            assertEquals(BigInteger.valueOf(300L), familyShareAcrossTable(starvedJob, SHINING),
                    "账体换宽域之后，池子给不起的那一截照样夹掉——判据没被放宽成装饰");
            assertTrue(MaterialAllocationFixtures.assignedSnapshot(starvedJob).values().stream()
                            .allMatch(share -> share.getExact(SHINING).compareTo(BigInteger.valueOf(500L)) <= 0),
                    "任何一张都不许被抬过自己申报的那 500 枚");
        }

        /** 走一遍提交腿真抽（网络给 0）＋逐族收窄，交回收窄后的作业。 */
        private static ParallelExecutingCraftingJob narrowWithZeroArrival(RecursiveCraftingPlan plan) {
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.ZERO)),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);
            return job;
        }

        /**
         * 一张自产样板 ＋ 若干同族消费者的计划：自产发数交回宽域真值随计划出门
         * （{@link WideCraftSnapshot#capture}），{@code patternTimes} 那份则按 AE2 口径饱和——
         * 正是实机大订单的形状：代表值到顶而真值还在涨。
         *
         * @param producerFirings 自产样板的发数真值（可以 {@code >long}）
         * @param consumers       消费者样板（摊派顺位由执行侧那把尺决定，本夹具不指定）
         * @param shares          各消费者在 {@link #SHINING} 这一族上申报的份额
         */
        private static RecursiveCraftingPlan planWithSelfEmit(FakePattern producer,
                BigInteger producerFirings, List<IPatternDetails> consumers,
                List<BigInteger> shares) {
            var crafts = new WideCraftCounter();
            crafts.add(producer, PlanAmount.of(producerFirings));
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var times = new LinkedHashMap<IPatternDetails, Long>();
            // AE2 那份代表值字典：越界即 Long.MAX_VALUE（写口的窄读来源，与实机同形）
            times.put(producer, producerFirings.bitLength() > 63
                    ? Long.MAX_VALUE : producerFirings.longValueExact());
            for (int index = 0; index < consumers.size(); index++) {
                var share = new WideKeyCounter();
                share.add(SHINING, shares.get(index));
                material.put(consumers.get(index), share);
                times.put(consumers.get(index), 1L);
            }
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), counter(SHINING, 1L), times);
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, WideCraftSnapshot.capture(crafts));
        }

        /** 只产 {@code output}×{@code perFiring} 的自产样板（无输入槽）。 */
        private static FakePattern emittingPattern(net.minecraft.world.item.Item definition,
                AEKey output, long perFiring) {
            return new FakePattern(AEItemKey.of(definition),
                    new GenericStack[] {new GenericStack(output, perFiring)},
                    new IPatternDetails.IInput[] {});
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #118 第一条：同族两个消费者 + 到账只够一份 ⇒ 族合计放行份额＝到账，不是 2×到账。
        // ----------------------------------------------------------------------------------------

        /**
         * 摊派用的到账读数：族需求 200（两个消费者各申报 100），网络只交得出 60。
         *
         * <p>独立手算表（收窄后该是多少，全部现算于纸面，不从被测代码反推）：
         * <table border="1">
         * <caption>族份额摊派</caption>
         * <tr><th>消费者</th><th>收窄前份额</th><th>摊派顺位</th><th>收窄后份额</th>
         *     <th>quotaExact</th><th>capByRemainingShare(请求 100)</th></tr>
         * <tr><td>环成员（原样回炉 seed）</td><td>100</td><td>首位</td><td>60</td><td>60</td><td>60</td></tr>
         * <tr><td>旁人</td><td>100</td><td>次位</td><td>0</td><td>0（在册夹死，不是 null）</td><td>0</td></tr>
         * <tr><td>族合计</td><td>200</td><td>—</td><td>60 ＝到账</td><td>—</td><td>60 ＝到账</td></tr>
         * </table>
         * 未摊派的老算法给的是 60 + 60 ＝ 120，即 {@code (N−1)×到账} 的假份额。
         */
        void familyArrivalIsSplitBetweenConsumersInsteadOfHandedToEach() {
            var ringConsumer = ringConsumerPattern(AEItemKey.of(Items.GLOWSTONE));
            var plainConsumer = plainConsumerPattern(AEItemKey.of(Items.PRISMARINE_SHARD));
            // 配给：两张样板各在这一族上申报 100 份；计划聚合需求 200。
            var plan = planRationingBoth(ringConsumer, plainConsumer, 100L, 200L);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            assertEquals(BigInteger.valueOf(100L), MaterialAllocationFixtures.assignedSnapshot(job).get(ringConsumer).getExact(SHINING));
            assertEquals(BigInteger.valueOf(100L), MaterialAllocationFixtures.assignedSnapshot(job).get(plainConsumer).getExact(SHINING));

            // 真链第一步：提交腿真抽，网络只交得出 60 ⇒ 族到账读数 60（缺口不挂在途账）。
            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var pool = new WideCraftingInventory(ignored -> {
            });
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.valueOf(60L))), pool,
                    IActionSource.empty(), arrivals);
            assertEquals(BigInteger.valueOf(60L), familyArrivalOf(arrivals, SHINING),
                    "本用例的前提：这一族真到账 60");
            assertEquals(BigInteger.ZERO, familyOutsideArrivalOf(arrivals, SHINING),
                    "缺口不挂在途账也不越一刀⇒ 池子里没有到账之外的兜底那一腿，必须照收");
            narrowEveryFamily(job, arrivals);

            // 真链第二步：账面份额——首位摊到整份到账，次位摊到 0，族合计一枚都不许多。
            assertEquals(BigInteger.valueOf(60L), MaterialAllocationFixtures.assignedSnapshot(job).get(ringConsumer).getExact(SHINING),
                    "摊派首位（环成员）摊到到账 60：min(申报 100, 公用池 60)");
            assertEquals(BigInteger.ZERO, MaterialAllocationFixtures.assignedSnapshot(job).get(plainConsumer).getExact(SHINING),
                    "公用池已被首位摊完 ⇒ 次位剩 0，不是再来一份 60");
            assertEquals(BigInteger.valueOf(60L), familyShareAcrossTable(job),
                    "同族两个消费者合计放行 60＝到账量；老算法在这里放行 120＝2×到账");

            // 真链第三步：执行侧读到的夹值（quotaExact 与取料点那一道 capByRemainingShare）。
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(60L, reservation.remainingQuota(ringConsumer, SHINING), "首位还能推 60 枚");
            assertEquals(0L, reservation.remainingQuota(plainConsumer, SHINING),
                    "次位读 0（份额用完夹死），不是 -1（没有配额口径）——0 与 null 两型不可互换");
            assertEquals(new BigInteger("60"), ParallelCraftingCpuLogic.capByRemainingShare(
                    BigInteger.valueOf(100L), reservation, ringConsumer, SHINING,
                    ringConsumer.getInputs()[0], new java.util.HashMap<>()),
                    "取料点向首位这一号最多放行 60");
            assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.capByRemainingShare(
                    BigInteger.valueOf(100L), reservation, plainConsumer, SHINING,
                    plainConsumer.getInputs()[0], new java.util.HashMap<>()),
                    "取料点向次号放行 0：请求 100 被摊派后的份额夹死");
            assertTrue(shortfall.isEmpty(), "短交照旧不挂在途账——这一刀只收账面");
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #118 第二条：单消费者那一档不许被摊派改动（回归护栏）。
        // ----------------------------------------------------------------------------------------

        void singleConsumerRationIsUntouchedByTheSplit() {
            // 部分到账：单消费者仍是老那一刀 min(申报 100, 到账 60) ＝ 60，一枚不多一枚不少。
            var only = plainConsumerPattern(AEItemKey.of(Items.GLOWSTONE));
            var plan = planRationingBoth(only, null, 100L, 100L);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(SHINING, BigInteger.valueOf(60L))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);
            assertEquals(BigInteger.valueOf(60L), MaterialAllocationFixtures.assignedSnapshot(job).get(only).getExact(SHINING),
                    "单消费者＝摊派只有一位 ⇒ 与收窄前逐字同形：60");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(60L, reservation.remainingQuota(only, SHINING));
            assertEquals(new BigInteger("60"), ParallelCraftingCpuLogic.capByRemainingShare(
                    BigInteger.valueOf(100L), reservation, only, SHINING,
                    only.getInputs()[0], new java.util.HashMap<>()));
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #131 第一条：同族一个变体<b>全到账</b>、另一个一枚都没到 ⇒ 全到账那一档不许被夹成 0。
        // ----------------------------------------------------------------------------------------

        /**
         * 一族两个变体键（同一物品本体、不同耐久组件）：V1 需求 30 全到账、V2 需求 10 抽到 0。
         *
         * <p>独立手算表（族到账 {@code 30+0=30}、族申报 {@code 30+10=40}、族自产 0）：
         * <table border="1">
         * <caption>#131 第一条：族轴到账摊派</caption>
         * <tr><th>变体</th><th>计划需求</th><th>网络交得出</th><th>收窄前份额</th>
         *     <th>收窄后份额</th><th>remainingQuota</th><th>capByRemainingShare(请求 40)</th></tr>
         * <tr><td>V1（登记首位）</td><td>30</td><td>30（全到账）</td><td>30</td><td>30</td><td>30</td><td>30</td></tr>
         * <tr><td>V2（登记次位）</td><td>10</td><td>0</td><td>10</td><td>0</td><td>30（族一份）</td><td>30（族一份）</td></tr>
         * <tr><td>族合计</td><td>40</td><td>30</td><td>40</td><td>30＝族到账</td><td>—</td><td>—</td></tr>
         * </table>
         * 配额是族口径一份（{@code ParallelPlanReservation#shareOf} 按 {@code getPrimaryKey()} 合计），
         * 所以 V2 那一号问到的也是族份额 30——被夹没的是它自己的那份登记，不是别人还能取的量。
         *
         * <p>改前的形状（到账按精确键记、封顶按族花）：V1 全到账走 {@code gap<=0} 那一支直接
         * {@code continue}、不入袋，只有 {@code narrow(V2, 0)} 发生 ⇒ 族池 0 ⇒ {@code narrowVariants}
         * 把该族两个变体一起 {@code setExact(0)} ⇒ 池子里那 30 枚实物的配额被夹成 0，这一格停在缺口上。
         */
        void fullyArrivedVariantIsNotClampedByADrySiblingOfTheSameFamily() {
            var consumer = variantConsumerPattern(AEItemKey.of(Items.CRAFTING_TABLE));
            var share = new WideKeyCounter();
            share.add(V1, 30L);
            share.add(V2, 10L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            var demand = new KeyCounter();
            demand.add(V1, 30L);
            demand.add(V2, 10L);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), demand, times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            // 真链第一步：网络只交得出 V1 的 30 枚，V2 一枚都没有。
            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(Map.of(
                            V1, BigInteger.valueOf(30L), V2, BigInteger.ZERO))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);

            // 真链第二步：账面份额——登记首位保住自己那一格 30，次位摊到 0，族合计＝族到账 30。
            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer);
            assertEquals(BigInteger.valueOf(30L), assigned.getExact(V1),
                    "V1 那 30 枚实物已经躺在池子里，配额一枚都不许被同族的空档夹没");
            assertEquals(BigInteger.ZERO, assigned.getExact(V2),
                    "族池已被登记首位摊完 ⇒ V2 摊到 0");
            assertEquals(BigInteger.valueOf(30L), familyShareAcrossTable(job, V1),
                    "族合计放行 30＝族到账，不是 0（改前）也不是 40（没收窄）");

            // 真链第三步：执行侧读到的夹值。
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(30L, reservation.remainingQuota(consumer, V1),
                    "K1 的配额不许归 0：还能取 30");
            assertEquals(PlanAmount.of(30L), reservation.quotaExact(V1, consumer, consumer.getInputs()[0]),
                    "族份额真值 30（宽域读口，不经饱和）");
            assertEquals(BigInteger.valueOf(30L), ParallelCraftingCpuLogic.capByRemainingShare(
                    BigInteger.valueOf(40L), reservation, consumer, V1,
                    consumer.getInputs()[0], new java.util.HashMap<>()),
                    "取料点在这一格最多放行 30——请求 40 被族到账夹住");
            // 链上第一步的读数放最后钉：上面那条 V1 份额才是本用例要守的夹值，红字要落在那一条上。
            assertEquals(BigInteger.valueOf(30L), familyArrivalOf(arrivals, V1),
                    "前提读数：族到账合计＝V1 那 30 枚（全到账那一档也记在族这根轴上）");
            assertEquals(BigInteger.ZERO, familyOutsideArrivalOf(arrivals, V1),
                    "两档的缺口都不挂在途账、也不越一刀⇒ 到账就是上界，必须照收");
            assertTrue(shortfall.isEmpty(), "短交照旧不挂在途账——这一刀只收账面");
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #131 第二条（反向一档）：V1 短交、V2 全到账 ⇒ 族池必须含 V2 那一份。
        // ----------------------------------------------------------------------------------------

        /**
         * 一族两个变体：V1 需求 30 只到账 10（缺口不挂在途账），V2 需求 10 全到账。
         *
         * <p>独立手算表（族到账 {@code 10+10=20}、族申报 40、族自产 0）：
         * <table border="1">
         * <caption>#131 第二条：族池含全到账那一档</caption>
         * <tr><th>变体</th><th>计划需求</th><th>网络交得出</th><th>收窄前份额</th>
         *     <th>收窄后份额</th><th>remainingQuota</th><th>capByRemainingShare(请求 30)</th></tr>
         * <tr><td>V1（登记首位）</td><td>30</td><td>10（短交）</td><td>30</td><td>20</td><td>20</td><td>20</td></tr>
         * <tr><td>V2（登记次位）</td><td>10</td><td>10（全到账）</td><td>10</td><td>0</td><td>20（族一份）</td><td>20（族一份）</td></tr>
         * <tr><td>族合计</td><td>40</td><td>20</td><td>40</td><td>20＝族到账</td><td>—</td><td>—</td></tr>
         * </table>
         * 改前只有 {@code narrow(V1, 10)} 发生 ⇒ 族池 10、族合计放行 10——V2 那 10 枚已经进了池子的
         * 实物在账面上不存在，正是本刀要消灭的少记形状（少记一头会把还能取的量夹小）。
         */
        void familyPoolAddsTheFullyArrivedSiblingToTheShortOne() {
            var consumer = variantConsumerPattern(AEItemKey.of(Items.BOOK));
            var share = new WideKeyCounter();
            share.add(V1, 30L);
            share.add(V2, 10L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            var demand = new KeyCounter();
            demand.add(V1, 30L);
            demand.add(V2, 10L);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), demand, times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(Map.of(
                            V1, BigInteger.valueOf(10L), V2, BigInteger.valueOf(10L)))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer);
            assertEquals(BigInteger.valueOf(20L), assigned.getExact(V1),
                    "登记首位摊到族池 20（不是只按 V1 自己那 10 枚算）");
            assertEquals(BigInteger.ZERO, assigned.getExact(V2), "族池摊完 ⇒ 次位 0");
            assertEquals(BigInteger.valueOf(20L), familyShareAcrossTable(job, V1),
                    "夹值按族到账合计 20，不是只按短交那一档的 10");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(20L, reservation.remainingQuota(consumer, V1), "族份额读回 20");
            assertEquals(BigInteger.valueOf(20L), ParallelCraftingCpuLogic.capByRemainingShare(
                    BigInteger.valueOf(30L), reservation, consumer, V2,
                    consumer.getInputs()[1], new java.util.HashMap<>()),
                    "取料点向 V2 那一号放行族份额 20——它自己那份登记夹到 0 不是少给");
            // 链上第一步的读数放最后钉（红字要落在上面那条族合计上）。
            assertEquals(BigInteger.valueOf(20L), familyArrivalOf(arrivals, V1),
                    "前提读数：族到账合计＝短交 10 ＋全到账 10＝20");
            assertEquals(BigInteger.ZERO, familyOutsideArrivalOf(arrivals, V1),
                    "两档的缺口都不挂在途账、也不越一刀⇒ 到账就是上界");
            assertTrue(shortfall.isEmpty(), "短交照旧不挂在途账");
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #131 第三条（那条不许破的不变式）+ #133（欠夹那一型）：>long 喂满整刀的那一员自己摊得
        // 满、逐字不收窄，同族那一格谁都没兜底的假配额照旧夹。
        // ----------------------------------------------------------------------------------------

        /**
         * V1 需求 {@code 1.4e19}（越出 {@code long}，走 {@link WideLedgerSnapshot} 的宽域账），网络喂满
         * 一整刀 {@code 9223372036854775807}；V2 需求 10 抽到 0（缺口不挂在途账）。
         *
         * <p>独立手算表（族到账 {@code 9.22e18+0}、"本步不搬"那一截 {@code 1.4e19−9223372036854775807
         * =4776627963145224193}；族池＝两腿合计＝{@code 1.4e19}，族申报 {@code 1.4e19+10}）：
         * <table border="1">
         * <caption>#131 第三条 × #133：兜底那一腿只养活自己那一员</caption>
         * <tr><th>变体</th><th>计划需求（真值）</th><th>网络交得出</th><th>缺口去向</th>
         *     <th>收窄前份额</th><th>收窄后份额（该是）</th></tr>
         * <tr><td>V1</td><td>1.4e19</td><td>9.22e18（一整刀）</td><td>本步不搬 4.78e18，按批续取</td>
         *     <td>1.4e19</td><td>1.4e19（逐字不变）</td></tr>
         * <tr><td>V2</td><td>10</td><td>0</td><td>不挂账（假配额）</td>
         *     <td>10</td><td>0（池子已被 V1 摊满，没有第二份兜底）</td></tr>
         * <tr><td>族合计</td><td>{@code 1.4e19+10}</td><td>9.22e18</td><td>兜底 4.78e18</td>
         *     <td>{@code 1.4e19+10}</td><td>{@code 1.4e19}＝族池</td></tr>
         * </table>
         * 三条坏形状都要被这一条挡住：{@code 75c4e67} 之前只有 {@code narrow(V2, 0)} ⇒ 族池 0 ⇒
         * {@code 1.4e19} 整笔夹成 0；"族到账聚合、没有兜底那一腿"则给族池 {@code 9.22e18} ⇒ 把
         * {@code >long} 的一族永久夹成一刀的量，后面的按批续取全废；而 {@code 75c4e67} 那面<b>族根布尔旗</b>
         * 反过来整族 {@code return} ⇒ V2 那 10 枚谁都没兜底的假配额跟着漏放（台账 #133 的欠夹）。
         */
        void familyFedAWholeChunkKeepsItsOwnShareAndClampsTheUnbackedSibling() {
            var consumer = variantConsumerPattern(AEItemKey.of(Items.FURNACE));
            var demand = new BigInteger("14000000000000000000");
            var share = new WideKeyCounter();
            share.setExact(V1, demand);
            share.add(V2, 10L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            // used 腿的 AE2 代表值读数就是那道哨兵 Long.MAX_VALUE：它只把键送进抽取循环，
            // 真值一律由宽域账（WideLedgerSnapshot 的 USED 那一本）交回。
            var wideUsed = new WideKeyCounter();
            wideUsed.setExact(V1, demand);
            var missing = new KeyCounter();
            missing.add(V2, 10L);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    counter(V1, Long.MAX_VALUE), new KeyCounter(), missing, times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, WideLedgerSnapshot.capture(null, wideUsed, null), null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(Map.of(V1, demand))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            narrowEveryFamily(job, arrivals);

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer);
            assertEquals(demand, assigned.getExact(V1),
                    "1.4e19 那一格一枚都不许收（收了就再也放不开）：兜底那一腿正好摊满它自己");
            assertEquals(BigInteger.ZERO, assigned.getExact(V2),
                    "V2 那一格谁都没兜底：同族旁人有兜底不许替它的假配额作保（台账 #133 的欠夹）");
            assertEquals(demand, familyShareAcrossTable(job, V1),
                    "族合计收到族池 1.4e19＝到账 9.22e18＋本步不搬 4.78e18");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(PlanAmount.of(demand),
                    reservation.quotaExact(V1, consumer, consumer.getInputs()[0]),
                    "族合计一份＝1.4e19 原样交回，不是被一刀的量夹出来的假数");
            assertEquals(PlanAmount.of(demand),
                    reservation.quotaExact(V2, consumer, consumer.getInputs()[1]),
                    "V2 那一号问到的是同一个族合计——份额是族口径一份，不按精确键各发一份");
            assertEquals(demand, ParallelCraftingCpuLogic.capByRemainingShare(
                    demand, reservation, consumer, V1, consumer.getInputs()[0], new java.util.HashMap<>()),
                    "取料点向 1.4e19 的请求放行 1.4e19：按批续取那条路还开着");
            assertEquals(Long.MAX_VALUE, reservation.remainingQuota(consumer, V1),
                    "唯一的对外饱和出口：long 读数钉在天花板，不是\"族只剩一刀\"");
            // 链上第一步的读数放最后钉（红字要落在上面那条"1.4e19 一枚都不许收"上）。
            assertEquals(new BigInteger("9223372036854775807"), familyArrivalOf(arrivals, V1),
                    "族到账只有\"一整刀\"这一截，1.4e19 的其余部分压根不在到账轴上");
            assertEquals(new BigInteger("4776627963145224193"), familyOutsideArrivalOf(arrivals, V1),
                    "\"提交这一步本来就不搬\"的那一截按量记进兜底池，不是记一面旗");
            assertTrue(shortfall.isEmpty(),
                    "\"提交这一步本来就不搬\"的那一截不挂在途账——界面那个永远不降的\"正在合成 9.2E\"");
        }

        // ----------------------------------------------------------------------------------------
        // 台账 #131 第四条 + #133 主判据：同族一员挂账 + 另一员 declared-shortfall ⇒ 后者照旧被夹。
        // ----------------------------------------------------------------------------------------

        /**
         * V1 在 {@code usedItems}（不在缺口账）⇒ 短交的那一截挂进 {@code waitingFor}，是有实物兜底的在途量；
         * V2 只在 {@code missingItems} ⇒ 短交不挂账（#117 的假份额本体）。两个变体同属一族。
         *
         * <p>独立手算表（族到账 10、在途兜底 20 ⇒ 族池 30，族申报 40）：
         * <table border="1">
         * <caption>#131 第四条 × #133：在途那一截只养活挂账那一员</caption>
         * <tr><th>变体</th><th>计划需求</th><th>网络交得出</th><th>缺口去向</th>
         *     <th>收窄前份额</th><th>收窄后份额（该是）</th></tr>
         * <tr><td>V1（登记首位）</td><td>30</td><td>10</td><td>挂在途账 20</td><td>30</td><td>30</td></tr>
         * <tr><td>V2（登记次位）</td><td>10</td><td>0</td><td>不挂账</td><td>10</td><td>0</td></tr>
         * <tr><td>族合计</td><td>40</td><td>10</td><td>兜底 20</td><td>40</td><td>30＝族池</td></tr>
         * </table>
         * 少了兜底那一腿：族池只算到账 10 ⇒ V1 摊到 10，而 V1 那 20 枚马上就回池——配额已经夹没、
         * 再也放不开（{@code narrow} 只降不升），与 #131 要消灭的形状同一型。
         * 而 {@code 75c4e67} 那面<b>族根布尔旗</b>反着错：V1 挂账 ⇒ 整族 {@code return} ⇒ V2 那 10 枚
         * 假配额照样不夹（台账 #133 的欠夹）。这一条在改前的树上必须红。
         */
        void awaitedSliceExemptsOnlyItsOwnMemberAndStillClampsTheDeclaredShortfall() {
            var consumer = variantConsumerPattern(AEItemKey.of(Items.CARTOGRAPHY_TABLE));
            var share = new WideKeyCounter();
            share.add(V1, 30L);
            share.add(V2, 10L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            // V1 记在 usedItems（在途会回 CPU 的那一截），V2 记在 missingItems（规划期就做不出来）
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    counter(V1, 30L), new KeyCounter(), counter(V2, 10L), times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(Map.of(
                            V1, BigInteger.valueOf(10L), V2, BigInteger.ZERO))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            assertEquals(20L, shortfall.get(V1), "前提读数：V1 短的那 20 枚挂在途账——它是有实物兜底的量");
            assertEquals(0L, shortfall.get(V2), "前提读数：V2 那一档不挂在途账（#117 的假份额本体）");
            narrowEveryFamily(job, arrivals);

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer);
            assertEquals(BigInteger.valueOf(30L), assigned.getExact(V1),
                    "在途那 20 枚回池后还要靠这一格取料：份额一枚不许预先夹没");
            assertEquals(BigInteger.ZERO, assigned.getExact(V2),
                    "同族有人挂账不再是整族的免夹金牌：V2 那一格谁都没兜底，照旧夹到族池剩 0");
            assertEquals(BigInteger.valueOf(30L), familyShareAcrossTable(job, V1),
                    "族合计＝族池 到账10＋在途20＝30，不是原样 40");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(30L, reservation.remainingQuota(consumer, V1), "族份额读回 30＝族池");
            // 链上第一步的读数放最后钉（红字要落在上面那条"V2 照旧被夹"上）。
            assertEquals(BigInteger.valueOf(20L), familyOutsideArrivalOf(arrivals, V1),
                    "挂进在途账的那一截照量记进兜底池：它正是 V1 摊得满的那一条腿");
            assertEquals(BigInteger.valueOf(10L), familyArrivalOf(arrivals, V1), "族到账 10");
        }

        /**
         * 兜底那一腿是<b>公用池里的钱</b>，不是"某一员的专属豁免"（台账 #133 的反向一档）。
         *
         * <p>同一族的两个变体，登记顺位反过来摆：{@code V1}（份额 10）是 declared-shortfall、谁都没
         * 兜底，{@code V2}（份额 30）挂 20 枚在途账。族池＝到账 10＋兜底 20＝30、族申报 40。
         *
         * <p>独立手算表（摊派按登记顺位逐张扣）：
         * <table border="1">
         * <caption>#133 反向一档：池子被登记首位摊走 10</caption>
         * <tr><th>变体</th><th>计划需求</th><th>网络交得出</th><th>缺口去向</th>
         *     <th>收窄前份额</th><th>收窄后份额（该是）</th></tr>
         * <tr><td>V1（登记首位）</td><td>10</td><td>0</td><td>不挂账</td><td>10</td><td>10（池子给得出）</td></tr>
         * <tr><td>V2（登记次位）</td><td>30</td><td>10</td><td>挂在途账 20</td><td>30</td><td>20</td></tr>
         * <tr><td>族合计</td><td>40</td><td>10</td><td>兜底 20</td><td>40</td><td>30＝族池</td></tr>
         * </table>
         * 这一条与上一条合起来才说明豁免记的是<b>量</b>：改前的族根布尔旗在两档里都整族不夹，
         * 两条都读不到"池子摊完就夹"这个形状。
         */
        void theBackedSliceIsPoolMoneySpentByTheConsumerStandingFirst() {
            var consumer = variantConsumerPattern(AEItemKey.of(Items.SMOKER));
            var share = new WideKeyCounter();
            share.add(V1, 10L);
            share.add(V2, 30L);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            // V1 只在 missingItems（假配额），V2 在 usedItems（短的那 20 枚挂在途账）
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    counter(V2, 30L), new KeyCounter(), counter(V1, 10L), times(consumer, 1L));
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            var shortfall = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new HoldingNetwork(Map.of(
                            V1, BigInteger.ZERO, V2, BigInteger.valueOf(10L)))),
                    new WideCraftingInventory(ignored -> {
                    }), IActionSource.empty(), arrivals);
            assertEquals(20L, shortfall.get(V2), "前提读数：V2 短的那 20 枚挂在途账");
            assertEquals(0L, shortfall.get(V1), "前提读数：V1 那一档不挂在途账");
            narrowEveryFamily(job, arrivals);

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(consumer);
            assertEquals(BigInteger.valueOf(10L), assigned.getExact(V1),
                    "登记首位摊得到族池 ⇒ 它那一格原样不动（族池给得起）");
            assertEquals(BigInteger.valueOf(20L), assigned.getExact(V2),
                    "池子被首位摊走 10 枚 ⇒ V2 只剩 20：兜底是一笔钱，不是一面免夹旗");
            assertEquals(BigInteger.valueOf(30L), familyShareAcrossTable(job, V1), "族合计＝族池 30");
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            assertEquals(30L, reservation.remainingQuota(consumer, V2), "族份额一份＝30");
        }

        // ----------------------------------------------------------------------------------------
        // 桩与构造
        // ----------------------------------------------------------------------------------------

        /** 同一物品本体的两个变体键（带耐久组件）：族口径判据把它们当一个，精确键口径把它们当两个。 */
        private static AEItemKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        private static final AEKey V1 = wornKey(10);
        private static final AEKey V2 = wornKey(20);

        /** 两格各吃同族一个变体的消费者：登记顺位 V1 先、V2 后（摊派按这一把序扣）。 */
        private static FakePattern variantConsumerPattern(AEItemKey definition) {
            return new FakePattern(definition,
                    new GenericStack[] {new GenericStack(CELL, 1L)},
                    new IPatternDetails.IInput[] {
                            new FakeInput(new GenericStack(V1, 1L)),
                            new FakeInput(new GenericStack(V2, 1L))});
        }

        /** 生产调用点那一条搬运（{@code ParallelCraftingCpuLogic#trySubmitPartialJob}）逐字同形：逐族调一次收窄。 */
        private static void narrowEveryFamily(ParallelExecutingCraftingJob job,
                CraftingContinuationPartialSubmit.FamilyArrivalLedger arrivals) {
            for (var arrival : arrivals.families()) {
                job.narrowPlanMaterialShareByArrival(arrival.sampleKey(), arrival.arrived(),
                        arrival.outsideArrivals());
            }
        }

        /** 账本里这一族的到账合计（宽域读数）：没有这一族的条目就是用例前提没立住，直接失败。 */
        private static BigInteger familyArrivalOf(CraftingContinuationPartialSubmit.FamilyArrivalLedger arrivals,
                AEKey key) {
            var family = key.getPrimaryKey();
            for (var arrival : arrivals.families()) {
                if (family.equals(arrival.sampleKey().getPrimaryKey())) {
                    return arrival.arrived().exactValue();
                }
            }
            throw new AssertionError("到账账里没有这一族的条目：" + family);
        }

        /**
         * 这一族"短掉却不落在到账轴上"的那一截合计（宽域读数）：台账 #133 之后豁免是一条<b>量</b>，
         * 不再是一面族根的旗——用例要钉的就是这个数到底记了几枚。
         */
        private static BigInteger familyOutsideArrivalOf(
                CraftingContinuationPartialSubmit.FamilyArrivalLedger arrivals, AEKey key) {
            var family = key.getPrimaryKey();
            for (var arrival : arrivals.families()) {
                if (family.equals(arrival.sampleKey().getPrimaryKey())) {
                    return arrival.outsideArrivals().exactValue();
                }
            }
            throw new AssertionError("到账账里没有这一族的条目：" + family);
        }

        /** 族合计的独立读数：把表里<b>每张样板</b>在这一族上的份额全加回来（宽域），不读被测算法的任何中间量。 */
        private static BigInteger familyShareAcrossTable(ParallelExecutingCraftingJob job, AEKey familyKey) {
            var total = BigInteger.ZERO;
            var family = familyKey.getPrimaryKey();
            for (var assigned : MaterialAllocationFixtures.assignedSnapshot(job).values()) {
                for (AEKey variant : assigned.keys()) {
                    if (variant != null && family.equals(variant.getPrimaryKey())) {
                        total = total.add(assigned.getExact(variant));
                    }
                }
            }
            return total;
        }

        /** {@link #SHINING} 那一族的合计读数：老用例的入口逐字不变，只是把族键交给同一个算法。 */
        private static BigInteger familyShareAcrossTable(ParallelExecutingCraftingJob job) {
            return familyShareAcrossTable(job, SHINING);
        }

        /** 只吃 {@link #SHINING} 的普通消费者：不在任何环里 ⇒ 摊派顺位的次位。 */
        private static FakePattern plainConsumerPattern(AEItemKey definition) {
            return new FakePattern(definition,
                    new GenericStack[] {new GenericStack(CELL, 1L)},
                    new IPatternDetails.IInput[] {new FakeInput(new GenericStack(SHINING, 1L))});
        }

        /**
         * 环成员：一发吃掉一枚 {@link #SEED} 又原样还回一枚 ⇒ {@code RingSeedLedger} 认 {@code SEED} 为环种，
         * {@code RecursiveTaskOrdering} 把它排在旁人之前（摊派首位与执行侧推送顺位同一把尺）。
         */
        private static FakePattern ringConsumerPattern(AEItemKey definition) {
            return new FakePattern(definition,
                    new GenericStack[] {new GenericStack(CELL, 1L), new GenericStack(SEED, 1L)},
                    new IPatternDetails.IInput[] {
                            new FakeInput(new GenericStack(SHINING, 1L)),
                            new FakeInput(new GenericStack(SEED, 1L))});
        }

        /** 两张样板（第二张可为 null＝只剩一张）各配给 {@code ration} 份，计划聚合需求 {@code planDemand}。 */
        private static RecursiveCraftingPlan planRationingBoth(
                IPatternDetails first, @Nullable IPatternDetails second, long ration, long planDemand) {
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (IPatternDetails pattern : second == null
                    ? new IPatternDetails[] {first} : new IPatternDetails[] {first, second}) {
                var share = new WideKeyCounter();
                share.add(SHINING, ration);
                material.put(pattern, share);
                times.put(pattern, 1L);
            }
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), counter(SHINING, planDemand), times);
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
        }

        private static RecursiveCraftingPlan planWithRation(IPatternDetails consumer, AEKey rationKey,
                long ration, KeyCounter used, KeyCounter missing) {
            var share = new WideKeyCounter();
            share.add(rationKey, ration);
            var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            material.put(consumer, share);
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    used, new KeyCounter(), missing, times(consumer, 1L));
            return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                    1L, Map.of(), material, null, null);
        }

        private static Map<IPatternDetails, Long> times(IPatternDetails pattern, long firings) {
            var map = new LinkedHashMap<IPatternDetails, Long>();
            map.put(pattern, firings);
            return map;
        }

        private static FakePattern consumingPattern(AEKey input, AEKey output) {
            return new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                    new GenericStack[] { new GenericStack(output, 1L) },
                    new IPatternDetails.IInput[] { new FakeInput(new GenericStack(input, 1L)) });
        }

        private static KeyCounter counter(AEKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        private static IGrid gridOf(MEStorage storage) {
            return git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder()
                    .liveStorage(storage).build();
        }


        /** 持 {@code total} 枚（宽域）、每次最多交出一个 {@code long} 的网络桩。 */
        private static final class HoldingNetwork implements MEStorage {
            private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
            /** 键 → 还剩几枚（宽域）；同族几个变体各记各的，抽取按精确键问。 */
            private final Map<AEKey, BigInteger> held = new java.util.HashMap<>();

            private HoldingNetwork(AEKey what, BigInteger total) {
                held.put(what, total);
            }

            private HoldingNetwork(Map<AEKey, BigInteger> totals) {
                held.putAll(totals);
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
                var remaining = held.get(key);
                if (remaining == null) {
                    return 0L;
                }
                var granted = remaining.min(BigInteger.valueOf(amount)).min(LONG_MAX);
                if (mode == Actionable.MODULATE) {
                    held.put(key, remaining.subtract(granted));
                }
                return granted.longValue();
            }

            @Override
            public Component getDescription() {
                return Component.literal("holding-network");
            }
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.declaredShortfallNarrowsRationToRealArrival", () -> new ParallelSubmitArrivalQuotaCases().declaredShortfallNarrowsRationToRealArrival()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.fullArrivalKeepsTheRationByteIdentical", () -> new ParallelSubmitArrivalQuotaCases().fullArrivalKeepsTheRationByteIdentical()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.plannedSelfEmitSurvivesZeroArrival", () -> new ParallelSubmitArrivalQuotaCases().plannedSelfEmitSurvivesZeroArrival()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.beyondLongPlanSelfEmitIsSpreadAcrossTwoFamilyConsumers", () -> new ParallelSubmitArrivalQuotaCases().beyondLongPlanSelfEmitIsSpreadAcrossTwoFamilyConsumers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.withinLongPlanSelfEmitKeepsTheAllocationVerbatim", () -> new ParallelSubmitArrivalQuotaCases().withinLongPlanSelfEmitKeepsTheAllocationVerbatim()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.familyArrivalIsSplitBetweenConsumersInsteadOfHandedToEach", () -> new ParallelSubmitArrivalQuotaCases().familyArrivalIsSplitBetweenConsumersInsteadOfHandedToEach()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.singleConsumerRationIsUntouchedByTheSplit", () -> new ParallelSubmitArrivalQuotaCases().singleConsumerRationIsUntouchedByTheSplit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.fullyArrivedVariantIsNotClampedByADrySiblingOfTheSameFamily", () -> new ParallelSubmitArrivalQuotaCases().fullyArrivedVariantIsNotClampedByADrySiblingOfTheSameFamily()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.familyPoolAddsTheFullyArrivedSiblingToTheShortOne", () -> new ParallelSubmitArrivalQuotaCases().familyPoolAddsTheFullyArrivedSiblingToTheShortOne()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.familyFedAWholeChunkKeepsItsOwnShareAndClampsTheUnbackedSibling", () -> new ParallelSubmitArrivalQuotaCases().familyFedAWholeChunkKeepsItsOwnShareAndClampsTheUnbackedSibling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.awaitedSliceExemptsOnlyItsOwnMemberAndStillClampsTheDeclaredShortfall", () -> new ParallelSubmitArrivalQuotaCases().awaitedSliceExemptsOnlyItsOwnMemberAndStillClampsTheDeclaredShortfall()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelSubmitArrivalQuotaCases.theBackedSliceIsPoolMoneySpentByTheConsumerStandingFirst", () -> new ParallelSubmitArrivalQuotaCases().theBackedSliceIsPoolMoneySpentByTheConsumerStandingFirst())
            );
        }
    }

    /** ParallelTiedHoleKeyOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelTiedHoleKeyOrderCases {
        private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);

        /** 六个键的本体（同一批实例，创建序固定；它们的哈希序由会话定）。 */
        private static final List<AEKey> KEYS = List.of(
                AEItemKey.of(Items.LAPIS_LAZULI),
                AEItemKey.of(Items.DIAMOND),
                AEItemKey.of(Items.REDSTONE),
                AEItemKey.of(Items.EMERALD),
                AEItemKey.of(Items.GOLD_INGOT),
                AEItemKey.of(Items.IRON_INGOT));

        /**
         * 内容序＝注册序＝排水序，并且<b>按构造确定不等于本会话的哈希序</b>（台账 #141 第 1 条的牙）。
         *
         * <p>为什么必须这样摆：账本键是同一批 {@code static final} 的 {@code AEKey} 实例，
         * {@code HashMap} 的遍历序只看键的哈希、与插入序无关 ⇒ "反序构造样板实例"那条等价腿对
         * "外层容器退回 {@code HashMap}"这一型<b>恒真</b>，绝对钉值只剩 {@code 1-1/k!} 的概率牙。
         * 这里改用同一批键实测出的哈希序的<b>反序</b>当期望序：反序必不等于哈希序（互异元素），
         * 产品一旦退回哈希序，两条臂都<b>每次会话确定红</b>。
         */
        private static final List<AEKey> CONTENT_ORDER = reverseOf(hashOrderWhenInsertedAs(KEYS));

        /**
         * 反事实读数：把 {@code insertionOrder} 原样喂进一只 {@code HashMap}——那正是"外层容器退回
         * 哈希序"时产品会走的那条路——交出它的遍历序。
         */
        private static List<AEKey> hashOrderWhenInsertedAs(List<AEKey> insertionOrder) {
            var probe = new java.util.HashMap<AEKey, Boolean>();
            for (var key : insertionOrder) {
                probe.put(key, Boolean.TRUE);
            }
            return List.copyOf(probe.keySet());
        }

        private static List<AEKey> reverseOf(List<AEKey> keys) {
            var out = new ArrayList<>(keys);
            Collections.reverse(out);
            return List.copyOf(out);
        }

        /**
         * 确定性牙：期望序（＝注册序）按构造是本会话哈希序的反序，所以"产品若走哈希序"给出的读数
         * 必不等于钉值。这一条在两条臂的开头各跑一次，红字直接点名判据自身失效的那一种会话。
         */
        private static void assertPinnedOrderIsNotTheHashOrder() {
            var pinned = CONTENT_ORDER.stream().map(String::valueOf).toList();
            var counterfactual = hashOrderWhenInsertedAs(CONTENT_ORDER).stream()
                    .map(String::valueOf)
                    .toList();
            assertNotEquals(pinned, counterfactual,
                    () -> "判据自身失效：这批键的 HashMap 遍历序碰巧等于钉住的注册序，绝对钉值挡不住"
                            + "退回哈希序那一型（换键，不是放宽判据）");
        }

        /** 六个键各欠 1 枚（一发一枚、网络与自产都没有）：并列 hole 的整档形状。 */
        void tiedHolesAreNamedInTheOrderTheDrainBilledThem() {
            var expected = CONTENT_ORDER.stream().map(String::valueOf).toList();
            assertPinnedOrderIsNotTheHashOrder();
            var first = readBuild(false);
            var second = readBuild(true);

            assertTrue(first.line().contains(" unbalanced=" + CONTENT_ORDER.size() + "/"),
                    () -> "前提读数：六个键各一个洞：" + first.line());
            assertEquals(expected, first.printedHoleKeys(),
                    "并列 hole 的点名次序＝排水记账序（一把尺都不加）：" + first.line());
            // 这一条只挡"点名跟着样板实例的身份哈希走"那一型：账本键是上面那批 static final 的
            // AEKey，反序创建样板实例并不换它们的哈希序 ⇒ 它对"退回 AEKey 哈希序"恒真，牙在上面那条。
            assertEquals(expected, second.printedHoleKeys(),
                    "换一组样板实例（身份哈希不同、创建顺序相反）后，同一份计划该给出同一份点名次序");
            assertEquals(first.line(), second.line(),
                    "整行读数随样板实例变＝还在读实例哈希序：" + second.line());
        }

        /**
         * 同型臂走 <b>plan 表</b>（{@code adoptPlannedMaterialPlan} 收下规划出口的配给表，
         * 读数走 {@code billPlannedMaterialHoles} → {@code sumAssignedByKey}）——生产主路是这一条，
         * 上面那条自算臂（{@code src=submit}）盖不住它（台账 #141 第 2 条）。
         *
         * <p>这一臂上并列洞的点名次序由 {@code ParallelExecutingCraftingJob#rationPatternsInExecutionOrder}
         * （任务表过 {@link RecursiveTaskOrdering}）交回，与配给那本身份表的插入序无关：两次构造只换
         * 身份表的插入序，整行读数必须逐字相同，且等于自算臂那份期望序（同一把尺、同一份注册序）。
         */
        void tiedHolesAreNamedInTheSameOrderOnThePlanTableArm() {
            var expected = CONTENT_ORDER.stream().map(String::valueOf).toList();
            assertPinnedOrderIsNotTheHashOrder();
            var first = readPlanArmBuild(false);
            var second = readPlanArmBuild(true);

            assertTrue(first.line().contains(" src=plan"),
                    () -> "这一臂读的是照单采用的配给表（自算臂压根不碰它）：" + first.line());
            assertTrue(first.line().contains(" unbalanced=" + CONTENT_ORDER.size() + "/"),
                    () -> "前提读数：六个键各一个洞：" + first.line());
            assertEquals(expected, first.printedHoleKeys(),
                    () -> "plan 表臂的并列 hole 点名次序＝任务表那把尺的登记序：" + first.line());
            assertEquals(first.line(), second.line(),
                    () -> "换一种配给身份表插入序就换一份点名＝还在吃 System.identityHashCode："
                            + second.line());
        }

        /** 一次构造：造计划 → 建作业 → 走真读数口。 */
        private static Build readBuild(boolean reverseCreationOrder) {
            // 实例创建序可以反过来摆（登记序不变）：这一份序对账面没有任何语义，正是不许影响点名次序的东西
            var creationOrder = new ArrayList<>(CONTENT_ORDER);
            if (reverseCreationOrder) {
                Collections.reverse(creationOrder);
            }
            var byKey = new LinkedHashMap<AEKey, TiedHolePattern>();
            for (var key : creationOrder) {
                byKey.put(key, new TiedHolePattern((AEItemKey) key));
            }
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (var key : CONTENT_ORDER) {
                times.put(byKey.get(key), 1L);
            }
            var plan = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            // 前提读数：任务表顺位＝注册序，且这一档没有环成员（那把尺不动并列项）
            var ordered = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
            assertEquals(CONTENT_ORDER, ordered.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                    "夹具前提：推送底序没立住，本用例的点名次序就无从判读");

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            return new Build(line, printedHoleKeys(line));
        }

        /** 一次构造的读数：整行与按出现次序点名的洞键。 */
        private record Build(String line, List<String> printedHoleKeys) {
        }

        /**
         * plan 表臂的一次构造：配给表随 {@link RecursiveCraftingPlan} 出门，
         * {@code describePlanBalance} 走 {@code adoptPlannedMaterialPlan}（{@code src=plan}）。
         *
         * @param reversedRationInsert 假＝按注册序把六张插进配给那本身份表，真＝反着插（同一批实例）
         */
        private static Build readPlanArmBuild(boolean reversedRationInsert) {
            var byKey = new LinkedHashMap<AEKey, TiedHolePattern>();
            for (var key : CONTENT_ORDER) {
                byKey.put(key, new TiedHolePattern((AEItemKey) key));
            }
            var times = new LinkedHashMap<IPatternDetails, Long>();
            for (var key : CONTENT_ORDER) {
                times.put(byKey.get(key), 1L);
            }
            var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), times);

            var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
            var insertionOrder = new ArrayList<>(CONTENT_ORDER);
            if (reversedRationInsert) {
                Collections.reverse(insertionOrder);
            }
            for (var key : insertionOrder) {
                // 每张样板配自己那一号键 1 枚，执行侧供给里没有这一号键 ⇒ 六个并列 hole
                var ration = new WideKeyCounter();
                ration.add(key, 1L);
                assigned.put(byKey.get(key), ration);
            }
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 1L,
                    Map.of(), assigned, null, null, java.util.Set.of(), new IdentityHashMap<>());
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
            var ordered = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
            assertEquals(CONTENT_ORDER, ordered.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                    "夹具前提：推送底序没立住，plan 表臂的点名次序就无从判读");

            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            return new Build(line, printedHoleKeys(line));
        }

        /** 按出现次序把 {@code xxx hole=} 点名的键抠出来。 */
        private static List<String> printedHoleKeys(String line) {
            var printed = new ArrayList<String>();
            var matcher = java.util.regex.Pattern.compile(" (minecraft:[a-z_]+) hole=").matcher(line);
            while (matcher.find()) {
                printed.add(matcher.group(1));
            }
            return List.copyOf(printed);
        }

        /** 一格单候选（内容序那一号键）的样板；不覆写 equals/hashCode＝每组实例一位身份哈希。 */
        private static final class TiedHolePattern implements IPatternDetails {

            private final AEItemKey key;

            private TiedHolePattern(AEItemKey key) {
                this.key = key;
            }

            @Override
            public AEItemKey getDefinition() {
                return key;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new TiedHoleSlot(key)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(CELL, 1L));
            }
        }

        private record TiedHoleSlot(AEKey candidate) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(candidate, 1L)};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
                return candidate.equals(input);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelTiedHoleKeyOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTiedHoleKeyOrderCases.tiedHolesAreNamedInTheOrderTheDrainBilledThem", () -> new ParallelTiedHoleKeyOrderCases().tiedHolesAreNamedInTheOrderTheDrainBilledThem()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelTiedHoleKeyOrderCases.tiedHolesAreNamedInTheSameOrderOnThePlanTableArm", () -> new ParallelTiedHoleKeyOrderCases().tiedHolesAreNamedInTheSameOrderOnThePlanTableArm())
            );
        }
    }

    /** ParallelUnboundedSourceExtractionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelUnboundedSourceExtractionCases {
        /** 就地产出的那一个键：创造盘／无限岩浆这一型，池子里永远躺着 0。 */
        private static final AEItemKey INFINITE_SOURCE = AEItemKey.of(Items.LAVA_BUCKET);
        /** 同物品的两个数据变体：受损键具有无限来源不代表全新键也具有。 */
        private static AEItemKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        private static final AEItemKey INFINITE_WORN = wornKey(50);
        private static final AEItemKey SAME_BODY_FRESH = wornKey(0);
        private static final AEItemKey ORE = AEItemKey.of(Items.IRON_ORE);
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

        /** 无限格每发的件数：1000 枚一发，四发就是 4000 枚——这一格永远拿不出这个数。 */
        private static final long MINTED_UNITS_PER_FIRING = 1000L;

        private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
        private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

        /** 没有别的任务占料、也没有提交期配给，且<b>不</b>认任何键为无限源：老口径逐字不变。 */
        private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), NO_PLAN, NO_TAKEN);

        /** 同样没有占料与配给，但规划侧认下了 {@code INFINITE_SOURCE} 那一族。 */
        private static final ParallelPlanReservation RESERVATION_UNBOUNDED = MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), NO_PLAN, NO_TAKEN, Set.of(INFINITE_SOURCE));

        /** 无限格（1000 枚/发）+ 有限格（{@code ORE} 1 枚/发）：份数只可能由有限格钉住。 */
        private static final FakePattern INFINITE_THEN_ORE = new FakePattern(
                AEItemKey.of(Items.GRINDSTONE),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(INFINITE_SOURCE,
                                MINTED_UNITS_PER_FIRING)}, 1L, BUCKET, Set.of(INFINITE_SOURCE)),
                        new FakeInput(new GenericStack[]{new GenericStack(ORE, 1)}, 1L, null, Set.of(ORE))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /**
         * 同一型，但无限格<b>一发真需 {@code >long}</b>（模板量 {@code Long.MAX/2} × 倍率 4）：
         * 宽域付款那一条腿要就地记真值，窄域付款那一条腿要照旧整批归位。
         */
        private static final long HUGE_TEMPLATE_UNITS = Long.MAX_VALUE / 2L;
        private static final long HUGE_MULTIPLIER = 4L;
        private static final BigInteger HUGE_FIRING_UNITS =
                BigInteger.valueOf(HUGE_TEMPLATE_UNITS).multiply(BigInteger.valueOf(HUGE_MULTIPLIER));
        private static final FakePattern INFINITE_BEYOND_LONG = new FakePattern(
                AEItemKey.of(Items.SMOKER),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(INFINITE_SOURCE,
                                HUGE_TEMPLATE_UNITS)}, HUGE_MULTIPLIER, null, Set.of(INFINITE_SOURCE)),
                        new FakeInput(new GenericStack[]{new GenericStack(ORE, 1)}, 1L, null, Set.of(ORE))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 有限格一发真需 {@code >long}：窄域 holder 装不下它，整批必须归位（用来触发回滚那一支）。 */
        private static final FakePattern ORE_BEYOND_LONG = new FakePattern(
                AEItemKey.of(Items.BLAST_FURNACE),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(INFINITE_SOURCE,
                                MINTED_UNITS_PER_FIRING)}, 1L, null, Set.of(INFINITE_SOURCE)),
                        new FakeInput(new GenericStack[]{new GenericStack(ORE, HUGE_TEMPLATE_UNITS)},
                                HUGE_MULTIPLIER, null, Set.of(ORE))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /** 现场压根没有的那一格：用来把整批逼回滚。 */
        private static final AEItemKey MISSING = AEItemKey.of(Items.NETHER_STAR);

        /**
         * 同一格<b>混付</b>：先真抽走 1 枚 {@code ORE}，再退到无限候选就地认 1 枚（倍率 2）。
         * 第二格现场没有 ⇒ 整批归位，这一格两种来源都躺在 holder 里。
         */
        private static final FakePattern MIXED_THEN_SHORT = new FakePattern(
                AEItemKey.of(Items.CRAFTING_TABLE),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(ORE, 1),
                                        new GenericStack(INFINITE_SOURCE, 1)},
                                2L, null, Set.of(ORE, INFINITE_SOURCE)),
                        new FakeInput(new GenericStack[]{new GenericStack(MISSING, 1)},
                                1L, null, Set.of(MISSING))
                },
                List.of(new GenericStack(PRODUCT, 1)));

        /**
         * 机制本身：无限格不再"连候选都递不出来"，份数由有限格现场量钉住。
         *
         * <p>改前这一条红在 {@code prepare} 直接交 {@code null}——池子里 {@code INFINITE_SOURCE}=0，
         * {@code findFuzzyTemplates} 递不出任何键，{@code primaryCandidate} 返回 null，整批退回单发混取
         * 也照样 null，于是 {@code pushed=0} 永久停滞。
         */
        void unboundedSlotPaysInPlaceWhileTheFiniteSlotPinsTheFirings() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            var outputs = new KeyCounter();
            var containers = new KeyCounter();

            var batch = ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, pool, null, 10L,
                    outputs, containers, RESERVATION_UNBOUNDED);

            assertNotNull(batch, "无限格递不出候选就把整批判死：这一发明明付得起");
            assertEquals(4L, batch.executions(), "份数由有限格的现场量钉住，不被无限格的 0 存量夹成 0 发");
            assertEquals(MINTED_UNITS_PER_FIRING * 4L, batch.holder()[0].get(INFINITE_SOURCE),
                    "就地认量按真值进 holder：4 发 × 1000 枚");
            assertEquals(4L, batch.holder()[1].get(ORE));
            assertEquals(0, WideCraftingInventory.amount(pool, INFINITE_SOURCE).signum(),
                    "池子里该键仍是 0：holder 交出的那一份不来自池子");
            assertEquals(0L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE), "有限格的 4 枚真被抽走");
            assertEquals(4L, outputs.get(PRODUCT), "产出预期按实收份数挂");
            assertEquals(4L, containers.get(BUCKET), "回流容器按实收份数挂");
        }

        /**
         * 样板需要全新镐，但来源只提供受损键，不能凭空生成全新资源。
         */
        void unconfiguredVariantCannotBeGeneratedFromAnotherInfiniteVariant() {
            var pattern = new FakePattern(AEItemKey.of(Items.CAMPFIRE),
                    new FakeInput[]{
                            new FakeInput(new GenericStack[]{new GenericStack(SAME_BODY_FRESH,
                                    MINTED_UNITS_PER_FIRING)}, 1L, null, Set.of(SAME_BODY_FRESH)),
                            new FakeInput(new GenericStack[]{new GenericStack(ORE, 1)}, 1L, null, Set.of(ORE))
                    },
                    List.of(new GenericStack(PRODUCT, 1)));
            var pool = widePool();
            pool.insert(ORE, 2L, Actionable.MODULATE);
            var reservation = MaterialAllocationFixtures.build(
                    Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                    Map.of(), NO_PLAN, NO_TAKEN, Set.of(INFINITE_WORN));
            assertFalse(reservation.isUnboundedSupply(SAME_BODY_FRESH));

            var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 9L,
                    new KeyCounter(), new KeyCounter(), reservation);

            assertNull(batch);
            assertEquals(2L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(0L, pool.extract(SAME_BODY_FRESH, Long.MAX_VALUE, Actionable.SIMULATE));
        }

        /**
         * 就地认量的那一截<b>一枚都不许落进池子</b>，成功与回滚两型都要成立。
         *
         * <p>回滚型（有限格一发真需 {@code >long}）才是这一条的用力点：{@code reinjectTaken} 只要把
         * holder 里的每一格都 {@code insert} 回去，无限格那一份就地造出来的料就进了池子，下一刻被
         * {@code storeItems()} 当残池投回网络 —— 凭空多一份料，本仓已因此回退过一次。
         */
        void mintedUnitsNeverLandInThePoolOnSuccessNorOnRollback() {
            var successPool = widePool();
            successPool.insert(ORE, 4L, Actionable.MODULATE);
            var success = ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, successPool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_UNBOUNDED);
            assertNotNull(success);
            assertEquals(0, WideCraftingInventory.amount(successPool, INFINITE_SOURCE).signum(),
                    "成功那一趟：holder 交出 4000 枚，池子里该键仍必须是 0");

            var rollbackPool = widePool();
            rollbackPool.insert(ORE, Long.MAX_VALUE, Actionable.MODULATE);
            assertNull(ParallelBatchExtraction.prepare(ORE_BEYOND_LONG, rollbackPool, null, 2L,
                            new KeyCounter(), new KeyCounter(), RESERVATION_UNBOUNDED),
                    "有限格那一发装不进 holder：整批一枚不付");
            assertEquals(0, WideCraftingInventory.amount(rollbackPool, INFINITE_SOURCE).signum(),
                    "整批归位时无限格是「本就不存在」，塞回池子就是凭空多一份料");
            assertEquals(Long.MAX_VALUE, WideCraftingInventory.amount(rollbackPool, ORE).longValueExact(),
                    "真抽走的那一格照旧原数回池");
        }

        /**
         * 混槽回滚（独立复核 P1-a）：同一格里先<b>真抽走</b>一段有限候选、再退到无限候选把剩下的补齐。
         *
         * <p>标记按"格"跳过的写法会把这一格里<b>真从池子抽走</b>的那一枚永久丢在池外——整批归位时
         * 它压根不在归还名单里。按<b>键</b>相减才既留住真料、又不把凭空的量还回池子。
         */
        void mixedSlotRollbackReturnsTheRealUnitAndKeepsTheMintedOneOutOfThePool() {
            var pool = widePool();
            pool.insert(ORE, 1L, Actionable.MODULATE);

            assertNull(ParallelBatchExtraction.prepare(MIXED_THEN_SHORT, pool, null, 1L,
                            new KeyCounter(), new KeyCounter(), RESERVATION_UNBOUNDED),
                    "第三格现场没有：整批必须归位");
            assertEquals(1L, WideCraftingInventory.amount(pool, ORE).longValueExact(),
                    "真从池子抽走的那一枚必须原路回来：按格跳过会把它永久丢在池外");
            assertEquals(0, WideCraftingInventory.amount(pool, INFINITE_SOURCE).signum(),
                    "就地认量的那一枚不许进池子：进池即被 storeItems() 当残池投回网络 = 凭空多一份料");
        }

        /** 付款循环里的真值账：{@code >long} 的一发就地认量时，真值记进 {@code exactPaid}，holder 只留长读数。 */
        void beyondLongUnboundedFiringRecordsItsTrueValueNotTheSaturatedReadout() {
            var pool = widePool();
            pool.insert(ORE, 3L, Actionable.MODULATE);
            var prototype = ParallelBatchExtraction.prepareUnitPrototype(INFINITE_BEYOND_LONG, pool, null,
                    new KeyCounter(), RESERVATION_UNBOUNDED, null);

            assertNotNull(prototype, "无限格真需 >long 时宽域付款就地认量，不许因为「池子里是 0」付不出这一发");
            assertEquals(1L, prototype.executions());
            assertEquals(HUGE_FIRING_UNITS, prototype.exactPaid()[0].getExact(INFINITE_SOURCE),
                    "销账读的是这一发的真值");
            assertEquals(Long.MAX_VALUE, prototype.holder()[0].get(INFINITE_SOURCE),
                    "holder 只能钉在天花板上：它是长读数，不是量");
            assertEquals(0, WideCraftingInventory.amount(pool, INFINITE_SOURCE).signum());
            assertEquals(2L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                    "有限格那一枚真从池子里被抽走（3 枚起点）");
        }

        /**
         * 申报上界同尺：无限格不参与"现场量 ÷ 每发件数"。
         *
         * <p>改前这里返回 0 —— 于是 {@code declaredReject} 一路累加（实机 99），声明形态压根不适用。
         */
        void declaredCeilingIsNotZeroedByTheUnboundedSlot() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);

            var declared = ParallelBatchExtraction.declaredPayableCeiling(INFINITE_THEN_ORE, pool, null,
                    RESERVATION_UNBOUNDED, new BigInteger("10000000000000000000"));

            assertEquals(BigInteger.valueOf(4L), declared,
                    "上界由有限格的现场量给出：无限格给得出任意多枚，就不是这一格的约束项");
        }

        /** 对照：不认任何键为无限源时，申报上界与批量口径逐字保持老形状（真缺料仍然算缺料）。 */
        void aSourceThatIsNotUnboundedStillStallsAsMissing() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);

            var declared = ParallelBatchExtraction.declaredPayableCeiling(INFINITE_THEN_ORE, pool, null,
                    RESERVATION_FREE, BigInteger.TEN);
            assertEquals(BigInteger.ZERO, declared, "没被认成无限源的键仍按现场量算：0 枚就是 0 发");
            assertNull(ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE));
        }

        /**
         * 取证：一条 {@code noCandWhy=<none>} 定不了因。这一行要点名<b>哪张样板第几格</b>、
         * 候选表里有哪些键、其中哪些被认成无限源、池子里对应多少。
         */
        void noCandidateForensicsNamesThePatternSlotItsCandidatesAndTheirPool() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();

            assertNull(ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_FREE, probe));

            assertEquals(1L, probe.ncNoListing, "这一格连候选都递不出来");
            assertTrue(probe.noCandKey.contains("minecraft:lava_bucket"),
                    "候选表里的键要点名：" + probe.noCandKey);
            assertTrue(probe.noCandKey.contains("slot0"),
                    "第几格要点名：" + probe.noCandKey);
            assertTrue(probe.noCandKey.contains(String.valueOf(INFINITE_THEN_ORE.getDefinition())),
                    "哪张样板要点名：" + probe.noCandKey);
            assertTrue(probe.noCandKey.contains("pool=0"),
                    "池子里对应多少要点名：" + probe.noCandKey);
            assertTrue(probe.noCandKey.contains("unbounded=no"),
                    "其中哪些是无限键要点名：" + probe.noCandKey);
        }

        /**
         * 就地认量的枚数不许吃这张样板的配给份额（独立复核 P0-3）。
         *
         * <p>销账把 mint 的全额记进 {@code planMaterialEaten} 的话，凭空的一份就把份额吃穿：
         * 真键的 {@code usableFor} 归 0、整条 lane 锁死（实机 {@code ncLocked=857}、池里 1.8e19 富余
         * 一格不放）。真从池子抽走的那几枚照旧入账，一分不少。
         */
        void mintedUnitsNeverEatThePatternQuota() {
            var job = new ParallelExecutingCraftingJob(
                    new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, false, false,
                            new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                    ignored -> {
                    }, (CraftingLink) null, null);
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);

            var batch = ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_UNBOUNDED);
            assertNotNull(batch);
            ParallelCraftingCpuLogic.recordTakenInputs(job, INFINITE_THEN_ORE, batch);

            var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(INFINITE_THEN_ORE);
            assertNotNull(eaten);
            assertEquals(0, eaten.getExact(INFINITE_SOURCE).signum(),
                    "凭空认量的枚数不许进 ate：它会把真键的配额吃穿");
            assertEquals(4L, eaten.getExact(ORE).longValueExact(),
                    "真从池子抽走的那 4 枚照旧入账，一分不少");
        }

        /** 被认成无限源的键不再落在 {@code nolist} 那一档：它现在递得出候选、也付得出这一格。 */
        void anUnboundedSlotIsNoLongerCountedAsHavingNoCandidate() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            var probe = new ParallelBatchExtraction.Probe();

            var batch = ParallelBatchExtraction.prepare(INFINITE_THEN_ORE, pool, null, 4L,
                    new KeyCounter(), new KeyCounter(), RESERVATION_UNBOUNDED, probe);

            assertNotNull(batch);
            assertEquals(0L, probe.ncNoListing, "停滞行把「看不见」报成「没料」的那一档不该再涨");
            assertEquals(0L, probe.ncEmpty, "无限格就地认量，也不是真缺料");
            assertEquals(1L, probe.uniformOk);
        }

        private static WideCraftingInventory widePool() {
            return new WideCraftingInventory(ignored -> {
            });
        }

        /**
         * 回补那条腿不再搬<b>账上已认</b>的无限源：取料点就地认量之后，搬进池子的同一份量要么被记两次、
         * 要么留下残池被 {@code storeItems()} 投回网络（凭空多一份料）。
         *
         * <p>对照半边未声明身份，模拟成功也不能转成无限来源；缓冲和真实材料保持原样。
         */
        void obligationRefillStopsHaulingARecognizedUnboundedSourceIntoThePool() {
            var pool = widePool();
            pool.insert(ORE, 4L, Actionable.MODULATE);
            var known = new CreativeRefill();

            long pulled = ParallelBatchExtraction.refillObligationBatch(INFINITE_THEN_ORE, pool, null,
                    2L, RESERVATION_UNBOUNDED, known);
            assertEquals(0L, pulled, "已认下的无限源不经回补这一条腿");
            assertEquals(0, known.pulls, "也不向网络伸手");
            assertEquals(0, WideCraftingInventory.amount(pool, INFINITE_SOURCE).signum(),
                    "池子里不许躺着一截无限源残池");

            var paid = ParallelBatchExtraction.payDeclaredObligation(INFINITE_THEN_ORE, pool, null,
                    2L, RESERVATION_UNBOUNDED, null);
            assertNotNull(paid, "回补一字不搬，这一批照样付得出去");
            assertEquals(2L, paid.executions());
            assertEquals(MINTED_UNITS_PER_FIRING * 2L, paid.holder()[0].get(INFINITE_SOURCE));
            assertEquals(2L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE), "有限格真付了 2 枚");

            // 对照：没有已核对来源身份，拒收时保持缺料，不生成资源。
            var freshPool = widePool();
            freshPool.insert(ORE, 4L, Actionable.MODULATE);
            var fresh = new CreativeRefill();
            long freshPulled = ParallelBatchExtraction.refillObligationBatch(INFINITE_THEN_ORE, freshPool,
                    null, 2L, RESERVATION_FREE, fresh);
            assertEquals(0L, freshPulled, "真实拒收：没有实物进入池子");
            assertEquals(0, WideCraftingInventory.amount(freshPool, INFINITE_SOURCE).signum(),
                    "注进池子那一写法留下的残池，唯一去处是 storeItems() 投回网络 = 凭空多一份料");
            assertTrue(fresh.noted.isEmpty(), "模拟答应但实际拒收不构成无限供给身份");
            assertEquals(4L, WideCraftingInventory.amount(freshPool, ORE).longValueExact(),
                    "这一批没付出去：有限格那一截原样留在池里，不是被吞掉");
        }

        /** 网络"报得出取不出"的通道：SIMULATE 有求必应，MODULATE 一枚不给。 */
        private static final class CreativeRefill implements ParallelBatchExtraction.ObligationRefill {
            private final java.util.Set<AEKey> noted = new java.util.HashSet<>();
            private int pulls;

            @Override
            public long pull(@Nullable AEKey what, long wanted) {
                pulls++;
                return 0L;
            }

            @Override
            public long probe(@Nullable AEKey what, long wanted) {
                return what == null || wanted <= 0L ? 0L : wanted;
            }

            @Override
            public void noteUnboundedSupply(@Nullable AEKey what) {
                if (what != null) {
                    noted.add(what);
                }
            }
        }

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
            public boolean isValid(AEKey input, Level level) {
                return accepts.contains(input);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return remainder;
            }
        }

        private record FakePattern(AEItemKey definition, FakeInput[] slots,
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
            org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.unboundedSlotPaysInPlaceWhileTheFiniteSlotPinsTheFirings", () -> new ParallelUnboundedSourceExtractionCases().unboundedSlotPaysInPlaceWhileTheFiniteSlotPinsTheFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.unconfiguredVariantCannotBeGeneratedFromAnotherInfiniteVariant", () -> new ParallelUnboundedSourceExtractionCases().unconfiguredVariantCannotBeGeneratedFromAnotherInfiniteVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.mintedUnitsNeverLandInThePoolOnSuccessNorOnRollback", () -> new ParallelUnboundedSourceExtractionCases().mintedUnitsNeverLandInThePoolOnSuccessNorOnRollback()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.mixedSlotRollbackReturnsTheRealUnitAndKeepsTheMintedOneOutOfThePool", () -> new ParallelUnboundedSourceExtractionCases().mixedSlotRollbackReturnsTheRealUnitAndKeepsTheMintedOneOutOfThePool()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.beyondLongUnboundedFiringRecordsItsTrueValueNotTheSaturatedReadout", () -> new ParallelUnboundedSourceExtractionCases().beyondLongUnboundedFiringRecordsItsTrueValueNotTheSaturatedReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.declaredCeilingIsNotZeroedByTheUnboundedSlot", () -> new ParallelUnboundedSourceExtractionCases().declaredCeilingIsNotZeroedByTheUnboundedSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.aSourceThatIsNotUnboundedStillStallsAsMissing", () -> new ParallelUnboundedSourceExtractionCases().aSourceThatIsNotUnboundedStillStallsAsMissing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.noCandidateForensicsNamesThePatternSlotItsCandidatesAndTheirPool", () -> new ParallelUnboundedSourceExtractionCases().noCandidateForensicsNamesThePatternSlotItsCandidatesAndTheirPool()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.mintedUnitsNeverEatThePatternQuota", () -> new ParallelUnboundedSourceExtractionCases().mintedUnitsNeverEatThePatternQuota()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.anUnboundedSlotIsNoLongerCountedAsHavingNoCandidate", () -> new ParallelUnboundedSourceExtractionCases().anUnboundedSlotIsNoLongerCountedAsHavingNoCandidate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUnboundedSourceExtractionCases.obligationRefillStopsHaulingARecognizedUnboundedSourceIntoThePool", () -> new ParallelUnboundedSourceExtractionCases().obligationRefillStopsHaulingARecognizedUnboundedSourceIntoThePool())
            );
        }
    }

    /** ParallelUntrustedProductionClaimCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelUntrustedProductionClaimCases {
        private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);
        /** 计划产量超出 long、但池中确有有限实物的键。 */
        private static final AEKey CUBE = AEItemKey.of(new ItemStack(Items.ENDER_EYE));
        /** 把贷方账顶撞顶的那张样板自己吃的键。 */
        private static final AEKey DIRT = AEItemKey.of(new ItemStack(Items.DIRT));
        /** 欠账发数：恰好占用池中全部现货。 */
        private static final long OWED = 1_000L;

        void unrepresentableFutureProductionStillReservesExistingStockForDebt() {
            // producer 一发产 Long.MAX_VALUE 枚 CUBE：这本贷方账撞顶 ⇒ CUBE 进"没有可信口径"的名单
            var producer = pattern(Items.GRAVEL, new GenericStack(DIRT, 1L),
                    new GenericStack(CUBE, Long.MAX_VALUE));
            // debtor 是申报出去、还欠着 OWED 发材料的那一张；declarer 是同吃 CUBE 的下一张
            var debtor = pattern(Items.STONE, new GenericStack(CUBE, 1L),
                    new GenericStack(AEItemKey.of(new ItemStack(Items.COBBLESTONE)), 1L));
            var declarer = pattern(Items.OBSIDIAN, new GenericStack(CUBE, 1L),
                    new GenericStack(AEItemKey.of(new ItemStack(Items.STONE)), 1L));

            var pool = new WideCraftingInventory(ignored -> {
            });
            pool.insert(CUBE, OWED, Actionable.MODULATE);
            var job = newJob();
            job.tasks.put(producer, progress(1L));
            job.tasks.put(debtor, progress(OWED + 1L));
            job.tasks.put(declarer, progress(1L));
            job.batchObligations.incur(debtor, BigInteger.valueOf(OWED));
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                    Map.of(CUBE, OWED, DIRT, 1L), job.materialAllocation);

            assertEquals(1, reservation.untrustedProductionKeyCount(),
                    "前提读数：CUBE 这本产出贷方账放不下可信口径（实机同一行的 prodUntrusted=1）");
            assertTrue(reservation.productionHasNoTrustworthyReading(CUBE),
                    "确认计划产量投影不可信，以覆盖旧补丁错误豁免的条件");

            var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                    job.tasks.entrySet(), pool, reservation);
            assertEquals(BigInteger.valueOf(OWED), claims.getExact(CUBE),
                    "未来产量超出 long 不改变已经存在的实物：欠账仍须预留池内口粮");

            assertEquals(BigInteger.ZERO, ParallelBatchExtraction.declaredPayableCeiling(
                    declarer, pool, null, reservation, BIG_LONG, claims),
                    "不能把同一份池内实物再次申报给新批次");
            assertEquals(OWED, ParallelCraftingCpuLogic.payObligationBatch(job, debtor, OWED,
                    pool, null, reservation, new ParallelPoolLedger(), null, null));
            assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(debtor));
            assertEquals(BigInteger.ZERO, pool.amount(CUBE), "已预留实物完整用于原欠账交割");
        }

        void continuingDebtsRotateSoTheFifthPatternGetsPaidNextRound() {
            var job = newJob();
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(CUBE, BIG_LONG.multiply(BIG_LONG).multiply(BigInteger.valueOf(100L)), Actionable.MODULATE);
            for (Item definition : List.of(Items.STONE, Items.GRAVEL, Items.OBSIDIAN,
                    Items.COBBLESTONE, Items.SAND)) {
                var debtor = pattern(definition, new GenericStack(CUBE, Long.MAX_VALUE),
                        new GenericStack(DIRT, 1L));
                job.tasks.put(debtor, progress(10L));
                job.batchObligations.incur(debtor, BIG_LONG.multiply(BigInteger.valueOf(20L)));
            }
            var fifth = job.batchObligations.pendingPatterns().get(4);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                    Map.of(CUBE, Long.MAX_VALUE), job.materialAllocation);
            var probe = new ParallelBatchExtraction.Probe();
            assertEquals(Long.MAX_VALUE, ParallelCraftingCpuLogic.payObligationBatches(job, pool, null,
                    reservation, new ParallelPoolLedger(), null, probe, () -> true));
            assertEquals(4L, probe.obligationBatches, "每轮四批预算保持不变，单批可以整笔扣清大数库存");
            assertEquals(BIG_LONG.multiply(BigInteger.valueOf(20L)), job.batchObligations.pendingFirings(fifth));
            assertEquals(BIG_LONG.multiply(BigInteger.valueOf(20L)), job.batchObligations.totalPending());
            assertEquals(BIG_LONG.multiply(BIG_LONG).multiply(BigInteger.valueOf(20L)), pool.amount(CUBE));
            assertEquals(Long.MAX_VALUE, ParallelCraftingCpuLogic.payObligationBatches(job, pool, null,
                    reservation, new ParallelPoolLedger(), null, probe, () -> true));
            assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(fifth),
                    "第五张在下一轮取得交割机会，一次付清");
            assertEquals(BigInteger.ZERO, job.batchObligations.totalPending());
            assertEquals(BigInteger.ZERO, pool.amount(CUBE));
        }

        void exhaustedBudgetLeavesUnattemptedDebtAtTheFront() {
            var job = newJob();
            for (Item definition : List.of(Items.STONE, Items.GRAVEL)) {
                var debtor = pattern(definition, new GenericStack(CUBE, Long.MAX_VALUE), new GenericStack(DIRT, 1L));
                job.tasks.put(debtor, progress(10L));
                job.batchObligations.incur(debtor, BigInteger.TEN);
            }
            var initial = job.batchObligations.pendingPatterns();
            var pool = new WideCraftingInventory(ignored -> {});
            pool.insertExact(CUBE, BIG_LONG.multiply(BigInteger.valueOf(100L)), Actionable.MODULATE);
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(),
                    Map.of(CUBE, Long.MAX_VALUE), job.materialAllocation);
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            assertEquals(10L, ParallelCraftingCpuLogic.payObligationBatches(job, pool, null,
                    reservation, new ParallelPoolLedger(), null, new ParallelBatchExtraction.Probe(),
                    () -> checks.getAndIncrement() == 0));
            assertEquals(initial.get(1), job.batchObligations.pendingPatterns().getFirst());
            assertEquals(BigInteger.TEN, job.batchObligations.pendingFirings(initial.get(1)));
            assertEquals(BigInteger.ZERO, job.batchObligations.pendingFirings(initial.getFirst()));
            assertEquals(BIG_LONG.multiply(BigInteger.valueOf(90L)), pool.amount(CUBE));
        }

        void failedSettlementRotatesWithoutChangingDebtOrStock() {
            var job = newJob();
            var debtor = pattern(Items.STONE, new GenericStack(CUBE, 1L), new GenericStack(DIRT, 1L));
            var other = pattern(Items.GRAVEL, new GenericStack(CUBE, 1L), new GenericStack(DIRT, 1L));
            job.tasks.put(debtor, progress(10L));
            job.tasks.put(other, progress(10L));
            job.batchObligations.incur(debtor, BigInteger.TEN);
            job.batchObligations.incur(other, BigInteger.TEN);
            var first = job.batchObligations.pendingPatterns().getFirst();
            var pool = new WideCraftingInventory(ignored -> {});
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(), job.materialAllocation);
            assertEquals(0L, ParallelCraftingCpuLogic.payObligationBatch(job, first, 1L,
                    pool, null, reservation, new ParallelPoolLedger(), null, null));
            assertEquals(first, job.batchObligations.pendingPatterns().getLast());
            assertEquals(BigInteger.valueOf(20L), job.batchObligations.totalPending());
            assertEquals(BigInteger.ZERO, pool.amount(CUBE));
        }

        private static ParallelExecutingCraftingJob newJob() {
            ICraftingPlan plan = new CraftingPlan(new GenericStack(CUBE, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            return new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);
        }

        private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
            var progress = new ParallelExecutingCraftingJob.TaskProgress();
            progress.value = firings;
            return progress;
        }

        /** 一张只有一个候选、原样吃掉给定输入的单槽样板（退料键为 null ⇒ 不是模具槽）。 */
        private static FakePattern pattern(Item definition, GenericStack input, GenericStack output) {
            return new FakePattern(AEItemKey.of(new ItemStack(definition)),
                    new GenericStack[] {output},
                    new IPatternDetails.IInput[] {new FakeInput(input)});
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelUntrustedProductionClaimCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUntrustedProductionClaimCases.unrepresentableFutureProductionStillReservesExistingStockForDebt", () -> new ParallelUntrustedProductionClaimCases().unrepresentableFutureProductionStillReservesExistingStockForDebt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUntrustedProductionClaimCases.continuingDebtsRotateSoTheFifthPatternGetsPaidNextRound", () -> new ParallelUntrustedProductionClaimCases().continuingDebtsRotateSoTheFifthPatternGetsPaidNextRound()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUntrustedProductionClaimCases.exhaustedBudgetLeavesUnattemptedDebtAtTheFront", () -> new ParallelUntrustedProductionClaimCases().exhaustedBudgetLeavesUnattemptedDebtAtTheFront()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelUntrustedProductionClaimCases.failedSettlementRotatesWithoutChangingDebtOrStock", () -> new ParallelUntrustedProductionClaimCases().failedSettlementRotatesWithoutChangingDebtOrStock())
            );
        }
    }

    /** ParallelVariantAttributionOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class ParallelVariantAttributionOrderCases {
        private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
        private static final AEItemKey PATTERN_ITEM = AEItemKey.of(Items.STONE);

        /** 一族（同一把铁镐）的五个变体键本体（创建序固定，哈希序由会话定）。 */
        private static final AEKey V1 = wornKey(10);
        private static final AEKey V2 = wornKey(20);
        private static final AEKey V3 = wornKey(30);
        private static final AEKey V4 = wornKey(40);
        private static final AEKey V5 = wornKey(50);

        /**
         * 候选顺位＝登记顺位＝摊派顺位，并且<b>按构造确定不等于本会话的哈希序</b>（台账 #141 第 1 条）。
         *
         * <p>这一批账本键是 {@code static final} 的同一批实例 ⇒ {@code HashMap} 的遍历序与插入序无关
         * ⇒ "把 {@code usedItems} 反着写"那条等价腿对"付款键容器退回 {@code HashMap}"这一型<b>恒真</b>，
         * 绝对钉值只剩 {@code 1-1/k!} 的概率牙。所以候选顺位取"同一批键实测出的 {@code HashMap}
         * 遍历序的反序"：反序必不等于哈希序 ⇒ 产品一退回哈希序，本用例每次会话都确定红。
         */
        private static final List<AEKey> CANDIDATES = reverseOfHashOrder(List.of(V1, V2, V3, V4, V5));

        /** 用产品同一批键建 {@code HashMap}，交出其遍历序的反序（＝本用例钉住的候选顺位）。 */
        private static List<AEKey> reverseOfHashOrder(List<AEKey> keys) {
            return List.copyOf(reversed(hashOrderWhenInsertedAs(keys)));
        }

        /**
         * 反事实读数：把 {@code insertionOrder} 这笔序按原样喂进一只 {@code HashMap}（＝容器退回
         * 哈希序时产品会走的那条路），交出它的遍历序。
         */
        private static List<AEKey> hashOrderWhenInsertedAs(List<AEKey> insertionOrder) {
            var probe = new java.util.HashMap<AEKey, Boolean>();
            for (var key : insertionOrder) {
                probe.put(key, Boolean.TRUE);
            }
            return List.copyOf(probe.keySet());
        }

        private static List<AEKey> reversed(List<AEKey> keys) {
            var out = new ArrayList<>(keys);
            Collections.reverse(out);
            return List.copyOf(out);
        }

        /** 族到账只有登记首位那一张的 10 枚。 */
        private static final long ARRIVED = 10L;

        private static AEKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        /**
         * 独立手算表（纸面算出，不经被测代码）。一发一枚、50 发；每一号候选的池子里躺着 10 枚，
         * 排水按候选顺位逐号榨干 ⇒ 五个变体各付 10 枚，共 50＝全部发数（无缺口）。
         *
         * <table border="1">
         * <caption>#137：候选顺位登记 + 族池 10 的摊派</caption>
         * <tr><th>变体（候选顺位）</th><th>mint 份额</th><th>族摊派后</th></tr>
         * <tr><td>候选首位</td><td>10</td><td>10（族池 10 枚被登记首位那一号摊走）</td></tr>
         * <tr><td>候选第 2..5 号</td><td>各 10</td><td>各 0</td></tr>
         * <tr><td>族合计</td><td>50</td><td>10＝族到账</td></tr>
         * </table>
         *
         * <p>"首位到底是哪一号耐久"随会话变（候选顺位按构造取本会话哈希序的反序，见上面那条），
         * <b>不随会话变</b>的是相对归属：只有候选首位那一号保住 10 枚，其余四号摊到 0。
         * 登记序若是哈希序，"10 枚归谁"就换人——被保住的那一格不再是候选首位那一号。
         */
        void variantSharesFollowSlotCandidateOrderNotKeyHash() {
            // 确定性牙（#141 第 1 条）：把候选顺位原样喂进一只 HashMap——那正是"付款键容器退回
            // 哈希序"时产品会走的那条路。它的遍历序必须不等于候选顺位，钉值才每次会话都能抓住回归。
            assertNotEquals(candidateOrderReadout(),
                    hashOrderWhenInsertedAs(CANDIDATES).stream()
                            .map(ParallelVariantAttributionOrderCases::label).toList(),
                    () -> "判据自身失效：这批键的哈希序碰巧等于候选顺位，绝对钉值挡不住退回哈希序");
            var ordered = readBuild(false);
            var shuffledInsertion = readBuild(true);

            assertEquals(candidateOrderReadout(), ordered.registeredVariants(),
                    "配给账里这一族的变体顺位＝这一槽的候选顺位（排水按它逐号付，摊派按它逐号扣）");
            var handTable = new java.util.LinkedHashMap<String, BigInteger>();
            for (int index = 0; index < CANDIDATES.size(); index++) {
                handTable.put(label(CANDIDATES.get(index)),
                        index == 0 ? BigInteger.valueOf(ARRIVED) : BigInteger.ZERO);
            }
            assertEquals(Map.copyOf(handTable), ordered.shares,
                    "族池 10 枚该归候选顺位首位那一个变体（手算表）");
            assertEquals(BigInteger.valueOf(ARRIVED), ordered.familyTotal,
                    "族合计收到族到账 10：不是 50（没收窄）也不是 0（把保住的那一格也夹没）");
            assertEquals(ARRIVED, ordered.quotaOfFirst, "取料点向候选首位那一号放行 10");

            assertEquals(ordered.registeredVariants(), shuffledInsertion.registeredVariants(),
                    "与本顺位无关的插入序（usedItems 反着写、换一组样板实例）不许改登记序");
            assertEquals(ordered.shares, shuffledInsertion.shares,
                    "两档归属逐字相同：哪个变体保住 10 枚、哪几个摊到 0");
            assertEquals(ordered.familyTotal, shuffledInsertion.familyTotal);
        }

        /**
         * 一次构造的全部读数。变体一律以<b>耐久值</b>点名——{@code AEItemKey#toString} 不印组件，
         * 五个变体打出来是同一串 {@code minecraft:iron_pickaxe (with patches)}，红字会无从判读。
         */
        private record Build(List<String> registeredVariants, Map<String, BigInteger> shares,
                BigInteger familyTotal, long quotaOfFirst) {
        }

        private static List<String> candidateOrderReadout() {
            return CANDIDATES.stream().map(ParallelVariantAttributionOrderCases::label).toList();
        }

        private static String label(AEKey key) {
            return "dmg=" + ((AEItemKey) key).toStack().getDamageValue();
        }

        /**
         * @param reverseSupplyInsertionOrder 第二次构造：把 {@code usedItems} 那几笔反着写——它只喂
         *                                    池子的<b>查表</b>，不该决定任何顺位
         */
        private static Build readBuild(boolean reverseSupplyInsertionOrder) {
            var pattern = new FuzzyConsumerPattern();
            var used = new KeyCounter();
            var supplies = new ArrayList<>(CANDIDATES);
            if (reverseSupplyInsertionOrder) {
                Collections.reverse(supplies);
            }
            for (AEKey variant : supplies) {
                used.add(variant, ARRIVED);
            }
            var plan = new CraftingPlan(new GenericStack(CELL, 1L), 0L, false, false,
                    used, new KeyCounter(), new KeyCounter(),
                    new java.util.LinkedHashMap<IPatternDetails, Long>(Map.of(pattern, 50L)));
            var job = new ParallelExecutingCraftingJob(plan, ignored -> {
            }, (CraftingLink) null, null);

            // 真链第一步：提交期自算配给趟（排水按候选顺位逐号付）
            ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
            var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
            assertTrue(line.contains(" src=submit"), () -> "读的就是自算那一臂：" + line);
            assertTrue(line.contains(" ok keys="), () -> "50 发全被候选池盖住，本用例不该有缺口：" + line);

            // 真链第二步：唯一的族收窄口（族到账按<b>候选首位</b>那一号的池子计）
            var firstCandidate = CANDIDATES.get(0);
            job.narrowPlanMaterialShareByArrival(firstCandidate, PlanAmount.of(ARRIVED), PlanAmount.ZERO);

            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
            var registered = new ArrayList<String>();
            var shares = new java.util.LinkedHashMap<String, BigInteger>();
            var familyTotal = BigInteger.ZERO;
            for (AEKey variant : assigned.keys()) {
                registered.add(label(variant));
                shares.put(label(variant), assigned.getExact(variant));
                familyTotal = familyTotal.add(assigned.getExact(variant));
            }
            var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                    job.materialAllocation);
            return new Build(List.copyOf(registered), Map.copyOf(shares), familyTotal,
                    reservation.remainingQuota(pattern, firstCandidate));
        }

        /** 一格五个候选（同族五个变体）的消费者样板；不覆写 equals/hashCode＝每组实例一位身份哈希。 */
        private static final class FuzzyConsumerPattern implements IPatternDetails {

            @Override
            public AEItemKey getDefinition() {
                return PATTERN_ITEM;
            }

            @Override
            public IInput[] getInputs() {
                var candidates = new GenericStack[CANDIDATES.size()];
                for (int index = 0; index < candidates.length; index++) {
                    candidates[index] = new GenericStack(CANDIDATES.get(index), 1L);
                }
                return new IInput[] {new FuzzySlot(candidates)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(CELL, 1L));
            }
        }

        /** 候选顺位＝数组序（AE2 的模糊槽就是这个形状）；一发一枚、无退料。 */
        private record FuzzySlot(GenericStack[] candidates) implements IPatternDetails.IInput {

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey key, @Nullable net.minecraft.world.level.Level level) {
                for (var candidate : candidates) {
                    if (candidate.what().equals(key)) {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ParallelVariantAttributionOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ParallelVariantAttributionOrderCases.variantSharesFollowSlotCandidateOrderNotKeyHash", () -> new ParallelVariantAttributionOrderCases().variantSharesFollowSlotCandidateOrderNotKeyHash())
            );
        }
    }

    /** PatternStableIdentityCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternStableIdentityCases {
        private static final AEItemKey OUT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey IN_A = AEItemKey.of(Items.IRON_NUGGET);
        private static final AEItemKey IN_B = AEItemKey.of(Items.GOLD_NUGGET);

        /** 同定义、同输出、同输入槽的两张实例：{@code #s} 摘要必须一致（跨会话可对上号）。 */
        void sameRecipeDifferentInstancesShareStableDigest() {
            var one = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);
            var two = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);

            assertEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(one)),
                    stablePart(ParallelCraftingCpuLogic.patternIdentityTag(two)),
                    "同配方的两张实例，#s 摘要必须逐字相同（这才是跨会话可比的身份）");
        }

        /** 换输入槽的键：配方变了，{@code #s} 摘要必须跟着变。 */
        void differentRecipeYieldsDifferentStableDigest() {
            var a = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);
            var b = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_B);

            assertNotEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(a)),
                    stablePart(ParallelCraftingCpuLogic.patternIdentityTag(b)),
                    "配方不同就换 #s：否则把两张不同的样板当成同一张");
        }

        /**
         * useless 折叠壳<b>不改写</b> {@code #s} 这句话不许留——本用例按读到的壳算式做一个等价壳，
         * 只折两条腿（{@code getOutputs()} 每枚 {@code amount × k}、
         * {@code getInputs()[i].getMultiplier() × k}），连 {@code definition} 都<b>原样留着</b>，
         * {@code #s} 照样变。真壳还有第三条腿更狠：
         * {@code ScaledProcessingPattern} 的 definition 是
         * {@code SmartDoublingPatterns.executionDefinition(inner, k)}——同一件物品上把
         * {@code SMART_DOUBLING_OPERATIONS} 组件先 remove 再 set 成 {@code k}。
         *
         * <p>依据（不是推断）：{@code useless_mod-1.21.1-2.3.9.jar} 里
         * {@code ScaledProcessingPattern} 与 {@code ScaledProcessingPattern$ScaledInput} 的字节码
         * （构造器对每个输出走 {@code Math.multiplyExact(GenericStack.amount, operationsPerPush)}，
         * {@code ScaledInput.getMultiplier} 对倍率走同一句乘法；留档见
         * {@code .scratch/javap-221R2-scaled-processing.txt}）。
         * 所以两档名单（{@code stage=exit} 对 {@code stage=build}）跨这道壳时，对号只认
         * token 里的 {@code definition} ＋份数，同名并列才退化到 {@code #i}/{@code #s} 分实例。
         */
        void scalingFoldingShellDoesNotPreserveStableDigest() {
            var inner = pattern(AEItemKey.of(Items.IRON_INGOT), List.of(new GenericStack(
                    AEItemKey.of(Items.IRON_NUGGET), 2L)), 3L, AEItemKey.of(Items.RAW_IRON));
            var shell = new ScalingShell(inner, 4L);

            assertEquals(inner.getDefinition(), shell.getDefinition(),
                    () -> "夹具前提：这一臂只折份数两条腿，definition 逐字不动（真壳连它也要改）");
            assertNotEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(inner)),
                    stablePart(ParallelCraftingCpuLogic.patternIdentityTag(shell)),
                    () -> "折叠壳内外 #s 必须不同：摘要吃的是 outputs 与槽倍率，壳把两条都 ×k。"
                            + "壳内=" + ParallelCraftingCpuLogic.patternIdentityTag(shell)
                            + " 壳外=" + ParallelCraftingCpuLogic.patternIdentityTag(inner));
            assertEquals("#i" + Integer.toHexString(System.identityHashCode(shell))
                            + stablePart(ParallelCraftingCpuLogic.patternIdentityTag(shell)),
                    ParallelCraftingCpuLogic.patternIdentityTag(shell),
                    "身份标还是 #i+#s 两段（跨壳对不上号这件事由 #s 变、#i 也变共同决定）");
        }

        // ---- fixtures ----

        /**
         * 按 useless {@code ScaledProcessingPattern} 读到的算式做的等价壳：输出每枚 ×k、
         * 槽倍率 ×k、候选表与 definition 原样（真壳的 definition 那一条腿另由 javadoc 记着）。
         */
        private static final class ScalingShell implements IPatternDetails {
            private final IPatternDetails inner;
            private final long k;

            ScalingShell(IPatternDetails inner, long k) {
                this.inner = inner;
                this.k = k;
            }

            @Override
            public IInput[] getInputs() {
                var raw = inner.getInputs();
                var scaled = new IInput[raw.length];
                for (int index = 0; index < raw.length; index++) {
                    scaled[index] = new StubInput(raw[index].getPossibleInputs(),
                            Math.multiplyExact(raw[index].getMultiplier(), k));
                }
                return scaled;
            }

            @Override
            public List<GenericStack> getOutputs() {
                return inner.getOutputs().stream()
                        .map(stack -> new GenericStack(stack.what(),
                                Math.multiplyExact(stack.amount(), k)))
                        .toList();
            }

            @Override
            public AEItemKey getDefinition() {
                return inner.getDefinition();
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return inner.supportsPushInputsToExternalInventory();
            }
        }

        private static String stablePart(String tag) {
            int at = tag.indexOf("#s");
            assertTrue(at >= 0, "身份标缺 #s 段：" + tag);
            return tag.substring(at);
        }

        /** 一张只读身份的样板替身：definition/输出/单输入槽由参数定死，摘要就吃这几样。 */
        private static IPatternDetails pattern(AEItemKey definition, List<GenericStack> outputs,
                long multiplier, AEItemKey inputKey) {
            return new IPatternDetails() {
                @Override
                public IInput[] getInputs() {
                    return new IInput[] { new StubInput(new GenericStack[] { new GenericStack(inputKey, 1L) },
                            multiplier) };
                }

                @Override
                public List<GenericStack> getOutputs() {
                    return outputs;
                }

                @Override
                public AEItemKey getDefinition() {
                    return definition;
                }

                @Override
                public boolean supportsPushInputsToExternalInventory() {
                    return false;
                }
            };
        }

        private static final class StubInput implements IPatternDetails.IInput {
            private final GenericStack[] possible;
            private final long multiplier;

            StubInput(GenericStack[] possible, long multiplier) {
                this.possible = possible;
                this.multiplier = multiplier;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return possible;
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
                return possible.length > 0 && possible[0].what().equals(input);
            }

            @Override
            public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternStableIdentityCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternStableIdentityCases.sameRecipeDifferentInstancesShareStableDigest", () -> new PatternStableIdentityCases().sameRecipeDifferentInstancesShareStableDigest()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternStableIdentityCases.differentRecipeYieldsDifferentStableDigest", () -> new PatternStableIdentityCases().differentRecipeYieldsDifferentStableDigest()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternStableIdentityCases.scalingFoldingShellDoesNotPreserveStableDigest", () -> new PatternStableIdentityCases().scalingFoldingShellDoesNotPreserveStableDigest())
            );
        }
    }
}
