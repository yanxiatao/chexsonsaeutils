package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥ —— {@link GraphCompiler} 四道静默口各给一个独立计数，汇成<b>一行常规可读</b>（{@code INFO}）读数。
 *
 * <p>实机那一跑（bigint 计划卡死）里，规划出口自称闭合（{@code gross=[ok keys=68]}），而作业里某枚键
 * {@code src=NONE ∧ toMake=0 ∧ left>0}。问不出的那一型是<b>「图上有生产路线、却没排进任务表、
 * 又不进 shortage 账」</b>：它出在编译期把样板挡出图的那几道口——今天这三档 compile skip 只汇成
 * {@code skippedPatterns=} 一个数、那一行还在诊断档（{@code GraphDiagnostics.log}），
 * {@code getCraftingFor} 空集那一格连 trace 都不打，{@code canEmitFor} 抢身份只有 trace 看得见，
 * 而 trace 默认关（实机全文件 {@code skippedPatterns}／{@code decline=}／{@code compile probe} 各 0 命中）。
 *
 * <p>本读数只让人<b>看见</b>这几道口各挡了几张，<b>一个字都不改 skip 判据</b>、不进任何闸门。
 * 覆盖面自报：{@code askedKeys}（这一趟问了提供者清单的键数）／{@code zeroCandidateKeys}（问清单却
 * 一张没递进 {@code producers}）／{@code shown/total} 式样例点名（≤4 枚，不许当全量）。
 * {@code admit} 是编译期逐键已在手的 {@code getCraftingFor} 命中总数（不另开全量扫描）；
 * {@code host} 在编译期<b>读不到真值</b>——这一趟只握有 {@code ICraftingService} 与样板表，
 * 提供者／机器实例不在视野内，如实标 {@code NA}，不拿别的集合冒充。
 */
final class GraphCompilerSilentSkipReadoutTest {

    private static final AEItemKey CLOCK = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey SNOWBALL = AEItemKey.of(Items.SNOWBALL);
    /** 被 {@code ancestorOutput} 挡掉的那张样板的 definition（样例点名要认得出是哪张）。 */
    private static final AEItemKey LOOP_DEF = AEItemKey.of(Items.CRAFTING_TABLE);
    /** 被 {@code emptySlot} 挡掉的那张样板的 definition。 */
    private static final AEItemKey EMPTY_SLOT_DEF = AEItemKey.of(Items.FURNACE);
    /** 被 {@code ancestorInput} 挡掉的那张样板的 definition。 */
    private static final AEItemKey BACK_EDGE_DEF = AEItemKey.of(Items.COMPASS);

