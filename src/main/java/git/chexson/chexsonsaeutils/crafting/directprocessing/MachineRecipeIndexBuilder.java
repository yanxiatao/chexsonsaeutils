package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MachineRecipeIndexBuilder {

    private final MachineAdapterRegistry adapterRegistry;
    private final RecipeTypeCandidateResolver candidateResolver;
    private final GenericRecipeShapeReader shapeReader;

    public MachineRecipeIndexBuilder() {
        this(MachineAdapterRegistry.directProcessingDefaults(), new GenericRecipeShapeReader(), true);
    }

    public static MachineRecipeIndexBuilder fromConfig() {
        return new MachineRecipeIndexBuilder(
                MachineAdapterRegistry.directProcessingDefaults(),
                new GenericRecipeShapeReader(),
                genericDiscoveryEnabled()
        );
    }

    public MachineRecipeIndexBuilder(
            MachineAdapterRegistry adapterRegistry,
            GenericRecipeShapeReader shapeReader
    ) {
        this(adapterRegistry, shapeReader, true);
    }

    public MachineRecipeIndexBuilder(
            MachineAdapterRegistry adapterRegistry,
            GenericRecipeShapeReader shapeReader,
            boolean genericDiscoveryEnabled
    ) {
        this.adapterRegistry = adapterRegistry == null ? MachineAdapterRegistry.empty() : adapterRegistry;
        this.candidateResolver = new RecipeTypeCandidateResolver(
                this.adapterRegistry,
                MachineRecipeConfigMappingRegistry.instance(),
                genericDiscoveryEnabled
        );
        this.shapeReader = shapeReader == null ? new GenericRecipeShapeReader() : shapeReader;
    }

    public MachineRecipeIndex buildIndex(Level level, MachineIdentity identity, long version) {
        return buildIndexTemplate(level, identity).withVersion(version);
    }

    public MachineRecipeIndex buildIndexTemplate(Level level, MachineIdentity identity) {
        if (level == null || identity == null) {
            return MachineRecipeIndex.empty();
        }
        RecipeTypeCandidateResolver.Resolution resolution = candidateResolver.resolve(identity);
        MachineRecipeUserConfigStore.LoadedImportedSignatures imported =
                MachineRecipeUserConfigStore.instance().loadImportedSignatures(
                        level.registryAccess(),
                        identity,
                        List.of()
                );
        if (!resolution.hasCandidates()) {
            return buildImportedOrUnsupportedIndex(
                    identity,
                    resolution.kinds(),
                    resolution.status(),
                    resolution.reasonCode(),
                    imported
            );
        }
        MachineRecipeIndex resolvedIndex = buildSupportedIndex(
                level,
                identity,
                resolution.candidates(),
                resolution.kinds(),
                resolution.status(),
                resolution.reasonCode(),
                imported
        );
        if (!resolvedIndex.isEmpty()) {
            return resolvedIndex;
        }
        if (resolution.source() == MachineRecipeCandidateSource.EXPLICIT_ADAPTER) {
            MachineRecipeIndex fallbackIndex = tryResolveNamingConventionCandidates(
                    level,
                    identity,
                    candidateResolver.resolveFallbackHints(identity),
                    imported
            );
            if (!fallbackIndex.isEmpty()) {
                return fallbackIndex;
            }
        }
        return resolvedIndex;
    }

    public List<ResourceLocation> validateRecipeTypeIds(
            Level level,
            List<ResourceLocation> recipeTypeIds,
            int defaultTicks
    ) {
        if (level == null || recipeTypeIds == null || recipeTypeIds.isEmpty()) {
            return List.of();
        }
        Map<ResourceLocation, RecipeTypeCandidate> candidates = new LinkedHashMap<>();
        for (ResourceLocation recipeTypeId : recipeTypeIds) {
            if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(recipeTypeId)) {
                continue;
            }
            RecipeType<?> recipeType = recipeTypeId == null ? null : BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
            if (recipeType == null) {
                continue;
            }
            candidates.putIfAbsent(recipeTypeId, new RecipeTypeCandidate(
                    null,
                    recipeType,
                    MachineRecipeCandidateSource.CONFIG_MAPPING,
                    defaultTicks
            ));
        }
        CandidateScanResult result = collectSupportedCandidates(level, candidates.values());
        return result.recipeTypeIds();
    }

    @Nullable
    public MachineRecipeConfigImportRequest validateImportRequest(
            Level level,
            MachineIdentity identity,
            MachineRecipeConfigImportRequest request
    ) {
        if (level == null || identity == null || request == null) {
            return null;
        }
        List<MachineRecipeImportedSignature> importedSignatures = validateImportedSignatures(
                level.registryAccess(),
                request.signatureHintsJson(),
                request.recipeTypeIds()
        );
        Set<ResourceLocation> mergedRecipeTypeIds = new LinkedHashSet<>(
                validateRecipeTypeIds(level, request.recipeTypeIds(), request.defaultTicks())
        );
        mergedRecipeTypeIds.addAll(MachineRecipeImportedSignature.collectRecipeTypeIds(importedSignatures));
        if (mergedRecipeTypeIds.isEmpty()) {
            return null;
        }
        return new MachineRecipeConfigImportRequest(
                identity.machineItemId(),
                identity.blockId(),
                List.copyOf(mergedRecipeTypeIds),
                request.defaultTicks(),
                request.ioMode(),
                request.keyTypes(),
                request.enabled(),
                MachineRecipeImportedSignature.toJson(level.registryAccess(), importedSignatures)
        );
    }

    private MachineRecipeIndex buildSupportedIndex(
            Level level,
            MachineIdentity identity,
            List<RecipeTypeCandidate> candidates,
            EnumSet<MachineRecipeKind> kinds,
            MachineSupportStatus supportedStatus,
            MachineSupportReasonCode fallbackReason,
            MachineRecipeUserConfigStore.LoadedImportedSignatures imported
    ) {
        CandidateScanResult result = collectSupportedCandidates(level, identity, candidates);
        Set<RecipeSignature> mergedSignatures = mergeSignatures(result.signatures(), imported.signatures());
        List<ResourceLocation> supportedRecipeTypeIds = mergeRecipeTypeIds(
                result.recipeTypeIds(),
                imported.recipeTypeIds()
        );
        if (!mergedSignatures.isEmpty()) {
            return new MachineRecipeIndex(
                    identity,
                    kinds,
                    supportedRecipeTypeIds,
                    mergedSignatures,
                    supportedStatus(result, imported, supportedStatus),
                    MachineSupportReasonCode.NONE,
                    0L
            );
        }
        MachineSupportStatus status = MachineSupportStatus.NEEDS_CONFIG_MAPPING;
        MachineSupportReasonCode reason = result.hadUnreadableCandidates()
                ? MachineSupportReasonCode.IDENTIFIED_RECIPE_TYPE_UNREADABLE
                : fallbackReason;
        if (reason == MachineSupportReasonCode.NONE) {
            reason = MachineSupportReasonCode.MAPPING_MISSING;
        }
        return new MachineRecipeIndex(
                identity,
                kinds,
                List.of(),
                Set.of(),
                status,
                reason,
                0L
        );
    }

    private CandidateScanResult collectSupportedCandidates(
            Level level,
            Iterable<RecipeTypeCandidate> candidates
    ) {
        return collectSupportedCandidates(level, null, candidates);
    }

    private CandidateScanResult collectSupportedCandidates(
            Level level,
            MachineIdentity identity,
            Iterable<RecipeTypeCandidate> candidates
    ) {
        Map<ResourceLocation, RecipeTypeCandidate> matchedCandidates = new LinkedHashMap<>();
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        boolean hadUnreadableCandidates = false;
        if (candidates == null) {
            return new CandidateScanResult(List.of(), Set.of(), false);
        }
        for (RecipeTypeCandidate candidate : candidates) {
            if (candidate == null || candidate.recipeType() == null) {
                continue;
            }
            ResourceLocation recipeTypeId = BuiltInRegistries.RECIPE_TYPE.getKey(candidate.recipeType());
            if (recipeTypeId == null || matchedCandidates.containsKey(recipeTypeId)) {
                continue;
            }
            ShapeScanState shapeScanState = new ShapeScanState();
            Set<RecipeSignature> candidateSignatures = new LinkedHashSet<>();
            collectSignatures(level, identity, candidate, candidateSignatures, shapeScanState);
            hadUnreadableCandidates |= shapeScanState.unreadableShape;
            if (!candidateSignatures.isEmpty()) {
                matchedCandidates.put(recipeTypeId, candidate);
                signatures.addAll(candidateSignatures);
            }
        }
        return new CandidateScanResult(
                recipeTypeIds(new ArrayList<>(matchedCandidates.values())),
                signatures,
                hadUnreadableCandidates
        );
    }

    private MachineRecipeIndex tryResolveNamingConventionCandidates(
            Level level,
            MachineIdentity identity,
            List<ResourceLocation> candidateIds,
            MachineRecipeUserConfigStore.LoadedImportedSignatures imported
    ) {
        Map<ResourceLocation, RecipeTypeCandidate> candidates = new LinkedHashMap<>();
        for (ResourceLocation candidateId : candidateIds) {
            RecipeType<?> recipeType = candidateId == null ? null : BuiltInRegistries.RECIPE_TYPE.get(candidateId);
            if (recipeType == null) {
                continue;
            }
            candidates.putIfAbsent(candidateId, new RecipeTypeCandidate(
                    null,
                    recipeType,
                    MachineRecipeCandidateSource.GENERIC_RECIPE_TYPE,
                    20
            ));
        }
        CandidateScanResult result = collectSupportedCandidates(level, identity, candidates.values());
        Set<RecipeSignature> mergedSignatures = mergeSignatures(result.signatures(), imported.signatures());
        List<ResourceLocation> supportedRecipeTypeIds = mergeRecipeTypeIds(
                result.recipeTypeIds(),
                imported.recipeTypeIds()
        );
        if (!mergedSignatures.isEmpty()) {
            return new MachineRecipeIndex(
                    identity,
                    EnumSet.noneOf(MachineRecipeKind.class),
                    supportedRecipeTypeIds,
                    mergedSignatures,
                    supportedStatus(result, imported, MachineSupportStatus.SUPPORTED_GENERIC),
                    MachineSupportReasonCode.NONE,
                    0L
            );
        }
        MachineSupportStatus status = MachineSupportStatus.NEEDS_CONFIG_MAPPING;
        MachineSupportReasonCode reason = result.hadUnreadableCandidates()
                ? MachineSupportReasonCode.IDENTIFIED_RECIPE_TYPE_UNREADABLE
                : MachineSupportReasonCode.NAMING_CONVENTION_NEEDS_MAPPING;
        return new MachineRecipeIndex(
                identity,
                EnumSet.noneOf(MachineRecipeKind.class),
                List.of(),
                Set.of(),
                status,
                reason,
                0L
        );
    }

    public PatternCompatibility compileCompatibility(Level level, MachineRecipeIndex index, IPatternDetails pattern) {
        if (level == null || index == null || pattern == null) {
            return PatternCompatibility.unsupported(MachineSupportReasonCode.PATTERN_DECODE_FAILED);
        }
        if (index.isEmpty()) {
            return PatternCompatibility.unsupported(index.status(), index.reasonCode());
        }
        List<GenericStack> outputs = DirectProcessingStackSupport.normalizeStacks(pattern.getOutputs());
        if (outputs.isEmpty()) {
            return PatternCompatibility.unsupported(MachineSupportReasonCode.MULTIPLE_PATHS);
        }
        List<RecipeSignatureInput> signatureInputs = collectDeterministicInputs(pattern);
        if (signatureInputs.isEmpty()) {
            return PatternCompatibility.unsupported(MachineSupportReasonCode.SUBSTITUTION_INPUT);
        }
        ScaledSignatureMatch match = index.findScaledMatch(signatureInputs, outputs);
        RecipeSignature signature = match == null ? null : match.signature();
        if (signature != null) {
            return PatternCompatibility.supported(index.status(), pattern, signature);
        }
        return PatternCompatibility.unsupported(MachineSupportStatus.NEEDS_CONFIG_MAPPING, MachineSupportReasonCode.MAPPING_MISSING);
    }

    private static List<RecipeSignatureInput> collectDeterministicInputs(IPatternDetails pattern) {
        List<GenericStack> inputs = new ArrayList<>();
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs.length != 1 || possibleInputs[0] == null || possibleInputs[0].what() == null) {
                return List.of();
            }
            long amount = multiplyOrZero(
                    Math.max(1L, possibleInputs[0].amount()),
                    Math.max(1L, input.getMultiplier())
            );
            if (amount <= 0L) {
                return List.of();
            }
            inputs.add(new GenericStack(possibleInputs[0].what(), amount));
        }
        return DirectProcessingStackSupport.toSignatureInputs(inputs);
    }

    private static long multiplyOrZero(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return 0L;
        }
        return left * right;
    }

    private List<MachineRecipeImportedSignature> validateImportedSignatures(
            net.minecraft.core.HolderLookup.Provider registries,
            String signatureHintsJson,
            List<ResourceLocation> requestedRecipeTypeIds
    ) {
        List<MachineRecipeImportedSignature> parsedHints =
                MachineRecipeImportedSignature.parseJson(registries, signatureHintsJson);
        if (parsedHints.isEmpty()) {
            return List.of();
        }
        Set<ResourceLocation> requestedIds = requestedRecipeTypeIds == null || requestedRecipeTypeIds.isEmpty()
                ? Set.of()
                : Set.copyOf(requestedRecipeTypeIds);
        Set<MachineRecipeImportedSignature> acceptedHints = new LinkedHashSet<>();
        for (MachineRecipeImportedSignature hint : parsedHints) {
            if (hint == null || hint.recipeTypeId() == null) {
                continue;
            }
            if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(hint.recipeTypeId())) {
                continue;
            }
            if (!requestedIds.isEmpty() && !requestedIds.contains(hint.recipeTypeId())) {
                continue;
            }
            RecipeSignature signature = hint.toRecipeSignature();
            MachineRecipeImportedSignature canonical = MachineRecipeImportedSignature.fromRecipeSignature(
                    hint.recipeTypeId(),
                    signature
            );
            if (canonical != null) {
                acceptedHints.add(canonical);
            }
        }
        return acceptedHints.isEmpty() ? List.of() : List.copyOf(acceptedHints);
    }

    static MachineSupportStatus supportedStatus(
            CandidateScanResult result,
            MachineRecipeUserConfigStore.LoadedImportedSignatures imported,
            MachineSupportStatus fallbackStatus
    ) {
        if (result != null && !result.signatures().isEmpty()) {
            return fallbackStatus;
        }
        if (imported != null && !imported.signatures().isEmpty()) {
            return MachineSupportStatus.SUPPORTED_CONFIG;
        }
        return fallbackStatus;
    }

    static MachineRecipeIndex buildImportedOrUnsupportedIndex(
            @Nullable MachineIdentity identity,
            Set<MachineRecipeKind> kinds,
            MachineSupportStatus unsupportedStatus,
            MachineSupportReasonCode unsupportedReason,
            MachineRecipeUserConfigStore.LoadedImportedSignatures imported
    ) {
        if (identity == null) {
            return MachineRecipeIndex.empty();
        }
        if (imported != null && !imported.signatures().isEmpty()) {
            return new MachineRecipeIndex(
                    identity,
                    kinds,
                    imported.recipeTypeIds(),
                    imported.signatures(),
                    MachineSupportStatus.SUPPORTED_CONFIG,
                    MachineSupportReasonCode.NONE,
                    0L
            );
        }
        return new MachineRecipeIndex(
                identity,
                kinds,
                List.of(),
                Set.of(),
                unsupportedStatus,
                unsupportedReason,
                0L
        );
    }

    private static Set<RecipeSignature> mergeSignatures(
            Set<RecipeSignature> primary,
            Set<RecipeSignature> secondary
    ) {
        if ((primary == null || primary.isEmpty()) && (secondary == null || secondary.isEmpty())) {
            return Set.of();
        }
        Set<RecipeSignature> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return Set.copyOf(merged);
    }

    private static List<ResourceLocation> mergeRecipeTypeIds(
            List<ResourceLocation> primary,
            List<ResourceLocation> secondary
    ) {
        Set<ResourceLocation> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void collectSignatures(
            Level level,
            @org.jetbrains.annotations.Nullable MachineIdentity identity,
            RecipeTypeCandidate candidate,
            Set<RecipeSignature> signatures,
            ShapeScanState shapeScanState
    ) {
        RecipeType recipeType = candidate.recipeType();
        List<RecipeHolder> recipes = level.getRecipeManager().getAllRecipesFor(recipeType);
        for (RecipeHolder holder : recipes) {
            if (!(holder.value() instanceof Recipe recipe)) {
                continue;
            }
            try {
                GenericRecipeShapeReader.ShapeReadOutcome outcome =
                        shapeReader.readStaticItemRecipeOutcome(level, candidate, recipe, identity);
                signatures.addAll(outcome.signatures());
                if (outcome.signatures().isEmpty()) {
                    shapeScanState.unreadableShape = true;
                }
            } catch (RuntimeException ignored) {
                // Keep a single unreadable mod recipe from aborting this direct machine's local index rebuild.
                shapeScanState.unreadableShape = true;
            }
        }
    }

    private static final class ShapeScanState {
        private boolean unreadableShape;
    }

    static record CandidateScanResult(
            List<ResourceLocation> recipeTypeIds,
            Set<RecipeSignature> signatures,
            boolean hadUnreadableCandidates
    ) {
        CandidateScanResult {
            recipeTypeIds = recipeTypeIds == null ? List.of() : List.copyOf(recipeTypeIds);
            signatures = signatures == null ? Set.of() : Set.copyOf(signatures);
        }
    }

    private static List<ResourceLocation> recipeTypeIds(List<RecipeTypeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (RecipeTypeCandidate candidate : candidates) {
            ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(candidate.recipeType());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    private static List<ResourceLocation> recipeTypeIds(Iterable<RecipeTypeCandidate> candidates) {
        if (candidates == null) {
            return List.of();
        }
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (RecipeTypeCandidate candidate : candidates) {
            if (candidate == null || candidate.recipeType() == null) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(candidate.recipeType());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    private static boolean genericDiscoveryEnabled() {
        return ChexsonsaeutilsCompatibilityConfig
                .AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED
                .get();
    }

}
