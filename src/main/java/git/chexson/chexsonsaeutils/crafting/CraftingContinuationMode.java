package git.chexson.chexsonsaeutils.crafting;

public enum CraftingContinuationMode {
    DEFAULT,
    IGNORE_MISSING;

    public static CraftingContinuationMode defaultMode() {
        return DEFAULT;
    }

    public boolean allowsSimulationStart() {
        return this == IGNORE_MISSING;
    }
}
