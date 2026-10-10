package git.chexson.chexsonsaeutils.crafting.solve;

import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;

import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;

import java.math.BigInteger;

import net.minecraft.world.item.Items;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;

/**
 * ⑤ 毛账数域：{@link PlanGrossAudit} 的 {@code need}／{@code supply} 两格过去各存一个向上饱和的
 * {@code long}，两张<b>各顶满天花板</b>的账先各自撞顶、再相减 ⇒ {@code need==supply==MAX}，
 * 读起来就是"配平"——{@code gross=[ok keys=N]} 与"真缺料"在<b>读数上同形</b>。
 * 这一行由 {@code GraphPlanningSession} 打进 {@code INFO}（stage=solve／stage=convert）供每一轮排障读，
 * 读数不许撒谎。本增量只改"读哪一本账"：份数读 {@link WideCraftCounter} 的精确值、
 * 计划对象上的宽域账读 {@link git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier}
 * 的现成读口，逐键累计与排水换到 {@link BigInteger}（{@code SlotAccount#drainSlotWide} 同一条顺位尺），
 * 判据本体一个字没动——毛账仍只作诊断分档、不参与放行。
 *
 * <p>夹具必须是<b>多张同键</b>：一张样板的乘算先饱和就永远跨不过 {@code long}
 * （本仓 memory 与既有夹具都明确过这一点），单张顶格与两张顶格在窄读数上是同一个数。
 *
 * <p>那一行同时自报用的是哪把尺：{@code view=wide}／{@code view=narrow}；缺口键的读数里
 * 真值越界就印 BigInteger 原文，读不到宽账而 {@code long} 恰顶格时印 {@code -1}
 * （本仓既有的顶格标记写法，同 {@code per100r} 的 {@code pwr=-1}），不再把 9223372036854775807 当量。
 */
final class PlanGrossAuditWideViewTest {

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
    @Test
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
    @Test
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
    @Test
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
    @Test
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
}
