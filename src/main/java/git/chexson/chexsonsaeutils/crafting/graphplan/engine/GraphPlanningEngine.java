package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Entry facade for level-1 graph planning: session creation over a captured
 * network stock snapshot. Admission gating (grid/parallel-CPU/any-grid) lives
 * in {@code PlanningLevelRouter}; this class only owns engine identity and
 * session construction.
 */
public final class GraphPlanningEngine {

    public static final String ENGINE_ID = "chexsonsaeutils:graph_planning";

    private GraphPlanningEngine() {
    }

    public static GraphPlanningSession createSession(ICraftingService craftingService, KeyCounter networkStock,
            AEKey target, CalculationStrategy strategy, long budgetMillis, @Nullable Level level,
            boolean retentionEnabled, @Nullable appeng.api.storage.MEStorage storage,
            @Nullable IGrid planningGrid) {
        return GraphPlanningSession.create(craftingService, networkStock, target, strategy,
                new GraphPlanningBudget(budgetMillis), level, retentionEnabled,
                storage, planningGrid);
    }
}
