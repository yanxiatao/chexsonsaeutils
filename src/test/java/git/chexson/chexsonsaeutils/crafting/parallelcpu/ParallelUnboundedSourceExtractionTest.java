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
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无限源在<b>取料点</b>的那一条腿：它不经池子，按需就地认量。
 *
 * <p>提交侧刻意不向网络抽无限源键（{@code tryExtractInitialItems} 遇无限键 {@code continue}），
 * 于是池子里这些键恒为 0；而取料点的候选宇宙是 AE2 {@code getValidItemTemplates} 递回来的
 * {@code inv.findFuzzyTemplates(stack.what())} —— <b>只有池子里现存的键</b>。两者一叠，实机形态就是
 * {@code ae2:creative_storage_cell x 1000000} 那一跑：30 个任务、一发不推进、994 次尝试全记成
 * {@code ncSplit=... nolist994}（这一格连候选都递不出来）。
 *
 * <p>本文件钉住修好之后的四条：
 * <ul>
 *   <li>无限键的候选回到<b>样板声明的候选表</b>，份数<b>不</b>夹在这一格上（由别的槽钉住）；</li>
 *   <li>付款就地认量：既不向池子 {@code extract}，也不向池子 {@code insert}；</li>
 *   <li>整批凑不齐而回滚时，无限键那一格本就不存在 ⇒ 不许被塞回池子（进池即被 {@code storeItems}
 *       当残池投回网络 = 凭空多一份料）；</li>
 *   <li>申报上界同尺：无限格不参与"现场量 ÷ 每发件数"，否则申报归零（实机 {@code declaredReject=99}）。</li>
 * </ul>
 *
 * <p>无限判别仍只有那一条：键在 {@code ParallelPlanReservation#isUnboundedSupply} 的族集合里
 * （由"同一笔请求 SIMULATE 答应而 MODULATE 一枚不给"认下）。"报了多少"与"一次能取出多少"
 * 都不在这里当判据，所以"存量 &gt; long 因而向网络报满 long 的有限盘"不会走到这些用例的任何一支。
 */
final class ParallelUnboundedSourceExtractionTest {

    /** 就地产出的那一个键：创造盘／无限岩浆这一型，池子里永远躺着 0。 */
    private static final AEItemKey INFINITE_SOURCE = AEItemKey.of(Items.LAVA_BUCKET);
    /** 同族（同一物品本体）两个变体：认下受损的那一枚，全新的也算无限源。 */
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
    @Test
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
     * 无限豁免按<b>物品本体族</b>放行：样板声明的是全新的镐，认下的是受损的那一枚——同一物品本体，
     * 取料点要按同一把尺子认，否则这一格又回到"连候选都递不出来"。
     */
    @Test
    void theUnboundedFamilyCoversVariantsOfTheSameItemBody() {
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
        assertTrue(reservation.isUnboundedSupply(SAME_BODY_FRESH),
                "夹具前提：认下的那一族必须覆盖到本用例声明的变体");

        var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 9L,
                new KeyCounter(), new KeyCounter(), reservation);

        assertNotNull(batch);
        assertEquals(2L, batch.executions());
        assertEquals(MINTED_UNITS_PER_FIRING * 2L, batch.holder()[0].get(SAME_BODY_FRESH));
    }

    /**
     * 就地认量的那一截<b>一枚都不许落进池子</b>，成功与回滚两型都要成立。
     *
     * <p>回滚型（有限格一发真需 {@code >long}）才是这一条的用力点：{@code reinjectTaken} 只要把
     * holder 里的每一格都 {@code insert} 回去，无限格那一份就地造出来的料就进了池子，下一刻被
     * {@code storeItems()} 当残池投回网络 —— 凭空多一份料，本仓已因此回退过一次。
     */
    @Test
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
    @Test
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
    @Test
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
    @Test
    void declaredCeilingIsNotZeroedByTheUnboundedSlot() {
        var pool = widePool();
        pool.insert(ORE, 4L, Actionable.MODULATE);

        var declared = ParallelBatchExtraction.declaredPayableCeiling(INFINITE_THEN_ORE, pool, null,
                RESERVATION_UNBOUNDED, new BigInteger("10000000000000000000"));

        assertEquals(BigInteger.valueOf(4L), declared,
                "上界由有限格的现场量给出：无限格给得出任意多枚，就不是这一格的约束项");
    }

    /** 对照：不认任何键为无限源时，申报上界与批量口径逐字保持老形状（真缺料仍然算缺料）。 */
    @Test
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
    @Test
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
    @Test
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
    @Test
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
     * <p>对照半边钉住"本批现场认下"那一型：<b>同样不搬也不注</b>。这一批付不出、欠账留在义务账上，
     * 下一 tick 配给表认得它之后再就地认量——用一 tick 延迟换"认量绝不进池"这条不变式。
     */
    @Test
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

        // 对照：本批现场才认下的那一型（账上还没有它）也不注进池子——认下即收手，键交回作业，
        // 下一 tick 的配给表认得它，那一批由取料点就地认量。这一批付不出是<b>一 tick 延迟</b>，
        // 欠账原样留在义务账上，不是丢料。
        var freshPool = widePool();
        freshPool.insert(ORE, 4L, Actionable.MODULATE);
        var fresh = new CreativeRefill();
        long freshPulled = ParallelBatchExtraction.refillObligationBatch(INFINITE_THEN_ORE, freshPool,
                null, 2L, RESERVATION_FREE, fresh);
        assertEquals(0L, freshPulled, "现场认下即收手：凭空的量一枚都不进池子");
        assertEquals(0, WideCraftingInventory.amount(freshPool, INFINITE_SOURCE).signum(),
                "注进池子那一写法留下的残池，唯一去处是 storeItems() 投回网络 = 凭空多一份料");
        assertEquals(Set.of(INFINITE_SOURCE), fresh.noted, "认中要交回作业，下一批才就地认量");
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
}
