package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerBatchClient;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #216 交割凭据：把万象炉申报批的<b>收工</b>从"发数跑完＋产出缓冲空"那一格，换到<b>交割账（已交割量）</b>。
 *
 * <p><b>凭据来源（装机 2.3.9 jar javap 逐字复核）</b>：{@code AdvancedAlloyFurnaceAeManager} 里
 * {@code notifyCpuBatchCompleted} 只在 {@code PendingCraftingOutput.isEmpty()} 成立时才发，而
 * {@code isEmpty} 读的是 {@code ledger}（{@code CraftingAeAmountAccumulator}），{@code ledger} 由
 * {@code attributeDeliveredOutputs} 在真插进 ME 网络之后按实际入网量走 {@code consume} 逐批扣减——
 * 扣到空才发 {@code SUCCESS}。也就是说 {@code SUCCESS} 终局<b>本身带数量含义</b>：这一批计划量已真写进网络。
 *
 * <p>本类钉住五臂，每把变异只红自己：
 * <ul>
 *   <li><b>报满零实物臂（主臂）</b>：申报批 {@code accepted>0}、{@code planNet} 报满（自报=申报，
 *       {@code waitingFor} 恒空）、该批终局<b>永不来</b> ⇒ {@code ret=0} 也不许判成收工；
 *       <b>#227 K2 之后第二臂读的是真差值</b>（{@code planNet − 已交割 == 0}，
 *       {@code ParallelCraftingCpuLogic#declaredNetworkDeliveriesSettled}），不再是"在飞表里还有没有
 *       一笔挂着待交割"那个布尔读数；</li>
 *   <li><b>交割对平臂</b>：该批 {@code SUCCESS} 到 ⇒ 交割账记的枚数＝该批 {@code planNet} 枚数，
 *       差值为 0 时才放行收工；</li>
 *   <li><b>不误伤臂（守 e105b10 / #213）</b>：{@code CANCELLED} ⇒ 发数原样退回待办、交割账不记已交割，
 *       而该笔划进 {@code planNet} 的那一截随回滚一起退掉（见上面 K2 那一臂）；
 *       非万象炉宿主（无终局台账、也不划 {@code planNet}）⇒ 不索要，差值恒 0，
 *       收工判据第二臂逐字退回"任务表空且产出缓冲空"；</li>
 *   <li><b>哨兵标记臂</b>：交割账加数被 {@code Long.MAX_VALUE} 饱和哨兵夹过时，{@code pendingDelivery} 读数
 *       带 {@code (clamped)}，去掉标记只红这一条。</li>
 * </ul>
 *
 * <p><b>#227 K2 再加三臂（收工第二臂换成读差值）</b>：
 * <ul>
 *   <li>{@link #planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen()}：账上记满了直投预期、在飞表
 *       却是空的（{@code map} 尺寸 0）⇒ 旧那把布尔尺被尺寸骗成"已交割干净"、判成收工；差值尺必须
 *       判它<b>不收工</b>。<b>本臂 HEAD 必红</b>；</li>
 *   <li>{@link #cancelledTerminalRefundsThePlannedNetworkAmountSoTheJobCanStillSettle()}：一笔
 *       {@code CANCELLED} ⇒ 该笔当初划进 {@code planNet} 的枚数<b>随回滚一起退掉</b>（发数已退回待办，
 *       账要对得上），差值归零 ⇒ 收工<b>能</b>成立——这是防 {@code #209} 那一型的牙：一笔取消既不许
 *       永久挡住收工，也不许被当成已交割。<b>本臂 HEAD 在退账那一格红</b>；</li>
 *   <li>{@link #allBatchesSucceededWithBalancedQuantitiesKeepsTheReadoutsAndSettlesTheJob()}：全批
 *       {@code SUCCESS} 且数量对平 ⇒ {@code planNet}／{@code undelivered} 两档读数与改前<b>逐字一致</b>、
 *       收工成立（这条是"换尺子不许顺手少划"的不变量臂，HEAD 上本就为绿）。</li>
 * </ul>
 *
 * <p>主臂/对平/回退臂走的是<b>真宿主腿</b>：{@code attemptDeclaredBatch → pushDeclaredBatch →
 * UselessBigIntegerBatchClient.dispatchOutcome → receipt → noteAdmitted 挂待交割}，终局沿
 * {@code UselessBigIntegerReceipt#deliverTerminal}（= {@code UselessBigIntegerApiBridge#onBatchFinished}
 * 回调体的全部实质动作）打进来。哨兵臂只证标记管线（不重排任何算式），故直接打
 * {@code noteAdmitted} 那一个既有入口。
 */
final class ParallelDeclaredBatchDeliveryCredentialTest {

    private static final long UNITS_PER_FIRING = 1000L;
    private static final long FIRINGS = 5_000L;
    /** 终点产物每发 1 枚 ⇒ 5000 发申报 5000 枚。 */
    private static final long PRODUCT_PER_FIRING = 1L;

    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

    /** 无回流容器的申报样板：报满时产出缓冲能真的空掉（主臂的落点靠这一格不被容器件搅浑）。 */
    private static final FakePattern PATTERN = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                    UNITS_PER_FIRING, null, Set.of(MATERIAL))},
            List.of(new GenericStack(PRODUCT, PRODUCT_PER_FIRING)));

    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), Map.of(), Map.of());

    // ==================== 主臂：报满零实物，终局永不来 ⇒ 不许收工 ====================

    @Test
    void declaredBatchReportingFullPlannedButNeverTerminatingKeepsTheJobOpen() {
        var job = newRecursiveJob();
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintReportingHost();

        var push = attempt(job, pool, host, 10L);
        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "这条腿整批接单");

        // 报满：自报计划＝申报 ⇒ 产出缓冲被摘空（这正是旧那把"只扫在飞表尺寸"的尺被骗的落点）
        // #227 K2 之后这一臂问的是差值：declaredNetworkDeliveriesSettled 改读 planNet − 已交割。
        assertEquals(0L, job.waitingForExact(PRODUCT).exactValue().signum(),
                "夹具前提：自报盖满申报，产出缓冲为空");
        assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                job.expectedToNetwork(PRODUCT).exactValue(), "planNet 报满 5000 枚");
        assertTrue(job.declaredTerminals.hasUndeliveredDeclaredOutput(),
                "待交割挂着——这一笔把产物划成待交割，终局却还没来");

        // 台账 #225 读数 2 的接线牙：走的是<b>真申报腿</b>（attemptDeclaredBatch），分档与真挂账
        // 必须各数到 1 笔——CPU 腿那句 noteDeclaredHostShape 没接、或 noteAdmitted 少记一笔，
        // 都红在这里；而"81 笔申报 / 在飞 3 笔"那一型就是这么被读出来的。
        var coverage = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(job.declaredTerminals,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("declaredBigint[累计]=1", tokenStartingWith(coverage, "declaredBigint[累计]="),
                "carrier 宿主那一档要数到这一笔：" + coverage);
        assertEquals("declaredOther[累计]=0", tokenStartingWith(coverage, "declaredOther[累计]="),
                "这一笔出自 carrier，不许记进别的宿主那一档：" + coverage);
        assertEquals("inflightRegistered[累计]=1", tokenStartingWith(coverage, "inflightRegistered[累计]="),
                "这一笔真挂进了在飞表：" + coverage);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                tokenStartingWith(coverage, "noReceipt[累计]="),
                "没有静默收手，就不能凭空数到一笔：" + coverage);

        // 走既有那两把尺把这张样板放行出表（发数跑完 + 材料欠账清了），任务表因此排空
        settleAndDropTask(job);
        assertTrue(job.tasks.isEmpty() && job.waitingFor.list.isEmpty(),
                "夹具前提：任务表空、产出缓冲空——拦住收工的只剩直投差值那一格");

        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "报满而货不到（ret=0、终局永不来）不许被当成收工——#216 那一轮的落点，"
                        + "K2 换尺子后由 planNet − 已交割 这一格接着拦");

        var line = stallLine(job, pool, 10L + 4_213L);
        assertEquals("inflightBatches=1", tokenStartingWith(line, "inflightBatches="),
                "这一笔要看得见还挂着：" + line);
        assertEquals("pendingDelivery=" + (FIRINGS * PRODUCT_PER_FIRING) + "枚",
                tokenStartingWith(line, "pendingDelivery="),
                "待交割那一格点名它欠网络几枚（无 clamped：这一批加数没越界）：" + line);
    }

    // ==================== 对平臂：SUCCESS 到 ⇒ 交割账枚数＝planNet，此时才收工 ====================

    @Test
    void successTerminalCreditsTheDeliveryBookBalancingPlanNetThenAllowsCompletion() {
        var job = newRecursiveJob();
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintReportingHost();

        assertNotNull(attempt(job, pool, host, 10L));
        settleAndDropTask(job);
        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "SUCCESS 之前：待交割挂着 ⇒ 不许收工（与主臂同一格的前半）");

        var batchId = host.lastAcceptedBatchReceipt().batchId();
        assertNotNull(batchId);
        assertEquals(0.0f, job.timeTracker.getProgress(), "计划回报不能冒充已合成进度");
        host.lastAcceptedBatchReceipt().deliverTerminal(batchId, DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(1.0f, job.timeTracker.getProgress(), "真实交割终局才推进进度");
        host.lastAcceptedBatchReceipt().deliverTerminal(batchId, DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        assertEquals(1.0f, job.timeTracker.getProgress(), "重复终局不能重复计入完成量");


        // 交割对平：该批 SUCCESS 到 ⇒ 交割账记的枚数＝该批 planNet 枚数
        assertEquals(job.expectedToNetwork(PRODUCT).exactValue(),
                job.declaredNetworkDeliveredAmount(PRODUCT).exactValue(),
                "交割账（已交割）与 planNet（待交割）逐键对平：SUCCESS 的枚数就是那批划走的枚数");
        assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                job.declaredNetworkDeliveredAmount(PRODUCT).exactValue(),
                "记进交割账的正是这批的 5000 枚");
        assertFalse(job.declaredTerminals.hasUndeliveredDeclaredOutput(),
                "SUCCESS 撤下在飞待交割：这一格从判据里落下");
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "只有该批 SUCCESS 到齐，才允许收工");
    }

    /** 两种负向终局走真实申报口后再做作业 NBT 往返，撤回的直投预期不能在存档中复活。 */
    @Test
    void negativeTerminalRefundPersistsThroughRealJobNbtForCancelAndReject() {
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        for (var outcome : List.of(DeclaredBatchTerminalLedger.Outcome.CANCELLED,
                DeclaredBatchTerminalLedger.Outcome.REJECTED)) {
            var job = newRecursiveJob();
            var inventory = pool(FIRINGS * UNITS_PER_FIRING);
            var host = new BigintReportingHost();
            assertNotNull(attempt(job, inventory, host, 10L));
            assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                    job.expectedToNetwork(PRODUCT).exactValue());
            // 先按既有两格出表规则结掉义务，NBT 无任务样板解码；终局到达时不得复活已出表条目。
            settleAndDropTask(job);
            var poolBefore = inventory.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE);
            var obligationBefore = job.batchObligations.pendingFirings(PATTERN);
            host.lastAcceptedBatchReceipt().deliverTerminal(host.lastAcceptedBatchReceipt().batchId(), outcome);
            assertTrue(job.tasks.isEmpty());
            assertEquals(obligationBefore, job.batchObligations.pendingFirings(PATTERN));
            assertEquals(poolBefore, inventory.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(BigInteger.ZERO, job.expectedToNetwork(PRODUCT).exactValue());
            assertEquals(BigInteger.ZERO, job.declaredNetworkDeliveredAmount(PRODUCT).exactValue());
            var saved = job.writeToNBT(registries);
            var savedExpectation = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            ParallelExecutingCraftingJob.readExpectedNetworkOutputs(saved, registries, savedExpectation);
            assertEquals(BigInteger.ZERO, savedExpectation.getExact(PRODUCT), "存档保存的是撤回后的真值");
            var lane = new ParallelCraftingLaneState(
                    ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), UUID.randomUUID(), 0L);
            var restored = new ParallelExecutingCraftingJob(saved, registries, ignored -> { }, lane);
            assertEquals(BigInteger.ZERO, restored.expectedToNetwork(PRODUCT).exactValue());
            assertEquals(0, restored.declaredTerminals.inflightBatchCount());
            assertTrue(restored.tasks.isEmpty());
        }
    }

    // ==================== 不误伤臂：CANCELLED ⇒ 发数回退、不记已交割（守 e105b10）====================

    @Test
    void cancelledTerminalRollsBackFiringsAndNeverCreditsDelivery() {
        var job = newRecursiveJob();
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintReportingHost();

        var push = attempt(job, pool, host, 10L);
        assertNotNull(push);
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        var progress = job.taskProgress(PATTERN);
        assertNotNull(progress);

        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        // e105b10 边界逐字守住：CANCELLED 把那一批发数退回待办
        assertEquals(PlanAmount.of(BigInteger.valueOf(FIRINGS)).exactValue(),
                progress.exactRemaining().exactValue(),
                "CANCELLED 把那一批发数原样退回待办");
        // 负向终局不记已交割（只有 SUCCESS 的枚数才转已交割）
        assertEquals(0L, job.declaredNetworkDeliveredAmount(PRODUCT).exactValue().signum(),
                "CANCELLED 不转已交割：交割账这一格停在 0");
        // 待交割随在飞撤下（发数退回后这张样板重新申报，届时重挂新的待交割）
        assertFalse(job.declaredTerminals.hasUndeliveredDeclaredOutput(),
                "负向终局撤下这一笔在飞待交割，不残留");
    }

    // ==================== #227 K2：收工第二臂改读 planNet − 已交割 ====================

    /**
     * 账上记满了"直投网络"的预期、在飞表却是空的（{@code map} 尺寸 0）——旧那把布尔尺按尺寸判
     * "没有挂着待交割的笔" ⇒ 恒真 ⇒ 收工成立（实机 2026-10-02 19:55 那一跑的字段形状：
     * {@code inflightBatches=0 undelivered=NONE pendingDelivery=NONE} 而 {@code ret=0}）。
     *
     * <p><b>本臂 HEAD 必红</b>：差值尺读 {@code planNet − 已交割}，那一格是 5000，非零 ⇒ 不许收工。
     * 这一格真宿主腿在 #227 K1 之后已经造不出来了（挂不上台账的批根本不划账），所以这里直接打
     * 划账那唯一一处出口（{@code reserveExpectedWaiting}），与上面
     * {@code hostWithoutTerminalLedgerKeepsTheOldTableDrainedRouteVerbatim} 用的是同一个既有入口。
     */
    @Test
    void planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen() {
        var job = newRecursiveJob();
        var announced = new KeyCounter();
        announced.add(PRODUCT, FIRINGS * PRODUCT_PER_FIRING);
        var plannedToNetwork = new KeyCounter();
        plannedToNetwork.add(PRODUCT, FIRINGS * PRODUCT_PER_FIRING);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, announced, new KeyCounter(), plannedToNetwork);

        // 夹具前提：报满 ⇒ 产出缓冲被摘空、一笔都没挂在飞台账上（旧尺子就是被这一格骗的）
        assertEquals(0, job.declaredTerminals.inflightBatchCount(), "夹具前提：在飞表是空的");
        assertFalse(job.declaredTerminals.hasUndeliveredDeclaredOutput(),
                "夹具前提：旧那把布尔尺在这一格读出来是『没有挂着待交割』");
        settleAndDropTask(job);
        assertTrue(job.tasks.isEmpty() && job.waitingFor.list.isEmpty(),
                "夹具前提：任务表与产出缓冲都空着——收工只剩第二臂这一格");

        assertFalse(ParallelCraftingCpuLogic.declaredNetworkDeliveriesSettled(job),
                "planNet 记满 5000 而交割账为 0 ⇒ 差值非零 ⇒ 第二臂不成立（旧行为被 map 尺寸骗成成立）");
        assertFalse(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "没人交割过的那 5000 枚不许被当成收工——本臂 HEAD 红在这里");
    }

    /**
     * 一笔 {@code CANCELLED} ⇒ 该笔当初划进 {@code planNet} 的枚数<b>原样退掉</b>，差值归零 ⇒
     * 收工<b>能</b>成立。
     *
     * <p><b>这是防 {@code #209} 那一型的牙</b>：{@code onTerminal} 的负向分支把该笔的
     * {@code pendingDelivery} 整笔丢弃、不转已交割（台账自己不留账），若第二臂改读差值而不退这一截，
     * {@code planNet − 已交割} 就<b>永远非零</b>——一笔取消把整张作业永久钉在"未收工"上，正是
     * 12:54 那一刀被回退过的病。退账走 {@code expectedToNetwork} 同一本账（不建第二本、不加第二把尺），
     * 而它<b>不等于</b>判成已交割：交割账那一格照旧停在 0。
     *
     * <p>夹具走的是"条目已出表、终局才到"那一档（回退落空那一型）：发数退回无处可落，
     * 但划进 {@code planNet} 的那一截必须照样退掉，否则差值尺永远结不掉。
     *
     * <p><b>本臂 HEAD 在退账那一格红</b>：改前 {@code expectedToNetwork} 停在 5000（没人退它）。
     */
    @Test
    void cancelledTerminalRefundsThePlannedNetworkAmountSoTheJobCanStillSettle() {
        var job = newRecursiveJob();
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintReportingHost();
        assertNotNull(attempt(job, pool, host, 10L));
        assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                job.expectedToNetwork(PRODUCT).exactValue(),
                "夹具前提：报满的那一笔把 5000 枚全划进了 planNet（改前改后都一样）");

        settleAndDropTask(job);
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        assertEquals(PlanAmount.ZERO.exactValue(), job.expectedToNetwork(PRODUCT).exactValue(),
                "负向终局：这一笔当初划进 planNet 的 5000 枚原样退掉——本臂 HEAD 红在这里");
        assertEquals(0L, job.declaredNetworkDeliveredAmount(PRODUCT).exactValue().signum(),
                "退账不许被判成已交割：交割账那一格停在 0");
        assertTrue(ParallelCraftingCpuLogic.declaredNetworkDeliveriesSettled(job),
                "差值归零 ⇒ 一笔 CANCELLED 不许永久挡住收工");
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "任务表空 + 产出缓冲空 + 差值 0 ⇒ 收工成立（#209 那一型不许复发）");
    }

    /**
     * <b>不变量臂（换尺子不许顺手少划／多划）</b>：全批 {@code SUCCESS} 且数量对平 ⇒
     * 收工成立，而 {@code planNet}／{@code undelivered} 两档读数与改前<b>逐字一致</b>
     * （{@code SUCCESS} 只把该笔转成已交割，绝不动 {@code planNet} 那本预期账）。
     */
    @Test
    void allBatchesSucceededWithBalancedQuantitiesKeepsTheReadoutsAndSettlesTheJob() {
        var job = newRecursiveJob();
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintReportingHost();
        assertNotNull(attempt(job, pool, host, 10L));
        settleAndDropTask(job);

        var batchId = host.lastAcceptedBatchReceipt().batchId();
        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.SUCCESS);

        assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                job.expectedToNetwork(PRODUCT).exactValue(),
                "SUCCESS 不许把 planNet 一起退掉：那本预期账与改前逐字同一份数");
        assertEquals(BigInteger.valueOf(FIRINGS * PRODUCT_PER_FIRING),
                job.declaredNetworkDeliveredAmount(PRODUCT).exactValue(), "交割账记的正是那 5000 枚");
        assertEquals(PlanAmount.ZERO.exactValue(),
                job.declaredNetworkUndelivered(PRODUCT).exactValue(), "差值那一格归零");
        assertTrue(ParallelCraftingCpuLogic.declaredNetworkDeliveriesSettled(job),
                "全批对平 ⇒ 第二臂成立");
        assertTrue(ParallelCraftingCpuLogic.isDyeableRecursiveJobComplete(job),
                "收工成立，且没有任何一格读数被这把新尺改小");
    }

    // ==================== 不误伤臂：非万象炉宿主 ⇒ 收工第二臂逐字退回老路（守 #213）====================

    @Test
    void hostWithoutTerminalLedgerKeepsTheOldTableDrainedRouteVerbatim() {
        var job = newRecursiveJob();
        // 直投网络那口整批报满（与接入交割凭据之前的 tickClosing 那条老路同型），但没有在飞台账
        var announced = new KeyCounter();
        announced.add(PRODUCT, FIRINGS * PRODUCT_PER_FIRING);
        var plannedToNetwork = new KeyCounter();
        plannedToNetwork.add(PRODUCT, FIRINGS * PRODUCT_PER_FIRING);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, announced, new KeyCounter(), plannedToNetwork);

        // 没有申报批挂在飞台账：那把<b>只扫表尺寸</b>的旧布尔尺在这一格恒空——而 #227 K2 之后收工第二臂
        // 读的是 planNet − 已交割，这一格里那 5000 枚照样拦着收工（见
        // planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen）。这一条只问台账自己的两格读数。
        assertFalse(job.declaredTerminals.hasUndeliveredDeclaredOutput(),
                "没有申报批在飞 ⇒ 台账那把只扫表尺寸的布尔尺恒空（它已不是收工判据）");
        assertEquals("NONE", job.declaredTerminals.describePendingDelivery(),
                "不接这条通道的作业：待交割读数恒 NONE");
    }

    // ==================== 哨兵标记臂：加数被饱和哨兵夹过 ⇒ 读数带 clamped，不冒充量 ====================

    @Test
    void clampedDeliveryAddendIsFlaggedNotCountedAsAPlainQuantity() {
        var pending = Map.<AEKey, BigInteger>of(PRODUCT, BigInteger.valueOf(FIRINGS));

        // 夹过：同一份待交割，clamped=true ⇒ 读数必须带出 (clamped)
        var clampedJob = newParallelJob();
        var clampedReceipt = receiptWithBatchId();
        clampedJob.declaredTerminals.noteAdmitted(clampedReceipt, PATTERN,
                BigInteger.valueOf(FIRINGS), 10L, clampedJob::rollBackDeclaredBatchFirings,
                pending, true, clampedJob::creditDeclaredBatchDelivered);
        assertEquals("pendingDelivery=" + FIRINGS + "枚(clamped)",
                tokenStartingWith(stallLine(clampedJob, pool(FIRINGS * UNITS_PER_FIRING), 4_223L),
                        "pendingDelivery="),
                "加数被 Long.MAX 饱和哨兵夹过 ⇒ 读数带 (clamped)，不许把它读成一个真数");

        // 没夹过：同一条口 clamped=false ⇒ 读数不带标记（变异去掉标记位，两条一起红即说明标记没接进管线）
        var plainJob = newParallelJob();
        var plainReceipt = receiptWithBatchId();
        plainJob.declaredTerminals.noteAdmitted(plainReceipt, PATTERN,
                BigInteger.valueOf(FIRINGS), 10L, plainJob::rollBackDeclaredBatchFirings,
                pending, false, plainJob::creditDeclaredBatchDelivered);
        assertFalse(plainJob.declaredTerminals.describePendingDelivery().contains("(clamped)"),
                "没被夹过的加数不许冒出 (clamped) 标记：那是把正常量也报成饱和");
    }

    // ==================== 夹具 ====================

    private static void settleAndDropTask(ParallelExecutingCraftingJob job) {
        var owed = job.batchObligations.pendingFirings(PATTERN);
        if (owed.signum() > 0) {
            job.batchObligations.settle(PATTERN, owed.longValueExact());
        }
        // 出表判据（发数跑完 + 材料欠账清了）放行后，产线会把这张样板从任务表摘掉；离线这里手动摘
        job.tasks.remove(PATTERN);
    }

    private static UselessBigIntegerReceipt receiptWithBatchId() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        receipt.noteBatchId(UUID.randomUUID());
        return receipt;
    }

    private static ParallelCraftingCpuLogic.DeclaredPush attempt(
            ParallelExecutingCraftingJob job, ListCraftingInventory pool, DeclaredBatchHost host,
            long currentTick) {
        return ParallelCraftingCpuLogic.attemptDeclaredBatch(host, PATTERN, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE,
                new ParallelBatchExtraction.Probe(), null, null, null, currentTick);
    }

    private static String stallLine(ParallelExecutingCraftingJob job, ListCraftingInventory pool,
            long currentTick) {
        return ParallelCraftingCpuLogic.describeObligationStallLine(
                UUID.randomUUID(), job.batchObligations.totalPending(), job, job.tasks.entrySet(),
                pool, RESERVATION_FREE, new ParallelBatchExtraction.Probe(), currentTick);
    }

    private static String tokenStartingWith(String line, String marker) {
        for (var token : line.split(" ")) {
            if (token.startsWith(marker)) {
                return token;
            }
        }
        throw new AssertionError("停滞行里没有以 " + marker + " 开头的格：" + line);
    }

    /** 递归作业：终点产物 {@code PRODUCT x FIRINGS}，任务表只挂着产出终点键的 {@code PATTERN}。 */
    private static ParallelExecutingCraftingJob newRecursiveJob() {
        var base = new CraftingPlan(new GenericStack(PRODUCT, FIRINGS * PRODUCT_PER_FIRING),
                0L, true, false, new KeyCounter(), new KeyCounter(), new KeyCounter(),
                Map.of(PATTERN, FIRINGS));
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                FIRINGS * PRODUCT_PER_FIRING, Map.of(), Map.of(), null, null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, newCpuLink(), null);
        job.tasks.put(PATTERN, progress(FIRINGS));
        return job;
    }

    /** 非递归作业（哨兵臂只问在飞读数，不需要交付臂）。 */
    private static ParallelExecutingCraftingJob newParallelJob() {
        return new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    private static CraftingLink newCpuLink() {
        return new CraftingLink(
                CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                (appeng.api.networking.crafting.ICraftingCPU) null);
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

    /**
     * 万象炉壳的替身：真走 {@code UselessBigIntegerBatchClient.dispatchOutcome}，并把"计划入网"报回
     * <b>整批申报量</b>（报满）——即 {@code takePlannedNetworkOutputs} 交出 {@code 实收 × 每发终点量}，
     * 令 {@code reserveOne} 把申报产出 100% 划成待交割、产出缓冲被摘空（"报满零实物"那一型）。
     */
    private static final class BigintReportingHost implements DeclaredBatchHost,
            DeclaredBatchPlannedOutputs, DeclaredBatchTerminalCarrier {

        private @Nullable UselessBigIntegerReceipt lastReceipt;
        private final UUID batchId = UUID.randomUUID();
        private long lastAccepted;

        @Override
        public boolean supportsDeclaredBatch(IPatternDetails pattern) {
            return true;
        }

        @Override
        public boolean declaredBatchFiringsUnbounded() {
            return true;
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            lastReceipt = null;
            lastAccepted = 0L;
            var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, pattern,
                    unitPrototype, declaredExecutions, ignored -> List.of(new FakeMachine(batchId)));
            var result = outcome.result();
            if (result == null || result.accepted() == null || result.accepted().signum() <= 0) {
                return BigInteger.ZERO;
            }
            lastReceipt = result.receipt();
            lastAccepted = result.accepted().longValue();
            return result.accepted();
        }

        @Override
        public @Nullable UselessBigIntegerReceipt lastAcceptedBatchReceipt() {
            return lastReceipt;
        }

        @Override
        public @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter takePlannedNetworkOutputs() {
            // 报满：宿主自报计划＝实收 × 每发终点量 ⇒ reserveOne 把申报产出全额划成待交割
            var planned = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            planned.add(PRODUCT, lastAccepted * PRODUCT_PER_FIRING);
            return planned;
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

    /** 接 bigint 通道的一台机器：容量给满、准入即提交，并把 batchId 记进 token。 */
    private static final class FakeMachine implements UselessBigIntegerBatchClient.Machine {

        private final @Nullable UUID batchId;

        private FakeMachine(@Nullable UUID batchId) {
            this.batchId = batchId;
        }

        @Override
        public String machineIdentity() {
            return "overworld@1,2,3";
        }

        @Override
        public boolean throttled() {
            return false;
        }

        /** 回网队列深度读数口：这一份夹具不扮它，交回 {@code null}＝问不到（下游那一格如实印 ?）。 */
        @Override
        public @Nullable UselessBigIntegerBatchClient.QueuePending queuePending() {
            return null;
        }

        @Override
        public UselessBigIntegerBatchClient.Capacity capacity(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested) {
            return new UselessBigIntegerBatchClient.Capacity(requested, "");
        }

        @Override
        public @Nullable UselessBigIntegerBatchClient.BatchHandle admit(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested, UselessBigIntegerReceipt receipt) {
            receipt.noteBatchId(batchId);
            return new UselessBigIntegerBatchClient.BatchHandle() {

                @Override
                public @Nullable BigInteger count() {
                    return requested;
                }

                @Override
                public boolean commit() {
                    return true;
                }

                /** 这一份夹具只走"提交成功"那一支：所有权这一问回 false，不影响任何判据。 */
                @Override
                public boolean hasTransferredInputOwnership() {
                    return false;
                }
            };
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
