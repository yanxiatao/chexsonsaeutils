package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InfinityCellStore {
    private static final Logger LOG = LoggerFactory.getLogger(InfinityCellStore.class);
    private static final int FORMAT_VERSION = 1;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final InfinityCellStore GLOBAL = new InfinityCellStore();

    private final KeyDictionary keyDictionary = new KeyDictionary();
    private final Map<UUID, CellData> cells = new HashMap<>();
    @Nullable
    private Path currentRoot;
    private boolean currentRootLoaded;

    public static InfinityCellStore global() {
        return GLOBAL;
    }

    public synchronized CellData cell(UUID id) {
        return cells.computeIfAbsent(id, ignored -> new CellData(keyDictionary));
    }

    public KeyDictionary keyDictionary() {
        return keyDictionary;
    }

    public synchronized void load(Path worldRoot) {
        load(worldRoot, defaultRegistries());
    }

    public synchronized void load(Path worldRoot, RegistryAccess registries) {
        prepareRootLoad(worldRoot, registries);
        currentRoot = worldRoot;
        var dir = CellStoragePaths.getCellDir(worldRoot);
        if (!Files.isDirectory(dir)) {
            currentRootLoaded = true;
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .forEach(path -> loadCellFile(path, registries));
        } catch (IOException e) {
            LOG.error("Failed to load infinity cell store from {}", dir, e);
        }
        currentRootLoaded = true;
    }

    public synchronized void loadOnce(Path worldRoot) {
        loadOnce(worldRoot, defaultRegistries());
    }

    public synchronized void loadOnce(Path worldRoot, RegistryAccess registries) {
        if (currentRootLoaded && worldRoot.equals(currentRoot)) {
            return;
        }
        load(worldRoot, registries);
    }

    public synchronized void save(Path worldRoot) {
        save(worldRoot, defaultRegistries());
    }

    public synchronized void save(Path worldRoot, RegistryAccess registries) {
        currentRoot = worldRoot;
        try {
            Files.createDirectories(CellStoragePaths.getCellDir(worldRoot));
            for (var entry : cells.entrySet()) {
                if (entry.getValue().isDirty()) {
                    saveCellFile(worldRoot, entry.getKey(), entry.getValue(), registries);
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to save infinity cell store to {}", worldRoot, e);
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
            LOG.warn("Skipped infinity cell save because no world root is available");
            return;
        }
        save(root, registries);
    }

    private void prepareRootLoad(Path worldRoot, RegistryAccess registries) {
        if (currentRoot != null && !currentRoot.equals(worldRoot) && hasDirtyCells()) {
            LOG.warn("Saving dirty infinity cell store before switching world root from {} to {}", currentRoot, worldRoot);
            save(currentRoot, registries);
        }
        if (!worldRoot.equals(currentRoot)) {
            keyDictionary.clear();
            cells.clear();
        }
        currentRoot = worldRoot;
        currentRootLoaded = false;
    }

    private boolean hasDirtyCells() {
        for (var cell : cells.values()) {
            if (cell.isDirty()) {
                return true;
            }
        }
        return false;
    }

    public static int readEntryCount(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        var tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return tag == null ? 0 : tag.getList("entries", Tag.TAG_COMPOUND).size();
    }

    private void loadCellFile(Path file, RegistryAccess registries) {
        try {
            var id = UUID.fromString(file.getFileName().toString().replace(".nbt", ""));
            var tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag == null || tag.getInt("version") != FORMAT_VERSION) {
                LOG.warn("Skipped unsupported infinity cell file {}", file);
                return;
            }
            var remap = loadKeys(tag.getList("keys", Tag.TAG_COMPOUND), registries);
            var data = cell(id);
            data.clearForLoad();
            var entries = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                var oldKeyId = entry.getInt("keyId");
                var keyId = remap.getOrDefault(oldKeyId, oldKeyId);
                data.loadAmount(keyId, readAmount(entry));
            }
            data.markClean();
        } catch (Exception e) {
            LOG.error("Failed to load infinity cell file {}", file, e);
        }
    }

    private Map<Integer, Integer> loadKeys(ListTag keys, RegistryAccess registries) {
        var remap = new HashMap<Integer, Integer>();
        for (int i = 0; i < keys.size(); i++) {
            var keyEntry = keys.getCompound(i);
            var savedId = keyEntry.getInt("id");
            var key = decodeKey(keyEntry, registries);
            if (key == null) {
                LOG.warn("Skipped undecodable infinity cell key id {}", savedId);
                continue;
            }
            remap.put(savedId, keyDictionary.loadWithPreferredId(savedId, key));
        }
        return remap;
    }

    @Nullable
    private AEKey decodeKey(CompoundTag keyEntry, RegistryAccess registries) {
        var type = keyEntry.getString("type");
        var keyTag = keyEntry.getCompound("tag");
        if (type.equals(AEKeyType.items().getId().toString())) {
            return AEKeyType.items().loadKeyFromTag(registries, keyTag);
        }
        if (type.equals(AEKeyType.fluids().getId().toString())) {
            return AEKeyType.fluids().loadKeyFromTag(registries, keyTag);
        }
        if (keyEntry.contains("generic", Tag.TAG_COMPOUND)) {
            return AEKey.fromTagGeneric(registries, keyEntry.getCompound("generic"));
        }
        return null;
    }

    private void saveCellFile(Path worldRoot, UUID id, CellData data, RegistryAccess registries)
            throws IOException {
        var path = CellStoragePaths.getNbtFile(worldRoot, id);
        if (data.isEmpty()) {
            Files.deleteIfExists(path);
            data.markClean();
            return;
        }

        var tag = new CompoundTag();
        tag.putInt("version", FORMAT_VERSION);
        tag.put("keys", writeKeys(data, registries));
        tag.put("entries", writeEntries(data));

        var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, tmp);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        data.markClean();
    }

    private ListTag writeKeys(CellData data, RegistryAccess registries) throws IOException {
        var keys = new ListTag();
        for (var keyId : data.keyIds()) {
            var key = keyDictionary.keyFor(keyId);
            if (key == null) {
                throw new IOException("Missing AEKey for keyId " + keyId);
            }
            var keyTag = new CompoundTag();
            keyTag.putInt("id", keyId);
            keyTag.putString("type", key.getType().getId().toString());
            keyTag.put("tag", key.toTag(registries));
            keyTag.put("generic", key.toTagGeneric(registries));
            keys.add(keyTag);
        }
        return keys;
    }

    private ListTag writeEntries(CellData data) {
        var entries = new ListTag();
        for (var entry : data.amountEntries()) {
            var entryTag = new CompoundTag();
            var keyId = entry.getIntKey();
            entryTag.putInt("keyId", keyId);
            var exact = data.exactAmount(keyId);
            if (exact.compareTo(LONG_MAX) <= 0) {
                entryTag.putLong("amount", exact.longValue());
            } else {
                entryTag.putByteArray("bigAmount", exact.toByteArray());
            }
            entries.add(entryTag);
        }
        return entries;
    }

    private static BigInteger readAmount(CompoundTag entry) {
        if (entry.contains("bigAmount", Tag.TAG_BYTE_ARRAY)) {
            return new BigInteger(entry.getByteArray("bigAmount"));
        }
        return BigInteger.valueOf(entry.getLong("amount"));
    }

    private static RegistryAccess defaultRegistries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess();
        }
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    public static final class KeyDictionary {
        private final Object2IntOpenHashMap<AEKey> idsByKey = new Object2IntOpenHashMap<>();
        private final Int2ObjectOpenHashMap<AEKey> keysById = new Int2ObjectOpenHashMap<>();
        private int nextId = 1;

        private KeyDictionary() {
            idsByKey.defaultReturnValue(0);
        }

        public synchronized int idFor(AEKey key) {
            var id = idsByKey.getInt(key);
            if (id != 0) {
                return id;
            }
            id = nextId++;
            idsByKey.put(key, id);
            keysById.put(id, key);
            return id;
        }

        synchronized int loadWithPreferredId(int preferredId, AEKey key) {
            var existing = idsByKey.getInt(key);
            if (existing != 0) {
                return existing;
            }
            var preferredKey = keysById.get(preferredId);
            if (preferredKey == null || preferredKey.equals(key)) {
                idsByKey.put(key, preferredId);
                keysById.put(preferredId, key);
                nextId = Math.max(nextId, preferredId + 1);
                return preferredId;
            }
            return idFor(key);
        }

        @Nullable
        synchronized AEKey keyFor(int id) {
            return keysById.get(id);
        }

        synchronized int existingIdFor(AEKey key) {
            return idsByKey.getInt(key);
        }

        synchronized void clear() {
            idsByKey.clear();
            keysById.clear();
            nextId = 1;
        }
    }

    public static final class CellData {
        private final KeyDictionary keyDictionary;
        private final Int2LongOpenHashMap amounts = new Int2LongOpenHashMap();
        private final Int2ObjectOpenHashMap<BigInteger> overflowAmounts = new Int2ObjectOpenHashMap<>();
        private BigInteger total = BigInteger.ZERO;
        private long version;
        private boolean dirty;
        @Nullable
        private Snapshot cachedSnapshot;

        private CellData(KeyDictionary keyDictionary) {
            this.keyDictionary = keyDictionary;
            amounts.defaultReturnValue(0);
        }

        public synchronized long insert(AEKey key, long amount, Actionable mode) {
            if (amount <= 0) {
                return 0;
            }
            if (mode == Actionable.MODULATE) {
                add(keyDictionary.idFor(key), amount);
            }
            return amount;
        }

        public synchronized long extract(AEKey key, long amount, Actionable mode) {
            if (amount <= 0) {
                return 0;
            }
            var keyId = keyDictionary.existingIdFor(key);
            if (keyId == 0) {
                return 0;
            }
            var available = visibleAmount(keyId);
            var extracted = Math.min(amount, available);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                subtract(keyId, extracted);
            }
            return extracted;
        }

        public synchronized long visibleAmount(AEKey key) {
            var keyId = keyDictionary.existingIdFor(key);
            return keyId == 0 ? 0 : visibleAmount(keyId);
        }

        public synchronized BigInteger exactAmount(AEKey key) {
            var keyId = keyDictionary.existingIdFor(key);
            return keyId == 0 ? BigInteger.ZERO : exactAmount(keyId);
        }

        public synchronized boolean hasOverflow(AEKey key) {
            var keyId = keyDictionary.existingIdFor(key);
            return keyId != 0 && overflowAmounts.containsKey(keyId);
        }

        public synchronized Snapshot snapshot() {
            if (cachedSnapshot != null && cachedSnapshot.version == version) {
                return cachedSnapshot;
            }
            var keys = new AEKey[amounts.size()];
            var values = new long[amounts.size()];
            var index = 0;
            for (var entry : amounts.int2LongEntrySet()) {
                var amount = visibleAmount(entry.getIntKey());
                if (amount <= 0) {
                    continue;
                }
                var key = keyDictionary.keyFor(entry.getIntKey());
                if (key == null) {
                    continue;
                }
                keys[index] = key;
                values[index] = amount;
                index++;
            }
            if (index != keys.length) {
                keys = java.util.Arrays.copyOf(keys, index);
                values = java.util.Arrays.copyOf(values, index);
            }
            cachedSnapshot = new Snapshot(keys, values, version);
            return cachedSnapshot;
        }

        public synchronized void getAvailableStacks(KeyCounter out) {
            var snapshot = snapshot();
            for (int i = 0; i < snapshot.size(); i++) {
                out.add(snapshot.keys()[i], snapshot.amounts()[i]);
            }
        }

        public synchronized BigInteger snapshotTotal() {
            return total;
        }

        public synchronized BigInteger totalAmount() {
            return total;
        }

        public synchronized int typeCount() {
            return amounts.size();
        }

        synchronized boolean isDirty() {
            return dirty;
        }

        synchronized boolean isEmpty() {
            return amounts.isEmpty();
        }

        synchronized void markClean() {
            dirty = false;
        }

        synchronized void clearForLoad() {
            amounts.clear();
            overflowAmounts.clear();
            total = BigInteger.ZERO;
            version++;
            cachedSnapshot = null;
        }

        synchronized void loadAmount(int keyId, BigInteger amount) {
            if (amount.signum() <= 0) {
                return;
            }
            var previous = exactAmount(keyId);
            if (amount.compareTo(LONG_MAX) <= 0) {
                amounts.put(keyId, amount.longValue());
                overflowAmounts.remove(keyId);
            } else {
                amounts.put(keyId, Long.MAX_VALUE);
                overflowAmounts.put(keyId, amount);
            }
            total = total.subtract(previous).add(amount);
            version++;
            cachedSnapshot = null;
        }

        synchronized int[] keyIds() {
            return amounts.keySet().toIntArray();
        }

        synchronized Iterable<Int2LongMap.Entry> amountEntries() {
            return Int2LongMaps.fastIterable(amounts);
        }

        synchronized BigInteger exactAmount(int keyId) {
            var overflow = overflowAmounts.get(keyId);
            return overflow != null ? overflow : BigInteger.valueOf(amounts.get(keyId));
        }

        private long visibleAmount(int keyId) {
            return overflowAmounts.containsKey(keyId) ? Long.MAX_VALUE : amounts.get(keyId);
        }

        private void add(int keyId, long delta) {
            total = total.add(BigInteger.valueOf(delta));
            var overflow = overflowAmounts.get(keyId);
            if (overflow != null) {
                overflowAmounts.put(keyId, overflow.add(BigInteger.valueOf(delta)));
                markChanged();
                return;
            }

            var current = amounts.get(keyId);
            if (Long.MAX_VALUE - current < delta) {
                overflowAmounts.put(keyId, BigInteger.valueOf(current).add(BigInteger.valueOf(delta)));
                amounts.put(keyId, Long.MAX_VALUE);
            } else {
                amounts.put(keyId, current + delta);
            }
            markChanged();
        }

        private void subtract(int keyId, long delta) {
            total = total.subtract(BigInteger.valueOf(delta));
            var overflow = overflowAmounts.get(keyId);
            if (overflow != null) {
                var updated = overflow.subtract(BigInteger.valueOf(delta));
                if (updated.compareTo(LONG_MAX) > 0) {
                    overflowAmounts.put(keyId, updated);
                    amounts.put(keyId, Long.MAX_VALUE);
                } else if (updated.signum() > 0) {
                    overflowAmounts.remove(keyId);
                    amounts.put(keyId, updated.longValue());
                } else {
                    overflowAmounts.remove(keyId);
                    amounts.remove(keyId);
                }
                markChanged();
                return;
            }

            var updated = amounts.get(keyId) - delta;
            if (updated > 0) {
                amounts.put(keyId, updated);
            } else {
                amounts.remove(keyId);
            }
            markChanged();
        }

        private void markChanged() {
            version++;
            dirty = true;
            cachedSnapshot = null;
        }
    }

    public record Snapshot(AEKey[] keys, long[] amounts, long version) {
        public int size() {
            return keys.length;
        }
    }
}
