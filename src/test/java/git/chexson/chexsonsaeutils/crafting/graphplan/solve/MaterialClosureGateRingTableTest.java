package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 台账 #221 R1 —— 环成员在配给表上的<b>原始槽认领</b>必须出得了表。
 *
 * <p>闸门里两张尺在这一档天生不同轨，而这不是缺陷：
 * <ul>
 * <li>{@code billRawSlots} 按环成员自己列出的候选顺位记<b>原始槽量</b>（每发吃几枚 × 发数），
 * 因为执行侧真的每发伸手要这一枚——配额夹对它形同虚设的话，就是
 * {@code billRawSlots} 抬头那条实机 {@code over=[template ate=63 share=0, netherrack, diamond]}；</li>
 * <li>{@code addRingNetFlows} 给同一枚键入的是<b>净流量</b>：环里"上一发还回下一发的种"两侧相消，
 * 净值为 0 的键连条目都不留（{@code RingMacro#netFlowOf} 把 0 值摘掉），于是既不进 {@code demand}
 * 也不进 {@code supply}。</li>
 * </ul>
 *
 * <p>被摘掉的 {@code planCoveredByPlanSupply} 拿净流量那本账去核原始槽认领，读到的就是"超配"，
 * 而这一档执行侧明明取到得到 ⇒ 扣掉的是<b>每一张带环的计划</b>的整本配给表（HEAD 实测见下）。
 * 判据只留一把尺：{@code validate} 的逐键 {@code supply >= demand} 与 {@code validateFuzzyDrain}，
 * 白卷出口只剩那一条（{@code MaterialClosureGateTest#unfundedFuzzySlotShipsNoTable} 钉住）。
 *
 * <p>夹具走真管线：{@code GraphCompiler.compile} 忠实收缩环宏 ⇒ {@code LinearPlanner.solve} 交付
 * 发射向量 ⇒ {@code buildMaterialPlan} 出门。环 A{@code 煤×1 → 钻石×2} ＋ B{@code 钻石×1 → 煤×1}
 * （入口钻石、每轮净增 1 枚），消费者钟×1 吃钻石×4，网络现量煤 1 枚／钻石 1 枚。
 */
final class MaterialClosureGateRingTableTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey COAL = AEItemKey.of(Items.COAL);

    /** 钻石生产者（环成员 A）：煤×1 → 钻石×2。 */
    private static final FakePattern DIAMOND_MAKER = FakePattern.colored(
            1, AEItemKey.of(Items.STONE), List.of(new GenericStack(DIAMOND, 2L)),
            new FakeInput(COAL, 1L));
    /** 煤生产者（环成员 B）：钻石×1 → 煤×1。 */
    private static final FakePattern COAL_MAKER = FakePattern.colored(
            2, AEItemKey.of(Items.COBBLESTONE), List.of(new GenericStack(COAL, 1L)),
            new FakeInput(DIAMOND, 1L));
    /** 终点消费者：钻石×4 → 钟×1。 */
    private static final FakePattern CONSUMER =
            FakePattern.of(TOP, 1L, new FakeInput(DIAMOND, 4L));

    /**
     * 主臂：环成员的原始槽认领照样进下发的表，一张都不许因为"净流量盖不住"而被整本扣掉。
     *
     * <p>HEAD（带 cover）在这一条红在 {@code assignedSize=0}：净流量把煤消成 0、供给里只剩起步
     * 预留的 1 枚，而 A 的 raw 认领是 3 枚 ⇒ cover 判超配 ⇒ 连消费者自己那格钻石 4 枚一起不发。
     */
    @Test
    void ringMemberRawClaimShipsTableDespiteZeroNetFlow() {
        CraftGraph graph = compileRingGraph();
        var macros = graph.ringMacrosByEntry().getOrDefault(DIAMOND, List.of());
        assertFalse(macros.isEmpty(), "前提：A/B 得真按入口钻石收缩成环宏");
        var macro = macros.getFirst();
        assertEquals(0L, macro.netOutputs().get(COAL),
                () -> "前提：净流量这一把尺给煤记的是 0（环内自己还环内自己吃）, netOut="
                        + describeKeyCounter(macro.netOutputs()));

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);
        assertFalse(result.hasDeclined(), () -> "这一单本该闭合: " + result.declineReason());
        var state = result.state();
        assertNotNull(state, "判决必须带着账本回来");
        assertNull(MaterialClosureGate.validate(state, graph, 1L, null),
                "前提：守恒判据逐键 supply >= demand 判得过（煤压根不在 demand 里）");
        assertEquals(3L, state.craftsWide().getAmount(DIAMOND_MAKER).exactValue().longValueExact(),
                "前提：环成员 A 交付 3 发");
        assertEquals(0L, state.used().getAmount(COAL).exactValue().longValue(),
                "煤那一腿在提取账上是 0：网络上除了起步预留没再动过一枚");
        assertEquals(1L, state.ringExtractions().getAmount(COAL).exactValue().longValue(),
                "煤在这一趟只有起步预留那一枚来路");

        var bill = MaterialClosureGate.buildMaterialPlan(state, graph, 1L);
        assertEquals(3, bill.assigned().size(),
                () -> "带环的计划不许交白卷（HEAD 在这一格实得 0 张表）: "
                        + bill.assigned().keySet());

        var memberClaim = bill.assigned().get(DIAMOND_MAKER);
        assertNotNull(memberClaim, () -> "环成员 A 的原始槽认领必须进表，否则配额夹对它形同虚设");
        assertEquals(3L, memberClaim.getAmount(COAL).exactValue().longValue(),
                "A 每发吃煤 1 枚 × 3 发 = 3 枚：这是执行侧真伸手的那一份，不是净流量那一份");

        var siblingClaim = bill.assigned().get(COAL_MAKER);
        assertNotNull(siblingClaim, "环成员 B 的钻石认领同样进表");
        assertEquals(3L, siblingClaim.getAmount(DIAMOND).exactValue().longValue(),
                "B 每发吃钻石 1 枚 × 3 发 = 3 枚");

        var consumerClaim = bill.assigned().get(CONSUMER);
        assertNotNull(consumerClaim, "环外消费者的定键槽份额不许被连坐扣掉");
        assertEquals(4L, consumerClaim.getAmount(DIAMOND).exactValue().longValue(),
                "钟每发吃钻石 4 枚 × 1 发 = 4 枚");
    }

    /** 真管线编译：忠实收缩（不手搓环宏、不手搓份数账）。 */
    private static CraftGraph compileRingGraph() {
        KeyCounter stock = new KeyCounter();
        stock.add(COAL, 1L);
        stock.add(DIAMOND, 1L);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of((AEKey) COAL, 1L, (AEKey) DIAMOND, 1L))
                .pattern(DIAMOND, DIAMOND_MAKER)
                .pattern(COAL, COAL_MAKER)
                .pattern(TOP, CONSUMER)
                .build();
        return GraphCompiler.compile(grid.crafting, stock, TOP, null);
    }

    private static String describeKeyCounter(KeyCounter counter) {
        var builder = new StringBuilder();
        for (var stack : counter) {
            builder.append(stack.getKey()).append('=').append(stack.getLongValue()).append(' ');
        }
        return builder.toString();
    }
}
