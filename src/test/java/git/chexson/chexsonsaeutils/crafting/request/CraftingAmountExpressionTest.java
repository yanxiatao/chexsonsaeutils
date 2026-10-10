package git.chexson.chexsonsaeutils.crafting.request;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CraftingAmountExpressionTest {
    private final CraftingAmountExpression expression = new CraftingAmountExpressionImpl();
    @Test void preservesWideIntegersAndScientificNotationThroughArithmetic() {
        assertEquals(new BigInteger("9223372036854775808"), expression.parse("9223372036854775808", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(100).subtract(BigInteger.ONE), expression.parse("1e100-1", 1).orElseThrow());
        assertEquals(new BigInteger("125000000000000000003"), expression.parse("1.25E+20+3", 1).orElseThrow());
        assertEquals(BigInteger.valueOf(10_000_000_000L), expression.parse("1e10", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(10), expression.parse("1e+10", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(10), expression.parse("1E10", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(19), expression.parse("1E19", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(20), expression.parse("1E20", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(19), expression.parse("1e19", 1).orElseThrow());
        assertEquals(BigInteger.TEN.pow(20), expression.parse("1e20", 1).orElseThrow());
        assertEquals(BigInteger.valueOf(512), expression.parse("(6/3)*2^8", 1).orElseThrow());
    }
    @Test void convertsFractionalUnitsExactlyAndRoundsLikeAe2() {
        assertEquals(BigInteger.ONE, expression.parse("1e-3", 1000).orElseThrow());
        assertEquals(BigInteger.ONE, expression.parse("0.0001", 1000).orElseThrow());
        assertEquals(BigInteger.ONE, expression.parse("1e-4", 1000).orElseThrow());
        assertEquals(new BigInteger("922337203685477580812345"),
                expression.parse("922337203685477580812.345", 1000).orElseThrow());
        assertEquals(BigInteger.ONE, expression.parse("1/3000", 1000).orElseThrow());
        assertEquals(BigInteger.TEN.pow(23), expression.parse("1e20", 1000).orElseThrow());
        assertTrue(expression.parse("1.5", 1).isEmpty());
        assertTrue(expression.parse("1e-3", 1).isEmpty());
    }
    @Test void refillPreservesSmallestUnitForWideAmountsAndNonTerminatingUnitRatios() {
        for (long unit : new long[] {1, 3, 1000, 81, Long.MAX_VALUE}) {
            for (var amount : new BigInteger[] {BigInteger.ONE, BigInteger.valueOf(7), BigInteger.TEN.pow(20).add(BigInteger.ONE)}) {
                assertEquals(amount, expression.parse(expression.format(amount, unit), unit).orElseThrow(), "unit=" + unit);
            }
        }
    }
    @Test void largestSupportedQuantityStillRefillsFractionalUnitsWithoutTruncatingTheInput() {
        var amount = BigInteger.TEN.pow(4095).add(BigInteger.ONE);
        var text = expression.format(amount, 1000);
        assertEquals(amount, expression.parse(text, 1000).orElseThrow());
    }
    @Test void keepsNativeAe2ArithmeticSemantics() {
        var format = new java.text.DecimalFormat("#.######", new java.text.DecimalFormatSymbols());
        format.setParseBigDecimal(true);
        for (var input : new String[] {"2^3^2", "(3+5)*7", "1E20-1", "1/3*3", "2^31", "1/0"}) {
            var nativeValue = appeng.client.gui.MathExpressionParser.parse(input, format);
            var expected = nativeValue.filter(value -> value.signum() > 0 && value.scale() <= 0)
                    .map(java.math.BigDecimal::toBigIntegerExact);
            assertEquals(expected, expression.parse(input, 1), input);
        }
    }
    @Test void rejectsInvalidAndUnboundedWorkWithoutThrowingIntoScreen() {
        for (var input : new String[] {"", "1e", "1e+", "1/0", "(2", "NaN", "0", "-1", "2^-1",
                "1e999999999", "2^999999999", "(-1e4000)^29^29", "1e4096", "(".repeat(200) + "1" + ")".repeat(200)})
            assertTrue(expression.parse(input, 1).isEmpty(), input);
        assertThrows(IllegalArgumentException.class, () -> expression.parse("1", 0));
        assertThrows(IllegalArgumentException.class, () -> expression.format(BigInteger.TEN.pow(4096), 1));
    }
}
