package git.chexson.chexsonsaeutils.bigint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K2 尺本体的钉子：{@link ClampedMath} 两档三方法的极性与重载档位。
 *
 * <p>一条判据只判一件事：正向顶、负向底、只夹上界档的"负向如实相加"、以及重载漂移。
 * 期望值全部是数域边界本身，不是业务配额。
 */
final class ClampedMathTest {

    @Test
    void addClampsUpwardAtLongDomainTop() {
        assertEquals(Long.MAX_VALUE, ClampedMath.add(Long.MAX_VALUE - 1L, 5L));
        assertEquals(Long.MAX_VALUE, ClampedMath.add(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(7L, ClampedMath.add(2L, 5L));
    }

    @Test
    void addClampsDownwardAtLongDomainBottom() {
        assertEquals(Long.MIN_VALUE, ClampedMath.add(Long.MIN_VALUE + 1L, -5L));
        assertEquals(Long.MIN_VALUE, ClampedMath.add(Long.MIN_VALUE, Long.MIN_VALUE));
        assertEquals(-7L, ClampedMath.add(-2L, -5L));
    }

    @Test
    void addUpClampsTopOnlyInLongDomain() {
        assertEquals(Long.MAX_VALUE, ClampedMath.addUp(Long.MAX_VALUE - 1L, 5L));
        assertEquals(Long.MAX_VALUE, ClampedMath.addUp(Long.MAX_VALUE, 1L));
        assertNotEquals(Long.MAX_VALUE, ClampedMath.addUp(0L, Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, ClampedMath.addUp(0L, Long.MIN_VALUE));
    }

    /**
     * 只夹上界档的负方向"如实相加"：溢出点与现网单向档逐字一致，MIN 再减 1 就是回绕到 MAX，
     * 本条把回绕钉成期望值，防止后续有人顺手给它补下界。
     */
    @Test
    void addUpKeepsLongWrapAroundOnNegativeOverflow() {
        assertEquals(Long.MAX_VALUE, ClampedMath.addUp(Long.MIN_VALUE, -1L));
        assertEquals(Long.MAX_VALUE, ClampedMath.addUp(-1L, Long.MIN_VALUE));
    }

    /**
     * 重载漂移钉子：两个 int 实参只会走 int 档，夹在 Integer.MAX_VALUE 而不是 Long.MAX_VALUE。
     * K3 删掉 {@code add(int,int)}（产品调用者 0）后，int 档只剩 {@code addUp}，本条随之后移。
     */
    @Test
    void intArgsStayInIntDomainCeiling() {
        int sumUp = ClampedMath.addUp(Integer.MAX_VALUE - 1, 5);
        assertEquals(Integer.MAX_VALUE, sumUp);
        assertNotEquals(Long.MAX_VALUE, (long) sumUp);
    }

    /**
     * int 档只夹上界：{@code Integer.MIN_VALUE} 再减 1 就是回绕到 {@code Integer.MAX_VALUE}，
     * 与 long 档同一条纪律（不许顺手补下界）。
     */
    @Test
    void intArgsKeepIntDomainWrapOnNegativeOverflow() {
        assertEquals(Integer.MAX_VALUE, ClampedMath.addUp(Integer.MIN_VALUE, -1));
    }

    /**
     * 混合实参（一侧 long）明确走 long 档：和能越过 Integer.MAX_VALUE，说明没被 int 档夹住。
     */
    @Test
    void mixedArgsResolveToLongDomain() {
        long widened = ClampedMath.add(Integer.MAX_VALUE - 1, 5L);
        assertTrue(widened > Integer.MAX_VALUE, "mixed args must resolve to the long overload");
        assertEquals(2147483651L, widened);
        long widenedUp = ClampedMath.addUp(Integer.MAX_VALUE - 1, 5L);
        assertEquals(2147483651L, widenedUp);
    }
}
