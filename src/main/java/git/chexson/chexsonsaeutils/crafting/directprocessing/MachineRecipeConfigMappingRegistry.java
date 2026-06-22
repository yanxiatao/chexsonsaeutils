package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MachineRecipeConfigMappingRegistry {

    private static final MachineRecipeConfigMappingRegistry INSTANCE = new MachineRecipeConfigMappingRegistry();

    private final Map<ResourceLocation, List<RecipeTypeCandidate>> userConfigMappingsByMachineId = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<RecipeTypeCandidate>> legacyConfigMappingsByMachineId = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<RecipeTypeCandidate>> datapackMappingsByMachineId = new LinkedHashMap<>();
    private final Map<ResourceLocation, List<RecipeTypeCandidate>> runtimeMappingsByMachineId = new LinkedHashMap<>();
    private long epoch;

    private MachineRecipeConfigMappingRegistry() {
    }

    public static MachineRecipeConfigMappingRegistry instance() {
        return INSTANCE;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                copyMappings(userConfigMappingsByMachineId),
                copyMappings(legacyConfigMappingsByMachineId),
                copyMappings(datapackMappingsByMachineId),
                copyMappings(runtimeMappingsByMachineId)
        );
    }

    public synchronized void restore(Snapshot snapshot) {
        userConfigMappingsByMachineId.clear();
        legacyConfigMappingsByMachineId.clear();
        datapackMappingsByMachineId.clear();
        runtimeMappingsByMachineId.clear();
        if (snapshot != null) {
            restoreMappings(userConfigMappingsByMachineId, snapshot.userConfigMappingsByMachineId());
            restoreMappings(legacyConfigMappingsByMachineId, snapshot.legacyConfigMappingsByMachineId());
            restoreMappings(datapackMappingsByMachineId, snapshot.datapackMappingsByMachineId());
            restoreMappings(runtimeMappingsByMachineId, snapshot.runtimeMappingsByMachineId());
        }
        epoch++;
        MachineRecipeIndexCache.instance().clear();
    }

    public synchronized void replaceUserConfigMappings(List<ParsedConfigMapping> parsedMappings) {
        userConfigMappingsByMachineId.clear();
        if (parsedMappings != null) {
            for (ParsedConfigMapping parsedMapping : parsedMappings) {
                if (parsedMapping != null) {
                    putMapping(
                            userConfigMappingsByMachineId,
                            parsedMapping.machineId(),
                            parsedMapping.recipeType(),
                            parsedMapping.defaultTicks()
                    );
                }
            }
        }
        epoch++;
        MachineRecipeIndexCache.instance().clear();
    }

    public synchronized void replaceMappings(List<String> encodedMappings) {
        legacyConfigMappingsByMachineId.clear();
        if (encodedMappings != null) {
            for (String encodedMapping : encodedMappings) {
                ParsedMapping parsedMapping = parseMapping(encodedMapping);
                if (parsedMapping != null) {
                    putMapping(
                            legacyConfigMappingsByMachineId,
                            parsedMapping.machineId(),
                            parsedMapping.recipeType(),
                            parsedMapping.defaultTicks()
                    );
                }
            }
        }
        epoch++;
        MachineRecipeIndexCache.instance().clear();
    }

    public synchronized void registerMapping(
            ResourceLocation machineId,
            RecipeType<?> recipeType,
            int defaultTicks
    ) {
        if (machineId == null || recipeType == null) {
            return;
        }
        putMapping(runtimeMappingsByMachineId, machineId, recipeType, defaultTicks);
        epoch++;
        MachineRecipeIndexCache.instance().clear();
    }

    public synchronized void replaceParsedMappings(List<ParsedConfigMapping> parsedMappings) {
        datapackMappingsByMachineId.clear();
        if (parsedMappings != null) {
            for (ParsedConfigMapping parsedMapping : parsedMappings) {
                if (parsedMapping != null) {
                    putMapping(datapackMappingsByMachineId, parsedMapping.machineId(), parsedMapping.recipeType(), parsedMapping.defaultTicks());
                }
            }
        }
        epoch++;
        MachineRecipeIndexCache.instance().clear();
    }

    private void putMapping(
            Map<ResourceLocation, List<RecipeTypeCandidate>> mappingsByMachineId,
            ResourceLocation machineId,
            RecipeType<?> recipeType,
            int defaultTicks
    ) {
        mappingsByMachineId.computeIfAbsent(machineId, ignored -> new ArrayList<>()).add(new RecipeTypeCandidate(
                null,
                recipeType,
                MachineRecipeCandidateSource.CONFIG_MAPPING,
                defaultTicks
        ));
    }

    public synchronized List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        if (identity == null) {
            return List.of();
        }
        List<RecipeTypeCandidate> candidates = new ArrayList<>();
        addResolvedCandidates(candidates, runtimeMappingsByMachineId, identity);
        addResolvedCandidates(candidates, userConfigMappingsByMachineId, identity);
        addResolvedCandidates(candidates, legacyConfigMappingsByMachineId, identity);
        addResolvedCandidates(candidates, datapackMappingsByMachineId, identity);
        return candidates.isEmpty() ? List.of() : List.copyOf(candidates);
    }

    private static void addResolvedCandidates(
            List<RecipeTypeCandidate> candidates,
            Map<ResourceLocation, List<RecipeTypeCandidate>> mappingsByMachineId,
            MachineIdentity identity
    ) {
        List<RecipeTypeCandidate> mappedCandidates = mappingsByMachineId.get(identity.machineItemId());
        if ((mappedCandidates == null || mappedCandidates.isEmpty()) && identity.blockId() != null) {
            mappedCandidates = mappingsByMachineId.get(identity.blockId());
        }
        if (mappedCandidates != null && !mappedCandidates.isEmpty()) {
            candidates.addAll(mappedCandidates);
        }
    }

    @Nullable
    private static ParsedMapping parseMapping(String encodedMapping) {
        if (encodedMapping == null || encodedMapping.isBlank()) {
            return null;
        }
        String[] parts = encodedMapping.split("=", 3);
        if (parts.length < 2) {
            return null;
        }
        ResourceLocation machineId = ResourceLocation.tryParse(parts[0].trim());
        ResourceLocation recipeTypeId = ResourceLocation.tryParse(parts[1].trim());
        if (machineId == null || recipeTypeId == null) {
            return null;
        }
        RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
        if (recipeType == null) {
            return null;
        }
        int defaultTicks = parts.length >= 3 ? parsePositiveInt(parts[2], 20) : 20;
        return new ParsedMapping(machineId, recipeType, defaultTicks);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record ParsedConfigMapping(
            ResourceLocation machineId,
            RecipeType<?> recipeType,
            int defaultTicks
    ) {
    }

    private static Map<ResourceLocation, List<RecipeTypeCandidate>> copyMappings(
            Map<ResourceLocation, List<RecipeTypeCandidate>> source
    ) {
        Map<ResourceLocation, List<RecipeTypeCandidate>> copiedMappings = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<ResourceLocation, List<RecipeTypeCandidate>> entry : source.entrySet()) {
                copiedMappings.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return copiedMappings;
    }

    private static void restoreMappings(
            Map<ResourceLocation, List<RecipeTypeCandidate>> target,
            Map<ResourceLocation, List<RecipeTypeCandidate>> source
    ) {
        if (source == null) {
            return;
        }
        for (Map.Entry<ResourceLocation, List<RecipeTypeCandidate>> entry : source.entrySet()) {
            target.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    public record Snapshot(
            Map<ResourceLocation, List<RecipeTypeCandidate>> userConfigMappingsByMachineId,
            Map<ResourceLocation, List<RecipeTypeCandidate>> legacyConfigMappingsByMachineId,
            Map<ResourceLocation, List<RecipeTypeCandidate>> datapackMappingsByMachineId,
            Map<ResourceLocation, List<RecipeTypeCandidate>> runtimeMappingsByMachineId
    ) {
        public Snapshot {
            userConfigMappingsByMachineId = Map.copyOf(copyMappings(userConfigMappingsByMachineId));
            legacyConfigMappingsByMachineId = Map.copyOf(copyMappings(legacyConfigMappingsByMachineId));
            datapackMappingsByMachineId = Map.copyOf(copyMappings(datapackMappingsByMachineId));
            runtimeMappingsByMachineId = Map.copyOf(copyMappings(runtimeMappingsByMachineId));
        }
    }

    private record ParsedMapping(
            ResourceLocation machineId,
            RecipeType<?> recipeType,
            int defaultTicks
    ) {
    }
}
