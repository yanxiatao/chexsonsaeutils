package git.chexson.chexsonsaeutils.crafting.solve;

import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;

/**
 * 注册型无限源（KubeJS／ExtendedAE 的岩浆、水）在<b>规划侧</b>的验收。
 *
 * <p>它不在任何存储单元里：网络的库存表只报单元那份真存量，
 * {@code MEStorage.extract(key, N, SIMULATE)} 却对任意 N 都答应。判别只认行为
 * （{@code 取出 > 报出}），与单元侧 {@code PlanningSupplyTruth#overReportingKeys} 同一条判据，
 * 只是问的对象从单元抬到网格总库存。
 *
 * <p>三型一起钉：① 取出＞报出 ⇒ 认，且必须真出得了单；② 报量＝SIMULATE 读数、只是真不够
 * （{@code refinedstorage:silicon} 那一型）⇒ 照旧判缺料；③ 报量顶到 {@code Long.MAX_VALUE}
 * ⇒ 哨兵不是数量，extract 一问都不许发生、更不许 mint——缺口按"需求即供给"记进宽域账，
 * 判决延后给提交腿（真无限源照常跑得动；neoeco 那型有限盘则是"计划出得来、跑到盘空如实
 * 停滞"，有意接受的取舍）。延后不是吞掉：需求全量必须能在计划载体的宽域 used 账上读回。
 */
final class NetworkRegisteredUnboundedSourceTest {

    private static final AEKey LAVA = AEFluidKey.of(Fluids.LAVA);
    private static final AEKey SILICON = AEItemKey.of(Items.QUARTZ);
    private static final AEKey SATURATED_DISK = AEItemKey.of(Items.REDSTONE);
    private static final AEKey DEFERRED_KEY = AEItemKey.of(Items.GLOWSTONE);
    private static final AEKey TARGET = AEItemKey.of(Items.CAULDRON);

    /** 每发吃 1e16 枚原料：1000 发 ⇒ 用料 1e19 枚，比 long 天花板高一截（实机 lava 形态）。 */
    private static final long PER_CRAFT = 10_000_000_000_000_000L;
    private static final BigInteger NEED = BigInteger.valueOf(PER_CRAFT)
            .multiply(BigInteger.valueOf(1_000L));

