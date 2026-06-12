package git.chexson.chexsonsaeutils.crafting.directprocessing;

import java.util.concurrent.atomic.AtomicLong;

public final class MachineRecipeReloadTracker {

    private static final AtomicLong RECIPE_RELOAD_EPOCH = new AtomicLong();

    private MachineRecipeReloadTracker() {
    }

    public static long recipeReloadEpoch() {
        return RECIPE_RELOAD_EPOCH.get();
    }

    public static void markRecipeReloaded() {
        RECIPE_RELOAD_EPOCH.incrementAndGet();
        MachineRecipeIndexCache.instance().clear();
    }
}
