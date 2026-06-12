package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEItemKey;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PatternCompatibilityCache {

    private final Map<Key, PatternCompatibility> entries = new LinkedHashMap<>();

    public PatternCompatibility get(AEItemKey patternDefinition, long machineRecipeIndexVersion) {
        return entries.get(new Key(patternDefinition, machineRecipeIndexVersion));
    }

    public void put(AEItemKey patternDefinition, long machineRecipeIndexVersion, PatternCompatibility compatibility) {
        if (patternDefinition == null || compatibility == null) {
            return;
        }
        entries.put(new Key(patternDefinition, machineRecipeIndexVersion), compatibility);
    }

    public void remove(AEItemKey patternDefinition, long machineRecipeIndexVersion) {
        if (patternDefinition == null) {
            return;
        }
        entries.remove(new Key(patternDefinition, machineRecipeIndexVersion));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private record Key(AEItemKey patternDefinition, long machineRecipeIndexVersion) {
    }
}
