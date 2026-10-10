package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.core.localization.GuiText;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 的改写规则离线用例：溢出键<b>原地替换</b> AE2 自己那行（不是追加），
 * 没溢出的行连对象引用都不换；形状对不上时一行都不动。
 */
class WideAmountConfirmLinesTest {

    private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEKey LAVA = AEItemKey.of(Items.LAVA_BUCKET);
    private static final AmountNumberStyle ABBR = AmountNumberStyle.ABBREVIATED;
    private static final AmountNumberStyle SCI = AmountNumberStyle.SCIENTIFIC;
    private static final BigInteger OVERFLOW = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(7));

    private static WideLedgerSnapshot missingOf(AEKey key, BigInteger amount) {
        WideKeyCounter missing = new WideKeyCounter();
        missing.add(key, amount);
        return WideLedgerSnapshot.capture(missing, null, null);
    }

    /** 块里两行：存储行（能塞 long）+ 缺料行（AE2 写的是饱和 Long.MAX）。 */
    private static List<Component> blockOfTwoLines() {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.translatable(GuiText.FromStorage.getTranslationKey(), "1B"));
        lines.add(Component.translatable(GuiText.Missing.getTranslationKey(),
                Long.toString(Long.MAX_VALUE)));
        return lines;
    }

    @Test
    void overflowedLineIsReplacedInPlaceAndNeighboursKeepIdentity() {
        List<Component> lines = blockOfTwoLines();
        Component storedLine = lines.get(0);
        Component saturatedLine = lines.get(1);

        int hits = WideAmountConfirmLines.applyWideAmounts(lines, 0,
                1_000_000_000L, Long.MAX_VALUE, 0L,
                missingOf(LAVA, OVERFLOW), LAVA, AmountFormat.SLOT, ABBR);

        assertEquals(1, hits, "只有一个溢出键就该改一行");
        assertEquals(2, lines.size(), "原地替换，行数不许变多");
        assertSame(storedLine, lines.get(0), "没溢出的存储行必须还是同一个组件对象");
        assertNotSame(saturatedLine, lines.get(1), "缺料那一行应被换成新组件");
        TranslatableContents replaced = (TranslatableContents) lines.get(1).getContents();
        assertEquals(GuiText.Missing.getTranslationKey(), replaced.getKey(), "沿用 AE2 自己的标签");
        String shown = String.valueOf(replaced.getArgs()[0]);
        assertEquals(EnhancedCraftingStatusFormatting.formatAmount(LAVA, OVERFLOW, AmountFormat.SLOT, ABBR),
                shown, "替换成真值缩写");
        assertTrue(!shown.contains(Long.toString(Long.MAX_VALUE)),
                "不能再出现饱和的 Long.MAX_VALUE：" + shown);
    }

    @Test
    void keyWithoutOverflowLeavesEveryLineUntouched() {
        List<Component> lines = blockOfTwoLines();
        List<Component> before = new ArrayList<>(lines);

        int hits = WideAmountConfirmLines.applyWideAmounts(lines, 0,
                1_000_000_000L, Long.MAX_VALUE, 0L,
                missingOf(LAVA, OVERFLOW), GLASS, AmountFormat.SLOT, ABBR);

        assertEquals(0, hits, "条目键没有溢出账就不该动");
        assertEquals(before.size(), lines.size());
        for (int index = 0; index < lines.size(); index++) {
            assertSame(before.get(index), lines.get(index), "第 " + index + " 行对象都不该换");
        }
    }

    @Test
    void misshapedBlockTouchesNothing() {
        List<Component> lines = blockOfTwoLines();
        // 块长 3 行却只有 2 行可用：AE2 的行数与条目读数不一致 ⇒ 宁缺勿误伤
        assertEquals(0, WideAmountConfirmLines.applyWideAmounts(lines, 0,
                1L, 1L, 1L, missingOf(LAVA, OVERFLOW), LAVA, AmountFormat.FULL, ABBR));
        assertEquals(2, lines.size());
        // 起点越界同理
        assertEquals(0, WideAmountConfirmLines.applyWideAmounts(lines, 5,
                1_000_000_000L, Long.MAX_VALUE, 0L, missingOf(LAVA, OVERFLOW), LAVA, AmountFormat.FULL, ABBR));
        assertEquals(2, lines.size());
    }

    @Test
    void tooltipBlockStartsAfterTheItemNameLines() {
        // tooltip 场景：AE2 的键名前缀行在前，数量块在尾部——块起点由调用方按 size-块长算好
        List<Component> lines = new ArrayList<>(List.of(Component.literal("icon"), Component.literal("name")));
        lines.addAll(blockOfTwoLines());
        int shown = WideAmountConfirmLines.shownLineCount(1_000_000_000L, Long.MAX_VALUE, 0L);

        assertEquals(1, WideAmountConfirmLines.applyWideAmounts(lines, lines.size() - shown,
                1_000_000_000L, Long.MAX_VALUE, 0L, missingOf(LAVA, OVERFLOW), LAVA, AmountFormat.FULL, ABBR));
        assertEquals(4, lines.size(), "前缀两行 + 数量两行");
        TranslatableContents replaced = (TranslatableContents) lines.get(3).getContents();
        assertEquals(GuiText.Missing.getTranslationKey(), replaced.getKey());
        String full = String.valueOf(replaced.getArgs()[0]);
        assertTrue(full.replace(",", "").equals(OVERFLOW.toString()),
                "FULL 口径要给全量真值：" + full);
    }

    @Test
    void scientificStyleRestylesTheWholeCompactBlockWithoutALedger() {
        List<Component> lines = blockOfTwoLines();

        int rewritten = WideAmountConfirmLines.applyWideAmounts(lines, 0,
                1_000_000_000L, Long.MAX_VALUE, 0L, null, LAVA, AmountFormat.SLOT, SCI);

        assertEquals(2, rewritten, "指数法下两行都要换写法，同一列不留两种计数方式");
        assertEquals(2, lines.size(), "换写法不改行数");
        assertEquals("1.00e9", argOf(lines.get(0)));
        assertEquals("9.22e18", argOf(lines.get(1)));
    }

    @Test
    void scientificStyleNeverTouchesTheFullPrecisionTooltip() {
        List<Component> lines = blockOfTwoLines();
        List<Component> before = new ArrayList<>(lines);

        assertEquals(0, WideAmountConfirmLines.applyWideAmounts(lines, 0,
                1_000_000_000L, Long.MAX_VALUE, 0L, null, LAVA, AmountFormat.FULL, SCI));
        for (int index = 0; index < lines.size(); index++) {
            assertSame(before.get(index), lines.get(index), "tooltip 恒给逐位真值，对象都不该换");
        }
    }

    private static String argOf(Component line) {
        return String.valueOf(((TranslatableContents) line.getContents()).getArgs()[0]);
    }
}

