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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 台账 #78 —— 求解器（现只剩 {@code LinearPlanner} 一个）的 >long 需求漏斗逐腿判据。
 *
 * <p>形状与 {@code WidePerSiteDemandTest} 一致：真求解器入口 + 真账本，期望值来自独立手算表
 * （每发几枚 × 几发 = 该入账几枚），不许由被测函数复算。每条判据只钉一条腿：把那条腿单独
 * 还原成饱和写法（{@code WideMath.wideMul} 或 {@code Ae2Amounts.toLong}）即红。
 *
 * <p>本条原是「非默认求解器」的对拍：另两档（独立残量搜索与聚合核）都已摘，剩下这两条判据
 * 打在唯一现役求解器上，读数是同一份——摘档时逐字对过。
 *
 * <p><b>本类覆盖 2 条直接可观察的 >long 腿</b>：
 * <ol>
 * <li>{@link #linearPlannerEmittableBooksWideNotCeiling()} —— LinearPlanner {@code :535}
 * emittable 腿走 {@code emitItemsWide}：读 {@code emitted()}。</li>
 * <li>{@link #wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining()} ——
 * LinearPlanner {@code :1063} 的宽域需求口 {@code requestSlotUnits → request(Site, PlanAmount)}：
 * 预 WIP 的 {@code Math.multiplyExact(multiplier, times)} 越界抛 {@code ArithmeticException}
 * ⇒ 顶层 catch ⇒ 整趟 DECLINE。读 {@code emitted()} 反证分轮喂对了。</li>
 * </ol>
 *
 * <p><b>其余 WIP 触及的腿</b>（{@code :737/:740/:1011/:1043/:1061} 与环宏净流量那几段）
 * 的观察口径都落在 {@code working}（无对外读口）与 {@code used}（网络提取峰值，只算
 * {@code unmodified − working}）。这些腿把 wide 值截成天花板以后，只有"下游消费者需要 >long 件"
 * 才能通过 missing 反读出来——而 {@code craftedPerPattern × times ≤ MAX_VALUE} 的结构约束
 * （{@code times = ceil(totalRequested / outputPerCraft)}）保证主输出腿上的
 * {@code mul(out.amount, times)} 在 {@code totalRequested ≤ MAX_VALUE} 下不越界。
 *
 * <p>能越界的只有非主输出（副产物）——而副产物在图里没有消费者（有消费者的
 * {@code byproduct/remainderSupply} 在 {@code LinearPlanner} 这一侧靠 {@code selfBlocked}
 * 走回环、走不到 wide 分支；原聚合核与独立残量搜索那两档另有一套自己的受理域判决，
 * 已随引擎一起摘掉）。
 * 因此本轮判据范围不覆盖它们，登记在报告里；{@code :1011} 与环宏净流量的 wide 通道走的是
 * 同一段 {@code request(Site, PlanAmount)} 与 {@code insert(AEKey, PlanAmount)}，由
 * {@link #linearPlannerEmittableBooksWideNotCeiling()} 与
 * {@link #wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining()} 一起把
 * "同一把尺子" 的语义钉住；{@code :1061} 自喂修正量与环宏净流量的 wide 通道
 * 由同一段 {@code WideMath.mul} 与被 #2 用例覆盖的 {@code request(Site, PlanAmount)} 分轮
 * 语义间接锁。
 */
class WideLegsSolverAlignmentTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey LEAF = AEItemKey.of(Items.GOLD_INGOT);

    private static final long AMOUNT_PER_CRAFT = 10L;
    private static final long REQUEST_1E18 = 1_000_000_000_000_000_000L;
    private static final BigInteger NEED_1E19 = new BigInteger("10000000000000000000");
    private static final BigInteger NEED_MAX_MUL_10 = new BigInteger("92233720368547758070");

    /**
     * 判据 #1（LinearPlanner {@code :505}）：emittable 腿必须走宽域入账。
     *
     * <p>独立手算：1e18 发 × 每发 10 枚 = 1e19 枚。饱和写法（{@code WideMath.wideMul} 交回
     * {@code Long.MAX_VALUE}）在这一格把总发射记成 9.22e18，emitted 少一截即闸门供给侧
     * 与需求侧读到两个数（假缺口，见本仓"守恒闸门两侧必须同一把尺"）。
     */
    @Test
    void linearPlannerEmittableBooksWideNotCeiling() {
        // amountPerCraft=10 让 site.amount × requestedAmount 本身越界；
        // 与 WidePerSiteDemandTest 的 amount=1 形状区分开：那条腿的 wide 分轮每轮 wideMul(1, chunk)
        // 逐字退回 long 版，压根不触发饱和——本用例的 amount=10 才让 wideMul(10, 1e18) 真饱和。
        var producer = new GraphPattern(new FakeDetails(), TOP, 1L,
                List.of(new GenericStack(TOP, 1L)),
                List.of(new GraphInput(LEAF, AMOUNT_PER_CRAFT, 1L, GraphInput.Mode.NORMAL,
                        null, null, null, false)));
        var graph = new CraftGraph(TOP,
                Map.of(TOP, List.of(producer)),
                Map.of(producer.source(), producer),
                List.of(producer.source()),
                Map.of(), Map.of(), Map.of(),
                Map.<AEKey, Long>of(),
                java.util.Set.of(LEAF), Map.of(), false, null, 1, 1);

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS,
                REQUEST_1E18);

        assertFalse(result.hasDeclined(), () -> "发射口是无界的，1e19 枚的需求该被真值盖住: "
                + result.declineReason());
        assertEquals(NEED_1E19, result.state().emitted().getExact(LEAF),
                "emitted 账 = 每发 10 枚 × 1e18 发 = 1e19 枚；记成 9.22e18 就是那条饱和腿的指纹");
    }

    /**
     * 判据 #2（{@code LinearPlanner#requestSlotUnits} → {@code request(Site, PlanAmount)}）：
     * 宽域需求口分轮喂，避免 {@code multiplyExact} 抛 ⇒ 整趟 DECLINE。
     *
     * <p>形状：TOP 每发 1 枚、吃 10 枚 LEAF；LEAF 是 emittable ⇒ emit 走发射账。
     * 请求 Long.MAX_VALUE 单位 TOP ⇒ 单格 LEAF 用料 = 10 × Long.MAX_VALUE ≈ 9.22e19 枚。
     * 预 WIP：{@code fire()} 里 {@code Math.multiplyExact(10, MAX_VALUE)} 抛 ⇒ 顶层
     * catch {@code ArithmeticException} ⇒ DECLINE。
     * 后 WIP：走 {@code request(Site, PlanAmount)} 分轮，emit 记 9.22e19 枚。
     *
     * <p>手算：每轮 LEAF 站点 {@code templateAmount=1}（emit 每单位一枚）；每轮喂
     * MAX_VALUE 单位 LEAF × 1 枚/单位 = MAX_VALUE 枚。总用料 = 10 × MAX_VALUE ⇒ 分 11 轮，
     * 前 10 轮各喂 MAX_VALUE，第 11 轮喂 9.22e19 − 10 × MAX_VALUE = 0（正好凑齐）。
     * 期望 emitted = 10 × Long.MAX_VALUE = 92233720368547758070 枚。
     */
    @Test
    void wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining() {
        var producer = new GraphPattern(new FakeDetails(), TOP, 1L,
                List.of(new GenericStack(TOP, 1L)),
                List.of(new GraphInput(LEAF, 1L, AMOUNT_PER_CRAFT, GraphInput.Mode.NORMAL,
                        null, null, null, false)));
        var graph = new CraftGraph(TOP,
                Map.of(TOP, List.of(producer)),
                Map.of(producer.source(), producer),
                List.of(producer.source()),
                Map.of(), Map.of(), Map.of(),
                Map.<AEKey, Long>of(),
                java.util.Set.of(LEAF), Map.of(), false, null, 1, 1);
        long request = Long.MAX_VALUE;
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, request);
        assertFalse(result.hasDeclined(), () -> "10 × Long.MAX_VALUE 的用料必须走宽域分轮，"
                + "饱和写法在这一格会抛 multiplyExact ⇒ DECLINE: " + result.declineReason());
        assertEquals(NEED_MAX_MUL_10, result.state().emitted().getExact(LEAF),
                "emitted 必须记 10 × Long.MAX_VALUE = 92233720368547758070 枚，不是天花板");
    }

    /** 轻量样板替身：等价性由实例身份承担（GraphPattern.equals 就是身份）。 */
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
            return AEItemKey.of(new ItemStack(Items.CLOCK));
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
