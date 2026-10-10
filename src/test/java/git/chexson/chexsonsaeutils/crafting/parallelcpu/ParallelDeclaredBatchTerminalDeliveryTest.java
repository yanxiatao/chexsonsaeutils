package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerBatchClient;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import net.minecraft.nbt.CompoundTag;
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
 * 万象炉 bigint 申报批的<b>负向终局＝那一批发数退回待办</b>（任务 #209，对 {@code 992eae8} 那一刀的收口）。
 *
 * <p><b>992eae8 留下的原病</b>：把结案凭据换成终局事件方向对，但负向终局的处置写成了
 * <b>永久钉表</b>——{@code onTerminal} 里 {@code SUCCESS} 直接 return、{@code CANCELLED}/{@code REJECTED}
 * 只把发数累加进 {@code undelivered}（只增不清），而 {@code taskMayLeaveTable} 的第三格读
 * {@code owesUndelivered}。发数在<b>接单那一刻</b>就被 {@code consumeFirings} 扣掉，一笔
 * {@code CANCELLED} ⇒ {@code owesUndelivered} 恒真 ⇒ 那张<b>剩余发数已为 0</b>的任务永不出表
 * ⇒ <b>作业永不收工</b>。这一型正是被回退的 {@code 4816673}，也正是用户确认"已经修好"的症状。
 *
 * <p><b>这一刀的判决口径</b>：负向终局＝那一笔<b>从未被结案</b>，而不是"记一笔永久欠账"。
 * ① 那一批发数沿 {@link Rollback} 交回的那一个既有<b>宽域</b>入口
 * （{@code ParallelExecutingCraftingJob.TaskProgress#addFirings}）退回<b>待办</b>——
 * {@code isFinished()} 自己就假 ⇒ 任务留在表里、可重新申报，<b>不需要</b>靠钉表来表达欠交付；
 * ② 那一笔的<b>材料义务</b>在<b>重新申报</b>那一刻由既有的 {@code BatchObligationLedger#incur} 重挂
 * （沿用既有那条一字口径："接受后才 commit 游标；拒绝／能耗缩批先回注且不前移"）——
 * 料被机器吃掉这件事不由我们退料，由"重新欠料"来表达；
 * ③ {@code owesUndelivered} 这一格<b>从 {@code taskMayLeaveTable} 里撤掉</b>：出表判据回到两格
 * （发数跑完 + 材料欠账清了），台账里的"欠交付"自始至终只是停滞行的纯读数。
 * "报满而货不到算交付"这个洞由 ① 挡住：负向终局把发数退回 ⇒ 没结案的批永远不会被当成已交付。
 *
 * <p><b>臂走的是真宿主腿，不是池子腿</b>：每条臂都真跑
 * {@code attemptDeclaredBatch → host.pushDeclaredBatch → UselessBigIntegerBatchClient.dispatchOutcome
 * （findTargets → capacity → admit → commit）→ Result.receipt → 在飞台账}，
 * 终局则沿 {@code UselessBigIntegerReceipt#deliverTerminal} 打进来——那一句就是
 * {@code UselessBigIntegerApiBridge#onBatchFinished} 回调体的<b>全部</b>实质动作。
 * 桥类本身在离线测试树里加载不了（{@code useless_mod} 是 {@code compileOnly}），
 * 所以"假 ctx/假 result 打进来"这件事在这里落成"假 batchId + 真 Outcome 打同一条口"；
 * {@code BatchResult → Outcome} 那三档映射只能在编译期由穷举 switch 保证，离线跑不到（如实登记）。
 *
 * <p><b>永不终局那一型的口径</b>（{@code 992eae8} 定死、这一刀不许退）：
 * <b>不索要、不拦表，只点名</b>。2.3.9 只有 {@code flushQueuedCraftingOutputs}⇒{@code SUCCESS} 与
 * {@code cancelAllTasks}⇒{@code CANCELLED} 两个终局出口，{@code REJECTED} 在全 jar 字节码里
 * 除枚举自身声明外零引用；料被存进 NBT／机器被拆／区块卸载都在"永远没有终局"这一档。
 * 于是这一型的批次只进 {@code inflightBatches=}／{@code oldestInflightTicks=<龄>@<id8>} 两格读数。
 */
final class ParallelDeclaredBatchTerminalDeliveryTest {

    private static final long UNITS_PER_FIRING = 1000L;
    private static final long FIRINGS = 5_000L;

    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

    private static final FakePattern PATTERN = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(MATERIAL))},
            List.of(new GenericStack(PRODUCT, 1)));

    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), Map.of(), Map.of());

    // ==================== A1 退回臂：负向终局把那一批发数退回待办 ====================

    /**
     * 一笔 {@code CANCELLED} ⇒ 这一笔的发数<b>原样退回</b>该样板的<b>精确宽域</b>待办，
     * {@code isFinished()} 自己就假 ⇒ 随后能<b>重新申报</b>（真路再走一次 {@code attemptDeclaredBatch}
     * 并 {@code noteAdmitted} 挂上新的一笔在飞）。
     *
     * <p><b>本臂独有的牙</b>：{@code onTerminal} 的负向分支<b>调用</b>
     * {@code rollBackFirings} 把 {@link ParallelExecutingCraftingJob#rollBackDeclaredBatchFirings}
     * 打进来——把退回写成"只记欠账"（992eae8 的形状，只 {@code owed.firings += firings}、不退回待办）
     * 只红这一条：{@code progress.exactRemaining()} 停在 0 ⇒ 第一格红字（{@code 5000 != 0}），
     * 随后 {@code attempt} 因 {@code declared<=1} 交回 {@code null} ⇒ 第二格红字。
     * A2/A3 用的是<b>部分批</b>（task 里还留着一大截发数），即便退回被摘掉发数也还 &gt; 0
     * ⇒ 那两条臂在这一把变异上仍绿——退回这一格被隔离在本臂上。
     */
    @Test
    void negativeTerminalRollsTheCancelledBatchFiringsBackToTheTaskTodoListAndAllowsRedeclare() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        var push = attempt(job, pool, host, 10L);
        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "这条腿一分没少");
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        assertNotNull(batchId);
        assertEquals(0, progress.exactRemaining().signum(),
                "接单那一刻发数就被 consumeFirings 扣干——这正是 992eae8 之后钉表回路的落点");
        assertTrue(progress.isFinished(), "扣干之后 isFinished 真");

        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        // 退回＝精确宽域加回那一笔的量：走的是 TaskProgress#addFirings，不是饱和的 long 读数
        assertEquals(PlanAmount.of(BigInteger.valueOf(FIRINGS)).exactValue(),
                progress.exactRemaining().exactValue(),
                "CANCELLED 把那一批发数原样退回待办——A1 的第一根牙");
        assertFalse(progress.isFinished(),
                "退回之后 isFinished 自己就假：不需要靠钉表来表达欠交付——A1 的第二根牙");
        // 台账里那一格读数还在（欠交付被点名），只是它不再拦表
        assertEquals(BigInteger.valueOf(FIRINGS), undeliveredCumulative(job, PATTERN),
                "undelivered= 的累计读数照样点名（这一格从判据降级为纯读数，读数本身不撤）");

        // 退回之后能重新申报：清掉这一批的欠账（回补腿在实机里就是干这件事的），走同一条口挂新一笔
        job.batchObligations.settle(PATTERN, FIRINGS - 1L);
        var pool2 = pool(FIRINGS * UNITS_PER_FIRING);
        var rePush = attempt(job, pool2, host, 20L);
        assertNotNull(rePush,
                "退回的那一发被真路重新申报出去——A1 的第三根牙（变异摘掉退回时 declared<=1 ⇒ null）");
        assertEquals(1, job.declaredTerminals.inflightBatchCount(),
                "重新申报挂上了新一笔在飞记录：noteAdmitted 再次可达");
    }

    /**
     * <b>A1 的宽域牙</b>：一笔 &gt; {@code long} 的发数在负向终局后<b>原样</b>退回——不夹、不回绕、
     * 不拿饱和的 {@code Long.MAX} 哨兵冒充量。
     *
     * <p>把退回写成"从 {@code progress.value}（饱和读数）算"的写法会在这一格红：
     * {@code value} 在越界任务上是 {@code Long.MAX} 哨兵，加回一笔 1e19 会得到一个跟真值无关的数。
     */
    @Test
    void negativeTerminalRollsBackAWideBatchFiringsWithoutClampingOrWrapping() {
        var job = newJob();
        // 起始 0：把这一笔的宽域发数当成"接单那一刻已被 consumeFirings 扣干"那一格
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        job.tasks.put(PATTERN, progress);
        var firings = new BigInteger("10000000000000000000"); // 1e19，越出 long

        // 真路挂一笔 >long 的在飞记录：noteAdmitted 交回的就是 job::rollBackDeclaredBatchFirings 那一个出口
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        var batchId = UUID.randomUUID();
        receipt.noteBatchId(batchId);
        job.declaredTerminals.noteAdmitted(receipt, PATTERN, firings, 10L,
                job::rollBackDeclaredBatchFirings);
        assertEquals(1, job.declaredTerminals.inflightBatchCount(), "挂上就是在飞");
        assertEquals(0, progress.exactRemaining().signum(), "这一笔的宽域发数在接单那一刻已被扣干");

        // 负向终局到：退回走 progress.addFirings(PlanAmount.of(firings))——既有那一个宽域入口
        receipt.deliverTerminal(batchId, DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        assertEquals(firings, progress.exactRemaining().exactValue(),
                "一笔 >long 的负向终局原样退回：不夹、不回绕、不把 Long.MAX 哨兵当量——A1 的宽域牙");
        assertFalse(progress.exactRemaining().isFast(),
                "退回之后仍是宽域侧值：饱和的 long 读数会在这里塌成 Long.MAX");
    }

    // ==================== A2 重挂臂：材料义务在真路重新申报那一刻被重挂 ====================

    /**
     * 同一笔被 CANCELLED 之后走<b>真路重新申报</b>：{@code BatchObligationLedger#incur}
     * 把这一笔的材料欠账<b>重挂</b>回去——{@code hasPending} 从"清了"回到 {@code true}、
     * {@code owed} 增加。<b>本臂不手改账</b>：所有对义务账的写入都出自
     * {@code attemptDeclaredBatch} 里既有的那一句 {@code incur(accepted - 1)}。
     *
     * <p><b>两根牙</b>：① 负向终局<b>不退料、不清材料欠账</b>——清了就同时撤掉它对池子的遮罩，
     * "同一份存量许诺两次"逐字复发；② 清完欠账后<b>真路重新申报</b>（{@code attemptDeclaredBatch}），
     * 由既有的那一句 {@code incur(accepted - 1)} 把新的欠账<b>重挂</b>回去（{@code hasPending} 回 true、
     * {@code owed} 增加）。<b>本臂全程不手改账</b>：唯一往义务账里写的是 {@code attemptDeclaredBatch} 自己。
     *
     * <p><b>本臂独有的变异 M-A2</b>：把"退料"写进 {@code rollBackDeclaredBatchFirings}（回退发数的同时
     * {@code settle} 掉这一张样板的欠账）⇒ 只红第一根牙（{@code owedAfterAccept != 0}）；A1/A3/A4/A5
     * 都不断这一格（A1 只读发数、A3 把欠账先清了才终局、A4 无终局、A5 不接台账）⇒ 只红 A2。
     *
     * <p><b>部分批那一档</b>：本臂故意让每一批都只吞一小截发数（配给交得出多少吞多少，task 里还剩一大截），
     * 于是<b>不依赖 A1 的退回</b>也能重新申报——A1 的变异摘掉退回之后这一臂仍绿，退回与重挂两把刀彼此隔离。
     */
    @Test
    void materialObligationIsReHungByTheRealRedeclarePathAfterACancellation() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        // 现场只给两发的料：第一批被现场可付上界夹到 2 发，任务表上还留 4998 发
        var pool = pool(2L * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        var firstPush = attempt(job, pool, host, 10L);
        assertNotNull(firstPush);
        assertTrue(firstPush.accepted() > 0L && firstPush.accepted() < FIRINGS,
                "这一批是部分批：A2 的真路重新申报不依赖 A1 的退回");
        var firstBatchId = host.lastAcceptedBatchReceipt().batchId();
        var owedAfterAccept = job.batchObligations.pendingFirings(PATTERN);
        assertTrue(owedAfterAccept.signum() > 0, "申报那一刻材料欠账已经挂上（既有那一格 incur）");

        // 牙①：负向终局到——不退料、不清义务账，那一格账本在这一路上<b>一字不动</b>
        host.lastAcceptedBatchReceipt().deliverTerminal(firstBatchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);
        assertEquals(owedAfterAccept, job.batchObligations.pendingFirings(PATTERN),
                "M-A2 落点：负向终局不许顺手清材料欠账（清了它对池子的遮罩就一起撤走，同一份存量许诺两次复发）");
        assertTrue(job.batchObligations.hasPending(PATTERN),
                "A2 牙①：材料欠账原样挂着——重挂的凭据是发数退回，不是把欠账销掉");

        // 一张样板同时只挂一笔欠账：还清之前真路重新申报必须被 hasPending 拦下（既有那道口）
        var waiting = attempt(job, pool(FIRINGS * UNITS_PER_FIRING), host, 20L);
        assertNotNull(waiting);
        assertEquals(0L, waiting.accepted(), "欠账还清之前不许叠第二笔申报");
        assertTrue(waiting.endsTaskRound(), "等待交割时不许回落到 holder 再次取料");
        assertFalse(waiting.rejected(), "等待上一批交割不是宿主拒收");

        // 回补腿把这一批的欠账还清（既有 settle 那一个出口，模拟现场把 (k-1) 发的料真交给机器）
        job.batchObligations.settle(PATTERN, owedAfterAccept.longValueExact());
        assertFalse(job.batchObligations.hasPending(PATTERN), "回补完材料欠账清了");

        // 牙②：真路重新申报 ⇒ 既有 incur 把新的欠账重挂回去
        var rePush = attempt(job, pool(FIRINGS * UNITS_PER_FIRING), host, 30L);
        assertNotNull(rePush, "清完欠账后发数还有一大截 ⇒ 重新申报真走 attemptDeclaredBatch");
        assertTrue(job.batchObligations.hasPending(PATTERN),
                "A2 牙②：重新申报那一刻 hasPending 回到 true（既有 incur，不是手改账）");
        assertEquals(BigInteger.valueOf(rePush.accepted() - 1L),
                job.batchObligations.pendingFirings(PATTERN),
                "A2 牙②：owed 增加了这一批的 (accepted-1)——出自 attemptDeclaredBatch 里既有的那一句");
    }

    // ==================== A3 收工不被钉死：负向终局之后作业仍能收工 ====================

    /**
     * <b>最要紧的一条</b>：一笔 {@code CANCELLED} ⇒ 任务留在表里；随后那批发数<b>重新申报</b>
     * 并收到 {@code SUCCESS} ⇒ 出表、作业能收工。
     *
     * <p>这一把变异＝<b>把 {@code owesUndelivered} 那一格塞回 {@code taskMayLeaveTable}</b>：
     * 只要这一格还读台账的"欠交付"，那张<b>剩余发数已 0、材料欠账已清</b>的样板就永远出不了表
     * （992eae8 的形状；{@code 4816673} 那一型的复发）。撤掉这一格之后，凭据换到"负向终局
     * 把发数退回 ⇒ 没结案的批永远不会被当成已交付"这一条上——本用例的第三段（把 firings 与
     * obligation 都清干净、断言 {@code taskMayLeaveTable=true}）就是挡这一格的那一颗钉子。
     */
    @Test
    void aCancelledThenRedeclaredAndSucceededBatchLeavesTheTableAndTheJobCanFinish() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        // 部分批：现场只给 2 发的料，第一批吞 2 发；剩下 4998 发不发不可——
        // A3 的"留在表里"这一格与 A1 的退回无关（发数本来就 > 0），A1 的变异不串到这里
        var pool = pool(2L * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        var firstPush = attempt(job, pool, host, 10L);
        assertNotNull(firstPush);
        var firstBatchId = host.lastAcceptedBatchReceipt().batchId();
        // 回补腿把这一批的欠账清了（材料账先自己走完，好让 A3 的"留在表里"这一格只剩发数那一格）
        job.batchObligations.settle(PATTERN, job.batchObligations.pendingFirings(PATTERN).longValueExact());

        host.lastAcceptedBatchReceipt().deliverTerminal(firstBatchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        // 第一段：那一笔被 CANCELLED ⇒ 任务留在表里（部分批这一档，发数本来就 > 0，
        // 与 A1 的退回无关——A1 的变异摘掉退回之后 firings 仍 > 0，这一格仍真）
        assertFalse(progress.isFinished(), "留在表里：发数没跑完");
        assertFalse(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "留在表里：判据只有那两格（发数跑完 + 材料欠账清了），这一格红在发数没跑完上——"
                        + "台账根本不在这一格的形参里，它点不点欠交付都不许改这一格");
        // 台账里那一格"欠交付"读数被点名（992eae8 那一格若还在，就是从这一格里读出来拦表的）
        assertEquals(BigInteger.valueOf(firstPush.accepted()),
                undeliveredCumulative(job, PATTERN), "台账里那一格读数还在——但只是读数");

        // 第二段：真路重新申报（发数还有 4998 或 5000 都够），并收到 SUCCESS
        var pool2 = pool(FIRINGS * UNITS_PER_FIRING);
        var rePush = attempt(job, pool2, host, 20L);
        assertNotNull(rePush, "重新申报真走 attemptDeclaredBatch（不手改任何账本）");
        var reBatchId = host.lastAcceptedBatchReceipt().batchId();
        host.lastAcceptedBatchReceipt().deliverTerminal(reBatchId,
                DeclaredBatchTerminalLedger.Outcome.SUCCESS);

        // 第三段：把这一批的欠账清了 + 把发数跑完（走既有那两本账各自唯一的写入口）——
        // 这两步是"这一批被交割了"的等价物，正是 A3 要问的那一格的落点：两格都清了之后能不能出表
        job.batchObligations.settle(PATTERN, job.batchObligations.pendingFirings(PATTERN).longValueExact());
        long remaining = progress.exactRemaining().exactValue().longValueExact();
        progress.consumeFirings(remaining);

        assertTrue(progress.isFinished(), "发数跑完");
        assertFalse(job.batchObligations.hasPending(PATTERN), "材料欠账清了");
        // 直接断言那一档：台账里那一格欠交付读数<b>照旧点着名</b>（不是"已交付"，是"重新欠账"），
        // 而那张仍可出表——把 owesUndelivered 那一格塞回判据就是要在这里红。
        assertTrue(undeliveredCumulative(job, PATTERN).signum() > 0L,
                "欠交付点名仍在（纯读数）：这一格先钉住台账确实还点着那一笔，下一格的放行才有牙");
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "出表：这一格是原病的落点——台账点着欠交付，那张照样能出表（判据压根不读台账）");
    }

    // ==================== A4 永不终局臂（992eae8 M3 语义逐字保持绿） ====================

    /**
     * SUCCESS 到 ⇒ 那批发数<b>结掉</b>：在飞记录撤下、不许冒出欠交付、允许出表。
     *
     * <p>这一条臂的<b>独有</b>牙是 {@code inflightBatches} 被结掉这一件事：把终局分支改成
     * "永不清账"，只有这里的 {@code assertEquals(0, …inflightBatchCount())} 与
     * {@code oldestInflightTicks=0@none} 会红——拒绝臂的终局本来就不是 SUCCESS，
     * 永不终局臂根本没有终局可清。
     */
    @Test
    void successTerminalSettlesTheFiredBatchAndClearsTheInflightReadout() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        var push = attempt(job, pool, host, 10L);
        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "实收＝申报：这条腿一分没少");
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        assertNotNull(batchId, "机器在 admit 那层报回的 batchId 必须在 token 上");
        assertEquals(1, job.declaredTerminals.inflightBatchCount(),
                "申报一挂上就是在飞：终局还没到，这一笔还没收场");

        host.lastAcceptedBatchReceipt().deliverTerminal(batchId, DeclaredBatchTerminalLedger.Outcome.SUCCESS);

        assertEquals(0, job.declaredTerminals.inflightBatchCount(),
                "SUCCESS 到：这一批发数结掉在飞记录（不清账就是本臂的红点）");
        assertEquals("NONE", job.declaredTerminals.describeUndelivered(),
                "SUCCESS 之后 undelivered= 恒空（结掉的那一笔不许冒出欠交付读数）");
        assertEquals("0@none", job.declaredTerminals.describeOldestInflight(999L),
                "结掉之后最老一笔读数归零：在飞笔数不是只增不减的水位");
        // SUCCESS 什么都不回退：发数停在 0（接单那一刻就被 consumeFirings 扣掉了）
        assertTrue(progress.isFinished(), "SUCCESS 不许把已扣掉的发数退回待办（回退只属于负向终局）");

        job.batchObligations.settle(PATTERN, FIRINGS - 1L);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "发数跑完 + 材料交割干净 ⇒ 出表（这一格一字不读台账）");
        var line = stallLine(job, pool, 999L);
        assertTrue(line.contains("inflightBatches=0 ") && line.contains("oldestInflightTicks=0@none ")
                        && line.contains(" undelivered=NONE"),
                "结清的作业在停滞行上三格读数都必须是空：" + line);
    }

    /**
     * 挂了却<b>永远等不到终局</b>的那一笔：只点名，<b>不许</b>把整张作业的收工判据走向死路。
     *
     * <p>992eae8 M3 语义逐字保持：这一型<b>不索要、不拦表、只点名</b>。把"在飞"也算进拦表判据
     * （在 {@code taskMayLeaveTable} 里再摆一格读 inflight）只红这一条臂：
     * SUCCESS 臂那笔已被清掉、A1/A3 两臂读的是负向终局退回之后的两把尺、
     * A5 臂压根没有在飞记录 ⇒ 只有本臂会红。
     */
    @Test
    void batchThatNeverTerminatesIsNamedByTheReadoutsButNeverPinsTheTable() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        assertNotNull(attempt(job, pool, host, 10L), "申报批真挂上一笔");
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        assertNotNull(batchId);

        // 这一笔从此没有任何终局事件（2.3.9 真做得到：料进了 pending 后被存进 NBT／机器被拆／区块卸载）
        job.batchObligations.settle(PATTERN, FIRINGS - 1L);
        // 直接断言那一档：台账说这一笔<b>还在飞、没任何终局回执</b>（现成读数点名），判据照旧放行。
        assertEquals(1, job.declaredTerminals.inflightBatchCount(),
                "夹具前提：这一笔挂在在飞里，且没等到任何终局");
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "永不到货的终局不许拦出表——M-A4 落点：把 inflight 塞进拦表判据（先得把台账摆回形参）"
                        + "就红在这里，而那一格本身就是新闸门");

        var line = stallLine(job, pool, 10L + 4_213L);
        assertEquals("inflightBatches=1", tokenStartingWith(line, "inflightBatches="),
                "这一笔要看得见还挂着：" + line);
        assertEquals("oldestInflightTicks=4213@" + batchId.toString().substring(0, 8),
                tokenStartingWith(line, "oldestInflightTicks="),
                "最老一笔的 tick 龄与它的 batchId 前缀同格点名：" + line);
        assertEquals("undelivered=NONE", tokenStartingWith(line, "undelivered="),
                "欠交付那一格只认已到的负向终局，不许把在飞算进去：" + line);
    }

    /**
     * <b>A1 退回臂（REJECTED 那一档）</b>：一笔 REJECTED（2.3.9 字节码里没有发射点，为将来的版本留位）
     * 的处置与 CANCELLED <b>同一条</b>：那一批发数原样退回待办、台账里的欠交付读数照常点名。
     *
     * <p>这一条与两条 CANCELLED 退回臂同属 A1 家族：M-A1（摘掉退回）只红这三条、M-A2/A3/A4/A5 都不落到
     * "REJECTED 把发数退回"这一格上——SUCCESS 那臂走的是"结掉不回退"、永不终局那臂根本没有终局。
     */
    @Test
    void rejectedTerminalRollsBackTheSameWayAsCancelled() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();

        assertNotNull(attempt(job, pool, host, 10L));
        var batchId = host.lastAcceptedBatchReceipt().batchId();
        job.batchObligations.settle(PATTERN, FIRINGS - 1L);

        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.REJECTED);

        assertEquals(PlanAmount.of(BigInteger.valueOf(FIRINGS)).exactValue(),
                progress.exactRemaining().exactValue(),
                "REJECTED 与 CANCELLED 同一条：那一批发数原样退回");
        assertFalse(progress.isFinished(), "REJECTED 之后 isFinished 假：不靠钉表");
        assertEquals(BigInteger.valueOf(FIRINGS), undeliveredCumulative(job, PATTERN),
                "REJECTED 的累计欠交付读数与 CANCELLED 同一条");
    }

    // ==================== A5 capability 正向臂 ====================

    /**
     * 宿主<b>没有终局事件来源</b>（不实现 {@link DeclaredBatchTerminalCarrier}）⇒ 台账一分不挂，
     * 出表判据与接入本通道之前<b>逐字相同</b>。
     *
     * <p>这一格与 {@code ParallelDeclaredBatchObligationTest#finishedTaskWithUndeliveredObligationStaysOnTheTableAndKeepsItsClaim}
     * （第 466 / 477 行两格）共同钉住 A5：那条臂钉"欠着材料就不许出表"，本臂钉"没接这条通道的作业
     * 出表判据一字不变"；两条臂走的是<b>同一个</b> {@code taskMayLeaveTable}——全仓只有那一个判据出口，
     * 两条臂<b>一字不许改</b>。
     */
    @Test
    void hostWithoutTerminalSourceIsNotAskedAnythingAndBehavesExactlyAsBefore() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);

        var push = attempt(job, pool, new PlainDeclaredHost(), 10L);
        assertNotNull(push);
        assertEquals(FIRINGS, push.accepted(), "既有宿主的申报腿一分不变");
        assertEquals(0, job.declaredTerminals.inflightBatchCount(),
                "没有 cpuToken 就没有在飞记录：不猜、不补、不索要");
        assertEquals("NONE", job.declaredTerminals.describeUndelivered());
        assertEquals("0@none", job.declaredTerminals.describeOldestInflight(10L));

        job.batchObligations.settle(PATTERN, FIRINGS - 1L);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "既有三参口径该出表");
    }

    /**
     * <b>续传口径（在飞与欠交付都不落 NBT，这一条把它钉住）</b>：落盘的只有既有的发数账，
     * 重启后交回的新作业台账为空 ⇒ 旧 {@code batchId} 的终局（永不来）无处可记，也<b>不</b>被发明出来。
     *
     * <p>风险如实写在 {@code DeclaredBatchTerminalLedger} 类注释：跨重启窗口被机器取消的那一批
     * <b>不会</b>被退回待办（终局永不来 ⇒ 本刀的那一条回退腿也就不会被触发）；重载后
     * {@code inflightBatches=} 从 0 起算。这两格本身仍是纯读数。
     *
     * <p><b>#227 K2 之后必须改口的那一句</b>：改前这里写着"这两条只影响读数与事后归因，
     * <b>不</b>影响收工判据"。收工第二臂今天读 {@code planNet − 已交割}，而分子那本账照旧落 NBT、
     * 这本台账不落 ⇒ 重启前带着直投预期的作业差值非零、停在"未收工等交割"。本臂那台宿主压根不报
     * 计划入网（{@code takePlannedNetworkOutputs} 交回 {@code null}）⇒ {@code planNet} 恒空 ⇒ 续传
     * 作业的收工仍与既有口径逐字一致；{@code planNet} 带着落盘的预期回来那一档不在本臂形状里。
     *
     * <p><b>本臂不投终局</b>：A5 问的是"接了台账与没接台账的作业在续传之后行为逐字一致"这一格，
     * 与 A1 的退回腿正交。若把"负向终局＋退回"塞进这一臂，就会与 A1 的隔离对撞。
     */
    @Test
    void reloadedJobCarriesNoInflightAndInventsNoRollbackOrUndeliveredDebt() {
        var old = newJob();
        var oldProgress = progress(FIRINGS);
        old.tasks.put(PATTERN, oldProgress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();
        assertNotNull(attempt(old, pool, host, 10L));
        assertEquals(1, old.declaredTerminals.inflightBatchCount(),
                "old 上确实挂着一笔在飞：这一条臂问的就是这笔在飞会不会跟着 NBT 走");

        var item = new CompoundTag();
        ParallelExecutingCraftingJob.writeTaskProgress(item, oldProgress);
        var fresh = newJob();
        var restored = ParallelExecutingCraftingJob.readTaskProgress(item);
        fresh.tasks.put(PATTERN, restored);

        assertEquals(0, fresh.declaredTerminals.inflightBatchCount(), "在飞记录不落 NBT：新作业恒空");
        assertEquals("NONE", fresh.declaredTerminals.describeUndelivered(),
                "重启后不许把旧 batchId 的那一笔发明成欠交付读数（那是一笔永远等不到凭据的账）");
        assertFalse(item.contains("declaredBatchTerminal"),
                "发数条目里不许出现任何在飞/欠交付键：这一本的口径是不落盘");
        // 发数账照常落 NBT（consumeFirings 之后的读数），与接入本通道之前逐字一致
        assertEquals(0, restored.exactRemaining().signum(),
                "续传读回的发数账就是接单那一刻扣干之后的读数：终局没来 ⇒ 不发明回退");
        fresh.batchObligations.settle(PATTERN, FIRINGS - 1L);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, restored, fresh.batchObligations),
                "续传作业的出表判据与既有口径逐字一致（判据只读那两本照旧落 NBT 的账）");
    }

    /**
     * <b>回退落空那一档的口径</b>（唯一还留着的形状，也只留读数）：条目已经出表之后终局才到
     * ⇒ 退回来的那一笔<b>不</b>被塞回任务表（那是复活别人已经判完的活，比拦表更坏），
     * 只在停滞行的 {@code undelivered=<样板>#累计枚/已回退M枚@batch[…]} 里点名。
     *
     * <p>把"回退落空也硬塞回任务表"写成"重新放回 tasks"的写法会红在这里：本臂断言
     * {@code job.tasks.get(PATTERN)} 仍然为 {@code null}、且 {@code 累计>0/已回退=0}。
     */
    @Test
    void terminalArrivingAfterTheEntryLeftTheTableOnlyNamesItAndNeverResurrectsTheTask() {
        var job = newJob();
        var progress = progress(FIRINGS);
        job.tasks.put(PATTERN, progress);
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new BigintLikeHost();
        assertNotNull(attempt(job, pool, host, 10L));
        var batchId = host.lastAcceptedBatchReceipt().batchId();

        // 走 992eae8 那一档：条目先按"两把尺都清了"出表——发数被 consumeFirings 扣干 + 材料欠账清了
        job.batchObligations.settle(PATTERN, FIRINGS - 1L);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(PATTERN, progress, job.batchObligations),
                "两把尺都清了 ⇒ 出表（既有判据，与台账一字无关）");
        job.tasks.remove(PATTERN);
        assertNull(job.taskProgress(PATTERN), "条目已出表");

        // 出表之后终局才到：不复活条目
        host.lastAcceptedBatchReceipt().deliverTerminal(batchId,
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);
        assertNull(job.taskProgress(PATTERN),
                "回退落空 ⇒ 本刀不许把条目塞回任务表（那是复活别人已经判完的活）");
        var cumulative = undeliveredCumulative(job, PATTERN);
        var rolledBack = undeliveredRolledBack(job, PATTERN);
        assertEquals(BigInteger.valueOf(FIRINGS), cumulative, "累计欠交付读数照常点名");
        assertEquals(BigInteger.ZERO, rolledBack,
                "已回退那一截为 0：本臂就是'回退落空只在读数里点名、绝不复活条目'那一条");
    }

    // ==================== 夹具 ====================

    /** 读一张样板的<b>累计</b>欠交付发数（从 describeUndelivered 那一格里拆出来，只经由公开读数口）。 */
    private static BigInteger undeliveredCumulative(ParallelExecutingCraftingJob job,
            IPatternDetails details) {
        var cell = undeliveredCell(job, details);
        var end = cell.indexOf("枚/已回退");
        assertTrue(end > 0, "欠交付累计那一格没拼完：" + cell);
        return new BigInteger(cell.substring(0, end));
    }

    /** 读一张样板的<b>已回退</b>发数（同样从 describeUndelivered 拆出来）。 */
    private static BigInteger undeliveredRolledBack(ParallelExecutingCraftingJob job,
            IPatternDetails details) {
        var cell = undeliveredCell(job, details); // 形如 "5000枚/已回退0枚"（cell 已截到 '@' 之前）
        var start = cell.indexOf("枚/已回退") + "枚/已回退".length();
        var end = cell.indexOf('枚', start);
        assertTrue(end > start, "已回退那一格没拼完：" + cell);
        return new BigInteger(cell.substring(start, end));
    }

    private static String undeliveredCell(ParallelExecutingCraftingJob job, IPatternDetails details) {
        var described = job.declaredTerminals.describeUndelivered();
        var head = String.valueOf(details.getDefinition()).replace(' ', '_') + "#";
        var start = described.indexOf(head);
        assertTrue(start >= 0, "读数里点不到这张样板：" + described);
        var from = start + head.length();
        var end = described.indexOf('@', from);
        assertTrue(end > from, "欠交付那一格没拼完：" + described);
        return described.substring(from, end);
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

    private static ParallelCraftingCpuLogic.DeclaredPush attempt(
            ParallelExecutingCraftingJob job, ListCraftingInventory pool, DeclaredBatchHost host,
            long currentTick) {
        return ParallelCraftingCpuLogic.attemptDeclaredBatch(host, PATTERN, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE,
                new ParallelBatchExtraction.Probe(), null, null, null, currentTick);
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

    /**
     * 万象炉壳的替身：{@link #pushDeclaredBatch} 真走
     * {@code UselessBigIntegerBatchClient.dispatchOutcome}（{@code findTargets → capacity → admit
     * → commit}），机器发现注的是本用例的假机器——这条腿与真壳只剩"目标从网格里查"这一处不同。
     *
     * <p>假机器在 {@code admit} 那一层把 {@code batchId} 记进 {@code cpuToken}，与
     * {@code UselessBigIntegerApiBridge#onBatchAdmitted} 打的是同一条口。
     */
    private static final class BigintLikeHost implements DeclaredBatchHost,
            DeclaredBatchPlannedOutputs, DeclaredBatchTerminalCarrier {

        private @Nullable UselessBigIntegerReceipt lastReceipt;
        private @Nullable UUID batchId = UUID.randomUUID();

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
            var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, pattern,
                    unitPrototype, declaredExecutions, ignored -> List.of(new FakeMachine(batchId)));
            var result = outcome.result();
            if (result == null || result.accepted() == null || result.accepted().signum() <= 0) {
                return BigInteger.ZERO;
            }
            lastReceipt = result.receipt();
            return result.accepted();
        }

        @Override
        public @Nullable UselessBigIntegerReceipt lastAcceptedBatchReceipt() {
            return lastReceipt;
        }

        @Override
        public @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter takePlannedNetworkOutputs() {
            // 计划产出那一截由 NetworkDeliveryTest 钉；本用例只问终局凭据，留空即"机器连计划都没报"
            return null;
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

    /** 接 bigint 通道的一台机器：容量给满、准入即提交，并把 batchId 记进 token（桥侧 admit 回调同一条口）。 */
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

        @Override
        public @org.jetbrains.annotations.Nullable UselessBigIntegerBatchClient.QueuePending
                queuePending() {
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

    /** 既有宿主：不实现 {@link DeclaredBatchTerminalCarrier} ⇒ 台账一个字的判据都不许问它。 */
    private static final class PlainDeclaredHost implements DeclaredBatchHost {

        @Override
        public boolean supportsDeclaredBatch(IPatternDetails pattern) {
            return true;
        }

        @Override
        public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
            return BigInteger.valueOf(Long.MAX_VALUE);
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
            @Nullable appeng.api.stacks.AEKey remainder,
            Set<appeng.api.stacks.AEKey> accepts) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(appeng.api.stacks.AEKey input, Level level) {
            return accepts.contains(input);
        }

        @Override
        public @Nullable appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
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
