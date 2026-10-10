package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.execution.CraftingCpuHelper;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-A（实机 2026-09-28 18:51 那一跑，lane {@code 0ac8673b}）：递归计划下终点产物已经<b>交齐</b>，
 * 作业却永远不收尾——交完产物那两支只调 {@code finishDyeableRecursiveJobIfComplete()}，而那条判据
 * 只看"任务表自己清空 + 产出缓冲自己清空"，压根不看产物交没交齐。尾巴排不下去 ⇒ 判据永远不成立
 * ⇒ 不进 {@code finishJob(true)} ⇒ 库存（含环起步借的那颗种）卡在 CPU 里。
 *
 * <p>钉两件事：
 * <ul>
 *   <li><b>判据</b>：交付量（宽域真值，<b>只认实物</b>——宿主自报的 planned 量不算交付）对得上订单量就
 *       收工，任务表留着排不下去的尾巴也算收工；差一口气不收，任一已接单批次的材料义务未结清也不收；</li>
 *   <li><b>真收尾</b>：从 AE2 交进来的那一句 {@code insert} 起，走产线同一条链
 *       （{@code insertInternal} → 交付判据 → {@code finishJob(true)} → flush 交给 requester →
 *       {@code markDone}）到作业出表、库存清干净。</li>
 * </ul>
 *
 * <p><b>离线侧的两条限制（不是回避，是环境）</b>：① {@code readFromNBT} 那一条腿要活的
 * {@code Level} 才能 decode 样板，所以真链那一腿的任务表只能是空表，"排不下去的尾巴"在那里以
 * 尾巴申报出去、永不回池的那份实物（{@code waitingFor} 挂账）呈现；带真尾巴条目的那一腿走判据层
 * 断言。② 收尾里 {@code storeItems()} 要活的网格存储，本用例那份池子在 flush 之后恰好空掉
 * （终点产物就是池子的全部内容），分轮回池那一份算式由 {@code ParallelWidePoolRoundStoreTest}
 * 钉着，本刀一字未动它。
 */
final class ParallelRecursiveFinalOutputDeliveryTest {

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
    @Test
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
    @Test
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

    @Test
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
        var resumeJob = newRecursiveJob(tail, 0L, newCpuLink());
        resumeJob.waitingFor.insert(PRODUCT, ORDER, Actionable.MODULATE);
        resumeJob.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);
        var lane = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L);
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
    @Test
    void partialDeliveryBeforeReloadCountsTowardActualFinalDeliveryAfterReload() {
        var job = newRecursiveJob(Map.of(), newCpuLink());
        job.addFinalOutputDelivered(PRODUCT, 40L);
        job.waitingFor.insert(PRODUCT, 60L, Actionable.MODULATE);
        job.waitingFor.insert(TAIL_MATERIAL, 7L, Actionable.MODULATE);
        var pool = new appeng.crafting.inv.ListCraftingInventory(ignored -> {});
        pool.insert(PRODUCT, 40L, Actionable.MODULATE);
        var saved = new CompoundTag();
        saved.put("job", job.writeToNBT(REGISTRIES));
        saved.put("inventory", pool.writeToNBT(REGISTRIES));
        var lane = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L);
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
    @Test
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
    @Test
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
    @Test
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
        var requester = new RecordingRequester();
        tieLinkToRequester(cpuLink, requester);

        assertTrue(logic.tickCraftingLogic(null, null, 0L, null, null),
                "任务表已空：这一 tick 推不出任何一发，返回 idle 才对得上产线那条调度");

        assertTrue(cpuLink.isDone(), "tick 收尾没把作业推进 finishJob(true)：链接仍不是完成态");
        assertFalse(lane.isLaneActive(),
                "判据成立却没人问：作业仍挂在 lane 上（实机那一跑只能手动取消，取消后那颗种不退网、下一条 lane 的环饿死）");
        assertEquals(1L, network.stored(SEED), "环起步借的那颗种必须经 storeItems() 退回网络");
        assertEquals(0L, network.stored(PRODUCT), "网络侧不许多出终点产物：收工不伪造实物");
        assertTrue(logic.isInventoryEmpty(), "收尾后 CPU 池必须是空池");
        assertTrue(requesteeDeliveries(requester).isEmpty(),
                "planned 那一腿没有实物回执：requester 一枚不收，交付账也不许被这一改推进");
    }

    /**
     * 反向半边（不许把"每 tick 也问"做成"还没交割就收工"）：终点键在 {@code waitingFor} 上仍挂着
     * 一截<b>实物</b>欠账时，同一趟 tick 既不收工、也不许把那颗种投回网络。
     */
    @Test
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
    @Test
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
}
