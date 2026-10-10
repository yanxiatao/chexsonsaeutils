package git.chexson.chexsonsaeutils.mattermassstore;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.serialization.Codec;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物质团内容物外部持久化存储（仿 {@link InfinityCellStore} 的世界数据文件模式）。
 * <p>
 * 动机：物质团的 AEItemKey 只含 CUSTOM_NAME + 物质团 UUID（内容物不进组件，
 * 否则样板上报时预分配的 key 与实际产物 key 不一致，CPU 回流匹配失败）。
 * 内容物按物质团 UUID 存于 {@code <world>/data/chexsonsaeutils/matter_mass/<uuid>.nbt}，
 * 同 UUID 的多张物质团物品共享同一份内容物（聚合/多次推送的天然合并语义）。
 * 条目清空即删除内存缓存与磁盘文件（"内容物清空后物质团消失"的存储侧基础）。
 */
public final class MatterMassStore {

    private static final Logger LOG = LoggerFactory.getLogger(MatterMassStore.class);
    private static final int FORMAT_VERSION = 1;
    private static final String DIR = "data/chexsonsaeutils/matter_mass";
    private static final Codec<List<GenericStack>> CONTENTS_CODEC = GenericStack.CODEC.listOf();
    /**
     * 溢出格的真值落盘键：沿用 {@link InfinityCellStore} 条目的 {@code bigAmount} 键名与
     * "BigInteger 补码全量字节"这一种落法（那份是二选一键、这里多挂在饱和 {@code amt} 位旁边，
     * 因为 {@code GenericStack.CODEC} 只认 {@code amt} 那一个键），不新造第 8 套格式。
     */
    private static final String NBT_ENTRY_BIG_AMOUNT = "bigAmount";
    private static final MatterMassStore GLOBAL = new MatterMassStore();

    private final Map<UUID, List<GenericStack>> entries = new HashMap<>();
    /**
     * 越出 {@code long} 那一格的<b>精确真值</b>侧表（按物质团 UUID 分本）。
     *
     * <p>{@link #entries} 那份是网络读得到的饱和 {@code long} 视图（溢出格恒为
     * {@code Long.MAX_VALUE}）；这一本才是本数，与内容物同进同出、一起落盘。
     * 范式与落盘键位照同包的 {@link InfinityCellStore}（{@code overflowAmounts} +
     * {@code bigAmount} 补码字节），不新造第 8 套格式。
     */
    private final Map<UUID, Map<AEKey, BigInteger>> overflow = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    @Nullable
    private Path currentRoot;
    private boolean currentRootLoaded;

    private MatterMassStore() {
    }

    public static MatterMassStore global() {
        return GLOBAL;
    }

    /** @return 内容物副本（永不返回 null）；无条目返回空列表 */
    public synchronized List<GenericStack> getContents(UUID id) {
        var contents = entries.get(id);
        return contents == null ? List.of() : List.copyOf(contents);
    }

    public synchronized boolean isEmpty(UUID id) {
        var contents = entries.get(id);
        return contents == null || contents.isEmpty();
    }

    /**
     * 追加内容物（同 key 合并求和，保持首次出现顺序）。
     * 用于机器吞料入团与外部回填。
     */
    public synchronized void append(UUID id, List<GenericStack> stacks) {
        if (stacks.isEmpty()) {
            return;
        }
        appendWithExactDeltas(id, stacks, null);
    }

