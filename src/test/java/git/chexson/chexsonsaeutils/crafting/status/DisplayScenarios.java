package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.core.localization.GuiText;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.ModConfigSpec;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合成预览与状态的行为场景；由类别测试统一调用。 */
public final class DisplayScenarios {
    private DisplayScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.status",
                () -> CraftingAmountTextCases.verify(temporaryRoot),
                () -> CraftingPreviewRowsCases.verify(temporaryRoot),
                () -> EnhancedCraftingStatusFormattingStyleWiringCases.verify(temporaryRoot),
                () -> EnhancedCraftingStatusFormattingWideCases.verify(temporaryRoot),
                () -> ExactMenuFrameCases.verify(temporaryRoot),
                () -> PlanGraphHighlightCases.verify(temporaryRoot),
                () -> PlanGraphMaterialsCases.verify(temporaryRoot),
                () -> PlanGraphCases.verify(temporaryRoot),
                () -> PlanSummaryAmountsCases.verify(temporaryRoot),
                () -> PlanSummaryBytesCases.verify(temporaryRoot),
                () -> WideAmountConfirmLinesCases.verify(temporaryRoot)
        );
    }

    /** 执行本包的按需度量，保留各场景名称与状态隔离。 */
    public static void measure(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.status",
                () -> CraftingAmountTextCases.measure(temporaryRoot)
        );
    }

    /** CraftingAmountTextCases 的独立场景夹具，按类别统一执行。 */
    public static final class CraftingAmountTextCases {
        private static final AmountNumberStyle ABBR = AmountNumberStyle.ABBREVIATED;

        void stationaryPreviewAndMonitorConvertLargeIntegersOnlyOnce() {
            CraftingAmountText cache = new CraftingAmountTextImpl();
            var amount = BigInteger.valueOf(9).pow(4000);
            AEKey key = AEItemKey.of(Items.PAPER);
            String slot = cache.format(key, amount, AmountFormat.SLOT, ABBR);
            String full = cache.format(key, amount, AmountFormat.FULL, ABBR);
            for (int frame = 0; frame < 120; frame++) {
                assertSame(slot, cache.format(key, amount, AmountFormat.SLOT, ABBR));
                assertSame(full, cache.format(key, amount, AmountFormat.FULL, ABBR));
            }
            assertEquals(slot, cache.format(key, new BigInteger(amount.toString()), AmountFormat.SLOT, ABBR),
                    "服务器重发相同数量时仍可复用");
            assertEquals(EnhancedCraftingStatusFormatting.formatUncached(key, amount.add(BigInteger.ONE), AmountFormat.FULL, ABBR),
                    cache.format(key, amount.add(BigInteger.ONE), AmountFormat.FULL, ABBR));
        }

        void cachePreservesUnitsSignsStylesAndExactFullAmounts() {
            CraftingAmountText cache = new CraftingAmountTextImpl();
            for (AEKey key : new AEKey[]{null, AEItemKey.of(Items.PAPER), AEFluidKey.of(Fluids.LAVA)}) {
                for (var amount : new BigInteger[]{BigInteger.ZERO, BigInteger.valueOf(1500),
                        BigInteger.TEN.pow(40).add(BigInteger.valueOf(123)), BigInteger.TEN.pow(4000).negate()}) {
                    for (var style : AmountNumberStyle.values()) {
                        for (var format : new AmountFormat[]{AmountFormat.SLOT, AmountFormat.FULL}) {
                            String expected = key == null
                                    ? EnhancedCraftingStatusFormatting.formatUncached(amount, format, style)
                                    : EnhancedCraftingStatusFormatting.formatUncached(key, amount, format, style);
                            assertEquals(expected, cache.format(key, amount, format, style));
                        }
                    }
                }
            }
        }

        void localeChangesInvalidateGroupedNumbers() {
            Locale previous = Locale.getDefault();
            try {
                CraftingAmountText cache = new CraftingAmountTextImpl();
                var amount = BigInteger.TEN.pow(40).add(BigInteger.valueOf(123));
                Locale.setDefault(Locale.US);
                String english = cache.format(null, amount, AmountFormat.FULL, ABBR);
                Locale.setDefault(Locale.GERMANY);
                String german = cache.format(null, amount, AmountFormat.FULL, ABBR);
                assertNotEquals(english, german);
                assertEquals(EnhancedCraftingStatusFormatting.formatUncached(amount, AmountFormat.FULL, ABBR), german);
            } finally { Locale.setDefault(previous); }
        }

        void scrollingAndChangingCountsCannotGrowCacheWithoutBound() {
            CraftingAmountText cache = new CraftingAmountTextImpl();
            var amount = BigInteger.TEN.pow(4000);
            String initial = cache.format(null, amount, AmountFormat.SLOT, ABBR);
            for (int i = 1; i <= 256; i++) cache.format(null, amount.add(BigInteger.valueOf(i)), AmountFormat.SLOT, ABBR);
            assertNotSame(initial, cache.format(null, amount, AmountFormat.SLOT, ABBR), "超过条数上限应淘汰最旧条目");
            cache = new CraftingAmountTextImpl();
            initial = cache.format(null, amount, AmountFormat.FULL, ABBR);
            for (int i = 1; i <= 60; i++) cache.format(null, amount.add(BigInteger.valueOf(i)), AmountFormat.FULL, ABBR);
            assertNotSame(initial, cache.format(null, amount, AmountFormat.FULL, ABBR), "完整文本达到字符预算时也应淘汰");
        }

        void scientificDigitsKeepTruncationIncludingSmallAndNegativeValues() {
            assertEquals("0.00e0", EnhancedCraftingStatusFormatting.formatScientific(BigInteger.ZERO));
            assertEquals("1.20e1", EnhancedCraftingStatusFormatting.formatScientific(BigInteger.valueOf(12)));
            assertEquals("-9.99e3", EnhancedCraftingStatusFormatting.formatScientific(BigInteger.valueOf(-9999)));
            assertEquals("9.99e4000", EnhancedCraftingStatusFormatting.formatScientific(BigInteger.TEN.pow(4001).subtract(BigInteger.ONE)));
        }

        void measureRepeatedVisiblePreviewAndMonitorAmounts() {
            AEKey key = AEItemKey.of(Items.PAPER);
            var amounts = new BigInteger[15];
            for (int i = 0; i < amounts.length; i++) amounts[i] = BigInteger.valueOf(9).pow(4000 - i);
            CraftingAmountText cache = new CraftingAmountTextImpl();
            long checksum = 0;
            for (var value : amounts) {
                checksum += cache.format(key, value, AmountFormat.SLOT, ABBR).length();
                checksum += EnhancedCraftingStatusFormatting.formatUncached(key, value, AmountFormat.SLOT, ABBR).length();
            }
            long begin = System.nanoTime();
            for (int frame = 0; frame < 60; frame++) for (var value : amounts)
                checksum += EnhancedCraftingStatusFormatting.formatUncached(key, value, AmountFormat.SLOT, ABBR).length();
            long uncached = System.nanoTime() - begin;
            begin = System.nanoTime();
            for (int frame = 0; frame < 60; frame++) for (var value : amounts)
                checksum += cache.format(key, value, AmountFormat.SLOT, ABBR).length();
            long cached = System.nanoTime() - begin;
            System.out.printf(Locale.ROOT, "PREVIEW_TEXT 15 visible values, 60 frames: uncached=%.3fms cached=%.3fms checksum=%d%n",
                    uncached / 1e6, cached / 1e6, checksum);
            assertTrue(checksum > 0);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.stationaryPreviewAndMonitorConvertLargeIntegersOnlyOnce", () -> new CraftingAmountTextCases().stationaryPreviewAndMonitorConvertLargeIntegersOnlyOnce()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.cachePreservesUnitsSignsStylesAndExactFullAmounts", () -> new CraftingAmountTextCases().cachePreservesUnitsSignsStylesAndExactFullAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.localeChangesInvalidateGroupedNumbers", () -> new CraftingAmountTextCases().localeChangesInvalidateGroupedNumbers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.scrollingAndChangingCountsCannotGrowCacheWithoutBound", () -> new CraftingAmountTextCases().scrollingAndChangingCountsCannotGrowCacheWithoutBound()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.scientificDigitsKeepTruncationIncludingSmallAndNegativeValues", () -> new CraftingAmountTextCases().scientificDigitsKeepTruncationIncludingSmallAndNegativeValues())
            );
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingAmountTextCases.measureRepeatedVisiblePreviewAndMonitorAmounts", () -> new CraftingAmountTextCases().measureRepeatedVisiblePreviewAndMonitorAmounts())
            );
        }
    }

    /** CraftingPreviewRowsCases 的独立场景夹具，按类别统一执行。 */
    public static final class CraftingPreviewRowsCases {
        private static final AEItemKey KEY = AEItemKey.of(Items.PAPER);
        private static final AmountNumberStyle ABBR = AmountNumberStyle.ABBREVIATED;

        void repeatedFramesReuseComponentsWithoutSharingTheMutableList() {
            CraftingPreviewRows cache = new CraftingPreviewRowsImpl();
            var entry = new CraftingPlanSummaryEntry(KEY, Long.MAX_VALUE, 123, 2);
            var snapshot = missing(BigInteger.TEN.pow(4800));
            var first = cache.lines(entry, snapshot, true, AmountFormat.SLOT, ABBR, false);
            for (int frame = 0; frame < 120; frame++) {
                var next = cache.lines(entry, snapshot, true, AmountFormat.SLOT, ABBR, false);
                assertNotSame(first, next);
                assertSame(first.get(1), next.get(1));
                next.clear();
            }
            assertEquals(3, cache.lines(entry, snapshot, true, AmountFormat.SLOT, ABBR, false).size());
        }

        void updatedSnapshotFormatAndStyleCannotReuseStaleNumbers() {
            CraftingPreviewRows cache = new CraftingPreviewRowsImpl();
            var entry = new CraftingPlanSummaryEntry(KEY, Long.MAX_VALUE, 1500, 0);
            var exact = BigInteger.TEN.pow(4800).add(BigInteger.valueOf(321));
            var snapshot = missing(exact);
            var slot = cache.lines(entry, snapshot, true, AmountFormat.SLOT, ABBR, false);
            assertEquals("1.00e4800", argument(slot.get(1)));
            var full = cache.lines(entry, snapshot, true, AmountFormat.FULL, ABBR, false);
            assertEquals(EnhancedCraftingStatusFormatting.formatAmount(KEY, exact, AmountFormat.FULL, ABBR), argument(full.get(1)));
            var scientific = cache.lines(entry, snapshot, true, AmountFormat.SLOT, AmountNumberStyle.SCIENTIFIC, false);
            assertEquals("1.50e3", argument(scientific.getFirst()));
            var changed = cache.lines(entry, missing(exact.multiply(BigInteger.TWO)), true, AmountFormat.SLOT, ABBR, false);
            assertEquals("2.00e4800", argument(changed.get(1)));
            var nativeLines = cache.lines(entry, null, false, AmountFormat.SLOT, ABBR, false);
            assertEquals(KEY.formatAmount(Long.MAX_VALUE, AmountFormat.SLOT), argument(nativeLines.get(1)));
        }

        void patternCountsAndFeatureSwitchInvalidateOnlyTheAppropriateRows() {
            CraftingPreviewRows cache = new CraftingPreviewRowsImpl();
            var entry = new CraftingPlanSummaryEntry(KEY, 0, 0, 1);
            var enhanced = (EnhancedCraftingPlanSummaryEntry) (Object) entry;
            enhanced.chexsonsaeutils$setPatternTimes(List.of(BigInteger.TEN.pow(1000), BigInteger.TEN, BigInteger.ONE));
            var lines = cache.lines(entry, null, false, AmountFormat.SLOT, ABBR, true);
            assertEquals(2, lines.size());
            assertTrue(argument(lines.getLast()).contains("(+1)"));
            assertEquals(1, cache.lines(entry, null, false, AmountFormat.SLOT, ABBR, false).size());
            enhanced.chexsonsaeutils$setPatternTimes(List.of(BigInteger.valueOf(7)));
            assertEquals("7", argument(cache.lines(entry, null, false, AmountFormat.SLOT, ABBR, true).getLast()));
        }

        void scrollingEvictsOldRows() {
            CraftingPreviewRows cache = new CraftingPreviewRowsImpl();
            var first = new CraftingPlanSummaryEntry(KEY, 0, 1, 0);
            var component = cache.lines(first, null, false, AmountFormat.SLOT, ABBR, false).getFirst();
            for (int index = 0; index < 64; index++) cache.lines(new CraftingPlanSummaryEntry(KEY, 0, index + 2, 0),
                    null, false, AmountFormat.SLOT, ABBR, false);
            assertNotSame(component, cache.lines(first, null, false, AmountFormat.SLOT, ABBR, false).getFirst());
        }

        private static WideLedgerSnapshot missing(BigInteger value) {
            var counter = new WideKeyCounter(); counter.add(KEY, value);
            return WideLedgerSnapshot.capture(counter, null, null);
        }
        private static String argument(Component line) {
            return ((TranslatableContents) line.getContents()).getArgs()[0].toString();
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("CraftingPreviewRowsCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingPreviewRowsCases.repeatedFramesReuseComponentsWithoutSharingTheMutableList", () -> new CraftingPreviewRowsCases().repeatedFramesReuseComponentsWithoutSharingTheMutableList()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingPreviewRowsCases.updatedSnapshotFormatAndStyleCannotReuseStaleNumbers", () -> new CraftingPreviewRowsCases().updatedSnapshotFormatAndStyleCannotReuseStaleNumbers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingPreviewRowsCases.patternCountsAndFeatureSwitchInvalidateOnlyTheAppropriateRows", () -> new CraftingPreviewRowsCases().patternCountsAndFeatureSwitchInvalidateOnlyTheAppropriateRows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("CraftingPreviewRowsCases.scrollingEvictsOldRows", () -> new CraftingPreviewRowsCases().scrollingEvictsOldRows())
            );
        }
    }

    /** EnhancedCraftingStatusFormattingStyleWiringCases 的独立场景夹具，按类别统一执行。 */
    public static final class EnhancedCraftingStatusFormattingStyleWiringCases {
        // 被驱动的那枚键：config 侧的 public 常量，不需要反射。
        private static final ModConfigSpec.ConfigValue<String> STYLE_KEY =
                ChexsonsaeutilsCompatibilityConfig.CRAFTING_AMOUNT_NUMBER_STYLE;

        // 门名标签：红字自带归因（测试日志不印用例名）。
        private static final String TAG = "STYLEWIRING";

        void restoreConfiguredDefault() {
            // 只把这枚键 set 回默认串；不摘 loaded 轨、不碰别的全局。
            STYLE_KEY.set(STYLE_KEY.getDefault());
        }

        void currentStyleFollowsTheLoadedConfigString() {
            // ①前置：本臂量的是 loaded 轨；轨不在就红在这里，而不是红在后面三档（不许假绿）。
            assertTrue(ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded(),
                    TAG + "|gate=SW-PRE|SPEC 未加载 ⇒ loaded 轨不存在，四档一根都没被求值（缺失前提，不是行为差异）");

            // ②正向档：串 scientific 接到 SCIENTIFIC。
            STYLE_KEY.set("scientific");
            assertEquals(AmountNumberStyle.SCIENTIFIC, EnhancedCraftingStatusFormatting.currentStyle(),
                    TAG + "|gate=SW-SCIENTIFIC|配置串 scientific 没接到 SCIENTIFIC ⇒ 读点被摘或接线断（序3 那笔即此形状）");

            // ③默认档：串 abbreviated 接到 ABBREVIATED。
            STYLE_KEY.set("abbreviated");
            assertEquals(AmountNumberStyle.ABBREVIATED, EnhancedCraftingStatusFormatting.currentStyle(),
                    TAG + "|gate=SW-ABBREVIATED|配置串 abbreviated 没接到 ABBREVIATED");

            // ④极性档：认不出的串回缩写（配置写错时不该让界面空），单开一行只判这一件事。
            STYLE_KEY.set("bogus");
            assertEquals(AmountNumberStyle.ABBREVIATED, EnhancedCraftingStatusFormatting.currentStyle(),
                    TAG + "|gate=SW-FALLBACK|无法识别的串没回 ABBREVIATED ⇒ 兜底极性被翻");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingStyleWiringCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingStyleWiringCases.currentStyleFollowsTheLoadedConfigString", () -> {
                            var scenario = new EnhancedCraftingStatusFormattingStyleWiringCases();
                            try {
                                scenario.currentStyleFollowsTheLoadedConfigString();
                            } finally {
                                scenario.restoreConfiguredDefault();
                            }
                        })
            );
        }
    }

    /** EnhancedCraftingStatusFormattingWideCases 的独立场景夹具，按类别统一执行。 */
    public static final class EnhancedCraftingStatusFormattingWideCases {
        private static final AmountNumberStyle ABBR = AmountNumberStyle.ABBREVIATED;
        private static final AmountNumberStyle SCI = AmountNumberStyle.SCIENTIFIC;

        private static BigInteger pow10(int exponent) {
            return BigInteger.TEN.pow(exponent);
        }

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

        void beyondLadderFallsBackToScientific() {
            assertEquals("1.00e36", EnhancedCraftingStatusFormatting.formatAmount(pow10(36), AmountFormat.SLOT, ABBR));
            assertEquals("1.62e40", EnhancedCraftingStatusFormatting.formatAmount(
                    new BigInteger("162").multiply(pow10(38)), AmountFormat.SLOT, ABBR));
        }

        void fullFormatAlwaysShowsEveryDigit() {
            BigInteger value = new BigInteger("12345678901234567890123");
            String full = EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, ABBR);
            // 全量真值：去掉分组分隔符后必须逐位等于原值，不缩写。
            assertEquals(value.toString(), full.replaceAll("[^0-9]", ""));
        }

        void nullAndNegativeAreHandled() {
            assertEquals("", EnhancedCraftingStatusFormatting.formatAmount((BigInteger) null, AmountFormat.SLOT, ABBR));
            // 负宽域值（-1.6e19）超 long，SLOT 走阶梯带负号。
            assertEquals("-16Qi", EnhancedCraftingStatusFormatting.formatAmount(
                    BigInteger.valueOf(-16).multiply(pow10(18)), AmountFormat.SLOT, ABBR));
        }

        void scientificStyleRewritesEveryCompactAmount() {
            // 一千以上一律指数法，3 位有效数字；溢出量与能塞 long 的量同一写法。
            assertEquals("1.50e3", EnhancedCraftingStatusFormatting.formatAmount(1_500L, AmountFormat.SLOT, SCI));
            assertEquals("1.62e19", EnhancedCraftingStatusFormatting.formatAmount(
                    new BigInteger("162").multiply(pow10(17)), AmountFormat.SLOT, SCI));
            assertEquals("-9.22e18", EnhancedCraftingStatusFormatting.formatAmount(
                    BigInteger.valueOf(Long.MIN_VALUE), AmountFormat.SLOT, SCI));
        }

        void scientificStyleKeepsSmallAndFullAmountsReadable() {
            // 一千以下写 5.00e2 反而难读：给纯整数。
            assertEquals("999", EnhancedCraftingStatusFormatting.formatAmount(999L, AmountFormat.SLOT, SCI));
            // FULL（tooltip）不受计数方式影响，仍是逐位真值。
            BigInteger value = new BigInteger("12345678901234567890123");
            assertEquals(EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, ABBR),
                    EnhancedCraftingStatusFormatting.formatAmount(value, AmountFormat.FULL, SCI));
        }

        void configValueFallsBackToAbbreviated() {
            assertEquals(AmountNumberStyle.SCIENTIFIC, AmountNumberStyle.parse("scientific"));
            assertEquals(AmountNumberStyle.SCIENTIFIC, AmountNumberStyle.parse(" SCIENTIFIC "));
            assertEquals(ABBR, AmountNumberStyle.parse("abbreviated"));
            assertEquals(ABBR, AmountNumberStyle.parse("指数"));
            assertEquals(ABBR, AmountNumberStyle.parse(null));
        }

        void compactColumnCarriesNoUnitWhileTheTooltipKeepsIt() {
            // 流体键 1000 mB = 1 B：紧凑列与 AE2 一样只给数，不挂单位占格宽。
            AEKey lava = AEFluidKey.of(Fluids.LAVA);
            BigInteger wide = new BigInteger("162").multiply(pow10(17));
            assertEquals("16.2Qa", EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.SLOT, ABBR));
            assertEquals("1.62e16", EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.SLOT, SCI));
            assertTrue(EnhancedCraftingStatusFormatting.formatAmount(lava, wide, AmountFormat.FULL, ABBR)
                    .endsWith(" B"), "tooltip 的 FULL 仍按 AE2 口径挂单位");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.valuesThatFitLongDelegateToExistingLongPath", () -> new EnhancedCraftingStatusFormattingWideCases().valuesThatFitLongDelegateToExistingLongPath()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.overflowContinuesTheSuffixLadder", () -> new EnhancedCraftingStatusFormattingWideCases().overflowContinuesTheSuffixLadder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.beyondLadderFallsBackToScientific", () -> new EnhancedCraftingStatusFormattingWideCases().beyondLadderFallsBackToScientific()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.fullFormatAlwaysShowsEveryDigit", () -> new EnhancedCraftingStatusFormattingWideCases().fullFormatAlwaysShowsEveryDigit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.nullAndNegativeAreHandled", () -> new EnhancedCraftingStatusFormattingWideCases().nullAndNegativeAreHandled()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.scientificStyleRewritesEveryCompactAmount", () -> new EnhancedCraftingStatusFormattingWideCases().scientificStyleRewritesEveryCompactAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.scientificStyleKeepsSmallAndFullAmountsReadable", () -> new EnhancedCraftingStatusFormattingWideCases().scientificStyleKeepsSmallAndFullAmountsReadable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.configValueFallsBackToAbbreviated", () -> new EnhancedCraftingStatusFormattingWideCases().configValueFallsBackToAbbreviated()),
                () -> org.junit.jupiter.api.Assertions.assertAll("EnhancedCraftingStatusFormattingWideCases.compactColumnCarriesNoUnitWhileTheTooltipKeepsIt", () -> new EnhancedCraftingStatusFormattingWideCases().compactColumnCarriesNoUnitWhileTheTooltipKeepsIt())
            );
        }
    }

    /** ExactMenuFrameCases 的独立场景夹具，按类别统一执行。 */
    public static final class ExactMenuFrameCases {
        private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT), GOLD = AEItemKey.of(Items.GOLD_INGOT);

        void replaying4000ImmutableEntriesReusesTheSameMapEveryFrame() {
            ExactMenuFrame<BigInteger> projector = new ExactMenuFrameImpl<>();
            var mutable = new HashMap<AEKey, BigInteger>();
            var amount = BigInteger.TEN.pow(1000);
            for (int level = 0; level < 4000; level++) mutable.put(FakePlanGrid.dyedKey(Items.PAPER, level), amount);
            var snapshot = Map.copyOf(mutable);
            Map<AEKey, BigInteger> current = Map.of();
            for (int frame = 0; frame < 120; frame++) {
                current = projector.apply(current, snapshot, Set.of(), true);
                assertSame(snapshot, current, "同一份完整精确帧不应在每帧复制 4000 个条目");
            }
            assertEquals(4000, current.size());
        }

        void fullAndIncrementalFramesPreserveReplacementAndRemovalSemantics() {
            ExactMenuFrame<BigInteger> projector = new ExactMenuFrameImpl<>();
            var prior = Map.of(IRON, BigInteger.TEN, GOLD, BigInteger.ONE);
            assertEquals(Map.of(IRON, BigInteger.TWO), projector.apply(prior, Map.of(IRON, BigInteger.TWO), Set.of(GOLD), false));
            assertEquals(Map.of(GOLD, BigInteger.TEN), projector.apply(prior, Map.of(GOLD, BigInteger.TEN), Set.of(), true));
            assertTrue(projector.apply(prior, Map.of(), Set.of(), true).isEmpty());
            assertEquals(prior, projector.apply(prior, Map.of(), Set.of(), false));
            // 与 MapDelta.apply 一致：先删除后更新，同一键的更新最终生效。
            assertEquals(Map.of(IRON, BigInteger.TWO), projector.apply(prior, Map.of(IRON, BigInteger.TWO), Set.of(IRON, GOLD), true));
        }

        void mutableNetworkMapsCannotAlterAnAlreadyDisplayedFrame() {
            ExactMenuFrame<BigInteger> projector = new ExactMenuFrameImpl<>();
            var incoming = new HashMap<>(Map.of(IRON, BigInteger.TEN));
            var displayed = projector.apply(Map.of(), incoming, Set.of(), true);
            incoming.clear();
            assertEquals(Map.of(IRON, BigInteger.TEN), displayed);
            assertThrows(UnsupportedOperationException.class, () -> displayed.clear());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ExactMenuFrameCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactMenuFrameCases.replaying4000ImmutableEntriesReusesTheSameMapEveryFrame", () -> new ExactMenuFrameCases().replaying4000ImmutableEntriesReusesTheSameMapEveryFrame()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactMenuFrameCases.fullAndIncrementalFramesPreserveReplacementAndRemovalSemantics", () -> new ExactMenuFrameCases().fullAndIncrementalFramesPreserveReplacementAndRemovalSemantics()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactMenuFrameCases.mutableNetworkMapsCannotAlterAnAlreadyDisplayedFrame", () -> new ExactMenuFrameCases().mutableNetworkMapsCannotAlterAnAlreadyDisplayedFrame())
            );
        }
    }

    /** PlanGraphHighlightCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphHighlightCases {
        private static final PlanGraph.Node NODE = new PlanGraph.Node(AEItemKey.of(Items.IRON_INGOT), false,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
        private static PlanGraph.Edge edge(int from, int to) { return new PlanGraph.Edge(from, to, BigInteger.ONE, false, false); }
        void followsAllAncestorsAndDescendantsWithoutFollowingUnrelatedSideBranches() {
            // 0→1→2→3→4 为主链；5→1 是另一种基础输入。0→6→4 绕过选中物料 2，不能误高亮。
            var graph = new PlanGraph(Collections.nCopies(8, NODE), List.of(edge(0, 1), edge(1, 2), edge(2, 3),
                    edge(3, 4), edge(5, 1), edge(0, 6), edge(6, 4), edge(7, 6)));
            var direct = PlanGraphHighlight.create(graph, 2, false);
            assertEquals(Set.of(1, 2), direct.edges()); assertEquals(Set.of(1, 2, 3), direct.nodes());
            var chain = PlanGraphHighlight.create(graph, 2, true);
            assertEquals(Set.of(0, 1, 2, 3, 4), chain.edges()); assertEquals(Set.of(0, 1, 2, 3, 4, 5), chain.nodes());
            assertTrue(PlanGraphHighlight.create(graph, -1, true).edges().isEmpty());
        }
        void longChainWithCycleAndParallelAlternativeEdgesTerminatesWithoutLosingRelations() {
            int size = 10000; var edges = new ArrayList<PlanGraph.Edge>();
            for (int i = 0; i < size - 1; i++) edges.add(edge(i, i + 1));
            edges.add(new PlanGraph.Edge(6000, 5000, BigInteger.TEN, false, false, true));
            edges.add(new PlanGraph.Edge(4999, 5000, BigInteger.TWO, false, true));
            var graph = new PlanGraph(Collections.nCopies(size, NODE), edges);
            var chain = PlanGraphHighlight.create(graph, 5000, true);
            assertEquals(size, chain.nodes().size()); assertEquals(edges.size(), chain.edges().size());
        }
        void overviewConnectsCentersAndSharesTracksForSameColumnPair() {
            var graph = new PlanGraph(Collections.nCopies(3, NODE), List.of(edge(0, 1), edge(0, 2), edge(2, 2)));
            var layout = PlanGraphLayout.create(graph); var routes = PlanGraphRoutes.createOverview(graph, layout);
            for (int i = 0; i < routes.size(); i++) {
                var e = graph.edges().get(i); var points = routes.get(i).points();
                var from = layout.positions().get(e.from()); var to = layout.positions().get(e.to());
                assertEquals(new PlanGraphRoutes.Point(from.x() + 15, from.y() + 15), points.getFirst());
                assertEquals(new PlanGraphRoutes.Point(to.x() + 15, to.y() + 15), points.getLast());
                for (int j = 1; j < points.size(); j++) assertTrue(points.get(j - 1).x() == points.get(j).x()
                        || points.get(j - 1).y() == points.get(j).y());
            }
            assertEquals(routes.get(0).points().get(1).y(), routes.get(1).points().get(1).y());
            assertTrue(routes.get(2).maxY() > layout.positions().get(2).y() + PlanGraphLayout.NODE_SIZE);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphHighlightCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphHighlightCases.followsAllAncestorsAndDescendantsWithoutFollowingUnrelatedSideBranches", () -> new PlanGraphHighlightCases().followsAllAncestorsAndDescendantsWithoutFollowingUnrelatedSideBranches()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphHighlightCases.longChainWithCycleAndParallelAlternativeEdgesTerminatesWithoutLosingRelations", () -> new PlanGraphHighlightCases().longChainWithCycleAndParallelAlternativeEdgesTerminatesWithoutLosingRelations()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphHighlightCases.overviewConnectsCentersAndSharesTracksForSameColumnPair", () -> new PlanGraphHighlightCases().overviewConnectsCentersAndSharesTracksForSameColumnPair())
            );
        }
    }

    /** PlanGraphMaterialsCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphMaterialsCases {
        private static final BigInteger WIDE = BigInteger.TEN.pow(24);
        private static PlanGraph.Node material(net.minecraft.world.item.Item item) {
            return new PlanGraph.Node(AEItemKey.of(item), false, WIDE, BigInteger.ZERO, BigInteger.ZERO);
        }
        private static PlanGraph.Node recipe() {
            return new PlanGraph.Node(AEItemKey.of(Items.PAPER), true, WIDE, BigInteger.ZERO, BigInteger.ZERO);
        }
        void hidesRecipesButPreservesExactAlternativesByproductsAndReturns() {
            var source = new PlanGraph(List.of(material(Items.GOLD_INGOT), recipe(), material(Items.IRON_INGOT), material(Items.BUCKET)),
                    List.of(new PlanGraph.Edge(2, 1, WIDE.multiply(BigInteger.TWO), true, true),
                            new PlanGraph.Edge(1, 0, WIDE, false, false),
                            new PlanGraph.Edge(1, 3, WIDE.multiply(BigInteger.valueOf(3)), false, true, true)));
            var projected = PlanGraphMaterials.create(source); var graph = projected.graph();
            assertEquals(3, graph.nodes().size()); assertTrue(graph.nodes().stream().noneMatch(PlanGraph.Node::pattern));
            assertSame(source.nodes().getFirst(), graph.nodes().getFirst());
            assertEquals(List.of(0, 2), graph.edges().stream().map(PlanGraph.Edge::to).toList());
            assertTrue(graph.edges().stream().allMatch(e -> e.from() == 1 && e.alternative()));
            assertTrue(graph.edges().getLast().remainder());
            assertEquals(WIDE.multiply(BigInteger.TWO), projected.relations().getFirst().input());
            assertEquals(WIDE.multiply(BigInteger.valueOf(3)), projected.relations().getLast().output());
            assertEquals(WIDE, projected.relations().getFirst().recipe().amount());
            assertEquals(List.of(source.nodes().get(1)), projected.recipesFor(0));
            assertEquals(4, source.nodes().size()); assertEquals(3, source.edges().size());
        }
        void preservesSharedProducersCyclesAndRecipesWithoutInputs() {
            var source = new PlanGraph(List.of(material(Items.GOLD_INGOT), material(Items.IRON_INGOT), recipe(), recipe(), recipe()),
                    List.of(new PlanGraph.Edge(1, 2, WIDE, true, false), new PlanGraph.Edge(2, 0, WIDE, false, false),
                            new PlanGraph.Edge(0, 3, WIDE, true, false), new PlanGraph.Edge(3, 1, WIDE, false, false),
                            new PlanGraph.Edge(3, 0, WIDE, false, false, true), new PlanGraph.Edge(4, 0, WIDE, false, false)));
            var projected = PlanGraphMaterials.create(source);
            assertEquals(3, projected.graph().edges().size());
            assertEquals(2, PlanGraphLayout.create(projected.graph()).positions().stream().filter(PlanGraphLayout.Position::cycle).count());
            assertTrue(projected.graph().edges().stream().anyMatch(e -> e.from() == e.to() && e.remainder()));
            assertEquals(3, projected.recipesFor(0).size());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphMaterialsCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphMaterialsCases.hidesRecipesButPreservesExactAlternativesByproductsAndReturns", () -> new PlanGraphMaterialsCases().hidesRecipesButPreservesExactAlternativesByproductsAndReturns()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphMaterialsCases.preservesSharedProducersCyclesAndRecipesWithoutInputs", () -> new PlanGraphMaterialsCases().preservesSharedProducersCyclesAndRecipesWithoutInputs())
            );
        }
    }

    /** PlanGraphCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphCases {
        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT), GOLD = AEItemKey.of(Items.GOLD_INGOT), STICK = AEItemKey.of(Items.STICK);
        private static final BigInteger WIDE = BigInteger.TEN.pow(24);
        private record Input(GenericStack[] getPossibleInputs, long getMultiplier, AEKey remainder) implements IPatternDetails.IInput {
            Input(GenericStack[] possible, long multiplier) { this(possible, multiplier, null); }
            @Override public boolean isValid(AEKey key, Level level) { return true; }
            @Override public AEKey getRemainingKey(AEKey key) { return remainder; }
        }
        private record Pattern(AEItemKey getDefinition, IPatternDetails.IInput[] getInputs, List<GenericStack> getOutputs) implements IPatternDetails {}
        void capturesExactExecutionsByproductsAndAlternativeUpperBounds() {
            var pattern = new Pattern(STICK, new IPatternDetails.IInput[]{new Input(new GenericStack[]{new GenericStack(IRON, 2), new GenericStack(GOLD, 3)}, 4)},
                    List.of(new GenericStack(STICK, 5), new GenericStack(GOLD, 1)));
            var missing = new KeyCounter(); missing.add(IRON, 9);
            var crafts = new WideCraftCounter(); crafts.add(pattern, PlanAmount.of(WIDE));
            var plan = new WideAmountCraftingPlan(new CraftingPlan(new GenericStack(STICK, Long.MAX_VALUE), 1, true, false,
                    new KeyCounter(), new KeyCounter(), missing, Map.of(pattern, Long.MAX_VALUE)),
                    WideLedgerSnapshot.empty(), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(WIDE.multiply(BigInteger.valueOf(5))));
            var graph = PlanGraph.fromPlan(plan);
            assertEquals(WIDE.multiply(BigInteger.valueOf(5)), graph.nodes().getFirst().amount());
            var task = graph.nodes().stream().filter(PlanGraph.Node::pattern).findFirst().orElseThrow(); assertEquals(WIDE, task.amount());
            assertEquals(4, graph.edges().size());
            assertEquals(List.of(WIDE.multiply(BigInteger.valueOf(8)), WIDE.multiply(BigInteger.valueOf(12))),
                    graph.edges().stream().filter(PlanGraph.Edge::input).map(PlanGraph.Edge::amount).toList());
            assertTrue(graph.edges().stream().filter(PlanGraph.Edge::input).allMatch(PlanGraph.Edge::alternative));
            var complete = new WideAmountCraftingPlan(new CraftingPlan(plan.finalOutput(), 1, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(), plan.patternTimes()),
                    WideLedgerSnapshot.empty(), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(WIDE.multiply(BigInteger.valueOf(5))));
            var expectedSummaryGraph = PlanGraph.fromPlan(complete);
            var summary = appeng.menu.me.crafting.CraftingPlanSummary.fromJob(
                    git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder().build(),
                    appeng.api.networking.security.IActionSource.empty(), complete);
            assertEquals(expectedSummaryGraph, ((EnhancedCraftingPlanSummary) (Object) summary).chexsonsaeutils$graph());
            var packet = buffer();
            try { summary.write(packet);
                var decoded = (EnhancedCraftingPlanSummary) (Object) appeng.menu.me.crafting.CraftingPlanSummary.read(packet);
                assertEquals(expectedSummaryGraph, decoded.chexsonsaeutils$graph()); assertEquals(0, packet.readableBytes());
            } finally { packet.release(); }
            assertEquals(BigInteger.valueOf(9), graph.nodes().stream().filter(n -> !n.pattern() && n.key().equals(IRON)).findFirst().orElseThrow().missing());
            var buf = buffer();
            try { graph.write(buf); assertEquals(graph, PlanGraph.read(buf)); assertEquals(0, buf.readableBytes()); }
            finally { buf.release(); }
        }
        private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)); }
        void numbersBeyond4096DigitsRoundTripWhenTheGraphFitsItsBudget() {
            var huge = BigInteger.TEN.pow(1000).multiply(BigInteger.valueOf(9).pow(4000));
            assertEquals(4817, huge.toString().length());
            var graph = new PlanGraph(List.of(new PlanGraph.Node(IRON, false, huge, huge, BigInteger.ZERO)),
                    List.of(new PlanGraph.Edge(0, 0, huge, true, false)));
            var b = buffer();
            try { graph.write(b); assertEquals(graph, PlanGraph.read(b)); assertEquals(0, b.readableBytes()); }
            finally { b.release(); }
        }

        void exceedingGraphBudgetStopsBeforeConvertingTheRestOfTheGraph() {
            var huge = BigInteger.TEN.pow(5000);
            var nodes = new ArrayList<>(Collections.nCopies(1000,
                    new PlanGraph.Node(IRON, false, huge, BigInteger.ZERO, BigInteger.ZERO)));
            var last = new CountedInteger();
            nodes.add(new PlanGraph.Node(IRON, false, last, BigInteger.ZERO, BigInteger.ZERO));
            var b = buffer();
            try {
                new PlanGraph(nodes, List.of()).write(b);
                assertTrue(b.readableBytes() < 256);
                var decoded = PlanGraph.read(b);
                assertFalse(decoded.problem().isEmpty());
                assertTrue(decoded.nodes().isEmpty());
                assertEquals(0, last.conversions, "达到预算后不能继续转换后面的数量");
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }

        private static final class CountedInteger extends BigInteger {
            private int conversions;
            CountedInteger() { super("123"); }
            @Override public String toString() { conversions++; return super.toString(); }
        }
        void selectedInputsUsePlannerAmountsAndContainerReturnsUseSlotMultiplier() {
            var pattern = new Pattern(STICK, new IPatternDetails.IInput[]{new Input(new GenericStack[]{new GenericStack(IRON, 2), new GenericStack(GOLD, 3)}, 4, IRON)}, List.of(new GenericStack(STICK, 1)));
            var plan = new git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan(
                    new CraftingPlan(new GenericStack(STICK, 10), 1, false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 10L)),
                    new KeyCounter(), new KeyCounter(), 10L, Map.of(STICK, Map.of(IRON, 8L)), Map.of(), WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(), Map.of());
            var graph = PlanGraph.fromPlan(plan);
            var inputs = graph.edges().stream().filter(PlanGraph.Edge::input).toList(); assertEquals(1, inputs.size());
            assertEquals(BigInteger.valueOf(80), inputs.getFirst().amount()); assertFalse(inputs.getFirst().alternative());
            var returned = graph.edges().stream().filter(PlanGraph.Edge::remainder).toList(); assertEquals(1, returned.size());
            assertEquals(BigInteger.valueOf(40), returned.getFirst().amount());
            assertEquals(2, PlanGraphLayout.create(graph).positions().stream().filter(PlanGraphLayout.Position::cycle).count());
        }
        private static PlanGraph.Node node() { return new PlanGraph.Node(IRON, false, WIDE, BigInteger.ZERO, BigInteger.ZERO); }
        private static PlanGraph.Edge edge(int a, int b) { return new PlanGraph.Edge(a, b, BigInteger.ONE, false, false); }
        void laysOutSharedDependenciesAndCyclesWithoutInventingSequentialExecution() {
            var graph = new PlanGraph(Collections.nCopies(6, node()), List.of(edge(0,1), edge(0,2), edge(1,3), edge(2,3), edge(3,4), edge(4,3), edge(4,5)));
            var layout = PlanGraphLayout.create(graph); var p = layout.positions();
            assertEquals(p.get(1).layer(), p.get(2).layer()); assertEquals(p.get(3).layer(), p.get(4).layer());
            assertTrue(p.get(3).cycle()); assertTrue(p.get(4).cycle()); assertFalse(p.get(5).cycle());
            assertTrue(p.get(5).layer() > p.get(4).layer()); assertTrue(p.get(1).layer() > p.get(0).layer());
            assertTrue(p.get(5).y() < p.get(4).y()); assertEquals(p.get(1).y(), p.get(2).y());
            assertEquals(layout, PlanGraphLayout.create(graph));
        }
        void handlesDeepChainsWithoutJavaStackRecursion() {
            var edges = new ArrayList<PlanGraph.Edge>(); for (int i = 0; i < 9999; i++) edges.add(edge(i, i + 1));
            var layout = PlanGraphLayout.create(new PlanGraph(Collections.nCopies(10000, node()), edges));
            assertEquals(9999, layout.positions().getLast().layer());
        }
        void rejectsInvalidReferencesAndNegativeAmounts() {
            assertThrows(IllegalArgumentException.class, () -> new PlanGraph(List.of(node()), List.of(edge(0, 1))));
            assertThrows(IllegalArgumentException.class, () -> new PlanGraph.Node(IRON, false, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ZERO));
            var b = buffer(); try { b.writeUtf(""); b.writeVarInt(-1); assertThrows(IllegalArgumentException.class, () -> PlanGraph.read(b)); } finally { b.release(); }
        }
        void arrowsAttachToSeparateNodePortsAndCycleRoutesStayOutsideIcons() {
            var graph = new PlanGraph(Collections.nCopies(4, node()), List.of(edge(0,1), edge(0,2), edge(2,3), edge(3,2)));
            var layout = PlanGraphLayout.create(graph); var routes = PlanGraphRoutes.create(graph, layout);
            assertNotEquals(routes.get(0).points().getFirst(), routes.get(1).points().getFirst());
            for (int i = 0; i < routes.size(); i++) {
                var route = routes.get(i).points(); var edge = graph.edges().get(i);
                var source = layout.positions().get(edge.from()); var target = layout.positions().get(edge.to());
                assertEquals(target.y() < source.y() ? source.y() : source.y() + PlanGraphLayout.NODE_SIZE, route.getFirst().y());
                assertEquals(target.y() <= source.y() ? target.y() + PlanGraphLayout.NODE_SIZE : target.y(), route.getLast().y());
                assertTrue(route.getFirst().x() > source.x() && route.getFirst().x() < source.x() + PlanGraphLayout.NODE_SIZE);
                assertTrue(route.getLast().x() > target.x() && route.getLast().x() < target.x() + PlanGraphLayout.NODE_SIZE);
                assertTrue(routes.get(i).visible(route.getLast().x(), route.getLast().y(), route.getLast().x(), route.getLast().y()));
                assertFalse(routes.get(i).visible(-1000, -1000, -900, -900));
                assertTrue(routes.get(i).maxX() < layout.width()); assertTrue(routes.get(i).maxY() < layout.height());
                for (int j = 1; j < route.size(); j++) assertTrue(route.get(j - 1).x() == route.get(j).x() || route.get(j - 1).y() == route.get(j).y());
                if (source.y() == target.y()) assertTrue(route.get(1).y() > source.y() + PlanGraphLayout.NODE_SIZE);
            }
        }
        void oversizedGraphPublishesExplicitProblemInsteadOfOversizedMenuPacket() {
            var graph = new PlanGraph(Collections.nCopies(20000, node()), List.of()); var b = buffer();
            try { graph.write(b); assertTrue(b.readableBytes() < 256); var result = PlanGraph.read(b);
                assertFalse(result.problem().isEmpty()); assertTrue(result.nodes().isEmpty()); assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }    void finalTargetIsTopmostAndInterleavedCycleMembersStayInContiguousBlocks() {
            // 两个独立环的编号交错，中间还夹着非环材料；聚集依据真实 SCC 而非编号。
            var graph = new PlanGraph(Collections.nCopies(7, node()), List.of(edge(1, 3), edge(3, 1),
                    edge(2, 5), edge(5, 2), edge(3, 0), edge(5, 0), edge(4, 0), edge(6, 4), edge(6, 1), edge(6, 2)));
            var layout = PlanGraphLayout.create(graph); var p = layout.positions();
            assertEquals(24, p.get(0).y());
            assertTrue(p.stream().allMatch(position -> position.y() >= p.get(0).y()));
            assertEquals(p.get(1).y(), p.get(3).y()); assertEquals(80, p.get(3).x() - p.get(1).x());
            assertEquals(p.get(2).y(), p.get(5).y()); assertEquals(80, p.get(5).x() - p.get(2).x());
            assertTrue(p.get(2).x() > p.get(3).x() + PlanGraphLayout.NODE_SIZE);
            assertTrue(p.get(4).x() > p.get(5).x() + PlanGraphLayout.NODE_SIZE);
            assertTrue(p.get(6).y() > p.get(4).y());
            assertEquals(layout, PlanGraphLayout.create(graph));
            for (var route : PlanGraphRoutes.create(graph, layout)) {
                assertTrue(route.minX() >= 0 && route.minY() >= 0);
                assertTrue(route.maxX() < layout.width() && route.maxY() < layout.height());
            }
        }
        void targetRemainsTopmostWhenItsCycleProducesAnotherTerminalByproduct() {
            var graph = new PlanGraph(Collections.nCopies(3, node()), List.of(edge(0, 1), edge(1, 0), edge(1, 2)));
            var p = PlanGraphLayout.create(graph).positions();
            assertEquals(24, p.get(0).y()); assertEquals(p.get(0).y(), p.get(1).y());
            assertTrue(p.get(2).y() > p.get(0).y());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.capturesExactExecutionsByproductsAndAlternativeUpperBounds", () -> new PlanGraphCases().capturesExactExecutionsByproductsAndAlternativeUpperBounds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.numbersBeyond4096DigitsRoundTripWhenTheGraphFitsItsBudget", () -> new PlanGraphCases().numbersBeyond4096DigitsRoundTripWhenTheGraphFitsItsBudget()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.exceedingGraphBudgetStopsBeforeConvertingTheRestOfTheGraph", () -> new PlanGraphCases().exceedingGraphBudgetStopsBeforeConvertingTheRestOfTheGraph()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.selectedInputsUsePlannerAmountsAndContainerReturnsUseSlotMultiplier", () -> new PlanGraphCases().selectedInputsUsePlannerAmountsAndContainerReturnsUseSlotMultiplier()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.laysOutSharedDependenciesAndCyclesWithoutInventingSequentialExecution", () -> new PlanGraphCases().laysOutSharedDependenciesAndCyclesWithoutInventingSequentialExecution()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.handlesDeepChainsWithoutJavaStackRecursion", () -> new PlanGraphCases().handlesDeepChainsWithoutJavaStackRecursion()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.rejectsInvalidReferencesAndNegativeAmounts", () -> new PlanGraphCases().rejectsInvalidReferencesAndNegativeAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.arrowsAttachToSeparateNodePortsAndCycleRoutesStayOutsideIcons", () -> new PlanGraphCases().arrowsAttachToSeparateNodePortsAndCycleRoutesStayOutsideIcons()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.oversizedGraphPublishesExplicitProblemInsteadOfOversizedMenuPacket", () -> new PlanGraphCases().oversizedGraphPublishesExplicitProblemInsteadOfOversizedMenuPacket()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.finalTargetIsTopmostAndInterleavedCycleMembersStayInContiguousBlocks", () -> new PlanGraphCases().finalTargetIsTopmostAndInterleavedCycleMembersStayInContiguousBlocks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphCases.targetRemainsTopmostWhenItsCycleProducesAnotherTerminalByproduct", () -> new PlanGraphCases().targetRemainsTopmostWhenItsCycleProducesAnotherTerminalByproduct())
            );
        }
    }

    /** PlanSummaryAmountsCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanSummaryAmountsCases {
        private static final AEItemKey KEY = AEItemKey.of(Items.IRON_INGOT);
        private static final BigInteger TIMES = BigInteger.TEN.pow(20);
        private record Pattern() implements IPatternDetails {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.STICK); }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(KEY, 2)); }
        }
        private WideAmountCraftingPlan plan() {
            var pattern = new Pattern();
            var used = new KeyCounter(); used.add(KEY, Long.MAX_VALUE);
            var missing = new KeyCounter(); missing.add(KEY, 20);
            var emit = new KeyCounter(); emit.add(KEY, 1);
            var exactUsed = new WideKeyCounter(); exactUsed.add(KEY, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN));
            var crafts = new WideCraftCounter(); crafts.add(pattern, PlanAmount.of(TIMES));
            return new WideAmountCraftingPlan(new CraftingPlan(new GenericStack(KEY, Long.MAX_VALUE), 1,
                    true, false, used, emit, missing, Map.of(pattern, Long.MAX_VALUE)),
                    WideLedgerSnapshot.capture(null, exactUsed, null), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(TIMES));
        }
        void summaryAddsAllExactComponentsWithoutUsingProjectedCraftCounts() {
            var counts = PlanSummaryAmounts.fromPlan(plan());
            var stored = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(11));
            var output = TIMES.multiply(BigInteger.TWO).add(BigInteger.ONE);
            assertEquals(stored, counts.stored().get(KEY));
            assertEquals(BigInteger.valueOf(20), counts.missing().get(KEY));
            assertEquals(output, counts.crafting().get(KEY));
            assertEquals(stored, counts.displaySnapshot().get(WideAmountKind.USED, KEY));
            assertEquals(output, counts.displaySnapshot().get(WideAmountKind.EMIT, KEY));
            assertEquals(Long.MAX_VALUE, counts.entries().getFirst().getCraftAmount());
            assertEquals(20, counts.entries().getFirst().getMissingAmount());
        }
        void patternTimesUseWidePlanAndSurviveActualEntryProtocol() {
            var entry = new CraftingPlanSummaryEntry(KEY, 0, 0, Long.MAX_VALUE);
            var summary = new CraftingPlanSummary(1, false, List.of(entry));
            CraftingStatusEnhancer.attachPatternTimes(summary, plan());
            var enhanced = (EnhancedCraftingPlanSummaryEntry) (Object) entry;
            assertEquals(List.of(TIMES), enhanced.chexsonsaeutils$patternTimes());
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            try {
                entry.write(buf);
                var decoded = (EnhancedCraftingPlanSummaryEntry) (Object) CraftingPlanSummaryEntry.read(buf);
                assertEquals(List.of(TIMES), decoded.chexsonsaeutils$patternTimes());
                assertEquals(0, buf.readableBytes());
            } finally { buf.release(); }
        }
        void normalizationSortsExactlyOnceAndFormattingRetainsCompleteDigits() {
            var sorted = CraftingStatusEnhancer.normalizePatternTimes(List.of(BigInteger.TEN, TIMES, BigInteger.ONE));
            assertEquals(List.of(TIMES, BigInteger.TEN), CraftingStatusEnhancer.displayedPatternTimes(sorted, 2));
            assertThrows(IllegalArgumentException.class, () -> CraftingStatusEnhancer.normalizePatternTimes(List.of(BigInteger.valueOf(-1))));
            var full = EnhancedCraftingStatusFormatting.formatAmount(TIMES, AmountFormat.FULL, AmountNumberStyle.SCIENTIFIC);
            assertEquals(TIMES.toString(), full.replaceAll("[^0-9]", ""));
            assertTrue(EnhancedCraftingStatusFormatting.formatAmount(TIMES, AmountFormat.SLOT, AmountNumberStyle.SCIENTIFIC)
                    .toLowerCase(Locale.ROOT).contains("e20"));
            assertEquals("1T", EnhancedCraftingStatusFormatting.formatAmount(BigInteger.TEN.pow(12),
                    AmountFormat.SLOT, AmountNumberStyle.ABBREVIATED));
            assertEquals("1Qi", EnhancedCraftingStatusFormatting.formatAmount(BigInteger.TEN.pow(18),
                    AmountFormat.SLOT, AmountNumberStyle.ABBREVIATED));
        }

        void exactCpuFrameProtocolKeepsAllComponentsAndRejectsNegativeAmounts() {
            var payload = new git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload(4, 7,
                    new git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts(
                            Map.of(KEY, TIMES), Map.of(KEY, TIMES.add(BigInteger.ONE)), Map.of(KEY, TIMES.multiply(BigInteger.TWO))));
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            try {
                var codec = git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload.STREAM_CODEC;
                codec.encode(buf, payload);
                assertEquals(payload, codec.decode(buf));
                assertEquals(0, buf.readableBytes());
                buf.clear(); buf.writeVarInt(4); buf.writeVarInt(7); buf.writeVarInt(1);
                AEKey.writeKey(buf, KEY); buf.writeByteArray(BigInteger.valueOf(-1).toByteArray());
                assertThrows(IllegalArgumentException.class, () -> codec.decode(buf));
            } finally { buf.release(); }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanSummaryAmountsCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryAmountsCases.summaryAddsAllExactComponentsWithoutUsingProjectedCraftCounts", () -> new PlanSummaryAmountsCases().summaryAddsAllExactComponentsWithoutUsingProjectedCraftCounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryAmountsCases.patternTimesUseWidePlanAndSurviveActualEntryProtocol", () -> new PlanSummaryAmountsCases().patternTimesUseWidePlanAndSurviveActualEntryProtocol()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryAmountsCases.normalizationSortsExactlyOnceAndFormattingRetainsCompleteDigits", () -> new PlanSummaryAmountsCases().normalizationSortsExactlyOnceAndFormattingRetainsCompleteDigits()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryAmountsCases.exactCpuFrameProtocolKeepsAllComponentsAndRejectsNegativeAmounts", () -> new PlanSummaryAmountsCases().exactCpuFrameProtocolKeepsAllComponentsAndRejectsNegativeAmounts())
            );
        }
    }

    /** PlanSummaryBytesCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanSummaryBytesCases {
        void recursivePlanBytesReachTheTitleInTheSameSummaryPacket() {
            var exact = BigInteger.TEN.pow(40).add(BigInteger.valueOf(123));
            var base = new CraftingPlan(new GenericStack(AEItemKey.of(Items.PAPER), 1), Long.MAX_VALUE,
                    false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
            var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 1, Map.of(),
                    Map.of(), WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(), Map.of(), null, PlanAmount.of(exact));
            var server = new Summary();
            PlanSummaryBytes.attach(server, plan);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                PlanSummaryBytes.write(buffer, server.chexsonsaeutils$exactBytes());
                var client = new Summary();
                client.chexsonsaeutils$setExactBytes(PlanSummaryBytes.read(buffer));
                assertEquals(exact, PlanSummaryBytes.resolve(client, ""), "独立菜单字段缺失时仍显示原计划真值");
                assertEquals(exact, PlanSummaryBytes.resolve(client, Long.toString(Long.MAX_VALUE)), "陈旧 long 投影不能覆盖当前摘要");
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }

        void originalFiniteSummaryKeepsItsLongBytes() {
            var original = new CraftingPlanSummary(1234, false, List.of());
            assertEquals(BigInteger.valueOf(1234), PlanSummaryBytes.resolve(original, ""));
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                PlanSummaryBytes.write(buffer, null);
                assertNull(PlanSummaryBytes.read(buffer));
            } finally { buffer.release(); }
        }

        private static final class Summary extends CraftingPlanSummary implements EnhancedCraftingPlanSummary {
            private PlanGraph graph = PlanGraph.EMPTY;
            @Override public PlanGraph chexsonsaeutils$graph() { return graph; }
            @Override public void chexsonsaeutils$setGraph(PlanGraph value) { graph = value; }
            private BigInteger bytes;
            private long millis;
            private String solver = "graph";
            Summary() { super(Long.MAX_VALUE, false, List.of()); }
            @Override public BigInteger chexsonsaeutils$exactBytes() { return bytes; }
            @Override public void chexsonsaeutils$setExactBytes(BigInteger value) { bytes = value; }
            @Override public long chexsonsaeutils$planningMillis() { return millis; }
            @Override public String chexsonsaeutils$solver() { return solver; }
            @Override public void chexsonsaeutils$setProvenance(long millis, String solver) { this.millis = millis; this.solver = solver; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanSummaryBytesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryBytesCases.recursivePlanBytesReachTheTitleInTheSameSummaryPacket", () -> new PlanSummaryBytesCases().recursivePlanBytesReachTheTitleInTheSameSummaryPacket()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanSummaryBytesCases.originalFiniteSummaryKeepsItsLongBytes", () -> new PlanSummaryBytesCases().originalFiniteSummaryKeepsItsLongBytes())
            );
        }
    }

    /** WideAmountConfirmLinesCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideAmountConfirmLinesCases {
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

        void scientificStyleRestylesTheWholeCompactBlockWithoutALedger() {
            List<Component> lines = blockOfTwoLines();

            int rewritten = WideAmountConfirmLines.applyWideAmounts(lines, 0,
                    1_000_000_000L, Long.MAX_VALUE, 0L, null, LAVA, AmountFormat.SLOT, SCI);

            assertEquals(2, rewritten, "指数法下两行都要换写法，同一列不留两种计数方式");
            assertEquals(2, lines.size(), "换写法不改行数");
            assertEquals("1.00e9", argOf(lines.get(0)));
            assertEquals("9.22e18", argOf(lines.get(1)));
        }

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.overflowedLineIsReplacedInPlaceAndNeighboursKeepIdentity", () -> new WideAmountConfirmLinesCases().overflowedLineIsReplacedInPlaceAndNeighboursKeepIdentity()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.keyWithoutOverflowLeavesEveryLineUntouched", () -> new WideAmountConfirmLinesCases().keyWithoutOverflowLeavesEveryLineUntouched()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.misshapedBlockTouchesNothing", () -> new WideAmountConfirmLinesCases().misshapedBlockTouchesNothing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.tooltipBlockStartsAfterTheItemNameLines", () -> new WideAmountConfirmLinesCases().tooltipBlockStartsAfterTheItemNameLines()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.scientificStyleRestylesTheWholeCompactBlockWithoutALedger", () -> new WideAmountConfirmLinesCases().scientificStyleRestylesTheWholeCompactBlockWithoutALedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideAmountConfirmLinesCases.scientificStyleNeverTouchesTheFullPrecisionTooltip", () -> new WideAmountConfirmLinesCases().scientificStyleNeverTouchesTheFullPrecisionTooltip())
            );
        }
    }
}
