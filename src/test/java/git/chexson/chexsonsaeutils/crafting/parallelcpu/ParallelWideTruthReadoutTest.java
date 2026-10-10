package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
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
 * 台账 #192 的<b>读数真值</b>用例：实机 bigint lane（{@code ae2:creative_storage_cell x 1e11}）尾段
 * 卡死时，三处读数正把真因遮住——饱和哨兵当量摆出来（{@code alloc=} 四格顶格、{@code pushed=} 恒
 * {@code 9223372036854775807}），真卡死的那一格（{@code blocked=} 点名的 flux 格）一个字都没有。
 * 本文件一条用例只红它自己那一格：
 * <ul>
 * <li>{@code alloc=}：排序与六列读数吃宽域真值（&gt;long 印精确十进制，不是天花板）；</li>
 * <li>{@code alloc=}：必含本窗 {@code blocked=} 真失败格／{@code 回补受阻} 点名的键；</li>
 * <li>{@code blocked=}：点名的样板从 1 张扩到最多 3 张（按 {@code owed} 降序），段形状不变；</li>
 * <li>{@code noCandWhy=}：自报身份（末次快照）并补印每发件数（{@code IInput.getMultiplier()}）；</li>
 * <li>{@code ncSplit=}：格名自报窗口（百窗累计、跨样板），子标签 {@code empty} 改说真判据
 *     （池内 &lt; 每发件数——有 7 枚、每发要更多也进这一档，"真没料"那句话术是撒谎）；</li>
 * <li>{@code pushed=}：100 轮合计走宽域真值，不再一轮顶格就把整窗钉死在哨兵上。</li>
 * </ul>
 * 全部判据算式零改动：这些格只被<b>渲染与排序</b>读取，{@code usableFor}／
 * {@code clampByRemainingQuota}／折发数／回补／{@code payInChunks}／{@code usableTemplates}
 * 一条都不经过。
 */
final class ParallelWideTruthReadoutTest {

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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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

    /**
     * {@code noCandWhy=} 只是"最后一次 noteNoCandidate"的快照，与 {@code ncSplit=} 的百窗累计
     * 不是同一窗、不是同一格——实机把两者读成同一格的自相矛盾（"池内 7 枚 accept 得进却进 noCand"）。
     * 这一格必须自报身份（末次），并补印每发件数：串里那个 {@code x1} 是 template 量（AE2 恒 1），
     * 没有 {@code IInput.getMultiplier()}，"7 枚 &lt; 每发 9 枚"这一型永远说不清。
     */
    @Test
    void noCandWhyCellSelfReportsTheLastNoteAndAddsUnitsPerFiring() {
        var p = new FakePattern(AEItemKey.of(Items.ANVIL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.DIRT), 1L)},
                        4L, null, Set.of(AEItemKey.of(Items.DIRT)))},
                List.of(new GenericStack(PRODUCT, 1)));
        var job = newJob();
        job.tasks.put(p, progress(2L));
        var probe = new ParallelBatchExtraction.Probe();
        probe.noteNoCandidate(0, p.getInputs()[0], widePool(), null, p, RESERVATION_FREE);
        var line = stallLine(job, widePool(), probe.noCandKey, probe.noCandStored, probe.noCandQuota);

        assertTrue(line.contains("noCandWhy=[末次]"),
                "这一格要自报它是末次快照（与 ncSplit[百窗累计] 分得开）：" + line);
        assertTrue(List.of(line.split(" ")).contains("每发件数=4"),
                "每发件数 = IInput.getMultiplier() = 4（x1 是 template 量，不是这一问）：" + line);

        // 反向那一档：身份标回查不到（这一张已不在本轮任务表）就明打 "?"，不许拿上一张的每发冒充。
        var emptyTable = newJob();
        var stray = stallLine(emptyTable, widePool(), probe.noCandKey, probe.noCandStored, probe.noCandQuota);
        assertTrue(List.of(stray.split(" ")).contains("每发件数=?"),
                "回查不到就打 ?（纯读数宁缺勿假）：" + stray);
    }

    // ---------- ncSplit=：格名自报窗口，子标签说真判据 ----------

    /**
     * {@code ncSplit} 是 100 窗跨样板累计，不是本轮这一格；子标签 {@code empty} 的旧话术
     * "所有候选现场都是 0——真没料"与它落进的判据（池内 &lt; 每发件数）不符：本用例池里明明躺着
     * 7 枚、每发要 9 枚，也被记进这一档。格名改成 {@code ncSplit[百窗累计]=}、标签改成
     * {@code 池内不够一发}——只改话术，判据算式一字未动。
     */
    @Test
    void ncSplitCellNameSaysItsWindowAndItsLabelSaysTheRealRuler() {
        var cand = AEItemKey.of(Items.AMETHYST_SHARD);
        var p = new FakePattern(AEItemKey.of(Items.CAULDRON),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(cand, 1L)}, 9L, null, Set.of(cand))},
                List.of(new GenericStack(PRODUCT, 1)));
        var pool = widePool();
        pool.insertExact(cand, BigInteger.valueOf(7L), Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();
        probe.noteNoCandidate(0, p.getInputs()[0], pool, null, p, RESERVATION_FREE);
        assertEquals(1L, probe.ncEmpty, "夹具前提：这一档真走 ncEmpty（池内 7 < 每发 9），不是别的归因");

        var line = renderPer100rLine(probe, pool, 0L);
        var token = firstTokenStartingWith(line, "ncSplit");
        assertEquals("ncSplit[百窗累计]=池内不够一发100/nolist0/locked0/short0", token,
                "格名自报窗口、子标签与实现判据对齐（100 轮 × 每轮 1 次 ncEmpty）：整行=" + line);
        assertFalse(line.contains("ncSplit=empty"), "旧话术不许回来：" + line);
    }

    // ---------- pushed=：宽域真值合计 ----------

    @Test
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
    @Test
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
                    ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), UUID.randomUUID(), 10L)
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
}
