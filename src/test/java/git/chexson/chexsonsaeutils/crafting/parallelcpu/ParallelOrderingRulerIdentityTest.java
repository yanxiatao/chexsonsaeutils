package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 顺位尺只许有一把：执行侧推送顺位、提交期 mint 那趟排水、族到账摊派顺位三条用途共用一个算式。
 *
 * <p>缺陷形状（{@code 8ad7ca8} 留下的账）：同一份"契约带的内部件 ∪ 任务表现算的环起步量"在
 * {@code ParallelCraftingCpuLogic#ringInternalItemsForOrdering} 与 {@code
 * ParallelExecutingCraftingJob#orderingInternalItems()} 各写了一份。两份实现一定会漂，
 * 漂的那天摊派读的就不是排水那个顺位了。
 *
 * <h3>手算 oracle（独立于被测算式）</h3>
 * <pre>
 *   环样板：每发吃 2 枚种、还 2 枚种，7 发   ⇒ 净流量 0 ⇒ 派生账本记起步量 SEED=2（一发那一份）
 *   旁人样板：每发吃 1 枚雪块、产 1 枚铁锭，3 发 ⇒ 不成环 ⇒ 派生账本里没有它
 *   契约内部件：SEED=1、SNOW=9
 *   —— 取并集、同键取更大起步量 ⇒ {SEED: max(1,2)=2, SNOW: 9}，键数 2
 *   —— 裸计划（AE2 CraftingPlan）/ 契约交白卷 ⇒ 只剩派生那一份 ⇒ {SEED: 2}，键数 1
 * </pre>
 *
 * <p>牙在两把读尺各自对上<b>手算表</b>（算式本身错了也红），以及两个输入喂进
 * {@link RecursiveTaskOrdering} 交出的<b>任务序列</b>（钉的是这把尺的用途，不只是计数器内容）。
 *
 * <p><b>"两个入口逐元素相同"那一臂已经删掉</b>（三条用例里各一份）：{@code 6ea1411} 把
 * {@code ParallelCraftingCpuLogic#ringInternalItemsForOrdering} 改成委托
 * {@code ParallelExecutingCraftingJob#orderingInternalItems()} 之后，那一臂比的两侧就是同一次
 * 调用的两份返回值——恒真，测不到任何东西。委托本身由"两个入口各自对上同一张手算表"那两臂守着，
 * 不需要一条自我比较的断言来撑场面。
 */
final class ParallelOrderingRulerIdentityTest {

    private static final AEKey SEED = AEItemKey.of(Items.NETHERITE_SCRAP);
    private static final AEKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

    private static final long RING_FIRINGS = 7L;
    private static final long RIVAL_FIRINGS = 3L;
    /** 环每发的起步量：吃 2 枚还 2 枚 ⇒ {@code RingSeedLedger} 记的就是这一份。 */
    private static final long DERIVED_SEED = 2L;
    /** 契约把同一个种记成 1 枚：比派生量小 ⇒ 取并集必须留 2 枚。 */
    private static final long CONTRACT_SEED = 1L;
    /** 契约独有的一族（任务表里没有任何样板产它）：派生给不出，只能靠并集留在册。 */
    private static final long CONTRACT_SNOW = 9L;

    /** 环样板：吃 2 枚种、还 2 枚种，外加一枚铁锭。 */
    private static FakePattern ringPattern() {
        return FakePattern.colored(-1, AEItemKey.of(Items.FLOWER_POT),
                List.of(new GenericStack(SEED, DERIVED_SEED), new GenericStack(PRODUCT, 1L)),
                FakeInput.stack(SEED, DERIVED_SEED));
    }

    /** 旁人样板：只吃雪块、不成环，排序上落在环之后。 */
    private static FakePattern rivalPattern() {
        return FakePattern.colored(-1, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(PRODUCT, 1L)),
                FakeInput.stack(SNOW, 1L));
    }

    /**
     * 建作业：任务表两行按手算表给发数。
     *
     * @param contractInternals {@code null} 交回 AE2 裸计划（没有契约那一半），
     *                          非空（含空表）走带内部件的递归契约
     */
    private static ParallelExecutingCraftingJob jobOf(
            FakePattern ring, FakePattern rival, @Nullable KeyCounter contractInternals) {
        Map<IPatternDetails, Long> times = new LinkedHashMap<>();
        times.put(ring, RING_FIRINGS);
        times.put(rival, RIVAL_FIRINGS);
        var base = new CraftingPlan(new GenericStack(PRODUCT, RING_FIRINGS + RIVAL_FIRINGS),
                0L, true, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        ICraftingPlan plan = contractInternals == null
                ? base
                : new RecursiveCraftingPlan(base, new KeyCounter(), contractInternals, 0L,
                        Map.of(), Map.of(), null, null);
        return new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
    }

    /** 契约那一半的内部件：一种比派生量小、一种派生给不出。 */
    private static KeyCounter contractInternalItems() {
        var internal = new KeyCounter();
        internal.add(SEED, CONTRACT_SEED);
        internal.add(SNOW, CONTRACT_SNOW);
        return internal;
    }

    /** {@code KeyCounter} 的迭代序是哈希序（按设计不保证），所以读数一律按键取名、取值再比。 */
    private static Map<String, Long> readout(KeyCounter counter) {
        var snapshot = new TreeMap<String, Long>();
        for (var entry : counter) {
            snapshot.put(String.valueOf(entry.getKey()), entry.getLongValue());
        }
        return snapshot;
    }

    private static Map<String, Long> expectedMerged() {
        var expected = new TreeMap<String, Long>();
        expected.put(String.valueOf(SEED), DERIVED_SEED);
        expected.put(String.valueOf(SNOW), CONTRACT_SNOW);
        return expected;
    }

    private static Map<String, Long> expectedDerivedOnly() {
        var expected = new TreeMap<String, Long>();
        expected.put(String.valueOf(SEED), DERIVED_SEED);
        return expected;
    }

    /** 同键两份读数取更大的那一档：契约记小了也不能把派生量盖掉。 */
    @Test
    void dyeableContractAndDerivedSeedsMergeToTheSameBookThroughBothEntries() {
        var ring = ringPattern();
        var rival = rivalPattern();
        var job = jobOf(ring, rival, contractInternalItems());

        var logicSide = ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job);
        var jobSide = job.orderingInternalItems();

        assertEquals(expectedMerged(), readout(logicSide),
                () -> "执行侧那一入口交出的表不对上手算表：" + readout(logicSide));
        assertEquals(expectedMerged(), readout(jobSide),
                () -> "摊派那一入口交出的表不对上手算表：" + readout(jobSide));
        // 这里原来还有一条 assertEquals(readout(jobSide), readout(logicSide))：6ea1411 把 logicSide
        // 那一入口改成委托 job.orderingInternalItems() 之后，它是同一次调用的两份返回值互比，恒真。
        assertSameOrderingSequence(job, logicSide, jobSide, ring, rival);
    }

    /** 裸计划（AE2 {@code CraftingPlan}）：只剩任务表现算的那一份。 */
    @Test
    void barePlanFallsBackToDerivedSeedsThroughBothEntries() {
        var ring = ringPattern();
        var rival = rivalPattern();
        var job = jobOf(ring, rival, null);

        var logicSide = ParallelCraftingCpuLogic.ringInternalItemsForOrdering(job);
        var jobSide = job.orderingInternalItems();

        assertEquals(expectedDerivedOnly(), readout(logicSide),
                () -> "裸计划这一臂只剩派生账本：" + readout(logicSide));
        assertEquals(expectedDerivedOnly(), readout(jobSide),
                () -> "裸计划这一臂只剩派生账本：" + readout(jobSide));
        // 同一形状的"两入口逐元素相同"臂在此删掉（委托之后恒真，理由见类注释）。
        assertSameOrderingSequence(job, logicSide, jobSide, ring, rival);
    }

    /** 这把尺的用途：同一张任务表经两个入口喂进排序，交出的序列逐元素相同，且环在旁人之前。 */
    private static void assertSameOrderingSequence(ParallelExecutingCraftingJob job,
            KeyCounter logicSide, KeyCounter jobSide,
            FakePattern ring, FakePattern rival) {
        var fromLogic = keysOf(RecursiveTaskOrdering.orderedEntries(job.tasks, logicSide));
        var fromJob = keysOf(RecursiveTaskOrdering.orderedEntries(job.tasks, jobSide));
        assertEquals(fromLogic, fromJob,
                "两个入口喂出来的推送顺位不是同一个序：" + fromLogic + " vs " + fromJob);
        assertEquals(List.of(ring, rival), fromLogic,
                () -> "顺位本身也要钉住（环生产者排最前）：" + fromLogic);
    }

    private static List<IPatternDetails> keysOf(
            List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> ordered) {
        var keys = new ArrayList<IPatternDetails>(ordered.size());
        for (var entry : ordered) {
            keys.add(entry.getKey());
        }
        return keys;
    }
}
