package git.chexson.chexsonsaeutils.crafting.status;

/**
 * Client-synced planning provenance (wall-clock millis and solving level)
 * attached to a {@code CraftingPlanSummary}.
 */
public interface EnhancedCraftingPlanSummary {

    long chexsonsaeutils$planningMillis();

    String chexsonsaeutils$solver();

    void chexsonsaeutils$setProvenance(long millis, String solver);
}
