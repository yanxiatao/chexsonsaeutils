package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.search.ResidualBestFirstPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 规划器预热：服务器启动后在守护线程上把整条生产规划管线（编译产物、残量求解、
 * 线性回退、转换、守恒闸门、环收缩、环索引）跑一遍——首次真实请求不必再付
 * 类加载与首次解释执行的冷启动税。
 *
 * <p>实测：冷启动首次请求比暖机后慢 5-8 倍（160 ms 对 25 ms 级），其中大部分是
 * 规划器自身的类加载与 JIT 预热，与请求规模无关。本类在合成的小图上走完全链路，
 * 全部对象都是方法内局部量，不触碰任何游戏状态；守护线程、只跑一次。
 *
 * <p>触发时机：{@link ServerStartedEvent} 之后——此时注册表可用，而玩家打开合成
 * 界面通常还有数秒，预热早已完成。
 */
@EventBusSubscriber(modid = "chexsonsaeutils")
public final class PlannerWarmup {

    private static volatile boolean warmed;

    private PlannerWarmup() {
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent ignored) {
        if (warmed) {
            return;
        }
        warmed = true;
        var thread = new Thread(PlannerWarmup::warm, "chexsonsaeutils-planner-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void warm() {
        try {
            // 编译链预热：合成小服务跑一遍 GraphCompiler.compile 全流程
            // （expand/classVariants/finalize/环宏/assemble），首次真实请求
            // 不再付编译器类的加载与解释执行税。与游戏状态零交互。
            warmCompiler();
            var graph = syntheticGraph();
            // 生产主路径与回退路径都显式预热。
            var result = ResidualBestFirstPlanner.solve(
                    graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2L);
            var fallbackResult = LinearPlanner.solve(
                    graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2L);
            // 转换 + 守恒闸门 + 负值出口（挂在 convert 的调用链上）
            if (!result.hasDeclined()) {
                Ae2PlanConverter.toPlan(result, graph);
            }
            if (!fallbackResult.hasDeclined()) {
                Ae2PlanConverter.toPlan(fallbackResult, graph);
            }
            // 环收缩 / 环索引（编译期的环宏段）
            var ring = CompressedRing.calculate(java.util.Set.of());
            if (ring != null) {
                ring.catalysts();
            }
            new RingId(null, 0);
            // 宽域出口
            Ae2AmountsWarmup.touch();
        } catch (RuntimeException | LinkageError ignored) {
            // 预热失败不影响任何功能——首次请求按冷路径照常工作
        }
    }

    /**
     * 编译链预热：合成小服务跑一遍 {@code GraphCompiler.compile} 全流程。
     * 两级小链（clock ← gunpowder ← iron）覆盖 expand / classVariants /
     * finalize / 环宏 / assemble 全部类加载；环宏直接由 producer 图做 SCC
     * 收缩（零副作用，只为加载类）。
     */
    private static void warmCompiler() {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey mid = AEItemKey.of(Items.GUNPOWDER);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);
        var topPattern = new WarmPattern(top, List.of(new GenericStack(top, 1L)),
                new WarmInput(mid, 2L), new WarmInput(leaf, 1L));
        var midPattern = new WarmPattern(mid, List.of(new GenericStack(mid, 1L)),
                new WarmInput(leaf, 4L));
        var service = new WarmService(Map.of(
                top, List.of(topPattern),
                mid, List.of(midPattern)));
        var stock = new KeyCounter();
        stock.add(leaf, 10_000L);
        var graph = git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler.compile(
                service, stock, top, null);
        if (graph.hasDeclined()) {
            return;
        }
        var solved = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2L);
        if (!solved.hasDeclined()) {
            Ae2PlanConverter.toPlan(solved, graph);
        }
    }

    /** 最小 crafting 服务桩：只服务预热小图的查询，其余返回空。 */
    private static final class WarmService implements appeng.api.networking.crafting.ICraftingService {
        private final Map<AEKey, List<IPatternDetails>> craftingFor;

        private WarmService(Map<AEKey, List<IPatternDetails>> craftingFor) {
            this.craftingFor = craftingFor;
        }

        @Override
        public java.util.Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return craftingFor.getOrDefault(whatToCraft, List.of());
        }

        @Override
        public boolean canEmitFor(AEKey someItem) {
            return false;
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, appeng.api.storage.AEKeyFilter filter) {
            return null;
        }

        @Override
        public void refreshNodeCraftingProvider(appeng.api.networking.IGridNode node) {
        }

        @Override
        public void addGlobalCraftingProvider(appeng.api.networking.crafting.ICraftingProvider cc) {
        }

        @Override
        public void removeGlobalCraftingProvider(appeng.api.networking.crafting.ICraftingProvider cc) {
        }

        @Override
        public void refreshGlobalCraftingProvider(appeng.api.networking.crafting.ICraftingProvider provider) {
        }

        @Override
        public java.util.concurrent.Future<appeng.api.networking.crafting.ICraftingPlan> beginCraftingCalculation(
                Level level, appeng.api.networking.crafting.ICraftingSimulationRequester simRequester, AEKey what,
                long amount, CalculationStrategy strategy) {
            throw new UnsupportedOperationException("warmup only");
        }

        @Override
        public appeng.api.networking.crafting.ICraftingSubmitResult submitJob(
                appeng.api.networking.crafting.ICraftingPlan job,
                appeng.api.networking.crafting.ICraftingRequester requestingMachine,
                appeng.api.networking.crafting.ICraftingCPU target, boolean prioritizePower,
                appeng.api.networking.security.IActionSource src) {
            throw new UnsupportedOperationException("warmup only");
        }

        @Override
        public com.google.common.collect.ImmutableSet<appeng.api.networking.crafting.ICraftingCPU> getCpus() {
            return com.google.common.collect.ImmutableSet.of();
        }

        @Override
        public java.util.Set<AEKey> getCraftables(appeng.api.storage.AEKeyFilter filter) {
            return java.util.Set.of();
        }

        @Override
        public boolean isRequesting(AEKey what) {
            return false;
        }

        @Override
        public long getRequestedAmount(AEKey what) {
            return 0;
        }

        @Override
        public boolean isRequestingAny() {
            return false;
        }
    }

    /** 触碰数值域出口类（Ae2Amounts / PlanAmount 静态缓存 / ByteAccount）。 */
    private static final class Ae2AmountsWarmup {        private static void touch() {
            var key = AEItemKey.of(Items.PAPER);
            var counter = new KeyCounter();
            git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts.toKeyCounter(
                    new git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter(), counter);
            var account = new git.chexson.chexsonsaeutils.crafting.numeric.ByteAccount();
            account.add(key, 1L, 1L);
            account.value();
        }
    }

    /**
     * 两级合成链：clock ← gunpowder ×2 ← iron ×4，库存给足，求解必然完整。
     * 图对象经公开构造器手工搭建（不走 GraphCompiler——它需要真实 crafting 服务）。
     */
    private static CraftGraph syntheticGraph() {
        AEItemKey top = AEItemKey.of(Items.CLOCK);
        AEItemKey mid = AEItemKey.of(Items.GUNPOWDER);
        AEItemKey leaf = AEItemKey.of(Items.IRON_INGOT);

        var topPattern = new WarmPattern(top, List.of(new GenericStack(top, 1L)),
                new WarmInput(mid, 2L), new WarmInput(leaf, 1L));
        var midPattern = new WarmPattern(mid, List.of(new GenericStack(mid, 1L)),
                new WarmInput(leaf, 4L));
        var topGraphPattern = new GraphPattern(topPattern, top, 1L,
                List.copyOf(topPattern.getOutputs()), List.of(
                        new git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput(
                                mid, 1L, 2L, git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput.Mode.NORMAL,
                                null, null, null, false),
                        new git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput(
                                leaf, 1L, 1L, git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput.Mode.NORMAL,
                                null, null, null, false)));
        var midGraphPattern = new GraphPattern(midPattern, mid, 1L,
                List.copyOf(midPattern.getOutputs()), List.of(
                        new git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput(
                                leaf, 1L, 4L, git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput.Mode.NORMAL,
                                null, null, null, false)));

        Map<AEKey, List<GraphPattern>> producers = new HashMap<>();
        producers.put(top, List.of(topGraphPattern));
        producers.put(mid, List.of(midGraphPattern));
        Map<IPatternDetails, GraphPattern> patternsBySource = new HashMap<>();
        patternsBySource.put(topPattern, topGraphPattern);
        patternsBySource.put(midPattern, midGraphPattern);

        Map<AEKey, Long> stock = Map.of(leaf, 10_000L);
        Map<AEKey, List<AEKey>> classVariants = Map.of(
                leaf, List.of(leaf), mid, List.of(mid), top, List.of(top));

        return new CraftGraph(top,
                java.util.Collections.unmodifiableMap(producers),
                java.util.Collections.unmodifiableMap(patternsBySource),
                List.of(topPattern, midPattern),
                Map.of(), Map.of(), Map.of(),
                stock, java.util.Set.of(), classVariants,
                false, null, 1, 1);
    }


    private record WarmPattern(AEItemKey definition, List<GenericStack> outputs, WarmInput... inputs)
            implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return this.definition;
        }

        @Override
        public IInput[] getInputs() {
            var array = new IInput[this.inputs.length];
            for (int i = 0; i < this.inputs.length; i++) {
                array[i] = this.inputs[i];
            }
            return array;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return this.outputs;
        }
    }

    private record WarmInput(AEKey key, long multiplier) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(this.key, 1L)};
        }

        @Override
        public long getMultiplier() {
            return this.multiplier;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable Level level) {
            return input.equals(this.key);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
