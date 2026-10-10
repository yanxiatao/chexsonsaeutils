package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MachineRecipeIndex {

    private static final MachineRecipeIndex EMPTY = new MachineRecipeIndex(
            null,
            Set.of(),
            List.of(),
            Set.of(),
            MachineSupportStatus.UNSUPPORTED_UNREADABLE,
            MachineSupportReasonCode.PATTERN_DECODE_FAILED,
            0L
    );

    @Nullable
    private final MachineIdentity identity;
    private final Set<MachineRecipeKind> kinds;
    private final List<ResourceLocation> recipeTypeIds;
    private final Set<RecipeSignature> signatures;
    private final Map<RecipeSignatureKey, RecipeSignature> signatureLookup;
    private final Map<RecipeSignatureShapeKey, List<RecipeSignature>> signatureShapeLookup;
    private final Map<RecipeSignatureKey, MachineSupportStatus> signatureStatuses = new LinkedHashMap<>();
    private final MachineSupportStatus status;
    private final MachineSupportReasonCode reasonCode;
    private final long version;

    public MachineRecipeIndex(
            @Nullable MachineIdentity identity,
            Set<MachineRecipeKind> kinds,
            List<ResourceLocation> recipeTypeIds,
            Set<RecipeSignature> signatures,
            MachineSupportStatus status,
            MachineSupportReasonCode reasonCode,
            long version
    ) {
        this.identity = identity;
        this.kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        this.recipeTypeIds = recipeTypeIds == null ? List.of() : List.copyOf(recipeTypeIds);
        this.signatures = new LinkedHashSet<>(signatures == null ? Set.of() : signatures);
        this.signatureLookup = buildSignatureLookup(this.signatures);
        this.signatureShapeLookup = buildSignatureShapeLookup(this.signatures);
        this.status = status == null ? MachineSupportStatus.UNSUPPORTED_UNREADABLE : status;
        this.reasonCode = reasonCode == null ? MachineSupportReasonCode.MALFORMED_DATA : reasonCode;
        this.version = version;
    }

    public static MachineRecipeIndex empty() {
        return EMPTY;
    }

    /** 合并独立机器的支持范围，空索引不影响其他机器的有效配方。 */
    public static MachineRecipeIndex merge(List<MachineRecipeIndex> indices, long version) {
        if (indices.isEmpty()) return empty();
        Set<MachineRecipeKind> kinds = new LinkedHashSet<>();
        Set<ResourceLocation> types = new LinkedHashSet<>();
        Set<RecipeSignature> signatures = new LinkedHashSet<>();
        MachineRecipeIndex first = indices.getFirst();
        MachineSupportStatus status = MachineSupportStatus.SUPPORTED_GENERIC;
        for (MachineRecipeIndex index : indices) {
            if (index.isEmpty()) continue;
            kinds.addAll(index.kinds);
            types.addAll(index.recipeTypeIds);
            signatures.addAll(index.signatures);
        }
        if (signatures.isEmpty()) return first.withVersion(version);
        MachineRecipeIndex merged = new MachineRecipeIndex(first.identity, kinds, List.copyOf(types), signatures,
                status, MachineSupportReasonCode.NONE, version);
        for (MachineRecipeIndex index : indices) {
            if (index.isEmpty()) continue;
            for (RecipeSignature signature : index.signatures) {
                merged.signatureStatuses.merge(RecipeSignatureKey.of(signature), index.statusFor(signature),
                        (left, right) -> left.ordinal() <= right.ordinal() ? left : right);
            }
        }
        return merged;
    }

    /** 构建阶段记录每条签名的来源，已知原生配方优先于导入配方。 */
    MachineRecipeIndex withDiscoveryStatuses(Map<RecipeSignatureKey, MachineSupportStatus> nativeStatuses,
                                            Set<RecipeSignature> imported) {
        signatureStatuses.putAll(nativeStatuses);
        for (RecipeSignature signature : imported) {
            signatureStatuses.putIfAbsent(RecipeSignatureKey.of(signature), MachineSupportStatus.SUPPORTED_CONFIG);
        }
        return this;
    }

    /** 返回匹配配方的实际发现来源，用于混合机器的样板状态。 */
    public MachineSupportStatus statusFor(RecipeSignature signature) {
        return signatureStatuses.getOrDefault(RecipeSignatureKey.of(signature), status);
    }

    @Nullable
    public MachineIdentity identity() {
        return identity;
    }

    public long version() {
        return version;
    }

    public int recipeSignatureCount() {
        return signatures.size();
    }

    public MachineRecipeIndex withVersion(long newVersion) {
        MachineRecipeIndex copy = new MachineRecipeIndex(identity, kinds, recipeTypeIds, signatures, status, reasonCode, newVersion);
        copy.signatureStatuses.putAll(signatureStatuses);
        return copy;
    }

    public boolean isEmpty() {
        return identity == null || status == MachineSupportStatus.NEEDS_CONFIG_MAPPING || signatures.isEmpty();
    }

    public MachineSupportStatus status() {
        return status;
    }

    public MachineSupportReasonCode reasonCode() {
        return reasonCode;
    }

    @Nullable
    public RecipeSignature findSignature(AEKey input, long inputAmount, AEKey output, long outputAmount) {
        RecipeSignatureKey key = RecipeSignatureKey.of(input, inputAmount, output, outputAmount);
        return key == null ? null : signatureLookup.get(key);
    }

    @Nullable
    public RecipeSignature findSignature(
            java.util.List<RecipeSignatureInput> inputs,
            List<GenericStack> outputs
    ) {
        RecipeSignatureKey key = RecipeSignatureKey.of(inputs, outputs);
        return key == null ? null : signatureLookup.get(key);
    }

    @Nullable
    public ScaledSignatureMatch findScaledMatch(
            List<RecipeSignatureInput> inputs,
            List<GenericStack> outputs
    ) {
        RecipeSignature exactMatch = findSignature(inputs, outputs);
        if (exactMatch != null) {
            return new ScaledSignatureMatch(exactMatch, 1);
        }
        RecipeSignatureShapeKey shapeKey = RecipeSignatureShapeKey.of(inputs, outputs);
        if (shapeKey == null) {
            return null;
        }
        List<RecipeSignature> candidates = signatureShapeLookup.get(shapeKey);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Map<AEKey, Long> normalizedOutputs = DirectProcessingStackSupport.stackAmounts(outputs);
        for (RecipeSignature candidate : candidates) {
            Long inputRatio = DirectProcessingStackSupport.deriveExecutionCount(
                    inputs,
                    candidate.inputs()
            );
            if (inputRatio == null || inputRatio <= 0L) {
                continue;
            }
            List<GenericStack> scaledOutputs = DirectProcessingStackSupport.scaleStacks(
                    candidate.outputs(),
                    inputRatio
            );
            if (!normalizedOutputs.equals(DirectProcessingStackSupport.stackAmounts(scaledOutputs))) {
                continue;
            }
            return new ScaledSignatureMatch(candidate, inputRatio);
        }
        return null;
    }

    /** 只遍历索引中的实际配方，不展开替换候选的笛卡尔积。 */
    public List<RecipeSignature> matchingSignatures(appeng.api.crafting.IPatternDetails pattern) {
        if (pattern == null) return List.of();
        var deterministic = DirectProcessingStackSupport.patternInputs(pattern);
        if (!deterministic.isEmpty()) {
            var match = findScaledMatch(deterministic, pattern.getOutputs());
            return match == null ? List.of() : List.of(match.signature());
        }
        var matches = new java.util.ArrayList<RecipeSignature>();
        for (var signature : signatures) {
            if (!DirectProcessingStackSupport.patternInputs(pattern, signature).isEmpty()) matches.add(signature);
        }
        return List.copyOf(matches);
    }

    public boolean supports(AEKey input, long inputAmount, AEKey output, long outputAmount) {
        return findSignature(input, inputAmount, output, outputAmount) != null;
    }

    public Set<MachineRecipeKind> kinds() {
        return kinds;
    }

    public List<ResourceLocation> recipeTypeIds() {
        return recipeTypeIds;
    }

    public String keyTypeSummary() {
        boolean item = false;
        boolean fluid = false;
        boolean other = false;
        for (RecipeSignature signature : signatures) {
            for (RecipeSignatureInput input : signature.inputs()) {
                item |= input.input() instanceof AEItemKey;
                fluid |= input.input() instanceof AEFluidKey;
                other |= !(input.input() instanceof AEItemKey) && !(input.input() instanceof AEFluidKey);
            }
            for (GenericStack output : signature.outputs()) {
                item |= output.what() instanceof AEItemKey;
                fluid |= output.what() instanceof AEFluidKey;
                other |= !(output.what() instanceof AEItemKey) && !(output.what() instanceof AEFluidKey);
            }
        }
        int activeKinds = (item ? 1 : 0) + (fluid ? 1 : 0) + (other ? 1 : 0);
        if (activeKinds <= 1 && item) {
            return "Item";
        }
        if (activeKinds <= 1 && fluid) {
            return "Fluid";
        }
        if (activeKinds <= 1 && other) {
            return "Other";
        }
        if (activeKinds == 0) {
            return "-";
        }
        return "Mixed";
    }

    private static Map<RecipeSignatureKey, RecipeSignature> buildSignatureLookup(Set<RecipeSignature> signatures) {
        Map<RecipeSignatureKey, RecipeSignature> lookup = new LinkedHashMap<>();
        for (RecipeSignature signature : signatures) {
            RecipeSignatureKey key = RecipeSignatureKey.of(signature);
            if (key != null) {
                lookup.putIfAbsent(key, signature);
            }
        }
        return Map.copyOf(lookup);
    }

    private static Map<RecipeSignatureShapeKey, List<RecipeSignature>> buildSignatureShapeLookup(
            Set<RecipeSignature> signatures
    ) {
        Map<RecipeSignatureShapeKey, List<RecipeSignature>> lookup = new LinkedHashMap<>();
        for (RecipeSignature signature : signatures) {
            RecipeSignatureShapeKey key = RecipeSignatureShapeKey.of(signature);
            if (key == null) {
                continue;
            }
            lookup.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(signature);
        }
        Map<RecipeSignatureShapeKey, List<RecipeSignature>> copied = new LinkedHashMap<>();
        for (Map.Entry<RecipeSignatureShapeKey, List<RecipeSignature>> entry : lookup.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copied);
    }
}
