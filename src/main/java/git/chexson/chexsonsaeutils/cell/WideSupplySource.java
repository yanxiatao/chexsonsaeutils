package git.chexson.chexsonsaeutils.cell;

import java.math.BigInteger;

import appeng.api.stacks.AEKey;

/**
 * 供给侧的「宽域真值」只读旁路口。
 *
 * <p>存在理由：AE2 的整条计数口径是 {@code long}（{@code MEStorage.insert/extract} 的
 * {@code long amount}、{@code KeyCounter} 的 {@code Object2LongMap}），因此我们无限磁盘
 * 对网络只能把溢出键报成 {@code Long.MAX_VALUE}（{@code InfinityCellStore.CellData}
 * 的 {@code visibleAmount}）。
 * 那是 AE2 接口的既有事实，动它等于改所有 mod 看到的网络读数，影响面太大，本 mod 不动。
 *
 * <p>真正需要 "> long 那一截" 的只有规划侧的供给判据，所以真值由本接口<b>旁路</b>供给：
 * 纯读、零副作用，不改写入/提取/网络聚合的任何口径，也不改变网络对其他 mod 的读数。
 * 实现方必须保证：{@link #reportedAmount(AEKey)} 就是它对网络报出的那份 {@code long} 读数，
 * 调用方据此把「网络读数里属于本供给源的一截」换成 {@link #exactAmount(AEKey)} 真值。
 */
public interface WideSupplySource {

    /** 该键在本供给源里的真值存量（可 &gt; {@code long}）。只读，零副作用。 */
    BigInteger exactAmount(AEKey key);

    /** 该键按 AE2 {@code long} 口径报给网络的读数（溢出键即饱和值）。 */
    long reportedAmount(AEKey key);
}