    /**
     * 档位 {@code ancestorOutput} 真挡了一张样板：{@code GLASS} 名下登记了一张「产出 {@code CLOCK}」
     * 的样板——{@code CLOCK} 正是它的祖先链，compile skip 第一档把它挡在图外。
     * 于是 {@code GLASS} 一枚候选都没递进 {@code producers}：正是实机问不出的那一型。
     */
    @Test
    void ancestorOutputSkipIsCountedAndNamedWithItsPattern() {
        var top = FakePattern.of(CLOCK, 1L, FakeInput.stack(GLASS, 1L));
        // 注册在 GLASS 名下、产出 CLOCK（祖先链上的一枚）⇒ compilePattern 第一档 skip
        var backEdge = FakePattern.colored(-1, LOOP_DEF,
                List.of(new GenericStack(CLOCK, 1L)),
                FakeInput.stack(REDSTONE, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(GLASS, 10L, REDSTONE, 100L))
                .pattern(CLOCK, top)
                .pattern(GLASS, backEdge)
                .build();

        var line = captureReadout(grid, CLOCK);

        assertTrue(line.contains("askedKeys=2"), () -> "问了 2 枚键（CLOCK 与 GLASS）：" + line);
        assertTrue(line.contains("admit=2 host=NA"),
                () -> "admit＝两枚键各命中 1 张（共 2，编译期在手）；host 读不到真值、必须如实 NA：" + line);
        assertTrue(line.contains("ancestorOutput=1/1"),
                () -> "ancestorOutput 那一档必须自己记到 1 张，不能只汇进 skippedPatterns：" + line);
        assertTrue(line.contains("ancestorOutputSamples=[minecraft:glass,minecraft:crafting_table]"),
                () -> "样例点名「哪枚键的哪张样板」被挡：" + line);
        assertTrue(line.contains("zeroCandidateKeys=1/1"),
                () -> "GLASS 一枚候选都没递出来 ⇒ 覆盖面要报 1/1：" + line);
        assertTrue(line.contains("zeroCandidateSamples=[minecraft:glass,pats=1,via=ancestorOutput]"),
                () -> "要点名 AE2 递了几张、被哪一档吃掉：" + line);
        // 别的档位各记各的：没触发的三档必须报 0，不许跟着沾光
        assertTrue(line.contains("noProvider=0/0"), () -> "没有空集那一档：" + line);
        assertTrue(line.contains("emptySlot=0/0"), () -> "没有空槽那一档：" + line);
        assertTrue(line.contains("ancestorInput=0/0"), () -> "没有祖先链输入那一档：" + line);
        assertTrue(line.contains("emittable=0/0"), () -> "没有 emit 抢身份那一档：" + line);
    }

    /**
     * 档位 {@code emptySlot} 真挡了一张样板：槽的候选表交回空数组、没有 primary 候选。
     * 这一条同时证「四档各自一个<b>独立</b>计数」——上一档红的臂与这一档互不沾光。
     */
    @Test
    void emptyCandidateSlotSkipHasItsOwnCounter() {
        // 一个候选都没有的槽：getPossibleInputs() 交回空数组 ⇒ compilePattern 第二档 skip
        var hollow = FakePattern.colored(-1, EMPTY_SLOT_DEF,
                List.of(new GenericStack(CLOCK, 1L)),
                FakeInput.multi(1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .pattern(CLOCK, hollow)
                .build();

        var line = captureReadout(grid, CLOCK);

        assertTrue(line.contains("emptySlot=1/1"),
                () -> "emptySlot 那一档必须自己记到 1 张：" + line);
        assertTrue(line.contains("emptySlotSamples=[minecraft:clock,minecraft:furnace]"),
                () -> "样例点名被挡的那张样板：" + line);
        assertTrue(line.contains("zeroCandidateKeys=1/1"),
                () -> "根键一张没递出来：" + line);
        assertTrue(line.contains("zeroCandidateSamples=[minecraft:clock,pats=1,via=emptySlot]"),
                () -> "via 要指向真吃掉它的那一档：" + line);
        assertTrue(line.contains("askedKeys=1"), () -> "只问了根键一枚：" + line);
        assertTrue(line.contains("admit=1 host=NA"), () -> "admit 是在手读数、host 读不到：" + line);
        assertTrue(line.contains("ancestorOutput=0/0"), () -> "另一档不许跟着沾光：" + line);
        assertTrue(line.contains("ancestorInput=0/0"), () -> "另一档不许跟着沾光：" + line);
        assertTrue(line.contains("noProvider=0/0"), () -> "另一档不许跟着沾光：" + line);
        assertTrue(line.contains("emittable=0/0"), () -> "另一档不许跟着沾光：" + line);
    }

    /**
     * 什么都没挡：那一行<b>仍要打</b>，且各档报 0——用来把「没打这行」与「打了但全 0」分得开。
     * 夹具是一张无输入槽的样板（编译一路走到 {@code compiled.add}，四道口一个都不碰）。
     */
    @Test
    void readoutIsStillPrintedWhenNothingWasSkipped() {
        var clean = FakePattern.of(CLOCK, 1L);
        var grid = FakeGrid.builder()
                .storage(Map.of(CLOCK, 1L))
                .pattern(CLOCK, clean)
                .build();

        var line = captureReadout(grid, CLOCK);

        assertTrue(line.contains("noProvider=0/0"), () -> "空集那一档要如实报 0：" + line);
        assertTrue(line.contains("emittable=0/0"), () -> "emit 抢身份那一档要如实报 0：" + line);
        assertTrue(line.contains("ancestorOutput=0/0"), () -> "compile skip 第一档要如实报 0：" + line);
        assertTrue(line.contains("emptySlot=0/0"), () -> "compile skip 第二档要如实报 0：" + line);
        assertTrue(line.contains("ancestorInput=0/0"), () -> "compile skip 第三档要如实报 0：" + line);
        assertTrue(line.contains("zeroCandidateKeys=0/0"), () -> "覆盖面：一枚都没被挡空：" + line);
        assertTrue(line.contains("askedKeys=1"), () -> "覆盖面：问了 1 枚键：" + line);
        assertTrue(line.contains("admit=1 host=NA"), () -> "admit 在手、host 读不到：" + line);
        assertTrue(line.contains("zeroCandidateSamples=[]") && line.contains("ancestorOutputSamples=[]"),
                () -> "全 0 时样例格子也要打出来（空表），别让人以为整行没打：" + line);
    }

    /**
     * 另外两口（{@code noProvider}＝{@code getCraftingFor} 交回空集、今天连 trace 都不打；
     * {@code emittable}＝{@code canEmitFor} 在 {@code expand} 里抢走身份）各记各的：
     * 空集那一枚进 {@code zeroCandidate}（{@code pats=0}），可发射那一枚<b>不算</b>问了清单的键
     * ——它在问清单之前就出门了，所以只进 {@code emittable} 与 {@code askedKeys} 之外。
     */
    @Test
    void emptyProviderSetAndEmittableGrabEachGetTheirOwnCounter() {
        var top = FakePattern.of(CLOCK, 1L,
                FakeInput.stack(GLASS, 1L), FakeInput.stack(REDSTONE, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(GLASS, 10L))
                .emittable(REDSTONE)
                .pattern(CLOCK, top)
                .build();

        var line = captureReadout(grid, CLOCK);

        assertTrue(line.contains("noProvider=1/1"),
                () -> "GLASS 名下空集 ⇒ 这一档要自己记到 1 枚：" + line);
        assertTrue(line.contains("noProviderSamples=[minecraft:glass]"), () -> line);
        assertTrue(line.contains("emittable=1/1"),
                () -> "REDSTONE 被 canEmitFor 抢走身份 ⇒ 这一档要自己记到 1 枚：" + line);
        assertTrue(line.contains("emittableSamples=[minecraft:redstone]"), () -> line);
        assertTrue(line.contains("askedKeys=2"),
                () -> "只数问了提供者清单的两枚（CLOCK／GLASS），可发射那一枚没问到清单：" + line);
        assertTrue(line.contains("admit=1 host=NA"),
                () -> "admit＝两枚键的清单命中总数（CLOCK 1 张、GLASS 0 张）：" + line);
        assertTrue(line.contains("zeroCandidateKeys=1/1"),
                () -> "空集那一枚就是一个候选都没递出来：" + line);
        assertTrue(line.contains("zeroCandidateSamples=[minecraft:glass,pats=0,via=noProvider]"), () -> line);
        assertTrue(line.contains("ancestorOutput=0/0") && line.contains("emptySlot=0/0")
                        && line.contains("ancestorInput=0/0"),
                () -> "compile skip 三档一个都没碰：" + line);
    }

    /**
     * 档位 {@code ancestorInput}（compile skip 第三档）：{@code GLASS} 名下的样板产 {@code SNOWBALL}、
     * 却要吃祖先链上的 {@code CLOCK}，且这张样板自己不产 {@code CLOCK}（自喂豁免够不着）⇒ 挡在图外。
     */
    @Test
    void ancestorInputSkipHasItsOwnCounter() {
        var top = FakePattern.of(CLOCK, 1L, FakeInput.stack(GLASS, 1L));
        var backEdge = FakePattern.colored(-1, BACK_EDGE_DEF,
                List.of(new GenericStack(SNOWBALL, 1L)),
                FakeInput.stack(CLOCK, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(GLASS, 10L, SNOWBALL, 10L))
                .pattern(CLOCK, top)
                .pattern(GLASS, backEdge)
                .build();

        var line = captureReadout(grid, CLOCK);

        assertTrue(line.contains("ancestorInput=1/1"),
                () -> "第三档必须自己记到 1 张：" + line);
        assertTrue(line.contains("ancestorInputSamples=[minecraft:glass,minecraft:compass]"),
                () -> "样例点名被挡的那张样板：" + line);
        assertTrue(line.contains("zeroCandidateSamples=[minecraft:glass,pats=1,via=ancestorInput]"),
                () -> line);
        // 另三档各记各的，不许跟着沾光
        assertTrue(line.contains("ancestorOutput=0/0"), () -> line);
        assertTrue(line.contains("emptySlot=0/0"), () -> line);
        assertTrue(line.contains("noProvider=0/0"), () -> line);
        assertTrue(line.contains("emittable=0/0"), () -> line);
    }

    // ---- 捕获 ----

    private static String captureReadout(FakeGrid grid, AEItemKey target) {
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
            GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            return appender.lastLine("Graph compiler silent-skip readout:");
        } finally {
            rootLogger.removeAppender(appender.getName());
            rootLogger.setLevel(previousLevel);
            context.updateLoggers();
            appender.stop();
        }
    }

    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("compiler-silent-skip-capture", null,
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
