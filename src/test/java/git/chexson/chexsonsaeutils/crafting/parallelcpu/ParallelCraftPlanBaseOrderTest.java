package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务表那条底序<b>不是</b>样板哈希的函数（台账 #136，MF-1）。
 *
 * <p>上一刀把 {@code job.tasks} 换成 {@link LinkedHashMap} 只修了容器：喂进那张表的是
 * {@code plan.patternTimes()} 的遍历序，而它来自 {@code Ae2PlanConverter} 的
 * {@code new HashMap<>(state.crafts())} 与 {@code WideCraftCounter} 主表
 * （{@code Object2LongOpenHashMap}）——两层都是哈希序。样板的哈希一路取到 {@code Item} 的
 * <b>身份哈希</b>（AE2 源码 {@code AECraftingPattern:137}／{@code AEProcessingPattern:85}／
 * {@code AESmithingTablePattern:138}／{@code AEStonecuttingPattern:117} 一律
 * {@code return definition.hashCode();}，再往下是 {@code AEItemKey:60} 的
 * {@code ItemStack.hashItemAndComponents}）⇒ <b>换一次 JVM 会话就换一次顺位</b>。
 *
 * <p>本用例打的就是"跨会话"这件事，形状是：同一份计划内容、两组样板实例（第二组<b>实例本身</b>
 * 换掉 ⇒ 身份哈希换掉，并且按相反顺序 new 出来交给图），逐字比一遍顺位与这条顺位喂出来的数字。
 * 改前的树上两份读数的先后可以相反；改后顺位＝份数账的<b>注册序</b>（求解先给谁记账），与实例无关。
 * 期望值另钉一份独立手算表（{@link #handTable()}），不是"两次调用互相比对"。
 *
 * <p>为什么"换一组实例"就等于"换一次会话"：会话之间变的正是 {@code Item} 那一位身份哈希，
 * 它进了 {@code AEItemKey#hashCode}、又进 {@code WideCraftCounter} 主表的槽位序。用例里不另起
 * JVM，就用同一维自由度（键的哈希不可预测）把它搬进一次运行。
 */
final class ParallelCraftPlanBaseOrderTest {

    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
    /** 内容序（＝注册序）：石、铁砧、雪、书、制图台。definition 的字符串序与它无关。 */
    private static final AEItemKey DEF_FIRST = AEItemKey.of(Items.STONE);
    private static final AEItemKey DEF_SECOND = AEItemKey.of(Items.ANVIL);
    private static final AEItemKey DEF_THIRD = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey DEF_VARIANT_FIRST = AEItemKey.of(Items.WRITABLE_BOOK);
    private static final AEItemKey DEF_VARIANT_SECOND = AEItemKey.of(Items.CARTOGRAPHY_TABLE);
    /** 同一族（一把破损铁镐）的两个变体键，各由一张样板付。 */
    private static final AEKey VARIANT_ONE = wornKey(10);
    private static final AEKey VARIANT_TWO = wornKey(20);

    private static final List<AEItemKey> CONTENT_ORDER =
            List.of(DEF_FIRST, DEF_SECOND, DEF_THIRD, DEF_VARIANT_FIRST, DEF_VARIANT_SECOND);

    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger ZERO = BigInteger.ZERO;

    /**
     * 独立手算表（纸面算出，不经被测代码）。每一格是"这一张样板在这一键上的配给份额"，
     * 排水与族摊派都按<b>注册序</b>逐位扣：
     *
     * <table border="1">
     * <caption>#136：五张样板、玻璃池 2、变体族池 1</caption>
     * <tr><th>样板（注册序）</th><th>吃</th><th>发数</th><th>mint 份额</th>
     *     <th>族摊派后</th><th>planBalance 点名</th></tr>
     * <tr><td>石</td><td>玻璃 1</td><td>1</td><td>1（池 2→1）</td><td>1</td><td>—</td></tr>
     * <tr><td>铁砧</td><td>玻璃 1</td><td>1</td><td>1（池 1→0）</td><td>1</td><td>—</td></tr>
     * <tr><td>雪</td><td>玻璃 1</td><td>1</td><td>1（池空，记 shortfall）</td><td>0（摊不到钱）</td>
     *     <td>want= 点名它，hole=1</td></tr>
     * <tr><td>书</td><td>变体一 1</td><td>1</td><td>1</td><td>1（族池 1 被登记首位摊走）</td><td>—</td></tr>
     * <tr><td>制图台</td><td>变体二 1</td><td>1</td><td>1</td><td>0（族池已空）</td><td>—</td></tr>
     * </table>
     *
     * <p>底序错了这一张表就整个错：玻璃池那两枚落在<b>哪两张</b>头上、{@code want=} 点名谁、
     * 变体族那 1 枚归给<b>哪个变体</b>，在哈希序下都由键的哈希决定。
     */
    private static Expected handTable() {
        return new Expected(CONTENT_ORDER,
                Map.of(DEF_FIRST, ONE, DEF_SECOND, ONE, DEF_THIRD, ZERO,
                        DEF_VARIANT_FIRST, ONE, DEF_VARIANT_SECOND, ZERO),
                DEF_THIRD);
    }

    /** 手算表形状：顺位、逐张份额、被点名没盖住的那一张。 */
    private record Expected(List<AEItemKey> order, Map<AEItemKey, BigInteger> sharesAfterNarrowing,
            AEItemKey namedUncovered) {
    }

    @Test
    void taskTableOrderAndTheNumbersItFeedsAreIndependentOfPatternIdentity() {
        var hand = handTable();
        // 两次构造＝同一份计划内容的两组实例：实例不同⇒身份哈希不同（第二组还按相反顺序 new
        // 出来交给图）。改前的顺位正是由那一位哈希决定，两次可以给出相反的答案。
        var first = readBuild(false);
        var second = readBuild(true);

        for (var build : List.of(first, second)) {
            assertEquals(hand.order(), build.planTimesOrder(),
                    "交出去的份数字典顺位＝规划侧注册序，不是主表哈希序");
            assertEquals(hand.order(), build.taskOrder(),
                    "作业任务表的底序＝那份注册序（三条顺位共用它）");
            assertEquals(hand.sharesAfterNarrowing(), build.sharesAfterNarrowing,
                    "族池摊派与变体归属：钱落在哪几张、族里哪个变体上，按底序逐位扣");
            assertTrue(build.planBalance().contains(" want=[" + label(hand.namedUncovered) + "=1]"),
                    () -> "planBalance 该点名注册序第三位（雪）——池子只有两枚玻璃。整行="
                            + build.planBalance());
            assertTrue(build.planBalance().contains(" minecraft:glass hole=1 uncoveredNeed=1 "),
                    () -> "少配的就是那 1 枚（每发 1 × 1 发）：" + build.planBalance());
            assertFalse(build.planBalance().contains(label(DEF_FIRST)),
                    () -> "注册序首位拿到了那两枚里的第一枚，不许被报成缺口。整行=" + build.planBalance());
            assertFalse(build.planBalance().contains(label(DEF_SECOND)),
                    () -> "注册序次位同理。整行=" + build.planBalance());
        }
        // 顺位立住了才会有这一条：两组实例的整行读数逐字相同
        assertEquals(first.planBalance(), second.planBalance(),
                "同一份计划的两组实例构造读出不同的行——底序还跟着哈希走");
    }

    /** 一次构造：真实出口（转换器）+ 真实作业 + 真实排水 mint + 真实摊派。 */
    private static Build readBuild(boolean reversePatternCreationOrder) {
        var patterns = newPatterns(reversePatternCreationOrder);
        var state = new GraphPlanningState(Map.of(GLASS, 0L), Map.of());
        // 登记一律按内容序（＝求解先给谁记账那一序）；两组实例的差别只在实例本身与图的登记列表序
        for (var definition : CONTENT_ORDER) {
            state.addCrafting(patterns.byDefinition.get(definition), 1L);
        }
        state.addUsedForReconciliation(GLASS, 2L);
        state.addUsedForReconciliation(VARIANT_ONE, 1L);
        state.addUsedForReconciliation(VARIANT_TWO, 1L);

        var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1L, false), graph(patterns));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        var planTimesOrder = plan.patternTimes().keySet().stream()
                .map(IPatternDetails::getDefinition).toList();
        var taskOrder = job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList();
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        // 唯一的收窄口：玻璃族到账 2（与 mint 那份供给同量，只检顺位）、变体族到账 1
        job.narrowPlanMaterialShareByArrival(GLASS, PlanAmount.of(2L), PlanAmount.ZERO);
        job.narrowPlanMaterialShareByArrival(VARIANT_ONE, PlanAmount.of(1L), PlanAmount.ZERO);

        var shares = new LinkedHashMap<AEItemKey, BigInteger>();
        for (var task : job.tasks.entrySet()) {
            var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(task.getKey());
            var total = BigInteger.ZERO;
            for (AEKey key : assigned.keys()) {
                total = total.add(assigned.getExact(key));
            }
            shares.put(task.getKey().getDefinition(), total);
        }
        return new Build(planTimesOrder, taskOrder, line, shares);
    }

    /** 一次构造的全部读数。 */
    private record Build(List<AEItemKey> planTimesOrder, List<AEItemKey> taskOrder, String planBalance,
            Map<AEItemKey, BigInteger> sharesAfterNarrowing) {
    }

    /** {@code describeConsumers} 那一格的标签形状（样板身份只用 definition，不参与任何排序）。 */
    private static String label(AEItemKey definition) {
        return definition + "#" + BaseOrderPattern.class.getSimpleName() + " unit=1x1";
    }

    /** 五张样板；第二组按相反顺序 new 实例（身份哈希与第一组无关），内容序登记不变。 */
    private static Patterns newPatterns(boolean reverseCreationOrder) {
        var byDefinition = new LinkedHashMap<AEItemKey, BaseOrderPattern>();
        for (var definition : CONTENT_ORDER) {
            byDefinition.put(definition, new BaseOrderPattern(definition, inputFor(definition)));
        }
        var created = new ArrayList<>(byDefinition.values());
        if (reverseCreationOrder) {
            Collections.reverse(created);
        }
        return new Patterns(byDefinition, created);
    }

    private static AEKey inputFor(AEItemKey definition) {
        if (DEF_VARIANT_FIRST.equals(definition)) {
            return VARIANT_ONE;
        }
        if (DEF_VARIANT_SECOND.equals(definition)) {
            return VARIANT_TWO;
        }
        return GLASS;
    }

    /** 内容序登记表 + 实例创建序（后者交给图，正是不许影响读数的东西）。 */
    private record Patterns(LinkedHashMap<AEItemKey, BaseOrderPattern> byDefinition,
            List<BaseOrderPattern> createdInOrder) {
    }

    private static CraftGraph graph(Patterns patterns) {
        return new CraftGraph(CELL,
                Map.of(),
                new IdentityHashMap<IPatternDetails, git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern>(),
                List.copyOf(patterns.createdInOrder()),
                Map.of(), Map.of(), Map.of(),
                Map.of(),
                Set.of(),
                Map.of(), false, null, 1, 1);
    }

    private static AEKey wornKey(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    /**
     * 单格单候选的样板。<b>刻意不覆写 {@code equals/hashCode}</b>：换一组实例就换一次哈希，
     * 而生产路上的样板换一次 JVM 会话就换一次哈希（同一维自由度）。
     */
    private static final class BaseOrderPattern implements IPatternDetails {

        private final AEItemKey definition;
        private final AEKey input;

        private BaseOrderPattern(AEItemKey definition, AEKey input) {
            this.definition = definition;
            this.input = input;
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {new SingleInput(input)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(CELL, 1L));
        }
    }

    /** 一发吃一枚候选、不退料：不是模具槽，排水按整批发数计费。 */
    private record SingleInput(AEKey candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(candidate, 1L)};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey key, @Nullable net.minecraft.world.level.Level level) {
            return candidate.equals(key);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
