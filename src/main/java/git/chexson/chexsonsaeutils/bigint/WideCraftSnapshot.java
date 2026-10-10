package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;

/**
 * 一份计划出账时刻的<b>份数</b>宽域快照：只保留发数越过 {@code long} 上限的样板。
 *
 * <p>与 {@link WideLedgerSnapshot} 同一套取舍：能塞 long 的发数由 AE2 自己的
 * {@code patternTimes()}（{@code Map<IPatternDetails, Long>}）如实表达，无需旁路；
 * 只有会被饱和成 {@code Long.MAX_VALUE} 的那批样板才进这里，读回规则是
 * 「快照里有真值就用真值，否则用 {@code patternTimes()} 的 long」。
 *
 * <p>为什么非要有旁路：AE2 的 {@code patternTimes} 字段类型是 {@code long}，改不动；
 * 而任务表要能记、能执行超过 {@code long} 的发数（请求 {@code 1e19} 枚、每发产 1 枚时
 * 份数就是 {@code 1e19}）。饱和值只是<b>代表值</b>，真值必须随计划对象一起出门，
 * 由下游按样板逐个交割，绝不能在规划侧被饱和数字反喂成"这就是这么多发"。
 *
 * <p>不可变：构造后只读，不持有任何账本实例。只活在内存里——随计划对象
 * （{@code WideAmountPlanCarrier}）产生与回收，<b>不进 NBT 与存档</b>；
 * 份数上线协议（任务表宽域与份数声明）属下一赛道，本快照只提供规划侧真值。
 */
public final class WideCraftSnapshot {

    private static final WideCraftSnapshot EMPTY = new WideCraftSnapshot(Map.of());

    private final Map<IPatternDetails, BigInteger> overLongTimes;

    private WideCraftSnapshot(Map<IPatternDetails, BigInteger> overLongTimes) {
        this.overLongTimes = overLongTimes;
    }

    public static WideCraftSnapshot empty() {
        return EMPTY;
    }

    /** 从份数宽账抓快照：只留超 {@code long} 的发数；一张都没有时返回 {@link #empty()}。 */
    public static WideCraftSnapshot capture(WideCraftCounter crafts) {
        if (crafts == null || !crafts.hasOverflow()) {
            // 常态：没有样板越界，一次位标记判断即返回，不遍历、不分配
            return EMPTY;
        }
        Map<IPatternDetails, BigInteger> captured = null;
        for (IPatternDetails pattern : crafts.keys()) {
            var times = crafts.getAmount(pattern);
            if (!times.isFast()) {
                if (captured == null) {
                    captured = new HashMap<>();
                }
                captured.put(pattern, times.exactValue());
            }
        }
        return captured == null ? EMPTY : new WideCraftSnapshot(Map.copyOf(captured));
    }

    public boolean isEmpty() {
        return this.overLongTimes.isEmpty();
    }

    public int size() {
        return this.overLongTimes.size();
    }

    /** 超界发数表（只读，可能为空）；键的等值语义与 {@code patternTimes()} 一致。 */
    public Map<IPatternDetails, BigInteger> entries() {
        return this.overLongTimes;
    }

    /** 该样板的宽域真值；没有超界记录时返回 {@code null}（表示 AE2 的 long 就是真值）。 */
    @Nullable
    public BigInteger get(IPatternDetails pattern) {
        return this.overLongTimes.get(pattern);
    }

    @Override
    public String toString() {
        return this.overLongTimes.isEmpty()
                ? "WideCraftSnapshot[empty]"
                : "WideCraftSnapshot" + this.overLongTimes;
    }
}
