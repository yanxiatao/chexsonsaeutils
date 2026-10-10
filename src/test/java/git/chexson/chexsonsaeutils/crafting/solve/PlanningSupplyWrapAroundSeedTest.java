package git.chexson.chexsonsaeutils.crafting.solve;

import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #197：AE2 的聚合 {@code long} 加法回绕且不饱和——「两片各 &gt; {@code Long.MAX}/2 的库存
 * 相加」得到的是<b>负数</b>（{@code 5e18+5e18 ⇒ -8446744073709551616}），而规划的验真层今天只认
 * 「恰等哨兵 {@code Long.MAX_VALUE}」（{@code PlanningSupplyTruth#capture} 与
 * {@code GraphPlanPostProcessor:57}），回绕成别的值的键被逐字当本数种进沙盒。
 *
 * <p>反向臂：规划看到的该键供给必须等于<b>逐存储宽域真值</b>（1e19），需求越出真值的那一截
 * （1.6e19 − 1e19 = 6e18）必须被既有缺料账点名上报，而不是拿回绕读数放行或整截判空。
 * HEAD 在这里必须逐字红：{@code supplyOf} 读回的是回绕负数。
 *
 * <p>正向臂：普通键（两片各 1e9，聚合读数可信）的规划供给与求解下发数字<b>逐字不变</b>——
 * 下列字面数字取自 HEAD 实跑，改后必须一字不差。
 */
final class PlanningSupplyWrapAroundSeedTest {

    /** 反向臂的键：网络里有真生产者以外的来路——没有生产者，只靠库存。 */
    private static final AEItemKey CELL_KEY = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEItemKey PLAIN_KEY = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);

    private static final long FRAG_WIDE = 5_000_000_000_000_000_000L;
    private static final long WRAPPED_AGGREGATE = -8446744073709551616L;
    private static final BigInteger TRUTH_WIDE = new BigInteger("10000000000000000000");
    private static final long FRAG_PLAIN = 1_000_000_000L;

    /** 前提本身也要有牙：两片各 > Long.MAX/2，AE2 真实聚合（KeyCounter→fastutil addTo）回绕成负数。 */
    @Test
    void ae2AggregationOfTwoBigFragmentsWrapsNegative() {
        var stock = new KeyCounter();
        stock.add(CELL_KEY, FRAG_WIDE);
        stock.add(CELL_KEY, FRAG_WIDE);
        assertEquals(WRAPPED_AGGREGATE, stock.get(CELL_KEY),
                "两片各 5e18 经 AE2 普通 long 加法必须回绕成负数（前提，不许随实现漂移）");
    }

    /** 反向臂一：回绕读数的键，规划供给必须读成逐存储宽域真值 1e19。 */
    @Test
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
    @Test
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
    @Test
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
    @Test
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
}
