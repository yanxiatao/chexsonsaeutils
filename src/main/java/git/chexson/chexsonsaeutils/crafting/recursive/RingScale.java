package git.chexson.chexsonsaeutils.crafting.recursive;

import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;

/**
 * 环 scale 双轨载体：宽域整数闭式与 legacy double 链的分支收敛在
 * {@link WideMath}（legacy 位模式为先除后乘的原位顺序）。
 */
public record RingScale(long total, long divisor) {

    public static RingScale of(long total, long divisor) {
        return new RingScale(total, divisor);
    }

    public long ceilScaled(long amount) {
        return WideMath.ceilMulDiv(amount, total, divisor);
    }

    public long floorScaled(long amount) {
        return WideMath.floorMulDiv(amount, total, divisor);
    }
}
