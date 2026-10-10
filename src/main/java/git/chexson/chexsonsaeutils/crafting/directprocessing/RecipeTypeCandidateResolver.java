package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RecipeTypeCandidateResolver {

    private final MachineAdapterRegistry adapterRegistry;
    private final MachineRecipeConfigMappingRegistry configMappingRegistry;
    private final boolean genericDiscoveryEnabled;

    public RecipeTypeCandidateResolver(MachineAdapterRegistry adapterRegistry) {
        this(adapterRegistry, MachineRecipeConfigMappingRegistry.instance(), true);
    }

    public RecipeTypeCandidateResolver(
            MachineAdapterRegistry adapterRegistry,
            MachineRecipeConfigMappingRegistry configMappingRegistry,
            boolean genericDiscoveryEnabled
    ) {
        this.adapterRegistry = adapterRegistry == null ? MachineAdapterRegistry.empty() : adapterRegistry;
        this.configMappingRegistry = configMappingRegistry == null
                ? MachineRecipeConfigMappingRegistry.instance()
                : configMappingRegistry;
        this.genericDiscoveryEnabled = genericDiscoveryEnabled;
    }

    public Resolution resolve(MachineIdentity identity) {
        if (identity == null) {
            return Resolution.unsupported(
                    MachineSupportStatus.UNSUPPORTED_UNREADABLE,
                    MachineSupportReasonCode.REGISTRY_MISSING
            );
        }
        DirectProcessingPackRules rules = DirectProcessingPackRulesRegistry.current();
        if (!rules.allowsMachine(identity)) {
            return Resolution.unsupported(MachineSupportStatus.UNSUPPORTED_UNREADABLE,
                    MachineSupportReasonCode.PACK_RULE_DISABLED);
        }
        List<RecipeTypeCandidate> adapterCandidates = rules.replacesTypes(identity)
                ? List.of() : adapterRegistry.resolveCandidates(identity);
        List<RecipeTypeCandidate> configCandidates = new ArrayList<>(rules.replacesTypes(identity)
                ? List.of() : configMappingRegistry.resolveCandidates(identity));
        for (ResourceLocation id : rules.addedTypes(identity)) {
            RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(id);
            if (type != null) configCandidates.add(new RecipeTypeCandidate(null, type,
                    MachineRecipeCandidateSource.CONFIG_MAPPING, 20));
        }
        Map<RecipeType<?>, RecipeTypeCandidate> candidates = new LinkedHashMap<>();
        for (RecipeTypeCandidate candidate : adapterCandidates) candidates.putIfAbsent(candidate.recipeType(), candidate);
        for (RecipeTypeCandidate candidate : configCandidates) candidates.putIfAbsent(candidate.recipeType(), candidate);
        List<ResourceLocation> hints = List.of();
        if (genericDiscoveryEnabled && rules.allowsAutomaticDiscovery(identity) && adapterCandidates.isEmpty()) {
            for (RecipeTypeCandidate candidate : resolveVanillaGenericCandidates(identity)) {
                candidates.putIfAbsent(candidate.recipeType(), candidate);
            }
            hints = resolveNamingConventionHints(identity);
            for (RecipeTypeCandidate candidate : toAutomaticCandidates(hints)) {
                candidates.putIfAbsent(candidate.recipeType(), candidate);
            }
        }
        candidates.entrySet().removeIf(entry -> !rules.allowsType(identity,
                BuiltInRegistries.RECIPE_TYPE.getKey(entry.getKey())));
        hints = hints.stream().filter(id -> rules.allowsType(identity, id)).toList();
        if (!candidates.isEmpty()) {
            MachineRecipeCandidateSource source = !adapterCandidates.isEmpty() ? MachineRecipeCandidateSource.EXPLICIT_ADAPTER
                    : !configCandidates.isEmpty() ? MachineRecipeCandidateSource.CONFIG_MAPPING
                    : MachineRecipeCandidateSource.GENERIC_RECIPE_TYPE;
            return Resolution.supported(List.copyOf(candidates.values()), hints, source);
        }
        return Resolution.unsupported(
                MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                MachineSupportReasonCode.MAPPING_MISSING
        );
    }

    public List<ResourceLocation> resolveFallbackHints(MachineIdentity identity) {
        if (!genericDiscoveryEnabled || identity == null || !DirectProcessingPackRulesRegistry.current().allowsAutomaticDiscovery(identity)) {
            return List.of();
        }
        return resolveNamingConventionHints(identity).stream()
                .filter(id -> DirectProcessingPackRulesRegistry.current().allowsType(identity, id)).toList();
    }

    private List<RecipeTypeCandidate> resolveVanillaGenericCandidates(MachineIdentity identity) {
        List<RecipeTypeCandidate> candidates = new ArrayList<>();
        for (MachineRecipeKind kind : MachineRecipeKind.values()) {
            if (kind.matches(identity)) {
                candidates.add(new RecipeTypeCandidate(
                        kind,
                        kind.recipeType(),
                        MachineRecipeCandidateSource.GENERIC_RECIPE_TYPE,
                        kind.defaultTicks()
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private List<ResourceLocation> resolveNamingConventionHints(MachineIdentity identity) {
        java.util.Set<ResourceLocation> hints = new java.util.LinkedHashSet<>();
        hints.addAll(resolveNamingConventionHints(identity.machineItemId()));
        if (identity.blockId() != null) hints.addAll(resolveNamingConventionHints(identity.blockId()));
        return List.copyOf(hints);
    }

    private List<ResourceLocation> resolveNamingConventionHints(ResourceLocation machineId) {
        if (machineId == null) {
            return List.of();
        }
        String machinePath = machineId.getPath().toLowerCase(Locale.ROOT);
        List<String> machinePathCandidates = new ArrayList<>();
        List<String> recipePathCandidates = new ArrayList<>();
        addNamingCandidate(machinePathCandidates, machinePath);
        addTrimmedSuffixCandidate(machinePathCandidates, machinePath, "_machine");
        addTrimmedSuffixCandidate(machinePathCandidates, machinePath, "_block");
        addTrimmedSuffixCandidate(machinePathCandidates, machinePath, "_table");
        addTrimmedSuffixCandidate(machinePathCandidates, machinePath, "_factory");
        addTrimmedSuffixCandidate(machinePathCandidates, machinePath, "_controller");
        for (String candidateMachinePath : machinePathCandidates) {
            addNamingCandidate(recipePathCandidates, candidateMachinePath);
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "crusher", "crushing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "pulverizer", "pulverizing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "grinder", "grinding");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "sawmill", "sawing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "sawmill", "sawmilling");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "press", "pressing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "mixer", "mixing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "infuser", "infusing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "assembler", "assembling");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "washer", "washing");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "enricher", "enriching");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "macerator", "macerating");
            addSuffixCandidate(recipePathCandidates, candidateMachinePath, "compactor", "compacting");
        }
        List<ResourceLocation> hints = new ArrayList<>();
        for (String candidatePath : recipePathCandidates) {
            ResourceLocation recipeTypeId = ResourceLocation.fromNamespaceAndPath(machineId.getNamespace(), candidatePath);
            RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
            if (recipeType != null) {
                hints.add(recipeTypeId);
            }
        }
        addVanillaFallbackHint(hints, machinePathCandidates, "furnace", "smelting");
        addVanillaFallbackHint(hints, machinePathCandidates, "smelter", "smelting");
        addVanillaFallbackHint(hints, machinePathCandidates, "smoking", "smoking");
        addVanillaFallbackHint(hints, machinePathCandidates, "smoker", "smoking");
        addVanillaFallbackHint(hints, machinePathCandidates, "blasting", "blasting");
        addVanillaFallbackHint(hints, machinePathCandidates, "blast_furnace", "blasting");
        return List.copyOf(hints);
    }

    private static List<RecipeTypeCandidate> toAutomaticCandidates(List<ResourceLocation> namingConventionHints) {
        if (namingConventionHints == null || namingConventionHints.isEmpty()) {
            return List.of();
        }
        Map<ResourceLocation, RecipeTypeCandidate> candidatesById = new LinkedHashMap<>();
        for (ResourceLocation recipeTypeId : namingConventionHints) {
            RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
            if (recipeType == null) {
                continue;
            }
            candidatesById.putIfAbsent(recipeTypeId, new RecipeTypeCandidate(
                    null,
                    recipeType,
                    MachineRecipeCandidateSource.GENERIC_RECIPE_TYPE,
                    20
            ));
        }
        return candidatesById.isEmpty() ? List.of() : List.copyOf(candidatesById.values());
    }

    private static void addTrimmedSuffixCandidate(List<String> candidates, String machinePath, String suffix) {
        if (machinePath.endsWith(suffix)) {
            addNamingCandidate(candidates, machinePath.substring(0, machinePath.length() - suffix.length()));
        }
    }

    private static void addSuffixCandidate(
            List<String> candidates,
            String machinePath,
            String singularPath,
            String recipePath
    ) {
        if (machinePath.endsWith(singularPath) || machinePath.endsWith(recipePath)) {
            addNamingCandidate(candidates, recipePath);
        }
    }

    private static void addNamingCandidate(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static void addVanillaFallbackHint(
            List<ResourceLocation> hints,
            List<String> machinePathCandidates,
            String machineSuffix,
            String recipePath
    ) {
        for (String machinePathCandidate : machinePathCandidates) {
            if (!machinePathCandidate.endsWith(machineSuffix) && !machinePathCandidate.endsWith(recipePath)) {
                continue;
            }
            ResourceLocation recipeTypeId = ResourceLocation.withDefaultNamespace(recipePath);
            RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
            if (recipeType != null && !hints.contains(recipeTypeId)) {
                hints.add(recipeTypeId);
            }
        }
    }

    public record Resolution(
            List<RecipeTypeCandidate> candidates,
            List<ResourceLocation> namingConventionHints,
            EnumSet<MachineRecipeKind> kinds,
            MachineRecipeCandidateSource source,
            MachineSupportStatus status,
            MachineSupportReasonCode reasonCode
    ) {
        private static Resolution supported(
                List<RecipeTypeCandidate> candidates,
                MachineRecipeCandidateSource source
        ) {
            return supported(candidates, List.of(), source);
        }

        private static Resolution supported(
                List<RecipeTypeCandidate> candidates,
                List<ResourceLocation> namingConventionHints,
                MachineRecipeCandidateSource source
        ) {
            EnumSet<MachineRecipeKind> kinds = EnumSet.noneOf(MachineRecipeKind.class);
            for (RecipeTypeCandidate candidate : candidates) {
                if (candidate.kind() != null) {
                    kinds.add(candidate.kind());
                }
            }
            return new Resolution(
                    List.copyOf(candidates),
                    namingConventionHints == null ? List.of() : List.copyOf(namingConventionHints),
                    kinds,
                    source,
                    supportedStatusForSource(source),
                    MachineSupportReasonCode.NONE
                );
        }

        private static Resolution unsupported(
                MachineSupportStatus status,
                MachineSupportReasonCode reasonCode
        ) {
            return new Resolution(
                    List.of(),
                    List.of(),
                    EnumSet.noneOf(MachineRecipeKind.class),
                    MachineRecipeCandidateSource.UNSUPPORTED,
                    status,
                    reasonCode
            );
        }

        public boolean hasCandidates() {
            return !candidates.isEmpty();
        }

        private static MachineSupportStatus supportedStatusForSource(MachineRecipeCandidateSource source) {
            if (source == MachineRecipeCandidateSource.EXPLICIT_ADAPTER) {
                return MachineSupportStatus.SUPPORTED_EXPLICIT;
            }
            if (source == MachineRecipeCandidateSource.CONFIG_MAPPING) {
                return MachineSupportStatus.SUPPORTED_CONFIG;
            }
            return MachineSupportStatus.SUPPORTED_GENERIC;
        }
    }
}
