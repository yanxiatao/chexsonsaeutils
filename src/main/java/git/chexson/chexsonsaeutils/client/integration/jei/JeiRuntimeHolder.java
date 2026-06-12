package git.chexson.chexsonsaeutils.client.integration.jei;

import net.minecraft.resources.ResourceLocation;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;

public final class JeiRuntimeHolder {

    private static final JeiMachineRecipeTypeHintBridge HINT_BRIDGE = new JeiMachineRecipeTypeHintBridge();
    private static final JeiRecipeVisibilityBridge VISIBILITY_BRIDGE = new JeiRecipeVisibilityBridge();
    private static final JeiRecipeSignatureHintBridge SIGNATURE_HINT_BRIDGE = new JeiRecipeSignatureHintBridge();
    @Nullable
    private static volatile IJeiRuntime runtime;

    private JeiRuntimeHolder() {
    }

    public static void setRuntime(@Nullable IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static void clearRuntime() {
        runtime = null;
    }

    public static boolean hasRuntime() {
        return runtime != null;
    }

    public static List<JeiMachineRecipeTypeHint> collectHintsForMachine(
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId
    ) {
        return HINT_BRIDGE.collectHintsForMachine(runtime, machineItemId, machineBlockId);
    }

    public static List<JeiMachineRecipeTypeHint> collectVisibleHintsForMachine(
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId
    ) {
        return VISIBILITY_BRIDGE.collectVisibleHintsForMachine(
                runtime,
                machineItemId,
                machineBlockId,
                HINT_BRIDGE
        );
    }

    public static List<MachineRecipeImportedSignature> collectSignatureHintsForMachine(
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId
    ) {
        return SIGNATURE_HINT_BRIDGE.collectSignatureHintsForMachine(
                runtime,
                machineItemId,
                machineBlockId,
                HINT_BRIDGE
        );
    }
}
