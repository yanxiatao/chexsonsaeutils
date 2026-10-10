package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明批量走「产物按宿主自报计划直接入网」这条口的用例：申报产出按机器报的枚数一次划分成
 * 预期入网 + 等实物两截，合计恒等于申报量，不重复计也不漏计。
 *
 * <p><b>预期入网那一截不是交付证据</b>：它出自宿主 {@code admit} 那一刻按配方自报的计划量，
 * 里面没有一枚"插进存储成功"的回执（见 {@code UselessBigIntegerReceipt} 的类注释）。这条口只管
 * "这一截不会再有实物回 CPU 池子"。
 *
 * <p>这条口是给 {@code UselessBigIntegerDeclaredHost} 那种宿主用的（机器把整批产物不经 CPU 池子
 * 直接交出去）。底线各一个用例：
 * <ul>
 *   <li>划分只在 {@code reserveOne} 一处发生：容器件没有"预期入网"口径，整笔仍挂实物预期；</li>
 *   <li>没实现该口的宿主逐字保持三参口径（全额挂实物），既有推送路径零行为变化；</li>
 *   <li>整批拒收也要把计划回执取走一次并清空：留着上一批的入网数会串到下一批头上；</li>
 *   <li>申报表之外的键一笔账都不许添；停滞行那一格写作 {@code planNet=}，不许冒充交付。</li>
 * </ul>
 *
 * <p><b>#227 K1 加的那道前提</b>：把产出划成"直投网络"之前，先判<b>这一笔能不能挂进在飞台账</b>
 * （宿主是 {@link DeclaredBatchTerminalCarrier} + 交回了该笔的 {@code cpuToken} + token 上有
 * {@code batchId}）。挂不上就不许划——那条口只有该笔终局 {@code SUCCESS} 才转成已交割，
 * 没有终局可等的批次划走的枚数就此成了"账上记满却没人交割"的死账（实机 2026-10-02 19:55
 * {@code push=81}／{@code declared=113} 而 {@code inflightBatches=0 undelivered=NONE}、化学红石缺
 * {@code 1.27e20} 那一型）。判定不成立 ⇒ 整批走既有的"全额挂 {@code waitingFor} 等实物"那条路
 * （语义出自 {@code UselessBigIntegerBatchClient} 的 {@code FIRST_MISSING_PLAN} 那条 WARN，
 * 本仓定过版，此处复用不新建）。四臂：
 * <ul>
 *   <li>{@link #carrierReceiptWithoutBatchIdKeepsTheWholeBatchOnThePhysicalRoute()}：token 上没
 *       {@code batchId} ⇒ 改前 {@code planNet} 记满、实物只剩差额；改后 {@code planNet=0}、整批挂实物；</li>
 *   <li>{@link #carrierWithoutAnyReceiptKeepsTheWholeBatchOnThePhysicalRoute()}：压根没交回 token
 *       ⇒ 同一档，且台账那两支静默口照旧点名（{@code noteAdmitted} 一字未改）；</li>
 *   <li>{@link #carrierReceiptWithBatchIdStillSplitsThePlannedOutputsVerbatim()}：有 {@code batchId}
 *       的正常批，两档读数<b>逐字不变</b>——挡"把更保守写成少划"的假修复；</li>
 *   <li>{@link #hostWithoutTheReporterKeepsThePhysicalRouteVerbatim()} 与
 *       {@link #nonCarrierReporterCannotSplitItsOutputsEither()}：非 carrier 宿主那一档零行为变化
 *       （极限组装机那类既不报计划入网、也不是 carrier；生产里报计划入网的宿主同时就是 carrier）。</li>
 * </ul>
 *
 * <p>夹具沿用 {@link ParallelDeclaredBatchObligationTest} 的 {@code FakePattern} 口径。
 */
final class ParallelDeclaredBatchNetworkDeliveryTest {

    private static final long UNITS_PER_FIRING = 1000L;
    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    /** 回流容器（模具/桶那一类投下去原样退回的键）：只有实物一条路。 */
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);
    /** 动态产物的真实键：申报表里没它，只可能由机器回执带回来。 */
    private static final AEItemKey DYNAMIC = AEItemKey.of(Items.CLOCK);

    private static final FakePattern THOUSAND_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(MATERIAL))},
            List.of(new GenericStack(PRODUCT, 1)));

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), NO_PLAN, NO_TAKEN);

    private static final long FIRINGS = 5_000L;

    /** 中间产物经网络回交时必须由 CPU 认领，跨 long 累计仍保存全部待收量。 */
    @Test
    void intermediateOutputsAreReservedForTheCpuAcrossLongBatches() {
        var job = newJob();
        var consumer = new FakePattern(AEItemKey.of(Items.FURNACE),
                new FakeInput[]{new FakeInput(new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                        1L, null, Set.of(PRODUCT))}, List.of(new GenericStack(DYNAMIC, 1L)));
        job.tasks.put(consumer, progress(Long.MAX_VALUE));
        var outputs = new KeyCounter();
        outputs.add(PRODUCT, Long.MAX_VALUE);

        for (int batch = 0; batch < 3; batch++) {
            var direct = ParallelCraftingCpuLogic.reserveExpectedWaiting(
                    job, outputs, new KeyCounter(), outputs);
            assertTrue(direct.isEmpty(), "本作业的中间材料不划成公共网络库存");
        }
        var exact = BIG_LONG.multiply(BigInteger.valueOf(3L));
        assertEquals(exact, git.chexson.chexsonsaeutils.bigint.WideCraftingInventory.amount(
                job.waitingFor, PRODUCT), "CPU 接收账累计完整中间产出，不饱和丢失尾段");
        assertEquals(BigInteger.ZERO, job.expectedToNetwork(PRODUCT).exactValue());
        assertEquals(exact, job.accountAnnounced.getExact(PRODUCT));
    }

    /** 发数已申报完但义务材料尚未交割，仍须接收其口粮。 */
    @Test
    void finishedConsumerWithPendingObligationStillReceivesItsInputs() {
        var job = newJob();
        var consumer = new FakePattern(AEItemKey.of(Items.FURNACE),
                new FakeInput[]{new FakeInput(new GenericStack[]{new GenericStack(PRODUCT, 1L)},
                        1L, null, Set.of(PRODUCT))}, List.of(new GenericStack(DYNAMIC, 1L)));
        job.tasks.put(consumer, progress(0L));
        job.batchObligations.incur(consumer, BigInteger.TEN);
        var outputs = new KeyCounter();
        outputs.add(PRODUCT, 10L);
        assertTrue(ParallelCraftingCpuLogic.reserveExpectedWaiting(
                job, outputs, new KeyCounter(), outputs).isEmpty());
        assertEquals(10L, job.waitingFor.extract(PRODUCT, 10L, Actionable.SIMULATE));

        job.batchObligations.settle(consumer, 10L);
        assertEquals(Map.of(PRODUCT, 10L), ParallelCraftingCpuLogic.reserveExpectedWaiting(
                job, outputs, new KeyCounter(), outputs), "没有待消费与待交割时才允许直接入网");
    }

    /** 申报产出被一次划分：自报计划那一截进预期入网账，差额才挂实物，合计恰等于申报量。 */
    @Test
    void declaredOutputsSplitOnceBetweenNetworkAndPhysicalWithoutDoubleCounting() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new CarrierReportingHost(4_000L);
        var expectedOutputs = new KeyCounter();
        var expectedContainers = new KeyCounter();

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job,
                job.tasks.entrySet(), pool,
                null, RESERVATION_FREE, new ParallelPoolLedger(), expectedOutputs, expectedContainers,
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "宿主整批接单：实收＝申报");
        assertEquals(FIRINGS, expectedOutputs.get(PRODUCT), "申报产出 5000 枚");
        assertEquals(FIRINGS * UNITS_PER_FIRING, expectedContainers.get(BUCKET),
                "回流容器按既有的倍率口径记（每发 multiplier 只），本用例只关心它整笔不被分流");

        assertEquals(BigInteger.valueOf(4_000L), job.expectedToNetwork(PRODUCT).exactValue(),
                "机器自报的 4000 枚记进预期入网账");
        assertEquals(1_000L, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "没入网的那 1000 枚才挂实物预期");
        assertEquals(BigInteger.valueOf(FIRINGS),
                job.expectedToNetwork(PRODUCT).exactValue()
                        .add(BigInteger.valueOf(
                                job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE))),
                "实物 + 预期入网 = 申报总量：一次划分，不多也不少");
        assertEquals(FIRINGS, job.accountAnnounced.getLong(PRODUCT),
                "报称产出流水账按全额记，与产物走哪条路无关");

        assertEquals(0L, job.expectedToNetwork(BUCKET).signum(),
                "宿主自报里没有容器那一键：分流只作用于产出");
        assertEquals(FIRINGS * UNITS_PER_FIRING,
                job.waitingFor.extract(BUCKET, Long.MAX_VALUE, Actionable.SIMULATE),
                "容器整笔挂实物：它必须回 CPU 才再投得出去");

        assertEquals(FIRINGS - 1L, job.batchObligations.pendingFirings(THOUSAND_A_FIRING).longValue(),
                "机器只收了单发原型：剩下 (k-1) 份挂义务账");
        assertEquals(FIRINGS * UNITS_PER_FIRING - UNITS_PER_FIRING,
                pool.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE),
                "只有原型那一发离开池子，其余仍作为欠账的口粮在池里");
    }

    /**
     * 宿主报在申报表之外的键：一笔账都不许添。
     *
     * <p>改前那一支把它"全额按已入网结算"，顺手还 {@code accountAnnounced} 复加一次——这张样板
     * 从没申报过这个键，两本账就此凭空多量（键的计划量还能超 {@code long}，那份夹到
     * {@code Long.MAX_VALUE} 的哨兵会被当成量记进去）。生产侧也根本命中不到它：申报表与宿主自报
     * 计划同出 {@code details.getOutputs()}（{@code AlloyFurnaceBigIntegerCrafting#plannedOutputs}），
     * 键集恒等；动态产物的真实键只在 {@code onBatchOutputs} 那侧才认得出来，不在这条口上。
     */
    @Test
    void plannedKeyOutsideTheAnnouncementTableEntersNoBookAtAll() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var host = new CarrierReportingHost(4_000L);
        host.extraPlannedKey = DYNAMIC;
        var expectedOutputs = new KeyCounter();

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job, job.tasks.entrySet(),
                pool(FIRINGS * UNITS_PER_FIRING), null, RESERVATION_FREE, new ParallelPoolLedger(),
                expectedOutputs, new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null, null, null,
                null, 0L);

        assertNotNull(push);
        assertEquals(0L, job.expectedToNetwork(DYNAMIC).signum(),
                "申报表之外的键不进预期入网账");
        assertEquals(0L, job.accountAnnounced.getLong(DYNAMIC),
                "申报表之外的键也不进报称产出流水账：这张样板从没申报过它");
        assertEquals(0L, job.waitingFor.extract(DYNAMIC, Long.MAX_VALUE, Actionable.SIMULATE),
                "也不挂实物预期");
        // 申报表之内的划分口径一字不变：预期 4000 + 等实物 1000 = 申报 5000
        assertEquals(BigInteger.valueOf(4_000L), job.expectedToNetwork(PRODUCT).exactValue(),
                "申报内那一截照旧进预期账");
        assertEquals(1_000L, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "自报计划没盖住的那 1000 枚才挂实物预期");
    }

    /**
     * {@code alloc=} 那一行里，预期入网的那一截<b>格名</b>必须说它是预期。
     *
     * <p>这一格的读数是宿主自报的计划量，一条都不是入网回执；过去它写作 {@code net=}，与
     * "已入网交付"同一副口径，于是"交付对平"的话术盖住了 {@code actual < planned}：少产的那一截
     * 在这行里既看不出来，还被算进"已经交付"。格名从 {@code net=} 改成 {@code planNet=} 之后，
     * 这一条把它连同数值一起钉住——改回交付口径即变红，且 {@code planNet=4000} 那一格是
     * 从 {@link ParallelCraftingCpuLogic.StallProbe#formatAllocation} 这个真渲染口出来的。
     */
    @Test
    void expectedChunkIsPrintedAsPlannedNotAsDelivered() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var ration = new WideKeyCounter();
        ration.add(MATERIAL, FIRINGS);
        MaterialAllocationFixtures.assign(job, THOUSAND_A_FIRING, ration);
        var host = new CarrierReportingHost(4_000L);
        var expectedOutputs = new KeyCounter();

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job,
                job.tasks.entrySet(), pool(FIRINGS * UNITS_PER_FIRING), null, RESERVATION_FREE,
                new ParallelPoolLedger(), expectedOutputs, new KeyCounter(), Long.MAX_VALUE,
                Long.MAX_VALUE, null, null, null, null, 0L);
        assertNotNull(push);

        var line = ParallelCraftingCpuLogic.StallProbe.formatAllocation(job,
                Map.of(MATERIAL, 0L), Map.of(PRODUCT, 1_000L), Map.of(PRODUCT, 0L), null);

        var cell = firstTokenOf(line, "planNet=");
        assertEquals("planNet=4000", cell,
                "预期入网那一格：格名带 plan 前缀，数值是自报计划的那 4000 枚。渲染行=" + line);
        // alloc= 那一段里不许再留一副交付口径的裸 net=（读起来是"已经入网"，而它只是预期）。
        // quotaBlind= 那一段的 net= 不在此列：它是向网络现问的 SIMULATE 读数，量的是"网络里有几枚"。
        var allocSegment = line.substring(line.indexOf(" alloc="), line.indexOf(" quotaBlind="));
        assertFalse(allocSegment.contains(" net="),
                "alloc= 段里的预期账必须写作 planNet=，不许冒充交付。该段=" + allocSegment);
    }

    /** 取日志/读数行里以 {@code marker} 开头的那一个空白分隔 token（没有就是 {@code <missing>}）。 */
    private static String firstTokenOf(String line, String marker) {
        for (var token : line.split(" ")) {
            if (token.startsWith(marker)) {
                return token;
            }
        }
        return "<missing>";
    }

    /** 没实现计划回执口的宿主：逐字三参口径，产物全额挂实物预期。 */
    @Test
    void hostWithoutTheReporterKeepsThePhysicalRouteVerbatim() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var expectedOutputs = new KeyCounter();

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new PlainDeclaredHost(),
                THOUSAND_A_FIRING, job, job.tasks.entrySet(), pool(FIRINGS * UNITS_PER_FIRING),
                null, RESERVATION_FREE,
                new ParallelPoolLedger(), expectedOutputs, new KeyCounter(), Long.MAX_VALUE,
                Long.MAX_VALUE, null, null, null, null, 0L);

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted());
        assertEquals(FIRINGS, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "全额等实物：与接入这条回执通道之前一字不差");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(), "一笔都不进预期入网账");
        // 台账 #225 读数 2 的接线牙：这一笔出自<b>非 carrier 宿主</b>（交不回 cpuToken），
        // 分档必须记进 declaredOther 而不是 declaredBigint——CPU 腿那句
        // noteDeclaredHostShape(host instanceof DeclaredBatchTerminalCarrier) 没接上就红在这一格。
        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("declaredOther[累计]=1", firstTokenOf(coverage, "declaredOther[累计]="),
                "别的申报宿主那一档要数到这一笔：" + coverage);
        assertEquals("declaredBigint[累计]=0", firstTokenOf(coverage, "declaredBigint[累计]="),
                "这一笔不是 carrier 宿主，不许记进 bigint 那一档：" + coverage);
        assertEquals("inflightRegistered[累计]=0", firstTokenOf(coverage, "inflightRegistered[累计]="),
                "非 carrier 天生挂不上在飞台账：" + coverage);
    }

    /** 整批拒收也要把回执取走一次并清空：上一批的入网数不许串到下一批。 */
    @Test
    void rejectedBatchDrainsTheReportSoItCannotLeakIntoTheNextBatch() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var host = new CarrierReportingHost(4_000L);
        host.accepts = false;

        var rejected = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job, job.tasks.entrySet(),
                pool(FIRINGS * UNITS_PER_FIRING), null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null, null, null,
                null, 0L);

        assertNotNull(rejected);
        assertTrue(rejected.rejected(), "宿主没收：整批作废");
        assertNull(host.takePlannedNetworkOutputs(), "计划回执已被取走并清空");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(), "拒收不入预期入网账");
        assertTrue(job.waitingFor.list.isEmpty(), "拒收不挂任何实物预期");
        assertTrue(job.accountTaken.isEmpty(), "拒收不提走任何料");

        // 下一批真接单：账只按这一批的回执结，不带上一次那份 4000
        host.accepts = true;
        var accepted = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job, job.tasks.entrySet(),
                pool(FIRINGS * UNITS_PER_FIRING), null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null, null, null,
                null, 1L);
        assertNotNull(accepted);
        assertEquals(BigInteger.valueOf(4_000L), job.expectedToNetwork(PRODUCT).exactValue(),
                "预期入网账里只有这一批自报的那一截");
    }

    // ==================== #227 K1：登记不成的那一笔，不许把产出划成"直投网络" ====================

    /**
     * carrier 交回了 token，但机器没报 {@code batchId}（台账的静默口二那一型）。
     *
     * <p><b>改前的形状</b>：{@code planNet} 记满自报的那 4000 枚、{@code waitingFor} 只剩差额 1000 枚，
     * 而那笔在飞台账压根没挂上（{@code inflightBatchCount()=0}）⇒ 实物没人等、在飞看不见、
     * {@code planNet} 记满、出表照旧——实机 2026-10-02 19:55 那一跑读到的正是这一格。
     * <b>改后</b>：判定不成立 ⇒ 划账交 {@code null} ⇒ 整批挂 {@code waitingFor} 等实物。
     */
    @Test
    void carrierReceiptWithoutBatchIdKeepsTheWholeBatchOnThePhysicalRoute() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var host = new CarrierReportingHost(4_000L);
        host.batchIdOnReceipt = false;
        var expectedOutputs = new KeyCounter();

        var push = attempt(job, host, expectedOutputs, new KeyCounter());

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "夹具前提：宿主整批接单，实收＝申报");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(),
                "挂不上在飞台账的那一笔，一格都不许划成直投网络");
        assertEquals(FIRINGS, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "整批挂 waitingFor 等实物（UselessBigIntegerBatchClient 的 FIRST_MISSING_PLAN 那条 WARN"
                        + "写过的语义，复用不新建）");
        assertEquals(0, job.declaredTerminals.inflightBatchCount(),
                "这一笔本来就挂不上在飞台账：不猜、不补、不索要");
        assertEquals(FIRINGS, job.accountAnnounced.getLong(PRODUCT),
                "报称产出流水账照全额记，与产物走哪条路无关（这一格一字未动）");

        // 静默口二仍数这一笔，但它欠的枚数从此是 0（改前印 4000：划走了却没有人交割）
        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("noBatchId[累计]=1,枚数合计=0,最新样板=" + THOUSAND_A_FIRING.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(THOUSAND_A_FIRING),
                firstTokenOf(coverage, "noBatchId[累计]="),
                "静默口二照旧点名这一笔，枚数合计随不再划账一起归零：" + coverage);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                firstTokenOf(coverage, "noReceipt[累计]="),
                "另一支静默口一笔都不许跟着涨：" + coverage);
        assertEquals("inflightRegistered[累计]=0",
                firstTokenOf(coverage, "inflightRegistered[累计]="), coverage);
    }

    /** carrier 压根没交回该笔的 token（台账的静默口一那一型）：同一档处置，整批挂实物。 */
    @Test
    void carrierWithoutAnyReceiptKeepsTheWholeBatchOnThePhysicalRoute() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var host = new CarrierReportingHost(4_000L);
        host.handsBackReceipt = false;
        var expectedOutputs = new KeyCounter();

        var push = attempt(job, host, expectedOutputs, new KeyCounter());

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "夹具前提：宿主整批接单");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(),
                "没有 token 就没有终局凭据，一格都不许划成直投网络");
        assertEquals(FIRINGS, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "整批挂 waitingFor 等实物");
        assertEquals(0, job.declaredTerminals.inflightBatchCount(), "没有凭据可挂，在飞表恒空");

        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("noReceipt[累计]=1,枚数合计=0,最新样板=" + THOUSAND_A_FIRING.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(THOUSAND_A_FIRING),
                firstTokenOf(coverage, "noReceipt[累计]="),
                "静默口一照旧点名这一笔，枚数合计随不再划账一起归零：" + coverage);
        assertEquals("noBatchId[累计]=0,枚数合计=0,最新样板=<none>",
                firstTokenOf(coverage, "noBatchId[累计]="),
                "两支静默口各记各的，另一支不许跟着沾光：" + coverage);
    }

    /**
     * <b>不变量臂（防"把更保守写成少划"的假修复）</b>：token 上带着 {@code batchId} 的正常批，
     * 申报全额照旧划成直投网络、{@code waitingFor} 一格不挂、在飞台账挂上一笔、待交割读数报满。
     * 判定成立那条路上，今天的数字一个都不许变。
     */
    @Test
    void carrierReceiptWithBatchIdStillSplitsThePlannedOutputsVerbatim() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        // 报满：宿主自报计划＝申报（实机那一跑的形状）
        var host = new CarrierReportingHost(FIRINGS);
        var expectedOutputs = new KeyCounter();

        var push = attempt(job, host, expectedOutputs, new KeyCounter());

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "夹具前提：整批接单");
        assertEquals(BigInteger.valueOf(FIRINGS), job.expectedToNetwork(PRODUCT).exactValue(),
                "有 batchId 的正常批：申报全额照旧划成直投网络，一格都不许少划");
        assertEquals(0L, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "被自报盖满的那一批不再挂实物预期：这一格少划一档就是假修复");
        assertEquals(1, job.declaredTerminals.inflightBatchCount(), "这一笔挂进了在飞台账");
        assertEquals(FIRINGS + "枚", job.declaredTerminals.describePendingDelivery(),
                "待交割读数交出正是被划走的那 5000 枚");

        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("inflightRegistered[累计]=1",
                firstTokenOf(coverage, "inflightRegistered[累计]="), coverage);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                firstTokenOf(coverage, "noReceipt[累计]="), coverage);
        assertEquals("noBatchId[累计]=0,枚数合计=0,最新样板=<none>",
                firstTokenOf(coverage, "noBatchId[累计]="), coverage);
    }

    /**
     * 报计划入网、却<b>不是</b> carrier 的宿主：同一道判定也不许它划账。
     *
     * <p>生产里没有这一型（唯一交回"计划入网"的宿主 {@code UselessBigIntegerDeclaredHost} 同时实现
     * {@link DeclaredBatchTerminalCarrier}），所以这一档零生产变化；它钉的是判定的完整形状——
     * 划账的前提是"这一笔挂得上在飞台账"，而不是"宿主报回了计划量"。
     */
    @Test
    void nonCarrierReporterCannotSplitItsOutputsEither() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(FIRINGS));
        var host = new ReportingHost(4_000L);
        var expectedOutputs = new KeyCounter();

        var push = attempt(job, host, expectedOutputs, new KeyCounter());

        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "夹具前提：整批接单");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(),
                "不是 carrier 就没有终局凭据，报回的计划量一格也不许划成直投网络");
        assertEquals(FIRINGS, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "整批挂 waitingFor 等实物");
        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("declaredOther[累计]=1", firstTokenOf(coverage, "declaredOther[累计]="), coverage);
        assertEquals("inflightRegistered[累计]=0",
                firstTokenOf(coverage, "inflightRegistered[累计]="), coverage);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                firstTokenOf(coverage, "noReceipt[累计]="),
                "非 carrier 走不到 noteAdmitted，两支静默口都不许涨：" + coverage);
    }

    /** 走 {@code attemptDeclaredBatch} 真腿的公共夹具（与上面各条同一套形参）。 */
    private static ParallelCraftingCpuLogic.DeclaredPush attempt(
            ParallelExecutingCraftingJob job, DeclaredBatchHost host,
            KeyCounter expectedOutputs, KeyCounter expectedContainerItems) {
        return ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job,
                job.tasks.entrySet(), pool(FIRINGS * UNITS_PER_FIRING), null, RESERVATION_FREE,
                new ParallelPoolLedger(), expectedOutputs, expectedContainerItems,
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
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

    private static ListCraftingInventory pool(long units) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(MATERIAL, units, Actionable.MODULATE);
        return inventory;
    }

    /** 收整批、并把"计划入网"产出报回来的宿主：模拟 bigint 通道那种"产物不回 CPU 池子"的宿主。 */
    private static class ReportingHost implements DeclaredBatchHost, DeclaredBatchPlannedOutputs {

        /** 包内可见（不是 {@code private}）：{@link CarrierReportingHost} 继承本类，私有字段不被继承、
         *  经子类型表达式点不到；用例仍只在外层类体内访问。 */
        final long deliveredProducts;
        boolean accepts = true;
        @Nullable AEKey extraPlannedKey;
        @Nullable WideKeyCounter pending;

        private ReportingHost(long deliveredProducts) {
            this.deliveredProducts = deliveredProducts;
        }

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
            if (!accepts) {
                pending = null;
                return BigInteger.ZERO;
            }
            // 真机器自报的就是 pattern.getOutputs() × 份数（计划量，不是入网回执）：只有产出，
            // 没有回流容器那一键
            var delivered = new WideKeyCounter();
            delivered.add(PRODUCT, deliveredProducts);
            if (extraPlannedKey != null) {
                delivered.add(extraPlannedKey, 7L);
            }
            pending = delivered;
            return declaredExecutions;
        }

        @Override
        public @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter takePlannedNetworkOutputs() {
            var taken = pending;
            pending = null;
            return taken;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
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
     * 同一份宿主形状再加上"交回该笔终局 token"那一格能力——生产里报"计划入网"的宿主<b>就是</b>
     * 这一型（{@code UselessBigIntegerDeclaredHost} 同时实现两接口），所以既有划账口径的用例都改走这里。
     *
     * <p>两格可开关，用来打 #227 K1 的那一判：
     * {@link #batchIdOnReceipt} {@code false} ⇒ 交回 token 但机器没报 {@code batchId}（静默口二）；
     * {@link #handsBackReceipt} {@code false} ⇒ 压根不交回 token（静默口一）。
     */
    private static final class CarrierReportingHost extends ReportingHost
            implements DeclaredBatchTerminalCarrier {

        private @Nullable UselessBigIntegerReceipt lastReceipt;
        private boolean batchIdOnReceipt = true;
        private boolean handsBackReceipt = true;

        private CarrierReportingHost(long deliveredProducts) {
            super(deliveredProducts);
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            lastReceipt = null;
            var accepted = super.pushDeclaredBatch(pattern, unitPrototype, declaredExecutions);
            if (accepted.signum() <= 0L || !handsBackReceipt) {
                return accepted;
            }
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            if (batchIdOnReceipt) {
                receipt.noteBatchId(UUID.randomUUID());
            }
            lastReceipt = receipt;
            return accepted;
        }

        @Override
        public @Nullable UselessBigIntegerReceipt lastAcceptedBatchReceipt() {
            return lastReceipt;
        }
    }

    /** 只实现声明接口、不实现回执口的宿主：默认口径必须与接入本通道之前一致。 */
    private static final class PlainDeclaredHost implements DeclaredBatchHost {

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
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
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
}
