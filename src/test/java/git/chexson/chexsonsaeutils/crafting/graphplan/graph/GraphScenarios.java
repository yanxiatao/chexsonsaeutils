package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.RouteOrdering;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图编译与路径选择的行为场景；由类别测试统一调用。 */
public final class GraphScenarios {
    private GraphScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.graphplan.graph",
                () -> ActiveRouteSelectionCases.verify(temporaryRoot),
                () -> GraphCompilerCacheKeyCases.verify(temporaryRoot),
                () -> GraphCompilerFuzzyClassOrderCases.verify(temporaryRoot),
                () -> GraphCompilerFuzzyReachNodeKeyCases.verify(temporaryRoot),
                () -> GraphCompilerProviderTieOrderCases.verify(temporaryRoot),
                () -> GraphPlanningWorkingSetCases.verify(temporaryRoot),
                () -> MissingProducerShapeProbeCases.verify(temporaryRoot),
                () -> ProviderPatternOrderRemainderCostCases.verify(temporaryRoot),
                () -> RingContractionSelfReplicatingCases.verify(temporaryRoot),
                () -> RingStartupLongBoundaryCases.verify(temporaryRoot)
        );
    }

    /** ActiveRouteSelectionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ActiveRouteSelectionCases {
        private static final AEKey T = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey N = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEKey S = AEItemKey.of(Items.REDSTONE);

        void macroEdgeCycleIsDetectedWithParticipant() {
            // Q produces N and consumes T; the macro edge T→N closes the cycle.
            var consumer = new GraphPattern(null, N, 1, List.of(),
                    List.of(normalInput(T, GraphInput.Mode.NORMAL, null)));
            var macro = new RingMacro(RingId.of(T, List.of()),
                    counter(N, 1), new KeyCounter(), new KeyCounter(), Map.of(), Map.of(), true);
            var graph = new CraftGraph(T,
                    Map.of(N, List.of(consumer)), Map.of(), List.of(), Map.of(T, List.of(macro)),
                    Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

            var selection = ActiveRouteSelection.analyze(graph);

            assertFalse(selection.acyclic());
            assertEquals(Set.of(T, N), selection.cyclicKeys());
            assertEquals(Set.of(consumer), selection.cycleParticipants());
        }

        void acyclicGraphHasNoCycles() {
            var producer = new GraphPattern(null, T, 1, List.of(),
                    List.of(normalInput(S, GraphInput.Mode.NORMAL, null)));
            var graph = new CraftGraph(T,
                    Map.of(T, List.of(producer)), Map.of(), List.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

            var selection = ActiveRouteSelection.analyze(graph);

            assertTrue(selection.acyclic());
            assertTrue(selection.cyclicKeys().isEmpty());
            assertTrue(selection.cycleParticipants().isEmpty());
        }

        void returnedModeInputDoesNotCloseCycle() {
            // Same shape as the macro cycle, but the producer's input is a 1:1
            // container return — the capacity walk never requests it, so the only
            // remaining edge is the macro's T→N and the graph is acyclic.
            var containerReturn = new GraphPattern(null, N, 1, List.of(),
                    List.of(normalInput(T, GraphInput.Mode.RETURNED, T)));
            var macro = new RingMacro(RingId.of(T, List.of()),
                    counter(N, 1), new KeyCounter(), new KeyCounter(), Map.of(), Map.of(), true);
            var graph = new CraftGraph(T,
                    Map.of(N, List.of(containerReturn)), Map.of(), List.of(), Map.of(T, List.of(macro)),
                    Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

            var selection = ActiveRouteSelection.analyze(graph);

            assertTrue(selection.acyclic());
            assertTrue(selection.cyclicKeys().isEmpty());
            assertTrue(selection.cycleParticipants().isEmpty());
        }

        private static GraphInput normalInput(AEKey key, GraphInput.Mode mode, AEKey remainder) {
            return new GraphInput(key, 1, 1, mode, remainder, null, null, false);
        }

        private static KeyCounter counter(AEKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ActiveRouteSelectionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ActiveRouteSelectionCases.macroEdgeCycleIsDetectedWithParticipant", () -> new ActiveRouteSelectionCases().macroEdgeCycleIsDetectedWithParticipant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ActiveRouteSelectionCases.acyclicGraphHasNoCycles", () -> new ActiveRouteSelectionCases().acyclicGraphHasNoCycles()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ActiveRouteSelectionCases.returnedModeInputDoesNotCloseCycle", () -> new ActiveRouteSelectionCases().returnedModeInputDoesNotCloseCycle())
            );
        }
    }

    /** GraphCompilerCacheKeyCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphCompilerCacheKeyCases {
        void collidingStockHashesStillUseExactComponentKeySet() {
            AEItemKey first = collidingNameKey("Aa");
            AEItemKey second = collidingNameKey("BB");

            assertNotEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());

            var firstKey = new GraphCompiler.CacheKey(null, 1L, AEItemKey.of(Items.CLOCK), Set.of(first));
            var secondKey = new GraphCompiler.CacheKey(null, 1L, AEItemKey.of(Items.CLOCK), Set.of(second));

            assertNotEquals(firstKey, secondKey);
        }

        private static AEItemKey collidingNameKey(String name) {
            ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            return AEItemKey.of(stack);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphCompilerCacheKeyCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerCacheKeyCases.collidingStockHashesStillUseExactComponentKeySet", () -> new GraphCompilerCacheKeyCases().collidingStockHashesStillUseExactComponentKeySet())
            );
        }
    }

    /** GraphCompilerFuzzyClassOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphCompilerFuzzyClassOrderCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);

        /** 槽的首选候选：这一 item 的无样板变体（＝AE2 要做模糊代用的那一格）。 */
        private static final AEItemKey CANDIDATE = AEItemKey.of(Items.SLIME_BALL);
        private static final AEItemKey SUBSTITUTE_AAA = componentKey(Items.SLIME_BALL, "aaa");
        private static final AEItemKey SUBSTITUTE_BBB = componentKey(Items.SLIME_BALL, "bbb");

        /**
         * MF-3（台账 #143）那一档：可损伤 item 的两个变体——满耐久（补丁表为空，仍带整套默认组件）与
         * {@code DamageValue=5}（补丁表非空）。实测口径是<b>带组件补丁那一档在前</b>，
         * 由 {@code " (with patches)"} 那一段的字典序决定，不是按耐久排的（AE2 那一侧才按耐久档排）。
         */
        private static final AEItemKey PICKAXE_UNDAMAGED = AEItemKey.of(Items.IRON_PICKAXE);
        private static final AEItemKey PICKAXE_DAMAGED = damagedKey(Items.IRON_PICKAXE, 5);
        /** 槽的首选候选：这一 item 的无样板变体（＝要做模糊代用的那一格）。 */
        private static final AEItemKey PICKAXE_CANDIDATE = damagedKey(Items.IRON_PICKAXE, 9);

        /** 副作用那一档：两把铁剑都带 DamageValue，只有数值不同（10 与 9 按字典序排，不按数值序）。 */
        private static final AEItemKey SWORD_TEN = damagedKey(Items.IRON_SWORD, 10);
        private static final AEItemKey SWORD_NINE = damagedKey(Items.IRON_SWORD, 9);

        /** 同宽数字那一档：两把铁斧只差一位数（8 与 9），字典序给的先手与 AE2 的受损优先相反。 */
        private static final AEItemKey AXE_LIGHTLY_DAMAGED = damagedKey(Items.IRON_AXE, 8);
        private static final AEItemKey AXE_MORE_DAMAGED = damagedKey(Items.IRON_AXE, 9);

        /** 两份代用样板都有样板可做、槽都认；节点身份只该由稳定键定，不该由那份清单的来序定。 */
        void fuzzySubstituteFollowsStableKeyNotByTheOrderTheListCameIn() {
            var forward = substituteNodeKeys(false);
            var reversed = substituteNodeKeys(true);

            assertEquals(List.of(SUBSTITUTE_AAA), forward,
                    () -> "模糊代用的节点身份应按稳定键升序取第一个（mark=aaa 在前）。实际=" + describe(forward));
            assertEquals(forward, reversed,
                    () -> "登记序换了就换节点身份＝还在吃 AE2 craftableItemsList 的哈希遍历序，"
                            + "而它定的是 producers/routes 的键。实际=" + describe(reversed));
        }

        /** 红字要认得出是哪一个组件变体（{@code AEItemKey#toString} 只会印 "(with patches)"）。 */
        private static String describe(List<AEKey> nodeKeys) {
            var builder = new StringBuilder("[");
            for (var nodeKey : nodeKeys) {
                builder.append(nodeKey);
                if (nodeKey instanceof AEItemKey itemKey) {
                    var customData = itemKey.get(DataComponents.CUSTOM_DATA);
                    builder.append('[').append(customData == null ? "<no custom data>"
                            : customData.copyTag().toString()).append(']');
                }
                builder.append(", ");
            }
            return builder.append(']').toString();
        }

        /**
         * 一次编译里真正落到 {@code producers} 上的那两个代用键（按图的登记顺序交出，用于对照来序）。
         *
         * @param bbbRegisteredFirst 两份样板的登记序——在这里就是"那份清单交来的序"
         */
        private static List<AEKey> substituteNodeKeys(boolean bbbRegisteredFirst) {
            var patternAaa = FakePattern.crafting(SUBSTITUTE_AAA, 1, Map.of(REDSTONE, 1L));
            var patternBbb = FakePattern.crafting(SUBSTITUTE_BBB, 1, Map.of(REDSTONE, 1L));
            var topPattern = FakePattern.of(TOP, 1,
                    FakeInput.multi(1, CANDIDATE, SUBSTITUTE_AAA, SUBSTITUTE_BBB));
            var builder = FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100L, SUBSTITUTE_AAA, 5L, SUBSTITUTE_BBB, 5L));
            if (bbbRegisteredFirst) {
                builder.pattern(SUBSTITUTE_BBB, patternBbb).pattern(SUBSTITUTE_AAA, patternAaa);
            } else {
                builder.pattern(SUBSTITUTE_AAA, patternAaa).pattern(SUBSTITUTE_BBB, patternBbb);
            }
            var grid = builder.pattern(TOP, topPattern).build();

            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

            var nodeKeys = new ArrayList<AEKey>();
            for (AEKey nodeKey : graph.producersByOutput().keySet()) {
                if (nodeKey.equals(SUBSTITUTE_AAA) || nodeKey.equals(SUBSTITUTE_BBB)) {
                    nodeKeys.add(nodeKey);
                }
            }
            // 夹具前提：只有一条代用路被选中，否则"节点身份"这条读数压根没跑
            assertEquals(1, nodeKeys.size(), () -> "两个代用变体里应当只落进一个节点键。实际=" + nodeKeys);
            return List.copyOf(nodeKeys);
        }

        /** 同 item 的组件变体：自定义数据里一枚字符串标记（AE2 的模糊分族按 primary key，正是要这一形）。 */
        private static AEItemKey componentKey(Item item, String mark) {
            var stack = new ItemStack(item);
            var tag = new CompoundTag();
            tag.putString("mark", mark);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return AEItemKey.of(stack);
        }

        /**
         * MF-3（台账 #143）：可损伤 item 的两档——满耐久（补丁表为空，但照样带 9 项默认组件，实测）与
         * {@code DamageValue=5}（补丁表非空）。这一条钉住<b>实测的</b>产出次序：<b>带组件补丁那一档在前</b>，
         * 由 {@code "minecraft:iron_pickaxe (with patches)…"} 里那个空格（{@code 0x20}）排在无补丁那一档的
         * 组件段 {@code "["}（{@code 0x5B}）之前决定——不是按耐久算出来的。
         * AE2 那一侧这把尺不存在：它按 {@code FuzzySearch.KeyComparator} 排耐久档
         * （{@code reference-sources/ae2-19.2.17-sources/appeng/api/stacks/FuzzySearch.java:97-108}，
         * 受损重的在前、并列才退到 {@code hashCode}）。这一对恰好同向是巧合，同宽数字那档就反向
         * （见 {@code fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues}）。
         * 夹具把<b>该输的那一档</b>（满耐久）先登记：撤掉族内排序 ⇒ 交回 {@code [满耐久, 受损]}，这条红。
         */
        void fuzzyClassPutsComponentPatchedVariantFirst() {
            assertEquals(List.of(renderDamage(PICKAXE_DAMAGED), renderDamage(PICKAXE_UNDAMAGED)),
                    renderDamageOrder(ProviderPatternOrder.fuzzyClassView(pickaxeGrid(false).crafting)
                            .craftableClass(PICKAXE_CANDIDATE)),
                    () -> "模糊档的产出次序＝稳定键那串的字典序：带组件补丁那一档在前（空格 0x20 < 组件段 [），"
                            + "这一档把满耐久那张先登记，撤掉排序就交回 [满耐久, 受损]");
            assertEquals(List.of(PICKAXE_DAMAGED), pickaxeNodeKeys(false),
                    () -> "满耐久那张先登记时，节点身份仍必须落在带补丁那一档（按稳定键，不按来序）");
            assertEquals(pickaxeNodeKeys(false), pickaxeNodeKeys(true),
                    () -> "登记序换了就换节点身份＝还在吃来序，没有定序");
        }

        /**
         * MF-3 点名的副作用：族内排序走<b>字面字典序</b>，所以组件<b>数值大小不按数值序</b>
         * ——{@code int:10} 排在 {@code int:9} 之前。这一条把该副作用钉成口径（不是数值序），
         * 谁把它换成数值序或撤掉排序（夹具把 9 先登记），这条就红。
         */
        void fuzzyClassOrdersDamageReadingsLexicographicallyNotNumerically() {
            assertEquals(List.of(renderDamage(SWORD_TEN), renderDamage(SWORD_NINE)),
                    renderDamageOrder(ProviderPatternOrder.fuzzyClassView(swordGrid().crafting)
                            .craftableClass(SWORD_NINE)),
                    () -> "字典序副作用：int:10 在 int:9 之前（数值序会给出相反的两档）");
        }

        /**
         * MF-3 的正主："这改的是挑选次序，不只是定序"——两档只差一位数（{@code int:8} 与 {@code int:9}）时
         * 字典序给出<b>耐久升序</b>（轻损在前），而 AE2 的 {@code FuzzySearch} 是<b>受损重的在前</b>
         * （{@code FuzzySearch.java:97-98}）⇒ 两把尺在这一对<b>给出相反的先手</b>。
         * 夹具把受损更重的那张先登记：撤掉排序就交回 {@code [int:9, int:8]}，这条红。
         */
        void fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues() {
            assertEquals(List.of(renderDamage(AXE_LIGHTLY_DAMAGED), renderDamage(AXE_MORE_DAMAGED)),
                    renderDamageOrder(ProviderPatternOrder.fuzzyClassView(axeGrid().crafting)
                            .craftableClass(AXE_LIGHTLY_DAMAGED)),
                    () -> "我们的口径是字典序＝耐久升序（int:8 在 int:9 前）；AE2 的受损优先给相反的先手");
        }

        /** 红字要认得出是哪一档在前：{@code AEItemKey#toString} 对带补丁的档只印 "(with patches)"。 */
        private static List<String> renderDamageOrder(List<AEKey> keys) {
            var rendered = new ArrayList<String>(keys.size());
            for (var key : keys) {
                rendered.add(renderDamage(key));
            }
            return List.copyOf(rendered);
        }

        private static String renderDamage(AEKey key) {
            return key + " damage=" + ((AEItemKey) key).getReadOnlyStack().getDamageValue();
        }

        /** 铁镐一族的两档：{@code damagedRegisteredFirst} 控制登记的来序（＝撤掉定序时会读到的那份序）。 */
        private static FakeGrid pickaxeGrid(boolean damagedRegisteredFirst) {
            var undamagedPattern = FakePattern.crafting(PICKAXE_UNDAMAGED, 1, Map.of(REDSTONE, 1L));
            var damagedPattern = FakePattern.crafting(PICKAXE_DAMAGED, 1, Map.of(REDSTONE, 1L));
            var topPattern = FakePattern.of(TOP, 1, FakeInput.anyVariant(PICKAXE_CANDIDATE, 1));
            var builder = FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100L, PICKAXE_UNDAMAGED, 5L, PICKAXE_DAMAGED, 5L));
            if (damagedRegisteredFirst) {
                builder.pattern(PICKAXE_DAMAGED, damagedPattern).pattern(PICKAXE_UNDAMAGED, undamagedPattern);
            } else {
                builder.pattern(PICKAXE_UNDAMAGED, undamagedPattern).pattern(PICKAXE_DAMAGED, damagedPattern);
            }
            return builder.pattern(TOP, topPattern).build();
        }

        /** 铁剑一族的两档（都带 DamageValue，只有数值不同）：登记序把 {@code int:9} 摆在前面。 */
        private static FakeGrid swordGrid() {
            var nine = FakePattern.crafting(SWORD_NINE, 1, Map.of(REDSTONE, 1L));
            var ten = FakePattern.crafting(SWORD_TEN, 1, Map.of(REDSTONE, 1L));
            return FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100L, SWORD_NINE, 5L, SWORD_TEN, 5L))
                    .pattern(SWORD_NINE, nine)
                    .pattern(SWORD_TEN, ten)
                    .build();
        }

        /** 铁斧一族的两档（同宽数字 8 与 9）：登记序把受损更重的 {@code int:9} 摆在前面。 */
        private static FakeGrid axeGrid() {
            var more = FakePattern.crafting(AXE_MORE_DAMAGED, 1, Map.of(REDSTONE, 1L));
            var lighter = FakePattern.crafting(AXE_LIGHTLY_DAMAGED, 1, Map.of(REDSTONE, 1L));
            return FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100L, AXE_MORE_DAMAGED, 5L, AXE_LIGHTLY_DAMAGED, 5L))
                    .pattern(AXE_MORE_DAMAGED, more)
                    .pattern(AXE_LIGHTLY_DAMAGED, lighter)
                    .build();
        }

        /** 一次编译里 {@code TOP} 那格真正落进 {@code producers} 的铁镐变体（应为带补丁那一档）。 */
        private static List<AEKey> pickaxeNodeKeys(boolean damagedRegisteredFirst) {
            var grid = pickaxeGrid(damagedRegisteredFirst);
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

            var nodeKeys = new ArrayList<AEKey>();
            for (AEKey nodeKey : graph.producersByOutput().keySet()) {
                if (nodeKey.equals(PICKAXE_UNDAMAGED) || nodeKey.equals(PICKAXE_DAMAGED)) {
                    nodeKeys.add(nodeKey);
                }
            }
            // 夹具前提：只有一档被选中，否则"节点身份"这条读数压根没跑
            assertEquals(1, nodeKeys.size(), () -> "两档铁镐里应当只落进一个节点键。实际=" + nodeKeys);
            return List.copyOf(nodeKeys);
        }

        /** 可损伤 item 的一档：只带 DamageValue（走 AE2 的 {@code FuzzySearch} 那一族形状）。 */
        private static AEItemKey damagedKey(Item item, int damage) {
            var stack = new ItemStack(item);
            stack.setDamageValue(damage);
            return AEItemKey.of(stack);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyClassOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyClassOrderCases.fuzzySubstituteFollowsStableKeyNotByTheOrderTheListCameIn", () -> new GraphCompilerFuzzyClassOrderCases().fuzzySubstituteFollowsStableKeyNotByTheOrderTheListCameIn()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyClassOrderCases.fuzzyClassPutsComponentPatchedVariantFirst", () -> new GraphCompilerFuzzyClassOrderCases().fuzzyClassPutsComponentPatchedVariantFirst()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyClassOrderCases.fuzzyClassOrdersDamageReadingsLexicographicallyNotNumerically", () -> new GraphCompilerFuzzyClassOrderCases().fuzzyClassOrdersDamageReadingsLexicographicallyNotNumerically()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyClassOrderCases.fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues", () -> new GraphCompilerFuzzyClassOrderCases().fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues())
            );
        }
    }

    /** GraphCompilerFuzzyReachNodeKeyCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphCompilerFuzzyReachNodeKeyCases {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyReachNodeKeyCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyReachNodeKeyCases.ghostFirstCandidateHandsTheCellToTheReachCandidateProducer", () -> new GraphCompilerFuzzyReachNodeKeyCases().ghostFirstCandidateHandsTheCellToTheReachCandidateProducer()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyReachNodeKeyCases.reachCandidateWithoutProducerStillReportsItsCellAndWideGap", () -> new GraphCompilerFuzzyReachNodeKeyCases().reachCandidateWithoutProducerStillReportsItsCellAndWideGap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyReachNodeKeyCases.declaredFirstCandidateKeepsItsNodeKeyAndEveryPlanningNumber", () -> new GraphCompilerFuzzyReachNodeKeyCases().declaredFirstCandidateKeepsItsNodeKeyAndEveryPlanningNumber()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerFuzzyReachNodeKeyCases.patternLessFirstCandidateStillStepsOntoItsCraftableSiblingVariant", () -> new GraphCompilerFuzzyReachNodeKeyCases().patternLessFirstCandidateStillStepsOntoItsCraftableSiblingVariant())
            );
        }
    }

    /** GraphCompilerProviderTieOrderCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphCompilerProviderTieOrderCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);

        /** 六张并列样板的 definition（字典序升序＝独立手算的期望序）。 */
        private static final AEItemKey D_COAL = AEItemKey.of(Items.COAL);
        private static final AEItemKey D_DIAMOND = AEItemKey.of(Items.DIAMOND);
        private static final AEItemKey D_EMERALD = AEItemKey.of(Items.EMERALD);
        private static final AEItemKey D_FEATHER = AEItemKey.of(Items.FEATHER);
        private static final AEItemKey D_GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey D_IRON_NUGGET = AEItemKey.of(Items.IRON_NUGGET);
        private static final List<AEItemKey> DEFINITIONS = List.of(
                D_COAL, D_DIAMOND, D_EMERALD, D_FEATHER, D_GOLD, D_IRON_NUGGET);

        /** 六张样板各吃一枚的输入（一一对应，每张只发得出 1 发）。 */
        private static final List<AEItemKey> INPUTS = List.of(
                AEItemKey.of(Items.STRING), AEItemKey.of(Items.LEATHER), AEItemKey.of(Items.BRICK),
                AEItemKey.of(Items.PAPER), AEItemKey.of(Items.FLINT), AEItemKey.of(Items.GUNPOWDER));

        /** 非并列臂：高优先级那张的 definition 字典序在后，低优先级那张在前——两把尺给的序相反。 */
        private static final AEItemKey HIGH_PRIORITY_DEFINITION = AEItemKey.of(Items.SNOWBALL);
        private static final AEItemKey LOW_PRIORITY_DEFINITION = AEItemKey.of(Items.APPLE);

        /**
         * 退料臂：两张样板除退料键外一字不差（同一 definition、同一候选输入、同一输出、同一实现类）。
         * 钉值是注册表 id 的字典序：{@code minecraft:bucket} 在 {@code minecraft:glass_bottle} 之前。
         */
        private static final AEItemKey REMAINDER_DEFINITION = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey REMAINDER_INPUT = AEItemKey.of(Items.WATER_BUCKET);
        private static final AEItemKey REMAINDER_BUCKET = AEItemKey.of(Items.BUCKET);
        private static final AEItemKey REMAINDER_GLASS_BOTTLE = AEItemKey.of(Items.GLASS_BOTTLE);

        /**
         * MF-1 那一档的候选表：首位候选两张样板<b>逐字相同</b>（退料都为空），只有<b>非首位候选</b>的退料不同。
         * 现场只喂首位候选那份料，所以两张样板都按首位候选发射——退料差别只活在稳定键这一串上。
         */
        private static final AEItemKey MULTI_FIRST_CANDIDATE = AEItemKey.of(Items.WATER_BUCKET);
        private static final AEItemKey MULTI_SECOND_CANDIDATE = AEItemKey.of(Items.LAVA_BUCKET);
        private static final GenericStack[] MULTI_CANDIDATES = {
                new GenericStack(MULTI_FIRST_CANDIDATE, 1L),
                new GenericStack(MULTI_SECOND_CANDIDATE, 1L),
        };

        /**
         * 组件臂：同一 item 的两个组件变体作 definition（旧稳定键上两者都印成
         * {@code "minecraft:xxx (with patches)"}）。钉值按组件读数的字典序：
         * {@code mark=aaa} 在 {@code mark=bbb} 之前。
         */
        private static final AEItemKey COMPONENT_DEFINITION_AAA = componentKey(Items.CLOCK, "aaa");
        private static final AEItemKey COMPONENT_DEFINITION_BBB = componentKey(Items.CLOCK, "bbb");
        private static final AEItemKey COMPONENT_INPUT = AEItemKey.of(Items.STRING);

        /**
         * 退料臂（MF-1，台账 #143）：AE2 的退料挂在<b>实际用的那个 template</b> 上
         * （{@code reference-sources/ae2-19.2.17-sources/appeng/crafting/pattern/AECraftingPattern.java:629-633}
         * 的 {@code getRemainingKey(template)} 走 {@code getRecipeRemainder(slot, itemKey)}），
         * 而 #142 那道修复只对候选<b>首位</b>求一次退料键。这一档的形状：两张样板<b>首位候选逐字相同</b>、
         * 只有<b>非首位候选</b>的退料不同（{@code bucket}／{@code glass_bottle}）⇒ 旧键上两串相同、
         * 比较返回 0，次序又落回 {@code List.sort} 的稳定序＝来序＝AE2 那只 {@code HashSet} 的遍历序。
         * 判据与上面那条退料臂同形：两次相反插入喂进收集点 ⇒ 路线序逐字相同，钉在 {@code bucket} 那张在前。
         */
        void patternsDifferingByRemainderOfANonFirstCandidateKeepOneOrder() {
            var withBucket = new PerCandidateRemainderPattern(
                    perCandidateSlot(MULTI_CANDIDATES, Map.of(MULTI_SECOND_CANDIDATE, REMAINDER_BUCKET)));
            var withGlassBottle = new PerCandidateRemainderPattern(perCandidateSlot(MULTI_CANDIDATES,
                    Map.of(MULTI_SECOND_CANDIDATE, REMAINDER_GLASS_BOTTLE)));

            var forward = routeSources(List.of(withBucket, withGlassBottle), MULTI_FIRST_CANDIDATE);
            var reversed = routeSources(List.of(withGlassBottle, withBucket), MULTI_FIRST_CANDIDATE);

            assertEquals(List.of(withBucket, withGlassBottle), forward,
                    () -> "首位候选相同、只有非首位候选的退料不同 ⇒ 旧键（只按首位候选求退料）两串逐字相同，"
                            + "比较返回 0，次序又是喂进来的序：" + describe(forward));
            assertEquals(forward, reversed,
                    () -> "反着喂就换了先后＝退料段只按候选首位求，非首位候选的退料没进稳定键："
                            + describe(reversed));
        }

        /** 退料键不同而其余声明相同的两张样板：反着喂必须给出同一份钉住的路线序。 */
        void patternsDifferingOnlyByRemainderKeyKeepOneOrder() {
            var withBucket = new TiedPattern(REMAINDER_DEFINITION, REMAINDER_INPUT, 0, REMAINDER_BUCKET);
            var withGlassBottle = new TiedPattern(REMAINDER_DEFINITION, REMAINDER_INPUT,
                    0, REMAINDER_GLASS_BOTTLE);

            var forward = routeSources(List.of(withBucket, withGlassBottle), REMAINDER_INPUT);
            var reversed = routeSources(List.of(withGlassBottle, withBucket), REMAINDER_INPUT);

            assertEquals(List.of(withBucket, withGlassBottle), forward,
                    () -> "只差退料键的两张样板在旧稳定键上逐字相同，比较返回 0 ⇒ 次序又是喂进来的序："
                            + describe(forward));
            assertEquals(forward, reversed,
                    () -> "反着喂就换了先后＝退料键没进稳定键，并列项又落回 AE2 的 HashSet 遍历序："
                            + describe(reversed));
        }

        /** 同 item 不同组件的两张样板：反着喂必须给出同一份钉住的路线序。 */
        void patternsDifferingOnlyByComponentsKeepOneOrder() {
            var aaa = new TiedPattern(COMPONENT_DEFINITION_AAA, COMPONENT_INPUT, 0, null);
            var bbb = new TiedPattern(COMPONENT_DEFINITION_BBB, COMPONENT_INPUT, 0, null);

            var forward = routeSources(List.of(aaa, bbb), COMPONENT_INPUT);
            var reversed = routeSources(List.of(bbb, aaa), COMPONENT_INPUT);

            assertEquals(List.of(aaa, bbb), forward,
                    () -> "带组件的 definition 只印 \"(with patches)\"，两张样板并列 ⇒ 次序又是喂进来的序："
                            + describe(forward));
            assertEquals(forward, reversed,
                    () -> "反着喂就换了先后＝组件差异没进稳定键，并列项又落回 AE2 的 HashSet 遍历序："
                            + describe(reversed));
        }

        /**
         * "取不到 codec 读数"那一型的<b>编码失败</b>臂（台账 #143 T3）：两张样板的 definition 只差
         * {@code max_stack_size}，而该组件的 codec 是 {@code ExtraCodecs.intRange(1, 99)}
         * （{@code net/minecraft/core/component/DataComponents.java}）⇒ 100 与 101 都编码失败。
         * 改前两条臂都只印固定标记 {@code <unencodable java.lang.Integer>}，这一档逐字相同 ⇒
         * 比较返回 0 ⇒ 次序又交回喂进来的序。补上不依赖 codec 的值文本之后，两串由
         * {@code =100}／{@code =101} 拆开，钉值是文本字典序（100 在前）。
         */
        void definitionsWhoseComponentValueCannotBeEncodedKeepOneOrder() {
            var hundred = new TiedPattern(maxStackSizeKey(Items.CLOCK, 100), COMPONENT_INPUT, 0);
            var hundredAndOne = new TiedPattern(maxStackSizeKey(Items.CLOCK, 101), COMPONENT_INPUT, 0);
            // 夹具前提：这两张的定义值必须真的走"编码失败"那条臂，否则这条读数压根没跑在档上
            assertTrue(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE.codec()
                            .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, 100).result().isEmpty(),
                    "夹具前提：max_stack_size=100 要被 codec 判编码失败，否则本用例测的不是那一档");

            var forward = routeSources(List.of(hundred, hundredAndOne), COMPONENT_INPUT);
            var reversed = routeSources(List.of(hundredAndOne, hundred), COMPONENT_INPUT);

            assertEquals(List.of(hundred, hundredAndOne), forward,
                    () -> "两个都取不到 codec 读数的值在旧键上印成同一个标记 ⇒ 并列 ⇒ 次序又是喂进来的序："
                            + describe(forward));
            assertEquals(forward, reversed,
                    () -> "反着喂就换了先后＝编码失败那一档没接不依赖 codec 的值文本，并列项又落回哈希遍历序："
                            + describe(reversed));
        }

        /**
         * "取不到 codec 读数"那一型的<b>瞬时组件</b>臂（台账 #143 T3）：一个 {@code codec()==null}
         * 的组件类型（＝原版口径的 transient 组件），两张样板的 definition 只差它身上的值。
         * 改前这一档只印 {@code <transient>}，两串逐字相同；现在标记后面仍续上值自己那份文本，
         * 于是 {@code aaa} 在 {@code bbb} 之前。
         */
        void definitionsDifferingOnlyByATransientComponentKeepOneOrder() {
            assertTrue(TRANSIENT_MARK.codec() == null,
                    "夹具前提：这个组件类型必须真的没有 codec，否则测的不是瞬时那一档");
            var aaa = new TiedPattern(transientMarkKey("aaa"), COMPONENT_INPUT, 0);
            var bbb = new TiedPattern(transientMarkKey("bbb"), COMPONENT_INPUT, 0);

            var forward = routeSources(List.of(aaa, bbb), COMPONENT_INPUT);
            var reversed = routeSources(List.of(bbb, aaa), COMPONENT_INPUT);

            assertEquals(List.of(aaa, bbb), forward,
                    () -> "瞬时组件的两个值在旧键上都印成 {@code <transient>} ⇒ 并列 ⇒ 次序又是喂进来的序："
                            + describe(forward));
            assertEquals(forward, reversed,
                    () -> "反着喂就换了先后＝瞬时组件那一档没接不依赖 codec 的值文本：" + describe(reversed));
        }

        /**
         * 一个没有 codec 的组件类型（＝原版口径的 transient 组件）：本仓的组件值读数走
         * {@code codec().encodeStart(NbtOps)}，这一档取不到任何持久化读数，正是稳定键里
         * "编码之外还剩下什么"的那一型。没注册进 {@code BuiltInRegistries} ⇒ 注册名读不到 ⇒
         * 两张样板在这一档的<b>键名也相同</b>，能拆开它们的只剩值自己那份文本。
         */
        private static final net.minecraft.core.component.DataComponentType<String> TRANSIENT_MARK =
                new net.minecraft.core.component.DataComponentType<>() {
                    @Override
                    public @Nullable com.mojang.serialization.Codec<String> codec() {
                        return null;
                    }

                    @Override
                    public net.minecraft.network.codec.StreamCodec<? super
                            net.minecraft.network.RegistryFriendlyByteBuf, String> streamCodec() {
                        return net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8;
                    }

                    @Override
                    public String toString() {
                        return "test:transient_mark";
                    }
                };

        /** 只在 {@code max_stack_size} 上分家的 definition（该值超出 codec 允许区间 ⇒ 编码失败）。 */
        private static AEItemKey maxStackSizeKey(Item item, int maxStackSize) {
            var stack = new net.minecraft.world.item.ItemStack(item);
            stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, maxStackSize);
            return AEItemKey.of(stack);
        }

        /** 只在瞬时组件上分家的 definition。 */
        private static AEItemKey transientMarkKey(String mark) {
            var stack = new net.minecraft.world.item.ItemStack(Items.CLOCK);
            stack.set(TRANSIENT_MARK, mark);
            return AEItemKey.of(stack);
        }

        /**
         * 只读编译期收集点交出的路线序（按<b>样板实例</b>认出次序——这两臂的 definition 串本就分不开）。
         *
         * @param providerOrder 喂进收集点的那份清单（＝AE2 {@code getCraftingFor} 交来的原样序）
         */
        private static List<IPatternDetails> routeSources(List<? extends IPatternDetails> providerOrder,
                AEKey stockedInput) {
            var storage = new java.util.LinkedHashMap<AEKey, Long>();
            storage.put(stockedInput, 8L);
            var grid = FakeGrid.builder()
                    .storage(storage)
                    .pattern(TOP, providerOrder.toArray(new IPatternDetails[0]))
                    .build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

            var sources = new ArrayList<IPatternDetails>();
            for (var route : RouteOrdering.routesFor(graph, TOP, true, false).routes()) {
                if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                    sources.add(patternRoute.pattern().source());
                }
            }
            assertEquals(providerOrder.size(), sources.size(),
                    () -> "夹具前提：两张样板都要真被编成路线，否则路线序这条读数压根没跑。实际=" + sources);
            return List.copyOf(sources);
        }

        /** 红字里把每张样板<b>逐个候选</b>的退料键/组件读数如实印出来，免得只看到"次序反了"。 */
        private static String describe(List<IPatternDetails> sources) {
            var builder = new StringBuilder();
            for (var source : sources) {
                builder.append('[').append(source.getDefinition());
                for (var input : source.getInputs()) {
                    for (var candidate : input.getPossibleInputs()) {
                        builder.append(" remaining[").append(candidate.what()).append("]=")
                                .append(input.getRemainingKey(candidate.what()));
                    }
                }
                builder.append(']');
            }
            return builder.toString();
        }

        /** 与 {@code GraphPlanningEquivalenceTest} 同形的组件夹具：自定义数据里一枚字符串标记。 */
        private static AEItemKey componentKey(Item item, String mark) {
            var stack = new net.minecraft.world.item.ItemStack(item);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("mark", mark);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            return AEItemKey.of(stack);
        }

        /** 并列项：两次相反插入必须给出同一份 routes 序与注册序，且等于字典序手算表。 */
        void tiedProviderPatternsKeepOneOrderAcrossReversedInsertion() {
            var forward = build(sixPatterns(false));
            var reversed = build(sixPatterns(true));

            assertEquals(DEFINITIONS, forward.routeOrder(),
                    () -> "并列优先级按稳定键定死（这批夹具的稳定键就是 definition 的注册表 id），"
                            + "喂进来的序不算数。实际=" + forward.routeOrder());
            assertEquals(forward.routeOrder(), reversed.routeOrder(),
                    () -> "routes 次序随提供者清单的插入序变＝还在吃 AE2 那只 HashSet 的遍历序："
                            + reversed.routeOrder());
            assertEquals(forward.registeredOrder(), reversed.registeredOrder(),
                    () -> "注册序（plan.patternTimes 的键序）随插入序变＝下游顺位跟着翻："
                            + reversed.registeredOrder());
            assertEquals(DEFINITIONS, forward.registeredOrder(),
                    () -> "求解首触序＝定过序的 routes 序（手算表：六张全被记上，按 definition 升序）："
                            + forward.registeredOrder());
        }

        /** 非并列项：优先级高的在前，稳定键那把尺不许把它们换过来。 */
        void differentPrioritiesStayBehindTheProviderPriorityRuler() {
            var forward = build(List.of(
                    new TiedPattern(HIGH_PRIORITY_DEFINITION, INPUTS.get(0), 5),
                    new TiedPattern(LOW_PRIORITY_DEFINITION, INPUTS.get(1), 1)));
            var reversed = build(List.of(
                    new TiedPattern(LOW_PRIORITY_DEFINITION, INPUTS.get(1), 1),
                    new TiedPattern(HIGH_PRIORITY_DEFINITION, INPUTS.get(0), 5)));

            assertEquals(List.of(HIGH_PRIORITY_DEFINITION, LOW_PRIORITY_DEFINITION), forward.routeOrder(),
                    () -> "优先级不同的两项仍按提供者优先级在前，稳定键只排并列那一档：" + forward.routeOrder());
            assertEquals(forward.routeOrder(), reversed.routeOrder(),
                    () -> "反着喂就换了先后＝把机器优先级那把尺抹平了：" + reversed.routeOrder());
            assertEquals(forward.registeredOrder(), reversed.registeredOrder(),
                    () -> "注册序跟着优先级序走：" + reversed.registeredOrder());
        }

        /** 六张并列样板：正序＝definition 升序摆，反序＝倒着摆（同优先级 0）。 */
        private static List<TiedPattern> sixPatterns(boolean reversedInsertion) {
            var patterns = new ArrayList<TiedPattern>(DEFINITIONS.size());
            for (int index = 0; index < DEFINITIONS.size(); index++) {
                patterns.add(new TiedPattern(DEFINITIONS.get(index), INPUTS.get(index), 0));
            }
            if (reversedInsertion) {
                Collections.reverse(patterns);
            }
            return List.copyOf(patterns);
        }

        /**
         * 一次构造的全部读数：{@code TOP} 这个键上的 routes 次序与计划交出的注册序。
         *
         * @param providerOrder 喂进收集点的那份清单（＝AE2 {@code getCraftingFor} 交来的原样序）
         */
        private static Build build(List<TiedPattern> providerOrder) {
            var storage = new java.util.LinkedHashMap<AEKey, Long>();
            for (var input : INPUTS) {
                storage.put(input, 1L);
            }
            var grid = FakeGrid.builder()
                    .storage(storage)
                    .pattern(TOP, providerOrder.toArray(new IPatternDetails[0]))
                    .build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

            var routeOrder = new ArrayList<AEItemKey>();
            for (var route : RouteOrdering.routesFor(graph, TOP, true, false).routes()) {
                if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                    routeOrder.add(patternRoute.pattern().source().getDefinition());
                }
            }

            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS,
                    providerOrder.size());
            assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
            var plan = Ae2PlanConverter.toPlan(result, graph);
            var registeredOrder = plan.patternTimes().keySet().stream()
                    .map(IPatternDetails::getDefinition)
                    .toList();
            // 夹具前提：每张都得真被记上，否则"注册序"这条读数压根没跑
            assertEquals(routeOrder, registeredOrder,
                    () -> "夹具前提：求解按 routes 序逐张首触，注册序与 routes 序才可对照。plan="
                            + registeredOrder + " routes=" + routeOrder);
            return new Build(List.copyOf(routeOrder), List.copyOf(registeredOrder));
        }

        /** 一次构造的读数。 */
        private record Build(List<AEItemKey> routeOrder, List<AEItemKey> registeredOrder) {
        }

        /** 单输入单输出的并列样板；definition 决定稳定键，priority 模拟提供者上的机器优先级。 */
        private static final class TiedPattern implements IPatternDetails, FakePlanGrid.PrioritizedPattern {

            private final AEItemKey definition;
            private final AEKey input;
            private final int priority;
            @Nullable
            private final AEKey remainder;

            private TiedPattern(AEItemKey definition, AEKey input, int priority) {
                this(definition, input, priority, null);
            }

            private TiedPattern(AEItemKey definition, AEKey input, int priority, @Nullable AEKey remainder) {
                this.definition = definition;
                this.input = input;
                this.priority = priority;
                this.remainder = remainder;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] {new SingleSlot(input, remainder)};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(TOP, 1L));
            }
        }

        /** 单候选槽：一发一枚那一号输入；{@code remainder} 模拟这张配方的退料（台账 #142 的退料臂）。 */
        private record SingleSlot(AEKey candidate, @Nullable AEKey remainder) implements IPatternDetails.IInput {
            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(candidate, 1L)};
            }

            @Override
            public long getMultiplier() {
                return 1L;
            }

            @Override
            public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
                return candidate.equals(input);
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return candidate.equals(template) ? remainder : null;
            }
        }

        /**
         * MF-1 那两张样板：definition / 候选表 / 输出 / 实现类全同，退料<b>按喂进来的 template 逐候选给</b>
         * （＝AE2 {@code AECraftingPattern.CraftingInputSlot#getRemainingKey} 的口径）。
         */
        private static final class PerCandidateRemainderPattern
                implements IPatternDetails, FakePlanGrid.PrioritizedPattern {

            private final IInput[] inputs;

            private PerCandidateRemainderPattern(IInput... inputs) {
                this.inputs = inputs;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public AEItemKey getDefinition() {
                return REMAINDER_DEFINITION;
            }

            @Override
            public IInput[] getInputs() {
                return inputs;
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(TOP, 1L));
            }
        }

        /** 多候选槽：候选表按数组序交出，退料只在 {@code remainders} 点名的那个候选上非空。 */
        private static IPatternDetails.IInput perCandidateSlot(GenericStack[] candidates,
                java.util.Map<AEKey, AEKey> remainders) {
            return new PerCandidateSlot(candidates.clone(), java.util.Map.copyOf(remainders));
        }

        private record PerCandidateSlot(GenericStack[] candidates,
                java.util.Map<AEKey, AEKey> remainders) implements IPatternDetails.IInput {

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
                for (var candidate : candidates) {
                    if (candidate.what().equals(input)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public @Nullable AEKey getRemainingKey(AEKey template) {
                return template == null ? null : remainders.get(template);
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.patternsDifferingByRemainderOfANonFirstCandidateKeepOneOrder", () -> new GraphCompilerProviderTieOrderCases().patternsDifferingByRemainderOfANonFirstCandidateKeepOneOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.patternsDifferingOnlyByRemainderKeyKeepOneOrder", () -> new GraphCompilerProviderTieOrderCases().patternsDifferingOnlyByRemainderKeyKeepOneOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.patternsDifferingOnlyByComponentsKeepOneOrder", () -> new GraphCompilerProviderTieOrderCases().patternsDifferingOnlyByComponentsKeepOneOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.definitionsWhoseComponentValueCannotBeEncodedKeepOneOrder", () -> new GraphCompilerProviderTieOrderCases().definitionsWhoseComponentValueCannotBeEncodedKeepOneOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.definitionsDifferingOnlyByATransientComponentKeepOneOrder", () -> new GraphCompilerProviderTieOrderCases().definitionsDifferingOnlyByATransientComponentKeepOneOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.tiedProviderPatternsKeepOneOrderAcrossReversedInsertion", () -> new GraphCompilerProviderTieOrderCases().tiedProviderPatternsKeepOneOrderAcrossReversedInsertion()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphCompilerProviderTieOrderCases.differentPrioritiesStayBehindTheProviderPriorityRuler", () -> new GraphCompilerProviderTieOrderCases().differentPrioritiesStayBehindTheProviderPriorityRuler())
            );
        }
    }

    /** GraphPlanningWorkingSetCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphPlanningWorkingSetCases {
        void unrelatedStockDoesNotExpandLedgersAndRefreshedStockStillLimitsCrafting() {
            var iron = AEItemKey.of(Items.IRON_INGOT);
            var gold = AEItemKey.of(Items.GOLD_INGOT);
            var stock = new LinkedHashMap<AEKey, Long>();
            stock.put(gold, 2000L);
            for (int color = 0; color < 1000; color++) {
                stock.put(FakePlanGrid.dyedKey(Items.LEATHER_HELMET, color), 1L);
            }
            var grid = FakeGrid.builder().storage(stock)
                    .pattern(iron, FakePattern.crafting(iron, 1L, Map.of(gold, 1L))).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), iron, null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            assertEquals(stock, graph.stock(), "完整库存快照仍供其他判据使用");
            assertEquals(2, graph.keyIndex().size(), "账本只需铁、金两个索引");
            var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, 1000L);
            assertFalse(result.hasDeclined(), result::declineReason);
            var plan = Ae2PlanConverter.toPlan(result, graph);
            var nativePlan = new Ae2TreePlanReference(grid, grid.requester,
                    new GenericStack(iron, 1000L), CalculationStrategy.CRAFT_LESS).run();
            FakePlanGrid.assertPlansEqual(nativePlan, plan);
            stock.put(gold, 500L);
            var refreshed = graph.withStock(stock);
            var partial = LinearPlanner.solve(refreshed, CalculationStrategy.CRAFT_LESS, 1000L);
            assertFalse(partial.hasDeclined(), partial::declineReason);
            var partialPlan = Ae2PlanConverter.toPlan(partial, refreshed);
            assertEquals(500L, partialPlan.finalOutput().amount());
            assertEquals(Map.of(gold, 500L), FakePlanGrid.toPlain(partialPlan.usedItems()));
            assertEquals(2, refreshed.keyIndex().size());
        }

        void relevantVariantsShareReadOnlyViewsAndRetainExtractionOrder() {
            var iron = AEItemKey.of(Items.IRON_INGOT);
            var gold = AEItemKey.of(Items.GOLD_INGOT);
            var stock = new LinkedHashMap<AEKey, Long>();
            for (int color = 0; color < 31; color++) {
                stock.put(FakePlanGrid.dyedKey(Items.GOLD_INGOT, color), 1L);
            }
            var grid = FakeGrid.builder().storage(stock)
                    .pattern(iron, FakePattern.of(iron, 1L, FakeInput.anyVariant(gold, 1L))).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), iron, null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            var members = graph.classVariants().get(gold);
            assertEquals(32, members.size());
            var ids = graph.denseKeyView().classMemberIds(graph.keyIndex().lookup(gold));
            for (int index = 0; index < members.size(); index++) {
                var member = members.get(index);
                assertSame(members, graph.classVariants().get(member));
                assertSame(ids, graph.denseKeyView().classMemberIds(graph.keyIndex().lookup(member)));
                assertEquals(member, graph.keyIndex().keyOf(ids[index]));
            }
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 20L);
            assertFalse(result.hasDeclined(), result::declineReason);
            var plan = Ae2PlanConverter.toPlan(result, graph);
            assertFalse(plan.simulation());
            assertEquals(20L, FakePlanGrid.toPlain(plan.usedItems()).values().stream()
                    .mapToLong(Long::longValue).sum());
            assertTrue(stock.keySet().containsAll(FakePlanGrid.toPlain(plan.usedItems()).keySet()));
            var expectedUsed = new LinkedHashMap<AEKey, Long>();
            for (var member : members) {
                if (stock.containsKey(member) && expectedUsed.size() < 20) {
                    expectedUsed.put(member, 1L);
                }
            }
            assertEquals(expectedUsed, FakePlanGrid.toPlain(plan.usedItems()), "按既有模糊类顺位取前二十枚");
            // 非耐久物品的原生哈希顺位与图的模糊类顺位不同；全量提取逐键对拍。
            var full = Ae2PlanConverter.toPlan(
                    LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 31L), graph);
            var nativePlan = new Ae2TreePlanReference(grid, grid.requester,
                    new GenericStack(iron, 31L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
            FakePlanGrid.assertPlansEqual(nativePlan, full);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphPlanningWorkingSetCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningWorkingSetCases.unrelatedStockDoesNotExpandLedgersAndRefreshedStockStillLimitsCrafting", () -> new GraphPlanningWorkingSetCases().unrelatedStockDoesNotExpandLedgersAndRefreshedStockStillLimitsCrafting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningWorkingSetCases.relevantVariantsShareReadOnlyViewsAndRetainExtractionOrder", () -> new GraphPlanningWorkingSetCases().relevantVariantsShareReadOnlyViewsAndRetainExtractionOrder())
            );
        }
    }

    /** MissingProducerShapeProbeCases 的独立场景夹具，按类别统一执行。 */
    public static final class MissingProducerShapeProbeCases {
        /** (a) 型：一枚"图上没有任何生产者样板"的键（实机 ae2lt:firmament_essence 那一线的形状）。 */
        private static final AEItemKey NO_PRODUCER = AEItemKey.of(Items.STRUCTURE_VOID);
        private static final AEItemKey FUEL = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);

        /**
         * (a) 结构洞：{@code getCraftingFor} 空 ⇒ 编译产物 {@code producersByOutput} 对这一枚也交回空集 ⇒
         * 求解器无论量多大都排不出产它的一张样板。这正是三枚 ae2lt 键在图上落不进生产者集的那一格。
         */
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MissingProducerShapeProbeCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MissingProducerShapeProbeCases.structuralHoleKeyIsAbsentFromTheProducerSetEntirely", () -> new MissingProducerShapeProbeCases().structuralHoleKeyIsAbsentFromTheProducerSetEntirely()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MissingProducerShapeProbeCases.structuralHoleKeyBeyondStockBecomesAShortageNotAMintedProducer", () -> new MissingProducerShapeProbeCases().structuralHoleKeyBeyondStockBecomesAShortageNotAMintedProducer())
            );
        }
    }

    /** ProviderPatternOrderRemainderCostCases 的独立场景夹具，按类别统一执行。 */
    public static final class ProviderPatternOrderRemainderCostCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);

        /** 一张样板一个槽三个候选：稳定键算一次正好读三次退料。 */
        private static final GenericStack[] CANDIDATES = {
                new GenericStack(AEItemKey.of(Items.WATER_BUCKET), 1L),
                new GenericStack(AEItemKey.of(Items.LAVA_BUCKET), 1L),
                new GenericStack(AEItemKey.of(Items.MILK_BUCKET), 1L),
        };

        /** 四张并列样板的钉值次序：definition 注册表 id 的字典序。 */
        private static final List<Item> DEFINITIONS_IN_ORDER = List.of(
                Items.COAL, Items.DIAMOND, Items.EMERALD, Items.FEATHER);

        void remaindersAreReadOncePerPatternAcrossRepeatedStabilisation() {
            var patterns = DEFINITIONS_IN_ORDER.stream().map(CountingPattern::new).toList();
            var grid = FakeGrid.builder().pattern(TOP, patterns.toArray(new IPatternDetails[0])).build();
            assertTrue(ProviderPatternOrder.priorityReadable(grid.crafting),
                    "夹具前提：读不到优先级时 stabilized 原样交回，一个稳定键都不算");

            var asDetails = patterns.stream().map(IPatternDetails.class::cast).toList();
            // 一次编译里这张样板会进它参与的每一个键的那份清单：这里以两次定序代表两趟读取点
            var reversed = ProviderPatternOrder.stabilized(grid.crafting, asDetails.reversed());
            var forward = ProviderPatternOrder.stabilized(grid.crafting, asDetails);

            assertEquals(forward, reversed, "次序由稳定键定死、与来序无关是 stabilized 自己的承诺");
            assertEquals(patterns, forward, "钉值：四张并列样板按 definition 注册表 id 升序");
            for (var pattern : patterns) {
                assertEquals(CANDIDATES.length, pattern.reads.get(),
                        () -> "一次编译内这张样板的退料段只许走一趟候选表（候选数＝" + CANDIDATES.length
                                + "）；每排序一次、每比较一次都算重走网格：" + pattern.definition);
            }
        }

        /** 数着 {@code getRemainingKey} 调用次数的样板替身。 */
        private static final class CountingPattern implements IPatternDetails {
            private final AEItemKey definition;
            private final AtomicInteger reads = new AtomicInteger();

            private CountingPattern(Item item) {
                this.definition = AEItemKey.of(new ItemStack(item));
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new CountingSlot(reads) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(TOP, 1L));
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

        /** 三个候选、无退料的槽：每读一个候选的退料键就记一笔。 */
        private record CountingSlot(AtomicInteger reads) implements IPatternDetails.IInput {
            @Override
            public GenericStack[] getPossibleInputs() {
                return CANDIDATES.clone();
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
                reads.incrementAndGet();
                return null;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ProviderPatternOrderRemainderCostCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ProviderPatternOrderRemainderCostCases.remaindersAreReadOncePerPatternAcrossRepeatedStabilisation", () -> new ProviderPatternOrderRemainderCostCases().remaindersAreReadOncePerPatternAcrossRepeatedStabilisation())
            );
        }
    }

    /** RingContractionSelfReplicatingCases 的独立场景夹具，按类别统一执行。 */
    public static final class RingContractionSelfReplicatingCases {
        private static AEItemKey key(net.minecraft.world.item.Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

        void selfReplicatingPatternContractsToMacro() {
            var template = key(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            // 1 出 2 自增殖：输入 template×1 → 输出 template×2
            FakePattern doubling = FakePattern.of(template, 2, new FakeInput(template, 1L));

            // 编译产物形态：CONSUMED_RETURNING 自喂槽（selfBlocked=false，豁免后）
            var graphPattern = new GraphPattern(
                    doubling,
                    template,
                    2L,
                    List.of(new appeng.api.stacks.GenericStack(template, 2L)),
                    List.of(new GraphInput(template, 1L, 1L,
                            GraphInput.Mode.CONSUMED_RETURNING, null, null, null, false)));
            // 层 1：净流量压缩
            var ring = git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing
                    .calculate(List.of(doubling));
            assertTrue(ring != null && ring.calculable(), "CompressedRing 应可算");
            assertTrue(ring.catalysts().get(template) > 0, "模板应为催化物");

            // 层 2：真递归判定（自供给放行）
            assertTrue(git.chexson.chexsonsaeutils.crafting.recursive.RecursiveRingPlanner
                    .isTrueRecursiveRing(ring, template), "自供给应为真递归");

            // 层 3：收缩
            List<RingMacro> macros = RingContraction.contract(
                    Map.of(template, List.of(graphPattern)));

            assertFalse(macros.isEmpty(), "自增殖样板必须收缩为环宏");
            var macro = macros.getFirst();
            assertTrue(macro.netOutputs().get(template) > 0, "净输出含模板");
            assertFalse(macro.catalysts().isEmpty(), "模板是催化物（种子）");
            assertFalse(macro.rootOnly(), "非根专用");
            assertTrue(macro.executionRatio().containsKey(doubling), "执行比含自增殖样板");
        }

        void mixedColorRecursiveRingContractsWithoutColorQualification() {
            var firstKey = key(Items.STONE);
            var secondKey = key(Items.COBBLESTONE);
            var first = FakePattern.colored(
                    3,
                    key(Items.PAPER),
                    List.of(new appeng.api.stacks.GenericStack(firstKey, 2L)),
                    new FakeInput(secondKey, 1L));
            var second = FakePattern.colored(
                    4,
                    key(Items.BOOK),
                    List.of(new appeng.api.stacks.GenericStack(secondKey, 1L)),
                    new FakeInput(firstKey, 1L));

            List<RingMacro> macros = RingContraction.contractPatterns(List.of(first, second));

            assertFalse(macros.isEmpty(), "异色真递归 SCC 也必须收缩");
            RingMacro macro = macros.stream()
                    .filter(candidate -> firstKey.equals(candidate.entryKey()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, macro.executionRatio().size());
            assertTrue(macro.executionRatio().containsKey(first));
            assertTrue(macro.executionRatio().containsKey(second));
        }

        void nonUniformRatioKeepsEveryInternalSeed() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var leaf = key(Items.REDSTONE);
            var producer = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(a, 2L)},
                    new GenericStack(b, 3L));
            var recycler = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(b, 1L)},
                    new GenericStack(a, 1L), new GenericStack(leaf, 1L));

            CompressedRing ring = CompressedRing.calculate(List.of(producer, recycler));

            assertTrue(ring != null && ring.calculable());
            assertEquals(1L, ring.executionRatio().get(producer));
            assertEquals(2L, ring.executionRatio().get(recycler));
            assertEquals(2L, ring.catalysts().get(a));
            assertEquals(2L, ring.catalysts().get(b));
            assertEquals(1L, ring.netOutputs().get(b));
            assertEquals(2L, ring.netOutputs().get(leaf));

            var macros = RingContraction.contractPatterns(List.of(producer, recycler));
            assertFalse(macros.isEmpty());
            for (RingMacro macro : macros) {
                // 数值逐字不变（2）；读数域随 #218 起步种量进 PlanAmount。
                assertEquals(Map.of(a, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(2L)),
                        macro.startupSeeds());
            }
        }

        void startupPlanRequestsEverySeedNeededByFirstFiring() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var intermediate = key(Items.IRON_INGOT);
            var output = key(Items.DIAMOND);
            var starter = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(a, 1L), new FakeInput(b, 1L)},
                    new GenericStack(intermediate, 1L));
            var recycler = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(intermediate, 1L)},
                    new GenericStack(a, 2L), new GenericStack(b, 2L),
                    new GenericStack(output, 1L));

            var macros = RingContraction.contractPatterns(List.of(starter, recycler));

            assertFalse(macros.isEmpty());
            for (RingMacro macro : macros) {
                // 数值逐字不变（1, 1）；读数域随 #218 起步种量进 PlanAmount。
                assertEquals(Map.of(a, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(1L),
                        b, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(1L)),
                        macro.startupSeeds());
            }
        }

        void coupledMultiKeyRatioIsStableAcrossMemberOrder() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var c = key(Items.REDSTONE);
            var leaf = key(Items.DIAMOND);
            var first = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(a, 5L)},
                    new GenericStack(b, 3L));
            var second = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(b, 2L)},
                    new GenericStack(c, 3L));
            var third = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(c, 2L)},
                    new GenericStack(a, 3L), new GenericStack(leaf, 1L));

            CompressedRing forward = CompressedRing.calculate(List.of(first, second, third));
            CompressedRing shuffled = CompressedRing.calculate(List.of(third, first, second));

            assertTrue(forward != null && forward.calculable());
            assertTrue(shuffled != null && shuffled.calculable());
            assertEquals(Map.of(first, 2L, second, 3L, third, 4L), forward.executionRatio());
            assertEquals(forward.executionRatio(), shuffled.executionRatio());
            assertEquals(10L, forward.catalysts().get(a));
            assertEquals(6L, forward.catalysts().get(b));
            assertEquals(8L, forward.catalysts().get(c));
            assertEquals(2L, forward.netOutputs().get(a));
            assertEquals(1L, forward.netOutputs().get(c));
            assertEquals(4L, forward.netOutputs().get(leaf));
        }

        void ringWithoutPositiveInternalContributionIsNotCalculable() {
            var a = key(Items.STICK);
            var leaf = key(Items.REDSTONE);
            var consumingLoop = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(a, 2L)},
                    new GenericStack(a, 1L), new GenericStack(leaf, 1L));

            CompressedRing ring = CompressedRing.calculate(List.of(consumingLoop));

            assertTrue(ring != null);
            assertFalse(ring.calculable());
            assertTrue(ring.executionRatio().isEmpty());
        }

        void infeasibleCoupledFlowTerminatesAsNotCalculable() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var c = key(Items.REDSTONE);
            var first = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(a, 4L)},
                    new GenericStack(b, 1L));
            var second = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(b, 2L)},
                    new GenericStack(c, 3L));
            var third = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(c, 4L)},
                    new GenericStack(a, 5L));

            CompressedRing ring = CompressedRing.calculate(List.of(first, second, third));

            assertTrue(ring != null);
            assertFalse(ring.calculable());
            assertTrue(ring.executionRatio().isEmpty());
        }

        void ratioOverflowTerminatesAsNotCalculable() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var first = FakePattern.withOutputs(
                    new FakeInput[]{FakeInput.stack(a, Long.MAX_VALUE)},
                    new GenericStack(b, 1L));
            var second = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(b, 1L)},
                    new GenericStack(a, 1L));

            CompressedRing ring = CompressedRing.calculate(List.of(first, second));

            assertTrue(ring != null);
            assertFalse(ring.calculable());
            assertTrue(ring.executionRatio().isEmpty());
        }

        void balancedCycleIsReportedAsUnsupported() {
            var a = key(Items.STICK);
            var b = key(Items.COAL);
            var first = FakePattern.of(a, 1L, new FakeInput(b, 1L));
            var second = FakePattern.of(b, 1L, new FakeInput(a, 1L));

            RingContraction.Analysis analysis = RingContraction.analyzePatterns(List.of(first, second));

            assertEquals(CraftGraph.CycleKind.UNSUPPORTED_CYCLE, analysis.cycleKind());
            assertTrue(analysis.macros().isEmpty());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.selfReplicatingPatternContractsToMacro", () -> new RingContractionSelfReplicatingCases().selfReplicatingPatternContractsToMacro()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.mixedColorRecursiveRingContractsWithoutColorQualification", () -> new RingContractionSelfReplicatingCases().mixedColorRecursiveRingContractsWithoutColorQualification()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.nonUniformRatioKeepsEveryInternalSeed", () -> new RingContractionSelfReplicatingCases().nonUniformRatioKeepsEveryInternalSeed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.startupPlanRequestsEverySeedNeededByFirstFiring", () -> new RingContractionSelfReplicatingCases().startupPlanRequestsEverySeedNeededByFirstFiring()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.coupledMultiKeyRatioIsStableAcrossMemberOrder", () -> new RingContractionSelfReplicatingCases().coupledMultiKeyRatioIsStableAcrossMemberOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.ringWithoutPositiveInternalContributionIsNotCalculable", () -> new RingContractionSelfReplicatingCases().ringWithoutPositiveInternalContributionIsNotCalculable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.infeasibleCoupledFlowTerminatesAsNotCalculable", () -> new RingContractionSelfReplicatingCases().infeasibleCoupledFlowTerminatesAsNotCalculable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.ratioOverflowTerminatesAsNotCalculable", () -> new RingContractionSelfReplicatingCases().ratioOverflowTerminatesAsNotCalculable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingContractionSelfReplicatingCases.balancedCycleIsReportedAsUnsupported", () -> new RingContractionSelfReplicatingCases().balancedCycleIsReportedAsUnsupported())
            );
        }
    }

    /** RingStartupLongBoundaryCases 的独立场景夹具，按类别统一执行。 */
    public static final class RingStartupLongBoundaryCases {
        private static final AEKey SEED = AEItemKey.of(Items.COAL);

        /** {@code 2^63-1}：{@code long} 顶格。 */
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        /** {@code 2^63}：越出 {@code long} 的第一档。 */
        private static final BigInteger TWO_POW_63 = new BigInteger("9223372036854775808");
        /** {@code 2^62}：旧口径白拒带的下沿。 */
        private static final BigInteger TWO_POW_62 = new BigInteger("4611686018427387904");

        void fireableCeilingInsideLongDomainClampsTheFirings() {
            assertEquals(4611686018427387904L, ceilingOf(TWO_POW_62),
                    "2^62 装得进 long 就必须按它夹住——旧写法在这一档不夹，发数顶到 Long.MAX_VALUE");
            assertEquals(9223372036854775806L, ceilingOf(new BigInteger("9223372036854775806")),
                    "顶格内侧 2^63-2 同样收得下、同样要夹");
            assertEquals(Long.MAX_VALUE, ceilingOf(LONG_MAX),
                    "恰在 2^63-1 那一档：商本身就是上界，夹与不夹读数一致（钉的是界没被画低一档）");
        }

        void fireableCeilingBeyondLongDomainDoesNotParticipate() {
            assertEquals(Long.MAX_VALUE, ceilingOf(TWO_POW_63),
                    "2^63 越界：这一条不参与最小化，上界交回 remainingTimes，不许把越界读数当量窄化进来");
            assertEquals(Long.MAX_VALUE, ceilingOf(TWO_POW_63.multiply(TWO_POW_63)),
                    "再大一档（2^126）同样不参与");
        }

        /** 每发吃 1 枚种、申报发数顶到 {@code Long.MAX_VALUE} 的一次直读。 */
        private static long ceilingOf(BigInteger availableSeed) {
            Map<AEKey, BigInteger> available = new LinkedHashMap<>();
            Map<AEKey, BigInteger> required = new LinkedHashMap<>();
            available.put(SEED, availableSeed);
            required.put(SEED, BigInteger.ONE);
            return RingStartupPlan.fireableTimes(available, required, Long.MAX_VALUE);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RingStartupLongBoundaryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupLongBoundaryCases.fireableCeilingInsideLongDomainClampsTheFirings", () -> new RingStartupLongBoundaryCases().fireableCeilingInsideLongDomainClampsTheFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingStartupLongBoundaryCases.fireableCeilingBeyondLongDomainDoesNotParticipate", () -> new RingStartupLongBoundaryCases().fireableCeilingBeyondLongDomainDoesNotParticipate())
            );
        }
    }
}
