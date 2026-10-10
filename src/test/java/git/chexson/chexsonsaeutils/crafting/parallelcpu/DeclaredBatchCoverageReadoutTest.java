package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.api.config.Actionable;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #225（读数 1–5 与 6–7）——申报批与回补这条腿的账都要能<b>自证覆盖面</b>。
 *
 * <p>要打的现场是 2026-10-02 19:55 那一跑的三条互斥读数：宿主行 {@code push=81}、批量行
 * {@code declared=113 obBatch=110}、全场只有 3 条 {@code 批次已终局(SUCCESS)}，而同一时刻
 * {@code inflightBatches=0 oldestInflightTicks=0@none undelivered=NONE pendingDelivery=NONE}。
 * 三把数出自两个不同的 100 轮窗与两本不同的账，<b>谁也证不了谁</b>；而
 * {@code DeclaredBatchTerminalLedger#noteAdmitted} 的两支静默 {@code return} 与
 * {@code UselessBigIntegerReceipt#deliverTerminal} 的两支静默收手<b>一笔计数都不留</b>。
 *
 * <p>本类钉的四条臂各打各的（别的臂不跟着沾光）：
 * <ul>
 *   <li>读数 1：两支静默口各有独立计数＋枚数合计＋最新样板身份标，且 {@code >0} 那一笔当场有一行
 *       WARN（打的是<b>真打印者</b>：捕获 appender 抓原文）；</li>
 *   <li>读数 2：{@code declaredBigint/declaredOther/inflightRegistered} 三格并排、同一本台账，
 *       笔数必须对平（{@code declaredBigint = inflightRegistered + noReceipt + noBatchId + 无效入参}）；</li>
 *   <li>读数 3：终局三档各自笔数 + {@code terminalUnmatched}（账上没有这笔）+ 回执侧两支静默收手
 *       的<b>全场 JVM</b> 累计；</li>
 *   <li>读数 4：{@code alloc=} 那一行 {@code planNet=} 之后并排 {@code delivered=}/{@code undelivered=}
 *       ——#225 那一轮审计时那本交割账<b>生产代码零读者</b>，那一轮只把它印出来；#227 K2 起收工第二臂
 *       读的是<b>同一处算式</b>的全键合计（{@code ParallelExecutingCraftingJob#
 *       declaredNetworkUndeliveredTotal}），这一行仍是只印不判、格名与逐键口径一字未动。</li>
 * </ul>
 *
 * <p><b>一个字都不进判据</b>：本类不新增任何 {@code if}／闸门／早退，也不改
 * {@code taskMayLeaveTable}／{@code isDyeableRecursiveJobComplete}／
 * {@code declaredNetworkDeliveriesSettled}／{@code hasUndeliveredDeclaredOutput} 任何一条算式。
 */
final class DeclaredBatchCoverageReadoutTest {

    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey CHEST = AEItemKey.of(Items.CHEST);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);
    private static final AEItemKey BOOKSHELF = AEItemKey.of(Items.BOOKSHELF);
    /** 配给表上留着一份没销的配额、而网络桩压根不持有它的那一枚（{@code quotaBlind=} 点名的那一型）。 */
    private static final AEItemKey RATIONED = AEItemKey.of(Items.NETHERITE_BLOCK);

    /** 一枚越出 {@code long} 的待交割枚数（实机那一跑是 3.44e20）：顶格哨兵不许冒充量。 */
    private static final BigInteger OVER_LONG_UNITS =
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN).add(BigInteger.valueOf(4_000L));

    // ==================== 读数 1：两支静默口各记各的，>0 那一笔当场留名 ====================

    /**
     * {@code receipt == null} 那一支：累计笔数、枚数合计（宽域精确原文）、最新样板身份标各成一格，
     * 另一支（{@code noBatchId}）<b>不许跟着沾光</b>；同时 WARN 必须被真打印者打出来。
     */
    @Test
    void missingReceiptArmCountsItsOwnBatchesAndWarnsWithItsWideUnits() {
        var job = newPlainJob();
        var ledger = job.declaredTerminals;
        var pattern = pattern();

        var line = captureWarnLine(() -> ledger.noteAdmitted(null, pattern,
                BigInteger.valueOf(4_096L), 10L, null,
                Map.<AEKey, BigInteger>of(PRODUCT, OVER_LONG_UNITS), false,
                job::creditDeclaredBatchDelivered));

        assertTrue(line.contains("臂=noReceipt"), () -> "这一支的臂名必须写对：" + line);
        assertTrue(line.contains("累计笔数=1"), () -> "第一笔就该数到 1：" + line);
        assertTrue(line.contains("枚数合计=" + OVER_LONG_UNITS),
                () -> "枚数合计走宽域精确原文，不许饱和在 9223372036854775807：" + line);
        assertTrue(line.contains("样板=" + pattern.getDefinition()),
                () -> "要点名是哪枚样板：" + line);

        var cells = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(ledger,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("noReceipt[累计]=1,枚数合计=" + OVER_LONG_UNITS
                        + ",最新样板=" + pattern.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(pattern),
                tokenStartingWith(cells, "noReceipt[累计]="),
                "静默口一那一格逐字（笔数/枚数合计/最新一枚样板的身份标）：" + cells);
        // 另一支一笔都没发生：不许跟着沾光（把两支并成一个计数器的写法在这里红）
        assertEquals("noBatchId[累计]=0,枚数合计=0,最新样板=<none>",
                tokenStartingWith(cells, "noBatchId[累计]="),
                "静默口二没被触发就必须是全 0：" + cells);
        // 这一笔压根没挂上台账 ⇒ 真挂账那一格停在 0（"申报了但没挂上"就是从这一对数字看出来的）
        assertEquals("inflightRegistered[累计]=0",
                tokenStartingWith(cells, "inflightRegistered[累计]="),
                "静默 return 的那一笔不许算进已挂账：" + cells);
    }

    /**
     * {@code batchId == null} 那一支：同样三个子字段，且 {@code noReceipt} 那一支不跟着涨。
     * 两支各记各的 ⇒ 下一跑能直接说出"81 笔申报里有多少笔是被这一支吞掉的"。
     */
    @Test
    void missingBatchIdArmCountsSeparatelyFromMissingReceiptArm() {
        var job = newPlainJob();
        var ledger = job.declaredTerminals;
        var pattern = pattern();
        // 交回了 token、但机器没报 batchId（老通道／admit 与 finished 之间的空档）
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");

        ledger.noteAdmitted(receipt, pattern, BigInteger.valueOf(2L), 10L, null,
                Map.<AEKey, BigInteger>of(PRODUCT, BigInteger.valueOf(9_000L)), false,
                job::creditDeclaredBatchDelivered);
        ledger.noteAdmitted(receipt, pattern, BigInteger.valueOf(3L), 11L, null,
                Map.<AEKey, BigInteger>of(PRODUCT, BigInteger.valueOf(1_000L)), false,
                job::creditDeclaredBatchDelivered);

        var cells = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(ledger,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("noBatchId[累计]=2,枚数合计=10000,最新样板=" + pattern.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(pattern),
                tokenStartingWith(cells, "noBatchId[累计]="),
                "两笔各 9000/1000 枚 ⇒ 笔数 2、枚数合计 10000（跨笔累加，只印最新一枚样板）：" + cells);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                tokenStartingWith(cells, "noReceipt[累计]="),
                "静默口一没被触发，不许跟着这一支涨：" + cells);
    }

    // ==================== 读数 2＋3：真渲染口（per100r 每 100 轮必打）交出十格 ====================

    /**
     * 走<b>真渲染口</b> {@code StallProbe#noteBatch}（喂满 100 轮、捕获 INFO 原文）：
     * 覆盖面十格必须真被那一行印出来，且各档数字对平。
     *
     * <p>喂的是真账：三笔 carrier 宿主 + 一笔别的宿主、一笔真挂上（后 SUCCESS）、一笔真挂上（后
     * CANCELLED）、一笔终局来了但账上没有、一笔没 receipt 的静默口、一笔<b>没挂台账</b>的回执终局。
     * 把 {@code describeDeclaredCoverageReadouts} 接成常量、或漏掉其中一格，都在对应那一段红。
     */
    @Test
    void perHundredRoundLinePrintsTheWholeCoverageFamilyFromOneLedger() {
        var job = newPlainJob();
        var ledger = job.declaredTerminals;
        var pattern = pattern();
        var expectedNoLedger = UselessBigIntegerReceipt.terminalDroppedNoLedger();
        var expectedMismatch = UselessBigIntegerReceipt.terminalDroppedIdMismatch();
        // 三笔申报来自"能挂在飞台账那一型"，一笔来自别的申报宿主
        ledger.noteDeclaredHostShape(true);
        ledger.noteDeclaredHostShape(true);
        ledger.noteDeclaredHostShape(true);
        ledger.noteDeclaredHostShape(false);

        // 其中两笔真挂上：第一笔 SUCCESS、第二笔 CANCELLED
        var successReceipt = receiptWithBatchId();
        ledger.noteAdmitted(successReceipt, pattern, BigInteger.valueOf(5L), 10L, null,
                Map.<AEKey, BigInteger>of(PRODUCT, BigInteger.valueOf(500L)), false,
                job::creditDeclaredBatchDelivered);
        var cancelledReceipt = receiptWithBatchId();
        ledger.noteAdmitted(cancelledReceipt, pattern, BigInteger.valueOf(7L), 11L, null,
                Map.<AEKey, BigInteger>of(PRODUCT, BigInteger.valueOf(700L)), false,
                job::creditDeclaredBatchDelivered);
        successReceipt.deliverTerminal(successReceipt.batchId(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        cancelledReceipt.deliverTerminal(cancelledReceipt.batchId(),
                DeclaredBatchTerminalLedger.Outcome.CANCELLED);
        // 同一笔再回一次终局：账上已经没有这一笔 ⇒ terminalUnmatched，且不许再算一次 SUCCESS
        successReceipt.deliverTerminal(successReceipt.batchId(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        // 第三笔 carrier 申报：回执没报 batchId ⇒ 静默口二吞掉一笔
        ledger.noteAdmitted(new UselessBigIntegerReceipt("overworld@1,2,3"), pattern,
                BigInteger.ONE, 12L, null, Map.of(), false, null);
        // 没挂台账的那一笔：终局回来了只能在回执侧静默收手（全场 JVM 口径）
        var orphanReceipt = receiptWithBatchId();
        orphanReceipt.deliverTerminal(orphanReceipt.batchId(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);

        var line = renderPer100rLine(job);
        assertEquals("declaredBigint[累计]=3", tokenStartingWith(line, "declaredBigint[累计]="), line);
        assertEquals("declaredOther[累计]=1", tokenStartingWith(line, "declaredOther[累计]="), line);
        assertEquals("inflightRegistered[累计]=2", tokenStartingWith(line, "inflightRegistered[累计]="),
                () -> "三笔 carrier 里只有两笔真挂进在飞表：" + line);
        assertEquals("noReceipt[累计]=0,枚数合计=0,最新样板=<none>",
                tokenStartingWith(line, "noReceipt[累计]="), line);
        assertEquals("noBatchId[累计]=1,枚数合计=0,最新样板=" + pattern.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(pattern),
                tokenStartingWith(line, "noBatchId[累计]="),
                () -> "第三笔 carrier 申报走的是静默口二（待交割交空表 ⇒ 枚数 0，但样板照样点名）：" + line);
        assertEquals("terminalSuccess[累计]=1", tokenStartingWith(line, "terminalSuccess[累计]="), line);
        assertEquals("terminalCancelled[累计]=1", tokenStartingWith(line, "terminalCancelled[累计]="), line);
        assertEquals("terminalOther[累计]=0", tokenStartingWith(line, "terminalOther[累计]="), line);
        assertEquals("terminalUnmatched[累计]=1", tokenStartingWith(line, "terminalUnmatched[累计]="),
                () -> "重复回来的那一笔终局账上没有，只能点这一格：" + line);
        assertEquals("terminalDropped[全场JVM]=noLedger=" + (expectedNoLedger + 1)
                        + "/idMismatch=" + expectedMismatch,
                tokenStartingWith(line, "terminalDropped[全场JVM]="),
                () -> "没挂台账的那一笔只进 noLedger 臂，idMismatch 臂一格都不许动：" + line);
        // 读数 5（台账 #225）：回网队列深度这一格<b>读得到真值</b>（2.3.9 那两只 public 口换算出来的），
        // 而离线这一跑压根没装 useless、这一轮也就没碰到任何一台机器 ⇒ 两格必须如实是
        // 问号 + {@code shown=0/total=0}。这里钉的是"没问到就不许印数"，不是"这一格永远问号"
        // （问到真值的那三档见下面 queuePendingReadoutPrints… 三条臂）。
        assertEquals("queuePending[useless侧]=?",
                tokenStartingWith(line, "queuePending[useless侧]="),
                () -> "一台机器都没问到时不许编一个数出来：" + line);
        assertEquals("queuePendingCov=shown=0/total=0", tokenStartingWith(line, "queuePendingCov="),
                () -> "覆盖面必须自己承认这一轮一台都没碰到：" + line);
        // 渲染口直调与真行逐字同源：谁把这一格从 per100r 那一行里摘掉，这一条当场红。
        assertEquals(" queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                " " + tokenStartingWith(line, "queuePending[useless侧]=") + " "
                        + tokenStartingWith(line, "queuePendingCov="),
                () -> "真行里那两格必须就是 queuePendingReadout 交出的那两格：" + line);

        // 一眼对平：carrier 三笔 = 真挂上两笔 + 静默口二一笔（拿<b>印出来的数</b>做恒等式，
        // 不是夹具自己数给自己——任何一格漏记或重复记，这一条就红）
        assertEquals(numberCell(line, "declaredBigint[累计]="),
                numberCell(line, "inflightRegistered[累计]=")
                        + numberCell(line, "noReceipt[累计]=")
                        + numberCell(line, "noBatchId[累计]="),
                () -> "declaredBigint 必须等于 inflightRegistered + noReceipt + noBatchId：" + line);
    }

    /** 取某一格开头的<b>数字</b>（格形 {@code name=<long>,…}）；格名对不上直接红。 */
    private static long numberCell(String line, String marker) {
        var token = tokenStartingWith(line, marker);
        var digits = new StringBuilder();
        for (var ch : token.substring(marker.length()).toCharArray()) {
            if (!Character.isDigit(ch)) {
                break;
            }
            digits.append(ch);
        }
        if (digits.isEmpty()) {
            throw new AssertionError("格 " + marker + " 开头的不是一个数：" + token);
        }
        return Long.parseLong(digits.toString());
    }

    // ==================== 读数 3 的另一半：回执侧 id 对不上那一支 ====================

    /** 台账挂着、终局却报回<b>另一个</b> batchId：只进 {@code idMismatch}，{@code terminalUnmatched} 不动。 */
    @Test
    void mismatchedTerminalBatchIdCountsTheIdMismatchArmOnly() {
        var job = newPlainJob();
        var ledger = job.declaredTerminals;
        var pattern = pattern();
        var before = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(ledger,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        var beforeNoLedger = droppedPart(before, "noLedger=");
        var beforeMismatch = droppedPart(before, "idMismatch=");

        var receipt = receiptWithBatchId();
        ledger.noteAdmitted(receipt, pattern, BigInteger.valueOf(4L), 10L, null,
                Map.<AEKey, BigInteger>of(), false, null);
        receipt.deliverTerminal(UUID.randomUUID(), DeclaredBatchTerminalLedger.Outcome.CANCELLED);

        var after = ParallelCraftingCpuLogic.describeDeclaredCoverageReadouts(ledger,
                " queuePending[useless侧]=? queuePendingCov=shown=0/total=0",
                new ProviderBatchCapabilitiesFactoryImpl().terminalDrops());
        assertEquals("terminalDropped[全场JVM]=noLedger=" + beforeNoLedger
                        + "/idMismatch=" + (beforeMismatch + 1),
                tokenStartingWith(after, "terminalDropped[全场JVM]="),
                () -> "id 对不上只涨 idMismatch 臂，另一臂一格都不许动。改前=" + before + " 改后=" + after);
        assertEquals(tokenStartingWith(before, "terminalUnmatched[累计]="),
                tokenStartingWith(after, "terminalUnmatched[累计]="),
                "这一支压根到不了台账，terminalUnmatched 不许跟着涨：" + after);
        assertEquals("terminalCancelled[累计]=0", tokenStartingWith(after, "terminalCancelled[累计]="),
                "对不上号的终局不许记成任何一档终局：" + after);
        assertEquals("inflightRegistered[累计]=1", tokenStartingWith(after, "inflightRegistered[累计]="),
                "挂账那一笔本身要数到：" + after);
    }

    /** 取 {@code terminalDropped[全场JVM]=noLedger=<a>/idMismatch=<b>} 里那一个标签后面的数。 */
    private static long droppedPart(String cells, String label) {
        var token = tokenStartingWith(cells, "terminalDropped[全场JVM]=");
        var from = token.indexOf(label);
        if (from < 0) {
            throw new AssertionError("格里没有 " + label + "：" + token);
        }
        var digits = new StringBuilder();
        for (var ch : token.substring(from + label.length()).toCharArray()) {
            if (!Character.isDigit(ch)) {
                break;
            }
            digits.append(ch);
        }
        return Long.parseLong(digits.toString());
    }

    // ==================== 读数 4：交割账从零读者变成看得见 ====================

    /**
     * {@code alloc=} 那一行 {@code planNet=} 之后并排 {@code delivered=}/{@code undelivered=}：
     * 挂上一笔待交割 5000 枚的申报批 ⇒ {@code delivered=0 undelivered=5000}；
     * 该笔 {@code SUCCESS} 到 ⇒ {@code delivered=5000 undelivered=0}。
     *
     * <p>两档必须换人：把 {@code delivered=} 接成常量、或干脆没读那本账，第二档就红。
     * 期望的枚数出自<b>夹具自己写死的申报量</b>（5000 枚），不是抄实现的读数。
     */
    @Test
    void allocationRowPrintsDeliveredAndUndeliveredOfTheSameKey() {
        var job = newPlainJob();
        var pattern = pattern();
        var ration = new WideKeyCounter();
        ration.add(MATERIAL, 5_000L);
        MaterialAllocationFixtures.assign(job, pattern, ration);

        // 申报 5000 枚产物、宿主自报计划 5000 枚 ⇒ 全额划成待交割（与 planNet 同一处算式）
        var expectedOutputs = new KeyCounter();
        expectedOutputs.add(PRODUCT, 5_000L);
        var plannedToNetwork = new KeyCounter();
        plannedToNetwork.add(PRODUCT, 5_000L);
        var recordedPlanned = ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expectedOutputs,
                new KeyCounter(), plannedToNetwork);
        var pendingWide = new java.util.LinkedHashMap<AEKey, BigInteger>();
        recordedPlanned.forEach((key, amount) -> pendingWide.put(key, BigInteger.valueOf(amount)));

        var receipt = receiptWithBatchId();
        job.declaredTerminals.noteAdmitted(receipt, pattern, BigInteger.valueOf(5_000L), 10L, null,
                pendingWide, false, job::creditDeclaredBatchDelivered);

        var beforeTerminal = allocationRow(job);
        assertEquals("planNet=5000", tokenStartingWith(beforeTerminal, "planNet="), beforeTerminal);
        assertEquals("delivered=0", tokenStartingWith(beforeTerminal, "delivered="),
                "SUCCESS 之前交割账就是空的（这本账此前生产代码零读者）：" + beforeTerminal);
        assertEquals("undelivered=5000", tokenStartingWith(beforeTerminal, "undelivered="),
                "还挂着没等到终局的逐键真值：" + beforeTerminal);

        receipt.deliverTerminal(receipt.batchId(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);

        var afterTerminal = allocationRow(job);
        assertEquals("delivered=5000", tokenStartingWith(afterTerminal, "delivered="),
                "SUCCESS 到齐 ⇒ 那 5000 枚转已交割：" + afterTerminal);
        assertEquals("undelivered=0", tokenStartingWith(afterTerminal, "undelivered="),
                "交割对平后这一格归零：" + afterTerminal);
        // 顺序也钉死：delivered/undelivered 紧跟 planNet，读日志的人拿同一行的三个数就能对平
        assertTrue(afterTerminal.contains(" planNet=5000 delivered=5000 undelivered=0 "),
                () -> "三格必须并排相邻：" + afterTerminal);
    }

    // ==================== 读数 6：回补这条腿要逐枚键自报流水 ====================

    /**
     * 读数 6（台账 #225）：{@code topUpKeys=} 那一格——"这一百拍里这一枚键自己被网络交回几枚"。
     *
     * <p>聚合那三格（{@code topUp=}／{@code topUpChunks=}／{@code topUpExact=}）只说得出
     * "这条 lane 一共搬进几刀几枚"，同窗里两枚键的回补糊在同一个数上，"这一枚一枚没回"与
     * "这一枚回了但不够"在字面上分不开。
     *
     * <p>喂的是<b>真搬运</b>：{@code ParallelBatchExtraction#refillObligationBatch} 走真回补腿，
     * 网络桩<b>按键</b>定数（{@code MATERIAL} 交 3 枚、{@code PRODUCT} 交 5 枚），两枚都真插进了池子
     * （{@code exactStored} 现读为证——这一格量的是"交回并落池"，不是"网络自报能交几枚"），
     * 再经生产侧<b>唯一</b>的累加口 {@code foldTaskTopUpUnitsPerKey} 折进 lane 流水，
     * 最后从<b>真渲染口</b> {@code describeStallLine} 取整行原文。
     * 期望的枚数出自夹具写死的那两份桩答复（3 与 5），不是抄实现的读数。
     *
     * <p><b>夹具形状（就地标明，不许读成生产形状）</b>：真路
     * （{@code refillTaskInputsFromNetwork}）里聚合三格与逐键这一格是<b>同一次取差值</b>一起涨的；
     * 离线跑不进那一条——它先向 {@code lane.cluster().grid()} 要一条真网络通道，离线没有网格就
     * 在那一句上直接交回 0。所以这一行里 {@code topUpExact=0} 与逐键两格并存，是<b>夹具形状</b>；
     * "逐键合计恒等于 topUpExact"那一道恒等式今天<b>没有</b>离线判据，也不许拿这一行冒充。
     */
    @Test
    void perKeyTopUpFlowNamesEachKeyWithItsOwnUnits() {
        var pattern = patternWithSlots(new FakeInput[]{
                slot(MATERIAL), slot(PRODUCT)});
        var pool = new ListCraftingInventory(ignored -> {
        });
        var probe = new ParallelBatchExtraction.Probe();
        var moved = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L,
                reservationFree(), (what, wanted) -> MATERIAL.equals(what) ? 3L : 5L, probe);

        assertEquals(8L, moved, "夹具前提：两枚键各真搬进 3/5 枚，合计 8");
        assertEquals(3L, ParallelBatchExtraction.exactStored(pool, MATERIAL).longValue(),
                "MATERIAL 那 3 枚必须真落池（没落池的一枚都不许记进流水）");
        assertEquals(5L, ParallelBatchExtraction.exactStored(pool, PRODUCT).longValue(),
                "PRODUCT 那 5 枚必须真落池");

        var laneState = new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L);
        var logic = laneState.logic();
        // 生产侧那一句的同一份入参：快照在前（新探针的表是空的），差值折进 lane 流水
        logic.foldTaskTopUpUnitsPerKey(Map.of(), probe);
        var line = logic.new StallProbe().describeStallLine(UUID.randomUUID(), 1,
                newPlainJob(), pool, null, "…", "", 0L, -2L);

        assertEquals("topUpKeys=[" + MATERIAL + "=3," + PRODUCT + "=5]",
                tokenStartingWith(line, "topUpKeys="),
                () -> "逐枚键各点各的枚数（谁也没替谁）：" + line);
        assertEquals("topUpKeysCov=shown=2/total=2", tokenStartingWith(line, "topUpKeysCov="),
                () -> "两枚键两枚都点了，覆盖面必须自己说清楚：" + line);
        // 位置也钉死：逐键两格紧跟聚合那一族，读日志的人拿同一行就能对上"总账→逐键"
        assertTrue(line.contains(" topUpExact=0 topUpKeys=["),
                () -> "逐键那一格必须接在 topUpExact= 后面同一行：" + line);
    }

    /**
     * 读数 6 的覆盖面那一半：流水里有五枚键、格内只点前四枚时，
     * {@code topUpKeysCov=} 必须报 {@code shown=4/total=5}——不把"看到的四枚"报成"一共四枚"
     * （封顶是纯读数封顶，不是把第五枚抹掉）。
     */
    @Test
    void perKeyTopUpCoverageCountsTheKeysItDoesNotName() {
        var pattern = patternWithSlots(new FakeInput[]{
                slot(MATERIAL), slot(PRODUCT), slot(CHEST), slot(BUCKET), slot(BOOKSHELF)});
        var pool = new ListCraftingInventory(ignored -> {
        });
        var probe = new ParallelBatchExtraction.Probe();
        ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L, reservationFree(),
                (what, wanted) -> 2L, probe);

        var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L).logic();
        logic.foldTaskTopUpUnitsPerKey(Map.of(), probe);
        var line = logic.new StallProbe().describeStallLine(UUID.randomUUID(), 1,
                newPlainJob(), pool, null, "…", "", 0L, -2L);

        assertEquals("topUpKeys=[" + MATERIAL + "=2," + PRODUCT + "=2," + CHEST + "=2," + BUCKET + "=2]",
                tokenStartingWith(line, "topUpKeys="),
                () -> "格内只点得出前四枚（封顶四格），第五枚不许被挤进来也不许被抹掉：" + line);
        assertEquals("topUpKeysCov=shown=4/total=5", tokenStartingWith(line, "topUpKeysCov="),
                () -> "点出四枚、账上五枚：这一格必须自己承认少点了那一枚：" + line);
    }

    // ==================== 读数 7：三处网络读数各带一位问不出的 mountsInUse ====================

    /**
     * 读数 7（台账 #225）：停滞行三处网络读数——{@code backoffBlind=…网络sim=}、
     * {@code missing=…(net: )}、{@code quotaBlind=…net=}——<b>同一行</b>各带一位
     * {@code mountsInUse=?}。
     *
     * <p>这一格补的是"网络真有、我们读 0"的最后一格残留：三格走的都是
     * {@code getStorageService().getInventory().extract(…, SIMULATE, …)}，而交回的那一个
     * {@code NetworkStorage}（AE2 19.2.17 {@code StorageService.java:195-197}）在
     * {@code NetworkStorage.java:48} 有一道私有递归闸——{@code :94-96}（insert）／
     * {@code :173-176}（extract）／{@code :204-207}（getAvailableStacks）三处"闸抬着就
     * 交回一个干净的 0"。闸挡住的那一趟，与"网络真没有这一枚"，在这三格的字面上<b>完全同形</b>。
     *
     * <p><b>问号是有牙的问号</b>：那一个字段没有 getter、{@code MEStorage} 接口
     * （{@code MEStorage.java:62-108}）也没有"此刻在不在递归里"这一问的口，所以这一格恒为
     * {@code ?}；谁拿 {@code net}/{@code 网络sim} 自己的值、或拿"我们这次调用没抛异常"顶成
     * {@code true}/{@code false}，下面三条断言当场红。
     */
    @Test
    void eachNetworkReadoutCarriesTheUnanswerableMountsInUseDigit() {
        // 配给表上留着一份没销的 RATIONED（网络桩不持有它 ⇒ net=0），任务表里另有一张真欠着 MATERIAL
        var consumer = patternWithSlots(new FakeInput[]{slot(RATIONED)});
        var job = jobWithRationOnRationedKey(consumer);
        var backedOff = patternWithSlots(new FakeInput[]{slot(MATERIAL)});
        job.tasks.put(backedOff, taskProgress(5L));
        job.materialBackoffUntil.put(backedOff, 1_000L);
        job.materialBackoffMissing.put(backedOff, new java.util.HashSet<>(Set.of(MATERIAL)));

        var pool = new ListCraftingInventory(ignored -> {
        });
        var line = new ParallelCraftingLaneState(null, UUID.randomUUID(), 10L).logic()
                .new StallProbe().describeStallLine(UUID.randomUUID(), 1, job, pool,
                        gridHolding(MATERIAL, 7L), "…", "", 0L, -2L);

        // 臂①：backoffBlind= 的 网络sim=
        var blind = tokenStartingWith(line, "backoffBlind=");
        assertTrue(blind.contains(",网络sim=7,mountsInUse=?"),
                () -> "网络读数后面必须紧跟问不出的那一位，且不许把 7 顶给它：" + blind);
        assertFalse(blind.chars().anyMatch(Character::isWhitespace),
                () -> "加了这一位仍然要一格一 token：" + blind);

        // 臂②：missing= 的 (net: )（那一格今天只在读到 >0 时印，见本条末尾的诚实说明）
        assertTrue(segment(line, " missing=[", " alloc=").contains("(net: 7 mountsInUse=?)"),
                () -> "missing 的 net 读数也得带上那一位：" + line);

        // 臂③：quotaBlind= 的 net=——这一档点的全是"池内 0 且网络也读 0"的键，正是残留那一型
        assertTrue(segment(line, " quotaBlind=", " ecoOut=").contains(" net=0 mountsInUse=?"),
                () -> "读到 0 的那一格必须自报「闸那一档问不出」：" + line);
    }

    /** 一槽的候选表（每发 1 枚、只收这一枚键）。 */
    private static FakeInput slot(AEKey what) {
        return new FakeInput(new GenericStack[]{new GenericStack(what, 1L)}, 1L, null, Set.of(what));
    }

    /** 任意槽数的样板：定义键固定为 {@code BARREL}，产物固定一枚，够读数用例现取现算。 */
    private static FakePattern patternWithSlots(FakeInput[] slots) {
        return new FakePattern(AEItemKey.of(Items.BARREL), slots,
                List.of(new GenericStack(PRODUCT, 1L)));
    }

    /** 没有配给口径的配给表（回补那条腿的编译期非空入参；族配额一律不夹）。 */
    private static ParallelPlanReservation reservationFree() {
        return MaterialAllocationFixtures.build(
                Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
                Map.of(), Map.of(), Map.of());
    }

    /**
     * 提交期配给表（{@code adoptPlannedMaterialPlan} 那一条真路）：消费者在 {@code RATIONED} 上留一份
     * <b>没销完</b>的配额 ⇒ {@code quotaBlind=} 才会点名这一枚（与
     * {@code ParallelQuotaBlindspotReadoutTest} 同一个建表口，不另造第二本配给账）。
     */
    private static ParallelExecutingCraftingJob jobWithRationOnRationedKey(IPatternDetails consumer) {
        var times = new java.util.LinkedHashMap<IPatternDetails, Long>();
        times.put(consumer, 1L);
        var share = new WideKeyCounter();
        share.add(RATIONED, 42_662_400_000L);
        var material = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(consumer, share);
        var base = new appeng.crafting.CraftingPlan(new GenericStack(CHEST, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                base, new KeyCounter(), new KeyCounter(), 1L, Map.of(), material, null, null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
        return job;
    }

    private static ParallelExecutingCraftingJob.TaskProgress taskProgress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /** 使用手写网格与存储服务，经真实存储接口提供指定键的库存。 */
    private static IGrid gridHolding(AEKey what, long total) {
        return new ParallelRecursiveFinalOutputDeliveryTest.TestGrid(new HoldingNetwork(what, total));
    }

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

    /** {@code alloc=} 那一行：走 StallProbe 的真渲染口，只取 {@code PRODUCT} 那一段。 */
    /** 全场终局计数从装配口现读；产线尚未探测任何提供者也必须显示真实累计。 */
    @Test
    void per100rReadsRealTerminalDropsWithoutCreatingCapabilitySession() {
        var productionFactory = new ProviderBatchCapabilitiesFactoryImpl();
        var before = productionFactory.terminalDrops();
        var receipt = receiptWithBatchId();
        receipt.deliverTerminal(receipt.batchId(), DeclaredBatchTerminalLedger.Outcome.SUCCESS);
        var createCalls = new java.util.concurrent.atomic.AtomicInteger();
        var factory = new git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilitiesFactory() {
            @Override
            public git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities create(
                    java.util.function.Supplier<IGrid> grid) {
                createCalls.incrementAndGet();
                throw new UnsupportedOperationException("诊断不得初始化宿主会话");
            }
            @Override
            public git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalDrops terminalDrops() {
                return productionFactory.terminalDrops();
            }
        };
        var line = renderPer100rLine(newPlainJob(),
                ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(factory));
        assertEquals("terminalDropped[全场JVM]=noLedger=" + (before.noLedger() + 1L)
                        + "/idMismatch=" + before.idMismatch(),
                tokenStartingWith(line, "terminalDropped[全场JVM]="));
        assertEquals(0, createCalls.get());
        assertEquals("queuePendingCov=shown=0/total=0", tokenStartingWith(line, "queuePendingCov="));
    }

    private static String allocationRow(ParallelExecutingCraftingJob job) {
        return ParallelCraftingCpuLogic.StallProbe.formatAllocation(job, Map.of(PRODUCT, 0L),
                Map.of(PRODUCT, 1L), Map.of(PRODUCT, 0L), null);
    }

    private static UselessBigIntegerReceipt receiptWithBatchId() {
        var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        receipt.noteBatchId(UUID.randomUUID());
        return receipt;
    }

    /** 走真渲染口把 per100r 那一行打出来（喂满 100 轮）；捕获 INFO 原文，不直调格式化方法。 */
    private static String renderPer100rLine(ParallelExecutingCraftingJob job) {
        return renderPer100rLine(job, ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster());
    }

    private static String renderPer100rLine(ParallelExecutingCraftingJob job, ParallelCraftingCpuCluster cluster) {
        var appender = new CapturingAppender("coverage-per100r-capture");
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        try {
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
            context.updateLoggers();
            var stallProbe = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 10L)
                    .logic().new StallProbe();
            for (int round = 0; round < 100; round++) {
                stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                        new ParallelBatchExtraction.Probe(), new java.util.HashMap<>(),
                        pool(), job.declaredTerminals, 0L);
            }
            return appender.lastLine("[chexsonsaeutils-batch] per100r");
        } finally {
            configuration.getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        }
    }

    /** 抓 WARN 原文：这一行除了日志没有第二个出口（打印者真会打印它）。 */
    private static String captureWarnLine(Runnable body) {
        var appender = new CapturingAppender("coverage-warn-capture");
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        try {
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.WARN, null);
            context.updateLoggers();
            body.run();
            return appender.lastLine("[chexsonsaeutils-declared] 申报批静默未挂账");
        } finally {
            configuration.getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        }
    }

    /** 取以 {@code marker} 开头的那一个空白分隔 token；缺格直接红，不许把缺失喂给后续断言。 */
    private static String tokenStartingWith(String line, String marker) {
        for (var token : line.split(" ")) {
            if (token.startsWith(marker)) {
                return token;
            }
        }
        throw new AssertionError("行里没有以 " + marker + " 开头的格：" + line);
    }

    /**
     * 取从 {@code marker} 起、到下一个 {@code nextMarker} 前为止的那一段（整段本来就带空格的那些格用，
     * 如 {@code missing=[…]}／{@code quotaBlind=[…]}）。{@code marker} 缺席当场红，
     * {@code nextMarker} 找不到就取到行尾——不把 {@code indexOf} 的 {@code -1} 喂进 {@code substring}。
     */
    private static String segment(String line, String marker, String nextMarker) {
        var from = line.indexOf(marker);
        if (from < 0) {
            throw new AssertionError("行里没有 " + marker + " 那一格：" + line);
        }
        var to = line.indexOf(nextMarker, from + marker.length());
        return line.substring(from, to < 0 ? line.length() : to);
    }

    private static ParallelExecutingCraftingJob newPlainJob() {
        return new ParallelExecutingCraftingJob(new EmptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
    }

    private static ListCraftingInventory pool() {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(MATERIAL, 1_000L, Actionable.MODULATE);
        return inventory;
    }

    private static FakePattern pattern() {
        return new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                        1L, null, Set.of(MATERIAL))},
                List.of(new GenericStack(PRODUCT, 1L)));
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

    /** 最小可用的空计划：作业只要建得起来、台账在就行。 */
    private static final class EmptyPlan implements ICraftingPlan {
        private final KeyCounter empty = new KeyCounter();

        @Override
        public @Nullable GenericStack finalOutput() {
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
    }

    /** 只收 WARN 及以上的原文行。 */
    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null,
                    org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            var message = event.getMessage();
            if (message != null) {
                this.lines.add(message.getFormattedMessage());
            }
        }

        String lastLine(String prefix) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).startsWith(prefix)) {
                    return lines.get(i);
                }
            }
            throw new AssertionError("没抓到前缀为 " + prefix + " 的行；所有行=" + lines);
        }
    }
}
