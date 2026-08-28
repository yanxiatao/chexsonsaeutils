package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;

import java.util.ArrayDeque;

/**
 * Thread-local pool of {@link ChildCraftingSimulationState} instances for the fast
 * crafting-plan path.
 *
 * <p>AE2's planning creates a fresh child simulation state for every speculative
 * branch probe (and throws it away after {@code applyDiff}). On large multi-branch
 * plans that is a lot of short-lived allocation and GC churn. This pool reuses the
 * instances: each recycled state is fully reset (see {@link FastSimStateReset}) and
 * re-pointed at the new parent, which is observationally identical to constructing a
 * new one, so the produced plan is unchanged.
 *
 * <p>Safety:
 * <ul>
 *   <li>The pool is a LIFO stack, so nested probes always receive distinct
 *       instances — no aliasing between an outer probe's state and an inner one.</li>
 *   <li>The pool is bounded per thread to keep memory in check.</li>
 *   <li>If the reset/re-point mixins are somehow unavailable, {@link #acquire} falls
 *       back to allocating a fresh state, so planning never crashes because of the
 *       pool.</li>
 * </ul>
 */
public final class FastSimStatePool {

    private static final int MAX_PER_THREAD = 64;

    private static final ThreadLocal<ArrayDeque<ChildCraftingSimulationState>> POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private FastSimStatePool() {
    }

    public static ChildCraftingSimulationState acquire(ICraftingInventory parent) {
        try {
            ArrayDeque<ChildCraftingSimulationState> stack = POOL.get();
            ChildCraftingSimulationState state = stack.pollFirst();
            if (state == null) {
                return new ChildCraftingSimulationState(parent);
            }
            ((FastSimStateReset) (Object) state).chexsonsaeutils$resetForFastReuse();
            ((FastSimStateParentAccess) (Object) state).chexsonsaeutils$setFastParent(parent);
            return state;
        } catch (RuntimeException failure) {
            // Never let pooling break crafting; behave exactly like the un-pooled path.
            return new ChildCraftingSimulationState(parent);
        }
    }

    public static void release(ChildCraftingSimulationState state) {
        if (state == null) {
            return;
        }
        try {
            ArrayDeque<ChildCraftingSimulationState> stack = POOL.get();
            if (stack.size() < MAX_PER_THREAD) {
                stack.addFirst(state);
            }
        } catch (RuntimeException ignored) {
            // Dropping the instance is always safe.
        }
    }
}
