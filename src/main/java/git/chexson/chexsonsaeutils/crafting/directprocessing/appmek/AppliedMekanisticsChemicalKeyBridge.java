package git.chexson.chexsonsaeutils.crafting.directprocessing.appmek;

import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.ChemicalStack;
import org.jetbrains.annotations.Nullable;

public final class AppliedMekanisticsChemicalKeyBridge {

    private static final AppliedMekanisticsChemicalKeyBridge INSTANCE = new AppliedMekanisticsChemicalKeyBridge();

    private AppliedMekanisticsChemicalKeyBridge() {
    }

    public static AppliedMekanisticsChemicalKeyBridge instance() {
        return INSTANCE;
    }

    public boolean isAvailable() {
        return true;
    }

    @Nullable
    public AEKey createKey(@Nullable ChemicalStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return MekanismKey.of(stack);
    }
}
