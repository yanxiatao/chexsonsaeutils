package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingContraction;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingStartupPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #218 批次二b-1（合并清单第 6、7 条）：环族"要留多少种、每轮净产出多少"的三处读数与算式
 * 从 {@code long} 抬进 {@code PlanAmount} 全程精确域。四臂：
 *
 * <ul>
 * <li><b>起步预留臂（主臂）</b>：忠实编译图，种键 K 的网络聚合读数被两片 5e18 回绕成负数
 * （{@code -8446744073709551616}，#197 同型），真值 1e19 由 {@code PlanningSupplyTruth} 交回。
 * 环宏起步要留 K×1000000000（十位数）。HEAD 红在：{@code reservableStock} 拿回绕读数做减法
 * ⇒ 预留 0 ⇒ 种不进预留账、留种扣回与还种判据全看不到这一腿，而缺口被沙盒宽域库存就地吸收
 * ⇒ 不报缺。改后断言起步预留份数＝宽域真值 1000000000。</li>
 * <li><b>净流量臂</b>：{@code netOutputs−netInputs} 的裸 {@code long} 减法顶格回绕成负
 * ⇒ {@code netPerCycle <= 0} ⇒ 还种那一趟（LinearPlanner 发射向量加成）整趟落空。
 * 改后减法与比较不许先把操作数窄化，还种轮数断言为正的真值。</li>
 * <li><b>不崩臂</b>：{@code RingStartupPlan.minimumSeeds} 的起步缺口 1.8e19 越出 long，
 * HEAD 在 {@code longValueExact()} 抛 {@link ArithmeticException}（红字带栈类名），
 * {@code RingContraction}/{@code planOnce} 都不捕。改后不许吞异常、不许夹 MAX、不许截断回 long：
 * 起步量交回宽域真值，越界那一档走既有 DECLINE/报缺出口并点名是哪一枚键。</li>
 * <li><b>正向控制</b>：聚合读数可信（单片 2e10）的同型环——起步预留读数与判决逐字不变。</li>
 * </ul>
 */
final class RingStartupWide218Test {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey E = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey K = AEItemKey.of(Items.COAL);

    /** 两片 5e18 经 AE2 普通 long 加法回绕；逐存储真值 1e19。 */
    private static final long FRAG = 5_000_000_000_000_000_000L;
    private static final BigInteger TRUTH = new BigInteger("10000000000000000000");

    /** 起步种量：环每发吃 K×1000000000、还 K×1000000001，minimumSeeds 交回的缺口就是十位数。 */
    private static final long SEED_K = 1_000_000_000L;

    // ---- 臂一：起步预留（主臂，HEAD 必红） ----

