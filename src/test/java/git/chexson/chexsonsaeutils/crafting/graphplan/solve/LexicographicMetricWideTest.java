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
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字典序路由度量的三条账必须在<b>宽域</b>里比（#219 K1 条目③）。
 *
 * <p>缺陷形状：{@code fireRoutesLexicographic} 的 {@code metric[]} 由
 * {@code totalOf(usedItems()) - baseUsed} 一类<b>差值</b>构成，而
 * {@code usedItems()}/{@code missingItems()}/{@code emittedItems()} 交回的是
 * {@code GraphPlanningState} 宽账本的<b>逐键饱和视图</b>（{@code Ae2Amounts.toKeyCounter}），
 * {@code totalOf} 再把那份读数累加后第二次饱和。于是同一条判据在两个数域里读数不同：
 * 两条候选路真值差很远，但都顶在同一块天花板上时<b>度量并列</b>，
 * 判决顺位被推到后面的分量（份数）上——而赢下那一眼的候选是<b>真被重放进计划</b>的，
 * 不是纯读数。
 *
 * <p>本用例把并列造在 {@code used} 那一档：两条路用的料都是网络存量（不报缺料、不发射），
 * 真值用量 A={@code 2.1e19} 枚、B={@code 1.8e19} 枚（B 更省），但 A 摊在 3 枚键上
 * （{@code 3 × 7e18}）、B 摊在 2 枚键上（{@code 2 × 9e18}），逐键读数都不越界，
 * 只有<b>合计</b>越界 ⇒ 两边都夹成 {@code Long.MAX_VALUE} ⇒ {@code used} 那一档假并列。
 * 往后一档是份数：A={@code 1e18} 发、B={@code 9e18} 发 ⇒ 饱和读数下 A 赢，
 * 宽域读数下 B 在 {@code used} 那一档就赢。
 */
class LexicographicMetricWideTest {

