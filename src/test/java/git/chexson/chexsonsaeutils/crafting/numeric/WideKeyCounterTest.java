package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.KeyIndex;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;

class WideKeyCounterTest {

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    @Test
    void noOverflowKeepsSideTableEmpty() {
        var counter = new WideKeyCounter();
        var paper = key(Items.PAPER);
        counter.add(paper, 100L);
        counter.add(paper, Long.MAX_VALUE - 200L);
        counter.add(paper, 50L);
        assertFalse(counter.hasOverflow(), "无溢出时侧表必须为空");
        assertEquals(Long.MAX_VALUE - 50L, counter.getLong(paper));
        assertEquals(1, counter.size());
    }

    @Test
    void overflowKeyReadsExactAndNarrowSentinel() {
        var counter = new WideKeyCounter();
        var stone = key(Items.STONE);
        counter.add(stone, Long.MAX_VALUE);
        counter.add(stone, 1L);
        assertTrue(counter.hasOverflow());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), counter.getExact(stone));
        assertEquals(Long.MAX_VALUE, counter.getLong(stone), "窄视图显示哨兵值");
        assertEquals(1, counter.size(), "侧表键同时占主表键位");
    }

    @Test
    void overflowFallingBackToLongDomainClearsSideTable() {
        var counter = new WideKeyCounter();
        var stone = key(Items.STONE);
        counter.add(stone, Long.MAX_VALUE);
        counter.add(stone, 1L);
        counter.subtract(stone, 1L);
        assertFalse(counter.hasOverflow());
        assertEquals(Long.MAX_VALUE, counter.getExact(stone).longValueExact());
    }

    @Test
    void crossValidatesAgainstInfinityCellStoreAtPow63() {
        var store = new InfinityCellStore();
        var data = store.cell(UUID.randomUUID());
        var item = key(Items.IRON_INGOT);
        data.insert(item, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
        data.insert(item, 1L, appeng.api.config.Actionable.MODULATE);

        var counter = new WideKeyCounter();
        counter.add(item, Long.MAX_VALUE);
        counter.add(item, 1L);

        assertEquals(data.exactAmount(item), counter.getExact(item), "2^63 用例两套账本必须一致");
    }

    @Test
    void totalSummarizesWideAndFastKeys() {
        var counter = new WideKeyCounter();
        counter.add(key(Items.PAPER), 10L);
        counter.add(key(Items.STONE), Long.MAX_VALUE);
        counter.add(key(Items.STONE), 5L);
        var expected = PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(5L)))
                .add(PlanAmount.of(10L));
        assertEquals(expected, counter.total());
    }

    @Test
    void copyAndRestoreRoundTrip() {
        var counter = new WideKeyCounter();
        var paper = key(Items.PAPER);
        counter.add(paper, 42L);
        var snapshot = counter.copy();

        counter.add(paper, 1L);
        counter.add(key(Items.STONE), Long.MAX_VALUE);
        counter.add(key(Items.STONE), 1L);
        counter.restore(snapshot);

        assertEquals(42L, counter.getLong(paper));
        assertFalse(counter.containsKey(key(Items.STONE)));
        assertFalse(counter.hasOverflow());
    }

    @Test
    void removeAndClearDropBothTables() {
        var counter = new WideKeyCounter();
        var paper = key(Items.PAPER);
        counter.add(paper, Long.MAX_VALUE);
        counter.add(paper, 1L);
        counter.remove(paper);
        assertFalse(counter.containsKey(paper));
        assertFalse(counter.hasOverflow());
        counter.add(paper, 7L);
        assertEquals(7L, counter.getLong(paper));
        counter.clear();
        assertTrue(counter.isEmpty());
    }

    @Test
    void putAllMergesIncludingOverflow() {
        var source = new WideKeyCounter();
        source.add(key(Items.PAPER), 5L);
        source.add(key(Items.STONE), Long.MAX_VALUE);
        source.add(key(Items.STONE), 1L);

        var target = new WideKeyCounter();
        target.add(key(Items.PAPER), 2L);
        target.putAll(source);

        assertEquals(7L, target.getLong(key(Items.PAPER)));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), target.getExact(key(Items.STONE)));
    }

    @Test
    void ae2AmountsToKeyCounterSaturatesAndScansNegatives() {
        var counter = new WideKeyCounter();
        counter.add(key(Items.PAPER), 100L);
        counter.add(key(Items.STONE), Long.MAX_VALUE);
        counter.add(key(Items.STONE), 1L);
        counter.add(key(Items.IRON_INGOT), 3L);

        var out = new KeyCounter();
        boolean negative = git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toKeyCounter(counter, out);

        assertFalse(negative);
        assertEquals(Long.MAX_VALUE, out.get(key(Items.STONE)), "超界键饱和");
        assertEquals(100L, out.get(key(Items.PAPER)));
    }

    @Test
    void ae2AmountsDetectsNegativeEntries() {
        var counter = new WideKeyCounter();
        counter.add(key(Items.PAPER), -5L);

        var out = new KeyCounter();
        boolean negative = git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toKeyCounter(counter, out);

        assertTrue(negative);
        assertEquals(0, out.size(), "负值键绝不进 KeyCounter");
    }

    /**
     * 台账 #76 的收口判据（配对侧）：id 的反查<b>恒落回发它的那张键表里的同一个键</b>。
     *
     * <p>规划侧"七张账本共用一张键内化表"（{@code GraphPlanningState} 的字段注释）是这条前置
     * 的支点，{@code Ae2Amounts#toKeyCounter} 的 {@code forEachPresent} 快路与 {@code keys()}
     * 的迭代器都靠它。这里两本账<b>共用</b>一张表、各写各的量（PAPER 上分别 10 枚与 20 枚），
     * 断言的是<b>键身份</b>：两本各自 {@code forEachPresent} 读回的都必须是 PAPER，
     * 而量各是 10 与 20（合计 30 只可能来自"同一 id ⇒ 同一键、两本各记各的数"）。
     * 反查若串到别的键表上（无参构造自带的那本），读回的键就不是 PAPER。
     */
    @Test
    void idsFromOneKeyTableResolveToTheSameKeyOnEveryLedgerPairedWithIt() {
        var shared = new KeyIndex(16);
        var paper = key(Items.PAPER);
        var iron = key(Items.IRON_INGOT);
        int paperId = shared.idOf(paper);
        shared.idOf(iron);
        var first = new WideKeyCounter(shared);
        var second = new WideKeyCounter(shared);
        first.addById(paperId, 10L);
        second.addById(paperId, 20L);

        var firstSeen = new java.util.ArrayList<String>();
        first.forEachPresent((k, v) -> firstSeen.add(k + "=" + v));
        var secondSeen = new java.util.ArrayList<String>();
        second.forEachPresent((k, v) -> secondSeen.add(k + "=" + v));

        assertEquals(java.util.List.of(paper + "=10"), firstSeen,
                "共用一张表的两本账：id 反查必须落回同一个键，量各按各本读");
        assertEquals(java.util.List.of(paper + "=20"), secondSeen,
                "同一 id 在另一本账上读回的是同一个键的另一份数");
        assertEquals(30L, first.getLongById(paperId) + second.getLongById(paperId),
                "两本各记各的：合计 30 只能来自『同 id 同键』，不是把两本并成一本");
    }
}
