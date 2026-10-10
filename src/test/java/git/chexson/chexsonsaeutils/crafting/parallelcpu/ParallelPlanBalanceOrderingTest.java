package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停滞行 {@code planBalance=} 里 {@code hole=}/{@code want=} 的<b>顺位</b>必须与执行侧推送顺位
 * 同一把尺（台账 #134）。
 *
 * <p>缺陷形状：{@code describePlanBalance} 过去先把任务表按 {@code getDefinition()} 的字符串排一遍、
 * 再套 {@link RecursiveTaskOrdering}，而推送那一路（{@code ParallelCraftingCpuLogic#tickCraftingLogic}）
 * 与族到账摊派（{@code ParallelExecutingCraftingJob#rationConsumersInExecutionOrder}）喂给同一把尺的
 * 是 {@code job.tasks} 的原始插入序。并列项（谁都不是环成员）的次序两行可以相反 ⇒ 报出来的
 * "谁没被供给盖住"与"现场真实扣料的先后"不再一致，而它上面那句注释正声称一致。
 *
 * <p>本用例的夹具刻意把两个序<b>反向</b>摆：插入序 [石, 铁砧]，definition 字符串序 [铁砧, 石]。
 * 池子里只有一份玻璃 ⇒ 按顺位排水，摊不到钱的是<b>推送顺位的次位</b>。报告点名的必须就是它。
 *
 * <p>第二条前提（任务表 {@code keySet()} 的序）同时钉住一件实测到的事：这本表<b>不能</b>是
 * {@link java.util.HashMap}。改前它是——并列项的先后于是由 {@code AEItemKey#hashCode}（取自
 * {@code Item} 的身份哈希）决定，<b>同一份夹具两次跑出两份相反的序</b>
 * （{@code [stone, anvil]} 与 {@code [anvil, stone]}），"推送顺位"跟着 JVM 会话变，账面数字无从复现。
 * 换成 {@link java.util.LinkedHashMap} 之后底序＝计划下发（或 NBT 读回）那一份序，上面那句
 * "那把尺不动并列项"才真的把插入序交到底。
 */
final class ParallelPlanBalanceOrderingTest {

    /** 玻璃只够一份：谁排在前面谁就拿走它，后面那一号进 {@code hole=}。 */
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
    /** 插入序第一位，definition 字符串序<b>第二</b>位（{@code item.minecraft.stone} > {@code item.minecraft.anvil}）。 */
    private static final AEItemKey DEF_FIRST_INSERTED = AEItemKey.of(Items.STONE);
    /** 插入序第二位，definition 字符串序<b>第一</b>位。 */
    private static final AEItemKey DEF_FIRST_BY_STRING = AEItemKey.of(Items.ANVIL);

    /**
     * 报告顺位 == 推送顺位：两份料只够一份时，被点名没盖住的那一个必须是<b>推送顺位次位</b>。
     *
     * <p>独立手算表（不经被测排序）：
     * <table border="1">
     * <caption>#134：插入序与 definition 序相反的一对样板</caption>
     * <tr><th>样板</th><th>插入序</th><th>definition 字符串序</th><th>每发吃玻璃</th><th>发数</th>
     *     <th>排水（池子只有 1 枚）</th></tr>
     * <tr><td>石（{@code item.minecraft.stone}）</td><td>第 1</td><td>第 2</td><td>1</td><td>1</td>
     *     <td>盖住：配给 1，不进 hole</td></tr>
     * <tr><td>铁砧（{@code item.minecraft.anvil}）</td><td>第 2</td><td>第 1</td><td>1</td><td>1</td>
     *     <td>没盖住：hole=1，want= 点名它</td></tr>
     * </table>
     * 改前的 definition 预排把两行的次序整个翻过来 ⇒ {@code want=} 点名的是石——正是这一条要咬住的形状。
     */
    @Test
    void planBalanceNamesTheConsumerThatThePushOrderPutsSecond() {
        var firstInserted = new OrderingPattern(DEF_FIRST_INSERTED);
        var firstByString = new OrderingPattern(DEF_FIRST_BY_STRING);
        // 夹具前提一：两个序真的反向摆着（不反向就没有并列项可分辨，本用例就空转）
        assertTrue(String.valueOf(DEF_FIRST_INSERTED).compareTo(String.valueOf(DEF_FIRST_BY_STRING)) > 0,
                "夹具必须让插入序与 definition 字符串序相反，否则这条判据无从落地");
        var plan = planWith(firstInserted, firstByString);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        // 夹具前提二：任务表的插入序就是 [石, 铁砧]，且谁都不是环成员 ⇒ 推送顺位＝插入顺位
        assertEquals(List.of(DEF_FIRST_INSERTED, DEF_FIRST_BY_STRING),
                job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList(),
                "任务表插入序没立住：本用例读的就是这一份序");
        var pushOrder = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
        assertEquals(List.of(DEF_FIRST_INSERTED, DEF_FIRST_BY_STRING),
                pushOrder.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                "推送顺位＝插入顺位（两份都不是环成员，那把尺不动并列项）");

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);

        assertTrue(line.contains(" src=submit"),
                () -> "读的是自算那一臂（排水按 orderedTasks 走）：" + line);
        assertTrue(line.contains(" want=[" + consumerLabel(firstByString) + "="),
                () -> "报告点名的该是推送顺位次位（铁砧）——池子只有一份料，排在后面的那个没被盖住。"
                        + "整行=" + line);
        assertFalse(line.contains(consumerLabel(firstInserted)),
                () -> "推送顺位首位（石）拿到了那一份料，不许被报成缺口；报出它＝报告顺位与推送顺位反了。"
                        + "整行=" + line);
        assertTrue(line.contains(" hole=1 "),
                () -> "没盖住的就是那 1 枚（每发 1 × 1 发）：" + line);
    }

    /** 重复日志不复加自产、配给或已吃账；同一个 reservation 始终读当前消费量。 */
    @Test
    void repeatedSubmitReadoutKeepsEveryMaterialBookUnchanged() {
        var pattern = new OrderingPattern(DEF_FIRST_INSERTED);
        var plan = planWith(pattern, new OrderingPattern(DEF_FIRST_BY_STRING));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        job.addPlanMaterialTaken(pattern, GLASS, 1L);
        var emit = job.accountPlannedEmit.getExact(CELL);
        var ration = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS);
        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getExact(GLASS);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var first = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertEquals(first, ParallelCraftingCpuLogic.describePlanBalance(job, plan));
        assertEquals(emit, job.accountPlannedEmit.getExact(CELL));
        assertEquals(ration, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS));
        assertEquals(eaten, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getExact(GLASS));
        assertEquals(0L, reservation.remainingQuota(pattern, GLASS));
    }

    /** 未初始化的作业读日志也不得建账；提交建账不依赖任何日志调用。 */
    @Test
    void diagnosticsDoNotInitializeMaterials() {
        var plan = planWith(new OrderingPattern(DEF_FIRST_INSERTED),
                new OrderingPattern(DEF_FIRST_BY_STRING));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertTrue(job.accountPlannedEmit.isEmpty());
        assertTrue(MaterialAllocationFixtures.assignedSnapshot(job).isEmpty());
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        assertEquals(java.math.BigInteger.valueOf(2L), job.accountPlannedEmit.getExact(CELL));
        assertEquals(2, MaterialAllocationFixtures.assignedSnapshot(job).size());
    }

    /** 计划配给短交后收窄到零；再次打印不得重新采用计划并抹掉收窄结果。 */
    @Test
    void adoptedRationIsNotRestoredByDiagnosticsAfterShortArrival() {
        var pattern = new OrderingPattern(DEF_FIRST_INSERTED);
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(pattern, 1L);
        var assigned = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        assigned.add(GLASS, 10L);
        var envelope = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        envelope.add(GLASS, 3L);
        var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                base, new KeyCounter(), new KeyCounter(), 1L, Map.of(), Map.of(pattern, assigned),
                null, null, java.util.Set.of(), Map.of(pattern, envelope));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        job.narrowPlanMaterialShareByArrival(GLASS,
                git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO,
                git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO);
        var emit = job.accountPlannedEmit.getExact(CELL);
        var first = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertEquals(first, ParallelCraftingCpuLogic.describePlanBalance(job, plan));
        assertEquals(java.math.BigInteger.ZERO, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(GLASS));
        assertEquals(java.math.BigInteger.valueOf(3L),
                MaterialAllocationFixtures.envelopeSnapshot(job).get(pattern).getExact(GLASS));
        assertEquals(emit, job.accountPlannedEmit.getExact(CELL));
        assertEquals(java.math.BigInteger.TEN, assigned.getExact(GLASS));
    }

    /** 自产两倍 long 上限可完整支付下游两发；显示饱和不得参与排水。 */
    @Test
    void submitAllocationUsesExactSelfProductionBeyondLong() {
        var producer = new ProductionPattern(DEF_FIRST_INSERTED, GLASS, 1L, CELL, Long.MAX_VALUE);
        var consumer = new ProductionPattern(DEF_FIRST_BY_STRING, CELL, Long.MAX_VALUE,
                AEItemKey.of(Items.COMPASS), 1L);
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(producer, 2L);
        times.put(consumer, 2L);
        var used = new KeyCounter();
        used.add(GLASS, 2L);
        var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.COMPASS), 2L),
                0L, true, false, used, new KeyCounter(), new KeyCounter(), times);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> { }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var exact = java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO);
        assertEquals(exact, job.accountPlannedEmit.getExact(CELL));
        assertEquals(exact, MaterialAllocationFixtures.assignedSnapshot(job).get(consumer).getExact(CELL));
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertFalse(line.contains(" hole="), () -> line);
        assertEquals(exact, job.accountPlannedEmit.getExact(CELL));
    }

    private record ProductionPattern(AEItemKey definition, AEKey input, long units,
            AEKey output, long amount) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() { return definition; }
        @Override
        public IInput[] getInputs() {
            return new IInput[] {new IInput() {
                @Override
                public GenericStack[] getPossibleInputs() {
                    return new GenericStack[] {new GenericStack(input, units)};
                }
                @Override
                public long getMultiplier() { return 1L; }
                @Override
                public boolean isValid(AEKey key, net.minecraft.world.level.Level level) {
                    return input.equals(key);
                }
                @Override
                public @Nullable AEKey getRemainingKey(AEKey key) { return null; }
            }};
        }
        @Override
        public List<GenericStack> getOutputs() { return List.of(new GenericStack(output, amount)); }
    }

    /** 消费者标签与 {@code describeConsumer} 同一形状：样板身份取 definition，不参与任何排序算式。 */
    private static String consumerLabel(IPatternDetails pattern) {
        return pattern.getDefinition() + "#" + pattern.getClass().getSimpleName() + " unit=1x1";
    }

    /** 两份样板各 1 发、各吃 1 枚玻璃；{@code usedItems} 只给得出 1 枚 ⇒ 顺位次位必欠。 */
    private static CraftingPlan planWith(IPatternDetails first, IPatternDetails second) {
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(first, 1L);
        times.put(second, 1L);
        var used = new KeyCounter();
        used.add(GLASS, 1L);
        return new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                used, new KeyCounter(), new KeyCounter(), times);
    }

    /** 单格单候选（玻璃 1 枚）、产出一枚箱子的样板；不在任何环里 ⇒ 环优先那把尺不动它的序。 */
    private record OrderingPattern(AEItemKey definition) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {new OrderingInput(GLASS)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(CELL, 1L));
        }
    }

    private record OrderingInput(AEKey candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(candidate, 1L)};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate.equals(input);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
