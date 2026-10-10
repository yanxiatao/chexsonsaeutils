package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场量派生的批量提取：能提几发只看池子里有多少，不为任何用途扣着键等人。
 *
 * <p>唯一例外是现场落得下不止一个键的槽——候选表被撑开也好、按物品本体放行组件变体也好：
 * 只吃得下某个键的任务还挂在表上时，那份现场料是它的，本槽按顺位也不能改道吃
 * （见 {@link #fuzzySlotSkipsMaterialReservedByOtherTasks}
 * 与 {@link #singleCandidateVariantSlotKeepsReservedVariant}）。
 *
 * <p>环催化物的起步份数是规划期多算一发产出、随 {@code usedItems} 一次提进 CPU 库存的；
 * 执行侧只靠 {@code RingSeedLedger} 调整推送顺序，不改这里的取料量。
 */
final class ParallelBatchExtractionTest {

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

    @Test
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
    @Test
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
    @Test
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
    @Test
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

    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
}
