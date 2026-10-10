package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 份数宽域地基自测（规划侧闭环）：同一张样板跨站点累加出超过 {@code long} 的发数时，
 * 账本必须记下真值、守恒闸门必须按真值判供需、回滚必须把账撤销干净，而出口交回 AE2 的
 * {@code patternTimes} 只能是饱和代表值、真值随计划对象一起出门。
 *
 * <p>可达性口径照实机：请求 {@code ae2:creative_storage_cell x 1000000} 时单键流量已到
 * 1.6e19（缺料账本早已宽域）；每发产 1 枚、请求 1e19 枚 ⇒ 份数本身就是 1e19，
 * 超过 {@code Long.MAX_VALUE}(9.223372036854775807e18)。
 * 与 {@link MaterialClosureGateTest} 同一立场：直接造 state 与 graph，不经过 solve
 * （solve 侧口径由等价对拍锁）。
 */
class WideCraftCountTest {

    private static final BigInteger CRAFTS_1E19 = new BigInteger("10000000000000000000");
    private static final BigInteger CRAFTS_1_2E19 = new BigInteger("12000000000000000000");
    private static final BigInteger CRAFTS_2E19 = new BigInteger("20000000000000000000");
    private static final long FIRINGS_5E18 = 5_000_000_000_000_000_000L;
    private static final long FIRINGS_6E18 = 6_000_000_000_000_000_000L;
    /** 每发 2 枚 × 4e18 发 = 8e18 一枚笔：需求单项留在 long 域内，两笔合计 1.6e19。 */
    private static final long FIRINGS_4E18 = 4_000_000_000_000_000_000L;

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    /** {@code OUT×1 ← IN×perCraft} 的单样板。 */
    private static GraphPattern oneToOne(AEItemKey out, AEItemKey in, long perCraft) {
        return new GraphPattern(new FakeDetails(), out, 1,
                List.of(new GenericStack(out, 1L)),
                List.of(new GraphInput(in, perCraft, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    private static CraftGraph graphOf(List<GraphPattern> patterns, Map<AEKey, Long> stock) {
        var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
        var order = new java.util.ArrayList<IPatternDetails>();
        for (var pattern : patterns) {
            bySource.put(pattern.source(), pattern);
            order.add(pattern.source());
        }
        return new CraftGraph(IRON,
                Map.of(IRON, patterns),
                Map.copyOf(bySource),
                order,
                Map.of(), Map.of(), Map.of(),
                stock,
                java.util.Set.of(),
                Map.of(), false, null, 1, 1);
    }

    /**
     * 不假拒：产出侧真值 2e19 枚（每发 1 枚，发数本身超界），需求侧两笔精确乘积合计 1.6e19。
     * 份数按饱和视图读会塌回一个 {@code Long.MAX_VALUE}，盖不住 1.6e19，这张配得平的表
     * 就被判成"材料不闭合"整条拒掉——实机形态与 lava 假拒同一条（连计划页都进不去）。
     */
    @Test
    void gateDoesNotRejectPlanClosedOnlyByBeyondLongCraftCount() {
        var producer = oneToOne(IRON, GOLD, 1L);
        var firstConsumer = oneToOne(SNOW, IRON, 2L);
        var secondConsumer = oneToOne(SNOW, IRON, 2L);
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(producer.source(), PlanAmount.of(CRAFTS_1E19));
        state.addCrafting(producer.source(), PlanAmount.of(CRAFTS_1E19));
        assertEquals(CRAFTS_2E19, state.craftsWide().getAmount(producer.source()).exactValue(),
                "同一张样板跨站点累加的发数必须如实记为宽域值");
        state.addCrafting(firstConsumer.source(), FIRINGS_4E18);
        state.addCrafting(secondConsumer.source(), FIRINGS_4E18);
        // 生产者的原料由缺料账补平（本用例只锁供需判据，不重算真实提取）
        state.addMissing(GOLD, Long.MAX_VALUE);
        state.addMissing(GOLD, Long.MAX_VALUE);
        state.addMissing(GOLD, Long.MAX_VALUE);

        String violation = MaterialClosureGate.validate(state,
                graphOf(List.of(producer, firstConsumer, secondConsumer), Map.of(GOLD, 0L)), 0L);
        assertNull(violation, () -> "2e19 枚产出盖得住 1.6e19 枚需求，闸门不该拒：" + violation);
    }

    /**
     * 不假通过：产出侧真值 1e19 枚、需求侧真值 1.2e19 枚——真算料错误，必须照拦，
     * 且拦下时报的数字必须是宽域真值。饱和视图把两侧都读成 {@code Long.MAX_VALUE}，
     * 供需"看起来"正好配平，缺料计划就此被判可行。
     */
    @Test
    void gateDoesNotPassPlanThatOnlyClosesAfterCraftCountSaturation() {
        var producer = oneToOne(IRON, GOLD, 1L);
        var consumer = oneToOne(SNOW, IRON, 1L);
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(producer.source(), FIRINGS_5E18);
        state.addCrafting(producer.source(), FIRINGS_5E18);
        assertEquals(CRAFTS_1E19, state.craftsWide().getAmount(producer.source()).exactValue());
        state.addCrafting(consumer.source(), FIRINGS_6E18);
        state.addCrafting(consumer.source(), FIRINGS_6E18);
        assertEquals(CRAFTS_1_2E19, state.craftsWide().getAmount(consumer.source()).exactValue());
        state.addMissing(GOLD, Long.MAX_VALUE);
        state.addMissing(GOLD, Long.MAX_VALUE);

        String violation = MaterialClosureGate.validate(state,
                graphOf(List.of(producer, consumer), Map.of(GOLD, 0L)), 0L);
        assertNotNull(violation, "两侧都饱和成天花板时闸门就失去判据");
        assertTrue(violation.contains("supply=" + CRAFTS_1E19),
                () -> "供给必须按宽域真值报出 1e19，实测：" + violation);
        assertTrue(violation.contains("demand=" + CRAFTS_1_2E19),
                () -> "需求必须按宽域真值报出 1.2e19，实测：" + violation);
    }

    /**
     * 计划出口：AE2 的 {@code patternTimes} 字段是 {@code Map<IPatternDetails, Long>}（改不动），
     * 交出去只能是向上饱和的代表值；真值随计划对象经 {@link WideAmountPlanCarrier} 出门。
     */
    @Test
    void exitShipsSaturatedPatternTimesAndWideCraftsOnThePlan() {
        var producer = oneToOne(IRON, GOLD, 1L);
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(producer.source(), FIRINGS_5E18);
        state.addCrafting(producer.source(), FIRINGS_5E18);

        var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1L, false),
                graphOf(List.of(producer), Map.of(GOLD, 0L)));

        assertEquals(Long.MAX_VALUE, (long) plan.patternTimes().get(producer.source()),
                "交给 AE2 的份数是向上饱和的代表值");
        var wideCrafts = WideAmountPlanCarrier.resolveWideCrafts(plan);
        assertEquals(CRAFTS_1E19, wideCrafts.get(producer.source()), "旁路账必须是规划侧真值");
    }

    /** 没超界的份数不进旁路账：AE2 那份 long 就是真值，出口也不必套壳。 */
    @Test
    void narrowPlanCarriesNoWideCraftDictionary() {
        var producer = oneToOne(IRON, GOLD, 1L);
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(producer.source(), 7L);

        var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1L, false),
                graphOf(List.of(producer), Map.of(GOLD, 0L)));

        assertEquals(7L, (long) plan.patternTimes().get(producer.source()));
        assertTrue(WideAmountPlanCarrier.resolveWideCrafts(plan).isEmpty(), "窄计划不该带份数旁路账");
    }

