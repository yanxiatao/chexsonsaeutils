package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配给表 {@code used} 腿回灌真到账量（提交短交不再留下"配额齐、池子空"的假份额），
 * 且到账记在<b>族</b>这根轴上（与封顶同一根轴：同族全到账那一档不许被同族一次短交夹成 0）。
 *
 * <p>走的是实机同一条路：提交腿真抽（{@code CraftingContinuationPartialSubmit
 * #extractAvailableInitialItems}）→ 族轴到账读数（{@code FamilyArrivalLedger}）交回 → 唯一收窄口
 * （{@code ParallelExecutingCraftingJob#narrowPlanMaterialShareByArrival}）→ 配给判据（{@code
 * ParallelPlanReservation#remainingQuota}/{@code quotaExact}）与取料点那道
 * {@code ParallelCraftingCpuLogic#capByRemainingShare}。计划配给表的采用也走真路（{@code
 * adoptPlannedMaterialPlan}）。
 *
 * <p>族轴的兜底记的是<b>量</b>不是旗（台账 #133）：短掉的那一截里，挂进在途账（{@code waitingFor}）
 * 或属于"提交这一步本来就不搬、由执行侧按批续取"（{@code >long} 喂满一整刀）的部分照数并进摊派
 * 池子；{@code pool＝到账＋那一截＋自产}、{@code allowance＝min(pool, 申报)} ⇒ 有兜底的那一员自己
 * 摊得满（对它等于不收窄），同族那一格谁都没兜底的 declared-shortfall 假配额照旧夹。
 */
final class ParallelSubmitArrivalQuotaTest {

    /** 实机那格的形状：一单里唯一候选是 enriched_shining 一类"申报了却一份料都没到"的键。 */
    private static final long SESSION_QUOTA = 42_662_400_000L;
    private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
    private static final AEKey CELL = AEItemKey.of(Items.CHEST);
    /** 环种：只有一发把它原样回炉的那张样板算环成员，摊派顺位靠它排在旁人之前。 */
    private static final AEKey SEED = AEItemKey.of(Items.BUCKET);

    // ----------------------------------------------------------------------------------------------------
    // 主用例：配额 4.27e10、提交腿到账 0、计划自产 0 ⇒ 份额收到 0，取料点不再空撞。
    // ----------------------------------------------------------------------------------------------------

    @Test
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

    @Test
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

    @Test
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
    @Test
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
    @Test
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
    @Test
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

    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
        var service = Proxy.newProxyInstance(IStorageService.class.getClassLoader(),
                new Class<?>[] {IStorageService.class},
                (proxy, method, args) -> "getInventory".equals(method.getName())
                        ? storage : fallback(method));
        return (IGrid) Proxy.newProxyInstance(IGrid.class.getClassLoader(),
                new Class<?>[] {IGrid.class},
                (proxy, method, args) -> "getStorageService".equals(method.getName())
                        ? service : fallback(method));
    }

    private static Object fallback(java.lang.reflect.Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        return 0;
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
}
