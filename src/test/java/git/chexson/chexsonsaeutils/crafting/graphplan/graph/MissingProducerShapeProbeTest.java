package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

/**
 * 台账 #203 第 1 步（<b>分类探针，主代码零判据改动</b>）：03:18 那一跑（lane b1c73c5f，
 * ae2:creative_storage_cell x 1e11）点名的五枚缺料键，离线逐枚复现"它为什么进不了这张计划的生产者集"，
 * 三选一：(a) 结构洞 / (b) 准入洞 / (c) 同型残留（d782652 那一型换了站点）。
 *
 * <p><b>实据取材</b>：{@code reference-sources/probe-dumps/plan-captures/tail-2018.json}
 * （{@code schemaVersion=2}，{@code integrity.complete=true}），逐字节读出五枚键的
 * {@code getCraftingFor}（这就是 {@code GraphCompiler} 唯一问生产者的入口，:188/:295）：
 * <ul>
 * <li>{@code ae2lt:firmament_essence}（键 1241）：{@code getCraftingFor=[]}，无样板产它，却被子样
 *     515/517/522/550 消费 ⇒ <b>(a) 结构洞</b>；生产它的 {@code ae2lt:firmament_conversion} 机器
 *     （reference-sources/AE2-Lightning-Tech .../blockentity/FirmamentConversionCoreBlockEntity.java）
 *     既无 {@code ICraftingProvider}、也无 {@code IPatternDetails}/{@code getAvailablePatterns}，
 *     规划图 {@code getCraftingFor} 收不到那台机器的配方；</li>
 * <li>{@code ae2lt:firmament_alloy_ingot}（键 1242）：{@code getCraftingFor=[]}，同上 ⇒ <b>(a)</b>；</li>
 * <li>{@code ae2lt:lightning(tier=extreme_high_voltage)}（键 534，一枚非 item 的 {@code AEKey}）：
 *     {@code getCraftingFor=[]}，被六张子样消费，闪电由 TeslaCoil 直投网络（同机器非提供者）⇒ <b>(a)</b>；</li>
 * <li>{@code mekanism:enriched_redstone}（键 512）：{@code getCraftingFor=[204]}（204：红石→1 枚）⇒
 *     <b>生产者在图上存在</b>，不是 (a)；03:18 日志给它的是 {@code planEmit=Long.MAX}（AE2 long 账被饱和成哨兵），
 *     属第 2 步第 3 件"承诺量不进销账"的形状，<b>这份日志与离线都答不了它是 (b) 还是 (c)</b>——
 *     离线夹具没有那一跑的 >long 存量/发数分布；</li>
 * <li>{@code advanced_ae:quantum_infusion_source}（键 206，流体）：{@code getCraftingFor=[74]}（74 每发 1000）⇒
 *     <b>生产者在图上存在</b>，同上一枚，非 (a)，离线判不了 (b)/(c)。</li>
 * </ul>
 *
 * <p>本类只断言可离线坐实的那一半：(a) 型"图上没有生产者路由"⇒ {@code producersByOutput} 交回空集，
 * 且存量装不下时它进缺料表（{@code simulation}）而不是被 mint 出一个凭空的产出。
 * 不新增判据、不改任何既有乘式。
 */
final class MissingProducerShapeProbeTest {

    /** (a) 型：一枚"图上没有任何生产者样板"的键（实机 ae2lt:firmament_essence 那一线的形状）。 */
    private static final AEItemKey NO_PRODUCER = AEItemKey.of(Items.STRUCTURE_VOID);
    private static final AEItemKey FUEL = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);

    /**
     * (a) 结构洞：{@code getCraftingFor} 空 ⇒ 编译产物 {@code producersByOutput} 对这一枚也交回空集 ⇒
     * 求解器无论量多大都排不出产它的一张样板。这正是三枚 ae2lt 键在图上落不进生产者集的那一格。
     */
    @Test
    void structuralHoleKeyIsAbsentFromTheProducerSetEntirely() {
        var consumer = FakePattern.of(TARGET, 1L, FakeInput.stack(NO_PRODUCER, 1L));
        Map<AEKey, Long> stock = Map.of(NO_PRODUCER, 1_000_000_000_000L, FUEL, 100L);
        var grid = FakeGrid.builder().storage(stock).pattern(TARGET, consumer).build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        assertTrue(graph.producersByOutput().getOrDefault(NO_PRODUCER, List.of()).isEmpty(),
                () -> "getCraftingFor 空集的键，图上没有一条生产者路由；生产者集="
                        + graph.producersByOutput().keySet());
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10L);
        assertFalse(result.hasDeclined(), () -> "存量够付这一枚 ⇒ 不该 DECLINE: " + result.declineReason());
    }

    /**
     * (a) 结构洞、存量装不下：这一枚被如实记进缺料表（{@code simulation=true}），
     * 而不是拿一个不存在的生产者凑数——离线复现三枚 ae2lt 键"消费却无生产者"的下场。
     */
    @Test
    void structuralHoleKeyBeyondStockBecomesAShortageNotAMintedProducer() {
        var consumer = FakePattern.of(TARGET, 1L, FakeInput.stack(NO_PRODUCER, 1L));
        Map<AEKey, Long> stock = Map.of(NO_PRODUCER, 5L);
        var grid = FakeGrid.builder().storage(stock).pattern(TARGET, consumer).build();
        var networkStock = new KeyCounter();
        stock.forEach(networkStock::add);

        var session = GraphPlanningSession.create(grid.crafting, networkStock, TARGET,
                CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(0L), null,
                false, grid.storage.getInventory(), grid);
        var attempt = session.solve(10L);

        var plan = attempt.handled() ? attempt.plan() : null;
        long missing = plan == null ? 0L : Math.max(0L, plan.missingItems().get(NO_PRODUCER));
        // 无生产者 ⇒ 或判决被拒（handled=false，带 shortage 账），或交回带 simulation 的缺料计划；
        // 唯一不许出现的形状是"缺料报 0 且 simulation=false 却根本没有生产者"——那才是静默假闭合。
        assertTrue(!attempt.handled() || (plan.simulation() && missing > 0L),
                () -> "结构洞键不许被报成'够'：handled=" + attempt.handled()
                        + " simulation=" + (plan == null ? "n/a" : plan.simulation())
                        + " missing=" + missing);
    }
}