    /**
     * 网络里有「取出＞报出」的注册型无限元件时，直接产生对应量的原料绕过提取——
     * 不许按单元那份真存量判缺料，把确认界面的开始键夹灰（实机 2026-09-25 00:15 那一组单）。
     */
    @Test
    void registeredSourceWithBeyondLongDemandIsCreditedAndLeftToTheSubmitLeg() {
        // 库存表只报 100 枚岩浆，网络总库存对任意请求都答应（KubeJS／ExtendedAE 那一型）
        var network = new FakeNetworkInventory(LAVA, 100L, Long.MAX_VALUE);

        var attempt = runPlanning(Map.of(LAVA, 100L), network, 1_000L);

        assertTrue(attempt.handled(), () -> "无限源认中后这一单该出得了计划，而不是继续拒: "
                + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertFalse(plan.simulation(),
                () -> "岩浆是无限源，这一单没有缺口：还带 simulation 标记就是开始键仍然灰着 " + plan.missingItems());
        assertEquals(0L, plan.missingItems().get(LAVA),
                () -> "不许报缺料: " + plan.missingItems());
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(LAVA),
                () -> "报量有限而 SIMULATE 给满的键不 mint：它与有限盘同形，凭空的量一次都不许造: "
                        + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
        assertEquals(NEED, WideAmountPlanCarrier.resolveWideLedgers(plan).get(WideAmountKind.USED, LAVA),
                "绕过提取不等于少用料：计划仍按 1e19 枚岩浆记账");
        // 只有一问：出口对账按缺口量问的那一次 SIMULATE；判决延后，不再做第二趟网格层认源
        assertEquals(1, network.asks.size(), () -> "延后判决不许再多问网络: " + network.asks);
    }

    /**
     * 反向用例（{@code refinedstorage:silicon} 那一型）：网络报量与 SIMULATE 读数一致、
     * 只是真不够——照旧判缺料；「取出＞报出」一次都没成立就不许认成无限源。
     */
    @Test
    void finiteSourceWhoseSimulateMatchesItsReportStillShorts() {
        // 报 1e18 枚，一次最多也只给 1e18 枚：取出＝报出
        var network = new FakeNetworkInventory(SILICON, 1_000_000_000_000_000_000L,
                1_000_000_000_000_000_000L);

        var attempt = runPlanning(Map.of(SILICON, network.reported), network, 1_000L);

        assertTrue(attempt.handled(), () -> "真缺料这一单仍该交回材料不足回执: " + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertTrue(plan.simulation(), "真不够就是做不出来：不许把缺口当无限源放行");
        assertTrue(plan.missingItems().get(SILICON) > 0L,
                () -> "缺口必须照报: " + plan.missingItems());
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(SILICON),
                "取出＝报出的有限盘不许进无限键集");
    }

    /**
     * 复核 P0 那一型：网络是<b>活</b>对象，两次读数之间有人往里放过几枚 ⇒ "取出"就会大于"报出"。
     *
     * <p>拿这个当判据会把普通有限键认成无限源，后果不是假缺口而是<b>凭空多料</b>（认中即随计划
     * 出门，提交侧不再抽它、取料点就地认量）。所以问的是"报量 + 余量"那一口，且要它<b>全额交出</b>
     * 才算：只多给几枚的有限盘永远填不满这一口。
     */
    @Test
    void aFiniteStoreThatGainedAFewUnitsBetweenReadsIsNotMistakenForInfinite() {
        // 报 100 枚，一次最多交出 101 枚：正是"两次读数之间净增了几枚"的形状
        var network = new FakeNetworkInventory(SILICON, 100L, 101L);

        var attempt = runPlanning(Map.of(SILICON, network.reported), network, 1_000L);

        assertTrue(attempt.handled(), () -> "这一单该照实交回材料不足回执: " + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertTrue(plan.simulation(), "净增几枚的有限盘不是无限源：不许把缺口认成无限供给");
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(SILICON),
                () -> "有限键进了无限键集，执行侧就会凭空地认量: "
                        + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
    }

    /**
     * 报量已经顶到 {@code Long.MAX_VALUE} 的有限盘（neoecoae／extendedae_plus 那一型）：
     * 那是<b>哨兵不是数量</b>，问它也问不出更多信息——extract 一问都不许发生，更不许认成
     * 无限源。缺口按"需求即供给"记进宽域账后延后给提交腿：那里对越出 long 的需求<b>只喂一刀</b>，
     * 认明这一刀给不给得起——一口给不满就是真缺料、如实报缺；给得起满口则放行提交，剩下的那一截
     * 由执行侧 {@code refillTaskInputsFromNetwork} / {@code payDeclaredBatchObligations} 按批常态续取
     * （有意接受的取舍，见结论）。
     */
    @Test
    void saturatedReadingIsSentinelNeverAskedAndNeverMinted() {
        var network = new FakeNetworkInventory(SATURATED_DISK, Long.MAX_VALUE, Long.MAX_VALUE);

        // 3000 发 × 1e16 枚 = 3e19 枚：饱和读数盖不住，缺口走延后判决而不是缺料表
        var attempt = runPlanning(Map.of(SATURATED_DISK, Long.MAX_VALUE), network, 3_000L);

        assertTrue(attempt.handled(), () -> "延后判决的这一单该出得了计划: " + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertFalse(plan.simulation(), "规划期判不了哨兵键够不够：不许再把它记成缺料单");
        assertEquals(0L, plan.missingItems().get(SATURATED_DISK),
                () -> "规划期缺料表里不许再有它: " + plan.missingItems());
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(SATURATED_DISK),
                "报满 long 的有限盘不是无限源（这一坑用户纠过两次）");
        assertEquals(BigInteger.valueOf(3_000L).multiply(BigInteger.valueOf(PER_CRAFT)),
                WideAmountPlanCarrier.resolveWideLedgers(plan).get(WideAmountKind.USED, SATURATED_DISK),
                "需求即供给：3e19 枚全量记进宽域 used 账，提交腿照这份数只喂一刀认明给不给得起");
        // 报量已是哨兵：连出口对账那一问都不该发生（判决不靠网络读数）
        assertTrue(network.asks.isEmpty(), () -> "哨兵键一问都不许: " + network.asks);
    }

    /**
     * 实机 2026-09-25 02:34 那一型：上一单跑起来之后宿主读数被顶到哨兵，网络对
     * KubeJS 那型<b>真无限源</b>也报 {@code Long.MAX_VALUE}——与 neoeco 有限盘在读侧
     * 完全同形，规划期判不了"够不够"。判决延后给提交腿（越出 long 的需求只喂一刀、一口给不满
     * 按真值报缺，剩下那一截由执行侧按批常态续取）：计划不再带缺口、开始键不灰；但绝不 mint——
     * 无限键集里没有它。
     */
    @Test
    void saturatedNetworkReportDefersVerdictToSubmitLegInsteadOfMissingIt() {
        var network = new FakeNetworkInventory(SATURATED_DISK, Long.MAX_VALUE, Long.MAX_VALUE);

        // 5e6 发 × 1e16 枚 = 5e22 枚：超 long 两个量级（实机 1e10 单的形态）
        var attempt = runPlanning(Map.of(SATURATED_DISK, Long.MAX_VALUE), network, 5_000_000L);

        assertTrue(attempt.handled(), () -> "延后判决的这一单该出得了计划: " + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertFalse(plan.simulation(),
                () -> "哨兵报量的缺口不许再判死整单（判决延后给提交腿）: " + plan.missingItems());
        assertEquals(0L, plan.missingItems().get(SATURATED_DISK),
                () -> "规划期缺料表里不许再有它: " + plan.missingItems());
        assertFalse(WideAmountPlanCarrier.resolveUnboundedKeys(plan).contains(SATURATED_DISK),
                () -> "报了多少不是无限判据：哨兵键不许 mint 进无限键集: "
                        + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
    }

    /**
     * 延后不是吞掉：缺料表清空的同时，需求必须按"需求即供给"全量记进宽域 used 账——
     * 提交腿 {@code tryExtractInitialItems} 照这份账认明这一刀（≤long）给不给得起，一口给不满
     * 按真值报缺，给得起则剩下那一截交给执行侧按批续取。
     * 吞掉的样子是 missing=0 而 used 少于需求（料凭空消失）；这里逐位钉死 used=需求。
     */
    @Test
    void deferredKeyCarriesItsFullDemandIntoTheSubmitLedger() {
        var network = new FakeNetworkInventory(DEFERRED_KEY, Long.MAX_VALUE, Long.MAX_VALUE);
        long firings = 2_000_000L;

        var attempt = runPlanning(Map.of(DEFERRED_KEY, Long.MAX_VALUE), network, firings);

        assertTrue(attempt.handled(), () -> "延后判决的这一单该出得了计划: " + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan);
        assertFalse(plan.simulation(), "延后判决：不许再带 simulation 标记");
        assertEquals(BigInteger.valueOf(firings).multiply(BigInteger.valueOf(PER_CRAFT)),
                WideAmountPlanCarrier.resolveWideLedgers(plan).get(WideAmountKind.USED, DEFERRED_KEY),
                "延后=判决交给提交腿：宽域 used 账必须记满需求全量（需求即供给），少一枚都是吞料");
        assertEquals(Long.MAX_VALUE, plan.usedItems().get(DEFERRED_KEY),
                "窄视图照旧交饱和代表值：真值只走宽域载体，哨兵不许被当成数量");
        assertTrue(network.asks.isEmpty(),
                () -> "报量已是哨兵：extract 一问都不该发生（判决不靠网络读数）: " + network.asks);
    }

    // ---- fixtures ----

    /** 走完整的图规划一趟：编译 → 求解 → 出口对账 →（修复后）网格层无限源判别 → 转换。 */
    private static PlanningAttemptResult runPlanning(Map<AEKey, Long> stock, FakeNetworkInventory network,
            long requestedAmount) {
        var grid = FakeGrid.builder()
                .storage(stock)
                .pattern(TARGET, FakePattern.crafting(TARGET, 1L, Map.of(network.key, PER_CRAFT)))
                .build();
        var networkStock = new KeyCounter();
        stock.forEach(networkStock::add);
        // 与生产同一条链：storage 就是 grid.getStorageService().getInventory()（生产侧见 RecursivePlanningKernel#session）
        var session = GraphPlanningSession.create(grid.crafting, networkStock, TARGET,
                CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(0L), null,
                false, network, grid);
        return session.solve(requestedAmount);
    }

    /**
     * 网络总库存桩：报量 {@code reported}（库存表里那份读数），一次 SIMULATE 最多交出
     * {@code perAsk}——{@code 取出 > 报出} 就是无限元件的行为签名。
     */
    private static final class FakeNetworkInventory implements MEStorage {
        private final AEKey key;
        private final long reported;
        private final long perAsk;
        /** SIMULATE 询价台账：用来钉「只在判缺料那条路上问」。 */
        private final java.util.List<String> asks = new java.util.ArrayList<>();

        private FakeNetworkInventory(AEKey key, long reported, long perAsk) {
            this.key = key;
            this.reported = reported;
            this.perAsk = perAsk;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            out.add(this.key, this.reported);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (!this.key.equals(what)) {
                return 0L;
            }
            if (mode == Actionable.SIMULATE) {
                this.asks.add(what + "=" + amount);
                // 负数问价就是拿"报量 + 1"去问过 Long.MAX_VALUE 那道哨兵：溢出，问不出任何东西
                assertTrue(amount >= 0L, "不许向网络问负数——报量已是哨兵时那一问根本不该发生");
            }
            return Math.min(amount, this.perAsk);
        }

        @Override
        public Component getDescription() {
            return Component.literal("fake-network");
        }
    }
}
