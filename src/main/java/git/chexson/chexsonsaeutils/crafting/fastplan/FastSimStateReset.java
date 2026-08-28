package git.chexson.chexsonsaeutils.crafting.fastplan;

/**
 * Duck interface mixed into {@code appeng.crafting.inv.CraftingSimulationState}.
 *
 * <p>Allows the fast planning path to reset a simulation state's accumulators so
 * the instance can be reused from a pool instead of being re-allocated. The reset
 * restores the state to exactly what a freshly constructed instance exposes, so a
 * reused state is observationally identical to a new one.
 */
public interface FastSimStateReset {
    void chexsonsaeutils$resetForFastReuse();
}