    /**
     * 合并这一本账：{@code long} 主表给出网络视图，越界那一格的真值进 {@link #overflow} 侧表。
     *
     * <p>老写法的 {@code overflow} 是**方法内局部变量**，而 {@code GenericStack.CODEC} 只认
     * {@code long} ⇒ 夹顶之后侧表就被丢弃，append 一次丢一次。现在侧表是字段、随条目一起落盘，
     * 且重开这一本账时先读侧表真值、不把饱和读数 {@code Long.MAX_VALUE} 当本数再加一次。
     *
     * @param exactBy 按键交回的真值增量（{@code null} 表示这份 {@code stacks} 就是本数）；
     *                合并另一团内容物时用它把越界那一截一起搬过来
     */
    private void appendWithExactDeltas(UUID id, List<GenericStack> stacks, Map<AEKey, BigInteger> exactBy) {
        var contents = entries.computeIfAbsent(id, ignored -> new ArrayList<>());
        // long 主表 + BigInteger 溢出侧表合并：Long::sum 回绕会产生负数垃圾被求解器读走
        var merged = new LinkedHashMap<AEKey, Long>();
        var overflowed = new HashMap<AEKey, BigInteger>();
        var carried = overflow.get(id);
        for (var stack : contents) {
            var carriedExact = carried == null ? null : carried.get(stack.what());
            if (carriedExact != null) {
                // 侧表里那一截才是本数；老 long 位只是它的饱和读数，不许当本数再进账
                overflowed.put(stack.what(), carriedExact);
                merged.put(stack.what(), Long.MAX_VALUE);
            } else {
                mergeAmount(merged, overflowed, stack.what(), stack.amount());
            }
        }
        for (var stack : stacks) {
            if (stack == null) {
                continue;
            }
            var exact = exactBy == null ? null : exactBy.get(stack.what());
            if (exact != null) {
                mergeBigIntegerAmount(merged, overflowed, stack.what(), exact);
            } else if (stack.amount() > 0) {
                mergeAmount(merged, overflowed, stack.what(), stack.amount());
            }
        }
        contents.clear();
        for (var entry : merged.entrySet()) {
            var exact = overflowed.get(entry.getKey());
            if (exact != null) {
                overflowTable(id).put(entry.getKey(), exact);
                LOG.warn("Matter mass amount for {} exceeds long domain: network view clamped to "
                        + "Long.MAX_VALUE, wide truth kept in the overflow ledger", entry.getKey());
                contents.add(new GenericStack(entry.getKey(), Long.MAX_VALUE));
            } else {
                contents.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        if (carried != null) {
            // 落回 long 域的键要把侧表条目撤下，否则旧真值会盖住新账
            carried.keySet().removeIf(key -> !overflowed.containsKey(key));
            if (carried.isEmpty()) {
                overflow.remove(id);
            }
        }
        dirty.add(id);
    }

    private Map<AEKey, BigInteger> overflowTable(UUID id) {
        return overflow.computeIfAbsent(id, ignored -> new HashMap<>());
    }

    /** 这一格的本数：侧表有真值就交回真值，否则逐字是 {@code long} 视图那一份。 */
    private BigInteger exactAmountOf(UUID id, AEKey what, long saturatedView) {
        var carried = overflow.get(id);
        var exact = carried == null ? null : carried.get(what);
        return exact != null ? exact : BigInteger.valueOf(saturatedView);
    }

    private static void mergeBigIntegerAmount(
            LinkedHashMap<AEKey, Long> merged,
            HashMap<AEKey, BigInteger> overflowed,
            AEKey key,
            BigInteger delta
    ) {
        var existingOverflow = overflowed.get(key);
        if (existingOverflow != null) {
            overflowed.put(key, existingOverflow.add(delta));
            return;
        }
        long current = merged.getOrDefault(key, 0L);
        var updated = BigInteger.valueOf(current).add(delta);
        if (updated.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
            overflowed.put(key, updated);
            merged.put(key, Long.MAX_VALUE);
            return;
        }
        merged.put(key, updated.longValueExact());
    }

    private static void mergeAmount(
            LinkedHashMap<AEKey, Long> merged,
            HashMap<AEKey, BigInteger> overflowed,
            AEKey key,
            long delta
    ) {
        var existingOverflow = overflowed.get(key);
        if (existingOverflow != null) {
            overflowed.put(key, existingOverflow.add(BigInteger.valueOf(delta)));
            return;
        }
        long current = merged.getOrDefault(key, 0L);
        long updated;
        try {
            updated = Math.addExact(current, delta);
        } catch (ArithmeticException overflowedNow) {
            overflowed.put(key, BigInteger.valueOf(current).add(BigInteger.valueOf(delta)));
            merged.put(key, Long.MAX_VALUE);
            return;
        }
        merged.put(key, updated);
    }

    /** @return 实际提取量；MODULATE 下条目抽空时自动删除条目与文件 */
    public synchronized long extract(UUID id, AEKey what, long amount, Actionable mode) {
        if (amount <= 0) {
            return 0;
        }
        var contents = entries.get(id);
        if (contents == null) {
            return 0;
        }
        long extracted = 0;
        for (int i = 0; i < contents.size(); i++) {
            var stack = contents.get(i);
            if (!stack.what().equals(what)) {
                continue;
            }
            // 本数在侧表里：按饱和读数 longValueExact 之外的那一截扣，扣不满的那部分才是取不到
            var available = exactAmountOf(id, what, stack.amount());
            var taken = available.min(BigInteger.valueOf(amount));
            extracted = taken.longValueExact();
            if (extracted > 0 && mode == Actionable.MODULATE) {
                var remaining = available.subtract(taken);
                var carried = overflow.get(id);
                if (remaining.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
                    overflowTable(id).put(what, remaining);
                    contents.set(i, new GenericStack(what, Long.MAX_VALUE));
                } else {
                    if (carried != null) {
                        carried.remove(what);
                        if (carried.isEmpty()) {
                            overflow.remove(id);
                        }
                    }
                    long left = remaining.longValueExact();
                    if (left > 0) {
                        contents.set(i, new GenericStack(what, left));
                    } else {
                        contents.remove(i);
                    }
                }
                afterMutated(id);
            }
            break;
        }
        return extracted;
    }

    /** @return 这一格的本数（侧表真值优先，缺条目即零）；{@code InfinityCellStore.exactAmount} 同一条口径。 */
    synchronized BigInteger exactAmount(UUID id, AEKey what) {
        var contents = entries.get(id);
        if (contents == null) {
            return BigInteger.ZERO;
        }
        for (var stack : contents) {
            if (stack.what().equals(what)) {
                return exactAmountOf(id, what, stack.amount());
            }
        }
        return BigInteger.ZERO;
    }

    /**
     * 取走源团全部内容物并入目标团，源团条目与文件随之删除。
     *
     * <p>老写法是 {@code append(target, takeAll(source))}：{@code takeAll} 交回的是饱和
     * {@code long} 视图，源团那一截越界的本数在合并这一刀就丢了——与本条要点名的
     * "append 一次丢一次"是同一条腿的两半。
     */
    public synchronized void transferAll(UUID target, UUID source) {
        if (target.equals(source)) {
            return;
        }
        var contents = entries.get(source);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        var exactBy = new LinkedHashMap<AEKey, BigInteger>();
        for (var stack : contents) {
            exactBy.merge(stack.what(), exactAmountOf(source, stack.what(), stack.amount()), BigInteger::add);
        }
        var moved = new ArrayList<GenericStack>(exactBy.size());
        for (var entry : exactBy.entrySet()) {
            var exact = entry.getValue();
            boolean insideLong = exact.bitLength() <= 63;
            moved.add(new GenericStack(entry.getKey(), insideLong ? exact.longValueExact() : Long.MAX_VALUE));
        }
        entries.remove(source);
        overflow.remove(source);
        dirty.add(source);
        appendWithExactDeltas(target, moved, exactBy);
    }

    public synchronized void getAvailableStacks(UUID id, KeyCounter out) {
        var contents = entries.get(id);
        if (contents == null) {
            return;
        }
        for (var stack : contents) {
            out.add(stack.what(), stack.amount());
        }
    }

    public synchronized int typeCount(UUID id) {
        var contents = entries.get(id);
        return contents == null ? 0 : contents.size();
    }

    /** 条目清空后的收尾：内存条目与侧表真值一并移除 + 标脏（保存时删除文件）。 */
    private void afterMutated(UUID id) {
        var contents = entries.get(id);
        if (contents == null || contents.isEmpty()) {
            entries.remove(id);
            overflow.remove(id);
        }
        dirty.add(id);
    }

    public synchronized void loadOnce(Path worldRoot, RegistryAccess registries) {
        if (currentRootLoaded && worldRoot.equals(currentRoot)) {
            return;
        }
        load(worldRoot, registries);
    }

    public synchronized void load(Path worldRoot, RegistryAccess registries) {
        prepareRootLoad(worldRoot, registries);
        currentRoot = worldRoot;
        var dir = worldRoot.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            currentRootLoaded = true;
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .forEach(path -> loadFile(path, registries));
        } catch (IOException e) {
            LOG.error("Failed to load matter mass store from {}", dir, e);
        }
        currentRootLoaded = true;
    }

    public synchronized void save(Path worldRoot, RegistryAccess registries) {
        currentRoot = worldRoot;
        if (dirty.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(worldRoot.resolve(DIR));
            for (var id : new HashSet<>(dirty)) {
                saveFile(worldRoot, id, registries);
            }
        } catch (IOException e) {
            LOG.error("Failed to save matter mass store to {}", worldRoot, e);
        }
    }

    public synchronized void saveCurrentWorld() {
        var server = ServerLifecycleHooks.getCurrentServer();
        var root = currentRoot;
        var registries = defaultRegistries();
        if (server != null) {
            root = server.getWorldPath(LevelResource.ROOT);
            registries = server.registryAccess();
        }
        if (root == null) {
            LOG.warn("Skipped matter mass store save because no world root is available");
            return;
        }
        save(root, registries);
    }

    private void prepareRootLoad(Path worldRoot, RegistryAccess registries) {
        if (currentRoot != null && !currentRoot.equals(worldRoot) && !dirty.isEmpty()) {
            LOG.warn("Saving dirty matter mass store before switching world root from {} to {}",
                    currentRoot, worldRoot);
            save(currentRoot, registries);
        }
        if (!worldRoot.equals(currentRoot)) {
            entries.clear();
            overflow.clear();
            dirty.clear();
        }
        currentRoot = worldRoot;
        currentRootLoaded = false;
    }

    private void loadFile(Path file, RegistryAccess registries) {
        try {
            var id = UUID.fromString(file.getFileName().toString().replace(".nbt", ""));
            var tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag == null || tag.getInt("version") != FORMAT_VERSION) {
                LOG.warn("Skipped unsupported matter mass file {}", file);
                return;
            }
            var result = CONTENTS_CODEC.parse(
                    registries.createSerializationContext(NbtOps.INSTANCE), tag.get("contents"));
            var contents = result.getOrThrow();
            overflow.remove(id);
            // 覆盖键与 contents 同源同序：第 i 条溢出真值就挂在这本账的第 i 个条目上
            ListTag encodedEntries = tag.getList("contents", Tag.TAG_COMPOUND);
            for (int index = 0; index < encodedEntries.size() && index < contents.size(); index++) {
                CompoundTag entryTag = encodedEntries.getCompound(index);
                if (entryTag.contains(NBT_ENTRY_BIG_AMOUNT, Tag.TAG_BYTE_ARRAY)) {
                    overflowTable(id).put(contents.get(index).what(),
                            new BigInteger(entryTag.getByteArray(NBT_ENTRY_BIG_AMOUNT)));
                }
            }
            if (!contents.isEmpty()) {
                entries.put(id, new ArrayList<>(contents));
            }
        } catch (Exception e) {
            LOG.error("Failed to load matter mass file {}", file, e);
        }
    }

    private void saveFile(Path worldRoot, UUID id, RegistryAccess registries) throws IOException {
        var path = worldRoot.resolve(DIR).resolve(id + ".nbt");
        var contents = entries.get(id);
        if (contents == null || contents.isEmpty()) {
            Files.deleteIfExists(path);
            dirty.remove(id);
            return;
        }
        var tag = new CompoundTag();
        tag.putInt("version", FORMAT_VERSION);
        ListTag encoded = (ListTag) CONTENTS_CODEC.encodeStart(
                registries.createSerializationContext(NbtOps.INSTANCE), List.copyOf(contents)).getOrThrow();
        var carried = overflow.get(id);
        if (carried != null && !carried.isEmpty()) {
            for (int index = 0; index < encoded.size(); index++) {
                var exact = carried.get(contents.get(index).what());
                if (exact != null) {
                    encoded.getCompound(index).putByteArray(NBT_ENTRY_BIG_AMOUNT, exact.toByteArray());
                }
            }
        }
        tag.put("contents", encoded);

        var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, tmp);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty.remove(id);
    }

    private static RegistryAccess defaultRegistries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess();
        }
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
