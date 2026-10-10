package git.chexson.chexsonsaeutils.crafting.speedplan;

import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceProvidersTickAccessor;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 按 provider 代与目标缓存冻结路线程序。 */
final class FrozenRoutePlannerCache {
    private static final int MAX_PROGRAMS = 256;
    private static final Map<CacheKey, FrozenRoutePlanner.CompiledProgram> PROGRAMS =
            new LinkedHashMap<>(32, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<CacheKey, FrozenRoutePlanner.CompiledProgram> eldest) {
                    return size() > MAX_PROGRAMS;
                }
            };

    private FrozenRoutePlannerCache() {
    }

    static FrozenRoutePlanner.CompiledProgram getOrCompile(
            FrozenRoutePlanner planner,
            ICraftingService service,
            AEKey target,
            @Nullable Level level
    ) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(target, "target");
        long providersTick = providersTick(service);
        if (providersTick == Long.MIN_VALUE) {
            return planner.compile(service, target, level);
        }
        var key = new CacheKey(service, providersTick, target);
        synchronized (PROGRAMS) {
            var cached = PROGRAMS.get(key);
            if (cached != null) {
                return cached;
            }
        }
        var compiled = planner.compile(service, target, level);
        synchronized (PROGRAMS) {
            PROGRAMS.keySet().removeIf(existing -> existing.service() == service
                    && existing.providersTick() != providersTick);
            var raced = PROGRAMS.get(key);
            if (raced != null) {
                return raced;
            }
            PROGRAMS.put(key, compiled);
            return compiled;
        }
    }

    private static long providersTick(ICraftingService service) {
        if (!(service instanceof CraftingService craftingService)) {
            return Long.MIN_VALUE;
        }
        try {
            return ((CraftingServiceProvidersTickAccessor) craftingService)
                    .chexsonsaeutils$getCraftingProviders()
                    .getLastModifiedOnTick();
        } catch (RuntimeException | LinkageError unavailable) {
            return Long.MIN_VALUE;
        }
    }

    private record CacheKey(ICraftingService service, long providersTick, AEKey target) {
        @Override
        public boolean equals(Object other) {
            return other instanceof CacheKey key
                    && service == key.service
                    && providersTick == key.providersTick
                    && target.equals(key.target);
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(service);
            hash = 31 * hash + Long.hashCode(providersTick);
            return 31 * hash + target.hashCode();
        }
    }
}
