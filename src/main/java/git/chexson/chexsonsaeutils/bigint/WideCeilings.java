package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;

/**
 * 数域天花板的唯一命名点（K3）。
 *
 * <p>{@link #LONG_ACCOUNT} 是全仓唯一一份"把 {@code Long.MAX_VALUE} 造成长整数域外的宽数"的构造。
 * 之前它在 17 个宿主文件里以 12 个不同字段名各造一遍（{@code LONG_MAX}×9、
 * {@code LONG_MAX_CEILING}、{@code oneChunk}、{@code longCeiling}，另有两处裸全限定名内联），
 * 同一道顶被写成一堆同义不同名的常量，读代码的人分不清哪一道是数域顶、哪一道是业务配额。
 *
 * <p><b>这是数域顶，不是业务顶。</b>它的值由 {@code long} 这个数域决定，与任何一台机器、
 * 任何一个槽位、任何一份配额无关。业务上界——每发件数、本轮可交量、池子容量、tick 预算——
 * 各有各的顶，一概不许挂到这个名字上：一旦挂上，"越过它就越出 {@code long} 可表达范围"
 * 这条硬事实就会被业务的涨跌污染，反过来也污染它。
 *
 * <p>因此本类只有一个常量、没有方法、没有开关、不做判据：它是命名的收敛点，不是行为入口。
 * 等式判据（"这个读数是不是正好顶在数域顶上"）用它写，夹取动作本身走
 * {@link ClampedMath}，宽域算术走 {@link PlanAmount}。
 */
public final class WideCeilings {

    /**
     * 数域顶：{@code long} 能表达的最大非负数。
     * 顶在这里的是"还装不装得进 {@code long}"，不是"业务允许多少"。
     */
    public static final BigInteger LONG_ACCOUNT = BigInteger.valueOf(Long.MAX_VALUE);

    private WideCeilings() {
    }
}