    @Test
    void fractionalFluidByteTieKeepsFirstRouteAfterExactComparison() {
        var target = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.LAVA);
        var water = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        var inputs = new java.util.ArrayList<GraphInput>();
        for (int i = 0; i < 125; i++) {
            inputs.add(new GraphInput(water, 8, 1, GraphInput.Mode.NORMAL, null, null, null, false));
        }
        var first = new GraphPattern(new FakeDetails(Items.CLOCK), target, 1,
                List.of(new GenericStack(target, 1)), inputs);
        var second = new GraphPattern(new FakeDetails(Items.COMPASS), target, 1,
                List.of(new GenericStack(target, 1)), List.of(
                        new GraphInput(water, 1000, 1, GraphInput.Mode.NORMAL, null, null, null, false)));
        var graph = new CraftGraph(target, Map.of(target, List.of(first, second)),
                Map.of(first.source(), first, second.source(), second), List.of(first.source(), second.source()),
                Map.of(), Map.of(), Map.of(), Map.of(water, 1000L), java.util.Set.of(), Map.of(), false, null, 2, 2);
        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, 1, true);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(1L, result.state().craftsWide().getAmount(first.source()).fastValue(),
                "两路均用一桶水、执行一发，精确字节同价时保持第一条路线");
        assertEquals(0L, result.state().craftsWide().getAmount(second.source()).fastValue());
        assertEquals(BigInteger.valueOf(1000), result.state().used().getExact(water));
    }

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    /** 路线 A 的三枚原料（每发各吃 7 枚）。 */
    private static final AEItemKey DIRT = AEItemKey.of(Items.DIRT);
    private static final AEItemKey GRAVEL = AEItemKey.of(Items.GRAVEL);
    private static final AEItemKey FLINT = AEItemKey.of(Items.FLINT);
    /** 路线 B 的两枚原料（每发各吃 1 枚）。 */
    private static final AEItemKey CLAY = AEItemKey.of(Items.CLAY_BALL);
    private static final AEItemKey SAND = AEItemKey.of(Items.SAND);

    /** 请求 9e18 枚铁：单发份数与逐键用量都塞得进 long，只有跨键合计越界。 */
    private static final long REQUEST = 9_000_000_000_000_000_000L;
    private static final long STOCK = 9_000_000_000_000_000_000L;
    /** A 每发产 9 枚 ⇒ 1e18 发；B 每发产 1 枚 ⇒ 9e18 发。 */
    private static final long A_FIRINGS = 1_000_000_000_000_000_000L;
    private static final BigInteger USED_A_PER_KEY = BigInteger.valueOf(7_000_000_000_000_000_000L);
    private static final BigInteger USED_B_PER_KEY = BigInteger.valueOf(9_000_000_000_000_000_000L);
    private static final BigInteger USED_A_TOTAL = new BigInteger("21000000000000000000");
    private static final BigInteger USED_B_TOTAL = new BigInteger("18000000000000000000");

    /**
     * 字典序开：宽域读数下 B 在 {@code used} 那一档就赢（真用量 {@code 1.8e19 < 2.1e19}），
     * 且<b>赢下的那一条真被重放进计划</b>——A 的三枚原料一枚都不许被动过。
     */
    @Test
    void saturatedLedgerMustNotHandTheWinToTheCostlierRoute() {
        var fixture = new Fixture();
        var graph = fixture.graph();

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST, true);

        assertFalse(result.hasDeclined(), () -> "两条路都从存量付得起，不该拒: " + result.declineReason());
        var state = result.state();
        // 合计账 = 赢下那一条的真用量：B 是 1.8e19，A 是 2.1e19。夹过的读数两条都是
        // Long.MAX，所以这一个数字就把「度量有没有在宽域里比」分辨出来。
        assertEquals(USED_B_TOTAL, state.used().total().exactValue(),
                "字典序必须选真用量更省的 B（1.8e19 枚）；读到 2.1e19 就是两条都被夹成"
                        + "天花板之后并列、判决顺延给了份数");
        assertEquals(BigInteger.ZERO, state.used().getExact(DIRT),
                "A 那一档被夹成与 B 并列后会被选中，DIRT 一旦入账就是走了夹过的读数");
        assertEquals(BigInteger.ZERO, state.used().getExact(GRAVEL), "同上：A 的原料一枚都不该动");
        assertEquals(BigInteger.ZERO, state.used().getExact(FLINT), "同上：A 的原料一枚都不该动");
        assertEquals(USED_B_PER_KEY, state.used().getExact(CLAY),
                "B 的两枚原料各吃 9e18 枚");
        assertEquals(USED_B_PER_KEY, state.used().getExact(SAND), "B 的两枚原料各吃 9e18 枚");
        assertEquals(BigInteger.valueOf(REQUEST),
                state.craftsWide().getAmount(fixture.patternB.source()).exactValue(),
                "重放的是 B：份数就是 9e18 发");
        assertEquals(BigInteger.ZERO,
                state.craftsWide().getAmount(fixture.patternA.source()).exactValue(),
                "A 那份样板一发都不许进份数账");
        assertTrue(state.missing().isEmpty(), () -> "全程从存量取料，不许报缺: " + state.missing());
    }

    /**
     * 对照臂：字典序关掉，路线按注册顺序贪心取第一条 ⇒ 赢的必须是 A。
     * 这一臂钉住「上一条用例选到 B 只能来自字典序度量本身」，而不是夹具顺手把 B 排在了前面。
     */
    @Test
    void greedyOrderStillTakesTheFirstRegisteredRoute() {
        var fixture = new Fixture();

        var result = LinearPlanner.solve(fixture.graph(), CalculationStrategy.CRAFT_LESS, REQUEST, false);

        assertFalse(result.hasDeclined(), () -> "贪心第一条付得起: " + result.declineReason());
        var state = result.state();
        assertEquals(USED_A_PER_KEY, state.used().getExact(DIRT), "贪心取注册序第一条 ⇒ A 的原料入账");
        assertEquals(BigInteger.ZERO, state.used().getExact(CLAY), "B 不参与");
        assertEquals(BigInteger.valueOf(A_FIRINGS),
                state.craftsWide().getAmount(fixture.patternA.source()).exactValue(),
                "A 每发产 9 枚 ⇒ 1e18 发");
    }

    /**
     * 夹具自身的那两本合计账，先把「逐键不越界、只有合计越界」这件事钉住：
     * 若哪天这两个数变了形，上面两条用例的构造性理由就没了。
     */
    @Test
    void bothCandidatesExceedTheCeilingOnlyInTheirTotals() {
        assertTrue(USED_A_PER_KEY.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) < 0
                        && USED_B_PER_KEY.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) < 0,
                "逐键用量必须塞得进 long：否则夹的是单键而不是合计");
        assertTrue(USED_A_TOTAL.compareTo(USED_B_TOTAL) != 0
                        && USED_A_TOTAL.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                        && USED_B_TOTAL.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "两条合计都必须越出 long 且互不相等：这才是" + "被夹并列" + "的形状");
    }

    /** 两条路 + 五枚原料存量的那张图。 */
    private static final class Fixture {
        private final GraphPattern patternA;
        private final GraphPattern patternB;

        Fixture() {
            patternA = new GraphPattern(new FakeDetails(Items.CLOCK), IRON, 9L,
                    List.of(new GenericStack(IRON, 9L)),
                    List.of(leaf(DIRT, 7L), leaf(GRAVEL, 7L), leaf(FLINT, 7L)));
            patternB = new GraphPattern(new FakeDetails(Items.COMPASS), IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(leaf(CLAY, 1L), leaf(SAND, 1L)));
        }

        CraftGraph graph() {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(patternA, patternB)),
                    Map.of(patternA.source(), patternA, patternB.source(), patternB),
                    List.of(patternA.source(), patternB.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.<AEKey, Long>of(DIRT, STOCK, GRAVEL, STOCK, FLINT, STOCK,
                            CLAY, STOCK, SAND, STOCK),
                    java.util.Set.of(), Map.of(), false, null, 2, 2);
        }

        private static GraphInput leaf(AEKey key, long perCraft) {
            return new GraphInput(key, perCraft, 1L, GraphInput.Mode.NORMAL, null, null, null, false);
        }
    }

    /** 轻量样板替身：definition 只需要逐样板唯一。 */
    private static final class FakeDetails implements IPatternDetails {
        private final AEItemKey definition;

        private FakeDetails(Item definitionItem) {
            this.definition = AEItemKey.of(new net.minecraft.world.item.ItemStack(definitionItem));
        }

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
            return this.definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
