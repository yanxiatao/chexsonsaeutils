package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.*;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingContraction;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingStartupPlan;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.*;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 材料闭合与事务恢复的行为场景；由类别测试统一调用。 */
public final class ClosureScenarios {
    private ClosureScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.graphplan.solve",
                () -> BatchPlanningEligibilityCases.verify(temporaryRoot),
                () -> CapacityBoundCeilingCases.verify(temporaryRoot),
                () -> CompressionDagBatchCases.verify(temporaryRoot),
                () -> GraphPlanningStateSupplyTruthCases.verify(temporaryRoot),
                () -> LexicographicMetricWideCases.verify(temporaryRoot),
                () -> MaterialClosureGateDrainOrderCases.verify(temporaryRoot),
                () -> MaterialClosureGateReachDemandCases.verify(temporaryRoot),
                () -> MaterialClosureGateRingTableCases.verify(temporaryRoot),
                () -> MaterialClosureGateSelfProducedReachDemandCases.verify(temporaryRoot),
                () -> MaterialClosureGateCases.verify(temporaryRoot),
                () -> MultiPatternCrystalGrowthCases.verify(temporaryRoot),
                () -> PagedPlanJournalCases.verify(temporaryRoot),
                () -> PlanJournalCases.verify(temporaryRoot),
                () -> RingStartupWide218Cases.verify(temporaryRoot),
                () -> WideCraftCountCases.verify(temporaryRoot),
                () -> WideLegsSolverAlignmentCases.verify(temporaryRoot),
                () -> WideLinearBatchCases.verify(temporaryRoot),
                () -> WidePerSiteDemandCases.verify(temporaryRoot),
                () -> WideRootRequestCases.verify(temporaryRoot)
        );
    }

    /** 执行本包的按需度量，保留各场景名称与状态隔离。 */
    public static void measure(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.graphplan.solve",
                () -> CompressionDagBatchCases.measure(temporaryRoot),
                () -> WideLinearBatchCases.measure(temporaryRoot),
                () -> WidePerSiteDemandCases.measure(temporaryRoot)
        );
    }

    /** BatchPlanningEligibilityCases 的独立场景夹具，按类别统一执行。 */
    public static final class BatchPlanningEligibilityCases {
        private static final AEKey TARGET = AEItemKey.of(Items.CLOCK);
        private static final AEKey CHILD = AEItemKey.of(Items.EMERALD);
        private static final AEKey RAW = AEItemKey.of(Items.IRON_INGOT);

        private static GraphInput input(AEKey key, long template) {
            return new GraphInput(key, template, 1, GraphInput.Mode.NORMAL, null, null, null, false);
        }

        private static GraphPattern pattern(AEKey key, long output, GraphInput... inputs) {
            return new GraphPattern(FakePattern.of(key, output, new FakeInput(RAW, 1)), key, output,
                    List.of(new GenericStack(key, output)), List.of(inputs));
        }

        private static CraftGraph graph(AEKey target, List<GraphPattern> patterns) {
            var producers = new LinkedHashMap<AEKey, List<GraphPattern>>();
            var sources = new LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
            for (var pattern : patterns) {
                producers.computeIfAbsent(pattern.outputKey(), ignored -> new ArrayList<>()).add(pattern);
                sources.put(pattern.source(), pattern);
            }
            return new CraftGraph(target, producers, sources, List.copyOf(sources.keySet()),
                    Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 1, 1);
        }

        void rejectsChunkRoundingTemplateCompetitionAndNonDeterministicFlows() {
            var invalid = List.of(
                    List.of(pattern(TARGET, 1, input(CHILD, 3)), pattern(CHILD, 2, input(RAW, 1))),
                    List.of(pattern(TARGET, 1, input(CHILD, 1)),
                            pattern(CHILD, 1, input(RAW, 2), input(RAW, 3))),
                    List.of(pattern(TARGET, 1, input(CHILD, 1)),
                            pattern(CHILD, 1, input(RAW, 1)), pattern(CHILD, 1, input(RAW, 1))),
                    List.of(pattern(TARGET, 1, input(CHILD, 1)), new GraphPattern(
                            FakePattern.of(CHILD, 1, new FakeInput(RAW, 1)), CHILD, 1,
                            List.of(new GenericStack(CHILD, 1), new GenericStack(RAW, 1)), List.of(input(RAW, 1)))));
            for (var patterns : invalid) {
                BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph(TARGET, patterns), 16, () -> { });
                assertFalse(eligibility.allows(TARGET, 1, true, 16));
            }
            var unsafeInputs = new ArrayList<GraphInput>();
            for (var mode : GraphInput.Mode.values()) {
                if (mode != GraphInput.Mode.NORMAL) {
                    unsafeInputs.add(new GraphInput(RAW, 1, 1, mode, RAW, null, null, false));
                }
            }
            unsafeInputs.add(new GraphInput(RAW, 1, 1, GraphInput.Mode.NORMAL, null,
                    List.of(new GraphInput.Candidate(RAW, 1)), Set.of(RAW), false));
            unsafeInputs.add(new GraphInput(RAW, 1, 1, GraphInput.Mode.NORMAL, null, null, null, true));
            unsafeInputs.add(new GraphInput(RAW, 1, 0, GraphInput.Mode.NORMAL, null, null, null, false));
            unsafeInputs.add(new GraphInput(RAW, 0, 1, GraphInput.Mode.NORMAL, null, null, null, false));
            for (var input : unsafeInputs) {
                var graph = graph(TARGET, List.of(pattern(TARGET, 1, input(CHILD, 1)), pattern(CHILD, 1, input)));
                BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph, 16, () -> { });
                assertFalse(eligibility.allows(TARGET, 1, true, 16), input::toString);
            }
        }

        void rootCeilingAndDepthChecksDoNotPolluteMemoizedChildEligibility() {
            var graph = graph(TARGET, List.of(pattern(TARGET, 2, input(CHILD, 3)), pattern(CHILD, 3, input(RAW, 1))));
            BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph, 16, () -> { });
            assertFalse(eligibility.allows(TARGET, 1, true, 2));
            assertTrue(eligibility.allows(TARGET, 1, true, 3));
            assertTrue(eligibility.allows(CHILD, 3, false, 2));
            assertFalse(eligibility.allows(CHILD, 1, false, 2));
            assertFalse(eligibility.allows(CHILD, 3, false, 1));
            assertFalse(eligibility.allows(TARGET, 0, true, 16));
        }

        void repeatedDiamondChecksEachKeyAndEdgeOnlyConstantTimes() {
            int depth = 128;
            var keys = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                    .limit(depth + 1L).map(AEItemKey::of).toList();
            var patterns = new ArrayList<GraphPattern>();
            for (int index = 0; index < depth; index++) {
                patterns.add(pattern(keys.get(index), 1, input(keys.get(index + 1), 1), input(keys.get(index + 1), 1)));
            }
            int[] checks = {0};
            BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(
                    graph(keys.getFirst(), patterns), depth + 1, () -> checks[0]++);
            assertTrue(eligibility.allows(keys.getFirst(), 1, true, depth + 1));
            assertTrue(checks[0] < depth * 10, "证明检查量必须随唯一节点及输入边增长");
            int before = checks[0];
            for (int index = 1; index < depth; index++) {
                assertTrue(eligibility.allows(keys.get(index), 1, false, depth + 1 - index));
            }
            assertEquals(before, checks[0], "共享子图的后续查询应复用证明");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("BatchPlanningEligibilityCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("BatchPlanningEligibilityCases.rejectsChunkRoundingTemplateCompetitionAndNonDeterministicFlows", () -> new BatchPlanningEligibilityCases().rejectsChunkRoundingTemplateCompetitionAndNonDeterministicFlows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("BatchPlanningEligibilityCases.rootCeilingAndDepthChecksDoNotPolluteMemoizedChildEligibility", () -> new BatchPlanningEligibilityCases().rootCeilingAndDepthChecksDoNotPolluteMemoizedChildEligibility()),
                () -> org.junit.jupiter.api.Assertions.assertAll("BatchPlanningEligibilityCases.repeatedDiamondChecksEachKeyAndEdgeOnlyConstantTimes", () -> new BatchPlanningEligibilityCases().repeatedDiamondChecksEachKeyAndEdgeOnlyConstantTimes())
            );
        }
    }

    /** CapacityBoundCeilingCases 的独立场景夹具，按类别统一执行。 */
    public static final class CapacityBoundCeilingCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

        /** 6e18 枚：合法的 {@code long} 请求，但大于旧天花板 {@code 1L << 62 = 4.61e18}。 */
        private static final long REQUEST_6E18 = 6_000_000_000_000_000_000L;

        /** 1e18 枚铁：请求本身合法（{@code long}），放大 10 倍后用料越出 {@code long}。 */
        private static final long REQUEST_1E18 = 1_000_000_000_000_000_000L;
        private static final java.math.BigInteger TRUTH_1_5E19 = new java.math.BigInteger("15000000000000000000");

        void craftLessRouteMustNotQuietlyStopAtTheOldCeiling() {
            var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(producer)),
                    Map.of(producer.source(), producer),
                    List.of(producer.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.<AEKey, Long>of(GOLD, REQUEST_6E18),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_6E18);

            assertFalse(result.hasDeclined(), () -> "6e18 枚请求被上界天花板挡在门外: "
                    + result.declineReason());
            var state = result.state();
            assertTrue(state.missingItems().isEmpty(),
                    () -> "网络里就有 6e18 枚金，不许报任何缺料: " + state.missingItems());
            assertEquals(REQUEST_6E18, (long) state.crafts().get(producer.source()),
                    "份数必须等于请求：每发 1 枚铁 ⇒ 6e18 发。少一分就是静默少产");
            assertEquals(REQUEST_6E18, state.usedItems().get(GOLD),
                    "用量必须等于请求：每发 1 枚金");
        }

        /**
         * 沙盒抽走多少，{@code used} 账就必须记多少<b>真值</b>——哪怕这一格的起步量本身 &gt; long。
         *
         * <p>旧实现是 {@code unmodified.getLong − working.getLong}：两个读数都走<b>饱和主表</b>。
         * 只要 working 还在溢出区（主表恒为 {@code Long.MAX_VALUE}），这一笔就报 {@code 0}；
         * 抽到刚好跌破天花板之后，才开始用 {@code Long.MAX_VALUE − working} 倒推。
         * 于是从真值 1.5e19 枚里抽走 {@code Long.MAX_VALUE} 枚，账上只剩
         * {@code 3446744073709551607} 枚——差的那 5.78e18 枚不是没抽，是<b>记丢了</b>。
         * 这本账正是守恒闸门与配给表的供给侧（{@code addToSupply(supply, state.used())}），
         * 少记一截就把配得平的图判成"材料不闭合"整条拒掉。
         */
        void usedLedgerRecordsTheWideDrawInsteadOfTheSaturatedDifference() {
            var state = new GraphPlanningState(Map.<AEKey, Long>of(GOLD, Long.MAX_VALUE), Map.of(),
                    git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                            Map.of(GOLD, git.chexson.chexsonsaeutils.bigint.PlanAmount
                                    .of(TRUTH_1_5E19))));

            // 先抽 1e18 枚：working 仍在溢出区，旧口径这里恒报 0——这一笔抽取整个隐身
            assertEquals(REQUEST_1E18, state.extract(GOLD, REQUEST_1E18, false),
                    "真值 1.5e19 枚起步，抽 1e18 枚必须给满");
            assertEquals(java.math.BigInteger.valueOf(REQUEST_1E18), state.used().getExact(GOLD),
                    "working 还在溢出区也要当场记真值：旧口径两个饱和读数一减恒为 0");

            // 再抽 Long.MAX 枚：累计 1.1e19 枚，working 跌出溢出区，旧口径从这里开始把账记丢
            assertEquals(Long.MAX_VALUE, state.extract(GOLD, Long.MAX_VALUE, false),
                    "沙盒里还剩 1.4e19 枚，整笔 Long.MAX 枚都该给得出");
            assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).add(
                            java.math.BigInteger.valueOf(REQUEST_1E18)),
                    state.used().getExact(GOLD),
                    "used 必须等于两次抽取之和（1.0e18 + 9.22e18）：饱和视图倒推会少记 5.78e18 枚");
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
                return AEItemKey.of(new ItemStack(Items.PAPER));
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CapacityBoundCeilingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CapacityBoundCeilingCases.craftLessRouteMustNotQuietlyStopAtTheOldCeiling", () -> new CapacityBoundCeilingCases().craftLessRouteMustNotQuietlyStopAtTheOldCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CapacityBoundCeilingCases.usedLedgerRecordsTheWideDrawInsteadOfTheSaturatedDifference", () -> new CapacityBoundCeilingCases().usedLedgerRecordsTheWideDrawInsteadOfTheSaturatedDifference())
            );
        }
    }

    /** CompressionDagBatchCases 的独立场景夹具，按类别统一执行。 */
    public static final class CompressionDagBatchCases {
        private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
        private static final AEKey RAW_IRON = AEItemKey.of(Items.IRON_NUGGET);
        private static final AEKey RAW_COPPER = AEItemKey.of(Items.GOLD_NUGGET);
        private static final AEKey ROD = AEItemKey.of(Items.STICK);
        private static final AEKey BLOCK = AEItemKey.of(Items.COPPER_BLOCK);
        private static final List<AEKey> MATERIALS = List.of(IRON, COPPER, RAW_IRON, RAW_COPPER, ROD, BLOCK);

        private record Fixture(FakeGrid grid, List<AEItemKey> chain, BigInteger alloyAmount) { }

        private static Fixture fixture(int depth) {
            return fixture(depth, false, false);
        }

        private static FakeInput input(AEKey key, long multiplier, boolean duplicate) {
            return duplicate ? FakeInput.multi(multiplier, key, key) : new FakeInput(key, multiplier);
        }

        private static Fixture fixture(int depth, boolean duplicate, boolean bulk) {
            var chain = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                    .map(AEItemKey::of).filter(key -> !MATERIALS.contains(key)).limit(depth + 1L).toList();
            assertEquals(depth + 1, chain.size());
            var builder = FakeGrid.builder().storage(Map.of(RAW_IRON, Long.MAX_VALUE, RAW_COPPER, Long.MAX_VALUE,
                    chain.get(depth), bulk ? 1L : 0L));
            for (int index = 0; index < depth; index++) {
                builder.pattern(chain.get(index), FakePattern.of(chain.get(index), 1,
                        input(chain.get(index + 1), 9, duplicate)));
            }
            builder.pattern(chain.get(depth), FakePattern.of(chain.get(depth), 1,
                    input(ROD, 4, duplicate), input(BLOCK, 1, duplicate)));
            builder.pattern(ROD, FakePattern.of(ROD, 1, input(IRON, 2, duplicate), input(COPPER, 1, duplicate)));
            builder.pattern(BLOCK, FakePattern.of(BLOCK, 1, input(COPPER, 9, duplicate)));
            builder.pattern(IRON, FakePattern.of(IRON, bulk ? 3 : 1, input(RAW_IRON, bulk ? 8 : 3, duplicate)));
            builder.pattern(COPPER, FakePattern.of(COPPER, bulk ? 3 : 1, input(RAW_COPPER, bulk ? 8 : 3, duplicate)));
            return new Fixture(builder.build(), chain, BigInteger.valueOf(9).pow(depth).subtract(bulk ? BigInteger.ONE : BigInteger.ZERO));
        }

        void duplicatedCandidatesAndBulkSharedProductsKeepReusableRoundingRemainders() {
            var fixture = fixture(22, true, true);
            var ironTimes = fixture.alloyAmount.multiply(BigInteger.valueOf(8)).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
            var copperTimes = fixture.alloyAmount.multiply(BigInteger.valueOf(13)).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
            var result = solve(fixture, ironTimes.multiply(BigInteger.valueOf(8)), copperTimes.multiply(BigInteger.valueOf(8)));
            assertFalse(result.simulation());
            assertEquals(ironTimes.multiply(BigInteger.valueOf(8)), result.state().used().getExact(RAW_IRON));
            assertEquals(copperTimes.multiply(BigInteger.valueOf(8)), result.state().used().getExact(RAW_COPPER));
            assertEquals(2L, result.state().extract(IRON, 100, true));
            assertEquals(1L, result.state().extract(COPPER, 100, true));
            assertTrue(result.state().journal() < 500, "重复候选及可重用余料不应引起宽需求分轮");
        }

        void differentCandidateAmountsAndAlternativeKeysRetainExtractionOrder() {
            var target = AEItemKey.of(Items.CLOCK);
            var cases = List.of(
                    FakeInput.ordered(1, List.of(new appeng.api.stacks.GenericStack(RAW_IRON, 3),
                            new appeng.api.stacks.GenericStack(RAW_IRON, 2)), List.of(RAW_IRON)),
                    FakeInput.multi(1, RAW_IRON, RAW_COPPER));
            for (int index = 0; index < cases.size(); index++) {
                var stock = index == 0 ? Map.of(RAW_IRON, 5L) : Map.of(RAW_IRON, 1L, RAW_COPPER, 1L);
                var grid = FakeGrid.builder().storage(stock).pattern(target, FakePattern.of(target, 1, cases.get(index))).build();
                var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(result.simulation());
                assertEquals(BigInteger.valueOf(index == 0 ? 5 : 1), result.state().used().getExact(RAW_IRON));
                if (index == 1) assertEquals(BigInteger.ONE, result.state().used().getExact(RAW_COPPER));
            }
        }

        private static SolveResult solve(Fixture fixture, BigInteger ironStock, BigInteger copperStock) {
            var graph = GraphCompiler.compile(fixture.grid.crafting, fixture.grid.storage.getCachedInventory(),
                    fixture.chain.getFirst(), null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                    RAW_IRON, PlanAmount.of(ironStock), RAW_COPPER, PlanAmount.of(copperStock))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertNull(MaterialClosureGate.validate(result.state(), graph, 1, null));
            return result;
        }

        void exponentialCompressionWithSharedMaterialUsesBoundedJournalAndExactFirings() {
            for (int depth : new int[] {24, 25, 64}) {
                assertCompressionAccounting(depth);
            }
        }

        private static void assertCompressionAccounting(int depth) {
            var fixture = fixture(depth);
            var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
            var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
            var result = solve(fixture, iron, copper);
            assertFalse(result.simulation());
            assertEquals(iron, result.state().used().getExact(RAW_IRON));
            assertEquals(copper, result.state().used().getExact(RAW_COPPER));
            var firings = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : result.state().craftsWide().keys()) {
                firings.put(entry.getOutputs().getFirst().what(), result.state().craftsWide().getAmount(entry).exactValue());
            }
            for (int index = 0; index < fixture.chain.size(); index++) {
                assertEquals(BigInteger.valueOf(9).pow(index), firings.get(fixture.chain.get(index)));
            }
            assertEquals(fixture.alloyAmount.multiply(BigInteger.valueOf(13)), firings.get(COPPER));
            assertTrue(result.state().journal() < 500, "日志应随图节点增长，实际=" + result.state().journal());
        }

        void failedBatchRollbackKeepsExactMissingMaterials() {
            var fixture = fixture(24);
            var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
            var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
            var result = solve(fixture, iron, BigInteger.valueOf(7));
            assertTrue(result.simulation());
            assertEquals(iron, result.state().used().getExact(RAW_IRON));
            assertEquals(BigInteger.valueOf(7), result.state().used().getExact(RAW_COPPER));
            assertEquals(copper.subtract(BigInteger.valueOf(7)), result.state().missing().getExact(RAW_COPPER));
            assertTrue(result.state().journal() < 500);
        }

        void wideSharedInputsMatchExactAccountingAcrossStockAndShortageCombinations() {
            var target = AEItemKey.of(Items.CLOCK);
            var intermediate = AEItemKey.of(Items.EMERALD);
            var random = new java.util.Random(0x9DA6L);
            var demand = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
            for (int sample = 0; sample < 48; sample++) {
                long intermediateStock = random.nextInt(100);
                var firings = demand.subtract(BigInteger.valueOf(intermediateStock));
                var rawDemand = firings.multiply(BigInteger.valueOf(8));
                var copperDemand = firings;
                var rawStock = rawDemand.add(BigInteger.valueOf(random.nextInt(41) - 20));
                var copperStock = copperDemand.add(BigInteger.valueOf(random.nextInt(41) - 20));
                var grid = FakeGrid.builder().storage(Map.of(intermediate, intermediateStock,
                        RAW_IRON, Long.MAX_VALUE, RAW_COPPER, Long.MAX_VALUE))
                        .pattern(target, FakePattern.of(target, 1, new FakeInput(intermediate, Long.MAX_VALUE)))
                        .pattern(intermediate, FakePattern.of(intermediate, 1,
                                new FakeInput(RAW_IRON, 3), new FakeInput(RAW_IRON, 5), new FakeInput(RAW_COPPER, 1)))
                        .build();
                var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                        RAW_IRON, PlanAmount.of(rawStock), RAW_COPPER, PlanAmount.of(copperStock))));
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
                assertFalse(result.hasDeclined(), result::declineReason);
                assertEquals(rawStock.compareTo(rawDemand) < 0 || copperStock.compareTo(copperDemand) < 0,
                        result.simulation());
                assertEquals(BigInteger.valueOf(intermediateStock), result.state().used().getExact(intermediate));
                assertEquals(firings, result.state().craftsWide().getAmount(
                        graph.producersByOutput().get(intermediate).getFirst().source()).exactValue());
                assertEquals(rawDemand.min(rawStock), result.state().used().getExact(RAW_IRON));
                assertEquals(copperDemand.min(copperStock), result.state().used().getExact(RAW_COPPER));
                assertEquals(rawDemand.subtract(rawStock).max(BigInteger.ZERO), result.state().missing().getExact(RAW_IRON));
                assertEquals(copperDemand.subtract(copperStock).max(BigInteger.ZERO), result.state().missing().getExact(RAW_COPPER));
                assertNull(MaterialClosureGate.validate(result.state(), graph, 2, null));
            }
        }

        void failedSharedBatchRestoresStockBeforePartialAndAlternativeRoutes() {
            var target = AEItemKey.of(Items.CLOCK);
            var intermediate = AEItemKey.of(Items.EMERALD);
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new appeng.api.stacks.GenericStack(target, 1)),
                    new FakeInput(intermediate, Long.MAX_VALUE)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new appeng.api.stacks.GenericStack(target, 1)), new FakeInput(RAW_IRON, 1));
            var grid = FakeGrid.builder().storage(Map.of(RAW_IRON, Long.MAX_VALUE))
                    .pattern(target, preferred, alternative)
                    .pattern(intermediate, FakePattern.of(intermediate, 1,
                            new FakeInput(RAW_IRON, 1), new FakeInput(RAW_IRON, 1)))
                    .build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW_IRON,
                    PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3))))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            // 完整首选批次失败并回滚；既有顺序随后先完成首选一发，再由替代路线完成一发。
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.ONE),
                    result.state().used().getExact(RAW_IRON));
            assertEquals(BigInteger.ONE, result.state().craftsWide().getAmount(preferred).exactValue());
            assertEquals(BigInteger.ONE, result.state().craftsWide().getAmount(alternative).exactValue());
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), result.state().craftsWide().getAmount(
                    graph.producersByOutput().get(intermediate).getFirst().source()).exactValue());
            assertEquals(3, result.state().craftsWide().size());
            assertNull(MaterialClosureGate.validate(result.state(), graph, 2, null));
        }

        void planningPerformanceReadout() {
            var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            for (int depth : new int[] {18, 22, 24, 25, 64}) {
                var fixture = fixture(depth);
                var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
                var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
                var elapsed = new ArrayList<Long>();
                var allocated = new ArrayList<Long>();
                int journal = 0;
                for (int sample = 0; sample < 3; sample++) {
                    long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long start = System.nanoTime();
                    var result = solve(fixture, iron, copper);
                    elapsed.add(System.nanoTime() - start);
                    allocated.add(allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before);
                    assertFalse(result.simulation());
                    assertEquals(iron, result.state().used().getExact(RAW_IRON));
                    assertEquals(copper, result.state().used().getExact(RAW_COPPER));
                    journal = result.state().journal();
                }
                elapsed.sort(Long::compare);
                allocated.sort(Long::compare);
                System.out.printf("压缩共享图 depth=%d median-ns=%d median-B=%d journal=%d%n",
                        depth, elapsed.get(1), allocated.get(1), journal);
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.duplicatedCandidatesAndBulkSharedProductsKeepReusableRoundingRemainders", () -> new CompressionDagBatchCases().duplicatedCandidatesAndBulkSharedProductsKeepReusableRoundingRemainders()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.differentCandidateAmountsAndAlternativeKeysRetainExtractionOrder", () -> new CompressionDagBatchCases().differentCandidateAmountsAndAlternativeKeysRetainExtractionOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.exponentialCompressionWithSharedMaterialUsesBoundedJournalAndExactFirings", () -> new CompressionDagBatchCases().exponentialCompressionWithSharedMaterialUsesBoundedJournalAndExactFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.failedBatchRollbackKeepsExactMissingMaterials", () -> new CompressionDagBatchCases().failedBatchRollbackKeepsExactMissingMaterials()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.wideSharedInputsMatchExactAccountingAcrossStockAndShortageCombinations", () -> new CompressionDagBatchCases().wideSharedInputsMatchExactAccountingAcrossStockAndShortageCombinations()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.failedSharedBatchRestoresStockBeforePartialAndAlternativeRoutes", () -> new CompressionDagBatchCases().failedSharedBatchRestoresStockBeforePartialAndAlternativeRoutes())
            );
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CompressionDagBatchCases.planningPerformanceReadout", () -> new CompressionDagBatchCases().planningPerformanceReadout())
            );
        }
    }

    /** GraphPlanningStateSupplyTruthCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphPlanningStateSupplyTruthCases {
        private static final AEKey GLASS = AEItemKey.of(Items.GLASS);

        /** 真值 3e19 枚：网络读数在这里被 AE2 的 {@code long} 夹成 9.22e18。 */
        private static final BigInteger TRUTH = new BigInteger("30000000000000000000");
        /** 单笔 9e18 枚：装得进 {@code long}，所以三笔之和（2.7e19）才越界。 */
        private static final long CHUNK = 9_000_000_000_000_000_000L;

        private static PlanningSupplyTruth truthOf(BigInteger supply) {
            return PlanningSupplyTruth.of(Map.of(GLASS, PlanAmount.of(supply)));
        }

        /** 接上真值：连取三笔 9e18 全给满，第四笔才交出剩下的 3e18。 */
        void sandboxSeedReadsTheWideSupplyTruth() {
            var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                    truthOf(TRUTH));

            for (int round = 1; round <= 3; round++) {
                assertEquals(CHUNK, state.extract(GLASS, CHUNK, false),
                        "第 " + round + " 笔：真值 3e19 枚足够连付三笔 9e18");
            }
            assertEquals(TRUTH.subtract(BigInteger.valueOf(3L).multiply(BigInteger.valueOf(CHUNK)))
                            .longValueExact(),
                    state.extract(GLASS, CHUNK, false),
                    "第四笔只剩 3e18：沙盒记得住的是真值，不是网络的天花板");
        }

        /** 没有真值就是今日口径，一字不变：起步量顶在 {@code Long.MAX_VALUE}，第二笔就只剩零头。 */
        void withoutTruthTheSeedIsExactlyTheLongReading() {
            var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                    PlanningSupplyTruth.EMPTY);

            assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
            assertEquals(Long.MAX_VALUE - CHUNK, state.extract(GLASS, CHUNK, false),
                    "对照段：老口径的起步量就是饱和读数，多出来的 2e19 枚根本不存在");
        }

        /**
         * 撤销必须把<b>宽域</b>起步量原样还回来：回滚写的是窄值就等于把真值抹成零头，
         * 而一次规划里 attempt 靠 {@code undoTo} 反复回档，这比种子本身更容易丢账。
         */
        void undoRestoresTheWideSeedExactly() {
            var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(),
                    truthOf(TRUTH));

            var mark = state.journal();
            assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
            state.undoTo(mark);

            for (int round = 1; round <= 3; round++) {
                assertEquals(CHUNK, state.extract(GLASS, CHUNK, false),
                        "回档后第 " + round + " 笔仍该给满：宽值必须逐位回到 seed 上");
            }
            assertEquals(TRUTH.subtract(BigInteger.valueOf(3L).multiply(BigInteger.valueOf(CHUNK)))
                            .longValueExact(),
                    state.extract(GLASS, CHUNK, false));
        }

        void availabilityReadDoesNotSeedOrJournalAndTracksWideUndo() {
            var state = new GraphPlanningState(Map.of(GLASS, Long.MAX_VALUE), Map.of(), truthOf(TRUTH));
            int keyId = state.idOf(GLASS);
            int mark = state.journal();
            assertEquals(TRUTH, state.availableExactByIdWide(GLASS, keyId).exactValue());
            assertEquals(mark, state.journal(), "剪枝读数不应触发懒缓存或写入日志");
            assertEquals(CHUNK, state.extract(GLASS, CHUNK, false));
            assertEquals(TRUTH.subtract(BigInteger.valueOf(CHUNK)),
                    state.availableExactByIdWide(GLASS, keyId).exactValue());
            state.undoTo(mark);
            assertEquals(TRUTH, state.availableExactByIdWide(GLASS, keyId).exactValue());
            state.ignore(GLASS);
            assertEquals(PlanAmount.ZERO, state.availableExactByIdWide(GLASS, keyId));
        }

        void availabilityExcludesRetainedSeedsAndIncludesLaterInsertions() {
            var state = new GraphPlanningState(Map.of(GLASS, 20L), Map.of(GLASS, java.util.List.of(GLASS)));
            int keyId = state.idOf(GLASS);
            state.holdBackRetainedSeedsWide(Map.of(GLASS, PlanAmount.of(7L)));
            assertEquals(13L, state.availableExactByIdWide(GLASS, keyId).fastValue());
            assertEquals(13L, state.extract(GLASS, 100L, false));
            assertEquals(PlanAmount.ZERO, state.availableExactByIdWide(GLASS, keyId));
            state.insert(GLASS, 5L);
            assertEquals(5L, state.availableExactByIdWide(GLASS, keyId).fastValue());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases.sandboxSeedReadsTheWideSupplyTruth", () -> new GraphPlanningStateSupplyTruthCases().sandboxSeedReadsTheWideSupplyTruth()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases.withoutTruthTheSeedIsExactlyTheLongReading", () -> new GraphPlanningStateSupplyTruthCases().withoutTruthTheSeedIsExactlyTheLongReading()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases.undoRestoresTheWideSeedExactly", () -> new GraphPlanningStateSupplyTruthCases().undoRestoresTheWideSeedExactly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases.availabilityReadDoesNotSeedOrJournalAndTracksWideUndo", () -> new GraphPlanningStateSupplyTruthCases().availabilityReadDoesNotSeedOrJournalAndTracksWideUndo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningStateSupplyTruthCases.availabilityExcludesRetainedSeedsAndIncludesLaterInsertions", () -> new GraphPlanningStateSupplyTruthCases().availabilityExcludesRetainedSeedsAndIncludesLaterInsertions())
            );
        }
    }

    /** LexicographicMetricWideCases 的独立场景夹具，按类别统一执行。 */
    public static final class LexicographicMetricWideCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("LexicographicMetricWideCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("LexicographicMetricWideCases.fractionalFluidByteTieKeepsFirstRouteAfterExactComparison", () -> new LexicographicMetricWideCases().fractionalFluidByteTieKeepsFirstRouteAfterExactComparison()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LexicographicMetricWideCases.saturatedLedgerMustNotHandTheWinToTheCostlierRoute", () -> new LexicographicMetricWideCases().saturatedLedgerMustNotHandTheWinToTheCostlierRoute()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LexicographicMetricWideCases.greedyOrderStillTakesTheFirstRegisteredRoute", () -> new LexicographicMetricWideCases().greedyOrderStillTakesTheFirstRegisteredRoute()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LexicographicMetricWideCases.bothCandidatesExceedTheCeilingOnlyInTheirTotals", () -> new LexicographicMetricWideCases().bothCandidatesExceedTheCeilingOnlyInTheirTotals())
            );
        }
    }

    /** MaterialClosureGateDrainOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialClosureGateDrainOrderCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);

        /** 手算表：槽倍率 1 × 每份 1 枚 × 3 发 = 整槽 3 枚；两张样板共用同一个池子（玻璃 3、雪块 3）。 */
        private static final String EXPECTED_DRAIN_PROFILE = "G3S0|G3S3";

        void identicallyDeclaredFuzzySlotsDrainInRegistrationOrder() {
            var agrees = drainProfile(true);
            var opposes = drainProfile(false);

            assertEquals(EXPECTED_DRAIN_PROFILE, agrees.profile(),
                    () -> "注册序靠前的那张先吃顺位第一候选（玻璃 3 枚），另一张退到雪块并补整槽封套："
                            + agrees.profile());
            assertEquals(agrees.profile(), opposes.profile(),
                    () -> "同一张表，只换了份数账的哈希遍历序，排水记账就对调了＝槽排序的并列档留在哈希序上。"
                            + "来序与注册序相同=" + agrees.profile() + "，相反=" + opposes.profile());
        }

        /**
         * 造一本两张逐字同声明样板的份数账，按要的来序（与注册序相同／相反）摆好后跑一趟闸门。
         *
         * <p>256 次还没摆出来就是夹具前提没成立，直接红，不许把没跑到的形状当证据。
         */
        private static Profile drainProfile(boolean wantHashOrderFollowsRegistration) {
            for (int attempt = 0; attempt < 256; attempt++) {
                var first = new FuzzyDetails(Items.REPEATER, attempt * 2 + 1);
                var second = new FuzzyDetails(Items.REPEATER, attempt * 2 + 2);
                var state = new GraphPlanningState(Map.of(GLASS, 3L, SNOW, 3L), Map.of());
                state.addCrafting(first, 3L);
                state.addCrafting(second, 3L);
                if (hashOrderFollowsRegistration(state, first, second) != wantHashOrderFollowsRegistration) {
                    continue;
                }
                state.extract(GLASS, 3L, false);
                state.extract(SNOW, 3L, false);
                var graph = twoPatternGraph(fuzzyPattern(first), fuzzyPattern(second));
                var violation = MaterialClosureGate.validate(state, graph, 6L);
                assertNull(violation, () -> "夹具前提：两侧账都配得平，闸门不该拒：" + violation);
                var table = MaterialClosureGate.buildMaterialPlan(state, graph, 6L).assigned();
                return new Profile(row(table, first) + "|" + row(table, second));
            }
            throw new AssertionError("夹具前提没造出来：摆不出"
                    + (wantHashOrderFollowsRegistration ? "与注册序相同" : "与注册序相反")
                    + "的份数账哈希遍历序（256 次尝试）。");
        }

        /** 排水后的配给表按注册序摊平成一串读数：每张样板在这个键上吃了／封到几枚。 */
        private static String row(Map<IPatternDetails, WideKeyCounter> table, IPatternDetails source) {
            var task = table.get(source);
            assertNotNull(task, () -> "这张样板的配给条目没下发：表=" + table);
            return "G" + task.getLong(GLASS) + "S" + task.getLong(SNOW);
        }

        /** 份数账（哈希图）的遍历序是不是"先登记的那张在前"。只用来摆夹具，不参与任何期望值。 */
        private static boolean hashOrderFollowsRegistration(GraphPlanningState state,
                IPatternDetails first, IPatternDetails second) {
            var iterated = new ArrayList<>(state.craftsWide().keys());
            assertEquals(2, iterated.size(), () -> "夹具前提：两张样板都得在册：" + iterated);
            return iterated.get(0) == first;
        }

        /** 一次排水的读数：按注册序摊平的配给表。 */
        private record Profile(String profile) {
        }

        private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(first, second)),
                    Map.of(first.source(), first, second.source(), second),
                    List.of(first.source(), second.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 3L, SNOW, 3L),
                    Set.of(),
                    Map.of(), false, null, 1, 1);
        }

        /** IRON×1 ← 模糊槽（顺位：玻璃每份 1，雪块每份 1），两张样板这一份声明逐字相同。 */
        private static GraphPattern fuzzyPattern(FuzzyDetails source) {
            return new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(GLASS, 1L),
                                    new GraphInput.Candidate(SNOW, 1L)),
                            Set.of(GLASS, SNOW), false)));
        }

        /**
         * 样板替身：原始候选表恒为 [玻璃, 雪块]（首位即 {@code billReachKeySlot} 的 reach 键），
         * 输出 IRON×1，{@code hashCode} 由夹具给定——那是这份账的哈希遍历序的唯一变量。
         */
        private static final class FuzzyDetails implements IPatternDetails {
            private final AEItemKey definition;
            private final int hash;

            private FuzzyDetails(Item item, int hash) {
                this.definition = AEItemKey.of(new ItemStack(item));
                this.hash = hash;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new FuzzySlotInput(
                        new GenericStack[] {new GenericStack(GLASS, 1L), new GenericStack(SNOW, 1L)}) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object obj) {
                return this == obj;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 原始模糊槽：候选按数组序、倍率 1、无退料（非模具槽）。 */
        private record FuzzySlotInput(GenericStack[] candidates) implements IPatternDetails.IInput {
            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates.clone();
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateDrainOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateDrainOrderCases.identicallyDeclaredFuzzySlotsDrainInRegistrationOrder", () -> new MaterialClosureGateDrainOrderCases().identicallyDeclaredFuzzySlotsDrainInRegistrationOrder())
            );
        }
    }

    /** MaterialClosureGateReachDemandCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialClosureGateReachDemandCases {
        /** 伸手那一枚（实机 {@code mekanism:enriched_redstone} 的形状）。 */
        private static final AEItemKey DUST = AEItemKey.of(Items.GLOWSTONE_DUST);
        /** 同槽靠后的备选候选（实机 essence 一族的形状：宽域存量、盖得住回落那一截）。 */
        private static final AEItemKey SIBLING = AEItemKey.of(Items.NETHER_STAR);
        private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);
        /** 生产样板的原料（装得进 long 的常规库存）。 */
        private static final AEItemKey FUEL = AEItemKey.of(Items.COAL);

        /** 请求 1e11 发 × 槽倍率 1e9 × 每发 1 枚 = 这一格伸手键的需求 1e20 枚。 */
        private static final long REQUEST = 100_000_000_000L;
        private static final long SLOT_MULTIPLIER = 1_000_000_000L;
        private static final BigInteger REQUIRED = new BigInteger("100000000000000000000");
        /** DUST 网络真值 5e19 枚。 */
        private static final BigInteger DUST_TRUTH = new BigInteger("50000000000000000000");
        /** SIBLING 网络真值 6e19 枚。 */
        private static final BigInteger SIBLING_TRUTH = new BigInteger("60000000000000000000");
        /** 手算缺口：1e20 − 5e19 = 5e19。 */
        private static final BigInteger SHORTAGE = new BigInteger("50000000000000000000");
        /** 手算排产：ceil(5e19 / 1e6) = 5e13 发。 */
        private static final BigInteger EXPECTED_PRODUCER_FIRINGS = new BigInteger("50000000000000");
        private static final long PRODUCER_OUTPUT_PER_FIRING = 1_000_000L;

        private static final class RawInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;
            private final long multiplier;

            private RawInput(long multiplier, GenericStack... candidates) {
                this.candidates = candidates;
                this.multiplier = multiplier;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        private static final class Details implements IPatternDetails {
            private final AEItemKey definition;
            private final RawInput input;
            private final List<GenericStack> outputs;

            private Details(net.minecraft.world.item.Item definitionItem, long multiplier,
                    GenericStack... candidates) {
                this(definitionItem, new RawInput(multiplier, candidates),
                        List.of(new GenericStack(TARGET, 1L)));
            }

            private Details(net.minecraft.world.item.Item definitionItem, RawInput input,
                    List<GenericStack> outputs) {
                this.definition = AEItemKey.of(new ItemStack(definitionItem));
                this.input = input;
                this.outputs = outputs;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { input };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return outputs;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 消费样板：TARGET×1/发，模糊槽 [DUST@1, SIBLING@1]、倍率 1e9。 */
        private static Details consumerDetails() {
            return new Details(Items.REPEATER, SLOT_MULTIPLIER,
                    new GenericStack(DUST, 1L), new GenericStack(SIBLING, 1L));
        }

        /** 生产样板：FUEL×1 → DUST×1e6/发。 */
        private static Details producerDetails() {
            return new Details(Items.COMPARATOR,
                    new RawInput(1L, new GenericStack(FUEL, 1L)),
                    List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)));
        }

        /** 忠实图：DUST 与 SIBLING 的 long 库存视图都长成大天花板（无限盘溢出键的 AE2 口径事实），
         * 真值由 {@link PlanningSupplyTruth} 纠回。{@code withProducer} 决定 DUST 在图上有没有生产者。 */
        private static CraftGraph graph(boolean withProducer, Map<AEKey, PlanAmount> truth,
                Details consumer, Details producer) {
            var consumerPattern = new GraphPattern(consumer, TARGET, 1L,
                    List.of(new GenericStack(TARGET, 1L)),
                    List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(DUST, 1L),
                                    new GraphInput.Candidate(SIBLING, 1L)),
                            Set.of(DUST, SIBLING), false)));
            var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
            var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
            var order = new java.util.ArrayList<IPatternDetails>();
            producers.put(TARGET, List.of(consumerPattern));
            bySource.put(consumer, consumerPattern);
            order.add(consumer);
            if (withProducer) {
                var producerPattern = new GraphPattern(producer, DUST, PRODUCER_OUTPUT_PER_FIRING,
                        List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)),
                        List.of(new GraphInput(FUEL, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
                producers.put(DUST, List.of(producerPattern));
                bySource.put(producer, producerPattern);
                order.add(producer);
            }
            var graph = new CraftGraph(TARGET, Map.copyOf(producers), Map.copyOf(bySource),
                    List.copyOf(order), Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, Long.MAX_VALUE, SIBLING, Long.MAX_VALUE, FUEL, 100_000_000_000_000L),
                    Set.of(), Map.of(), false, null, withProducer ? 2 : 1, 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(truth));
            return graph;
        }

        private static Map<AEKey, PlanAmount> laneTruth() {
            return Map.of(DUST, PlanAmount.of(DUST_TRUTH), SIBLING, PlanAmount.of(SIBLING_TRUTH));
        }

        /**
         * 排产臂（HEAD 必红）：图上 DUST 有生产者。求解链就是生产那一趟的入口
         * （{@code GraphPlanningSession#solveLedger} 直调 {@code LinearPlanner}）。
         */
        void beyondLongReachDemandOnProducibleFamilySchedulesTheProducer() {
            var consumer = consumerDetails();
            var producer = producerDetails();
            var graph = graph(true, laneTruth(), consumer, producer);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(result.hasDeclined(),
                    () -> "有生产路线的一族必须走排产出口把计划配平：" + result.declineReason());
            assertEquals(new BigInteger("100000000000"),
                    result.state().craftsWide().getAmount(consumer).exactValue(),
                    "消费样板发数逐字不变：1e11");
            var firings = result.state().craftsWide().getAmount(producer);
            assertEquals(EXPECTED_PRODUCER_FIRINGS, firings.exactValue(),
                    () -> "DUST 生产者发数必须钉在 ceil(缺口 5e19 / 每发 1e6) = 5e13 的宽域真值上："
                            + " 实得=" + firings + "（HEAD 的形状是零排产增量）");
        }

        /**
         * 报缺臂：同图撤掉 DUST 的生产者 ⇒ 这一格不许静默闭账，缺口必须逐键点名 DUST、
         * 并且是宽域真值 {@code 5e19}（不是饱和哨兵）。
         *
         * <p><b>载体随引擎换、数字没换</b>：摘档前这一格在 {@code GraphPlanningSession#solveLedger} 里
         * 先由独立残量搜索判一个带账 DECLINE，但那一份判决当场被丢弃——{@code solveLedger} 接着
         * 落 {@code LinearPlanner}，交回的本来就是这份"带 5e19 缺料的残缺计划"。所以生产出口对
         * 玩家的那一句（终产物照出、缺口如实挂账）一字未变；本条从"钉中间判决"改成"钉玩家看得见
         * 的那一本账"。
         *
         * <p>闸门自己的逐键缺口账本与判决文本（{@code demand=}／{@code short=}／键名点名）不在这里丢：
         * 那是 {@code MaterialClosureGate#validate} 的直接判据，由
         * {@code MaterialClosureGateSelfProducedReachDemandTest
         * .narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage} 逐字钉着。
         */
        void beyondLongReachDemandWithoutProducerIsReportedPerKeyWithWideGap() {
            var graph = graph(false, laneTruth(), consumerDetails(), null);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(result.hasDeclined(), () -> "不许把这一格整条拒掉后静默放行：" + result.declineReason());
            assertTrue(result.simulation(), "伸手族盖不住需求的一格必须带残缺标记，不许照闭");
            var gap = result.state().missing().getAmount(DUST);
            assertEquals(SHORTAGE, gap.exactValue(),
                    () -> "缺口账必须逐键点名 DUST 且是宽域真值 1e20 − 5e19 = 5e19 枚：" + gap
                            + "（这一格越出 long，被夹成饱和哨兵就报不出这个数）");
            assertEquals(DUST_TRUTH, result.state().used().getAmount(DUST).exactValue(),
                    "网络上那 5e19 枚照腿提走，缺的只是图上没有生产者的一截");
            assertEquals(BigInteger.ZERO, result.state().missing().getAmount(SIBLING).exactValue(),
                    "没伸手的备选不许被立洞");
        }

        /** 报缺臂第二景：计划自己没动伸手那一枚的库存（实机形态：提取腿为 0）⇒ 缺口仍按"需求−存量"记。 */
        void beyondLongReachShortageSubtractsUntouchedNetworkStock() {
            var consumer = consumerDetails();
            var pattern = new GraphPattern(consumer, TARGET, 1L,
                    List.of(new GenericStack(TARGET, 1L)),
                    List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(DUST, 1L),
                                    new GraphInput.Candidate(SIBLING, 1L)),
                            Set.of(DUST, SIBLING), false)));
            var graph = new CraftGraph(TARGET,
                    Map.of(TARGET, List.of(pattern)),
                    Map.of(consumer, pattern),
                    List.of(consumer),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, Long.MAX_VALUE, SIBLING, Long.MAX_VALUE),
                    Set.of(), Map.of(), false, null, 1, 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                    DUST, PlanAmount.of(DUST_TRUTH),
                    SIBLING, PlanAmount.of(SIBLING_TRUTH))));
            // 这份计划只从备选那一枚提走 40 枚，DUST 的 5e19 库存一枚没动——排水付不满整槽，
            // 但守恒判据先红：缺口必须是 需求 1e20 − 存量 5e19，不许把没动的存量当零报成 1e20。
            var state = new GraphPlanningState(
                    Map.of(DUST, Long.MAX_VALUE, SIBLING, 40L), Map.of());
            state.addCrafting(consumer, REQUEST);
            state.extract(SIBLING, 40L, false);
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            var violation = MaterialClosureGate.validate(state, graph, REQUEST, shortage);
            assertNotNull(violation, "伸手族盖不住需求的一格不许静默闭账");
            assertEquals(SHORTAGE, shortage.get(DUST).exactValue(),
                    () -> "宽域缺口 = 1e20 − 5e19（没动的存量也要如实进缺地）：" + shortage);
            assertTrue(violation.contains("demand=100000000000000000000"), () -> violation);
            assertTrue(violation.contains("short=50000000000000000000"), () -> violation);
            assertFalse(violation.contains("9223372036854775807"),
                    () -> "判决与缺口都不许拿饱和哨兵当量：" + violation);
        }

        /** 正向臂一(a)：可行订单照旧闭合——伸手族自己就盖得住整格需求（全额提走那一型）。 */
        void feasibleOrderWithBilledReachSupplyStillClosesUnchanged() {
            var graph = graph(false, Map.of(
                    DUST, PlanAmount.of(REQUIRED),
                    SIBLING, PlanAmount.of(SIBLING_TRUTH)), consumerDetails(), null);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(result.hasDeclined(),
                    () -> "伸手族自己就盖得住整格需求，这一单可行，不许被记账拦下：" + result.declineReason());
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                    "闭合照旧、缺口口袋空");
            assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
            assertEquals(REQUIRED, result.state().used().getAmount(DUST).exactValue(),
                    "DUST 全额提走那一截逐字不变");
        }

        /** 正向臂一(b)：伸手键是真无限发射源（emitted 腿盖住需求）⇒ 照旧闭合。 */
        void feasibleOrderWithEmittableReachKeyStillClosesUnchanged() {
            var consumer = new Details(Items.REPEATER, SLOT_MULTIPLIER, new GenericStack(DUST, 1L));
            var consumerPattern = new GraphPattern(consumer, TARGET, 1L,
                    List.of(new GenericStack(TARGET, 1L)),
                    List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(DUST, 1L)),
                            Set.of(DUST), false)));
            // DUST 网络无存量，但它是发射键：求解侧把不足那一腿记进 emitted（真无限源一型）。
            var graph = new CraftGraph(TARGET,
                    Map.of(TARGET, List.of(consumerPattern)),
                    Map.of(consumer, consumerPattern),
                    List.of(consumer),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, 0L),
                    Set.of(DUST), Map.of(), false, null, 1, 1);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(result.hasDeclined(),
                    () -> "无限发射的一格不许被报缺拦下：" + result.declineReason());
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                    () -> "闭合照旧：" + shortage);
            assertTrue(shortage.isEmpty(), () -> "发射腿盖得住的需求不许立洞：" + shortage);
            assertEquals(REQUIRED, result.state().emitted().getAmount(DUST).exactValue(),
                    "发射那一腿逐字不变：1e20 枚");
        }

        /**
         * 正向臂二（守 121/123/126）：首位族无实记、只有封套的宽域形状。
         *
         * <p>手算（同 {@code ParallelEnvelopeDemandHoleTest} 那张表，只是把键名换到本类）：
         * 倍率 3 × 宇宙内那枚每发 1e18 × 4 发 = 封套 1.2e19 枚（12000000000000000000，越出 long）；
         * 备选整槽付满 12 枚、计划供给 40 枚；伸手那一枚族在网络与计划账上都没有来路 ⇒
         * <b>不记需求</b>（记了就是把许可上界当需求、把整张表顶成白卷）：表照发、实记里
         * 不含那一格（封套另记一本，减法在 {@code envelopes} 上做）。把零来路的封套塞成需求或塞回
         * 实记的变异只能红这一条臂。
         */
        void envelopeOnlyWideFamilyShipsTableWithEnvelopeExcludedFromCoverNumerator() {
            var reach = AEItemKey.of(Items.ENDER_EYE);
            var paid = AEItemKey.of(Items.SNOWBALL);
            var out = AEItemKey.of(Items.IRON_INGOT);
            var source = new Details(Items.CLOCK, new RawInput(3L,
                    new GenericStack(reach, 1_000_000_000_000_000_000L),
                    new GenericStack(paid, 1L)),
                    List.of(new GenericStack(out, 1L)));
            var pattern = new GraphPattern(source, out, 1L,
                    List.of(new GenericStack(out, 1L)),
                    List.of(new GraphInput(reach, 1_000_000_000_000_000_000L, 3L,
                            GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(paid, 1L),
                                    new GraphInput.Candidate(reach, 1_000_000_000_000_000_000L)),
                            Set.of(reach, paid), false)));
            var graph = new CraftGraph(out,
                    Map.of(out, List.of(pattern)),
                    Map.of(source, pattern),
                    List.of(source),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(reach, 0L, paid, 40L),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(Map.of(reach, 0L, paid, 40L), Map.of());
            state.addCrafting(source, 4L);
            state.extract(paid, 40L, false);

            assertNull(MaterialClosureGate.validate(state, graph, 4L, null),
                    "首位族没有来路的封套形状不许被记成需求（拦下来就是 121/123/126 的白卷病）");
            var bill = MaterialClosureGate.buildMaterialPlan(state, graph, 4L);
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "封套不进需求 ⇒ 表必须照发：" + bill.assigned());
            assertEquals(new BigInteger("12000000000000000000"), shipped.getExact(reach),
                    "封套那一笔照样进下发的表（许可上界，执行侧配额夹要读它）");
            assertEquals(12L, shipped.getLong(paid), "备选排水实记逐字不变");
            assertNotNull(bill.envelopes().get(source), "封套本照记");
            assertEquals(new BigInteger("12000000000000000000"),
                    bill.envelopes().get(source).getExact(reach), "同一笔值另记封套本");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.beyondLongReachDemandOnProducibleFamilySchedulesTheProducer", () -> new MaterialClosureGateReachDemandCases().beyondLongReachDemandOnProducibleFamilySchedulesTheProducer()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.beyondLongReachDemandWithoutProducerIsReportedPerKeyWithWideGap", () -> new MaterialClosureGateReachDemandCases().beyondLongReachDemandWithoutProducerIsReportedPerKeyWithWideGap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.beyondLongReachShortageSubtractsUntouchedNetworkStock", () -> new MaterialClosureGateReachDemandCases().beyondLongReachShortageSubtractsUntouchedNetworkStock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.feasibleOrderWithBilledReachSupplyStillClosesUnchanged", () -> new MaterialClosureGateReachDemandCases().feasibleOrderWithBilledReachSupplyStillClosesUnchanged()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.feasibleOrderWithEmittableReachKeyStillClosesUnchanged", () -> new MaterialClosureGateReachDemandCases().feasibleOrderWithEmittableReachKeyStillClosesUnchanged()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateReachDemandCases.envelopeOnlyWideFamilyShipsTableWithEnvelopeExcludedFromCoverNumerator", () -> new MaterialClosureGateReachDemandCases().envelopeOnlyWideFamilyShipsTableWithEnvelopeExcludedFromCoverNumerator())
            );
        }
    }

    /** MaterialClosureGateRingTableCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialClosureGateRingTableCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateRingTableCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateRingTableCases.ringMemberRawClaimShipsTableDespiteZeroNetFlow", () -> new MaterialClosureGateRingTableCases().ringMemberRawClaimShipsTableDespiteZeroNetFlow())
            );
        }
    }

    /** MaterialClosureGateSelfProducedReachDemandCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialClosureGateSelfProducedReachDemandCases {
        /** 伸手那一枚（实机 {@code mekanism:enriched_redstone} 的形状）。 */
        private static final AEItemKey DUST = AEItemKey.of(Items.GLOWSTONE_DUST);
        /** 同槽靠后的备选候选（排水跨候选回落那一枚）。 */
        private static final AEItemKey SIBLING = AEItemKey.of(Items.NETHER_STAR);
        private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);
        /** 生产样板的原料。 */
        private static final AEItemKey FUEL = AEItemKey.of(Items.COAL);
        /** 封套契约臂那三枚。 */
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

        /** 消费样板发数：这一格要发 188161711833434039 发（实机那一格的发数形状）。 */
        private static final long REQUEST = 188_161_711_833_434_039L;
        private static final long SLOT_MULTIPLIER = 20L;
        /** 手算需求：20 × 1 × 188161711833434039 = 3763234236668680780（装得进 long ⇒ 旧尺不记的窄值）。 */
        private static final BigInteger REQUIRED = new BigInteger("3763234236668680780");
        /** 生产样板每发产出。 */
        private static final long PRODUCER_OUTPUT_PER_FIRING = 7L;
        /** 手算排产：ceil(3763234236668680780 / 7) = 537604890952668683 发。 */
        private static final BigInteger EXPECTED_PRODUCER_FIRINGS = new BigInteger("537604890952668683");
        /** FUEL 存量：够排产那一臂（≥ 537604890952668683 枚）。 */
        private static final long FUEL_ENOUGH = 600_000_000_000_000_000L;
        /** 饱和哨兵：不许在任何判决/缺口读数里冒充量。 */
        private static final String SENTINEL = "9223372036854775807";

        private static final class RawInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;
            private final long multiplier;

            private RawInput(long multiplier, GenericStack... candidates) {
                this.candidates = candidates;
                this.multiplier = multiplier;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return multiplier;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return true;
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        private static final class Details implements IPatternDetails {
            private final AEItemKey definition;
            private final RawInput input;
            private final List<GenericStack> outputs;

            private Details(Item definitionItem, long multiplier, GenericStack... candidates) {
                this(definitionItem, new RawInput(multiplier, candidates),
                        List.of(new GenericStack(TARGET, 1L)));
            }

            private Details(Item definitionItem, RawInput input, List<GenericStack> outputs) {
                this.definition = AEItemKey.of(new ItemStack(definitionItem));
                this.input = input;
                this.outputs = outputs;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { input };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return outputs;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 消费样板：TARGET×1/发，模糊槽 [DUST@1, SIBLING@1]、倍率 20。 */
        private static Details consumerDetails(Item definitionItem) {
            return new Details(definitionItem, SLOT_MULTIPLIER,
                    new GenericStack(DUST, 1L), new GenericStack(SIBLING, 1L));
        }

        /** 消费样板（<b>定键</b>形态，窄路逐字臂用）：同一个槽但没有候选表 ⇒ 不进模糊槽宇宙。 */
        private static Details fixedConsumerDetails() {
            return new Details(Items.REPEATER, SLOT_MULTIPLIER, new GenericStack(DUST, 1L));
        }

        /** 生产样板：FUEL×1 → DUST×7/发。 */
        private static Details producerDetails() {
            return new Details(Items.COMPARATOR,
                    new RawInput(1L, new GenericStack(FUEL, 1L)),
                    List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)));
        }

        /** 消费样板的图侧条目：模糊槽候选顺位 [DUST, SIBLING]，两枚都在宇宙内。 */
        private static GraphPattern consumerPattern(Details consumer, boolean fuzzy) {
            var candidates = fuzzy
                    ? List.of(new GraphInput.Candidate(DUST, 1L), new GraphInput.Candidate(SIBLING, 1L))
                    : null;
            Set<AEKey> variants = fuzzy ? Set.of(DUST, SIBLING) : null;
            return new GraphPattern(consumer, TARGET, 1L,
                    List.of(new GenericStack(TARGET, 1L)),
                    List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                            candidates, variants, false)));
        }

        /** 生产样板的图侧条目：定键槽 FUEL×1，产出 DUST×7/发。 */
        private static GraphPattern producerPattern(Details producer) {
            return new GraphPattern(producer, DUST, PRODUCER_OUTPUT_PER_FIRING,
                    List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)),
                    List.of(new GraphInput(FUEL, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        }

        /**
         * 自产型忠实图：{@code dustStock} 是伸手那一枚的网络现量（本刀主角取 {@code 0}——两条旧腿都为零），
         * {@code siblingStock} 是同槽靠后那枚备选的现量（取够数时"整槽本来能靠回落付满"），
         * {@code withProducer} 决定图上到底有没有这一枚的生产路线，{@code fuelStock} 决定那条路线
         * 真跑得动跑不动。
         */
        private static CraftGraph dustGraph(long dustStock, long siblingStock, boolean withProducer,
                long fuelStock, Details consumer, Details producer) {
            var consumerPattern = consumerPattern(consumer, true);
            var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
            var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
            var order = new java.util.ArrayList<IPatternDetails>();
            producers.put(TARGET, List.of(consumerPattern));
            bySource.put(consumer, consumerPattern);
            order.add(consumer);
            if (withProducer) {
                var producerPattern = producerPattern(producer);
                producers.put(DUST, List.of(producerPattern));
                bySource.put(producer, producerPattern);
                order.add(producer);
            }
            return new CraftGraph(TARGET, Map.copyOf(producers), Map.copyOf(bySource),
                    List.copyOf(order), Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, dustStock, SIBLING, siblingStock, FUEL, fuelStock),
                    Set.of(), Map.of(), false, null, order.size(), 1);
        }

        /** 求解链就是生产那一趟的入口（{@code GraphPlanningSession#solveLedger} 直调 {@code LinearPlanner}）。 */
        private static SolveResult solveLedger(CraftGraph graph, long request) {
            return LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, request, false, null);
        }

        /**
         * 自产型红臂（HEAD 必红）：一格需求 {@code 3763234236668680780} 塞得进 {@code long}、
         * DUST 网络现量 0、图上却有它的生产路线（同槽备选 SIBLING 现量 4e18 够回落付满整槽）。
         *
         * <p>HEAD 的形态：{@code !shot.isFast()} 把窄值挡在门外 ⇒ 这一格既不进 demand、也没人要求排产
         * ⇒ 生产者发数 {@code 0}、整格白吃备选、照旧闭账（实机 {@code src=NONE} 却 {@code ok keys=} 那一格）。
         * 改后 demand 记上 3763234236668680780 枚 ⇒ 守恒判据在同一趟里逼出排产腿 ⇒ DUST 生产者发数必须
         * 等于 ceil(需求 / 每发 7 枚)。
         */
        void narrowProducibleReachDemandSchedulesTheProducer() {
            var consumer = consumerDetails(Items.REPEATER);
            var producer = producerDetails();
            var graph = dustGraph(0L, 4_000_000_000_000_000_000L, true, FUEL_ENOUGH, consumer, producer);
            var result = solveLedger(graph, REQUEST);
            assertFalse(result.hasDeclined(),
                    () -> "有生产路线的一族必须走排产出口把计划配平：" + result.declineReason());
            assertEquals(BigInteger.valueOf(REQUEST),
                    result.state().craftsWide().getAmount(consumer).exactValue(),
                    "消费样板发数逐字不变：188161711833434039");
            var firings = result.state().craftsWide().getAmount(producer);
            assertEquals(EXPECTED_PRODUCER_FIRINGS, firings.exactValue(),
                    () -> "DUST 生产者发数必须钉在 ceil(需求 3763234236668680780 / 每发 7)"
                            + " = 537604890952668683 的具体十位数上： 实得=" + firings
                            + "（HEAD 的形态：窄值被 !shot.isFast() 挡在门外 ⇒ 这一格根本不记账 ⇒ 零排产增量）");
            assertEquals(BigInteger.ZERO, result.state().used().getAmount(DUST).exactValue(),
                    "自产腿盖住整格 ⇒ 网络上那一枚一枚都不必提，提取腿逐字为零");
            assertEquals(EXPECTED_PRODUCER_FIRINGS, result.state().used().getAmount(FUEL).exactValue(),
                    "生产样板的原料腿逐字等于排产发数 × 每发 1 枚");
            var task = MaterialClosureGate.buildMaterialPlan(result.state(), graph, REQUEST)
                    .assigned().get(consumer);
            assertNotNull(task, "配给表照发");
            assertEquals(REQUIRED, task.getExact(DUST), "伸手键那一格的份额逐字钉在整格需求上");
            assertEquals(BigInteger.ZERO, task.getExact(SIBLING),
                    "归属从备选挪回伸手那一枚（键宇宙＝执行侧真伸手的键宇宙）");
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                    () -> "自产腿盖住之后闭合照旧、缺口口袋空：" + shortage);
            assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
        }

        /**
         * 自产型报缺臂（HEAD 必红）：同一形状的账，图上明明有这一枚的生产路线、却还没被排上
         * （{@code 池内=0}、{@code src=NONE} 的实机形态）。
         *
         * <p>出路只剩既有的 {@code shortageSink}（全仓唯一写入点）：必须逐字点名 DUST 与缺口
         * {@code 3763234236668680780} 枚，判决里 {@code producers=1} 要能指出"有路线却没排产"，
         * 也不许拿饱和哨兵当量。HEAD 在这一格连判决都没有——那一声不吭就是本刀要关的洞。
         */
        void narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage() {
            var consumer = consumerDetails(Items.REPEATER);
            var graph = dustGraph(0L, 0L, true, FUEL_ENOUGH, consumer, producerDetails());
            var state = new GraphPlanningState(Map.of(DUST, 0L, SIBLING, 0L, FUEL, FUEL_ENOUGH), Map.of());
            state.addCrafting(consumer, REQUEST);
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            var violation = MaterialClosureGate.validate(state, graph, REQUEST, shortage);
            assertNotNull(violation, () -> "窄值且网络现量 0 的一格不许再静默闭账");
            var gap = shortage.get(DUST);
            assertNotNull(gap, () -> "缺口账必须点名 DUST：" + shortage.keySet());
            assertEquals(REQUIRED, gap.exactValue(), () -> "缺口 = 需求 3763234236668680780 − 来路 0：" + gap);
            assertTrue(violation.contains(DUST.toString()), () -> "判决必须逐字点名键名：" + violation);
            assertTrue(violation.contains("demand=3763234236668680780"), () -> violation);
            assertTrue(violation.contains("producers=1"),
                    () -> "判决要能指出这一枚在图上有生产路线（有路线却没排产那一型）：" + violation);
            assertFalse(violation.contains(SENTINEL), () -> "饱和哨兵不许冒充缺口数：" + violation);
            assertFalse(shortage.containsKey(SIBLING), () -> "没伸手的备选不许被立洞：" + shortage);
        }

        /**
         * 不误拦臂（守历史条目 36）：伸手那一枚自己就盖得住整格需求的<b>窄值</b>订单照旧闭合。
         *
         * <p>手算：DUST 现量 3763234236668680780 枚 = 整格需求 ⇒ 提取腿付满、生产者一发都不必排、
         * 缺口口袋空、配给表份额逐字不变。记账一旦做成"要求首选独自盖住整槽之外的更严假设"，
         * 这一臂立刻红（发数或提取腿多出那一截）。
         */
        void feasibleNarrowOrderWithHonestReachStockStillClosesUnchanged() {
            var consumer = consumerDetails(Items.REPEATER);
            var producer = producerDetails();
            var graph = dustGraph(REQUIRED.longValueExact(), 0L, true, FUEL_ENOUGH, consumer, producer);
            var result = solveLedger(graph, REQUEST);
            assertFalse(result.hasDeclined(),
                    () -> "伸手族自己就盖得住整格需求，这一单可行，不许被记账拦下：" + result.declineReason());
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                    () -> "闭合照旧、缺口口袋空：" + shortage);
            assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
            assertEquals(REQUIRED, result.state().used().getAmount(DUST).exactValue(),
                    "DUST 提取那一腿逐字不变：3763234236668680780 枚");
            assertEquals(BigInteger.valueOf(REQUEST),
                    result.state().craftsWide().getAmount(consumer).exactValue(),
                    "消费样板发数逐字不变：188161711833434039");
            assertEquals(BigInteger.ZERO, result.state().craftsWide().getAmount(producer).exactValue(),
                    "存量已经盖住整格 ⇒ 生产者一发都不许多排，记账不许变成额外产能");
            var task = MaterialClosureGate.buildMaterialPlan(result.state(), graph, REQUEST)
                    .assigned().get(consumer);
            assertNotNull(task, "配给表照发");
            assertEquals(REQUIRED, task.getExact(DUST), "排水实记份额逐字不变");
            assertEquals(BigInteger.ZERO, task.getExact(SIBLING), "备选那一枚一枚都没被吃：逐字不变");
        }

        /**
         * 封套契约臂（守 121/123/126/196）：<b>窄值</b>、三条来路全为零（网络 0、计划 0、图上没有生产
         * 路线）而整槽由备选付满的一格 ⇒ 只该有封套。
         *
         * <p>手算表（独立于被测乘式）：
         * <pre>
         *   槽倍率 3   每模板量 2 枚   发数 5
         *   整槽需求 = 3 × 5 = 15 模板 = 30 枚（30 装得进 long ⇒ 正是"宽窄一律"会碰到的那一档）
         *   排水实付（备选 SNOW）= 15 模板 × 每模板 2 枚 = 30 枚（SNOW 现场 40 枚，付满）
         *   封套（首位 GLASS）  = 每发 2 枚 × 倍率 3 × 5 发 = 30 枚（许可上界）
         *   排水实记（剔掉封套那一本后）= SNOW 30 ≤ 供给 40 ⇒ 表照发
         * </pre>
         * 这一臂是"记账不许做成要求首选独自盖住整槽"的牙：把零来路的封套塞成需求，这里立刻红——
         * 拦下来就是 {@code 121/123/126} 定过的白卷病，也正是台账 #46/#146 的跨候选回落口径。
         */
        void unsourcedNarrowReachFamilyStillShipsEnvelopeInsteadOfDemand() {
            var source = new Details(Items.CLOCK, new RawInput(3L,
                    new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L)),
                    List.of(new GenericStack(IRON, 1L)));
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 2L, 3L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, 2L),
                                    new GraphInput.Candidate(GLASS, 2L)),
                            Set.of(GLASS, SNOW), false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(source, pattern),
                    List.of(source),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 0L, SNOW, 40L),
                    Set.of(), Map.of(), false, null, 1, 1);
            var state = new GraphPlanningState(Map.of(GLASS, 0L, SNOW, 40L), Map.of());
            state.addCrafting(source, 5L);
            state.extract(SNOW, 40L, false);

            assertNull(MaterialClosureGate.validate(state, graph, 5L, null),
                    "三条来路全为零的封套形状不许被记成需求——拦下来就是 121/123/126 的白卷病");
            var bill = MaterialClosureGate.buildMaterialPlan(state, graph, 5L);
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "封套不进需求 ⇒ 表必须照发：" + bill.assigned());
            assertEquals(30L, shipped.getLong(GLASS), "封套那一笔照样进下发的表（许可上界，配额夹读它）");
            assertEquals(30L, shipped.getLong(SNOW), "排水实记逐字不变");
            assertNotNull(bill.envelopes().get(source), "封套本照记");
            assertEquals(30L, bill.envelopes().get(source).getLong(GLASS), "同一笔值另记封套本");
        }

        /**
         * 多格共吃臂：两格同伸手 DUST（每格 20 倍率 × 1 枚 × 5 发 = 100 枚，窄值），图上这一枚有生产
         * 路线却没被排上，网络存量只有 60 枚。
         *
         * <p>手算：demand = 100 + 100 = 200，supply 只并<b>一次</b>物理量 60 ⇒ 缺口 140。
         * 存量若被逐格并两次就是 200−120 ⇒ 假闭合；一格都不并就报成 200 ⇒ 毛账报缺（历史条目 36）。
         * 两个错向在这一臂上都红，读数是 140 才说明"demand 逐格复加、supply 只并一次"。
         */
        void sharedStockAcrossTwoNarrowSlotsReportsRealGapNotSumOfDemand() {
            var first = consumerDetails(Items.REPEATER);
            var second = consumerDetails(Items.COMPASS);
            var producer = producerDetails();
            var firstPattern = consumerPattern(first, true);
            var secondPattern = consumerPattern(second, true);
            var producerPattern = producerPattern(producer);
            var graph = new CraftGraph(TARGET,
                    Map.of(TARGET, List.of(firstPattern, secondPattern), DUST, List.of(producerPattern)),
                    Map.of(first, firstPattern, second, secondPattern, producer, producerPattern),
                    List.of(first, second, producer),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, 60L, SIBLING, 0L, FUEL, 0L),
                    Set.of(), Map.of(), false, null, 3, 1);
            var state = new GraphPlanningState(Map.of(DUST, 60L, SIBLING, 0L, FUEL, 0L), Map.of());
            state.addCrafting(first, 5L);
            state.addCrafting(second, 5L);
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            var violation = MaterialClosureGate.validate(state, graph, 10L, shortage);
            assertNotNull(violation, "两格共吃一份存量、图上路线又没排上 ⇒ 不许假闭合");
            var gap = shortage.get(DUST);
            assertNotNull(gap, () -> "缺口账必须点名 DUST：" + shortage.keySet());
            assertEquals(new BigInteger("140"), gap.exactValue(),
                    () -> "缺口 = 两格需求 200 − 只并一次的存量 60 = 140 枚：" + shortage);
            assertTrue(violation.contains("demand=200"), () -> violation);
            assertFalse(shortage.containsKey(SIBLING), () -> "没伸手的备选不许被立洞：" + shortage);
        }

        /**
         * 窄路逐字臂：不涉模糊槽的普通订单（<b>定键</b>槽，DUST 现量 0 ⇒ 产能全靠排产）。
         *
         * <p>字节水位的锚点是<b>同一张图上的 AE2 原生树</b>，不是我方求解器：先在离线参照
         * （{@code Ae2TreePlanReference}，AE2 自己的 {@code CraftingTreeNode} + 沙盒）上量一次，
         * 再把 {@code bytes} 钉成等于它那一份，并同时钉住它那一侧的字面数值
         * （{@code bytes=2.771431E7}、{@code exactBytes=27714310}）。
         * 换引擎后要是与原生树不再相等，那就是真丢功能，不是"重基线"能盖过去的。
         *
         * <p>{@code journal} 是我方 undo 日志的计次（原生树没有这一格），留作绝对水位钉住
         * "一次分配都不许多"；它跟 AE2 无关，不许被当成字节水位那一格的对照片。
         * 消费者发数 {@code 1000000}、生产者发数 {@code 2857143}（DUST 需求 {@code 1e6 发 × 20 倍率
         * = 2e7 枚 ÷ 每发 7 枚 向上取整）、原料腿 {@code usedFuel=2857143}、{@code usedDust=0}
         * 与摘档前逐字相同。
         */
        void plainOrderWithoutFuzzySlotKeepsJournalAndByteWatermark() {
            var consumer = fixedConsumerDetails();
            var producer = producerDetails();
            var consumerPattern = consumerPattern(consumer, false);
            var producerPattern = producerPattern(producer);
            var graph = new CraftGraph(TARGET,
                    Map.of(TARGET, List.of(consumerPattern), DUST, List.of(producerPattern)),
                    Map.of(consumer, consumerPattern, producer, producerPattern),
                    List.of(consumer, producer),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(DUST, 0L, SIBLING, 600_000_000_000_000L, FUEL, 100_000_000_000_000L),
                    Set.of(), Map.of(), false, null, 2, 1);
            var longRequest = 1_000_000L;
            var result = solveLedger(graph, longRequest);
            assertFalse(result.hasDeclined(), () -> "定键订单照旧闭合：" + result.declineReason());
            // 锚点：同一张图（同样两张样板、同样的库存读数、同样的 1e6 发请求）上的 AE2 原生树。
            var referenceGrid = FakeGrid.builder()
                    .storage(Map.of(DUST, 0L, SIBLING, 600_000_000_000_000L, FUEL, 100_000_000_000_000L))
                    .pattern(TARGET, consumer)
                    .pattern(DUST, producer)
                    .build();
            var treePlan = new Ae2TreePlanReference(referenceGrid, referenceGrid.requester,
                    new GenericStack(TARGET, longRequest), CalculationStrategy.REPORT_MISSING_ITEMS).run();
            assertFalse(treePlan.simulation(), "AE2 树那一侧这一单配得平，水位才有意义");
            assertEquals(2.771431E7, treePlan.bytes(), 0.0,
                    "AE2 原生树自己那一侧的字节水位（AE2 改了口径这条就红）");
            assertEquals(treePlan.bytes(), result.state().bytes(), 0.0,
                    "字节水位必须逐字等于 AE2 树：换了求解引擎也不许改这份对外读数");
            assertEquals(new BigInteger("27714310"), result.state().byteAccount().exactValue().exactValue(),
                    "完整字节包含物料费 23857143 与节点费 3857167，与 AE2 字节水位一致");
            assertEquals(20, result.state().journal(), "journal mark 逐字不变（一次分配都不许多）");
            assertEquals(BigInteger.valueOf(longRequest),
                    result.state().craftsWide().getAmount(consumer).exactValue(), "消费者发数逐字不变：1e6");
            assertEquals(new BigInteger("2857143"),
                    result.state().craftsWide().getAmount(producer).exactValue(),
                    "生产者发数逐字不变：ceil(2e7 枚 / 每发 7 枚) = 2857143 发");
            assertEquals(new BigInteger("2857143"),
                    result.state().used().getAmount(FUEL).exactValue(),
                    "原料腿逐字不变：2857143 发 × 每发 1 枚 FUEL");
            assertEquals(BigInteger.ZERO,
                    result.state().used().getAmount(DUST).exactValue(),
                    "DUST 那一腿逐字不变：定键槽的 2e7 枚全由自产补齐，网络现量 0 一枚也没提");
            var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            assertNull(MaterialClosureGate.validate(result.state(), graph, longRequest, shortage),
                    () -> "闭合照旧、缺口口袋空：" + shortage);
            assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.narrowProducibleReachDemandSchedulesTheProducer", () -> new MaterialClosureGateSelfProducedReachDemandCases().narrowProducibleReachDemandSchedulesTheProducer()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage", () -> new MaterialClosureGateSelfProducedReachDemandCases().narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.feasibleNarrowOrderWithHonestReachStockStillClosesUnchanged", () -> new MaterialClosureGateSelfProducedReachDemandCases().feasibleNarrowOrderWithHonestReachStockStillClosesUnchanged()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.unsourcedNarrowReachFamilyStillShipsEnvelopeInsteadOfDemand", () -> new MaterialClosureGateSelfProducedReachDemandCases().unsourcedNarrowReachFamilyStillShipsEnvelopeInsteadOfDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.sharedStockAcrossTwoNarrowSlotsReportsRealGapNotSumOfDemand", () -> new MaterialClosureGateSelfProducedReachDemandCases().sharedStockAcrossTwoNarrowSlotsReportsRealGapNotSumOfDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateSelfProducedReachDemandCases.plainOrderWithoutFuzzySlotKeepsJournalAndByteWatermark", () -> new MaterialClosureGateSelfProducedReachDemandCases().plainOrderWithoutFuzzySlotKeepsJournalAndByteWatermark())
            );
        }
    }

    /** MaterialClosureGateCases 的独立场景夹具，按类别统一执行。 */
    public static final class MaterialClosureGateCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEItemKey SAND = AEItemKey.of(Items.SAND);
        private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
        /** 实机判决里那条自喂槽的键（{@code material closure violated: minecraft:lava}）。 */
        private static final AEKey LAVA = appeng.api.stacks.AEFluidKey.of(
                net.minecraft.world.level.material.Fluids.LAVA);
        private static final AEKey WATER = appeng.api.stacks.AEFluidKey.of(
                net.minecraft.world.level.material.Fluids.WATER);
        /**
         * 幽灵候选（实机 {@code mekansismsun:artificial_sun_port} 那一枚的形状）：网络 0 枚、图上没有生产者、
         * 该槽 {@code isValid} 不收它 ⇒ 它<b>只</b>出现在 AE2 原始候选表首位，规划宇宙里根本没有它。
         */
        private static final AEKey GHOST = AEItemKey.of(Items.STRUCTURE_VOID);
        /** 备选候选：规划宇宙里有供给，取料点顺位退到它才真付得到。 */
        private static final AEKey PAID = AEItemKey.of(Items.EMERALD);
        /**
         * 同族（同一物品本体）两枚变体：#199 回炉那一型的夹具——{@code isValid} 只收全新那一枚，
         * 声明顺位首位却是被挡下的重损镐，两枚每发件数还不一样（6 枚 vs 2 枚）。
         */
        private static final AEKey WORN = wornKey(50);
        private static final AEKey PRISTINE = wornKey(0);

        private static AEItemKey key(net.minecraft.world.item.Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

        /** 同一物品本体的两个键：带 damage 组件的镐，受损程度不同就是不同的 AEKey（同族不同枚）。 */
        private static AEItemKey wornKey(int damage) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        /** IRON×1 ← GOLD×2 的单样板图。 */
        private static CraftGraph graph(GraphPattern pattern) {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GOLD, 100L),
                    java.util.Set.of(),
                    Map.of(), false, null, 1, 1);
        }

        private static GraphPattern ironFromGoldPattern() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, 2L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        }

        void balancedLedgerPasses() {
            var pattern = ironFromGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(pattern.source(), 10);
            state.extract(GOLD, 20, false);

            String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
            assertNull(violation, () -> "平衡账目不应被拦：" + violation);
        }

        /** 每发吃 3e12 枚金：两张样板各 3.1e6 发 ⇒ 该键需求 1.8e19，远超 long 上限。 */
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
                    java.util.Set.of(),
                    Map.of(), false, null, 1, 1);
        }

        /**
         * 实机形态：请求 {@code ae2:creative_storage_cell x 1000000} 把 lava 需求顶到 1.6e19。
         * 求解侧每个站点只能按 long 记缺料，但缺料账本本身是宽域的（{@code WideKeyCounter}）——
         * 两笔天花板合计 1.8e19，正好把守恒式配平。闸门必须读宽域那本账：读 narrow 的
         * {@code missingItems()} 会把缺口截成一个 long，"账不平"就成了假象，整条规划被拒、
         * 连计划页面都进不去。
         */
        void wideMissingLedgerClosesTheConservationCheckBeyondLong() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(first.source(), 3_000_000L);
            state.addCrafting(second.source(), 3_000_000L);
            // 两个站点各自把缺料记到 long 天花板：AE2 边界只能表达到这里，账本内部是宽域。
            state.addMissing(GOLD, Long.MAX_VALUE);
            state.addMissing(GOLD, Long.MAX_VALUE);

            String violation = MaterialClosureGate.validate(
                    state, twoPatternGraph(first, second), 6_000_000L);
            assertNull(violation, () -> "两笔天花板缺料合计已覆盖需求，闸门不该拒：" + violation);
        }

        /**
         * 需求项本身超 long、而发数装得进 long（实机大订单的常态）：三数乘积必须在<b>宽域</b>上算。
         *
         * <p>旧口径在这种形状下走 long 快路径的饱和乘算，每格都被钉成 {@code Long.MAX_VALUE} 再相加
         * ⇒ 供需两侧都不再是计划里那份数。钉得越早的那一侧就是被凭空造出来的缺口，规模越大越深：
         * 同一张样板小订单算得出、大订单报"材料不足"，而 {@code missing=0} 又说求解自己认为账是平的。
         */
        void beyondLongDemandTermIsBilledOnItsExactValueNotTheCeiling() {
            var pattern = hugeGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            // 每发 3e12 枚 × 一千万发 = 3e19 枚，发数本身在 long 域内。
            state.addCrafting(pattern.source(), 10_000_000L);
            state.addMissing(GOLD, 1L);

            var shortage = new java.util.LinkedHashMap<AEKey, git.chexson.chexsonsaeutils.bigint.PlanAmount>();
            String violation = MaterialClosureGate.validate(
                    state, graph(pattern), 10_000_000L, shortage);

            assertNotNull(violation, "真需求 3e19 只配了 1 枚，该拒");
            assertEquals(new java.math.BigInteger("29999999999999999999"),
                    shortage.get(GOLD).exactValue(), "缺口账本身也要按真值记，不只判决文本");
            assertTrue(violation.contains("demand=30000000000000000000"), () -> violation);
            assertTrue(violation.contains("short=29999999999999999999"),
                    () -> "缺口按真值算，不是天花板相减：" + violation);
        }

        /** 自喂催化槽（CONSUMED_RETURNING）：起步那一眼 = 每发量 × 槽倍率，本身就 > long。 */
        private static GraphPattern lavaSelfFeedingPattern() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(LAVA, 3_000_000_000_000L, 3_000_000_000L,
                            GraphInput.Mode.CONSUMED_RETURNING, null, null, null, false)));
        }

        /** 模具槽（RETURNED，投多少原样退多少）：同一条起步量，计费键与归还键都是它自己。 */
        private static GraphPattern waterMouldPattern() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(WATER, 5_000_000_000_000L, 4_000_000_000L,
                            GraphInput.Mode.RETURNED, WATER, null, null, false)));
        }

        /**
         * 实机 2026-09-24 13:50 的第一条判决证据：{@code short=9223372036854775807} <b>恰好</b>
         * 是 {@code Long.MAX_VALUE}，而 demand − supply 也正是这个数 ⇒ 需求侧还剩一条把单项钉成
         * 天花板的饱和腿，且它不在 {@code mul3} 上。
         *
         * <p>这条腿就是回流/自喂槽的<b>起步量</b>：整批只有首发射真实提取，所以它压根不乘发数，
         * 只算「每发量 × 槽倍率」。lava 这类自喂催化物在 {@code > long} 的计划里，这一乘自己就撞顶
         * （每发 3e12 枚 × 倍率 3e9 = 9e21 枚）。钉成天花板的这一项既不是需求也不是供给，
         * 而是凭空立在 demand 上的一格"看起来刚好差一个 long"的假缺口。
         */
        void beyondLongSelfFeedingStartupIsBilledOnItsExactValueNotTheCeiling() {
            var pattern = lavaSelfFeedingPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            state.addCrafting(pattern.source(), 2L);

            var shortage = new java.util.LinkedHashMap<AEKey,
                    git.chexson.chexsonsaeutils.bigint.PlanAmount>();
            String violation = MaterialClosureGate.validate(state, graph(pattern), 2L, shortage);

            assertNotNull(violation, "9e21 枚 lava 一枚没配，该拒");
            assertEquals(new java.math.BigInteger("9000000000000000000000"),
                    shortage.get(LAVA).exactValue(), "自喂槽的起步量按真值入账，缺口账也按真值报");
            assertTrue(violation.contains("demand=9000000000000000000000"), () -> violation);
            assertTrue(violation.contains("short=9000000000000000000000"),
                    () -> "缺口不能是天花板相减：" + violation);
            assertFalse(violation.contains("9223372036854775807"),
                    () -> "判决里出现 Long.MAX_VALUE 就是那条饱和腿的指纹：" + violation);
        }

        /** 同一条尺子打在模具槽（RETURNED）上：起步量 2e22 枚，退回来的只有倍率那一份。 */
        void beyondLongReturnedMouldStartupIsBilledOnItsExactValueNotTheCeiling() {
            var pattern = waterMouldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            state.addCrafting(pattern.source(), 2L);

            var shortage = new java.util.LinkedHashMap<AEKey,
                    git.chexson.chexsonsaeutils.bigint.PlanAmount>();
            String violation = MaterialClosureGate.validate(state, graph(pattern), 2L, shortage);

            assertNotNull(violation, "2e22 枚水只有 4e9 枚退得回来，该拒");
            // demand = 5e12 × 4e9 = 2e22，supply = multiplier = 4e9 ⇒ short = 1.9999999999996e22
            assertEquals(new java.math.BigInteger("19999999999996000000000"),
                    shortage.get(WATER).exactValue(), () -> violation);
            assertFalse(violation.contains("9223372036854775807"),
                    () -> "模具槽那一眼也不能被钉成天花板：" + violation);
        }

        /**
         * 同一类截断也存在于"本作业产出"那本账：产料是逐笔累加的，两笔天花板合计 1.8e19，
         * narrow 视图会把它读回一个 long，于是需求侧又"假不平"。
         * （"已从网络提走"那本账是峰值语义，单键不可能越过一次 long 提取，故不在此构造。）
         */
        void wideEmittedLedgerClosesTheConservationCheckBeyondLong() {        var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            state.addCrafting(first.source(), 3_000_000L);
            state.addCrafting(second.source(), 3_000_000L);
            state.emitItems(GOLD, Long.MAX_VALUE);
            state.emitItems(GOLD, Long.MAX_VALUE);

            String violation = MaterialClosureGate.validate(
                    state, twoPatternGraph(first, second), 6_000_000L);
            assertNull(violation, () -> "两笔天花板产出合计已覆盖需求，闸门不该拒：" + violation);
        }

        /** 反例：宽域合计仍盖不住需求，那就是真算料错误，必须照拦。 */
        void shortWideMissingIsStillCaught() {
            var first = hugeGoldPattern();
            var second = hugeGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(first.source(), 3_100_000L);
            state.addCrafting(second.source(), 3_100_000L);
            state.addMissing(GOLD, 1_000L);
            state.addMissing(GOLD, 1_000L);

            assertNotNull(MaterialClosureGate.validate(
                            state, twoPatternGraph(first, second), 6_000_000L),
                    "宽域缺料盖不住需求就是算料错误，不能放行");
        }

        void underExtractedInputIsCaught() {
            // 输入消耗漏记形态：crafts 全量计 demand，used 只有一半——
            // 「缺料计划判可行」正是这个形态。
            var pattern = ironFromGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(pattern.source(), 10);
            state.extract(GOLD, 10, false);

            String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
            assertNotNull(violation, "输入漏记必须被闸门拦下");
            assertTrue(violation.contains("gold_ingot"), violation);
        }

        void outputShortfallAgainstFinalIsCaught() {
            // 产出凭空缩水形态：crafts 只有 5 次，final 却要 10——
            // supply(IRON)=5 < demand(IRON)=10。
            var pattern = ironFromGoldPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
            state.addCrafting(pattern.source(), 5);
            state.extract(GOLD, 10, false);

            String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
            assertNotNull(violation, "产出缩水必须被闸门拦下");
            assertTrue(violation.contains("iron_ingot"), violation);
        }

        void siblingComponentSupplyCannotSatisfyExactDemand() {
            var firstStack = new ItemStack(Items.AMETHYST_SHARD);
            firstStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("version-1"));
            var first = AEItemKey.of(firstStack);
            var secondStack = new ItemStack(Items.AMETHYST_SHARD);
            secondStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("version-2"));
            var second = AEItemKey.of(secondStack);
            Map<appeng.api.stacks.AEKey, List<appeng.api.stacks.AEKey>> variants =
                    Map.of(first, List.of(first, second), second, List.of(first, second));
            var graph = new CraftGraph(second,
                    Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(first, 1L), java.util.Set.of(), variants,
                    false, null, 0, 0);
            var state = new GraphPlanningState(Map.of(first, 1L), variants);
            state.extract(first, 1L, false);

            String violation = MaterialClosureGate.validate(state, graph, 1L);

            assertNotNull(violation, "同一物品的其它组件版本不能满足精确需求");
            assertTrue(violation.contains("supply=0"), violation);
            assertTrue(violation.contains("demand=1"), violation);
        }

        /** 靠前候选扣不完退到次候选能补齐时，排水判据必须放行，不能一刀切拒绝模糊槽。 */
        void fuzzySlotSpillingToNextCandidatePasses() {
            var pattern = ironFromFuzzySlot();
            var state = new GraphPlanningState(Map.of(GLASS, 400L, SAND, 60L), Map.of());
            state.addCrafting(pattern.source(), 100);
            state.extract(GLASS, 400, false);
            state.extract(SAND, 60, false);

            String violation = MaterialClosureGate.validate(state, graph(pattern), 100);
            assertNull(violation, () -> "退路候选补得齐整批时不应被拦：" + violation);
        }

        /**
         * 实机 22:11 假拒形态：crafting_pattern 的模糊槽 multiplier=3，候选全是每份 1
         * 的玻璃族键，族内份数与需求逐字相等。一发在 AE2 的 {@code prepareSingleFiring}
         * 里跨候选续取，所以按"单键盖住整数发"收费会拒掉可执行的表——而末级算法也规划
         * 不出时 DECLINE 是终局，作业直接起不来。
         */
        void fuzzySlotSplitsOneFiringAcrossCandidates() {
            var pattern = ironFromThreeUnitFuzzySlot();
            var state = new GraphPlanningState(Map.of(GLASS, 5L, SAND, 1L), Map.of());
            state.addCrafting(pattern.source(), 2);
            state.extract(GLASS, 5, false);
            state.extract(SAND, 1, false);

            String violation = MaterialClosureGate.validate(state, graph(pattern), 2);
            assertNull(violation, () -> "份数恰好盖住需求时不应被拦：" + violation);
        }

        /**
         * 同槽跨候选回落不是算料错误（2026-09-19 21:58 实机定口径）：首选族整槽付不完时，
         * 排水把差的那几份记到同槽下一个候选键上，两键相加正好等于整槽应吃——实机那条槽
         * {@code refinedstorage:silicon=2570673696 + ae2:silicon=480 == firingNeed}，且 CPU 现场
         * 真有那 480 枚。这张表必须照常下发：扣表会把计划打回提交期重算，尾段反而更长。
         * 判决只留 WARN 行点名"哪张样板靠回落过关"。
         */
        void payerFamilyPaidBySameSlotFallbackStillShipsTable() {
            var pattern = ironFromGlassOrSandFuzzy(3L);
            var glassProducer = glassFromSnowPattern();
            var state = new GraphPlanningState(Map.of(GLASS, 5L, SNOW, 1L), Map.of());
            state.addCrafting(pattern.source(), 2);
            state.extract(GLASS, 5, false);
            state.extract(SNOW, 1, false);

            var table = MaterialClosureGate.buildMaterialPlan(
                    state, glassFuzzyGraph(pattern, glassProducer), 2L).assigned();

            var task = table.get(pattern.source());
            assertNotNull(task, "跨候选回落的整槽表不能扣下");
            // 账本换 WideKeyCounter 后的等价读法（本用例量级远未越界，窄读数即真值）
            assertEquals(6L, task.getLong(GLASS) + task.getLong(SNOW),
                    () -> "整槽 3x2 枚必须由同槽候选合计付清, table=" + task);
        }

        /**
         * {@code billReachKeySlot} 的数值口径（台账 #114 的第一条钉）：封套记的是<b>整槽份额</b>。
         *
         * <p>形状：原始候选顺位 [玻璃首位, 雪块备选]，规划 ranked 把备选排在前先付清
         * （实机 lane c1fbde62 的"首位候选从没被排水付到"）。排水实记只有雪块 2 枚——玻璃族
         * 在本格的顺位实吃恒为 0（这正是它不进册的前提）；补记落下整槽份额
         * 槽倍率 1 × 每发 1 枚 × 2 发 = 2 枚。同槽两族并存、合计 4 枚 = 2× 槽需，这是 0fbec2b
         * 定版的封套口径（"执行侧若向首位族伸手，至多整格"），不是排水拆分。
         *
         * <p>本用例走 {@code buildMaterialPlan} 真入口逐键断言份数：谁把它收窄成"排水实吃"（=0）、
         * 改成"族已在册也重写"、或干脆不补记，这里就红。既有的两条相关用例守的是别的东西——
         * {@code ParallelDeclaredBatchObligationTest} 的 M3 两型（{@code planBalanceNamesReachKeys...}/
         * {@code planBalanceOmitsReachKeys...}）只断言日志字符串形状，且表是手搓的、根本不经过这一笔；
         * M1 端到端臂只断言"份额为正且两臂相等"，不钉这个数到底是几。
         */
        void reachKeyEnvelopeShipsWholeSlotUnits() {
            var source = new ReachDetails();
            var pattern = reachEnvelopePattern(source);
            var table = MaterialClosureGate.buildMaterialPlan(
                    reachEnvelopeState(source, 10L), reachEnvelopeGraph(pattern), 2L).assigned();

            var task = table.get(source);
            assertNotNull(task, "玻璃有 10 枚计划供给消化封套，表不该被扣下");
            assertEquals(2L, task.getLong(SNOW), "排水实付的那 2 枚记在备选键上");
            assertEquals(2L, task.getLong(GLASS),
                    "补记按整槽份额（1×1×2 枚）入账——该族在本格的顺位实吃是 0，不是这一笔的数");
            assertEquals(4L, task.getLong(SNOW) + task.getLong(GLASS),
                    () -> "封套与实记同格并存：该格合计 2× 槽需是定版形状, table=" + task);
        }

        /**
         * 封套不算排水实记（本刀的核心）。
         *
         * <p>改前的期望是"该族计划供给盖不住封套那一笔 ⇒ 整张配给表被判成白卷"——这条期望钉的正是
         * <b>缺陷形状</b>而非需求：封套记的是"执行侧若向首位族伸手，至多整格"的<b>许可上界</b>，
         * 不是计划真从这一族取走的量。首位族规划供给为 0 是常态，把封套当实取就会让整张配给表
         * 被顶回去 ⇒ 执行侧退回提交期自算那一臂（两臂宇宙不同 ⇒ 假洞 {@code hole=960}）。
         * 这是规模相关的假缺口。
         *
         * <p>改后期望：首位族规划供给为 0 时表照发、封套份额逐字不变（仍是整槽 1×1×2=2 枚）。
         * 供给恰好 2 枚时下发内容逐字相同——封套记不记进实记都不改<b>表本身</b>，只改"是否
         * 因它交白卷"。
         */
        void reachKeyEnvelopeShipsWhenPrimaryFamilyUnsupplied() {
            var starved = new ReachDetails();
            var starvedGraph = reachEnvelopeGraph(reachEnvelopePattern(starved));
            assertNull(MaterialClosureGate.validate(reachEnvelopeState(starved, 0L), starvedGraph, 2L),
                    "整槽由备选付满时守恒闸门闭合——封套的超配不进守恒判据");
            var table = MaterialClosureGate.buildMaterialPlan(
                    reachEnvelopeState(starved, 0L), starvedGraph, 2L).assigned();
            var starvedTask = table.get(starved);
            assertNotNull(starvedTask,
                    () -> "首位族规划供给为 0：封套不进需求 ⇒ 表照发（旧口径把整表顶成白卷）：" + table);
            assertEquals(2L, starvedTask.getLong(GLASS),
                    "封套份额＝整槽 1×1×2 枚，逐字不变：记在表里给执行侧当上界，只是不算排水实记");
            assertEquals(2L, starvedTask.getLong(SNOW), "排水实付的 2 枚照记在备选键上");

            var covered = new ReachDetails();
            var coveredTask = MaterialClosureGate.buildMaterialPlan(
                    reachEnvelopeState(covered, 2L), reachEnvelopeGraph(reachEnvelopePattern(covered)), 2L)
                    .assigned().get(covered);
            assertNotNull(coveredTask, "该族供给恰好盖得住封套那一笔时表必须下发");
            assertEquals(2L, coveredTask.getLong(GLASS), "下发的那一笔仍是整槽份额，与上一例逐字相同");
            assertEquals(2L, coveredTask.getLong(SNOW), "排水实记不受这一崖口影响");
        }

        /**
         * "封套不算排水实记"的第二条独立数值钉（期望来自手算 oracle，不用被测乘式重算）。
         *
         * <p>手算表（每份几、几发、该记多少）：
         * <pre>
         *   槽倍率 = 3   每模板量 = 2 枚   发数 = 5
         *   整槽需求 = 3 × 5 = 15 模板 = 15 × 2 = 30 枚
         *   排水实付(备选 SNOW) = 15 模板 × 每模板 2 枚 = 30 枚   （玻璃现场量为 0，付不到）
         *   封套(首位 GLASS)   = 每发 2 枚 × 槽倍率 3 × 5 发     = 30 枚（=整槽，许可上界）
         *   SNOW 计划供给 = 40 枚(used)   GLASS 计划供给 = 0 枚
         *   ⇒ 排水实记（剔掉封套那一本后）只剩 SNOW 30 ≤ 40 ⇒ 表照发；GLASS 份额 30、SNOW 份额 30
         * </pre>
         * 未修主代码上：零来路的封套 GLASS 30 被塞进 demand、计划供给 0 ⇒ 守恒判据把这一格判成平
         * 不过 ⇒ {@code Map.of()} ⇒ {@code table.get(source)} 为 null，本用例 {@code assertNotNull} 直接红。
         */
        void envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied() {
            var source = new ReachDetails(new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));
            var state = envelopeStarvedState(source);

            var task = MaterialClosureGate.buildMaterialPlan(
                    state, envelopeStarvedGraph(source), 5L).assigned().get(source);
            assertNotNull(task, "首位族计划供给 0：封套不进需求 ⇒ 表照发");
            assertEquals(30L, task.getLong(GLASS), "封套＝整槽 2×3×5=30 枚（许可上界）");
            assertEquals(30L, task.getLong(SNOW), "排水实付＝15 模板 × 每模板 2 枚 = 30 枚");
            assertEquals(60L, task.getLong(SNOW) + task.getLong(GLASS),
                    () -> "封套与实记同格并存：合计 2× 槽需 30 枚是封套口径, table=" + task);
        }

        /**
         * 封套那一本要<b>随契约一起下发</b>（本刀的另一半）：执行侧 {@code hole=} 拿它当被减数，
         * 不减就把"整格许可上界"当"计划要吃却没来源的料"报成假洞。
         *
         * <p>夹具与期望都沿用 {@code envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied} 那张手算表
         * （槽倍率 3 × 每模板 2 枚 × 5 发 = 整槽 30 枚；备选 SNOW 付满 30 枚、首位 GLASS 实吃 0）：
         * <pre>
         *   assigned（下发的整本）: GLASS = 30（封套）  SNOW = 30（排水实记）
         *   envelopes（封套另本）  : GLASS = 30          SNOW = 不落账
         *   相减剩下的实记          : GLASS = 0           SNOW = 30
         * </pre>
         * 两头各钉一刀：① 这本交空 ⇒ 执行侧那侧就把上界当实记（假洞 {@code hole=30}，还按大小
         * 排在真洞前面）；② 顺手把封套从<b>下发表</b>里也摘掉 ⇒ 执行侧退回 {@code noBasisReach}
         * 那一型（首位族现场有量却夹成 0）。所以两本都要逐字对。
         */
        void envelopeBookShipsAsSeparateLedgerWithoutTouchingTheTable() {
            var source = new ReachDetails(new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));
            var bill = MaterialClosureGate.buildMaterialPlan(
                    envelopeStarvedState(source), envelopeStarvedGraph(source), 5L);

            var shipped = bill.assigned().get(source);
            var booked = bill.envelopes().get(source);
            assertNotNull(shipped, "下发的整本里封套那一笔不许被摘掉");
            assertNotNull(booked, "封套那一本必须随同一趟账出门（这本账是 hole 的被减数）");
            assertEquals(30L, booked.getLong(GLASS), "同一笔值：与下发表里那一笔逐字相同");
            assertEquals(shipped.getLong(GLASS), booked.getLong(GLASS),
                    () -> "封套在下发表与封套本是同一笔，不许两处各算一份, 表=" + shipped);
            assertEquals(0L, booked.getLong(SNOW),
                    "排水实记不进封套本：减多了就把真洞也抹平（cover 与 hole 都会少核一笔）");
            assertEquals(30L, shipped.getLong(SNOW), "下发表的排水实记逐字不变");
        }

        /** 封套手算夹具的槽形：原始候选顺位 [玻璃@2 首位, 雪块@2 备选]，规划 ranked 备选在前。 */
        private static GraphPattern envelopeStarvedPattern(IPatternDetails source) {
            return new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 2L, 3L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, 2L),
                                    new GraphInput.Candidate(GLASS, 2L)),
                            java.util.Set.of(GLASS, SNOW), false)));
        }

        /** 封套手算夹具的图。 */
        private static CraftGraph envelopeStarvedGraph(IPatternDetails source) {
            var pattern = envelopeStarvedPattern(source);
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 0L, SNOW, 40L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** 封套手算夹具的账：5 发全跑，雪块提 40 枚，玻璃计划供给 0（不提取）。 */
        private static GraphPlanningState envelopeStarvedState(IPatternDetails source) {
            var state = new GraphPlanningState(Map.of(GLASS, 0L, SNOW, 40L), Map.of());
            state.addCrafting(source, 5L);
            state.extract(SNOW, 40L, false);
            return state;
        }

        /**
         * 计费键必须是执行侧真付得到的那一枚（台账 #194，实机 {@code ae2:creative_storage_cell x 1e11}
         * 尾段卡死那一格）。
         *
         * <p>实机那一格的读数：{@code slot#0[... 每发=1, 池内=0, 配额=2374101614985, 可付发=0]} 配
         * {@code 回补受阻[... got=0_why=ranDry_网络sim=0_同族键数=0_同族[]下界]}，同窗规划侧
         * {@code Graph planning wide supply truth: supply={}} 且全场零条 deferred shortage。
         * 那一枚配额就出自 {@code billReachKeySlot}：reach 键无条件取 AE2 原始候选表<b>首位</b>，
         * 不复核这一格真付得到付不到 ⇒ 网络里根本没有的键被记上一整槽天量 ⇒ 执行侧
         * {@code quotaExact}（{@code ParallelPlanReservation}:690-694）因 {@code hasFamilyEntry}
         * 为真而不交 null ⇒ 回补腿（{@code ParallelBatchExtraction}:1743）永远要补
         * {@code 2.37e12} 枚不存在的料，逐次 {@code got=0 ⇒ ranDry}（约 :1794/:1832）。
         *
         * <p>手算表（槽倍率 1 × 每模板 1 枚 × 4 发 = 整槽 4 枚；候选宇宙只收 {@code PAID}）：
         * <pre>
         *   原始候选顺位 = [GHOST@1 首位, PAID@1 备选]，isValid 只收 PAID
         *   规划 ranked  = [PAID@1]                    ⇒ GHOST 不在配给表的宇宙里
         *   排水实付(PAID) = 4 枚（used 4）  GHOST 网络 0 枚、无生产者
         *   ⇒ 期望：PAID 份额 4、GHOST 一枚都不进 assigned、也不进 envelopes
         * </pre>
         * GHOST 不在册 ⇒ {@code hasFamilyEntry} 为假 ⇒ 伸手口径（{@code slot != null}）对它交
         * {@code null}（"无配额口径"，不夹），而不是交一个执行侧永远凑不齐的天量。这条是本用例的
         * 下游推论（{@code ParallelPlanReservation} 是包级私有类，gate 级用例造不出它），
         * 所以这里把它的<b>前件</b>逐字钉死：这本账非空、且没有 GHOST 这一族。
         *
         * <p>未修主代码上应交出 {@code task.getLong(GHOST) == 4}（整槽封套记在幽灵键上）⇒ 红。
         */
        void ghostFirstCandidateNeverEntersTheQuotaBook() {
            var source = ReachDetails.accepting(java.util.Set.of(PAID),
                    new GenericStack(GHOST, 1L), new GenericStack(PAID, 1L));
            var pattern = ghostReachPattern(source);
            var state = ghostReachState(source);
            String violation = MaterialClosureGate.validate(state, ghostReachGraph(pattern), 4L);
            assertNull(violation, () -> "整槽由付得到的那一枚付满，守恒闸门该放行：" + violation);

            var bill = MaterialClosureGate.buildMaterialPlan(state, ghostReachGraph(pattern), 4L);
            var task = bill.assigned().get(source);
            assertNotNull(task, () -> "这一格付得到料，配给表不该交白卷：" + bill.assigned());
            assertEquals(4L, task.getLong(PAID), "排水实付的 4 枚照记在付得到的那一枚上");
            assertEquals(0L, task.getLong(GHOST),
                    () -> "付不到的首位候选一枚都不许进配给表（它是回补腿那句 ranDry 的来路）, 表=" + task);
            assertFalse(hasFamilyEntry(task, GHOST),
                    () -> "quotaExact 的前件：这本账非空却没这一族 ⇒ 伸手口径对它交 null, 表=" + task);
            var envelope = bill.envelopes().get(source);
            assertTrue(envelope == null || envelope.getLong(GHOST) == 0L,
                    () -> "封套本同样不许给幽灵键记一笔, 封套=" + envelope);
        }

        /**
         * 首位付不到时按顺位<b>退到下一候选</b>计费，而不是摘掉这一笔（台账 #194 的另一半）。
         *
         * <p>沿用 {@link #envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied} 那张手算表
         * （槽倍率 3 × 每模板 2 枚 × 5 发 = 整槽 30 枚，备选 SNOW 付满 30、玻璃实吃 0），只在
         * 原始候选表<b>开头</b>多塞一枚付不到的幽灵：宇宙（候选 × {@code isValid}）仍然收玻璃，
         * 所以封套必须原样落在玻璃上＝30 枚，幽灵键 0 枚。
         *
         * <p>未修主代码上 reach 键是无条件的首位＝GHOST（每模板 1 枚）⇒ 封套记成 {@code 3×1×5=15}
         * 枚幽灵、玻璃那一笔根本没记 ⇒ 两条断言一起红。
         */
        void reachKeyFallsBackToNextPayableCandidateWhenFirstIsGhost() {
            var source = ReachDetails.accepting(java.util.Set.of(GLASS, SNOW),
                    new GenericStack(GHOST, 1L), new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L));

            var bill = MaterialClosureGate.buildMaterialPlan(
                    envelopeStarvedState(source), envelopeStarvedGraph(source), 5L);
            var task = bill.assigned().get(source);
            assertNotNull(task, () -> "退到的那一枚付得到 ⇒ 表照发：" + bill.assigned());
            assertEquals(0L, task.getLong(GHOST),
                    () -> "退让不许把幽灵键留在账上, 表=" + task);
            assertEquals(30L, task.getLong(GLASS),
                    () -> "封套落在顺位退到的那一枚：2×3×5=30 枚, 表=" + task);
            assertEquals(30L, task.getLong(SNOW), "排水实记不受退让影响");
            var envelope = bill.envelopes().get(source);
            assertNotNull(envelope, "封套那一本仍要随同一趟账出门（hole= 的被减数）");
            assertEquals(30L, envelope.getLong(GLASS), "封套逐字不变，只是换了它记在哪一枚上");
        }

        /** 幽灵首位候选夹具的槽形：候选宇宙只收 PAID（GHOST 被 isValid 挡在规划宇宙外）。 */
        private static GraphPattern ghostReachPattern(IPatternDetails source) {
            return new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(PAID, 1L, 1L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(PAID, 1L)),
                            java.util.Set.of(PAID), false)));
        }

        /**
         * 族判据不覆盖"同族在册、声明精确键被 {@code isValid} 挡下"那一型（台账 #199 回炉第一刀）。
         *
         * <p>{@code 2527e4e} 换的是"整族都不在宇宙里"的幽灵键，落点仍是<b>声明那枚</b>的键与模板量；
         * 而宇宙判据只到族（同族有任一枚就算付得到）。同族里只有一枚在册、{@code reach} 恰是被挡下的
         * 那一枚时，封套记在挡下的那枚键与它的模板量上——执行侧顺位真伸手的是宇宙内那一枚，
         * 每发件数还不一样 ⇒ 照样记出一笔花不到的量（同一个形状，只是换了挡住的位置）。
         *
         * <p>手算表（槽倍率 3 × 5 发 ＝ 15 模板整格）：
         * <pre>
         *   原始候选顺位 = [重损镐@6 首位（isValid 不收）, 全新镐@2（宇宙内）, 雪块@2（备选）]
         *   规划 ranked  = [雪块@2, 全新镐@2]        ⇒ 这一族在宇宙里只有全新镐，每发 2 枚
         *   排水实付(SNOW) = 15 模板 × 2 枚           = 30 枚（计划供给 40 付满整格）
         *   封套  期望 = 倍率 3 × 每发 2 枚 × 5 发    = 30 枚，记在<b>全新镐</b>那一枚上
         *   HEAD 实得 = 记在<b>重损镐</b>上、按它自己那一份 6 枚 = 3×6×5 = 90 枚
         * </pre>
         * 未修主代码上 {@code task.getLong(PRISTINE)} 交 0（那一枚压根没被记）、
         * {@code task.getLong(WORN)} 交 90 ⇒ 三条断言一起红。
         */
        void envelopeIsBilledOnTheInUniverseVariantNotTheBlockedDeclaredSibling() {
            var source = ReachDetails.accepting(java.util.Set.of(SNOW, PRISTINE),
                    new GenericStack(WORN, 6L), new GenericStack(PRISTINE, 2L),
                    new GenericStack(SNOW, 2L));

            var bill = MaterialClosureGate.buildMaterialPlan(
                    sameFamilyEnvelopeState(source), sameFamilyEnvelopeGraph(source), 5L);
            var task = bill.assigned().get(source);
            assertNotNull(task, () -> "整格由雪块付满 ⇒ 表照发，不该交白卷：" + bill.assigned());
            assertEquals(30L, task.getLong(SNOW), "排水实记逐字不变（3×5 模板 × 每发 2 枚）");
            assertEquals(30L, task.getLong(PRISTINE),
                    () -> "封套必须落在宇宙内真付得到的那一枚、按它那一份每发件数（3×2×5=30）, 表=" + task);
            assertEquals(0L, task.getLong(WORN),
                    () -> "被 isValid 挡下的那一枚一枚都不许进配给表（它就是回补腿 got=0 ⇒ ranDry 的来路）, 表="
                            + task);
            var envelope = bill.envelopes().get(source);
            assertNotNull(envelope, "封套那一本仍要随同一趟账出门（hole= 的被减数）");
            assertEquals(30L, envelope.getLong(PRISTINE), "封套本与下发表同一笔、同一个键");
            assertEquals(0L, envelope.getLong(WORN), "封套本也不许给挡下的那一枚记一笔");
        }

        /**
         * 正向臂（同一把尺的另一侧）：首位声明候选<b>本来就在宇宙内</b>时，键与件数逐字不变。
         *
         * <p>与反向臂同一份宇宙、同一串乘式，只把被挡下的那一枚从首位撤掉 ⇒ 封套仍落在
         * 全新镐 30 枚、雪块实记仍 30 枚。这一例在改前改后都必须绿：换尺不许动已经对的那一型。
         */
        void envelopeStaysOnTheSameNumbersWhenTheDeclaredFirstCandidateIsTheUniverseOne() {
            var source = ReachDetails.accepting(java.util.Set.of(SNOW, PRISTINE),
                    new GenericStack(PRISTINE, 2L), new GenericStack(SNOW, 2L));

            var bill = MaterialClosureGate.buildMaterialPlan(
                    sameFamilyEnvelopeState(source), sameFamilyEnvelopeGraph(source), 5L);
            var task = bill.assigned().get(source);
            assertNotNull(task, () -> "正向臂前提：表照发：" + bill.assigned());
            assertEquals(30L, task.getLong(PRISTINE), "首位就在宇宙内 ⇒ 封套的键与量逐字不变");
            assertEquals(30L, task.getLong(SNOW), "排水实记逐字不变");
            assertEquals(0L, task.getLong(WORN), "这一型里重损镐从未被声明，表上不该凭空多它一族");
        }

        /** 同族两枚夹具的图：雪块计划供给 40、镐的两枚变体都是 0（这一族只剩封套那一笔）。 */
        private static CraftGraph sameFamilyEnvelopeGraph(IPatternDetails source) {
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(WORN, 6L, 3L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, 2L),
                                    new GraphInput.Candidate(PRISTINE, 2L)),
                            java.util.Set.of(SNOW, PRISTINE), false)));
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(SNOW, 40L, WORN, 0L, PRISTINE, 0L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** 同族两枚夹具的账：5 发全跑，只从雪块提 40 枚（镐那一族计划供给 0）。 */
        private static GraphPlanningState sameFamilyEnvelopeState(IPatternDetails source) {
            var state = new GraphPlanningState(Map.of(SNOW, 40L, WORN, 0L, PRISTINE, 0L), Map.of());
            state.addCrafting(source, 5L);
            state.extract(SNOW, 40L, false);
            return state;
        }

        /** 幽灵首位候选夹具的图：GHOST 网络 0 枚、图上没有它的生产者。 */
        private static CraftGraph ghostReachGraph(GraphPattern pattern) {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GHOST, 0L, PAID, 4L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** 幽灵首位候选夹具的账：4 发全跑，只从 PAID 提 4 枚。 */
        private static GraphPlanningState ghostReachState(IPatternDetails source) {
            var state = new GraphPlanningState(Map.of(GHOST, 0L, PAID, 4L), Map.of());
            state.addCrafting(source, 4L);
            state.extract(PAID, 4L, false);
            return state;
        }

        /**
         * 与 {@code ParallelPlanReservation#hasFamilyEntry} 同一口径（物品本体族、含 0 值条目）：
         * 这里是"伸手口径有没有配额基准"的前件读数，不是第二道判据。
         */
        private static boolean hasFamilyEntry(WideKeyCounter amounts, AEKey key) {
            var family = key.getPrimaryKey();
            for (AEKey candidate : amounts.keys()) {
                if (candidate != null && family.equals(candidate.getPrimaryKey())) {
                    return true;
                }
            }
            return false;
        }


        /**
         * 条目①（任务 #219）在<b>窄量级</b>上的同一型：排水<b>实记</b>由 {@code emitted} 那一腿盖住时，
         * 表必须照发。
         *
         * <p>手算表：单候选模糊槽（候选只有玻璃），每发 1 枚 × 槽倍率 1 × 2 发 = 2 枚，全额由<b>排水
         * 实付</b>记在玻璃键上（该族已被本格排水付到、族已在册，故不补封套）。玻璃的计划供给只来自
         * {@code emitted}（2 枚）。
         *
         * <p><b>这一臂原来钉的是两套宇宙并存的假白卷</b>：旧 cover 自己拼腿（只认「自产 + used +
         * 环种子」、不认 {@code emitted}），于是同一趟账里闸门判平的表在出口被判"超配"、整张扣下
         * ——执行侧退回提交期自算那一臂。并成一套账后这里读到的就是 {@code 2 >= 2}：判决照旧是
         * "超配才扣表"，只是这一判用的同一把尺不再凭空造缺口（越界那一型见
         * {@link #supplyOnlyInEmittedLegBeyondLongStillShipsTheTable}）。
         */
        void drainClaimFundedByEmittedLegShipsTable() {
            var source = new FakeDetails();
            var pattern = new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(GLASS, 1L)),
                            java.util.Set.of(GLASS), false)));
            var state = new GraphPlanningState(Map.of(GLASS, 0L), Map.of());
            state.addCrafting(source, 2L);
            state.emitItems(GLASS, 2L);   // 计划自产那一腿：emitted 就是这份表的来路
            assertNull(MaterialClosureGate.validate(state, graph(pattern), 2L),
                    "守恒/排水由 emitted 盖住 ⇒ 闸门放行，才轮到 cover 核排水实记");

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph(pattern), 2L);
            var shipped = bill.assigned().get(source);
            assertNotNull(shipped, () -> "排水实记由 emitted 腿盖住（2 >= 2），cover 不许凭空判超配交白卷："
                    + bill);
            assertEquals(2L, shipped.getLong(GLASS), "下发的就是排水实记那 2 枚");
            assertTrue(bill.envelopes().isEmpty(),
                    () -> "该族已被本格排水付到、族已在册 ⇒ 不补封套，封套本照旧交空：" + bill.envelopes());
        }

        /** 封套用例的槽形：规划 ranked [雪块, 玻璃]（备选在前），原始候选 [玻璃, 雪块]。 */
        private static GraphPattern reachEnvelopePattern(IPatternDetails source) {
            return new GraphPattern(source, IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 1L, 1L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(SNOW, 1L),
                                    new GraphInput.Candidate(GLASS, 1L)),
                            java.util.Set.of(GLASS, SNOW), false)));
        }

        /** 封套用例的图：目标 IRON，雪块与玻璃都在候选宇宙内。 */
        private static CraftGraph reachEnvelopeGraph(GraphPattern pattern) {
            return new CraftGraph(IRON,
                    Map.of(IRON, List.of(pattern)),
                    Map.of(pattern.source(), pattern),
                    List.of(pattern.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 10L, SNOW, 10L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** 封套用例的账：2 发全跑，雪块提 10 枚，玻璃提 {@code reachUsed} 枚（0 即"现场量为 0"）。 */
        private static GraphPlanningState reachEnvelopeState(IPatternDetails source, long reachUsed) {
            var state = new GraphPlanningState(Map.of(GLASS, reachUsed, SNOW, 10L), Map.of());
            state.addCrafting(source, 2L);
            state.extract(SNOW, 10L, false);
            if (reachUsed > 0L) {
                state.extract(GLASS, reachUsed, false);
            }
            return state;
        }

        /**
         * 封套用例的样板替身：{@code getInputs()} 必须交回<b>真的</b> AE2 候选表——
         * 既有 {@link FakeDetails} 的 {@code getInputs()} 是空数组，{@code billReachKeySlot}
         * 对早退，这一笔在旧的 gate 级用例里从未被跑到（台账 #114 第 3 点的根因）。
         */
        private static final class ReachDetails implements IPatternDetails {
            private final GenericStack[] rawCandidates;
            /** 该槽 {@code isValid} 真收的键；{@code null}＝全收（沿用既有封套夹具的声明形状）。 */
            private final @Nullable java.util.Set<AEKey> validInputs;

            /** 默认原始候选顺位 [玻璃@1 首位, 雪块@1 备选]，全收。 */
            ReachDetails() {
                this(new GenericStack(GLASS, 1L), new GenericStack(SNOW, 1L));
            }

            /** 自定义原始候选表（首位即 {@code billReachKeySlot} 的 reach 键），{@code isValid} 全收。 */
            ReachDetails(GenericStack... rawCandidates) {
                this(rawCandidates, null);
            }

            /** 自定义原始候选表 + {@code isValid} 真收的键集：收不到的候选不该被计费。 */
            static ReachDetails accepting(java.util.Set<AEKey> validInputs, GenericStack... rawCandidates) {
                return new ReachDetails(rawCandidates, validInputs);
            }

            private ReachDetails(GenericStack[] rawCandidates, @Nullable java.util.Set<AEKey> validInputs) {
                this.rawCandidates = rawCandidates;
                this.validInputs = validInputs;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new ReachInput(validInputs, rawCandidates) };
            }

            @Override
            public java.util.List<GenericStack> getOutputs() {
                return List.of(new GenericStack(IRON, 1L));
            }

            @Override
            public AEItemKey getDefinition() {
                return key(Items.REPEATER);
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        /** 原始模糊槽：顺位按给定数组、倍率 1、无退还键（非模具槽）；{@code validInputs} 为 null 时全收。 */
        private static final class ReachInput implements IPatternDetails.IInput {
            private final GenericStack[] candidates;
            private final @Nullable java.util.Set<AEKey> validInputs;

            private ReachInput(@Nullable java.util.Set<AEKey> validInputs, GenericStack... candidates) {
                this.candidates = candidates;
                this.validInputs = validInputs;
            }

            @Override
            public GenericStack[] getPossibleInputs() {
                return candidates;
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
                return validInputs == null || validInputs.contains(input);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return null;
            }
        }

        /**
         * 规划出口的配给表是执行侧配额账的<b>唯一上游</b>，它自己必须先留得住宽域：
         * 同一张样板两个定键槽各记 9e18 枚（单笔都装得进 {@code long}），合计 1.8e19 枚越界。
         *
         * <p>老口径这张表是 {@code Map<AEKey, Long>}，逐笔合并走饱和加 ⇒ 表上写下的是
         * {@link Long#MAX_VALUE}，执行侧照这份数取料就凭空少掉 8.78e18 枚配额（第五刀的断头）。
         * 现在整张表是 {@code WideKeyCounter}：账上记真值，AE2 那份 {@code long} 读数仍是饱和哨兵，
         * 但没人拿它当配额口径。
         */
        void plannedMaterialQuotaAboveLongShipsAsExactValue() {
            var miner = goldMinePattern();
            var consumer = twoGoldSlotPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            // 金由自产样板盖住：每发 Long.MAX 枚 × 3 发 = 2.77e19 枚，配给配得出 1.8e19 枚
            state.addCrafting(miner.source(), 3L);
            // 每槽每发 3e12 枚 × 3e6 发 = 9e18 一枚一笔，两笔同键复加才是越界的那一步
            state.addCrafting(consumer.source(), 3_000_000L);

            var table = MaterialClosureGate.buildMaterialPlan(
                    state, twoPatternGraph(miner, consumer), 3_000_000L).assigned();

            var share = table.get(consumer.source());
            assertNotNull(share, () -> "两个槽各 9e18 枚的配给表不该被判超配: 表大小=" + table.size());
            assertEquals(new java.math.BigInteger("18000000000000000000"), share.getExact(GOLD),
                    "合计 1.8e19 枚必须如实入账：这张表就是执行侧配给账的被减数");
            assertEquals(Long.MAX_VALUE, share.getLong(GOLD),
                    "AE2 的 long 读数仍是向上饱和的哨兵值（窄视图口径一字未动）");
            assertEquals(Long.MAX_VALUE, legacySaturatedBill(9_000_000_000_000_000_000L),
                    "对照：老口径的 merge(key, 本次份数, 饱和加) 在同一串输入下只能写下天花板");
        }

        /** 老口径 {@code bill} 的合并式（{@code merge(key, units, saturatingAdd)}），只做对照断言。 */
        private static long legacySaturatedBill(long slotUnits) {
            return slotUnits > Long.MAX_VALUE - slotUnits ? Long.MAX_VALUE : slotUnits + slotUnits;
        }

        /**
         * 条目①（任务 #219）主臂：一枚键的供给<b>只落在 {@code emitted} 那一腿</b>、且合计越出
         * {@code long} 时，配给表必须照发——不再被 cover 自己拼的那套缺腿宇宙判成超配、交白卷。
         *
         * <p>夹具（同键复加才跨得过天花板，单张样板/单笔都顶不过 {@code long}）：消费者每发吃
         * {@code 3e12} 枚金 × {@code 4e6} 发 ⇒ 表上这一格 {@code 1.2e19} 枚；金的来路只有两笔各
         * {@code 6e18} 枚的 {@code emitted}。闸门那一趟读得到 {@code emitted}（守恒式平），HEAD 的
         * cover 腿清单里没有这一条 ⇒ 供给读 {@code 0}、{@code 1.2e19} 的实记被判"超配"、整张表退回
         * 白卷（旧出口是 {@code MaterialPlanBill.noTable()}，它随 A5 一起摘掉了）——执行侧因此没有
         * 可照单的配给，退回提交期自算那一臂（两臂宇宙不同＝假洞入口）。
         */
        void supplyOnlyInEmittedLegBeyondLongStillShipsTheTable() {
            var consumer = goldBuyerPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            // 份数 3e12 × 4e6 = 1.2e19 枚：配给表上这一格越出 long
            state.addCrafting(consumer.source(), 4_000_000L);
            // 金的来路只在这两条 emitted 腿里：单笔 6e18 塞得进 long，两笔同键复加才越界
            state.emitItemsWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
            state.emitItemsWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
            assertEquals(new java.math.BigInteger("12000000000000000000"),
                    state.emitted().getAmount(GOLD).exactValue(),
                    "夹具前提：emitted 那一腿合计 1.2e19 枚（>long 的真值，不是天花板哨兵）");

            assertNull(MaterialClosureGate.validate(state, graph(consumer), 4_000_000L),
                    "夹具前提：闸门按同一本账判这单是平的");

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph(consumer), 4_000_000L);
            var shipped = bill.assigned().get(consumer.source());
            assertNotNull(shipped, () -> "供给只落在 emitted 腿上的配平表不许交白卷："
                    + bill.assigned());
            assertEquals(new java.math.BigInteger("12000000000000000000"), shipped.getExact(GOLD),
                    "下发的份数逐字是表上那一格——cover 读的就是闸门同一本账");
        }

        /**
         * 条目①同一臂换一条腿：供给只落在 {@code missing}（模拟轮缺口，守恒式的供给侧平衡项）时，
         * 表同样照发。钉的是"cover 不再自己挑腿"，不是"emitted 那一格特殊"。
         */
        void supplyOnlyInMissingLedgerBeyondLongStillShipsTheTable() {
            var consumer = goldBuyerPattern();
            var state = new GraphPlanningState(Map.of(GOLD, 0L), Map.of());
            state.addCrafting(consumer.source(), 4_000_000L);
            // 两笔各 6e18（单笔塞得进 long），同键复加 1.2e19 才越界
            state.addMissingWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));
            state.addMissingWide(GOLD, PlanAmount.of(6_000_000_000_000_000_000L));

            assertNull(MaterialClosureGate.validate(state, graph(consumer), 4_000_000L),
                    "夹具前提：缺料那一腿盖住了需求，闸门判平");

            var bill = MaterialClosureGate.buildMaterialPlan(state, graph(consumer), 4_000_000L);
            var shipped = bill.assigned().get(consumer.source());
            assertNotNull(shipped, () -> "供给只落在 missing 腿上的配平表不许交白卷："
                    + bill.assigned());
            assertEquals(new java.math.BigInteger("12000000000000000000"), shipped.getExact(GOLD),
                    "下发的份数逐字是表上那一格");
        }

        /** IRON×1 ← 每发 {@code 3e12} 枚金的定键槽（条目①那两臂的被计费样板）。 */
        private static GraphPattern goldBuyerPattern() {
            return new GraphPattern(new FakeDetails(), IRON, 1L,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                            GraphInput.Mode.NORMAL, null, null, null, false)));
        }

        /**
         * 金矿样板：无输入，每发产 {@code Long.MAX_VALUE} 枚金（只为把配给的来源顶住）。
         * 必须用带真产出的替身——闸门供给原账的自产腿读 {@code GraphPattern.allOutputs}
         * （任务 #219 条目①定下"供给腿只在闸门那一趟数一次"），替身不带产出的话
         * 供给那一格就真的是 0。
         */
        private static GraphPattern goldMinePattern() {
            return new GraphPattern(new MineDetails(Items.GOLD_ORE, GOLD, Long.MAX_VALUE),
                    GOLD, Long.MAX_VALUE,
                    List.of(new GenericStack(GOLD, Long.MAX_VALUE)), List.of());
        }

        /** 消费样板：两个定键槽各每发吃 3e12 枚金（同一键两笔，合计才越界）。 */
        private static GraphPattern twoGoldSlotPattern() {
            var slot = new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                    GraphInput.Mode.NORMAL, null, null, null, false);
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)), List.of(slot, slot));
        }

        /** IRON×1 ← 模糊槽（multiplier=mult，候选：玻璃@1 → 雪块@1）。 */
        private static GraphPattern ironFromGlassOrSandFuzzy(long mult) {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 1L, mult, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(GLASS, 1L),
                                    new GraphInput.Candidate(SNOW, 1L)),
                            java.util.Set.of(GLASS, SNOW), false)));
        }

        /** SNOW×1 → GLASS×1：让玻璃族成为图上可自产族（首选族整槽规则的 owns 判据）。 */
        private static GraphPattern glassFromSnowPattern() {
            return new GraphPattern(new FakeDetails(), GLASS, 1,
                    List.of(new GenericStack(GLASS, 1L)),
                    List.of(new GraphInput(SNOW, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        }

        /** 以 IRON 为目标、可挂玻璃生产者的双样板图。 */
        private static CraftGraph glassFuzzyGraph(GraphPattern consumer,
                @Nullable GraphPattern glassProducer) {
            var producers = new java.util.HashMap<appeng.api.stacks.AEKey,
                    List<GraphPattern>>();
            producers.put(IRON, List.of(consumer));
            var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
            bySource.put(consumer.source(), consumer);
            var order = new java.util.ArrayList<IPatternDetails>();
            order.add(consumer.source());
            if (glassProducer != null) {
                producers.put(GLASS, List.of(glassProducer));
                bySource.put(glassProducer.source(), glassProducer);
                order.add(glassProducer.source());
            }
            return new CraftGraph(IRON,
                    producers, Map.copyOf(bySource), order,
                    Map.of(), Map.of(), Map.of(),
                    Map.of(GLASS, 6L, SNOW, 10L),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
        }

        /** IRON×1 ← 模糊槽（顺位：玻璃每发 8，沙子每发 1）。 */
        private static GraphPattern ironFromFuzzySlot() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 8L, 1L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(GLASS, 8L), new GraphInput.Candidate(SAND, 1L)),
                            java.util.Set.of(GLASS, SAND), false)));
        }

        /** IRON×1 ← 模糊槽（multiplier=3，玻璃与沙子都是每份 1 的候选）。 */
        private static GraphPattern ironFromThreeUnitFuzzySlot() {
            return new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GLASS, 1L, 3L, GraphInput.Mode.NORMAL, null,
                            List.of(new GraphInput.Candidate(GLASS, 1L), new GraphInput.Candidate(SAND, 1L)),
                            java.util.Set.of(GLASS, SAND), false)));
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

        /** 带<b>真产出</b>的样板替身：配给来源核对读的是 AE2 那份 {@code getOutputs()}。 */
        private static final class MineDetails implements IPatternDetails {
            private final AEItemKey definition;
            private final GenericStack output;

            private MineDetails(net.minecraft.world.item.Item item, AEKey what, long amount) {
                this.definition = key(item);
                this.output = new GenericStack(what, amount);
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public java.util.List<GenericStack> getOutputs() {
                return List.of(output);
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.balancedLedgerPasses", () -> new MaterialClosureGateCases().balancedLedgerPasses()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.wideMissingLedgerClosesTheConservationCheckBeyondLong", () -> new MaterialClosureGateCases().wideMissingLedgerClosesTheConservationCheckBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.beyondLongDemandTermIsBilledOnItsExactValueNotTheCeiling", () -> new MaterialClosureGateCases().beyondLongDemandTermIsBilledOnItsExactValueNotTheCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.beyondLongSelfFeedingStartupIsBilledOnItsExactValueNotTheCeiling", () -> new MaterialClosureGateCases().beyondLongSelfFeedingStartupIsBilledOnItsExactValueNotTheCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.beyondLongReturnedMouldStartupIsBilledOnItsExactValueNotTheCeiling", () -> new MaterialClosureGateCases().beyondLongReturnedMouldStartupIsBilledOnItsExactValueNotTheCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.wideEmittedLedgerClosesTheConservationCheckBeyondLong", () -> new MaterialClosureGateCases().wideEmittedLedgerClosesTheConservationCheckBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.shortWideMissingIsStillCaught", () -> new MaterialClosureGateCases().shortWideMissingIsStillCaught()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.underExtractedInputIsCaught", () -> new MaterialClosureGateCases().underExtractedInputIsCaught()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.outputShortfallAgainstFinalIsCaught", () -> new MaterialClosureGateCases().outputShortfallAgainstFinalIsCaught()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.siblingComponentSupplyCannotSatisfyExactDemand", () -> new MaterialClosureGateCases().siblingComponentSupplyCannotSatisfyExactDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.fuzzySlotSpillingToNextCandidatePasses", () -> new MaterialClosureGateCases().fuzzySlotSpillingToNextCandidatePasses()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.fuzzySlotSplitsOneFiringAcrossCandidates", () -> new MaterialClosureGateCases().fuzzySlotSplitsOneFiringAcrossCandidates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.payerFamilyPaidBySameSlotFallbackStillShipsTable", () -> new MaterialClosureGateCases().payerFamilyPaidBySameSlotFallbackStillShipsTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.reachKeyEnvelopeShipsWholeSlotUnits", () -> new MaterialClosureGateCases().reachKeyEnvelopeShipsWholeSlotUnits()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.reachKeyEnvelopeShipsWhenPrimaryFamilyUnsupplied", () -> new MaterialClosureGateCases().reachKeyEnvelopeShipsWhenPrimaryFamilyUnsupplied()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied", () -> new MaterialClosureGateCases().envelopeNeverEntersDemandWhenPrimaryFamilyUnsupplied()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.envelopeBookShipsAsSeparateLedgerWithoutTouchingTheTable", () -> new MaterialClosureGateCases().envelopeBookShipsAsSeparateLedgerWithoutTouchingTheTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.ghostFirstCandidateNeverEntersTheQuotaBook", () -> new MaterialClosureGateCases().ghostFirstCandidateNeverEntersTheQuotaBook()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.reachKeyFallsBackToNextPayableCandidateWhenFirstIsGhost", () -> new MaterialClosureGateCases().reachKeyFallsBackToNextPayableCandidateWhenFirstIsGhost()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.envelopeIsBilledOnTheInUniverseVariantNotTheBlockedDeclaredSibling", () -> new MaterialClosureGateCases().envelopeIsBilledOnTheInUniverseVariantNotTheBlockedDeclaredSibling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.envelopeStaysOnTheSameNumbersWhenTheDeclaredFirstCandidateIsTheUniverseOne", () -> new MaterialClosureGateCases().envelopeStaysOnTheSameNumbersWhenTheDeclaredFirstCandidateIsTheUniverseOne()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.drainClaimFundedByEmittedLegShipsTable", () -> new MaterialClosureGateCases().drainClaimFundedByEmittedLegShipsTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.plannedMaterialQuotaAboveLongShipsAsExactValue", () -> new MaterialClosureGateCases().plannedMaterialQuotaAboveLongShipsAsExactValue()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.supplyOnlyInEmittedLegBeyondLongStillShipsTheTable", () -> new MaterialClosureGateCases().supplyOnlyInEmittedLegBeyondLongStillShipsTheTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MaterialClosureGateCases.supplyOnlyInMissingLedgerBeyondLongStillShipsTheTable", () -> new MaterialClosureGateCases().supplyOnlyInMissingLedgerBeyondLongStillShipsTheTable())
            );
        }
    }

    /** MultiPatternCrystalGrowthCases 的独立场景夹具，按类别统一执行。 */
    public static final class MultiPatternCrystalGrowthCases {
        private static final AEItemKey SEED = AEItemKey.of(Items.WHEAT_SEEDS);
        private static final AEItemKey PURE = AEItemKey.of(Items.DIAMOND);
        private static final AEItemKey DUST = AEItemKey.of(Items.REDSTONE);
        private static final AEItemKey BLOCK = AEItemKey.of(Items.DIAMOND_BLOCK);
        private static final AEItemKey ORDINARY = AEItemKey.of(Items.EMERALD);

        void ordinaryCrystalCanBootstrapFromOneStoredDustBatch() {
            var grow = FakePattern.of(PURE, 1, new FakeInput(SEED, 1));
            var grind = FakePattern.of(DUST, 1, new FakeInput(PURE, 1));
            var recycle = FakePattern.of(SEED, 8, new FakeInput(DUST, 4));
            var pack = FakePattern.of(BLOCK, 1, new FakeInput(PURE, 9));
            var unpack = FakePattern.of(ORDINARY, 9, new FakeInput(BLOCK, 1));
            var grid = FakeGrid.builder().storage(Map.of(DUST, 4L))
                    .pattern(PURE, grow).pattern(DUST, grind).pattern(SEED, recycle)
                    .pattern(BLOCK, pack).pattern(ORDINARY, unpack).build();
            var stock = new KeyCounter(); stock.add(DUST, 4L);
            var graph = GraphCompiler.compile(grid.crafting, stock, ORDINARY, null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
            for (long amount : new long[]{9L, 18L, 27L, 45L, 900L}) {
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, amount);
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(result.simulation(), "有启动粉的环必须给出实际可提交计划");
                assertTrue(result.state().missing().isEmpty(), () -> "missing=" + result.state().missing());
                assertEquals(4L, result.state().usedAmount(DUST));
                assertTrue(result.state().craftsWide().getAmount(recycle).signum() > 0);
                assertEquals(amount, result.finalAmount());
                assertNull(MaterialClosureGate.validate(result.state(), graph, amount, null));
            }
            var amount = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO).add(java.math.BigInteger.valueOf(9)));
            var wide = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, amount, false, null);
            assertFalse(wide.hasDeclined(), wide::declineReason);
            assertFalse(wide.simulation(), () -> "dust=" + wide.state().missing().getAmount(DUST)
                    + " seed=" + wide.state().missing().getAmount(SEED) + " pure=" + wide.state().missing().getAmount(PURE)
                    + " block=" + wide.state().missing().getAmount(BLOCK) + " final=" + wide.state().missing().getAmount(ORDINARY));
            assertTrue(wide.state().missing().isEmpty());
            assertEquals(amount, wide.exactFinalAmount());
            assertEquals(4L, wide.state().usedAmount(DUST));
            assertNull(MaterialClosureGate.validate(wide.state(), graph, amount, null));
        }

        void startupIdentityIsSharedAcrossEntriesButUndoRestoresUnstartedState() {
            var first = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(SEED, 11);
            var otherEntry = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(PURE, 11);
            var distinct = new git.chexson.chexsonsaeutils.crafting.recursive.RingId(SEED, 12);
            var state = new GraphPlanningState(Map.of(), Map.of(), new PlanJournal.Arena(16));
            int checkpoint = state.journal();
            state.markRingStarted(first);
            assertTrue(state.hasRingStarted(otherEntry));
            assertFalse(state.hasRingStarted(distinct));
            int started = state.journal();
            state.markRingStarted(otherEntry);
            assertEquals(started, state.journal(), "相同起步池不重复登记");
            for (int i = 0; i < 50; i++) state.addBytes(1);
            state.undoTo(checkpoint);
            assertFalse(state.hasRingStarted(first));
            assertFalse(state.hasRingStarted(otherEntry));
            state.markRingStarted(otherEntry);
            assertTrue(state.hasRingStarted(first));
            state.releaseRollback();
        }

        void wideCycleRoundingBalancesInternalPowderAndPreservesExternalMaterialFees() {
            var raw = AEItemKey.of(Items.IRON_INGOT);
            var grow = FakePattern.of(PURE, 1, new FakeInput(SEED, 1));
            var grind = FakePattern.of(DUST, 1, new FakeInput(PURE, 1));
            var recycle = FakePattern.of(SEED, 8, new FakeInput(DUST, 4), new FakeInput(raw, 2));
            var macro = RingContraction.contractPatterns(List.of(grow, grind, recycle)).getFirst();
            var planned = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100).add(java.math.BigInteger.ONE));
            var rounded = new LinkedHashMap<appeng.api.crafting.IPatternDetails, git.chexson.chexsonsaeutils.bigint.PlanAmount>();
            macro.executionRatio().forEach((pattern, ratio) -> rounded.put(pattern,
                    git.chexson.chexsonsaeutils.bigint.WideMath.wideCeilMulDiv(ratio, planned, macro.entryOutputPerCycle())));
            assertTrue(macro.netFlowOf(rounded).get(DUST).signum() < 0);
            var balanced = macro.internallyBalancedFirings(rounded, planned);
            var net = macro.netFlowOf(balanced);
            assertFalse(net.containsKey(DUST));
            assertTrue(net.get(SEED).compareTo(planned.exactValue()) >= 0);
            assertEquals(balanced.get(recycle).exactValue().multiply(java.math.BigInteger.TWO).negate(), net.get(raw));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MultiPatternCrystalGrowthCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MultiPatternCrystalGrowthCases.ordinaryCrystalCanBootstrapFromOneStoredDustBatch", () -> new MultiPatternCrystalGrowthCases().ordinaryCrystalCanBootstrapFromOneStoredDustBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MultiPatternCrystalGrowthCases.startupIdentityIsSharedAcrossEntriesButUndoRestoresUnstartedState", () -> new MultiPatternCrystalGrowthCases().startupIdentityIsSharedAcrossEntriesButUndoRestoresUnstartedState()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MultiPatternCrystalGrowthCases.wideCycleRoundingBalancesInternalPowderAndPreservesExternalMaterialFees", () -> new MultiPatternCrystalGrowthCases().wideCycleRoundingBalancesInternalPowderAndPreservesExternalMaterialFees())
            );
        }
    }

    /** PagedPlanJournalCases 的独立场景夹具，按类别统一执行。 */
    public static final class PagedPlanJournalCases {
        private static final AEItemKey KEY = AEItemKey.of(Items.PAPER);
        private static final PlanAmount WIDE = PlanAmount.of(BigInteger.TEN.pow(100));
        private static final IPatternDetails PATTERN = new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.BARREL); }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(KEY, 1)); }
        };
        private static final RingId RING = RingId.of(KEY, List.of());

        private static final class Fixture implements AutoCloseable {
            final WideKeyCounter[] counters = new WideKeyCounter[7];
            final WideCraftCounter crafts = new WideCraftCounter();
            final ByteAccount bytes = new ByteAccount();
            final Set<RingId> failed = new HashSet<>(), repaid = new HashSet<>(), started = new HashSet<>();
            final PlanJournal journal;
            final int key;
            Fixture(int pageEntries) { this(new PlanJournal.Arena(pageEntries)); }
            Fixture(PlanJournal.Arena arena) {
                var index = new KeyIndex(16);
                for (int i = 0; i < counters.length; i++) counters[i] = new WideKeyCounter(index);
                key = counters[0].idOf(KEY);
                journal = new PlanJournal(counters, crafts, failed, repaid, started, bytes, arena);
            }
            void mutate(int step) {
                journal.counterSet(PlanJournal.UNMODIFIED, key, step % 3 == 0 ? 0 : step);
                journal.counterAddDelta(PlanJournal.WORKING, key, WIDE);
                journal.counterSetExact(PlanJournal.USED, key, WIDE.multiply(PlanAmount.of(step + 1L)));
                journal.counterAddDelta(PlanJournal.MISSING, key, step % 2 == 0 ? Long.MIN_VALUE : 3L);
                journal.craftsMerge(PATTERN, step % 2 == 0 ? WIDE : PlanAmount.of(3L));
                journal.stackBytes(KEY, 3L, WIDE);
                journal.stackBytes(KEY, 7L, 11L);
                journal.plainBytes(1.25);
                if (step == 0) { journal.ringFailed(RING); journal.ringSeedRepaid(RING); journal.ringStarted(RING); }
            }
            void sameAs(Fixture other) {
                for (int i = 0; i < counters.length; i++) {
                    assertEquals(other.counters[i].getExact(KEY), counters[i].getExact(KEY));
                    assertEquals(other.counters[i].containsKey(KEY), counters[i].containsKey(KEY));
                }
                assertEquals(other.crafts.getAmount(PATTERN), crafts.getAmount(PATTERN));
                assertEquals(other.bytes.exactValue(), bytes.exactValue());
                assertEquals(Double.doubleToRawLongBits(other.bytes.ae2Bytes()), Double.doubleToRawLongBits(bytes.ae2Bytes()));
                assertEquals(other.failed, failed); assertEquals(other.repaid, repaid);
                assertEquals(other.started, started);
            }
            @Override public void close() { journal.releaseRollback(); }
        }

        void nestedRollbackAndRebranchAreIdenticalToMemoryJournal() {
            try (var paged = new Fixture(16); var memory = new Fixture(0)) {
                for (int i = 0; i < 5; i++) { paged.mutate(i); memory.mutate(i); }
                int inner = paged.journal.mark();
                for (int i = 5; i < 25; i++) { paged.mutate(i); memory.mutate(i); }
                paged.sameAs(memory);
                assertTrue(paged.journal.spilledPages() > 0);
                var file = paged.journal.spillPath(); assertTrue(Files.exists(file));
                paged.journal.undoTo(inner); memory.journal.undoTo(inner); paged.sameAs(memory);
                for (int i = 30; i < 45; i++) { paged.mutate(i); memory.mutate(i); }
                paged.sameAs(memory);
                paged.journal.undoTo(0); memory.journal.undoTo(0); paged.sameAs(memory);
                assertFalse(paged.counters[0].containsKey(KEY), "缺位和零缓存标记须区分");
                paged.journal.releaseRollback(); assertFalse(Files.exists(file));
            }
        }

        void residentArraysStayBoundedWhenHistoryHasHundredThousandEntries() {
            try (var paged = new Fixture(1024)) {
                for (int i = 0; i < 100_000; i++) paged.journal.counterAddDelta(PlanJournal.WORKING, paged.key, 1L);
                assertEquals(100_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
                assertEquals(1024, paged.journal.residentCapacity());
                assertEquals(97, paged.journal.spilledPages());
                paged.journal.undoTo(50_000);
                assertEquals(50_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
                for (int i = 0; i < 70_000; i++) paged.journal.counterAddDelta(PlanJournal.WORKING, paged.key, 2L);
                assertEquals(190_000L, paged.counters[PlanJournal.WORKING].getLong(KEY));
                assertEquals(1024, paged.journal.residentCapacity());
                paged.journal.undoTo(0);
                assertFalse(paged.counters[PlanJournal.WORKING].containsKey(KEY));
            }
        }

        void exactPageBoundaryCanBeReusedWithoutLosingOldValues() {
            try (var paged = new Fixture(16)) {
                for (int i = 0; i < 16; i++) paged.journal.counterSetExact(PlanJournal.USED, paged.key, WIDE.add(PlanAmount.of(i)));
                int mark = paged.journal.mark();
                for (int i = 0; i < 40; i++) paged.journal.counterAddDelta(PlanJournal.USED, paged.key, 1L);
                paged.journal.undoTo(mark);
                assertEquals(WIDE.add(PlanAmount.of(15)), paged.counters[PlanJournal.USED].getAmountById(paged.key));
                for (int i = 0; i < 40; i++) paged.journal.counterAddDelta(PlanJournal.USED, paged.key, 2L);
                paged.journal.undoTo(0);
                assertFalse(paged.counters[PlanJournal.USED].containsKey(KEY));
            }
        }

        void leavingFailedAttemptDeletesTemporaryJournalAndReleasesReferences() {
            var paged = new Fixture(16);
            for (int i = 0; i < 10; i++) paged.mutate(i);
            var file = paged.journal.spillPath(); assertTrue(Files.exists(file));
            assertThrows(IllegalStateException.class, () -> {
                try (paged) { throw new IllegalStateException("simulated branch failure"); }
            });
            assertFalse(Files.exists(file));
            assertEquals(0, paged.journal.residentCapacity());
            assertEquals(0, paged.journal.spilledPages());
        }
        void automaticMigrationPreservesEarlierMarksAndWideRareColumns() {
            var low = new java.util.concurrent.atomic.AtomicBoolean(false);
            git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                    new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(
                            low.get() ? 1024L : Long.MAX_VALUE, 256L);
            try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure)); var memory = new Fixture(0)) {
                for (int i = 0; i < 20; i++) { adaptive.mutate(i); memory.mutate(i); }
                int inner = adaptive.journal.mark();
                for (int i = 20; i < 40; i++) { adaptive.mutate(i); memory.mutate(i); }
                int before = adaptive.journal.mark();
                assertEquals(0, adaptive.journal.spilledPages());
                low.set(true); adaptive.journal.checkMemoryPressure();
                assertEquals(before, adaptive.journal.mark(), "迁移不能修改原有检查点编号");
                assertEquals(16, adaptive.journal.residentCapacity());
                assertTrue(adaptive.journal.spilledPages() > 0);
                var file = adaptive.journal.spillPath(); assertTrue(Files.exists(file));
                adaptive.sameAs(memory);
                low.set(false); adaptive.journal.checkMemoryPressure();
                assertEquals(16, adaptive.journal.residentCapacity(), "压力恢复后本次求解仍保持分页，避免来回切换");
                for (int i = 40; i < 70; i++) { adaptive.mutate(i); memory.mutate(i); }
                adaptive.sameAs(memory);
                adaptive.journal.undoTo(inner); memory.journal.undoTo(inner); adaptive.sameAs(memory);
                for (int i = 90; i < 110; i++) { adaptive.mutate(i); memory.mutate(i); }
                adaptive.sameAs(memory);
                adaptive.journal.undoTo(0); memory.journal.undoTo(0); adaptive.sameAs(memory);
                adaptive.journal.releaseRollback(); assertFalse(Files.exists(file));
            }
        }

        void expandingArrayTriggersMigrationBeforeHeadroomIsExhausted() {
            git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                    new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(1_000_000L, 980_000L);
            try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
                // 1024 初始条目仍未侵入预留，但扩容所需的第二份数组会侵入。
                for (int i = 0; i < 512; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
                assertEquals(0, adaptive.journal.spilledPages());
                for (int i = 512; i < 1100; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
                assertEquals(16, adaptive.journal.residentCapacity());
                assertEquals(1100L, adaptive.counters[PlanJournal.WORKING].getLong(KEY));
                adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.WORKING].containsKey(KEY));
            }
        }

        void healthyHeadroomKeepsInMemoryFastPath() {
            git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                    new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(Long.MAX_VALUE, 256L);
            try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
                for (int i = 0; i < 2500; i++) adaptive.journal.counterAddDelta(PlanJournal.WORKING, adaptive.key, 1L);
                adaptive.journal.checkMemoryPressure();
                assertEquals(4096, adaptive.journal.residentCapacity());
                assertEquals(0, adaptive.journal.spilledPages()); assertNull(adaptive.journal.spillPath());
                adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.WORKING].containsKey(KEY));
            }
        }

        void migrationOfShortTailWaitsUntilFirstPageIsFull() {
            var low = new java.util.concurrent.atomic.AtomicBoolean(false);
            git.chexson.chexsonsaeutils.crafting.memory.HeapPressure pressure = () ->
                    new git.chexson.chexsonsaeutils.crafting.memory.HeapPressure.Sample(low.get() ? 0L : Long.MAX_VALUE, 256L);
            try (var adaptive = new Fixture(new PlanJournal.Arena(0, 16, pressure))) {
                for (int i = 0; i < 7; i++) adaptive.journal.counterSetExact(PlanJournal.USED, adaptive.key, WIDE.add(PlanAmount.of(i)));
                int mark = adaptive.journal.mark();
                low.set(true); adaptive.journal.checkMemoryPressure();
                assertEquals(16, adaptive.journal.residentCapacity()); assertNull(adaptive.journal.spillPath());
                for (int i = 0; i < 50; i++) adaptive.journal.counterAddDelta(PlanJournal.USED, adaptive.key, 1L);
                adaptive.journal.undoTo(mark);
                assertEquals(WIDE.add(PlanAmount.of(6)), adaptive.counters[PlanJournal.USED].getAmountById(adaptive.key));
                adaptive.journal.undoTo(0); assertFalse(adaptive.counters[PlanJournal.USED].containsKey(KEY));
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.nestedRollbackAndRebranchAreIdenticalToMemoryJournal", () -> new PagedPlanJournalCases().nestedRollbackAndRebranchAreIdenticalToMemoryJournal()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.residentArraysStayBoundedWhenHistoryHasHundredThousandEntries", () -> new PagedPlanJournalCases().residentArraysStayBoundedWhenHistoryHasHundredThousandEntries()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.exactPageBoundaryCanBeReusedWithoutLosingOldValues", () -> new PagedPlanJournalCases().exactPageBoundaryCanBeReusedWithoutLosingOldValues()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.leavingFailedAttemptDeletesTemporaryJournalAndReleasesReferences", () -> new PagedPlanJournalCases().leavingFailedAttemptDeletesTemporaryJournalAndReleasesReferences()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.automaticMigrationPreservesEarlierMarksAndWideRareColumns", () -> new PagedPlanJournalCases().automaticMigrationPreservesEarlierMarksAndWideRareColumns()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.expandingArrayTriggersMigrationBeforeHeadroomIsExhausted", () -> new PagedPlanJournalCases().expandingArrayTriggersMigrationBeforeHeadroomIsExhausted()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.healthyHeadroomKeepsInMemoryFastPath", () -> new PagedPlanJournalCases().healthyHeadroomKeepsInMemoryFastPath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PagedPlanJournalCases.migrationOfShortTailWaitsUntilFirstPageIsFull", () -> new PagedPlanJournalCases().migrationOfShortTailWaitsUntilFirstPageIsFull())
            );
        }
    }

    /** PlanJournalCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanJournalCases {
        void hundredDigitLedgerRestoresNestedMarksAndExactBytes() {
            var paper = key(Items.PAPER);
            var wide = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100));
            var state = new GraphPlanningState(Map.of(paper, 17L), Map.of());
            int outer = state.journal();
            state.insert(paper, wide);
            state.emitItemsWide(paper, wide);
            state.addMissingWide(paper, wide);
            state.recordRingExtraction(paper, wide);
            state.addStackBytesWide(paper, 3L, wide);
            assertEquals(wide.multiply(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(24L))
                    .ceilDiv(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(paper.getAmountPerByte())),
                    state.byteAccount().exactValue());
            int inner = state.journal();
            var units = wide.divide(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(7L));
            assertEquals(units, state.extractTemplatesByIdWide(paper, state.idOf(paper), 7L, units));
            assertTrue(state.used().isEmpty(), "提取的是模拟产出，尚未触及原库存，不计 requiredExtract");
            state.reduceMissingWide(paper, wide);
            assertTrue(state.missing().isEmpty());
            state.undoTo(inner);
            assertEquals(wide.exactValue(), state.missing().getExact(paper));
            assertTrue(state.used().isEmpty());
            assertEquals(wide, state.extractByIdWide(paper, state.idOf(paper), wide));
            state.undoTo(outer);
            assertTrue(state.missing().isEmpty());
            assertTrue(state.emitted().isEmpty());
            assertTrue(state.ringExtractions().isEmpty());
            assertEquals(0.0, state.bytes());
            assertEquals(git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO, state.byteAccount().exactValue());
            assertEquals(17L, state.extract(paper, Long.MAX_VALUE, false));
        }

        void negativeWideAndMinLongDeltasUndoExactly() {
            var paper = key(Items.PAPER);
            var state = new GraphPlanningState(Map.of(), Map.of());
            var amount = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100));
            state.addMissingWide(paper, amount);
            int mark = state.journal();
            state.addMissing(paper, Long.MIN_VALUE);
            state.reduceMissingWide(paper, amount);
            assertEquals(java.math.BigInteger.valueOf(Long.MIN_VALUE), state.missing().getExact(paper));
            state.undoTo(mark);
            assertEquals(amount.exactValue(), state.missing().getExact(paper));
            var empty = new GraphPlanningState(Map.of(), Map.of());
            empty.insert(paper, amount.negate());
            empty.undoTo(0);
            assertEquals(0L, empty.extract(paper, Long.MAX_VALUE, true));
        }

        private static AEItemKey key(net.minecraft.world.item.Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

        void undoRestoresEveryLedger() {
            var paper = key(Items.PAPER);
            var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());
            var ringId = RingId.of(paper, java.util.List.of());
            var pattern = new ThrowingPatternDetails();

            int mark = state.journal();
            state.addCrafting(pattern, 5);
            state.markRingMacroFailed(ringId);
            state.markRingSeedRepaid(ringId);
            state.recordRingExtraction(paper, 3);
            state.recordInternalItem(paper, 7);
            state.emitItems(paper, 2);
            state.addMissing(paper, 4);
            state.insert(paper, 10);
            state.extract(paper, 5, false);
            state.addBytes(16.0);
            state.addStackBytes(paper, 100, 1);
            assertTrue(state.hasRingSeedRepaid(ringId), "还种轮数已按 ringId 记上");

            state.undoTo(mark);

            assertTrue(state.crafts().isEmpty(), "crafts 回滚到空");
            assertFalse(state.hasRingMacroFailed(ringId));
            assertFalse(state.hasRingSeedRepaid(ringId), "环宏回滚后还种记账必须还原，否则重试不再加成");
            assertEquals(0, state.ringExtractions().getLong(paper));
            assertEquals(0, state.recursiveInternalItems().getLong(paper));
            assertTrue(state.emittedItems().isEmpty());
            assertTrue(state.missingItems().isEmpty());
            assertEquals(0, state.usedAmount(paper), "used 峰值回滚");
            assertEquals(0.0, state.bytes(), "double 读数按位回滚");
            assertEquals(100, state.extract(paper, 1000, true), "working 回滚到原库存");
        }

        void undoRestoresAbsenceSoStockCanBePulledAgain() {
            var paper = key(Items.PAPER);
            var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());

            int mark = state.journal();
            assertEquals(100, state.extract(paper, 100, false));
            state.undoTo(mark);

            assertEquals(100, state.extract(paper, 100, false), "undo 必须恢复 absent 而非 0 标记，否则 cacheFuzzy 短路后库存无法重拉");
        }

        void undoRestoresWideValues() {
            var paper = key(Items.PAPER);
            var state = new GraphPlanningState(Map.of(paper, 10L), Map.of());

            int mark = state.journal();
            state.insert(paper, Long.MAX_VALUE - 5);
            assertEquals(Long.MAX_VALUE, state.extract(paper, Long.MAX_VALUE, true), "溢出键读窄视图哨兵");

            state.undoTo(mark);
            assertEquals(10, state.extract(paper, Long.MAX_VALUE, true), "宽值回滚后必须落回 long 域精确值");
        }

        void nestedMarksRollBackInReverseOrder() {
            var paper = key(Items.PAPER);
            var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());

            int outer = state.journal();
            state.insert(paper, 10);
            int inner = state.journal();
            state.insert(paper, 20);

            state.undoTo(inner);
            assertEquals(110, state.extract(paper, 1000, true), "内层回滚只撤内层增量");
            state.undoTo(outer);
            assertEquals(100, state.extract(paper, 1000, true), "外层回滚撤掉全部增量");
        }

        /** 只为 crafts 账本提供一个非空键的轻量样板替身。 */
        private static final class ThrowingPatternDetails implements appeng.api.crafting.IPatternDetails {
            @Override
            public appeng.api.crafting.IPatternDetails.IInput[] getInputs() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.List<GenericStack> getOutputs() {
                throw new UnsupportedOperationException();
            }

            @Override
            public AEItemKey getDefinition() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.hundredDigitLedgerRestoresNestedMarksAndExactBytes", () -> new PlanJournalCases().hundredDigitLedgerRestoresNestedMarksAndExactBytes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.negativeWideAndMinLongDeltasUndoExactly", () -> new PlanJournalCases().negativeWideAndMinLongDeltasUndoExactly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.undoRestoresEveryLedger", () -> new PlanJournalCases().undoRestoresEveryLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.undoRestoresAbsenceSoStockCanBePulledAgain", () -> new PlanJournalCases().undoRestoresAbsenceSoStockCanBePulledAgain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.undoRestoresWideValues", () -> new PlanJournalCases().undoRestoresWideValues()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanJournalCases.nestedMarksRollBackInReverseOrder", () -> new PlanJournalCases().nestedMarksRollBackInReverseOrder())
            );
        }
    }

    /** RingStartupWide218Cases 的独立场景夹具，按类别统一执行。 */
    public static final class RingStartupWide218Cases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey E = AEItemKey.of(Items.DIAMOND);
        private static final AEItemKey K = AEItemKey.of(Items.COAL);

        /** 两片 5e18 经 AE2 普通 long 加法回绕；逐存储真值 1e19。 */
        private static final long FRAG = 5_000_000_000_000_000_000L;
        private static final BigInteger TRUTH = new BigInteger("10000000000000000000");

        /** 起步种量：环每发吃 K×1000000000、还 K×1000000001，minimumSeeds 交回的缺口就是十位数。 */
        private static final long SEED_K = 1_000_000_000L;

        // ---- 臂一：起步预留（主臂，HEAD 必红） ----

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

        void minimumSeedsBeyondLongReturnsWideTruth() {
            var pattern = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                    List.of(new GenericStack(K, 1L)),
                    FakeInput.multi(9_000_000_000_000_000_000L, K),
                    FakeInput.multi(9_000_000_000_000_000_000L, K));

            var seeds = RingStartupPlan.minimumSeeds(Map.of(pattern, 1L));

            assertEquals(PlanAmount.of(new BigInteger("18000000000000000000")), seeds.get(K),
                    "起步缺口 1.8e19 交回宽域真值，不许 longValueExact 崩、不许夹 MAX、不许吞成 0");
        }

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.beyondLongSeedSupplyStillReservesWideTruthStartupReservation", () -> new RingStartupWide218Cases().beyondLongSeedSupplyStillReservesWideTruthStartupReservation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.trustworthySeedStockKeepsStartupReservationVerbatim", () -> new RingStartupWide218Cases().trustworthySeedStockKeepsStartupReservationVerbatim()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.netPerCycleWrapAroundNeverZeroesSeedRepayment", () -> new RingStartupWide218Cases().netPerCycleWrapAroundNeverZeroesSeedRepayment()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.minimumSeedsBeyondLongReturnsWideTruth", () -> new RingStartupWide218Cases().minimumSeedsBeyondLongReturnsWideTruth()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.beyondLongStartupSeedDeclinesWithNamedShortage", () -> new RingStartupWide218Cases().beyondLongStartupSeedDeclinesWithNamedShortage()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupWide218Cases.ringMacroCompilesWithTenDigitStartupSeed", () -> new RingStartupWide218Cases().ringMacroCompilesWithTenDigitStartupSeed())
            );
        }
    }

    /** WideCraftCountCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideCraftCountCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.gateDoesNotRejectPlanClosedOnlyByBeyondLongCraftCount", () -> new WideCraftCountCases().gateDoesNotRejectPlanClosedOnlyByBeyondLongCraftCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.gateDoesNotPassPlanThatOnlyClosesAfterCraftCountSaturation", () -> new WideCraftCountCases().gateDoesNotPassPlanThatOnlyClosesAfterCraftCountSaturation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.exitShipsSaturatedPatternTimesAndWideCraftsOnThePlan", () -> new WideCraftCountCases().exitShipsSaturatedPatternTimesAndWideCraftsOnThePlan()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.narrowPlanCarriesNoWideCraftDictionary", () -> new WideCraftCountCases().narrowPlanCarriesNoWideCraftDictionary()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.undoRollsBackWideCraftCountExactly", () -> new WideCraftCountCases().undoRollsBackWideCraftCountExactly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.ringMacroFiringVectorStaysExactBeyondLong", () -> new WideCraftCountCases().ringMacroFiringVectorStaysExactBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.craftLedgerCountsBeyondLongWithoutThrowing", () -> new WideCraftCountCases().craftLedgerCountsBeyondLongWithoutThrowing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.narrowViewAndKeySetAreReadOnly", () -> new WideCraftCountCases().narrowViewAndKeySetAreReadOnly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftCountCases.ceilBatchAtTheLongCeilingBooksExactFiringsNeverNegativeOnes", () -> new WideCraftCountCases().ceilBatchAtTheLongCeilingBooksExactFiringsNeverNegativeOnes())
            );
        }
    }

    /** WideLegsSolverAlignmentCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideLegsSolverAlignmentCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideLegsSolverAlignmentCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLegsSolverAlignmentCases.linearPlannerEmittableBooksWideNotCeiling", () -> new WideLegsSolverAlignmentCases().linearPlannerEmittableBooksWideNotCeiling()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLegsSolverAlignmentCases.wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining", () -> new WideLegsSolverAlignmentCases().wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining())
            );
        }
    }

    /** WideLinearBatchCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideLinearBatchCases {
        private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey INTERMEDIATE = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey RAW = AEItemKey.of(Items.REDSTONE);
        private static final AEItemKey ALTERNATIVE = AEItemKey.of(Items.DIAMOND);
        private static final long COST = 1_000_000_000_000L;
        private static final long ORDER = 100_000_000_000_000L;

        void nonAlignedOutputRetainsPerRoundRemainders() {
            var target = pattern(TARGET, 1, input(INTERMEDIATE, Long.MAX_VALUE));
            var intermediate = pattern(INTERMEDIATE, 2, input(RAW, 1));
            var graph = customGraph(List.of(target, intermediate), Map.of(RAW, Long.MAX_VALUE));
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(BigInteger.TEN.pow(30)))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 5);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            var times = BigInteger.valueOf(Long.MAX_VALUE / 2 + 1).multiply(BigInteger.valueOf(5));
            assertEquals(times, result.state().craftsWide().getAmount(intermediate.source()).exactValue());
            assertEquals(times, result.state().used().getExact(RAW));
            assertEquals(5, result.state().extract(INTERMEDIATE, 100, true));
        }

        void repeatedInputWithDifferentTemplatesRetainsChunkCompetition() {
            var target = pattern(TARGET, 1, input(INTERMEDIATE, Long.MAX_VALUE));
            var intermediate = pattern(INTERMEDIATE, Long.MAX_VALUE, input(RAW, 2), input(RAW, 3));
            var raw = pattern(RAW, 1, input(ALTERNATIVE, 1));
            var graph = customGraph(List.of(target, intermediate, raw), Map.of(RAW, 6L, ALTERNATIVE, 100L));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            // 合并两轮会改成库存用量 4、补产 6；保留原顺序应为库存用量 5、补产 5。
            assertEquals(BigInteger.valueOf(5), result.state().used().getExact(RAW));
            assertEquals(BigInteger.valueOf(5), result.state().craftsWide().getAmount(raw.source()).exactValue());
            assertEquals(1, result.state().extract(RAW, 100, true));
        }

        void hundredTrillionConsumesExactIntermediateTemplatesAndPreservesRemainder() {
            var graph = graph(PlanAmount.of(BigInteger.TEN.pow(30)), PlanAmount.of(3 * COST + 17), false);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            var demand = BigInteger.valueOf(ORDER - 3).multiply(BigInteger.valueOf(COST));
            assertEquals(demand, result.state().craftsWide().getAmount(
                    graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).exactValue());
            assertEquals(demand.multiply(BigInteger.TWO), result.state().used().getExact(RAW));
            assertEquals(BigInteger.valueOf(3 * COST), result.state().used().getExact(INTERMEDIATE));
            assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
            assertEquals(ORDER, result.finalAmount());
            assertTrue(result.state().journal() < 100, "日志条目不得随宽值分轮增长");
        }

        void stockedProducerDoesNotWalkItsProductionRoute() {
            var supply = BigInteger.valueOf(ORDER).multiply(BigInteger.valueOf(COST));
            var graph = graph(PlanAmount.ZERO, PlanAmount.of(supply.add(BigInteger.valueOf(17))), false);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            assertEquals(supply, result.state().used().getExact(INTERMEDIATE));
            assertEquals(0, result.state().craftsWide().getAmount(
                    graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).signum());
            assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
            assertTrue(result.state().journal() < 100);
        }

        void missingLeafAndFailedWideRouteKeepExactLedgers() {
            var demand = BigInteger.valueOf(ORDER).multiply(BigInteger.valueOf(COST));
            var graph = graph(PlanAmount.ZERO, PlanAmount.ZERO, false);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertTrue(result.simulation());
            assertEquals(demand.multiply(BigInteger.TWO), result.state().missing().getExact(RAW));
            graph = graph(PlanAmount.ZERO, PlanAmount.of(17), true);
            result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            assertEquals(BigInteger.valueOf(ORDER), result.state().used().getExact(ALTERNATIVE));
            assertEquals(0, result.state().used().getAmount(RAW).signum());
            assertEquals(0, result.state().used().getAmount(INTERMEDIATE).signum());
            assertEquals(0, result.state().craftsWide().getAmount(
                    graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).signum());
            assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
        }

        void largeQuantityPerformanceReadout() {
            var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            for (long requested : new long[] {10_000_000_000L, 10_000_000_000_000L, ORDER}) {
                var graph = graph(PlanAmount.of(BigInteger.TEN.pow(30)), PlanAmount.ZERO, false);
                long[] elapsed = new long[9];
                long[] allocated = new long[9];
                int journal = 0;
                for (int sample = 0; sample < 9; sample++) {
                    long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long start = System.nanoTime();
                    var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, requested);
                    elapsed[sample] = System.nanoTime() - start;
                    allocated[sample] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                    assertFalse(result.hasDeclined(), result::declineReason);
                    assertFalse(result.simulation());
                    assertEquals(BigInteger.valueOf(requested).multiply(BigInteger.valueOf(COST * 2)),
                            result.state().used().getExact(RAW));
                    journal = result.state().journal();
                }
                java.util.Arrays.sort(elapsed);
                java.util.Arrays.sort(allocated);
                System.out.printf("宽生产链 request=%d median-ns=%d median-B=%d journal=%d%n",
                        requested, elapsed[4], allocated[4], journal);
            }
        }

        private static CraftGraph graph(PlanAmount rawSupply, PlanAmount intermediateSupply, boolean alternative) {
            var source = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(TARGET, 1)), FakeInput.stack(INTERMEDIATE, COST)).withPriority(10);
            var target = new GraphPattern(source, TARGET, 1, List.of(new GenericStack(TARGET, 1)),
                    List.of(input(INTERMEDIATE, COST)));
            var intermediate = new GraphPattern(FakePattern.of(INTERMEDIATE, 1, FakeInput.stack(RAW, 2)),
                    INTERMEDIATE, 1, List.of(new GenericStack(INTERMEDIATE, 1)), List.of(input(RAW, 2)));
            var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
            producers.put(TARGET, List.of(target));
            producers.put(INTERMEDIATE, List.of(intermediate));
            var patterns = new java.util.LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
            patterns.put(target.source(), target);
            patterns.put(intermediate.source(), intermediate);
            if (alternative) {
                var alt = new GraphPattern(FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1)),
                        TARGET, 1, List.of(new GenericStack(TARGET, 1)), List.of(input(ALTERNATIVE, 1)));
                producers.put(TARGET, List.of(target, alt));
                patterns.put(alt.source(), alt);
            }
            var graph = new CraftGraph(TARGET, producers, patterns, List.copyOf(patterns.keySet()),
                    Map.of(), Map.of(), Map.of(), Map.of(RAW, Long.MAX_VALUE, INTERMEDIATE, Long.MAX_VALUE,
                            ALTERNATIVE, ORDER), Set.of(), Map.of(), alternative, null, alternative ? 2 : 1,
                    alternative ? 2 : 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, rawSupply, INTERMEDIATE, intermediateSupply)));
            return graph;
        }

        private static GraphInput input(AEKey key, long amount) {
            return new GraphInput(key, amount, 1, GraphInput.Mode.NORMAL, null, null, null, false);
        }

        private static GraphPattern pattern(AEKey key, long output, GraphInput... inputs) {
            var sourceInputs = java.util.Arrays.stream(inputs)
                    .map(input -> FakeInput.stack(input.key(), input.amountPerCraft())).toArray(FakeInput[]::new);
            var source = FakePattern.of(key, output, sourceInputs);
            return new GraphPattern(source, key, output, List.of(new GenericStack(key, output)), List.of(inputs));
        }

        private static CraftGraph customGraph(List<GraphPattern> patterns, Map<AEKey, Long> stock) {
            var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
            var sources = new java.util.LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
            for (var pattern : patterns) {
                producers.put(pattern.allOutputs().getFirst().what(), List.of(pattern));
                sources.put(pattern.source(), pattern);
            }
            return new CraftGraph(TARGET, producers, sources, List.copyOf(sources.keySet()),
                    Map.of(), Map.of(), Map.of(), stock, Set.of(), Map.of(), false, null, 1, 1);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.nonAlignedOutputRetainsPerRoundRemainders", () -> new WideLinearBatchCases().nonAlignedOutputRetainsPerRoundRemainders()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.repeatedInputWithDifferentTemplatesRetainsChunkCompetition", () -> new WideLinearBatchCases().repeatedInputWithDifferentTemplatesRetainsChunkCompetition()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.hundredTrillionConsumesExactIntermediateTemplatesAndPreservesRemainder", () -> new WideLinearBatchCases().hundredTrillionConsumesExactIntermediateTemplatesAndPreservesRemainder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.stockedProducerDoesNotWalkItsProductionRoute", () -> new WideLinearBatchCases().stockedProducerDoesNotWalkItsProductionRoute()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.missingLeafAndFailedWideRouteKeepExactLedgers", () -> new WideLinearBatchCases().missingLeafAndFailedWideRouteKeepExactLedgers())
            );
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideLinearBatchCases.largeQuantityPerformanceReadout", () -> new WideLinearBatchCases().largeQuantityPerformanceReadout())
            );
        }
    }

    /** WidePerSiteDemandCases 的独立场景夹具，按类别统一执行。 */
    public static final class WidePerSiteDemandCases {
        /** 独立度量，不以耗时作门禁；同一夹具同时核对份数、用量与分配量。 */
        @org.junit.jupiter.api.Tag("footprint")
        void planningPerformanceReadout() {
            var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            assertTrue(bean instanceof com.sun.management.ThreadMXBean,
                    "度量需要 JVM 的线程分配量接口");
            var allocation = (com.sun.management.ThreadMXBean) bean;
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (long requested : new long[] {1_000_000L, REQUEST_4E18}) {
                var graph = ironFromGoldGraph(true, 100L);
                for (int warmup = 0; warmup < 100; warmup++) {
                    LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);
                }
                long allocated = allocation.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                SolveResult result = null;
                int samples = 200;
                for (int sample = 0; sample < samples; sample++) {
                    result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);
                    assertFalse(result.hasDeclined());
                }
                long elapsed = System.nanoTime() - start;
                long bytes = allocation.getThreadAllocatedBytes(thread) - allocated;
                assertEquals(BigInteger.valueOf(requested).multiply(BigInteger.valueOf(100L)),
                        result.state().used().getExact(SAND));
                System.out.printf("规划度量 request=%d ns/op=%d allocated-B/op=%d journal=%d%n",
                        requested, elapsed / samples, bytes / samples, result.state().journal());
            }
            var amount = PlanAmount.of(BigInteger.TEN.pow(22));
            for (int warmup = 0; warmup < 30; warmup++) {
                measureWideLedger(amount);
            }
            long allocated = allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            int samples = 50;
            int entries = 0;
            for (int sample = 0; sample < samples; sample++) {
                entries = measureWideLedger(amount);
            }
            long elapsed = System.nanoTime() - start;
            long bytes = allocation.getThreadAllocatedBytes(thread) - allocated;
            System.out.printf("宽账度量 amount=1e22 ns/op=%d allocated-B/op=%d journal=%d%n",
                    elapsed / samples, bytes / samples, entries);
        }

        private static int measureWideLedger(PlanAmount amount) {
            var state = new GraphPlanningState(Map.of(), Map.of());
            int mark = state.journal();
            state.insert(GOLD, amount);
            assertEquals(amount, state.extractByIdWide(GOLD, state.idOf(GOLD), amount));
            state.emitItemsWide(GOLD, amount);
            state.addMissingWide(GOLD, amount);
            int entries = state.journal() - mark;
            state.undoTo(mark);
            assertTrue(state.emitted().isEmpty());
            assertTrue(state.missing().isEmpty());
            return entries;
        }

        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey SAND = AEItemKey.of(Items.SAND);

        void enormousTerminalDemandReportsExactMissingWithoutChunking() {
            var graph = ironFromGoldGraph(false, Long.MAX_VALUE);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, Long.MAX_VALUE);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2), result.state().missing().getExact(GOLD));
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE),
                    result.state().craftsWide().getAmount(graph.producersByOutput().get(IRON).get(0).source())
                            .exactValue());
        }

        void enormousTerminalDemandConsumesWholeTemplatesAndPreservesRemainder() {
            var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, Long.MAX_VALUE, 1L, GraphInput.Mode.NORMAL,
                            null, null, null, false)));
            var demand = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
            var graph = new CraftGraph(IRON, Map.of(IRON, List.of(producer)),
                    Map.of(producer.source(), producer), List.of(producer.source()),
                    Map.of(), Map.of(), Map.of(), Map.of(GOLD, Long.MAX_VALUE),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(
                    Map.of(GOLD, PlanAmount.of(demand.add(BigInteger.valueOf(17L))))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, Long.MAX_VALUE);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertTrue(result.state().missing().isEmpty());
            assertEquals(demand, result.state().used().getExact(GOLD));
            assertEquals(17L, result.state().extract(GOLD, 100L, true));
        }

        /** 每发 1 枚铁吃 10 枚金 ⇒ 1e18 枚铁的请求放大成 1e19 枚金。 */
        private static final long REQUEST_1E18 = 1_000_000_000_000_000_000L;
        private static final long PER_CRAFT_GOLD = 10L;
        private static final BigInteger NEED_1E19 = new BigInteger("10000000000000000000");
        private static final BigInteger TRUTH_1_5E19 = new BigInteger("15000000000000000000");

        void beyondLongSingleSiteDemandDrawsInRoundsInsteadOfSaturating() {
            var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, PER_CRAFT_GOLD, 1L, GraphInput.Mode.NORMAL, null,
                            null, null, false)));
            var graph = new CraftGraph(IRON,
                    Map.of(IRON, List.of(producer)),
                    Map.of(producer.source(), producer),
                    List.of(producer.source()),
                    Map.of(), Map.of(), Map.of(),
                    Map.<AEKey, Long>of(GOLD, Long.MAX_VALUE),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(
                    Map.of(GOLD, PlanAmount.of(TRUTH_1_5E19))));

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_1E18);

            assertFalse(result.hasDeclined(), () -> "真值 1.5e19 枚金盖得住 1e19 枚的需求，不该拒: "
                    + result.declineReason());
            var state = result.state();
            assertTrue(state.missingItems().isEmpty(),
                    () -> "池子里真有 1.5e19 枚金，不许报缺料: " + state.missingItems());
            assertEquals(REQUEST_1E18, (long) state.crafts().get(producer.source()),
                    "份数必须等于请求 1e18 发；少一分就是被饱和读数截掉");
            assertEquals(NEED_1E19, state.used().getExact(GOLD),
                    "用量 1e19 枚金本身就 >long：这本账必须记下真值");
        }

        /**
         * 排够臂（#211）：单站点<b>件数</b>需求 {@code 4e20}（&gt;long）时，那一格的生产者必须被排到
         * 宽域真值 {@code 4e20} 发，不许顶在 {@code Long.MAX_VALUE} 就出表。
         *
         * <p>形状：请求 {@code 4e18} 枚铁（合法 {@code long}），每张铁样板一发吃 {@code 100} 枚金
         * ⇒ 金这一格的件数需求 = {@code 100 × 4e18 = 4e20}。金的生产者每发产 {@code 1} 枚金。
         *
         * <p>HEAD 的断点不在"分轮口"（{@code request(Site, PlanAmount)}），而在<b>轮内</b>：
         * 金的<b>单位数</b> {@code 4e18} 装得进 {@code long}，于是 {@code fire} 直接把 long 版需求口
         * 叫起来，轮内第一句 {@code totalRequested = WideMath.wideMul(requestedAmount, site.amount)}
         * 把 {@code 4e18 × 100 = 4e20} 饱和成 {@code Long.MAX_VALUE} ⇒ 金的生产者只被排
         * {@code 9.22e18} 发，剩下那一截 {@code 4e20 − 9.22e18} 无声消失（连缺料都不报，
         * 是守恒闸门把它拦成 DECLINE 的，不是求解侧发现了洞）。
         *
         * <p>改后的口径：分轮的轮长由"轮内乘算不夹真值"决定（{@code 轮长 × 每件数 ≤ long}），
         * 于是每一轮的 {@code totalRequested} 都是精确值，逐轮累加进份数账 ⇒ 总发数 {@code 4e20}。
         * 断言钉<b>具体十位数</b>，不写"非饱和"这种读不出量的判据。
         */
        void beyondLongSiteItemDemandSchedulesTheProducerToTheWideTrueFirings() {
            var graph = ironFromGoldGraph(true, 100L);

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_4E18);

            assertFalse(result.hasDeclined(), () -> "5e20 枚沙盖得住 4e20 枚金的需求，不该拒: "
                    + result.declineReason());
            var state = result.state();
            assertTrue(state.missingItems().isEmpty(),
                    () -> "料链配得平，不许报任何缺料: " + state.missing());
            var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
            var ironPattern = graph.producersByOutput().get(IRON).get(0).source();
            assertEquals(GOLD_FIRINGS_4E20, state.craftsWide().getAmount(goldPattern).exactValue(),
                    "金的生产者必须排满 4e20 发（每发产 1 枚金）：顶在 Long.MAX 就是尾段静默少产");
            assertEquals(BigInteger.valueOf(REQUEST_4E18),
                    state.craftsWide().getAmount(ironPattern).exactValue(),
                    "铁样板的发数就是请求的 4e18 发");
            assertEquals(GOLD_FIRINGS_4E20, state.used().getExact(SAND),
                    "沙那一格的用量是 4e20 枚（每发 1 枚 × 4e20 发），这本账必须记下宽域真值");
        }

        /**
         * 报缺臂（#211）：同一张图把金的生产者撤掉 ⇒ 必须<b>当场报出</b>缺的是哪一枚键、缺多少枚，
         * 缺口数必须是宽域真值 {@code 4e20}，不许是被夹过的 {@code 9223372036854775807}，
         * 也不许 {@code CRAFT_LESS} 静默截半。
         *
         * <p>走的是<b>既有</b>那条延后判缺腿：轮内 {@code totalRequested} 精确 ⇒ 模拟那一腿逐轮把
         * 缺口累进 {@code state.missing()} 的宽账 ⇒ 守恒闸门"supply＋missing ≥ demand"在同一把
         * 宽域尺下配平（不再凭空造出一格假 hole），缺料表按真值出门。
         *
         * <p>HEAD 红在金那一格只被喂<b>一轮</b>：{@code totalRequested} 饱和成 {@code Long.MAX_VALUE}，
         * 缺料表记下 {@code 9.22e18} 枚——少报 {@code 3.9e20} 枚，同时闸门按 {@code 4e20} 的需求
         * 判出 {@code 3.9e20} 的 hole，把整条规划拒掉（玩家一个键名都看不到）。
         */
        void producerlessBeyondLongSiteDemandNamesTheKeyAndTheWideGap() {
            var graph = ironFromGoldGraph(false, 100L);

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_4E18);

            assertFalse(result.hasDeclined(), () -> "缺口该如实进缺料表，不该把整条规划判死: "
                    + result.declineReason());
            var state = result.state();
            // keys() 交回的是 Iterable 视图，不是按元素相等的 Set——先落成 Set 再比，
            // 否则断言消息里只有视图对象的身份哈希，读不出缺的是哪一枚键。
            var missingKeys = new java.util.LinkedHashSet<AEKey>();
            state.missing().keys().forEach(missingKeys::add);
            assertEquals(java.util.List.of(GOLD), new java.util.ArrayList<>(missingKeys),
                    "缺料表必须逐字点名那一枚键，不许捎上别的键");
            BigInteger gap = state.missing().getExact(GOLD);
            assertEquals(GOLD_FIRINGS_4E20, gap,
                    "缺口必须是宽域真值 4e20 枚：需求 100×4e18 一枚都不许少");
            assertTrue(gap.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                    () -> "缺口" + gap + " 没跨过 long 域，说明这一格还是被夹着报的（饱和读数不是量）");
        }

        /**
         * 窄路退回臂（#211）：件数需求塞得进 {@code long} 的那一支，调用序列必须与今天
         * <b>逐字相同</b>——一次分配都不许多。
         *
         * <p>判据用现成的 journal 账：每一轮需求都要往回滚日志追加"提料 / 份数 / 产出"条目，
         * 多切一轮就多一批条目。所以 {@code state.journal()}（当前日志水位）就是"多分配了几次"
         * 的直接读数。两个水位数字都是在 {@code 437a6c8} 上<b>实测</b>钉下来的，不是推出来的。
         *
         * <p>两条夹具一起才有意义：
         * <ul>
         * <li>{@code 1e6} 那一跑是小窄值，钉"窄路今天怎么走就怎么走"；</li>
         * <li>{@code 2e18 单位 × 每单位 4 枚 = 8e18 枚}那一跑把窄值<b>顶到天花板边上</b>
         * （{@code 8e18 < Long.MAX 9.22e18}）——轮长算子一旦被无谓地收紧（比如把
         * {@code roundUnits} 再除以二），这一跑就会多切出一轮、水位立刻变红。
         * 只留第一条的话，本臂对"轮长过紧"这类回归是不敏感的。</li>
         * </ul>
         */
        void narrowSiteDemandStillTakesExactlyOneRound() {
            var graph = ironFromGoldGraph(true, 100L);
            long requested = 1_000_000L;

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);

            assertFalse(result.hasDeclined(), () -> "窄值请求不该被本轮改动挡下: " + result.declineReason());
            var state = result.state();
            var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
            assertEquals(BigInteger.valueOf(requested * 100L),
                    state.craftsWide().getAmount(goldPattern).exactValue(),
                    "窄值形状不变：每发 100 金 ⇒ 1e6 发铁要 1e8 发金");
            assertEquals(NARROW_JOURNAL_WATER_MARK, state.journal(),
                    "窄值那一支的调用序列必须一字不变：journal 水位涨了就是多分了轮");
        }

        /**
         * 窄路退回臂的第二跑：件数需求 {@code 8e18} 枚，离 {@code long} 天花板只差
         * {@code 1.22e18} 枚——仍然"塞得进 long"，必须一次分配搞定。
         *
         * <p>这一跑是本轮长算式的<b>敏感性</b>来源：{@code WideMath.roundUnits(4)} 交出
         * {@code 2.305e18} 个单位数，请求恰好 {@code 2e18} 个单位数压在它下面，所以走的还是
         * 今天那一条单轮 {@code long} 路。把轮长再收紧一分（任何"顺手保守一点"的除法）都会
         * 立刻在这里多切出一轮。
         */
        void boundaryNarrowSiteDemandStillTakesOneRound() {
            var graph = ironFromGoldGraph(true, 4L);

            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_2E18);

            assertFalse(result.hasDeclined(), () -> "8e18 枚金塞得进 long，不该被拆轮挡下: "
                    + result.declineReason());
            var state = result.state();
            var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
            assertEquals(GOLD_ITEMS_8E18, state.craftsWide().getAmount(goldPattern).exactValue(),
                    "每单位 4 枚 × 2e18 单位 = 8e18 枚金，一发都不许多也不许少");
            assertEquals(GOLD_ITEMS_8E18, state.used().getExact(SAND),
                    "沙的用量同样还是 8e18 枚（每发金吃 1 枚沙）");
            assertEquals(BOUNDARY_NARROW_JOURNAL_WATER_MARK, state.journal(),
                    "顶到天花板边上的窄值也必须一次分配搞定：水位涨了就是轮长被算紧了");
        }

        /**
         * 铁 ← 金 ← 沙 这条三点链。每发铁吃 {@code goldPerCraft} 枚金，
         * 每发金产 1 枚金并吃 1 枚沙。
         *
         * @param withGoldProducer 金的生产者在不在图上（撤掉即"图上再无来源"那一型）
         * @param goldPerCraft     每发铁吃几枚金——它决定这一格的<b>件数</b>需求会不会越出 long
         */
        private static CraftGraph ironFromGoldGraph(boolean withGoldProducer, long goldPerCraft) {
            var ironProducer = new GraphPattern(new FakeDetails(Items.CLOCK), IRON, 1,
                    List.of(new GenericStack(IRON, 1L)),
                    List.of(new GraphInput(GOLD, goldPerCraft, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
            var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
            producers.put(IRON, List.of(ironProducer));
            var patternsBySource = new java.util.HashMap<IPatternDetails, GraphPattern>();
            var registrationOrder = new java.util.ArrayList<IPatternDetails>();
            patternsBySource.put(ironProducer.source(), ironProducer);
            registrationOrder.add(ironProducer.source());
            if (withGoldProducer) {
                var goldProducer = new GraphPattern(new FakeDetails(Items.COMPASS), GOLD, 1,
                        List.of(new GenericStack(GOLD, 1L)),
                        List.of(new GraphInput(SAND, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
                producers.put(GOLD, List.of(goldProducer));
                patternsBySource.put(goldProducer.source(), goldProducer);
                registrationOrder.add(goldProducer.source());
            }
            var graph = new CraftGraph(IRON, producers, patternsBySource, registrationOrder,
                    Map.of(), Map.of(), Map.of(),
                    Map.<AEKey, Long>of(SAND, Long.MAX_VALUE),
                    java.util.Set.of(), Map.of(), false, null, 1, 1);
            // 沙的真值 5e20 枚：够排满 4e20 枚金，不许因为读数饱和就把它当"夹得住"。
            graph.attachSupplyTruth(PlanningSupplyTruth.of(
                    Map.of(SAND, PlanAmount.of(SAND_TRUTH_5E20))));
            return graph;
        }

        /** 请求 {@code 4e18} 枚铁：本身是合法 {@code long}，放大 100 倍后金那一格要 {@code 4e20} 枚。 */
        private static final long REQUEST_4E18 = 4_000_000_000_000_000_000L;
        /** 请求 {@code 2e18} 枚铁：每单位 4 枚金 ⇒ 件数 {@code 8e18} 枚，仍塞得进 {@code long}。 */
        private static final long REQUEST_2E18 = 2_000_000_000_000_000_000L;
        private static final BigInteger GOLD_FIRINGS_4E20 = new BigInteger("400000000000000000000");
        private static final BigInteger GOLD_ITEMS_8E18 = new BigInteger("8000000000000000000");
        private static final BigInteger SAND_TRUTH_5E20 = new BigInteger("500000000000000000000");
        /** 窄值那一条在 {@code 437a6c8} 上实测的 journal 水位（取证见本类注释）：多一轮就涨一截。 */
        private static final int NARROW_JOURNAL_WATER_MARK = 20;
        /** 顶到天花板边上的窄值那一条的实测水位（20，与小窄值同＝一次分配搞定）：轮长一算紧就多切一轮。 */
        private static final int BOUNDARY_NARROW_JOURNAL_WATER_MARK = 20;

        /** 轻量样板替身：definition 只需要唯一身份。 */
        private static final class FakeDetails implements IPatternDetails {
            private final AEItemKey definition;

            private FakeDetails() {
                this(Items.CLOCK);
            }

            private FakeDetails(Item definitionItem) {
                this.definition = AEItemKey.of(new ItemStack(definitionItem));
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.enormousTerminalDemandReportsExactMissingWithoutChunking", () -> new WidePerSiteDemandCases().enormousTerminalDemandReportsExactMissingWithoutChunking()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.enormousTerminalDemandConsumesWholeTemplatesAndPreservesRemainder", () -> new WidePerSiteDemandCases().enormousTerminalDemandConsumesWholeTemplatesAndPreservesRemainder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.beyondLongSingleSiteDemandDrawsInRoundsInsteadOfSaturating", () -> new WidePerSiteDemandCases().beyondLongSingleSiteDemandDrawsInRoundsInsteadOfSaturating()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.beyondLongSiteItemDemandSchedulesTheProducerToTheWideTrueFirings", () -> new WidePerSiteDemandCases().beyondLongSiteItemDemandSchedulesTheProducerToTheWideTrueFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.producerlessBeyondLongSiteDemandNamesTheKeyAndTheWideGap", () -> new WidePerSiteDemandCases().producerlessBeyondLongSiteDemandNamesTheKeyAndTheWideGap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.narrowSiteDemandStillTakesExactlyOneRound", () -> new WidePerSiteDemandCases().narrowSiteDemandStillTakesExactlyOneRound()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.boundaryNarrowSiteDemandStillTakesOneRound", () -> new WidePerSiteDemandCases().boundaryNarrowSiteDemandStillTakesOneRound())
            );
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePerSiteDemandCases.planningPerformanceReadout", () -> new WidePerSiteDemandCases().planningPerformanceReadout())
            );
        }
    }

    /** WideRootRequestCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideRootRequestCases {
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey RAW = AEItemKey.of(Items.GOLD_INGOT);
        private static final BigInteger REQUEST = BigInteger.TEN.pow(30).add(BigInteger.ONE);
        private record Pattern(AEItemKey definition) implements IPatternDetails {
            @Override public AEItemKey getDefinition() { return definition; }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(PRODUCT, 2)); }
        }
        private CraftGraph graph(BigInteger stock) {
            return graph(stock, List.of(new GraphInput(RAW, 3, 1, GraphInput.Mode.NORMAL, null, null, null, false)));
        }
        private CraftGraph graph(BigInteger stock, List<GraphInput> inputs) {
            var pattern = new GraphPattern(new Pattern(AEItemKey.of(Items.CLOCK)), PRODUCT, 2,
                    List.of(new GenericStack(PRODUCT, 2)), inputs);
            var graph = new CraftGraph(PRODUCT, Map.of(PRODUCT, List.of(pattern)), Map.of(pattern.source(), pattern),
                    List.of(pattern.source()), Map.of(), Map.of(), Map.of(), Map.of(RAW, Long.MAX_VALUE),
                    Set.of(), Map.of(), false, null, 1, 1);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(stock))));
            return graph;
        }
        void wideRootPreservesRequestCeilingRemainderAndPlanCarrier() {
            var times = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO);
            var graph = graph(times.multiply(BigInteger.valueOf(3)));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
            assertFalse(result.hasDeclined(), result.declineReason());
            assertFalse(result.simulation());
            assertEquals(REQUEST, result.exactFinalAmount().exactValue());
            assertEquals(times.multiply(BigInteger.valueOf(3)), result.state().used().getExact(RAW));
            assertEquals(times, result.state().craftsWide().getAmount(graph.producersByOutput().get(PRODUCT).getFirst().source()).exactValue());
            assertNull(MaterialClosureGate.validate(result.state(), graph, result.exactFinalAmount(), null));
            var plan = Ae2PlanConverter.toPlan(result, graph);
            assertEquals(REQUEST, WideAmountPlanCarrier.resolveFinalOutput(plan).exactValue());
            assertEquals(Long.MAX_VALUE, plan.finalOutput().amount());
        }
        void wideBatchDebitsEveryInputEvenWhenTwoSlotsShareOneRawKey() {
            var times = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO);
            var total = times.multiply(BigInteger.valueOf(8));
            var graph = graph(total, List.of(
                    new GraphInput(RAW, 3, 1, GraphInput.Mode.NORMAL, null, null, null, false),
                    new GraphInput(RAW, 5, 1, GraphInput.Mode.NORMAL, null, null, null, false)));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
            assertFalse(result.hasDeclined(), result.declineReason());
            assertFalse(result.simulation());
            assertEquals(total, result.state().used().getExact(RAW));
            assertNull(MaterialClosureGate.validate(result.state(), graph, result.exactFinalAmount(), null));
        }
        void wideRootReportsExactShortageAndPartialCraftingUsesActualAvailableStock() {
            var graph = graph(BigInteger.valueOf(30));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(REQUEST), false, null);
            assertFalse(result.hasDeclined(), result.declineReason());
            assertTrue(result.simulation());
            var total = REQUEST.add(BigInteger.ONE).divide(BigInteger.TWO).multiply(BigInteger.valueOf(3));
            assertEquals(total.subtract(BigInteger.valueOf(30)), result.state().missing().getExact(RAW));
            var partial = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, PlanAmount.of(REQUEST), false, null);
            assertFalse(partial.hasDeclined(), partial.declineReason());
            assertFalse(partial.simulation());
            assertEquals(BigInteger.valueOf(20), partial.exactFinalAmount().exactValue());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideRootRequestCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideRootRequestCases.wideRootPreservesRequestCeilingRemainderAndPlanCarrier", () -> new WideRootRequestCases().wideRootPreservesRequestCeilingRemainderAndPlanCarrier()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideRootRequestCases.wideBatchDebitsEveryInputEvenWhenTwoSlotsShareOneRawKey", () -> new WideRootRequestCases().wideBatchDebitsEveryInputEvenWhenTwoSlotsShareOneRawKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideRootRequestCases.wideRootReportsExactShortageAndPartialCraftingUsesActualAvailableStock", () -> new WideRootRequestCases().wideRootReportsExactShortageAndPartialCraftingUsesActualAvailableStock())
            );
        }
    }
}
