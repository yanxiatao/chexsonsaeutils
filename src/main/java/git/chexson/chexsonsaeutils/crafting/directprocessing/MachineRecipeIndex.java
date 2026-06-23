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
        return new MachineRecipeIndex(identity, kinds, recipeTypeIds, signatures, status, reasonCode, newVersion);
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
        List<RecipeSignatureInput> normalizedInputs = DirectProcessingStackSupport.normalizeSignatureInputs(inputs);
        List<GenericStack> normalizedOutputs = DirectProcessingStackSupport.normalizeStacks(outputs);
        for (RecipeSignature candidate : candidates) {
            Integer inputRatio = DirectProcessingStackSupport.deriveExecutionCount(
                    normalizedInputs,
                    candidate.inputs()
            );
            if (inputRatio == null || inputRatio <= 0) {
                continue;
            }
            List<GenericStack> scaledOutputs = DirectProcessingStackSupport.scaleStacks(
                    candidate.outputs(),
                    inputRatio
            );
            if (!normalizedOutputs.equals(scaledOutputs)) {
                continue;
            }
            return new ScaledSignatureMatch(candidate, inputRatio);
        }
        return null;
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
