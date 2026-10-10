package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 宽域格式化离线用例：委托路径观感零变、溢出续阶梯、超阶梯科学计数、FULL 全量真值，
 * 以及指数计数法（配置 {@code craftingAmountNumberStyle=scientific}）的写法。
 */
class EnhancedCraftingStatusFormattingWideTest {

    private static final AmountNumberStyle ABBR = AmountNumberStyle.ABBREVIATED;
    private static final AmountNumberStyle SCI = AmountNumberStyle.SCIENTIFIC;

    private static BigInteger pow10(int exponent) {
        return BigInteger.TEN.pow(exponent);
    }

    @Test
    void valuesThatFitLongDelegateToExistingLongPath() {
        for (long value : new long[]{0L, 1L, 999L, 1_000L, 1_500L, 999_999L, 5_000_000L,
                1_234_567_890L, Long.MAX_VALUE}) {
            BigInteger wide = BigInteger.valueOf(value);
            assertEquals(EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.SLOT, ABBR),
                    EnhancedCraftingStatusFormatting.formatAmount(wide, AmountFormat.SLOT, ABBR),
                    "SLOT 委托：value=" + value);
            assertEquals(EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, ABBR),
                    EnhancedCraftingStatusFormatting.formatAmount(wide, AmountFormat.FULL, ABBR),
                    "FULL 委托：value=" + value);
        }
    }

    @Test
    void overflowContinuesTheSuffixLadder() {
        // Long.MAX_VALUE 约 9.22e18；1.6e19 是实机 lava 缺料量级 → 16Qi。
        assertEquals("16Qi", EnhancedCraftingStatusFormatting.formatAmount(
                BigInteger.valueOf(16).multiply(pow10(18)), AmountFormat.SLOT, ABBR));
        assertEquals("1Sp", EnhancedCraftingStatusFormatting.formatAmount(pow10(24), AmountFormat.SLOT, ABBR));
        assertEquals("1Oc", EnhancedCraftingStatusFormatting.formatAmount(pow10(27), AmountFormat.SLOT, ABBR));
        assertEquals("1Sx", EnhancedCraftingStatusFormatting.formatAmount(pow10(21), AmountFormat.SLOT, ABBR));
        assertEquals("2De", EnhancedCraftingStatusFormatting.formatAmount(
                BigInteger.valueOf(2).multiply(pow10(33)), AmountFormat.SLOT, ABBR));
    }

    @Test
    void beyondLadderFallsBackToScientific() {
        assertEquals("1.00e36", EnhancedCraftingStatusFormatting.formatAmount(pow10(36), AmountFormat.SLOT, ABBR));
        assertEquals("1.62e40", EnhancedCraftingStatusFormatting.formatAmount(
                new BigInteger("162").multiply(pow10(38)), AmountFormat.SLOT, ABBR));
    }

    @Test
    void fullFormatAlwaysShowsEveryDigit() {
        BigInteger value = new BigInteger("12345678901234567890123");
        String full = EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, ABBR);
        // 全量真值：去掉分组分隔符后必须逐位等于原值，不缩写。
        assertEquals(value.toString(), full.replaceAll("[^0-9]", ""));
    }

    @Test
    void nullAndNegativeAreHandled() {
        assertEquals("", EnhancedCraftingStatusFormatting.formatAmount((BigInteger) null, AmountFormat.SLOT, ABBR));
        // 负宽域值（-1.6e19）超 long，SLOT 走阶梯带负号。
        assertEquals("-16Qi", EnhancedCraftingStatusFormatting.formatAmount(
                BigInteger.valueOf(-16).multiply(pow10(18)), AmountFormat.SLOT, ABBR));
    }

    @Test
    void scientificStyleRewritesEveryCompactAmount() {
        // 一千以上一律指数法，3 位有效数字；溢出量与能塞 long 的量同一写法。
        assertEquals("1.50e3", EnhancedCraftingStatusFormatting.formatAmount(1_500L, AmountFormat.SLOT, SCI));
        assertEquals("1.62e19", EnhancedCraftingStatusFormatting.formatAmount(
                new BigInteger("162").multiply(pow10(17)), AmountFormat.SLOT, SCI));
        assertEquals("-9.22e18", EnhancedCraftingStatusFormatting.formatAmount(
                BigInteger.valueOf(Long.MIN_VALUE), AmountFormat.SLOT, SCI));
    }

    @Test
    void scientificStyleKeepsSmallAndFullAmountsReadable() {
        // 一千以下写 5.00e2 反而难读：给纯整数。
        assertEquals("999", EnhancedCraftingStatusFormatting.formatAmount(999L, AmountFormat.SLOT, SCI));
        // FULL（tooltip）不受计数方式影响，仍是逐位真值。
        BigInteger value = new BigInteger("12345678901234567890123");
        assertEquals(EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, ABBR),
                EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, SCI));
    }

    @Test
    void configValueFallsBackToAbbreviated() {
        assertEquals(AmountNumberStyle.SCIENTIFIC, AmountNumberStyle.parse("scientific"));
        assertEquals(AmountNumberStyle.SCIENTIFIC, AmountNumberStyle.parse(" SCIENTIFIC "));
        assertEquals(ABBR, AmountNumberStyle.parse("abbreviated"));
        assertEquals(ABBR, AmountNumberStyle.parse("指数"));
        assertEquals(ABBR, AmountNumberStyle.parse(null));
    }

    @Test
    void compactColumnCarriesNoUnitWhileTheTooltipKeepsIt() {
        // 流体键 1000 mB = 1 B：紧凑列与 AE2 一样只给数，不挂单位占格宽。
        AEKey lava = AEFluidKey.of(Fluids.LAVA);
        BigInteger wide = new BigInteger("162").multiply(pow10(17));
        assertEquals("16.2Qa", EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.SLOT, ABBR));
        assertEquals("1.62e16", EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.SLOT, SCI));
        assertTrue(EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.FULL, ABBR)
                .endsWith(" B"), "tooltip 的 FULL 仍按 AE2 口径挂单位");
    }
}
