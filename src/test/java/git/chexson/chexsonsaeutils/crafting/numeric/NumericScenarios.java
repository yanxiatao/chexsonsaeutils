package git.chexson.chexsonsaeutils.crafting.numeric;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.ByteAccount;
import git.chexson.chexsonsaeutils.bigint.KeyIndex;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精确数量与材料账本的行为场景；由类别测试统一调用。 */
public final class NumericScenarios {
    private NumericScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.numeric",
                () -> ByteAccountCases.verify(temporaryRoot),
                () -> PlanAmountCases.verify(temporaryRoot),
                () -> WideCraftingInventoryCases.verify(temporaryRoot),
                () -> WideKeyCounterCases.verify(temporaryRoot),
                () -> WideMathCeilCases.verify(temporaryRoot)
        );
    }

    /** 执行本包的按需度量，保留各场景名称与状态隔离。 */
    public static void measure(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.numeric",
                () -> WideKeyCounterFootprintCases.measure(temporaryRoot)
        );
    }

    /** ByteAccountCases 的独立场景夹具，按类别统一执行。 */
    public static final class ByteAccountCases {
        void routeByteDeltaRoundsAfterSubtractionIncludingWideAndFractionalFees() {
            var water = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
            var account = new ByteAccount();
            account.addBytes(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(BigInteger.TEN.pow(30)));
            account.add(water, 999, 1);
            var baseline = account.copy();
            account.add(water, 2, 1);
            assertEquals(1L, account.roundedDifference(baseline).fastValue());
            assertEquals(0L, baseline.roundedDifference(account).fastValue());
            account.restore(baseline);
            assertEquals(0L, account.roundedDifference(baseline).fastValue());
            assertEquals(BigInteger.TEN.pow(30).add(BigInteger.ONE), baseline.value().exactValue());
        }

        void mixedResourceDenominatorsDoNotOverflowDuringExactSummation() {
            var account = new ByteAccount();
            for (long apb : new long[] {2_147_483_647L, 2_147_483_629L, 2_147_483_587L}) {
                account.addBucket(apb, apb * 1000 + 1);
            }
            assertEquals(24_001L, account.value().fastValue());
        }

        void longNodeFeesPreserveEveryIntegerAndUndo() {
            long bytes = (1L << 53) + 1L;
            var account = new ByteAccount();
            account.addBytes(bytes);
            assertEquals(BigInteger.valueOf(bytes), account.value().exactValue());
            var state = new git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState(
                    java.util.Map.of(), java.util.Map.of());
            state.addBytes(3L);
            int mark = state.journal();
            state.addBytes(bytes);
            assertEquals(BigInteger.valueOf(bytes).add(BigInteger.valueOf(3)), state.byteAccount().value().exactValue());
            state.undoTo(mark);
            assertEquals(3L, state.byteAccount().value().fastValue());
        }

        void fractionalFluidFeesRoundOnlyAfterExactAccumulation() {
            var account = new ByteAccount();
            var water = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
            for (int i = 0; i < 1000; i++) account.add(water, 1, 1);
            assertEquals(BigInteger.ONE, account.value().exactValue());
            assertTrue(account.ae2Bytes() > 1.0, "浮点诊断值仍保留原始累加结果");
        }

        void fractionalFeeIsNotLostBesideLargeIntegerNodeFees() {
            var account = new ByteAccount();
            account.addBytes((1L << 53) - 1);
            account.add(appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER), 1, 1);
            assertEquals(BigInteger.ONE.shiftLeft(53), account.value().exactValue());
        }

        private static AEItemKey paper() {
            return AEItemKey.of(new ItemStack(Items.PAPER));
        }

        void matchesAe2DoubleFormulaBitForBitInExactDomain() {
            var account = new ByteAccount();
            long[] amounts = {1L, 3L, 100L, 4096L};
            long[] multipliers = {1L, 2L, 7L, 13L};
            double expected = 0.0;
            for (long amount : amounts) {
                for (long multiplier : multipliers) {
                    account.add(paper(), amount, multiplier);
                    expected += (double) amount * multiplier / paper().getAmountPerByte() * 8;
                }
            }
            assertEquals((long) Math.ceil(expected), account.value().fastValue(), "精确域内与 AE2 double 读数逐位相等");
            assertEquals(expected, account.ae2Bytes(), "double 累加器位模式一致");
        }

        void addBytesDirectMatchesAe2Path() {
            var account = new ByteAccount();
            account.addBytes(1234.5);
            account.addBytes(0.5);
            assertEquals(1235L, account.value().fastValue());
        }

        void beyondDoubleExactnessUsesPreciseIntegerAccount() {
            var account = new ByteAccount();
            long amount = (1L << 60) + 1L;
            account.add(paper(), amount, 1L);

            // 物品 apb=8：精确值 = ceil(8·amount/8) = amount；double 侧 2^60+1 舍入为 2^60（间隔 256）
            var expected = BigInteger.valueOf(amount);
            assertEquals(expected, account.value().exactValue(), "精确值 = 2^60+1");

            double ae2Reading = Math.ceil(account.ae2Bytes());
            // long 先参与比较：double 混合比较会把 amount 舍入回 2^60
            assertTrue((long) ae2Reading < amount, "AE2 double 读数丢精度，精确值必须更大");
            assertEquals(BigInteger.TWO.pow(60), BigInteger.valueOf((long) ae2Reading), "double 读数落在 2^60");
        }

        void valueFallsBackToPreciseWhenBeyondLongDomain() {
            var account = new ByteAccount();
            account.add(paper(), Long.MAX_VALUE, 16L);

            // 精确值 = ceil(8·MAX·16/8) = MAX·16，超出 long 域
            var expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(16L));
            assertEquals(expected, account.value().exactValue());
        }

        void copyAndRestoreRoundTrip() {
            var account = new ByteAccount();
            account.add(paper(), 100L, 3L);
            var snapshot = account.copy();

            account.add(paper(), 55L, 1L);
            account.restore(snapshot);

            double expected = (double) 100L * 3L / paper().getAmountPerByte() * 8;
            assertEquals(expected, account.ae2Bytes());
            assertEquals((long) Math.ceil(expected), account.value().fastValue());
        }
        void wideNodeFeesAndMaterialFeesAreBothCountedExactly() {
            var account = new ByteAccount();
            var nodes = BigInteger.TEN.pow(400).add(BigInteger.ONE);
            account.addBytes(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(nodes));
            account.add(paper(), Long.MAX_VALUE, 16);
            var expected = nodes.add(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(16)));
            assertEquals(expected, account.value().exactValue());
            var saved = account.copy();
            account.addBytes(1234.5);
            account.restore(saved);
            assertEquals(expected, account.value().exactValue());
        }

        void journalUndoRestoresWideNodeFeesAndMixedMaterialFees() {
            var state = new git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState(java.util.Map.of(), java.util.Map.of());
            state.addBytes(5.5);
            int mark = state.journal();
            state.addBytes(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(BigInteger.TEN.pow(40)));
            state.addStackBytesWide(paper(), 7, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(BigInteger.TEN.pow(35)));
            assertEquals(BigInteger.TEN.pow(40).add(BigInteger.TEN.pow(35).multiply(BigInteger.valueOf(7))).add(BigInteger.valueOf(6)),
                    state.byteAccount().value().exactValue());
            state.undoTo(mark);
            assertEquals(6L, state.byteAccount().value().fastValue());
            assertEquals(5.5, state.byteAccount().ae2Bytes());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.routeByteDeltaRoundsAfterSubtractionIncludingWideAndFractionalFees", () -> new ByteAccountCases().routeByteDeltaRoundsAfterSubtractionIncludingWideAndFractionalFees()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.mixedResourceDenominatorsDoNotOverflowDuringExactSummation", () -> new ByteAccountCases().mixedResourceDenominatorsDoNotOverflowDuringExactSummation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.longNodeFeesPreserveEveryIntegerAndUndo", () -> new ByteAccountCases().longNodeFeesPreserveEveryIntegerAndUndo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.fractionalFluidFeesRoundOnlyAfterExactAccumulation", () -> new ByteAccountCases().fractionalFluidFeesRoundOnlyAfterExactAccumulation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.fractionalFeeIsNotLostBesideLargeIntegerNodeFees", () -> new ByteAccountCases().fractionalFeeIsNotLostBesideLargeIntegerNodeFees()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.matchesAe2DoubleFormulaBitForBitInExactDomain", () -> new ByteAccountCases().matchesAe2DoubleFormulaBitForBitInExactDomain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.addBytesDirectMatchesAe2Path", () -> new ByteAccountCases().addBytesDirectMatchesAe2Path()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.beyondDoubleExactnessUsesPreciseIntegerAccount", () -> new ByteAccountCases().beyondDoubleExactnessUsesPreciseIntegerAccount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.valueFallsBackToPreciseWhenBeyondLongDomain", () -> new ByteAccountCases().valueFallsBackToPreciseWhenBeyondLongDomain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.copyAndRestoreRoundTrip", () -> new ByteAccountCases().copyAndRestoreRoundTrip()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.wideNodeFeesAndMaterialFeesAreBothCountedExactly", () -> new ByteAccountCases().wideNodeFeesAndMaterialFeesAreBothCountedExactly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ByteAccountCases.journalUndoRestoresWideNodeFeesAndMixedMaterialFees", () -> new ByteAccountCases().journalUndoRestoresWideNodeFeesAndMixedMaterialFees())
            );
        }
    }

    /** PlanAmountCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanAmountCases {
        void arithmeticMatchesExactReferenceAtBoundariesAndRandomValues() {
            long[] edges = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -16, -1, 0, 1, 16,
                    Long.MAX_VALUE - 1, Long.MAX_VALUE};
            for (long left : edges) {
                for (long right : edges) {
                    assertArithmetic(left, right);
                }
            }
            var random = new java.util.Random(0x20261007L);
            for (int sample = 0; sample < 10_000; sample++) {
                assertArithmetic(random.nextLong(), random.nextLong());
            }
        }

        private static void assertArithmetic(long left, long right) {
            var a = BigInteger.valueOf(left);
            var b = BigInteger.valueOf(right);
            var x = PlanAmount.of(left);
            var y = PlanAmount.of(right);
            assertEquals(a.add(b), x.add(y).exactValue());
            assertEquals(a.add(b).bitLength() < Long.SIZE, x.add(y).isFast());
            assertEquals(a.subtract(b), x.subtract(y).exactValue());
            assertEquals(a.subtract(b).bitLength() < Long.SIZE, x.subtract(y).isFast());
            var product = a.multiply(b);
            assertEquals(product, x.multiply(y).exactValue());
            assertEquals(product.bitLength() < Long.SIZE, x.multiply(y).isFast());
            assertEquals(product.bitLength() < Long.SIZE,
                    git.chexson.chexsonsaeutils.bigint.WideMath.mulFitsLong(left, right));
            if (right != 0) {
                var qr = a.divideAndRemainder(b);
                assertEquals(qr[0], x.divide(y).exactValue());
                assertEquals(qr[1], x.remainder(y).exactValue());
                assertEquals(qr[1].signum() != 0 && qr[1].signum() == b.signum()
                        ? qr[0].add(BigInteger.ONE) : qr[0], x.ceilDiv(y).exactValue());
                assertEquals(qr[1].signum() != 0 && qr[1].signum() != b.signum()
                        ? qr[0].subtract(BigInteger.ONE) : qr[0], x.floorDiv(y).exactValue());
            }
        }

        void identityOperationsReuseWideOperandsAndMixedComparisonIsExact() {
            var amount = PlanAmount.of(BigInteger.TEN.pow(100));
            assertSame(amount, amount.add(PlanAmount.ZERO));
            assertSame(amount, PlanAmount.ZERO.add(amount));
            assertSame(amount, amount.subtract(PlanAmount.ZERO));
            assertSame(amount, amount.multiply(PlanAmount.ONE));
            assertSame(amount, PlanAmount.ONE.multiply(amount));
            assertSame(amount, amount.divide(PlanAmount.ONE));
            assertSame(amount, amount.ceilDiv(PlanAmount.ONE));
            assertSame(amount, amount.floorDiv(PlanAmount.ONE));
            assertSame(PlanAmount.ZERO, amount.subtract(amount));
            assertSame(PlanAmount.ZERO, amount.multiply(PlanAmount.ZERO));
            for (long value : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
                var narrow = PlanAmount.of(value);
                assertTrue(narrow.compareTo(amount) < 0);
                assertTrue(amount.compareTo(narrow) > 0);
                assertTrue(narrow.compareTo(amount.negate()) > 0);
                assertTrue(amount.negate().compareTo(narrow) < 0);
                assertEquals(Long.hashCode(value), narrow.hashCode());
            }
            assertEquals(amount.exactValue().hashCode(), amount.hashCode());
        }

        void smallValuesAreCachedAndFast() {
            assertSame(PlanAmount.of(5L), PlanAmount.of(5L));
            assertSame(PlanAmount.of(-16L), PlanAmount.of(-16L));
            assertTrue(PlanAmount.of(5L).isFast());
            assertEquals(5L, PlanAmount.of(5L).fastValue());
        }

        void wideResultFallingBackToLongDomainNormalizesToFast() {
            var wide = PlanAmount.of(BigInteger.TWO.pow(63));
            var back = wide.subtract(PlanAmount.ONE);
            assertTrue(back.isFast(), "结果回落 long 域时 wide 必须为 null");
            assertEquals(Long.MAX_VALUE, back.fastValue());
        }

        void minLongNegateIsExact() {
            var result = PlanAmount.of(Long.MIN_VALUE).negate();
            assertEquals(BigInteger.valueOf(Long.MIN_VALUE).negate(), result.exactValue());
            assertEquals(BigInteger.TWO.pow(63), result.exactValue());
        }

        void compareMixedFastAndWide() {
            var wideAbove = PlanAmount.of(BigInteger.TWO.pow(63));
            // 2^63 恰为 Long.MIN_VALUE，须用 -(2^63+1) 才真正落在宽域
            var wideBelow = PlanAmount.of(BigInteger.TWO.pow(63).add(BigInteger.ONE).negate());
            assertTrue(wideAbove.compareTo(PlanAmount.of(Long.MAX_VALUE)) > 0);
            assertTrue(wideBelow.compareTo(PlanAmount.of(Long.MIN_VALUE)) < 0);
            assertEquals(0, PlanAmount.of(Long.MAX_VALUE).compareTo(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE))));
            assertTrue(PlanAmount.of(Long.MAX_VALUE).compareTo(PlanAmount.ONE) > 0);
        }

        void ceilDivWideOperandsMatchReference() {
            var big = BigInteger.TWO.pow(70).add(BigInteger.valueOf(12345L));
            var dividend = PlanAmount.of(big);
            var divisor = PlanAmount.of(7L);
            var q = big.divideAndRemainder(BigInteger.valueOf(7L));
            var expected = q[1].signum() != 0 ? q[0].add(BigInteger.ONE) : q[0];
            assertEquals(expected, dividend.ceilDiv(divisor).exactValue());
        }

        void floorDivAndRemainderMatchJavaSemantics() {
            assertEquals(-4L, PlanAmount.of(-7L).floorDiv(PlanAmount.of(2L)).fastValue());
            assertEquals(3L, PlanAmount.of(7L).floorDiv(PlanAmount.of(2L)).fastValue());
            assertEquals(-1L, PlanAmount.of(-7L).remainder(PlanAmount.of(2L)).fastValue());
            assertEquals(1L, PlanAmount.of(7L).remainder(PlanAmount.of(2L)).fastValue());
            var wide = PlanAmount.of(BigInteger.TWO.pow(70));
            assertEquals(BigInteger.TWO.pow(70).remainder(BigInteger.valueOf(3L)),
                    wide.remainder(PlanAmount.of(3L)).exactValue());
        }

        void minMaxAndSignumMixed() {
            var wide = PlanAmount.of(BigInteger.TWO.pow(63));
            assertEquals(PlanAmount.of(Long.MAX_VALUE), PlanAmount.of(Long.MAX_VALUE).min(wide));
            assertEquals(wide, PlanAmount.of(Long.MAX_VALUE).max(wide));
            assertEquals(1, wide.signum());
            assertEquals(-1, wide.negate().signum());
            assertEquals(0, PlanAmount.ZERO.signum());
        }

        void equalsAndHashCodeAcrossFastAndWide() {
            var fast = PlanAmount.of(Long.MAX_VALUE);
            var wideSame = PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE));
            assertEquals(fast, wideSame);
            assertEquals(fast.hashCode(), wideSame.hashCode());
            assertNotEquals(fast, PlanAmount.of(Long.MAX_VALUE - 1L));
        }

        void fitsLongBoundary() {
            assertTrue(Ae2Amounts.fitsLong(PlanAmount.of(Long.MAX_VALUE)));
            assertTrue(Ae2Amounts.fitsLong(PlanAmount.of(Long.MIN_VALUE)));
            assertTrue(Ae2Amounts.fitsLong(PlanAmount.of(BigInteger.TWO.pow(63).subtract(BigInteger.ONE))));
            assertFalse(Ae2Amounts.fitsLong(PlanAmount.of(BigInteger.TWO.pow(63))));
            assertFalse(Ae2Amounts.fitsLong(PlanAmount.of(BigInteger.TWO.pow(63).add(BigInteger.ONE).negate())));
        }

        private static void assertSame(Object expected, Object actual) {
            org.junit.jupiter.api.Assertions.assertSame(expected, actual);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.arithmeticMatchesExactReferenceAtBoundariesAndRandomValues", () -> new PlanAmountCases().arithmeticMatchesExactReferenceAtBoundariesAndRandomValues()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.identityOperationsReuseWideOperandsAndMixedComparisonIsExact", () -> new PlanAmountCases().identityOperationsReuseWideOperandsAndMixedComparisonIsExact()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.smallValuesAreCachedAndFast", () -> new PlanAmountCases().smallValuesAreCachedAndFast()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.wideResultFallingBackToLongDomainNormalizesToFast", () -> new PlanAmountCases().wideResultFallingBackToLongDomainNormalizesToFast()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.minLongNegateIsExact", () -> new PlanAmountCases().minLongNegateIsExact()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.compareMixedFastAndWide", () -> new PlanAmountCases().compareMixedFastAndWide()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.ceilDivWideOperandsMatchReference", () -> new PlanAmountCases().ceilDivWideOperandsMatchReference()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.floorDivAndRemainderMatchJavaSemantics", () -> new PlanAmountCases().floorDivAndRemainderMatchJavaSemantics()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.minMaxAndSignumMixed", () -> new PlanAmountCases().minMaxAndSignumMixed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.equalsAndHashCodeAcrossFastAndWide", () -> new PlanAmountCases().equalsAndHashCodeAcrossFastAndWide()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanAmountCases.fitsLongBoundary", () -> new PlanAmountCases().fitsLongBoundary())
            );
        }
    }

    /** WideCraftingInventoryCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideCraftingInventoryCases {
        private static final HolderLookup.Provider REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey GEAR = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private static final BigInteger TRIPLE_LONG_MAX = LONG_MAX.multiply(BigInteger.valueOf(3L));

        /** 同一键累计越出 {@code long}：宽域读口给精确值，主表停在 {@code Long.MAX_VALUE}。 */
        void accumulatedBeyondLongReadsExactlyWhileMainTableStopsAtLongMax() {
            var inventory = newInventory(ignored -> {
            });

            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, 1L, Actionable.MODULATE);
            inventory.insert(PRODUCT, LONG_MAX.subtract(BigInteger.ONE).longValueExact(), Actionable.MODULATE);

            assertEquals(TRIPLE_LONG_MAX, inventory.amount(PRODUCT), "累计 3×Long.MAX_VALUE，逐位精确");
            assertEquals(Long.MAX_VALUE, inventory.list.get(PRODUCT),
                    "AE2 看见的主表停在 long 天花板：既不绕回，也报不出比真值大的数");
            assertTrue(inventory.hasOverflow(), "这笔确实越界了，侧表该有条目");
        }

        /** 越界后又被抽回 {@code long} 域：侧表条目撤下，主表读数重新等于精确值。 */
        void fallingBackIntoLongDomainRetiresTheSideEntry() {
            var inventory = newInventory(ignored -> {
            });
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            assertEquals(TRIPLE_LONG_MAX.subtract(LONG_MAX), inventory.amount(PRODUCT));

            assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
            assertEquals(LONG_MAX, inventory.amount(PRODUCT));
            assertEquals(Long.MAX_VALUE, inventory.list.get(PRODUCT));
            assertFalse(inventory.hasOverflow(), "回到 long 域就该撤掉侧表条目");

            assertEquals(1L, inventory.extract(PRODUCT, 1L, Actionable.MODULATE));
            assertEquals(LONG_MAX.subtract(BigInteger.ONE), inventory.amount(PRODUCT));
            assertEquals(Long.MAX_VALUE - 1L, inventory.list.get(PRODUCT));
        }

        /** 抽干一个越界键：侧表与主表一起清空，不留 0 值条目（与基类抽干即 remove 同一条口径）。 */
        void drainingAnOverflowKeyEvictsItWholesale() {
            var inventory = newInventory(ignored -> {
            });
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, 5L, Actionable.MODULATE);
            assertEquals(LONG_MAX.add(BigInteger.valueOf(5L)), inventory.amount(PRODUCT));

            assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
            assertEquals(BigInteger.valueOf(5L), inventory.amount(PRODUCT));
            assertFalse(inventory.hasOverflow(), "差额抽完就回到 long 域");

            assertEquals(5L, inventory.extract(PRODUCT, 5L, Actionable.MODULATE));
            assertEquals(BigInteger.ZERO, inventory.amount(PRODUCT));
            assertTrue(inventory.list.isEmpty(), "抽干即整条撤掉，不留 0 值条目");
            assertFalse(inventory.hasOverflow());
        }

        /** 没越界的键：不挂侧表、不写覆盖键，写出的 NBT 与基类一字不差。 */
        void inRangeKeysGetNoSideTableAndNoWideOverrideKey() {
            var wide = newInventory(ignored -> {
            });
            var plain = new ListCraftingInventory(ignored -> {
            });

            for (int batch = 0; batch < 4; batch++) {
                wide.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);
                plain.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);
                wide.insert(GEAR, 7L, Actionable.MODULATE);
                plain.insert(GEAR, 7L, Actionable.MODULATE);
            }

            assertFalse(wide.hasOverflow(), "全程没越界 ⇒ 侧表一次都没被建起来");
            assertEquals(BigInteger.valueOf(4_000_000_000_000L), wide.amount(PRODUCT));
            assertEquals(snapshotOf(plain), snapshotOf(wide), "主表内容与基类实现逐字相同");

            var wideTag = wide.writeToNBT(REGISTRIES);
            assertEquals(plain.writeToNBT(REGISTRIES), wideTag, "常态写出的 NBT 与基类一字不差");
            assertEquals(0, countWideEntries(wideTag), "不越界就一个覆盖键都不挂");
        }

        /** 越界条目进出 NBT 逐位相等；只有真越界那条挂覆盖键，AE2 可见的老 {@code #} 位仍是饱和读数。 */
        void wideEntriesRoundTripThroughNbtBitForBit() {
            var inventory = newInventory(ignored -> {
            });
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, 4_000_000_000_000_000_000L, Actionable.MODULATE);
            inventory.insert(GEAR, 42L, Actionable.MODULATE);

            var exactProduct = LONG_MAX.multiply(BigInteger.valueOf(2L))
                    .add(BigInteger.valueOf(4_000_000_000_000_000_000L));
            assertEquals(exactProduct, inventory.amount(PRODUCT));

            ListTag tag = inventory.writeToNBT(REGISTRIES);
            assertEquals(2, tag.size());
            assertEquals(1, countWideEntries(tag), "只有真越界的那一条多挂覆盖键");

            var restored = newInventory(ignored -> {
            });
            restored.readFromNBT(tag, REGISTRIES);
            assertEquals(exactProduct, restored.amount(PRODUCT), "含低位特征数字，逐位还原");
            assertEquals(BigInteger.valueOf(42L), restored.amount(GEAR));
            assertEquals(Long.MAX_VALUE, restored.list.get(PRODUCT),
                    "读回后对 AE2 报的仍是饱和读数，不是真值");
            assertEquals(snapshotOf(inventory), snapshotOf(restored));
        }

        /** 没有覆盖键的旧数据：按主表 {@code long} 读数原样吃回来，不产生侧表条目。 */
        void legacyEntriesWithoutOverrideKeyReadBackAsMainTableLongs() {
            ListTag legacy = new ListTag();
            legacy.add(entry(PRODUCT, Long.MAX_VALUE));
            legacy.add(entry(GEAR, 12345L));

            var inventory = newInventory(ignored -> {
            });
            inventory.insert(GEAR, 999L, Actionable.MODULATE);
            inventory.readFromNBT(legacy, REGISTRIES);

            assertFalse(inventory.hasOverflow(), "缺覆盖键就是\"这笔装得进 long\"，不是迁移壳");
            assertEquals(LONG_MAX, inventory.amount(PRODUCT));
            assertEquals(BigInteger.valueOf(12345L), inventory.amount(GEAR), "读回是覆盖不是累加：与基类同一条规则");
            assertEquals(2, snapshotOf(inventory).size());
        }

        /**
         * 回归护栏：一整套 {@code insert/extract/clear} 脚本在 {@link WideCraftingInventory} 与基类
         * {@link ListCraftingInventory} 上平行跑，每步比对<b>返回值、主表全量快照、变更回调次数</b>。
         *
         * <p>脚本刻意覆盖容易走偏的形状：全额抽干（基类整条 remove 键）、0 值入账（基类会留下 0 值
         * 条目）、抽到一半、SIMULATE 不许发回调、恰好塞满 {@code long} 但一次都没越界。
         */
        void baseIdentityCallsMatchPlainListCraftingInventoryStepByStep() {
            var wideLog = new ArrayList<String>();
            var wide = newInventory(key -> wideLog.add(itemName(key)));
            var plainLog = new ArrayList<String>();
            var plain = new ListCraftingInventory(key -> plainLog.add(itemName(key)));
            // 两边都以基类身份被调用：宽域实现拿不到任何子类特权的捷径
            ListCraftingInventory wideAsBase = wide;

            apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(PRODUCT, 100L, Actionable.MODULATE));
            apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(BUCKET, 1L, Actionable.MODULATE));
            applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE));
            applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.extract(PRODUCT, 30L, Actionable.MODULATE));
            applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
            apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(PRODUCT, 0L, Actionable.MODULATE));
            apply(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.insert(GEAR, 4_000_000_000_000_000_000L, Actionable.MODULATE));
            apply(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.insert(GEAR, 4_000_000_000_000_000_000L, Actionable.MODULATE));
            applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.extract(GEAR, Long.MAX_VALUE, Actionable.SIMULATE));
            applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                    inv -> inv.extract(BUCKET, 1L, Actionable.MODULATE));
            apply(wideAsBase, plain, wideLog, plainLog, ListCraftingInventory::clear);

            assertFalse(wide.hasOverflow(), "整段脚本一次都没越界 ⇒ 全程该走基类原路径");
            assertEquals(plainLog, wideLog, "变更回调的序列与键集逐字相同");
        }

        /** 越界键对基类身份的读数：钉在 {@code Long.MAX_VALUE}，不绕回也不报得比真值大。 */
        void overflowKeyStillReportsLongMaxToBaseIdentityCallers() {
            var inventory = newInventory(ignored -> {
            });
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);

            assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                    "getWaitingFor 那条读口的口径：饱和读数，绝不绕回成负数");
            assertEquals(TRIPLE_LONG_MAX.subtract(LONG_MAX), inventory.amount(PRODUCT),
                    "真值只在宽域读口上可见");

            // 一批回池最多要 Long.MAX 枚：拿得到就全拿，剩的那一截留给下一批
            assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
            assertEquals(LONG_MAX, inventory.amount(PRODUCT));
            assertFalse(inventory.hasOverflow(), "拿完这一批恰好回到 long 域");
        }

        /** 静态读口：只握基类引用的调用方也拿得到精确值，非宽域容器则按主表 long 读数。 */
        void staticAccessorReadsExactThroughBaseTypedReference() {
            var inventory = newInventory(ignored -> {
            });
            inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
            inventory.insert(PRODUCT, 1L, Actionable.MODULATE);

            ListCraftingInventory asBase = inventory;
            assertEquals(LONG_MAX.add(BigInteger.ONE), WideCraftingInventory.amount(asBase, PRODUCT));

            ListCraftingInventory plain = new ListCraftingInventory(ignored -> {
            });
            plain.insert(GEAR, 5L, Actionable.MODULATE);
            assertEquals(BigInteger.valueOf(5L), WideCraftingInventory.amount(plain, GEAR));
            assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(plain, null));
            assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(null, GEAR));
        }

        // ---- 夹具 ----

        @FunctionalInterface
        private interface VoidOp {
            void run(ListCraftingInventory inventory);
        }

        @FunctionalInterface
        private interface ReadOp {
            long run(ListCraftingInventory inventory);
        }

        /** 两边施加同一条无返回值操作，随即比对可观测面：主表全量快照 + 这一动作出的回调次数。 */
        private static void apply(ListCraftingInventory wide, ListCraftingInventory plain,
                List<String> wideLog, List<String> plainLog, VoidOp op) {
            int wideBefore = wideLog.size();
            int plainBefore = plainLog.size();
            op.run(wide);
            op.run(plain);
            assertEquals(snapshotOf(plain), snapshotOf(wide), "主表快照必须逐键逐值相同");
            assertEquals(plainLog.size() - plainBefore, wideLog.size() - wideBefore,
                    "同一条操作发出的变更回调次数必须相同");
        }

        /** 两边各施加一次同一条带返回值操作：返回值、主表快照、回调次数一起比。 */
        private static void applyAndCompare(ListCraftingInventory wide, ListCraftingInventory plain,
                List<String> wideLog, List<String> plainLog, ReadOp op) {
            int wideBefore = wideLog.size();
            int plainBefore = plainLog.size();
            long onWide = op.run(wide);
            long onPlain = op.run(plain);
            assertEquals(onPlain, onWide, "以基类身份调 extract 的返回值必须逐字相同");
            assertEquals(snapshotOf(plain), snapshotOf(wide), "主表快照必须逐键逐值相同");
            assertEquals(plainLog.size() - plainBefore, wideLog.size() - wideBefore,
                    "同一条操作发出的变更回调次数必须相同");
        }

        /** 主表全量快照：连键的存在性与 0 值条目一起比，比只比总量严得多。 */
        private static Map<String, Long> snapshotOf(ListCraftingInventory inventory) {
            Map<String, Long> snapshot = new LinkedHashMap<>();
            for (var entry : inventory.list) {
                snapshot.put(itemName(entry.getKey()), entry.getLongValue());
            }
            return snapshot;
        }

        private static String itemName(AEKey key) {
            return key instanceof AEItemKey itemKey
                    ? itemKey.getItem().getDescriptionId()
                    : String.valueOf(key);
        }

        private static CompoundTag entry(AEKey key, long amount) {
            CompoundTag tag = key.toTagGeneric(REGISTRIES);
            tag.putLong("#", amount);
            return tag;
        }

        private static int countWideEntries(ListTag tag) {
            int count = 0;
            for (int index = 0; index < tag.size(); index++) {
                if (tag.getCompound(index).contains(
                        WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                    count++;
                }
            }
            return count;
        }

        private static WideCraftingInventory newInventory(ListCraftingInventory.ChangeListener listener) {
            return new WideCraftingInventory(listener);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.accumulatedBeyondLongReadsExactlyWhileMainTableStopsAtLongMax", () -> new WideCraftingInventoryCases().accumulatedBeyondLongReadsExactlyWhileMainTableStopsAtLongMax()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.fallingBackIntoLongDomainRetiresTheSideEntry", () -> new WideCraftingInventoryCases().fallingBackIntoLongDomainRetiresTheSideEntry()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.drainingAnOverflowKeyEvictsItWholesale", () -> new WideCraftingInventoryCases().drainingAnOverflowKeyEvictsItWholesale()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.inRangeKeysGetNoSideTableAndNoWideOverrideKey", () -> new WideCraftingInventoryCases().inRangeKeysGetNoSideTableAndNoWideOverrideKey()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.wideEntriesRoundTripThroughNbtBitForBit", () -> new WideCraftingInventoryCases().wideEntriesRoundTripThroughNbtBitForBit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.legacyEntriesWithoutOverrideKeyReadBackAsMainTableLongs", () -> new WideCraftingInventoryCases().legacyEntriesWithoutOverrideKeyReadBackAsMainTableLongs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.baseIdentityCallsMatchPlainListCraftingInventoryStepByStep", () -> new WideCraftingInventoryCases().baseIdentityCallsMatchPlainListCraftingInventoryStepByStep()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.overflowKeyStillReportsLongMaxToBaseIdentityCallers", () -> new WideCraftingInventoryCases().overflowKeyStillReportsLongMaxToBaseIdentityCallers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftingInventoryCases.staticAccessorReadsExactThroughBaseTypedReference", () -> new WideCraftingInventoryCases().staticAccessorReadsExactThroughBaseTypedReference())
            );
        }
    }

    /** WideKeyCounterFootprintCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideKeyCounterFootprintCases {
        private static final int[] N_GRID = {1, 100, 10000};
        private static final int[] M_GRID = {1, 4, 16};
        private static final int BATCH_FOR_PER_INSTANCE = 10000;

        private static final AEKey[] KEYS = buildKeys();

        private static AEKey[] buildKeys() {
            Item[] items = {Items.PAPER, Items.STONE, Items.COBBLESTONE, Items.DIRT, Items.SAND,
                    Items.OAK_LOG, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.REDSTONE,
                    Items.LAPIS_LAZULI, Items.COAL, Items.GLASS, Items.BUCKET, Items.CLOCK, Items.STICK};
            AEKey[] keys = new AEKey[items.length];
            for (int i = 0; i < items.length; i++) {
                keys[i] = AEItemKey.of(new ItemStack(items[i]));
            }
            return keys;
        }

        private enum Shape {
            NOARG, SHARED, AE2
        }

        void retainedAndBuildTimeGrid() {
            List<String> lines = new ArrayList<>();
            lines.add("VM=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                    + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));
            lines.add(String.format("%-7s %-3s %8s %16s %15s %16s",
                    "shape", "M", "N", "retained_total_B", "per_instance_B", "build_us_total"));
            for (Shape shape : Shape.values()) {
                for (int m : M_GRID) {
                    long perInstance = measureRetained(shape, m, BATCH_FOR_PER_INSTANCE)
                            / BATCH_FOR_PER_INSTANCE;
                    long nsEach = measureBuildNanos(shape, m);
                    for (int n : N_GRID) {
                        long totalRetained = n == BATCH_FOR_PER_INSTANCE
                                ? measureRetained(shape, m, n)
                                : perInstance * n;
                        long buildUs = TimeUnit.NANOSECONDS.toMicros(nsEach * n);
                        lines.add(String.format("%-7s %-3d %8d %16d %15d %16d",
                                shape, m, n, totalRetained, perInstance, buildUs));
                    }
                }
            }
            String table = String.join(System.lineSeparator(), lines);
            System.out.println("== WideKeyCounter 常驻/构建度量表 ==");
            System.out.println(table);

            // 只报读数不硬断（见类注释）：形状间的倍数关系由人看表判定，绝不当闸门。
            long noarg = measureRetained(Shape.NOARG, 4, BATCH_FOR_PER_INSTANCE) / BATCH_FOR_PER_INSTANCE;
            long shared = measureRetained(Shape.SHARED, 4, BATCH_FOR_PER_INSTANCE) / BATCH_FOR_PER_INSTANCE;
            System.out.println("NOARG 每份常驻=" + noarg + "B  SHARED 每份常驻=" + shared
                    + "B  比值=" + (shared == 0L ? "n/a" : (noarg / shared))
                    + "（钉住的基准：收紧前 NOARG 3048B，收紧后 929B）");
        }

        /** 强 GC 前后堆用量差 = N 份账（含 M 个键的在册值）+ 持有数组本身的常驻字节。三轮取中位数。 */
        private static long measureRetained(Shape shape, int m, int n) {
            long[] rounds = new long[3];
            for (int r = 0; r < rounds.length; r++) {
                rounds[r] = measureRetainedOnce(shape, m, n);
            }
            java.util.Arrays.sort(rounds);
            return rounds[rounds.length / 2];
        }

        private static long measureRetainedOnce(Shape shape, int m, int n) {
            KeyIndex sharedIndex = shape == Shape.SHARED ? new KeyIndex(64) : null;
            if (shape == Shape.SHARED) {
                for (int i = 0; i < m; i++) {
                    sharedIndex.idOf(KEYS[i]);
                }
            }
            long before = settledHeapUsed();
            Object[] holders = new Object[n];
            for (int i = 0; i < n; i++) {
                holders[i] = buildCounter(shape, m, sharedIndex);
            }
            long after = settledHeapUsed();
            // 活性核对必须在 after 读数【之后】做：JVM 只保证对象活到最后一次使用，
            // holders 若在 after 读数前就已「最后使用」，编译后的帧允许 GC 回收整批，
            // 读数会假回落基线（本机实测踩过：30MB 存活批次在沉降读数中归零）。
            long liveKeys = 0L;
            for (Object h : holders) {
                liveKeys += h instanceof WideKeyCounter c
                        ? c.size()
                        : ((KeyCounter) h).size();
            }
            assertEquals((long) n * m, liveKeys, "批次必须活过 after 读数，否则测量被 GC 假象污染");
            long holderBytes = 16L + 4L * n;
            if (after <= before) {
                // 不为负 delta 抛断言：那只是 GC 在两次读数之间收了别处的垃圾，读数照常报出，人看表判定。
                System.out.println("note: " + shape + " m=" + m + " 堆用量差非正("
                        + (after - before) + "B)，本轮读数不可信");
            }
            return after - before - holderBytes;
        }

        /** 3 次独立构建取最小值，单位：每份账的纳秒。 */
        private static long measureBuildNanos(Shape shape, int m) {
            KeyIndex sharedIndex = shape == Shape.SHARED ? new KeyIndex(64) : null;
            if (shape == Shape.SHARED) {
                for (int i = 0; i < m; i++) {
                    sharedIndex.idOf(KEYS[i]);
                }
            }
            long best = Long.MAX_VALUE;
            int reps = 3;
            int n = Math.max(1000, 100000 / Math.max(1, m));
            for (int r = 0; r < reps; r++) {
                // 轮转小槽位：既防止 C2 把整轮构造当死代码消除，又只留 64 份驻留、
                // 不把耗时测成 GC 开销。
                Object[] sink = new Object[64];
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    sink[i & 63] = buildCounter(shape, m, sharedIndex);
                }
                long t1 = System.nanoTime();
                assertTrue(sink[0] != null);
                best = Math.min(best, (t1 - t0) / n);
            }
            return best;
        }

        private static Object buildCounter(Shape shape, int m, KeyIndex sharedIndex) {
            switch (shape) {
                case NOARG: {
                    var counter = new WideKeyCounter();
                    for (int j = 0; j < m; j++) {
                        counter.add(KEYS[j], j + 1L);
                    }
                    return counter;
                }
                case SHARED: {
                    var counter = new WideKeyCounter(sharedIndex);
                    for (int j = 0; j < m; j++) {
                        counter.add(KEYS[j], j + 1L);
                    }
                    return counter;
                }
                case AE2: {
                    var counter = new KeyCounter();
                    for (int j = 0; j < m; j++) {
                        counter.add(KEYS[j], j + 1L);
                    }
                    return counter;
                }
                default:
                    throw new IllegalStateException();
            }
        }

        /**
         * 沉降后的堆用量。不能用 {@code Runtime.totalMemory()-freeMemory()}：G1 下
         * freeMemory 读数会把装着存活对象的年轻代区域算成空闲（本机实测同一批 30MB
         * 存活对象，读数回落到基线）；也不能只看老年代池——System.gc 后批次可能
         * 仍整体存活于年轻代。改取全部堆池 used 之和，存活对象无论驻留哪个池都计入。
         */
        private static long settledHeapUsed() {
            long prev = -1L;
            for (int i = 0; i < 8; i++) {
                System.gc();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long used = heapPoolUsedBytes();
                // 连续两次读数差 < 256KB 才算堆已沉降；单点读数会被年轻代残垃圾污染。
                if (prev >= 0L && Math.abs(used - prev) < (256L << 10)) {
                    return used;
                }
                prev = used;
            }
            return prev;
        }

        private static long heapPoolUsedBytes() {
            long sum = 0L;
            for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                    sum += pool.getUsage().getUsed();
                }
            }
            return sum;
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterFootprintCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterFootprintCases.retainedAndBuildTimeGrid", () -> new WideKeyCounterFootprintCases().retainedAndBuildTimeGrid())
            );
        }
    }

    /** WideKeyCounterCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideKeyCounterCases {
        private static AEItemKey key(net.minecraft.world.item.Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

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

        void overflowFallingBackToLongDomainClearsSideTable() {
            var counter = new WideKeyCounter();
            var stone = key(Items.STONE);
            counter.add(stone, Long.MAX_VALUE);
            counter.add(stone, 1L);
            counter.subtract(stone, 1L);
            assertFalse(counter.hasOverflow());
            assertEquals(Long.MAX_VALUE, counter.getExact(stone).longValueExact());
        }

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

        void totalSummarizesWideAndFastKeys() {
            var counter = new WideKeyCounter();
            counter.add(key(Items.PAPER), 10L);
            counter.add(key(Items.STONE), Long.MAX_VALUE);
            counter.add(key(Items.STONE), 5L);
            var expected = PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(5L)))
                    .add(PlanAmount.of(10L));
            assertEquals(expected, counter.total());
        }

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.noOverflowKeepsSideTableEmpty", () -> new WideKeyCounterCases().noOverflowKeepsSideTableEmpty()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.overflowKeyReadsExactAndNarrowSentinel", () -> new WideKeyCounterCases().overflowKeyReadsExactAndNarrowSentinel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.overflowFallingBackToLongDomainClearsSideTable", () -> new WideKeyCounterCases().overflowFallingBackToLongDomainClearsSideTable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.crossValidatesAgainstInfinityCellStoreAtPow63", () -> new WideKeyCounterCases().crossValidatesAgainstInfinityCellStoreAtPow63()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.totalSummarizesWideAndFastKeys", () -> new WideKeyCounterCases().totalSummarizesWideAndFastKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.copyAndRestoreRoundTrip", () -> new WideKeyCounterCases().copyAndRestoreRoundTrip()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.removeAndClearDropBothTables", () -> new WideKeyCounterCases().removeAndClearDropBothTables()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.putAllMergesIncludingOverflow", () -> new WideKeyCounterCases().putAllMergesIncludingOverflow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.ae2AmountsToKeyCounterSaturatesAndScansNegatives", () -> new WideKeyCounterCases().ae2AmountsToKeyCounterSaturatesAndScansNegatives()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.ae2AmountsDetectsNegativeEntries", () -> new WideKeyCounterCases().ae2AmountsDetectsNegativeEntries()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideKeyCounterCases.idsFromOneKeyTableResolveToTheSameKeyOnEveryLedgerPairedWithIt", () -> new WideKeyCounterCases().idsFromOneKeyTableResolveToTheSameKeyOnEveryLedgerPairedWithIt())
            );
        }
    }

    /** WideMathCeilCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideMathCeilCases {
        void sharedCeilMatchesTheInlinedFormBitForBitAndSurvivesTheCeiling() {
            long[] values = {0L, 1L, 2L, 3L, 7L, 8L, 63L, 64L, 1_000L,
                    Integer.MAX_VALUE, Integer.MAX_VALUE + 1L, 1L << 60,
                    Long.MAX_VALUE - 1L, Long.MAX_VALUE};
            long[] divisors = {1L, 2L, 3L, 5L, 8L, 10L, 1_024L, 3_000_000_000_000L,
                    Long.MAX_VALUE - 1L, Long.MAX_VALUE};
            for (long value : values) {
                for (long divisor : divisors) {
                    long shared = WideMath.ceilDivExact(value, divisor);
                    assertTrue(shared >= 0L, () -> "ceil 出了负发数: " + value + "/" + divisor);
                    if (value <= Long.MAX_VALUE - (divisor - 1L)) {
                        long legacy = (value + divisor - 1L) / divisor;
                        assertEquals(legacy, shared, () -> "非回绕区间必须逐位一致: " + value + "/" + divisor);
                    }
                }
            }
            // 顶到天花板那一格：老写法 (MAX + 2 - 1) 先回绕成负数，商跟着带负号进份数账
            assertTrue((Long.MAX_VALUE + 1L) < 0L, "MAX + 1 回绕——这正是 >long 订单分轮喂下来时的形状");
            assertEquals(-4_611_686_018_427_387_904L, (Long.MAX_VALUE + 2L - 1L) / 2L,
                    "老写法在这一格算出负发数（对照）");
            assertEquals(4_611_686_018_427_387_904L, WideMath.ceilDivExact(Long.MAX_VALUE, 2L),
                    "公共实现同一格算出精确的 ceil(MAX / 2)");
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideMathCeilCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideMathCeilCases.sharedCeilMatchesTheInlinedFormBitForBitAndSurvivesTheCeiling", () -> new WideMathCeilCases().sharedCeilMatchesTheInlinedFormBitForBitAndSurvivesTheCeiling())
            );
        }
    }
}
