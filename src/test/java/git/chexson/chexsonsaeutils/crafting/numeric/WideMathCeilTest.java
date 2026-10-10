package git.chexson.chexsonsaeutils.crafting.numeric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.WideMath;

/**
 * {@link WideMath#ceilDivExact(long, long)} 与它替掉的那两写法逐位对拍。
 *
 * <p>本条原是 {@code graphplan/search/ResidualBestFirstPlannerTest} 里的一条：那个类自带过一份与
 * {@code ceilDivExact} 同形的取整算式，并轨时把对拍留在了求解器用例里，而它<b>不调用任何求解器</b>，
 * 判据完全落在 {@code crafting/numeric}。求解器摘掉后按迁移表把它搬回自己的维度。
 *
 * <p>并轨只在<b>逐位一致</b>时才允许：老写法的形状是
 * {@code value / divisor + (value % divisor == 0 ? 0 : 1)}（那一份私有实现），
 * 惯常的 {@code (value + divisor - 1) / divisor} 则是它等价而会回绕的兄弟。
 * 这里在两者都不回绕的全区间上逐点对拍（含 0、1、天花板与其前一位），并顺手把回绕那一格
 * 单列出来：只有公共实现活得下来。收敛本身零行为变化。
 */
final class WideMathCeilTest {

    @Test
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
}
