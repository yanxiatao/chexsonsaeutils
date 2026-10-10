package git.chexson.chexsonsaeutils.crafting.remote;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 有界元数据、精确需求和独立在途容量的账本；不饱和宽数量，不凭完成通知增加实物。 */
public final class RemoteLedgerImpl<K> implements RemoteLedger<K> {
    private final int maxKeys;
    private final long capacity;
    private final Map<K, BigInteger> pending = new LinkedHashMap<>();
    private final Map<K, BigInteger> buffer = new LinkedHashMap<>();
    private final Map<UUID, Reservation<K>> reservations = new LinkedHashMap<>();
    public RemoteLedgerImpl(int maxKeys, long capacity) {
        if (maxKeys <= 0 || capacity <= 0) throw new IllegalArgumentException("远程账本容量必须为正");
        this.maxKeys = maxKeys;
        this.capacity = capacity;
    }
    @Override public boolean enqueue(K key, BigInteger amount) {
        Objects.requireNonNull(key); Objects.requireNonNull(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("远程接单数量必须为正");
        if (!pending.containsKey(key) && distinctKeys(key) > maxKeys) return false;
        pending.merge(key, amount, BigInteger::add);
        return true;
    }
    private int distinctKeys(K key) {
        var keys = new java.util.HashSet<>(pending.keySet());
        keys.addAll(buffer.keySet());
        reservations.values().forEach(r -> keys.add(r.key()));
        keys.add(key);
        return keys.size();
    }
    private long occupied() {
        long result = 0;
        for (var amount : buffer.values()) result = Math.addExact(result, amount.longValueExact());
        for (var reservation : reservations.values()) result = Math.addExact(result, reservation.remaining());
        return result;
    }
    @Override public Map<K, BigInteger> pending() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pending)); }
    @Override public Map<K, BigInteger> buffered() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(buffer)); }
    @Override public Map<UUID, Reservation<K>> reservations() { return Map.copyOf(reservations); }
    @Override public long batch(K key, long limit) {
        if (limit <= 0) throw new IllegalArgumentException("批次上限必须为正");
        return pending.getOrDefault(key, BigInteger.ZERO).min(BigInteger.valueOf(Math.min(limit, capacity - occupied()))).longValueExact();
    }
    @Override public void submitted(UUID link, K key, long amount) {
        if (amount <= 0 || amount > batch(key, amount) || reservations.containsKey(link))
            throw new IllegalStateException("来源提交额度无效或 link 重复");
        subtract(pending, key, BigInteger.valueOf(amount));
        reservations.put(link, new Reservation<>(key, amount));
    }
    @Override public long receive(UUID link, K key, long amount, boolean simulate) {
        if (amount < 0) throw new IllegalArgumentException("来源接收数量不能为负");
        var reservation = reservations.get(link);
        if (reservation == null || !reservation.key().equals(key)) return 0;
        long accepted = Math.min(amount, reservation.remaining());
        if (!simulate && accepted > 0) {
            buffer.merge(key, BigInteger.valueOf(accepted), BigInteger::add);
            reservations.put(link, new Reservation<>(key, reservation.remaining() - accepted));
        }
        return accepted;
    }
    @Override public void close(UUID link, boolean retry) {
        var reservation = reservations.remove(link);
        if (reservation == null) throw new IllegalStateException("来源 link 未登记");
        if (retry && reservation.remaining() > 0) pending.merge(reservation.key(), BigInteger.valueOf(reservation.remaining()), BigInteger::add);
    }
    @Override public void delivered(K key, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("真实交付数量必须为正");
        subtract(buffer, key, BigInteger.valueOf(amount));
    }
    @Override public void cancelPending() { pending.clear(); }
    @Override public void movePending(K key, BigInteger amount) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("转移数量必须为正");
        subtract(pending, key, amount);
    }
    private static <K> void subtract(Map<K, BigInteger> map, K key, BigInteger amount) {
        var old = map.getOrDefault(key, BigInteger.ZERO);
        var next = old.subtract(amount);
        if (next.signum() < 0) throw new IllegalStateException("跨网账本不能透支");
        if (next.signum() == 0) map.remove(key); else map.put(key, next);
    }
    /** 正常存档重建入口，完整校验预约、实物及容量；不悄悄截断坏账。 */
    public static <K> RemoteLedgerImpl<K> restore(int maxKeys, long capacity, Map<K, BigInteger> pending,
            Map<K, BigInteger> buffer, Map<UUID, Reservation<K>> reservations) {
        var result = new RemoteLedgerImpl<K>(maxKeys, capacity);
        pending.forEach((key, amount) -> { if (!result.enqueue(key, amount)) throw new IllegalStateException("存档需求键超限"); });
        buffer.forEach((key, amount) -> {
            if (amount.signum() <= 0) throw new IllegalStateException("存档缓冲数量无效");
            result.buffer.put(Objects.requireNonNull(key), amount);
        });
        reservations.forEach((id, r) -> {
            if (r.remaining() < 0) throw new IllegalStateException("存档预约数量无效");
            result.reservations.put(Objects.requireNonNull(id), new Reservation<>(Objects.requireNonNull(r.key()), r.remaining()));
        });
        for (var key : result.buffer.keySet()) if (result.distinctKeys(key) > maxKeys) throw new IllegalStateException("存档实物键超限");
        for (var r : result.reservations.values()) if (result.distinctKeys(r.key()) > maxKeys) throw new IllegalStateException("存档预约键超限");
        if (result.occupied() > capacity) throw new IllegalStateException("存档缓冲超出预约容量");
        return result;
    }
}
