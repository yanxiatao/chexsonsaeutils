package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑧ —— {@code describeHoles} 那把"同时摆图上生产者数与计划份数"的尺，过去只在 shortage 非空时经
 * 常规日志出口打；实机尾段卡死那一跑走的是 idle 出口 ⇒ 那把尺一个字没出。
 *
 * <p>本用例打在"打印者真会打印它"上：
 * <ul>
 * <li>idle 档现在也交回<b>一行同一把尺</b>（{@code Graph planning shortage holes idle:}），
 *     且如实自报 {@code keys=0}——不造 shortage、不加判据，只让"尺跑了覆盖 0 枚"与"尺压根没跑"分得开。</li>
 * <li>非 idle 档同一把尺自报 {@code keys}/{@code shown}：shortage 有 5 枚只逐一点名前 3 枚时，
 *     必须报 {@code keys=5 shown=3}，不许让人把"点了前几枚"当成全量。</li>
 * </ul>
 */
final class GraphPlanHolesIdleReadoutTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey EMERALD = AEItemKey.of(Items.EMERALD);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);

    /** idle 出口：判决不带逐键缺口账 ⇒ 那把尺此刻交回一行 {@code keys=0 shown=0 []}。 */
    @Test
    void idleExitStillEmitsTheHolesRulerSelfReportingZeroKeys() {
        var graph = graph();

        var line = capture("Graph planning shortage holes idle:", () ->
                GraphPlanPostProcessor.shortagePlan(
                        PlanningAttemptResult.declined("graph planning budget exhausted"), graph));

        assertTrue(line.contains("keys=0"),
                () -> "idle 档必须交回同一把尺并自报覆盖 0 枚键（shortage 空是 idle 的前提，不许编缺口）："
                        + line);
        assertTrue(line.contains("[]"), () -> "覆盖 0 枚 ⇒ 尺体为空方括号，逐字同一出口形状：" + line);
    }

    /**
     * 非 idle：5 枚缺口键只逐一点名前 3 枚 ⇒ 尺体自报 {@code keys=5}（全量）、{@code shown=3}（点名的），
     * 证明"点了前几枚"不等于"全量"。
     */
    @Test
    void nonIdleHolesRulerReportsFullKeyCountBeyondTheShownThree() {
        var graph = graph();
        var state = new GraphPlanningState(Map.of(), Map.of());
        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        long step = 0L;
        for (AEKey key : List.of(GOLD, COPPER, DIAMOND, EMERALD, REDSTONE)) {
            shortage.put(key, PlanAmount.of(BigInteger.valueOf(100L + step++)));
        }
        var attempt = PlanningAttemptResult.declinedWithLedger(
                SolveResult.declinedShortage("material closure violated: multi-key", shortage, state, 1L));

        var line = capture("Graph planning shortage holes:", () ->
                GraphPlanPostProcessor.shortagePlan(attempt, graph));

        assertTrue(line.contains("keys=5"),
                () -> "缺口有 5 枚就必须自报 keys=5（全量），实得：" + line);
        assertTrue(line.contains("shown=3"),
                () -> "尺只逐一点名前 3 枚，必须自报 shown=3，不许当成全量：" + line);
    }

    // ---- fixtures ----

    private static String capture(String prefix, Runnable body) {
        var appender = new CapturingAppender();
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var rootLogger = configuration.getRootLogger();
        var previousLevel = rootLogger.getLevel();
        try {
            appender.start();
            configuration.addAppender(appender);
            rootLogger.addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
            rootLogger.setLevel(org.apache.logging.log4j.Level.INFO);
            context.updateLoggers();
            body.run();
            return appender.lastLine(prefix);
        } finally {
            rootLogger.removeAppender(appender.getName());
            rootLogger.setLevel(previousLevel);
            context.updateLoggers();
            appender.stop();
        }
    }

    private static CraftGraph graph() {
        var pattern = new GraphPattern(new FakeDetails(), IRON, 1L,
                List.of(new GenericStack(IRON, 1L)), List.of());
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(), Set.of(), Map.of(), false, null, 1, 1);
    }

    private static final class FakeDetails implements IPatternDetails {
        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(IRON, 1L));
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

    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("graph-plan-holes-idle-capture", null,
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

        String lastLine(String prefix) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).startsWith(prefix)) {
                    return lines.get(i);
                }
            }
            throw new AssertionError("没抓到前缀为 " + prefix + " 的行；所有行=" + lines);
        }
    }
}
