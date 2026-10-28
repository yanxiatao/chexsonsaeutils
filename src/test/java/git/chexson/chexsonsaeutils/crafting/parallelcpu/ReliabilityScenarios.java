package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.*;

/** 审查问题的行为回归：实际取消、接收、提交入口及存档往返。 */
final class ReliabilityScenarios {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.CHEST);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final List<Recipe> RECIPES = List.of(
            new Recipe(Items.CYAN_CANDLE), new Recipe(Items.BROWN_CANDLE),
            new Recipe(Items.GRAY_CANDLE), new Recipe(Items.LIME_CANDLE), new Recipe(Items.PINK_CANDLE));
    private static final Recipe RECIPE = RECIPES.getFirst();

    static {
        PatternDetailsHelper.registerDecoder(new IPatternDetailsDecoder() {
            @Override public boolean isEncodedPattern(ItemStack stack) {
                return RECIPES.stream().anyMatch(recipe -> stack.is(recipe.item));
            }
            @Override public IPatternDetails decodePattern(AEItemKey key, Level level) {
                return RECIPES.stream().filter(recipe -> recipe.getDefinition().equals(key)).findFirst().orElse(null);
            }
        });
    }

    static void verify(java.nio.file.Path ignored) {
        assertAll("取消、退款、补料与递归收货",
                ReliabilityScenarios::pausedCancellationSettlesWideDebtAcrossReload,
                ReliabilityScenarios::ordinaryCompletionKeepsUnpaidBatchMaterials,
                ReliabilityScenarios::ordinaryDeclaredReceiptFinishesAfterSettlingRetainedZeroTask,
                ReliabilityScenarios::cancelledAndFailedSubmissionRefundsSurviveReload,
                ReliabilityScenarios::recursiveRequesterRetriesExactRemainderAfterReload,
                ReliabilityScenarios::zeroStockPartialSubmissionRetainsExternalDemand);
    }

    private static void pausedCancellationSettlesWideDebtAcrossReload() {
        var network = new Storage();
        var cluster = cluster(network);
        var lane = registeredLane(cluster);
        var owed = MAX.multiply(BigInteger.valueOf(9)).add(BigInteger.valueOf(9));
        var job = job(lane, true, true);
        job.batchObligations.incur(RECIPE, owed);
        for (Recipe recipe : RECIPES.subList(1, RECIPES.size())) {
            job.tasks.put(recipe, new ParallelExecutingCraftingJob.TaskProgress());
            job.batchObligations.incur(recipe, BigInteger.ONE);
        }
        job.suspended = true;
        load(lane, job, MATERIAL, owed.add(BigInteger.valueOf(11)));

        cluster.cancelLane(lane);
        assertEquals(1, cluster.activeLaneCount(), "未付材料不能随取消删除 lane");
        assertTrue(lane.logic().hasJob());
        assertTrue(lane.getLastLink().isCanceled());
        assertEquals(BigInteger.ZERO, network.amount(MATERIAL));
        lane.logic().tickCraftingLogic(null, null, 1, null, null);
        assertTrue(lane.logic().hasJob(), "每 tick 分批结算，暂停不应阻止取消后的清算");
        assertEquals(BigInteger.ZERO, network.amount(MATERIAL), "未清偿前不能退款");

        var saved = lane.writeToNBT(REGISTRIES);
        assertTrue(saved.getCompound("logic").getCompound("job").getBoolean("cancellationRequested"));
        var restored = ParallelCraftingLaneState.readFromNBT(cluster, saved, REGISTRIES, 2);
        for (int tick = 2; tick < 10 && restored.logic().hasJob(); tick++) {
            restored.logic().tickCraftingLogic(null, null, tick, null, null);
        }
        assertFalse(restored.logic().hasJob());
        assertFalse(restored.isLaneActive());
        assertEquals(BigInteger.valueOf(7), network.amount(MATERIAL), "整笔义务实际扣除，只退真实余料");
    }

    private static void ordinaryCompletionKeepsUnpaidBatchMaterials() {
        var network = new Storage();
        var cluster = cluster(network);
        var lane = registeredLane(cluster);
        var job = job(lane, false, true);
        job.batchObligations.incur(RECIPE, BigInteger.valueOf(9));
        job.waitingFor.insert(PRODUCT, 1, Actionable.MODULATE);
        load(lane, job, MATERIAL, BigInteger.valueOf(16));
        var requester = new Requester();
        requester.limit = Long.MAX_VALUE;
        CraftingLink link = (CraftingLink) lane.getLastLink();
        attach(link, requester);

        assertEquals(1, lane.logic().insert(PRODUCT, 1, Actionable.MODULATE));
        assertTrue(lane.logic().hasJob());
        assertFalse(link.isDone(), "requester 收货不能免除宿主已接单批次的材料义务");
        assertEquals(BigInteger.ZERO, network.amount(MATERIAL));
        lane.logic().tickCraftingLogic(null, null, 1, null, null);
        assertFalse(lane.logic().hasJob());
        assertTrue(link.isDone());
        assertEquals(BigInteger.ONE, requester.delivered);
        assertEquals(BigInteger.valueOf(7), network.amount(MATERIAL));
    }

    private static void ordinaryDeclaredReceiptFinishesAfterSettlingRetainedZeroTask() {
        var network = new Storage();
        var cluster = cluster(network);
        var lane = registeredLane(cluster);
        var job = job(lane, true, true);
        job.batchObligations.incur(RECIPE, BigInteger.valueOf(9));
        var output = new KeyCounter(); output.add(PRODUCT, 1);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, output, new KeyCounter(), output);
        var receipt = new git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt("test-machine");
        UUID batchId = UUID.randomUUID();
        receipt.noteBatchId(batchId);
        job.declaredTerminals.noteAdmitted(receipt, RECIPE, BigInteger.ONE, 0,
                job::rollBackDeclaredBatchFirings, Map.of(PRODUCT, BigInteger.ONE), false,
                job::creditDeclaredBatchDelivered);
        receipt.deliverTerminal(batchId,
                git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(0, job.remainingAmount, "真实 SUCCESS 回执确认终产物已经入网");
        assertFalse(job.completionRequested, "回执只更新交割账，不直接走 finishJob");
        assertEquals(1, job.tasks.size(), "零发任务仍因材料债务保留");
        load(lane, job, MATERIAL, BigInteger.valueOf(16));
        lane.logic().tickCraftingLogic(null, null, 1, null, null);
        assertFalse(lane.logic().hasJob(), "清债后必须清理零发任务并结束直投订单");
        assertEquals(BigInteger.valueOf(7), network.amount(MATERIAL));
        assertEquals(BigInteger.ZERO, network.amount(PRODUCT), "回执不能重复生成已经入网的产物");
        assertTrue(lane.logic().isInventoryEmpty());
    }

    private static void cancelledAndFailedSubmissionRefundsSurviveReload() {
        for (boolean failedSubmission : List.of(false, true)) {
            var network = new Storage();
            network.insertLimit = BigInteger.ZERO;
            var cluster = cluster(network);
            if (failedSubmission) {
                network.contents.put(MATERIAL, BigInteger.valueOf(3));
                var used = new KeyCounter(); used.add(MATERIAL, 9);
                var plan = new CraftingPlan(new GenericStack(PRODUCT, 1), 0, false, false,
                        used, new KeyCounter(), new KeyCounter(), Map.of());
                assertFalse(cluster.submitJob(cluster.grid(), plan, null, IActionSource.empty(), 0).successful());
            } else {
                var lane = registeredLane(cluster);
                lane.logic().insertBuffered(MATERIAL, 3, Actionable.MODULATE);
                cluster.cancelLane(lane);
            }
            assertEquals(1, cluster.activeLaneCount(), "网络拒收时保留退款 lane");
            var saved = new CompoundTag(); cluster.writeToNBT(saved, REGISTRIES);
            var lanes = saved.getList("parallelCpuLanes", Tag.TAG_COMPOUND);
            assertEquals(1, lanes.size());
            var restored = ParallelCraftingLaneState.readFromNBT(cluster, lanes.getCompound(0), REGISTRIES, 1);
            assertFalse(restored.logic().hasJob());
            assertEquals(3, restored.logic().getBufferedAmount(MATERIAL));
            network.insertLimit = MAX;
            restored.logic().tickCraftingLogic(null, null, 1, null, null);
            assertFalse(restored.isLaneActive());
            assertEquals(BigInteger.valueOf(3), network.amount(MATERIAL));
        }
    }

    private static void recursiveRequesterRetriesExactRemainderAfterReload() {
        var network = new Storage();
        var cluster = cluster(network);
        var lane = registeredLane(cluster);
        BigInteger order = MAX.multiply(BigInteger.TWO).add(BigInteger.TEN);
        var base = new CraftingPlan(new GenericStack(PRODUCT, Long.MAX_VALUE), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new WideAmountCraftingPlan(base, WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(),
                Set.of(), PlanAmount.of(order));
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), false, false), lane.linkCpu());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        job.dyeableRecursivePlan = true;
        load(lane, job, PRODUCT, order);
        var requester = new Requester();
        attach((CraftingLink) lane.getLastLink(), requester);
        lane.logic().tickCraftingLogic(null, null, 1, null, null);
        assertTrue(lane.logic().hasJob(), "完全拒收时作业和产物均须保留");
        assertEquals(BigInteger.ZERO, requester.delivered);
        requester.limit = 17;
        lane.logic().tickCraftingLogic(null, null, 2, null, null);
        assertEquals(BigInteger.valueOf(17), requester.delivered);
        assertTrue(lane.logic().hasJob(), "部分接收不能 markDone 或将剩余产物退网");
        assertEquals(BigInteger.ZERO, network.amount(PRODUCT));

        var restored = ParallelCraftingLaneState.readFromNBT(cluster, lane.writeToNBT(REGISTRIES), REGISTRIES, 3);
        CraftingLink restoredLink = (CraftingLink) restored.getLastLink();
        attach(restoredLink, requester);
        requester.limit = Long.MAX_VALUE;
        restored.logic().tickCraftingLogic(null, null, 3, null, null);
        assertFalse(restored.logic().hasJob());
        assertTrue(restoredLink.isDone());
        assertEquals(order, requester.delivered, "重载后只继续剩余量，不重复首轮已交的 17 枚");
        assertEquals(BigInteger.ZERO, network.amount(PRODUCT));
        assertTrue(restored.logic().isInventoryEmpty());
    }

    private static void zeroStockPartialSubmissionRetainsExternalDemand() {
        var network = new Storage();
        var cluster = cluster(network);
        var missing = new KeyCounter(); missing.add(MATERIAL, 9);
        var plan = new CraftingPlan(new GenericStack(PRODUCT, 9), 0, true, false,
                new KeyCounter(), new KeyCounter(), missing, Map.of(RECIPE, 9L));
        assertTrue(cluster.submitPartialJob(cluster.grid(), plan, null, IActionSource.empty(), 0).successful());
        var lane = activeLane(cluster);
        var tag = new CompoundTag(); lane.logic().writeToNBT(tag, REGISTRIES);
        var restoredJob = new ParallelExecutingCraftingJob(tag.getCompound("job"), REGISTRIES, ignored -> {}, lane);
        assertEquals(BigInteger.valueOf(9), restoredJob.initialItemsRemaining.amount(MATERIAL));
        assertEquals(PlanAmount.ZERO, restoredJob.waitingForExact(MATERIAL), "外部补料不得伪装为在途产物");
        var provider = new ProductionProvider();
        var energy = new Energy();
        var service = new appeng.me.service.CraftingService(cluster.grid(), cluster.grid().getStorageService(), energy) {
            @Override public Iterable<appeng.api.networking.crafting.ICraftingProvider> getProviders(IPatternDetails key) {
                return List.of(provider);
            }
        };
        network.contents.put(MATERIAL, BigInteger.valueOf(4));
        for (int tick = 1; tick <= 4; tick++) tickAndReturn(lane, service, energy, provider, tick);
        assertEquals(4L, provider.consumed, "第一次补料必须能通过真实配额与派发链被机器消费");
        assertTrue(lane.logic().hasJob());
        network.contents.put(MATERIAL, BigInteger.valueOf(20));
        for (int tick = 100; tick < 120 && lane.logic().hasJob(); tick++) {
            tickAndReturn(lane, service, energy, provider, tick);
        }
        assertFalse(lane.logic().hasJob(), "补齐原料后整单完成，而非困在提交时收窄成零的配额");
        assertEquals(9L, provider.consumed, "只派发原计划的九次，不额外消费原材料");
        assertEquals(BigInteger.valueOf(9), network.amount(PRODUCT));
        assertEquals(BigInteger.valueOf(15), network.amount(MATERIAL), "只提取计划缺口，不能多取");
        assertTrue(lane.logic().isInventoryEmpty());
    }

    private static void tickAndReturn(ParallelCraftingLaneState lane, appeng.me.service.CraftingService service,
            Energy energy, ProductionProvider provider, long tick) {
        lane.logic().tickCraftingLogic(energy, service, tick, null, null);
        if (provider.pending > 0) {
            long produced = provider.pending;
            provider.pending = 0;
            assertEquals(produced, lane.logic().insert(PRODUCT, produced, Actionable.MODULATE));
        }
    }

    private static final class ProductionProvider implements appeng.api.networking.crafting.ICraftingProvider {
        long consumed;
        long pending;
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(RECIPE); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] holder) {
            long amount = holder[0].get(MATERIAL);
            assertTrue(amount > 0);
            consumed += amount;
            pending += amount;
            return true;
        }
    }

    private static final class Energy implements appeng.api.networking.energy.IEnergyService {
        @Override public double extractAEPower(double amount, Actionable mode, appeng.api.config.PowerMultiplier multiplier) { return amount; }
        @Override public double getIdlePowerUsage() { return 0; }
        @Override public double getChannelPowerUsage() { return 0; }
        @Override public double getAvgPowerUsage() { return 0; }
        @Override public double getAvgPowerInjection() { return 0; }
        @Override public boolean isNetworkPowered() { return true; }
        @Override public double injectPower(double amount, Actionable mode) { return 0; }
        @Override public double getStoredPower() { return Double.MAX_VALUE; }
        @Override public double getMaxStoredPower() { return Double.MAX_VALUE; }
        @Override public double getEnergyDemand(double maxRequired) { return 0; }
    }

    private static ParallelCraftingCpuCluster cluster(Storage storage) {
        IGrid grid = new DeliveryScenarios.ParallelRecursiveFinalOutputDeliveryCases.TestGrid(storage);
        var host = new ParallelCpuHost() {
            @Override public void saveChanges() {}
            @Override public IGrid getGrid() { return grid; }
            @Override public Level getLevel() { return null; }
            @Override public IGridNode getActionableNode() { return null; }
            @Override public CpuSelectionMode getSelectionMode() { return CpuSelectionMode.ANY; }
            @Override public boolean isParallelCpuProviderActive() { return true; }
            @Override public boolean canProcessParallelCpuJobs() { return true; }
        };
        return new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
    }

    private static ParallelCraftingLaneState registeredLane(ParallelCraftingCpuCluster cluster) {
        var plan = new CraftingPlan(new GenericStack(PRODUCT, 1), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        assertTrue(cluster.submitJob(cluster.grid(), plan, null, IActionSource.empty(), 0).successful());
        return activeLane(cluster);
    }

    private static ParallelCraftingLaneState activeLane(ParallelCraftingCpuCluster cluster) {
        var lanes = new ArrayList<ParallelCraftingLane>(); cluster.appendActiveLanes(lanes);
        return (ParallelCraftingLaneState) lanes.getFirst();
    }

    private static ParallelExecutingCraftingJob job(ParallelCraftingLaneState lane, boolean standalone, boolean withTask) {
        var plan = new CraftingPlan(new GenericStack(PRODUCT, 1), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), withTask ? Map.of(RECIPE, 1L) : Map.of());
        var link = new CraftingLink(CraftingCpuHelper.generateLinkData(lane.laneId(), standalone, false), lane.linkCpu());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        if (withTask) job.tasks.get(RECIPE).consumeFirings(1);
        return job;
    }

    private static void load(ParallelCraftingLaneState lane, ParallelExecutingCraftingJob job, AEKey key, BigInteger amount) {
        var tag = new CompoundTag(); tag.put("job", job.writeToNBT(REGISTRIES));
        var pool = new WideCraftingInventory(ignored -> {}); pool.insertExact(key, amount, Actionable.MODULATE);
        tag.put("inventory", pool.writeToNBT(REGISTRIES)); lane.logic().readFromNBT(tag, REGISTRIES);
    }

    private static void attach(CraftingLink cpuLink, ICraftingRequester requester) {
        var nexus = new CraftingLinkNexus(cpuLink.getCraftingID()); cpuLink.setNexus(nexus);
        var requesterLink = new CraftingLink(CraftingCpuHelper.generateLinkData(cpuLink.getCraftingID(), false, true), requester);
        requesterLink.setNexus(nexus);
    }

    private static final class Requester implements ICraftingRequester {
        long limit;
        BigInteger delivered = BigInteger.ZERO;
        @Override public long insertCraftedItems(ICraftingLink link, AEKey key, long amount, Actionable mode) {
            long accepted = Math.min(limit, amount);
            if (mode == Actionable.MODULATE) delivered = delivered.add(BigInteger.valueOf(accepted));
            return accepted;
        }
        @Override public ImmutableSet<ICraftingLink> getRequestedJobs() { return ImmutableSet.of(); }
        @Override public void jobStateChange(ICraftingLink link) {}
        @Override public IGridNode getActionableNode() { return null; }
    }

    private static final class Storage implements MEStorage {
        final Map<AEKey, BigInteger> contents = new HashMap<>();
        BigInteger insertLimit = MAX;
        BigInteger amount(AEKey key) { return contents.getOrDefault(key, BigInteger.ZERO); }
        @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            var extracted = amount(key).min(BigInteger.valueOf(amount));
            if (mode == Actionable.MODULATE) contents.put(key, amount(key).subtract(extracted));
            return extracted.longValueExact();
        }
        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            var accepted = BigInteger.valueOf(amount).min(insertLimit);
            if (mode == Actionable.MODULATE) contents.put(key, amount(key).add(accepted));
            return accepted.longValueExact();
        }
        @Override public Component getDescription() { return Component.literal("有限存储"); }
    }

    private static final class Recipe implements IPatternDetails {
        private final net.minecraft.world.item.Item item;
        Recipe(net.minecraft.world.item.Item item) { this.item = item; }
        @Override public AEItemKey getDefinition() { return AEItemKey.of(item); }
        @Override public IInput[] getInputs() {
            return new IInput[] {new IInput() {
                @Override public GenericStack[] getPossibleInputs() { return new GenericStack[] {new GenericStack(MATERIAL, 1)}; }
                @Override public long getMultiplier() { return 1; }
                @Override public boolean isValid(AEKey key, Level level) { return MATERIAL.equals(key); }
                @Override public AEKey getRemainingKey(AEKey key) { return null; }
            }};
        }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 1)); }
    }
}
