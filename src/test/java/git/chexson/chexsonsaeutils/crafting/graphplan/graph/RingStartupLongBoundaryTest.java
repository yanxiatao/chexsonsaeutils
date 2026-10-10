package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 环起步"这一轮最多能发几次"的窄化判据：与全仓同一条尺——{@code BigInteger.bitLength() <= 63}
 * 才装得进 {@code long}（{@code long} 是 64 位有符号，{@code 2^63-1} 的 bitLength 恰是 63）。
 *
 * <p>现网缺陷：{@code RingStartupPlan.fireableTimes} 旧写法用 {@code < 63}，把
 * {@code 2^62..2^63-1} 整段<b>白拒</b>——那一档的商压根不参与最小化，发数顶到
 * {@code remainingTimes} 去，上限凭空放大一整截，扣料那一腿随后按超发的量走。
 *
 * <p>两侧都钉：只断"收得下"会放走"假拒"（界画低了也照样绿），所以 {@code 2^63-1}/{@code 2^63-2}/
 * {@code 2^62} 三档断<b>要夹</b>，{@code 2^63} 与 {@code 2^126} 两档断<b>不许夹</b>。
 */
final class RingStartupLongBoundaryTest {

    private static final AEKey SEED = AEItemKey.of(Items.COAL);

    /** {@code 2^63-1}：{@code long} 顶格。 */
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /** {@code 2^63}：越出 {@code long} 的第一档。 */
    private static final BigInteger TWO_POW_63 = new BigInteger("9223372036854775808");
    /** {@code 2^62}：旧口径白拒带的下沿。 */
    private static final BigInteger TWO_POW_62 = new BigInteger("4611686018427387904");

    @Test
    void fireableCeilingInsideLongDomainClampsTheFirings() {
        assertEquals(4611686018427387904L, ceilingOf(TWO_POW_62),
                "2^62 装得进 long 就必须按它夹住——旧写法在这一档不夹，发数顶到 Long.MAX_VALUE");
        assertEquals(9223372036854775806L, ceilingOf(new BigInteger("9223372036854775806")),
                "顶格内侧 2^63-2 同样收得下、同样要夹");
        assertEquals(Long.MAX_VALUE, ceilingOf(LONG_MAX),
                "恰在 2^63-1 那一档：商本身就是上界，夹与不夹读数一致（钉的是界没被画低一档）");
    }

    @Test
    void fireableCeilingBeyondLongDomainDoesNotParticipate() {
        assertEquals(Long.MAX_VALUE, ceilingOf(TWO_POW_63),
                "2^63 越界：这一条不参与最小化，上界交回 remainingTimes，不许把越界读数当量窄化进来");
        assertEquals(Long.MAX_VALUE, ceilingOf(TWO_POW_63.multiply(TWO_POW_63)),
                "再大一档（2^126）同样不参与");
    }

    /** 每发吃 1 枚种、申报发数顶到 {@code Long.MAX_VALUE} 的一次直读。 */
    private static long ceilingOf(BigInteger availableSeed) {
        Map<AEKey, BigInteger> available = new LinkedHashMap<>();
        Map<AEKey, BigInteger> required = new LinkedHashMap<>();
        available.put(SEED, availableSeed);
        required.put(SEED, BigInteger.ONE);
        return RingStartupPlan.fireableTimes(available, required, Long.MAX_VALUE);
    }
}