    @Test
    void beyondLongSeedSupplyStillReservesWideTruthStartupReservation() {
        var stock = new KeyCounter();
        stock.add(E, 1L);
        stock.add(K, FRAG);
        stock.add(K, FRAG);
        assertEquals(-8446744073709551616L, stock.get(K), "前提：聚合读数回绕成负数（#197 同型）");
        FakeGrid grid = saturatedSeedGrid(stock);
        CraftGraph graph = GraphCompiler.compile(grid.crafting, stock, TOP, null);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(K, PlanAmount.of(TRUTH))));

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);

        assertFalse(result.hasDeclined(), () -> "判决不该被回绕读数打死: " + result.declineReason());
        var state = result.state();
        assertNotNull(state, () -> "判决必须带着账本回来: " + result.declineReason());
        assertTrue(state.missingItems().isEmpty(), () -> "这一趟不许有缺口: "
                + FakePlanGrid.toPlain(state.missingItems()));
        assertTrue(state.used().getExact(K).signum() > 0,
                () -> "前提：环宏真的起步过，K 真的被消费了, used=" + state.used().getExact(K));
        // 主判据：起步预留份数＝宽域真值十位数——HEAD 读回绕库存，这一格是 0（既不留种也不报缺）。
        assertEquals(PlanAmount.of(SEED_K), state.ringExtractions().getAmount(K),
                "起步预留必须按宽域供给真值留够 K×1000000000，不许被回绕读数顶成 0");
    }

    // ---- 臂一正向控制：聚合读数可信 ⇒ 预留读数逐字不变 ----

    @Test
    void trustworthySeedStockKeepsStartupReservationVerbatim() {
        var stock = new KeyCounter();
        stock.add(E, 1L);
        stock.add(K, 20_000_000_000L);
        FakeGrid grid = saturatedSeedGrid(stock);
        CraftGraph graph = GraphCompiler.compile(grid.crafting, stock, TOP, null);

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);

        assertFalse(result.hasDeclined(), () -> "判决不该拒: " + result.declineReason());
        var state = result.state();
        assertNotNull(state, () -> "判决必须带着账本回来: " + result.declineReason());
        // 数字取自 HEAD 实跑（正向验收：改后必须逐字相同）
        assertEquals(PlanAmount.of(SEED_K), state.ringExtractions().getAmount(K),
                "可信库存的起步预留读数逐字不变");
        assertEquals(PlanAmount.of(1L), state.ringExtractions().getAmount(E),
                "入口键起步预留读数逐字不变");
    }

    // ---- 臂二：每轮净产出裸减法回绕 ⇒ 还种发数不许落空 ----

    @Test
    void netPerCycleWrapAroundNeverZeroesSeedRepayment() {
        var netInputs = new KeyCounter();
        netInputs.add(E, Long.MIN_VALUE + 2L);
        var netOutputs = new KeyCounter();
        netOutputs.add(E, 3L);
        var catalysts = new KeyCounter();
        catalysts.add(E, 1L);
        var macro = new RingMacro(RingId.of(E, List.of()), netInputs, netOutputs, catalysts,
                Map.of(), Map.of(), false);
        var graph = new CraftGraph(TOP, Map.of(), Map.of(), List.of(),
                Map.of(E, List.of(macro)), Map.of(), Map.of(),
                Map.<AEKey, Long>of(), java.util.Set.of(), Map.of(), false, null, 1, 1);
        assertEquals(1L, graph.retainedSeeds().getOrDefault(E, 0L), "前提：留种账里真有这一枚");

        // 3 − (Long.MIN_VALUE + 2) 的 long 减法回绕成负数（真值 9223372036854775809 > 0）。
        // HEAD：netPerCycle <= 0 ⇒ 还种轮数恒 0 ⇒ 还种那一趟整趟落空。
        var repayment = macro.seedRepaymentCycles(graph,
                PlanAmount.of(4_000_000_000_000_000_000L));

        assertEquals(PlanAmount.of(1L), repayment,
                "每轮净产出为正的真值时还种轮数不许回绕成 0");
    }

    // ---- 臂三：起步缺口越界不崩（HEAD 红在 ArithmeticException 栈类名） ----

    @Test
    void minimumSeedsBeyondLongReturnsWideTruth() {
        var pattern = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(K, 1L)),
                FakeInput.multi(9_000_000_000_000_000_000L, K),
                FakeInput.multi(9_000_000_000_000_000_000L, K));

        var seeds = RingStartupPlan.minimumSeeds(Map.of(pattern, 1L));

        assertEquals(PlanAmount.of(new BigInteger("18000000000000000000")), seeds.get(K),
                "起步缺口 1.8e19 交回宽域真值，不许 longValueExact 崩、不许夹 MAX、不许吞成 0");
    }

    @Test
    void beyondLongStartupSeedDeclinesWithNamedShortage() {
        // 忠实编译图：M1 起步要吃 K×1.8e19（两槽各 9e18 倍率），K 由环成员 M2 自供，
        // M2 每发还要吃环外料 X——网络只给 1 枚 X ⇒ 起步种的交付链付不齐。
        // HEAD 在这一档压根走不到求解（编译期 longValueExact 崩，红字带栈类名）；
        // 改后必须走既有判决链：不崩、账本带回、缺口点名、带缺口的计划以 simulation 出门。
        var x = AEItemKey.of(Items.GOLD_INGOT);
        var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(E, 9_000_000_000_000_000_001L)),
                FakeInput.multi(9_000_000_000_000_000_000L, K),
                FakeInput.multi(9_000_000_000_000_000_000L, K));
        var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(K, 9_000_000_000_000_000_000L)),
                new FakeInput(E, 1L), new FakeInput(x, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(E, 1L));
        var stock = new KeyCounter();
        stock.add(E, 1L);
        stock.add(x, 1L);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(E, 1L, x, 1L))
                .pattern(E, first)
                .pattern(K, second)
                .pattern(TOP, consumer)
                .build();

        CraftGraph graph = GraphCompiler.compile(grid.crafting, stock, TOP, null);
        // 前提：环宏真在图上，且起步种 K 那一腿是越出 long 的宽域真值。
        var macros = graph.ringMacrosByEntry().getOrDefault(E, List.of());
        assertFalse(macros.isEmpty(), "前提：M1/M2 环必须按入口 E 收缩成环宏");
        assertEquals(PlanAmount.of(new BigInteger("18000000000000000000")),
                macros.getFirst().startupSeeds().get(K),
                "前提：起步种 K 交回宽域真值 1.8e19，不许崩、不许夹 MAX");

        // 交付 2 枚 E 而可用库存只有 1 枚：环宏必须点火，1.8e19 的起步种才有这一腿。
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2L);

        var state = result.state();
        assertNotNull(state, () -> "越界那一档必须带着账本回来，不许崩栈: " + result.declineReason());
        assertTrue(state.missing().getExact(K).signum() > 0,
                () -> "付不出的那截要由既有缺料账点名起步种键 K: missing="
                        + FakePlanGrid.toPlain(state.missingItems()));
        assertTrue(result.simulation(),
                () -> "带缺口的计划不许以正式计划出门: declined=" + result.declineReason());
    }

    // ---- fixtures ----

    /**
     * 自增殖环（忠实编译形态）：每发 out E×2 + K×1000000001，in E×1 + K×1000000000。
     * 入口 E、催化物 {E, K}、minimumSeeds {E:1, K:1000000000}；消费者 TOP 每发吃 E×4。
     */
    private static FakeGrid saturatedSeedGrid(KeyCounter stock) {
        var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(E, 2L),
                        new GenericStack(K, SEED_K + 1L)),
                new FakeInput(E, 1L), FakeInput.stack(K, SEED_K));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(E, 4L));
        return FakeGrid.builder()
                .storage(Map.of(E, stock.get(E), K, stock.get(K)))
                .pattern(E, pring)
                .pattern(K, pring)
                .pattern(TOP, consumer)
                .build();
    }

    /** 收缩口直读：环宏真的建出来了（前提断言，红字要能区分"没收缩"与"收缩了但预留 0"）。 */
    @Test
    void ringMacroCompilesWithTenDigitStartupSeed() {
        var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(E, 2L),
                        new GenericStack(K, SEED_K + 1L)),
                new FakeInput(E, 1L), FakeInput.stack(K, SEED_K));
        var macros = RingContraction.contractPatterns(List.of(pring));
        assertFalse(macros.isEmpty(), "前提：单样板自增殖环必须收缩成环宏");
        var macro = macros.stream().filter(m -> m.entryKey().equals(E)).findFirst().orElseThrow();
        assertEquals(1000000000L, startupSeedCount(macro, K),
                "前提：minimumSeeds 交回的起步种量是十位数 1000000000");
    }

    /** 读数随起步种的数域走（long 或 PlanAmount）。 */
    private static long startupSeedCount(RingMacro macro, AEKey key) {
        Object value = macro.startupSeeds().get(key);
        if (value instanceof PlanAmount amount) {
            return amount.exactValue().longValueExact();
        }
        return ((Long) value).longValue();
    }
}
