package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 模糊槽的<b>节点身份</b>必须落在「取料点顺位真伸手、且在这一格规划宇宙里」的那一枚候选上（台账 #201）。
 *
 * <p>探针图（前一条刀离线复现，逐字）：消费槽声明顺位
 * {@code [GHOST@1（无生产者、isValid 不收）, DIGITAL@2（有生产者）]}，
 * 旧 {@code resolveNodeKey} 只在<b>每发件数相等</b>时退替身，于是 DIGITAL 被那句
 * "don't replace 1000 water by 1000 buckets" 挡在门外 ⇒ node key 留在幽灵键上 ⇒
 * 闭式解把整格需求算在 GHOST 名下、DIGITAL 的生产者一次都没排进计划 ⇒ 闸门报
 * {@code material closure violated on fuzzy slot ... billed=structure_void}，
 * 实机同型（{@code bigger_ae2:digital_singularity_flux_storage_cell}）执行侧永远
 * {@code ranDry}，整条 lane 卡死。
 *
 * <p>三条臂各有一把自己的牙：
 * <ul>
 * <li>{@link #ghostFirstCandidateHandsTheCellToTheReachCandidateProducer}：只红"伸手那一枚的键没换过去"
 * （图上另有生产者时demand 必须落在 DIGITAL 上、生产者必须排进任务表）；</li>
 * <li>{@link #reachCandidateWithoutProducerStillReportsItsCellAndWideGap}：只红"伸手那一枚必须在图上
 * 有生产者才算"——宇宙（{@code isValid}）收下它就要按它记账，图上没生产者时需求照排、缺口走既有的
 * 延后判缺腿报出<b>哪一格、缺多少（宽域真值）</b>；</li>
 * <li>{@link #declaredFirstCandidateKeepsItsNodeKeyAndEveryPlanningNumber} 与
 * {@link #patternLessFirstCandidateStillStepsOntoItsCraftableSiblingVariant}：只红"放宽成随便换一枚"——
 * 首位本来就可付的槽（含同族可付变体那一档）node key 与每发件数逐字不动。</li>
 * </ul>
 *
 * <p>每发件数与键一起换不是顺手加的：执行侧那一格的份额口径是
 * {@code SlotAccount#unitsPerFiringExact(slot, candidate)}＝槽倍率 × <b>那一枚自己的</b>每发量。
 * 换了键而量仍按声明首位记，就是把每发 2 枚的一格算成每发 1 枚——排出来的产正好少一半，
 * lane 走到一半照样 {@code ranDry}。
 */
final class GraphCompilerFuzzyReachNodeKeyTest {

    /** 探针图的幽灵首位：无生产者、{@code isValid} 不收。 */
    private static final AEItemKey GHOST = AEItemKey.of(Items.STRUCTURE_VOID);
    /** 探针图的伸手那一枚：每发 2 枚、有生产者（实机同型 digital_singularity_flux_storage_cell）。 */
    private static final AEItemKey DIGITAL = AEItemKey.of(Items.NETHERITE_INGOT);
    /** 第二候选：每发 1 枚——旧规则"件数相等才退替身"就是被这一档与 DIGITAL@2 的差异挡住的。 */
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOW_BLOCK);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);

    /** 探针图那一格的声明：顺位 {@code [GHOST@1, DIGITAL@2]}，宇宙只收 DIGITAL。 */
    private static FakeInput probeSlot() {
        return FakeInput.ordered(1L,
                List.of(new GenericStack(GHOST, 1L), new GenericStack(DIGITAL, 2L)),
                List.of(DIGITAL));
    }

    /** 臂 1 的探针图：消费槽 + DIGITAL 的生产者。 */
    private static FakeGrid probeGrid(FakePattern consumer, FakePattern makeDigital) {
        return FakeGrid.builder()
                .storage(Map.of(IRON, 100L))
                .pattern(TARGET, consumer)
                .pattern(DIGITAL, makeDigital)
                .build();
    }

    /**
     * 排产臂：node key 落到伸手那一枚之后，DIGITAL 的生产者必须进任务表，份数按它自己那一份每发量记。
     *
     * <p>HEAD 上逐字红在这里：{@code LinearPlanner.solve} 交回
     * {@code material closure violated on fuzzy slot ... billed=structure_void ranked=[...@2=0]}
     * ——这一格被算在幽灵名下，DIGITAL 一次都没排产，闸门当场拒掉整条规划。
     */
    @Test
    void ghostFirstCandidateHandsTheCellToTheReachCandidateProducer() {
        var makeDigital = FakePattern.crafting(DIGITAL, 2L, Map.of(IRON, 1L));
        var consumer = FakePattern.of(TARGET, 1L, probeSlot());
        var grid = probeGrid(consumer, makeDigital);

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        assertFalse(graph.hasDeclined(), () -> "探针图该编得出来: " + graph.declineReason());
        var slot = graph.patternsBySource().get(consumer).inputs().get(0);
        assertEquals(DIGITAL, slot.key(),
                () -> "这一格的 node key 必须是顺位真伸手的那一枚，不是被 isValid 挡下的幽灵 " + GHOST);
        assertEquals(2L, slot.amountPerCraft(),
                "每发件数跟着伸手那一枚走：执行侧按 candidate 自己那份量吃料");

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10L);
        assertFalse(result.hasDeclined(),
                () -> "HEAD 上这一格算在 " + GHOST + " 名下、" + DIGITAL + " 的生产者不排产: "
                        + result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);

        assertEquals(10L, plan.patternTimes().get(consumer), "订单 10 枚 ⇒ 消费样板 10 发");
        assertEquals(10L, plan.patternTimes().get(makeDigital),
                () -> "10 发 × 每发 2 枚 = 20 枚 DIGITAL ÷ 每发 2 枚 = 10 发: " + plan.patternTimes());
        assertEquals(Map.of(IRON, 10L), FakePlanGrid.toPlain(plan.usedItems()),
                "网络真吃只有生产者那一份原料");
        assertFalse(plan.simulation(), () -> "没有缺口还带 simulation 就是开始键仍然灰着: "
                + plan.missingItems());
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(),
                () -> "伸手那一枚有生产者，不许报缺: " + plan.missingItems());
    }

    /**
     * 报缺臂：同一张探针图撤掉 DIGITAL 的生产者 ⇒ 伸手那一枚照样是这一格的身份（宇宙收下它，
     * 图上有没有生产者不参与这一步），需求照排；缺口走<b>既有</b>的延后判缺那条腿
     * （{@code GraphPlanPostProcessor#deferSaturatedMissingToSubmitLeg}），逐字报出
     * {@code DIGITAL} 与宽域真值 5e18 发 × 2 枚 = 1e19 枚。
     *
     * <p>这把牙只红"这一枚必须在图上有生产者才算伸手"那一型：那样 node key 退回幽灵、
     * 闸门当场 {@code billed=structure_void} 拒掉整条规划（HEAD 的形状），
     * 计划压根出不了门，更谈不上把缺口交给提交腿。
     */
    @Test
    void reachCandidateWithoutProducerStillReportsItsCellAndWideGap() {
        var consumer = FakePattern.of(TARGET, 1L, probeSlot());
        Map<AEKey, Long> stock = Map.of(DIGITAL, Long.MAX_VALUE);
        var grid = FakeGrid.builder().storage(stock).pattern(TARGET, consumer).build();
        var networkStock = new KeyCounter();
        stock.forEach(networkStock::add);
        var network = new SentinelInventory(DIGITAL);

        // 5e18 发 × 每发 2 枚 = 1e19 枚：越出 long 一截，正是"哨兵读数判不了够不够"那一档
        var session = GraphPlanningSession.create(grid.crafting, networkStock, TARGET,
                CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(0L), null,
                false, network, grid);
        var attempt = session.solve(5_000_000_000_000_000_000L);

        assertTrue(attempt.handled(),
                () -> "需求照排、缺口交给提交腿：这一单该出得了计划而不是被闸门拒死: "
                        + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);

        var wideUsed = WideAmountPlanCarrier.resolveWideLedgers(plan).get(WideAmountKind.USED, DIGITAL);
        assertEquals(new BigInteger("10000000000000000000"), wideUsed,
                () -> "哪一格＝" + DIGITAL + "、缺多少＝1e19 枚宽域真值（需求即供给全量记账，"
                        + "少一枚就是吞料，饱和成哨兵就是拿天花板当量）: "
                        + WideAmountPlanCarrier.resolveWideLedgers(plan).entries(WideAmountKind.USED));
        assertFalse(plan.simulation(),
                () -> "哨兵报量的缺口不许再判死整单（判决延后给提交腿）: " + plan.missingItems());
        assertEquals(0L, plan.missingItems().get(DIGITAL),
                () -> "规划期缺料表里不许再有它: " + plan.missingItems());
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(DIGITAL),
                () -> "图上没有任何生产者能产这一枚：延后不是 mint，不许把它认成无限源随计划凭空造料: "
                        + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
        assertTrue(network.asks.isEmpty(),
                () -> "报量已是哨兵：extract 一问都不该发生（判决不靠网络读数）: " + network.asks);
    }

    /**
     * 正向臂（a）：声明首位本来就可付的槽——node key 仍是它自己，每发件数仍是它那一份，
     * 任务表／用料表与改前逐字同一张数字表。第二候选每发 1 枚（件数不等），旧规则下
     * 「首位不可付时才按件数相等退替身」这条不许被顺手放宽成"往任意一枚换"。
     */
    @Test
    void declaredFirstCandidateKeepsItsNodeKeyAndEveryPlanningNumber() {
        var makeDigital = FakePattern.crafting(DIGITAL, 2L, Map.of(IRON, 1L));
        var consumer = FakePattern.of(TARGET, 1L, FakeInput.ordered(1L,
                List.of(new GenericStack(DIGITAL, 2L), new GenericStack(SNOW, 1L)),
                List.of(DIGITAL, SNOW)));
        var grid = FakeGrid.builder()
                .storage(Map.of(IRON, 100L))
                .pattern(TARGET, consumer)
                .pattern(DIGITAL, makeDigital)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());
        var slot = graph.patternsBySource().get(consumer).inputs().get(0);
        assertEquals(DIGITAL, slot.key(), "首位可付 ⇒ node key 逐字不动（不许退到件数相等的那一枚）");
        assertEquals(2L, slot.amountPerCraft(), "首位可付 ⇒ 每发件数逐字不动");

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10L);
        assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertEquals(10L, plan.patternTimes().get(consumer));
        assertEquals(10L, plan.patternTimes().get(makeDigital));
        assertEquals(Map.of(IRON, 10L), FakePlanGrid.toPlain(plan.usedItems()));
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(), () -> plan.missingItems().toString());
    }

    /**
     * 正向臂（b）：退替身这一条既有规则本身不动——声明首位（裸铁，无生产者）可付的是<b>同族
     * 那枚在册变体</b>（带补丁铁＝样板产出），node key 仍落在那枚变体上、每发件数仍是声明首位
     * 那一份（1）。这是台账 #143 钉过的挑选次序，不是本次放宽出来的口子。
     */
    @Test
    void patternLessFirstCandidateStillStepsOntoItsCraftableSiblingVariant() {
        AEItemKey patched = patchedVariant(IRON, "patched");
        var makePatched = FakePattern.crafting(patched, 1L, Map.of(GOLD, 1L));
        var consumer = FakePattern.of(TARGET, 1L, FakeInput.anyVariant(IRON, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 20L))
                .pattern(TARGET, consumer)
                .pattern(patched, makePatched)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());
        var slot = graph.patternsBySource().get(consumer).inputs().get(0);
        assertEquals(patched, slot.key(),
                () -> "同族在册的可付变体仍是首选退路，不许换成声明那枚没生产的裸键 " + IRON);
        assertEquals(1L, slot.amountPerCraft(), "这一档的每发件数仍是声明首位那一份");

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 4L);
        assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertEquals(4L, plan.patternTimes().get(makePatched));
        assertEquals(Map.of(GOLD, 4L), FakePlanGrid.toPlain(plan.usedItems()));
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(), () -> plan.missingItems().toString());
    }

    /** 同 item 的带组件变体（可合成宇宙的在册成员）。 */
    private static AEItemKey patchedVariant(AEItemKey key, String name) {
        var stack = new ItemStack(key.getItem());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }

    /**
     * 哨兵报量的网络总库存：{@code getAvailableStacks} 对这一枚交回 {@code Long.MAX_VALUE}，
     * 与实机 neoecoae／extendedae_plus 那种"有限盘顶到天花板"同形（台账 #201 的报缺臂用它）。
     */
    private static final class SentinelInventory implements MEStorage {
        private final AEKey key;
        private final List<String> asks = new java.util.ArrayList<>();

        private SentinelInventory(AEKey key) {
            this.key = key;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            out.add(this.key, Long.MAX_VALUE);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (!this.key.equals(what)) {
                return 0L;
            }
            if (mode == Actionable.SIMULATE) {
                this.asks.add(what + "=" + amount);
            }
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("sentinel-network");
        }
    }
}
