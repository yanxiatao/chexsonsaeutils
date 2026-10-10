package git.chexson.chexsonsaeutils.bigint;

import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 一份计划出账时刻的宽域快照：只保留<b>超过 {@code long} 上限</b>的键。
 *
 * <p>能塞 long 的键在确认表里由 AE2 自己按 long 如实显示，无需旁路；只有会被
 * {@code Ae2Amounts.toLong} 饱和成 {@code Long.MAX_VALUE} 的那批键才进这里。
 * 三类（{@link WideAmountKind}）各一张 {@code AEKey -> BigInteger} 表，空类不建表。
 *
 * <p>不可变：构造后只读。不持有任何 {@code WideKeyCounter}、player 或 level 引用。
 * 只活在内存里：随计划对象（{@code WideAmountPlanCarrier}）产生与回收，不进 NBT 与存档。
 */
public final class WideLedgerSnapshot {


    private final Map<WideAmountKind, Map<AEKey, BigInteger>> byKind;

    private WideLedgerSnapshot(Map<WideAmountKind, Map<AEKey, BigInteger>> byKind) {
        this.byKind = byKind;
    }

    public static WideLedgerSnapshot empty() {
        return new WideLedgerSnapshot(Map.of());
    }

    /**
     * 由解码结果组装快照（同包工厂，供 {@link WidePlanLedgerCodec#decode} 回填）。
     * 传入的表会被逐类深拷贝，调用方可继续复用。
     */
    static WideLedgerSnapshot fromDecoded(Map<WideAmountKind, Map<AEKey, BigInteger>> decoded) {
        if (decoded == null || decoded.isEmpty()) {
            return empty();
        }
        EnumMap<WideAmountKind, Map<AEKey, BigInteger>> copy = new EnumMap<>(WideAmountKind.class);
        for (Map.Entry<WideAmountKind, Map<AEKey, BigInteger>> entry : decoded.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        return copy.isEmpty() ? empty() : new WideLedgerSnapshot(Map.copyOf(copy));
    }

    public boolean isEmpty() {
        return this.byKind.isEmpty();
    }

    /** 某类下的溢出键表（只读，可能为空）。 */
    public Map<AEKey, BigInteger> entries(WideAmountKind kind) {
        return this.byKind.getOrDefault(kind, Map.of());
    }

    /** 取某键某类的宽域真值；无溢出记录返回 {@code null}（表示走 AE2 的 long 显示）。 */
    @Nullable
    public BigInteger get(WideAmountKind kind, AEKey key) {
        Map<AEKey, BigInteger> map = this.byKind.get(kind);
        return map == null ? null : map.get(key);
    }

    /**
     * 从三张宽域账本抓取快照：逐类只留绝对值超过 {@code long} 的键。
     * 任一账本为空或无溢出键时该类不建表。
     */
    public static WideLedgerSnapshot capture(@Nullable WideKeyCounter missing,
            @Nullable WideKeyCounter used, @Nullable WideKeyCounter emitted) {
        EnumMap<WideAmountKind, Map<AEKey, BigInteger>> captured = new EnumMap<>(WideAmountKind.class);
        captureKind(captured, WideAmountKind.MISSING, missing);
        captureKind(captured, WideAmountKind.USED, used);
        captureKind(captured, WideAmountKind.EMIT, emitted);
        return captured.isEmpty() ? empty() : new WideLedgerSnapshot(Map.copyOf(captured));
    }

    @Override
    public String toString() {
        if (byKind.isEmpty()) {
            return "WideLedgerSnapshot[empty]";
        }
        StringBuilder out = new StringBuilder("WideLedgerSnapshot{");
        boolean first = true;
        for (WideAmountKind kind : WideAmountKind.values()) {
            Map<AEKey, BigInteger> entries = this.byKind.get(kind);
            if (entries == null) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(kind.wireToken()).append('=').append(entries.size()).append(" keys");
        }
        return out.append("}").toString();
    }

    private static void captureKind(EnumMap<WideAmountKind, Map<AEKey, BigInteger>> target,
            WideAmountKind kind, @Nullable WideKeyCounter counter) {
        if (counter == null || counter.isEmpty()) {
            return;
        }
        Map<AEKey, BigInteger> overflow = null;
        for (AEKey key : counter.keys()) {
            BigInteger exact = counter.getExact(key);
            if (exact.abs().compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
                if (overflow == null) {
                    overflow = new HashMap<>();
                }
                overflow.put(key, exact);
            }
        }
        if (overflow != null) {
            target.put(kind, Map.copyOf(overflow));
        }
    }
}
