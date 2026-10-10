package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.me.cells.BasicCellInventory;
import net.minecraft.server.MinecraftServer;
import java.math.BigInteger;
import java.util.*;

/** 每个贡献独立读数后宽域求和；可扩展发现/适配器由装配层注入。 */
public final class TerminalInventoryReaderImpl implements TerminalInventoryReader {
    private final List<StorageMountDiscovery> discoveries;
    private final List<ExactStorageAdapter> adapters;
    private final NetworkExactInventory networkExact;
    public TerminalInventoryReaderImpl(List<StorageMountDiscovery> discoveries, List<ExactStorageAdapter> adapters) {
        this(discoveries, adapters, null);
    }
    public TerminalInventoryReaderImpl(List<StorageMountDiscovery> discoveries, List<ExactStorageAdapter> adapters,
            NetworkExactInventory networkExact) {
        this.discoveries = List.copyOf(discoveries);
        this.adapters = List.copyOf(adapters);
        this.networkExact = networkExact;
    }
    @Override public TerminalInventorySnapshot read(IGrid grid, MinecraftServer server) {
        var mounts = new LinkedHashMap<Object, StorageMount>();
        boolean sourcesLocated = true;
        for (var node : grid.getNodes()) {
            if (!node.isOnline()) continue;
            var matches = discoveries.stream().filter(d -> d.supports(node)).toList();
            if (matches.size() > 1) throw new IllegalStateException("存储主机发现重复注册");
            if (matches.isEmpty()) {
                if (node.getService(IStorageProvider.class) != null && (networkExact == null || !networkExact.covers(node))) sourcesLocated = false;
            } else for (var mount : matches.getFirst().discover(node)) {
                var prior = mounts.putIfAbsent(identity(mount), mount);
                if (prior != null && prior.visible() != mount.visible())
                    throw new IllegalStateException("同一存储身份出现在不同挂载，无法证明唯一贡献");
            }
        }
        var totals = networkExact == null ? aggregate(mounts.values(), server)
                : reconcile(networkExact.read(grid), mounts.values(), server);
        var unknownKeys = new HashSet<AEKey>();
        for (var mount : mounts.values()) {
            if (!(mount.original() instanceof BasicCellInventory) && adapters.stream().noneMatch(a -> a.supports(mount.original()))) {
                for (var entry : mount.visible().getAvailableStacks()) {
                    if (entry.getLongValue() != 0) unknownKeys.add(entry.getKey());
                }
            }
        }
        var known = networkExact == null ? totals : aggregate(mounts.values(), server);
        return confirmReadings(totals, known, grid.getStorageService().getCachedInventory(), unknownKeys,
                sourcesLocated, grid.getCraftingService().getCraftables(key -> true));
    }
    /**
     * 按键确认数量，避免一个未知来源污染整个网络的条目。
     * totals 是逐挂载采集并校正后的和，known 是独立适配确认的和；二者相等证明该键没有额外贡献。
     * 该证明只允许使用网络宽域采集，不能对饱和的 AE2 long 总量做相等判断。
     * cached 保留未确认时的原生读数；unknownKeys 限定已发现但未适配来源影响的键。
     * sourcesLocated 表示节点来源均已定位，craftables 保留原版合成标记。
     */
    public TerminalInventorySnapshot confirmReadings(Map<AEKey, StorageAmount> totals,
            Map<AEKey, StorageAmount> known, KeyCounter cached, Set<AEKey> unknownKeys,
            boolean sourcesLocated, Set<AEKey> craftables) {
        var amounts = new HashMap<>(totals);
        var unverified = new HashMap<AEKey, Long>();
        var keys = new HashSet<>(totals.keySet());
        keys.addAll(cached.keySet());
        for (var key : keys) {
            var independent = known.get(key);
            boolean proven = independent != null && (independent.unbounded()
                    || (networkExact != null && independent.equals(totals.get(key))));
            if (proven || (sourcesLocated && !unknownKeys.contains(key) && totals.containsKey(key))) continue;
            unverified.put(key, cached.get(key));
            if (independent != null) amounts.put(key, independent); else amounts.remove(key);
        }
        return new TerminalInventorySnapshot(amounts, unverified, craftables,
                sourcesLocated && unknownKeys.isEmpty());
    }
    /** 基线为逐挂载 BigInteger 精确和，不是已丢失信息的网络 long 总量。 */
    public Map<AEKey, StorageAmount> reconcile(Map<AEKey, StorageAmount> baseline,
            Collection<StorageMount> mounts, MinecraftServer server) {
        var result = new HashMap<>(baseline);
        var seen = new HashSet<Object>();
        for (var mount : mounts) {
            if (!seen.add(identity(mount))) continue;
            var matches = adapters.stream().filter(a -> a.supports(mount.original())).toList();
            if (matches.size() > 1) throw new IllegalStateException("精确数量适配重复注册");
            if (matches.isEmpty() || (networkExact != null && networkExact.observesExactAmounts(mount.visible()))) continue;
            var exact = matches.getFirst().read(mount.original(), server);
            var visible = new KeyCounter();
            mount.visible().getAvailableStacks(visible);
            for (var entry : visible) {
                var prior = result.get(entry.getKey());
                var replacement = Objects.requireNonNull(exact.get(entry.getKey()), "精确快照缺少可见键");
                verifyVisible(entry.getLongValue(), replacement);
                if (prior == null) throw new IllegalStateException("实际网络采集缺少已挂载单元");
                if (replacement.unbounded()) result.put(entry.getKey(), StorageAmount.infinite());
                else if (!prior.unbounded()) result.put(entry.getKey(), StorageAmount.finite(prior.value()
                        .subtract(BigInteger.valueOf(entry.getLongValue())).add(replacement.value())));
            }
        }
        return Map.copyOf(result);
    }
    /** 独立来源汇总入口，测试直接调用同一行为，避免源码形状断言。 */
    public Map<AEKey, StorageAmount> aggregate(Collection<StorageMount> mounts, MinecraftServer server) {
        var result = new HashMap<AEKey, StorageAmount>();
        var seen = new HashSet<Object>();
        for (var mount : mounts) {
            if (!seen.add(identity(mount))) continue;
            var visible = new KeyCounter();
            mount.visible().getAvailableStacks(visible);
            var matches = adapters.stream().filter(a -> a.supports(mount.original())).toList();
            if (matches.size() > 1) throw new IllegalStateException("精确数量适配重复注册");
            Map<AEKey, StorageAmount> exact = matches.isEmpty() ? Map.of() : matches.getFirst().read(mount.original(), server);
            for (var entry : visible) {
                var key = entry.getKey();
                var value = exact.get(key);
                if (value == null) {
                    if (!matches.isEmpty()) throw new IllegalStateException("可见库存未包含于精确快照");
                    if (!(mount.original() instanceof BasicCellInventory)) continue;
                    if (entry.getLongValue() < 0) throw new IllegalStateException("单来源库存回绕");
                    value = StorageAmount.finite(BigInteger.valueOf(entry.getLongValue()));
                }
                if (!matches.isEmpty()) verifyVisible(entry.getLongValue(), value);
                if (value.unbounded() || value.value().signum() > 0) result.merge(key, value, StorageAmount::add);
            }
        }
        return Map.copyOf(result);
    }
    private Object identity(StorageMount mount) {
        var matches = adapters.stream().filter(a -> a.supports(mount.original())).toList();
        if (matches.size() > 1) throw new IllegalStateException("精确数量适配重复注册");
        return matches.isEmpty() ? mount.identity() : Objects.requireNonNull(matches.getFirst().identity(mount));
    }
    private static void verifyVisible(long visible, StorageAmount exact) {
        long expected = exact.unbounded() ? Long.MAX_VALUE
                : exact.value().min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        if (visible != expected) throw new IllegalStateException("挂载改变了数量，精确适配无法证明可见贡献");
    }

}
