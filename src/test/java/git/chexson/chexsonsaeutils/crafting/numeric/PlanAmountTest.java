package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;

class PlanAmountTest {

    @Test
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
        assertEquals(a.subtract(b), x.subtract(y).exactValue());
        var product = a.multiply(b);
        assertEquals(product, x.multiply(y).exactValue());
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

    @Test
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

    @Test
    void smallValuesAreCachedAndFast() {
        assertSame(PlanAmount.of(5L), PlanAmount.of(5L));
        assertSame(PlanAmount.of(-16L), PlanAmount.of(-16L));
        assertTrue(PlanAmount.of(5L).isFast());
        assertEquals(5L, PlanAmount.of(5L).fastValue());
    }

    @Test
    void addAcrossLongBoundaryPromotes() {
        var result = PlanAmount.of(Long.MAX_VALUE).add(PlanAmount.ONE);
        assertFalse(result.isFast());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), result.exactValue());
        assertEquals(BigInteger.TWO.pow(63), result.exactValue());
    }

    @Test
    void wideResultFallingBackToLongDomainNormalizesToFast() {
        var wide = PlanAmount.of(BigInteger.TWO.pow(63));
        var back = wide.subtract(PlanAmount.ONE);
        assertTrue(back.isFast(), "结果回落 long 域时 wide 必须为 null");
        assertEquals(Long.MAX_VALUE, back.fastValue());
    }

    @Test
    void multiplyAcrossLongBoundaryIsExact() {
        var result = PlanAmount.of(Long.MAX_VALUE / 2L).multiply(PlanAmount.of(3L));
        assertFalse(result.isFast());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE / 2L).multiply(BigInteger.valueOf(3L)), result.exactValue());
    }

    @Test
    void minLongNegateIsExact() {
        var result = PlanAmount.of(Long.MIN_VALUE).negate();
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).negate(), result.exactValue());
        assertEquals(BigInteger.TWO.pow(63), result.exactValue());
    }

    @Test
    void compareMixedFastAndWide() {
        var wideAbove = PlanAmount.of(BigInteger.TWO.pow(63));
        // 2^63 恰为 Long.MIN_VALUE，须用 -(2^63+1) 才真正落在宽域
        var wideBelow = PlanAmount.of(BigInteger.TWO.pow(63).add(BigInteger.ONE).negate());
        assertTrue(wideAbove.compareTo(PlanAmount.of(Long.MAX_VALUE)) > 0);
        assertTrue(wideBelow.compareTo(PlanAmount.of(Long.MIN_VALUE)) < 0);
        assertEquals(0, PlanAmount.of(Long.MAX_VALUE).compareTo(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE))));
        assertTrue(PlanAmount.of(Long.MAX_VALUE).compareTo(PlanAmount.ONE) > 0);
    }

    @Test
    void ceilDivMatchesBigIntegerReferenceIncludingNegatives() {
        long[] dividends = {0L, 1L, 2L, 7L, -7L, 8L, -8L, 9L, -9L,
                Long.MAX_VALUE / 2, Long.MIN_VALUE / 2, 1L << 40};
        long[] divisors = {1L, 2L, 3L, 7L, 8L, -2L, -3L, -7L};
        for (long a : dividends) {
            for (long b : divisors) {
                var q = BigInteger.valueOf(a).divide(BigInteger.valueOf(b));
                var r = BigInteger.valueOf(a).remainder(BigInteger.valueOf(b));
                var expected = r.signum() != 0 && r.signum() == BigInteger.valueOf(b).signum()
                        ? q.add(BigInteger.ONE) : q;
                assertEquals(expected, PlanAmount.of(a).ceilDiv(PlanAmount.of(b)).exactValue(),
                        "ceilDiv(" + a + "," + b + ")");
            }
        }
    }

    @Test
    void ceilDivWideOperandsMatchReference() {
        var big = BigInteger.TWO.pow(70).add(BigInteger.valueOf(12345L));
        var dividend = PlanAmount.of(big);
        var divisor = PlanAmount.of(7L);
        var q = big.divideAndRemainder(BigInteger.valueOf(7L));
        var expected = q[1].signum() != 0 ? q[0].add(BigInteger.ONE) : q[0];
        assertEquals(expected, dividend.ceilDiv(divisor).exactValue());
    }

    @Test
    void floorDivAndRemainderMatchJavaSemantics() {
        assertEquals(-4L, PlanAmount.of(-7L).floorDiv(PlanAmount.of(2L)).fastValue());
        assertEquals(3L, PlanAmount.of(7L).floorDiv(PlanAmount.of(2L)).fastValue());
        assertEquals(-1L, PlanAmount.of(-7L).remainder(PlanAmount.of(2L)).fastValue());
        assertEquals(1L, PlanAmount.of(7L).remainder(PlanAmount.of(2L)).fastValue());
        var wide = PlanAmount.of(BigInteger.TWO.pow(70));
        assertEquals(BigInteger.TWO.pow(70).remainder(BigInteger.valueOf(3L)),
                wide.remainder(PlanAmount.of(3L)).exactValue());
    }

    @Test
    void divideTruncatesTowardZero() {
        assertEquals(-3L, PlanAmount.of(-7L).divide(PlanAmount.of(2L)).fastValue());
        assertEquals(3L, PlanAmount.of(7L).divide(PlanAmount.of(2L)).fastValue());
    }

    @Test
    void subtractAcrossLongLowerBoundaryPromotes() {
        var result = PlanAmount.of(Long.MIN_VALUE).subtract(PlanAmount.ONE);
        assertFalse(result.isFast());
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE), result.exactValue());
    }

    @Test
    void minMaxAndSignumMixed() {
        var wide = PlanAmount.of(BigInteger.TWO.pow(63));
        assertEquals(PlanAmount.of(Long.MAX_VALUE), PlanAmount.of(Long.MAX_VALUE).min(wide));
        assertEquals(wide, PlanAmount.of(Long.MAX_VALUE).max(wide));
        assertEquals(1, wide.signum());
        assertEquals(-1, wide.negate().signum());
        assertEquals(0, PlanAmount.ZERO.signum());
    }

    @Test
    void equalsAndHashCodeAcrossFastAndWide() {
        var fast = PlanAmount.of(Long.MAX_VALUE);
        var wideSame = PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE));
        assertEquals(fast, wideSame);
        assertEquals(fast.hashCode(), wideSame.hashCode());
        assertNotEquals(fast, PlanAmount.of(Long.MAX_VALUE - 1L));
    }

    @Test
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
}
