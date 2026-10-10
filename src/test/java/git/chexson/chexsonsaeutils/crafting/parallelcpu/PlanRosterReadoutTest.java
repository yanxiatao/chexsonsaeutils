package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-1 计划名单一行：作业建表那一刻，对<b>下发计划里的每一张样板</b>记一格
 * {@code definition#稳定id,times=份数原文,->in|DROP(zeroFirings)}，
 * 并同一行自报 {@code shown}/{@code total}/{@code zeroFirings}。
 *
 * <p>要点：0 发那张必须在名单里出现，且带 {@code DROP(zeroFirings)}；只列进表的等于没答。
 * 名单来源就是 {@code ParallelExecutingCraftingJob} 里 {@code executions.signum() <= 0}
 * 那一句——读数出自<b>真丢掉它的那一句</b>，不在别处再算一份"看起来一样的数"。
 */
final class PlanRosterReadoutTest {

    /** 一张进表（times=5），另一张 0 发（times=0）——后者本行必须点名 DROP。 */
    @Test
    void zeroFiringPatternMustBeNamedInRosterLine() {
        var inPattern = new StubPattern(Items.IRON_INGOT);
        var zeroPattern = new StubPattern(Items.GOLD_INGOT);

        var plan = new StubPlan(Map.of(
                inPattern, 5L,
                zeroPattern, 0L));

        var line = captureRosterLine(() -> new ParallelExecutingCraftingJob(
                plan, ignored -> { }, (CraftingLink) null, null));

        // shown/total/zeroFirings 三格与实际张数一致
        assertEquals("shown=2/total=2", cellOf(line, "shown="),
                "两张都要出现在名单里（截断不许吞掉 0 发那张）");
        assertEquals("zeroFirings=1", cellOf(line, "zeroFirings="),
                "表里真丢掉了 1 张 0 发样板，格子里必须当场对上");
        // 0 发那张必须被点名，且带 DROP
        assertTrue(line.contains(zeroPattern.getDefinition().toString().replace(' ', '_')
                        + ParallelCraftingCpuLogic.patternIdentityTag(zeroPattern)
                        + ",times=0,->DROP(zeroFirings)"),
                "名单里必须出现 0 发那张、带 DROP(zeroFirings)，实得：" + line);
        // 进表那一张带 in，times=5
        assertTrue(line.contains(inPattern.getDefinition().toString().replace(' ', '_')
                        + ParallelCraftingCpuLogic.patternIdentityTag(inPattern)
                        + ",times=5,->in"),
                "名单里也要看得见进表的 in 那一张（不许只列 DROP 名单），实得：" + line);
    }

    /** 名单一行按空格切出的<b>格</b>必须都是 key=value 形状（值内不许有空白）。 */
    @Test
    void everyRosterCellIsWhitespaceFreeKeyValue() {
        var a = new StubPattern(Items.IRON_INGOT);
        var b = new StubPattern(Items.GOLD_INGOT);
        var c = new StubPattern(Items.DIAMOND);
        var plan = new StubPlan(Map.of(a, 3L, b, 0L, c, 7L));

        var line = captureRosterLine(() -> new ParallelExecutingCraftingJob(
                plan, ignored -> { }, (CraftingLink) null, null));

        // 前缀固定 "Graph planning pattern roster: " 之后按空格切，每格必须是 key=value
        var head = "Graph planning pattern roster:";
        assertTrue(line.startsWith(head), "行头必须固定，实得：" + line);
        for (var cell : line.substring(head.length()).trim().split(" ")) {
            assertTrue(cell.contains("="), "名单每一格必须是 key=value 形状，实得：" + cell);
        }
        assertEquals("shown=3/total=3", cellOf(line, "shown="),
                "三张都要进表——三张都要出现在名单里");
        assertEquals("zeroFirings=1", cellOf(line, "zeroFirings="));
    }

    // ---- fixtures ----

    /**
     * 抓名单那一行。
     *
     * <p><b>捕获档必须是 DEBUG</b>：主代码用 {@code LOGGER.debug} 打这行——作者读的现场文件是
     * PrismLauncher 实例的 {@code debug.log}（DEBUG 档在那儿开着），{@code latest.log} 里没有这行。
     * 本用例原先把 appender 挂在 {@code Level.INFO} 上，又没动 root 档，DEBUG 事件在 logger 这一层
     * 就被拦下，捕获列表恒空——红在<b>探针挂错档</b>，不在打印形状。这里临时把 root 降到 DEBUG、
     * 按 DEBUG 挂探针，{@code finally} 里逐字还原，不碰产品日志配置、不加开关。
     */
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

    /** 取回名单行里以 {@code prefix} 开头的那一整格（按空格切），找不到抛错——别做永真断言。 */
    private static String cellOf(String line, String prefix) {
        for (var part : line.split(" ")) {
            if (part.startsWith(prefix)) {
                return part;
            }
        }
        throw new AssertionError("名单里没有 " + prefix + " 开头的格：" + line);
    }

    /** 一张样板：definition 用不同物品区分，无输入无输出。 */
    private static final class StubPattern implements IPatternDetails {
        private final AEItemKey definition;

        StubPattern(Item item) {
            this.definition = AEItemKey.of(new net.minecraft.world.item.ItemStack(item));
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

    /** ICraftingPlan 只暴露建表用到的几个入口：finalOutput/emittedItems/patternTimes。 */
    private static final class StubPlan implements ICraftingPlan {
        private final Map<IPatternDetails, Long> times;

        StubPlan(Map<IPatternDetails, Long> times) {
            // 保序：LinkedHashMap 让名单里的次序就是插入次序
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
            super("plan-roster-capture", null,
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
