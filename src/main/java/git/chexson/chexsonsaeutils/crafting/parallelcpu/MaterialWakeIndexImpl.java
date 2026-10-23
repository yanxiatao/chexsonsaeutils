package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 基于 AE2 原生样板替代能力建立精确/族索引；未知样板仍由调用方检查实际输入槽。 */
final class MaterialWakeIndexImpl implements MaterialWakeIndex {
    private record Registration(Set<AEKey> keys, boolean exact) { }
    private final Map<IPatternDetails, Registration> registrations = new HashMap<>();
    private final Map<AEKey, Set<IPatternDetails>> exact = new HashMap<>();
    private final Map<Object, Set<IPatternDetails>> families = new HashMap<>();
    private final Predicate<IPatternDetails> exactOnly;

    MaterialWakeIndexImpl() {
        this(pattern -> pattern instanceof AECraftingPattern nativePattern && !nativePattern.canSubstitute());
    }

    /** 注入可证明的精确输入能力，供独立索引验证使用；生产默认采用 AE2 真实能力。 */
    MaterialWakeIndexImpl(Predicate<IPatternDetails> exactOnly) {
        this.exactOnly = exactOnly;
    }

    @Override public void register(IPatternDetails pattern, Set<AEKey> keys) {
        remove(pattern);
        var registration = new Registration(Set.copyOf(keys), exactOnly.test(pattern));
        registrations.put(pattern, registration);
        for (var key : registration.keys()) {
            if (registration.exact()) exact.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(pattern);
            else families.computeIfAbsent(key.getPrimaryKey(), ignored -> new LinkedHashSet<>()).add(pattern);
        }
    }

    @Override public void remove(IPatternDetails pattern) {
        var registration = registrations.remove(pattern);
        if (registration == null) return;
        for (var key : registration.keys()) {
            if (registration.exact()) remove(exact, key, pattern);
            else remove(families, key.getPrimaryKey(), pattern);
        }
    }

    private static <K> void remove(Map<K, Set<IPatternDetails>> index, K key, IPatternDetails pattern) {
        var members = index.get(key);
        if (members == null) return;
        members.remove(pattern);
        if (members.isEmpty()) index.remove(key);
    }

    @Override public Set<IPatternDetails> candidates(AEKey arriving) {
        var result = new LinkedHashSet<IPatternDetails>();
        result.addAll(exact.getOrDefault(arriving, Set.of()));
        result.addAll(families.getOrDefault(arriving.getPrimaryKey(), Set.of()));
        return result;
    }
}
