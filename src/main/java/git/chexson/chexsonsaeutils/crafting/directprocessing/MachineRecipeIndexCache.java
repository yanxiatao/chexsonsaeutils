package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

public final class MachineRecipeIndexCache {

    private static final int MAX_ENTRIES = 256;
    private static final MachineRecipeIndexCache INSTANCE = new MachineRecipeIndexCache();

    private final Map<Key, MachineRecipeIndex> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long missCount;

    private MachineRecipeIndexCache() {
    }

    public static MachineRecipeIndexCache instance() {
        return INSTANCE;
    }

    public synchronized BuildResult getOrBuild(
            Level level,
            MachineIdentity identity,
            long recipeEpoch,
            long configMappingEpoch,
            long machineIndexVersion,
            BiFunction<Level, MachineIdentity, MachineRecipeIndex> builder
    ) {
        if (level == null || identity == null || builder == null) {
            return new BuildResult(MachineRecipeIndex.empty(), false);
        }
        Key key = new Key(identity, recipeEpoch, configMappingEpoch);
        MachineRecipeIndex cached = entries.get(key);
        if (cached != null) {
            return new BuildResult(cached.withVersion(machineIndexVersion), true);
        }
        MachineRecipeIndex built = builder.apply(level, identity);
        if (built == null) {
            built = MachineRecipeIndex.empty();
        }
        entries.put(key, built.withVersion(0L));
        trimToMaxEntries();
        missCount++;
        return new BuildResult(built.withVersion(machineIndexVersion), false);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long missCount() {
        return missCount;
    }

    public synchronized void clear() {
        entries.clear();
        missCount = 0L;
    }

    private void trimToMaxEntries() {
        while (entries.size() > MAX_ENTRIES) {
            Key eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    public record BuildResult(MachineRecipeIndex index, boolean cacheHit) {
    }

    private record Key(MachineIdentity identity, long recipeEpoch, long configMappingEpoch) {
    }
}
