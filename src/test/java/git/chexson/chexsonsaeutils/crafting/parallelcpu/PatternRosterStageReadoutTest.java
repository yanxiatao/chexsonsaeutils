package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.crafting.plan.PatternRosterReadout;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑩ —— 计划名单两档（{@code stage=exit} 规划出口 / {@code stage=build} 建表）<b>同一把尺</b>。
 *
 * <p>四条用例各钉一件事，名单宇宙一律经
 * {@link PatternRosterReadout#universeAndTimes(ICraftingPlan)} 从<b>真计划对象</b>派生：
 * <ol>
 * <li>{@code stage=exit} 那一行由<b>真出口</b>（{@link GraphPlanningSession} 转换完成处）打出来，
 * 用例捕获它并核对抬头、{@code shown/total} 与同一份 plan 的 {@code patternTimes().size()}；</li>
 * <li>两档除抬头逐字相等，且两侧读的都是<b>宽域份数真值</b>——代表值字典里那颗饱和哨兵不许露面；</li>
 * <li>exit 有、build 没有的那张凭 token 点名：两份 plan 的字典<b>真的</b>差那一张；</li>
 * <li>{@code stage=build} 那一行由真建表（{@link ParallelExecutingCraftingJob}）打出来。</li>
 * </ol>
 *
 * <p><b>{@code #s} 跨 useless 折叠壳不等</b>（本轮读 {@code useless_mod-1.21.1-2.3.9.jar} 的字节码
 * 定的事实，不是推断）：{@code ScaledProcessingPattern(inner, k)} 三条腿全改——
 * {@code definition} 换成 {@code SmartDoublingPatterns.executionDefinition(inner, k)}（同一件物品上把
 * {@code SMART_DOUBLING_OPERATIONS} 组件先 remove 再 set 成 {@code k}）、
 * {@code getOutputs()} 每枚 {@code Math.multiplyExact(amount, k)}、
 * {@code getInputs()[i].getMultiplier()} 也 {@code × k}（候选表本身不变）。
 * {@link ParallelCraftingCpuLogic#patternStableDigest} 读的正是这三条腿，于是壳内外 {@code #s}
 * <b>必然</b>不同。两档对拍因此不靠 {@code #s} 跨壳对上：名单 token 里本来就有
 * {@code definition}，跨壳靠 definition ＋ 份数并列；只有同名两张才退化到 {@code #i}/{@code #s}
 * 分家（分的是<b>同会话的两个实例</b>，不是壳前壳后）。这一条由
 * {@code PatternStableIdentityTest#scalingFoldingShellDoesNotPreserveStableDigest} 钉住。
 */
final class PatternRosterStageReadoutTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey RAW = AEItemKey.of(Items.RAW_IRON);

    /**
     * 越界那一档的发数真值与它在 AE2 代表值字典里的饱和哨兵（两个数不许在名单行里互换）。
     * 真值必须<b>真的</b>越出 {@code long}——{@code WideCraftSnapshot.capture} 只留越界那批，
     * 装得进 long 的数压根不进旁路账，拿了也就测不到"两档读的是同一本宽域账"。
     */
    private static final BigInteger WIDE_TIMES = new BigInteger("22000000000000000000");
    private static final String SENTINEL_TIMES = String.valueOf(Long.MAX_VALUE);

    /**
     * 主臂：{@code stage=exit} 那一行由真出口打出来，用例只负责<b>捕获</b>它。
     *
     * <p>走完整条规划链（{@code GraphCompiler.compile → solve → 出口对账 → convert}），
     * 打印点就是 {@code GraphPlanningSession} 里那一行 {@code LOGGER.debug}，没有第二份组装。
     */
    @Test
    void exitPrinterEmitsRosterLineThroughTheRealSessionExit() {
        var maker = FakePattern.of(IRON, 1L, new FakeInput(RAW, 2L));     // RAW×2 → IRON×1
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(IRON, 3L));  // IRON×3 → CLOCK×1
        var grid = FakeGrid.builder()
                .storage(Map.of(RAW, 100L))
                .pattern(IRON, maker)
                .pattern(TOP, consumer)
                .build();
        var networkStock = new KeyCounter();
        networkStock.add(RAW, 100L);
        var session = GraphPlanningSession.create(grid.crafting, networkStock, TOP,
                CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(0L), null, false);

        var attemptBox = new PlanningAttemptResult[1];
        var line = captureRosterLine(() -> attemptBox[0] = session.solve(2L));
        var attempt = attemptBox[0];
        assertNotNull(attempt, "solve 必须交回一次判决");
        assertTrue(attempt.handled(), () -> "前提：这一单得出得了计划：" + attempt.declineReason());
        var plan = attempt.plan();
        assertNotNull(plan, "handled 的判决必须带着计划回来");

        assertTrue(line.startsWith("Graph planning pattern roster: stage=exit "),
                () -> "规划出口那一行必须带 stage=exit 抬头（打印点在真出口上）：" + line);
        int total = plan.patternTimes().size();
        assertTrue(line.contains("shown=" + total + "/total=" + total),
                () -> "shown/total 必须与同一份 plan 的 patternTimes().size()=" + total
                        + " 可核对：" + line);
        // 份数点名：钟 2 发、铁 6 发（6 枚铁 / 每发 1 枚），两张都进表（没有 0 发的那一档）。
        assertTrue(line.contains(maker.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(maker) + ",times=6,->in"),
                () -> "生产者那张要凭自己的身份与份数点名：" + line);
        assertTrue(line.contains(consumer.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(consumer) + ",times=2,->in"),
                () -> "消费者那张要凭自己的身份与份数点名：" + line);
        assertFalse(line.contains("DROP"), () -> "两张都排到了产，不该有 DROP：" + line);
    }

    /**
     * 两档同尺的正向核：两份<b>各自独立</b>构造的真计划（同一批样板实例、同一份宽域份数账），
     * 除抬头逐字相等。
     *
     * <p>这一条不许写成"同一份 map 喂两次"：宇宙必须由 {@code universeAndTimes(plan)} 从计划对象
     * 现派生——哪一档退回读 {@code patternTimes()} 那份 {@code long} 代表值，抬头之外的那一截就会
     * 把饱和哨兵 {@code 9223372036854775807} 印出来，逐字相等当场红。哨兵不许露面由第三条断言
     * 单独钉（它与"两行相等"不是同一判据，缺一条就是永真式）。
     */
    @Test
    void bothStagesRenderTheSameWideRulerOnTwoRealPlans() {
        var folded = new StubPattern(Items.NETHERITE_INGOT);
        var plain = new StubPattern(Items.GOLD_INGOT);

        var exitLine = PatternRosterReadout.formatLine("exit",
                PatternRosterReadout.universeAndTimes(planWithWideTimes(folded, plain)));
        var buildLine = PatternRosterReadout.formatLine("build",
                PatternRosterReadout.universeAndTimes(planWithWideTimes(folded, plain)));

        assertEquals(exitLine.substring("stage=exit ".length()),
                buildLine.substring("stage=build ".length()),
                () -> "两档必须同一把尺、只差 stage= 抬头。exit=" + exitLine + " build=" + buildLine);
        assertTrue(exitLine.contains(",times=" + WIDE_TIMES + ","),
                () -> "份数读宽域真值 " + WIDE_TIMES + "，不是代表值字典里那个数：" + exitLine);
        assertFalse(exitLine.contains(",times=" + SENTINEL_TIMES + ","),
                () -> "饱和哨兵是标记不是量，不许进名单：" + exitLine);
        assertTrue(exitLine.contains("shown=2/total=2"), () -> exitLine);
    }

    /**
     * exit 里存在、build 里消失的那张凭 token 点名：<b>两份 plan 的字典真的差那一张</b>
     * （C 在规划出口之后、建表之前整张从计划里消失），宇宙由 {@code universeAndTimes} 现派生。
     */
    @Test
    void patternThatVanishesBetweenExitAndBuildIsNamedByIdentityTag() {
        var a = new StubPattern(Items.IRON_INGOT);
        var b = new StubPattern(Items.GOLD_INGOT);
        var vanished = new StubPattern(Items.DIAMOND);

        var exitTimes = new LinkedHashMap<IPatternDetails, Long>();
        exitTimes.put(a, 3L);
        exitTimes.put(b, 7L);
        exitTimes.put(vanished, 2L);
        var buildTimes = new LinkedHashMap<IPatternDetails, Long>();
        buildTimes.put(a, 3L);
        buildTimes.put(b, 7L);

        var exit = PatternRosterReadout.formatLine("exit",
                PatternRosterReadout.universeAndTimes(planWithTimes(exitTimes)));
        var build = PatternRosterReadout.formatLine("build",
                PatternRosterReadout.universeAndTimes(planWithTimes(buildTimes)));

        var vanishedToken = vanished.getDefinition()
                + ParallelCraftingCpuLogic.patternIdentityTag(vanished) + ",times=2,->in";
        assertTrue(exit.contains(vanishedToken),
                () -> "消失那张必须在 stage=exit 里点名（含它的身份标），实得：" + exit);
        var vanishedTag = ParallelCraftingCpuLogic.patternIdentityTag(vanished);
        assertFalse(build.contains(vanishedTag),
                () -> "stage=build 里没有它，才说明它在规划出口之后被整张丢掉：" + build);
        assertTrue(exit.contains("shown=3/total=3"), () -> exit);
        assertTrue(build.contains("shown=2/total=2"), () -> build);
    }

    /**
     * 建表那一档真会打印 {@code stage=build}：走真 {@link ParallelExecutingCraftingJob} 构造，
     * 捕获 {@code Graph planning pattern roster:} 那一行（DEBUG 档，落 debug.log）。
     */
    @Test
    void buildPrinterEmitsRosterLineWithStageBuild() {
        var a = new StubPattern(Items.IRON_INGOT);
        var zero = new StubPattern(Items.GOLD_INGOT);
        var plan = new StubPlan(new LinkedHashMap<>(Map.of(a, 5L, zero, 0L)));

        var line = captureRosterLine(() -> new ParallelExecutingCraftingJob(
                plan, ignored -> { }, (CraftingLink) null, null));

        assertTrue(line.startsWith("Graph planning pattern roster: stage=build "),
                () -> "建表那一行必须带 stage=build 抬头，实得：" + line);
        assertTrue(line.contains("shown=2/total=2"), () -> line);
        assertTrue(line.contains("zeroFirings=1"), () -> line);
    }

    // ---- 夹具 ----

    /** 真 AE2 计划：名单宇宙的另一半（宽域份数账）交给 {@link RecursiveCraftingPlan} 载体。 */
    private static ICraftingPlan planWithTimes(LinkedHashMap<IPatternDetails, Long> times) {
        return new CraftingPlan(new GenericStack(TOP, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
    }

    /**
     * 一份"代表值到顶、真值还在涨"的真计划（与实机大订单同形）：
     * {@code folded} 的 {@code patternTimes} 写 {@code Long.MAX_VALUE} 哨兵、宽域账写
     * {@code 2.2e19}（越界才会进 {@code WideCraftSnapshot}），另一张两边同值。
     * 两次调用交回<b>两个不同的计划对象</b>。
     */
    private static ICraftingPlan planWithWideTimes(StubPattern folded, StubPattern plain) {
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(folded, Long.MAX_VALUE);
        times.put(plain, 5L);
        var crafts = new WideCraftCounter();
        crafts.add(folded, PlanAmount.of(WIDE_TIMES));
        crafts.add(plain, PlanAmount.of(5L));
        var base = new CraftingPlan(new GenericStack(TOP, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 1L,
                Map.of(), new IdentityHashMap<IPatternDetails, WideKeyCounter>(),
                null, WideCraftSnapshot.capture(crafts));
    }

    /** 把 {@code body} 期间打进根 logger 的那一行名单读数收下来（DEBUG 档，与真出口同一通道）。 */
    private static String captureRosterLine(Runnable body) {
        var appender = new CapturingAppender();
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var rootLogger = configuration.getRootLogger();
        var previousLevel = rootLogger.getLevel();
        try {
            appender.start();
            configuration.addAppender(appender);
            rootLogger.addAppender(appender, org.apache.logging.log4j.Level.DEBUG, null);
            rootLogger.setLevel(org.apache.logging.log4j.Level.DEBUG);
            context.updateLoggers();
            body.run();
            return appender.lastLine("Graph planning pattern roster:");
        } finally {
            rootLogger.removeAppender(appender.getName());
            rootLogger.setLevel(previousLevel);
            context.updateLoggers();
            appender.stop();
        }
    }

    private static final class StubPattern implements IPatternDetails {
        private final AEItemKey definition;

        StubPattern(Item item) {
            this.definition = AEItemKey.of(new ItemStack(item));
        }

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
            return this.definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    private static final class StubPlan implements ICraftingPlan {
        private final Map<IPatternDetails, Long> times;

        StubPlan(Map<IPatternDetails, Long> times) {
            this.times = new LinkedHashMap<>(times);
        }

        @Override
        public long bytes() {
            return 0L;
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return this.times;
        }

        @Override
        public GenericStack finalOutput() {
            return null;
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return new KeyCounter();
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public boolean simulation() {
            return false;
        }
    }

    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("roster-stage-capture", null,
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
