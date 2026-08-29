package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.serialization.Codec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
    private static final MatterMassStore GLOBAL = new MatterMassStore();

    private final Map<UUID, List<GenericStack>> entries = new HashMap<>();
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
        var contents = entries.computeIfAbsent(id, ignored -> new ArrayList<>());
        var merged = new LinkedHashMap<AEKey, Long>();
        for (var stack : contents) {
            merged.merge(stack.what(), stack.amount(), Long::sum);
        }
        for (var stack : stacks) {
            if (stack != null && stack.amount() > 0) {
                merged.merge(stack.what(), stack.amount(), Long::sum);
            }
        }
        contents.clear();
        merged.forEach((key, amount) -> contents.add(new GenericStack(key, amount)));
        dirty.add(id);
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
            extracted = Math.min(amount, stack.amount());
            if (extracted > 0 && mode == Actionable.MODULATE) {
                var remaining = stack.amount() - extracted;
                if (remaining > 0) {
                    contents.set(i, new GenericStack(what, remaining));
                } else {
                    contents.remove(i);
                }
                afterMutated(id);
            }
            break;
        }
        return extracted;
    }

    /** 取走全部内容物并删除条目（释放/聚合去重用）。 */
    public synchronized List<GenericStack> takeAll(UUID id) {
        var contents = entries.remove(id);
        if (contents == null || contents.isEmpty()) {
            dirty.remove(id);
            return List.of();
        }
        dirty.add(id);
        return List.copyOf(contents);
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

    /** 条目清空后的收尾：内存条目移除 + 标脏（保存时删除文件）。 */
    private void afterMutated(UUID id) {
        var contents = entries.get(id);
        if (contents == null || contents.isEmpty()) {
            entries.remove(id);
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
        var encoded = CONTENTS_CODEC.encodeStart(
                registries.createSerializationContext(NbtOps.INSTANCE), List.copyOf(contents));
        tag.put("contents", encoded.getOrThrow());

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