    /**
     * 回滚一致性：搜索中途撤销一次 {@code addCrafting} 后，份数账必须回到原值——
     * 含"宽域值降回 long 域"这一档。饱和哨兵若留在主表上，交回 AE2 的就是一辈子的天花板。
     */
    @Test
    void undoRollsBackWideCraftCountExactly() {
        var pattern = new FakeDetails();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());

        int outer = state.journal();
        state.addCrafting(pattern, 7L);
        int inner = state.journal();
        state.addCrafting(pattern, PlanAmount.of(CRAFTS_1E19));
        assertEquals(CRAFTS_1E19.add(BigInteger.valueOf(7)),
                state.craftsWide().getAmount(pattern).exactValue(), "宽域增量如实入账");
        assertEquals(Long.MAX_VALUE, (long) state.crafts().get(pattern), "宽域样板在窄视图上读饱和哨兵");

        state.undoTo(inner);
        assertEquals(7L, (long) state.crafts().get(pattern), "撤掉宽域增量后必须降回 long 域精确值");
        assertTrue(state.craftsWide().getAmount(pattern).isFast(), "降回窄域后不得留侧表残值");
        assertFalse(state.craftsWide().hasOverflow(), "降回窄域后不得留溢出键");

        state.addCrafting(pattern, Long.MAX_VALUE);
        state.addCrafting(pattern, Long.MAX_VALUE);
        assertFalse(state.craftsWide().getAmount(pattern).isFast(), "两次天花板相加即越界");
        state.undoTo(outer);
        assertTrue(state.crafts().isEmpty(), "crafts 回滚到空（absent 不得还原成 0 标记）");
    }

    /** 环宏的发射向量：还种加成允许超 {@code long}，不再饱和成代表值。 */
    @Test
    void ringMacroFiringVectorStaysExactBeyondLong() {
        var netOutputs = new KeyCounter();
        netOutputs.set(IRON, 3L);
        var macro = new RingMacro(RingId.of(IRON, List.of()), new KeyCounter(), netOutputs,
                new KeyCounter(), Map.of(), Map.of(), Map.of(), false);

        assertEquals(16L, macro.plannedWithSeedReturn(10L, 2L).fastValue(), "窄域结果的旧口径不变");
        var planned = macro.plannedWithSeedReturn(FIRINGS_5E18, FIRINGS_5E18);
        assertEquals(CRAFTS_2E19, planned.exactValue(),
                "5e18 + 5e18×3 = 2e19，旧实现在这里饱和成 Long.MAX_VALUE");
        assertFalse(planned.isFast());
    }

    /** 份数账本身：越界不抛异常、不静默饱和，交回 AE2 的窄视图向上饱和。 */
    @Test
    void craftLedgerCountsBeyondLongWithoutThrowing() {        var pattern = new FakeDetails();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(pattern, Long.MAX_VALUE);
        state.addCrafting(pattern, 1L);

        PlanAmount counted = state.craftsWide().getAmount(pattern);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), counted.exactValue(),
                "Long.MAX_VALUE + 1 = 9223372036854775808，必须记真值而不是回绕成负数");
        assertFalse(counted.isFast());
        assertEquals(Long.MAX_VALUE, (long) state.crafts().get(pattern), "交回 AE2 的代表值向上饱和");
    }

    /**
     * 窄视图与 {@code keys()} 交出<b>只读</b>视图（台账 #141 第 3 条 + #143 的 MF-4 重测）：外部绕过
     * {@code add} 往视图上写是静默丢键那一型——主表被改而 {@code slots}/{@code registered} 不知道，
     * 注册序视图就此少一张样板，而 {@code size()} 与 {@code longView()} 的读数还对得上。
     * 生产侧调用点全是只读遍历（AE2 的 {@code patternTimes()} 也只被遍历），所以这一层直接收掉写口、
     * 不靠约定。
     *
     * <p>MF-4 复核说 {@code Collections.unmodifiableMap} "挡不住 {@code entrySet()} 的
     * {@code Entry.setValue} 与 {@code replaceAll}"，在当前树上重测<b>不成立</b>：本仓跑在
     * Java 21 上，JDK 的 {@code Collections$UnmodifiableMap} 把 {@code entrySet()} 再包一层
     * {@code UnmodifiableEntry}（{@code setValue} 抛 UOE），并自己覆写了 {@code replaceAll}。
     * 这两条臂因此钉在这里当回归牙——将来谁把 {@code longView()} 换成直交主表
     * （或换成不被包的副本视图），这两条先红。
     */
    @Test
    void narrowViewAndKeySetAreReadOnly() {
        var pattern = new FakeDetails();
        var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
        state.addCrafting(pattern, 3L);

        assertThrows(UnsupportedOperationException.class, () -> state.crafts().put(pattern, 9L),
                "交回 AE2 的那份字典不许当写口绕过登记");
        assertThrows(UnsupportedOperationException.class, () -> state.craftsWide().keys().remove(pattern),
                "keys() 的 remove 会抽掉主表键位而留下注册槽——视图与主表失同步");
        assertThrows(UnsupportedOperationException.class,
                () -> state.craftsWide().longView().entrySet().iterator().next().setValue(0L),
                "Entry.setValue 若能落进主表，一张样板就被外部写成 0 而不动 overflow 与槽位");
        assertThrows(UnsupportedOperationException.class,
                () -> state.craftsWide().longView().replaceAll((unusedPattern, unusedValue) -> 0L),
                "replaceAll 走的同一条 Entry 写口");

        assertEquals(3L, (long) state.crafts().get(pattern), "四次拒绝写入后账本本体一字未动");
        assertEquals(1, state.craftsWide().size(), "主表从不存 0 值这条不变式不许被外部写口打破");
        assertEquals(List.of(pattern), List.copyOf(state.craftsWide().orderedLongView().keySet()),
                "注册槽位不许被外部写口抽掉");
    }

    /**
     * 实机 2026-09-24 13:50 取证行的第二条判决证据：账本里躺着一份<b>负</b>发数
     * （{@code useless_mod:omniversal_pattern ... times=-13216328854969394}）。
     *
     * <p>根因不在 {@code PlanJournal} 的回滚方向，也不在 {@code WideCraftCounter} 的合并，
     * 而在<b>算这一批发射几发</b>的那一眼取整上：{@code ceil(a/b)} 写成 {@code (a + b − 1) / b}。
     * {@code > long} 的订单由 {@code request(site, PlanAmount)} 按 {@code Long.MAX_VALUE} 分轮
     * 喂下来，于是 {@code a} 就是天花板本身：只要这张样板一发产 2 枚（{@code b >= 2}），
     * {@code a + b − 1} 就先回绕成负数，整张样板的发数带着负号进了 {@code crafts}。
     *
     * <p>它出不了门只因为守恒闸门在别处报了缺料——负发数<b>不在</b>
     * {@code hasNegativeExitValues()} 的检查域里（那只查 working/used/emitted/missing/ringExtractions
     * 那几本 AEKey 键账），于是带着负数一路走到出口。这里锁住真实求解路径：
     * 顶到天花板的那一轮必须算出精确的 {@code ceil(Long.MAX_VALUE / 2)}，不许是负数。
     */
    @Test
    void ceilBatchAtTheLongCeilingBooksExactFiringsNeverNegativeOnes() {
        var twoPerCraft = new GraphPattern(new FakeDetails(), IRON, 2,
                List.of(new GenericStack(IRON, 2L)),
                List.of(new GraphInput(GOLD, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        var graph = graphOf(List.of(twoPerCraft), Map.of(GOLD, Long.MAX_VALUE));

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, Long.MAX_VALUE);

        var state = result.state();
        assertNotNull(state, () -> "判决要带着账本回来才点得出名：" + result.declineReason());
        for (IPatternDetails source : state.craftsWide().keys()) {
            PlanAmount times = state.craftsWide().getAmount(source);
            assertTrue(times.signum() >= 0, () -> "发数为负就是从这条真实路径写进去的: "
                    + source.getDefinition() + " times=" + times);
        }
        assertFalse(result.hasDeclined(),
                () -> "网络里有一整个 long 的金，这一单配得平，不该拒：" + result.declineReason());
        assertEquals(4_611_686_018_427_387_904L, state.craftsWide().getAmount(twoPerCraft.source()).fastValue(),
                "ceil(Long.MAX_VALUE / 2) = 4611686018427387904，一发两枚刚盖住整单需求");
    }

    /** 轻量样板替身：definition 只需要唯一身份。 */
    private static final class FakeDetails implements IPatternDetails {
        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public AEItemKey getDefinition() {
            return key(Items.PAPER);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
