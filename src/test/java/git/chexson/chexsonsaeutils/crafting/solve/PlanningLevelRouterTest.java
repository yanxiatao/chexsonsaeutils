package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.events.GridEvent;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.ParallelCpuProvider;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门控用例：图算法<b>只服务自有并行 CPU</b>。
 *
 * <p>非自有并行 CPU 的普通/原生网格一律不进我方算法路径，回落 AE2 原生树。
 * 两个 any-grid 直通入口与已删档的 fast 键（{@code parallelCpuFastPlanningEnabled}
 * 与 {@code parallelCpuFastPlanningBudgetMillis}）都已按战略整体删除、不留兼容壳，
 * 因此旧 TOML 里残留的这些键必须读不到即忽略——本用例就把它们写成 {@code true} 来验。
 */
final class PlanningLevelRouterTest {

    /** 启动期 TOML 回落读取器的目录覆盖键，见 {@code ChexsonsaeutilsCompatibilityConfig.StartupConfigReader}。 */
    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

    /** 旧实例配置里可能残留的 any-grid 键：全部为 true，也不该放开任何网格。 */
    private static final String STALE_ANY_GRID_TOML = """
            [parallelCraftingCpuTool]
            parallelCpuFastPlanningEnabled = true
            parallelCpuFastPlanningBudgetMillis = 10000
            parallelCraftingCpuEnabled = true
            graphPlanningEnabled = true
            parallelCpuFastPlanningAnyGridEnabled = true
            graphPlanningAnyGridEnabled = true
            """;

    @TempDir
    static Path configDir;

    private String previousConfigDir;

    @BeforeEach
    void pointStartupReaderAtStaleConfig() throws IOException {
        previousConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        Files.writeString(configDir.resolve("chexsonsaeutils-common.toml"), STALE_ANY_GRID_TOML);
        System.setProperty(CONFIG_DIR_PROPERTY, configDir.toAbsolutePath().toString());
    }

    @AfterEach
    void restoreConfigDirProperty() {
        if (previousConfigDir == null) {
            System.clearProperty(CONFIG_DIR_PROPERTY);
        } else {
            System.setProperty(CONFIG_DIR_PROPERTY, previousConfigDir);
        }
    }

    private static FakeGrid plainGridWithoutParallelCpu() {
        return FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L)))
                .build();
    }

    @Test
    void nullGridNeverTakesOurAlgorithms() {
        assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(null), "无网格不该进 graph 路径");
    }

    @Test
    void plainGridStaysOnNativePathEvenWithStaleAnyGridKeys() {
        FakeGrid grid = plainGridWithoutParallelCpu();
        assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid),
                "普通/原生网格（无自有并行 CPU）不进 graph 路径，任何配置都开不了这个口");
    }

    @Test
    void gridHostedForeignOwnerStaysOnNativePath() {
        FakeGrid grid = plainGridWithoutParallelCpu();
        // 宿主存在但未提供本模组的并行 CPU 能力。
        grid.node.owner = new Object();
        assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "外来宿主网格不进 graph 路径");
    }

    @Test
    void activeParallelCpuProviderEnablesGraphPlanning() {
        FakeGrid grid = plainGridWithoutParallelCpu();
        grid.node.owner = (ParallelCpuProvider) () -> true;
        assertTrue(PlanningLevelRouter.shouldUseGraphPlanning(grid), "活跃并行 CPU 应启用图规划");
    }

    @Test
    void inactiveParallelCpuProviderStaysOnNativePath() {
        FakeGrid grid = plainGridWithoutParallelCpu();
        grid.node.owner = (ParallelCpuProvider) () -> false;
        assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(grid), "非活跃并行 CPU 不启用图规划");
    }

    @Test
    void emptyGridStaysOnNativePath() {
        assertFalse(PlanningLevelRouter.shouldUseGraphPlanning(
                new NodeGrid(plainGridWithoutParallelCpu(), List.of())), "空网格不启用图规划");
    }

    @Test
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

    @Test
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

    @Test
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

    /**
     * 判据（删 FAST 档之后）：图没有交出计划时只有一条去路——AE2 原生树。
     *
     * <p>断的是**真读数**而不是方法签名：真调用一次 {@code logFallbackPlan}，从 appender 取回
     * 那一行，档位必须钉死在 {@code (native)}——重新长出"按档位字符串分叉"就当场红。
     * 同时断它把交进来的计划**原样交出**（回落腿不重新规划、不改道）。
     */
    @Test
    void fallbackLineNamesTheNativeTierAndReturnsThePlanUntouched() {
        assertTrue(captureFallbackLine().startsWith("Graph fallback (native): minecraft:iron_ingot x 7,"),
                "回落腿那一行必须只报原生树这一档（重新长出档位分叉就红）");
    }

    /** 真调用 {@code logFallbackPlan}，把它发出去的那一行取回来；返回 null 是有意的（不重新规划）。 */
    private static String captureFallbackLine() {
        CapturingAppender appender = captureLines("Graph fallback", () ->
                assertNull(PlanningLevelRouter.logFallbackPlan(
                        new GenericStack(IRON, 7L), null, System.nanoTime()),
                        "回落腿只能交出原生树那份计划，不许自己再造一份"));
        assertEquals(1, appender.lines.size(), "这一次调用只该发一行读数，实际= " + appender.lines);
        return appender.lines.get(0);
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
}
