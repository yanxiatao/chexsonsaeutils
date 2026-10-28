package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.crafting.ParallelCpuProvider;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanPostProcessor;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import static git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.assertPlansEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规划入口与提交的行为场景；由类别测试统一调用。 */
public final class SubmissionScenarios {
    private SubmissionScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.solve",
                () -> FastSimStatePoolCases.verify(temporaryRoot),
                () -> GraphPlanningFallbackCases.verify(temporaryRoot),
                () -> NetworkRegisteredUnboundedSourceCases.verify(temporaryRoot),
                () -> OrderedPlanningRecursiveCapabilityCases.verify(temporaryRoot),
                () -> OrderedPlanningSelectorCases.verify(temporaryRoot),
                () -> PartialPlanMissingSummaryCases.verify(temporaryRoot),
                () -> PlanGrossAuditWideViewCases.verify(temporaryRoot),
                () -> PlanningLevelRouterCases.verify(temporaryRoot),
                () -> PlanningProvenanceCases.verify(temporaryRoot),
                () -> PlanningSupplyWrapAroundSeedCases.verify(temporaryRoot),
                () -> TerminalShortagePlanCases.verify(temporaryRoot),
                () -> WidePlanLedgerCodecCases.verify(temporaryRoot)
        );
    }

    /** FastSimStatePoolCases 的独立场景夹具，按类别统一执行。 */
    public static final class FastSimStatePoolCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

        void reusedStateDiscardsPreviousChangesAndReadsTheNewParent() {
            var originalParent = inventory(10L);
            var state = FastSimStatePool.acquire(originalParent);
            assertEquals(4L, state.extract(IRON, 4L, Actionable.MODULATE));
            state.insert(IRON, 100L, Actionable.MODULATE);
            FastSimStatePool.release(state);

            var newParent = inventory(20L);
            var reused = FastSimStatePool.acquire(newParent);
            try {
                assertSame(state, reused);
                assertEquals(20L, reused.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
                assertEquals(20L, reused.extract(IRON, Long.MAX_VALUE, Actionable.MODULATE));
                assertEquals(0L, reused.extract(IRON, 1L, Actionable.SIMULATE));
                assertEquals(10L, originalParent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
                assertEquals(20L, newParent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            } finally {
                FastSimStatePool.release(reused);
            }
        }

        void nestedStatesNeverShareChanges() {
            var parent = inventory(10L);
            var outer = FastSimStatePool.acquire(parent);
            var inner = FastSimStatePool.acquire(outer);
            try {
                assertNotSame(outer, inner);
                assertEquals(3L, inner.extract(IRON, 3L, Actionable.MODULATE));
                assertEquals(7L, inner.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
                assertEquals(10L, outer.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
                inner.applyDiff(outer);
                assertEquals(7L, outer.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
                assertEquals(10L, parent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            } finally {
                FastSimStatePool.release(inner);
                FastSimStatePool.release(outer);
            }
        }

        private static ListCraftingInventory inventory(long amount) {
            var inventory = new ListCraftingInventory(ignored -> { });
            inventory.insert(IRON, amount, Actionable.MODULATE);
            return inventory;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("FastSimStatePoolCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("FastSimStatePoolCases.reusedStateDiscardsPreviousChangesAndReadsTheNewParent", () -> new FastSimStatePoolCases().reusedStateDiscardsPreviousChangesAndReadsTheNewParent()),
                () -> org.junit.jupiter.api.Assertions.assertAll("FastSimStatePoolCases.nestedStatesNeverShareChanges", () -> new FastSimStatePoolCases().nestedStatesNeverShareChanges())
            );
        }
    }

    /** GraphPlanningFallbackCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphPlanningFallbackCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

        /** 图这一腿走仍在生产的有序规划入口（GRAPH 打头，见 {@link OrderedPlanningReplay}）。 */
        private static ICraftingPlan runGraph(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
            return OrderedPlanningReplay.create(
                    grid, grid.requester, new GenericStack(output, amount), strategy, PlanningAlgorithmId.GRAPH).run();
        }

        private static ICraftingPlan runTree(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
            return new Ae2TreePlanReference(
                    grid, grid.requester, new GenericStack(output, amount), strategy).run();
        }

        void graphExpressibleRequestMatchesNativeTree() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 100L))
                    .pattern(IRON, ironFromGold)
                    .build();

            assertPlansEqual(runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS),
                    runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
        }

        void multipleRoutesMatchNativeTree() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 10L, DIAMOND, 30L))
                    .pattern(IRON, ironFromGold, ironFromDiamond)
                    .build();

            ICraftingPlan expected = runTree(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(expected);
            assertTrue(expected.multiplePaths());
            // Multi-route graphs are solved directly since phase 4; the result
            // must still equal the optimized tree's answer.
            assertPlansEqual(expected, runGraph(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS));
        }

        void craftLessBisectionMatchesNativeTree() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 10L))
                    .pattern(IRON, ironFromGold)
                    .build();

            ICraftingPlan expected = runTree(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
            assertNotNull(expected);
            assertFalse(expected.simulation());
            ICraftingPlan graphPlan = runGraph(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
            assertPlansEqual(expected, graphPlan);
            assertEquals(5L, graphPlan.finalOutput().amount());
        }

        void missingItemsSimulationMatchesNativeTree() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 5L))
                    .pattern(IRON, ironFromGold)
                    .build();

            ICraftingPlan expected = runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(expected);
            assertTrue(expected.simulation());
            assertPlansEqual(expected, runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
        }

        /**
         * 回落腿不管 bigint（台账 #155/T12）：AE2 原生树真跑一趟交出的那一份，就是 AE2 自己的
         * {@code CraftingPlan}，三条宽域读口（出入账／份数账／无限源键集）一律解析为空。
         *
         * <p>挑的这一格是宽域账<b>最可能被顺手补上去</b>的那一型：整单做不出来、原生树交回
         * {@code simulation=true} 的缺料计划——我方自有图引擎在这种场合确实会换成带宽域缺料账的
         * 回执（#83），原生腿要是也长出这一手，回落就变成"给 AE2 的结果再宽域化"。
         *
         * <p>一行只判一件事：类、出入账、份数账、无限源键各断一条，红字点名是哪一条断的。
         */
        void nativeTreePlanCarriesNoWideLegs() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 5L))
                    .pattern(IRON, ironFromGold)
                    .build();

            ICraftingPlan plan = runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertTrue(plan.simulation(), "这一格原生树交的是残缺计划：宽域账最可能被补上去的那一型");

            assertEquals(CraftingPlan.class, plan.getClass(),
                    "原生腿只能交 AE2 自己的 CraftingPlan：套上我方载体就等于给原生树造宽域账");
            assertTrue(WideAmountPlanCarrier.resolveWideLedgers(plan).isEmpty(),
                    "原生计划不得带宽域出入账 " + WideAmountPlanCarrier.resolveWideLedgers(plan));
            assertTrue(WideAmountPlanCarrier.resolveWideCrafts(plan).isEmpty(),
                    "原生计划不得带宽域份数账 " + WideAmountPlanCarrier.resolveWideCrafts(plan));
            assertTrue(WideAmountPlanCarrier.resolveUnboundedKeys(plan).isEmpty(),
                    "原生计划不得带无限源键集 " + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases.graphExpressibleRequestMatchesNativeTree", () -> new GraphPlanningFallbackCases().graphExpressibleRequestMatchesNativeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases.multipleRoutesMatchNativeTree", () -> new GraphPlanningFallbackCases().multipleRoutesMatchNativeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases.craftLessBisectionMatchesNativeTree", () -> new GraphPlanningFallbackCases().craftLessBisectionMatchesNativeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases.missingItemsSimulationMatchesNativeTree", () -> new GraphPlanningFallbackCases().missingItemsSimulationMatchesNativeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningFallbackCases.nativeTreePlanCarriesNoWideLegs", () -> new GraphPlanningFallbackCases().nativeTreePlanCarriesNoWideLegs())
            );
        }
    }

    /** NetworkRegisteredUnboundedSourceCases 的独立场景夹具，按类别统一执行。 */
    public static final class NetworkRegisteredUnboundedSourceCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.registeredSourceWithBeyondLongDemandIsCreditedAndLeftToTheSubmitLeg", () -> new NetworkRegisteredUnboundedSourceCases().registeredSourceWithBeyondLongDemandIsCreditedAndLeftToTheSubmitLeg()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.finiteSourceWhoseSimulateMatchesItsReportStillShorts", () -> new NetworkRegisteredUnboundedSourceCases().finiteSourceWhoseSimulateMatchesItsReportStillShorts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.aFiniteStoreThatGainedAFewUnitsBetweenReadsIsNotMistakenForInfinite", () -> new NetworkRegisteredUnboundedSourceCases().aFiniteStoreThatGainedAFewUnitsBetweenReadsIsNotMistakenForInfinite()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.saturatedReadingIsSentinelNeverAskedAndNeverMinted", () -> new NetworkRegisteredUnboundedSourceCases().saturatedReadingIsSentinelNeverAskedAndNeverMinted()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.saturatedNetworkReportDefersVerdictToSubmitLegInsteadOfMissingIt", () -> new NetworkRegisteredUnboundedSourceCases().saturatedNetworkReportDefersVerdictToSubmitLegInsteadOfMissingIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkRegisteredUnboundedSourceCases.deferredKeyCarriesItsFullDemandIntoTheSubmitLedger", () -> new NetworkRegisteredUnboundedSourceCases().deferredKeyCarriesItsFullDemandIntoTheSubmitLedger())
            );
        }
    }

    /** OrderedPlanningRecursiveCapabilityCases 的独立场景夹具，按类别统一执行。 */
    public static final class OrderedPlanningRecursiveCapabilityCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey A = AEItemKey.of(Items.STICK);
        private static final AEItemKey B = AEItemKey.of(Items.COAL);
        private static final AEItemKey LEAF = AEItemKey.of(Items.REDSTONE);

        void everyConfiguredAlgorithmUsesSharedRecursiveKernel() {
            var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L)),
                    new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
            var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1L, LEAF, 32L))
                    .pattern(A, second)
                    .pattern(B, first)
                    .pattern(TOP, consumer)
                    .build();

            assertEveryAlgorithmPlans(grid, consumer, first, second);
        }

        void retentionOnlyReseedsCatalystsExtractedFromNetwork() {
            var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L)),
                    new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
            var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1L, LEAF, 32L))
                    .pattern(A, second)
                    .pattern(B, first)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(B, 1L);
            stock.add(LEAF, 32L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 8L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            var graph = kernel.session().graph();
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

            assertFalse(result.hasDeclined(), result::declineReason);
            assertEquals(0L, result.state().usedAmount(A), "A must be supplied by the ring, not network stock");
            assertTrue(result.state().usedAmount(B) > 0L, "B is the actual network seed");
            assertTrue(result.state().ringExtractions().getLong(B) > 0L);
            assertEquals(0L, result.state().ringExtractions().getLong(A));
            assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
            assertEquals(0L, result.state().ringExtractions().getLong(A));
            RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class,
                    Ae2PlanConverter.toPlan(result, graph));
            assertTrue(contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B) > 0L);
            assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
            assertTrue(contract.chexsonsaeutils$dyeableRecursiveInternalItems().get(B) > 0L);
        }

        void retentionPreservesCompleteMultiSeedStartupSet() {
            var intermediate = AEItemKey.of(Items.IRON_INGOT);
            var output = AEItemKey.of(Items.DIAMOND);
            var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(intermediate, 1L)),
                    new FakeInput(A, 1L), new FakeInput(B, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                            new GenericStack(output, 1L)),
                    new FakeInput(intermediate, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, B, 1L))
                    .pattern(A, recycler)
                    .pattern(B, recycler)
                    .pattern(intermediate, starter)
                    .pattern(output, recycler)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(A, 1L);
            stock.add(B, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 8L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            var graph = kernel.session().graph();
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

            assertFalse(result.hasDeclined(), result::declineReason);
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
            assertEquals(1L, result.state().ringExtractions().getLong(A));
            assertEquals(1L, result.state().ringExtractions().getLong(B));
            assertEquals(0L, result.state().ringExtractions().getLong(intermediate));
            assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
            RecursivePlanContract directContract = assertInstanceOf(RecursivePlanContract.class,
                    Ae2PlanConverter.toPlan(result, graph));
            assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
            assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
            assertEquals(0L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));

            forEachAlgorithmPlan(grid, plan -> {
                RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class, plan);
                assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
                assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
                assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));
                assertEquals(8L, plan.patternTimes().get(consumer));
                assertTrue(plan.patternTimes().getOrDefault(starter, 0L) >= 8L);
                assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 8L);
            });
        }

        /**
         * 留种口径（用户要求）：网络里本来有一颗种子，计划可以把它提取进 CPU 用掉，
         * 但作业跑完这颗种子必须回到网络——所以计划必须多算一发。
         *
         * <p>这里的主计划恰好只吃种子、一次环都不走（{@code ringExtractions} 为空），
         * 早期版本整段早退，种子被吃干且无缺口可报。
         */
        void retentionRefiresRingWhenPlanEatsTheOnlySeed() {
            var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            var doubling = FakePattern.of(template, 2L, new FakeInput(template, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(template, 1L))
                    .pattern(template, doubling)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(template, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 1L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "eating the only seed must still yield a plan");
            assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
            assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                    "吃到唯一种子必须多算一发把种子还回网络, patterns=" + plan.patternTimes());
            // 多算那一发的剩余份留在 CPU 池子里，作业结束时由 storeItems 倒回网络；
            // 验收口径是计划全部发射跑完后网络投影仍留有种子（投影按整轮取整，可能多留）。
            assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                    "作业后网络必须仍留有 1 颗种子, patterns=" + plan.patternTimes());
        }

        /**
         * 做种那一发是<b>规划里的一发</b>：网络里缺的那枚原料如实报缺料，计划照出，
         * 而不是事后补种补不动、或者干脆拒掉整条递归规划。
         *
         * <p>实机形态是 {@code ae2:creative_storage_cell x 1}：留种要跑锻造模板环一发，
         * 环净吃一枚环外料（夹具用煤炭占这个位置），而网络里压根没有它。旧口径先解完再补，
         * 补种排不动就 {@code decline=retention net input ... has no reusable plan path}
         * → recursive 与 NATIVE 双双拒规划 → {@code Failed to start crafting job}。
         */
        void unsourcableSeedMaterialIsPlannedInAndReportedMissing() {
            var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            var doubling = FakePattern.of(template, 2L,
                    new FakeInput(template, 1L), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(template, 1L))
                    .pattern(template, doubling)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(template, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 1L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "原料不齐也要有计划可交");
            // 两发环：一发交付，一发还种，各吃一枚 B，网络里没有 ⇒ 如实缺 2 枚。
            assertEquals(2L, plan.missingItems().get(B),
                    () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
            // 那一发是规划自己排出来的（种子不可花 → 只能造），不是解完之后附加的。
            assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                    () -> "times=" + plan.patternTimes());
        }

        /**
         * 实机形态：网络里的原料刚好够主计划吃光。留种生效的那条规则是<b>那颗种不是可花库存</b>，
         * 于是主计划自己得把补种那一发排出来，原料在同一次闭式解里竞争。
         *
         * <p>旧口径是解完再补账：沙盒已被主计划扣干（{@code plannedUsed == network}），
         * 补种排不动 → 报缺料甚至拒规划。夹具用煤炭占钻石的位置：库存 3 枚全被主链吃掉，
         * 环那一发还要 1 枚。
         */
        void seedMaterialsAreReservedBeforeTheMainPlanSpendsThem() {
            var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            var coal = AEItemKey.of(Items.COAL);
            var doubling = FakePattern.of(template, 2L,
                    new FakeInput(template, 1L), new FakeInput(coal, 1L));
            var consumer = FakePattern.of(TOP, 1L,
                    new FakeInput(template, 1L), new FakeInput(coal, 3L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(template, 1L, coal, 3L))
                    .pattern(template, doubling)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(template, 1L);
            stock.add(coal, 3L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 1L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "做种占位之后仍要有计划可交");
            assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                    () -> "补种那一发必须在计划里, times=" + plan.patternTimes());
            // 三枚煤炭全被提进 CPU：主链一发 3 枚，还种那一发的份额排不到料，只能如实缺料。
            assertEquals(3L, plan.usedItems().get(coal),
                    () -> "used=" + FakePlanGrid.toPlain(plan.usedItems()));
            assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                    "作业跑完网络里还得有一颗种子, patterns=" + plan.patternTimes());
        }

        /**
         * 实机形态（锻造模板环 1 出 2）：主链要吃 N 枚模板，网络里正好只有起步那 1 枚种。
         *
         * <p>环的起步预留会把那颗种重新塞回沙盒，于是计划按"N 枚消耗 = 环净产 N−1 + 种 1"排程，
         * 种被当成原料花掉 ⇒ {@code projected=0 SHORT}（16:58 实机：makes 2x63 / eats 1x63 + 1x64）。
         * 留种生效的口径是<b>要多接一发</b>：要 2 枚就按 3 枚规划，多那一发的原料在同一次闭式解里争。
         */
        void ringStartupSeedIsRepaidByOneMoreCycle() {
            var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            var doubling = FakePattern.of(template, 2L,
                    new FakeInput(template, 1L), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(template, 1L, B, 8L))
                    .pattern(template, doubling)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(template, 1L);
            stock.add(B, 8L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 2L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "要还种也得有计划可交");
            // 吃 2 枚就得净产 2 枚：起步那枚种不算可花库存，环得多跑一发。
            assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 2L,
                    () -> "times=" + plan.patternTimes());
            assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                    () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
        }

        /**
         * 实机形态（544 任务那条 SHORT 的形状）：环的留种催化物同时是另一个净输出键的入口，
         * 网络里只有 1 枚种，而环外样板每发吃 2 枚。
         *
         * <p>改前红：起步预留把网络里那颗种插回沙盒后，主链的同键需求把它吃掉；即使发射向量
         * 按"实际借走 1 枚"多接了一发，那一发也被后续同键需求吸收 —— 判决行
         * {@code seed=1 projected=0 SHORT makes=[2x5] eats=[1x5, 2x3] graphStock=1}。
         * 留种成立需要两件事同时成立：起步预留不把种变成可花料（预留后立刻扣回可花账）＋
         * 还种轮数按实际借走量算、去重按环而不是按入口键。
         */
        void ringSeedBorrowedByOtherEntryIsRepaidToNetwork() {
            var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L), new GenericStack(LEAF, 3L)), new FakeInput(B, 1L));
            var outside = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L)), new FakeInput(B, 2L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L), new FakeInput(A, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1L))
                    .pattern(B, pring).pattern(LEAF, pring).pattern(A, outside)
                    .pattern(TOP, consumer).build();
            var stock = new KeyCounter();
            stock.add(B, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 3L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);
            var graph = kernel.session().graph();
            assertEquals(1L, graph.retainedSeeds().getOrDefault(B, 0L), "种就是网络里那 1 枚催化物");
            assertTrue(graph.ringMacrosByEntry().size() >= 2, "同一环两个入口：" + graph.ringMacrosByEntry().keySet());

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "留种也得有计划可交");
            assertFalse(plan.simulation(), () -> "留种退化成模拟: missing="
                    + FakePlanGrid.toPlain(plan.missingItems()));
            assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
            assertTrue(projectedSeed(plan, B, 1L) >= 1L,
                    () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
            // 刚够交付是 5 发（净产 5 + 起步预留的 1 枚 = 环外要吃 6 枚），多接那一发才是留种。
            assertTrue(plan.patternTimes().getOrDefault(pring, 0L) >= 6L,
                    () -> "还种那一发没排出来, times=" + plan.patternTimes());
        }

        /**
         * 同一环被两个入口取用的形状：入口键 ≠ 留种催化物键时规则不得退化成 no-op。
         *
         * <p>这条在改前就是绿的（守形状用，不是判别用例）：改前的判别红由
         * {@link #ringSeedBorrowedByOtherEntryIsRepaidToNetwork} 承担。
         */
        void retainedSeedSurvivesWhenRingIsEnteredByBothKeys() {
            var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L), new GenericStack(LEAF, 3L)), new FakeInput(B, 1L));
            var outside = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L), new FakeInput(A, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1L))
                    .pattern(B, pring).pattern(LEAF, pring).pattern(A, outside)
                    .pattern(TOP, consumer).build();
            var stock = new KeyCounter();
            stock.add(B, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 3L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "双入口形状也要有计划可交");
            assertFalse(plan.simulation(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
            assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
            assertTrue(plan.patternTimes().getOrDefault(pring, 0L) >= 3L,
                    () -> "times=" + plan.patternTimes());
            assertTrue(projectedSeed(plan, B, 1L) >= 1L,
                    () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
        }

        /** stock + Σ(样板产出 − 样板输入)：计划跑完该键在网络侧的投影量。 */
        private static long projectedSeed(ICraftingPlan plan, AEKey seed, long networkStock) {
            long amount = networkStock;
            for (var entry : plan.patternTimes().entrySet()) {
                var details = entry.getKey();
                long times = entry.getValue();
                if (details == null || times <= 0L) {
                    continue;
                }
                for (var output : details.getOutputs()) {
                    if (output != null && seed.equals(output.what())) {
                        amount += output.amount() * times;
                    }
                }
                for (var input : details.getInputs()) {
                    var possible = input.getPossibleInputs();
                    if (possible == null) {
                        continue;
                    }
                    for (var candidate : possible) {
                        if (candidate != null && seed.equals(candidate.what())) {
                            amount -= candidate.amount() * input.getMultiplier() * times;
                            break;
                        }
                    }
                }
            }
            return amount;
        }

        void everyConfiguredAlgorithmHandlesCrossPatternCatalyst() {
            var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L)),
                    new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, LEAF, 32L))
                    .pattern(A, recycler)
                    .pattern(B, producer)
                    .pattern(TOP, consumer)
                    .build();

            assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
        }

        void everyConfiguredAlgorithmPreservesContractForFiniteUnsupportedCycle() {
            var forward = FakePattern.of(A, 1L, new FakeInput(B, 1L));
            var backEdge = FakePattern.of(B, 1L, new FakeInput(A, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1L))
                    .pattern(A, forward)
                    .pattern(B, backEdge)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(B, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 1L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            assertEquals(CraftGraph.CycleKind.UNSUPPORTED_CYCLE, kernel.session().graph().cycleKind());
            forEachAlgorithmPlan(grid, 1L, plan -> {
                assertEquals(1L, plan.finalOutput().amount());
                assertEquals(1L, plan.patternTimes().get(consumer));
                assertEquals(1L, plan.patternTimes().get(forward));
                assertEquals(0L, plan.patternTimes().getOrDefault(backEdge, 0L));
                assertEquals(1L, plan.usedItems().get(B));
            });
        }

        void recursiveKernelRetriesWithoutExpiredOptimizationBudget() throws InterruptedException {
            var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L)),
                    new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, LEAF, 32L))
                    .pattern(A, recycler)
                    .pattern(B, producer)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(A, 1L);
            stock.add(LEAF, 32L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 8L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

            var graph = kernel.session().graph();
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
            assertFalse(graph.ringMacrosByEntry().getOrDefault(B, List.of()).isEmpty(),
                    "recursive ring macro must be compiled for its entry key");
            Thread.sleep(20L);
            ICraftingPlan plan = kernel.solve();

            assertNotNull(plan, "expired optimization budget must not make recursive planning decline");
            assertFalse(plan.simulation(), () -> "fallback plan remained simulated: missing="
                    + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
            assertInstanceOf(RecursivePlanContract.class, plan);
        }

        void externallyDetectedRecursivePathRetriesWhenGraphClassificationIsNone() throws InterruptedException {
            var producer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(LEAF, 1L))
                    .pattern(TOP, producer)
                    .build();
            var stock = new KeyCounter();
            stock.add(LEAF, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 1L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

            assertEquals(CraftGraph.CycleKind.NONE, kernel.session().graph().cycleKind());
            Thread.sleep(20L);
            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "external recursive detection must force an exact retry");
            assertFalse(plan.simulation());
            assertTrue(plan.missingItems().isEmpty());
            assertEquals(1L, plan.patternTimes().get(producer));
        }

        void everyConfiguredAlgorithmHandlesNonUniformRingRatio() {
            var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 3L)), new FakeInput(A, 2L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L), new GenericStack(LEAF, 1L)),
                    new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 2L))
                    .pattern(A, recycler)
                    .pattern(B, producer)
                    .pattern(LEAF, recycler)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(A, 2L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 8L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, kernel.session().graph().cycleKind());
            var leafMacros = kernel.session().graph().ringMacrosByEntry().getOrDefault(LEAF, List.of());
            assertFalse(leafMacros.isEmpty());
            assertEquals(1L, leafMacros.getFirst().executionRatio().get(producer));
            assertEquals(2L, leafMacros.getFirst().executionRatio().get(recycler));
            ICraftingPlan plan = kernel.solve();

            assertNotNull(plan, "non-uniform recursive ratio must have an internal exact plan");
            assertFalse(plan.simulation(), () -> "non-uniform ring remained simulated: missing="
                    + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
            assertTrue(plan.missingItems().isEmpty());
            assertInstanceOf(RecursivePlanContract.class, plan);
            assertEquals(8L, plan.patternTimes().get(consumer));
            // 留种加成只看"实际从网络起步预留里借走了几枚留种催化物"：这里被扣的起步种是
            // 环中间产物（煤炭，netOutputs>0 才算留种对象），而网络里煤炭为 0 ⇒ 一枚都没借走
            // ⇒ 不加发（多算那一发既不还出任何东西，又抢主链的料）。所以发射量就是"刚好交付"
            // 那一版：每轮 producer 1 发 + recycler 2 发。真借走种的形状由
            // ringSeedBorrowedByRingEntryIsRepaidToNetwork 守着。
            assertEquals(9L, plan.patternTimes().get(producer));
            assertEquals(24L, plan.patternTimes().get(recycler));

            assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
        }

        void everyConfiguredAlgorithmHandlesNestedRings() {
            var inner = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(A, 2L), new GenericStack(LEAF, 1L)),
                    new FakeInput(A, 1L));
            var outerSeed = AEItemKey.of(Items.IRON_INGOT);
            var outerIntermediate = AEItemKey.of(Items.GOLD_INGOT);
            var outerOutput = AEItemKey.of(Items.DIAMOND);
            var outerProducer = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(outerIntermediate, 2L)),
                    new FakeInput(outerSeed, 1L), new FakeInput(LEAF, 1L));
            var outerRecycler = FakePattern.colored(3, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(outerSeed, 1L), new GenericStack(outerOutput, 1L)),
                    new FakeInput(outerIntermediate, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(outerOutput, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, outerSeed, 1L, outerIntermediate, 1L))
                    .pattern(A, inner)
                    .pattern(LEAF, inner)
                    .pattern(outerSeed, outerRecycler)
                    .pattern(outerIntermediate, outerProducer)
                    .pattern(outerOutput, outerRecycler)
                    .pattern(TOP, consumer)
                    .build();

            assertEveryAlgorithmPlansNestedRings(
                    grid, consumer, inner, outerProducer, outerRecycler);
        }

        void everyConfiguredAlgorithmHandlesMultiOutputRing() {
            var firstOutput = AEItemKey.of(Items.IRON_INGOT);
            var secondOutput = AEItemKey.of(Items.GOLD_INGOT);
            var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L), new GenericStack(firstOutput, 1L),
                            new GenericStack(secondOutput, 1L)),
                    new FakeInput(A, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
            var consumer = FakePattern.of(TOP, 1L,
                    new FakeInput(firstOutput, 1L), new FakeInput(secondOutput, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, B, 1L))
                    .pattern(A, recycler)
                    .pattern(B, producer)
                    .pattern(firstOutput, producer)
                    .pattern(secondOutput, producer)
                    .pattern(TOP, consumer)
                    .build();

            assertEveryAlgorithmPlansMultiOutputRing(grid, consumer, producer, recycler);
        }

        private static void assertEveryAlgorithmPlansNestedRings(
                FakeGrid grid,
                FakePattern consumer,
                FakePattern inner,
                FakePattern outerProducer,
                FakePattern outerRecycler
        ) {
            forEachAlgorithmPlan(grid, plan -> {
                assertEquals(8L, plan.patternTimes().get(consumer));
                assertTrue(plan.patternTimes().getOrDefault(inner, 0L) >= 8L);
                assertTrue(plan.patternTimes().getOrDefault(outerProducer, 0L) >= 8L);
                assertTrue(plan.patternTimes().getOrDefault(outerRecycler, 0L) >= 8L);
            });
        }

        private static void assertEveryAlgorithmPlansMultiOutputRing(
                FakeGrid grid,
                FakePattern consumer,
                FakePattern producer,
                FakePattern recycler
        ) {
            forEachAlgorithmPlan(grid, plan -> {
                assertEquals(8L, plan.patternTimes().get(consumer));
                assertTrue(plan.patternTimes().getOrDefault(producer, 0L) >= 8L);
                assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 4L);
            });
        }

        private static void forEachAlgorithmPlan(
                FakeGrid grid,
                java.util.function.Consumer<ICraftingPlan> assertion
        ) {
            forEachAlgorithmPlan(grid, 8L, assertion);
        }

        private static void forEachAlgorithmPlan(
                FakeGrid grid,
                long amount,
                java.util.function.Consumer<ICraftingPlan> assertion
        ) {
            var service = PlanningAlgorithmOrderService.global();
            List<String> originalOrder = service.snapshot().order();
            try {
                for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                    moveFirst(algorithm);
                    ICraftingPlan plan = new OrderedPlanningCalculation(
                            null, grid, grid.requester, new GenericStack(TOP, amount),
                            CalculationStrategy.REPORT_MISSING_ITEMS).run();
                    assertFalse(plan.simulation(), algorithm + " must calculate the recursive rings");
                    assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                    assertInstanceOf(RecursivePlanContract.class, plan,
                            algorithm + " must preserve recursive metadata");
                    assertion.accept(plan);
                }
            } finally {
                service.reload(originalOrder);
            }
        }

        private static void assertEveryAlgorithmPlans(
                FakeGrid grid,
                FakePattern consumer,
                FakePattern first,
                FakePattern second
        ) {
            var service = PlanningAlgorithmOrderService.global();
            List<String> originalOrder = service.snapshot().order();
            try {
                for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                    moveFirst(algorithm);
                    ICraftingPlan plan = new OrderedPlanningCalculation(
                            null, grid, grid.requester, new GenericStack(TOP, 8L),
                            CalculationStrategy.REPORT_MISSING_ITEMS).run();
                    assertFalse(plan.simulation(), algorithm + " must calculate the recursive ring");
                    assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                    assertInstanceOf(RecursivePlanContract.class, plan, algorithm + " must preserve recursive metadata");
                    assertEquals(8L, plan.patternTimes().get(consumer));
                    assertTrue(plan.patternTimes().getOrDefault(first, 0L) > 0L);
                    assertTrue(plan.patternTimes().getOrDefault(second, 0L) > 0L);
                }
            } finally {
                service.reload(originalOrder);
            }
        }

        private static void moveFirst(PlanningAlgorithmId first) {
            var service = PlanningAlgorithmOrderService.global();
            // 服务侧的序现在是串名册：按枚举个名取名册形状，再把要置顶的那档挪到首位（顺位不许被 Set 打散）。
            var order = new ArrayList<>(java.util.Arrays.stream(PlanningAlgorithmId.values())
                    .map(Enum::name)
                    .toList());
            order.remove(first.name());
            order.addFirst(first.name());
            service.reload(order);
        }

        /**
         * 实机形态：请求量把某键需求顶过 long 上限（{@code ae2:creative_storage_cell x 1000000}，
         * {@code lava demand=1.6e19}）。旧行为是守恒闸门判"不闭合"→ 递归规划被拒 → 一路落到
         * NATIVE 也拒 → {@code IllegalStateException: Terminal planning algorithm declined: NATIVE}，
         * 连计划页面都进不去。用户口径的预期：<b>如实报缺料、计划照出</b>。
         */
        void demandBeyondLongRangeReportsMissingInsteadOfDeclining() {
            var producer = FakePattern.of(TOP, 1L, new FakeInput(B, 3_000_000_000_000L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B, 1000L))
                    .pattern(TOP, producer)
                    .build();
            var stock = new KeyCounter();
            stock.add(B, 1000L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 10_000_000L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, "需求超过 long 上限也要有计划可交");
            // 精确数字取决于哪一步先窄化，口径只看一件事：缺料按"装得下的最大量"如实报出来，
            // 而不是整条规划被拒。
            assertTrue(plan.missingItems().get(B) > Long.MAX_VALUE / 2,
                    () -> "缺料该报到 long 天花板量级, missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        }

        /**
         * 预算豁免这条腿（会话层 {@code BudgetPolicy.EXEMPT}）此前<b>零用例覆盖</b>：用例侧的预算
         * 一律传 0＝不计费，"环场景再解一趟免预算"这件事从来没被量到过。单求解器迁移把
         * {@code solveLedger} 收敛成直调 LinearPlanner，这一趟只是"顺带还在"，没有任何一条用例
         * 证明它还在——摘掉它的后果是实机默认 10 秒预算把环场景第二趟顶成 budgetExhausted ⇒
         * 落原生树 ⇒「递归样板卡死」那一族症状复现。
         *
         * <p>这里把会话层的两趟摆在一起量，两条前条件都自证：
         * <ul>
         * <li>先把共享预算真的耗干（轮询 {@code exhausted()}，不用固定 sleep），
         * {@code ENFORCED} 那一趟必须交回 {@code budgetExhausted}——顶住的位置必须是求解段；</li>
         * <li>{@code EXEMPT} 那一趟必须交出<b>精确解</b>：handled、不是 simulation、终产物量＝请求量、
         * 一个键都不缺。豁免一旦被顺手收掉，这一格立刻变成 budgetExhausted 而红。</li>
         * </ul>
         * 图用的是多种子起步那一型（{@code CycleKind.COMPRESSIBLE}），正是实机那一族症状的形状。
         */
        void exhaustedBudgetStillYieldsExactRingPlanThroughTheExemptAttempt() {
            var intermediate = AEItemKey.of(Items.IRON_INGOT);
            var output = AEItemKey.of(Items.DIAMOND);
            var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(intermediate, 1L)),
                    new FakeInput(A, 1L), new FakeInput(B, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                            new GenericStack(output, 1L)),
                    new FakeInput(intermediate, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, B, 1L))
                    .pattern(A, recycler)
                    .pattern(B, recycler)
                    .pattern(intermediate, starter)
                    .pattern(output, recycler)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(A, 1L);
            stock.add(B, 1L);
            var budget = new GraphPlanningBudget(1L);
            while (!budget.exhausted()) {
                Thread.onSpinWait();
            }
            var session = GraphPlanningSession.create(grid.crafting, stock, TOP,
                    CalculationStrategy.REPORT_MISSING_ITEMS, budget, null, true, null, grid);
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, session.graph().cycleKind(),
                    "前条件：这张图确实成环，走的才是环场景那一趟");

            var enforced = session.solve(8L);
            assertFalse(enforced.handled(), "前条件：预算已耗尽，常规那一趟必须被顶住");
            assertEquals(GraphPlanningBudget.Phase.SOLVE, enforced.budgetExhaustedPhase(),
                    () -> "顶住的位置必须是求解段, 实得=" + enforced.budgetExhaustedPhase());

            var exempt = session.solve(8L, GraphPlanningSession.BudgetPolicy.EXEMPT);
            assertTrue(exempt.handled(), () -> "免预算那一趟必须跑得完, 判决=" + exempt.declineReason());
            ICraftingPlan plan = exempt.plan();
            assertNotNull(plan);
            assertFalse(plan.simulation(), "交出的必须是精确解，不是模拟计划");
            assertEquals(8L, plan.finalOutput().amount(), "终产物量逐字等于请求量");
            assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(),
                    () -> "一个键都不许缺: " + FakePlanGrid.toPlain(plan.missingItems()));
        }

        /**
         * 上面那条量的是<b>会话层</b>两趟，绕开了生产触发口：把 {@code RecursivePlanningKernel.solve}
         * 里那条免预算判定整条摘掉，门禁仍全绿——而玩家真正走的正是 kernel 那一趟
         * （{@code OrderedPlanningCalculation} 的 GRAPH 与 NATIVE 两档都经它）。
         *
         * <p>这条把入口换成 kernel：预算按生产口径交给 kernel（{@code budgetMillis=1L}，
         * 会话内共享），先把图编译出来（{@code session()}）再把墙钟耗干，然后调
         * {@code solveRecursive()}。前条件是真前条件：{@code planOnce} 进 SOLVE 段第一件事就是
         * {@code budget.check()}，墙钟只向前走，所以预算既已耗尽 ⇒ 计预算那一趟必然顶在 SOLVE 段
         * 交回 {@code budgetExhausted}；此时还能交出精确解的，只可能是免预算那一趟。
         * 摘掉那条判定，这里立刻变成 {@code plan == null}（落原生树＝「递归样板卡死」的形状）。
         */
        void exhaustedBudgetStillYieldsExactRingPlanThroughTheProductionKernelEntry() {
            var intermediate = AEItemKey.of(Items.IRON_INGOT);
            var output = AEItemKey.of(Items.DIAMOND);
            var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(intermediate, 1L)),
                    new FakeInput(A, 1L), new FakeInput(B, 1L));
            var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                            new GenericStack(output, 1L)),
                    new FakeInput(intermediate, 1L));
            var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A, 1L, B, 1L))
                    .pattern(A, recycler)
                    .pattern(B, recycler)
                    .pattern(intermediate, starter)
                    .pattern(output, recycler)
                    .pattern(TOP, consumer)
                    .build();
            var stock = new KeyCounter();
            stock.add(A, 1L);
            stock.add(B, 1L);
            var kernel = new RecursivePlanningKernel(
                    null, grid, grid.requester, new GenericStack(TOP, 8L),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);
            var session = kernel.session();
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, kernel.graphCycleKind(),
                    "前条件：这张图确实成环，kernel 的环臂才可能触发免预算那一趟");
            while (!session.budget().exhausted()) {
                Thread.onSpinWait();
            }
            assertTrue(session.budget().exhausted(),
                    "前条件：kernel 交给会话的共享预算必须已耗尽，计预算那一趟必然被顶住");

            ICraftingPlan plan = kernel.solveRecursive();

            assertNotNull(plan, () -> "环 + 预算耗尽必须仍由免预算那一趟交出计划, kernel 判决落空"
                    + "（这正是落原生树、递归样板卡死的那一型）");
            assertFalse(plan.simulation(), "交出的必须是精确解，不是模拟计划");
            assertEquals(8L, plan.finalOutput().amount(), "终产物量逐字等于请求量");
            assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(),
                    () -> "一个键都不许缺: " + FakePlanGrid.toPlain(plan.missingItems()));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmUsesSharedRecursiveKernel", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmUsesSharedRecursiveKernel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.retentionOnlyReseedsCatalystsExtractedFromNetwork", () -> new OrderedPlanningRecursiveCapabilityCases().retentionOnlyReseedsCatalystsExtractedFromNetwork()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.retentionPreservesCompleteMultiSeedStartupSet", () -> new OrderedPlanningRecursiveCapabilityCases().retentionPreservesCompleteMultiSeedStartupSet()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.retentionRefiresRingWhenPlanEatsTheOnlySeed", () -> new OrderedPlanningRecursiveCapabilityCases().retentionRefiresRingWhenPlanEatsTheOnlySeed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.unsourcableSeedMaterialIsPlannedInAndReportedMissing", () -> new OrderedPlanningRecursiveCapabilityCases().unsourcableSeedMaterialIsPlannedInAndReportedMissing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.seedMaterialsAreReservedBeforeTheMainPlanSpendsThem", () -> new OrderedPlanningRecursiveCapabilityCases().seedMaterialsAreReservedBeforeTheMainPlanSpendsThem()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.ringStartupSeedIsRepaidByOneMoreCycle", () -> new OrderedPlanningRecursiveCapabilityCases().ringStartupSeedIsRepaidByOneMoreCycle()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.ringSeedBorrowedByOtherEntryIsRepaidToNetwork", () -> new OrderedPlanningRecursiveCapabilityCases().ringSeedBorrowedByOtherEntryIsRepaidToNetwork()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.retainedSeedSurvivesWhenRingIsEnteredByBothKeys", () -> new OrderedPlanningRecursiveCapabilityCases().retainedSeedSurvivesWhenRingIsEnteredByBothKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmHandlesCrossPatternCatalyst", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmHandlesCrossPatternCatalyst()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmPreservesContractForFiniteUnsupportedCycle", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmPreservesContractForFiniteUnsupportedCycle()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.recursiveKernelRetriesWithoutExpiredOptimizationBudget", () -> new OrderedPlanningRecursiveCapabilityCases().recursiveKernelRetriesWithoutExpiredOptimizationBudget()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.externallyDetectedRecursivePathRetriesWhenGraphClassificationIsNone", () -> new OrderedPlanningRecursiveCapabilityCases().externallyDetectedRecursivePathRetriesWhenGraphClassificationIsNone()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmHandlesNonUniformRingRatio", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmHandlesNonUniformRingRatio()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmHandlesNestedRings", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmHandlesNestedRings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.everyConfiguredAlgorithmHandlesMultiOutputRing", () -> new OrderedPlanningRecursiveCapabilityCases().everyConfiguredAlgorithmHandlesMultiOutputRing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.demandBeyondLongRangeReportsMissingInsteadOfDeclining", () -> new OrderedPlanningRecursiveCapabilityCases().demandBeyondLongRangeReportsMissingInsteadOfDeclining()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.exhaustedBudgetStillYieldsExactRingPlanThroughTheExemptAttempt", () -> new OrderedPlanningRecursiveCapabilityCases().exhaustedBudgetStillYieldsExactRingPlanThroughTheExemptAttempt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningRecursiveCapabilityCases.exhaustedBudgetStillYieldsExactRingPlanThroughTheProductionKernelEntry", () -> new OrderedPlanningRecursiveCapabilityCases().exhaustedBudgetStillYieldsExactRingPlanThroughTheProductionKernelEntry())
            );
        }
    }

    /** OrderedPlanningSelectorCases 的独立场景夹具，按类别统一执行。 */
    public static final class OrderedPlanningSelectorCases {
        void configuredOrderControlsFirstSuccessfulAttempt() {
            List<PlanningAlgorithmId> visited = new java.util.ArrayList<>();

            var selected = OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                    algorithm -> true,
                    algorithm -> {
                        visited.add(algorithm);
                        return algorithm == PlanningAlgorithmId.GRAPH ? "graph-plan" : null;
                    });

            assertEquals(List.of(PlanningAlgorithmId.GRAPH), visited);
            assertEquals(PlanningAlgorithmId.GRAPH, selected.algorithm());
            assertEquals("graph-plan", selected.value());
        }

        /**
         * 判据（删 FAST 档）：默认序就是「图 + 原生树」，图那一档 DECLINE（交回 null）之后
         * 直接落到原生树——序里没有任何中间算法档，期望写死。
         */
        void graphDeclineFallsStraightThroughToNativeTreeInDefaultOrder() {
            List<PlanningAlgorithmId> tried = new java.util.ArrayList<>();

            var selected = OrderedPlanningSelector.select(
                    // 配置侧交回的是串名册；顺位由这里原样 parse 成枚举，一名对一枚、不借 Set 也不重排。
                    git.chexson.chexsonsaeutils.config.PlanningServerConfig.DEFAULT_ORDER.stream()
                            .map(PlanningAlgorithmId::parse)
                            .flatMap(java.util.Optional::stream)
                            .toList(),
                    algorithm -> true,
                    algorithm -> {
                        tried.add(algorithm);
                        return algorithm == PlanningAlgorithmId.NATIVE ? "native-plan" : null;
                    });

            assertEquals(List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE), tried);
            assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());
            assertEquals("native-plan", selected.value());
        }

        void ineligibleAndRecoverableFailuresContinue() {
            // 可恢复失败那一腿：图这一档抛异常（非终点档）⇒ 继续往下试，由终点档交回计划。
            List<PlanningAlgorithmId> visited = new java.util.ArrayList<>();

            var selected = OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                    algorithm -> true,
                    algorithm -> {
                        visited.add(algorithm);
                        if (algorithm == PlanningAlgorithmId.GRAPH) {
                            throw new IllegalStateException("decline");
                        }
                        return algorithm == PlanningAlgorithmId.NATIVE ? "native-plan" : null;
                    });

            assertEquals(List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE), visited);
            assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());

            // 不许入围那一腿：图被 eligibility 挡在门外 ⇒ 一次都不试，直接由终点档交回。
            List<PlanningAlgorithmId> tried = new java.util.ArrayList<>();
            var fellThrough = OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                    algorithm -> algorithm != PlanningAlgorithmId.GRAPH,
                    algorithm -> {
                        tried.add(algorithm);
                        return "native-plan";
                    });

            assertEquals(List.of(PlanningAlgorithmId.NATIVE), tried);
            assertEquals(PlanningAlgorithmId.NATIVE, fellThrough.algorithm());
        }

        void successfulTerminalStopsLaterEntries() {
            AtomicInteger attempts = new AtomicInteger();

            var selected = OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.NATIVE, PlanningAlgorithmId.GRAPH),
                    algorithm -> true,
                    algorithm -> {
                        attempts.incrementAndGet();
                        return "plan";
                    });

            assertEquals(PlanningAlgorithmId.NATIVE, selected.algorithm());
            assertEquals(1, attempts.get());
        }

        void terminalFailureDoesNotFallThrough() {
            AtomicInteger attempts = new AtomicInteger();

            assertThrows(IllegalArgumentException.class, () -> OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.NATIVE, PlanningAlgorithmId.GRAPH),
                    algorithm -> true,
                    algorithm -> {
                        attempts.incrementAndGet();
                        throw new IllegalArgumentException("native failed");
                    }));
            assertEquals(1, attempts.get());
        }

        void cancellationAlwaysPropagates() {
            assertThrows(CancellationException.class, () -> OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                    algorithm -> true,
                    algorithm -> {
                        throw new CancellationException("cancelled");
                    }));
        }

        void emptyEligibleSetReturnsNull() {
            assertNull(OrderedPlanningSelector.select(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE),
                    algorithm -> false,
                    algorithm -> "unreachable"));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.configuredOrderControlsFirstSuccessfulAttempt", () -> new OrderedPlanningSelectorCases().configuredOrderControlsFirstSuccessfulAttempt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.graphDeclineFallsStraightThroughToNativeTreeInDefaultOrder", () -> new OrderedPlanningSelectorCases().graphDeclineFallsStraightThroughToNativeTreeInDefaultOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.ineligibleAndRecoverableFailuresContinue", () -> new OrderedPlanningSelectorCases().ineligibleAndRecoverableFailuresContinue()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.successfulTerminalStopsLaterEntries", () -> new OrderedPlanningSelectorCases().successfulTerminalStopsLaterEntries()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.terminalFailureDoesNotFallThrough", () -> new OrderedPlanningSelectorCases().terminalFailureDoesNotFallThrough()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.cancellationAlwaysPropagates", () -> new OrderedPlanningSelectorCases().cancellationAlwaysPropagates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("OrderedPlanningSelectorCases.emptyEligibleSetReturnsNull", () -> new OrderedPlanningSelectorCases().emptyEligibleSetReturnsNull())
            );
        }
    }

    /** PartialPlanMissingSummaryCases 的独立场景夹具，按类别统一执行。 */
    public static final class PartialPlanMissingSummaryCases {
        private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEKey REDSTONE = AEItemKey.of(Items.REDSTONE);

        private static CraftingPlanSummaryEntry entry(AEKey key, long missing, long stored, long craft) {
            return new CraftingPlanSummaryEntry(key, missing, stored, craft);
        }

        private static CraftingPlanSummary summary(List<CraftingPlanSummaryEntry> entries) {
            return new CraftingPlanSummary(1L, true, entries);
        }

        private static StubPlan plan(boolean simulation, long declaredMissing) {
            StubPlan plan = new StubPlan();
            plan.simulation = simulation;
            if (declaredMissing > 0L) {
                plan.missing.add(GLASS, declaredMissing);
            }
            return plan;
        }

        void completePlansAreLeftAlone() {
            CraftingPlanSummary summary = summary(List.of(entry(GLASS, 0L, 100L, 5L)));
            assertSame(summary, PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(false, 7L)),
                    "非残缺计划一律不碰");
        }

        void declaredShortfallMovesOutOfStoredIntoMissing() {
            CraftingPlanSummary summary = summary(List.of(
                    entry(GLASS, 0L, 1_007L, 5L),
                    entry(REDSTONE, 0L, 20L, 3L)));

            CraftingPlanSummary fixed = PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(true, 7L));

            CraftingPlanSummaryEntry glass = fixed.getEntries().stream()
                    .filter(e -> e.getWhat().equals(GLASS)).findFirst().orElseThrow();
            assertEquals(7L, glass.getMissingAmount(), "计划申报的缺口要上屏");
            assertEquals(1_000L, glass.getStoredAmount(), "挪出缺口后剩下的才是可用数量");
            CraftingPlanSummaryEntry redstone = fixed.getEntries().stream()
                    .filter(e -> e.getWhat().equals(REDSTONE)).findFirst().orElseThrow();
            assertEquals(0L, redstone.getMissingAmount(), "没申报缺口的键不该多出缺料行");
            assertEquals(20L, redstone.getStoredAmount());
            assertEquals(1L, fixed.getUsedBytes(), "字节与 simulation 标记照抄原概要");
            assertEquals(true, fixed.isSimulation());
        }

        void missingAlreadyComputedByAe2IsNeverDoubleCounted() {
            CraftingPlanSummary summary = summary(List.of(entry(GLASS, 3L, 1_000L, 5L)));

            assertSame(summary, PartialPlanMissingSummary.revealDeclaredMissing(summary, plan(true, 7L)),
                    "AE2 已经算出缺口的条目不参与还原");
        }

        void saturatedShortfallStillShowsAsMissing() {
            // 超 long 的缺口经 Ae2Amounts.toLong 饱和成 Long.MAX：那一格可用数量本就无从还原，
            // 真值由确认屏的宽域改写负责，这里只保证缺料行出现、不出现负数。
            CraftingPlanSummary summary = summary(List.of(entry(GLASS, 0L, Long.MAX_VALUE, 5L)));

            CraftingPlanSummaryEntry glass = PartialPlanMissingSummary
                    .revealDeclaredMissing(summary, plan(true, Long.MAX_VALUE)).getEntries().get(0);

            assertEquals(Long.MAX_VALUE, glass.getMissingAmount());
            assertEquals(0L, glass.getStoredAmount());
        }

        /** AE2 的 CraftingPlan 桩：只给本用例要读的 simulation 与缺料账。 */
        private static final class StubPlan implements ICraftingPlan {

            private final KeyCounter missing = new KeyCounter();
            private boolean simulation;

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return simulation;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return missing;
            }

            @Override
            public KeyCounter emittedItems() {
                return missing;
            }

            @Override
            public KeyCounter missingItems() {
                return missing;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PartialPlanMissingSummaryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialPlanMissingSummaryCases.completePlansAreLeftAlone", () -> new PartialPlanMissingSummaryCases().completePlansAreLeftAlone()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialPlanMissingSummaryCases.declaredShortfallMovesOutOfStoredIntoMissing", () -> new PartialPlanMissingSummaryCases().declaredShortfallMovesOutOfStoredIntoMissing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialPlanMissingSummaryCases.missingAlreadyComputedByAe2IsNeverDoubleCounted", () -> new PartialPlanMissingSummaryCases().missingAlreadyComputedByAe2IsNeverDoubleCounted()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialPlanMissingSummaryCases.saturatedShortfallStillShowsAsMissing", () -> new PartialPlanMissingSummaryCases().saturatedShortfallStillShowsAsMissing())
            );
        }
    }

    /** PlanGrossAuditWideViewCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGrossAuditWideViewCases {
        private static final AEItemKey GLASS = AEItemKey.of(net.minecraft.world.item.Items.GLASS);
        private static final AEItemKey SAND = AEItemKey.of(net.minecraft.world.item.Items.SAND);
        private static final AEItemKey PRODUCT = AEItemKey.of(net.minecraft.world.item.Items.CLOCK);

        /** 越出 {@code long} 的发数：单张就顶格，两张在窄域里永远是同一个天花板。 */
        private static final BigInteger WIDE = new BigInteger("16000000000000000000");
        private static final BigInteger TWO_WIDE = WIDE.add(WIDE);

        /**
         * 两张样板各吃同一枚键、各自顶满天花板：真缺 {@code 1.6e19}，而窄读数两侧同为 {@code MAX} ⇒ 报 {@code ok}。
         * 宽账尺下必须报出真缺口，并把 {@code need} 的越界真值原样印出来（不是 9223372036854775807）。
         */
        void twoCeilingConsumersOfOneKeyReportTheTrueHoleNotOk() {
            var glassMaker = FakePattern.of(GLASS, 1L);
            var consumerA = FakePattern.of(PRODUCT, 1L, FakeInput.stack(GLASS, 1L));
            var consumerB = FakePattern.of(SAND, 1L, FakeInput.stack(GLASS, 1L));
            var crafts = new WideCraftCounter();
            crafts.add(glassMaker, PlanAmount.of(WIDE));
            crafts.add(consumerA, PlanAmount.of(WIDE));
            crafts.add(consumerB, PlanAmount.of(WIDE));

            var balances = PlanGrossAudit.measure(crafts, null);
            var line = PlanGrossAudit.describe(balances, null, null, null);

            assertFalse(line.contains("gross=[ok"),
                    () -> "两侧各顶格再相减就是 0——ok 这一支正是这条毛账在撒谎的那格：" + line);
            assertTrue(line.contains("view=wide"), () -> "读的是宽账就得自报 view=wide：" + line);
            assertEquals(1, PlanGrossAudit.holes(balances).size(), () -> line);
            assertEquals(GLASS, PlanGrossAudit.holes(balances).get(0).getKey(), () -> line);
            assertTrue(line.contains("hole=16000000000000000000"), () -> "缺口要印 BigInteger 原文：" + line);
            assertTrue(line.contains("need=32000000000000000000"), () -> "越界的真需求要印真值：" + line);
            assertTrue(line.contains("supply=16000000000000000000"), () -> line);
            assertFalse(line.contains(String.valueOf(Long.MAX_VALUE)),
                    () -> "顶格哨兵不许被当成量印出来：" + line);
        }

        /** 同一把宽尺下真配平的那一型：仍然如实报 ok，并且照样自报 view=wide（不靠夸大缺口来"看着像修好了"）。 */
        void balancedWideAuditStillSaysOkButNamesItsRuler() {
            var glassMaker = FakePattern.of(GLASS, 1L);
            var consumerA = FakePattern.of(PRODUCT, 1L, FakeInput.stack(GLASS, 1L));
            var consumerB = FakePattern.of(SAND, 1L, FakeInput.stack(GLASS, 1L));
            var crafts = new WideCraftCounter();
            crafts.add(glassMaker, PlanAmount.of(TWO_WIDE));
            crafts.add(consumerA, PlanAmount.of(WIDE));
            crafts.add(consumerB, PlanAmount.of(WIDE));

            var line = PlanGrossAudit.describe(PlanGrossAudit.measure(crafts, null), null, null, null);

            assertTrue(line.startsWith("gross=[ok keys="), () -> "供给够付两张各 1.6e19 的需求 ⇒ ok：" + line);
            assertTrue(line.contains("view=wide"), () -> line);
            assertTrue(line.contains("firings=64000000000000000000"),
                    () -> "发数合计也是宽域真值（3 张：3.2e19+1.6e19+1.6e19）：" + line);
        }

        /**
         * 只读得到 AE2 那份 {@code long} 的时候（{@code patternTimes} 字典、没有账载体）：
         * 那一行如实标 {@code view=narrow}，而 {@code KeyCounter} 里恰好顶格的读数印成 {@code -1} 标记，
         * 不再以 {@code 9223372036854775807} 的形态被当成量。
         */
        void narrowViewIsNamedAndTheCeilingReadingIsMarkedNotPrintedAsAnAmount() {
            var consumer = FakePattern.of(PRODUCT, 1L, FakeInput.stack(GLASS, 10L));
            var balances = PlanGrossAudit.measure(
                    Map.<IPatternDetails, Long>of(consumer, 1L), null);
            var missingItems = new KeyCounter();
            missingItems.add(GLASS, Long.MAX_VALUE);

            var line = PlanGrossAudit.describe(balances, null, null, missingItems);

            assertTrue(line.contains("view=narrow"),
                    () -> "只读到 AE2 那份 long 就得如实标 narrow：" + line);
            assertTrue(line.contains(" miss=-1"),
                    () -> "顶格的缺料读数要印成可辨认的标记：" + line);
            assertFalse(line.contains("9223372036854775807"),
                    () -> "天花板不许被当成量印出去：" + line);
        }

        /**
         * 规划出口那一行（{@link PlanGrossAudit#gross}）走的必须是计划对象<b>自带</b>的宽域读口：
         * AE2 那份 {@code patternTimes} 三张全是 {@code MAX} 的代表值，宽域快照里才是真发数——
         * 拿到真值才能报出真缺口，拿不到才如实 {@code view=narrow}。
         */
        void grossReadsThePlansOwnWideLedgers() {
            var glassMaker = FakePattern.of(GLASS, 1L);
            var consumerA = FakePattern.of(PRODUCT, 1L, FakeInput.stack(GLASS, 1L));
            var consumerB = FakePattern.of(SAND, 1L, FakeInput.stack(GLASS, 1L));
            // AE2 那份字典：三张都只报得出天花板
            Map<IPatternDetails, Long> saturatedTimes = new java.util.LinkedHashMap<>();
            saturatedTimes.put(glassMaker, Long.MAX_VALUE);
            saturatedTimes.put(consumerA, Long.MAX_VALUE);
            saturatedTimes.put(consumerB, Long.MAX_VALUE);
            // 计划对象自带的宽域份数账：真发数 1.6e19
            var crafts = new WideCraftCounter();
            crafts.add(glassMaker, PlanAmount.of(WIDE));
            crafts.add(consumerA, PlanAmount.of(WIDE));
            crafts.add(consumerB, PlanAmount.of(WIDE));
            var missing = new WideKeyCounter();
            var used = new WideKeyCounter();
            var emitted = new WideKeyCounter();
            var delegate = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), saturatedTimes);
            var plan = new WideAmountCraftingPlan(delegate,
                    WideLedgerSnapshot.capture(missing, used, emitted),
                    WideCraftSnapshot.capture(crafts), java.util.Set.of());

            var line = PlanGrossAudit.gross(plan);

            assertTrue(line.contains("view=wide"),
                    () -> "计划对象带得出资账 ⇒ 必须走现成的宽域读口：" + line);
            assertTrue(line.contains("hole=16000000000000000000"),
                    () -> "真缺口要从宽域份数里算出来，不是三张 MAX 相减的 0：" + line);
            assertFalse(line.contains("gross=[ok"), () -> line);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGrossAuditWideViewCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGrossAuditWideViewCases.twoCeilingConsumersOfOneKeyReportTheTrueHoleNotOk", () -> new PlanGrossAuditWideViewCases().twoCeilingConsumersOfOneKeyReportTheTrueHoleNotOk()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGrossAuditWideViewCases.balancedWideAuditStillSaysOkButNamesItsRuler", () -> new PlanGrossAuditWideViewCases().balancedWideAuditStillSaysOkButNamesItsRuler()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGrossAuditWideViewCases.narrowViewIsNamedAndTheCeilingReadingIsMarkedNotPrintedAsAnAmount", () -> new PlanGrossAuditWideViewCases().narrowViewIsNamedAndTheCeilingReadingIsMarkedNotPrintedAsAnAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGrossAuditWideViewCases.grossReadsThePlansOwnWideLedgers", () -> new PlanGrossAuditWideViewCases().grossReadsThePlansOwnWideLedgers())
            );
        }
    }

    /** PlanningLevelRouterCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanningLevelRouterCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

        private static FakeGrid plainGridWithoutParallelCpu() {
            return FakeGrid.builder()
                    .storage(Map.of(GOLD, 100L))
                    .pattern(IRON, FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L)))
                    .build();
        }

        void nullGridNeverTakesOurAlgorithms() {
            assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(null), "无网格不该进 graph 路径");
        }

        void plainGridStaysOnNativePath() {
            FakeGrid grid = plainGridWithoutParallelCpu();
            assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid),
                    "普通/原生网格（无自有并行 CPU）不进 graph 路径，任何配置都开不了这个口");
        }

        void gridHostedForeignOwnerStaysOnNativePath() {
            FakeGrid grid = plainGridWithoutParallelCpu();
            // 宿主存在但未提供本模组的并行 CPU 能力。
            grid.node.owner = new Object();
            assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "外来宿主网格不进 graph 路径");
        }

        void activeParallelCpuProviderEnablesGraphPlanning() {
            FakeGrid grid = plainGridWithoutParallelCpu();
            grid.node.owner = (ParallelCpuProvider) () -> true;
            assertTrue(PlanningLevelRouter.shouldUseGraphPlanning(grid), "活跃并行 CPU 应启用图规划");
        }

        void inactiveParallelCpuProviderStaysOnNativePath() {
            FakeGrid grid = plainGridWithoutParallelCpu();
            grid.node.owner = (ParallelCpuProvider) () -> false;
            assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "非活跃并行 CPU 不启用图规划");
        }

        void emptyGridStaysOnNativePath() {
            assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(
                    new NodeGrid(plainGridWithoutParallelCpu(), List.of())), "空网格不启用图规划");
        }

        void graphPlanningDisabledStaysOnNativePathEvenWithActiveProvider() {
            assertTrue(ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded(), "本用例验证已加载配置轨");
            FakeGrid grid = plainGridWithoutParallelCpu();
            grid.node.owner = (ParallelCpuProvider) () -> true;
            var setting = ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_ENABLED;
            boolean previous = setting.get();
            try {
                setting.set(false);
                assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "图规划总开关仍控制能力入口");
            } finally {
                setting.set(previous);
            }
        }

        void parallelCpuDisabledStaysOnNativePathEvenWithActiveProvider() {
            assertTrue(ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded(), "本用例验证已加载配置轨");
            FakeGrid grid = plainGridWithoutParallelCpu();
            grid.node.owner = (ParallelCpuProvider) () -> true;
            var setting = ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED;
            boolean previous = setting.get();
            try {
                setting.set(false);
                assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "并行 CPU 总开关仍控制能力入口");
            } finally {
                setting.set(previous);
            }
        }

        void nodeTraversalFailureLogsCauseAndLaterProbeRecovers() {
            FakeGrid grid = plainGridWithoutParallelCpu();
            grid.node.owner = (ParallelCpuProvider) () -> true;
            var failure = new IllegalStateException("网格节点暂时不可遍历");
            var attempts = new AtomicInteger();
            var failingGrid = new NodeGrid(grid, () -> {
                if (attempts.getAndIncrement() == 0) {
                    throw failure;
                }
                return List.<IGridNode>of(grid.node).iterator();
            });

            CapturingAppender captured = captureLines("Parallel CPU capability detection", () ->
                    assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(failingGrid),
                            "探测异常必须回落原生规划"));

            assertEquals(1, captured.lines.size(), "一次探测失败应输出一条诊断");
            assertSame(failure, captured.causes.get(0), "诊断应保留异常原因");
            assertTrue(PlanningLevelRouter.shouldUseGraphPlanning(failingGrid), "节点恢复后应重新识别活跃 CPU");
        }


        private static CapturingAppender captureLines(String marker, Runnable action) {
            var appender = new CapturingAppender("planning-capture-" + System.nanoTime(), marker);
            var context = (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            var configuration = context.getConfiguration();
            appender.start();
            try {
                configuration.addAppender(appender);
                configuration.getRootLogger()
                        .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
                context.updateLoggers();
                action.run();
            } finally {
                configuration.getRootLogger().removeAppender(appender.getName());
                context.updateLoggers();
                appender.stop();
            }
            return appender;
        }

        /** 保留既有网格行为，仅注入节点集合以验证空网格和遍历异常。 */
        private record NodeGrid(FakeGrid delegate, Iterable<IGridNode> nodes) implements IGrid {

            @Override
            public <C extends IGridService> C getService(Class<C> iface) {
                return delegate.getService(iface);
            }

            @Override
            public <T extends GridEvent> T postEvent(T event) {
                return delegate.postEvent(event);
            }

            @Override
            public Iterable<Class<?>> getMachineClasses() {
                return delegate.getMachineClasses();
            }

            @Override
            public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) {
                return delegate.getMachineNodes(machineClass);
            }

            @Override
            public <T> Set<T> getMachines(Class<T> machineClass) {
                return delegate.getMachines(machineClass);
            }

            @Override
            public <T> Set<T> getActiveMachines(Class<T> machineClass) {
                return delegate.getActiveMachines(machineClass);
            }

            @Override
            public Iterable<IGridNode> getNodes() {
                return nodes;
            }

            @Override
            public boolean isEmpty() {
                return !nodes.iterator().hasNext();
            }

            @Override
            public IGridNode getPivot() {
                return nodes.iterator().next();
            }

            @Override
            public int size() {
                int count = 0;
                for (IGridNode ignored : nodes) {
                    count++;
                }
                return count;
            }

            @Override
            public void export(com.google.gson.stream.JsonWriter writer) {
                delegate.export(writer);
            }
        }

        private static final class CapturingAppender
                extends org.apache.logging.log4j.core.appender.AbstractAppender {

            private final String marker;

            private final List<String> lines = new ArrayList<>();
            private final List<Throwable> causes = new ArrayList<>();

            private CapturingAppender(String name, String marker) {
                super(name, null, org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(),
                        true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
                this.marker = marker;
            }

            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                var message = event.getMessage();
                if (message == null) {
                    return;
                }
                // 只收本次调用的诊断，其他日志不进断言。
                String formatted = message.getFormattedMessage();
                if (formatted.startsWith(marker)) {
                    lines.add(formatted);
                    causes.add(event.getThrown());
                }
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.nullGridNeverTakesOurAlgorithms", () -> new PlanningLevelRouterCases().nullGridNeverTakesOurAlgorithms()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.plainGridStaysOnNativePath", () -> new PlanningLevelRouterCases().plainGridStaysOnNativePath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.gridHostedForeignOwnerStaysOnNativePath", () -> new PlanningLevelRouterCases().gridHostedForeignOwnerStaysOnNativePath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.activeParallelCpuProviderEnablesGraphPlanning", () -> new PlanningLevelRouterCases().activeParallelCpuProviderEnablesGraphPlanning()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.inactiveParallelCpuProviderStaysOnNativePath", () -> new PlanningLevelRouterCases().inactiveParallelCpuProviderStaysOnNativePath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.emptyGridStaysOnNativePath", () -> new PlanningLevelRouterCases().emptyGridStaysOnNativePath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.graphPlanningDisabledStaysOnNativePathEvenWithActiveProvider", () -> new PlanningLevelRouterCases().graphPlanningDisabledStaysOnNativePathEvenWithActiveProvider()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.parallelCpuDisabledStaysOnNativePathEvenWithActiveProvider", () -> new PlanningLevelRouterCases().parallelCpuDisabledStaysOnNativePathEvenWithActiveProvider()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningLevelRouterCases.nodeTraversalFailureLogsCauseAndLaterProbeRecovers", () -> new PlanningLevelRouterCases().nodeTraversalFailureLogsCauseAndLaterProbeRecovers())
            );
        }
    }

    /** PlanningProvenanceCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanningProvenanceCases {
        void resolvesTaggedPlanAndIgnoresValueEqualTwin() {
            StubPlan tagged = new StubPlan();
            StubPlan twin = new StubPlan();
            assertEquals(tagged, twin);

            PlanningProvenance.tag(tagged, PlanningProvenance.GRAPH, System.nanoTime() - 5_000_000L);

            PlanningProvenance.Info info = PlanningProvenance.peek(tagged);
            assertEquals(PlanningProvenance.GRAPH, info.solver());
            assertTrue(info.millis() >= 4L);
            assertNull(PlanningProvenance.peek(twin));
        }

        void peekIsNonDestructive() {
            StubPlan plan = new StubPlan();
            PlanningProvenance.tag(plan, PlanningProvenance.NATIVE, System.nanoTime());

            assertSame(PlanningProvenance.peek(plan), PlanningProvenance.peek(plan));
        }

        void canExtendTimingToPacketDeliveryBoundary() {
            StubPlan plan = new StubPlan();
            long started = System.nanoTime() - 8_000_000L;
            PlanningProvenance.tag(plan, PlanningProvenance.GRAPH, started);

            PlanningProvenance.Info info = PlanningProvenance.peek(plan);
            assertTrue(info.calculationCompletedNanos() >= started);
            assertTrue(info.elapsedMillisAt(info.calculationCompletedNanos() + 4_000_000L) >= 12L);
        }

        /** 图与原生树的来源名称必须使用不同的翻译键。 */
        void solverTranslationKeysDistinguishGraphAndNative() {
            assertEquals("gui.chexsonsaeutils.solver.graph",
                    PlanningProvenance.solverTranslationKey(PlanningProvenance.GRAPH));
            assertEquals("gui.chexsonsaeutils.solver.native",
                    PlanningProvenance.solverTranslationKey(PlanningProvenance.NATIVE));
            assertEquals(2L, java.util.stream.Stream.of(PlanningProvenance.GRAPH, PlanningProvenance.NATIVE)
                    .map(PlanningProvenance::solverTranslationKey)
                    .distinct()
                    .count(), "屏上的求解器读数只剩图/原生两值");
        }

        void recursiveWrapperResolvesThroughDelegate() {
            StubPlan delegate = new StubPlan();
            StubRecursivePlan wrapper = new StubRecursivePlan(delegate);

            PlanningProvenance.tag(wrapper, PlanningProvenance.GRAPH, System.nanoTime());

            assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(delegate).solver());
            assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(wrapper).solver());
        }

        /** Value-equal stub mimicking AE2's {@code CraftingPlan} record. */
        private static class StubPlan implements ICraftingPlan {

            private final KeyCounter used = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return this.used;
            }

            @Override
            public KeyCounter emittedItems() {
                return this.used;
            }

            @Override
            public KeyCounter missingItems() {
                return this.used;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof StubPlan;
            }

            @Override
            public int hashCode() {
                return StubPlan.class.hashCode();
            }
        }

        private static final class StubRecursivePlan extends StubPlan implements RecursivePlanContract {

            private final ICraftingPlan delegate;

            StubRecursivePlan(ICraftingPlan delegate) {
                this.delegate = delegate;
            }

            @Override
            public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
                return true;
            }

            @Override
            public ICraftingPlan chexsonsaeutils$getDelegate() {
                return this.delegate;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof StubRecursivePlan;
            }

            @Override
            public int hashCode() {
                return StubRecursivePlan.class.hashCode();
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases.resolvesTaggedPlanAndIgnoresValueEqualTwin", () -> new PlanningProvenanceCases().resolvesTaggedPlanAndIgnoresValueEqualTwin()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases.peekIsNonDestructive", () -> new PlanningProvenanceCases().peekIsNonDestructive()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases.canExtendTimingToPacketDeliveryBoundary", () -> new PlanningProvenanceCases().canExtendTimingToPacketDeliveryBoundary()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases.solverTranslationKeysDistinguishGraphAndNative", () -> new PlanningProvenanceCases().solverTranslationKeysDistinguishGraphAndNative()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningProvenanceCases.recursiveWrapperResolvesThroughDelegate", () -> new PlanningProvenanceCases().recursiveWrapperResolvesThroughDelegate())
            );
        }
    }

    /** PlanningSupplyWrapAroundSeedCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanningSupplyWrapAroundSeedCases {
        /** 反向臂的键：网络里有真生产者以外的来路——没有生产者，只靠库存。 */
        private static final AEItemKey CELL_KEY = AEItemKey.of(Items.COPPER_INGOT);
        private static final AEItemKey PLAIN_KEY = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);

        private static final long FRAG_WIDE = 5_000_000_000_000_000_000L;
        private static final long WRAPPED_AGGREGATE = -8446744073709551616L;
        private static final BigInteger TRUTH_WIDE = new BigInteger("10000000000000000000");
        private static final long FRAG_PLAIN = 1_000_000_000L;

        /** 前提本身也要有牙：两片各 > Long.MAX/2，AE2 真实聚合（KeyCounter→fastutil addTo）回绕成负数。 */
        void ae2AggregationOfTwoBigFragmentsWrapsNegative() {
            var stock = new KeyCounter();
            stock.add(CELL_KEY, FRAG_WIDE);
            stock.add(CELL_KEY, FRAG_WIDE);
            assertEquals(WRAPPED_AGGREGATE, stock.get(CELL_KEY),
                    "两片各 5e18 经 AE2 普通 long 加法必须回绕成负数（前提，不许随实现漂移）");
        }

        /** 反向臂一：回绕读数的键，规划供给必须读成逐存储宽域真值 1e19。 */
        void wrappedAggregateSeedsWideTruthIntoPlanning() {
            var stock = wrappedStock();
            var grid = gridWithCells(
                    new ReportingCell(CELL_KEY, FRAG_WIDE),
                    new ReportingCell(CELL_KEY, FRAG_WIDE));

            var truth = PlanningSupplyTruth.capture(grid, stock);

            assertEquals(PlanAmount.of(TRUTH_WIDE), truth.supplyOf(CELL_KEY, stock.get(CELL_KEY)),
                    "规划看到的该键供给＝逐存储宽域真值 1e19，不是回绕读数 -8446744073709551616");
            // 图上的供给读数同一把尺
            var graph = graphSeededWith(truth, CELL_KEY, WRAPPED_AGGREGATE, 2L);
            assertEquals(TRUTH_WIDE, graph.supplyAmount(CELL_KEY).exactValue(),
                    "供给种子（graph.supplyAmount）必须吃到宽域真值");
        }

        /**
         * 反向臂二：需求 1.6e19（每发 2 枚 × 8e18 发）越出真值 1e19 的那一截 <b>6e18</b>
         * 必须被缺料账点名上报——这一截没有生产者（图上根本没有它的样板），报数按真值算，
         * 不许拿回绕读数当本数放行（ok keys=），也不许当「整截无料」把真有的 1e19 报成缺口。
         */
        void ghostConsumptionBeyondWideTruthIsNamedAsShortage() {
            var stock = wrappedStock();
            var grid = gridWithCells(
                    new ReportingCell(CELL_KEY, FRAG_WIDE),
                    new ReportingCell(CELL_KEY, FRAG_WIDE));
            var truth = PlanningSupplyTruth.capture(grid, stock);
            var graph = graphSeededWith(truth, CELL_KEY, WRAPPED_AGGREGATE, 2L);

            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS,
                    8_000_000_000_000_000_000L);

            var state = result.state();
            assertNotNull(state, () -> "判决必须带着能点名的缺口账回来: " + result.declineReason());
            assertTrue(result.simulation(), "带缺口的计划不许以正式计划出门（ok keys= 那一形）");
            var shortage = result.shortage();
            var reported = shortage != null && shortage.get(CELL_KEY) != null
                    ? shortage.get(CELL_KEY).exactValue()
                    : state.missing().getExact(CELL_KEY);
            assertEquals(new BigInteger("6000000000000000000"), reported,
                    "幽灵消费＝需求 1.6e19 − 真值 1e19 的那一截必须按真值差额点名上报");
            assertTrue(state.used().getExact(CELL_KEY).compareTo(TRUTH_WIDE) <= 0,
                    () -> "网络消费不许超过逐存储宽域真值 1e19: used=" + state.used().getExact(CELL_KEY));
        }

        /** 正向臂：聚合可信（两片各 1e9，和＝2e9 恰好等于聚合读数）——一切逐字不变。 */
        void plainFragmentsKeepAggregateTrustworthyAndPlanNumbersByteIdentical() {
            var stock = new KeyCounter();
            stock.add(PLAIN_KEY, FRAG_PLAIN);
            stock.add(PLAIN_KEY, FRAG_PLAIN);
            assertEquals(2_000_000_000L, stock.get(PLAIN_KEY));
            var grid = gridWithCells(
                    new ReportingCell(PLAIN_KEY, FRAG_PLAIN),
                    new ReportingCell(PLAIN_KEY, FRAG_PLAIN));

            var truth = PlanningSupplyTruth.capture(grid, stock);

            assertSame(PlanningSupplyTruth.EMPTY, truth,
                    "聚合读数与逐存储和一致：没有键要换尺，快照必须原样为空");

            var graph = graphSeededWith(truth, PLAIN_KEY, 2_000_000_000L, 2L);
            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, 1_000_000_000L);

            assertFalse(result.hasDeclined(), () -> "供给 2e9 恰好盖住需求 2e9，不该拒: "
                    + result.declineReason());
            var state = result.state();
            assertNotNull(state);
            var producer = graph.patternsBySource().values().iterator().next();
            // 下列四个数字取自 HEAD 实跑（#197 正向验收：改后必须逐字相同）
            assertEquals(2_000_000_000L, graph.supplyAmount(PLAIN_KEY).exactValue().longValueExact(),
                    "规划供给＝聚合读数 2000000000，逐字不变");
            assertEquals(1_000_000_000L, (long) state.crafts().get(producer.source()),
                    "份数 1000000000，逐字不变");
            assertEquals(2_000_000_000L, state.used().getExact(PLAIN_KEY).longValueExact(),
                    "用量 2000000000，逐字不变");
            assertTrue(state.missingItems().isEmpty(), "池子里真有 2e9，不许报缺料");
            assertEquals(1_000_000_000L, result.finalAmount(), "交付 1000000000，逐字不变");
        }

        /**
         * 复核点名的那一型（台账 {@code 200}）：聚合读数<b>回绕成负</b>，而逐存储扫描<b>看不见</b>这一枚键
         * ⇒ 种子只能是<b>下界 0</b>：不许把负读数当本数放行，也不许替看不见的那一枚编一个真值。
         *
         * <p>诊断行要把三件事分开报出来，不许混成一句"看不见"：
         * <ul>
         * <li>伸到了（{@code scannedCells>0}）却数不着这一枚（{@code blindKeys=1}）；</li>
         * <li>压根没伸到（{@code scannedCells=0}／{@code tallyKeys=0}）；</li>
         * <li>伸到了而且看得见这一枚（{@code blindKeys=0}）——这一档 {@code blindKeys} 不虚报。</li>
         * </ul>
         * 三行的 {@code scannedCells=}／{@code tallyKeys=}／{@code blindKeys=} 三格必须各自对上真数，
         * 断言吃的是原文格子，不是"没抛异常"。
         */
        void blindSuspectKeySeedsLowerBoundAndDiagnosisSeparatesTheThreeSentences() {
            var stock = wrappedStock();

            // 一、伸到了（一张装 PLAIN 的单元）但账上没有 CELL_KEY 这一枚
            var reached = new PlanningSupplyTruth[1];
            var reachedLine = captureIdleWarnLine(() -> reached[0] = PlanningSupplyTruth.capture(
                    gridWithCells(new ReportingCell(PLAIN_KEY, FRAG_PLAIN)), stock));
            assertEquals(PlanAmount.ZERO, reached[0].supplyOf(CELL_KEY, stock.get(CELL_KEY)),
                    "扫描看不见这一枚键时种子只能是下界 0，不许放行回绕负数");
            assertEquals(BigInteger.ZERO, graphSeededWith(reached[0], CELL_KEY, WRAPPED_AGGREGATE, 2L)
                            .supplyAmount(CELL_KEY).exactValue(),
                    "种进沙盒的供给种子＝0（下界），不是 -8446744073709551616");
            assertEquals(1, reached[0].scannedCellCount(), "这一趟真伸到了一张单元：不是压根没伸到");
            assertEquals(1, reached[0].tallyKeyCount(), "逐存储那本账里只登记了 PLAIN 那一枚");
            assertEquals("scannedCells=1", coverageToken(reachedLine, "scannedCells="),
                    "伸到了就得报 scannedCells>0：这一行说的是「数不着这一枚」");
            assertEquals("tallyKeys=1", coverageToken(reachedLine, "tallyKeys="));
            assertEquals("blindKeys=1", coverageToken(reachedLine, "blindKeys="),
                    "看不见就得报 blindKeys>0：这一行不是「网络真没有」");

            // 二、压根没伸到任何宿主
            var neverReached = new PlanningSupplyTruth[1];
            var neverReachedLine = captureIdleWarnLine(() -> neverReached[0] = PlanningSupplyTruth.capture(
                    FakePlanGrid.FakeGrid.builder().build(), stock));
            assertEquals(PlanAmount.ZERO, neverReached[0].supplyOf(CELL_KEY, stock.get(CELL_KEY)),
                    "没伸到同样是下界 0，方向一致：只少报不多报");
            assertEquals("scannedCells=0", coverageToken(neverReachedLine, "scannedCells="),
                    "压根没伸到就得报 scannedCells=0：与「伸到了但数不着」是两句话");
            assertEquals("tallyKeys=0", coverageToken(neverReachedLine, "tallyKeys="));
            assertEquals("blindKeys=1", coverageToken(neverReachedLine, "blindKeys="));
            assertNotEquals(reachedLine, neverReachedLine,
                    "「扫描看不见」与「压根没伸到」不许打成同一行");

            // 三、伸到了而且看得见这一枚（两张各 5e18 的宽域碎片）：blindKeys 不许虚报
            var covered = captureIdleWarnLine(() -> PlanningSupplyTruth.capture(
                    gridWithCells(new ReportingCell(CELL_KEY, FRAG_WIDE),
                            new ReportingCell(CELL_KEY, FRAG_WIDE)), stock));
            assertEquals("blindKeys=0", coverageToken(covered, "blindKeys="),
                    "账上真有这一枚就不是「看不见」，blindKeys 不许抢报");
            assertEquals("scannedCells=2", coverageToken(covered, "scannedCells="));
        }

        // ---- fixtures ----

        private static KeyCounter wrappedStock() {
            var stock = new KeyCounter();
            stock.add(CELL_KEY, FRAG_WIDE);
            stock.add(CELL_KEY, FRAG_WIDE);
            return stock;
        }

        /** 每张库存一个宿主单元：报量按 AE2 口径逐份如实报，自身不回绕。 */
        private static FakePlanGrid.FakeGrid gridWithCells(StorageCell... cells) {
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.of(cells), true);
            return grid;
        }

        /** 目标 TARGET：每发吃 {@code perCraft} 枚 {@code key}；图上没有该键的任何生产者。 */
        private static CraftGraph graphSeededWith(PlanningSupplyTruth truth, AEKey key,
                long aggregateReading, long perCraft) {
            var producer = new GraphPattern(new FakeDetails(), TARGET, 1,
                    List.of(new GenericStack(TARGET, 1L)),
                    List.of(new GraphInput(key, perCraft, 1L, GraphInput.Mode.NORMAL, null,
                            null, null, false)));
            var graph = new CraftGraph(TARGET,
                    Map.of(TARGET, List.of(producer)),
                    Map.of(producer.source(), producer),
                    List.of(producer.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.<AEKey, Long>of(key, aggregateReading),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
            graph.attachSupplyTruth(truth);
            return graph;
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
                return AEItemKey.of(new ItemStack(Items.CLOCK));
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 驱动器/存储舱宿主：两个取单元口交同一份（网络挂载视图＝无额外过滤）。 */
        private static final class FakeDrive implements IChestOrDrive {
            private final List<StorageCell> cells;
            private final boolean powered;

            private FakeDrive(List<StorageCell> cells, boolean powered) {
                this.cells = cells;
                this.powered = powered;
            }

            @Override
            public int getCellCount() {
                return this.cells.size();
            }

            @Override
            public CellState getCellStatus(int slot) {
                return CellState.NOT_EMPTY;
            }

            @Override
            public boolean isPowered() {
                return this.powered;
            }

            @Override
            public boolean isCellBlinking(int slot) {
                return false;
            }

            @Override
            public @Nullable Item getCellItem(int slot) {
                return Items.AIR;
            }

            @Override
            public @Nullable MEStorage getCellInventory(int slot) {
                return this.cells.get(slot);
            }

            @Override
            public @Nullable StorageCell getOriginalCellInventory(int slot) {
                return this.cells.get(slot);
            }

            @Override
            public @Nullable IGridNode getActionableNode() {
                return null;
            }
        }

        /** 普通存储单元：对网络如实报自己那一份（&gt; long 的盘不在这条腿上——本用例量的是回绕，不是饱和）。 */
        private static final class ReportingCell implements StorageCell {
            private final AEKey key;
            private final long amount;

            private ReportingCell(AEKey key, long amount) {
                this.key = key;
                this.amount = amount;
            }

            @Override
            public void getAvailableStacks(KeyCounter out) {
                out.add(this.key, this.amount);
            }

            @Override
            public long extract(AEKey what, long requested, appeng.api.config.Actionable mode,
                    IActionSource source) {
                return this.key.equals(what) ? Math.min(requested, this.amount) : 0L;
            }

            @Override
            public Component getDescription() {
                return Component.literal("reporting");
            }

            @Override
            public CellState getStatus() {
                return CellState.NOT_EMPTY;
            }

            @Override
            public double getIdleDrain() {
                return 0;
            }

            @Override
            public void persist() {
            }
        }

        /**
         * 收 {@code capture} 那句 idle 警告的原文（覆盖面读数的出口除了这一行没有第二处）：
         * 只在 {@code sources=0} 那一条腿上打，所以三臂都造的是「网络上有嫌疑键、我们一张宽域单元都没有」
         * 或「单元不是我们的」的形状。
         */
        private static String captureIdleWarnLine(Runnable body) {
            var appender = new CapturingAppender();
            var context = (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            var configuration = context.getConfiguration();
            try {
                appender.start();
                configuration.addAppender(appender);
                configuration.getRootLogger()
                        .addAppender(appender, org.apache.logging.log4j.Level.WARN, null);
                context.updateLoggers();
                body.run();
                return appender.lastLine("Graph planning wide supply truth idle:");
            } finally {
                configuration.getRootLogger().removeAppender(appender.getName());
                context.updateLoggers();
                appender.stop();
            }
        }

        /** 按空格切格取回 {@code key=} 那一格原文：诊断行一格一个 token，值里不带空格。 */
        private static String coverageToken(String line, String marker) {
            for (var part : line.split(" ")) {
                if (part.startsWith(marker)) {
                    return part;
                }
            }
            throw new AssertionError("诊断行里没有 " + marker + " 开头的格：" + line);
        }

        /** 只收 WARN 及以上的原文行。 */
        private static final class CapturingAppender
                extends org.apache.logging.log4j.core.appender.AbstractAppender {

            private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

            private CapturingAppender() {
                super("planning-supply-coverage-capture", null,
                        org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
            }

            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                var message = event.getMessage();
                if (message != null) {
                    this.lines.add(message.getFormattedMessage());
                }
            }

            private String lastLine(String prefix) {
                String found = "<no log line captured with prefix " + prefix + ">";
                for (var line : this.lines) {
                    if (line.startsWith(prefix)) {
                        found = line;
                    }
                }
                return found;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases.ae2AggregationOfTwoBigFragmentsWrapsNegative", () -> new PlanningSupplyWrapAroundSeedCases().ae2AggregationOfTwoBigFragmentsWrapsNegative()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases.wrappedAggregateSeedsWideTruthIntoPlanning", () -> new PlanningSupplyWrapAroundSeedCases().wrappedAggregateSeedsWideTruthIntoPlanning()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases.ghostConsumptionBeyondWideTruthIsNamedAsShortage", () -> new PlanningSupplyWrapAroundSeedCases().ghostConsumptionBeyondWideTruthIsNamedAsShortage()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases.plainFragmentsKeepAggregateTrustworthyAndPlanNumbersByteIdentical", () -> new PlanningSupplyWrapAroundSeedCases().plainFragmentsKeepAggregateTrustworthyAndPlanNumbersByteIdentical()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyWrapAroundSeedCases.blindSuspectKeySeedsLowerBoundAndDiagnosisSeparatesTheThreeSentences", () -> new PlanningSupplyWrapAroundSeedCases().blindSuspectKeySeedsLowerBoundAndDiagnosisSeparatesTheThreeSentences())
            );
        }
    }

    /** TerminalShortagePlanCases 的独立场景夹具，按类别统一执行。 */
    public static final class TerminalShortagePlanCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        // 递归环夹具用的三枚键 + 目标件（与 OrderedPlanningRecursiveCapabilityTest 同一形状）
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey A = AEItemKey.of(Items.STICK);
        private static final AEItemKey B = AEItemKey.of(Items.COAL);
        private static final AEItemKey LEAF = AEItemKey.of(Items.REDSTONE);

        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        /**
         * 每发 3e12 枚金 × {@value #FIRINGS} 发 = 9e18 一枚笔，两笔合计 1.8e19 越过 long。
         *
         * <p>单笔刻意留在 long 域内：闸门的需求乘算自 2026-09-23 起按<b>真值</b>算，不再把越界的
         * 单项钉成天花板；本用例锁的是"缺料账读宽域"，不该顺带依赖饱和值。
         */
        private static final long FIRINGS = 3_000_000L;
        /** 该键的总需求：两个站点各 9e18 枚，合计 1.8e19 枚。 */
        private static final BigInteger DEMAND = BigInteger.valueOf(9_000_000_000_000_000_000L).shiftLeft(1);

        /** 闸门判缺料时逐键缺口要记<b>全</b>、记<b>精确</b>：判决文本只点名第一个键，缺口账不能只抄它。 */
        void closureGateCollectsEveryShortKeyBeyondLong() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = goldLedger(first, second, 1_000L);

            var shortage = new LinkedHashMap<AEKey, PlanAmount>();
            String violation = MaterialClosureGate.validate(
                    state, twoPatternGraph(first, second), 6_000_000L, shortage);

            assertNotNull(violation, () -> "宽域缺料盖不住需求就是算料错误，不能放行" + shortage);
            assertEquals(PlanAmount.of(DEMAND.subtract(BigInteger.valueOf(2_000L))), shortage.get(GOLD),
                    "缺口 = 需求 1.8446744e19 − 已记缺料 2000：必须按宽域真值记，不许被 long 截成一枚天花板");
        }

        /** 账配平时那只口袋一个字都不该写：报缺料不是"顺手记一笔"。 */
        void balancedLedgerCollectsNoShortage() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = goldLedger(first, second, Long.MAX_VALUE);

            var shortage = new LinkedHashMap<AEKey, PlanAmount>();
            String violation = MaterialClosureGate.validate(
                    state, twoPatternGraph(first, second), 6_000_000L, shortage);

            assertNull(violation, () -> "两笔天花板缺料已盖住需求，闸门不该拒：" + violation);
            assertTrue(shortage.isEmpty(), () -> "闭合的账不该留下缺口条目：" + shortage);
        }

        /**
         * 用户定的新出口：闸门判决 + 那份被拒的账 → {@code simulation=true} 的残缺计划。
         * AE2 的 {@code KeyCounter} 只能装 {@code long}，交回的那份读数是向上饱和的哨兵；
         * 真值随 {@link WideAmountPlanCarrier} 的缺料账出门，确认屏按真值显示。
         */
        void terminalShortagePlanReportsTheExactGapInsteadOfThrowing() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = goldLedger(first, second, 1_000L);
            var graph = twoPatternGraph(first, second);
            var shortage = new LinkedHashMap<AEKey, PlanAmount>();
            String violation = MaterialClosureGate.validate(state, graph, 6_000_000L, shortage);
            assertNotNull(violation);

            ICraftingPlan plan = GraphPlanPostProcessor.shortagePlan(
                    PlanningAttemptResult.declinedWithLedger(
                            SolveResult.declinedShortage(violation, shortage, state, 6_000_000L)), graph);

            assertNotNull(plan, "终点算法也没辙时要交回一份材料不足回执，而不是一行异常");
            assertTrue(plan.simulation(),
                    "缺料计划必须带 simulation 标记，否则执行侧会照单吞掉一份没有来源的料");
            assertEquals(IRON, plan.finalOutput().what());
            assertEquals(6_000_000L, plan.finalOutput().amount(), "需求一分不许砍");
            assertEquals(Long.MAX_VALUE, plan.missingItems().get(GOLD),
                    "AE2 那份 long 读数按向上饱和报缺料（今日口径）");
            assertEquals(shortage.get(GOLD).exactValue(), WideAmountPlanCarrier.resolveWideLedgers(plan)
                            .entries(WideAmountKind.MISSING).get(GOLD),
                    "确认屏要看得见的真值：闸门逐键算出多少缺口就报多少，>long 那一截不许被截成天花板");
            assertTrue(plan.patternTimes().isEmpty(),
                    "判决是「这份订单做不出来」：回执只交缺料事实，不摆一份跑不通的样板表充数");
        }

        /**
         * 实机 2026-09-22 22:36 交不出回执的确切形状：上一刀把缺口记进<b>那份被判不可行的账本</b>再转换，
         * 而不可行的账本从超支库存出账、出口值带负数，守卫一挡就什么都没有了。
         * 现在回执只依赖闸门逐键缺口——账本坏成什么样（甚至压根没有账本）都不许影响它。
         */
        void shortageReportDoesNotDependOnTheRejectedLedger() {
            var graph = twoPatternGraph(hugeGoldPattern(), hugeGoldPattern());
            var shortage = new LinkedHashMap<AEKey, PlanAmount>();
            shortage.put(GOLD, PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1)));

            var plan = GraphPlanPostProcessor.shortagePlan(PlanningAttemptResult.declinedWithLedger(
                    new SolveResult(null, 6_000_000L, true, "material closure violated: gold", shortage)), graph);

            assertNotNull(plan, "被拒的账本不可用，不是把缺料事实一起吞掉的正当理由");
            assertTrue(plan.simulation());
            assertEquals(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1))
                            .exactValue(),
                    WideAmountPlanCarrier.resolveWideLedgers(plan)
                            .entries(WideAmountKind.MISSING).get(GOLD),
                    ">long 的那一截仍要按真值报出去");
        }

        /** 只认「真缺料」这一类判决：预算耗尽 / 图 overflow / 编译失败一律照旧抛原异常。 */
        void everyOtherDeclineKeepsThrowingPathUntouched() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var graph = twoPatternGraph(first, second);

            assertNull(GraphPlanPostProcessor.shortagePlan(
                    PlanningAttemptResult.declined("graph planning budget exhausted during SOLVE"), graph));
            assertNull(GraphPlanPostProcessor.shortagePlan(
                    PlanningAttemptResult.declined("graph overflow"), graph));
            assertNull(GraphPlanPostProcessor.shortagePlan(null, graph));
            // 判决不带缺口账（模糊槽排水这类）：即使手里有账本也不许替它编一个缺口
            assertNull(GraphPlanPostProcessor.shortagePlan(PlanningAttemptResult.declinedWithLedger(
                    SolveResult.declined("material closure violated on fuzzy slot: unfundedUnits=1")), graph));
        }

        /**
         * 回落链的最后一站照旧会抛：终点算法拿到的是<b>没有缺口账</b>的判决时，新出口交回
         * {@code null}，{@link OrderedPlanningSelector} 仍按
         * {@code Terminal planning algorithm declined: NATIVE} 抛出去——只有真缺料那一条路径改了行为。
         *
         * <p>用"非正请求量"当判决来源只为一个目的：离线拿到一条<b>确定</b>不是守恒闸门判出的判决
         * （预算耗尽这类要看机器快慢，不能当判据）。真实订单量走的是另一条路：有缺口账就报缺料。
         */
        void terminalDeclineWithoutShortageStillThrows() {
            var grid = ringGrid();

            var calculation = new OrderedPlanningCalculation(
                    null, grid, grid.requester, new GenericStack(TOP, 0L),
                    CalculationStrategy.REPORT_MISSING_ITEMS,
                    List.of(PlanningAlgorithmId.NATIVE), false, 5_000L);

            var failure = assertThrows(IllegalStateException.class, calculation::run);
            assertEquals("Terminal planning algorithm declined: NATIVE", failure.getMessage());
        }

        /**
         * 递归核这一环的判据：还没解过就没有判决，更没有缺口账 —— {@code terminalShortagePlan()}
         * 交回 {@code null}，调用方照旧按"终点算法也没辙"抛原异常。
         */
        void kernelWithoutAnyDeclineHandsBackNothing() {
            var grid = ringGrid();
            var stock = new appeng.api.stacks.KeyCounter();
            stock.add(B, 1L);
            stock.add(LEAF, 32L);
            var kernel = new RecursivePlanningKernel(null, grid, grid.requester,
                    new GenericStack(TOP, 8L), CalculationStrategy.REPORT_MISSING_ITEMS, 5_000L, stock);

            assertNull(kernel.terminalShortagePlan(), "没解过就没有判决：不许凭空造一份缺料回执");
        }

        // ---- fixtures ----

        /** 一条真递归环（A↔B）+ 一个吃 A 的目标件：有环，终点算法才走我方递归核。 */
        private static FakePlanGrid.FakeGrid ringGrid() {
            var first = FakePlanGrid.FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(B, 2L)),
                    new FakePlanGrid.FakeInput(A, 1L), new FakePlanGrid.FakeInput(LEAF, 1L));
            var second = FakePlanGrid.FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(A, 2L)), new FakePlanGrid.FakeInput(B, 1L));
            var consumer = FakePlanGrid.FakePattern.of(TOP, 1L, new FakePlanGrid.FakeInput(A, 1L));
            return FakePlanGrid.FakeGrid.builder()
                    .storage(Map.of(B, 1L, LEAF, 32L))
                    .pattern(A, second)
                    .pattern(B, first)
                    .pattern(TOP, consumer)
                    .build();
        }

        /** 两发各记 {@code missingPerSite} 枚缺料的那本账：该键需求 {@code 2×Long.MAX_VALUE} 枚金。 */
        private static GraphPlanningState goldLedger(GraphPattern first, GraphPattern second,
                long missingPerSite) {
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(first.source(), FIRINGS);
            state.addCrafting(second.source(), FIRINGS);
            state.addMissing(GOLD, missingPerSite);
            state.addMissing(GOLD, missingPerSite);
            return state;
        }

        private static GraphPattern hugeGoldPattern() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                            GraphInput.Mode.NORMAL, null, null, null, false)));
        }

        private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(first, second)),
                    Map.of(first.source(), first, second.source(), second),
                    List.of(first.source(), second.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GOLD, 100L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** 轻量样板替身：闸门与转换器只拿 definition 当身份。 */
        private static final class FakeDetails implements IPatternDetails {
            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of();
            }

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(new ItemStack(Items.PAPER));
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.closureGateCollectsEveryShortKeyBeyondLong", () -> new TerminalShortagePlanCases().closureGateCollectsEveryShortKeyBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.balancedLedgerCollectsNoShortage", () -> new TerminalShortagePlanCases().balancedLedgerCollectsNoShortage()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.terminalShortagePlanReportsTheExactGapInsteadOfThrowing", () -> new TerminalShortagePlanCases().terminalShortagePlanReportsTheExactGapInsteadOfThrowing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.shortageReportDoesNotDependOnTheRejectedLedger", () -> new TerminalShortagePlanCases().shortageReportDoesNotDependOnTheRejectedLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.everyOtherDeclineKeepsThrowingPathUntouched", () -> new TerminalShortagePlanCases().everyOtherDeclineKeepsThrowingPathUntouched()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.terminalDeclineWithoutShortageStillThrows", () -> new TerminalShortagePlanCases().terminalDeclineWithoutShortageStillThrows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalShortagePlanCases.kernelWithoutAnyDeclineHandsBackNothing", () -> new TerminalShortagePlanCases().kernelWithoutAnyDeclineHandsBackNothing())
            );
        }
    }

    /** WidePlanLedgerCodecCases 的独立场景夹具，按类别统一执行。 */
    public static final class WidePlanLedgerCodecCases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final BigInteger OVERFLOW =
                BigInteger.valueOf(16).multiply(BigInteger.TEN.pow(18)); // 1.6e19 > Long.MAX

        private static AEItemKey key(net.minecraft.world.item.Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

        void overflowKeysRoundTripWithExactAmountsAndReconstructedKeys() {
            WideKeyCounter missing = new WideKeyCounter();
            missing.add(key(Items.LAVA_BUCKET), OVERFLOW);
            WideKeyCounter emitted = new WideKeyCounter();
            emitted.add(key(Items.PAPER), OVERFLOW.add(BigInteger.ONE));

            WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(missing, null, emitted);
            String encoded = WidePlanLedgerCodec.encode(snapshot, REGISTRIES);
            assertTrue(!encoded.isEmpty(), "有溢出键时编码串非空");

            WideLedgerSnapshot decoded = WidePlanLedgerCodec.decode(encoded, REGISTRIES);

            AEKey lavaKey = key(Items.LAVA_BUCKET);
            AEKey paperKey = key(Items.PAPER);
            assertEquals(OVERFLOW, decoded.get(WideAmountKind.MISSING, lavaKey),
                    "缺料真值往返一致且 AEKey 按值还原");
            assertEquals(OVERFLOW.add(BigInteger.ONE), decoded.get(WideAmountKind.EMIT, paperKey),
                    "产出真值往返一致");
        }

        void onlyOverflowKeysAreCaptured() {
            WideKeyCounter missing = new WideKeyCounter();
            missing.add(key(Items.STONE), 12_345L); // 能塞 long，不该进快照
            WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(missing, null, null);
            assertTrue(snapshot.isEmpty(), "无溢出键时快照为空");
            assertEquals("", WidePlanLedgerCodec.encode(snapshot, REGISTRIES));
            assertTrue(WidePlanLedgerCodec.decode("", REGISTRIES).isEmpty());
        }

        void multipleOverflowRecordsSeparateAndReparse() {
            WideKeyCounter used = new WideKeyCounter();
            used.add(key(Items.IRON_INGOT), OVERFLOW);
            used.add(key(Items.GOLD_INGOT), OVERFLOW.multiply(BigInteger.valueOf(3)));
            WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(null, used, null);
            String encoded = WidePlanLedgerCodec.encode(snapshot, REGISTRIES);
            WideLedgerSnapshot decoded = WidePlanLedgerCodec.decode(encoded, REGISTRIES);

            Map<AEKey, BigInteger> usedEntries = decoded.entries(WideAmountKind.USED);
            assertEquals(2, usedEntries.size());
            assertEquals(OVERFLOW, decoded.get(WideAmountKind.USED, key(Items.IRON_INGOT)));
            assertEquals(OVERFLOW.multiply(BigInteger.valueOf(3)),
                    decoded.get(WideAmountKind.USED, key(Items.GOLD_INGOT)));
        }

        void decodeToleratesGarbageAndMissingProvider() {
            assertTrue(WidePlanLedgerCodec.decode(null, REGISTRIES).isEmpty());
            assertTrue(WidePlanLedgerCodec.decode("nonsense", REGISTRIES).isEmpty());
            assertTrue(WidePlanLedgerCodec.decode("miss|!!!|abc", REGISTRIES).isEmpty());
            assertTrue(WidePlanLedgerCodec.decode("miss|aGVsbG8=|123", null).isEmpty());
            assertNull(WidePlanLedgerCodec.decode("", REGISTRIES).get(WideAmountKind.MISSING, key(Items.STONE)));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WidePlanLedgerCodecCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanLedgerCodecCases.overflowKeysRoundTripWithExactAmountsAndReconstructedKeys", () -> new WidePlanLedgerCodecCases().overflowKeysRoundTripWithExactAmountsAndReconstructedKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanLedgerCodecCases.onlyOverflowKeysAreCaptured", () -> new WidePlanLedgerCodecCases().onlyOverflowKeysAreCaptured()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanLedgerCodecCases.multipleOverflowRecordsSeparateAndReparse", () -> new WidePlanLedgerCodecCases().multipleOverflowRecordsSeparateAndReparse()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanLedgerCodecCases.decodeToleratesGarbageAndMissingProvider", () -> new WidePlanLedgerCodecCases().decodeToleratesGarbageAndMissingProvider())
            );
        }
    }
}
