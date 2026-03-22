package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;

import java.util.function.Supplier;

public final class CraftingContinuationSubmitBridge {
    private static final ThreadLocal<CraftingContinuationMode> CURRENT_MODE =
            ThreadLocal.withInitial(CraftingContinuationMode::defaultMode);

    private CraftingContinuationSubmitBridge() {
    }

    public static CraftingContinuationMode getConfirmMode(CraftConfirmMenu menu) {
        return modeHost(menu).getContinuationMode();
    }

    public static void setConfirmMode(CraftConfirmMenu menu, CraftingContinuationMode mode) {
        modeHost(menu).requestContinuationMode(normalize(mode));
    }

    public static <T> T withContinuationMode(CraftingContinuationMode mode, Supplier<T> submitter) {
        CraftingContinuationMode previousMode = CURRENT_MODE.get();
        CURRENT_MODE.set(normalize(mode));
        try {
            return submitter.get();
        } finally {
            CURRENT_MODE.set(normalize(previousMode));
        }
    }

    public static CraftingContinuationMode currentMode() {
        return normalize(CURRENT_MODE.get());
    }

    public static boolean allowsSimulationStart(CraftConfirmMenu menu, CraftingPlanSummary plan) {
        return plan != null && plan.isSimulation() && getConfirmMode(menu).allowsSimulationStart();
    }

    private static CraftingContinuationMode normalize(CraftingContinuationMode mode) {
        return mode == null ? CraftingContinuationMode.defaultMode() : mode;
    }

    private static ContinuationModeHost modeHost(CraftConfirmMenu menu) {
        if (menu instanceof ContinuationModeHost host) {
            return host;
        }
        throw new IllegalStateException("CraftConfirmMenu is missing continuation mode access");
    }

    public interface ContinuationModeHost {
        CraftingContinuationMode getContinuationMode();

        void setContinuationMode(CraftingContinuationMode mode);

        void requestContinuationMode(CraftingContinuationMode mode);
    }
}
